/**
 * Main application ViewModel that combines window layout, per-session state,
 * and UI settings into a single observable [AppBackingViewModel.State].
 *
 * Platform UI layers (Compose on Android, SwiftUI on iOS, xterm.js on web)
 * collect [AppBackingViewModel.stateFlow] to drive their rendering. Layout
 * mutation methods delegate to a [LayoutViewModel]; settings mutations are
 * routed through a [SettingsViewModel]; server-pushed dynamic state and
 * envelope streams are folded in by a [SessionStateViewModel].
 *
 * Theme persistence uses the v2 theme system: the dual-slot selection +
 * appearance is persisted under [PersistKeys.THEME_V2_SELECTION] and the user's
 * custom themes under [PersistKeys.THEME_V2_CUSTOM]. Resolution is a direct
 * lookup into the explicit-token [Theme] catalog — no per-pane schemes and no
 * colour calculator.
 *
 * @see SettingsPersister
 * @see SettingsViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.CancellationException
import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.contentOrNull
import se.soderbjorn.lunamux.ClaudeUsageData
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowSocket
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.time.Duration.Companion.seconds

/**
 * Backing ViewModel for the top-level application screen. Merges server-pushed
 * window config, session states, and UI settings into a single [State] flow.
 *
 * @param windowSocket      the live `/window` WebSocket for sending commands.
 * @param windowState       the shared [WindowStateRepository] cache.
 * @param settingsPersister optional callback to persist UI setting changes to
 *   the server; `null` in unit tests.
 */
class AppBackingViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository,
    private val settingsPersister: SettingsPersister? = null,
) {
    private val layout = LayoutViewModel(windowSocket)
    private val settings = SettingsViewModel(settingsPersister)
    private val sessionState = SessionStateViewModel(windowSocket, windowState)

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the entire application UI state. Emitted via
     * [stateFlow] whenever any constituent value changes.
     *
     * @property config             the current window layout, or `null` before
     *   the first server push.
     * @property sessionStates      per-session state labels keyed by session ID.
     * @property pendingApproval    `true` if the device is awaiting approval.
     * @property claudeUsage        latest AI token usage data, if available.
     * @property appearance         the user's light/dark mode preference.
     * @property paneFontSize       per-pane font size override, or `null`.
     * @property paneFontFamily     preset key for the terminal/code font family.
     * @property sidebarWidth       persisted sidebar width in pixels, or `null`.
     * @property sidebarCollapsed   whether the sidebar is currently collapsed.
     * @property headerCollapsed    whether the app header is currently hidden.
     * @property usageBarCollapsed  whether the Claude usage bar is hidden.
     * @property electronCustomTitleBar whether the Electron window hides the
     *   native title bar in favour of themed chrome.
     * @property uiSettingsHydrated `true` once the server has pushed at least
     *   one UiSettings envelope.
     * @property lightThemeName     theme bound to the light slot.
     * @property darkThemeName      theme bound to the dark slot.
     * @property customThemes       the user's custom (cloned/edited) themes.
     * @property favoriteThemeNames names of the user's starred / favorite themes
     *   (the theme picker hoists these to the top of its single list).
     */
    data class State(
        val config: WindowConfig? = null,
        val sessionStates: Map<String, String?> = emptyMap(),
        val pendingApproval: Boolean = false,
        val claudeUsage: ClaudeUsageData? = null,
        val appearance: Appearance = Appearance.Auto,
        val paneFontSize: Int? = null,
        val paneFontFamily: String? = null,
        /** Sidebar / topbar chrome font preset key, or `null` to inherit. */
        val sidebarFontFamily: String? = null,
        /** Sidebar / topbar chrome font size in px, or `null` to inherit. */
        val sidebarFontSizePx: Int? = null,
        /** Tab strip font preset key, or `null` to fall back to sidebar. */
        val tabbarFontFamily: String? = null,
        /** Tab strip font size in px, or `null` to fall back to sidebar. */
        val tabbarFontSizePx: Int? = null,
        /** Pane title (pane header) font preset key, or `null` to fall back to sidebar. */
        val paneHeaderFontFamily: String? = null,
        /** Pane title (pane header) font size in px, or `null` to fall back to sidebar. */
        val paneHeaderFontSizePx: Int? = null,
        val sidebarWidth: Int? = null,
        val sidebarCollapsed: Boolean = false,
        val headerCollapsed: Boolean = false,
        val usageBarCollapsed: Boolean = false,
        val electronCustomTitleBar: Boolean = false,
        val uiSettingsHydrated: Boolean = false,
        val lightThemeName: String = DEFAULT_LIGHT_THEME,
        val darkThemeName: String = DEFAULT_DARK_THEME,
        val customThemes: List<Theme> = emptyList(),
        val favoriteThemeNames: Set<String> = emptySet(),
    ) {
        /** Builds the persisted v2 snapshot from this state. */
        fun toThemeSnapshot(): ThemeSnapshotV2 = ThemeSnapshotV2(
            darkThemeName = darkThemeName,
            lightThemeName = lightThemeName,
            customThemes = customThemes,
            appearance = appearance,
            favorites = favoriteThemeNames.toList(),
        )
    }

    /**
     * Start collecting window state and envelope streams. Long-running; call
     * from a lifecycle-scoped coroutine. Returns when the scope is cancelled.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun run() {
        sessionState.run(
            onDynamic = { dyn ->
                emit(_stateFlow.value.copy(
                    config = dyn.config,
                    sessionStates = dyn.sessionStates,
                    pendingApproval = dyn.pendingApproval,
                ))
            },
            onClaudeUsage = { usage ->
                emit(_stateFlow.value.copy(claudeUsage = usage))
            },
            onUiSettings = { uiSettings ->
                // Echo guard: suppress pushes only within 2 s of a *local*
                // settings write (the server's broadcast of our own POST).
                // Before any local write there is nothing to echo, so the
                // very first push — the connection's initial replay that
                // carries the theme — must always apply (issue #93: mobile
                // showed default colors until a later unrelated push).
                val lastLocal = settings.lastLocalSettingsChange
                if (lastLocal == null || lastLocal.elapsedNow() > 2.seconds) {
                    applyServerUiSettings(uiSettings)
                }
            },
        )
    }

    // ── UI settings mutations ───────────────────────────────────────

    /** Update the light/dark appearance mode and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setAppearance(appearance: Appearance) {
        emit(_stateFlow.value.copy(appearance = appearance))
        persistThemeSnapshot()
    }

    /** Update the per-pane font size and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPaneFontSize(size: Int) {
        emit(_stateFlow.value.copy(paneFontSize = size))
        persistFonts()
    }

    /** Update the terminal/code font-family preset and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPaneFontFamily(key: String) {
        emit(_stateFlow.value.copy(paneFontFamily = key.ifEmpty { null }))
        persistFonts()
    }

    /** Update the sidebar / topbar chrome font preset and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setSidebarFontFamily(key: String) {
        emit(_stateFlow.value.copy(sidebarFontFamily = key.ifEmpty { null }))
        persistFonts()
    }

    /** Update the sidebar / topbar chrome font size and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setSidebarFontSizePx(size: Int) {
        emit(_stateFlow.value.copy(sidebarFontSizePx = size))
        persistFonts()
    }

    /** Update the tab strip font preset and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setTabbarFontFamily(key: String) {
        emit(_stateFlow.value.copy(tabbarFontFamily = key.ifEmpty { null }))
        persistFonts()
    }

    /** Update the tab strip font size and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setTabbarFontSizePx(size: Int) {
        emit(_stateFlow.value.copy(tabbarFontSizePx = size))
        persistFonts()
    }

    /** Update the pane title (pane header) font preset and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPaneHeaderFontFamily(key: String) {
        emit(_stateFlow.value.copy(paneHeaderFontFamily = key.ifEmpty { null }))
        persistFonts()
    }

    /** Update the pane title (pane header) font size and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPaneHeaderFontSizePx(size: Int) {
        emit(_stateFlow.value.copy(paneHeaderFontSizePx = size))
        persistFonts()
    }

    /** Update the sidebar width and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setSidebarWidth(width: Int) {
        emit(_stateFlow.value.copy(sidebarWidth = width))
        settings.persistSetting("sidebarWidth", width.toString())
    }

    /** Collapse or expand the sidebar and persist the preference. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setSidebarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(sidebarCollapsed = collapsed))
        settings.persistSetting("sidebarCollapsed", collapsed.toString())
    }

    /** Collapse or expand the app header and persist the preference. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setHeaderCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(headerCollapsed = collapsed))
        settings.persistSetting("headerCollapsed", collapsed.toString())
    }

    /** Collapse or expand the Claude usage bar and persist the preference. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setUsageBarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(usageBarCollapsed = collapsed))
        settings.persistSetting("usageBarCollapsed", collapsed.toString())
    }

    /** Enable or disable the custom Electron title bar and persist it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setElectronCustomTitleBar(enabled: Boolean) {
        emit(_stateFlow.value.copy(electronCustomTitleBar = enabled))
        persistFonts()
    }

    // ── Layout mutations (delegated to LayoutViewModel) ─────────────

    /** Tell the server to switch to tab [tabId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setActiveTab(tabId: String) = layout.setActiveTab(tabId)

    /** Focus [paneId] within [tabId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setFocusedPane(tabId: String, paneId: String) = layout.setFocusedPane(tabId, paneId)

    /** Create a new tab with a default terminal pane. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addTab() = layout.addTab()

    /** Close tab [tabId] and all panes within it. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun closeTab(tabId: String) = layout.closeTab(tabId)

    /** Rename tab [tabId] to [title]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun renameTab(tabId: String, title: String) = layout.renameTab(tabId, title)

    /** Reorder [tabId] relative to [targetTabId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) =
        layout.moveTab(tabId, targetTabId, before)

    /** Close pane [paneId] (terminal, file-browser, or git). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun closePane(paneId: String) = layout.closePane(paneId)

    /** Close all panes that share [sessionId] and terminate the PTY. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun closeSession(sessionId: String) = layout.closeSession(sessionId)

    /** Set a custom title for [paneId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun renamePane(paneId: String, title: String) = layout.renamePane(paneId, title)

    /** Add a new terminal pane to [tabId] starting in [cwd]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addPaneToTab(tabId: String, cwd: String? = null) =
        layout.addPaneToTab(tabId, cwd)

    /** Add a file-browser pane to [tabId] rooted at [cwd]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addFileBrowserToTab(tabId: String, cwd: String? = null) =
        layout.addFileBrowserToTab(tabId, cwd)

    /** Add a git pane to [tabId] rooted at [cwd]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addGitToTab(tabId: String, cwd: String? = null) =
        layout.addGitToTab(tabId, cwd)

    /** Add a linked pane to [tabId] sharing [targetSessionId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun addLinkToTab(tabId: String, targetSessionId: String) =
        layout.addLinkToTab(tabId, targetSessionId)

    /** Update the geometry (position and size) for [paneId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setPaneGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) =
        layout.setPaneGeom(paneId, x, y, width, height)

    /** Bring [paneId] to the front of its tab's z-order. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun raisePane(paneId: String) = layout.raisePane(paneId)

    /** Move [paneId] from its current tab to [targetTabId]. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun movePaneToTab(paneId: String, targetTabId: String) =
        layout.movePaneToTab(paneId, targetTabId)

    /** Ask the server to re-send Claude AI usage data. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun refreshUsage() = layout.refreshUsage()

    /** Request the server to open the settings panel. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun openSettings() = layout.openSettings()

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Optional observer invoked with the raw settings JSON each time
     * [applyServerUiSettings] runs (initial REST hydration and every live
     * [se.soderbjorn.lunamux.WindowEnvelope.UiSettings] push that passes
     * the local-change guard).
     *
     * Used by the web frontend to forward settings keys the [State] model
     * doesn't track — currently the custom hotkey bindings blob
     * (`darkness.hotkeyBindings`), which is handed to the darkness-toolkit
     * so key rebindings sync live across clients. Mobile apps leave this
     * `null` (they have no shortcut support).
     */
    var onServerUiSettingsApplied: ((kotlinx.serialization.json.JsonObject) -> Unit)? = null

    /**
     * Apply a server-pushed UI settings JSON object to the local state.
     * Called when a [se.soderbjorn.lunamux.WindowEnvelope.UiSettings]
     * envelope arrives.
     */
    fun applyServerUiSettings(settingsJson: kotlinx.serialization.json.JsonObject) {
        val cur = _stateFlow.value
        emit(settings.applyServerUiSettings(cur, settingsJson))
        onServerUiSettingsApplied?.invoke(settingsJson)
    }

    /**
     * Mirror a toolkit-side v2 selection-blob write into the local [State]
     * *immediately*, without re-persisting. Called from the web persister
     * adapter when a toolkit-side mutation that touches the selection blob is
     * about to POST to the server, so chrome and app state stay in lockstep.
     *
     * Reads `appearance` + slot names out of the [PersistKeys.THEME_V2_SELECTION]
     * blob JSON.
     *
     * @param blobJson the stringified selection blob as persisted.
     */
    fun applyToolkitUiSettingsBlob(blobJson: String) {
        settings.stampLocalChange()
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(blobJson)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        val cur = _stateFlow.value
        val appearanceName = (obj["appearance"]
            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        val newAppearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: cur.appearance
        val light = (obj["lightThemeName"]
            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: cur.lightThemeName
        val dark = (obj["darkThemeName"]
            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: cur.darkThemeName
        if (cur.appearance == newAppearance && cur.lightThemeName == light &&
            cur.darkThemeName == dark) return
        emit(cur.copy(appearance = newAppearance, lightThemeName = light, darkThemeName = dark))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }

    // ── Theme slots + custom themes ─────────────────────────────────

    /** Persist the chosen theme for the Light slot. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setLightThemeName(name: String) {
        emit(_stateFlow.value.copy(lightThemeName = name))
        persistThemeSnapshot()
    }

    /** Persist the chosen theme for the Dark slot. */
    @Throws(CancellationException::class, Exception::class)
    suspend fun setDarkThemeName(name: String) {
        emit(_stateFlow.value.copy(darkThemeName = name))
        persistThemeSnapshot()
    }

    /** Insert or replace a custom theme (by name). */
    @Throws(CancellationException::class, Exception::class)
    suspend fun saveCustomTheme(theme: Theme) {
        val cur = _stateFlow.value
        val next = cur.customThemes.filterNot { it.name == theme.name } + theme
        emit(cur.copy(customThemes = next))
        persistThemeSnapshot()
    }

    /**
     * Delete a custom theme by name, falling the light/dark slots back to the
     * built-in defaults when they pointed at the removed theme, and dropping it
     * from the favorites set.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun deleteCustomTheme(name: String) {
        val cur = _stateFlow.value
        val nextThemes = cur.customThemes.filterNot { it.name == name }
        val nextLight = if (cur.lightThemeName == name) DEFAULT_LIGHT_THEME else cur.lightThemeName
        val nextDark = if (cur.darkThemeName == name) DEFAULT_DARK_THEME else cur.darkThemeName
        emit(cur.copy(
            customThemes = nextThemes,
            lightThemeName = nextLight,
            darkThemeName = nextDark,
            favoriteThemeNames = cur.favoriteThemeNames - name,
        ))
        persistThemeSnapshot()
    }

    /**
     * Toggle whether [name] is starred / favorited and persist the change.
     * Called from the web Theme Manager's per-card star button (via
     * `LunamuxThemeManagerHost.toggleFavorite`). The starred set is persisted
     * under [PersistKeys.THEME_V2_FAVORITES] and synced to every connected client.
     *
     * @param name the theme to star or unstar.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun toggleThemeFavorite(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteThemeNames) {
            cur.favoriteThemeNames - name
        } else {
            cur.favoriteThemeNames + name
        }
        emit(cur.copy(favoriteThemeNames = next))
        persistThemeSnapshot()
    }

    /**
     * Persist the v2 theme snapshot: the custom themes under
     * [PersistKeys.THEME_V2_CUSTOM] (shared across Darkness apps), the dual-slot
     * selection + appearance under [PersistKeys.THEME_V2_SELECTION] (per-app),
     * and the starred-theme names under [PersistKeys.THEME_V2_FAVORITES]
     * (per-app), in a single batch.
     */
    private suspend fun persistThemeSnapshot() {
        val snap = _stateFlow.value.toThemeSnapshot()
        settings.persistSettings(mapOf(
            PersistKeys.THEME_V2_CUSTOM to snap.customThemesJson(),
            PersistKeys.THEME_V2_SELECTION to snap.selectionJson(),
            PersistKeys.THEME_V2_FAVORITES to snap.favoritesJson(),
        ))
    }

    /** Persist font + per-app toggle preferences (non-theme settings). */
    private suspend fun persistFonts() {
        val s = _stateFlow.value
        settings.persistSettings(buildMap {
            s.paneFontFamily?.let { put("monoFontFamily", it) }
            s.paneFontSize?.let { put("monoFontSizePx", it.toString()) }
            s.sidebarFontFamily?.let { put("sidebarFontFamily", it) }
            s.sidebarFontSizePx?.let { put("sidebarFontSizePx", it.toString()) }
            s.tabbarFontFamily?.let { put("tabbarFontFamily", it) }
            s.tabbarFontSizePx?.let { put("tabbarFontSizePx", it.toString()) }
            s.paneHeaderFontFamily?.let { put("paneHeaderFontFamily", it) }
            s.paneHeaderFontSizePx?.let { put("paneHeaderFontSizePx", it.toString()) }
            put("electronCustomTitleBar", s.electronCustomTitleBar.toString())
        })
    }
}

/**
 * Resolves the flat [ResolvedTheme] for this state snapshot and the host's
 * current dark-mode flag.
 *
 * @param systemIsDark whether the host OS is currently in dark mode.
 * @return the resolved 19-token theme for the active slot.
 */
fun AppBackingViewModel.State.resolvedTheme(systemIsDark: Boolean): ResolvedTheme =
    toThemeSnapshot().resolve(systemIsDark)
