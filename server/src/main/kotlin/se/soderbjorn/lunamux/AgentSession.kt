/**
 * PTY-less virtual session backing the `agent` pane kind.
 *
 * [AgentSession] implements the same [TermSession] surface a PTY-backed
 * [TerminalSession] does, so everything downstream — the `/pty/{id}`
 * WebSocket bridge, scrollback persistence, the MCP read tools — works
 * unchanged. The differences:
 *
 *  - There is no process. Output is produced by an MCP client through the
 *    console tools (`console_write`, `console_write_raw`, `screen_draw` +
 *    `screen_present`), all of which funnel into [emitOutput]: the bytes
 *    feed the headless [ScreenEmulator], land in the replay ring, and
 *    broadcast to attached clients exactly like PTY output.
 *  - [write] — client keystrokes arriving over `/pty/{id}` — is
 *    *redirected*: in screen mode the bytes are parsed into discrete key
 *    tokens drained by `console_poll_input` / `console_read_key`; in
 *    transcript mode a cooked line discipline (echo, backspace, CR→line)
 *    turns them into submitted lines for `console_read` / `console_ask`.
 *  - A structured transcript (list of [AgentTranscriptItem]) runs in
 *    parallel with the byte mirror, so the web client can render
 *    transcript mode as a real conversation UI over the `/agent/{id}`
 *    socket while mobile clients read the same content through their
 *    terminal views.
 *
 * Agent sessions are ephemeral: [shutdown] (driven by the owning MCP
 * session's close, or the pane being closed) is final — there is no
 * reattach.
 *
 * @see TermSession
 * @see AgentContent
 * @see se.soderbjorn.lunamux.mcp.registerMcpConsoleTools
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * A discrete input event drained by the screen-mode input tools.
 *
 * @property type `"key"` or `"resize"`.
 * @property key the key token for key events (`"a"`, `"enter"`, `"up"`,
 *   `"ctrl+c"`, `"alt+x"`, …), null for resizes.
 * @property cols/rows the new grid size for resize events, null for keys.
 */
data class AgentInputEvent(
    val type: String,
    val key: String? = null,
    val cols: Int? = null,
    val rows: Int? = null,
)

/**
 * See the file-level doc. Create via the constructor and register with
 * [TerminalSessions.registerAgent]; the returned session id is what pane
 * configs and MCP tools address.
 *
 * @param renderMode `"transcript"` or `"screen"` (see [AgentContent]).
 * @param initialCols initial grid width for the headless emulator.
 * @param initialRows initial grid height.
 * @param fixedGrid when true (screen-mode consoles), the agent-requested
 *   grid is authoritative: client viewport size votes are ignored instead
 *   of shrinking the grid, so a game board keeps its dimensions on every
 *   device (clients render the out-of-bounds area like an undersized PTY).
 */
class AgentSession(
    val renderMode: String,
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val fixedGrid: Boolean = false,
) : TermSession {

    private val _output = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 256)
    override val output = _output.asSharedFlow()

    // Owns the forwarder coroutine that relays the ordered event channel to
    // subscribers; cancelled in [shutdown].
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Ordered outbound stream (see SessionEvent) ──────────────────────────
    // Same discipline as TerminalSession: output/size are stamped with a monotonic
    // seq under [outboundLock] so [attachPayload] is exact against the live stream.
    // No grid/reflow here — agent output is authored at one width, so a resize emits
    // only a Size event (no synthesized resync redraw).
    private val outboundLock = Any()
    private var eventSeq = 0L
    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 256)
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
    private val forwarderJob = scope.launch { for (ev in eventChannel) _events.emit(ev) }

    override fun attachPayload(): AttachPayload = synchronized(outboundLock) {
        val (c, r) = _sizeEvents.value
        AttachPayload(eventSeq, c, r, ringSnapshot())
    }

    /** Enqueue a Size event so attached `/pty` clients re-fit to the new grid. */
    private fun emitSizeEvent(cols: Int, rows: Int) = synchronized(outboundLock) {
        eventChannel.trySend(SessionEvent.Size(++eventSeq, cols, rows))
    }

    // No shell → no cwd / program title, but the members must exist.
    private val _cwd = MutableStateFlow<String?>(null)
    override val cwd: StateFlow<String?> = _cwd.asStateFlow()
    private val _programTitle = MutableStateFlow<String?>(null)
    override val programTitle: StateFlow<String?> = _programTitle.asStateFlow()

    private val screen = ScreenEmulator(initialCols, initialRows)

    @Volatile
    private var closed = false

    // ── Replay ring (mirror of TerminalSession's, smaller) ──────────────
    private val ringCapacity = 64 * 1024
    private val ring = ByteArray(ringCapacity)
    private var ringSize = 0
    private var ringStart = 0
    private val ringLock = Any()

    @Volatile
    private var bytesWritten: Long = 0

    override fun bytesWritten(): Long = bytesWritten

    // ── Transcript (structured, for the /agent socket) ──────────────────
    private val transcript = mutableListOf<AgentTranscriptItem>()
    private val itemIds = AtomicLong(0)

    private val _transcriptEvents = MutableSharedFlow<AgentServerMessage>(extraBufferCapacity = 64)

    /**
     * Structured transcript events for `/agent/{id}` socket subscribers:
     * [AgentServerMessage.Append] / [AgentServerMessage.Awaiting] /
     * [AgentServerMessage.Closed]. New subscribers should first send
     * [transcriptSnapshot].
     */
    val transcriptEvents: SharedFlow<AgentServerMessage> = _transcriptEvents.asSharedFlow()

    /** Number of currently blocked console_read/console_ask waiters. */
    private val awaiting = AtomicInteger(0)

    /**
     * Full transcript snapshot for a freshly connected `/agent` socket.
     */
    fun transcriptSnapshot(): AgentServerMessage.Snapshot = synchronized(transcript) {
        AgentServerMessage.Snapshot(items = transcript.toList(), awaitingInput = awaiting.get() > 0)
    }

    /** Append a structured transcript item and broadcast it. */
    private fun appendTranscript(role: String, text: String): AgentTranscriptItem {
        val item = AgentTranscriptItem(id = itemIds.incrementAndGet(), role = role, text = text)
        synchronized(transcript) { transcript.add(item) }
        _transcriptEvents.tryEmit(AgentServerMessage.Append(item))
        return item
    }

    // ── Input channels ───────────────────────────────────────────────────
    /** Submitted lines awaiting a console_read / console_ask. */
    private val lines = Channel<String>(capacity = 64)

    /** Discrete key/resize events awaiting poll_input / read_key. */
    private val inputEvents = Channel<AgentInputEvent>(capacity = 512)

    // ── Output path (the MCP client "prints") ────────────────────────────

    /**
     * Emit [bytes] as session output: feed the emulator, stamp the replay
     * ring, and broadcast to attached clients. All console/screen tools
     * funnel through here.
     */
    fun emitOutput(bytes: ByteArray) {
        if (bytes.isEmpty() || closed) return
        screen.feed(bytes, bytes.size)
        synchronized(outboundLock) {
            appendToRing(bytes)
            eventChannel.trySend(SessionEvent.Output(++eventSeq, bytes))
        }
        _output.tryEmit(bytes)
    }

    /**
     * Post agent text to the transcript: appends a structured item AND
     * mirrors it to the byte stream (CRLF line endings) so terminal-view
     * clients see the same content.
     *
     * @param role `"output"` or `"prompt"`.
     * @param text the text (LF-normalized; may be multi-line).
     */
    fun postTranscript(role: String, text: String) {
        appendTranscript(role, text)
        val mirrored = text.replace("\r\n", "\n").replace("\n", "\r\n")
        val bytes = if (role == "prompt") {
            // Bold + a trailing space so a terminal viewer sees an inline prompt.
            "\u001B[1m$mirrored\u001B[0m ".toByteArray(Charsets.UTF_8)
        } else {
            "$mirrored\r\n".toByteArray(Charsets.UTF_8)
        }
        emitOutput(bytes)
    }

    // ── Screen-mode staged drawing (double buffer) ───────────────────────
    private val staged = StringBuilder()
    private val stagedLock = Any()

    /**
     * Stage ANSI/VT [data] to be flushed atomically by [present]. Used by
     * `screen_draw` so a frame appears at once instead of op-by-op.
     */
    fun stage(data: String) = synchronized(stagedLock) {
        staged.append(data)
    }

    /**
     * Flush everything staged since the last present as one output burst.
     *
     * @return the number of bytes flushed.
     */
    fun present(): Int {
        val burst = synchronized(stagedLock) {
            val s = staged.toString()
            staged.setLength(0)
            s
        }
        if (burst.isEmpty()) return 0
        val bytes = burst.toByteArray(Charsets.UTF_8)
        emitOutput(bytes)
        return bytes.size
    }

    // ── Input path (client keystrokes via /pty, or /agent submits) ──────

    /**
     * Submit a full input line (from the web transcript UI's input box via
     * the `/agent` socket, or from the cooked line discipline below).
     * Appends an `input` transcript item, mirrors it to the byte stream
     * when it didn't originate from terminal typing (which already echoed),
     * and delivers it to a blocked `console_read` / `console_ask`.
     *
     * @param text the submitted line (no trailing newline).
     * @param echoBytes mirror the line into the byte stream — true for
     *   `/agent`-socket submissions, false for cooked-mode lines whose
     *   characters were already echoed while typing.
     */
    fun submitLine(text: String, echoBytes: Boolean) {
        appendTranscript("input", text)
        if (echoBytes) {
            emitOutput("\u001B[36m❯ $text\u001B[0m\r\n".toByteArray(Charsets.UTF_8))
        }
        lines.trySend(text)
    }

    /**
     * Await one submitted line (used by `console_read` / `console_ask`).
     * Broadcasts awaiting-state flips so transcript UIs can enable their
     * input affordance.
     *
     * @param timeoutMs how long to wait.
     * @return the line, or null on timeout / shutdown.
     */
    suspend fun readLine(timeoutMs: Long): String? {
        if (awaiting.incrementAndGet() == 1) {
            _transcriptEvents.tryEmit(AgentServerMessage.Awaiting(true))
        }
        try {
            return withTimeoutOrNull(timeoutMs) {
                runCatching { lines.receive() }.getOrNull()
            }
        } finally {
            if (awaiting.decrementAndGet() == 0) {
                _transcriptEvents.tryEmit(AgentServerMessage.Awaiting(false))
            }
        }
    }

    /**
     * Drain up to [max] pending input events without blocking (used by
     * `console_poll_input`).
     */
    fun pollEvents(max: Int): List<AgentInputEvent> {
        val out = mutableListOf<AgentInputEvent>()
        while (out.size < max) {
            val e = inputEvents.tryReceive().getOrNull() ?: break
            out.add(e)
        }
        return out
    }

    /**
     * Await one input event (used by `console_read_key`).
     *
     * @param timeoutMs how long to wait.
     * @return the event, or null on timeout / shutdown.
     */
    suspend fun awaitEvent(timeoutMs: Long): AgentInputEvent? =
        withTimeoutOrNull(timeoutMs) {
            runCatching { inputEvents.receive() }.getOrNull()
        }

    // Cooked-mode line buffer (transcript mode typed input).
    private val lineBuf = StringBuilder()

    /**
     * Client keystrokes from `/pty/{id}`. Screen mode: parse into key
     * tokens for the event channel. Transcript mode: cooked line
     * discipline — echo printable chars, handle backspace, deliver the
     * line on Enter.
     */
    override fun write(bytes: ByteArray) {
        if (closed) return
        if (renderMode == "screen") {
            for (token in parseKeyTokens(bytes)) {
                inputEvents.trySend(AgentInputEvent(type = "key", key = token))
            }
            return
        }
        // Cooked mode.
        val text = bytes.toString(Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\r' || ch == '\n' -> {
                    emitOutput("\r\n".toByteArray(Charsets.US_ASCII))
                    val line = synchronized(lineBuf) {
                        val s = lineBuf.toString()
                        lineBuf.setLength(0)
                        s
                    }
                    submitLine(line, echoBytes = false)
                    // Swallow a LF directly following a CR.
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                ch == '\u007F' || ch == '\b' -> {
                    val removed = synchronized(lineBuf) {
                        if (lineBuf.isNotEmpty()) {
                            lineBuf.setLength(lineBuf.length - 1); true
                        } else false
                    }
                    if (removed) emitOutput("\b \b".toByteArray(Charsets.US_ASCII))
                }
                ch == '\u0003' -> { // Ctrl-C clears the pending line.
                    synchronized(lineBuf) { lineBuf.setLength(0) }
                    emitOutput("^C\r\n".toByteArray(Charsets.US_ASCII))
                }
                ch == '\u001B' -> {
                    // Discard escape sequences (arrows etc.) in cooked mode.
                    i += escapeSequenceLength(text, i) - 1
                }
                ch.code >= 0x20 -> {
                    synchronized(lineBuf) { lineBuf.append(ch) }
                    emitOutput(ch.toString().toByteArray(Charsets.UTF_8))
                }
            }
            i++
        }
    }

    // ── TermSession plumbing (sizes, snapshot, lifecycle) ────────────────

    private val clientSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val _sizeEvents = MutableStateFlow(Pair(initialCols, initialRows))
    override val sizeEvents: StateFlow<Pair<Int, Int>> = _sizeEvents.asStateFlow()

    // Agent consoles have no 3D-grid override, so the [priority] tier is
    // ignored here — the min()-across-clients aggregation (or a fixed
    // agent-requested grid) is authoritative.
    override fun setClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
        if (fixedGrid) return // agent-requested grid is authoritative
        clientSizes[clientId] = Pair(max(1, cols), max(1, rows))
        applyEffectiveSize()
    }

    override fun forceClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
        if (fixedGrid) return // agent-requested grid is authoritative
        clientSizes.clear()
        clientSizes[clientId] = Pair(max(1, cols), max(1, rows))
        applyEffectiveSize()
    }

    override fun removeClient(clientId: String) {
        if (clientSizes.remove(clientId) != null) applyEffectiveSize()
    }

    private fun applyEffectiveSize() {
        val sizes = clientSizes.values
        if (sizes.isEmpty()) return
        val c = sizes.minOf { it.first }
        val r = sizes.minOf { it.second }
        if (Pair(c, r) == _sizeEvents.value) return
        screen.resize(c, r)
        _sizeEvents.value = Pair(c, r)
        inputEvents.trySend(AgentInputEvent(type = "resize", cols = c, rows = r))
        emitSizeEvent(c, r)
    }

    /**
     * Force the emulator grid to an agent-requested size (`screen_config`)
     * regardless of client votes; clients see the new authoritative size
     * via [sizeEvents] (the `/pty` bridge pushes a Size message).
     */
    fun configureGrid(cols: Int, rows: Int) {
        clientSizes.clear()
        screen.resize(cols, rows)
        _sizeEvents.value = Pair(cols, rows)
        emitSizeEvent(cols, rows)
    }

    override fun detectState(): SessionState? = null

    override fun screenText(): String = screen.snapshotVisibleText()

    override fun isProcessAlive(): Boolean = !closed

    override fun resetTerminalModes() {
        emitOutput("\u001B[0m\u001B[?25h".toByteArray(Charsets.US_ASCII))
    }

    /** A copy of the agent's replay ring (its recent output bytes). */
    private fun ringSnapshot(): ByteArray = synchronized(ringLock) {
        if (ringSize == 0) return@synchronized ByteArray(0)
        val out = ByteArray(ringSize)
        if (ringStart + ringSize <= ringCapacity) {
            System.arraycopy(ring, ringStart, out, 0, ringSize)
        } else {
            val tail = ringCapacity - ringStart
            System.arraycopy(ring, ringStart, out, 0, tail)
            System.arraycopy(ring, 0, out, tail, ringSize - tail)
        }
        out
    }

    // Agent output is authored at one width and never reflows, so persistence and
    // read_scrollback just hand back the ring (persist bytes verbatim; read_scrollback
    // strips ANSI). No grid/synthesized redraw as for a PTY session.
    override fun persistSnapshot(): ByteArray = ringSnapshot()

    override fun transcriptText(): String = ringSnapshot().toString(Charsets.UTF_8)

    override fun shutdown() {
        if (closed) return
        closed = true
        _transcriptEvents.tryEmit(AgentServerMessage.Closed)
        lines.close()
        inputEvents.close()
        scope.cancel()
    }

    private fun appendToRing(chunk: ByteArray) = synchronized(ringLock) {
        for (b in chunk) {
            val writeIdx = (ringStart + ringSize) % ringCapacity
            ring[writeIdx] = b
            if (ringSize < ringCapacity) ringSize++ else ringStart = (ringStart + 1) % ringCapacity
        }
        bytesWritten += chunk.size
    }

    companion object {

        /**
         * Parse raw client input bytes into discrete key tokens (the
         * inverse of `McpWriteTools.encodeKeyToken`). Handles CSI arrows /
         * nav keys, SS3 F-keys, control characters, alt-chords, and plain
         * printable characters. A bare trailing ESC is reported as
         * `"escape"` (terminals send complete sequences per write, so a
         * split sequence is vanishingly rare and at worst degrades one
         * keypress).
         *
         * @param bytes the raw input from the client socket.
         * @return the key tokens, in order.
         */
        internal fun parseKeyTokens(bytes: ByteArray): List<String> {
            val text = bytes.toString(Charsets.UTF_8)
            val out = mutableListOf<String>()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    ch == '\u001B' -> {
                        val parsed = parseEscape(text, i)
                        out.add(parsed.first)
                        i += parsed.second
                        continue
                    }
                    ch == '\r' || ch == '\n' -> out.add("enter")
                    ch == '\t' -> out.add("tab")
                    ch == '\u007F' || ch == '\b' -> out.add("backspace")
                    ch == ' ' -> out.add("space")
                    ch.code in 1..26 -> out.add("ctrl+" + ('a' + (ch.code - 1)))
                    ch.code >= 0x20 -> out.add(ch.toString())
                }
                i++
            }
            return out
        }

        /** CSI final byte → token for the common navigation keys. */
        private val CSI_FINAL = mapOf(
            'A' to "up", 'B' to "down", 'C' to "right", 'D' to "left",
            'H' to "home", 'F' to "end",
        )

        /** CSI `<n>~` parameter → token. */
        private val CSI_TILDE = mapOf(
            "1" to "home", "2" to "insert", "3" to "delete", "4" to "end",
            "5" to "pageup", "6" to "pagedown",
            "15" to "f5", "17" to "f6", "18" to "f7", "19" to "f8",
            "20" to "f9", "21" to "f10", "23" to "f11", "24" to "f12",
        )

        /** SS3 final byte → token (F1–F4 in application mode). */
        private val SS3_FINAL = mapOf('P' to "f1", 'Q' to "f2", 'R' to "f3", 'S' to "f4")

        /**
         * Parse one escape sequence starting at [start] (which is ESC).
         *
         * @return (token, consumed-char-count).
         */
        private fun parseEscape(text: String, start: Int): Pair<String, Int> {
            if (start + 1 >= text.length) return "escape" to 1
            return when (val next = text[start + 1]) {
                '[' -> {
                    var i = start + 2
                    val params = StringBuilder()
                    while (i < text.length && (text[i].isDigit() || text[i] == ';')) {
                        params.append(text[i]); i++
                    }
                    if (i >= text.length) return "escape" to 1
                    val final = text[i]
                    val consumed = i - start + 1
                    when {
                        final == '~' -> (CSI_TILDE[params.toString().substringBefore(';')]
                            ?: "escape") to consumed
                        CSI_FINAL.containsKey(final) -> CSI_FINAL[final]!! to consumed
                        else -> "escape" to consumed // unknown CSI — swallow it
                    }
                }
                'O' -> {
                    if (start + 2 >= text.length) return "escape" to 2
                    val final = text[start + 2]
                    (SS3_FINAL[final] ?: CSI_FINAL[final] ?: "escape") to 3
                }
                else -> {
                    // ESC + printable = alt chord; ESC + control = bare escape.
                    if (next.code >= 0x20) "alt+$next" to 2 else "escape" to 1
                }
            }
        }

        /**
         * Length (in chars, including the ESC) of the escape sequence at
         * [start], used by cooked mode to skip sequences it discards.
         */
        internal fun escapeSequenceLength(text: String, start: Int): Int =
            parseEscape(text, start).second
    }
}
