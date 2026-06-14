/**
 * Backing ViewModel for a single terminal pane, exposing metadata used by
 * pane headers, tab status dots, and sidebar entries.
 *
 * This ViewModel intentionally does **not** wrap [PtySocket.output] -- platform
 * code subscribes to the raw byte stream directly to feed into xterm.js or a
 * native terminal renderer. Instead, it tracks the current PTY size, session
 * state, title, and per-pane font size.
 *
 * @see se.soderbjorn.termtastic.client.PtySocket
 * @see AppBackingViewModel
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.client.PtySocket
import se.soderbjorn.termtastic.client.WindowSocket

/**
 * Lightweight ViewModel for terminal pane metadata. Does NOT wrap
 * [PtySocket.output] — platform code subscribes to that directly to
 * feed bytes into xterm.js / native terminal views. This VM exists so
 * pane headers, tab status dots, and sidebar entries can reactively
 * observe per-terminal state.
 */
/**
 * ViewModel for a terminal pane identified by [paneId].
 *
 * @param paneId       the unique ID of the terminal leaf node.
 * @param sessionId    the PTY session identifier.
 * @param ptySocket    the live PTY WebSocket for resize commands.
 * @param windowSocket the live `/window` WebSocket for observing config.
 */
class TerminalBackingViewModel(
    private val paneId: String,
    private val sessionId: String,
    private val ptySocket: PtySocket,
    private val windowSocket: WindowSocket,
) {
    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the terminal pane UI state.
     *
     * @property ptySize      current (cols, rows) of the PTY, or `null` before
     *   the first server-reported size.
     * @property sessionState the human-readable process state (e.g. `"working"`).
     * @property title        the pane title shown in the tab bar.
     * @property fontSize     per-pane font size override, or `null` for default.
     */
    data class State(
        val ptySize: Pair<Int, Int>? = null,
        val sessionState: String? = null,
        val title: String = "",
        val fontSize: Int? = null,
    )

    /**
     * Start collecting PTY size, session state, and config updates. Long-running
     * -- cancels when the enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                ptySocket.ptySize.collect { size ->
                    emit(_stateFlow.value.copy(ptySize = size))
                }
            }
            launch {
                windowSocket.states.collect { states ->
                    emit(_stateFlow.value.copy(sessionState = states[sessionId]))
                }
            }
            launch {
                windowSocket.config
                    .filterNotNull()
                    .collect { config ->
                        val leaf = findLeafById(config, paneId) ?: return@collect
                        val tc = leaf.content as? TerminalContent
                        emit(_stateFlow.value.copy(
                            title = leaf.title,
                            fontSize = tc?.fontSize,
                        ))
                    }
            }
        }
    }

    // ── Actions ─────────────────────────────────────────────────────

    /**
     * Forward a resize event to the PTY.
     *
     * @param cols new column count.
     * @param rows new row count.
     */
    suspend fun resize(cols: Int, rows: Int) {
        ptySocket.resize(cols, rows)
    }

    /**
     * Force-resize the PTY, evicting other clients' size entries.
     *
     * @param cols new column count.
     * @param rows new row count.
     * @see PtySocket.forceResize
     */
    suspend fun forceResize(cols: Int, rows: Int) {
        ptySocket.forceResize(cols, rows)
    }

    /**
     * Update the per-pane font size and persist it to the server.
     *
     * @param size the new font size in points.
     */
    suspend fun setFontSize(size: Int) {
        emit(_stateFlow.value.copy(fontSize = size))
        windowSocket.send(WindowCommand.SetTerminalFontSize(paneId = paneId, size = size))
    }

    private fun emit(state: State) {
        _stateFlow.value = state
    }
}
