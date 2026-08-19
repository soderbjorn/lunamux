package se.soderbjorn.lunamux.android.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.android.BuildConfig
import se.soderbjorn.lunamux.android.ui.MiniTerminalRegistry
import se.soderbjorn.lunamux.client.CandidateConnection
import se.soderbjorn.lunamux.client.CandidateConnector
import se.soderbjorn.lunamux.client.ClientIdentity
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunamux.client.WindowSocket
import se.soderbjorn.lunamux.client.demo.isDemoHost
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Process-scoped holder for the live [LunamuxClient]. The app is tiny
 * enough that a plain singleton is cleaner than pulling in Hilt or Koin.
 * [LunamuxApp] rebuilds this whenever the user commits a new host/port
 * from the Connect screen.
 */
object ConnectionHolder {
    @Volatile
    private var currentClient: LunamuxClient? = null

    @Volatile
    private var currentWindowSocket: WindowSocket? = null

    /**
     * Returns the currently connected [LunamuxClient], or null if disconnected.
     *
     * @return the active client instance, or null.
     */
    fun client(): LunamuxClient? = currentClient

    /**
     * Returns the open [WindowSocket] for the current connection, or null if disconnected.
     *
     * @return the active window socket, or null.
     */
    fun windowSocket(): WindowSocket? = currentWindowSocket

    /**
     * Reflects whether the server is currently showing a device-approval
     * dialog for this connection. UI can observe this to show
     * "Waiting for approval…" instead of a bare spinner.
     */
    val pendingApproval: StateFlow<Boolean>?
        get() = currentClient?.windowState?.pendingApproval

    /**
     * Try every address of a host entry in order and keep the first that
     * connects. Used by the hosts screen for saved entries — QR-paired entries
     * carry several addresses, and even manually-added ones benefit from the
     * same code path.
     *
     * Phase 1 (per address, inside [CandidateConnector]): WebSocket handshake
     * with a 12 s budget. Phase 2 (winner only): wait for the initial Config
     * envelope, up to 5 min so a server-side approval dialog can be answered.
     * The current client is published before phase 2 so [pendingApproval] is
     * observable while waiting.
     *
     * @param addresses ordered endpoints to try — typically a host entry's
     *   [se.soderbjorn.lunamux.client.HostEntry.addresses] verbatim.
     * @param authToken the device-auth token.
     * @param pinnedFingerprintHex TLS pin (verify mode), or `null` for TOFU
     *   capture on first connect.
     * @param pairingToken one-time QR pairing token, or `null` outside the
     *   pairing flow.
     * @param onAttempt invoked with each endpoint just before it is tried, so
     *   the UI can name what it is waiting on. Passes the endpoint rather than
     *   a formatted string: the hosts screen matches it against the walk to
     *   show "address 2 of 3", which a string would force it to re-parse.
     * @return the winning [CandidateConnection]; the caller promotes its
     *   endpoint to the head of the entry's address list.
     * @throws Throwable when every address fails — pin-mismatch failures
     *   take precedence (see [CandidateConnector.connectFirstReachable]).
     */
    suspend fun connectMulti(
        addresses: List<HostPort>,
        authToken: String,
        pinnedFingerprintHex: String? = null,
        pairingToken: String? = null,
        onAttempt: (HostPort) -> Unit = {},
    ): CandidateConnection {
        disconnect()
        Log.i("ConnectionHolder", "connectMulti: ${addresses.size} address(es)")
        // Phase 1 — reach an address. Every failure is already typed by the
        // shared connector: a pin mismatch propagates for the cert-changed
        // dialog, and an unreachable walk arrives as ServerUnreachableException.
        // Nothing is re-wrapped here — doing so was what forced the hosts screen
        // to re-derive, in Android code, a fact shared code had established.
        val connection = CandidateConnector.connectFirstReachable(
            endpoints = addresses,
            authToken = authToken,
            // Real hosts only — the demo never reaches connectMulti.
            identity = androidIdentity(demo = false),
            pinnedFingerprintHex = pinnedFingerprintHex,
            pairingToken = pairingToken,
            onAttempt = onAttempt,
        )
        // Publish before the config wait so the approval-pending flow is
        // observable while the server-side dialog (if any) is up.
        currentClient = connection.client
        currentWindowSocket = connection.windowSocket
        try {
            withTimeout(300_000) {
                connection.windowSocket.awaitInitialConfig()
            }
        } catch (t: Throwable) {
            Log.w("ConnectionHolder", "connectMulti failed awaiting config", t)
            disconnect()
            throw t
        }
        return connection
    }

    /**
     * Self-reported identity for this device. Android devices report type
     * "Android" so the settings UI can tell them apart from iOS and browser
     * tabs, and the running app version so the server can gate newer pane
     * kinds (agent consoles, 1.5+) to clients that can render them. The
     * hostname + first non-loopback IPv4 lookups are best-effort and
     * advisory; a failure just means blanks.
     *
     * Runs on [Dispatchers.IO]: `getLocalHost()` does a reverse-DNS lookup
     * that can block for seconds on a bad resolver, and connect callers run
     * on the main dispatcher — resolving it here keeps that off the UI thread.
     *
     * In demo mode both lookups are skipped: the demo runs against the
     * in-process `DemoServer` and opens no socket, so there is no server to
     * report an identity to and no reason to make the user wait on a resolver
     * that may block for seconds. Mirrors the iOS `ConnectionHolder.identity`,
     * where skipping the same pair of lookups additionally avoids tripping the
     * local-network permission alert.
     *
     * @param demo whether this connect targets the built-in demo.
     * @return the identity to hand to the shared client.
     */
    private suspend fun androidIdentity(demo: Boolean): ClientIdentity = withContext(Dispatchers.IO) {
        ClientIdentity(
            type = "Android",
            hostname = if (demo) null else runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
            selfReportedIp = if (demo) null else runCatching { firstNonLoopbackIpv4() }.getOrNull(),
            version = BuildConfig.VERSION_NAME,
        )
    }

    /**
     * Tears down any existing client and creates a fresh one for [serverUrl].
     *
     * Retained for the built-in demo path only (see
     * [se.soderbjorn.lunamux.android.ui.HostsScreen]'s demo footer);
     * real hosts go through [connectMulti]. Performs the same two-phase
     * handshake: WebSocket session (15 s), then the first Config envelope
     * (up to 5 min for a device-approval dialog).
     *
     * @param serverUrl the server URL containing host and port.
     * @param authToken the authentication token for this client.
     * @param pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
     *   cert (verify mode), or `null` to run TOFU capture on first connect.
     *   The caller can read `client().observedFingerprint.value` afterwards
     *   to persist the captured pin.
     * @return the connected [LunamuxClient] instance.
     * @throws Throwable if the connection or handshake fails. A
     *   `javax.net.ssl.SSLHandshakeException` whose cause chain contains a
     *   `CertificateException` starting with `"pin-mismatch:"` indicates the
     *   server's cert no longer matches the stored pin; UI should surface
     *   the cert-changed dialog (see [isPinMismatch]).
     * @see connectMulti
     */
    suspend fun connect(
        serverUrl: ServerUrl,
        authToken: String,
        pinnedFingerprintHex: String? = null,
    ): LunamuxClient {
        disconnect()
        Log.i("ConnectionHolder", "connect: ${serverUrl.wsUrl("/window")}")
        val fresh = LunamuxClient(
            serverUrl = serverUrl,
            authToken = authToken,
            identity = androidIdentity(demo = isDemoHost(serverUrl.host)),
            pinnedFingerprintHex = pinnedFingerprintHex,
        )
        val socket = fresh.openWindowSocket()
        try {
            // Phase 1: WebSocket handshake — 15 s is plenty.
            withTimeout(15_000) {
                socket.awaitSessionReady()
            }
            // Phase 2: wait for the first Config envelope. When device
            // approval is pending, the server-side dialog can take minutes
            // to be answered, so use a generous timeout. The client
            // receives a PendingApproval envelope immediately (observable
            // via [pendingApproval]) so the UI can show feedback.
            withTimeout(300_000) {
                socket.awaitInitialConfig()
            }
        } catch (t: Throwable) {
            Log.w("ConnectionHolder", "connect failed", t)
            runCatching { socket.close() }
            runCatching { fresh.close() }
            throw t
        }
        currentClient = fresh
        currentWindowSocket = socket
        return fresh
    }

    /**
     * Scans all network interfaces for the first non-loopback, non-link-local
     * IPv4 address to use as the advisory self-reported IP in the client identity.
     *
     * @return the IP address string, or null if none found.
     */
    private fun firstNonLoopbackIpv4(): String? {
        val nics = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in nics) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    /**
     * App-resume hook: if the `/window` socket has been quiet long enough
     * to be presumed dead (the OS silently kills TCP connections while the
     * phone sleeps; the read loop never errors), force an immediate
     * reconnect so the tree/screens refresh with the server's current
     * state. No-op when disconnected or when the connection is healthy —
     * the server pushes session states every ~3 s, so a healthy socket is
     * never quiet for [WINDOW_STALE_MS].
     *
     * Called from [se.soderbjorn.lunamux.android.ui.LunamuxApp]'s
     * lifecycle observer on every ON_START.
     */
    fun refreshAfterResume() {
        currentWindowSocket?.reconnectIfStale(WINDOW_STALE_MS)
    }

    /** Quiet threshold for [refreshAfterResume]; see the 3 s state poller. */
    private const val WINDOW_STALE_MS = 8_000L

    /**
     * Closes the current window socket and client, resetting both to null.
     * Safe to call even when already disconnected.
     */
    suspend fun disconnect() {
        currentWindowSocket?.close()
        currentWindowSocket = null
        currentClient?.close()
        currentClient = null
        // The thumbnail frame cache outlives any registry (it is process-wide,
        // so returning to the overview paints instantly), which means leaving a
        // host would otherwise keep its rendered screens in memory and seed the
        // next host's overview from them.
        MiniTerminalRegistry.clearFrameCache()
    }

    /**
     * Walk a throwable's cause chain looking for the `"pin-mismatch:"` marker
     * thrown by [se.soderbjorn.lunamux.client.createPinnedHttpClient] when
     * the server's leaf cert no longer matches the stored pin. Used by
     * [se.soderbjorn.lunamux.android.ui.HostsScreen] to decide between
     * showing a generic error snackbar and a cert-changed re-pair dialog.
     *
     * The scan itself now lives in the shared [CandidateConnector] (so iOS
     * gets it too); this delegate is kept for existing call sites.
     *
     * @param t the throwable to inspect (typically the failure surfaced from
     *   [connect] / [connectMulti]).
     * @return `true` if any link in the cause chain is a pin-mismatch.
     */
    fun isPinMismatch(t: Throwable): Boolean = CandidateConnector.isPinMismatch(t)
}
