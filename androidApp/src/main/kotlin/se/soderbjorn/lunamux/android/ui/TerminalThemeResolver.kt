/**
 * Theme/appearance resolution for the Android terminal screen.
 *
 * [rememberTerminalPalette] reads the optional centrally-loaded
 * [ResolvedTheme] from [LocalUiSettings] (or fetches + resolves one
 * per-screen as a fallback) for the current system dark/light mode and
 * returns it.
 *
 * [applyDefaultColors] mutates a [TerminalEmulator]'s default colour
 * indices (what SGR 0 / "default" resolves to) so existing rows repaint on the
 * next paint; [applyTerminalColors] adds the [TerminalView]'s own background so
 * the letterbox around the full-screen text grid matches. The headless
 * thumbnail emulators need the colour half without a view, so they call
 * [applyDefaultColors] directly (see [MiniTerminalRegistry.setDefaultColors]) —
 * one derivation of the theme trio, one mutation, three call sites.
 *
 * @see TerminalScreen
 * @see MiniTerminalRegistry
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
import se.soderbjorn.lunula.core.ResolvedTheme
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
 * Write the resolved theme's default foreground/background/cursor into
 * [emulator]'s colour table — the three slots SGR 0 and "default" resolve
 * through, so existing rows repaint in the theme on the next paint.
 *
 * The single place that knows how a [ResolvedTheme] maps onto the emulator's
 * palette. Called by [applyTerminalColors] for the full-screen view's emulator,
 * and by [MiniTerminalRegistry] for each headless thumbnail emulator (which has
 * no view, and which must re-apply after every RIS the server sends).
 *
 * Caller contract for the registry: run this on the emulator's own dispatcher
 * while holding its lock.
 *
 * @param emulator the emulator whose colour table is themed.
 * @param theme    the resolved theme to take text/bg/accent from.
 */
internal fun applyDefaultColors(
    emulator: TerminalEmulator,
    theme: ResolvedTheme,
) {
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = theme.text.toInt()
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = theme.bg.toInt()
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = theme.accent.toInt()
}

/**
 * Paint the full-screen terminal with the resolved theme: [applyDefaultColors]
 * for the emulator's default colour indices, plus the view's own background so
 * the letterbox around the text grid matches.
 *
 * @param view     the terminal view whose background is set.
 * @param emulator the view's emulator, themed via [applyDefaultColors].
 * @param theme    the resolved theme to apply.
 */
internal fun applyTerminalColors(
    view: TerminalView,
    emulator: TerminalEmulator,
    theme: ResolvedTheme,
) {
    applyDefaultColors(emulator, theme)
    view.setBackgroundColor(theme.bg.toInt())
}
