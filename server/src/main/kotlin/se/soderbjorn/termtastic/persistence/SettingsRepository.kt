/**
 * SQLite-backed persistent settings storage for Termtastic.
 *
 * This file contains [SettingsRepository], which wraps a SQLDelight-managed
 * SQLite database behind a generic key/value API. It persists the window
 * layout ([WindowConfig]), per-pane scrollback ring buffers, UI preferences
 * (theme, sidebar width, etc. as a JSON blob), device-auth trusted/denied
 * lists, and server feature flags (allow remote connections, Claude usage
 * polling).
 *
 * The repository is created once in [Application.main] and threaded through
 * to all consumers: [WindowState], [DeviceAuth], [SettingsDialog],
 * [ClaudeUsageMonitor], and the `/api/ui-settings` REST endpoints.
 *
 * UI settings are also exposed as a [StateFlow] so the `/window` WebSocket
 * can push live updates to all connected renderers when the user toggles
 * dark/light mode.
 *
 * **UI settings persistence is owned by `toolkit-store`**, not SQLite.
 * [loadUiSettings] / [mergeUiSettings] read and write **two** files:
 *  - `defaultSharedThemesPath()` — cross-app `themes.json` holding theme
 *    and scheme *definitions* shared across every Darkness app.
 *  - `defaultAppUiSettingsPath("termtastic")` — termtastic's per-app
 *    settings file holding *selections* (selected theme slots,
 *    appearance, fonts, sizes, app toggles).
 *
 * SQLite still holds window/scrollback/auth/feature-flag data — those
 * remain termtastic-private and unaffected by the split.
 *
 * @see AppPaths
 * @see WindowState
 * @see DeviceAuth
 */
package se.soderbjorn.termtastic.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.darkness.core.SHARED_THEMES_KEYS
import se.soderbjorn.darkness.core.mergeSharedThemes
import se.soderbjorn.darkness.store.defaultAppUiSettingsPath
import se.soderbjorn.darkness.store.defaultSharedThemesPath
import se.soderbjorn.darkness.store.readUiSettingsRaw
import se.soderbjorn.darkness.store.writeUiSettingsRaw
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.LeafContent
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.Pane
import se.soderbjorn.termtastic.PaneGeometry
import se.soderbjorn.termtastic.TabConfig
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.db.TermtasticDatabase
import se.soderbjorn.termtastic.windowJson
import java.io.File

/**
 * SQLite-backed key/value store. v1 only persists the window config blob, but
 * the table is generic so additional keys (themes, recent commands, etc.) can
 * land without a schema migration.
 *
 * Blob format is versioned via the key suffix (`window.config.v1`) so most
 * future evolution doesn't touch SQL at all.
 */
class SettingsRepository(dbFile: File) {

    private val log = LoggerFactory.getLogger(SettingsRepository::class.java)
    private val driver: JdbcSqliteDriver
    private val database: TermtasticDatabase

    init {
        dbFile.parentFile?.mkdirs()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        val schema = TermtasticDatabase.Schema
        val current = currentUserVersion()
        if (current == 0L) {
            schema.create(driver).value
            setUserVersion(schema.version)
        } else if (current < schema.version) {
            schema.migrate(driver, current, schema.version).value
            setUserVersion(schema.version)
        }

        database = TermtasticDatabase(driver)

        // Belt-and-suspenders: the SQLDelight-generated schema version doesn't
        // bump automatically when we add tables to Settings.sq (no .sqm
        // migrations yet), so pre-existing DBs at user_version=1 would never
        // pick up the new pane_scrollback table otherwise.
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE IF NOT EXISTS pane_scrollback (
                    leaf_id    TEXT NOT NULL PRIMARY KEY,
                    bytes      BLOB NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent(),
            parameters = 0,
        )
    }

    /**
     * Load the persisted window layout configuration.
     *
     * Prefers the v3 key (free-form pane layout); otherwise falls back to v2
     * (split tree + floating layer) and flattens the split tree into a flat
     * list of absolute panes; v1 first renames MarkdownContent → FileBrowserContent,
     * then flattens. Migrated configs are re-persisted under v3 so subsequent
     * boots skip the rewrite. Corrupt blobs are quarantined under a
     * timestamped key.
     *
     * @return the deserialised [WindowConfig], or null if none exists
     * @see saveWindowConfig
     */
    fun loadWindowConfig(): WindowConfig? {
        // Prefer v3 (current format).
        val v3 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V3)
            .executeAsOneOrNull()
        if (v3 != null) {
            return decodeOrQuarantine(v3, WINDOW_CONFIG_KEY_V3)
        }
        // Fall back to v2 (split tree). Flatten and re-persist under v3.
        val v2 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V2)
            .executeAsOneOrNull()
        if (v2 != null) {
            val migrated = migrateV2ToV3(v2) ?: return null
            val encoded = windowJson.encodeToString(WindowConfig.serializer(), migrated)
            database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V3, encoded)
            database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V2)
            return migrated
        }
        // Fall back to v1 (pre-file-browser). Rename discriminator, then flatten.
        val v1 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V1)
            .executeAsOneOrNull()
            ?: return null
        val v1Migrated = v1.replace("\"kind\":\"markdown\"", "\"kind\":\"fileBrowser\"")
        val flattened = migrateV2ToV3(v1Migrated) ?: return null
        val encoded = windowJson.encodeToString(WindowConfig.serializer(), flattened)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V3, encoded)
        database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V1)
        return flattened
    }

    /**
     * Deserialise a legacy v2 blob (split-tree + floating layer) and flatten
     * it into a v3 [WindowConfig] with `TabConfig.panes`. Each leaf in the
     * split tree is assigned an absolute `(x, y, width, height)` derived
     * recursively from its ancestor ratios, snapped to the 10% grid. Floating
     * panes are appended verbatim (also snapped). Z values are assigned in
     * stable traversal order, tree-first then floating, so stacking is
     * preserved.
     *
     * Returns null if the blob can't be parsed; the blob is quarantined as a
     * side effect.
     */
    private fun migrateV2ToV3(raw: String): WindowConfig? {
        val legacy = try {
            legacyJson.decodeFromString(LegacyConfig.serializer(), raw)
        } catch (t: Throwable) {
            val ts = System.currentTimeMillis()
            val quarantineKey = "$WINDOW_CONFIG_KEY_V2.corrupt.$ts"
            log.warn(
                "Failed to decode legacy v2 window config; quarantining as '{}'",
                quarantineKey,
                t,
            )
            database.settingsQueries.upsert(quarantineKey, raw)
            database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V2)
            return null
        }
        val migratedTabs = legacy.tabs.map { tab ->
            val panes = mutableListOf<Pane>()
            var zCounter = 0L
            tab.root?.let { flattenLegacyNode(it, 0.0, 0.0, 1.0, 1.0, panes) { ++zCounter } }
            for (fp in tab.floating) {
                val box = PaneGeometry.normalize(fp.x, fp.y, fp.width, fp.height)
                panes.add(
                    Pane(
                        leaf = fp.leaf.toLeafNode(),
                        x = box.x, y = box.y,
                        width = box.width, height = box.height,
                        z = ++zCounter,
                    )
                )
            }
            TabConfig(
                id = tab.id,
                title = tab.title,
                panes = panes,
                focusedPaneId = tab.focusedPaneId,
            )
        }
        return WindowConfig(tabs = migratedTabs, activeTabId = legacy.activeTabId)
    }

    private fun flattenLegacyNode(
        node: LegacyNode,
        x: Double, y: Double, w: Double, h: Double,
        out: MutableList<Pane>,
        nextZ: () -> Long,
    ) {
        when (node) {
            is LegacyLeaf -> {
                val box = PaneGeometry.normalize(x, y, w, h)
                out.add(
                    Pane(
                        leaf = node.toLeafNode(),
                        x = box.x, y = box.y,
                        width = box.width, height = box.height,
                        z = nextZ(),
                    )
                )
            }
            is LegacySplit -> {
                val r = node.ratio.coerceIn(0.05, 0.95)
                if (node.orientation.equals("Horizontal", ignoreCase = true)) {
                    flattenLegacyNode(node.first, x, y, w * r, h, out, nextZ)
                    flattenLegacyNode(node.second, x + w * r, y, w * (1.0 - r), h, out, nextZ)
                } else {
                    flattenLegacyNode(node.first, x, y, w, h * r, out, nextZ)
                    flattenLegacyNode(node.second, x, y + h * r, w, h * (1.0 - r), out, nextZ)
                }
            }
        }
    }

    private fun decodeOrQuarantine(raw: String, sourceKey: String): WindowConfig? {
        return try {
            windowJson.decodeFromString(WindowConfig.serializer(), raw)
        } catch (t: Throwable) {
            val ts = System.currentTimeMillis()
            val quarantineKey = "$sourceKey.corrupt.$ts"
            log.warn(
                "Failed to decode persisted window config; quarantining as '{}'",
                quarantineKey,
                t
            )
            database.settingsQueries.upsert(quarantineKey, raw)
            database.settingsQueries.deleteByKey(sourceKey)
            null
        }
    }

    /**
     * Persist the window layout configuration to SQLite.
     *
     * Serialises [config] to JSON and upserts it under the current version key.
     * Runs on [Dispatchers.IO] to avoid blocking the caller.
     *
     * @param config the window configuration to persist
     * @see loadWindowConfig
     */
    suspend fun saveWindowConfig(config: WindowConfig) = withContext(Dispatchers.IO) {
        val json = windowJson.encodeToString(WindowConfig.serializer(), config)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V3, json)
    }

    /**
     * Load the persisted scrollback ring buffer for a pane.
     *
     * @param leafId the leaf node id whose scrollback to load
     * @return the raw scrollback bytes, or null if none have been saved
     */
    fun loadScrollback(leafId: String): ByteArray? =
        database.settingsQueries.selectScrollback(leafId).executeAsOneOrNull()

    /**
     * Persist the scrollback ring buffer for a pane.
     *
     * @param leafId the leaf node id to save scrollback for
     * @param bytes the raw ring buffer bytes to persist
     */
    suspend fun saveScrollback(leafId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        database.settingsQueries.upsertScrollback(leafId, bytes, System.currentTimeMillis())
    }

    /**
     * Delete the persisted scrollback for a pane (used during GC of stale leaves).
     *
     * @param leafId the leaf node id whose scrollback to delete
     */
    fun deleteScrollback(leafId: String) {
        database.settingsQueries.deleteScrollback(leafId)
    }

    /**
     * Return the set of all leaf ids that have persisted scrollback data.
     * Used by the GC pass in [WindowState.initialize] to identify stale entries.
     *
     * @return set of leaf id strings
     */
    fun allScrollbackLeafIds(): Set<String> =
        database.settingsQueries.selectAllScrollbackLeafIds().executeAsList().toSet()

    /**
     * Read a raw string value from the settings key/value store.
     *
     * @param key the settings key
     * @return the stored value, or null if the key does not exist
     */
    fun getString(key: String): String? =
        database.settingsQueries.selectByKey(key).executeAsOneOrNull()

    /**
     * Write a raw string value to the settings key/value store (upsert).
     *
     * @param key the settings key
     * @param value the string value to store
     */
    fun putString(key: String, value: String) {
        database.settingsQueries.upsert(key, value)
    }

    /**
     * Close the underlying SQLite JDBC driver. Called from the shutdown hook
     * in [Application.main].
     */
    fun close() {
        driver.close()
    }

    private fun currentUserVersion(): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor: SqlCursor ->
                val v = if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L
                QueryResult.Value(v)
            },
            parameters = 0
        ).value
    }

    private fun setUserVersion(version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    /**
     * Termtastic's [appName] for the per-app UI-settings file. Used as the
     * filename stem (e.g. `termtastic.json`) under the shared Darkness
     * directory.
     */
    private val appName: String = "termtastic"

    /**
     * Path to the shared Darkness theme/scheme **definitions** file
     * (`themes.json`), owned by `toolkit-store`. This file is shared
     * across every Darkness app on this machine: custom themes/schemes
     * authored in one app are visible in the others. Falls back to a
     * sub-path of the termtastic data dir if the OS-conventional path
     * can't be resolved (rare — `user.home` would have to be missing).
     */
    private val sharedThemesPath: String =
        defaultSharedThemesPath() ?: java.io.File(dbFile.parentFile, "themes.json").absolutePath

    /**
     * Path to termtastic's per-app UI-settings file (`termtastic.json`).
     * Holds the user's selections + UI preferences (selected theme slots,
     * appearance, fonts, sizes, app-specific toggles) — *not* shared with
     * other Darkness apps.
     */
    private val appSettingsPath: String =
        defaultAppUiSettingsPath(appName)
            ?: java.io.File(dbFile.parentFile, "$appName.json").absolutePath

    private val _uiSettings = MutableStateFlow(loadUiSettings())
    val uiSettings: StateFlow<JsonObject> = _uiSettings.asStateFlow()

    /**
     * Load the merged UI-settings JsonObject from the two files: the
     * cross-app `themes.json` (definitions, canonical nested form) and
     * termtastic's per-app settings file (selections + UI prefs, flat
     * scalars). The per-app file takes precedence on key collisions —
     * its keys are termtastic-local choices that should not be
     * overridden by another app's edits.
     *
     * The merged JsonObject is consumed directly by the renderer's
     * `applyServerUiSettings` via [ThemeSnapshot.fromJsonObject], which
     * expects nested form for the shared keys.
     */
    private fun loadUiSettings(): JsonObject {
        val shared = readJsonObject(sharedThemesPath)
        val perApp = readJsonObject(appSettingsPath)
        return JsonObject(shared + perApp)
    }

    private fun readJsonObject(path: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val raw = readUiSettingsRaw(path) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return runCatching { Json.parseToJsonElement(raw) as JsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * Return the current UI settings JSON object (in-memory snapshot).
     *
     * @return the merged UI settings as a [JsonObject]
     */
    fun getUiSettings(): JsonObject = _uiSettings.value

    /**
     * Replace the in-memory UI settings snapshot with [snapshot] without
     * touching disk. Called by the file-watch subscription installed in
     * [se.soderbjorn.termtastic.installSharedThemesWatcher] when an
     * external Darkness app rewrites either of the two backing files.
     *
     * @param snapshot the new in-memory snapshot to publish to subscribers
     */
    fun publishExternalUiSettings(snapshot: JsonObject) {
        _uiSettings.value = snapshot
    }

    /**
     * Merge [incoming] keys into the existing UI settings, partition the
     * result across the shared `themes.json` (theme/scheme definitions)
     * and the per-app `termtastic.json` (selections + UI prefs), and
     * update the in-memory [uiSettings] flow.
     *
     * Both writes are atomic and last-writer-wins on each file
     * independently — the partition is deterministic so concurrent writes
     * from another Darkness app and termtastic only race within their
     * respective scopes.
     *
     * @param incoming JSON object with the keys to merge (new keys are added,
     *                 existing keys are overwritten)
     * @return the merged [JsonObject] after persistence
     */
    fun mergeUiSettings(incoming: JsonObject): JsonObject {
        val existing = _uiSettings.value
        val merged = JsonObject(existing + incoming)
        val sharedKeys = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        val perAppKeys = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in merged) {
            if (k in SHARED_THEMES_KEYS) sharedKeys[k] = v else perAppKeys[k] = v
        }
        // Read-merge-write for the cross-app `themes.json` so a peer
        // app's contributions (custom themes/schemes added since our
        // in-memory snapshot was last refreshed by the file watcher)
        // aren't clobbered by our wholesale rewrite. [mergeSharedThemes]
        // returns canonical nested form, which is also the on-disk
        // format every Darkness app reads. The per-app file is
        // single-writer so it doesn't need merging.
        val sharedOnDisk = JsonObject(readJsonObject(sharedThemesPath))
        val sharedFinal = mergeSharedThemes(JsonObject(sharedKeys), sharedOnDisk)
        runCatching { writeUiSettingsRaw(sharedThemesPath, sharedFinal.toString()) }
            .onFailure { log.warn("Failed to persist shared themes to {}", sharedThemesPath, it) }
        runCatching { writeUiSettingsRaw(appSettingsPath, JsonObject(perAppKeys).toString()) }
            .onFailure { log.warn("Failed to persist per-app UI settings to {}", appSettingsPath, it) }
        // Refresh the in-memory snapshot to reflect the merged shared
        // contributions. The renderer consumes nested form via
        // [ThemeSnapshot.fromJsonObject]; no flattening needed.
        _uiSettings.value = JsonObject(sharedFinal + JsonObject(perAppKeys))
        return _uiSettings.value
    }

    /**
     * Returns the absolute path of the toolkit-owned shared themes file
     * the server reads from and writes to. Exposed so [se.soderbjorn.termtastic.ServerInitializer]
     * can install a `watchUiSettings` subscription on it.
     */
    fun sharedThemesPath(): String = sharedThemesPath

    /**
     * Returns the absolute path of termtastic's per-app UI-settings file.
     * Exposed so [se.soderbjorn.termtastic.ServerInitializer] can install
     * a watcher on it alongside the shared themes file.
     */
    fun appSettingsPath(): String = appSettingsPath

    /**
     * Whether the server accepts connections from non-loopback addresses.
     * Default is false (localhost only). Toggled at runtime from the
     * settings dialog; the effective check runs in DeviceAuth.authorize, so
     * changes apply on the next incoming request without restarting Netty.
     */
    fun isAllowRemoteConnections(): Boolean =
        getString(ALLOW_REMOTE_KEY)?.equals("true", ignoreCase = true) == true

    /**
     * Toggle whether the server accepts connections from non-loopback addresses.
     *
     * @param value true to allow remote connections, false for localhost only
     */
    fun setAllowRemoteConnections(value: Boolean) {
        putString(ALLOW_REMOTE_KEY, value.toString())
    }

    /**
     * Whether the Claude Code usage polling monitor is enabled.
     *
     * @return true if the user has opted in to usage data collection
     */
    fun isClaudeUsagePollEnabled(): Boolean =
        getString(CLAUDE_USAGE_POLL_KEY)?.equals("true", ignoreCase = true) == true

    /**
     * Toggle the Claude Code usage polling monitor.
     *
     * @param value true to enable polling, false to disable
     */
    fun setClaudeUsagePollEnabled(value: Boolean) {
        putString(CLAUDE_USAGE_POLL_KEY, value.toString())
    }

    companion object {
        internal const val WINDOW_CONFIG_KEY_V1 = "window.config.v1"
        internal const val WINDOW_CONFIG_KEY_V2 = "window.config.v2"
        internal const val WINDOW_CONFIG_KEY_V3 = "window.config.v3"
        /** Current write key. Updated whenever the persisted schema rev bumps. */
        const val WINDOW_CONFIG_KEY = WINDOW_CONFIG_KEY_V3
        private const val ALLOW_REMOTE_KEY = "network.allow_remote.v1"
        private const val CLAUDE_USAGE_POLL_KEY = "claude.usage_poll.v1"
    }
}

// ---------------------------------------------------------------------------
// Legacy v2 wire format — throwaway types used only during migration so the
// old split-tree JSON still deserialises after the real WindowConfig/LeafNode
// dropped their `PaneNode`/`SplitNode`/`FloatingPane` counterparts.
// ---------------------------------------------------------------------------

/** Lenient decoder for legacy blobs: ignores any fields we no longer care about. */
private val legacyJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
}

@Serializable
private data class LegacyConfig(
    val tabs: List<LegacyTab>,
    val activeTabId: String? = null,
)

@Serializable
private data class LegacyTab(
    val id: String,
    val title: String,
    val root: LegacyNode? = null,
    val floating: List<LegacyFloater> = emptyList(),
    val focusedPaneId: String? = null,
)

@Serializable
private sealed class LegacyNode

@Serializable
@SerialName("leaf")
private data class LegacyLeaf(
    val id: String,
    val sessionId: String,
    val title: String,
    val customName: String? = null,
    val cwd: String? = null,
    val content: LeafContent? = null,
    val isLink: Boolean = false,
) : LegacyNode() {
    fun toLeafNode(): LeafNode = LeafNode(
        id = id, sessionId = sessionId, title = title,
        customName = customName, cwd = cwd, content = content, isLink = isLink,
    )
}

@Serializable
@SerialName("split")
private data class LegacySplit(
    val id: String,
    val orientation: String,
    val ratio: Double,
    val first: LegacyNode,
    val second: LegacyNode,
) : LegacyNode()

@Serializable
private data class LegacyFloater(
    val leaf: LegacyLeaf,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val z: Long,
)

