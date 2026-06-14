/**
 * "New update available!" label wiring for the Termtastic desktop (Electron)
 * renderer.
 *
 * The macOS desktop build shares the web frontend; this file starts the shared
 * version-update checker for that build and reflects its state onto the
 * `#app-logo-update-label` span declared in `index.html` (to the left of the
 * Termtastic wordmark in the lower-right logo overlay). Clicking the label
 * opens the manifest's per-platform "more info" URL in the user's default
 * browser via the Electron `openExternalUrl` bridge.
 *
 * Only the Electron build calls [startUpdateChecker] (gated on
 * `isElectronClient` in `main.kt`) — a plain browser tab has no `mac` manifest
 * entry and shows no update label. The running build's version is read from the
 * `electronApi.appVersion*` fields, exactly as the About dialog does.
 *
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
 * @see updateAppLogoState
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
import se.soderbjorn.termtastic.client.update.UpdatePlatform

/**
 * Construct and start the shared update checker for the desktop build, then
 * mirror its [se.soderbjorn.termtastic.client.update.UpdateCheckViewModel.State]
 * onto the logo's update label.
 *
 * Called once from `main.kt`'s `start()` inside the `isElectronClient` branch.
 * Reads the running build's version code/name from the Electron preload's
 * `electronApi`; falls back to `0` so a missing value simply reports "no
 * update" rather than failing.
 */
@OptIn(DelicateCoroutinesApi::class)
fun startUpdateChecker() {
    val electronApi = window.asDynamic().electronApi
    val versionName = (electronApi?.appVersionName as? String)?.takeIf { it.isNotBlank() } ?: "0"
    val versionCode = (electronApi?.appVersionCode as? String)?.toLongOrNull() ?: 0L

    val viewModel = UpdateCheckViewModel(
        platformId = UpdatePlatform.MAC,
        currentVersionCode = versionCode,
        currentVersionName = versionName,
        store = JsUpdateCheckStore(),
    )
    viewModel.start()
    GlobalScope.launch {
        viewModel.stateFlow.collect { state ->
            applyUpdateLabel(state.updateAvailable, state.latestVersionName, state.infoUrl)
        }
    }
}

/**
 * Show or hide the update pill, set its text (including the version name, to
 * match the mobile banners), and (re)wire its click handler.
 *
 * @param updateAvailable whether a newer build was found.
 * @param versionName the newer version's display name, appended to the label
 *   when present.
 * @param url the "more info" URL to open on click, or `null`.
 */
private fun applyUpdateLabel(updateAvailable: Boolean, versionName: String?, url: String?) {
    val pill = document.getElementById("app-logo-update") as? HTMLElement ?: return
    if (updateAvailable && url != null) {
        val textEl = document.getElementById("app-logo-update-text") as? HTMLElement
        textEl?.textContent =
            if (versionName != null) "New version available — $versionName" else "New version available"
        pill.hidden = false
        // Reserve bottom space in the main content area so the corner pill
        // never overlaps app content (the shrink itself is in styles.css,
        // keyed off this class). The pill must already be visible (hidden =
        // false above) for the overlay to have a non-zero measured height.
        publishUpdateReserveHeight()
        document.body?.classList?.add("tt-update-pill-visible")
        pill.onclick = {
            openExternalUrl(url)
            null
        }
    } else {
        pill.hidden = true
        document.body?.classList?.remove("tt-update-pill-visible")
        document.body?.style?.removeProperty("--tt-update-reserve")
        pill.onclick = null
    }
}

/**
 * Measure how far the "new version" pill pokes ABOVE the toolkit's bottom bar
 * and publish that distance as the `--tt-update-reserve` CSS custom property on
 * `<body>`, so the `body.tt-update-pill-visible .dt-app-frame-body` rule in
 * `styles.css` lifts the pane content by exactly that much (plus a small
 * clearance) and the pill no longer overlaps app content.
 *
 * Why measure against the bottom bar rather than reserve the whole lockup: the
 * logo's wordmark+dot row is *meant* to overlay the bottom bar (it shares that
 * line with the Claude-usage text, as before), so only the part of the pill
 * that rises above the bottom bar needs clearing. The pill is `position: fixed`
 * and the bottom bar is anchored at the window bottom, so both rects are stable
 * regardless of the reserve we are about to apply — measuring before adding the
 * class is accurate.
 *
 * Called from [applyUpdateLabel] each time the pill is shown — after the pill is
 * un-hidden so it reports a real bounding box. Falls back silently (leaving the
 * CSS 36px default) if the pill element is missing; if the bottom bar is absent
 * (empty/collapsed), the window bottom is used as the reference instead.
 */
private fun publishUpdateReserveHeight() {
    val pill = document.getElementById("app-logo-update") as? HTMLElement ?: return
    val pillTop = pill.getBoundingClientRect().top
    val bottomBar = document.querySelector(".dt-app-frame-bottombar") as? HTMLElement
    val bottomRef = bottomBar?.getBoundingClientRect()?.top ?: window.innerHeight.toDouble()
    // Distance the pill rises above the bottom bar, plus a few px of breathing
    // room so the pane border doesn't kiss the pill's top edge.
    val reserve = maxOf(0.0, bottomRef - pillTop) + 6.0
    document.body?.style?.setProperty("--tt-update-reserve", "${reserve.toInt()}px")
}

/**
 * Open [url] in the user's default browser via the Electron `openExternalUrl`
 * IPC bridge, falling back to `window.open` if the bridge is unavailable.
 *
 * @param url the absolute http(s) URL to open externally.
 */
private fun openExternalUrl(url: String) {
    val electronApi = window.asDynamic().electronApi
    if (electronApi?.openExternalUrl != null) {
        electronApi.openExternalUrl(url)
    } else {
        window.open(url, "_blank")
    }
}
