/**
 * Theme/appearance resolution for the Android terminal screen.
 *
 * [rememberTerminalPalette] reads the optional centrally-loaded
 * [ResolvedTheme] from [LocalUiSettings] (or fetches + resolves one
 * per-screen as a fallback) for the current system dark/light mode and
 * returns it.
 *
 * [applyTerminalColors] mutates a [TerminalEmulator]'s default colour
 * indices (what SGR 0 / "default" resolves to) so existing rows repaint
 * on the next `onScreenUpdated()`, and sets the [TerminalView]'s own
 * background so the letterbox around the text grid matches.
 *
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunamux.client.LunamuxThemeConfig
import se.soderbjorn.lunamux.client.fetchThemeConfig

/**
 * Resolve the terminal's [ResolvedTheme] for the supplied client + session.
 * Falls back to the default theme config until the central theme (or the
 * per-screen fetch) arrives.
 *
 * @param client     the connected client used to fetch the theme on first run.
 * @param sessionId  used as a remember key so a session change re-fetches.
 */
@Composable
internal fun rememberTerminalPalette(
    client: LunamuxClient,
    sessionId: String,
): ResolvedTheme {
    val centralTheme = LocalUiSettings.current
    // Fallback when no central theme has been provided yet: fetch the
    // dual-slot config and resolve the active slot for the current appearance.
    var localConfig by remember(sessionId) { mutableStateOf<LunamuxThemeConfig?>(null) }
    LaunchedEffect(client, sessionId) {
        if (centralTheme == null) {
            localConfig = client.fetchThemeConfig()
        }
    }
    val systemIsDark = isSystemInDarkTheme()
    val defaultConfig = remember { LunamuxThemeConfig.defaults() }
    return remember(centralTheme, localConfig, systemIsDark) {
        centralTheme ?: (localConfig ?: defaultConfig).resolve(systemIsDark)
    }
}

/**
 * Paint the terminal with the resolved theme. Mutates the emulator's
 * default foreground/background and cursor colour indices so existing
 * rows repaint on the next onScreenUpdated(), and sets the view's own
 * background so the letterbox around the text grid matches.
 */
internal fun applyTerminalColors(
    view: TerminalView,
    emulator: TerminalEmulator,
    theme: ResolvedTheme,
) {
    val fg = theme.text.toInt()
    val bg = theme.bg.toInt()
    val cursor = theme.accent.toInt()
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = fg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = bg
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor
    view.setBackgroundColor(bg)
}
