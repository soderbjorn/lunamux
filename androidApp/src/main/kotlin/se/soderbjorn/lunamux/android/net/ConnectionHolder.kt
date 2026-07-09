package se.soderbjorn.lunamux.android.net

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import se.soderbjorn.lunamux.android.BuildConfig
import se.soderbjorn.lunamux.client.ClientIdentity
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunamux.client.WindowSocket
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
     * Tears down any existing client and creates a fresh one for [serverUrl].
     *
     * Called from [se.soderbjorn.lunamux.android.ui.HostsScreen] when the
     * user taps a host to connect. Performs a two-phase handshake: first the
     * WebSocket session (15 s timeout), then waits for the server's initial
     * Config envelope (up to 5 min to allow for device-approval dialogs).
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
            // Android devices report type "Android" so the settings UI can tell
            // them apart from iOS and browser tabs, and the running app version
            // so the server can gate newer pane kinds (agent consoles, 1.5+) to
            // clients that can render them. We do a best-effort hostname + first
            // non-loopback IPv4 lookup here; both are advisory so a failure just
            // means blanks.
            identity = ClientIdentity(
                type = "Android",
                hostname = runCatching { InetAddress.getLocalHost().hostName }.getOrNull(),
                selfReportedIp = runCatching { firstNonLoopbackIpv4() }.getOrNull(),
                version = BuildConfig.VERSION_NAME,
            ),
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
    }

    /**
     * Walk a throwable's cause chain looking for the `"pin-mismatch:"` marker
     * thrown by [se.soderbjorn.lunamux.client.createPinnedHttpClient] when
     * the server's leaf cert no longer matches the stored pin. Used by
     * [se.soderbjorn.lunamux.android.ui.HostsScreen] to decide between
     * showing a generic error snackbar and a cert-changed re-pair dialog.
     *
     * @param t the throwable to inspect (typically the failure surfaced from
     *   [connect]).
     * @return `true` if any link in the cause chain is a pin-mismatch.
     */
    fun isPinMismatch(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 16) {
            val msg = c.message
            if (msg != null && msg.startsWith("pin-mismatch:")) return true
            c = c.cause
            depth++
        }
        return false
    }
}
