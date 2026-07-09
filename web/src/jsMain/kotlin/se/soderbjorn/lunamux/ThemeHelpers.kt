/**
 * Theme-related utility functions for the Lunamux web frontend.
 *
 * Post theme-system rewrite, a single flat [ResolvedTheme] (19 semantic
 * tokens) is the source of truth — there is no per-pane scheme map and no
 * colour calculator. These helpers resolve the active [ResolvedTheme] from the
 * global [appVm] state and translate it for the DOM (`--t-*` CSS vars via the
 * toolkit's [applyTheme]) and xterm.js.
 *
 * @see buildXtermTheme
 * @see applyAppearanceClass
 * @see se.soderbjorn.lunamux.client.viewmodel.resolvedTheme
 */
package se.soderbjorn.lunamux

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.lunamux.client.viewmodel.resolvedTheme

/** Feature flag: when true, applies a "spiced" variant to dark themes. Currently disabled. */
const val DARK_SPICED = false

/**
 * Determines whether the light variant should be active based on the current
 * appearance setting. Convenience inverse of toolkit-web's [isDarkActive] so
 * existing Lunamux call sites stay terse.
 *
 * @param appearance the user's selected appearance mode
 * @return true if light mode should be used
 */
fun isLightActive(appearance: Appearance): Boolean = !isDarkActive(appearance)

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
 * Resolves the active [ResolvedTheme] for the current appearance mode, using
 * the global [appVm] state. The active slot (light/dark) is chosen by the
 * appearance setting against the host's system dark-mode flag.
 *
 * @return the resolved 19-token palette for the current state.
 * @see se.soderbjorn.lunamux.client.viewmodel.resolvedTheme
 */
fun currentResolvedTheme(): ResolvedTheme {
    val state = appVm.stateFlow.value
    return state.resolvedTheme(isSystemDark())
}
