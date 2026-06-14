/**
 * Per-platform [HttpClient] factory with TLS fingerprint pinning (TOFU).
 *
 * Each Kotlin Multiplatform target supplies an `actual` implementation that
 * wires its native HTTP engine (OkHttp on Android, Darwin on iOS, CIO on the
 * JVM, browser fetch on JS) and installs a custom trust callback used for
 * SSH-style Trust-On-First-Use certificate pinning.
 *
 * Two operating modes, selected by whether [createPinnedHttpClient] is given a
 * pinned fingerprint:
 *
 *  - **Capture** (`pinnedFingerprintHex == null`): no chain validation. The
 *    leaf cert's SHA-256 is computed, exposed via [onPeerCertCaptured], and
 *    persisted by the caller off the network thread. Used on the very first
 *    connect to a new host, immediately before the server's
 *    `DeviceAuth.ApprovalDialog` runs.
 *  - **Verify** (`pinnedFingerprintHex != null`): the leaf's SHA-256 is
 *    constant-time compared against the pin. Mismatch aborts the handshake
 *    and (on JSSE targets) surfaces a `CertificateException` whose message
 *    starts with `"pin-mismatch:"` so the UI cause-chain match can fire a
 *    "Server certificate changed → Re-pair?" dialog. Every native target
 *    also invokes [onPinMismatch] with the observed leaf hex so UI layers
 *    that can't inspect the exception chain (iOS, where the Darwin
 *    URLSessionDelegate cancels the challenge without a marker error) have
 *    a uniform signal.
 *
 * Web/JS does not pin — the browser owns TLS verification end-to-end and
 * does not expose a hook for it. All arguments are accepted on that target
 * to keep the signature parallel, but ignored.
 *
 * @see TermtasticClient
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Apply the configuration shared by every platform: WebSockets, plus a strict
 * connect/request timeout so that an unreachable host fails fast rather than
 * hanging on SYN retransmits for ~75 seconds.
 *
 * @param config the platform's [HttpClientConfig] builder; mutated in place.
 */
fun applyCommonClientConfig(config: HttpClientConfig<*>) {
    config.install(WebSockets)
    config.install(HttpTimeout) {
        // Without this, an unreachable host (device on a different subnet,
        // Wi-Fi client isolation, wrong port) makes the WebSocket handshake
        // hang in kernel SYN retransmit for ~75 seconds before OkHttp gives
        // up. Cap it at 8 s so the UI surfaces the failure quickly.
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
    }
}

/**
 * Build a Ktor [HttpClient] for the current platform with TOFU pinning wired
 * into the TLS handshake.
 *
 * @param pinnedFingerprintHex lowercase hex SHA-256 of the server's leaf
 *   certificate that the client should pin against, or `null` to run in
 *   capture mode (first connect to a new host).
 * @param onPeerCertCaptured invoked at most once per client when the leaf
 *   cert is observed during the handshake — only fires in capture mode (i.e.
 *   when [pinnedFingerprintHex] is `null`). The callback runs off the network
 *   thread so callers can safely write to storage. Receives the same
 *   lowercase hex SHA-256 the caller is expected to persist.
 * @param onPinMismatch invoked at most once per client when a pin mismatch
 *   is detected in verify mode — i.e. [pinnedFingerprintHex] is non-null and
 *   the leaf cert's SHA-256 differs from it. Fires *before* the handshake is
 *   aborted (synchronously on the TLS thread on iOS / latched on JSSE
 *   targets). Keep the callback body O(1) — the intended use is writing to
 *   a `MutableStateFlow` so UI layers can read it after the surfaced
 *   exception. Receives the observed (not the pinned) lowercase hex SHA-256.
 * @return a configured [HttpClient]; the caller owns its lifecycle and must
 *   call `close()` to release the engine.
 * @see TermtasticClient.observedFingerprint
 * @see TermtasticClient.observedMismatch
 */
expect fun createPinnedHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
    onPinMismatch: (observedHex: String) -> Unit,
): HttpClient
