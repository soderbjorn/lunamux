/**
 * Platform-local persistence boundary for the version-update checker.
 *
 * The shared `client` module holds no on-device storage of its own, so the one
 * value the update checker needs to remember between launches — the wall-clock
 * time of the last successful check — is read and written through this
 * interface. Each platform supplies an implementation backed by its native
 * key-value store, mirroring how [se.soderbjorn.termtastic.client.viewmodel.SettingsPersister]
 * is implemented per platform:
 *
 *  - Android: Jetpack DataStore (`AndroidUpdateCheckStore`).
 *  - iOS: `UserDefaults` (a Swift type conforming to this interface).
 *  - macOS/Electron (web build): `window.localStorage` (`JsUpdateCheckStore`).
 *
 * Persisting the timestamp lets the checker fire its very first check on first
 * install/run, then schedule subsequent checks relative to the stored time
 * rather than re-checking immediately on every cold start.
 *
 * @see UpdateCheckViewModel
 */
package se.soderbjorn.termtastic.client.update

/**
 * Reads and writes the timestamp of the last completed update check.
 *
 * Implementations are constructed by each platform's app bootstrap and handed
 * to [UpdateCheckViewModel]. The methods are deliberately **synchronous**: the
 * backing stores (`UserDefaults`, `localStorage`, `SharedPreferences`) all do
 * fast in-process key-value access, and [UpdateCheckViewModel] only ever calls
 * them from inside its background coroutine — never on a UI thread. Keeping
 * them non-suspending also lets the iOS app conform to this interface from
 * plain Swift without async/completion-handler bridging.
 */
interface UpdateCheckStore {
    /**
     * Load the epoch-millisecond timestamp of the last completed check.
     *
     * Called once by [UpdateCheckViewModel.start] to decide whether this is a
     * first run (check immediately) or a subsequent run (schedule relative to
     * the stored time).
     *
     * @return the last-check time in milliseconds since the Unix epoch, or
     *   `null` if no check has ever been recorded on this device.
     */
    fun loadLastCheckEpochMillis(): Long?

    /**
     * Persist the epoch-millisecond timestamp of a check that just completed.
     *
     * Called by [UpdateCheckViewModel] after each check attempt so the next
     * launch can schedule correctly.
     *
     * @param epochMillis the check time in milliseconds since the Unix epoch.
     */
    fun saveLastCheckEpochMillis(epochMillis: Long)
}
