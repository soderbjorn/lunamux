/**
 * WebSocket-backed [PtySocket] implementation for the `/pty/{sessionId}`
 * endpoint, providing the live bidirectional byte stream between the
 * client's terminal renderer and the server-side PTY process.
 *
 * Includes automatic reconnection: when the connection drops (network blip,
 * app backgrounded long enough for the OS to kill the socket), the socket
 * reconnects with backoff. Because the server replays the session's full
 * ring buffer on every attach, each *re*-connect emits a [PtyEvent.Reset] on
 * [events] before the replay so the renderer clears and repaints exactly the
 * current server-side content instead of appending a duplicate replay.
 *
 * @see PtySocket
 * @see LunamuxClient.openPtySocket
 */
package se.soderbjorn.lunamux.client

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import se.soderbjorn.lunamux.PtyControl
import se.soderbjorn.lunamux.PtyServerMessage

/**
 * Live WebSocket connection to `/pty/{sessionId}`. See [PtySocket] for the
 * consumer contract; this class adds the Ktor WebSocket transport, delivering
 * the server's 128 kB ring-buffer replay as the first [PtyEvent.Bytes] on
 * [events] and a [PtyEvent.Reset] before each reconnect's replay.
 */
class RealPtySocket internal constructor(
    private val client: LunamuxClient,
    override val sessionId: String,
    private val initialGrid: StateFlow<Pair<Int, Int>?>? = null,
) : PtySocket {
    // One ordered stream of Bytes | Size | Reset. `replay = 64` lets a late
    // subscriber (a screen that composes after the socket connected) catch up
    // on recent events — the same window the old two-flow design replayed.
    private val _events = MutableSharedFlow<PtyEvent>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<PtyEvent> = _events.asSharedFlow()

    // Conflated mirror of the latest server size, for pure size observers only
    // (see PtySocket.ptySize); emulator feeders read PtyEvent.Size off [events]
    // so the resize stays ordered relative to the redraw bytes.
    private val _ptySize = MutableStateFlow<Pair<Int, Int>?>(null)
    override val ptySize: StateFlow<Pair<Int, Int>?> = _ptySize.asStateFlow()

    /** The current live session, or `null` while (re)connecting. */
    private val _activeSession = MutableStateFlow<DefaultClientWebSocketSession?>(null)

    private var runJob: Job? = null
    @Volatile private var closed = false

    /**
     * Wall-clock epoch-millis when output was last received (or the
     * connection last completed its handshake). Drives [reconnectIfStale];
     * unlike `/window` there is no periodic server push here, so an idle
     * shell looks "quiet" — callers use a small threshold meaning "refresh
     * unless actively streaming".
     *
     * Uses the wall clock ([Clock.System]) rather than a monotonic source on
     * purpose: monotonic clocks pause while the device is suspended, so a PTY
     * socket the OS killed during a long background would look freshly-quiet
     * on resume and [reconnectIfStale] would skip the reconnect that repaints
     * the terminal. See the matching note in RealWindowSocket.
     */
    @Volatile private var lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()

    /** Signalled by [reconnectIfStale] to cut a reconnect backoff short. */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val RECONNECT_BASE_MS = 500L
        private const val RECONNECT_MAX_MS = 15_000L

        /**
         * How many times to retry the *initial* connect (with backoff) before
         * giving up. The overview opens one PTY socket per visible terminal
         * miniature simultaneously, so a transient first-connect failure under
         * that burst is common; bounded retries recover from it while still
         * not hammering a genuinely unreachable/rejecting server forever.
         */
        private const val MAX_INITIAL_CONNECT_ATTEMPTS = 5
    }

    init {
        start()
    }

    private fun start() {
        runJob = client.scope.launch {
            var attempt = 0
            var everConnected = false
            while (!closed) {
                try {
                    // Declare the grid this client renders so the server synthesizes
                    // the attach redraw at our width. On the first connect the view may
                    // not have laid out yet (Android opens the socket early), so wait
                    // briefly for the first real grid; on reconnects the current value
                    // is already good. No grid → the server uses the PTY dims (correct
                    // for a passive viewer / thumbnail).
                    val grid: Pair<Int, Int>? = if (!everConnected) {
                        initialGrid?.let { flow -> withTimeoutOrNull(500) { flow.filterNotNull().first() } }
                    } else {
                        initialGrid?.value
                    }
                    val session = client.httpClient.webSocketSession(
                        // Viewer posture: this (mobile) client mirrors the desktop's PTY
                        // size and does not govern it until the user deliberately takes
                        // over. See PtyRoutes.readClientPosture / ptyConnectQuery.
                        client.wsUrlWithAuth("/pty/$sessionId") + ptyConnectQuery("viewer", grid)
                    )
                    _activeSession.value = session
                    lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()
                    attempt = 0
                    if (everConnected) {
                        // The server is about to replay the whole ring buffer
                        // again — tell the renderer to reset (clear + re-theme)
                        // before the replay instead of appending a duplicate.
                        _events.emit(PtyEvent.Reset)
                    }
                    everConnected = true
                    session.incoming.consumeEach { frame ->
                        lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()
                        when (frame) {
                            is Frame.Binary -> _events.emit(PtyEvent.Bytes(frame.readBytes()))
                            is Frame.Text -> runCatching {
                                val msg = client.json.decodeFromString<PtyServerMessage>(
                                    frame.readText()
                                )
                                when (msg) {
                                    is PtyServerMessage.Size -> {
                                        // Ordered on [events] for emulator feeders;
                                        // also mirrored into the conflated ptySize
                                        // for pure size observers.
                                        _events.emit(PtyEvent.Size(msg.cols, msg.rows))
                                        _ptySize.value = Pair(msg.cols, msg.rows)
                                    }
                                    is PtyServerMessage.Governance -> {
                                        // Ordered on [events] with the bytes it
                                        // qualifies: presentation must change
                                        // before the redraw authored for the new
                                        // driver arrives, not after it is painted.
                                        _events.emit(
                                            PtyEvent.Governance(msg.driving, msg.governed)
                                        )
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                    // consumeEach finished — server closed the connection.
                    _activeSession.value = null
                } catch (t: Throwable) {
                    _activeSession.value = null
                    if (!everConnected && attempt >= MAX_INITIAL_CONNECT_ATTEMPTS) {
                        // Never managed to connect after a bounded number of
                        // tries — stop instead of retrying into an
                        // unreachable/rejecting server forever.
                        println(
                            "PtySocket($sessionId): giving up after ${attempt + 1} initial " +
                                "connect attempts: ${t::class.simpleName}(${t.message ?: "no-message"})",
                        )
                        return@launch
                    }
                    // Otherwise fall through to the backoff below and retry —
                    // covers transient first-connect failures (e.g. the burst
                    // of miniature sockets the overview opens at once).
                }
                if (closed) break
                // Interruptible backoff before reconnecting (a retrySignal
                // from reconnectIfStale skips the wait on app resume).
                attempt++
                val backoffMs = (RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(5)))
                    .coerceAtMost(RECONNECT_MAX_MS)
                withTimeoutOrNull(backoffMs) { retrySignal.receive() }
            }
        }
    }

    /** Await the current live session (suspends across reconnects). */
    private suspend fun awaitSession(): DefaultClientWebSocketSession =
        _activeSession.value ?: _activeSession.filterNotNull().first()

    @Throws(CancellationException::class, Exception::class)
    override suspend fun send(bytes: ByteArray) {
        awaitSession().send(Frame.Binary(true, bytes))
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun resize(cols: Int, rows: Int) {
        val payload = client.json.encodeToString<PtyControl>(PtyControl.Resize(cols, rows))
        awaitSession().send(Frame.Text(payload))
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun forceResize(cols: Int, rows: Int) {
        val payload = client.json.encodeToString<PtyControl>(PtyControl.ForceResize(cols, rows))
        awaitSession().send(Frame.Text(payload))
    }

    override fun reconnectIfStale(maxQuietMillis: Long) {
        if (closed) return
        val quietMs = Clock.System.now().toEpochMilliseconds() - lastTrafficAtMillis
        // Leave an actively-streaming socket alone; a backward wall-clock step
        // (negative quiet) falls outside the range and is treated as stale so
        // we err toward a reconnect rather than a missed one.
        if (_activeSession.value != null && quietMs in 0 until maxQuietMillis) return
        println("PtySocket($sessionId): reconnectIfStale — quiet for ${quietMs}ms, kicking connection")
        retrySignal.trySend(Unit)
        client.scope.launch {
            runCatching { _activeSession.value?.cancel() }
        }
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun close() {
        closed = true
        runCatching { _activeSession.value?.close() }
        runJob?.cancel()
    }

    override fun closeDetached() {
        client.scope.launch { close() }
    }
}
