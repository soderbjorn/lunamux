/**
 * iOS (Darwin) implementation of [createPinnedHttpClient] with TOFU
 * fingerprint pinning.
 *
 * Hooks Ktor-Darwin's `engine { handleChallenge { ... } }` block. When iOS's
 * URL loading system raises the server-trust challenge, the handler:
 *
 *  - Extracts the leaf certificate via `SecTrustGetCertificateAtIndex(trust, 0)`.
 *  - Computes its SHA-256 via `CC_SHA256` from CommonCrypto.
 *  - In capture mode (no pin): emits the fingerprint via [onPeerCertCaptured]
 *    off the network thread (`dispatch_async` to a global queue), then accepts
 *    the cert with `challenge.proposedCredential`.
 *  - In verify mode (pin set): constant-time compares against the pin and
 *    either accepts the credential (match) or cancels the challenge (mismatch).
 *    On mismatch the handler invokes [onPinMismatch] with the observed leaf
 *    hex synchronously, before the cancel — Darwin's URL loading system
 *    surfaces the failure as an opaque `NSURLError` to Swift, so callers
 *    rely on the side-channel (a `MutableStateFlow` write inside
 *    [LunamuxClient]) to tell a pin mismatch apart from any other
 *    connection failure.
 *
 * The bridge to `NSURLProtectionSpace.serverTrust` and the disposition
 * constants goes through the tiny ObjC cinterop shim under
 * `src/nativeInterop/cinterop/lunamuxTls.def`. See the shim's KDoc for
 * why a shim is needed (Kotlin/Native 2.3.20 binding gap).
 *
 * @see createPinnedHttpClient
 */
package se.soderbjorn.lunamux.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSLog
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount
import platform.Security.SecTrustRef
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import se.soderbjorn.lunamux.client.tlsinterop.termtastic_credential_for_trust
import se.soderbjorn.lunamux.client.tlsinterop.termtastic_disposition_cancel
import se.soderbjorn.lunamux.client.tlsinterop.termtastic_disposition_perform_default
import se.soderbjorn.lunamux.client.tlsinterop.termtastic_disposition_use_credential
import se.soderbjorn.lunamux.client.tlsinterop.termtastic_server_trust
import kotlin.concurrent.AtomicInt

/**
 * Build an iOS [HttpClient] using Ktor Darwin with a TLS fingerprint
 * pinning challenge handler.
 *
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the pin, or `null`
 *   for capture mode.
 * @param onPeerCertCaptured invoked at most once when the leaf is observed
 *   in capture mode (dispatched off the TLS handshake thread).
 * @param onPinMismatch invoked at most once with the observed leaf hex when
 *   verify mode rejects a handshake. Runs synchronously on the TLS thread
 *   immediately before the challenge is cancelled, so the write is visible
 *   by the time the Ktor/Darwin engine surfaces the failure to the caller's
 *   `try { … } catch` site.
 * @return a configured [HttpClient]; caller closes.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createPinnedHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
    onPinMismatch: (observedHex: String) -> Unit,
): HttpClient {
    // 0 = not captured yet, 1 = already captured (so the callback fires at
    // most once per client). AtomicInt rather than AtomicReference<Boolean>
    // because the latter uses identity equality on a non-identity type.
    val captureLatch = AtomicInt(0)
    // Same single-shot latch for the verify-mode mismatch path — URLSession
    // can raise the server-trust challenge per connection when WebSocket
    // upgrades retry, and we only want to publish the observed hex once.
    val mismatchLatch = AtomicInt(0)
    val mode = if (pinnedFingerprintHex == null) {
        "capture"
    } else {
        "verify(pin=${pinnedFingerprintHex.take(8)}…)"
    }
    NSLog("[PinnedHttpClient.ios] building client mode=$mode")
    return HttpClient(Darwin) {
        applyCommonClientConfig(this)
        engine {
            handleChallenge { _, _, challenge, completionHandler ->
                val host = challenge.protectionSpace.host
                val method = challenge.protectionSpace.authenticationMethod
                NSLog("[PinnedHttpClient.ios] challenge host=$host method=$method")
                // Ktor's ChallengeHandler types its completionHandler's
                // disposition arg via the NSURLSessionAuthChallengeDisposition
                // typealias, which commonizes inconsistently across iosMain
                // metadata (Int) and the concrete iosArm64 / iosSimulatorArm64
                // compile units (Long). The cinterop shim's helpers return
                // `long`, which the concrete targets accept directly and
                // metadata rejects. Cast the handler once to a permissive
                // signature so the rest of the body compiles unchanged
                // everywhere. Runtime layout is identical.
                @Suppress("UNCHECKED_CAST")
                val complete = completionHandler as (Any, Any?) -> Unit
                val protectionSpace = challenge.protectionSpace
                if (protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
                    NSLog("[PinnedHttpClient.ios] non-server-trust method, deferring to default")
                    complete(termtastic_disposition_perform_default(), null)
                    return@handleChallenge
                }
                val serverTrust: SecTrustRef? = termtastic_server_trust(challenge)
                if (serverTrust == null) {
                    NSLog("[PinnedHttpClient.ios] no serverTrust on protection space — cancelling")
                    complete(termtastic_disposition_cancel(), null)
                    return@handleChallenge
                }
                val leafFingerprintHex = leafSha256Hex(serverTrust)
                if (leafFingerprintHex == null) {
                    NSLog("[PinnedHttpClient.ios] could not compute leaf SHA-256 — cancelling")
                    complete(termtastic_disposition_cancel(), null)
                    return@handleChallenge
                }
                NSLog(
                    "[PinnedHttpClient.ios] leaf=${leafFingerprintHex.take(16)}… " +
                        "pin=${pinnedFingerprintHex?.take(16) ?: "<none>"}",
                )

                // Build the credential from the actual SecTrustRef the server
                // presented. `challenge.proposedCredential` is nil for a
                // server-trust challenge whose chain failed default
                // validation (the self-signed case), so passing it back with
                // `useCredential` is treated as "no credential -> fall through
                // to default validation", which is what was causing the
                // -9807 (errSSLXCertChainInvalid) we previously saw. Wrapping
                // the SecTrustRef in NSURLCredential.credentialForTrust(...)
                // tells URLSession: "I have taken responsibility for trust,
                // use this exact trust object."
                val trustCredential = termtastic_credential_for_trust(serverTrust)

                if (pinnedFingerprintHex == null) {
                    // Capture mode — accept whatever the server presents and
                    // emit the fingerprint to the caller off the TLS
                    // handshake thread.
                    if (captureLatch.compareAndSet(expected = 0, newValue = 1)) {
                        val captured = leafFingerprintHex
                        NSLog("[PinnedHttpClient.ios] capture-mode: dispatching observed cert")
                        dispatch_async(
                            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
                        ) {
                            runCatching { onPeerCertCaptured(captured) }
                        }
                    }
                    NSLog("[PinnedHttpClient.ios] capture-mode: accepting cert via use-credential")
                    complete(
                        termtastic_disposition_use_credential(),
                        trustCredential,
                    )
                } else {
                    if (constantTimeEquals(leafFingerprintHex, pinnedFingerprintHex.lowercase())) {
                        NSLog("[PinnedHttpClient.ios] verify-mode: pin matches, accepting")
                        complete(
                            termtastic_disposition_use_credential(),
                            trustCredential,
                        )
                    } else {
                        NSLog("[PinnedHttpClient.ios] verify-mode: pin MISMATCH — cancelling")
                        // Publish the observed hex synchronously *before* we
                        // cancel — the callback is an in-memory StateFlow
                        // write, and the Swift catch path needs the value
                        // visible by the time Ktor surfaces the failure.
                        if (mismatchLatch.compareAndSet(expected = 0, newValue = 1)) {
                            runCatching { onPinMismatch(leafFingerprintHex) }
                        }
                        complete(
                            termtastic_disposition_cancel(),
                            null,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun leafSha256Hex(serverTrust: SecTrustRef): String? {
    val count = SecTrustGetCertificateCount(serverTrust)
    if (count <= 0) return null
    val leaf = SecTrustGetCertificateAtIndex(serverTrust, 0) ?: return null
    val der = SecCertificateCopyData(leaf) ?: return null
    try {
        val length = CFDataGetLength(der).toInt()
        if (length <= 0) return null
        val ptr = CFDataGetBytePtr(der) ?: return null
        val bytes = ptr.readBytes(length)
        return sha256Hex(bytes)
    } finally {
        CFRelease(der)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun sha256Hex(input: ByteArray): String {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    input.usePinned { inPinned ->
        digest.usePinned { outPinned ->
            CC_SHA256(
                inPinned.addressOf(0),
                input.size.convert(),
                outPinned.addressOf(0).reinterpret<UByteVar>(),
            )
        }
    }
    return digest.joinToString("") { ((it.toInt() and 0xff).toString(16).padStart(2, '0')) }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}
