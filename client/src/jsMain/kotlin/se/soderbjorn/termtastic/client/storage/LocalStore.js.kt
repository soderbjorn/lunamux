/**
 * JS (Electron renderer) `actual` for [LocalStore].
 *
 * The renderer runs with context isolation and no Node integration, so it
 * cannot touch the filesystem directly. Instead it calls the file-IO bridge the
 * preload script exposes on `window.electronApi`
 * (`readDataFile`/`writeDataFile`/`deleteDataFile`), which the Electron main
 * process services against a JSON file under `app.getPath("userData")`. Each
 * bridge call returns a `Promise`, awaited here via `kotlinx.coroutines.await`.
 *
 * Outside Electron (a plain browser tab) `electronApi` is absent; every
 * operation degrades to a no-op (`read` returns `null`). In practice only the
 * Electron desktop build constructs a [LocalStore].
 */
package se.soderbjorn.termtastic.client.storage

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * JS `actual` of [LocalStore]. Backed by the Electron `userData` file-IO bridge.
 */
actual class LocalStore {

    private val api: dynamic get() = window.asDynamic().electronApi

    actual suspend fun read(name: String): String? {
        val bridge = api ?: return null
        val result = (bridge.readDataFile(name) as Promise<String?>).await()
        return result
    }

    actual suspend fun write(name: String, text: String) {
        val bridge = api ?: return
        (bridge.writeDataFile(name, text) as Promise<Unit>).await()
    }

    actual suspend fun delete(name: String) {
        val bridge = api ?: return
        (bridge.deleteDataFile(name) as Promise<Unit>).await()
    }
}
