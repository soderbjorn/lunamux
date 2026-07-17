/**
 * Shared "News & Updates" checker wiring for the Lunamux desktop (Electron)
 * renderer.
 *
 * The macOS desktop build shares the web frontend. This file owns the single
 * shared [NewsUpdatesBackingViewModel] for the desktop — covering both version
 * updates and news — and reflects whether it has anything to show onto the
 * pulsing top-bar bell action (`tt-topbar-news`, built by
 * `LunamuxToolkitBootstrap.buildNewsTopbarAction`). The bell is always visible
 * and clickable; its coloured/muted look and pulse are driven via the
 * `document.body[data-tt-news]` / `[data-tt-news-pulse]` flags (see
 * [refreshNewsTopbarIcon] and `styles.css`). Clicking it always opens the
 * combined "News & Updates" screen — see [showNewsDialog].
 *
 * Only the Electron build calls [startNewsUpdatesChecker] (gated on
 * `isElectronClient` in `main.kt`) — a plain browser tab never reveals the bell.
 * Persistence is via the shared [LocalRepository] (the single `local_state.json`
 * blob under the app's `userData`, backed by the JSON-file [LocalStore]), not
 * localStorage — the same unified store the mobile clients use. The desktop has
 * no host list, so only the dismissed-news and last-check fields are populated.
 *
 * @see se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel
 * @see showNewsDialog
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel
import se.soderbjorn.lunamux.client.storage.LocalRepository
import se.soderbjorn.lunamux.client.storage.LocalStore
import se.soderbjorn.lunamux.client.storage.SecureStore
import se.soderbjorn.lunamux.client.update.UpdatePlatform

/**
 * The live shared news/update view-model, retained module-side so the top-bar
 * bell action's click handler can open [showNewsDialog] with the current state,
 * and so [refreshNewsTopbarIcon] can reflect that state onto the bell.
 *
 * `null` until [startNewsUpdatesChecker] runs (i.e. on any Electron build,
 * including demo); the bell's click handler is a no-op while it is `null`.
 */
internal var newsUpdatesViewModel: NewsUpdatesBackingViewModel? = null

/**
 * Construct and start the shared desktop news/update checker, then mirror its
 * [NewsUpdatesBackingViewModel.State] onto both the news and update pills.
 *
 * Called once from `main.kt`'s `start()` inside the `isElectronClient` branch,
 * for both real and demo builds — the news/updates icon and its check are part
 * of the demo showcase.
 * Reads the running build's version code/name from the Electron preload's
 * `electronApi` (as the About dialog does); falls back to `0` so a missing value
 * simply reports "no update".
 */
@OptIn(DelicateCoroutinesApi::class)
fun startNewsUpdatesChecker() {
    val electronApi = window.asDynamic().electronApi
    val versionName = (electronApi?.appVersionName as? String)?.takeIf { it.isNotBlank() } ?: "0"
    val versionCode = (electronApi?.appVersionCode as? String)?.toLongOrNull() ?: 0L

    val viewModel = NewsUpdatesBackingViewModel(
        repository = LocalRepository(LocalStore(), SecureStore()),
        platformId = UpdatePlatform.MAC,
        currentVersionCode = versionCode,
        currentVersionName = versionName,
        // The desktop build updates in-app via electron-updater (see
        // AutoUpdaterPanel.kt), so the versions.json update branch is suppressed
        // here to avoid double-notifying; this checker still surfaces news.
        enableUpdateCheck = false,
    )
    newsUpdatesViewModel = viewModel
    viewModel.start()
    GlobalScope.launch {
        viewModel.stateFlow.collect { state ->
            refreshNewsTopbarIcon(state)
        }
    }
}

/**
 * Reflect the current news/update state onto the top-bar bell's appearance.
 *
 * The bell button (`tt-topbar-news`) always lives in the DOM *and* is always
 * visible and clickable, so the News & Updates screen — and its Restore button —
 * is reachable at any time. This drives two body-level flags (rather than
 * mutating the toolkit-owned button, so the state survives the toolkit's topbar
 * rerenders):
 *
 *  - `data-tt-news="1"` when there is an update or unread news; absent (rendering
 *    the bell muted/grayed via CSS) when there is nothing new.
 *  - `data-tt-news-pulse="1"` when there is actual news, so the bell pulses; a
 *    version update on its own shows the bell coloured but static.
 *
 * (Desktop app updates surface in the sidebar-footer update banner — see
 * AutoUpdaterPanel.kt — not through this bell.)
 *
 * @param state the latest news/update state.
 */
internal fun refreshNewsTopbarIcon(state: NewsUpdatesBackingViewModel.State) {
    val body = document.body ?: return
    if (state.hasContent) {
        body.setAttribute("data-tt-news", "1")
    } else {
        body.removeAttribute("data-tt-news")
    }
    // Pulse only when there is actual news; a version update on its own shows
    // the icon coloured (via `data-tt-news`) but leaves it static.
    if (state.hasNews) {
        body.setAttribute("data-tt-news-pulse", "1")
    } else {
        body.removeAttribute("data-tt-news-pulse")
    }
}
