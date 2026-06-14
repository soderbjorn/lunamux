/**
 * Settings-mutation slice of the application backing model. Owns the
 * [SettingsPersister] and the local-change timestamp guard.
 *
 * Theme parsing / building / resolution lives in `darkness-toolkit`'s
 * [ThemeSnapshot] codec and [resolveActiveTheme] resolver — termtastic
 * routes through those helpers rather than duplicating the logic. The
 * non-theme settings fields (sidebar width, font preferences, header
 * collapse state, …) stay parsed inline here because they are not part of
 * the toolkit snapshot schema.
 *
 * Used internally by [AppBackingViewModel] which delegates its settings
 * methods here; the public surface stays on the coordinator so existing
 * consumers (web, Android Compose, Swift via Kotlin/Native binary) need
 * no call-site changes.
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ResolvedThemeBundle
import se.soderbjorn.darkness.core.ThemeSnapshot
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolveActiveTheme
import kotlin.time.TimeSource

/**
 * Persistence + parsing helper for settings-related fields of
 * [AppBackingViewModel.State]. Holds no flow of its own; the coordinator
 * passes the current state in and receives an updated copy back.
 *
 * @param settingsPersister optional callback to persist UI setting changes
 *   to the server; `null` in unit tests.
 */
internal class SettingsViewModel(
    private val settingsPersister: SettingsPersister?,
) {
    /**
     * Monotonic timestamp of the most recent local settings mutation.
     * Used to suppress server-pushed UiSettings echoes that arrive shortly
     * after the client itself POSTed.
     */
    var lastLocalSettingsChange: TimeSource.Monotonic.ValueTimeMark =
        TimeSource.Monotonic.markNow()
        private set

    /**
     * Stamp [lastLocalSettingsChange] without persisting. Used by callers
     * that route a write through the toolkit's [Persister] adapter (which
     * POSTs directly via [SettingsPersister.putSetting] and so bypasses
     * the persist-helpers below) but still need the 2-second echo guard
     * in [AppBackingViewModel.run] to suppress the round-trip.
     */
    fun stampLocalChange() {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
    }

    /** Stamp the local-change time and persist a single setting. */
    suspend fun persistSetting(key: String, value: String) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSetting(key, value)
    }

    /** Stamp the local-change time and persist a batch of settings. */
    suspend fun persistSettings(settings: Map<String, String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putSettings(settings)
    }

    /** Stamp the local-change time and fire-and-forget a settings batch. */
    fun fireAndForgetPersistSettings(settings: Map<String, String>) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.fireAndForgetPutSettings(settings)
    }

    /** Stamp the local-change time and persist a structured JSON blob. */
    suspend fun persistJsonSettings(obj: JsonObject) {
        lastLocalSettingsChange = TimeSource.Monotonic.markNow()
        settingsPersister?.putJsonSettings(obj)
    }

    /**
     * Apply a server-pushed UI settings JSON object to the given current
     * [cur] state and return the updated copy.
     *
     * Theme-related fields (custom themes, custom schemes, slot bindings,
     * favorites) are parsed via the toolkit's [ThemeSnapshot.fromJsonObject]
     * codec; non-theme fields (font preferences, sidebar/header/usage-bar
     * collapse, desktop notifications, electron titlebar, sidebar width)
     * stay parsed inline because they are not part of the toolkit's
     * snapshot schema.
     *
     * Unknown enum values fall through to the existing values so a bad
     * server blob never crashes.
     */
    fun applyServerUiSettings(
        cur: AppBackingViewModel.State,
        settings: JsonObject,
    ): AppBackingViewModel.State {
        // Toolkit-canonical wire shape: every theme/appearance/font/etc.
        // field rides inside one of two stringified-JSON blobs the toolkit's
        // own ThemeSnapshot / UiSettings codecs produce. Termtastic uses
        // the same codecs (read on this side, write through
        // [AppBackingViewModel.persistUiSettings] /
        // [AppBackingViewModel.persistThemeSnapshot]) so toolkit-chrome
        // mutations and termtastic-side mutations round-trip through one
        // shared format.
        //
        // App-specific UI bits the toolkit doesn't model (sidebar width,
        // header/sidebar/usage-bar collapse) stay as flat top-level keys.
        val snapshot = readSnapshotBlob(settings) ?: ThemeSnapshot()
        val uiBlob = readUiSettingsBlob(settings)

        // Theme is intentionally NOT read from the UI blob: it's a
        // *derived* value (active slot bundle resolved against appearance
        // and custom-scheme pool). The blob's `theme` field is a cache
        // that the toolkit's `persistUi` writes from its local view of
        // state — when the user picks a new theme, the collector's
        // `refreshActiveTheme` updates the resolved theme on this side,
        // but the persisted blob still carries the *previous* theme name
        // until something writes a fresh one. If this reader trusted
        // that field, the next server echo would clobber the freshly
        // resolved state with the stale value. State.theme stays
        // unchanged here; the collector re-runs `refreshActiveTheme`
        // whenever appearance / slot bindings / custom pools change.
        val customSchemes = if (snapshot.customSchemes.isNotEmpty())
            snapshot.customSchemes else cur.customSchemes

        val appearanceName = uiBlob?.get("appearance")?.jsonPrimitive?.contentOrNull
        val appearance = appearanceName
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
            ?: cur.appearance

        // App-specific flat keys.
        val sidebarW = settings["sidebarWidth"]?.jsonPrimitive?.intOrNull ?: cur.sidebarWidth
        val sidebarCol = settings["sidebarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.sidebarCollapsed
        val headerCol = settings["headerCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.headerCollapsed
        val usageBarCol = settings["usageBarCollapsed"]?.jsonPrimitive?.booleanOrNull ?: cur.usageBarCollapsed

        // Slot names: take the snapshot value when present, otherwise
        // preserve cur. No "fall back to UI-blob theme" path: that would
        // resurface the same stale-cache issue on slot bindings.
        val lightName = snapshot.lightThemeName ?: cur.lightThemeName
        val darkName = snapshot.darkThemeName ?: cur.darkThemeName

        // Custom themes / favorites — empty snapshot field means "absent
        // on this push", not "explicitly cleared", so we keep the cur value
        // when the snapshot is empty.
        val customThemes = if (snapshot.customThemes.isNotEmpty())
            snapshot.customThemes else cur.customThemes
        val favorites = if (snapshot.favoriteThemes.isNotEmpty())
            snapshot.favoriteThemes else cur.favoriteThemes
        val favoriteSchemes = if (snapshot.favoriteSchemes.isNotEmpty())
            snapshot.favoriteSchemes else cur.favoriteSchemes

        return cur.copy(
            theme = cur.theme,
            appearance = appearance,
            paneFontSize = snapshot.monoFontSizePx ?: cur.paneFontSize,
            paneFontFamily = snapshot.monoFontFamily ?: cur.paneFontFamily,
            sidebarFontFamily = snapshot.sidebarFontFamily ?: cur.sidebarFontFamily,
            sidebarFontSizePx = snapshot.sidebarFontSizePx ?: cur.sidebarFontSizePx,
            tabbarFontFamily = snapshot.tabbarFontFamily ?: cur.tabbarFontFamily,
            tabbarFontSizePx = snapshot.tabbarFontSizePx ?: cur.tabbarFontSizePx,
            sidebarWidth = sidebarW,
            sidebarCollapsed = sidebarCol,
            headerCollapsed = headerCol,
            usageBarCollapsed = usageBarCol,
            desktopNotifications = snapshot.desktopNotifications,
            electronCustomTitleBar = snapshot.useCustomTitleBar,
            uiSettingsHydrated = true,
            lightThemeName = lightName,
            darkThemeName = darkName,
            favoriteThemes = favorites,
            favoriteSchemes = favoriteSchemes,
            customSchemes = customSchemes,
            customThemes = customThemes,
        )
    }

    private fun readSnapshotBlob(settings: JsonObject): ThemeSnapshot? =
        (settings[PersistKeys.THEME_SNAPSHOT] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.let { runCatching { ThemeSnapshot.fromJsonString(it) }.getOrNull() }

    private fun readUiSettingsBlob(settings: JsonObject): JsonObject? =
        (settings[PersistKeys.UI_SETTINGS] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /**
     * Apply the active theme (chosen by appearance + light/dark slot
     * selection) to [cur] and return the updated copy with the main
     * [ColorScheme] and per-pane scheme map populated.
     *
     * Resolution is delegated to the toolkit's [resolveActiveTheme] —
     * it picks the slot, looks the slot's theme up in
     * `cur.customThemes ∪ defaultThemes`, and resolves every pane in
     * [paneToSection] via the
     * `sections[role] ?: colorScheme` ladder.
     *
     * @param paneToSection the host app's pane → universal-section map
     *   (e.g. `termtasticPanes` for the web client). When empty, the
     *   resulting [AppBackingViewModel.State.paneSchemes] is empty too —
     *   every pane will fall back to the main theme. Tests / iOS / Android
     *   pass `emptyMap()` because they don't paint per-pane.
     */
    fun refreshActiveTheme(
        cur: AppBackingViewModel.State,
        systemIsDark: Boolean,
        paneToSection: Map<String, String>,
    ): AppBackingViewModel.State {
        val snapshot = cur.toThemeSnapshot()
        val bundle: ResolvedThemeBundle = resolveActiveTheme(
            snapshot = snapshot,
            appearance = cur.appearance,
            systemIsDark = systemIsDark,
            paneToSection = paneToSection,
        )
        return cur.copy(theme = bundle.theme, paneSchemes = bundle.paneSchemes)
    }
}

/**
 * Build a [ThemeSnapshot] from the current [AppBackingViewModel.State].
 *
 * Used both by [SettingsViewModel.refreshActiveTheme] (for the resolver
 * input) and by the persistence path on the coordinator
 * ([AppBackingViewModel] turns the snapshot into a flat `Map<String, String>`
 * via [ThemeSnapshot.encodeAsStringMap] before handing it to the server
 * `SettingsPersister`).
 *
 * The state's `theme` field is intentionally not in the snapshot — it's a
 * derived view of the active slot, re-derived on every
 * [SettingsViewModel.refreshActiveTheme] pass, not a persisted field.
 *
 * @receiver the live state.
 * @return a [ThemeSnapshot] capturing slot bindings, custom themes/schemes,
 *   favorites, font preferences, and the electron-titlebar opt-in.
 */
internal fun AppBackingViewModel.State.toThemeSnapshot(): ThemeSnapshot = ThemeSnapshot(
    lightThemeName = lightThemeName,
    darkThemeName = darkThemeName,
    customThemes = customThemes,
    customSchemes = customSchemes,
    favoriteThemes = favoriteThemes,
    favoriteSchemes = favoriteSchemes,
    monoFontFamily = paneFontFamily,
    monoFontSizePx = paneFontSize,
    sidebarFontFamily = sidebarFontFamily,
    sidebarFontSizePx = sidebarFontSizePx,
    tabbarFontFamily = tabbarFontFamily,
    tabbarFontSizePx = tabbarFontSizePx,
    desktopNotifications = desktopNotifications,
    useCustomTitleBar = electronCustomTitleBar,
)
