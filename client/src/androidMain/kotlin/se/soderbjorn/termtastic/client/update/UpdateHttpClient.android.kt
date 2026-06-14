/**
 * Android (OkHttp) implementation of [createPlainHttpClient].
 *
 * Uses ordinary OkHttp TLS validation against the system trust store — the
 * update manifest is fetched from public GitHub, so no fingerprint pinning is
 * required (unlike the pinned client used for the user's own server).
 *
 * @see createPlainHttpClient
 */
package se.soderbjorn.termtastic.client.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Build an Android [HttpClient] backed by OkHttp with default TLS validation.
 *
 * @return a configured [HttpClient]; caller owns its lifecycle.
 */
actual fun createPlainHttpClient(): HttpClient = HttpClient(OkHttp) {
    applyUpdateClientConfig(this)
}
