/**
 * WebSocket-backed [WindowSocket] implementation for the `/window` endpoint,
 * exposing the server's authoritative window layout ([WindowConfig]) and
 * per-session state map as hot [kotlinx.coroutines.flow.StateFlow]s.
 *
 * The socket includes automatic reconnection with exponential backoff. The
 * actual state storage lives in [WindowStateRepository] (held by
 * [TermtasticClient]) so that data survives reconnects.
 *
 * @see WindowSocket
 * @see TermtasticClient.openWindowSocket
 * @see WindowStateRepository
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope

/**
 * Live WebSocket connection to `/window`. See [WindowSocket] for the contract.
 *
 * The socket opens lazily on the first subscription and stays live until
 * [close] is called or the owning [TermtasticClient.scope] is cancelled.
 * The git/file-browser round-trips come from the [WindowSocket] default
 * implementations (they only need [send] + [envelopes]).
 */
class RealWindowSocket internal constructor(
    private val client: TermtasticClient,
    private val path: String,
) : WindowSocket {
    /**
     * Forwarded from [TermtasticClient.windowState] so existing call sites
     * (`socket.config` / `socket.states`) keep compiling. The actual storage
     * lives on the client, so these flows survive socket reconnects.
     */
    override val config: StateFlow<WindowConfig?> get() = client.windowState.config
    override val states: StateFlow<Map<String, String?>> get() = client.windowState.states

    private val _envelopes = MutableSharedFlow<WindowEnvelope>(extraBufferCapacity = 64)
    override val envelopes = _envelopes.asSharedFlow()

    private val sessionReady = CompletableDeferred<DefaultClientWebSocketSession>()

    /**
     * Completes when the socket's run loop exits for the first time, whether
     * cleanly (server closed the WebSocket with a close frame) or
     * exceptionally. Used by [awaitInitialConfig] to detect a post-handshake
     * rejection — the server completes the WS upgrade, then immediately
     * closes because [DeviceAuth] returned REJECTED/HEADLESS, and without
     * this the client would sit at "Connecting…" forever waiting for a Config
     * envelope that will never arrive.
     */
    private val sessionClosed = CompletableDeferred<Unit>()

    /** The current live session, if any. Used by [send]. */
    private val _activeSession = MutableStateFlow<DefaultClientWebSocketSession?>(null)

    /**
     * Whether the underlying WebSocket is currently connected.
     *
     * Emits `true` when a session handshake completes and `false` when the
     * connection drops. UI layers can collect this to show/hide a
     * disconnected overlay without relying on per-PTY connection states.
     *
     * @see _activeSession
     */
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> get() = _connected

    private var runJob: Job? = null
    @Volatile private var closed = false

    /**
     * Wall-clock epoch-millis when the last frame was received (or the
     * connection last completed its handshake). Drives [reconnectIfStale]'s
     * zombie detection — the server pushes a session-state envelope every
     * ~3 s, so a healthy `/window` channel is never quiet for long.
     *
     * This deliberately uses the wall clock ([Clock.System]) rather than a
     * monotonic time source: monotonic clocks (`mach_absolute_time` on iOS,
     * `CLOCK_MONOTONIC` on Android) pause while the device is suspended, so a
     * socket the OS killed during a long background would look freshly-quiet
     * on resume and [reconnectIfStale] would wrongly skip the reconnect. The
     * wall clock counts suspended time, so the quiet window reflects real
     * elapsed time. A rare NTP step is harmless here — the worst case is one
     * unnecessary (or briefly deferred) reconnect.
     */
    @Volatile private var lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()

    /**
     * Signalled by [reconnectIfStale] to cut any reconnect backoff short so
     * a resume-triggered reconnect happens immediately rather than after up
     * to [RECONNECT_MAX_MS].
     */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }

    init {
        start()
    }

    private fun start() {
        // Capture the URL once outside the try so the catch block doesn't
        // re-dereference `client` after Swift may have already torn it down
        // in its connect-failure cleanup.
        val url = client.wsUrlWithAuth(path)
        runJob = client.scope.launch {
            var attempt = 0
            while (!closed) {
                try {
                    println("WindowSocket: opening $url (attempt ${attempt + 1})")
                    val session = client.httpClient.webSocketSession(url)
                    println("WindowSocket: handshake complete for $url")
                    _activeSession.value = session
                    _connected.value = true
                    lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()
                    if (!sessionReady.isCompleted) sessionReady.complete(session)
                    attempt = 0 // reset backoff on successful connection
                    session.incoming.consumeEach { frame ->
                        lastTrafficAtMillis = Clock.System.now().toEpochMilliseconds()
                        if (frame !is Frame.Text) return@consumeEach
                        val envelope = runCatching {
                            client.json.decodeFromString<WindowEnvelope>(frame.readText())
                        }.getOrNull() ?: return@consumeEach
                        when (envelope) {
                            is WindowEnvelope.Config -> client.windowState.updateConfig(envelope.config)
                            is WindowEnvelope.State -> client.windowState.updateStates(envelope.states)
                            is WindowEnvelope.PendingApproval -> client.windowState.setPendingApproval()
                            // Carries the merged flat-KV blob (incl. the
                            // toolkit's LAYOUT_STATE) — pull the minimized
                            // pane set out so the mobile sessions list can dim
                            // docked panes, live with web minimize/restore.
                            is WindowEnvelope.UiSettings -> client.windowState.updateUiSettings(envelope.settings)
                            is WindowEnvelope.FileBrowserDir,
                            is WindowEnvelope.FileBrowserContentMsg,
                            is WindowEnvelope.FileBrowserError,
                            is WindowEnvelope.ClaudeUsage,
                            is WindowEnvelope.GitList,
                            is WindowEnvelope.GitDiffResult,
                            is WindowEnvelope.GitError,
                            is WindowEnvelope.WorktreeDefaults,
                            is WindowEnvelope.WorktreeCreated,
                            is WindowEnvelope.WorktreeError,
                            // Agent notifications only need the passthrough
                            // emit below; platform UIs collect [envelopes]
                            // and render their own toast/banner.
                            is WindowEnvelope.AgentNotify -> Unit
                        }
                        _envelopes.emit(envelope)
                    }
                    // consumeEach finished — server closed the connection
                    println("WindowSocket: server closed connection")
                    _activeSession.value = null
                    _connected.value = false
                } catch (t: Throwable) {
                    _activeSession.value = null
                    _connected.value = false
                    val causes = generateSequence(t as Throwable?) { it.cause }
                        .joinToString(" -> ") {
                            "${it::class.simpleName}(${it.message ?: "no-message"})"
                        }
                    println("WindowSocket: connection to $url failed: $causes")
                    println("WindowSocket: stack trace:\n${t.stackTraceToString()}")
                    if (!sessionReady.isCompleted) {
                        // First connection failed — propagate to awaitInitialConfig.
                        // sessionClosed is completed by the finally block; do not
                        // double-complete here.
                        sessionReady.completeExceptionally(t)
                        return@launch
                    }
                } finally {
                    if (!sessionClosed.isCompleted) sessionClosed.complete(Unit)
                }
                if (closed) break
                // Exponential backoff before reconnecting. The wait is
                // interruptible: a retrySignal (sent by reconnectIfStale on
                // app resume) cuts it short so the user never stares at a
                // stale screen while a 30 s backoff plays out.
                attempt++
                val backoffMs = (RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                    .coerceAtMost(RECONNECT_MAX_MS)
                println("WindowSocket: reconnecting in ${backoffMs}ms")
                withTimeoutOrNull(backoffMs) { retrySignal.receive() }
            }
        }
    }

    override fun reconnectIfStale(maxQuietMillis: Long) {
        if (closed) return
        val quietMs = Clock.System.now().toEpochMilliseconds() - lastTrafficAtMillis
        // A healthy socket that has had traffic within the window is left
        // alone (quick app switches are free). `quietMs !in 0..<maxQuietMillis`
        // also covers a backward wall-clock step (negative quiet) by treating
        // it as stale — the safe direction is an extra reconnect, not a
        // skipped one.
        if (_connected.value && quietMs in 0 until maxQuietMillis) return
        println("WindowSocket: reconnectIfStale — quiet for ${quietMs}ms, kicking connection")
        // Skip any backoff in progress, then kill the current session (a
        // zombie socket never errors on its own — cancel() works even when
        // the underlying TCP connection is already gone). The run loop's
        // consumeEach ends, and it reconnects immediately.
        retrySignal.trySend(Unit)
        client.scope.launch {
            runCatching { _activeSession.value?.cancel() }
        }
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun awaitInitialConfig() {
        sessionReady.await()
        if (client.windowState.config.value != null) return
        coroutineScope {
            val configDeferred = async {
                client.windowState.config.filter { it != null }.first()
                Unit
            }
            val closedDeferred = async { sessionClosed.await() }
            select<Unit> {
                configDeferred.onAwait { }
                closedDeferred.onAwait { }
            }
            configDeferred.cancel()
            closedDeferred.cancel()
        }
        if (client.windowState.config.value == null) {
            throw IllegalStateException(
                "Server closed the /window socket before sending a config — " +
                    "likely device-auth rejection. Check the server's log for " +
                    "\"rejecting non-loopback\" or \"headless\".",
            )
        }
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun awaitSessionReady() {
        sessionReady.await()
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun send(command: WindowCommand) {
        val session = _activeSession.value ?: sessionReady.await()
        val payload = client.json.encodeToString<WindowCommand>(command)
        session.send(Frame.Text(payload))
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun close() {
        closed = true
        runCatching { _activeSession.value?.close() }
        runJob?.cancel()
    }
}
