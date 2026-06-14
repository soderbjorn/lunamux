/**
 * Browser localStorage-backed implementation of [AuthTokenStore].
 *
 * Persists the device-auth token under `localStorage["termtastic.authToken"]`
 * and mirrors it into a `termtastic_auth` cookie so same-origin HTTP requests
 * carry the token automatically.
 *
 * @see AuthTokenStore
 * @see getOrCreateToken
 */
package se.soderbjorn.termtastic.client

import kotlinx.browser.window

/**
 * [AuthTokenStore] backed by the browser's `localStorage` API.
 *
 * Uses the same `"termtastic.authToken"` key as the pre-refactor vanilla-JS
 * client, so tokens minted by the legacy code survive the migration without
 * triggering a new device-approval flow.
 */
class LocalStorageAuthTokenStore : AuthTokenStore {
    /** The localStorage key used to persist the token. */
    private val key = "termtastic.authToken"

    /**
     * Load the token from `localStorage`.
     *
     * @return the stored token, or `null` if none exists.
     */
    override fun load(): String? = window.localStorage.getItem(key)

    /**
     * Save [token] to `localStorage` and mirror it into a `termtastic_auth`
     * cookie (SameSite=Strict, one-year Max-Age) so same-origin fetches carry
     * it automatically.
     *
     * @param token the base64url-encoded device-auth token.
     */
    override fun save(token: String) {
        window.localStorage.setItem(key, token)
        // Mirror into a cookie so same-origin fetches attach it automatically.
        // SameSite=Strict + one-year Max-Age match the pre-refactor behaviour.
        window.document.cookie =
            "termtastic_auth=$token; Path=/; SameSite=Strict; Max-Age=31536000"
    }
}
