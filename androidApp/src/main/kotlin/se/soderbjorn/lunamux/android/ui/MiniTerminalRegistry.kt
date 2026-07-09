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
 * recompositions), and exposes each session's most recent lines as a
 * [StateFlow]. A thumbnail composable simply collects that flow, so leaving and
 * re-entering a tab re-attaches to an already-populated emulator and renders
 * instantly — no reconnect, no churn. Panes that share a session (linked views)
 * share a single socket.
 *
 * The registry is created by [OverviewContent], provided via
 * [LocalMiniTerminalRegistry], and [close]d when the overview leaves
 * composition.
 *
 * Read-only invariant: like [MiniTerminalPane], entries never call
 * [se.soderbjorn.lunamux.client.PtySocket.resize]/`send`, so a thumbnail can
 * never shrink the real PTY for other clients.
 *
 * @see MiniTerminalPane
 * @see OverviewContent
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import se.soderbjorn.lunamux.client.PtySocket
import se.soderbjorn.lunamux.client.LunamuxClient

/** Max recent logical lines kept per miniature (see [MiniTerminalPane]). */
private const val MINI_REGISTRY_MAX_LINES = 80

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
     * single-thread dispatcher serialising emulator access, the collector job,
     * and the published lines.
     */
    private class Entry(
        val socket: PtySocket,
        val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
        val job: Job,
        val lines: MutableStateFlow<List<String>>,
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var closed = false

    /**
     * Return the most-recent-lines flow for [sessionId], creating and starting
     * the underlying socket + emulator on first request. Subsequent calls (and
     * other panes sharing the session) get the same flow.
     *
     * @param sessionId the PTY session to mirror.
     * @return a hot [StateFlow] of the session's trailing lines, oldest-to-newest.
     */
    fun linesFor(sessionId: String): StateFlow<List<String>> = synchronized(lock) {
        entries.getOrPut(sessionId) { createEntry(sessionId) }.lines.asStateFlow()
    }

    /**
     * Build and start a live entry for [sessionId]: open the socket, wire a
     * headless emulator, and collect size + output into the lines flow.
     */
    private fun createEntry(sessionId: String): Entry {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val socket = client.openPtySocket(sessionId)
        // No view backs a registry emulator; the ref stays null and
        // applyingServerSize stays true so the shared session never echoes a
        // resize to the server.
        val viewRef = mutableStateOf<TerminalView?>(null)
        val session = createExternalTerminalSession(
            scope = scope,
            emulatorDispatcher = dispatcher,
            terminalViewRef = viewRef,
            applyingServerSize = AtomicBoolean(true),
            ptySocket = socket,
        )
        val emulator = createSyncedEmulator(session)
        val lines = MutableStateFlow<List<String>>(emptyList())

        val job = scope.launch {
            coroutineScope {
                launch {
                    socket.ptySize.collect { sz ->
                        if (sz == null) return@collect
                        withContext(dispatcher) {
                            synchronized(emulator) {
                                runCatching { emulator.resize(sz.first, sz.second, 1, 1) }
                            }
                        }
                        lines.value = synchronized(emulator) {
                            extractRecentLines(emulator, MINI_REGISTRY_MAX_LINES)
                        }
                    }
                }
                launch {
                    socket.output.collect { chunk ->
                        withContext(dispatcher) {
                            synchronized(emulator) { emulator.append(chunk, chunk.size) }
                        }
                        lines.value = synchronized(emulator) {
                            extractRecentLines(emulator, MINI_REGISTRY_MAX_LINES)
                        }
                    }
                }
            }
        }
        return Entry(socket, dispatcher, job, lines)
    }

    /**
     * Tear down every live entry: cancel collectors, close sockets (detached so
     * the close reaches the server even as the overview's scope unwinds), and
     * release the dispatchers. Idempotent. Called by [OverviewContent] on
     * dispose.
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
            entry.socket.closeDetached()
            runCatching { entry.dispatcher.close() }
        }
    }
}

/**
 * Extract the most recent up-to-[maxLines] logical lines from [emulator]'s
 * transcript (scrollback + current screen). Uses the joined-line transcript so
 * terminal-width hard wraps become single logical lines the miniature re-wraps
 * to its own width; trailing blank lines are trimmed. Returned oldest-to-newest.
 * Must be called under the emulator lock.
 *
 * CONSOLIDATION: the split + trim-to-maxLines below is duplicated, identically
 * in intent, on iOS (`MiniTerminalRegistry.swift` `extractRecentLines`) and web
 * (`LinkThumbnailRenderer.kt` `readLogicalLines`). Only the line above — reading
 * `transcriptText` from this platform's native emulator — is platform-specific.
 * The pure transform could be hoisted into `client/commonMain` (e.g.
 * `TerminalThumbnailModel.trimRecentLines`) and shared by all three front-ends;
 * see the full design note in `web/.../LinkThumbnailRenderer.kt`. Note Android
 * relies on Compose `Text` to re-wrap each logical line to the pane width (see
 * [MiniTerminalPane]), whereas web re-wraps explicitly — reconcile that before
 * sharing the wrap step.
 *
 * @param emulator the externally-fed emulator to read.
 * @param maxLines the most lines to return.
 * @return the trailing logical lines, oldest-to-newest.
 */
internal fun extractRecentLines(emulator: TerminalEmulator, maxLines: Int): List<String> {
    val text = runCatching { emulator.screen.transcriptText }.getOrDefault("")
    if (text.isBlank()) return emptyList()
    val all = text.split('\n')
    return if (all.size > maxLines) all.subList(all.size - maxLines, all.size) else all
}
