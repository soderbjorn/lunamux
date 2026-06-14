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
}
