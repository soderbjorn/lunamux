/**
 * JS (browser) implementation of [createPlainHttpClient].
 *
 * Uses Ktor's JS engine, which delegates to the browser's `fetch`. The browser
 * owns TLS verification end-to-end; for the update manifest that is exactly
 * what we want, since it is served by public GitHub over a CA-issued
 * certificate. This is the client the macOS Electron app's renderer uses to
 * fetch `versions.json`.
 *
 * @see createPlainHttpClient
 */
package se.soderbjorn.termtastic.client.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

/**
 * Build a JS [HttpClient] backed by the browser `fetch` engine.
 *
 * @return a configured [HttpClient]; caller owns its lifecycle.
 */
actual fun createPlainHttpClient(): HttpClient = HttpClient(Js) {
    applyUpdateClientConfig(this)
}
