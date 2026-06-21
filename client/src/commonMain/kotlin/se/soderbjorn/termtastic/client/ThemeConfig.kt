/**
 * Dual-slot theme configuration for the mobile (Android / iOS) clients.
 *
 * The web/Electron client picks **two** independent themes — one for light mode
 * and one for dark mode — and the server persists both in the v2 theme blobs
 * ([PersistKeys.THEME_V2_SELECTION] / [PersistKeys.THEME_V2_CUSTOM]). The mobile
 * clients fetch the same blobs via [TermtasticClient.fetchThemeConfig] and
 * [resolve] the active slot against the host's current dark-mode flag, so
 * flipping the device between light and dark selects the correct **slot** (not
 * merely a single theme's light/dark variant).
 *
 * Under the new theme system every theme defines its 19 semantic tokens
 * explicitly, so resolution is a direct lookup + format conversion — there is
 * no per-pane scheme map and no colour calculator. The resolved value is a flat
 * [ResolvedTheme] every platform consumes directly.
 *
 * @see TermtasticClient.fetchThemeConfig
 * @see ThemeSnapshotV2
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.ThemeSnapshotV2

/**
 * The server-persisted dual-slot theme choice (plus appearance), as consumed by
 * the mobile clients.
 *
 * Fetched once per connection via [TermtasticClient.fetchThemeConfig] and
 * [resolve]d against the host's *current* system dark-mode flag at paint time.
 *
 * @property studio the persisted v2 snapshot (dark/light slot names, custom
 *   themes, appearance) parsed from the server's v2 blobs.
 */
data class TermtasticThemeConfig(
    val studio: ThemeSnapshotV2,
) {
    /** The user's Light/Dark/Auto preference. */
    val appearance: Appearance get() = studio.appearance

    /**
     * Resolves the active slot to a flat [ResolvedTheme] for the given system
     * dark-mode flag.
     *
     * @param systemIsDark the host platform's "prefers dark" setting; only
     *   consulted when [appearance] is [Appearance.Auto].
     * @return the resolved 19-token palette for the active slot.
     */
    fun resolve(systemIsDark: Boolean): ResolvedTheme = studio.resolve(systemIsDark)

    companion object {
        /**
         * The pre-connection / demo default: both slots at their built-in
         * defaults with [Appearance.Auto], matching the web client.
         */
        fun defaults(): TermtasticThemeConfig = TermtasticThemeConfig(ThemeSnapshotV2())

        /**
         * Parses a `/api/ui-settings` response body into a config, reading the
         * v2 selection + custom-themes blobs. Each value may arrive as a nested
         * JSON object/array or as a JSON-encoded string. Malformed blobs fall
         * back to defaults silently.
         *
         * @param obj the parsed `/api/ui-settings` JSON object.
         * @return the resolved dual-slot config.
         */
        fun fromUiSettingsJson(obj: JsonObject): TermtasticThemeConfig {
            val selection = coerce(obj[PersistKeys.THEME_V2_SELECTION])
            val custom = coerce(obj[PersistKeys.THEME_V2_CUSTOM])
            return TermtasticThemeConfig(ThemeSnapshotV2.fromParts(selection, custom))
        }

        /** Coerce a value that may be a JSON-encoded string into a [JsonElement]. */
        private fun coerce(el: JsonElement?): JsonElement? = when (el) {
            null -> null
            is JsonPrimitive -> if (el.isString) {
                runCatching { Json.parseToJsonElement(el.content) }.getOrNull()
            } else null
            else -> el
        }
    }
}

/**
 * Top-level accessor for [TermtasticThemeConfig.defaults], convenient for Swift
 * call sites reaching it via the `ThemeConfigKt` file facade.
 *
 * @return the pre-connection default config.
 */
fun defaultThemeConfig(): TermtasticThemeConfig = TermtasticThemeConfig.defaults()

/**
 * Fetches `/api/ui-settings` and parses it into a [TermtasticThemeConfig].
 *
 * Returns `null` only on auth rejection or a failed request — callers keep
 * their current/default colours in that case. Demo mode and empty bodies yield
 * [TermtasticThemeConfig.defaults].
 *
 * @receiver the connected client.
 * @return the parsed config, or `null` if the request was rejected/failed.
 */
suspend fun TermtasticClient.fetchThemeConfig(): TermtasticThemeConfig? {
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
