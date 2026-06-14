/**
 * Shared backing view-model for the cross-platform "new version available"
 * notification.
 *
 * One [UpdateCheckViewModel] is constructed by each platform's app bootstrap
 * (Android's `TermtasticApp`, iOS's app entry via a Swift wrapper, and the
 * macOS Electron renderer's JS bootstrap). It owns all of the update logic so
 * the three platforms never drift: periodic scheduling, fetching the
 * `versions.json` manifest from GitHub, comparing the running build's version
 * code against the published one, and exposing the result as a single
 * immutable [State] over a [StateFlow]. Platform views only render the
 * notification from that state and open [State.infoUrl] when tapped.
 *
 * Scheduling: [start] always fires one check immediately on every app startup,
 * then re-checks every [checkIntervalMillis] (daily) while the app stays
 * running. The last completed check's time is persisted via an injected
 * [UpdateCheckStore] for display/logging. A failed check (network down, parse
 * error, etc.) is a silent no-op: the state is left untouched, so no
 * notification ever appears as a result of a failure.
 *
 * Debug logging follows the project `println("Tag: …")` convention so the
 * check cadence and outcome are visible in Logcat (Android), Xcode/Console
 * (iOS), and DevTools (Electron).
 *
 * @see VersionManifest
 * @see UpdateCheckStore
 */
package se.soderbjorn.termtastic.client.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
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

/** The raw GitHub URL of the published manifest at the repository root. */
const val DEFAULT_MANIFEST_URL: String =
    "https://raw.githubusercontent.com/soderbjorn/termtastic/main/versions.json"

/**
 * How often to re-check for updates after the on-startup check. Daily — the
 * checker also always checks once immediately on every app startup (see
 * [UpdateCheckViewModel.start]), so this only governs the cadence of
 * subsequent checks while the app stays running.
 */
const val CHECK_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * Construct an [UpdateCheckViewModel] with the production defaults for its
 * scope, manifest URL, HTTP client, and interval.
 *
 * Kotlin default-argument constructors do not bridge to Swift/ObjC (every
 * parameter becomes required), so the iOS app calls this factory — reachable as
 * `UpdateCheckViewModelKt.createUpdateCheckViewModel(...)` — to build the
 * view-model with only the values it actually needs to supply. Android and the
 * JS renderer call the constructor directly with Kotlin defaults.
 *
 * @param platformId the [UpdatePlatform] identifier for this platform.
 * @param currentVersionCode the running build's monotonic version code.
 * @param currentVersionName the running build's human-readable version.
 * @param store platform-local persistence for the last-check timestamp.
 * @return a not-yet-started [UpdateCheckViewModel]; the caller must call
 *   [UpdateCheckViewModel.start].
 */
fun createUpdateCheckViewModel(
    platformId: String,
    currentVersionCode: Long,
    currentVersionName: String,
    store: UpdateCheckStore,
): UpdateCheckViewModel = UpdateCheckViewModel(
    platformId = platformId,
    currentVersionCode = currentVersionCode,
    currentVersionName = currentVersionName,
    store = store,
)

/**
 * Owns the periodic update check and exposes its result as a [StateFlow].
 *
 * @param platformId the [UpdatePlatform] identifier for the platform this
 *   client runs on (`"android"`, `"ios"`, or `"mac"`); selects which manifest
 *   entry is consulted.
 * @param currentVersionCode the running build's monotonic version code, used as
 *   the comparison baseline against the manifest's `latestVersionCode`.
 * @param currentVersionName the running build's human-readable version, logged
 *   for context (not used in the comparison).
 * @param store platform-local persistence for the last-check timestamp.
 * @param scope coroutine scope the check loop runs in; defaults to a background
 *   [SupervisorJob] scope so a failed check never tears down siblings.
 * @param manifestUrl the manifest URL to fetch; overridable for testing.
 * @param httpClient the (un-pinned) client used to fetch the manifest; defaults
 *   to a fresh [createPlainHttpClient].
 * @param checkIntervalMillis the re-check interval; defaults to
 *   [CHECK_INTERVAL_MILLIS] and is overridable for testing.
 */
class UpdateCheckViewModel(
    private val platformId: String,
    private val currentVersionCode: Long,
    private val currentVersionName: String,
    private val store: UpdateCheckStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val httpClient: HttpClient = createPlainHttpClient(),
    private val checkIntervalMillis: Long = CHECK_INTERVAL_MILLIS,
) {
    private val _stateFlow = MutableStateFlow(State())

    /** The current update-check state, observed by every platform UI. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private var started = false

    /**
     * Immutable snapshot of the update-check feature, rendered by each platform.
     *
     * @property updateAvailable true when the manifest reports a newer build for
     *   this platform than the one running; drives whether the notification
     *   shows at all.
     * @property latestVersionName the newer version's display name, shown in the
     *   notification; `null` until a check has succeeded with an update.
     * @property infoUrl the "more info" URL opened when the notification is
     *   tapped; `null` until a check has succeeded with an update.
     * @property lastCheckEpochMillis epoch-millis time of the last completed
     *   check, or `null` if none has completed this run.
     * @property nextCheckEpochMillis epoch-millis time the next check is
     *   scheduled for, or `null` before the loop has scheduled one.
     */
    data class State(
        val updateAvailable: Boolean = false,
        val latestVersionName: String? = null,
        val infoUrl: String? = null,
        val lastCheckEpochMillis: Long? = null,
        val nextCheckEpochMillis: Long? = null,
    )

    /**
     * Start the update-check loop. Idempotent — repeated calls after the first
     * are ignored, so platforms can safely call it on every screen appearance
     * without spawning duplicate loops.
     *
     * Always checks once immediately (every app startup), then re-checks every
     * [checkIntervalMillis] (daily) for as long as the app keeps running.
     *
     * Called once by each platform's app bootstrap.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val lastCheck = runCatching { store.loadLastCheckEpochMillis() }.getOrNull()
            _stateFlow.value = _stateFlow.value.copy(lastCheckEpochMillis = lastCheck)
            println(
                "UpdateCheck: starting for platform=$platformId; previous check " +
                    "${isoOrNever(lastCheck)}; checking now, then every ${checkIntervalMillis}ms",
            )
            // Check on every startup, then re-check once per interval (daily).
            while (true) {
                checkNow()
                val next = Clock.System.now().toEpochMilliseconds() + checkIntervalMillis
                _stateFlow.value = _stateFlow.value.copy(nextCheckEpochMillis = next)
                println("UpdateCheck: next check at ${isoOrNever(next)}")
                delay(checkIntervalMillis)
            }
        }
    }

    /**
     * Perform a single update check: fetch the manifest, parse it, compare this
     * platform's entry against the running build, and emit the result. Never
     * throws — and a failure does nothing at all: on any network/parse error
     * the state and stored timestamp are left completely untouched, so a failed
     * check can never surface a notification.
     *
     * Exposed (not private) so a platform or test can trigger an out-of-band
     * check; the periodic loop in [start] calls it on each tick.
     */
    suspend fun checkNow() {
        println(
            "UpdateCheck: checking $manifestUrl for platform=$platformId " +
                "code=$currentVersionCode name=$currentVersionName",
        )
        val manifest = fetchManifest()
        if (manifest == null) {
            // Silent no-op on failure: no state change, no timestamp advance,
            // no notification. We simply retry on the next scheduled tick.
            println("UpdateCheck: check failed — leaving state unchanged (no notification)")
            return
        }
        // Only a successful fetch counts as a completed check.
        val now = Clock.System.now().toEpochMilliseconds()
        runCatching { store.saveLastCheckEpochMillis(now) }
        _stateFlow.value = _stateFlow.value.copy(lastCheckEpochMillis = now)

        if (manifest.schemaVersion > VersionManifest.SUPPORTED_SCHEMA_VERSION) {
            println(
                "UpdateCheck: manifest schemaVersion=${manifest.schemaVersion} newer than " +
                    "supported=${VersionManifest.SUPPORTED_SCHEMA_VERSION}; treating as no update",
            )
            return
        }
        val info = manifest.platforms[platformId]
        if (info == null) {
            println("UpdateCheck: manifest has no entry for platform=$platformId; no update")
            _stateFlow.value = _stateFlow.value.copy(
                updateAvailable = false,
                latestVersionName = null,
                infoUrl = null,
            )
            return
        }
        val updateAvailable = info.latestVersionCode > currentVersionCode
        println(
            "UpdateCheck: result latest code=${info.latestVersionCode} " +
                "name=${info.latestVersionName} updateAvailable=$updateAvailable",
        )
        _stateFlow.value = _stateFlow.value.copy(
            updateAvailable = updateAvailable,
            latestVersionName = if (updateAvailable) info.latestVersionName else null,
            infoUrl = if (updateAvailable) info.url else null,
        )
    }

    /**
     * Fetch and deserialize the manifest. Returns `null` on any transport,
     * status, or parse error so the caller can keep the prior state.
     */
    private suspend fun fetchManifest(): VersionManifest? {
        val body = runCatching {
            val response = httpClient.get(manifestUrl)
            if (!response.status.isSuccess()) {
                println("UpdateCheck: fetch failed: HTTP ${response.status}")
                return null
            }
            response.bodyAsText()
        }.getOrElse { t ->
            println("UpdateCheck: fetch failed: ${t.message ?: t::class.simpleName}")
            return null
        }
        return runCatching { json.decodeFromString<VersionManifest>(body) }.getOrElse { t ->
            println("UpdateCheck: parse failed: ${t.message ?: t::class.simpleName}")
            null
        }
    }

    /**
     * Render an epoch-millis timestamp as an ISO-8601 string for logs, or the
     * literal `"never"` for `null`.
     */
    private fun isoOrNever(epochMillis: Long?): String =
        if (epochMillis == null) "never" else Instant.fromEpochMilliseconds(epochMillis).toString()
}
