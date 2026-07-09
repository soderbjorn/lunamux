/**
 * App-wide singleton owner of the shared news/update checker on Android.
 *
 * The shared [NewsUpdatesBackingViewModel] runs one periodic check loop for the
 * whole app (covering both version updates and news), so it must be a single
 * instance rather than one-per-screen. This object lazily constructs it on first
 * use — wiring in the running build's version from [BuildConfig] and the shared
 * app-wide [se.soderbjorn.lunamux.client.storage.LocalRepository] (via
 * [AppLocalRepository]) — starts its loop, and exposes the resulting [stateFlow]
 * for any screen to observe.
 *
 * The bell toolbar icon on the hosts and sessions screens reads this state to
 * decide whether to show (`State.hasContent`), and the "News & Updates" screen
 * ([se.soderbjorn.lunamux.android.ui.NewsUpdatesScreen]) renders and mutates
 * it (download + swipe-to-dismiss).
 *
 * @see se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel
 * @see se.soderbjorn.lunamux.android.ui.NewsUpdatesScreen
 */
package se.soderbjorn.lunamux.android.net

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import se.soderbjorn.lunamux.android.BuildConfig
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel
import se.soderbjorn.lunamux.client.update.UpdatePlatform

/**
 * Process-wide holder for the single [NewsUpdatesBackingViewModel] instance.
 */
object NewsUpdatesController {

    @Volatile
    private var viewModel: NewsUpdatesBackingViewModel? = null

    /**
     * Return the singleton [NewsUpdatesBackingViewModel], constructing and
     * starting it on first call. Safe to call repeatedly (e.g. from each screen)
     * — the loop is started only once.
     *
     * @param context any [Context]; retained for call-site symmetry and to
     *   ensure app startup (which sets the shared store's app context) has run.
     * @return the started news/update view-model.
     */
    fun ensureStarted(context: Context): NewsUpdatesBackingViewModel {
        viewModel?.let { return it }
        return synchronized(this) {
            viewModel ?: NewsUpdatesBackingViewModel(
                repository = AppLocalRepository.instance,
                platformId = UpdatePlatform.ANDROID,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                currentVersionName = BuildConfig.VERSION_NAME,
            ).also {
                it.start()
                viewModel = it
            }
        }
    }

    /**
     * The current news/update state, or `null` before [ensureStarted] has been
     * called for the first time.
     */
    val stateFlow: StateFlow<NewsUpdatesBackingViewModel.State>?
        get() = viewModel?.stateFlow
}
