/**
 * Bootstrap helpers wired up by [main] in [Application]: SQLite restore,
 * scrollback saver, session-state poller, and the Claude usage monitor.
 *
 * Each function is small and stateless — `main()` composes them in order
 * and registers the shutdown hook.
 *
 * @see WindowState
 * @see SettingsRepository
 * @see ClaudeUsageMonitor
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.SHARED_THEMES_KEYS
import se.soderbjorn.darkness.store.Closeable
import se.soderbjorn.darkness.store.readUiSettingsRaw
import se.soderbjorn.darkness.store.watchUiSettings
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Snapshot of the per-leaf "last bytes saved" map plus the closure that
 * walks the live config and saves only those leaves whose ring contents
 * have advanced since the previous save.
 *
 * Returned by [installScrollbackSaver] so [main]'s shutdown hook can call
 * the flush function with `force = true` for a final write.
 */
class ScrollbackSaver internal constructor(
    private val repo: SettingsRepository,
    private val lastSavedBytes: ConcurrentHashMap<String, Long>,
) {
    /**
     * Iterate over every leaf in the live config and persist its ring buffer
     * snapshot when [force] is `true` or when the leaf's [TerminalSession.bytesWritten]
     * has advanced since the previous save. The session's effective grid size
     * is sampled alongside the snapshot so a restore can replay the bytes at
     * the width they were rendered for (raw PTY output only reconstructs
     * correctly in a same-width grid).
     */
    suspend fun saveAll(force: Boolean) {
        fun collect(leaf: LeafNode, out: MutableList<Pair<String, String>>) {
            val content = leaf.content
            val sid = when (content) {
                is TerminalContent -> content.sessionId
                null -> leaf.sessionId.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (sid != null && sid.isNotEmpty()) out.add(leaf.id to sid)
        }
        val pairs = mutableListOf<Pair<String, String>>()
        for (tab in WindowState.config.value.tabs) {
            tab.panes.forEach { collect(it.leaf, pairs) }
        }
        for ((leafId, sessionId) in pairs) {
            val session = TerminalSessions.get(sessionId) ?: continue
            val current = session.bytesWritten()
            if (!force && lastSavedBytes[leafId] == current) continue
            // Sample the size before the snapshot: a resize racing in between
            // is at worst off by one resize and gets corrected by the client's
            // post-restore reassert.
            val (cols, rows) = session.sizeEvents.value
            val snapshot = session.snapshot()
            runCatching { repo.saveScrollback(leafId, snapshot, cols, rows) }
                .onSuccess { lastSavedBytes[leafId] = current }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Failed to save scrollback for $leafId", it) }
        }
    }
}

/**
 * Launch the debounced window-config persistence coroutine. Bursts of
 * mutations coalesce into a single SQLite write at 2 s after the burst
 * settles. `drop(1)` skips the initial value StateFlow replays so we don't
 * immediately rewrite the row we just loaded from.
 */
@OptIn(FlowPreview::class)
internal fun installWindowConfigPersister(scope: CoroutineScope, repo: SettingsRepository) {
    scope.launch {
        WindowState.config
            .drop(1)
            .debounce(2_000.milliseconds)
            .collectLatest { cfg ->
                runCatching { repo.saveWindowConfig(cfg.withBlankSessionIds()) }
                    .onFailure { LoggerFactory.getLogger("WindowPersistence").warn("Failed to persist window config", it) }
            }
    }
}

/**
 * Launch a fast-path persister for per-world theme changes.
 *
 * [installWindowConfigPersister] coalesces bursts at 2 s, which is right for
 * high-churn events (cwd / title / pane geometry) but leaves a window where a
 * low-churn, high-value theme pick can be lost to a hard kill before it reaches
 * disk (issue #127 follow-up: "persistence seems unreliable"). This collector
 * watches only the worlds' theme pairs (`distinctUntilChanged` so ordinary
 * config churn is ignored) and flushes the whole config within 250 ms of a
 * change — long enough to coalesce a dark+light double-write, short enough that
 * a theme selection survives an abrupt exit. The write is idempotent with the
 * 2 s persister (both upsert the full config under the same key), so the two
 * coexisting is harmless.
 *
 * @param scope the persistence coroutine scope (shared with the 2 s persister).
 * @param repo  the settings repository the full config is upserted through.
 * @see installWindowConfigPersister
 */
@OptIn(FlowPreview::class)
internal fun installWorldThemePersister(scope: CoroutineScope, repo: SettingsRepository) {
    scope.launch {
        WindowState.config
            .map { cfg -> cfg.worlds.map { it.themeSelection } }
            .distinctUntilChanged()
            .drop(1)
            .debounce(250.milliseconds)
            .collectLatest {
                runCatching { repo.saveWindowConfig(WindowState.config.value.withBlankSessionIds()) }
                    .onFailure { LoggerFactory.getLogger("WindowPersistence").warn("Failed to persist world theme", it) }
            }
    }
}

/**
 * Build a [ScrollbackSaver] and launch the periodic save loop. 10 s
 * cadence keeps the DB warm without thrashing on busy shells; crashes
 * lose at most that window.
 */
internal fun installScrollbackSaver(scope: CoroutineScope, repo: SettingsRepository): ScrollbackSaver {
    val lastSavedBytes = ConcurrentHashMap<String, Long>()
    val saver = ScrollbackSaver(repo, lastSavedBytes)
    scope.launch {
        while (true) {
            delay(10_000)
            runCatching { saver.saveAll(force = false) }
                .onFailure { LoggerFactory.getLogger("ScrollbackPersistence").warn("Periodic scrollback save failed", it) }
        }
    }
    return saver
}

/**
 * Subscribe to **both** the cross-app shared themes file at
 * [SettingsRepository.sharedThemesPath] and Lunamux's per-app
 * UI-settings file at [SettingsRepository.appSettingsPath], re-reading
 * the shared file on every external change and republishing the merged
 * snapshot via [SettingsRepository.publishExternalUiSettings]. The
 * merged blob fans out through the existing `/window` UiSettings socket
 * push to every connected client. Each watcher suppresses events the
 * local process triggered, so saves originating from `mergeUiSettings`
 * won't loop.
 *
 * Per-app keys are sourced from the in-memory snapshot
 * ([SettingsRepository.getUiSettings]), NOT re-read from disk. The
 * per-app file is single-writer (this server), so memory is
 * authoritative; re-reading races with `mergeUiSettings`, which writes
 * `themes.json` then `termtastic.json` sequentially. A watcher fire for
 * the shared file in between those two writes would otherwise read the
 * still-stale per-app file and broadcast the prior theme selection,
 * causing intermittent "theme flipped to a previous one" regressions
 * (visible on routine actions like adding a pane, which persists
 * `LAYOUT_STATE` through the same merge path).
 *
 * Republish is also gated by [isPublishableUiSettings] as a defensive
 * belt-and-braces check: if the in-memory snapshot has no usable v2
 * theme-selection key, broadcasting a partial (mid-write) blob would
 * coerce clients to their slot defaults.
 *
 * @param repo the settings repository whose `_uiSettings` flow gets the update
 * @return a [Closeable] handle the shutdown hook can close to stop watching
 */
internal fun installSharedThemesWatcher(repo: SettingsRepository): Closeable {
    val sharedPath = repo.sharedThemesPath()
    val appPath = repo.appSettingsPath()

    fun republish() {
        repo.publishExternalUiSettings(buildRepublishSnapshot(sharedPath, repo.getUiSettings()) ?: return)
    }

    val sharedWatch = runCatching {
        watchUiSettings(sharedPath) { _ -> republish() }
    }.getOrElse { t ->
        LoggerFactory.getLogger("SharedThemesWatcher")
            .warn("Failed to install shared themes watcher on {}; live theme propagation disabled", sharedPath, t)
        Closeable { /* no-op fallback */ }
    }
    val appWatch = runCatching {
        watchUiSettings(appPath) { _ -> republish() }
    }.getOrElse { t ->
        LoggerFactory.getLogger("SharedThemesWatcher")
            .warn("Failed to install per-app UI-settings watcher on {}; live UI-pref propagation disabled", appPath, t)
        Closeable { /* no-op fallback */ }
    }
    return Closeable {
        runCatching { sharedWatch.close() }
        runCatching { appWatch.close() }
    }
}

private fun readJsonObject(path: String): Map<String, kotlinx.serialization.json.JsonElement> {
    val raw = readUiSettingsRaw(path) ?: return emptyMap()
    if (raw.isBlank()) return emptyMap()
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: emptyMap()
}

/**
 * Decide whether a merged UI-settings snapshot has enough state to push
 * to clients. Under the v2 theme system the selected slots + appearance
 * live in the per-app [PersistKeys.THEME_V2_SELECTION] blob; if that key
 * is absent the snapshot is partial (mid-write of the per-app file) and
 * broadcasting it would coerce clients back to their slot defaults.
 *
 * Internal so the test suite can exercise the predicate directly.
 *
 * @param merged the proposed snapshot
 * @return true when the snapshot has a usable v2 theme-selection key
 */
internal fun isPublishableUiSettings(merged: JsonObject): Boolean {
    val selElement = merged[PersistKeys.THEME_V2_SELECTION] ?: return false
    val prim = selElement as? kotlinx.serialization.json.JsonPrimitive ?: return false
    if (!prim.isString) return false
    return prim.content.isNotBlank()
}

/**
 * Build the snapshot the shared-themes watcher will broadcast: shared
 * theme/scheme definitions read from disk at [sharedPath], merged with
 * the per-app keys from the caller-supplied [inMemSnapshot]. Returns
 * `null` when the resulting blob isn't publishable
 * (see [isPublishableUiSettings]).
 *
 * Per-app keys come from memory rather than disk because the per-app
 * file is single-writer (this server) and `mergeUiSettings` updates
 * memory only after both of its sequential disk writes complete — so
 * re-reading from disk during a watcher fire that lands between those
 * writes would publish the stale prior theme selection.
 *
 * Internal so the test suite can exercise the merge directly without
 * spinning up a real [SettingsRepository].
 *
 * @param sharedPath absolute path of the cross-app `themes.json`
 * @param inMemSnapshot in-memory UI-settings snapshot (per-app keys are
 *   taken from here; keys in [SHARED_THEMES_KEYS] are ignored — those
 *   come from disk)
 * @return the snapshot to broadcast, or `null` if it isn't publishable yet
 */
internal fun buildRepublishSnapshot(sharedPath: String, inMemSnapshot: JsonObject): JsonObject? {
    val sharedObj = readJsonObject(sharedPath)
    val perAppKeys = inMemSnapshot.filterKeys { it !in SHARED_THEMES_KEYS }
    val merged = JsonObject(sharedObj + perAppKeys)
    if (!isPublishableUiSettings(merged)) return null
    return merged
}

/**
 * Launch the periodic AI-state poller. Polls every 3 s and broadcasts to
 * connected clients via the returned shared flow. Replay is 1 so newly
 * connected clients get the latest snapshot immediately.
 */
internal fun installSessionStatePoller(scope: CoroutineScope): MutableSharedFlow<Map<String, String?>> {
    val sessionStates = MutableSharedFlow<Map<String, String?>>(replay = 1)
    scope.launch {
        while (true) {
            delay(3_000)
            sessionStates.emit(TerminalSessions.resolveStates())
        }
    }
    return sessionStates
}
