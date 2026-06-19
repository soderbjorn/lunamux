/**
 * Development/debug toggles and canned sample data for the version-update and
 * news checkers.
 *
 * Flipping the booleans here lets a developer exercise the news/update UI
 * without depending on the network or on the once-per-day check cadence:
 *
 *  - [USE_SAMPLE_DATA] makes [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel]
 *    skip its manifest network fetches and return the hardcoded
 *    [SAMPLE_VERSION_MANIFEST] / [SAMPLE_NEWS_MANIFEST] below instead. The sample
 *    version manifest reports an absurdly high `latestVersionCode` for every
 *    platform so the update section always appears; the sample news manifest
 *    mirrors the published `news.json` so the news list always has items to show.
 *  - [CHECK_ON_EVERY_STARTUP] makes the checker run its first check immediately
 *    on startup, bypassing the persisted last-check timestamp that would
 *    otherwise defer the first check until a full interval has elapsed.
 *
 * Both default to `false` so production builds behave normally (fetch from
 * GitHub, check at most once per [se.soderbjorn.termtastic.client.newsupdates.CHECK_INTERVAL_MILLIS]).
 * Set them to `true` locally while iterating on the news/update UI, but do not
 * commit them as `true`.
 *
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel
 */
package se.soderbjorn.termtastic.client

import se.soderbjorn.termtastic.client.news.NewsItem
import se.soderbjorn.termtastic.client.news.NewsManifest
import se.soderbjorn.termtastic.client.update.PlatformVersionInfo
import se.soderbjorn.termtastic.client.update.UpdatePlatform
import se.soderbjorn.termtastic.client.update.VersionManifest

/**
 * When `true`, the news/update checker returns [SAMPLE_VERSION_MANIFEST] /
 * [SAMPLE_NEWS_MANIFEST] instead of fetching the manifests over the network.
 *
 * Read by the checker's manifest fetches. Defaults to `false` (real network
 * fetch).
 */
const val USE_SAMPLE_DATA: Boolean = false

/**
 * When `true`, the news/update checker performs its first check immediately on
 * startup, ignoring the persisted last-check timestamp that would normally defer
 * it until a full check interval has elapsed.
 *
 * Read in the checker's `start()`. Defaults to `false` (respect the timestamp).
 */
const val CHECK_ON_EVERY_STARTUP: Boolean = false

/**
 * Static stand-in for `versions.json`, returned by the update checker when
 * [USE_SAMPLE_DATA] is `true`.
 *
 * Every platform reports a very high `latestVersionCode` so the comparison in
 * [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.checkNow]
 * always resolves to "update available" regardless of the running build's version code.
 */
val SAMPLE_VERSION_MANIFEST: VersionManifest = VersionManifest(
    schemaVersion = 1,
    platforms = mapOf(
        UpdatePlatform.ANDROID to PlatformVersionInfo(
            latestVersionCode = 999_999L,
            latestVersionName = "99.0.0 (sample)",
            url = "https://termtastic.soderbjorn.se/#/download",
        ),
        UpdatePlatform.IOS to PlatformVersionInfo(
            latestVersionCode = 999_999L,
            latestVersionName = "99.0.0 (sample)",
            url = "https://termtastic.soderbjorn.se/#/download",
        ),
        UpdatePlatform.MAC to PlatformVersionInfo(
            latestVersionCode = 999_999L,
            latestVersionName = "99.0.0 (sample)",
            url = "https://termtastic.soderbjorn.se/#/download",
        ),
    ),
)

/**
 * Static stand-in for `news.json`, returned by the news checker when
 * [USE_SAMPLE_DATA] is `true`. Mirrors the published manifest: four active
 * items so the news list always has something to show (ids never previously
 * dismissed will all appear; ids already in the persisted dismissed set will not).
 */
val SAMPLE_NEWS_MANIFEST: NewsManifest = NewsManifest(
    schemaVersion = 1,
    items = listOf(
        NewsItem(
            id = "sample-welcome",
            active = true,
            date = "2026-06-19",
            title = "Welcome to Termtastic news",
            body = "The desktop app can now surface short announcements right here. " +
                "Click the News pill any time to catch up, and close a card to dismiss it for good.",
            url = "https://termtastic.soderbjorn.se/",
        ),
        NewsItem(
            id = "sample-split-panes",
            active = true,
            date = "2026-06-22",
            title = "Split panes",
            body = "Split any tab horizontally or vertically, then drag the divider to resize. " +
                "Double-click a divider to reset it to an even split.",
        ),
        NewsItem(
            id = "sample-themes",
            active = true,
            date = "2026-06-25",
            title = "Six new bundled themes",
            body = "Head to Settings > Appearance for Solarized, Gruvbox, and four more. " +
                "Light and dark slots are picked independently, so each follows your system appearance.",
        ),
        NewsItem(
            id = "sample-markdown-preview",
            active = true,
            date = "2026-06-28",
            title = "Markdown & git diff previews",
            body = "Open a .md file in the file browser to see it rendered, and review changes " +
                "inline in the git diff viewer.",
            url = "https://termtastic.soderbjorn.se/",
        ),
    ),
)
