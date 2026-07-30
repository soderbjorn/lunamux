/**
 * PTY-session lifecycle and registry.
 *
 * [TerminalSessions] is the process-wide registry of [TerminalSession]s.
 * Sessions are created on demand by [WindowState], identified by short
 * `s<n>` ids, and torn down when the last referencing pane closes.
 *
 * [TerminalSession] is a single PTY-backed session: it owns the
 * `PtyProcess`, maintains the canonical server-side screen ([SessionGrid]) that
 * clients attach to via a synthesized width-correct redraw, runs a headless
 * [ScreenEmulator] in parallel for AI-state detection, and
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
import se.soderbjorn.lunamux.pty.ClientPosture
import se.soderbjorn.lunamux.pty.ClientSizeArbiter
import se.soderbjorn.lunamux.pty.GridSerializer
import se.soderbjorn.lunamux.pty.OscScanner
import se.soderbjorn.lunamux.pty.ProcessCwdReader
import se.soderbjorn.lunamux.pty.RestoreMarker
import se.soderbjorn.lunamux.pty.SessionGrid
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
 *  - `/pty/{id}` bridges [events] / [attachPayload] / [write] /
 *    [setClientSize] / [forceClientSize] / [removeClient] /
 *    [resetTerminalModes] / [sizeEvents],
 *  - the scrollback saver uses [bytesWritten] + [persistSnapshot],
 *  - the state poller uses [detectState],
 *  - the MCP read tools use [transcriptText] / [screenText] /
 *    [isProcessAlive] / [cwd] / [programTitle],
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

    /**
     * The single ordered outbound stream — output chunks and size changes
     * interleaved with monotonic [SessionEvent.seq]. `/pty` clients collect this
     * (seq-gated against their [attachPayload]) instead of merging [output] and
     * [sizeEvents], which makes delivery order exact rather than wall-clock
     * dependent. [output] and [sizeEvents] remain for MCP tools and internal use.
     */
    val events: kotlinx.coroutines.flow.SharedFlow<SessionEvent>

    /**
     * Snapshot the session for a newly-attaching client: the grid dimensions to
     * adopt and a self-contained redraw reconstructing the current screen at
     * those dims, tagged with the last [SessionEvent.seq] it reflects. The client
     * adopts the size, applies the redraw, then processes [events] with a greater
     * seq. Captured atomically with seq assignment, so it is exact against the
     * live stream.
     *
     * @return the attach seed.
     */
    fun attachPayload(): AttachPayload

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

    /**
     * Declare [clientId]'s size-governance [posture] when it attaches. A
     * [ClientPosture.DRIVER] may seize the grid by typing or forcing; a
     * [ClientPosture.VIEWER] (a phone mirroring the desktop) governs only after
     * it explicitly takes over. Called once per connection by the `/pty` route,
     * before any votes. Default no-op so PTY-less sessions (`AgentSession`)
     * ignore it — every client is treated as a driver there.
     *
     * @param clientId the attaching client.
     * @param posture how that client participates in size arbitration.
     * @see se.soderbjorn.lunamux.pty.ClientSizeArbiter.setPosture
     */
    fun setClientPosture(clientId: String, posture: ClientPosture) {}

    /** Broadcast mode-reset sequences to attached clients (see issue #91). */
    fun resetTerminalModes()

    /** Tear the session down and release all resources. */
    fun shutdown()

    /**
     * Register [clientId]'s viewport size vote at the given [priority] tier.
     * The vote feeds the latest-active-client arbitration (see
     * [se.soderbjorn.lunamux.pty.ClientSizeArbiter]): it applies immediately
     * when [clientId] is the governing client; otherwise it is stored and takes
     * effect if the client later becomes the governor. A plain vote is ambient
     * — it never seizes governance, so a phone/viewer attaching cannot shrink
     * the grid the desktop owns. With no recorded activity the classic tiered
     * min() over the drivers' votes decides.
     */
    fun setClientSize(
        clientId: String,
        cols: Int,
        rows: Int,
        priority: SizePriority = SizePriority.NORMAL,
    )

    /**
     * Force the grid to [clientId]'s size and make it the governing client —
     * an explicit user action (Reformat / phone take-over) always wins, even
     * over a 3D override. Other clients' votes are **kept**, not evicted:
     * governance is decided by which client the user most recently acted on, so
     * a laptop reclaims the grid by simply typing. A viewer that forces is
     * promoted to a driver (see [se.soderbjorn.lunamux.pty.ClientSizeArbiter]).
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

    /**
     * The session's scrollback as text for the MCP `read_scrollback` tool. May
     * carry control sequences (callers strip): a PTY session returns its
     * canonical grid transcript (already plain), an agent session its raw output.
     *
     * @return the scrollback text.
     */
    fun transcriptText(): String

    /**
     * Recent output to persist for a restore after the server exits: a
     * self-contained blob replayed into a fresh session's screen on restart.
     *
     * @see ScrollbackSaver.saveAll
     */
    fun persistSnapshot(): ByteArray

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
@OptIn(FlowPreview::class)
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

    /**
     * The canonical server-side screen: the same vendored Termux emulator the
     * Android client renders with, fed the same raw PTY stream as [screen], plus
     * the session's history as logical lines outside it. Declared before the
     * `init` block so restored scrollback is ingested in the same order it reaches
     * the rest of the session. Read back by [GridSerializer] to synthesize
     * width-correct attach/resync redraws.
     */
    private val grid = SessionGrid(initialCols, initialRows)

    /**
     * Device-query replies the canonical grid produced, waiting to be written to the PTY.
     *
     * The queue exists to get the write off the generating thread. Replies are produced
     * inside `emulator.append`, on the PTY reader thread, while both [outboundLock] and the
     * grid monitor are held — and `PtyProcess.outputStream.write` is a raw blocking
     * `write(2)`. Writing there is the classic both-ends-blocked deadlock: the reader thread
     * blocks in the write because the pty buffer is full, and the pty cannot drain because
     * the only thing reading it is the thread now stuck in the write.
     *
     * `UNLIMITED` so [SessionGrid]'s sink can never suspend or fail to enqueue; a single
     * consumer ([answerWriterJob]) keeps replies in the order the emulator produced them,
     * which is what makes them a valid answer stream rather than an interleaving.
     */
    private val answerChannel = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * The one writer that drains [answerChannel] into the PTY.
     *
     * Single consumer by design: a query stream has one correct answer sequence, so replies
     * must reach the shell in the order the grid generated them.
     */
    private val answerWriterJob: Job = scope.launch {
        for (reply in answerChannel) write(reply)
    }

    // ── Ordered outbound stream (see SessionEvent) ──────────────────────────
    // seq assignment and the grid feed/resize/synthesize the seq refers to happen
    // together under [outboundLock], so [attachPayload] captures a grid state and a
    // seq that are mutually consistent — making the client's seq gate exact rather
    // than a wall-clock race (strictly stronger than the old merge{} in PtyRoutes).
    private val outboundLock = Any()
    private var eventSeq = 0L
    private val eventChannel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 1024)
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    // Fired on every cols change; the heavyweight synthesized resync redraw is
    // coalesced to fire once, RESYNC_DEBOUNCE_MS after the last change in a storm.
    private val resyncTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    @Volatile
    private var bytesWritten: Long = 0

    override fun bytesWritten(): Long = bytesWritten

    init {
        if (initialScrollback != null && initialScrollback.isNotEmpty()) {
            // Reconstruct the persisted scrollback into the canonical grid. The
            // blob is normally the server's own serializeForPersist() output
            // (self-contained styled rows); a legacy raw-byte blob works too —
            // its device queries are answered into nothing, because the grid's
            // answer sink is armed only below, after this feed — and the mode-reset
            // epilogue cancels any sticky modes (mouse/paste/focus/alt) a dead
            // full-screen app may have left set, so a restore never leaves the
            // fresh shell wedged (issue #91).
            grid.feed(initialScrollback, initialScrollback.size)
            grid.feed(RESTORE_MODE_RESET, RESTORE_MODE_RESET.size)
            // Break the line so the new shell's prompt starts below the restored
            // content rather than continuing it — but only when something was
            // actually restored, and only by a single line.
            //
            // This used to be an unconditional "\r\n\r\n". Two breaks made sense when
            // the persist form could end mid-line (it kept the live prompt row), but
            // it now ends at a committed line boundary, so the second break is a blank
            // line the user never asked for — and after a `clear` the blob restores
            // nothing at all, so BOTH breaks landed above the first prompt as two
            // stray empty lines at the top of a brand-new session.
            if (grid.transcriptText().isNotBlank()) {
                val gap = "\r\n".toByteArray(Charsets.UTF_8)
                grid.feed(gap, gap.size)
            }
        }
        // Only now does the canonical grid become the session's answerer. Everything above
        // replayed a *dead* session's bytes; answering a query out of that replay would put a
        // reply for a query nobody asked into the fresh shell's stdin, where ZLE reads it as
        // typed input. From here on the grid answers for the terminal and every client's own
        // reply is dropped at the server (see PtyRoutes) — one query, one answer.
        grid.armAnswerSink { reply ->
            // Enqueue only. The sink runs on the PTY reader thread inside emulator.append
            // with outboundLock and the grid monitor held; see answerChannel.
            answerChannel.trySend(reply)
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
            // Feed the canonical grid and assign this chunk's seq atomically, so a
            // concurrent attach/resize can't observe the grid updated but the seq
            // not yet advanced (which would re-deliver the chunk) or vice versa.
            synchronized(outboundLock) {
                grid.feed(buf, n)
                eventChannel.trySend(SessionEvent.Output(++eventSeq, chunk))
            }
            bytesWritten += n
            _output.emit(chunk)
        }
    }

    // Drains the ordered event channel into the broadcast flow. A single forwarder
    // preserves channel (== seq) order; the producers can't emit to the SharedFlow
    // directly because that suspends, which is illegal under [outboundLock].
    private val forwarderJob: Job = scope.launch {
        for (ev in eventChannel) _events.emit(ev)
    }

    // Emits one synthesized resync redraw after a cols-change storm settles. The
    // redraw is RIS-prefixed and self-contained, so only the last one matters; the
    // per-change Size events still go out immediately from applySize.
    private val resyncJob: Job = scope.launch {
        resyncTrigger.debounce(RESYNC_DEBOUNCE_MS).collect {
            synchronized(outboundLock) {
                val bytes = grid.synthesizeRedraw()
                eventChannel.trySend(SessionEvent.Output(++eventSeq, bytes))
            }
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
     * session output and feeds it into the canonical grid so future attach
     * redraws inherit the sane state. Called by [handleControl] when a client
     * sends [PtyControl.ResetModes] — the pane menu's "Reset terminal"
     * escape hatch for a terminal wedged in mouse-reporting mode
     * (issue #91). Touches only the client-side emulator state; the PTY
     * process itself is not signalled.
     */
    override fun resetTerminalModes() {
        val bytes = RESTORE_MODE_RESET + SHOW_CURSOR_SUFFIX
        synchronized(outboundLock) {
            grid.feed(bytes, bytes.size)
            eventChannel.trySend(SessionEvent.Output(++eventSeq, bytes))
        }
        bytesWritten += bytes.size
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
        val vote = SizeVote(max(MIN_GRID_COLS, cols), max(MIN_GRID_ROWS, rows), priority)
        val next = sizeArbiter.setSize(clientId, vote)
        publishGovernance()
        applySize(next)
    }

    /** Register [clientId]'s declared governance [posture] for this connection. */
    override fun setClientPosture(clientId: String, posture: ClientPosture) {
        sizeArbiter.setPosture(clientId, posture)
    }

    /**
     * "Reformat" / take-over handler: pin this client's cols/rows (at
     * [priority]), make it the governing client, and apply immediately. Other
     * clients' votes are kept — the arbiter decides governance by recency of
     * activity, not by which votes survive (see [ClientSizeArbiter.forceSize]).
     *
     * The dims share the same [MIN_GRID_COLS]×[MIN_GRID_ROWS] floor as a plain
     * vote: a forced size is broadcast to and obeyed by **every** attached
     * client, so a degenerate value from an unmeasured/hidden view must not be
     * able to collapse all of them at once.
     */
    override fun forceClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
        val only = SizeVote(max(MIN_GRID_COLS, cols), max(MIN_GRID_ROWS, rows), priority)
        val next = sizeArbiter.forceSize(clientId, only)
        publishGovernance()
        applySize(next)
    }

    /** Unregister a client's size entry when its WebSocket disconnects. */
    override fun removeClient(clientId: String) {
        val next = sizeArbiter.remove(clientId)
        publishGovernance()
        applySize(next)
    }

    /**
     * Record inbound user input from [clientId]: typing on a client makes it
     * the size governor, so one keystroke on the desktop reclaims the grid
     * from a phone that was merely peeking (or from a dead client whose
     * ping-eviction hasn't fired yet). See [ClientSizeArbiter].
     */
    override fun noteClientInput(clientId: String) {
        val next = sizeArbiter.noteInput(clientId)
        publishGovernance()
        applySize(next)
    }

    /**
     * The governor as of the last broadcast [SessionEvent.Governance], so a
     * change can be detected without re-broadcasting on every keystroke.
     * Guarded by [outboundLock] together with the seq it is published against.
     */
    private var publishedGovernor: String? = null

    /**
     * Broadcast a governance change if the arbiter's governor has moved since the
     * last one. Called after every arbiter mutation — including the ones that
     * leave the effective size unchanged, which is the case the old
     * width-comparison inference could not see at all: two clients rendering at
     * the same width swap governance without a single column changing.
     *
     * Compare-and-publish happens under [outboundLock] so the decision, the seq
     * assignment and the send are one atomic step; concurrent callers therefore
     * cannot interleave into a stale final broadcast, and a no-op change costs
     * one lock and one comparison on the input hot path.
     *
     * Every mutation site calls this **before** [applySize], so a Governance
     * frame is always seq'd ahead of the Size frame from the same mutation:
     * clients compute their mirror-vs-driving verdict when the Size arrives,
     * and the verdict must already be current or a take-over is judged with
     * the previous connection's answer (the stale-verdict race both web and
     * Android had).
     */
    private fun publishGovernance() {
        synchronized(outboundLock) {
            val governor = sizeArbiter.governor()
            if (governor == publishedGovernor) return
            publishedGovernor = governor
            eventChannel.trySend(SessionEvent.Governance(++eventSeq, governor))
        }
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
        val colsChanged = c != _sizeEvents.value.first
        screen.resize(c, r)
        // Resize the grid and emit the Size event atomically with seq assignment,
        // ordered ahead of any output authored at the new width.
        synchronized(outboundLock) {
            grid.resize(c, r)
            eventChannel.trySend(SessionEvent.Size(++eventSeq, c, r))
        }
        // The ioctl goes LAST: it fires SIGWINCH, and the program's repaint answer
        // can hit readJob within the same scheduler quantum. Emitters seq'd before
        // the ioctl guarantee every client sees the Size frame before any byte the
        // program authored at the new geometry, and that those bytes land in an
        // already-resized grid instead of poisoning the next resync.
        try {
            pty.winSize = WinSize(c, r)
        } catch (_: Throwable) {
            // Ignore; resize races are benign.
        }
        _sizeEvents.value = Pair(c, r)
        // Only a cols change rewraps the grid, so only then does the client need a
        // resync redraw; a rows-only change is carried by the Size event alone.
        if (colsChanged) resyncTrigger.tryEmit(Unit)
    }

    override fun attachPayload(): AttachPayload = synchronized(outboundLock) {
        // Capture the synthesized redraw and the seq it reflects together, so the
        // client's "process events with seq > attach.seq" gate is exact. The
        // governor is sampled under the same lock as the seq, so an attaching
        // client cannot land between a governance change and its broadcast and
        // end up believing the wrong thing until the next one.
        val governor = sizeArbiter.governor()
        val snap = grid.attachSnapshot()
        AttachPayload(eventSeq, snap.cols, snap.rows, snap.bytes, governor)
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
     * The canonical grid's normal-buffer transcript (scrollback + screen) as
     * plain text — backed by the server-side grid, so it stays correct across
     * width changes. Consumed by the MCP `read_scrollback` tool.
     *
     * @return the transcript text.
     */
    override fun transcriptText(): String = grid.transcriptText()

    /**
     * Build the blob to persist for a restore after the server exits: the
     * canonical grid serialized to a self-contained, replayable redraw — styled
     * scrollback, and (for a live TUI) an inert frozen frame. It carries no
     * cursor/mode epilogue, so a restored dead session cannot resurrect
     * mouse/paste/focus modes (issue #91). Fed into a fresh grid on restart
     * (see `init`).
     *
     * @return bytes for `pane_scrollback`.
     * @see ScrollbackSaver.saveAll
     * @see GridSerializer.serializeForPersist
     */
    override fun persistSnapshot(): ByteArray = grid.synthesizeForPersist()

    companion object {
        /**
         * Escape sequences appended to restored scrollback (see `init`) to
         * cancel terminal modes a dead full-screen app may have left enabled
         * (mouse tracking, focus reporting, bracketed paste, application
         * cursor keys), plus DECKPNM to restore the normal keypad.
         *
         * The bytes live in [RestoreMarker] (upstream LMX-2), which is also why they
         * no longer carry an alternate-screen exit: persisted with a *byte ring*, a
         * marker ending in `ESC[?1047l` came back on the next restore as an
         * alternate-buffer exit with no matching enter, and the ring's ingest path
         * answered an orphan by dropping every byte recorded before it.
         *
         * [RestoreMarker.neutralizeLegacy] repairs that in blobs already on disk and is
         * deliberately **not** called here, because neither half of the hazard survives
         * on this branch. There is no ring and no `AltScreenTracker`: a restored blob is
         * fed to the canonical grid, where a stale `ESC[?1047l` selects a normal buffer
         * that is already active — a no-op. And a marker can no longer accumulate in a
         * blob at all, because blobs are synthesized from the grid's *cells*
         * ([SessionGrid.synthesizeForPersist]) rather than being a byte ring, and these
         * bytes leave no cells behind.
         *
         * Used on the killed-server restore path and by [resetTerminalModes]; never on
         * live reconnect replays, where a running TUI still owns these modes
         * legitimately.
         *
         * @see RestoreMarker.MODE_RESET
         */
        private val RESTORE_MODE_RESET = RestoreMarker.MODE_RESET

        private val SHOW_CURSOR_SUFFIX = "[?25h".toByteArray(Charsets.US_ASCII)

        /**
         * Floor for any client-supplied grid — the smallest size a single
         * client may drive the shared PTY (and thereby every other attached
         * client) to. Generous enough for any real view, small enough to never
         * fight a legitimately tiny pane. Applied to both a forced resize
         * ([forceClientSize]) and a plain vote ([setClientSize]): under the
         * latest-active arbiter a plain vote can also become the governing size
         * and be broadcast to everyone, so a degenerate value from an
         * unmeasured/hidden view (e.g. a 3D preview mid-layout proposing ~1×1)
         * must not be able to collapse every client through either path.
         */
        private const val MIN_GRID_COLS = 20
        private const val MIN_GRID_ROWS = 5

        /**
         * Default grid until a client registers a real size — also the
         * fallback for restored sessions whose persisted scrollback predates
         * size recording. Seeds the PTY, the headless [ScreenEmulator] and
         * [sizeEvents] identically so all three views of the session agree.
         */
        private const val DEFAULT_COLS = 120
        private const val DEFAULT_ROWS = 32

        /**
         * How long a cols-change storm must settle before the (heavyweight)
         * synthesized resync redraw is emitted. Every Size event still goes out
         * immediately; only the redraw is coalesced, and since each redraw is
         * RIS-prefixed and self-contained, emitting just the last one is correct.
         */
        private const val RESYNC_DEBOUNCE_MS = 100L

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
