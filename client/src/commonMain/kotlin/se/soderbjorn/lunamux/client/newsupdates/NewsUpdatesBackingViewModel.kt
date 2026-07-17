/**
 * Shared backing view-model for the cross-platform "News & Updates" feature.
 *
 * One [NewsUpdatesBackingViewModel] is constructed by each platform's app
 * bootstrap (Android's `NewsUpdatesController`, iOS's `NewsUpdatesViewModel`
 * Swift wrapper, and the macOS Electron renderer's JS bootstrap). Following the
 * project's `*BackingViewModel` convention it owns *all* of the logic so the
 * platforms never drift: periodic scheduling, fetching both the `versions.json`
 * update manifest and the `news.json` manifest from the main website, comparing the
 * running build against the published one, filtering news to the active and
 * not-yet-dismissed items, and exposing the merged result as a single immutable
 * [State] over a [StateFlow]. Platform UIs only render that state, open
 * [State.infoUrl] for the download, call [dismissNews] when the user swipes a
 * news card away, [dismissUpdate] when they close/swipe the "New update" box, and
 * [restoreAll] when they tap "Restore" to bring every dismissed item back.
 *
 * This replaces the previous separate `UpdateCheckViewModel` and
 * `NewsCheckViewModel`: a single icon ([State.hasContent]) now reflects "there
 * is news or an update", and a single check loop covers both.
 *
 * Persistence is via the shared [LocalRepository] (the single `local_state.json`
 * blob), holding the dismissed-news-id set, the dismissed-update version code, and
 * the last-check timestamp among the app's other local state. The state is
 * hydrated asynchronously on [start] and persisted fire-and-forget on
 * [dismissNews] / [dismissUpdate] / [restoreAll]. A failed network check is a
 * silent no-op: state and the stored timestamp are left untouched.
 *
 * The most recently fetched manifests are cached in-memory so [restoreAll] can
 * re-apply them synchronously (and offline) without a fresh network round-trip.
 *
 * Debug logging follows the project `println("Tag: …")` convention so the check
 * cadence and outcome are visible in Logcat (Android), Xcode/Console (iOS), and
 * DevTools (Electron).
 *
 * @see LocalRepository
 * @see VersionManifest
 * @see NewsManifest
 */
package se.soderbjorn.lunamux.client.newsupdates

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.client.CHECK_ON_EVERY_STARTUP
import se.soderbjorn.lunamux.client.SAMPLE_NEWS_MANIFEST
import se.soderbjorn.lunamux.client.SAMPLE_VERSION_MANIFEST
import se.soderbjorn.lunamux.client.USE_SAMPLE_DATA
import se.soderbjorn.lunamux.client.news.NewsItem
import se.soderbjorn.lunamux.client.news.NewsManifest
import se.soderbjorn.lunamux.client.storage.LocalRepository
import se.soderbjorn.lunamux.client.update.VersionManifest
import se.soderbjorn.lunamux.client.update.createPlainHttpClient

/** The URL of the published version manifest, hosted on the main website. */
const val DEFAULT_MANIFEST_URL: String =
    "https://lunamux.dev/versions.json"

/** The URL of the published news manifest, hosted on the main website. */
const val DEFAULT_NEWS_URL: String =
    "https://lunamux.dev/news.json"

/**
 * How often to re-check for news/updates after the on-startup check. Daily —
 * the checker also checks once on startup (gated by the persisted last-check
 * time; see [NewsUpdatesBackingViewModel.start]), so this governs the cadence
 * of subsequent checks while the app stays running.
 */
const val CHECK_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Construct a [NewsUpdatesBackingViewModel] with the production defaults for its
 * scope, manifest URLs, HTTP client, and interval.
 *
 * Kotlin default-argument constructors do not bridge to Swift/ObjC (every
 * parameter becomes required), so the iOS app calls this factory — reachable as
 * `NewsUpdatesBackingViewModelKt.createNewsUpdatesBackingViewModel(...)` — to
 * build the view-model with only the values it must supply. Android and the JS
 * renderer call the constructor directly with Kotlin defaults.
 *
 * @param repository the shared [LocalRepository] owning persistence of the
 *   dismissed-id set and the last-check timestamp (in `local_state.json`).
 * @param platformId the [se.soderbjorn.lunamux.client.update.UpdatePlatform]
 *   identifier (`"android"`, `"ios"`, or `"mac"`); selects the manifest entry.
 * @param currentVersionCode the running build's monotonic version code.
 * @param currentVersionName the running build's human-readable version.
 * @return a not-yet-started [NewsUpdatesBackingViewModel]; the caller must call
 *   [NewsUpdatesBackingViewModel.start].
 */
fun createNewsUpdatesBackingViewModel(
    repository: LocalRepository,
    platformId: String,
    currentVersionCode: Long,
    currentVersionName: String,
): NewsUpdatesBackingViewModel = NewsUpdatesBackingViewModel(
    repository = repository,
    platformId = platformId,
    currentVersionCode = currentVersionCode,
    currentVersionName = currentVersionName,
)

/**
 * Owns the periodic news/update check and exposes its merged result as a
 * [StateFlow].
 *
 * @param repository the shared [LocalRepository] backing persistence of the
 *   dismissed-id set and last-check timestamp in `local_state.json`.
 * @param platformId the platform identifier selecting which `versions.json`
 *   entry is consulted.
 * @param currentVersionCode the running build's monotonic version code, the
 *   comparison baseline against the manifest's `latestVersionCode`.
 * @param currentVersionName the running build's human-readable version, logged
 *   for context (not used in the comparison).
 * @param enableUpdateCheck when false, `versions.json` is not fetched and its
 *   update comparison is skipped entirely (news still flows normally). Used by the
 *   desktop/Electron build, where electron-updater owns in-app updates and this
 *   manifest would otherwise double-notify. Defaults to true, so Android/iOS are
 *   unchanged.
 * @param scope coroutine scope the check loop runs in; defaults to a background
 *   [SupervisorJob] scope so a failed check never tears down siblings.
 * @param manifestUrl the version manifest URL; overridable for testing.
 * @param newsUrl the news manifest URL; overridable for testing.
 * @param httpClient the (un-pinned) client used to fetch the manifests; defaults
 *   to a fresh [createPlainHttpClient].
 * @param checkIntervalMillis the re-check interval; defaults to
 *   [CHECK_INTERVAL_MILLIS] and is overridable for testing.
 */
class NewsUpdatesBackingViewModel(
    private val repository: LocalRepository,
    private val platformId: String,
    private val currentVersionCode: Long,
    private val currentVersionName: String,
    private val enableUpdateCheck: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val newsUrl: String = DEFAULT_NEWS_URL,
    private val httpClient: HttpClient = createPlainHttpClient(),
    private val checkIntervalMillis: Long = CHECK_INTERVAL_MILLIS,
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current news/update state, observed by every platform UI. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    // allowComments / allowTrailingComma let the published versions.json and
    // news.json carry `//` and `/* */` comments (e.g. to temporarily "comment
    // out" an item) and tolerate a trailing comma without failing the whole
    // parse. ignoreUnknownKeys keeps forward-compatibility with future fields.
    private val json = Json {
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
    }

    private var started = false

    /** In-memory mirror of the persisted dismissed-news-id set. */
    private var dismissedNewsIds: Set<String> = emptySet()

    /**
     * In-memory mirror of the persisted dismissed-update version code (the
     * `latestVersionCode` the user has dismissed), or `null` if none.
     */
    private var dismissedUpdateVersionCode: Long? = null

    /**
     * The `latestVersionCode` of the update currently advertised by the manifest
     * for this platform, or `null` if none/unknown. Recorded by
     * [applyVersionManifest] so [dismissUpdate] knows which version to persist as
     * dismissed even after the box has been hidden.
     */
    private var latestUpdateVersionCode: Long? = null

    /**
     * The most recently fetched manifests, cached so [restoreAll] can re-apply the
     * un-dismissed view synchronously (and offline) instead of re-fetching. `null`
     * until the first successful fetch of each.
     */
    private var lastVersionManifest: VersionManifest? = null
    private var lastNewsManifest: NewsManifest? = null

    /**
     * Immutable snapshot of the merged news/update feature, rendered by each
     * platform.
     *
     * @property updateAvailable true when the manifest reports a newer build for
     *   this platform than the one running; contributes to [hasContent].
     * @property latestVersionName the newer version's display name; `null` when
     *   no update is available.
     * @property infoUrl the download URL opened from the "New update" section;
     *   `null` when no update is available.
     * @property newsItems the active, not-yet-dismissed news items in manifest
     *   order, listed in the "News" section.
     * @property unreadNewsCount convenience count, always equal to
     *   `newsItems.size`.
     * @property lastCheckEpochMillis epoch-millis time of the last completed
     *   check, or `null` if none has completed this run.
     */
    data class State(
        val updateAvailable: Boolean = false,
        val latestVersionName: String? = null,
        val infoUrl: String? = null,
        val newsItems: List<NewsItem> = emptyList(),
        val unreadNewsCount: Int = 0,
        val lastCheckEpochMillis: Long? = null,
    ) {
        /**
         * Whether the toolbar icon should show: there is either news to read or
         * an update to install.
         */
        val hasContent: Boolean get() = updateAvailable || newsItems.isNotEmpty()

        /**
         * Whether the toolbar icon should pulse: there is actual news to read.
         * A version update on its own shows the icon (via [hasContent]) but does
         * not pulse it, so the pulsing reads specifically as "there is news".
         */
        val hasNews: Boolean get() = newsItems.isNotEmpty()
    }

    /**
     * Start the news/update check loop. Idempotent — repeated calls after the
     * first are ignored, so platforms can safely call it on every screen
     * appearance without spawning duplicate loops.
     *
     * Hydrates the persisted state, then checks at most once per
     * [checkIntervalMillis] (daily) across launches: it checks immediately only
     * on a first run (no stored timestamp) or once a full interval has elapsed;
     * otherwise it defers the first check by the remaining interval. After that
     * it re-checks every [checkIntervalMillis] for as long as the app runs.
     *
     * Called once by each platform's app bootstrap.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val persisted = repository.ensureLoaded()
            dismissedNewsIds = persisted.dismissedNewsIds
            dismissedUpdateVersionCode = persisted.dismissedUpdateVersionCode
            val lastCheck = persisted.lastCheckEpochMillis
            _stateFlow.value = _stateFlow.value.copy(lastCheckEpochMillis = lastCheck)
            // Gate the first check on the persisted last-check time so a relaunch
            // within the interval does not re-check. CHECK_ON_EVERY_STARTUP forces
            // an immediate first check on every launch (debug only).
            val initialDelay = if (CHECK_ON_EVERY_STARTUP) 0L else initialDelayMillis(lastCheck)
            println(
                "NewsUpdates: starting for platform=$platformId; previous check " +
                    "${isoOrNever(lastCheck)}; first check " +
                    "${if (initialDelay == 0L) "now" else "in ${initialDelay}ms"}, " +
                    "then every ${checkIntervalMillis}ms",
            )
            if (initialDelay > 0L) {
                println("NewsUpdates: deferring first check by ${initialDelay}ms")
                delay(initialDelay)
            }
            while (true) {
                checkNow()
                delay(checkIntervalMillis)
            }
        }
    }

    /**
     * Perform a single check: fetch both manifests, compare this platform's
     * update entry against the running build, filter news to the active and
     * not-yet-dismissed items, and emit the merged result. Never throws — and a
     * total failure (both fetches fail) is a silent no-op: the state and stored
     * timestamp are left untouched.
     *
     * Exposed (not private) so a platform or test can trigger an out-of-band
     * check; the periodic loop in [start] calls it on each tick.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun checkNow() {
        println(
            "NewsUpdates: checking $manifestUrl + $newsUrl for platform=$platformId " +
                "code=$currentVersionCode name=$currentVersionName",
        )
        // Desktop disables the versions.json update path (electron-updater owns
        // updates there), so skip the fetch entirely rather than spend a request on
        // a manifest we'd ignore. News is always fetched.
        val versions = if (enableUpdateCheck) fetchVersionManifest() else null
        val news = fetchNewsManifest()
        if (versions == null && news == null) {
            // Silent no-op on total failure: no state change, no timestamp advance.
            println("NewsUpdates: both checks failed — leaving state unchanged")
            return
        }
        val now = Clock.System.now().toEpochMilliseconds()
        repository.setLastCheckEpochMillis(now)
        _stateFlow.value = _stateFlow.value.copy(lastCheckEpochMillis = now)

        // Cache the freshly-fetched manifests so restoreAll() can re-apply the
        // un-dismissed view without a network round-trip.
        if (versions != null) {
            lastVersionManifest = versions
            applyVersionManifest(versions)
        }
        if (news != null) {
            lastNewsManifest = news
            applyNewsManifest(news)
        }
    }

    /**
     * Mark a news item dismissed: add its id to the persisted set, drop it from
     * [State.newsItems] so the list and the icon refresh immediately, and persist
     * the updated state fire-and-forget so it stays gone after a relaunch.
     *
     * Called by each platform's "News & Updates" screen when the user swipes a
     * card away.
     *
     * @param id the [NewsItem.id] to dismiss.
     */
    fun dismissNews(id: String) {
        if (id in dismissedNewsIds) return
        dismissedNewsIds = dismissedNewsIds + id
        val remaining = _stateFlow.value.newsItems.filterNot { it.id == id }
        _stateFlow.value = _stateFlow.value.copy(newsItems = remaining, unreadNewsCount = remaining.size)
        scope.launch { repository.addDismissedNewsId(id) }
    }

    /**
     * Dismiss the currently-advertised update ("seen, don't remind me"): record
     * its [latestUpdateVersionCode] as dismissed, hide the "New update" box from
     * [State] so the screen and the toolbar bell refresh immediately, and persist
     * the dismissal fire-and-forget so it stays hidden after a relaunch. A newer
     * release (a higher version code) is shown again because the comparison in
     * [applyVersionManifest] only suppresses the exact dismissed code.
     *
     * No-op when there is no advertised update, or it is already dismissed.
     *
     * Called by each platform's "News & Updates" screen when the user closes
     * (desktop) or swipes away (mobile) the update box.
     */
    fun dismissUpdate() {
        val code = latestUpdateVersionCode ?: return
        if (code == dismissedUpdateVersionCode) return
        dismissedUpdateVersionCode = code
        _stateFlow.value = _stateFlow.value.copy(
            updateAvailable = false,
            latestVersionName = null,
            infoUrl = null,
        )
        scope.launch { repository.setDismissedUpdateVersionCode(code) }
    }

    /**
     * Restore everything the user has dismissed — all news items and any dismissed
     * update — so they reappear. Clears the in-memory dismissal state, persists the
     * cleared state fire-and-forget, and re-applies the cached manifests
     * synchronously so [State] (and the screen that reads `stateFlow.value`)
     * refreshes at once, offline. If no manifest has been fetched yet, falls back
     * to a fresh [checkNow].
     *
     * Called by each platform's "News & Updates" screen when the user taps
     * "Restore".
     */
    fun restoreAll() {
        dismissedNewsIds = emptySet()
        dismissedUpdateVersionCode = null
        scope.launch { repository.clearDismissed() }
        val versions = lastVersionManifest
        val news = lastNewsManifest
        if (versions == null && news == null) {
            scope.launch { checkNow() }
            return
        }
        versions?.let { applyVersionManifest(it) }
        news?.let { applyNewsManifest(it) }
    }

    /** Apply a fetched version manifest to the emitted [State]. */
    private fun applyVersionManifest(manifest: VersionManifest) {
        // Only ever reached when enableUpdateCheck is true: when false, checkNow
        // skips fetchVersionManifest and restoreAll has no cached manifest to apply,
        // so a desktop build never advertises a versions.json update.
        if (manifest.schemaVersion > VersionManifest.SUPPORTED_SCHEMA_VERSION) {
            println("NewsUpdates: version schemaVersion=${manifest.schemaVersion} unsupported; no update")
            latestUpdateVersionCode = null
            _stateFlow.value = _stateFlow.value.copy(updateAvailable = false, latestVersionName = null, infoUrl = null)
            return
        }
        val info = manifest.platforms[platformId]
        if (info == null) {
            println("NewsUpdates: no version entry for platform=$platformId; no update")
            latestUpdateVersionCode = null
            _stateFlow.value = _stateFlow.value.copy(updateAvailable = false, latestVersionName = null, infoUrl = null)
            return
        }
        // Remember the advertised code so dismissUpdate() can record it, even once
        // the box is hidden.
        latestUpdateVersionCode = info.latestVersionCode
        // A newer build than the running one — unless the user has dismissed this
        // exact version (a future, higher code re-shows it).
        val updateAvailable = info.latestVersionCode > currentVersionCode &&
            info.latestVersionCode != dismissedUpdateVersionCode
        println(
            "NewsUpdates: update latest code=${info.latestVersionCode} " +
                "name=${info.latestVersionName} dismissed=$dismissedUpdateVersionCode " +
                "updateAvailable=$updateAvailable",
        )
        _stateFlow.value = _stateFlow.value.copy(
            updateAvailable = updateAvailable,
            latestVersionName = if (updateAvailable) info.latestVersionName else null,
            infoUrl = if (updateAvailable) info.url else null,
        )
    }

    /** Apply a fetched news manifest to the emitted [State]. */
    private fun applyNewsManifest(manifest: NewsManifest) {
        if (manifest.schemaVersion > NewsManifest.SUPPORTED_SCHEMA_VERSION) {
            println("NewsUpdates: news schemaVersion=${manifest.schemaVersion} unsupported; treating as no news")
            _stateFlow.value = _stateFlow.value.copy(newsItems = emptyList(), unreadNewsCount = 0)
            return
        }
        val eligible = manifest.items.filter { it.active && it.id !in dismissedNewsIds }
        println("NewsUpdates: news ${eligible.size} active/undismissed of ${manifest.items.size}")
        _stateFlow.value = _stateFlow.value.copy(newsItems = eligible, unreadNewsCount = eligible.size)
    }

    /** Fetch + parse the version manifest; `null` on any transport/parse error. */
    private suspend fun fetchVersionManifest(): VersionManifest? {
        if (USE_SAMPLE_DATA) {
            println("NewsUpdates: USE_SAMPLE_DATA on — using sample version manifest")
            return SAMPLE_VERSION_MANIFEST
        }
        val body = runCatching {
            val response = httpClient.get(manifestUrl)
            if (!response.status.isSuccess()) {
                println("NewsUpdates: version fetch failed: HTTP ${response.status}")
                return null
            }
            response.bodyAsText()
        }.getOrElse { t ->
            println("NewsUpdates: version fetch failed: ${t.message ?: t::class.simpleName}")
            return null
        }
        return runCatching { json.decodeFromString<VersionManifest>(body) }.getOrElse { t ->
            println("NewsUpdates: version parse failed: ${t.message ?: t::class.simpleName}")
            null
        }
    }

    /** Fetch + parse the news manifest; `null` on any transport/parse error. */
    private suspend fun fetchNewsManifest(): NewsManifest? {
        if (USE_SAMPLE_DATA) {
            println("NewsUpdates: USE_SAMPLE_DATA on — using sample news manifest")
            return SAMPLE_NEWS_MANIFEST
        }
        val body = runCatching {
            val response = httpClient.get(newsUrl)
            if (!response.status.isSuccess()) {
                println("NewsUpdates: news fetch failed: HTTP ${response.status}")
                return null
            }
            response.bodyAsText()
        }.getOrElse { t ->
            println("NewsUpdates: news fetch failed: ${t.message ?: t::class.simpleName}")
            return null
        }
        return runCatching { json.decodeFromString<NewsManifest>(body) }.getOrElse { t ->
            println("NewsUpdates: news parse failed: ${t.message ?: t::class.simpleName}")
            null
        }
    }

    /**
     * Compute how long to wait before the first check given the persisted
     * last-check time: `0` to check immediately (first run, or a full interval
     * has elapsed); otherwise the remaining interval, clamped to
     * `[0, checkIntervalMillis]`.
     */
    private fun initialDelayMillis(lastCheckEpochMillis: Long?): Long {
        val sinceLast = lastCheckEpochMillis?.let {
            Clock.System.now().toEpochMilliseconds() - it
        } ?: return 0L
        if (sinceLast >= checkIntervalMillis) return 0L
        return (checkIntervalMillis - sinceLast).coerceIn(0L, checkIntervalMillis)
    }

    /** Render an epoch-millis timestamp as ISO-8601 for logs, or `"never"` for `null`. */
    private fun isoOrNever(epochMillis: Long?): String =
        if (epochMillis == null) "never" else Instant.fromEpochMilliseconds(epochMillis).toString()
}
