/**
 * Settings persistence abstraction for the Termtastic client.
 *
 * Platform-specific implementations send individual setting key/value pairs to
 * the server's `PATCH /api/ui-settings` endpoint, which merges them into the
 * stored JSON blob. The web client uses a `fetch`-based implementation; Android
 * and iOS use [TermtasticClient.httpClient].
 *
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Functional interface for persisting a single UI setting to the server.
 *
 * Called by [AppBackingViewModel] whenever the user changes a visual preference
 * (theme, appearance, font size, etc.).
 */
interface SettingsPersister {
    /**
     * Send a single key/value setting update to the server.
     *
     * @param key   the setting key (e.g. `"theme"`, `"appearance"`).
     * @param value the setting value as a string.
     */
    suspend fun putSetting(key: String, value: String)

    /**
     * Send multiple key/value setting updates to the server in a single
     * request. The default implementation falls back to individual
     * [putSetting] calls; platforms should override this to send a single
     * batch POST so the server broadcasts one consolidated update.
     *
     * @param settings map of setting keys to values.
     */
    suspend fun putSettings(settings: Map<String, String>) {
        for ((key, value) in settings) putSetting(key, value)
    }

    /**
     * Non-suspend variant of [putSettings] for callers that need to
     * persist without entering a coroutine context. The default
     * implementation is a no-op; platforms that support synchronous
     * fire-and-forget (e.g. web via `window.fetch`) should override.
     *
     * @param settings map of setting keys to values.
     */
    fun fireAndForgetPutSettings(settings: Map<String, String>) {}

    /**
     * Send nested JSON setting updates to the server in a single request.
     * Used by settings whose values are structured (arrays of favorites,
     * maps of custom color schemes, maps of custom themes).
     *
     * The default implementation downgrades values to strings where
     * possible and delegates to [putSettings]; platforms that want to
     * preserve nested JSON should override this. Called by
     * [AppBackingViewModel] for any setting that cannot be round-tripped
     * as a plain string.
     *
     * @param settings JSON object whose top-level keys are the setting
     *   keys to merge into the server's `ui.settings.v1` blob.
     */
    suspend fun putJsonSettings(settings: JsonObject) {
        val flat = buildMap<String, String> {
            for ((k, v) in settings) {
                if (v is JsonPrimitive && v.isString) {
                    put(k, v.content)
                } else if (v is JsonPrimitive) {
                    put(k, v.content)
                } else {
                    put(k, v.toString())
                }
            }
        }
        putSettings(flat)
    }
}
