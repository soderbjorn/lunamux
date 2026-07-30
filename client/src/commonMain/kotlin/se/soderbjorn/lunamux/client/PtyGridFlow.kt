/**
 * A tiny mutable holder for "the grid this client would like", shaped so a
 * non-Kotlin caller can build one.
 *
 * This file contains [PtyGridFlow], which exists purely to hand
 * [LunamuxClient.openPtySocket] its `initialGrid` from Swift.
 *
 * Why it exists. The connect-URL grid is what lets the server author a client's
 * *attach* redraw at that client's own width instead of at the PTY's current one
 * — the difference between a pane that opens correct and a pane that opens at
 * someone else's width and reflows a moment later. Android hands
 * `openPtySocket` a `MutableStateFlow` built inline, but `kotlinx.coroutines` is
 * not an exported dependency of the iOS framework, so Swift cannot construct a
 * `MutableStateFlow` at all. This wrapper is the smallest thing that closes that
 * gap: Swift builds one, keeps it, and calls [set] whenever it re-measures.
 *
 * @see LunamuxClient.openPtySocket the sole consumer of [flow]
 * @see ptyConnectQuery which renders the value into the `/pty` URL
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A settable `StateFlow<Pair<Int, Int>?>` of a client's desired terminal grid.
 *
 * Deliberately *not* the server's grid: this is what the client would render at
 * with its own font and viewport, which is the number the connect URL wants.
 * Feeding the server's grid back here would make the attach width circular.
 */
class PtyGridFlow {

    private val state = MutableStateFlow<Pair<Int, Int>?>(null)

    /** The grid to put on the connect URL, or null until the client has measured. */
    val flow: StateFlow<Pair<Int, Int>?> = state.asStateFlow()

    /**
     * Publish a newly measured grid.
     *
     * Called by the client's layout pass on every re-measure. Degenerate dims are
     * ignored rather than published, because a zero would be rendered into the URL
     * and read by the server as a real request.
     *
     * @param cols the client's natural column count.
     * @param rows the client's natural row count.
     */
    fun set(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        state.value = cols to rows
    }
}
