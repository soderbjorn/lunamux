/**
 * JS (Electron renderer / browser) `actual` for [SecureStore].
 *
 * The desktop build does not actually route its connection token through
 * [LocalRepository] — the web/desktop client mints and attaches its token via
 * the separate `localStorage`-backed `AuthTokenStore` — so this actual exists
 * mainly to satisfy the `expect` for the shared news/update bookkeeping path. It
 * is backed by `window.localStorage` (prefixed `secure.`), matching where the
 * web client already keeps its token. Outside a browser/renderer context (no
 * `localStorage`) every operation degrades to a no-op.
 */
package se.soderbjorn.termtastic.client.storage

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * JS `actual` of [SecureStore]. Backed by `window.localStorage`.
 */
actual class SecureStore {

    private fun storageKey(key: String): String = "secure.$key"

    actual suspend fun read(key: String): String? =
        runCatching { localStorage[storageKey(key)] }.getOrNull()

    actual suspend fun write(key: String, value: String) {
        runCatching { localStorage[storageKey(key)] = value }
    }

    actual suspend fun delete(key: String) {
        runCatching { localStorage.removeItem(storageKey(key)) }
    }
}
