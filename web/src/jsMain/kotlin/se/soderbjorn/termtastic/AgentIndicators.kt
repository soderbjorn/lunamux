/**
 * Agent-activity indicators for the web client:
 *
 *  - [updateAgentBadges] renders the per-window agent badge (the
 *    `LeafNode.agentNote` field set by the MCP `annotate_window` tool or
 *    the automatic agent-touch marker) as a small chip floating over the
 *    pane body. Called from [renderConfig] on every config push — badges
 *    can change without a structural layout change, so this runs before
 *    the structural fast-path bail.
 *
 *  - [showAgentToast] renders the MCP `notify` tool's transient
 *    [WindowEnvelope.AgentNotify] message as an auto-dismissing toast.
 *    Called from the envelope dispatcher in `WindowConnection.kt`.
 *
 * Styling lives in `styles.css` (`.agent-badge`, `.agent-toast`).
 *
 * @see renderConfig
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Sync every pane's agent badge with the latest server config.
 *
 * For each pane whose leaf carries a non-null `agentNote`, ensures a
 * `.agent-badge` chip exists inside the pane's body cell (the
 * `[data-pane]` element built by [mountPaneContent]) and updates its
 * text; panes without a note get any leftover chip removed. Robust to
 * panes whose DOM isn't mounted yet (inactive tabs) — they're picked up
 * on a later push once mounted.
 *
 * @param config the dynamic (JSON-parsed) server [WindowConfig].
 */
fun updateAgentBadges(config: dynamic) {
    val tabs = config.tabs as? Array<dynamic> ?: return
    for (tab in tabs) {
        val panes = tab.panes as? Array<dynamic> ?: continue
        for (p in panes) {
            val paneId = p.leaf?.id as? String ?: continue
            val note = p.leaf?.agentNote as? String
            val cell = document.querySelector("[data-pane='$paneId']") as? HTMLElement ?: continue
            val existing = cell.querySelector(":scope > .agent-badge") as? HTMLElement
            if (note.isNullOrEmpty()) {
                existing?.remove()
            } else {
                val badge = existing ?: (document.createElement("div") as HTMLElement).also {
                    it.className = "agent-badge"
                    it.setAttribute("title", "An MCP agent has been acting on this window")
                    cell.appendChild(it)
                }
                val label = "🤖 $note" // 🤖 prefix
                if (badge.textContent != label) badge.textContent = label
            }
        }
    }
}

/**
 * Show a transient agent notification as a toast in the bottom-right
 * corner. Stacks naturally if several arrive; each dismisses itself
 * after a few seconds (errors linger longer) or on click.
 *
 * @param message the notification text.
 * @param level `"info"`, `"warn"`, or `"error"` — picks the accent style.
 */
fun showAgentToast(message: String, level: String) {
    val host = (document.querySelector(".agent-toast-host") as? HTMLElement)
        ?: (document.createElement("div") as HTMLElement).also {
            it.className = "agent-toast-host"
            document.body?.appendChild(it)
        }
    val toast = document.createElement("div") as HTMLElement
    toast.className = "agent-toast agent-toast-$level"
    toast.textContent = message
    toast.addEventListener("click", { toast.remove() })
    host.appendChild(toast)
    val lifetimeMs = if (level == "error") 12_000 else 6_000
    window.setTimeout({ toast.remove() }, lifetimeMs)
}
