/**
 * Web/Electron persistence for the version-update checker's last-check
 * timestamp.
 *
 * Implements the shared [se.soderbjorn.termtastic.client.update.UpdateCheckStore]
 * over `window.localStorage`. Used only in the macOS Electron desktop build
 * (the update checker is not started for a plain browser tab); constructed by
 * [startUpdateChecker] and handed to the shared `UpdateCheckViewModel`, which
 * reads the stored time on startup and writes it after each check.
 *
 * The Android counterpart is `AndroidUpdateCheckStore` (SharedPreferences); the
 * iOS one is `IosUpdateCheckStore` (UserDefaults).
 *
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckStore
 */
package se.soderbjorn.termtastic

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import se.soderbjorn.termtastic.client.update.UpdateCheckStore

/** localStorage key for the epoch-millis time of the last update check. */
private const val KEY_LAST_CHECK = "tt_update_last_check"

/**
 * localStorage-backed [UpdateCheckStore] for the Electron renderer.
 *
 * @see startUpdateChecker
 */
class JsUpdateCheckStore : UpdateCheckStore {

    /**
     * Load the last-check timestamp from localStorage.
     *
     * @return the stored epoch-millis time, or `null` if absent or unparseable.
     */
    override fun loadLastCheckEpochMillis(): Long? =
        localStorage[KEY_LAST_CHECK]?.toLongOrNull()

    /**
     * Persist the time of a check that just completed.
     *
     * @param epochMillis the check time in milliseconds since the Unix epoch.
     */
    override fun saveLastCheckEpochMillis(epochMillis: Long) {
        localStorage[KEY_LAST_CHECK] = epochMillis.toString()
    }
}
