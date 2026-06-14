/**
 * Dual-slot theme configuration for the mobile (Android / iOS) clients.
 *
 * The web/Electron client picks **two** independent terminal themes — one for
 * light mode and one for dark mode — and the server persists both inside the
 * toolkit's [ThemeSnapshot] blob (`lightThemeName` / `darkThemeName`). The
 * mobile clients, however, historically fetched settings through
 * [TermtasticClient.fetchUiSettings], which routes through the toolkit's
 * [se.soderbjorn.darkness.core.UiSettings.resolveAgainst] codec. That codec
 * only reads a single top-level `theme` key — a key termtastic's server never
 * emits at top level (it lives inside the stringified `darkness.themeSnapshot`
 * / `darkness.uiSettings` blobs). The net effect was that mobile silently fell
 * back to the default theme and merely flipped *that one* scheme's light/dark
 * variants, so a user who chose Solarized for light + Neon Green for dark saw
 * Neon Green's light variant (green-on-white) on mobile instead of Solarized.
 *
 * [TermtasticThemeConfig] closes that gap by parsing the same dual-slot
 * [ThemeSnapshot] the web client writes and resolving the active slot through
 * the toolkit's [resolveActiveTheme] — exactly the path the web view-model
 * (`SettingsViewModel.refreshActiveTheme`) takes. The result is a single
 * [UiSettings] the existing per-pane mobile call sites already understand, so
 * downstream painting code needs no change beyond being handed slot-correct
 * colours.
 *
 * @see TermtasticClient.fetchThemeConfig
 * @see resolveActiveTheme
 * @see se.soderbjorn.darkness.core.ThemeSnapshot
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.Sections
import se.soderbjorn.darkness.core.ThemeSnapshot
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.resolveActiveTheme

/**
 * Termtastic's concrete-pane → universal-[Sections] map, used when resolving
 * the active theme's per-pane scheme assignments on mobile.
 *
 * Mirrors the web client's `termtasticPanes` (web `AppPanes.kt`) so the mobile
 * apps resolve panes identically to the desktop app. Kept here in the shared
 * client module so both Android and iOS share one definition.
 *
 * @see resolveActiveTheme
 */
val termtasticPaneToSection: Map<String, String> = mapOf(
    "terminal" to Sections.Main,
    "sidebar" to Sections.Sidebar,
    "tabs" to Sections.Tabs,
    "chrome" to Sections.Chrome,
    "active" to Sections.Active,
    "windows" to Sections.Windows,
    "diff" to Sections.Auxiliary,
    "fileBrowser" to Sections.Auxiliary,
    "git" to Sections.Auxiliary,
    "bottomBar" to Sections.BottomBar,
)

/**
 * The server-persisted dual-slot theme choice plus the appearance preference,
 * as consumed by the mobile clients.
 *
 * Fetched once per connection via [TermtasticClient.fetchThemeConfig] and then
 * [resolve]d against the host's *current* system dark-mode flag at paint time,
 * so flipping the device between light and dark selects the correct **slot**
 * (not merely the current theme's light/dark variant).
 *
 * @property snapshot   the dual-slot snapshot (light/dark theme names + custom
 *   theme/scheme pools) parsed from the server's `darkness.themeSnapshot` blob.
 * @property appearance the user's Light/Dark/Auto preference parsed from the
 *   server's `darkness.uiSettings` blob; `Auto` defers slot choice to the host.
 */
data class TermtasticThemeConfig(
    val snapshot: ThemeSnapshot,
    val appearance: Appearance,
) {
    /**
     * Resolve the active [UiSettings] for the given system dark-mode flag.
     *
     * Picks the light or dark slot via the toolkit's [resolveActiveTheme]
     * (honouring [appearance]), then wraps the resolved main scheme + per-pane
     * scheme map in a [UiSettings] so existing `schemeForPane(...)` call sites
     * keep working unchanged.
     *
     * @param systemIsDark the host platform's "prefers dark" setting; only
     *   consulted when [appearance] is [Appearance.Auto].
     * @return a single-theme [UiSettings] already specialised to the active
     *   slot for [systemIsDark].
     */
    fun resolve(systemIsDark: Boolean): UiSettings {
        val bundle = resolveActiveTheme(
            snapshot = snapshot,
            appearance = appearance,
            systemIsDark = systemIsDark,
            paneToSection = termtasticPaneToSection,
        )
        return UiSettings(
            theme = bundle.theme,
            appearance = appearance,
            paneSchemes = bundle.paneSchemes,
        )
    }

    companion object {
        /**
         * The pre-connection / demo default: both slots set to
         * [DEFAULT_THEME_NAME] with [Appearance.Auto], matching the web
         * client's `AppBackingViewModel.State` slot defaults so an
         * unconfigured server renders identically across platforms.
         */
        fun defaults(): TermtasticThemeConfig = TermtasticThemeConfig(
            snapshot = ThemeSnapshot(
                lightThemeName = DEFAULT_THEME_NAME,
                darkThemeName = DEFAULT_THEME_NAME,
            ),
            appearance = Appearance.Auto,
        )

        /**
         * Parse a `/api/ui-settings` response body into a
         * [TermtasticThemeConfig], reading the dual-slot snapshot and
         * appearance out of the toolkit's two stringified blobs.
         *
         * Empty slot names are filled with [DEFAULT_THEME_NAME] so an
         * unconfigured server matches the web client's defaults. Malformed
         * blobs fall back to [defaults] silently — a bad server payload never
         * crashes a client.
         *
         * @param obj the parsed `/api/ui-settings` JSON object.
         * @return the resolved dual-slot config.
         */
        fun fromUiSettingsJson(obj: JsonObject): TermtasticThemeConfig {
            val rawSnapshot = (obj[PersistKeys.THEME_SNAPSHOT] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.let { runCatching { ThemeSnapshot.fromJsonString(it) }.getOrNull() }
                ?: ThemeSnapshot()
            // Fill empty slots with the app default so an unconfigured server
            // matches the web client's State defaults (both = DEFAULT_THEME_NAME).
            val snapshot = rawSnapshot.copy(
                lightThemeName = rawSnapshot.lightThemeName ?: DEFAULT_THEME_NAME,
                darkThemeName = rawSnapshot.darkThemeName ?: DEFAULT_THEME_NAME,
            )
            val uiBlob = (obj[PersistKeys.UI_SETTINGS] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val appearance = uiBlob?.get("appearance")?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Appearance.valueOf(it) }.getOrNull() }
                ?: Appearance.Auto
            return TermtasticThemeConfig(snapshot, appearance)
        }
    }
}

/**
 * Fetch `/api/ui-settings` and parse it into a [TermtasticThemeConfig].
 *
 * This is the dual-slot-aware replacement for [fetchUiSettings] on the mobile
 * clients: it preserves both the light and dark theme choices instead of
 * collapsing to a single theme. Callers resolve the returned config against
 * the host's current dark-mode flag (see [TermtasticThemeConfig.resolve]).
 *
 * Returns `null` only on auth rejection or a failed request — callers keep
 * using their current/default colours in that case. Demo mode and empty/blank
 * bodies yield [TermtasticThemeConfig.defaults].
 *
 * @receiver the connected client.
 * @return the parsed config, or `null` if the request was rejected/failed.
 * @see fetchUiSettings
 */
/**
 * Top-level accessor for [TermtasticThemeConfig.defaults], convenient for
 * Swift call sites which reach it via the `ThemeConfigKt` file facade
 * (mirroring the project's other `*Kt` helpers) rather than the companion.
 *
 * @return the pre-connection default config (both slots [DEFAULT_THEME_NAME],
 *   [Appearance.Auto]).
 */
fun defaultThemeConfig(): TermtasticThemeConfig = TermtasticThemeConfig.defaults()

suspend fun TermtasticClient.fetchThemeConfig(): TermtasticThemeConfig? {
    // Demo mode never performs network requests: every client gets the stock
    // defaults, which is exactly what a fresh install looks like.
    if (demoMode) return TermtasticThemeConfig.defaults()
    val url = serverUrl.httpUrl("/api/ui-settings")
    val response = runCatching {
        httpClient.get(url) {
            header("X-Termtastic-Auth", authToken)
            for ((name, value) in clientInfoHeaders()) header(name, value)
        }
    }.getOrElse { return null }
    if (!response.status.isSuccess()) return null
    if (response.status == HttpStatusCode.NoContent) return TermtasticThemeConfig.defaults()
    val body = runCatching { response.bodyAsText() }.getOrElse { return null }
    if (body.isBlank()) return TermtasticThemeConfig.defaults()
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }
        .getOrNull() ?: return TermtasticThemeConfig.defaults()
    return TermtasticThemeConfig.fromUiSettingsJson(obj)
}
