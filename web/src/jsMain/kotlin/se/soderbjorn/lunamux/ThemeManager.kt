/**
 * Lunamux-side adapter for the darkness-toolkit theme manager.
 *
 * The full two-tab Theme Manager modal now lives in the toolkit at
 * `se.soderbjorn.darkness.web.themeeditor.ThemeManager`. This file owns
 * the lunamux-specific glue:
 *
 * 1. [LunamuxThemeManagerHost] — a [ThemeManagerHost] adapter that
 *    bridges the toolkit's read/write/render contract to Lunamux's
 *    [AppBackingViewModel] state and side-effects.
 * 2. [showThemeManager] — Lunamux's old entry point. Wraps the toolkit
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
package se.soderbjorn.lunamux

import se.soderbjorn.darkness.web.shell.AppFrameClassNames

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.themeeditor.ThemeManagerHost
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
 * surface to Lunamux's [AppBackingViewModel].
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
internal val lunamuxThemeHost: ThemeManagerHost get() = LunamuxThemeManagerHost

private object LunamuxThemeManagerHost : ThemeManagerHost {
    // ── Per-world theme scoping ─────────────────────────────────────
    // A world owns a dark+light theme *pair* ([WorldConfig.themeSelection]);
    // the theme slots the sidebar reads and writes are therefore the ACTIVE
    // world's, not a single global pair. A world with no override
    // (`themeSelection == null`) follows the global slots, so we fall back to
    // [AppBackingViewModel]'s global selection for both reads and the initial
    // value of the *other* slot when only one is being changed. Appearance,
    // custom-theme definitions and favorites stay global (see below). Writing
    // routes through [WindowCommand.SetWorldTheme] so the change lands on the
    // world the user is actually looking at (the server mirrors the default
    // world's pair into the global key for pre-1.9 clients).

    /** The active world's config, or `null` before the server reports worlds. */
    private fun activeWorld(): WorldConfig? =
        lunamuxClient.windowState.config.value?.activeWorldOrNull()

    override val appearance: Appearance
        get() = appVm.stateFlow.value.appearance
    override val lightThemeName: String
        get() = activeWorld()?.themeSelection?.lightThemeName ?: appVm.stateFlow.value.lightThemeName
    override val darkThemeName: String
        get() = activeWorld()?.themeSelection?.darkThemeName ?: appVm.stateFlow.value.darkThemeName
    override val customThemes: List<Theme>
        get() = appVm.stateFlow.value.customThemes
    override val favoriteThemeNames: Set<String>
        get() = appVm.stateFlow.value.favoriteThemeNames

    // [AppBackingViewModel]'s setters are suspend functions because they
    // can roundtrip to the server. We bridge into the toolkit's
    // synchronous host contract by launching on [GlobalScope] — the
    // toolkit doesn't await completion; the next [refreshThemeManager]
    // pass picks up the new state once the launched coroutine settles.
    private fun launch(block: suspend () -> Unit) {
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch { block() }
    }

    override fun setAppearance(appearance: Appearance) {
        launch { appVm.setAppearance(appearance) }
    }
    override fun setLightThemeName(name: String) {
        val world = activeWorld()
        if (world != null) {
            // Preserve the world's current dark slot (its override, or the global
            // slot it inherits) while replacing only the light one.
            val dark = world.themeSelection?.darkThemeName ?: appVm.stateFlow.value.darkThemeName
            launchCmd(
                WindowCommand.SetWorldTheme(
                    worldId = world.id,
                    selection = WorldThemeSelection(darkThemeName = dark, lightThemeName = name),
                ),
            )
        } else {
            launch { appVm.setLightThemeName(name) }
        }
    }
    override fun setDarkThemeName(name: String) {
        val world = activeWorld()
        if (world != null) {
            val light = world.themeSelection?.lightThemeName ?: appVm.stateFlow.value.lightThemeName
            launchCmd(
                WindowCommand.SetWorldTheme(
                    worldId = world.id,
                    selection = WorldThemeSelection(darkThemeName = name, lightThemeName = light),
                ),
            )
        } else {
            launch { appVm.setDarkThemeName(name) }
        }
    }
    override fun saveCustomTheme(theme: Theme) { launch { appVm.saveCustomTheme(theme) } }
    override fun deleteCustomTheme(name: String) { launch { appVm.deleteCustomTheme(name) } }
    override fun toggleFavorite(name: String) { launch { appVm.toggleThemeFavorite(name) } }

    // ── Per-app settings (font / size / titlebar / notifications) ─
    // Lunamux's terminal panes use the monospaced category; the
    // proportional category is stubbed (no proportional surface).
    // Sidebar / tab bar / chrome read from the `var(--dt-font-*)`
    // chain populated by `mountAppShell.applyHostFontVars` after each
    // settings sync.

    // The getters fold in the default fonts (JetBrains Mono; 12px chrome) when
    // the user hasn't picked a value, so the applied font and the Settings-
    // sidebar highlight stay in sync. See [effectiveFontKey] /
    // [effectiveChromeSize] — the default is a read-time fallback only and is
    // never persisted, so it never overwrites an explicit user choice.
    override val monoFontFamily: String?
        get() = effectiveFontKey(appVm.stateFlow.value.paneFontFamily)
    override val monoFontSizePx: Int?
        get() = appVm.stateFlow.value.paneFontSize
    override val sidebarFontFamily: String?
        get() = effectiveFontKey(appVm.stateFlow.value.sidebarFontFamily)
    override val sidebarFontSizePx: Int?
        get() = effectiveChromeSize(appVm.stateFlow.value.sidebarFontSizePx)
    override val tabbarFontFamily: String?
        get() = effectiveFontKey(appVm.stateFlow.value.tabbarFontFamily)
    override val tabbarFontSizePx: Int?
        get() = effectiveChromeSize(appVm.stateFlow.value.tabbarFontSizePx)
    override val paneHeaderFontFamily: String?
        get() = effectiveFontKey(appVm.stateFlow.value.paneHeaderFontFamily)
    override val paneHeaderFontSizePx: Int?
        get() = effectiveChromeSize(appVm.stateFlow.value.paneHeaderFontSizePx)
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
    override fun setPaneHeaderFontFamily(value: String?) {
        launch { appVm.setPaneHeaderFontFamily(value ?: "") }
    }
    override fun setPaneHeaderFontSizePx(value: Int?) {
        if (value != null) launch { appVm.setPaneHeaderFontSizePx(value) }
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
 * Lunamux's compatibility entry point for opening the Theme Manager.
 *
 * Existing callers (toolbar theme picker, settings panel handoff, status
 * bar shortcuts) keep calling [showThemeManager]; this forwarder wires up
 * the [LunamuxThemeManagerHost], wraps the toolkit panel in a sized
 * `.theme-manager-sidebar` slot, and delegates to the toolkit.
 *
 * Mutual exclusion with the settings panel (closes settings first if it's
 * open) is preserved here, since the settings panel is lunamux-owned
 * and the toolkit doesn't know about it.
 *
 * @param initialTab    "themes" — which tab to surface first.
 * @param focusTheme    optional theme name to scroll into view / open.
 */
fun showThemeManager(
    initialTab: String = "themes",
    focusTheme: String? = null,
) {
    // Settings panel was lunamux-owned; the toolkit now owns the
    // settings sidebar and handles mutual exclusion with the theme
    // manager via the gear / palette button handlers in
    // `mountAppShell`. Nothing Lunamux needs to coordinate here.
    if (themeManagerPanel != null) return
    val appBody = document.querySelector(".${AppFrameClassNames.BODY}") as? HTMLElement ?: return

    // Width-controlling wrapper. The toolkit's `.dt-theme-manager` is sized via
    // `flex: 1 1 auto` on the assumption it lives inside a sized sidebar slot;
    // mounting it directly into `.dt-app-frame-body` (a flex row) would let it
    // claim all remaining space. The wrapper provides the 480px slot + slide
    // animation that Lunamux's existing `.theme-manager-sidebar` CSS
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
        hostArg = LunamuxThemeManagerHost,
        mountInto = wrapper,
        initialTab = initialTab,
        focusTheme = focusTheme,
    )

    // Slide the wrapper open on the next frame so the browser has a starting
    // frame to interpolate from (mirrors the toolkit's own rAF gating for
    // its opacity transition).
    window.requestAnimationFrame { wrapper.classList.add("open") }
}

/**
 * Lunamux-side wrapper around the toolkit's [closeDarknessThemeManager].
 *
 * When the lunamux-owned wrapper slot is mounted, defers [onClosed] until
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
 * Lunamux-side wrapper around the toolkit's [refreshDarknessThemeManager].
 *
 * Called by window-state observers and server reconciliation to repaint
 * the open editor when external state changes.
 */
fun refreshThemeManager() {
    refreshDarknessThemeManager()
}
