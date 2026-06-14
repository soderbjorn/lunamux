/**
 * Server-side UI settings fetch and termtastic-specific helpers around the
 * shared [UiSettings] data class from `toolkit-core`.
 *
 * The data class itself now lives in `se.soderbjorn.darkness.core.UiSettings`.
 * This file keeps the termtastic-specific bits:
 *
 * - [TermtasticClient.fetchUiSettings] — pulls `/api/ui-settings` from the
 *   server and parses it via the shared [UiSettings.resolveAgainst] helper.
 * - [ColorScheme.effectiveColors] — deprecated convenience that returns
 *   the (foreground, background) hex pair for a scheme + appearance.
 *
 * @see se.soderbjorn.darkness.core.UiSettings
 * @see se.soderbjorn.darkness.core.ColorScheme
 * @see se.soderbjorn.darkness.core.Appearance
 */
package se.soderbjorn.termtastic.client

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.recommendedColorSchemes

/**
 * Resolve the (foreground, background) hex pair this colour scheme should
 * paint with, given the user's appearance preference and the host's system
 * dark-mode flag. `Appearance.Auto` defers to [systemIsDark].
 */
@Deprecated(
    "Use ColorScheme.resolve(appearance, systemIsDark) for the full semantic palette",
    replaceWith = ReplaceWith(
        "resolve(appearance, systemIsDark)",
        "se.soderbjorn.darkness.core.resolve",
    ),
)
fun ColorScheme.effectiveColors(
    appearance: Appearance,
    systemIsDark: Boolean,
): Pair<String, String> {
    val dark = when (appearance) {
        Appearance.Dark -> true
        Appearance.Light -> false
        Appearance.Auto -> systemIsDark
    }
    return if (dark) darkFg to darkBg else lightFg to lightBg
}

/**
 * Fetch `/api/ui-settings` and map it to a [UiSettings]. Returns `null` if
 * the server rejects auth or the request fails; callers should keep using
 * their current/default colours in that case.
 *
 * Unknown theme names and unknown appearance values fall back silently
 * (see [UiSettings.resolveAgainst]) so a typo in the server blob never
 * crashes a client.
 */
suspend fun TermtasticClient.fetchUiSettings(): UiSettings? {
    // Demo mode never performs network requests: every client gets the
    // stock defaults, which is exactly what a fresh install looks like.
    if (demoMode) return UiSettings.defaults()
    val url = serverUrl.httpUrl("/api/ui-settings")
    val response = runCatching {
        httpClient.get(url) {
            header("X-Termtastic-Auth", authToken)
            for ((name, value) in clientInfoHeaders()) header(name, value)
        }
    }.getOrElse { return null }
    if (!response.status.isSuccess()) return null
    if (response.status == HttpStatusCode.NoContent) return UiSettings.defaults()
    val body = runCatching { response.bodyAsText() }.getOrElse { return null }
    if (body.isBlank()) return UiSettings.defaults()
    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }
        .getOrNull() ?: return UiSettings.defaults()
    return UiSettings.resolveAgainst(obj, recommendedColorSchemes)
}
