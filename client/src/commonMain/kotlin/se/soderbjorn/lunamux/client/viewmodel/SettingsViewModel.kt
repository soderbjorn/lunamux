/**
 * Settings-mutation slice of the application backing model. Owns the
 * [SettingsPersister] and the local-change timestamp guard.
 *
 * Theme state rides in the two v2 blobs ([PersistKeys.THEME_V2_SELECTION] /
 * [PersistKeys.THEME_V2_CUSTOM]); font and per-app toggle preferences are flat
 * top-level keys. This slice parses an incoming server blob into an updated
 * [AppBackingViewModel.State] and persists outgoing changes.
 *
 * Used internally by [AppBackingViewModel] which delegates its settings methods
 * here; the public surface stays on the coordinator.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import kotlin.time.TimeSource

/**
 * Persistence + parsing helper for settings-related fields of
 * [AppBackingViewModel.State].
 *
 * @param settingsPersister optional callback to persist UI setting changes
 *   to the server; `null` in unit tests.
 */
internal class SettingsViewModel(
    private val settingsPersister: SettingsPersister?,
) {
    /**
     * Monotonic timestamp of the most recent local settings mutation, or
     * `null` when this client has not mutated any setting yet. Used to
     * suppress server-pushed UiSettings echoes that arrive shortly after the
     * client itself POSTed.
     *
     * Starts as `null` (not "now"): the guard exists to swallow echoes of
     * *our own writes*, and before the first local write there is nothing to
     * echo. Initialising it to construction time made the guard drop every
     * UiSettings envelope for the first two seconds of a connection —
     * including the server's initial replay that carries the theme — so a
     * freshly connected mobile client rendered with default colors until a
     * later, unrelated settings push happened to arrive (issue #93).
     */
    var lastLocalSettingsChange: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    /** Stamp [lastLocalSettingsChange] without persisting. */
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
     * Apply a server-pushed UI settings JSON object to [cur] and return the
     * updated copy.
     *
     * Theme fields come from the v2 selection + custom blobs; font and toggle
     * fields are flat top-level keys. An absent field preserves the current
     * value (an empty push never clears existing state); unknown enum values
     * fall through so a bad blob never crashes.
     */
    fun applyServerUiSettings(
        cur: AppBackingViewModel.State,
        settings: JsonObject,
    ): AppBackingViewModel.State {
        val selection = coerceObject(settings[PersistKeys.THEME_V2_SELECTION])
        val customEl = coerce(settings[PersistKeys.THEME_V2_CUSTOM])
        val favoritesEl = coerce(settings[PersistKeys.THEME_V2_FAVORITES])

        val appearance = selection?.get("appearance")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: cur.appearance
        val lightName = selection?.get("lightThemeName")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() } ?: cur.lightThemeName
        val darkName = selection?.get("darkThemeName")?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotEmpty() } ?: cur.darkThemeName

        val parsedCustom = customEl?.let { ThemeSnapshotV2.parseCustomThemes(it) }
        val customThemes = if (!parsedCustom.isNullOrEmpty()) parsedCustom else cur.customThemes

        // Favorites: an absent key preserves the current set (an empty push never
        // clears it), but a present empty array *does* clear it — the user having
        // unstarred their last favorite is a legitimate state to sync.
        val favoriteThemeNames = if (favoritesEl != null) {
            ThemeSnapshotV2.parseFavorites(favoritesEl).toSet()
        } else {
            cur.favoriteThemeNames
        }

        // Flat top-level keys (not part of any theme blob).
        fun str(key: String): String? =
            settings[key]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }
        fun int(key: String): Int? = settings[key]?.jsonPrimitive?.intOrNull
        fun bool(key: String): Boolean? = settings[key]?.jsonPrimitive?.booleanOrNull

        return cur.copy(
            appearance = appearance,
            paneFontSize = int("monoFontSizePx") ?: cur.paneFontSize,
            paneFontFamily = str("monoFontFamily") ?: cur.paneFontFamily,
            sidebarFontFamily = str("sidebarFontFamily") ?: cur.sidebarFontFamily,
            sidebarFontSizePx = int("sidebarFontSizePx") ?: cur.sidebarFontSizePx,
            tabbarFontFamily = str("tabbarFontFamily") ?: cur.tabbarFontFamily,
            tabbarFontSizePx = int("tabbarFontSizePx") ?: cur.tabbarFontSizePx,
            paneHeaderFontFamily = str("paneHeaderFontFamily") ?: cur.paneHeaderFontFamily,
            paneHeaderFontSizePx = int("paneHeaderFontSizePx") ?: cur.paneHeaderFontSizePx,
            sidebarWidth = int("sidebarWidth") ?: cur.sidebarWidth,
            sidebarCollapsed = bool("sidebarCollapsed") ?: cur.sidebarCollapsed,
            headerCollapsed = bool("headerCollapsed") ?: cur.headerCollapsed,
            usageBarCollapsed = bool("usageBarCollapsed") ?: cur.usageBarCollapsed,
            electronCustomTitleBar = bool("electronCustomTitleBar") ?: cur.electronCustomTitleBar,
            uiSettingsHydrated = true,
            lightThemeName = lightName,
            darkThemeName = darkName,
            customThemes = customThemes,
            favoriteThemeNames = favoriteThemeNames,
        )
    }

    /** Coerce a value that may be a JSON-encoded string into a [JsonElement]. */
    private fun coerce(el: JsonElement?): JsonElement? = when (el) {
        null -> null
        is JsonPrimitive -> if (el.isString) {
            runCatching { Json.parseToJsonElement(el.content) }.getOrNull()
        } else null
        else -> el
    }

    /** Coerce a value to a [JsonObject] (parsing a JSON string if needed). */
    private fun coerceObject(el: JsonElement?): JsonObject? = coerce(el) as? JsonObject
}
