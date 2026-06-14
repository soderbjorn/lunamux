/**
 * JVM (CIO) implementation of [createPlainHttpClient].
 *
 * Uses Ktor CIO with default JSSE trust against the system trust store. The
 * update manifest is fetched from public GitHub, so no fingerprint pinning is
 * required. Present mainly so the shared `client` module compiles on its `jvm`
 * target; the shipping update checks run on Android, iOS, and the JS/Electron
 * renderer.
 *
 * @see createPlainHttpClient
 */
package se.soderbjorn.termtastic.client.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Build a JVM [HttpClient] backed by Ktor CIO with default TLS validation.
 *
 * @return a configured [HttpClient]; caller owns its lifecycle.
 */
actual fun createPlainHttpClient(): HttpClient = HttpClient(CIO) {
    applyUpdateClientConfig(this)
}
