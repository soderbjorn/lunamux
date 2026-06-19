/**
 * Client-side cache of the authoritative window layout and per-session state
 * pushed by the Termtastic server over `/window`.
 *
 * [WindowStateRepository] is owned by [TermtasticClient] and survives
 * [WindowSocket] reconnects and UI lifecycle events (e.g. Android Compose
 * navigation). Subscribers get the last-known snapshot immediately via
 * [StateFlow] replay.
 *
 * @see WindowSocket
 * @see TermtasticClient.windowState
 */
package se.soderbjorn.termtastic.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.termtastic.WindowConfig

/**
 * Process-lifetime cache of the latest [WindowConfig] and per-session state
 * map pushed by the server over `/window`. Held by [TermtasticClient] so it
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
        _minimizedPaneIds.value = parseMinimizedPaneIds(settings)
    }

    /**
     * Extracts the set of minimized pane ids from a UI-settings blob by
     * reading the toolkit's `LAYOUT_STATE` entry's
     * `geometryByTab[tab][pane].isMinimized` flags.
     *
     * The `LAYOUT_STATE` value normally arrives as a JSON-encoded *string*
     * (the toolkit persister writes a stringified blob into the flat-KV);
     * some seed paths inline it as an object. Both shapes are handled, and
     * any malformed/missing data degrades to an empty set.
     *
     * @param settings the UI-settings JSON object.
     * @return ids of every pane whose `isMinimized` flag is set.
     */
    private fun parseMinimizedPaneIds(settings: JsonObject): Set<String> {
        val raw = settings[PersistKeys.LAYOUT_STATE] ?: return emptySet()
        val layout: JsonObject = when {
            raw is JsonObject -> raw
            raw is JsonPrimitive && raw.isString ->
                runCatching { layoutJson.parseToJsonElement(raw.content).jsonObject }.getOrNull()
                    ?: return emptySet()
            else -> return emptySet()
        }
        val geometryByTab = (layout["geometryByTab"] as? JsonObject) ?: return emptySet()
        val minimized = mutableSetOf<String>()
        for ((_, panesEl) in geometryByTab) {
            val panes = panesEl as? JsonObject ?: continue
            for ((paneId, geomEl) in panes) {
                val geom = geomEl as? JsonObject ?: continue
                val isMin = (geom["isMinimized"] as? JsonPrimitive)?.booleanOrNull ?: false
                if (isMin) minimized.add(paneId)
            }
        }
        return minimized
    }

    private companion object {
        /** Lenient parser for the embedded `LAYOUT_STATE` blob. */
        private val layoutJson = Json { ignoreUnknownKeys = true }
    }
}
