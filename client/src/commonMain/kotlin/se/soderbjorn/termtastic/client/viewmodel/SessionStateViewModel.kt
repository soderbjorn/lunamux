/**
 * Session-state slice of the application backing model. Combines the
 * server-pushed window config, per-session state labels, and pending-
 * approval flag into a single triple stream that [AppBackingViewModel]
 * folds into its own [State].
 *
 * Also subscribes to the WindowSocket envelope stream and surfaces the
 * Claude AI usage update, plus dispatches UiSettings envelopes back
 * through a caller-supplied callback so the coordinator decides whether
 * to apply them (suppressing local-echo races).
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository

/**
 * Aggregates the server-pushed window-state streams and dispatches
 * envelope updates to caller-supplied handlers.
 *
 * @param windowSocket the live `/window` WebSocket whose envelope stream
 *   carries usage updates and UiSettings echoes.
 * @param windowState  the shared [WindowStateRepository] cache that
 *   exposes window config / per-session states / approval flag.
 */
internal class SessionStateViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository,
) {
    /**
     * Snapshot of the dynamic-config triple emitted by [windowState].
     * Folded into [AppBackingViewModel.State] by the coordinator.
     */
    data class DynamicState(
        val config: WindowConfig?,
        val sessionStates: Map<String, String?>,
        val pendingApproval: Boolean,
    )

    /**
     * Run the long-running collection loop. Returns only when the
     * enclosing scope is cancelled.
     *
     * @param onDynamic       called for every dynamic state emission.
     * @param onClaudeUsage   called for each [WindowEnvelope.ClaudeUsage].
     * @param onUiSettings    called for each [WindowEnvelope.UiSettings].
     */
    suspend fun run(
        onDynamic: (DynamicState) -> Unit,
        onClaudeUsage: (ClaudeUsageData?) -> Unit,
        onUiSettings: (JsonObject) -> Unit,
    ) {
        coroutineScope {
            launch {
                combine(
                    windowState.config,
                    windowState.states,
                    windowState.pendingApproval,
                ) { cfg, states, pending ->
                    DynamicState(cfg, states, pending)
                }.collect { onDynamic(it) }
            }
            launch {
                windowSocket.envelopes.collect { envelope ->
                    when (envelope) {
                        is WindowEnvelope.ClaudeUsage -> onClaudeUsage(envelope.usage)
                        is WindowEnvelope.UiSettings -> onUiSettings(envelope.settings)
                        else -> Unit
                    }
                }
            }
        }
    }
}
