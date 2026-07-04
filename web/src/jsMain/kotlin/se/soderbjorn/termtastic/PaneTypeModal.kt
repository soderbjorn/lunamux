/**
 * Terminal-link picker modal for Termtastic.
 *
 * Opened from the "New pane" hover dropdown when the user picks
 * "Terminal link". Shows a scrolling list of existing terminal
 * sessions (grouped by tab) with a thumbnail preview for each;
 * clicking a preview dispatches [WindowCommand.AddLinkToTab] to link
 * the empty pane against that session.
 *
 * Each thumbnail is rendered by [LinkThumbnail] (see
 * [LinkThumbnailRenderer]): a hidden, read-only xterm.js instance
 * receives the session PTY as a faithful ANSI/buffer model, and the
 * visible card paints that buffer's transcript onto an HTML5 canvas,
 * re-wrapped to the card's own width and bottom-anchored — a port of
 * the Android overview's terminal miniature, which gives a more
 * comprehensive view than the previous shrunk-to-tiny-font live xterm
 * (which kept the source terminal's columns and clipped the right edge).
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
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.json

/**
 * Closes all live terminal preview WebSockets and disposes their thumbnail
 * renderers (which own and dispose the hidden xterm.js data-source instances).
 *
 * Called when the link picker is closed, to clean up resources created
 * for the preview thumbnails.
 *
 * @see LinkThumbnail.dispose
 */
fun disposeAllPreviews() {
    for (entry in previewEntries) {
        try { (entry.socket as WebSocket).close() } catch (_: Throwable) {}
        try { (entry.thumbnail as LinkThumbnail).dispose() } catch (_: Throwable) {}
        try { (entry.hiddenHost as HTMLElement).remove() } catch (_: Throwable) {}
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
                // Agent consoles carry a real session id whose byte stream is
                // served like a PTY's, so they are link-target eligible too —
                // a linked view renders the console through a terminal pane.
                if ((kind == "terminal" || kind == "agent") && sid.isNotEmpty()) {
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

                // Visible thumbnail: an HTML5 canvas painted by [LinkThumbnail]
                // with the Android-style re-wrapped, bottom-anchored transcript
                // (see LinkThumbnailRenderer). The card's CSS gives the preview
                // box a fixed size; size the canvas to its backing-store pixels.
                val previewContainer = document.createElement("div") as HTMLElement
                previewContainer.className = "link-preview-terminal"
                val canvas = document.createElement("canvas") as HTMLCanvasElement
                canvas.className = "link-preview-canvas"
                previewContainer.appendChild(canvas)
                leafCard.appendChild(previewContainer)

                // Hidden xterm.js model: receives the session PTY exactly as the
                // old visible preview did, but is kept off-screen and used only
                // as a faithful ANSI/buffer source for the canvas renderer.
                val hiddenHost = document.createElement("div") as HTMLElement
                hiddenHost.className = "link-preview-hidden-host"
                document.body?.appendChild(hiddenHost)

                val previewTerm = Terminal(json(
                    "cursorBlink" to false, "disableStdin" to true,
                    "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                    "fontSize" to 9, "cols" to 120, "rows" to 40,
                    "scrollback" to 200, "theme" to buildXtermTheme()
                ))
                previewTerm.open(hiddenHost)

                val theme = buildXtermTheme()
                val fg = (theme.foreground as? String) ?: "#d4d4d4"
                val bg = (theme.background as? String) ?: "#1e1e1e"
                val thumbnail = LinkThumbnail(previewTerm, canvas, fg, bg)

                // Lay out the canvas backing store to its rendered CSS size once
                // the card is in the DOM, then paint the initial (empty) frame.
                window.setTimeout({
                    val rect = previewContainer.getBoundingClientRect()
                    val w = (rect.width as? Number)?.toInt() ?: 0
                    val h = (rect.height as? Number)?.toInt() ?: 0
                    if (w > 0 && h > 0) {
                        canvas.width = w
                        canvas.height = h
                    }
                    thumbnail.repaint()
                }, 0)

                if (isDemoClient) {
                    // Demo mode: paint the simulated session's scrollback once
                    // into the hidden model instead of opening a preview socket.
                    attachDemoPreview(previewTerm, leaf.sessionId)
                    // The demo write lands asynchronously; repaint shortly after.
                    window.setTimeout({ thumbnail.scheduleRepaint() }, 120)
                    previewEntries.add(json(
                        "thumbnail" to thumbnail, "hiddenHost" to hiddenHost, "socket" to null
                    ))
                } else {
                    val previewUrl = "$proto://${window.location.host}/pty/${leaf.sessionId}?$authQueryParam"
                    val previewSocket = WebSocket(previewUrl)
                    previewSocket.asDynamic().binaryType = "arraybuffer"
                    previewSocket.onmessage = { event ->
                        val data = event.asDynamic().data
                        if (data !is String) {
                            previewTerm.write(Uint8Array(data as ArrayBuffer))
                            thumbnail.scheduleRepaint()
                        }
                    }
                    previewEntries.add(json(
                        "thumbnail" to thumbnail, "hiddenHost" to hiddenHost, "socket" to previewSocket
                    ))
                }

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
