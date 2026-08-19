/**
 * Overview-scoped registry of live, read-only terminal miniatures.
 *
 * The overview shows a terminal thumbnail per visible pane. Opening (and
 * closing) a PTY socket from each thumbnail's own composition lifecycle made
 * rendering unpredictable: switching tabs tore down every socket and rebuilt
 * them on return, and the rapid close→reopen to the same session raced with the
 * server's attach/detach handling, so thumbnails came up blank.
 *
 * This registry decouples the PTY socket + headless emulator lifecycle from the
 * pane composables. It opens at most one socket per session id, keeps it alive
 * for as long as the overview is on screen (across tab switches and
 * recompositions), and exposes each session's screen as a [StateFlow] of
 * [TerminalFrame] snapshots — the resolved colored grid at the server's own
 * cols×rows, published at most every [THUMB_FRAME_MIN_INTERVAL_MS] ms. A
 * thumbnail composable simply collects that flow, so leaving and re-entering a
 * tab re-attaches to an already-populated emulator and renders instantly — no
 * reconnect, no churn. Panes that share a session (linked views) share a
 * single socket.
 *
 * The registry is created by [OverviewContent], provided via
 * [LocalMiniTerminalRegistry], and [close]d when the overview leaves
 * composition. [OverviewContent] also pushes the resolved theme through
 * [setDefaultColors] — the headless emulators otherwise keep the Termux stock
 * palette for the default fg/bg/cursor slots, which the full-screen terminal
 * overrides via `applyTerminalColors`. Because every server attach redraw (and
 * every reconnect) starts with RIS, which resets the emulator's color table,
 * the theme is re-applied whenever a reset passes through — the same rule the
 * full-screen path follows via `containsTerminalReset`.
 *
 * Snapshots are gated twice, so a publisher only ever does work that shows up
 * on screen: nothing is published before the attach redraw lands (a snapshot of
 * the fresh 80×24 emulator is a blank frame that would replace real content),
 * and nothing is published while no thumbnail is collecting the flow (an
 * offscreen switcher card keeps its socket, not its snapshot cost) — a deferred
 * snapshot is re-armed the moment someone collects again.
 *
 * Read-only invariant: like [MiniTerminalPane], entries never call
 * [se.soderbjorn.lunamux.client.PtySocket.resize]/`send`, so a thumbnail can
 * never shrink the real PTY for other clients.
 *
 * @see MiniTerminalPane
 * @see TerminalFrame
 * @see OverviewContent
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import se.soderbjorn.lunamux.client.PtyEvent
import se.soderbjorn.lunamux.client.PtySocket
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunula.core.ResolvedTheme

/**
 * Minimum interval between published frames per session (~10 fps). Bursty
 * output coalesces in the conflated dirty channel; the first event after an
 * idle stretch snapshots immediately.
 */
private const val THUMB_FRAME_MIN_INTERVAL_MS = 100L

/**
 * CompositionLocal exposing the active [MiniTerminalRegistry], or `null` when
 * not inside an overview that provides one.
 */
val LocalMiniTerminalRegistry = compositionLocalOf<MiniTerminalRegistry?> { null }

/**
 * Holds one live emulator + PTY socket per session id for the lifetime of the
 * overview.
 *
 * @param client the connected client used to open PTY sockets.
 * @param scope  the overview-scoped coroutine scope; collectors run here and are
 *   cancelled when the overview leaves composition (also explicitly torn down by
 *   [close]).
 */
class MiniTerminalRegistry(
    private val client: LunamuxClient,
    private val scope: CoroutineScope,
) {
    /**
     * A single live miniature: the PTY socket, its externally-fed emulator, the
     * single-thread dispatcher serialising emulator access, the collector +
     * publisher jobs, the dirty signal between them, and the published frames.
     */
    private class Entry(
        val socket: PtySocket,
        val emulator: TerminalEmulator,
        val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
        val job: Job,
        val publishJob: Job,
        val resubscribeJob: Job,
        val dirty: Channel<Unit>,
        val frame: MutableStateFlow<TerminalFrame?>,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var closed = false

    /**
     * The resolved theme applied to every entry's emulator (see
     * [setDefaultColors]) and re-applied after every terminal reset. Null until
     * the theme first resolves; entries created before that keep the Termux
     * defaults until it lands.
     */
    @Volatile
    private var theme: ResolvedTheme? = null

    /**
     * Theme every live emulator (present and future) via [applyDefaultColors] —
     * the same three palette slots the full-screen terminal overrides through
     * `applyTerminalColors` — and republish each session's frame so
     * already-rendered thumbnails repaint.
     *
     * Called by [OverviewContent] whenever the resolved theme changes. The theme
     * is also remembered here, both for entries created later and for the
     * re-apply after a terminal reset.
     *
     * @param resolved the theme whose text/bg/accent become the emulators'
     *   default fg/bg/cursor.
     */
    fun setDefaultColors(resolved: ResolvedTheme) {
        theme = resolved
        val snapshot = synchronized(lock) { entries.values.toList() }
        for (entry in snapshot) {
            scope.launch {
                withContext(entry.dispatcher) {
                    synchronized(entry.emulator) { applyDefaultColors(entry.emulator, resolved) }
                }
                entry.dirty.trySend(Unit)
            }
        }
    }

    /**
     * Return the frame flow for [sessionId], creating and starting the
     * underlying socket + emulator on first request. Subsequent calls (and
     * other panes sharing the session) get the same flow.
     *
     * @param sessionId the PTY session to mirror.
     * @return a hot [StateFlow] of resolved screen snapshots; `null` until the
     *   first frame is published after attach.
     */
    fun frameFor(sessionId: String): StateFlow<TerminalFrame?> = synchronized(lock) {
        entries.getOrPut(sessionId) { createEntry(sessionId) }.frame.asStateFlow()
    }

    /**
     * Build and start a live entry for [sessionId]: open the socket, wire a
     * headless emulator, collect size + output events, and publish throttled
     * [TerminalFrame] snapshots.
     */
    private fun createEntry(sessionId: String): Entry {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val socket = client.openPtySocket(sessionId)
        // No view backs a registry emulator; the ref stays null. The session never votes a
        // size on its own (only the layout listener in TerminalScreen does, and there is no
        // view here), and a thumbnail never takes input, so its take-over gate is a no-op.
        val viewRef = mutableStateOf<TerminalView?>(null)
        val session = createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = dispatcher,
            terminalViewRef = viewRef,
            ptySocket = socket,
            // No view drives updateSize here, so the pin is never read; the
            // thumbnail sizes its emulator directly in the Size collector below.
            // A thumbnail never takes input, so the input handler is a no-op.
            serverGridPin = java.util.concurrent.atomic.AtomicReference(null),
            handleInput = {},
        )
        val emulator = createSyncedEmulator(session)
        // Seed from the process-wide last-frame cache: a registry rebuilt after
        // navigation (tree → terminal → back) shows each session's last-known
        // screen instantly while its socket reattaches, instead of a blank box —
        // which is also what the dive transition's reverse flight renders.
        val frame = MutableStateFlow<TerminalFrame?>(lastFrames.get(sessionId))
        val dirty = Channel<Unit>(Channel.CONFLATED)
        val revision = AtomicLong(frame.value?.revision ?: 0)
        // Nothing is worth snapshotting until the server's attach redraw lands:
        // a fresh emulator is a blank 80x24 grid, and the theme/size signals
        // below both fire long before the socket's first bytes (each entry opens
        // its own socket, so the replay is a connect + RTT away) — publishing
        // that blank would replace the seeded last-known screen and poison the
        // cache the dive transition flies back to.
        val hasContent = AtomicBoolean(false)
        // Set while a dirty signal went unpublished because nothing was
        // collecting the flow; cleared when the deferred snapshot is taken.
        val deferred = AtomicBoolean(false)

        // Theme the fresh emulator if the resolved theme already landed; a
        // theme arriving later reaches it via setDefaultColors.
        theme?.let { resolved ->
            scope.launch {
                withContext(dispatcher) { synchronized(emulator) { applyDefaultColors(emulator, resolved) } }
                dirty.trySend(Unit)
            }
        }

        // One ordered collector: size, output and reconnect resets applied to
        // the headless preview emulator in the order the server produced them
        // (the old split size/output collectors could interleave and mangle the
        // thumbnail's wrap). A thumbnail never votes a size, so this only reads.
        val job = scope.launch {
            socket.events.collect { ev ->
                withContext(dispatcher) {
                    synchronized(emulator) {
                        when (ev) {
                            is PtyEvent.Size ->
                                // The thumbnail passes no grid on connect, so the
                                // server synthesizes the redraw at the PTY dims — the
                                // thumbnail just renders at exactly that width.
                                runCatching {
                                    emulator.resize(ev.cols, ev.rows, 1, 1)
                                }
                            is PtyEvent.Bytes -> {
                                emulator.append(ev.data, ev.data.size)
                                hasContent.set(true)
                                // The attach redraw is RIS-prefixed, and a real
                                // `reset` in the shell sends one too. RIS resets
                                // the color table to the Termux stock palette, so
                                // the phone theme has to be written back or the
                                // thumbnail renders stock-black from the first
                                // attach onwards (the full-screen path re-applies
                                // on the same detection).
                                if (containsTerminalReset(ev.data)) reapplyTheme(emulator)
                            }
                            // A thumbnail renders at whatever width the server sends and
                            // never votes, so it is never the governor and has nothing to
                            // change when governance moves.
                            is PtyEvent.Governance -> Unit
                            PtyEvent.Reset -> {
                                val ris = byteArrayOf(0x1b, 'c'.code.toByte())
                                emulator.append(ris, ris.size)
                                reapplyTheme(emulator)
                            }
                        }
                    }
                }
                dirty.trySend(Unit)
            }
        }

        // Throttled publisher: one snapshot per dirty signal, then a floor
        // interval so a byte storm coalesces instead of snapshotting per event.
        // The snapshot runs on the same dispatcher (and under the same lock) as
        // the mutations above, so the published DTO is a consistent copy.
        val publishJob = scope.launch {
            for (unit in dirty) {
                if (!hasContent.get()) continue
                // Mark the signal deferred before testing the subscriber count,
                // so a collector that arrives in between is seen by the watcher
                // below rather than falling between the two.
                deferred.set(true)
                if (frame.subscriptionCount.value == 0) continue
                deferred.set(false)
                val next = withContext(dispatcher) {
                    synchronized(emulator) { snapshotFrame(emulator, revision.incrementAndGet()) }
                }
                frame.value = next
                lastFrames.put(sessionId, next)
                delay(THUMB_FRAME_MIN_INTERVAL_MS)
            }
        }

        // A card scrolled back into the switcher row (or a tab returned to) has
        // to show the session as it is now, not as it was when the last
        // collector went away — re-arm the deferred snapshot on resubscribe.
        val resubscribeJob = scope.launch {
            frame.subscriptionCount.collect { count ->
                if (count > 0 && deferred.get()) dirty.trySend(Unit)
            }
        }
        return Entry(socket, emulator, dispatcher, job, publishJob, resubscribeJob, dirty, frame)
    }

    /**
     * Re-write the resolved theme into [emulator]'s color table after a
     * terminal reset wiped it. No-op until the theme resolves.
     *
     * Called from the event collector, which already holds the emulator lock on
     * its dispatcher — the contract [applyDefaultColors] requires.
     *
     * @param emulator the entry's headless emulator, freshly reset.
     */
    private fun reapplyTheme(emulator: TerminalEmulator) {
        theme?.let { applyDefaultColors(emulator, it) }
    }

    companion object {
        /**
         * Process-wide cache of each session's most recent frame, surviving
         * registry teardown (the overview closes all sockets whenever the tree
         * leaves composition). Seeds fresh entries so returning to the overview
         * — including the dive transition's reverse flight — paints the
         * last-known screen immediately. Sixteen entries comfortably covers a
         * world's visible panes; stale sessions age out.
         */
        private val lastFrames = android.util.LruCache<String, TerminalFrame>(16)

        /**
         * The last frame published for [sessionId] by any registry, or `null`.
         *
         * Read by `TerminalScreen`, which paints it over its still-empty view
         * until the session's own output lands — the dive transition would
         * otherwise grow an empty box.
         *
         * @param sessionId the session whose frame to look up.
         * @return the cached frame, or `null` if none was ever published.
         */
        internal fun lastFrameFor(sessionId: String): TerminalFrame? = lastFrames.get(sessionId)

        /**
         * Publish a frame captured outside any registry.
         *
         * Called by `TerminalScreen` as the user leaves a terminal: the
         * full-screen view owns a live emulator, and it is the only thing that
         * knows the session's current screen while the overview's registry is
         * torn down. Without it the card the reverse flight lands on shows the
         * session as it was before the dive, until its socket has reattached.
         *
         * @param sessionId the session the frame belongs to.
         * @param frame     the snapshot to cache.
         */
        internal fun putFrame(sessionId: String, frame: TerminalFrame) {
            lastFrames.put(sessionId, frame)
        }

        /**
         * Drop every cached frame. Called when the app disconnects from a host
         * (see [se.soderbjorn.lunamux.android.net.ConnectionHolder.disconnect]):
         * the cache is process-wide and keyed by bare session id, so without
         * this it would both keep up to sixteen rendered screens of a host the
         * user has left in memory, and — if two hosts ever mint the same id —
         * seed the next host's overview with the previous host's screen.
         */
        fun clearFrameCache() {
            lastFrames.evictAll()
        }
    }

    /**
     * Tear down every live entry: cancel collectors and publishers, close
     * sockets (detached so the close reaches the server even as the overview's
     * scope unwinds), and release the dispatchers. Idempotent. Called by
     * [OverviewContent] on dispose.
     */
    fun close() {
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            val snapshot = entries.values.toList()
            entries.clear()
            snapshot
        }
        for (entry in toClose) {
            entry.job.cancel()
            entry.publishJob.cancel()
            entry.resubscribeJob.cancel()
            entry.dirty.close()
            entry.socket.closeDetached()
            runCatching { entry.dispatcher.close() }
        }
    }
}
