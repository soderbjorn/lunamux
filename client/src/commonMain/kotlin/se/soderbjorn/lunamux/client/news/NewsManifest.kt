/**
 * Serializable models for the Lunamux "news" manifest.
 *
 * Every Lunamux client (Android, iOS, and the macOS Electron app) fetches a
 * single `news.json` published on the main website (`lunamux.dev`) —
 * a sibling of `versions.json` — and deserializes it into a [NewsManifest]. Each [NewsItem]
 * is a short, dated announcement (a new feature, a notable fix, etc.) surfaced in
 * the mobile "News & Updates" screen and the desktop "News" pill. Each item
 * carries an [NewsItem.active] flag: every active item is eligible for display
 * until the user dismisses it.
 *
 * These classes intentionally mirror the on-disk JSON one-to-one and reuse the
 * same forward-compatible `schemaVersion` strategy as
 * [se.soderbjorn.lunamux.client.update.VersionManifest]: a client that sees a
 * [schemaVersion] newer than the one it understands
 * ([NewsManifest.SUPPORTED_SCHEMA_VERSION]) treats the manifest as "no news"
 * rather than risk misreading a future format.
 *
 * @see se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel
 */
package se.soderbjorn.lunamux.client.news

import kotlinx.serialization.Serializable

/**
 * The top-level shape of `news.json`.
 *
 * @property schemaVersion format version of the manifest, bumped if the layout
 *   ever changes incompatibly. Clients that see a [schemaVersion] newer than the
 *   one they understand ([SUPPORTED_SCHEMA_VERSION]) treat the manifest as
 *   "no news" rather than risk misreading a future format.
 * @property items the published news items. The client filters them by the
 *   [NewsItem.active] flag and the locally-stored confirmation state and
 *   displays the survivors in manifest order.
 */
@Serializable
data class NewsManifest(
    val schemaVersion: Int = 1,
    val items: List<NewsItem> = emptyList(),
) {
    companion object {
        /** The highest [schemaVersion] this build knows how to interpret. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/**
 * A single dated news announcement.
 *
 * @property id stable, opaque identifier, never reused. Used as the key for
 *   the locally-persisted "confirmed" (dismissed) set, so it must remain
 *   constant for the life of an item. By convention it is date-prefixed for
 *   readability (e.g. `2026-06-20-split-panes`) but is treated as opaque.
 * @property active whether the item is eligible for display. Every `active`
 *   item is shown (as a pill count and a modal card) until the user dismisses
 *   it; flip it to `false` in the manifest to retire an item for everyone.
 *   Defaults to `false` so a malformed entry that omits the flag is hidden
 *   rather than shown unintentionally.
 * @property date optional ISO-8601 calendar date (`YYYY-MM-DD`, no time) the
 *   item was published. **Display-only** — it is no longer used to filter
 *   items (eligibility is governed solely by [active]); when present it is
 *   shown as a date line on the news card, and omitted entirely when `null`.
 * @property title short headline shown in the news card.
 * @property body the announcement text as **plain text** — shown verbatim in
 *   the news card (no Markdown or HTML rendering; any markup is displayed
 *   literally).
 * @property url optional external link. When non-null the news card shows a
 *   button that opens this URL in the user's default browser; when null no
 *   button is shown.
 */
@Serializable
data class NewsItem(
    val id: String,
    val active: Boolean = false,
    val date: String? = null,
    val title: String,
    val body: String,
    val url: String? = null,
)
