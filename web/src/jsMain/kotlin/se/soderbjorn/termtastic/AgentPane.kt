/**
 * Web renderer for the `agent` pane kind (MCP-driven agent consoles).
 *
 * Two render modes (see `AgentContent` in :clientServer):
 *  - **screen** — handled in `mountPaneContent` by reusing the ordinary
 *    xterm.js terminal path ([ensureTerminal]) attached to
 *    `/pty/{sessionId}`; nothing in this file runs for screen mode.
 *  - **transcript** — built here: a plain-DOM scrolling conversation list
 *    plus an input row, fed by the `/agent/{sessionId}` WebSocket
 *    (snapshot + append/awaiting/closed events; submits go back as JSON).
 *
 * Entries are registered in [agentTranscripts] and pruned by
 * [pruneAgentTranscripts] from `renderConfig` when their pane leaves the
 * config — mirroring how terminal entries are pruned.
 *
 * @see mountPaneContent
 * @see AgentIndicators.kt for the badge/toast indicators
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.WebSocket

/**
 * Live state for one transcript-mode agent pane: its DOM, socket, and
 * dedupe watermark.
 */
class AgentTranscriptEntry(
    val paneId: String,
    val sessionId: String,
    val root: HTMLElement,
    val list: HTMLElement,
    val input: HTMLInputElement,
    val sendButton: HTMLButtonElement,
) {
    /** The open socket, when connected. */
    var socket: WebSocket? = null

    /** Highest transcript item id already rendered (snapshot dedupe). */
    var lastItemId: Double = 0.0

    /** True once the server said the console closed — stop reconnecting. */
    var closed: Boolean = false
}

/** Registry of live transcript panes, keyed by pane id. */
val agentTranscripts = mutableMapOf<String, AgentTranscriptEntry>()

/**
 * Drop transcript entries whose pane id is no longer in the live config,
 * closing their sockets. Called from [renderConfig] right after the
 * terminal-entry pruning.
 *
 * @param livePaneIds every pane id present in the incoming config.
 */
fun pruneAgentTranscripts(livePaneIds: Set<String>) {
    val stale = agentTranscripts.keys.filter { it !in livePaneIds }
    for (paneId in stale) {
        val entry = agentTranscripts.remove(paneId) ?: continue
        entry.closed = true
        try { entry.socket?.close() } catch (_: Throwable) {}
    }
}

/** Append one transcript item to the list (if newer than the watermark). */
private fun renderItem(entry: AgentTranscriptEntry, item: dynamic) {
    val id = (item.id as? Number)?.toDouble() ?: 0.0
    if (id <= entry.lastItemId) return
    entry.lastItemId = id
    val role = (item.role as? String) ?: "output"
    val row = document.createElement("div") as HTMLElement
    row.className = "agent-item agent-item-$role"
    row.textContent = (item.text as? String) ?: ""
    entry.list.appendChild(row)
    entry.list.scrollTop = entry.list.scrollHeight.toDouble()
}

/** Toggle the input affordance to reflect the agent's awaiting state. */
private fun setAwaiting(entry: AgentTranscriptEntry, awaiting: Boolean) {
    entry.root.setAttribute("data-awaiting", awaiting.toString())
    entry.input.placeholder =
        if (awaiting) "The agent is waiting for your input…" else "Type a line for the agent…"
}

/** Connect (or reconnect) the `/agent/{id}` socket for [entry]. */
private fun connectAgentSocket(entry: AgentTranscriptEntry) {
    if (entry.closed) return
    val url = "$proto://$backendHost/agent/${entry.sessionId}?$authQueryParam"
    val socket = WebSocket(url)
    entry.socket = socket
    socket.onmessage = { event ->
        val data = event.asDynamic().data
        if (data is String) {
            val msg: dynamic = JSON.parse(data)
            when (msg.type as? String) {
                "snapshot" -> {
                    val items = msg.items as? Array<dynamic> ?: emptyArray()
                    for (item in items) renderItem(entry, item)
                    setAwaiting(entry, (msg.awaitingInput as? Boolean) ?: false)
                }
                "append" -> renderItem(entry, msg.item)
                "awaiting" -> setAwaiting(entry, (msg.awaiting as? Boolean) ?: false)
                "closed" -> {
                    entry.closed = true
                    val note = document.createElement("div") as HTMLElement
                    note.className = "agent-item agent-item-ended"
                    note.textContent = "— console ended —"
                    entry.list.appendChild(note)
                    entry.input.disabled = true
                    entry.sendButton.disabled = true
                }
            }
        }
    }
    socket.onclose = {
        if (!entry.closed && agentTranscripts[entry.paneId] === entry) {
            window.setTimeout({ connectAgentSocket(entry) }, 750)
        }
    }
    socket.onerror = { socket.close() }
}

/**
 * Build the transcript-mode DOM for an agent pane: scrolling list +
 * input row, connected to the `/agent/{sessionId}` socket. Returns the
 * cached instance on re-mount so the transcript and socket survive tab
 * switches (mirrors [ensureTerminal]'s caching contract).
 *
 * @param paneId the pane id (registry key).
 * @param sessionId the backing agent session id.
 * @return the root element to append into the pane body.
 */
fun ensureAgentTranscript(paneId: String, sessionId: String): HTMLElement {
    agentTranscripts[paneId]?.let { return it.root }

    val root = document.createElement("div") as HTMLElement
    root.className = "agent-view"

    val list = document.createElement("div") as HTMLElement
    list.className = "agent-transcript"
    root.appendChild(list)

    val inputRow = document.createElement("div") as HTMLElement
    inputRow.className = "agent-input-row"
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "agent-input"
    val send = document.createElement("button") as HTMLButtonElement
    send.className = "agent-send"
    send.textContent = "Send"
    inputRow.appendChild(input)
    inputRow.appendChild(send)
    root.appendChild(inputRow)

    val entry = AgentTranscriptEntry(paneId, sessionId, root, list, input, send)
    agentTranscripts[paneId] = entry
    setAwaiting(entry, false)

    fun submit() {
        val text = input.value
        if (text.isEmpty() || entry.closed) return
        val sock = entry.socket ?: return
        if (sock.readyState.toInt() != WebSocket.OPEN.toInt()) return
        sock.send("""{"type":"submit","text":${JSON.stringify(text)}}""")
        input.value = ""
    }
    send.addEventListener("click", { submit() })
    input.addEventListener("keydown", { ev ->
        if (ev.asDynamic().key == "Enter") {
            ev.preventDefault()
            submit()
        }
    })

    connectAgentSocket(entry)
    return root
}
