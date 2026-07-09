/**
 * Sidebar colour palette and shared [ResolvedTheme] provider for the Lunamux
 * Android app.
 *
 * Derives adaptive colours from the flat [ResolvedTheme] produced by the user's
 * selected theme. When no theme has been resolved yet (e.g. before the first
 * server fetch), the accessors fall back to neutral defaults.
 *
 * [LocalUiSettings] is a [CompositionLocal] provided at the app root by
 * [se.soderbjorn.lunamux.android.ui.LunamuxApp] so that all screens
 * can access the resolved theme without fetching independently.
 *
 * @see se.soderbjorn.darkness.core.ResolvedTheme
 * @see se.soderbjorn.lunamux.client.LunamuxThemeConfig
 */
package se.soderbjorn.lunamux.android.ui

import se.soderbjorn.darkness.core.*

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.darkness.core.ResolvedTheme

/**
 * Composition-local providing the user's resolved [ResolvedTheme].
 *
 * Provided by [LunamuxApp] after a successful connection; defaults to
 * `null` (which causes palette accessors to fall back to neutral colours).
 *
 * @see LunamuxApp
 */
val LocalUiSettings = compositionLocalOf<ResolvedTheme?> { null }

/** Neutral fallback colours used before a theme has been resolved. */
private const val FALLBACK_SURFACE: Long = 0xFF2C2C2E
private const val FALLBACK_SURFACE_ALT: Long = 0xFF3A3A3C
private const val FALLBACK_BORDER: Long = 0x33FFFFFF
private const val FALLBACK_TEXT: Long = 0xFFF5F5F5
private const val FALLBACK_TEXT_DIM: Long = 0xFF8E8E93
private const val FALLBACK_ACCENT: Long = 0xFF4DC8F5 // Lunamux Dark accent (cyan)
private const val FALLBACK_WARN: Long = 0xFFF4B869

/** Adaptive sidebar background colour, derived from the resolved theme's `surface`. */
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.surface ?: FALLBACK_SURFACE).toInt())

/** Adaptive sidebar surface/card colour for elevated elements like top bars. */
internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.surface ?: FALLBACK_SURFACE).toInt())

/**
 * Adaptive elevated surface (`surfaceAlt`). Backs the overview mini-pane title
 * bar so it reads as a distinct strip above the pane content (web parity).
 */
internal val SidebarSurfaceAlt: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.surfaceAlt ?: FALLBACK_SURFACE_ALT).toInt())

/** Adaptive divider/border colour (`border`), e.g. a hairline under a title bar. */
internal val SidebarBorder: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.border ?: FALLBACK_BORDER).toInt())

/** Adaptive primary text colour for sidebar headings and labels. */
internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.text ?: FALLBACK_TEXT).toInt())

/**
 * Adaptive brightest text colour, from the resolved theme's `textBright` token.
 *
 * Used for prominent screen headings (e.g. the "News & Updates" top app bar
 * title) so they read as crisp white on dark themes — matching the web modal
 * titles, which use the same brightest token. Falls back to [FALLBACK_TEXT].
 */
internal val SidebarTextBright: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.textBright ?: FALLBACK_TEXT).toInt())

/** Adaptive secondary text colour for sidebar body text and subdued labels. */
internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.textDim ?: FALLBACK_TEXT_DIM).toInt())

/** Theme accent colour from the resolved theme's `accent` token. */
internal val SidebarAccent: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.accent ?: FALLBACK_ACCENT).toInt())

/** Semantic warn colour, used by the waiting-for-input state indicator. */
internal val SidebarWarn: Color
    @Composable @ReadOnlyComposable
    get() = Color((LocalUiSettings.current?.warn ?: FALLBACK_WARN).toInt())

/**
 * Outlined text-field colours derived from the resolved sidebar palette.
 *
 * Used by the host/tab/rename [androidx.compose.material3.AlertDialog]s so
 * their input fields (border, label, cursor, text) follow the user's theme
 * instead of the default Material accent, matching the themed dialog surface.
 *
 * @return [TextFieldColors] built from [SidebarAccent], [SidebarTextPrimary]
 *   and [SidebarTextSecondary].
 */
@Composable
internal fun themedTextFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = SidebarTextPrimary,
        unfocusedTextColor = SidebarTextPrimary,
        cursorColor = SidebarAccent,
        focusedBorderColor = SidebarAccent,
        unfocusedBorderColor = SidebarTextSecondary.copy(alpha = 0.4f),
        focusedLabelColor = SidebarAccent,
        unfocusedLabelColor = SidebarTextSecondary,
        focusedSupportingTextColor = SidebarTextSecondary,
        unfocusedSupportingTextColor = SidebarTextSecondary,
        focusedPlaceholderColor = SidebarTextSecondary,
        unfocusedPlaceholderColor = SidebarTextSecondary,
    )
