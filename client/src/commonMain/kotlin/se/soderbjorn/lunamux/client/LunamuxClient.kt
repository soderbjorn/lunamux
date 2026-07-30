/**
 * Core Lunamux client facade and networking entry point.
 *
 * [LunamuxClient] is the top-level object every platform creates at startup.
 * It owns the Ktor [HttpClient] (with WebSockets and timeout configuration),
 * the device auth token, client identity metadata, and the process-lifetime
 * [WindowStateRepository]. Factory methods [LunamuxClient.openWindowSocket]
 * and [LunamuxClient.openPtySocket] return hot socket wrappers for the two
 * live server endpoints.
 *
 * Ktor engine selection is automatic via Gradle per-target dependencies (OkHttp
 * on Android, CIO on JVM, JS fetch in the browser, Darwin on iOS).
 *
 * @see WindowSocket
 * @see PtySocket
 * @see ServerUrl
 */
package se.soderbjorn.lunamux.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.client.demo.DemoPtySocket
import se.soderbjorn.lunamux.client.demo.DemoServer
import se.soderbjorn.lunamux.client.demo.DemoWindowSocket
import se.soderbjorn.lunamux.client.demo.isDemoHost
import se.soderbjorn.lunamux.windowJson

/**
 * Top-level facade every client uses. Holds the server URL, the device auth
 * token, a long-lived Ktor `HttpClient` (with WebSockets), and the coroutine
 * scope that sockets live in. Factory methods [windowSocket] / [ptySocket]
 * return reusable wrappers for the two live endpoints.
 *
 * Ktor engine selection is done via Gradle dependencies per target:
 *   - :client/androidMain → OkHttp
 *   - :client/jsMain      → JS (fetch + browser WebSocket)
 *   - :client/jvmMain     → CIO (for :electron or tests)
 *   - :client/iosMain     → Darwin
 * `HttpClient { install(WebSockets) { ... } }` in common code picks up the
 * per-target engine automatically.
 */
/**
 * Self-reported metadata the client shares with the server so the new-device
 * approval dialog and the settings dialog can show which sort of client is
 * connecting (and from where it thinks it's coming — handy under NAT). All
 * fields are advisory: the server also records the observed TCP remote
 * address, which is what any real authorisation decision is based on.
 */
data class ClientIdentity(
    val type: String,
    val hostname: String? = null,
    val selfReportedIp: String? = null,
    /**
     * The client app's marketing version (e.g. `"1.5.0"`), used by the
     * server for capability gating — notably to decide whether this client
     * is new enough to be sent agent-console panes (added in 1.5). Released
     * clients before 1.5 never set this, so a `null`/absent value is the
     * server's signal that the client is too old for newer pane kinds.
     * Advisory and spoofable like the other identity fields.
     */
    val version: String? = null,
)

/**
 * Central client facade for communicating with a Lunamux server.
 *
 * Holds the [serverUrl], [authToken], and [identity] needed to authenticate
 * every HTTP and WebSocket request. Use [openWindowSocket] to subscribe to
 * window layout changes and [openPtySocket] to stream terminal I/O.
 *
 * The underlying [HttpClient] is built by [createPinnedHttpClient], which
 * installs a TOFU certificate-pinning trust manager on native targets.
 * If [pinnedFingerprintHex] is `null` the client runs in *capture mode* —
 * the leaf cert's SHA-256 is observed during the TLS handshake and surfaced
 * through [observedFingerprint] so the caller can persist it into the host
 * entry. If it is non-null the trust manager runs in *verify mode*,
 * constant-time comparing against the pin; on mismatch the JSSE-backed
 * targets throw `CertificateException("pin-mismatch: …")` (cause-chain
 * inspectable so the UI can show a re-pair dialog), and *every* native
 * target writes the observed hex to [observedMismatch] so platforms whose
 * engine swallows the rejection cause (iOS Darwin) still have a uniform
 * signal to branch on in their connect-failure catch.
 *
 * @param serverUrl the server endpoint descriptor.
 * @param authToken the base64url device-auth token (see [getOrCreateToken]).
 * @param identity  self-reported client metadata sent to the server.
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
 *   cert, or `null` to run capture mode on first connect.
 * @param pairingToken one-time pairing token from a scanned QR payload, sent
 *   alongside the auth token so the server can trust this device without an
 *   approval dialog. `null` (the default) outside the pairing flow. Safe to
 *   re-send after consumption: the server falls through to its trusted-device
 *   list, so reconnects don't need a token-free client.
 * @param scope     coroutine scope for socket reader loops; defaults to a
 *   [SupervisorJob] on [Dispatchers.Default].
 *
 * @see createLunamuxClient
 * @see createPinnedHttpClient
 * @see se.soderbjorn.termtastic.PairingPayload
 */
class LunamuxClient(
    val serverUrl: ServerUrl,
    val authToken: String,
    val identity: ClientIdentity,
    val pinnedFingerprintHex: String? = null,
    val pairingToken: String? = null,
    scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + uncaughtCoroutineLogger(),
    ),
) {
    internal val scope: CoroutineScope = scope

    /**
     * Whether this client runs against the in-process demo simulation
     * instead of a real server. Selected purely by the magic host name (see
     * [isDemoHost]): the web client passes it when the page is opened on
     * the `/demo` URL, and mobile users enter it as the host of a "server"
     * entry. In demo mode no network connection of any kind is made —
     * [openWindowSocket]/[openPtySocket] return the demo transports and the
     * Ktor [httpClient] is never constructed.
     */
    val demoMode: Boolean = isDemoHost(serverUrl.host)

    init {
        val mode = when {
            demoMode -> "demo"
            pinnedFingerprintHex == null -> "capture"
            else -> "verify(pin=${pinnedFingerprintHex.take(8)}…)"
        }
        println(
            "LunamuxClient: constructed for ${serverUrl.host}:${serverUrl.port} " +
                "mode=$mode identity=${identity.type}@${identity.hostname ?: "?"}",
        )
    }

    /**
     * Process-lifetime cache of the latest window config + per-session state
     * map. [WindowSocket] writes to this on every envelope; UI code reads
     * from it so that tearing down and rebuilding a screen always gets the
     * last-known snapshot instantly, independent of socket lifecycle.
     */
    val windowState: WindowStateRepository = WindowStateRepository()

    /**
     * The in-process server simulation backing demo mode, or `null` when
     * this client talks to a real server. Public because the web client's
     * raw-WebSocket terminal path (`connectPane`) attaches to demo sessions
     * directly instead of going through [PtySocket]. Declared after
     * [windowState] because the simulation seeds the repository with the
     * fixture config at construction time.
     */
    val demoServer: DemoServer? = if (demoMode) DemoServer(scope, windowState) else null

    /**
     * Shared serializer configuration — must stay byte-for-byte compatible
     * with what the server emits (see [windowJson] in :clientServer).
     */
    internal val json: Json = windowJson

    private val _observedFingerprint = MutableStateFlow<String?>(null)

    /**
     * The lowercase hex SHA-256 of the server's leaf certificate, observed
     * during the first successful TLS handshake when this client was
     * constructed without a pin. Stays `null` forever in verify mode (where
     * the pin is already known) and on JS (where the browser owns TLS).
     *
     * UI layers observe this so they can persist the captured pin into the
     * matching host entry once the server's `DeviceAuth.ApprovalDialog` has
     * approved the device.
     */
    val observedFingerprint: StateFlow<String?> = _observedFingerprint.asStateFlow()

    private val _observedMismatch = MutableStateFlow<String?>(null)

    /**
     * The lowercase hex SHA-256 of the server's leaf certificate observed
     * during a *failed* verify-mode handshake — i.e. the value the trust
     * callback computed before rejecting it for not matching
     * [pinnedFingerprintHex]. Stays `null` in capture mode, on success, and
     * on JS. Set just before the underlying engine surfaces the connection
     * failure, so UI layers can read it from the catch block to distinguish
     * a pin mismatch from an unrelated transport error (wrong port,
     * unreachable host, etc.) — particularly important on iOS where the
     * Darwin URLSessionDelegate cancels the challenge without a marker
     * NSError, leaving the catch site with an opaque generic failure.
     */
    val observedMismatch: StateFlow<String?> = _observedMismatch.asStateFlow()

    // Note: we don't install HttpCookies here because ws(s):// upgrades in
    // Ktor don't always thread cookies through all engines reliably. Instead,
    // every socket URL gets `?auth=<token>` appended — this is the same
    // belt-and-braces channel the server-side readAuthToken helper already
    // recognises (see Application.kt:readAuthToken).
    //
    // Lazy so demo-mode clients (which never make a network request of any
    // kind) skip constructing the engine entirely — important on JS where
    // the demo bundle may be served from a static host with no Lunamux
    // server behind it.
    private val httpClientLazy: Lazy<HttpClient> = lazy {
        createPinnedHttpClient(
            pinnedFingerprintHex = pinnedFingerprintHex,
            onPeerCertCaptured = { fp -> _observedFingerprint.value = fp },
            onPinMismatch = { hex -> _observedMismatch.value = hex },
        )
    }
    internal val httpClient: HttpClient get() = httpClientLazy.value

    /**
     * Open (or return) a websocket to `/window`. The returned [WindowSocket]
     * is hot: the `config` / `states` StateFlows start emitting as soon as the
     * server pushes the first envelopes. Call [WindowSocket.close] to tear
     * down the socket and its coroutine.
     *
     * In demo mode this returns a [DemoWindowSocket] backed by [demoServer]
     * instead; the handshake completes immediately and the fixture config is
     * already in [windowState].
     */
    fun openWindowSocket(): WindowSocket {
        demoServer?.let { return DemoWindowSocket(server = it, windowState = windowState) }
        return RealWindowSocket(client = this, path = "/window")
    }

    /**
     * Open a websocket to `/pty/{sessionId}`. The server synthesizes a
     * width-correct attach redraw as the first frames, then live frames.
     *
     * @param sessionId the PTY session to attach to.
     * @param initialGrid the grid this client renders, sampled onto the connect
     *   URL (`&cols=&rows=`) so the server synthesizes the redraw at that width.
     *   Null (or a null value) lets the server use the current PTY dims — correct
     *   for a passive viewer or a thumbnail. See [ptyConnectQuery].
     * @return the socket; a [DemoPtySocket] in demo mode (which ignores
     *   [initialGrid] — the demo is single-client and never passive).
     */
    fun openPtySocket(
        sessionId: String,
        initialGrid: kotlinx.coroutines.flow.StateFlow<Pair<Int, Int>?>? = null,
    ): PtySocket {
        demoServer?.let { return DemoPtySocket(sessionId = sessionId, server = it, scope = scope) }
        return RealPtySocket(client = this, sessionId = sessionId, initialGrid = initialGrid)
    }

    /**
     * URL helper that appends `?auth=<token>` for websocket endpoints. The
     * server's readAuthToken looks at cookie → query → header in that order,
     * so this wins even in environments where cookie jars don't cooperate
     * with upgrade requests.
     */
    internal fun wsUrlWithAuth(path: String): String {
        val sb = StringBuilder(serverUrl.wsUrl(path))
        sb.append("?auth=").append(urlEncode(authToken))
        sb.append("&clientType=").append(urlEncode(identity.type))
        identity.hostname?.takeIf { it.isNotBlank() }?.let {
            sb.append("&clientHost=").append(urlEncode(it))
        }
        identity.selfReportedIp?.takeIf { it.isNotBlank() }?.let {
            sb.append("&clientIp=").append(urlEncode(it))
        }
        identity.version?.takeIf { it.isNotBlank() }?.let {
            sb.append("&clientVersion=").append(urlEncode(it))
        }
        pairingToken?.takeIf { it.isNotBlank() }?.let {
            sb.append("&pairToken=").append(urlEncode(it))
        }
        return sb.toString()
    }

    /**
     * Headers to attach to REST requests so the server sees the same client
     * metadata it gets from WebSocket upgrades (which can't set headers).
     * Returned as a plain map so callers pass it through ktor's
     * [io.ktor.client.request.header] without pulling in HTTP types here.
     */
    internal fun clientInfoHeaders(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "X-Termtastic-Client-Type" to identity.type
        identity.hostname?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Client-Host" to it
        }
        identity.selfReportedIp?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Client-Ip" to it
        }
        identity.version?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Client-Version" to it
        }
        pairingToken?.takeIf { it.isNotBlank() }?.let {
            out += "X-Termtastic-Pair-Token" to it
        }
        return out
    }

    /**
     * Shut down the underlying Ktor [HttpClient], releasing connection pools
     * and any associated resources. After this call the client is no longer
     * usable. A no-op when the lazy client was never constructed (demo mode,
     * or a client torn down before its first request).
     */
    fun close() {
        if (httpClientLazy.isInitialized()) httpClient.close()
    }
}

/**
 * Minimal URL-encoder sufficient for the alphabetic client-info values we
 * pass as query params (token, client type, hostname, IP). Keeps us from
 * pulling a platform-specific URL util into `commonMain` just for this.
 */
private fun urlEncode(value: String): String {
    val sb = StringBuilder(value.length)
    for (c in value) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)
            else -> {
                val bytes = c.toString().encodeToByteArray()
                for (b in bytes) {
                    sb.append('%')
                    sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
                    sb.append(HEX[b.toInt() and 0x0f])
                }
            }
        }
    }
    return sb.toString()
}

private val HEX = "0123456789ABCDEF".toCharArray()

/**
 * Default [CoroutineExceptionHandler] for [LunamuxClient.scope]. Without
 * one, an uncaught throwable in any `client.scope.launch { … }` block
 * propagates to the Kotlin/Native runtime's terminator and aborts the
 * process via `pthread_kill(SIGABRT)` — visible in Xcode as a crash on
 * `com.apple.root.default-qos` with the deepest Kotlin frame inside
 * `JobSupport.notifyHandlers`. Logging here keeps the process alive long
 * enough for the catch site (Swift / Android UI) to surface a normal
 * error to the user instead of a process-wide crash.
 */
private fun uncaughtCoroutineLogger(): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        val causes = generateSequence(e as Throwable?) { it.cause }
            .joinToString(" -> ") {
                "${it::class.simpleName}(${it.message ?: "no-message"})"
            }
        println("LunamuxClient: uncaught in client.scope: $causes")
        println("LunamuxClient: stack:\n${e.stackTraceToString()}")
    }

/**
 * Factory for platforms (iOS) where Kotlin default parameters are not
 * exported. Creates a [LunamuxClient] with a default coroutine scope and
 * an optional pinned fingerprint for TOFU verify mode.
 *
 * @param serverUrl see [LunamuxClient].
 * @param authToken see [LunamuxClient].
 * @param identity see [LunamuxClient].
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
 *   cert, or `null` to run capture mode on first connect.
 * @param pairingToken one-time QR pairing token, or `null` outside pairing.
 */
fun createLunamuxClient(
    serverUrl: ServerUrl,
    authToken: String,
    identity: ClientIdentity,
    pinnedFingerprintHex: String? = null,
    pairingToken: String? = null,
): LunamuxClient = LunamuxClient(
    serverUrl = serverUrl,
    authToken = authToken,
    identity = identity,
    pinnedFingerprintHex = pinnedFingerprintHex,
    pairingToken = pairingToken,
)
