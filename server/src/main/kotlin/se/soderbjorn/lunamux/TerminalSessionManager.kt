/**
 * PTY-session lifecycle and registry.
 *
 * [TerminalSessions] is the process-wide registry of [TerminalSession]s.
 * Sessions are created on demand by [WindowState], identified by short
 * `s<n>` ids, and torn down when the last referencing pane closes.
 *
 * [TerminalSession] is a single PTY-backed session: it owns the
 * `PtyProcess`, replays a 128 kB ring buffer to reconnecting clients, runs
 * a headless [ScreenEmulator] in parallel for AI-state detection, and
 * negotiates a per-PTY winsize via latest-active-client arbitration
 * (tmux `window-size latest` semantics — see
 * [se.soderbjorn.lunamux.pty.ClientSizeArbiter]), falling back to the
 * tiered "highest tier wins, min() within it" aggregation when no client
 * activity has been observed.
 *
 * @see WindowState
 * @see ScreenEmulator
 * @see OscScanner
 * @see ProcessCwdReader
 */
package se.soderbjorn.lunamux

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.pty.ClientSizeArbiter
import se.soderbjorn.lunamux.pty.OscScanner
import se.soderbjorn.lunamux.pty.ProcessCwdReader
import se.soderbjorn.lunamux.pty.ReplaySanitizer
import se.soderbjorn.lunamux.pty.ShellInitFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * The session surface shared by PTY-backed [TerminalSession]s and PTY-less
 * agent-console sessions (`AgentSession` in `mcp/AgentSession.kt`). It is
 * exactly the contract the rest of the server programs against:
 *
 *  - `/pty/{id}` bridges [output] / [snapshot] / [write] /
 *    [setClientSize] / [forceClientSize] / [removeClient] /
 *    [resetTerminalModes] / [sizeEvents],
 *  - the scrollback saver uses [bytesWritten] + [snapshot],
 *  - the state poller uses [detectState],
 *  - the MCP read tools use [screenText] / [isProcessAlive] / [cwd] /
 *    [programTitle],
 *  - [TerminalSessions.destroy] uses [shutdown].
 *
 * For an agent session, [write] carries the *user's* keystrokes from
 * attached clients (routed into the agent's input channel) — symmetric
 * with a PTY where write() is also "input from the user".
 *
 * @see TerminalSession
 * @see TerminalSessions
 */
/** One client's size vote: its grid plus the [SizePriority] tier it competes in. */
internal data class SizeVote(val cols: Int, val rows: Int, val priority: SizePriority)

/**
 * Reduce a set of client size votes to the effective PTY grid: **highest tier
 * wins, `min()` within it**. Pick the greatest [SizePriority] present, then the
 * smallest cols/rows among the votes at that tier, so a higher tier fully
 * overrides lower ones instead of being clamped by them (see [SizeVote] /
 * [SizePriority]). An all-[SizePriority.NORMAL] set reduces to the classic
 * "smallest attached viewport wins".
 *
 * Pure and side-effect free so the tiering is unit-testable in isolation;
 * [se.soderbjorn.lunamux.pty.ClientSizeArbiter] calls it for the 3D-tier
 * override and as the no-activity fallback (see its class kdoc).
 *
 * @param votes the live per-client votes (may be empty).
 * @return the effective `(cols, rows)`, or `null` when there are no votes.
 */
internal fun pickEffectiveSize(votes: Collection<SizeVote>): Pair<Int, Int>? {
    if (votes.isEmpty()) return null
    val topTier = votes.maxOf { it.priority }
    val top = votes.filter { it.priority == topTier }
    return top.minOf { it.cols } to top.minOf { it.rows }
}

interface TermSession {
    /** Broadcast stream of output bytes for attached clients. */
    val output: kotlinx.coroutines.flow.SharedFlow<ByteArray>

    /** Last observed working directory (null for PTY-less sessions). */
    val cwd: StateFlow<String?>

    /** Last program-set title (null for PTY-less sessions). */
    val programTitle: StateFlow<String?>

    /** Effective (cols, rows) grid, updated as clients (dis)connect/resize. */
    val sizeEvents: StateFlow<Pair<Int, Int>>

    /** Total output bytes produced (drives incremental scrollback saves). */
    fun bytesWritten(): Long

    /** Deliver user input bytes into the session. */
    fun write(bytes: ByteArray)

    /**
     * Record that [clientId] just delivered user input; drives the
     * latest-active-client size arbitration (typing on a client makes it the
     * size governor — see [se.soderbjorn.lunamux.pty.ClientSizeArbiter]).
     *
     * Called by the `/pty` route for every inbound binary frame, *alongside*
     * [write] rather than inside it: [write] is also used by MCP tools and
     * agent plumbing that carry no client identity and must not perturb
     * sizing. Default no-op so PTY-less sessions (`AgentSession`) ignore it.
     *
     * @param clientId the per-connection client id that sent the input.
     */
    fun noteClientInput(clientId: String) {}

    /** Broadcast mode-reset sequences to attached clients (see issue #91). */
    fun resetTerminalModes()

    /** Tear the session down and release all resources. */
    fun shutdown()

    /**
     * Register [clientId]'s viewport size vote at the given [priority] tier.
     * The vote feeds the latest-active-client arbitration (see
     * [se.soderbjorn.lunamux.pty.ClientSizeArbiter]): it applies immediately
     * when [clientId] is the governing client (or when it is a
     * [SizePriority.MOBILE] vote, which claims governance — a phone must be
     * readable the moment it attaches); otherwise it is stored and takes
     * effect if the client later becomes the governor. With no recorded
     * activity the classic tiered min() aggregation decides.
     */
    fun setClientSize(
        clientId: String,
        cols: Int,
        rows: Int,
        priority: SizePriority = SizePriority.NORMAL,
    )

    /**
     * Force the grid to [clientId]'s size, evicting other clients' votes and
     * making it the governing client — an explicit user action (Reformat)
     * always wins. Other clients re-vote on their next automatic refit, but
     * ambient re-votes no longer steal the size back (see
     * [se.soderbjorn.lunamux.pty.ClientSizeArbiter]).
     */
    fun forceClientSize(
        clientId: String,
        cols: Int,
        rows: Int,
        priority: SizePriority = SizePriority.NORMAL,
    )

    /** Drop [clientId]'s size vote when its socket disconnects. */
    fun removeClient(clientId: String)

    /** Detect an AI-assistant state from the rendered screen, if any. */
    fun detectState(): SessionState?

    /** Recent output for reconnect replay. */
    fun snapshot(): ByteArray

    /** The currently rendered viewport as plain text. */
    fun screenText(): String

    /** Whether the backing process (or virtual session) is still live. */
    fun isProcessAlive(): Boolean
}

/**
 * Registry of process-wide sessions ([TermSession]s — PTY-backed and
 * agent). Each PTY session created via [create] also gets a watcher
 * coroutine that listens for cwd changes coming out of [TerminalSession.cwd]
 * and forwards them (debounced) into [WindowState.updatePaneCwd], and — while
 * the opt-in [programTitlesEnabled] flag is on — does the same for program-set
 * titles ([TerminalSession.programTitle] → [WindowState.applyProgramTitle]).
 * The 750 ms debounce coalesces `cd` / title bursts before they ever touch
 * the WindowConfig flow; the existing 2 s persistence debouncer in `main()`
 * then coalesces *those* updates into one SQLite write.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
object TerminalSessions {
    private val sessions = ConcurrentHashMap<String, TermSession>()
    private val watchJobs = ConcurrentHashMap<String, Job>()
    private val idCounter = AtomicLong(0)
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-database nonce suffixed onto newly minted session ids
     * (`s<n>-<nonce>`), matching the tab/pane id scheme so ids from
     * different server databases never collide anywhere clients key on
     * them. Assigned by [WindowState.initialize] before the first session
     * is created; empty (legacy unsuffixed ids) until then, which keeps
     * unit tests and pre-init paths working. User-facing session numbering
     * strips the suffix via [displayNumber].
     *
     * @see WindowState.initialize
     * @see displayNumber
     */
    @Volatile
    var idNonce: String = ""

    /**
     * The user-facing numeric part of a session id: `"s7-x4k9"` and the
     * legacy `"s7"` both yield `"7"`. Used wherever a default pane title
     * (`"Session 7"`) is derived from a session id, so the nonce suffix
     * never leaks into the UI.
     *
     * @param sessionId a session id as minted by [create].
     * @return the counter portion of the id, as a string.
     */
    fun displayNumber(sessionId: String): String =
        sessionId.removePrefix("s").substringBefore('-')

    private val stateOverrides = ConcurrentHashMap<String, String?>()

    private val lastNonNullState = ConcurrentHashMap<String, String>()
    private val nullStreak = ConcurrentHashMap<String, Int>()
    private const val STATE_GRACE_POLLS = 3  // 3 polls × 3 s = 9 s grace

    /**
     * Live opt-in flag for the "use program-set terminal titles" feature (the
     * [TERMINAL_PROGRAM_TITLE_KEY] UI setting). `main()`'s settings collector
     * writes toggle flips into this — deliberately a stable [MutableStateFlow]
     * that is mutated, never replaced: sessions (and their title watchers)
     * are created during `WindowState.initialize`, *before* `main()` finishes
     * wiring, and a watcher bound to a swapped-out flow instance would miss
     * every later flip. Each watcher re-collects on an off→on flip, so a pane
     * picks up the program's *current* title immediately — no restart, and no
     * waiting for the next title change; `main()` additionally sweeps stored
     * titles on an on→off flip.
     */
    val programTitlesEnabled = MutableStateFlow(false)

    /**
     * Create a fresh session and return its newly minted id (nonce-suffixed
     * once [idNonce] is assigned — see its kdoc).
     *
     * @param initialCwd starting working directory, or null for the home dir.
     * @param initialScrollback a persisted scrollback blob to replay to
     *   attaching clients, or null for a clean session.
     * @param initialCols grid columns the scrollback was captured at (from
     *   the persisted record); null falls back to the session default.
     * @param initialRows grid rows the scrollback was captured at; null falls
     *   back to the session default.
     */
    fun create(
        initialCwd: String? = null,
        initialScrollback: ByteArray? = null,
        initialCols: Int? = null,
        initialRows: Int? = null,
    ): String {
        val n = idCounter.incrementAndGet()
        val id = if (idNonce.isEmpty()) "s$n" else "s$n-$idNonce"
        val session = TerminalSession.create(initialCwd, initialScrollback, initialCols, initialRows)
        sessions[id] = session
        watchJobs[id] = watchScope.launch {
            launch {
                session.cwd
                    .filterNotNull()
                    .debounce(750.milliseconds)
                    .distinctUntilChanged()
                    .collect { newCwd -> WindowState.updatePaneCwd(id, newCwd) }
            }
            // Program-set titles (OSC 0/2), debounced like cwd changes. The
            // opt-in gate is the *outer* flow: while off nothing is collected
            // (free), and on enable flatMapLatest re-collects the title
            // StateFlow, which replays the current title so the pane is named
            // right away. An empty title flows through so a program clearing
            // its title falls the pane back to the cwd-based name.
            launch {
                programTitlesEnabled
                    .flatMapLatest { enabled ->
                        if (enabled) session.programTitle.filterNotNull().debounce(750.milliseconds)
                        else emptyFlow()
                    }
                    .collect { newTitle -> WindowState.applyProgramTitle(id, newTitle) }
            }
        }
        return id
    }

    /** Look up a live session by its id. */
    fun get(id: String): TermSession? = sessions[id]

    /**
     * Register a PTY-less agent-console session (see `AgentSession`) under
     * a freshly minted session id, so it is addressable everywhere a PTY
     * session is — `/pty/{id}`, the MCP tools, scrollback, state polling.
     * No cwd/title watcher is installed (agent sessions have neither).
     *
     * @param session the agent session to register.
     * @return the newly minted session id.
     */
    fun registerAgent(session: TermSession): String {
        val n = idCounter.incrementAndGet()
        val id = if (idNonce.isEmpty()) "s$n" else "s$n-$idNonce"
        sessions[id] = session
        return id
    }

    /**
     * Snapshot of every live session as `(id, session)` pairs, ordered by
     * the numeric portion of the id so listings are stable. The backing map
     * is private; this is the read accessor the MCP `list_sessions` /
     * `get_session` tools use to enumerate sessions (cross-referencing
     * window/tab ids by walking [WindowState.config] separately).
     *
     * @return live sessions at the time of the call; sessions created or
     *   destroyed afterwards are not reflected.
     */
    fun list(): List<Pair<String, TermSession>> =
        sessions.entries
            .sortedBy { it.key.removePrefix("s").substringBefore('-').toLongOrNull() ?: Long.MAX_VALUE }
            .map { it.key to it.value }

    /**
     * Override the detected state for a session.
     *
     * @param mode one of `"working"`, `"waiting"`, `"idle"` (forces idle),
     *             or `"auto"` (clears the override, resuming auto-detection).
     */
    fun setStateOverride(sessionId: String, mode: String) {
        when (mode) {
            "auto" -> stateOverrides.remove(sessionId)
            "idle" -> stateOverrides[sessionId] = null
            else -> stateOverrides[sessionId] = mode
        }
    }

    /** Resolve the current state for every live session. */
    fun resolveStates(): Map<String, String?> {
        val result = HashMap<String, String?>()
        for ((id, session) in sessions) {
            if (stateOverrides.containsKey(id)) {
                result[id] = stateOverrides[id]
                continue
            }
            val detected = session.detectState()?.state
            if (detected != null) {
                lastNonNullState[id] = detected
                nullStreak.remove(id)
                result[id] = detected
            } else {
                val prev = lastNonNullState[id]
                if (prev != null) {
                    val streak = (nullStreak[id] ?: 0) + 1
                    if (streak < STATE_GRACE_POLLS) {
                        nullStreak[id] = streak
                        result[id] = prev   // hold previous state
                    } else {
                        lastNonNullState.remove(id)
                        nullStreak.remove(id)
                        result[id] = null
                    }
                } else {
                    result[id] = null
                }
            }
        }
        return result
    }

    /**
     * Tear down a session: cancel its cwd watcher, shut down the PTY, and
     * remove all tracking state.
     */
    fun destroy(id: String) {
        stateOverrides.remove(id)
        lastNonNullState.remove(id)
        nullStreak.remove(id)
        watchJobs.remove(id)?.cancel()
        sessions.remove(id)?.shutdown()
    }
}

/**
 * A single PTY-backed session.
 *
 *  - Output is broadcast to all connected WebSockets via [output].
 *  - A small ring buffer of recent bytes is replayed to new subscribers.
 *  - A headless [ScreenEmulator] mirrors what xterm.js renders so
 *    [detectState] runs against the actual on-screen text.
 */
class TerminalSession private constructor(
    private val pty: PtyProcess,
    initialScrollback: ByteArray? = null,
    initialCols: Int = DEFAULT_COLS,
    initialRows: Int = DEFAULT_ROWS,
) : TermSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    override val output = _output.asSharedFlow()

    // Most recent shell working directory we've observed for this pane. Fed by
    // both the inline OSC 7 scanner (instant) and the proc-cwd poller
    // (fallback). Subscribers are expected to apply their own debouncing.
    private val _cwd = MutableStateFlow<String?>(null)
    override val cwd: StateFlow<String?> = _cwd.asStateFlow()

    private val _programTitle = MutableStateFlow<String?>(null)

    /**
     * Most recent program-set terminal title (OSC 0/2) we've observed for this
     * pane, raw and unsanitized — parsed by the same inline [OscScanner] that
     * handles OSC 7. `null` until a program first sets a title; an empty
     * string means the program cleared its title. Collected (debounced, and
     * only while the opt-in flag is on) by the watcher in
     * [TerminalSessions.create], which forwards it to
     * [WindowState.applyProgramTitle].
     */
    override val programTitle: StateFlow<String?> = _programTitle.asStateFlow()

    private val osc = OscScanner(
        onCwd = { path -> _cwd.value = path },
        onTitle = { title -> _programTitle.value = title },
    )

    private val screen = ScreenEmulator(initialCols = initialCols, initialRows = initialRows)

    // Ring buffer of recent bytes for reconnect replay.
    private val ringCapacity = 128 * 1024
    private val ring = ByteArray(ringCapacity)
    private var ringSize = 0
    private var ringStart = 0
    private val ringLock = Any()

    @Volatile
    private var bytesWritten: Long = 0

    override fun bytesWritten(): Long = bytesWritten

    init {
        if (initialScrollback != null && initialScrollback.isNotEmpty()) {
            // Blobs persisted by older server versions still contain terminal
            // query sequences (DSR, DA, OSC color reads, …); strip them once
            // at ingestion so every future replay of this ring is clean.
            // Newly persisted blobs are already sanitized (the saver persists
            // [snapshot], which strips on the way out).
            appendToRing(ReplaySanitizer.stripQueries(initialScrollback))
            // The restored scrollback may contain DECSET sequences from a
            // full-screen app (vim, htop, …) that died with the old server:
            // mouse tracking, focus reporting, bracketed paste, application
            // cursor keys, alternate screen. Replaying them verbatim leaves
            // the client terminal generating mouse/focus escape reports that
            // the fresh shell receives as garbage input — and nothing ever
            // sends the matching DECRST (issue #91). Neutralize those modes
            // right here in the ring, before the marker, so every future
            // replay of this scrollback ends in a sane state. The new shell's
            // own output follows later in the ring and re-enables anything it
            // actually wants (e.g. readline's bracketed paste).
            appendToRing(RESTORE_MODE_RESET)
            val marker = "\r\n\r\n[2m[session restored — previous process ended][0m\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
            appendToRing(marker)
        }
    }

    private val readJob: Job = scope.launch {
        val input = pty.inputStream
        val buf = ByteArray(4096)
        while (isActive) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                break
            }
            if (n <= 0) break
            osc.feed(buf, n)
            screen.feed(buf, n)
            val chunk = buf.copyOf(n)
            appendToRing(chunk)
            _output.emit(chunk)
        }
    }

    private val pollJob: Job = scope.launch {
        while (isActive) {
            delay(3_000)
            val pid = try { pty.pid() } catch (_: Throwable) { continue }
            val polled = ProcessCwdReader.read(pid)
            if (polled != null && polled != _cwd.value) {
                _cwd.value = polled
            }
        }
    }

    /** Write raw bytes to the PTY's stdin. */
    override fun write(bytes: ByteArray) {
        try {
            pty.outputStream.write(bytes)
            pty.outputStream.flush()
        } catch (_: Throwable) {
            // PTY may have died — ignore; next read will close things down.
        }
    }

    /**
     * Cancel sticky client-side terminal modes on every attached client.
     *
     * Broadcasts [RESTORE_MODE_RESET] (plus a cursor show) as ordinary
     * session output and stamps it into the ring buffer so future replays
     * inherit the sane state. Called by [handleControl] when a client
     * sends [PtyControl.ResetModes] — the pane menu's "Reset terminal"
     * escape hatch for a terminal wedged in mouse-reporting mode
     * (issue #91). Touches only the client-side emulator state; the PTY
     * process itself is not signalled.
     */
    override fun resetTerminalModes() {
        val bytes = RESTORE_MODE_RESET + SHOW_CURSOR_SUFFIX
        appendToRing(bytes)
        scope.launch { _output.emit(bytes) }
    }

    /** Destroy the underlying PTY process and cancel all coroutines. */
    override fun shutdown() {
        try {
            pty.destroy()
        } catch (_: Throwable) {
            // Best effort.
        }
        scope.cancel()
    }

    private val sizeArbiter = ClientSizeArbiter(initialCols, initialRows)
    private val _sizeEvents = MutableStateFlow(Pair(initialCols, initialRows))
    override val sizeEvents: StateFlow<Pair<Int, Int>> = _sizeEvents.asStateFlow()

    /** Register the declared terminal size for [clientId] at [priority]. */
    override fun setClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
        applySize(sizeArbiter.setSize(clientId, SizeVote(max(1, cols), max(1, rows), priority)))
    }

    /**
     * "Reformat" handler: evict every other client's entry, pin this
     * client's cols/rows (at [priority]), make it the governing client, and
     * apply immediately.
     *
     * The dims are clamped to a usable floor ([MIN_FORCE_COLS]×[MIN_FORCE_ROWS]),
     * not just ≥1: a forced size is broadcast to and obeyed by **every** attached
     * client, so a degenerate value from an unmeasured/hidden view (e.g. a 3D
     * preview mid-layout proposing ~1×1) would collapse all of them at once.
     * Regular [setClientSize] votes keep the ≥1 clamp — arbitration across
     * clients already bounds their effect.
     */
    override fun forceClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
        val only = SizeVote(max(MIN_FORCE_COLS, cols), max(MIN_FORCE_ROWS, rows), priority)
        applySize(sizeArbiter.forceSize(clientId, only))
    }

    /** Unregister a client's size entry when its WebSocket disconnects. */
    override fun removeClient(clientId: String) {
        applySize(sizeArbiter.remove(clientId))
    }

    /**
     * Record inbound user input from [clientId]: typing on a client makes it
     * the size governor, so one keystroke on the desktop reclaims the grid
     * from a phone that was merely peeking (or from a dead client whose
     * ping-eviction hasn't fired yet). See [ClientSizeArbiter].
     */
    override fun noteClientInput(clientId: String) {
        applySize(sizeArbiter.noteInput(clientId))
    }

    /**
     * Apply an arbitrated size change to the PTY, the headless emulator and
     * the broadcast [sizeEvents] flow. No-op for null (the arbiter reports
     * null when the effective size is unchanged — the per-keystroke guard).
     *
     * @param next the new effective grid, or null when nothing changed.
     */
    private fun applySize(next: Pair<Int, Int>?) {
        val (c, r) = next ?: return
        try {
            pty.winSize = WinSize(c, r)
        } catch (_: Throwable) {
            // Ignore; resize races are benign.
        }
        screen.resize(c, r)
        _sizeEvents.value = Pair(c, r)
    }

    /** Check the currently-rendered screen for AI assistant state markers. */
    override fun detectState(): SessionState? {
        val text = screen.snapshotVisibleText()
        if (text.isEmpty()) return null
        return StateDetector.detectState(text)
    }

    /**
     * The current rendered viewport as plain text, one row per line — what an
     * attached client's terminal is showing right now. Used by the MCP
     * `read_scrollback` tool's `screen` source so an agent can read the live
     * grid (e.g. a TUI's frame) instead of the raw byte history.
     *
     * @return the visible screen text from the headless [ScreenEmulator].
     */
    override fun screenText(): String = screen.snapshotVisibleText()

    /**
     * Whether the underlying PTY process is still running. Used by the MCP
     * `wait_for_exit` tool (polled) and echoed in `get_session` results. A
     * dead PTY can coexist with a live session object until the referencing
     * pane closes, so this is the authoritative "shell exited" signal.
     *
     * @return true while the PTY's process is alive.
     */
    override fun isProcessAlive(): Boolean = try {
        pty.isAlive
    } catch (_: Throwable) {
        false
    }

    /**
     * Return a copy of the ring buffer contents for reconnect replay, with
     * terminal *query* sequences stripped ([ReplaySanitizer.stripQueries]):
     * replaying a recorded query would make the client terminal answer it
     * again, injecting the answer into the shell as phantom input. The live
     * output stream is never filtered — only this replay copy.
     */
    override fun snapshot(): ByteArray {
        val ringBytes = synchronized(ringLock) {
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
        // Ring-buffer replay can end mid-render with a trailing DECTCEM hide
        // (ESC[?25l) and no matching show. Append a show to the replay tail;
        // a TUI that genuinely wants the cursor hidden will re-hide it on its
        // next frame.
        return ReplaySanitizer.stripQueries(ringBytes) + SHOW_CURSOR_SUFFIX
    }

    private fun appendToRing(chunk: ByteArray) = synchronized(ringLock) {
        for (b in chunk) {
            val writeIdx = (ringStart + ringSize) % ringCapacity
            ring[writeIdx] = b
            if (ringSize < ringCapacity) {
                ringSize++
            } else {
                ringStart = (ringStart + 1) % ringCapacity
            }
        }
        bytesWritten += chunk.size
    }

    companion object {
        /**
         * Escape sequences appended to restored scrollback (see `init`) to
         * cancel terminal modes a dead full-screen app may have left enabled.
         * In order: DECRST of X10/normal/highlight/button-event/any-event
         * mouse tracking plus the UTF-8, SGR and urxvt mouse encodings
         * (9, 1000-1003, 1005, 1006, 1015), focus-event reporting (1004),
         * bracketed paste (2004), application cursor keys (DECCKM, 1) and
         * the alternate screen buffer (1049 — a no-op when not active),
         * then DECKPNM (`ESC >`) to restore the normal keypad.
         *
         * Used only on the killed-server restore path, never on live
         * reconnect replays ([snapshot]), where a running TUI still owns
         * these modes legitimately.
         */
        private val RESTORE_MODE_RESET =
            "[?9;1000;1001;1002;1003;1005;1006;1015l[?1004l[?2004l[?1l[?1049l>"
                .toByteArray(Charsets.US_ASCII)

        private val SHOW_CURSOR_SUFFIX = "[?25h".toByteArray(Charsets.US_ASCII)

        /**
         * Floor for a **forced** resize ([forceClientSize]) — the smallest grid a
         * single client may pin the shared PTY (and thereby every other attached
         * client) to. Generous enough for any real view, small enough to never
         * fight a legitimately tiny pane. Regular per-client votes
         * ([setClientSize]) are not floored beyond ≥1: they only ever *lower*
         * the effective size via min() and never evict anyone.
         */
        private const val MIN_FORCE_COLS = 20
        private const val MIN_FORCE_ROWS = 5

        /**
         * Default grid until a client registers a real size — also the
         * fallback for restored sessions whose persisted scrollback predates
         * size recording. Seeds the PTY, the headless [ScreenEmulator] and
         * [sizeEvents] identically so all three views of the session agree.
         */
        private const val DEFAULT_COLS = 120
        private const val DEFAULT_ROWS = 32

        /**
         * Spawn the user's shell on a fresh PTY and wrap it in a session.
         *
         * Called by [TerminalSessions.create]; [initialCols]/[initialRows]
         * come from the persisted scrollback record on the restore path so
         * the replayed bytes reconstruct in a grid of the width they were
         * rendered for (see `SettingsRepository.ScrollbackRecord`), and are
         * null everywhere else.
         *
         * @param initialCwd starting working directory, or null for home.
         * @param initialScrollback persisted scrollback to seed the ring, or null.
         * @param initialCols initial grid columns; null → [DEFAULT_COLS].
         * @param initialRows initial grid rows; null → [DEFAULT_ROWS].
         * @return the running session.
         */
        fun create(
            initialCwd: String? = null,
            initialScrollback: ByteArray? = null,
            initialCols: Int? = null,
            initialRows: Int? = null,
        ): TerminalSession {
            val cols = initialCols?.takeIf { it > 0 } ?: DEFAULT_COLS
            val rows = initialRows?.takeIf { it > 0 } ?: DEFAULT_ROWS
            val shell = System.getenv("SHELL") ?: "/bin/bash"
            val home = System.getProperty("user.home")
            val startDir = initialCwd
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isDirectory }
                ?.absolutePath
                ?: home
            val env = HashMap(System.getenv()).apply {
                put("TERM", "xterm-256color")
                put("PROMPT_EOL_MARK", "")
            }
            ShellInitFiles.configureEnv(shell, env)
            val pty = PtyProcessBuilder(arrayOf(shell, "-l"))
                .setDirectory(startDir)
                .setEnvironment(env)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .start()
            return TerminalSession(pty, initialScrollback, cols, rows)
        }
    }
}
