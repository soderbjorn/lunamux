/**
 * Single cross-platform repository for all on-device, non-server local state.
 *
 * Every piece of local state a Termtastic mobile client must remember across
 * launches — the device-auth token, the user's saved [HostEntry] list (with TLS
 * pins), the first-launch onboarding flag, and the news/update bookkeeping
 * (dismissed ids + last-check time) — lives in one [LocalState] value persisted
 * as a single `local_state.json` file through the platform [LocalStore]. This
 * replaces the previous per-concern, per-platform stores (Android Jetpack
 * DataStore + iOS `hosts.json` / `UserDefaults` / Keychain) with one shared
 * source of truth so Android and iOS never drift.
 *
 * The only local state deliberately *not* owned here is the server-synced UI
 * settings (theme, fonts, layout), which round-trip through the server over the
 * `/window` socket and are therefore not on-device state at all — see
 * [se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel].
 *
 * Following the project's shared-view-model convention, platform glue constructs
 * exactly one [LocalRepository] (Android's `AppLocalRepository`, iOS's
 * `AppRepository`), calls [start] once, and observes [state]; the news checker
 * ([se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel]) is
 * handed the same instance so its persistence flows through this file too.
 *
 * Security note: the one genuinely sensitive value — the device-auth token — is
 * deliberately *not* kept in the plain `local_state.json`. It lives in the
 * platform [SecureStore] (iOS Keychain, Android `EncryptedSharedPreferences`),
 * while every other field stays in the unified JSON file. The repository ties
 * the two together so callers still see one [getOrCreateAuthToken] entry point.
 *
 * @see LocalStore
 * @see SecureStore
 * @see LocalState
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel
 */
package se.soderbjorn.termtastic.client.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.soderbjorn.termtastic.client.HostEntry
import se.soderbjorn.termtastic.client.encodeBase64Url
import se.soderbjorn.termtastic.client.secureRandomBytes

/** The `local_state.json` file name persisted via [LocalStore]. */
const val LOCAL_STATE_FILE_NAME: String = "local_state.json"

/**
 * The complete on-device local state, serialized as the single `local_state.json`
 * blob. Every field has a default so a missing or partially-written file decodes
 * cleanly into sensible empty values (and so newly-added fields are
 * forward/backward compatible).
 *
 * Note: the device-auth token is intentionally absent here — it is held in the
 * platform [SecureStore], not this plain-JSON blob. See [LocalRepository].
 *
 * @property hosts the user's saved server entries shown in the host picker.
 * @property onboardingSeen whether the first-launch walkthrough has been completed.
 * @property dismissedNewsIds the news-item ids the user has swiped away.
 * @property dismissedUpdateVersionCode the `latestVersionCode` of an update the
 *   user has dismissed ("seen, don't remind me"), or `null` if none. Keyed on the
 *   version code so a *newer* release (a higher code) is shown again rather than
 *   suppressed; cleared by [LocalRepository.clearDismissed].
 * @property lastCheckEpochMillis epoch-millis of the last completed news/update
 *   check, or `null` if none has completed.
 * @see LocalRepository
 */
@Serializable
data class LocalState(
    val hosts: List<HostEntry> = emptyList(),
    val onboardingSeen: Boolean = false,
    val dismissedNewsIds: Set<String> = emptySet(),
    val dismissedUpdateVersionCode: Long? = null,
    val lastCheckEpochMillis: Long? = null,
)

/**
 * Construct a [LocalRepository] over the given [localStore] with default scope.
 *
 * Kotlin default-argument constructors do not bridge to Swift/ObjC (every
 * parameter becomes required), so the iOS app calls this factory — reachable as
 * `LocalRepositoryKt.createLocalRepository(...)` — rather than the constructor.
 * Android constructs [LocalRepository] directly.
 *
 * @param localStore the platform JSON-file store backing `local_state.json`.
 * @param secureStore the platform secret store holding the device-auth token.
 * @return a not-yet-started [LocalRepository]; the caller should call [start].
 */
fun createLocalRepository(localStore: LocalStore, secureStore: SecureStore): LocalRepository =
    LocalRepository(localStore, secureStore)

/**
 * Owns [LocalState] and persists every mutation to `local_state.json`.
 *
 * Reads and writes are serialized through a [Mutex] so concurrent mutators (a UI
 * action and the news checker writing at once) never interleave a lost update.
 * [state] starts as `null` to signal "not yet hydrated"; platform UIs render a
 * loading/empty state until the first non-null value arrives, exactly as the old
 * per-platform stores' null-until-loaded sentinel did.
 *
 * @param store the platform JSON-file store backing `local_state.json`.
 * @param secureStore the platform secret store holding the device-auth token
 *   (iOS Keychain, Android `EncryptedSharedPreferences`) outside the plain JSON.
 * @param scope coroutine scope used by [start] and internal writes; defaults to a
 *   background [SupervisorJob] scope so a failed write never tears down siblings.
 */
class LocalRepository(
    private val store: LocalStore,
    private val secureStore: SecureStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<LocalState?>(null)

    /**
     * The current local state, or `null` until the first hydration completes.
     * Observed by every platform UI (host list, onboarding gate) and by the news
     * checker.
     */
    val state: StateFlow<LocalState?> = _state.asStateFlow()

    /** Guards hydration and every read-modify-write so they cannot interleave. */
    private val mutex = Mutex()

    private var hydrated = false
    private var started = false

    /**
     * Kick off hydration on [scope]. Idempotent — repeated calls are ignored, so
     * platform bootstrap can call it freely. The first read of `local_state.json`
     * publishes a non-null [state].
     *
     * Called once by each platform's app bootstrap.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { ensureLoaded() }
    }

    /**
     * Hydrate from disk if not already done and return the current snapshot.
     * Suspends until `local_state.json` has been read at least once, so callers
     * that must see persisted values (e.g. the news checker, the auth-token
     * mint, every mutator) can await a consistent base. Safe to call repeatedly.
     *
     * @return the hydrated [LocalState] (never `null`).
     */
    suspend fun ensureLoaded(): LocalState {
        if (!hydrated) {
            mutex.withLock {
                if (!hydrated) {
                    val text = runCatching { store.read(LOCAL_STATE_FILE_NAME) }.getOrNull()
                    val parsed = if (text == null) {
                        LocalState()
                    } else {
                        runCatching { json.decodeFromString<LocalState>(text) }.getOrElse {
                            println("LocalRepository: failed to parse $LOCAL_STATE_FILE_NAME; starting empty")
                            LocalState()
                        }
                    }
                    _state.value = parsed
                    hydrated = true
                }
            }
        }
        return _state.value ?: LocalState()
    }

    /**
     * Return the stored auth token, minting and persisting a fresh 32-byte
     * base64url token on first use. Idempotent after the first call on a device.
     *
     * Unlike the other fields, the token is read from and written to the platform
     * [SecureStore] (iOS Keychain, Android `EncryptedSharedPreferences`) under
     * [SECURE_AUTH_TOKEN_KEY], never the plain `local_state.json`. On iOS the key
     * matches the pre-consolidation `KeychainAuthTokenStore`, so an existing
     * device's token — and its server approval — carries over.
     *
     * Called by each platform's connect flow before opening the socket.
     *
     * @return the existing or newly-minted base64url device-auth token.
     */
    suspend fun getOrCreateAuthToken(): String {
        secureStore.read(SECURE_AUTH_TOKEN_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        val token = encodeBase64Url(secureRandomBytes(32))
        secureStore.write(SECURE_AUTH_TOKEN_KEY, token)
        return token
    }

    /**
     * Append a new host entry with a freshly-generated UUID and persist it.
     *
     * @param label user-visible display name for the host.
     * @param host hostname or IP address of the server.
     * @param port TCP port of the server.
     * @return the newly-created [HostEntry].
     */
    suspend fun addHost(label: String, host: String, port: Int): HostEntry {
        val entry = HostEntry(id = randomUuid(), label = label, host = host, port = port)
        mutate { it.copy(hosts = it.hosts + entry) }
        return entry
    }

    /**
     * Replace an existing host entry, matching on [HostEntry.id]. Used both for
     * edits and to record a captured TLS pin after a TOFU first-connect.
     *
     * @param entry the updated entry whose id must already exist.
     */
    suspend fun updateHost(entry: HostEntry) {
        mutate { s -> s.copy(hosts = s.hosts.map { if (it.id == entry.id) entry else it }) }
    }

    /**
     * Remove the host entry with the given [id].
     *
     * @param id the [HostEntry.id] to delete.
     */
    suspend fun deleteHost(id: String) {
        mutate { s -> s.copy(hosts = s.hosts.filterNot { it.id == id }) }
    }

    /**
     * Persist the first-launch onboarding flag.
     *
     * @param seen `true` once the walkthrough has been completed.
     */
    suspend fun setOnboardingSeen(seen: Boolean) {
        mutate { it.copy(onboardingSeen = seen) }
    }

    /**
     * Record a dismissed news-item id so it never reappears.
     *
     * Called by [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.dismissNews].
     *
     * @param id the news-item id to remember as dismissed.
     */
    suspend fun addDismissedNewsId(id: String) {
        mutate { it.copy(dismissedNewsIds = it.dismissedNewsIds + id) }
    }

    /**
     * Record the version code of an update the user has dismissed so the "New
     * update" box stays hidden until a newer build (a higher code) is published.
     *
     * Called by [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.dismissUpdate].
     *
     * @param code the dismissed update's `latestVersionCode`.
     */
    suspend fun setDismissedUpdateVersionCode(code: Long) {
        mutate { it.copy(dismissedUpdateVersionCode = code) }
    }

    /**
     * Clear every dismissal — both the dismissed-news-id set and the dismissed
     * update version — so all active news items and any available update reappear.
     *
     * Called by [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.restoreAll]
     * when the user taps "Restore" in the News & Updates screen.
     */
    suspend fun clearDismissed() {
        mutate { it.copy(dismissedNewsIds = emptySet(), dismissedUpdateVersionCode = null) }
    }

    /**
     * Record the time of the last completed news/update check.
     *
     * Called by [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.checkNow].
     *
     * @param epochMillis epoch-millis of the just-completed check.
     */
    suspend fun setLastCheckEpochMillis(epochMillis: Long) {
        mutate { it.copy(lastCheckEpochMillis = epochMillis) }
    }

    /**
     * Hydrate, apply [transform] to the current state under the [mutex], publish
     * the result, and write it back to `local_state.json`. A failed write logs
     * and leaves the in-memory state advanced (best-effort persistence, matching
     * the news checker's prior fire-and-forget behaviour).
     *
     * @param transform pure function from the current state to the next state.
     */
    private suspend fun mutate(transform: (LocalState) -> LocalState) {
        ensureLoaded()
        mutex.withLock {
            val next = transform(_state.value ?: LocalState())
            _state.value = next
            runCatching { store.write(LOCAL_STATE_FILE_NAME, json.encodeToString(LocalState.serializer(), next)) }
                .onFailure { println("LocalRepository: failed to persist $LOCAL_STATE_FILE_NAME: ${it.message}") }
        }
    }

    /**
     * Generate a random RFC-4122 version-4 UUID string using the platform's
     * cryptographic RNG ([secureRandomBytes]). Avoids a platform `UUID`
     * dependency so host-id minting stays in shared code.
     *
     * @return a canonical lowercase 8-4-4-4-12 UUID string.
     */
    private fun randomUuid(): String {
        val b = secureRandomBytes(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte() // variant 10xx
        val hex = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
