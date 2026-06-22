/**
 * App logo widget for the Termtastic web frontend.
 *
 * Renders the "Termtastic" wordmark and a small status dot pinned to the top
 * of the left (sessions) sidebar — see `TermtasticToolkitBootstrap.buildSidebarLogo`,
 * which wires it into the toolkit's `sidebarHeader` slot. The dot aggregates
 * all per-session states and (issue #38) is painted in the theme's foreground
 * colour — matching the per-row `.tt-status-dot` — instead of a fixed
 * green/red, so it meshes with any theme:
 *
 *   - waiting — at least one session is "waiting" (e.g. an agent is asking for
 *               input/approval). Waiting dominates because it needs action; the
 *               dot is swapped for a pulsing warning/exclamation triangle.
 *   - working — at least one session is "working" (agent is actively running)
 *               and none are waiting. The dot breathes (pulses) in place.
 *   - idle    — no session is working or waiting. A solid dot, no pulse.
 *
 * The shape (dot vs. triangle), not the colour, distinguishes the states now
 * that they all share the theme foreground colour — see `.app-logo-dot` in
 * styles.css.
 *
 * The logo brings back an older design element (see issue #14): a "Termtastic"
 * wordmark alongside a dot. In the previous incarnation the dot was to the left
 * of the wordmark and coloured by socket-connection status; this version moves
 * the dot to the right and ties it to work state, which is more informative for
 * day-to-day use.
 *
 * The logo is built in Kotlin (`buildSidebarLogo`) and slotted into the
 * sidebar header, so it scrolls/collapses with the sidebar. The dot element
 * keeps its `#app-logo-dot` id so [updateStateIndicators] can find and repaint
 * it on each server state push.
 *
 * @see updateAppLogoState
 * @see applyLogoDotState
 * @see updateStateIndicators
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Recomputes and applies the logo dot state based on the current per-session
 * state map.
 *
 * Called from [updateStateIndicators] whenever the server pushes a new state
 * envelope (or when [renderConfig] replays the current state after a config
 * change), so the dot is always in sync with the per-row status indicators in
 * the sidebar and tab bar.
 *
 * Aggregation rule:
 *   1. If any session value is `"waiting"` → mark as waiting (pulsing warning
 *      triangle).
 *   2. Else if any session value is `"working"` → mark as working (breathing
 *      dot).
 *   3. Else mark as idle (solid dot, no pulse).
 *
 * @param sessionStates the current session-id to state map from the server.
 *                      States other than `"working"` / `"waiting"` (including
 *                      `null`) count as idle.
 * @see updateStateIndicators
 */
internal fun updateAppLogoState(sessionStates: Map<String, String?>) {
    val dot = document.getElementById("app-logo-dot") as? HTMLElement ?: return
    applyLogoDotState(dot, sessionStates)
}

/**
 * Applies the aggregated work/wait state to a specific dot element.
 *
 * Split out from [updateAppLogoState] so [buildSidebarLogo] can paint the dot
 * at construction time — before it is attached to the DOM, when a
 * `getElementById` lookup would not yet find it — and so any future caller
 * holding the element directly can repaint without a query.
 *
 * @param dot the `.app-logo-dot` element to repaint.
 * @param sessionStates the current session-id to state map; states other than
 *   `"working"` / `"waiting"` (including `null`) count as idle.
 */
internal fun applyLogoDotState(dot: HTMLElement, sessionStates: Map<String, String?>) {
    var anyWaiting = false
    var anyWorking = false
    for (state in sessionStates.values) {
        when (state) {
            "waiting" -> { anyWaiting = true; break }
            "working" -> anyWorking = true
        }
    }
    // Reset both modifier classes before re-applying so the dot never ends up
    // carrying stale state when the aggregate transitions e.g. working→idle.
    dot.classList.remove("state-working")
    dot.classList.remove("state-waiting")
    when {
        anyWaiting -> dot.classList.add("state-waiting")
        anyWorking -> dot.classList.add("state-working")
        // Idle → no modifier class; the base .app-logo-dot rule paints a
        // solid, non-pulsing dot in the theme foreground colour.
    }
}
