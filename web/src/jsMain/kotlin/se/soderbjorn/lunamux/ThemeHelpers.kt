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
import se.soderbjorn.lunamux.client.viewmodel.resolvedTheme

/** Feature flag: when true, applies a "spiced" variant to dark themes. Currently disabled. */
const val DARK_SPICED = false

// NOTE: there is deliberately no `isLightActive(appearance)` helper here.
// The appearance mode only selects which *slot* of the theme pair is live —
// it says nothing about that slot's polarity, since either slot may hold a
// theme of either polarity. Chrome that needs to know "is the current palette
// light" must ask the resolved palette (`isColorLight(theme.surface)`, as
// applyAppearanceClass does), never the toggle; conflating the two is what put
// white inputs and a white Cancel button on a dark worktree dialog.

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
 * The active world's theme **pair** (dark + light slot names), or `null`
 * when the active world follows the global selection. Set on every config
 * push by [applyActiveWorldTheme]; overrides only the slot names — the
 * appearance mode (Auto/Dark/Light) and the shared custom-theme pool stay
 * global. Purely a client-side paint override: it never persists, so
 * switching to a non-default world can't corrupt the global/default-world
 * selection.
 */
internal var activeWorldTheme: se.soderbjorn.lunamux.WorldThemeSelection? = null

/**
 * The id of the world [activeWorldTheme] was last resolved for. Tracked
 * alongside the pair so [applyActiveWorldTheme] can key its repaint on the
 * *active world identity* changing, not just on the pair *value* changing.
 *
 * This is the fix for issue #127 ("loses track of theme per world"): two
 * worlds can legitimately carry an equal [WorldThemeSelection] (a new world is
 * seeded from the world it was created in, and every follow-global world shares
 * `null`). A pure value-dedup would then treat a world *switch* between them as
 * a no-op and leave the previous world's paint on screen — or, worse, skip the
 * repaint when a config echo for the switch arrives before the target world's
 * own `SetWorldTheme` write has landed. Keying on the world id as well makes the
 * restore deterministic: switching worlds always re-applies.
 */
private var activeWorldThemeWorldId: String? = null

/**
 * The effective theme snapshot to paint with: the global [appVm] snapshot,
 * with its dark/light slot names overridden by [activeWorldTheme] when a
 * world carries its own pair. Appearance + custom themes + favorites come
 * from the global state unchanged.
 *
 * @return the snapshot every paint path resolves against.
 */
internal fun effectiveThemeSnapshot(): ThemeSnapshotV2 {
    val base = appVm.stateFlow.value.toThemeSnapshot()
    val world = activeWorldTheme ?: return base
    return base.copy(
        darkThemeName = world.darkThemeName,
        lightThemeName = world.lightThemeName,
    )
}

/**
 * Resolves the active [ResolvedTheme] for the current appearance mode. Uses
 * the [effectiveThemeSnapshot] so the active world's theme pair (when any)
 * drives the palette; the active slot (light/dark) is chosen by the global
 * appearance against the host's system dark-mode flag.
 *
 * @return the resolved 19-token palette for the current state.
 * @see se.soderbjorn.lunamux.client.viewmodel.resolvedTheme
 */
fun currentResolvedTheme(): ResolvedTheme =
    effectiveThemeSnapshot().resolve(isSystemDark())

/**
 * Adopt the active world's theme pair (if it changed) and repaint. Called
 * on every server config push from the tab-source collector: switching
 * worlds carries a new [WorldConfig.themeSelection], which this overlays
 * onto the global selection and applies via [refreshAndApplyActiveTheme].
 *
 * Repaints when **either** the active world identity **or** the overlaid pair
 * changed, so a world *switch* always re-applies even when the two worlds share
 * an equal (or equally `null`) pair — see [activeWorldThemeWorldId] for why a
 * pure value-dedup was unreliable (issue #127). No-op when both the world id and
 * the pair are unchanged, so ordinary config pushes (a pane moved, a title
 * ticked) still don't repaint the theme.
 *
 * @param config the freshly-pushed server config.
 */
internal fun applyActiveWorldTheme(config: WindowConfig) {
    val world = config.activeWorldOrNull()
    val newWorldId = world?.id
    val newOverride = world?.themeSelection
    if (newWorldId == activeWorldThemeWorldId && newOverride == activeWorldTheme) return
    activeWorldThemeWorldId = newWorldId
    activeWorldTheme = newOverride
    refreshAndApplyActiveTheme()
}

/**
 * Builds the effective [ThemeSnapshotV2] for an **arbitrary** world's theme [selection]
 * (rather than the *active* world's, which [effectiveThemeSnapshot] handles): the global
 * [appVm] snapshot with only its dark/light slot names overridden by [selection]. Appearance
 * mode + custom-theme pool + favorites stay global, exactly as [effectiveThemeSnapshot] does
 * for the active world. When [selection] is `null` the world follows the global slots, so the
 * base snapshot is returned unchanged.
 *
 * Used by the 3D wormhole to resolve the **destination** world's palette while a transit is in
 * flight (the world being cycled to may differ from the one currently active), independent of
 * [activeWorldTheme]. @see resolvedThemeForWorld @see darkResolvedThemeForWorld
 *
 * @param selection the target world's theme pair, or `null` to follow the global slots.
 * @return the snapshot to resolve that world's palette against.
 */
internal fun snapshotForWorld(selection: WorldThemeSelection?): ThemeSnapshotV2 {
    val base = appVm.stateFlow.value.toThemeSnapshot()
    val sel = selection ?: return base
    return base.copy(darkThemeName = sel.darkThemeName, lightThemeName = sel.lightThemeName)
}

/**
 * Resolves an arbitrary world's [ResolvedTheme] for the current appearance mode — the
 * [snapshotForWorld] resolved against the host's system dark-mode flag. The world's slot pair
 * drives the palette; the active slot (light/dark) is chosen by the global appearance.
 *
 * @param selection the target world's theme pair, or `null` to follow the global slots.
 * @return the resolved 19-token palette for that world under the current appearance.
 * @see currentWorldTheme
 */
internal fun resolvedThemeForWorld(selection: WorldThemeSelection?): ResolvedTheme =
    snapshotForWorld(selection).resolve(isSystemDark())

/**
 * Resolves an arbitrary world's **dark-slot** [ResolvedTheme] directly, *ignoring* the
 * appearance setting — so it returns that world's chosen dark theme even when the app is in
 * light mode. Used for the 3D world's **sky** and the sky-derived transit-tunnel glow, which
 * should always read as deep-space dark regardless of the user's light/dark preference. Falls
 * back to the built-in [DEFAULT_DARK_THEME] if the named theme is missing.
 *
 * @param selection the target world's theme pair, or `null` to follow the global dark slot.
 * @return the resolved palette for that world's dark slot.
 * @see currentWorldSkyTheme @see currentDarkResolvedTheme
 */
internal fun darkResolvedThemeForWorld(selection: WorldThemeSelection?): ResolvedTheme {
    val snap = snapshotForWorld(selection)
    val all = allThemes(snap.customThemes)
    val theme = all.firstOrNull { it.name == snap.darkThemeName }
        ?: all.first { it.name == DEFAULT_DARK_THEME }
    return theme.resolve()
}

/**
 * Resolves the **active world's** dark-slot [ResolvedTheme] (via [effectiveThemeSnapshot]),
 * *ignoring* the appearance setting — so it returns the user's chosen dark theme even when the
 * app is currently in light mode. (A [Theme] carries a fixed light/dark [ThemeGroup]; there is
 * no light/dark switch inside one theme, so "the dark theme" means the dark-slot selection.)
 *
 * Used by the 3D world's **sky** ([currentWorldSkyTheme] → [applyWorldSky]) and the sky-derived
 * transit-tunnel glow, which should always read as deep-space dark regardless of the user's
 * light/dark preference. Falls back to the built-in [DEFAULT_DARK_THEME] if the named theme is
 * missing.
 *
 * @return the resolved 19-token palette for the active world's dark slot.
 * @see currentResolvedTheme @see currentWorldSkyTheme @see darkResolvedThemeForWorld
 */
fun currentDarkResolvedTheme(): ResolvedTheme = darkResolvedThemeForWorld(activeWorldTheme)
