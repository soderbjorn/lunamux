/**
 * App-wide singleton owner of the shared version-update checker on Android.
 *
 * The shared [UpdateCheckViewModel] runs one periodic check loop for the whole
 * app, so it must be a single instance rather than one-per-screen. This object
 * lazily constructs it on first use — wiring in the running build's version
 * from [BuildConfig] and a DataStore-backed [AndroidUpdateCheckStore] — starts
 * its loop, and exposes the resulting [stateFlow] for any screen to observe.
 *
 * The [se.soderbjorn.termtastic.android.ui.UpdateBanner] composable shown at the
 * top of the hosts and terminal screens reads this state to decide whether to
 * display the "new version available" bar.
 *
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
 * @see se.soderbjorn.termtastic.android.ui.UpdateBanner
 */
package se.soderbjorn.termtastic.android.net

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import se.soderbjorn.termtastic.android.BuildConfig
import se.soderbjorn.termtastic.android.data.AndroidUpdateCheckStore
import se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
import se.soderbjorn.termtastic.client.update.UpdatePlatform

/**
 * Process-wide holder for the single [UpdateCheckViewModel] instance.
 */
object UpdateCheckController {

    @Volatile
    private var viewModel: UpdateCheckViewModel? = null

    /**
     * Return the singleton [UpdateCheckViewModel], constructing and starting it
     * on first call. Safe to call repeatedly (e.g. from each screen) — the loop
     * is started only once.
     *
     * @param context any [Context]; its application context backs the store.
     * @return the started update-check view-model.
     */
    fun ensureStarted(context: Context): UpdateCheckViewModel {
        viewModel?.let { return it }
        return synchronized(this) {
            viewModel ?: UpdateCheckViewModel(
                platformId = UpdatePlatform.ANDROID,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                currentVersionName = BuildConfig.VERSION_NAME,
                store = AndroidUpdateCheckStore(context.applicationContext),
            ).also {
                it.start()
                viewModel = it
            }
        }
    }

    /**
     * The current update-check state, or `null` before [ensureStarted] has been
     * called for the first time.
     */
    val stateFlow: StateFlow<UpdateCheckViewModel.State>?
        get() = viewModel?.stateFlow
}
