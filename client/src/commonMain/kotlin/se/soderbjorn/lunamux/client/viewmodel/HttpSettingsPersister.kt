/**
 * HTTP-backed [SettingsPersister] for the mobile clients (Android now, iOS
 * planned). Mirrors the web client's `fetch`-based persister: it POSTs a
 * JSON object of setting key/value pairs to the server's
 * `POST /api/ui-settings` endpoint, which merges them into the stored blob
 * and broadcasts the result back over `/window`.
 *
 * Shared across platforms because it only needs [LunamuxClient]'s
 * Ktor [io.ktor.client.HttpClient], auth token, and client-info headers —
 * all already platform-agnostic. The overview's geometry edits (move /
 * resize / maximize / minimize / layout) author the toolkit-owned
 * `LAYOUT_STATE` blob through this persister exactly like the web toolkit,
 * so every connected client re-renders consistently.
 *
 * @see SettingsPersister
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
 * @see se.soderbjorn.lunamux.client.WindowLayoutState
 */
package se.soderbjorn.lunamux.client.viewmodel

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.client.LunamuxClient

/**
 * [SettingsPersister] that writes to `POST /api/ui-settings` over
 * [LunamuxClient.httpClient].
 *
 * Constructed by the Android/iOS overview hosts and handed to
 * [OverviewBackingViewModel] (and reusable by [AppBackingViewModel]).
 * No-ops in demo mode, and swallows transport failures (a dropped settings
 * write is non-fatal — the next push re-syncs).
 *
 * @param client the connected client whose HTTP engine, auth token, and
 *   server URL are reused for the request.
 */
class HttpSettingsPersister(
    private val client: LunamuxClient,
) : SettingsPersister {

    /**
     * Send a single key/value pair. See [SettingsPersister.putSetting].
     *
     * @param key   the setting key (e.g. `"LAYOUT_STATE"`).
     * @param value the setting value (e.g. a stringified geometry blob).
     */
    override suspend fun putSetting(key: String, value: String) {
        post(buildJsonObject { put(key, value) })
    }

    /**
     * Send several string settings in one request.
     *
     * @param settings map of setting keys to values.
     */
    override suspend fun putSettings(settings: Map<String, String>) {
        post(buildJsonObject { for ((k, v) in settings) put(k, v) })
    }

    /**
     * Send nested JSON settings verbatim (preserving real JSON shape rather
     * than the stringified fallback). Used for structured values.
     *
     * @param settings JSON object whose top-level keys are merged server-side.
     */
    override suspend fun putJsonSettings(settings: JsonObject) {
        post(settings)
    }

    /**
     * POST [body] to `/api/ui-settings` with the same auth + client-info
     * headers the WebSocket upgrade carries. Failures are swallowed.
     *
     * In demo mode there is no HTTP endpoint, so the write is routed into the
     * in-process [se.soderbjorn.lunamux.client.demo.DemoServer] instead,
     * which merges + broadcasts it just like the real server — so demo-mode
     * geometry/layout edits persist across screen teardown rather than being
     * lost with the overview view-model.
     *
     * @param body the JSON object of settings to merge.
     */
    private suspend fun post(body: JsonObject) {
        client.demoServer?.let {
            it.applyUiSettings(body)
            return
        }
        val url = client.serverUrl.httpUrl("/api/ui-settings")
        runCatching {
            client.httpClient.post(url) {
                header("X-Termtastic-Auth", client.authToken)
                for ((name, value) in client.clientInfoHeaders()) header(name, value)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
    }
}
