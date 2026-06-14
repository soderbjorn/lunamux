/**
 * Termtastic-side adapter for the darkness-toolkit theme manager.
 *
 * The full two-tab Theme Manager modal now lives in the toolkit at
 * `se.soderbjorn.darkness.web.themeeditor.ThemeManager`. This file owns
 * the termtastic-specific glue:
 *
 * 1. [TermtasticThemeManagerHost] — a [ThemeManagerHost] adapter that
 *    bridges the toolkit's read/write/render contract to termtastic's
 *    [AppBackingViewModel] state and side-effects.
 * 2. [showThemeManager] — termtastic's old entry point. Wraps the toolkit
 *    panel in a sized `.theme-manager-sidebar` slot (so it doesn't claim
 *    all available flex-row width inside `.dt-app-frame-body`) and forwards
 *    to the toolkit's `openDarknessThemeManager`. [closeThemeManager] coordinates
 *    the wrapper's slide-out with the toolkit's panel-fade close path.
 * 3. [refreshThemeManager] — pass-through to the toolkit so external
 *    callers (window-state observers, server reconciliation, etc.) can
 *    poke the open editor.
 *
 * @see se.soderbjorn.darkness.web.themeeditor.showThemeManager
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.shell.AppFrameClassNames
import se.soderbjorn.darkness.web.showConfirmDialog

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.themeeditor.ThemeManagerHost
import se.soderbjorn.darkness.web.themeeditor.defaultRenderConfigSilhouetteHtml
import se.soderbjorn.darkness.web.themeeditor.showThemeManager as openDarknessThemeManager
import se.soderbjorn.darkness.web.themeeditor.refreshThemeManager as refreshDarknessThemeManager
import se.soderbjorn.darkness.web.themeeditor.closeThemeManager as closeDarknessThemeManager

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * [ThemeManagerHost] implementation that bridges the toolkit's read/write
 * surface to termtastic's [AppBackingViewModel].
 *
 * The host is recreated each time the manager is opened — the toolkit
 * reads through it on every render, so re-fetching `appVm.stateFlow.value`
 * keeps the editor in sync with whatever the rest of the app has done.
 */
/**
 * Accessor for the toolkit-shared ThemeManagerHost. Public so
 * `mountAppShell` can plumb the same instance into both the theme
 * manager (palette button) and the Settings sidebar (gear button).
 */
internal val termtasticThemeHost: ThemeManagerHost get() = TermtasticThemeManagerHost

private object TermtasticThemeManagerHost : ThemeManagerHost {
    override val mainSchemeName: String
        get() = appVm.stateFlow.value.theme.name
    override val appearance: Appearance
        get() = appVm.stateFlow.value.appearance
    override val lightThemeName: String?
        get() = appVm.stateFlow.value.lightThemeName
    override val darkThemeName: String?
        get() = appVm.stateFlow.value.darkThemeName
    override val customThemes: Map<String, Theme>
        get() = appVm.stateFlow.value.customThemes
    override val customSchemes: Map<String, CustomScheme>
        get() = appVm.stateFlow.value.customSchemes
    override val favoriteThemes: Collection<String>
        get() = appVm.stateFlow.value.favoriteThemes
    override val favoriteSchemes: Collection<String>
        get() = appVm.stateFlow.value.favoriteSchemes

    /**
     * Termtastic's pane → universal-section map. Read by toolkit-side
     * resolvers so the active theme's section assignments can be
     * resolved against the host's concrete pane names.
     *
     * @see termtasticPanes
     */
    override val appPanes: Map<String, String>
        get() = termtasticPanes

    // [AppBackingViewModel]'s setters are suspend functions because they
    // can roundtrip to the server. We bridge into the toolkit's
    // synchronous host contract by launching on [GlobalScope] — the
    // toolkit doesn't await completion; the next [refreshThemeManager]
    // pass picks up the new state once the launched coroutine settles.
    private fun launch(block: suspend () -> Unit) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch { block() }
    }

    override fun setLightThemeName(name: String?) {
        if (name != null) launch { appVm.setLightThemeName(name) }
    }
    override fun setDarkThemeName(name: String?) {
        if (name != null) launch { appVm.setDarkThemeName(name) }
    }
    override fun toggleFavoriteTheme(name: String) { launch { appVm.toggleFavoriteTheme(name) } }
    override fun toggleFavoriteScheme(name: String) { launch { appVm.toggleFavoriteScheme(name) } }
    override fun saveCustomTheme(theme: Theme) { launch { appVm.saveCustomTheme(theme) } }
    override fun deleteCustomTheme(name: String) { launch { appVm.deleteCustomTheme(name) } }
    override fun saveCustomScheme(scheme: CustomScheme) { launch { appVm.saveCustomScheme(scheme) } }
    override fun deleteCustomScheme(name: String) { launch { appVm.deleteCustomScheme(name) } }

    /**
     * Delegate the thumbnail markup to the toolkit's neutral silhouette so
     * termtastic stays visually consistent with notegrow / the toolkit demo
     * and benefits from improvements made centrally. The previous
     * termtastic-local copy lived in `WebStateActions.renderConfigSilhouette`
     * and has been deleted.
     */
    override fun renderConfigSilhouetteHtml(theme: Theme): String =
        defaultRenderConfigSilhouetteHtml(
            theme = theme,
            isDark = !isLightActive(appVm.stateFlow.value.appearance),
            customSchemes = appVm.stateFlow.value.customSchemes,
        )
    override fun renderThemeSwatchHtml(scheme: ColorScheme): String =
        renderThemeSwatch(scheme)

    // ── Per-app settings (font / size / titlebar / notifications) ─
    // Termtastic's terminal panes use the monospaced category; the
    // proportional category is stubbed (no proportional surface).
    // Sidebar / tab bar / chrome read from the `var(--dt-font-*)`
    // chain populated by `mountAppShell.applyHostFontVars` after each
    // settings sync.

    override val monoFontFamily: String?
        get() = appVm.stateFlow.value.paneFontFamily
    override val monoFontSizePx: Int?
        get() = appVm.stateFlow.value.paneFontSize
    override val sidebarFontFamily: String?
        get() = appVm.stateFlow.value.sidebarFontFamily
    override val sidebarFontSizePx: Int?
        get() = appVm.stateFlow.value.sidebarFontSizePx
    override val tabbarFontFamily: String?
        get() = appVm.stateFlow.value.tabbarFontFamily
    override val tabbarFontSizePx: Int?
        get() = appVm.stateFlow.value.tabbarFontSizePx
    override val desktopNotifications: Boolean
        get() = appVm.stateFlow.value.desktopNotifications
    override val useCustomTitleBar: Boolean
        get() = appVm.stateFlow.value.electronCustomTitleBar

    override fun setMonoFontFamily(value: String?) {
        launch { appVm.setPaneFontFamily(value ?: "") }
    }
    override fun setMonoFontSizePx(value: Int?) {
        if (value != null) launch { appVm.setPaneFontSize(value) }
    }
    override fun setSidebarFontFamily(value: String?) {
        launch { appVm.setSidebarFontFamily(value ?: "") }
    }
    override fun setSidebarFontSizePx(value: Int?) {
        if (value != null) launch { appVm.setSidebarFontSizePx(value) }
    }
    override fun setTabbarFontFamily(value: String?) {
        launch { appVm.setTabbarFontFamily(value ?: "") }
    }
    override fun setTabbarFontSizePx(value: Int?) {
        if (value != null) launch { appVm.setTabbarFontSizePx(value) }
    }
    override fun setDesktopNotifications(value: Boolean) {
        launch { appVm.setDesktopNotifications(value) }
    }
    override fun setUseCustomTitleBar(value: Boolean) {
        launch { appVm.setElectronCustomTitleBar(value) }
    }
}

/**
 * Pending [closeThemeManager] callback, invoked once the wrapper has finished
 * sliding out and been detached. Stashed because the toolkit's close runs an
 * opacity transition first; we want callers like the settings-panel handoff
 * to fire only after the layout slot is fully reclaimed.
 */
private var pendingThemeManagerOnClosed: (() -> Unit)? = null

/**
 * Termtastic's compatibility entry point for opening the Theme Manager.
 *
 * Existing callers (toolbar theme picker, settings panel handoff, status
 * bar shortcuts) keep calling [showThemeManager]; this forwarder wires up
 * the [TermtasticThemeManagerHost], wraps the toolkit panel in a sized
 * `.theme-manager-sidebar` slot, and delegates to the toolkit.
 *
 * Mutual exclusion with the settings panel (closes settings first if it's
 * open) is preserved here, since the settings panel is termtastic-owned
 * and the toolkit doesn't know about it.
 *
 * @param initialTab    "themes" or "schemes" — which tab to surface first.
 * @param focusTheme    optional theme name to scroll into view / open.
 * @param focusScheme   optional scheme name to scroll into view / open.
 */
fun showThemeManager(
    initialTab: String = "themes",
    focusTheme: String? = null,
    focusScheme: String? = null,
) {
    // Settings panel was termtastic-owned; the toolkit now owns the
    // settings sidebar and handles mutual exclusion with the theme
    // manager via the gear / palette button handlers in
    // `mountAppShell`. Nothing termtastic needs to coordinate here.
    if (themeManagerPanel != null) return
    val appBody = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement ?: return

    // Width-controlling wrapper. The toolkit's `.dt-theme-manager` is sized via
    // `flex: 1 1 auto` on the assumption it lives inside a sized sidebar slot;
    // mounting it directly into `.dt-app-frame-body` (a flex row) would let it
    // claim all remaining space. The wrapper provides the 480px slot + slide
    // animation that termtastic's existing `.theme-manager-sidebar` CSS
    // (styles.css) already defines.
    val wrapper = document.createElement("aside") as HTMLElement
    wrapper.className = "theme-manager-sidebar"
    appBody.appendChild(wrapper)
    themeManagerPanel = wrapper

    // Bubbled-transitionend bridge:
    //  • The toolkit closes its panel by removing its `dt-open` class, which
    //    fades opacity 1 → 0. When that finishes, we collapse the wrapper
    //    (animate width 480 → 0).
    //  • When the wrapper's own width transition lands at 0, we detach it,
    //    clear state, and invoke any deferred [pendingThemeManagerOnClosed].
    // This avoids reaching into the toolkit's `internal`-scoped close hook.
    wrapper.addEventListener("transitionend", { ev: Event ->
        val target = ev.target as? HTMLElement ?: return@addEventListener
        val propertyName = ev.asDynamic().propertyName as? String
        if (target !== wrapper &&
            target.classList.contains("dt-theme-manager") &&
            !target.classList.contains("dt-open") &&
            propertyName == "opacity"
        ) {
            wrapper.classList.remove("open")
            return@addEventListener
        }
        if (target === wrapper && propertyName == "width" &&
            !wrapper.classList.contains("open")
        ) {
            wrapper.remove()
            if (themeManagerPanel === wrapper) themeManagerPanel = null
            val cb = pendingThemeManagerOnClosed
            pendingThemeManagerOnClosed = null
            cb?.invoke()
        }
    })

    openDarknessThemeManager(
        hostArg = TermtasticThemeManagerHost,
        mountInto = wrapper,
        initialTab = initialTab,
        focusTheme = focusTheme,
        focusScheme = focusScheme,
    )

    // Slide the wrapper open on the next frame so the browser has a starting
    // frame to interpolate from (mirrors the toolkit's own rAF gating for
    // its opacity transition).
    window.requestAnimationFrame { wrapper.classList.add("open") }
}

/**
 * Termtastic-side wrapper around the toolkit's [closeDarknessThemeManager].
 *
 * When the termtastic-owned wrapper slot is mounted, defers [onClosed] until
 * the wrapper has finished sliding out (so handoffs like opening the settings
 * panel see the layout slot already freed). When called with no wrapper
 * (defensive — shouldn't normally happen since `showThemeManager` always
 * mounts one), forwards directly to the toolkit.
 */
fun closeThemeManager(onClosed: (() -> Unit)? = null) {
    val wrapper = themeManagerPanel
    if (wrapper == null) {
        closeDarknessThemeManager(onClosed)
        return
    }
    pendingThemeManagerOnClosed = onClosed
    closeDarknessThemeManager(onClosed = null)
}

/**
 * Termtastic-side wrapper around the toolkit's [refreshDarknessThemeManager].
 *
 * Called by window-state observers and server reconciliation to repaint
 * the open editor when external state changes.
 */
fun refreshThemeManager() {
    refreshDarknessThemeManager()
}
