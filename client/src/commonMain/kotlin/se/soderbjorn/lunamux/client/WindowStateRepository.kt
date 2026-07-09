/**
 * Client-side cache of the authoritative window layout and per-session state
 * pushed by the Lunamux server over `/window`.
 *
 * [WindowStateRepository] is owned by [LunamuxClient] and survives
 * [WindowSocket] reconnects and UI lifecycle events (e.g. Android Compose
 * navigation). Subscribers get the last-known snapshot immediately via
 * [StateFlow] replay.
 *
 * @see WindowSocket
 * @see LunamuxClient.windowState
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.lunamux.WindowConfig

/**
 * The toolkit-owned geometry of a single pane, parsed from the `LAYOUT_STATE`
 * blob's `geometryByTab[tabId][paneId]` entry. This — not [se.soderbjorn.lunamux.Pane]'s
 * fields — is the authoritative position/size that the web/Electron client
 * renders and persists; mobile reads it to lay out the overview faithfully.
 *
 * All of [xPct]/[yPct]/[widthPct]/[heightPct] are fractions (0.0–1.0) of the
 * tab content area, matching the web semantics.
 *
 * @property xPct        top-left x as a fraction of the content width.
 * @property yPct        top-left y as a fraction of the content height.
 * @property widthPct    width as a fraction of the content width.
 * @property heightPct   height as a fraction of the content height.
 * @property zIndex      stacking order (higher renders on top).
 * @property isMaximized whether the pane currently fills the tab.
 * @property isMinimized whether the pane is docked off the canvas.
 */
data class ToolkitPaneGeometry(
    val xPct: Double,
    val yPct: Double,
    val widthPct: Double,
    val heightPct: Double,
    val zIndex: Int,
    val isMaximized: Boolean,
    val isMinimized: Boolean,
)

/**
 * Process-lifetime cache of the latest [WindowConfig] and per-session state
 * map pushed by the server over `/window`. Held by [LunamuxClient] so it
 * survives [WindowSocket] reconnects and, on Android, any Compose navigation
 * that tears down and rebuilds the list/terminal screens.
 *
 * Subscribers get the last-known snapshot immediately (StateFlow replay), so
 * returning to the tree view never shows a "Connecting…" flash or empty dots
 * as long as the server has pushed at least one config envelope this session.
 */
class WindowStateRepository {
    /** The latest window layout, or `null` before the first server push. */
    private val _config = MutableStateFlow<WindowConfig?>(null)
    /** Observable latest [WindowConfig]. Emits `null` until the server sends
     *  the first `Config` envelope. */
    val config: StateFlow<WindowConfig?> = _config.asStateFlow()

    /** Per-session AI-assistant / process state strings keyed by session ID. */
    private val _states = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** Observable map of session ID to human-readable state label. */
    val states: StateFlow<Map<String, String?>> = _states.asStateFlow()

    /**
     * Ids of panes the (web) client has minimized — parked in its dock and
     * excluded from layout. Mobile doesn't draw the dock, but it dims these
     * panes' rows in the sessions list so the state is visible cross-device.
     *
     * Sourced from the toolkit-owned `LAYOUT_STATE` geometry blob, which the
     * server merges into the UI-settings blob and broadcasts over `/window`
     * — so this updates live whenever a pane is minimized/restored anywhere.
     */
    private val _minimizedPaneIds = MutableStateFlow<Set<String>>(emptySet())
    /** Observable set of currently-minimized pane ids (see backing field). */
    val minimizedPaneIds: StateFlow<Set<String>> = _minimizedPaneIds.asStateFlow()

    /**
     * Authoritative per-tab pane geometry, keyed `tabId -> (paneId -> geometry)`,
     * parsed from the toolkit-owned `LAYOUT_STATE` blob the server broadcasts
     * over `/window`. This is the position/size the web/Electron client actually
     * renders — [se.soderbjorn.lunamux.Pane]'s own `x/y/width/height` fields
     * are placeholder defaults and must not be used for layout. The overview
     * reads this to mirror the desktop arrangement.
     *
     * Empty until the first `UiSettings` envelope arrives (or when the server
     * has no layout state yet); callers should fall back gracefully.
     */
    private val _geometryByTab = MutableStateFlow<Map<String, Map<String, ToolkitPaneGeometry>>>(emptyMap())
    /** Observable per-tab pane geometry (see backing field). */
    val geometryByTab: StateFlow<Map<String, Map<String, ToolkitPaneGeometry>>> = _geometryByTab.asStateFlow()

    /**
     * The raw, unparsed `LAYOUT_STATE` element from the last UI-settings push,
     * or `null` before the first push (or when the server has no layout state).
     *
     * [geometryByTab] is the parsed, render-ready projection; this is the
     * *whole* blob (including `presetByTab`, `paneOrderByTab`, and any toolkit
     * fields this client doesn't model) so that a mobile geometry edit can
     * read-modify-write it without dropping anything the web/Electron client
     * relies on. Consumed by
     * [se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel] via
     * [WindowLayoutState.parse].
     */
    private val _rawLayoutState = MutableStateFlow<JsonElement?>(null)
    /** Observable raw `LAYOUT_STATE` element (see backing field). */
    val rawLayoutState: StateFlow<JsonElement?> = _rawLayoutState.asStateFlow()

    /** Whether the server has sent a `PendingApproval` envelope (device not
     *  yet approved). */
    private val _pendingApproval = MutableStateFlow(false)
    /** `true` when the server is waiting for the user to approve this device. */
    val pendingApproval: StateFlow<Boolean> = _pendingApproval.asStateFlow()

    /**
     * Replace the cached [WindowConfig] with [config] and clear the
     * pending-approval flag (receiving a config implies approval succeeded).
     *
     * Called by [WindowSocket] when a `Config` envelope arrives.
     *
     * @param config the new authoritative window layout.
     */
    fun updateConfig(config: WindowConfig) {
        _pendingApproval.value = false
        _config.value = config
    }

    /**
     * Replace the cached per-session state map.
     *
     * Called by [WindowSocket] when a `State` envelope arrives.
     *
     * @param states map of session ID to state label (e.g. `"working"`).
     */
    fun updateStates(states: Map<String, String?>) {
        _states.value = states
    }

    /**
     * Flag that the server requires device approval before it will send a
     * config. The UI should show an approval-pending screen.
     *
     * Called by [WindowSocket] when a `PendingApproval` envelope arrives.
     */
    fun setPendingApproval() {
        _pendingApproval.value = true
    }

    /**
     * Refresh [minimizedPaneIds] from a server-pushed UI-settings blob.
     *
     * Called by [WindowSocket] when a `UiSettings` envelope arrives (on
     * connect and on every settings write, including web minimize/restore,
     * which merge `LAYOUT_STATE` through the same broadcast path).
     *
     * @param settings the complete UI-settings JSON object.
     */
    fun updateUiSettings(settings: JsonObject) {
        _rawLayoutState.value = settings[PersistKeys.LAYOUT_STATE]
        val geometry = parseGeometryByTab(settings)
        _geometryByTab.value = geometry
        _minimizedPaneIds.value = geometry.values
            .flatMap { panes -> panes.filterValues { it.isMinimized }.keys }
            .toSet()
    }

    /**
     * Parses the toolkit's `LAYOUT_STATE` blob into per-tab pane geometry by
     * reading each `geometryByTab[tab][pane]` entry's
     * `xPct/yPct/widthPct/heightPct/zIndex/isMaximized/isMinimized` fields.
     *
     * The `LAYOUT_STATE` value normally arrives as a JSON-encoded *string*
     * (the toolkit persister writes a stringified blob into the flat-KV);
     * some seed paths inline it as an object. Both shapes are handled, and
     * any malformed/missing data degrades to an empty map.
     *
     * @param settings the UI-settings JSON object.
     * @return `tabId -> (paneId -> geometry)`, empty when absent/malformed.
     */
    private fun parseGeometryByTab(settings: JsonObject): Map<String, Map<String, ToolkitPaneGeometry>> {
        val raw = settings[PersistKeys.LAYOUT_STATE] ?: return emptyMap()
        val layout: JsonObject = when {
            raw is JsonObject -> raw
            raw is JsonPrimitive && raw.isString ->
                runCatching { layoutJson.parseToJsonElement(raw.content).jsonObject }.getOrNull()
                    ?: return emptyMap()
            else -> return emptyMap()
        }
        val geometryByTab = (layout["geometryByTab"] as? JsonObject) ?: return emptyMap()
        val out = mutableMapOf<String, Map<String, ToolkitPaneGeometry>>()
        for ((tabId, panesEl) in geometryByTab) {
            val panes = panesEl as? JsonObject ?: continue
            val tabGeom = mutableMapOf<String, ToolkitPaneGeometry>()
            for ((paneId, geomEl) in panes) {
                val geom = geomEl as? JsonObject ?: continue
                tabGeom[paneId] = ToolkitPaneGeometry(
                    xPct = geom.num("xPct"),
                    yPct = geom.num("yPct"),
                    widthPct = geom.num("widthPct", default = 1.0),
                    heightPct = geom.num("heightPct", default = 1.0),
                    zIndex = (geom["zIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                    isMaximized = (geom["isMaximized"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    isMinimized = (geom["isMinimized"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }
            if (tabGeom.isNotEmpty()) out[tabId] = tabGeom
        }
        return out
    }

    /** Read a numeric field from a geometry object, defaulting when absent. */
    private fun JsonObject.num(key: String, default: Double = 0.0): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: default

    private companion object {
        /** Lenient parser for the embedded `LAYOUT_STATE` blob. */
        private val layoutJson = Json { ignoreUnknownKeys = true }
    }
}
