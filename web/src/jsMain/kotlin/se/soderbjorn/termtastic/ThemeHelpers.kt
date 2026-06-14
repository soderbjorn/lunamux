/**
 * Theme-related utility functions for the Termtastic web frontend.
 *
 * Provides helpers for resolving the current effective theme colors based on
 * appearance mode (Light/Dark/Auto), detecting the system color scheme preference,
 * and determining whether a background color is light or dark for contrast adjustment.
 *
 * @see buildXtermTheme
 * @see applyAppearanceClass
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.systemPrefersDark
import se.soderbjorn.darkness.web.toCssVarMap

import kotlinx.browser.window

/** Feature flag: when true, applies a "spiced" variant to dark themes. Currently disabled. */
const val DARK_SPICED = false

/**
 * The default terminal theme, resolved by name from the [recommendedColorSchemes] list.
 */
val defaultTheme: ColorScheme
    get() = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Determines whether the light variant of a theme should be active based on
 * the current appearance setting. Convenience inverse of toolkit-web's
 * [isDarkActive] so existing termtastic call sites stay terse.
 *
 * @param appearance the user's selected appearance mode
 * @return true if light mode should be used
 */
fun isLightActive(appearance: Appearance): Boolean = !isDarkActive(appearance)

/**
 * Returns the foreground color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex foreground color string
 */
fun themeForegroundForCurrent(theme: ColorScheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightFg else theme.darkFg

/**
 * Returns the background color for the given theme and appearance mode.
 *
 * @param theme the terminal theme
 * @param appearance the user's selected appearance mode
 * @return the hex background color string
 */
fun themeBackgroundForCurrent(theme: ColorScheme, appearance: Appearance): String =
    if (isLightActive(appearance)) theme.lightBg else theme.darkBg

/**
 * Determines whether a hex background color is perceptually light using the
 * YIQ luminance formula (weighted sum: R*299 + G*587 + B*114).
 *
 * Used to adjust ANSI color palettes for readability against the terminal background.
 *
 * @param bg the hex color string (e.g. "#1e1e1e")
 * @return true if the background is light (luminance > 140), false otherwise
 */
fun isBackgroundLight(bg: String): Boolean {
    if (bg.length < 7 || !bg.startsWith("#")) return false
    val r = bg.substring(1, 3).toIntOrNull(16) ?: return false
    val g = bg.substring(3, 5).toIntOrNull(16) ?: return false
    val b = bg.substring(5, 7).toIntOrNull(16) ?: return false
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}

/**
 * Resolves the full semantic palette for the currently active theme and
 * appearance mode, using the global [appVm] state.
 *
 * @return the resolved palette for the current state
 * @see ColorScheme.resolve
 */
fun currentResolvedPalette(): ResolvedPalette {
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)
    return state.theme.resolve(isDark)
}

/**
 * Resolves the semantic palette for a specific theme and appearance mode.
 *
 * Used by the theme chooser to preview palettes before selection.
 *
 * @param theme the theme to resolve
 * @param isDark whether to resolve in dark mode
 * @return the resolved palette
 */
fun resolvedPaletteFor(theme: ColorScheme, isDark: Boolean): ResolvedPalette =
    theme.resolve(isDark)

/**
 * Resolves the palette for a specific concrete pane, using the pane's own
 * resolved scheme from [AppBackingViewModel.State.paneSchemes] (populated by
 * the toolkit's [se.soderbjorn.darkness.core.resolveActiveTheme] resolver
 * via [termtasticPanes]) or falling back to the global theme.
 *
 * @param section concrete pane name (e.g. `"sidebar"`, `"terminal"`, `"diff"`,
 *   `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`, `"active"`,
 *   `"bottomBar"`, `"git"`).
 * @return the resolved palette for that pane.
 */
fun sectionPalette(section: String): ResolvedPalette {
    val state = appVm.stateFlow.value
    val isDark = !isLightActive(state.appearance)
    val paneScheme = state.paneSchemes[section]
    return (paneScheme ?: state.theme).resolve(isDark)
}

/**
 * Converts a [ResolvedPalette] to a map of CSS custom property names to
 * CSS colour values.
 *
 * Property names follow the `--t-group-token` convention (e.g.
 * `--t-surface-base`, `--t-text-primary`).  Colours with alpha use
 * `rgba()` format; fully opaque colours use `#rrggbb`.
 *
 * @return map of CSS property name to CSS colour string
 * @see argbToCss
 */
/**
 * Converts a [ResolvedPalette] to a map of the legacy CSS alias variables
 * used by existing stylesheet rules (e.g. `--surface`, `--text-primary`).
 *
 * These aliases are defined on `:root` in styles.css as `var(--t-*, fallback)`.
 * Because CSS resolves `var()` at the element where the alias is defined,
 * setting `--t-*` on a child element does NOT update the inherited alias.
 * This map must be set alongside [toCssVarMap] on any section container
 * that needs a per-section theme override.
 *
 * @return map of legacy CSS alias name to CSS colour string
 * @see toCssVarMap
 */
fun ResolvedPalette.toCssAliasMap(): Map<String, String> = buildMap {
    put("--termtastic-orange", argbToCss(accent.primary))
    put("--termtastic-orange-accent", argbToCss(accent.primary))
    put("--background", argbToCss(sidebar.bg))
    put("--surface", argbToCss(surface.raised))
    put("--bg-elevated", argbToCss(surface.overlay))
    put("--text-primary", argbToCss(text.primary))
    put("--text-secondary", argbToCss(text.secondary))
    put("--separator", argbToCss(border.subtle))
    put("--terminal-bg", argbToCss(terminal.bg))
    put("--toolbar-shadow", "0 2px 8px ${argbToCss(chrome.shadow)}")
}

// `ResolvedPalette.toCssVarMap()` now lives in `toolkit-web` and is
// imported at the top of this file. The previous in-package definition
// (~70 lines listing every `--t-*` token) was identical to the toolkit's
// and has been removed.

/**
 * CSS custom property names used by the "active indicators" section.
 *
 * These properties are always set on `:root` so they are available in
 * every section container regardless of per-section scoping.
 */
private val activeAccentProps = listOf(
    "--t-active-accent",
    "--t-active-accentSoft",
    "--t-active-accentGlow",
    "--t-active-sidebarActiveBg",
    "--t-active-sidebarActiveText",
)

/**
 * Returns CSS custom properties derived from the "active indicators"
 * section theme, or an empty map if no override is set.
 *
 * The returned vars are set on `:root` by [applyAppearanceClass] so
 * the active-indicator CSS rules can reference them with a fallback
 * chain back to the per-section accent colour.
 *
 * @return map of CSS property name to CSS colour string, or empty
 * @see applyAppearanceClass
 */
fun activeAccentCssVars(): Map<String, String> {
    val state = appVm.stateFlow.value
    if (state.paneSchemes["active"] == null) return emptyMap()
    val p = sectionPalette("active")
    return mapOf(
        "--t-active-accent" to argbToCss(p.accent.primary),
        "--t-active-accentSoft" to argbToCss(p.accent.primarySoft),
        "--t-active-accentGlow" to argbToCss(p.accent.primaryGlow),
        "--t-active-sidebarActiveBg" to argbToCss(p.sidebar.activeBg),
        "--t-active-sidebarActiveText" to argbToCss(p.sidebar.activeText),
    )
}

/**
 * Clears the `--t-active-*` CSS properties from an element.
 *
 * Called when the active-indicators section theme is cleared so the
 * CSS fallback chain reverts to each section's own accent colour.
 *
 * @param el the element to clear (typically `:root`)
 */
fun clearActiveAccentVars(el: org.w3c.dom.HTMLElement) {
    for (prop in activeAccentProps) el.style.removeProperty(prop)
}

