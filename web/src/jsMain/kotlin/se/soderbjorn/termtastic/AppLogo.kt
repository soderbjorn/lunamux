/**
 * App logo widget for the Termtastic web frontend.
 *
 * Renders the "Termtastic" wordmark and a small status dot in the lower-right
 * corner of the window as a fixed overlay (painted on top of everything). The
 * dot pulsates in three states derived from the aggregate of all per-session
 * states:
 *
 *   - red   — at least one session is "waiting" (e.g. an agent is asking for
 *             input/approval). Waiting dominates because it needs action.
 *   - blue  — at least one session is "working" (agent is actively running)
 *             and none are waiting.
 *   - green — no session is working or waiting (idle). Green is used instead
 *             of a theme-neutral grey so the three states stay distinguishable
 *             in both dark and light appearance modes.
 *
 * The logo brings back an older design element (see issue #14): a "Termtastic"
 * wordmark alongside a coloured dot. In the previous incarnation the dot was
 * to the left of the wordmark and coloured by socket-connection status; this
 * version moves the dot to the right and ties its colour to work state, which
 * is more informative for day-to-day use.
 *
 * The overlay is **always visible** — it lives directly inside `<body>` via
 * an `#app-logo` element declared in `index.html`, so it paints on top of
 * modals, the sidebar, the tab bar, and the main content area. It uses
 * `pointer-events: none` so it never steals clicks from the UI underneath.
 *
 * @see updateAppLogoState
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
 *   3. Else mark as idle (solid green, no pulse).
 *
 * @param sessionStates the current session-id to state map from the server.
 *                      States other than `"working"` / `"waiting"` (including
 *                      `null`) count as idle.
 * @see updateStateIndicators
 */
internal fun updateAppLogoState(sessionStates: Map<String, String?>) {
    val dot = document.getElementById("app-logo-dot") as? HTMLElement ?: return
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
        // Idle → no modifier class; the base .app-logo-dot rule paints it green.
    }
}
