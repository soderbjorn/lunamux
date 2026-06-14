/**
 * First-launch onboarding persistence for the Termtastic Android app.
 *
 * Tracks a single boolean — "has the user seen the onboarding walkthrough?" —
 * in the same Jetpack DataStore used for connection settings and saved hosts.
 * Read once at startup by [se.soderbjorn.termtastic.android.ui.TermtasticApp]
 * to decide whether to show [se.soderbjorn.termtastic.android.ui.OnboardingScreen]
 * before the host list, and written when the walkthrough is finished so it
 * never appears again.
 *
 * @see OnboardingPreferences
 * @see se.soderbjorn.termtastic.android.ui.OnboardingScreen
 */
package se.soderbjorn.termtastic.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore key for the "onboarding completed" flag. */
private val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")

/**
 * Read/write access to the first-launch onboarding flag.
 *
 * Backed by the shared [connectionDataStore]; both operations are one-shot
 * suspend reads/writes since the flag is consulted only at app startup.
 *
 * @param context application context used to access the DataStore.
 * @see se.soderbjorn.termtastic.android.ui.TermtasticApp
 */
class OnboardingPreferences(private val context: Context) {

    /**
     * Whether the onboarding walkthrough has already been completed on this
     * device.
     *
     * Called once at startup by [se.soderbjorn.termtastic.android.ui.TermtasticApp]
     * to gate the walkthrough.
     *
     * @return `true` if onboarding has been finished before; `false` on a fresh
     *   install.
     */
    suspend fun hasSeen(): Boolean =
        context.connectionDataStore.data.map { it[ONBOARDING_SEEN] ?: false }.first()

    /**
     * Mark the onboarding walkthrough as completed so it is never shown again.
     *
     * Called when the user finishes (or skips to the end of) the walkthrough.
     */
    suspend fun markSeen() {
        context.connectionDataStore.edit { prefs ->
            prefs[ONBOARDING_SEEN] = true
        }
    }
}
