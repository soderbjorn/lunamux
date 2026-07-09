/**
 * Client-side abstraction over the `/window` channel: the server's
 * authoritative window layout ([WindowConfig]), the per-session state map,
 * and the request/reply envelope stream.
 *
 * [WindowSocket] is an interface so the transport can be swapped:
 *   - [RealWindowSocket] talks WebSocket to a live Lunamux server
 *     (created via [LunamuxClient.openWindowSocket]);
 *   - [se.soderbjorn.lunamux.client.demo.DemoWindowSocket] answers from
 *     the in-process [se.soderbjorn.lunamux.client.demo.DemoServer]
 *     simulation and never touches the network (demo mode).
 *
 * The file-browser and git round-trips are default implementations on the
 * interface: they only need [send] + [envelopes], so every transport gets
 * them for free.
 *
 * @see RealWindowSocket
 * @see LunamuxClient.openWindowSocket
 * @see WindowStateRepository
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.lunamux.FileBrowserEntry
import se.soderbjorn.lunamux.GitFileEntry
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope

/**
 * Live connection to the window channel. Exposes the latest [WindowConfig]
 * and the per-session AI-assistant state map as hot [StateFlow]s. File-
 * browser round-trips (list/open) are modelled as suspend functions that
 * write a [WindowCommand] and await the matching [WindowEnvelope] reply.
 *
 * Implementations: [RealWindowSocket] (WebSocket transport) and
 * [se.soderbjorn.lunamux.client.demo.DemoWindowSocket] (in-process
 * simulation).
 */
interface WindowSocket {
    /** The latest authoritative window layout, `null` before the first push. */
    val config: StateFlow<WindowConfig?>

    /** Per-session AI-assistant state map (session id → `"working"` etc.). */
    val states: StateFlow<Map<String, String?>>

    /** Raw envelope stream: every server→client message, for routing layers. */
    val envelopes: SharedFlow<WindowEnvelope>

    /**
     * Whether the underlying channel is currently connected. UI layers
     * collect this to show/hide a disconnected overlay. Demo mode is
     * always `true`.
     */
    val connected: StateFlow<Boolean>

    /**
     * Waits for either the first [WindowEnvelope.Config] to arrive or the
     * channel to close post-handshake. Returns normally on config, throws
     * [IllegalStateException] if the channel closed before any config was
     * received (the server rejected us after the WS upgrade — typically
     * because device auth returned REJECTED or HEADLESS).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun awaitInitialConfig()

    /**
     * Suspends until the underlying transport handshake has completed (or
     * rethrows the failure if it didn't). Callers that want to surface
     * connection errors up to the UI — instead of quietly falling back to a
     * "no config yet" screen — should await this before navigating.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun awaitSessionReady()

    /**
     * Send a [WindowCommand] to the (real or simulated) server. If the
     * transport is not yet established, suspends until it is.
     *
     * @param command the command to send (e.g. [WindowCommand.AddTab]).
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun send(command: WindowCommand)

    /**
     * Permanently close the channel. After this call the [WindowSocket] is
     * no longer usable.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun close()

    /**
     * Mobile resume hook: if nothing has been received for at least
     * [maxQuietMillis], assume the underlying connection died while the app
     * was backgrounded (sleep/NAT silently kills TCP without an error — the
     * read loop just hangs forever) and force an immediate reconnect,
     * skipping any reconnect backoff in progress. The server re-pushes the
     * full config/state on reconnect, so the UI refreshes.
     *
     * The server broadcasts a session-state envelope every ~3 s, so a live
     * `/window` channel is never quiet for long — a threshold of ~6 s
     * reliably separates "healthy" from "zombie" without churning the
     * connection on quick app switches.
     *
     * Default is a no-op: the demo transport has no connection to refresh.
     *
     * @param maxQuietMillis quiet period after which the connection is
     *   presumed dead. Pass `0` to force a reconnect unconditionally.
     */
    fun reconnectIfStale(maxQuietMillis: Long) {}

    /**
     * Request a directory listing for a file-browser pane.
     *
     * @param paneId     the file-browser pane that owns the request.
     * @param dirRelPath relative path of the directory to list; `""` for root.
     * @param timeoutMs  maximum time to wait for the response.
     * @return the list of [FileBrowserEntry] items, or `null` on error/timeout.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun fileBrowserListDir(
        paneId: String,
        dirRelPath: String,
        timeoutMs: Long = 10_000,
    ): List<FileBrowserEntry>? {
        val reply = sendAndAwaitReply(WindowCommand.FileBrowserListDir(paneId, dirRelPath), timeoutMs) {
            (it is WindowEnvelope.FileBrowserDir && it.paneId == paneId && it.dirRelPath == dirRelPath) ||
                (it is WindowEnvelope.FileBrowserError && it.paneId == paneId)
        } ?: return null
        return when (reply) {
            is WindowEnvelope.FileBrowserDir -> reply.entries
            is WindowEnvelope.FileBrowserError -> null
            else -> null
        }
    }

    /**
     * Open a file and receive its rendered/highlighted content.
     *
     * @param paneId    the file-browser pane that owns the request.
     * @param relPath   relative path of the file to open.
     * @param timeoutMs maximum time to wait for the response.
     * @return a [WindowEnvelope.FileBrowserContentMsg] on success,
     *   [WindowEnvelope.FileBrowserError] on failure, or `null` on timeout.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun fileBrowserOpenFile(
        paneId: String,
        relPath: String,
        timeoutMs: Long = 10_000,
    ): WindowEnvelope? =
        sendAndAwaitReply(WindowCommand.FileBrowserOpenFile(paneId, relPath), timeoutMs) {
            (it is WindowEnvelope.FileBrowserContentMsg && it.paneId == paneId && it.relPath == relPath) ||
                (it is WindowEnvelope.FileBrowserError && it.paneId == paneId)
        }

    /**
     * Fire-and-forget: persist whether [dirRelPath] is expanded or collapsed
     * in the file-browser pane identified by [paneId].
     *
     * @param paneId     the owning file-browser pane.
     * @param dirRelPath relative path of the directory.
     * @param expanded   `true` to expand, `false` to collapse.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun fileBrowserSetExpanded(paneId: String, dirRelPath: String, expanded: Boolean) {
        send(WindowCommand.FileBrowserSetExpanded(paneId, dirRelPath, expanded))
    }

    /**
     * Request the list of changed files from git for the given pane.
     *
     * @param paneId    the git pane that owns the request.
     * @param timeoutMs maximum time to wait for the reply.
     * @return list of [GitFileEntry] items, or `null` on error/timeout.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun gitList(paneId: String, timeoutMs: Long = 10_000): List<GitFileEntry>? {
        val reply = sendAndAwaitReply(WindowCommand.GitList(paneId), timeoutMs) {
            (it is WindowEnvelope.GitList && it.paneId == paneId) ||
                (it is WindowEnvelope.GitError && it.paneId == paneId)
        } ?: return null
        return when (reply) {
            is WindowEnvelope.GitList -> reply.entries
            is WindowEnvelope.GitError -> null
            else -> null
        }
    }

    /**
     * Request the diff for a single file in the given git pane.
     *
     * @param paneId    the git pane that owns the request.
     * @param filePath  path of the file to diff.
     * @param timeoutMs maximum time to wait for the reply.
     * @return a [WindowEnvelope.GitDiffResult] on success,
     *   [WindowEnvelope.GitError] on failure, or `null` on timeout.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun gitDiff(
        paneId: String,
        filePath: String,
        timeoutMs: Long = 10_000,
    ): WindowEnvelope? =
        sendAndAwaitReply(WindowCommand.GitDiff(paneId, filePath), timeoutMs) {
            (it is WindowEnvelope.GitDiffResult && it.paneId == paneId && it.filePath == filePath) ||
                (it is WindowEnvelope.GitError && it.paneId == paneId)
        }
}

/**
 * RPC plumbing shared by the [WindowSocket] round-trip helpers: subscribe
 * to [WindowSocket.envelopes] **before** dispatching [command], then await
 * the first envelope matching [isReply] for up to [timeoutMs].
 *
 * The subscribe-first ordering matters: against the in-process demo server
 * the reply is emitted synchronously *inside* [WindowSocket.send], so a
 * subscriber attached afterwards has already missed it and would sit out
 * the whole timeout. The waiter is started UNDISPATCHED so its
 * subscription is registered on the spot. (Against a real server the reply
 * arrives a network round-trip later, which is why the old send-then-
 * subscribe ordering appeared to work.)
 *
 * @param command the request to send.
 * @param timeoutMs maximum time to wait for the matching reply.
 * @param isReply matcher for the reply envelope.
 * @return the matched envelope, or `null` on timeout.
 */
private suspend fun WindowSocket.sendAndAwaitReply(
    command: WindowCommand,
    timeoutMs: Long,
    isReply: (WindowEnvelope) -> Boolean,
): WindowEnvelope? = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            envelopes.filter(isReply).first()
        }
        send(command)
        waiter.await()
    }
}
