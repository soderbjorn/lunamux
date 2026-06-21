/**
 * App logo widget for the Termtastic web frontend.
 *
 * Renders the "Termtastic" wordmark and a small status dot pinned to the top
 * of the left (sessions) sidebar — see `TermtasticToolkitBootstrap.buildSidebarLogo`,
 * which wires it into the toolkit's `sidebarHeader` slot. The dot shows
 * three states derived from the aggregate of all per-session states:
 *
 *   - red   — at least one session is "waiting" (e.g. an agent is asking for
 *             input/approval). Waiting dominates because it needs action.
 *             Pulses fastest since it is action-required.
 *   - blue  — at least one session is "working" (agent is actively running)
 *             and none are waiting. Pulses to read as actively in motion.
 *   - green — no session is working or waiting (idle). Breathes at the slowest,
 *             calmest cadence in a fixed phosphor green (the landing page's
 *             brand-dot colour). All three colours are fixed so the states stay
 *             distinguishable in any appearance mode.
 *
 * The logo brings back an older design element (see issue #14): a "Termtastic"
 * wordmark alongside a coloured dot. In the previous incarnation the dot was
 * to the left of the wordmark and coloured by socket-connection status; this
 * version moves the dot to the right and ties its colour to work state, which
 * is more informative for day-to-day use.
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
 * change), so the dot is always in sync with the spinners/warning icons in
 * the sidebar and tab bar.
 *
 * Aggregation rule:
 *   1. If any session value is `"waiting"` → mark as waiting (red pulse).
 *   2. Else if any session value is `"working"` → mark as working (blue pulse).
 *   3. Else mark as idle (green breathe at the calmest cadence).
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
        // Idle → no modifier class; the base .app-logo-dot rule paints it
        // a fixed phosphor green breathing at the calmest cadence.
    }
}
