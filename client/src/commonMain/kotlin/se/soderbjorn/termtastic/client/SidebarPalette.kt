/**
 * Sidebar colour palette constants shared across all Termtastic client
 * platforms.
 *
 * These ARGB hex values mirror the CSS custom properties defined in
 * `web/src/jsMain/resources/styles.css` (`:root` for dark, `body.appearance-light`
 * for light). Native clients (Android Compose, SwiftUI) convert them to their
 * respective `Color` types.
 */
package se.soderbjorn.termtastic.client

import se.soderbjorn.darkness.core.*

/**
 * Shared ARGB colour constants for the sidebar theme, matching the `:root`
 * and `body.appearance-light` blocks in `web/src/jsMain/resources/styles.css`.
 * Platform-specific UI code converts these to native color types (`Color`
 * on Android, `SwiftUI.Color` on iOS).
 */
object SidebarPalette {
    // ── Dark theme (default) — legacy constants ────────────────────
    // These are kept for backward compatibility. New code should use
    // ResolvedPalette via fromPalette() instead.

    /** Dark-mode sidebar background. */
    @Deprecated("Use ResolvedPalette.sidebar.bg via fromPalette()", level = DeprecationLevel.WARNING)
    const val BACKGROUND = 0xFF1C1C1E
    /** Dark-mode card/surface background. */
    @Deprecated("Use ResolvedPalette.surface.raised via fromPalette()", level = DeprecationLevel.WARNING)
    const val SURFACE = 0xFF2C2C2E
    /** Dark-mode primary text colour. */
    @Deprecated("Use ResolvedPalette.text.primary via fromPalette()", level = DeprecationLevel.WARNING)
    const val TEXT_PRIMARY = 0xFFF5F5F5
    /** Dark-mode secondary/muted text colour. */
    @Deprecated("Use ResolvedPalette.text.secondary via fromPalette()", level = DeprecationLevel.WARNING)
    const val TEXT_SECONDARY = 0xFF8E8E93
    // ── Light theme — legacy constants ─────────────────────────────

    /** Light-mode sidebar background. */
    @Deprecated("Use ResolvedPalette.sidebar.bg via fromPalette()", level = DeprecationLevel.WARNING)
    const val BACKGROUND_LIGHT = 0xFFF5F5F7
    /** Light-mode card/surface background. */
    @Deprecated("Use ResolvedPalette.surface.raised via fromPalette()", level = DeprecationLevel.WARNING)
    const val SURFACE_LIGHT = 0xFFFFFFFF
    /** Light-mode primary text colour. */
    @Deprecated("Use ResolvedPalette.text.primary via fromPalette()", level = DeprecationLevel.WARNING)
    const val TEXT_PRIMARY_LIGHT = 0xFF1C1C1E
    /** Light-mode secondary/muted text colour. */
    @Deprecated("Use ResolvedPalette.text.secondary via fromPalette()", level = DeprecationLevel.WARNING)
    const val TEXT_SECONDARY_LIGHT = 0xFF6E6E73
}

/**
 * Sidebar-relevant colour values extracted from a [se.soderbjorn.darkness.core.ResolvedPalette].
 *
 * Use [fromPalette] to construct.  Platform UI layers convert these
 * [Long] ARGB values to their native colour types.
 *
 * @property background    sidebar background colour
 * @property surface       elevated surface / card colour
 * @property textPrimary   primary text / heading colour
 * @property textSecondary secondary / muted text colour
 * @property accentPrimary theme accent colour (from terminal fg)
 */
data class SidebarThemeColors(
    val background: Long,
    val surface: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val accentPrimary: Long,
) {
    companion object {
        /**
         * Extracts sidebar-relevant colours from a resolved semantic palette.
         *
         * @param palette the fully resolved palette from [se.soderbjorn.darkness.core.resolve]
         * @return a [SidebarThemeColors] containing the relevant subset
         */
        fun fromPalette(palette: se.soderbjorn.darkness.core.ResolvedPalette): SidebarThemeColors =
            SidebarThemeColors(
                background = palette.sidebar.bg,
                surface = palette.surface.raised,
                textPrimary = palette.sidebar.text,
                textSecondary = palette.sidebar.textDim,
                accentPrimary = palette.accent.primary,
            )
    }
}
