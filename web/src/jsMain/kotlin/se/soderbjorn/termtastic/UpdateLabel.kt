/**
 * External-link helper for the Termtastic desktop (Electron) renderer.
 *
 * The update + news state is owned by a single shared
 * [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel]
 * started in [startNewsUpdatesChecker] (see NewsLabel.kt) and surfaced through
 * the pulsing top-bar bell action, which opens the combined "News & Updates"
 * screen ([showNewsDialog]). This file holds only [openExternalUrl] — the bridge
 * the screen's Download / "Learn more" buttons use to open the manifest's
 * per-platform URL in the user's default browser.
 *
 * @see showNewsDialog
 * @see startNewsUpdatesChecker
 */
package se.soderbjorn.termtastic

import kotlinx.browser.window

/**
 * Open [url] in the user's default browser via the Electron `openExternalUrl`
 * IPC bridge, falling back to `window.open` if the bridge is unavailable.
 *
 * Called by the News & Updates screen's Download button and each news card's
 * "Learn more" link (see [showNewsDialog]).
 *
 * @param url the absolute http(s) URL to open externally.
 */
internal fun openExternalUrl(url: String) {
    val electronApi = window.asDynamic().electronApi
    if (electronApi?.openExternalUrl != null) {
        electronApi.openExternalUrl(url)
    } else {
        window.open(url, "_blank")
    }
}
