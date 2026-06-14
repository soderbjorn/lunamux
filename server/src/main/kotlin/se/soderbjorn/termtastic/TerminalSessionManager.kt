/**
 * PTY-session lifecycle and registry.
 *
 * [TerminalSessions] is the process-wide registry of [TerminalSession]s.
 * Sessions are created on demand by [WindowState], identified by short
 * `s<n>` ids, and torn down when the last referencing pane closes.
 *
 * [TerminalSession] is a single PTY-backed session: it owns the
 * `PtyProcess`, replays a 64 kB ring buffer to reconnecting clients, runs
 * a headless [ScreenEmulator] in parallel for AI-state detection, and
 * negotiates a per-PTY winsize as the minimum across all attached
 * clients (tmux-style semantics).
 *
 * @see WindowState
 * @see ScreenEmulator
 * @see Osc7Scanner
 * @see ProcessCwdReader
 */
package se.soderbjorn.termtastic

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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.pty.Osc7Scanner
import se.soderbjorn.termtastic.pty.ProcessCwdReader
import se.soderbjorn.termtastic.pty.ShellInitFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * Registry of process-wide PTYs. Each created session also gets a watcher
 * coroutine that listens for cwd changes coming out of [TerminalSession.cwd]
 * and forwards them (debounced) into [WindowState.updatePaneCwd]. The
 * 750 ms debounce coalesces `cd` bursts before they ever touch the
 * WindowConfig flow; the existing 2 s persistence debouncer in `main()`
 * then coalesces *those* updates into one SQLite write.
 */
@OptIn(FlowPreview::class)
object TerminalSessions {
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val watchJobs = ConcurrentHashMap<String, Job>()
    private val idCounter = AtomicLong(0)
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateOverrides = ConcurrentHashMap<String, String?>()

    private val lastNonNullState = ConcurrentHashMap<String, String>()
    private val nullStreak = ConcurrentHashMap<String, Int>()
    private const val STATE_GRACE_POLLS = 3  // 3 polls × 3 s = 9 s grace

    /** Create a fresh session and return its newly minted id. */
    fun create(initialCwd: String? = null, initialScrollback: ByteArray? = null): String {
        val id = "s${idCounter.incrementAndGet()}"
        val session = TerminalSession.create(initialCwd, initialScrollback)
        sessions[id] = session
        watchJobs[id] = watchScope.launch {
            session.cwd
                .filterNotNull()
                .debounce(750.milliseconds)
                .distinctUntilChanged()
                .collect { newCwd -> WindowState.updatePaneCwd(id, newCwd) }
        }
        return id
    }

    /** Look up a live session by its id. */
    fun get(id: String): TerminalSession? = sessions[id]

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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _output = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val output = _output.asSharedFlow()

    // Most recent shell working directory we've observed for this pane. Fed by
    // both the inline OSC 7 scanner (instant) and the proc-cwd poller
    // (fallback). Subscribers are expected to apply their own debouncing.
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    private val osc7 = Osc7Scanner { path -> _cwd.value = path }

    private val screen = ScreenEmulator(initialCols = 120, initialRows = 32)

    // Ring buffer of recent bytes for reconnect replay.
    private val ringCapacity = 128 * 1024
    private val ring = ByteArray(ringCapacity)
    private var ringSize = 0
    private var ringStart = 0
    private val ringLock = Any()

    @Volatile
    private var bytesWritten: Long = 0

    fun bytesWritten(): Long = bytesWritten

    init {
        if (initialScrollback != null && initialScrollback.isNotEmpty()) {
            appendToRing(initialScrollback)
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
            osc7.feed(buf, n)
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
    fun write(bytes: ByteArray) {
        try {
            pty.outputStream.write(bytes)
            pty.outputStream.flush()
        } catch (_: Throwable) {
            // PTY may have died — ignore; next read will close things down.
        }
    }

    /** Destroy the underlying PTY process and cancel all coroutines. */
    fun shutdown() {
        try {
            pty.destroy()
        } catch (_: Throwable) {
            // Best effort.
        }
        scope.cancel()
    }

    private val clientSizes = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val _sizeEvents = MutableStateFlow(Pair(120, 32))
    val sizeEvents: StateFlow<Pair<Int, Int>> = _sizeEvents.asStateFlow()

    /** Register the declared terminal size for [clientId]. */
    fun setClientSize(clientId: String, cols: Int, rows: Int) {
        clientSizes[clientId] = Pair(max(1, cols), max(1, rows))
        applyEffectiveSize()
    }

    /**
     * "Reformat" handler: evict every other client's entry, pin this
     * client's cols/rows, and apply immediately.
     */
    fun forceClientSize(clientId: String, cols: Int, rows: Int) {
        val only = Pair(max(1, cols), max(1, rows))
        clientSizes.clear()
        clientSizes[clientId] = only
        applyEffectiveSize()
    }

    /** Unregister a client's size entry when its WebSocket disconnects. */
    fun removeClient(clientId: String) {
        if (clientSizes.remove(clientId) != null) applyEffectiveSize()
    }

    private fun applyEffectiveSize() {
        val sizes = clientSizes.values
        if (sizes.isEmpty()) return
        val c = sizes.minOf { it.first }
        val r = sizes.minOf { it.second }
        try {
            pty.winSize = WinSize(c, r)
        } catch (_: Throwable) {
            // Ignore; resize races are benign.
        }
        screen.resize(c, r)
        _sizeEvents.value = Pair(c, r)
    }

    /** Check the currently-rendered screen for AI assistant state markers. */
    fun detectState(): SessionState? {
        val text = screen.snapshotVisibleText()
        if (text.isEmpty()) return null
        return StateDetector.detectState(text)
    }

    /** Return a copy of the ring buffer contents for reconnect replay. */
    fun snapshot(): ByteArray {
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
        return ringBytes + SHOW_CURSOR_SUFFIX
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
        private val SHOW_CURSOR_SUFFIX = "[?25h".toByteArray(Charsets.US_ASCII)

        fun create(initialCwd: String? = null, initialScrollback: ByteArray? = null): TerminalSession {
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
                .setInitialColumns(120)
                .setInitialRows(32)
                .start()
            return TerminalSession(pty, initialScrollback)
        }
    }
}
