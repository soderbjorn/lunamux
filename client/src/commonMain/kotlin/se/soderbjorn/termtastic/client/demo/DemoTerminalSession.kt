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
package se.soderbjorn.termtastic.client.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
    scope: CoroutineScope,
) {
    /** The fixture session id, mirroring [DemoSessionSpec.sessionId]. */
    val sessionId: String get() = spec.sessionId

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
     * Permanently shut the session down: stop accepting input and cancel
     * the consumer coroutine so the session can be garbage-collected.
     *
     * Called by [DemoServer.reapOrphanSessions] when the last pane
     * referencing this session closes — the demo equivalent of the real
     * server killing the PTY process.
     */
    fun close() {
        inputChannel.close()
        inputJob.cancel()
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
                write("\r\n")
                val response = if (line.trim() == "clear") {
                    "\u001b[2J\u001b[H"
                } else {
                    spec.respond(line)
                }
                write(response + spec.prompt)
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
