/**
 * Android (OkHttp) implementation of [createPinnedHttpClient] with TOFU
 * fingerprint pinning.
 *
 * Installs a custom [X509TrustManager] that operates in one of two modes
 * depending on whether a pin is provided:
 *
 *  - **Capture** (`pinnedFingerprintHex == null`): does not validate the cert
 *    chain (self-signed, no trust anchor on the device). Computes SHA-256 of
 *    the leaf cert's DER, latches it via an [AtomicBoolean] so the callback
 *    fires at most once, and dispatches [onPeerCertCaptured] off the network
 *    thread. The caller writes the value back to host storage (DataStore).
 *  - **Verify**: computes the leaf's SHA-256, constant-time compares against
 *    the pin. Mismatch throws a [CertificateException] whose message starts
 *    with `"pin-mismatch:"` so UI code can detect it via cause-chain
 *    inspection and surface the cert-changed dialog.
 *
 * OkHttp's built-in [okhttp3.CertificatePinner] is not suitable: it pins on
 * top of a successful default chain check, which we don't have for self-signed
 * certs.
 *
 * @see createPinnedHttpClient
 */
package se.soderbjorn.lunamux.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Build an Android [HttpClient] backed by OkHttp with TLS fingerprint pinning
 * installed via a custom [X509TrustManager]. See file-level KDoc for the
 * two-mode capture/verify contract.
 *
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the pin, or `null` for capture mode.
 * @param onPeerCertCaptured invoked at most once when the leaf is observed in capture mode.
 * @param onPinMismatch invoked at most once with the observed leaf hex when verify
 *   mode rejects a handshake — fires *before* the `pin-mismatch:`
 *   [CertificateException] is thrown so callers that prefer the cross-platform
 *   side-channel (a `StateFlow` write) can use it instead of cause-chain matching.
 * @return a configured [HttpClient]; caller closes.
 */
actual fun createPinnedHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
    onPinMismatch: (observedHex: String) -> Unit,
): HttpClient = HttpClient(OkHttp) {
    applyCommonClientConfig(this)
    engine {
        config {
            // Raise OkHttp's concurrent-call limits. Each open WebSocket counts
            // as a "running" call that never completes, and the overview opens
            // one PTY socket per visible terminal miniature on top of the
            // /window socket. OkHttp's default Dispatcher caps concurrent calls
            // per host at 5, so without this the extra miniature sockets queue
            // indefinitely (the connect suspends rather than failing) and those
            // panes stay blank until another socket is torn down.
            dispatcher(
                Dispatcher().apply {
                    maxRequests = 128
                    maxRequestsPerHost = 64
                },
            )
            val tm = LunamuxPinningTrustManager(
                pinnedFingerprintHex = pinnedFingerprintHex,
                onPeerCertCaptured = onPeerCertCaptured,
                onPinMismatch = onPinMismatch,
            )
            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<X509TrustManager>(tm), SecureRandom())
            }
            sslSocketFactory(ctx.socketFactory, tm)
            // Pinning supersedes hostname identity for self-signed certs
            // (the SAN list on the server side is for browser convenience).
            hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }
}

/**
 * Two-mode trust manager. Verify when [pinnedFingerprintHex] is non-null;
 * capture and emit the observed leaf fingerprint via [onPeerCertCaptured]
 * (once per client lifetime) when null.
 */
private class LunamuxPinningTrustManager(
    private val pinnedFingerprintHex: String?,
    private val onPeerCertCaptured: (String) -> Unit,
    private val onPinMismatch: (String) -> Unit,
) : X509TrustManager {

    private val captured = AtomicBoolean(false)
    private val mismatchSignalled = AtomicBoolean(false)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Server-side authentication only — clients do not present certs.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain")
        }
        val leafFingerprintHex = sha256Hex(chain[0].encoded)
        val pin = pinnedFingerprintHex
        if (pin == null) {
            if (captured.compareAndSet(false, true)) {
                // Hop off the TLS handshake thread: storage writes (DataStore
                // for hosts, etc.) must not block the network handshake.
                Thread {
                    runCatching { onPeerCertCaptured(leafFingerprintHex) }
                }.apply { isDaemon = true; name = "lunamux-tofu-capture" }.start()
            }
            return
        }
        val pinBytes = pin.lowercase().toByteArray(Charsets.US_ASCII)
        val leafBytes = leafFingerprintHex.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(pinBytes, leafBytes)) {
            // Side-channel signal for callers that don't want to walk the
            // exception cause chain. The callback is expected to be O(1)
            // (StateFlow write); fire it synchronously so the value is
            // visible by the time OkHttp propagates the throw.
            if (mismatchSignalled.compareAndSet(false, true)) {
                runCatching { onPinMismatch(leafFingerprintHex) }
            }
            throw CertificateException(
                "pin-mismatch: server presented $leafFingerprintHex but client pinned $pin"
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
