/**
 * JS (browser) implementation of [createPinnedHttpClient].
 *
 * The browser owns TLS verification end-to-end: trust anchor selection,
 * hostname matching, and any per-origin warning interstitial all happen
 * inside Chrome/Safari/Firefox. There is no fetch-level hook to install a
 * custom trust manager, so fingerprint pinning is not enforced from JS —
 * users instead click through the browser's self-signed-cert warning once
 * per origin (TOFU at the browser level).
 *
 * Both pinning arguments are accepted so the signature stays parallel with
 * the native targets, but are ignored here.
 *
 * @see createPinnedHttpClient
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Build a JS [HttpClient]. All pinning args are intentionally unused — the
 * browser handles TLS.
 *
 * @param pinnedFingerprintHex ignored.
 * @param onPeerCertCaptured ignored.
 * @param onPinMismatch ignored.
 * @return a configured [HttpClient]; caller closes.
 */
@Suppress("UNUSED_PARAMETER")
actual fun createPinnedHttpClient(
    pinnedFingerprintHex: String?,
    onPeerCertCaptured: (fingerprintHex: String) -> Unit,
    onPinMismatch: (observedHex: String) -> Unit,
): HttpClient = HttpClient(Js) {
    applyCommonClientConfig(this)
}
