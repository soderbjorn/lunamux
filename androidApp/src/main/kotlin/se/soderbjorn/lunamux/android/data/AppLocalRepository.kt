/**
 * App-wide singleton owner of the shared [LocalRepository] on Android.
 *
 * All on-device, non-server local state — the saved
 * [se.soderbjorn.lunamux.client.HostEntry] list, the onboarding flag, and the
 * news/update bookkeeping — lives in one [LocalRepository] persisted to a single
 * `local_state.json` file. The lone exception is the device-auth token, which
 * the repository keeps in the [SecureStore] (`EncryptedSharedPreferences`)
 * instead of the plain JSON. It must be a single process-wide instance so the
 * host list screen, the onboarding gate, and the news checker all observe and
 * mutate the same state; this object provides it.
 *
 * Backed by the shared [LocalStore] and [SecureStore], both of whose Android
 * actuals read the application [android.content.Context] from `LocalStoreContext`,
 * set in `MainActivity.onCreate` before any composition runs — so first access
 * here is always safe.
 *
 * Replaces the previous Jetpack DataStore–backed `ConnectionRepository`,
 * `HostsRepository`, and `OnboardingPreferences`.
 *
 * @see se.soderbjorn.lunamux.client.storage.LocalRepository
 * @see se.soderbjorn.lunamux.android.ui.HostsScreen
 * @see se.soderbjorn.lunamux.android.net.NewsUpdatesController
 */
package se.soderbjorn.lunamux.android.data

import se.soderbjorn.lunamux.client.storage.LocalRepository
import se.soderbjorn.lunamux.client.storage.LocalStore
import se.soderbjorn.lunamux.client.storage.SecureStore

/**
 * Process-wide holder for the single [LocalRepository] instance.
 */
object AppLocalRepository {
    /**
     * The shared repository, constructed and hydrated on first access. Its
     * [LocalRepository.start] kicks off the initial `local_state.json` read so
     * observers see a non-null state shortly after process start.
     */
    val instance: LocalRepository by lazy {
        LocalRepository(LocalStore(), SecureStore()).also { it.start() }
    }
}
