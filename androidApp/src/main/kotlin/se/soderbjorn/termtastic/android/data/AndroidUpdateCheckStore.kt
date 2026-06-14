/**
 * Android persistence for the version-update checker's last-check timestamp.
 *
 * Implements the shared [se.soderbjorn.termtastic.client.update.UpdateCheckStore]
 * over a small dedicated [android.content.SharedPreferences] file. Constructed
 * once by [se.soderbjorn.termtastic.android.net.UpdateCheckController] and
 * handed to the shared `UpdateCheckViewModel`, which reads the stored time on
 * startup to decide whether to check immediately and writes it after each
 * check.
 *
 * SharedPreferences (rather than the DataStore used elsewhere in the app) is a
 * deliberate fit for [UpdateCheckStore]'s synchronous contract: its reads and
 * the `commit()` write are in-process and fast, and the checker only ever calls
 * them from its background coroutine, never on the main thread.
 *
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckStore
 */
package se.soderbjorn.termtastic.android.data

import android.content.Context
import se.soderbjorn.termtastic.client.update.UpdateCheckStore

/** Name of the dedicated preferences file backing the update checker. */
private const val PREFS_NAME = "termtastic-update-check"

/** Key for the epoch-millis time of the last update check. */
private const val KEY_LAST_CHECK = "update_last_check_epoch_millis"

/**
 * SharedPreferences-backed [UpdateCheckStore] for the Android app.
 *
 * @param context any [Context]; its application context backs the prefs file.
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
 */
class AndroidUpdateCheckStore(context: Context) : UpdateCheckStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Load the last-check timestamp.
     *
     * @return the stored epoch-millis time, or `null` if no check has run yet
     *   on this device.
     */
    override fun loadLastCheckEpochMillis(): Long? =
        if (prefs.contains(KEY_LAST_CHECK)) prefs.getLong(KEY_LAST_CHECK, 0L) else null

    /**
     * Persist the time of a check that just completed.
     *
     * @param epochMillis the check time in milliseconds since the Unix epoch.
     */
    override fun saveLastCheckEpochMillis(epochMillis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, epochMillis).apply()
    }
}
