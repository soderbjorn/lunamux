/**
 * Web-side registry of live xterm.js terminal instances and their
 * connection-state map, plus the helper that re-fits every visible
 * terminal to its container.
 *
 * Lives next to [WebState] in the same package so all rendering code can
 * keep reading the unqualified `terminals`, `connectionState`, and
 * `windowSocketConnected` symbols without any call-site changes.
 *
 * @see WebState
 * @see fitPreservingScroll
 */
package se.soderbjorn.lunamux

import org.w3c.dom.HTMLElement

/** Map of paneId → live xterm.js terminal entry, refilled by `renderConfig`. */
internal val terminals = HashMap<String, TerminalEntry>()

/**
 * Whether [term] currently renders another client's grid as a read-only mirror
 * ([TerminalEntry.passive]).
 *
 * Exists because the ambient fit callers reach for a bare [Terminal] rather than the
 * owning [TerminalEntry]; [safeFit] uses this to refuse to reflow a mirror.
 *
 * @param term the xterm instance to classify.
 * @return true when some live entry owning [term] is mirroring.
 * @see safeFit @see applyMirrorPresentation
 */
internal fun isMirroringTerm(term: Terminal): Boolean =
    terminals.values.any { it.passive && it.term === term }

/**
 * Pane id of the terminal the user most recently interacted with, or
 * `null` if the user has never focused a terminal in this session.
 *
 * Sticky on purpose: set whenever a terminal container fires `focusin`,
 * but **not** cleared on `focusout`. The whole point is that involuntary
 * blurs caused by chrome rebuilds (server pushes a new `WindowConfig`
 * after `cd` updates the leaf title; toolkit replaces pane chrome which
 * momentarily detaches the xterm `<textarea>`) shouldn't erase the
 * user's intent. `LunamuxTabSource` reads this field after every
 * `WindowConfig` push to decide whether to schedule a `term.focus()`
 * restore. Cleared explicitly only when the corresponding terminal is
 * removed from the [terminals] map (PTY prune in `renderConfig`).
 *
 * @see se.soderbjorn.lunamux.ensureTerminal
 * @see se.soderbjorn.lunamux.lunamuxTabSource
 */
internal var lastFocusedTerminalId: String? = null

/**
 * Pane id of the `.dt-pane` ancestor under the most recent document-level
 * `pointerdown`, or `null` if the press landed outside any pane (chrome,
 * sidebar, tab strip) or no press has fired yet.
 *
 * Used by the terminal `focusout` safety net in `ensureTerminal` to tell
 * a real toolkit-driven detach (no preceding mousedown, or mousedown
 * inside *this* pane) apart from a user clicking a non-focusable element
 * in a *different* pane (title bar of another pane, file-browser chrome
 * in another pane). In the latter case the blur is voluntary — the user
 * is switching panes — and the safety net must not refocus the previous
 * terminal, because that immediately re-emits `SetFocusedPane` for it
 * and races the toolkit's just-sent `SetFocusedPane` for the clicked
 * pane, leaving the user's selection on the *old* pane.
 *
 * Set by a single capture-phase document `pointerdown` listener
 * installed in `main.kt`'s `start()`. Sticky between presses (cleared on
 * the next press, never on focus events), so a focusout that fires
 * synchronously inside the press handler chain sees the right value.
 */
internal var lastPointerDownPaneId: String? = null

/**
 * True while the user is dragging a pane-resize surface — a `.dt-pane-separator`
 * split bar or a `.dt-pane-corner-resize` handle (the toolkit's stable resize
 * chrome classes; if the toolkit ever renames them, detection degrades
 * gracefully to [sendResize]'s trailing debounce).
 *
 * While set, [sendResize] suppresses automatic PTY size votes: local grid
 * refits continue for visual feedback, but the transient intermediate widths
 * of the drag never reach the PTY. Programs hard-wrap their output at
 * whatever COLUMNS they see, and xterm.js cannot reflow hard-wrapped lines,
 * so every mid-drag width that reached the PTY used to leave a permanent
 * half-width scar in the scrollback — worst during split-bar drags, which
 * feed redistributed (briefly very narrow) widths to *both* neighbour panes.
 *
 * Set by the capture-phase document `pointerdown` listener in `main.kt`;
 * cleared (with a follow-up size flush) by the matching `pointerup` /
 * `pointercancel` / window-`blur` listeners. The committed final geometry is
 * separately asserted by the toolkit's `onGeometryChanged` → `forceReassert`,
 * which bypasses the gate by design.
 */
internal var resizeGestureActive = false

/** Map of paneId → string PTY connection state ("connected", "disconnected", …). */
internal val connectionState = HashMap<String, String>()

/** Whether the `/window` WebSocket is currently connected. */
internal var windowSocketConnected = false

/**
 * Re-measures every visible terminal and votes the grid it would like.
 *
 * Iterates all registered terminals and calls [sendResize] on those whose DOM element has
 * a non-null offsetParent (i.e. is visible). It used to refit them locally; a client's grid
 * is set only by a server `Size` frame now, so the most a sweep like this may do is ask.
 */
internal fun fitVisible() {
    for (entry in terminals.values) {
        val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
        if (parent != null) {
            try { sendResize(entry) } catch (_: Throwable) {}
        }
    }
}
