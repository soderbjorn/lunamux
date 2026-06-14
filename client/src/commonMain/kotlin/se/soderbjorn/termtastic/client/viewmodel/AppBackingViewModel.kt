/**
 * Main application ViewModel that combines window layout, per-session state,
 * and UI settings into a single observable [AppBackingViewModel.State].
 *
 * Platform UI layers (Compose on Android, SwiftUI on iOS, xterm.js on web)
 * collect [AppBackingViewModel.stateFlow] to drive their rendering. Layout
 * mutation methods (tab/pane CRUD, split, float, pop-out) delegate to a
 * [LayoutViewModel]; settings mutations are routed through a
 * [SettingsViewModel]; server-pushed dynamic state and envelope streams
 * are folded in by a [SessionStateViewModel]. This file is a thin
 * coordinator that preserves the original public API of
 * `AppBackingViewModel` so the iOS Kotlin/Native binary surface and the
 * web/Android consumers do not have to change.
 *
 * Theme persistence is routed through `darkness-toolkit`'s
 * [ThemeSnapshot.encodeAsStringMap] codec — the snapshot's flat-KV
 * encoding feeds termtastic's existing server `SettingsPersister` wire
 * (`persistSettings(Map<String, String>)`).
 *
 * @see se.soderbjorn.termtastic.client.WindowSocket
 * @see SettingsPersister
 * @see LayoutViewModel
 * @see SettingsViewModel
 * @see SessionStateViewModel
 */
package se.soderbjorn.termtastic.client.viewmodel

import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.contentOrNull
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.darkness.core.CustomScheme
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_LIGHT_THEME_NAME
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.ResolvedPalette
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.ThemeSnapshot
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.darkness.core.defaultThemes
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import kotlin.time.Duration.Companion.seconds

/**
 * Backing ViewModel for the top-level application screen. Merges server-pushed
 * window config, session states, and UI settings into a single [State] flow.
 *
 * @param windowSocket      the live `/window` WebSocket for sending commands.
 * @param windowState       the shared [WindowStateRepository] cache.
 * @param settingsPersister optional callback to persist UI setting changes to
 *   the server; `null` in unit tests.
 * @param paneToSection     the host app's pane → universal-section map (e.g.
 *   `termtasticPanes` from the web client). Threaded into
 *   [SettingsViewModel.refreshActiveTheme] so the toolkit resolver can
 *   compute per-pane scheme assignments. Defaults to empty so non-web
 *   consumers (Android UI fetches its own [UiSettings]; iOS doesn't paint
 *   per-pane) keep compiling without supplying a map.
 */
class AppBackingViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository,
    private val settingsPersister: SettingsPersister? = null,
    private val paneToSection: Map<String, String> = emptyMap(),
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
     * @property theme              the active terminal colour theme (global default).
     * @property appearance         the user's light/dark mode preference.
     * @property paneFontSize       per-pane font size override, or `null` for default.
     * @property paneFontFamily     preset key for the terminal/code font family
     *   (e.g. `"menlo"`, `"jetbrainsMono"`), or `null` to use the system default
     *   monospace stack. Applied to xterm.js terminals and CSS-based monospace
     *   surfaces (git diff, markdown code blocks) on the web client. Other
     *   clients ignore the value.
     * @property sidebarWidth       persisted sidebar width in pixels, or `null`.
     * @property sidebarCollapsed    whether the sidebar is currently collapsed.
     * @property headerCollapsed     whether the app header (tab bar + toolbar)
     *   is currently hidden.
     * @property usageBarCollapsed   whether the Claude usage bar is currently
     *   hidden.
     * @property desktopNotifications whether desktop notifications are enabled.
     * @property electronCustomTitleBar whether the Electron window should hide
     *   the native OS title bar in favour of the themed chrome.
     * @property uiSettingsHydrated `true` once the server has pushed at least
     *   one UiSettings envelope.
     * @property paneSchemes        per-pane resolved colour schemes,
     *   keyed by concrete pane name (e.g. `"sidebar"`, `"terminal"`, `"git"`).
     *   Populated by [refreshActiveTheme] from the active theme's section
     *   assignments via the toolkit's [resolveActiveTheme] resolver.
     *   Open-ended: holds whichever pane names the host app declared in its
     *   `paneToSection` map. Use [schemeForPane] for fallback-aware lookup.
     */
    data class State(
        val config: WindowConfig? = null,
        val sessionStates: Map<String, String?> = emptyMap(),
        val pendingApproval: Boolean = false,
        val claudeUsage: ClaudeUsageData? = null,
        val theme: ColorScheme = recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME },
        // Match the toolkit's [UiSettings.defaults] so the chrome and the
        // terminal pane agree on the initial appearance before any
        // persisted settings exist. If termtastic chose a different
        // default, the chrome would paint from the toolkit's Auto and the
        // terminal pane from termtastic's override — visibly inconsistent
        // until the user toggles, which writes a blob both sides can read.
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
        val sidebarWidth: Int? = null,
        val sidebarCollapsed: Boolean = false,
        val headerCollapsed: Boolean = false,
        val usageBarCollapsed: Boolean = false,
        val desktopNotifications: Boolean = true,
        val electronCustomTitleBar: Boolean = false,
        val paneSchemes: Map<String, ColorScheme> = emptyMap(),
        val uiSettingsHydrated: Boolean = false,
        // Default slot bindings point at a single ColorScheme rather than
        // a multi-section Theme bundle. resolveActiveTheme falls back to
        // `Theme(name = slotName, colorScheme = slotName)` when the name
        // doesn't match any bundle, so every section ends up with the
        // same palette — chrome, terminal pane, sidebar, tabs all
        // uniform. The toolkit's bundled defaults
        // ([DEFAULT_LIGHT_THEME_NAME] = "Paper & Ink",
        // [DEFAULT_DARK_THEME_NAME] = "Neon Circuit") deliberately split
        // the palette across sections, which reads as visual inconsistency
        // for a single-pane terminal app on first install.
        val lightThemeName: String = DEFAULT_THEME_NAME,
        val darkThemeName: String = DEFAULT_THEME_NAME,
        val favoriteThemes: List<String> = emptyList(),
        val favoriteSchemes: List<String> = emptyList(),
        val customSchemes: Map<String, CustomScheme> = emptyMap(),
        val customThemes: Map<String, Theme> = emptyMap(),
    )

    /**
     * Start collecting window state and envelope streams. This is a
     * long-running suspend function — call it from a lifecycle-scoped
     * coroutine. It returns only when the enclosing scope is cancelled.
     */
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
                val elapsed = settings.lastLocalSettingsChange.elapsedNow()
                if (elapsed > 2.seconds) {
                    applyServerUiSettings(uiSettings)
                }
            },
        )
    }

    // ── UI settings mutations ───────────────────────────────────────

    /** Update the terminal colour theme and persist the choice to the server. */
    suspend fun setTheme(theme: ColorScheme) {
        emit(_stateFlow.value.copy(theme = theme))
        persistUiSettings()
    }

    /** Update the light/dark appearance mode and persist it. */
    suspend fun setAppearance(appearance: Appearance) {
        emit(_stateFlow.value.copy(appearance = appearance))
        persistUiSettings()
    }

    /** Update the per-pane font size and persist it. */
    suspend fun setPaneFontSize(size: Int) {
        emit(_stateFlow.value.copy(paneFontSize = size))
        persistThemeSnapshot()
    }

    /** Update the terminal/code font-family preset and persist it. */
    suspend fun setPaneFontFamily(key: String) {
        emit(_stateFlow.value.copy(paneFontFamily = key.ifEmpty { null }))
        persistThemeSnapshot()
    }

    /** Update the sidebar / topbar chrome font preset and persist it. */
    suspend fun setSidebarFontFamily(key: String) {
        emit(_stateFlow.value.copy(sidebarFontFamily = key.ifEmpty { null }))
        persistThemeSnapshot()
    }

    /** Update the sidebar / topbar chrome font size and persist it. */
    suspend fun setSidebarFontSizePx(size: Int) {
        emit(_stateFlow.value.copy(sidebarFontSizePx = size))
        persistThemeSnapshot()
    }

    /** Update the tab strip font preset and persist it. */
    suspend fun setTabbarFontFamily(key: String) {
        emit(_stateFlow.value.copy(tabbarFontFamily = key.ifEmpty { null }))
        persistThemeSnapshot()
    }

    /** Update the tab strip font size and persist it. */
    suspend fun setTabbarFontSizePx(size: Int) {
        emit(_stateFlow.value.copy(tabbarFontSizePx = size))
        persistThemeSnapshot()
    }

    /** Update the sidebar width and persist it. */
    suspend fun setSidebarWidth(width: Int) {
        emit(_stateFlow.value.copy(sidebarWidth = width))
        settings.persistSetting("sidebarWidth", width.toString())
    }

    /** Collapse or expand the sidebar and persist the preference. */
    suspend fun setSidebarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(sidebarCollapsed = collapsed))
        settings.persistSetting("sidebarCollapsed", collapsed.toString())
    }

    /** Collapse or expand the app header and persist the preference. */
    suspend fun setHeaderCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(headerCollapsed = collapsed))
        settings.persistSetting("headerCollapsed", collapsed.toString())
    }

    /** Collapse or expand the Claude usage bar and persist the preference. */
    suspend fun setUsageBarCollapsed(collapsed: Boolean) {
        emit(_stateFlow.value.copy(usageBarCollapsed = collapsed))
        settings.persistSetting("usageBarCollapsed", collapsed.toString())
    }

    /** Enable or disable desktop notifications and persist the preference. */
    suspend fun setDesktopNotifications(enabled: Boolean) {
        emit(_stateFlow.value.copy(desktopNotifications = enabled))
        persistThemeSnapshot()
    }

    /** Enable or disable the custom Electron title bar and persist it. */
    suspend fun setElectronCustomTitleBar(enabled: Boolean) {
        emit(_stateFlow.value.copy(electronCustomTitleBar = enabled))
        persistThemeSnapshot()
    }

    /**
     * Apply a full theme configuration atomically — main theme plus all
     * per-section pane schemes — in a single **synchronous** state emission.
     * Persistence is fire-and-forget so the caller can run `applyAll()`
     * immediately after this returns without any coroutine involvement.
     *
     * @param theme    the resolved main [ColorScheme] to make active.
     * @param sections map of concrete pane name → resolved scheme (or
     *   `null` to drop that pane's entry). Open-ended; callers
     *   typically pass the toolkit-resolved map straight through.
     */
    fun applyThemeConfiguration(
        theme: ColorScheme,
        sections: Map<String, ColorScheme?>,
    ) {
        val nextPaneSchemes: Map<String, ColorScheme> =
            sections.filterValues { it != null }
                .mapValues { (_, v) -> v!! }
        val newState = _stateFlow.value.copy(theme = theme, paneSchemes = nextPaneSchemes)
        emit(newState)
        val ui = UiSettings(theme = newState.theme, appearance = newState.appearance)
        val batch = buildMap {
            put(PersistKeys.UI_SETTINGS, ui.toJsonString())
            for ((pane, t) in sections) put("theme.$pane", t?.name ?: "")
        }
        settings.fireAndForgetPersistSettings(batch)
    }

    // ── Layout mutations (delegated to LayoutViewModel) ─────────────

    /** Tell the server to switch to tab [tabId]. */
    suspend fun setActiveTab(tabId: String) = layout.setActiveTab(tabId)

    /** Focus [paneId] within [tabId]. */
    suspend fun setFocusedPane(tabId: String, paneId: String) = layout.setFocusedPane(tabId, paneId)

    /** Create a new tab with a default terminal pane. */
    suspend fun addTab() = layout.addTab()

    /** Close tab [tabId] and all panes within it. */
    suspend fun closeTab(tabId: String) = layout.closeTab(tabId)

    /** Rename tab [tabId] to [title]. */
    suspend fun renameTab(tabId: String, title: String) = layout.renameTab(tabId, title)

    /** Reorder [tabId] relative to [targetTabId]. */
    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) =
        layout.moveTab(tabId, targetTabId, before)

    /** Close pane [paneId] (terminal, file-browser, or git). */
    suspend fun closePane(paneId: String) = layout.closePane(paneId)

    /** Close all panes that share [sessionId] and terminate the PTY. */
    suspend fun closeSession(sessionId: String) = layout.closeSession(sessionId)

    /** Set a custom title for [paneId]. */
    suspend fun renamePane(paneId: String, title: String) = layout.renamePane(paneId, title)

    /** Add a new terminal pane to [tabId] starting in [cwd]. */
    suspend fun addPaneToTab(tabId: String, cwd: String? = null) =
        layout.addPaneToTab(tabId, cwd)

    /** Add a file-browser pane to [tabId] rooted at [cwd]. */
    suspend fun addFileBrowserToTab(tabId: String, cwd: String? = null) =
        layout.addFileBrowserToTab(tabId, cwd)

    /** Add a git pane to [tabId] rooted at [cwd]. */
    suspend fun addGitToTab(tabId: String, cwd: String? = null) =
        layout.addGitToTab(tabId, cwd)

    /** Add a linked pane to [tabId] sharing [targetSessionId]. */
    suspend fun addLinkToTab(tabId: String, targetSessionId: String) =
        layout.addLinkToTab(tabId, targetSessionId)

    /** Update the geometry (position and size) for [paneId]. */
    suspend fun setPaneGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) =
        layout.setPaneGeom(paneId, x, y, width, height)

    /** Bring [paneId] to the front of its tab's z-order. */
    suspend fun raisePane(paneId: String) = layout.raisePane(paneId)

    /** Move [paneId] from its current tab to [targetTabId]. */
    suspend fun movePaneToTab(paneId: String, targetTabId: String) =
        layout.movePaneToTab(paneId, targetTabId)

    /** Ask the server to re-send Claude AI usage data. */
    suspend fun refreshUsage() = layout.refreshUsage()

    /** Request the server to open the settings panel. */
    suspend fun openSettings() = layout.openSettings()

    // ── Internal ────────────────────────────────────────────────────

    /**
     * Apply a server-pushed UI settings JSON object to the local state.
     * Called when a [se.soderbjorn.termtastic.WindowEnvelope.UiSettings]
     * envelope arrives.
     */
    fun applyServerUiSettings(settingsJson: kotlinx.serialization.json.JsonObject) {
        val cur = _stateFlow.value
        emit(settings.applyServerUiSettings(cur, settingsJson))
    }

    /**
     * Mirror a toolkit-side `darkness.uiSettings` blob write into the
     * local [State] *immediately*, without re-persisting. Called from the
     * web persister adapter when the toolkit chrome's appearance toggle
     * (or any other toolkit-side mutation that touches that blob) is
     * about to POST to the server.
     *
     * The toolkit paints its chrome from its own local `ui` state the
     * moment the user clicks; the appVm only learns of the change once
     * the server echoes the broadcast back, which adds a perceptible
     * lag (theme-card swatches, editor mode-grouping, anything reading
     * `appVm.stateFlow.value.appearance`). Plumbing the same JSON blob
     * straight into appVm closes that window.
     *
     * Only [State.appearance] is read from the blob — [State.theme] is a
     * derived value computed by `refreshActiveTheme`, see the comment in
     * [SettingsViewModel.applyServerUiSettings].
     *
     * @param blobJson the stringified [se.soderbjorn.darkness.core.UiSettings]
     *   value as it would be persisted by `Persister.write`.
     */
    fun applyToolkitUiSettingsBlob(blobJson: String) {
        // Stamp the local-change time so the 2-second echo guard in [run]
        // suppresses the server's broadcast of this very write. Without
        // this, toolkit-side writes (via SettingsPersisterAdapter →
        // SettingsPersister.putSetting, which bypasses
        // SettingsViewModel.persistSetting where the stamp normally lands)
        // leave the guard cold; rapid appearance-toggle clicks then race
        // their own server echoes, which re-emit state and re-trigger the
        // collector → applyAll → persist → echo cycle, producing a visible
        // flash loop.
        settings.stampLocalChange()
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(blobJson)
                as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        val appearanceName = (obj["appearance"]
            as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        val newAppearance = runCatching { Appearance.valueOf(appearanceName) }
            .getOrNull() ?: return
        val cur = _stateFlow.value
        if (cur.appearance == newAppearance) return
        emit(cur.copy(appearance = newAppearance))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }

    // ── Theme / scheme registries and resolution ────────────────────

    /**
     * Look up a theme (bundle of per-section scheme assignments) by name.
     * Checks custom themes first then built-in [defaultThemes], so a user
     * may override a default by cloning under the same name.
     */
    fun lookupTheme(name: String): Theme? {
        if (name.isEmpty()) return null
        _stateFlow.value.customThemes[name]?.let { return it }
        return defaultThemes.firstOrNull { it.name == name }
    }

    /**
     * Look up a colour scheme by name. Custom schemes take precedence
     * over built-in [recommendedColorSchemes].
     */
    fun lookupScheme(name: String): ColorScheme? {
        if (name.isEmpty()) return null
        _stateFlow.value.customSchemes[name]?.let { return it.toColorScheme() }
        return recommendedColorSchemes.firstOrNull { it.name == name }
    }

    /**
     * Apply the currently-active theme (chosen by [Appearance] +
     * [systemIsDark] via the user's light / dark slot selection) to
     * [State.theme] and the per-pane [State.paneSchemes] map.
     *
     * Resolution is delegated to the toolkit's [resolveActiveTheme] —
     * see [SettingsViewModel.refreshActiveTheme].
     */
    fun refreshActiveTheme(systemIsDark: Boolean) {
        emit(settings.refreshActiveTheme(_stateFlow.value, systemIsDark, paneToSection))
    }

    // ── Light/dark slots, favourites, custom entities ───────────────

    /** Persist the chosen theme for the Light slot. */
    suspend fun setLightThemeName(name: String) {
        emit(_stateFlow.value.copy(lightThemeName = name))
        persistThemeSnapshot()
    }

    /** Persist the chosen theme for the Dark slot. */
    suspend fun setDarkThemeName(name: String) {
        emit(_stateFlow.value.copy(darkThemeName = name))
        persistThemeSnapshot()
    }

    /**
     * Toggle the favourite status of [name] in the theme favourites list.
     * Adds to the end if absent; removes if present.
     */
    suspend fun toggleFavoriteTheme(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteThemes)
            cur.favoriteThemes - name
        else
            cur.favoriteThemes + name
        emit(cur.copy(favoriteThemes = next))
        persistThemeSnapshot()
    }

    /** Reorder favourite themes by replacing the full list. */
    suspend fun setFavoriteThemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteThemes = ordered))
        persistThemeSnapshot()
    }

    /** Toggle the favourite status of a colour scheme. */
    suspend fun toggleFavoriteScheme(name: String) {
        val cur = _stateFlow.value
        val next = if (name in cur.favoriteSchemes)
            cur.favoriteSchemes - name
        else
            cur.favoriteSchemes + name
        emit(cur.copy(favoriteSchemes = next))
        persistThemeSnapshot()
    }

    /** Replace the full ordered list of favourite schemes. */
    suspend fun setFavoriteSchemes(ordered: List<String>) {
        emit(_stateFlow.value.copy(favoriteSchemes = ordered))
        persistThemeSnapshot()
    }

    /** Insert or replace a custom colour scheme by name. */
    suspend fun saveCustomScheme(scheme: CustomScheme) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { put(scheme.name, scheme) }
        emit(cur.copy(customSchemes = next))
        persistThemeSnapshot()
    }

    /**
     * Remove a custom scheme. Drops the name from [State.favoriteSchemes].
     */
    suspend fun deleteCustomScheme(name: String) {
        val cur = _stateFlow.value
        val next = cur.customSchemes.toMutableMap().apply { remove(name) }
        val nextFavs = cur.favoriteSchemes - name
        emit(cur.copy(customSchemes = next, favoriteSchemes = nextFavs))
        persistThemeSnapshot()
    }

    /** Insert or replace a custom theme (bundle). */
    suspend fun saveCustomTheme(theme: Theme) {
        val cur = _stateFlow.value
        val next = cur.customThemes.toMutableMap().apply { put(theme.name, theme) }
        emit(cur.copy(customThemes = next))
        persistThemeSnapshot()
    }

    /**
     * Delete a custom theme by name. Drops it from [State.favoriteThemes]
     * and from the light/dark slots (falling back to defaults when
     * orphaned) before persisting.
     */
    suspend fun deleteCustomTheme(name: String) {
        val cur = _stateFlow.value
        val nextThemes = cur.customThemes.toMutableMap().apply { remove(name) }
        val nextFavs = cur.favoriteThemes - name
        val nextLight = if (cur.lightThemeName == name) DEFAULT_LIGHT_THEME_NAME else cur.lightThemeName
        val nextDark = if (cur.darkThemeName == name) DEFAULT_DARK_THEME_NAME else cur.darkThemeName
        emit(cur.copy(
            customThemes = nextThemes,
            favoriteThemes = nextFavs,
            lightThemeName = nextLight,
            darkThemeName = nextDark,
        ))
        persistThemeSnapshot()
    }

    /**
     * Persist the current [ThemeSnapshot] to the server using the
     * toolkit-canonical wire shape: a single stringified-JSON blob keyed
     * by [PersistKeys.THEME_SNAPSHOT]. Mirrors the toolkit's own
     * `persistUi()` so theme-manager mutations from termtastic and
     * mutations from the toolkit chrome land in the same place on disk.
     */
    private suspend fun persistThemeSnapshot() {
        val obj = _stateFlow.value.toThemeSnapshot().encodeAsJsonObject()
        settings.persistSetting(PersistKeys.THEME_SNAPSHOT, obj.toString())
    }

    /**
     * Persist the current [UiSettings] (active theme + appearance) to the
     * server using the toolkit-canonical wire shape: a single stringified-
     * JSON blob keyed by [PersistKeys.UI_SETTINGS]. The toolkit chrome's
     * appearance-cycle button writes through this same key, so reads on
     * either side see one shared format.
     */
    private suspend fun persistUiSettings() {
        val s = _stateFlow.value
        val ui = UiSettings(theme = s.theme, appearance = s.appearance)
        settings.persistSetting(PersistKeys.UI_SETTINGS, ui.toJsonString())
    }
}

/**
 * Resolves the full semantic palette for this state snapshot.
 *
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the fully resolved [ResolvedPalette]
 */
fun AppBackingViewModel.State.resolvedPalette(systemIsDark: Boolean): ResolvedPalette =
    theme.resolve(appearance, systemIsDark)

/**
 * Resolves the colour scheme for a concrete pane, falling back to the
 * global [State.theme] when the pane has no override.
 *
 * Mirrors [se.soderbjorn.darkness.core.UiSettings.schemeForPane] so call
 * sites that read the live [AppBackingViewModel.State] use the same idiom
 * as those that read a server-fetched [se.soderbjorn.darkness.core.UiSettings].
 *
 * @param pane concrete pane name (e.g. `"sidebar"`, `"terminal"`, `"git"`).
 * @return the resolved [ColorScheme] for that pane.
 */
fun AppBackingViewModel.State.schemeForPane(pane: String): ColorScheme =
    paneSchemes[pane] ?: theme

/**
 * Resolves the semantic palette for a specific app pane, using the
 * pane-specific theme override if set, or falling back to the global theme.
 *
 * @param section concrete pane name (e.g. `"sidebar"`, `"terminal"`,
 *   `"diff"`, `"fileBrowser"`, `"tabs"`, `"chrome"`, `"windows"`,
 *   `"active"`, `"bottomBar"`)
 * @param systemIsDark whether the host OS is currently in dark mode
 * @return the resolved [ResolvedPalette] for that pane
 */
fun AppBackingViewModel.State.sectionPalette(section: String, systemIsDark: Boolean): ResolvedPalette =
    schemeForPane(section).resolve(appearance, systemIsDark)
