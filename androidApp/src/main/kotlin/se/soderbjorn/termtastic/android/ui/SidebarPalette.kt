/**
 * Sidebar colour palette and shared [UiSettings] provider for the Termtastic
 * Android app.
 *
 * Derives adaptive colours from the semantic [ResolvedPalette] system using
 * the user's selected theme from [UiSettings]. When no settings have been
 * loaded yet (e.g. before the first server fetch), falls back to the default
 * Neon Green theme.
 *
 * [LocalUiSettings] is a [CompositionLocal] provided at the app root by
 * [se.soderbjorn.termtastic.android.ui.TermtasticApp] so that all screens
 * can access the user's theme settings without fetching independently.
 *
 * @see se.soderbjorn.darkness.core.ResolvedPalette
 * @see se.soderbjorn.termtastic.resolve
 * @see se.soderbjorn.termtastic.client.UiSettings
 */
package se.soderbjorn.termtastic.android.ui

import se.soderbjorn.darkness.core.*

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.darkness.core.UiSettings

/**
 * Composition-local providing the user's [UiSettings] fetched from the server.
 *
 * Provided by [TermtasticApp] after a successful connection; defaults to
 * `null` (which causes palette accessors to fall back to the Neon Green theme).
 *
 * @see TermtasticApp
 */
val LocalUiSettings = compositionLocalOf<UiSettings?> { null }

/** The default theme used when no [UiSettings] have been loaded yet. */
private val defaultTheme = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }

/**
 * Resolves the sidebar palette for the current theme and system appearance.
 *
 * Uses the user's selected sidebar section theme (or global theme) from
 * [LocalUiSettings], respecting the appearance preference. Falls back to
 * the default Neon Green theme when settings are not yet available.
 *
 * @return the resolved palette for the sidebar section
 */
@Composable @ReadOnlyComposable
private fun sidebarPalette(): ResolvedPalette {
    val settings = LocalUiSettings.current
    val systemIsDark = isSystemInDarkTheme()
    val theme = settings?.schemeForPane("sidebar") ?: defaultTheme
    val appearance = settings?.appearance ?: Appearance.Auto
    return theme.resolve(appearance, systemIsDark)
}

/** Adaptive sidebar background colour, derived from the semantic palette. */
internal val SidebarBackground: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.bg)

/** Adaptive sidebar surface/card colour for elevated elements like top bars. */
internal val SidebarSurface: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().surface.raised)

/** Adaptive primary text colour for sidebar headings and labels. */
internal val SidebarTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.text)

/** Adaptive secondary text colour for sidebar body text and subdued labels. */
internal val SidebarTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().sidebar.textDim)

/** Theme accent colour derived from the terminal foreground. */
internal val SidebarAccent: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().accent.primary)

/** Semantic warn colour, used by the waiting-for-input state indicator. */
internal val SidebarWarn: Color
    @Composable @ReadOnlyComposable
    get() = Color(sidebarPalette().semantic.warn)

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
