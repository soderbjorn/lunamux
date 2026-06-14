/**
 * JVM (CIO) implementation of [createPinnedHttpClient] with TOFU fingerprint
 * pinning.
 *
 * Same two-mode behaviour as the Android actual — a custom
 * [javax.net.ssl.X509TrustManager] either captures the observed fingerprint
 * on first connect (`pinnedFingerprintHex == null`) or verifies the leaf cert
 * against the pin (and throws a `pin-mismatch:` [CertificateException] on
 * mismatch).
 *
 * Used by the Electron bundle (which runs on the JVM via Kotlin/JS only for
 * its main process — the in-app HTTP client lives on the renderer side
 * through `:web`) and by any future Compose Desktop client. It intentionally
 * does not delegate to the OS trust store, since Termtastic talks to
 * self-signed servers exclusively in pinned mode.
 *
 * @see createPinnedHttpClient
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager

/**
 * Build a JVM [HttpClient] backed by Ktor CIO with TLS fingerprint pinning.
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
): HttpClient = HttpClient(CIO) {
    applyCommonClientConfig(this)
    engine {
        https {
            trustManager = JvmPinningTrustManager(
                pinnedFingerprintHex = pinnedFingerprintHex,
                onPeerCertCaptured = onPeerCertCaptured,
                onPinMismatch = onPinMismatch,
            )
        }
    }
}

/**
 * Two-mode JVM trust manager — see the Android counterpart for the full
 * commentary; the logic is identical because both targets run on JSSE.
 */
private class JvmPinningTrustManager(
    private val pinnedFingerprintHex: String?,
    private val onPeerCertCaptured: (String) -> Unit,
    private val onPinMismatch: (String) -> Unit,
) : X509TrustManager {

    private val captured = AtomicBoolean(false)
    private val mismatchSignalled = AtomicBoolean(false)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Server-side auth only.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Empty server certificate chain")
        }
        val leafFingerprintHex = sha256Hex(chain[0].encoded)
        val pin = pinnedFingerprintHex
        if (pin == null) {
            if (captured.compareAndSet(false, true)) {
                Thread {
                    runCatching { onPeerCertCaptured(leafFingerprintHex) }
                }.apply { isDaemon = true; name = "termtastic-tofu-capture-jvm" }.start()
            }
            return
        }
        val pinBytes = pin.lowercase().toByteArray(Charsets.US_ASCII)
        val leafBytes = leafFingerprintHex.toByteArray(Charsets.US_ASCII)
        if (!MessageDigest.isEqual(pinBytes, leafBytes)) {
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
