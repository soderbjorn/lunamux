/**
 * SQLite-backed persistent settings storage for Lunamux.
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
 *  - `defaultAppUiSettingsPath("termtastic")` — Lunamux's per-app
 *    settings file holding *selections* (selected theme slots,
 *    appearance, fonts, sizes, app toggles).
 *
 * SQLite still holds window/scrollback/auth/feature-flag data — those
 * remain lunamux-private and unaffected by the split.
 *
 * @see AppPaths
 * @see WindowState
 * @see DeviceAuth
 */
package se.soderbjorn.lunamux.persistence

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_DARK_THEME
import se.soderbjorn.darkness.core.DEFAULT_LIGHT_THEME
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.SHARED_THEMES_KEYS
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.darkness.core.builtinTheme
import se.soderbjorn.darkness.core.hexToArgb
import se.soderbjorn.darkness.core.mergeSharedThemes
import se.soderbjorn.darkness.store.defaultAppUiSettingsPath
import se.soderbjorn.darkness.store.defaultSharedThemesPath
import se.soderbjorn.darkness.store.readUiSettingsRaw
import se.soderbjorn.darkness.store.writeUiSettingsRaw
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.PaneGeometry
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TerminalContent
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WorldThemeSelection
import se.soderbjorn.lunamux.db.LunamuxDatabase
import kotlinx.serialization.json.JsonElement
import se.soderbjorn.lunamux.windowJson
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
    private val database: LunamuxDatabase

    init {
        dbFile.parentFile?.mkdirs()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")

        val schema = LunamuxDatabase.Schema
        val current = currentUserVersion()
        if (current == 0L) {
            schema.create(driver).value
            setUserVersion(schema.version)
        } else if (current < schema.version) {
            schema.migrate(driver, current, schema.version).value
            setUserVersion(schema.version)
        }

        database = LunamuxDatabase(driver)

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
                    updated_at INTEGER NOT NULL,
                    cols       INTEGER,
                    rows       INTEGER
                )
            """.trimIndent(),
            parameters = 0,
        )
        // Same no-.sqm story for columns added to an existing table: bring
        // pre-existing DBs up to the current shape. SQLite has no
        // ADD COLUMN IF NOT EXISTS, so the "duplicate column name" error on
        // an already-migrated DB is expected and swallowed.
        runCatching {
            driver.execute(null, "ALTER TABLE pane_scrollback ADD COLUMN cols INTEGER", 0)
        }
        runCatching {
            driver.execute(null, "ALTER TABLE pane_scrollback ADD COLUMN rows INTEGER", 0)
        }
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
        // Prefer v4 (worlds container — current format).
        val v4 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V4)
            .executeAsOneOrNull()
        if (v4 != null) {
            return decodeOrQuarantine(v4, WINDOW_CONFIG_KEY_V4)
        }
        // Fall back to v3 (flat tabs, no worlds). Wrap into one default world
        // and re-persist under v4.
        val v3 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V3)
            .executeAsOneOrNull()
        if (v3 != null) {
            val flat = decodeOrQuarantine(v3, WINDOW_CONFIG_KEY_V3) ?: return null
            val migrated = migrateV3ToV4(flat)
            persistAsV4(migrated)
            database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V3)
            return migrated
        }
        // Fall back to v2 (split tree). Flatten to v3 shape, then wrap to v4.
        val v2 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V2)
            .executeAsOneOrNull()
        if (v2 != null) {
            val migrated = migrateV3ToV4(migrateV2ToV3(v2) ?: return null)
            persistAsV4(migrated)
            database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V2)
            return migrated
        }
        // Fall back to v1 (pre-file-browser). Rename discriminator, flatten, wrap.
        val v1 = database.settingsQueries
            .selectByKey(WINDOW_CONFIG_KEY_V1)
            .executeAsOneOrNull()
            ?: return null
        val v1Migrated = v1.replace("\"kind\":\"markdown\"", "\"kind\":\"fileBrowser\"")
        val migrated = migrateV3ToV4(migrateV2ToV3(v1Migrated) ?: return null)
        persistAsV4(migrated)
        database.settingsQueries.deleteByKey(WINDOW_CONFIG_KEY_V1)
        return migrated
    }

    /** Encode + upsert [cfg] under the current v4 key. */
    private fun persistAsV4(cfg: WindowConfig) {
        val encoded = windowJson.encodeToString(WindowConfig.serializer(), cfg)
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V4, encoded)
    }

    /**
     * V3→V4 migration: wrap a flat [WindowConfig] (tabs at top level, no
     * worlds) into one default world named [DEFAULT_WORLD_NAME], seeding its
     * theme pair from the current global selection. The legacy top-level
     * `tabs`/`activeTabId` are kept mirroring the default world so pre-1.9
     * clients still read them. A config that already carries worlds (a v4
     * blob that arrived here defensively) is returned unchanged.
     *
     * @param flat the pre-worlds config to wrap.
     * @return the worlds-shaped config.
     */
    internal fun migrateV3ToV4(flat: WindowConfig): WindowConfig {
        if (flat.worlds.isNotEmpty()) return flat
        val world = se.soderbjorn.lunamux.WorldConfig(
            id = DEFAULT_WORLD_ID,
            name = DEFAULT_WORLD_NAME,
            tabs = flat.tabs,
            activeTabId = flat.activeTabId,
            themeSelection = currentThemePair(),
        )
        return flat.copy(worlds = listOf(world), activeWorldId = DEFAULT_WORLD_ID)
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
        database.settingsQueries.upsert(WINDOW_CONFIG_KEY_V4, json)
    }

    /**
     * A persisted scrollback blob plus the terminal grid size it was rendered
     * at. Raw PTY bytes only reconstruct correctly when replayed into a grid
     * of the same width they were produced for (a TUI's absolute-cursor
     * repaints land on the wrong cells otherwise), so the saver records the
     * session's effective size alongside the bytes and the restore path seeds
     * the fresh session with it.
     *
     * A deliberate plain class, not a data class: the [bytes] payload has no
     * meaningful equals/hashCode and records are never compared.
     *
     * @property bytes the raw ring-buffer bytes as persisted.
     * @property cols the grid columns at save time, or null on rows written
     *   by servers that predate size recording.
     * @property rows the grid rows at save time, or null on legacy rows.
     * @see loadScrollback
     * @see saveScrollback
     */
    class ScrollbackRecord(val bytes: ByteArray, val cols: Int?, val rows: Int?)

    /**
     * Load the persisted scrollback ring buffer for a pane.
     *
     * Called by `WindowState.rehydrate` when re-creating a pane's session
     * after a server restart.
     *
     * @param leafId the leaf node id whose scrollback to load
     * @return the scrollback bytes plus the grid size they were captured at
     *   (null cols/rows on legacy rows), or null if none have been saved
     */
    fun loadScrollback(leafId: String): ScrollbackRecord? =
        database.settingsQueries.selectScrollback(leafId).executeAsOneOrNull()?.let {
            ScrollbackRecord(it.bytes, it.cols?.toInt(), it.rows?.toInt())
        }

    /**
     * Persist the scrollback ring buffer for a pane.
     *
     * Called by [ScrollbackSaver.saveAll], which samples the session's
     * effective grid size alongside the snapshot so a later restore can
     * replay the bytes at the width they were rendered for.
     *
     * @param leafId the leaf node id to save scrollback for
     * @param bytes the raw ring buffer bytes to persist
     * @param cols the session's effective grid columns at save time
     * @param rows the session's effective grid rows at save time
     */
    suspend fun saveScrollback(leafId: String, bytes: ByteArray, cols: Int?, rows: Int?) =
        withContext(Dispatchers.IO) {
            database.settingsQueries.upsertScrollback(
                leafId, bytes, System.currentTimeMillis(), cols?.toLong(), rows?.toLong(),
            )
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
     * Lunamux's [appName] for the per-app UI-settings file. Used as the
     * filename stem (e.g. `termtastic.json`) under the shared Darkness
     * directory.
     */
    private val appName: String = "termtastic"

    /**
     * Path to the shared Darkness theme/scheme **definitions** file
     * (`themes.json`), owned by `toolkit-store`. This file is shared
     * across every Darkness app on this machine: custom themes/schemes
     * authored in one app are visible in the others. Falls back to a
     * sub-path of the Lunamux data dir if the OS-conventional path
     * can't be resolved (rare — `user.home` would have to be missing).
     */
    private val sharedThemesPath: String =
        defaultSharedThemesPath() ?: java.io.File(dbFile.parentFile, "themes.json").absolutePath

    /**
     * Path to Lunamux's per-app UI-settings file (`termtastic.json`).
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
     * Lunamux's per-app settings file (selections + UI prefs, flat
     * scalars). The per-app file takes precedence on key collisions —
     * its keys are lunamux-local choices that should not be
     * overridden by another app's edits.
     *
     * The merged JsonObject is consumed directly by the renderer's
     * `applyServerUiSettings`, which expects the v2 theme keys
     * ([PersistKeys.THEME_V2_CUSTOM] / [PersistKeys.THEME_V2_SELECTION])
     * for theme state.
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
     * Synthesize the **frozen legacy compat shim** so a PRE-revamp mobile app
     * (which only understands the old `darkness.themeSnapshot` /
     * `darkness.uiSettings` wire shape) renders approximately right when it
     * fetches the current, v2-shaped UI settings.
     *
     * Called by [se.soderbjorn.lunamux.uiSettingsRoutes]'s `GET
     * /api/ui-settings` and by [se.soderbjorn.lunamux.windowRoutes] when it
     * publishes the `/window` `UiSettings` envelope. The synthesized keys are
     * MERGED on top of the real (v2) settings object so new apps keep the v2
     * keys and old apps additionally see the legacy keys.
     *
     * The reconstruction reads the two v2 blobs out of [stored] (the merged
     * settings object): [PersistKeys.THEME_V2_SELECTION] (per-app slot picks +
     * appearance) and [PersistKeys.THEME_V2_CUSTOM] (shared custom themes). It
     * builds a [ThemeSnapshotV2] via [ThemeSnapshotV2.fromStrings], resolves the
     * dark and light slot themes (looked up across built-ins ∪ custom, falling
     * back to the slot defaults), and hand-writes the legacy-shaped object.
     *
     * **This is the ONLY place the legacy wire shape is hand-written; it is a
     * frozen compatibility shim and must not be "improved" to track v2.**
     *
     * The emitted legacy `customSchemes` entry per slot carries the theme's
     * foreground (`text`) and background (`bg`) `#rrggbb` tokens verbatim and an
     * `accent.primary.*` override expressed as an ARGB [Long]
     * ([hexToArgb] of the theme's `accent`). When both slots resolve to the same
     * theme name a single `customSchemes` entry is emitted.
     *
     * @param stored the merged (v2-shaped) settings object to read v2 blobs from
     * @return a JSON object carrying the two legacy keys
     *   (`darkness.themeSnapshot`, `darkness.uiSettings`), each a JSON **string**
     * @see PersistKeys.THEME_SNAPSHOT
     * @see PersistKeys.UI_SETTINGS
     */
    private fun synthesizeLegacyPayload(stored: JsonObject): JsonObject {
        // Read the two v2 blobs as raw JSON strings (or null when absent).
        fun rawString(key: String): String? =
            (stored[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val snapshot = ThemeSnapshotV2.fromStrings(
            selectionJson = rawString(PersistKeys.THEME_V2_SELECTION),
            customThemesJson = rawString(PersistKeys.THEME_V2_CUSTOM),
        )

        val catalog = allThemes(snapshot.customThemes)
        fun resolveSlot(name: String, default: String): Theme =
            catalog.firstOrNull { it.name == name }
                ?: builtinTheme(default)
                ?: catalog.first { it.name == default }

        val darkTheme = resolveSlot(snapshot.darkThemeName, DEFAULT_DARK_THEME)
        val lightTheme = resolveSlot(snapshot.lightThemeName, DEFAULT_LIGHT_THEME)
        val darkName = darkTheme.name
        val lightName = lightTheme.name

        // A single legacy "scheme" derived from a v2 theme: the old shape carried
        // separate dark/light fg+bg pairs and an ARGB accent override.
        fun legacyScheme(theme: Theme): JsonObject = buildJsonObject {
            put("darkFg", JsonPrimitive(theme.text))
            put("lightFg", JsonPrimitive(theme.text))
            put("darkBg", JsonPrimitive(theme.bg))
            put("lightBg", JsonPrimitive(theme.bg))
            put("overrides", buildJsonObject {
                put("accent.primary.dark", JsonPrimitive(hexToArgb(theme.accent)))
                put("accent.primary.light", JsonPrimitive(hexToArgb(theme.accent)))
            })
        }

        val customSchemes = buildJsonObject {
            put(lightName, legacyScheme(lightTheme))
            // When both slots resolve to the same theme, emit a single entry.
            if (darkName != lightName) put(darkName, legacyScheme(darkTheme))
        }

        val themeSnapshotJson = buildJsonObject {
            put("theme.light", JsonPrimitive(lightName))
            put("theme.dark", JsonPrimitive(darkName))
            put("customSchemes", customSchemes)
        }.toString()

        val uiSettingsJson = buildJsonObject {
            put("appearance", JsonPrimitive(legacyAppearance(snapshot.appearance)))
        }.toString()

        return buildJsonObject {
            put(PersistKeys.THEME_SNAPSHOT, JsonPrimitive(themeSnapshotJson))
            put(PersistKeys.UI_SETTINGS, JsonPrimitive(uiSettingsJson))
        }
    }

    /** Maps the v2 [Appearance] enum to the legacy `appearance` string token. */
    private fun legacyAppearance(appearance: Appearance): String = when (appearance) {
        Appearance.Auto -> "Auto"
        Appearance.Dark -> "Dark"
        Appearance.Light -> "Light"
    }

    /**
     * Return the current UI settings MERGED with the synthesized legacy compat
     * keys, so the response carries BOTH the v2 keys (new apps) and the
     * synthesized legacy keys (pre-revamp apps).
     *
     * Called by [se.soderbjorn.lunamux.uiSettingsRoutes]'s `GET
     * /api/ui-settings` and by [se.soderbjorn.lunamux.windowRoutes] when it
     * builds the `/window` `UiSettings` envelope.
     *
     * @return the v2 settings object plus the two synthesized legacy keys
     * @see synthesizeLegacyPayload
     */
    fun getUiSettingsWithLegacy(): JsonObject = withLegacyCompat(_uiSettings.value)

    // ── Per-world theme ↔ legacy global selection bridge (B6) ─────────

    /**
     * Parse a `THEME_V2_SELECTION` element (a JSON object, or a
     * JSON-string-encoded object) into a per-world theme **pair**. Appearance
     * is dropped — it is global, not per-world.
     *
     * @param el the selection element (object or string), or null.
     * @return the dark+light pair, or null when unparseable / absent.
     */
    fun themePairFromSelection(el: JsonElement?): WorldThemeSelection? {
        if (el == null) return null
        val obj: JsonObject? = when (el) {
            is JsonObject -> el
            is JsonPrimitive -> if (el.isString) {
                runCatching { Json.parseToJsonElement(el.content) as? JsonObject }.getOrNull()
            } else null
            else -> null
        }
        obj ?: return null
        val snap = ThemeSnapshotV2.fromParts(selection = obj, customArray = null)
        return WorldThemeSelection(snap.darkThemeName, snap.lightThemeName)
    }

    /**
     * The current global theme pair from `THEME_V2_SELECTION`, used to seed a
     * newly-created world's pair. Never null in practice (defaults apply).
     *
     * @return the current dark+light pair.
     */
    fun currentThemePair(): WorldThemeSelection? {
        val raw = (_uiSettings.value[PersistKeys.THEME_V2_SELECTION] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val snap = ThemeSnapshotV2.fromStrings(selectionJson = raw, customThemesJson = null)
        return WorldThemeSelection(snap.darkThemeName, snap.lightThemeName)
    }

    /**
     * Mirror the **default** world's theme [pair] into the legacy global
     * `THEME_V2_SELECTION`, preserving the current global appearance (which
     * is global, not per-world). Runs through [mergeUiSettings] so the change
     * persists and pushes a `UiSettings` envelope to old clients.
     *
     * @param pair the default world's new dark+light pair.
     */
    fun mirrorDefaultWorldThemePair(pair: WorldThemeSelection) {
        val current = (_uiSettings.value[PersistKeys.THEME_V2_SELECTION] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val existing = ThemeSnapshotV2.fromStrings(selectionJson = current, customThemesJson = null)
        val updated = existing.copy(
            darkThemeName = pair.darkThemeName,
            lightThemeName = pair.lightThemeName,
        )
        mergeUiSettings(
            buildJsonObject {
                put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(updated.selectionJson()))
            },
        )
    }

    /**
     * Merge the synthesized legacy compat keys onto [stored]. v2 keys are kept;
     * the legacy keys are added on top.
     *
     * @param stored a v2-shaped settings object
     * @return [stored] plus the two synthesized legacy keys
     * @see synthesizeLegacyPayload
     */
    fun withLegacyCompat(stored: JsonObject): JsonObject =
        JsonObject(stored + synthesizeLegacyPayload(stored))

    /**
     * Replace the in-memory UI settings snapshot with [snapshot] without
     * touching disk. Called by the file-watch subscription installed in
     * [se.soderbjorn.lunamux.installSharedThemesWatcher] when an
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
     * from another Darkness app and Lunamux only race within their
     * respective scopes.
     *
     * No-op guard: when the merge produces a value identical to the current
     * in-memory snapshot (every incoming key already stored with the same
     * value), the call returns immediately — no disk writes, no file-watcher
     * churn, no `uiSettings` flow update. Misbehaving or merely chatty
     * clients re-POSTing unchanged blobs (the `LAYOUT_STATE` write storm
     * behind issue #93) then cost one JSON merge instead of two file
     * rewrites plus a broadcast to every connected client.
     *
     * @param incoming JSON object with the keys to merge (new keys are added,
     *                 existing keys are overwritten)
     * @return the merged [JsonObject] after persistence
     */
    fun mergeUiSettings(incoming: JsonObject): JsonObject {
        val existing = _uiSettings.value
        val merged = JsonObject(existing + incoming)
        // Identical merge — nothing to persist or publish (issue #93).
        if (merged == existing) return existing
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
        // contributions. The renderer consumes the v2 theme keys directly;
        // no flattening needed.
        _uiSettings.value = JsonObject(sharedFinal + JsonObject(perAppKeys))
        return _uiSettings.value
    }

    /**
     * Returns the absolute path of the toolkit-owned shared themes file
     * the server reads from and writes to. Exposed so [se.soderbjorn.lunamux.ServerInitializer]
     * can install a `watchUiSettings` subscription on it.
     */
    fun sharedThemesPath(): String = sharedThemesPath

    /**
     * Returns the absolute path of Lunamux's per-app UI-settings file.
     * Exposed so [se.soderbjorn.lunamux.ServerInitializer] can install
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

    /**
     * Whether the MCP endpoint (`/mcp`) is enabled — the global MCP kill
     * switch surfaced in the settings dialog's MCP section. Defaults to
     * `false`: nothing the server exposes should be on until the user asks
     * for it, even a surface that is localhost-only and needs a minted
     * token. Symmetric with [isAllowRemoteConnections] — only the literal
     * `"true"` enables it, so an unset key reads as off.
     * Checked per-request by `McpRoutes`, so flipping the switch takes
     * effect immediately without a restart.
     *
     * Note this reads as off for installs that predate the default flip and
     * never touched the switch, including ones with minted MCP tokens: their
     * key is unset, which is indistinguishable from a fresh install. Such
     * users re-enable it once in settings.
     *
     * @return true only once the user has switched MCP on.
     */
    fun isMcpEnabled(): Boolean =
        getString(MCP_ENABLED_KEY)?.equals("true", ignoreCase = true) == true

    /**
     * Toggle the global MCP kill switch.
     *
     * @param value true to enable the `/mcp` endpoint, false to disable it
     *   for every token until re-enabled.
     * @see isMcpEnabled
     */
    fun setMcpEnabled(value: Boolean) {
        putString(MCP_ENABLED_KEY, value.toString())
    }

    /**
     * Per-database id nonce embedded in every newly minted tab / pane /
     * session id (e.g. `t7-x4k9`), minted lazily on first use and then
     * stable for the lifetime of this database.
     *
     * Why it exists: the toolkit's per-tab layout state lives in a
     * machine-global settings file keyed by tab/pane id, but plain
     * sequential ids (`t1`, `n1`, …) are only unique per database — a dev
     * server, a packaged server, and any re-created database all mint the
     * same ids and their layout-state entries collide (issue #86). The
     * nonce makes ids from different databases distinct, so a stale entry
     * can never masquerade as a brand-new tab's state. Pre-existing
     * old-style ids in the persisted window config keep working unchanged;
     * only ids minted after the first boot with this code carry the nonce.
     *
     * Called by [WindowState.initialize] once at boot, before any id is
     * minted.
     *
     * @return the four-character base-36 nonce for this database.
     * @see WindowState.initialize
     */
    fun instanceIdNonce(): String {
        getString(INSTANCE_ID_NONCE_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        val rng = java.security.SecureRandom()
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val fresh = buildString {
            repeat(4) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
        putString(INSTANCE_ID_NONCE_KEY, fresh)
        return fresh
    }

    companion object {
        internal const val WINDOW_CONFIG_KEY_V1 = "window.config.v1"
        internal const val WINDOW_CONFIG_KEY_V2 = "window.config.v2"
        internal const val WINDOW_CONFIG_KEY_V3 = "window.config.v3"
        /** v4 wraps the flat v3 `tabs` into one default "worlds" world. */
        internal const val WINDOW_CONFIG_KEY_V4 = "window.config.v4"
        /** Current write key. Updated whenever the persisted schema rev bumps. */
        const val WINDOW_CONFIG_KEY = WINDOW_CONFIG_KEY_V4
        /** Migration id for the single default world (see [migrateV3ToV4]). */
        internal const val DEFAULT_WORLD_ID = "w1"
        internal const val DEFAULT_WORLD_NAME = "Home"
        private const val ALLOW_REMOTE_KEY = "network.allow_remote.v1"
        private const val CLAUDE_USAGE_POLL_KEY = "claude.usage_poll.v1"
        /** Key holding the global MCP kill switch (see [isMcpEnabled]). */
        private const val MCP_ENABLED_KEY = "mcp.enabled.v1"
        /** Key holding the per-database id nonce (see [instanceIdNonce]). */
        private const val INSTANCE_ID_NONCE_KEY = "instance.id_nonce.v1"
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

