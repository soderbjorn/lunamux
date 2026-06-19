/**
 * Per-platform factory for a plain (un-pinned) [HttpClient] used to fetch the
 * version-update manifest from public GitHub.
 *
 * Unlike [se.soderbjorn.termtastic.client.createPinnedHttpClient], which talks
 * to the user's self-signed Termtastic server and enforces TOFU certificate
 * pinning, the update checker fetches `versions.json` from
 * `raw.githubusercontent.com` — a public host with a normal CA-issued
 * certificate. So this client uses ordinary platform TLS validation and no
 * pinning hooks.
 *
 * Each Kotlin Multiplatform target provides an `actual` that wires its native
 * engine (OkHttp on Android, Darwin on iOS, CIO on the JVM, browser fetch on
 * JS), mirroring the [se.soderbjorn.termtastic.client.PinnedHttpClientFactory]
 * layout. Engine selection is done per-target rather than via Ktor's automatic
 * resolution because the latter is not reliable on Kotlin/Native.
 *
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel
 */
package se.soderbjorn.termtastic.client.update

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/**
 * Apply the configuration shared by every platform's update client: a strict
 * connect/request timeout so an unreachable network fails fast instead of
 * hanging the periodic check loop.
 *
 * @param config the platform's [HttpClientConfig] builder; mutated in place.
 */
internal fun applyUpdateClientConfig(config: HttpClientConfig<*>) {
    config.install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
    }
}

/**
 * Build a Ktor [HttpClient] for the current platform suitable for fetching the
 * public version manifest. No certificate pinning is installed.
 *
 * @return a configured [HttpClient]; the caller owns its lifecycle. In practice
 *   [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel]
 *   keeps it for the life of the app, so it is never explicitly closed.
 */
expect fun createPlainHttpClient(): HttpClient
