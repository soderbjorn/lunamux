/**
 * iOS (Darwin) implementation of [createPlainHttpClient].
 *
 * Uses Ktor-Darwin with the platform's default server-trust evaluation — the
 * update manifest comes from public GitHub, so no custom challenge handler or
 * fingerprint pinning is installed (unlike the pinned client for the user's
 * self-signed server).
 *
 * @see createPlainHttpClient
 */
package se.soderbjorn.lunamux.client.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * Build an iOS [HttpClient] backed by Ktor Darwin with default TLS validation.
 *
 * @return a configured [HttpClient]; caller owns its lifecycle.
 */
actual fun createPlainHttpClient(): HttpClient = HttpClient(Darwin) {
    applyUpdateClientConfig(this)
}
