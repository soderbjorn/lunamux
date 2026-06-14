/**
 * Terminal-link picker modal for Termtastic.
 *
 * Opened from the "New pane" hover dropdown when the user picks
 * "Terminal link". Shows a scrolling list of existing terminal
 * sessions (grouped by tab) with a live xterm.js preview for each;
 * clicking a preview dispatches [WindowCommand.AddLinkToTab] to link
 * the empty pane against that session.
 *
 * Historically this file also hosted the four-card "pick a pane type"
 * grid. That popup was replaced by the toolkit hover dropdown wired
 * in [termtasticTabSource]; the file now only contains the
 * link-picker flow.
 *
 * @see openTerminalLinkPicker
 * @see closePaneTypeModal
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.json

/**
 * Closes all live terminal preview WebSockets and disposes their xterm.js instances.
 *
 * Called when the link picker is closed, to clean up resources created
 * for the preview thumbnails.
 */
fun disposeAllPreviews() {
    for (entry in previewEntries) {
        try { (entry.socket as WebSocket).close() } catch (_: Throwable) {}
        try { entry.term.asDynamic().dispose() } catch (_: Throwable) {}
    }
    previewEntries.clear()
}

/**
 * Closes the terminal-link picker, disposing previews and detaching
 * the Escape key handler.
 *
 * @see openTerminalLinkPicker
 */
fun closePaneTypeModal() {
    disposeAllPreviews()
    modalEscHandler?.let { document.removeEventListener("keydown", it) }
    modalEscHandler = null
    document.getElementById("pane-type-modal")?.remove()
}

/**
 * Opens the terminal-link picker for a specific tab.
 *
 * Renders a scrolling list of existing terminal sessions grouped by
 * tab, each with a live xterm.js preview powered by its own
 * server-side PTY WebSocket. Clicking a session dispatches
 * [WindowCommand.AddLinkToTab] and closes the modal. Only one picker
 * can be open at a time.
 *
 * Wired from [termtasticTabSource]'s `paneAddMenuItems` callback,
 * specifically the "Terminal link" entry — the rest of the new-pane
 * flavours dispatch their `WindowCommand`s directly without a modal.
 *
 * @param emptyTabId the tab that will receive the linked pane.
 * @param anchorPaneId optional anchor pane id, currently informational
 *   (the link command doesn't use it; kept for parity with the other
 *   pane-add commands so future server-side cwd inheritance can wire
 *   in without a signature change).
 * @see closePaneTypeModal
 */
@Suppress("UNUSED_PARAMETER")
fun openTerminalLinkPicker(
    emptyTabId: String,
    anchorPaneId: String? = null,
) {
    if (document.getElementById("pane-type-modal") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "pane-type-modal"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal"

    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    closeBtn.innerHTML = "&times;"
    (closeBtn.asDynamic()).type = "button"
    closeBtn.addEventListener("click", { ev: Event -> ev.stopPropagation(); closePaneTypeModal() })
    card.appendChild(closeBtn)

    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = "Link to terminal"
    card.appendChild(titleEl)

    val body = document.createElement("div") as HTMLElement
    body.className = "pane-modal-body"
    card.appendChild(body)

    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    data class TermLeaf(val paneId: String, val leafTitle: String, val sessionId: String)
    data class TabGroup(val tabTitle: String, val leaves: List<TermLeaf>)

    fun collectTerminalLeaves(): List<TabGroup> {
        val cfg = currentConfig ?: return emptyList()
        val tabsArr = (cfg.tabs as? Array<dynamic>) ?: return emptyList()
        val groups = mutableListOf<TabGroup>()
        for (tab in tabsArr) {
            val tabTitle = tab.title as String
            val leaves = mutableListOf<TermLeaf>()
            val panes = tab.panes as? Array<dynamic> ?: emptyArray()
            for (p in panes) {
                val leaf = p.leaf
                val kind = (leaf.content?.kind as? String) ?: "terminal"
                val sid = leaf.sessionId as String
                if (kind == "terminal" && sid.isNotEmpty()) {
                    leaves.add(TermLeaf(leaf.id as String, leaf.title as String, sid))
                }
            }
            if (leaves.isNotEmpty()) groups.add(TabGroup(tabTitle, leaves))
        }
        return groups
    }

    val groups = collectTerminalLeaves()
    if (groups.isEmpty()) {
        val emptyMsg = document.createElement("p") as HTMLElement
        emptyMsg.className = "pane-modal-empty"
        emptyMsg.textContent = "No terminal sessions to link to"
        body.appendChild(emptyMsg)
    } else {
        val scrollContainer = document.createElement("div") as HTMLElement
        scrollContainer.className = "link-picker-scroll"

        for (group in groups) {
            val section = document.createElement("div") as HTMLElement
            section.className = "link-picker-section"
            val sectionHeader = document.createElement("div") as HTMLElement
            sectionHeader.className = "link-picker-tab-header"
            sectionHeader.innerHTML = """<span class="link-picker-tab-arrow">&#9660;</span> ${group.tabTitle}"""
            val contentDiv = document.createElement("div") as HTMLElement
            contentDiv.className = "link-picker-tab-content"
            sectionHeader.addEventListener("click", { _: Event ->
                val collapsed = contentDiv.style.display == "none"
                contentDiv.style.display = if (collapsed) "" else "none"
                sectionHeader.querySelector(".link-picker-tab-arrow")?.let { arrow ->
                    (arrow as HTMLElement).innerHTML = if (collapsed) "&#9660;" else "&#9654;"
                }
            })

            val grid = document.createElement("div") as HTMLElement
            grid.className = "link-picker-grid"

            for (leaf in group.leaves) {
                val leafCard = document.createElement("div") as HTMLElement
                leafCard.className = "link-picker-card"
                val cardTitle = document.createElement("div") as HTMLElement
                cardTitle.className = "link-picker-card-title"
                cardTitle.textContent = leaf.leafTitle
                leafCard.appendChild(cardTitle)

                val previewContainer = document.createElement("div") as HTMLElement
                previewContainer.className = "link-preview-terminal"
                val previewInner = document.createElement("div") as HTMLElement
                previewInner.className = "link-preview-inner"
                previewContainer.appendChild(previewInner)

                val previewTerm = Terminal(json(
                    "cursorBlink" to false, "disableStdin" to true,
                    "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                    "fontSize" to 7, "scrollback" to 100, "theme" to buildXtermTheme()
                ))
                val previewFit = FitAddon()
                previewTerm.loadAddon(previewFit)
                previewTerm.open(previewInner)
                previewTerm.options.theme = buildXtermTheme()
                leafCard.appendChild(previewContainer)

                if (isDemoClient) {
                    // Demo mode: paint the simulated session's scrollback once
                    // instead of opening a preview WebSocket.
                    attachDemoPreview(previewTerm, leaf.sessionId)
                    previewEntries.add(json("term" to previewTerm, "fit" to previewFit, "socket" to null))
                } else {
                    val previewUrl = "$proto://${window.location.host}/pty/${leaf.sessionId}?$authQueryParam"
                    val previewSocket = WebSocket(previewUrl)
                    previewSocket.asDynamic().binaryType = "arraybuffer"
                    previewSocket.onmessage = { event ->
                        val data = event.asDynamic().data
                        if (data !is String) previewTerm.write(Uint8Array(data as ArrayBuffer))
                    }
                    previewSocket.onopen = { _: Event ->
                        try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {}
                    }
                    previewEntries.add(json("term" to previewTerm, "fit" to previewFit, "socket" to previewSocket))
                }
                window.setTimeout({ try { safeFit(previewTerm, previewFit) } catch (_: Throwable) {} }, 100)

                leafCard.addEventListener("click", { _: Event ->
                    closePaneTypeModal()
                    launchCmd(WindowCommand.AddLinkToTab(tabId = emptyTabId, targetSessionId = leaf.sessionId))
                })
                grid.appendChild(leafCard)
            }
            contentDiv.appendChild(grid)
            section.appendChild(sectionHeader)
            section.appendChild(contentDiv)
            scrollContainer.appendChild(section)
        }
        body.appendChild(scrollContainer)
    }

    overlay.appendChild(card)
    overlay.addEventListener("click", { ev: Event -> if (ev.target === overlay) closePaneTypeModal() })

    val escHandler: (Event) -> Unit = { ev -> if ((ev as KeyboardEvent).key == "Escape") closePaneTypeModal() }
    modalEscHandler = escHandler
    document.addEventListener("keydown", escHandler)
    document.body?.appendChild(overlay)
}
