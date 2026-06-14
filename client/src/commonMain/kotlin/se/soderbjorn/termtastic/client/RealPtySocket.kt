/**
 * WebSocket-backed [PtySocket] implementation for the `/pty/{sessionId}`
 * endpoint, providing the live bidirectional byte stream between the
 * client's terminal renderer and the server-side PTY process.
 *
 * Includes automatic reconnection: when the connection drops (network blip,
 * app backgrounded long enough for the OS to kill the socket), the socket
 * reconnects with backoff. Because the server replays the session's full
 * ring buffer on every attach, each *re*-connect is prefixed with a full
 * terminal reset (`ESC c`) on [output] so the renderer repaints exactly the
 * current server-side content instead of appending a duplicate replay.
 *
 * @see PtySocket
 * @see TermtasticClient.openPtySocket
 */
package se.soderbjorn.termtastic.client

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
import kotlin.time.TimeSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import se.soderbjorn.termtastic.PtyControl
import se.soderbjorn.termtastic.PtyServerMessage

/**
 * Live WebSocket connection to `/pty/{sessionId}`. See [PtySocket] for the
 * consumer contract; this class adds the Ktor WebSocket transport, including
 * the server's 64 KB ring-buffer replay as the first frames on [output] and
 * reconnect-with-reset on connection loss.
 */
class RealPtySocket internal constructor(
    private val client: TermtasticClient,
    override val sessionId: String,
) : PtySocket {
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    override val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    // Latest authoritative PTY size as reported by the server's
    // PtyServerMessage.Size frames. Null until the first frame arrives.
    private val _ptySize = MutableStateFlow<Pair<Int, Int>?>(null)
    override val ptySize: StateFlow<Pair<Int, Int>?> = _ptySize.asStateFlow()

    /** The current live session, or `null` while (re)connecting. */
    private val _activeSession = MutableStateFlow<DefaultClientWebSocketSession?>(null)

    private var runJob: Job? = null
    @Volatile private var closed = false

    /**
     * When output was last received (or the connection last completed its
     * handshake). Drives [reconnectIfStale]; unlike `/window` there is no
     * periodic server push here, so an idle shell looks "quiet" — callers
     * use a small threshold meaning "refresh unless actively streaming".
     */
    @Volatile private var lastTrafficMark = TimeSource.Monotonic.markNow()

    /** Signalled by [reconnectIfStale] to cut a reconnect backoff short. */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val RECONNECT_BASE_MS = 500L
        private const val RECONNECT_MAX_MS = 15_000L

        /**
         * Full terminal reset (RIS), emitted on [output] before a
         * reconnect's ring-buffer replay so renderers clear the stale
         * content first instead of appending a duplicate transcript.
         */
        private val TERMINAL_RESET = "\u001bc".encodeToByteArray()
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
                    val session = client.httpClient.webSocketSession(
                        client.wsUrlWithAuth("/pty/$sessionId")
                    )
                    _activeSession.value = session
                    lastTrafficMark = TimeSource.Monotonic.markNow()
                    attempt = 0
                    if (everConnected) {
                        // The server is about to replay the whole ring
                        // buffer again — wipe the renderer first.
                        _output.emit(TERMINAL_RESET)
                    }
                    everConnected = true
                    session.incoming.consumeEach { frame ->
                        lastTrafficMark = TimeSource.Monotonic.markNow()
                        when (frame) {
                            is Frame.Binary -> _output.emit(frame.readBytes())
                            is Frame.Text -> runCatching {
                                val msg = client.json.decodeFromString<PtyServerMessage>(
                                    frame.readText()
                                )
                                when (msg) {
                                    is PtyServerMessage.Size ->
                                        _ptySize.value = Pair(msg.cols, msg.rows)
                                }
                            }
                            else -> Unit
                        }
                    }
                    // consumeEach finished — server closed the connection.
                    _activeSession.value = null
                } catch (t: Throwable) {
                    _activeSession.value = null
                    if (!everConnected) {
                        // Never managed to connect at all — keep the legacy
                        // single-shot failure mode instead of retrying into
                        // an unreachable/rejecting server forever.
                        println(
                            "PtySocket($sessionId): initial connect failed: " +
                                "${t::class.simpleName}(${t.message ?: "no-message"})",
                        )
                        return@launch
                    }
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
        val quietMs = lastTrafficMark.elapsedNow().inWholeMilliseconds
        if (_activeSession.value != null && quietMs < maxQuietMillis) return
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
