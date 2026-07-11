/**
 * In-process simulation of one PTY session for demo mode: a scrollback
 * "ring buffer", a live output flow, and a minimal line discipline that
 * echoes keystrokes and answers entered commands from a canned table.
 *
 * Mirrors the real server's `TerminalSession` shape closely enough that the
 * demo transports can speak the same protocol: a client attaching to the
 * session first receives the whole scrollback as one snapshot frame, then
 * live bytes as they are produced.
 *
 * @see DemoServer
 * @see DemoPtySocket
 * @see DemoSessionSpec
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One simulated terminal session.
 *
 * Input enters through [inputText]/[input] (from the demo transports), is
 * processed by a single consumer coroutine (so multi-byte pastes and rapid
 * typing keep their order), and produces output on [output]: first the
 * accumulated scrollback as one snapshot frame, then live frames.
 *
 * The line discipline is deliberately simple but realistic:
 *  - printable characters echo and accumulate in a line buffer;
 *  - backspace erases (`\b \b`), Enter runs the line through the spec's
 *    canned responder and prints a fresh prompt;
 *  - Ctrl-C prints `^C` and re-prompts (also "stopping" sessions that start
 *    inside a foreground program, see [DemoSessionSpec.startsAtPrompt]);
 *  - Ctrl-L and `clear` clear the screen;
 *  - CSI/SS3 escape sequences from arrow keys etc. are swallowed.
 *
 * @param spec the canned content and responder for this session.
 * @param scope long-lived coroutine scope (the client's) for the input loop.
 */
class DemoTerminalSession internal constructor(
    private val spec: DemoSessionSpec,
    private val scope: CoroutineScope,
) {
    /** The fixture session id, mirroring [DemoSessionSpec.sessionId]. */
    val sessionId: String get() = spec.sessionId

    /**
     * Simulated-agent activity callback: invoked with `true` when a script
     * run starts (the "Claude" in this session begins working) and `false`
     * when it finishes or is interrupted. Wired by [DemoServer] at session
     * creation to flip the session's `working` state in the published state
     * map — the demo counterpart of the real server's Claude state detector.
     */
    internal var onAgentActivity: ((Boolean) -> Unit)? = null

    /** Maximum scrollback retained, mirroring the server's 64 KB ring. */
    private val maxScrollbackChars = 64_000

    /** Accumulated scrollback; guarded by [lock]. */
    private val scrollback = StringBuilder(spec.transcript)

    /** Live output frames emitted after the snapshot. */
    private val live = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    /** Guards [scrollback] against concurrent snapshot/append. */
    private val lock = Mutex()

    /** Current (un-entered) input line. Touched only by the input loop. */
    private val lineBuffer = StringBuilder()

    /**
     * Whether the session is sitting at a shell prompt. Sessions that start
     * "inside" a foreground program (log tails, watchers) swallow keystrokes
     * until Ctrl-C drops them to the prompt — just like a real terminal
     * running a non-echoing program.
     */
    private var atPrompt = spec.startsAtPrompt

    /** Pending input chunks, consumed in order by a single coroutine. */
    private val inputChannel = Channel<String>(Channel.UNLIMITED)

    /**
     * Escape-sequence swallow state: 0 = none, 1 = saw ESC, 2 = inside a
     * CSI/SS3 sequence (consume until the final byte).
     */
    private var escState = 0

    /**
     * The single input-consumer coroutine, kept so [close] can cancel it.
     * Launched on the client's long-lived scope, so without an explicit
     * cancel it would outlive the session: every reaped session would
     * leave a coroutine parked on [inputChannel] forever, and a long
     * demo visit (panes created and closed repeatedly) would accumulate
     * dead loops that pin their sessions in memory.
     */
    private val inputJob = scope.launch {
        for (chunk in inputChannel) process(chunk)
    }

    /**
     * The currently-running script player ([DemoSessionSpec.liveScript]
     * loop or one [DemoSessionSpec.inputScript] burst), or an inactive/null
     * job when the simulated agent is idle. While active, keystrokes are
     * swallowed and Ctrl-C interrupts the run.
     */
    private var scriptJob: Job? = null

    /** Number of input-script bursts played, for canned-variant rotation. */
    private var inputRunCount = 0

    init {
        // Sessions with a live feed start "working" immediately — their
        // fixture state is already seeded as working, so no activity
        // callback is needed (or wired) this early.
        spec.liveScript?.let { startScript(it, loop = true) }
    }

    /**
     * Permanently shut the session down: stop accepting input and cancel
     * the consumer and script coroutines so the session can be
     * garbage-collected.
     *
     * Called by [DemoServer.reapOrphanSessions] when the last pane
     * referencing this session closes — the demo equivalent of the real
     * server killing the PTY process.
     */
    fun close() {
        inputChannel.close()
        inputJob.cancel()
        scriptJob?.cancel()
    }

    /**
     * Start playing [steps] on the session's scope: each step waits its
     * delay, then its text is written to the scrollback and the live flow.
     * Reports the run to [onAgentActivity] so [DemoServer] can flip the
     * session's `working` state on and off.
     *
     * @param steps the timed output steps to play.
     * @param loop `true` to repeat the steps forever (a session that is
     *   permanently mid-task), `false` to play once and go idle again.
     */
    private fun startScript(steps: List<DemoScriptStep>, loop: Boolean) {
        onAgentActivity?.invoke(true)
        scriptJob = scope.launch {
            do {
                for (step in steps) {
                    delay(step.delayMs)
                    write(step.text)
                }
            } while (loop)
            onAgentActivity?.invoke(false)
        }
    }

    /**
     * Ctrl-C while a script is playing: cancel the run, report the agent
     * as idle, and drop to the spec's prompt — the demo analogue of
     * interrupting Claude and landing back in the shell (looping feeds) or
     * at the Claude input prompt (input bursts).
     */
    private suspend fun interruptScript() {
        scriptJob?.cancel()
        scriptJob = null
        atPrompt = true
        onAgentActivity?.invoke(false)
        write("^C\r\n${spec.prompt}")
    }

    /**
     * Reset the session to its fixture starting point **in place** — same
     * object, so transports that are already attached keep streaming: any
     * running script is cancelled, the scrollback is rewound to the spec's
     * canned starting transcript, the line discipline is cleared, and a
     * live-feed session restarts its looping script from the top.
     *
     * Attached clients receive one "clear everything + starting transcript"
     * frame, so their terminals visually rewind; clients attaching later get
     * the fresh scrollback as their snapshot, as always.
     *
     * Called by [DemoServer.resetToFixtures] before the demo tour plays.
     */
    suspend fun restart() {
        scriptJob?.cancel()
        scriptJob = null
        lineBuffer.clear()
        escState = 0
        inputRunCount = 0
        atPrompt = spec.startsAtPrompt
        lock.withLock {
            scrollback.clear()
            scrollback.append(spec.transcript)
        }
        // 3J erases the emulator's scrollback, 2J the screen, H homes the
        // cursor — so the attached terminal rewinds to a blank slate before
        // the canned transcript replays on top of it. The sequence is NOT
        // appended to [scrollback]: a later subscriber starts from the clean
        // transcript and has nothing to erase.
        live.emit(("\u001b[3J\u001b[2J\u001b[H" + spec.transcript).encodeToByteArray())
        spec.liveScript?.let { startScript(it, loop = true) }
    }

    /**
     * The session's output as a cold-start flow: the current scrollback as
     * one snapshot frame first, then live frames. The snapshot is taken
     * under the same lock every append holds, so a subscriber never misses
     * or duplicates bytes across the snapshot/live boundary.
     *
     * @return a flow of output byte frames for one attaching client.
     */
    fun output(): Flow<ByteArray> = live.onSubscription {
        val snapshot = lock.withLock { scrollback.toString() }
        if (snapshot.isNotEmpty()) emit(snapshot.encodeToByteArray())
    }

    /**
     * Feed raw input bytes (keystrokes/paste) into the session. Used by
     * [DemoPtySocket.send].
     *
     * @param bytes UTF-8 input bytes.
     */
    fun input(bytes: ByteArray) {
        inputChannel.trySend(bytes.decodeToString())
    }

    /**
     * Feed input text into the session. Used by the web client, whose
     * xterm.js `onData` callback delivers strings.
     *
     * @param text the typed/pasted text (may include control characters).
     */
    fun inputText(text: String) {
        inputChannel.trySend(text)
    }

    /** Append [s] to the scrollback (with cap) and emit it as a live frame. */
    private suspend fun write(s: String) {
        if (s.isEmpty()) return
        lock.withLock {
            scrollback.append(s)
            if (scrollback.length > maxScrollbackChars) {
                scrollback.deleteRange(0, scrollback.length - maxScrollbackChars)
            }
        }
        live.emit(s.encodeToByteArray())
    }

    /** Process one input chunk character by character. */
    private suspend fun process(chunk: String) {
        for (ch in chunk) processChar(ch)
    }

    /** Handle a single input character through the line discipline. */
    private suspend fun processChar(ch: Char) {
        // Swallow escape sequences (arrow keys, etc.) regardless of mode.
        when (escState) {
            1 -> {
                escState = if (ch == '[' || ch == 'O') 2 else 0
                return
            }
            2 -> {
                if (ch.code in 0x40..0x7e) escState = 0
                return
            }
        }
        if (ch == '\u001b') {
            escState = 1
            return
        }

        // A running script (live feed or input burst) owns the terminal:
        // swallow keystrokes — echoing them would garble the in-place
        // status-line rewrites — except Ctrl-C, which interrupts the agent.
        if (scriptJob?.isActive == true) {
            if (ch == '\u0003') interruptScript()
            return
        }

        if (!atPrompt) {
            // A foreground program owns the terminal: only Ctrl-C does
            // anything (stops it and drops to the shell prompt).
            if (ch == '\u0003') {
                atPrompt = true
                write("^C\r\n${spec.prompt}")
            }
            return
        }

        when (ch) {
            '\r', '\n' -> {
                val line = lineBuffer.toString()
                lineBuffer.clear()
                // Erase from the caret to the end of the screen before the reply,
                // then newline. For the Claude box prompt ([DEMO_CLAUDE_PROMPT]) the
                // caret sits *above* its lower rule + `auto mode on` status line, so
                // this wipes that chrome so it doesn't linger under the submitted
                // line. For the single-line shell prompt the caret is already at the
                // bottom, so the erase is a no-op.
                write("\u001b[0J\r\n")
                val inputScript = spec.inputScript
                when {
                    // Simulated-agent sessions: any non-blank input sends
                    // "Claude" back to work for one scripted burst (the
                    // burst's last step re-prints the prompt).
                    inputScript != null && line.isNotBlank() ->
                        startScript(inputScript(line, inputRunCount++), loop = false)
                    inputScript != null -> write(spec.prompt)
                    else -> {
                        val response = if (line.trim() == "clear") {
                            "\u001b[2J\u001b[H"
                        } else {
                            spec.respond(line)
                        }
                        write(response + spec.prompt)
                    }
                }
            }
            '\u0003' -> { // Ctrl-C: abandon the current line.
                lineBuffer.clear()
                write("^C\r\n${spec.prompt}")
            }
            '\u000c' -> { // Ctrl-L: clear screen, re-print prompt + line.
                write("\u001b[2J\u001b[H${spec.prompt}$lineBuffer")
            }
            '\u007f', '\b' -> {
                if (lineBuffer.isNotEmpty()) {
                    lineBuffer.deleteAt(lineBuffer.length - 1)
                    write("\b \b")
                }
            }
            '\t' -> Unit // No completion in the demo.
            else -> {
                if (ch.code >= 0x20) {
                    lineBuffer.append(ch)
                    write(ch.toString())
                }
            }
        }
    }
}
