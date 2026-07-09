/**
 * Sidebar colour palette shared across all Lunamux client platforms.
 *
 * Native clients (Android Compose, SwiftUI) read a [SidebarThemeColors]
 * extracted from a resolved theme and convert the ARGB [Long] values to their
 * respective `Color` types.
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.darkness.core.ResolvedTheme

/**
 * Sidebar-relevant colour values extracted from a [ResolvedTheme].
 *
 * Use [fromTheme] to construct. Platform UI layers convert these [Long] ARGB
 * values to their native colour types.
 *
 * @property background    sidebar background colour (the `surface` token).
 * @property surface       elevated surface / card colour (the `surfaceAlt` token).
 * @property textPrimary   primary text / heading colour (the `text` token).
 * @property textSecondary secondary / muted text colour (the `textDim` token).
 * @property accentPrimary theme accent colour (the `accent` token).
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
         * Extracts sidebar-relevant colours from a resolved theme.
         *
         * @param theme the fully resolved theme (see [ResolvedTheme]).
         * @return a [SidebarThemeColors] containing the relevant subset.
         */
        fun fromTheme(theme: ResolvedTheme): SidebarThemeColors =
            SidebarThemeColors(
                background = theme.surface,
                surface = theme.surfaceAlt,
                textPrimary = theme.text,
                textSecondary = theme.textDim,
                accentPrimary = theme.accent,
            )
    }
}
