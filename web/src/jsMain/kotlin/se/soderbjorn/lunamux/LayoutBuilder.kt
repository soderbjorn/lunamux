/**
 * Layout builder for the Lunamux web frontend free-form pane layout.
 *
 * Responsible for constructing the DOM for the server-provided list of panes.
 * Handles terminal pane creation (xterm.js instances with WebSocket PTY
 * connections), file browser panes, git panes, per-pane absolute positioning,
 * drag-to-move (via the titlebar), drag-to-resize (via the bottom-right
 * corner), pane maximize/restore animations, and drag-and-drop for files and
 * cross-tab pane reordering.
 *
 * This is the core rendering engine that translates the declarative pane list
 * from the server into a live, interactive DOM.
 *
 * @see buildPane
 * @see buildLeafCell
 * @see renderConfig
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.encodeToString
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.FocusEvent
import org.w3c.dom.events.MouseEvent
import kotlin.js.json
import se.soderbjorn.darkness.web.themeeditor.resolveFontFamilyCss

/**
 * Attaches drag-and-drop file handling to a terminal container.
 *
 * When files are dropped onto the terminal, their paths are shell-quoted and
 * pasted into the terminal input. Uses Electron's `getPathForFile` API when
 * available, falling back to the standard File API path property.
 *
 * @param container the terminal container DOM element to attach handlers to
 * @param term the xterm.js [Terminal] instance to paste file paths into
 */
fun attachDragDrop(container: HTMLElement, term: Terminal) {
    container.addEventListener("dragenter", { event -> event.preventDefault() })
    container.addEventListener("dragover", { event ->
        event.preventDefault()
        event.asDynamic().dataTransfer.dropEffect = "copy"
    })
    container.addEventListener("drop", { event ->
        event.preventDefault(); event.stopPropagation()
        val files = event.asDynamic().dataTransfer?.files ?: return@addEventListener
        val count = (files.length as Number).toInt()
        if (count == 0) return@addEventListener
        val api = window.asDynamic().electronApi
        val parts = mutableListOf<String>()
        for (k in 0 until count) {
            val file = files[k]
            val path = (if (api?.getPathForFile != null) api.getPathForFile(file) else file.path) as? String
            if (!path.isNullOrEmpty()) parts.add(shellQuote(path))
        }
        if (parts.isEmpty()) return@addEventListener
        term.focus(); term.paste(parts.joinToString(" "))
    })
}

/**
 * Debounced automatic PTY size vote carrying [entry]'s current grid.
 *
 * Fired by the shared `term.onResize` handler (registered once per xterm
 * instance in [ensureTerminal]) and by [connectPane]'s open handler. Reads
 * [TerminalEntry.socket] at call time instead of closing over a specific
 * connection: xterm.js offers no listener unsubscribe through our bindings,
 * so a per-connection closure would leave one extra `onResize` listener
 * behind on every reconnect and multiply each vote. The debounce handle
 * lives on the entry ([TerminalEntry.pendingResizeTimer]) for the same
 * reason.
 *
 * The grid is sampled synchronously (`term.cols/rows` at call time) so the
 * vote carries the freshly fitted size even though the send itself is
 * debounced; the socket is re-read at fire time so a vote scheduled just
 * before a reconnect lands on the new connection.
 *
 * @param entry the terminal whose grid to vote.
 * @see ensureTerminal
 * @see forceReassert
 */
fun sendResize(entry: TerminalEntry) {
    if (entry.applyingServerSize) return
    // Per-pane "stop automatic reflow": when off, never push a resize to
    // the PTY automatically. This is the single chokepoint every
    // automatic local refit funnels through (via `term.onResize`), so
    // gating here freezes the remote PTY size regardless of which
    // geometry path triggered the local fit. The manual Reformat button
    // bypasses this by calling `forceReassert`, which sends a
    // `ForceResize` directly. See [TerminalEntry.autoReflow].
    if (!entry.autoReflow) return
    // Mid-gesture suppression: while the user drags a split bar or resize
    // corner, local refits keep the grid visually responsive but nothing is
    // voted to the PTY — programs hard-wrap output at whatever transient
    // COLUMNS they see, and xterm.js cannot reflow hard-wrapped lines, so a
    // mid-drag width that reaches the PTY leaves a permanent half-width
    // scar in scrollback. The final size is asserted on release (the
    // gesture-end flush in main.kt, plus the toolkit's onGeometryChanged →
    // forceReassert, which bypasses this gate). See [resizeGestureActive].
    if (resizeGestureActive) return
    if (!entryOpen(entry)) return
    val cols = entry.term.cols; val rows = entry.term.rows
    // While the pane rides a 3D-world plane, this automatic vote lands on the
    // *same* socket/clientId as the 3D world's explicit vote — fired by
    // `term.onResize` the instant `setPaneGrid` resizes the grid, and again on
    // every fresh socket-open re-seed. Vote at the pane's **tier** so it
    // *reinforces* rather than clobbers: a pane carrying a `grid3d` override
    // re-votes THREE_D at the same grid (a plain NORMAL Resize here would
    // silently drop the override to the NORMAL tier — the "counter-voted back"
    // failure the `isRidingSpikePlane` doc describes), while any other riding
    // pane, and every 2D pane, votes NORMAL. The circular fit that would ratchet
    // the grid down is suppressed separately (ResizeObserver / onopen guards), so
    // `cols`/`rows` here is always PTY-truth, never a fit proposal. Muting the
    // vote entirely instead would strand a pane that reconnects or re-mounts in
    // 3D with no size vote at all (blank on world round-trip).
    // @see setPaneGrid @see isRidingSpikePlane @see SizePriority
    val priority =
        if (isRidingSpikePlane(entry) && entry.paneId in spikeGrid3dByPane) SizePriority.THREE_D
        else SizePriority.NORMAL
    entry.pendingResizeTimer?.let { window.clearTimeout(it) }
    // 200 ms trailing debounce. This is the only transient-width mitigation
    // for resize paths that have no drag gesture to gate on (OS window-edge
    // drags, sidebar toggles): long enough to coalesce a continuous drag's
    // intermediate widths into (mostly) the final one, short enough that a
    // settled size still feels immediate.
    entry.pendingResizeTimer = window.setTimeout({
        entry.pendingResizeTimer = null
        val socket = entry.socket
        if (socket != null && entryOpen(entry)) {
            socket.send(
                windowJson.encodeToString<PtyControl>(
                    PtyControl.Resize(cols = cols, rows = rows, priority = priority)
                )
            )
        }
    }, 200)
}

/**
 * Whether [entry]'s current socket exists and is in the OPEN ready state.
 * Shared guard for [sendResize] and the input path.
 */
private fun entryOpen(entry: TerminalEntry): Boolean =
    entry.socket?.readyState?.toInt() == org.w3c.dom.WebSocket.OPEN.toInt()

/**
 * Establishes a WebSocket connection to the server's PTY endpoint for a terminal pane.
 *
 * Handles bidirectional data flow: user keystrokes are sent as binary data to the
 * server, and PTY output (binary) and control messages (JSON) are received and
 * written to the xterm.js terminal. Automatically reconnects on close (unless the
 * pane has been removed), and shows a device-rejected overlay on auth failure (code 1008).
 *
 * The first binary frame of every connection is the server's scrollback
 * replay: on a reconnect the terminal is reset (RIS) before it is written —
 * parity with the native client's `RealPtySocket` — so the replay replaces
 * the previous connection's transcript instead of appending a duplicate
 * copy, and input is gated while xterm parses it (see
 * [TerminalEntry.replaying]).
 *
 * Note: `term.onData`/`term.onResize` are deliberately NOT registered here —
 * xterm.js offers no unsubscribe through our bindings, so per-connection
 * registration would accumulate one listener per reconnect (N reconnects →
 * every keystroke sent N times). They are registered once per xterm instance
 * in [ensureTerminal].
 *
 * @param entry the [TerminalEntry] containing the terminal, session ID, and connection state
 * @see ensureTerminal
 * @see TerminalEntry
 */
fun connectPane(entry: TerminalEntry) {
    if (isDemoClient) {
        // Demo mode: no WebSocket — attach to the in-process simulation.
        connectDemoPane(entry)
        return
    }
    val url = "$proto://$backendHost/pty/${entry.sessionId}?$authQueryParam"
    connectionState[entry.sessionId] = "connecting"
    updateAggregateStatus()

    val socket = org.w3c.dom.WebSocket(url)
    socket.asDynamic().binaryType = "arraybuffer"
    entry.socket = socket

    fun isOpen(): Boolean = socket.readyState.toInt() == org.w3c.dom.WebSocket.OPEN.toInt()

    fun sendInput(data: String) {
        if (!isOpen()) return
        val encoder = js("new TextEncoder()")
        val bytes = encoder.encode(data)
        socket.send(bytes.buffer as org.khronos.webgl.ArrayBuffer)
    }

    // Reassigned (not accumulated) per connection: the one-time `onData`
    // handler in [ensureTerminal] routes through this slot, so a reconnect
    // just swaps which socket keystrokes go to.
    entry.sendInput = ::sendInput

    socket.onopen = { _: org.w3c.dom.events.Event ->
        entry.connected = true
        entry.awaitingSnapshot = true
        connectionState[entry.sessionId] = "connected"
        updateAggregateStatus()
        // Fit to our container and cast a *soft* size vote on every fresh
        // socket open — covers cold startup (panes restored from the server
        // with a stale PTY cols/rows) and reconnects after network blips.
        // The fit runs SYNCHRONOUSLY (not via setTimeout) because the server
        // can push a `PtyServerMessage.Size` immediately after the socket
        // opens; `applyServerSize` would resize the local term to the old PTY
        // value, and a deferred fit would then sample those stale dims.
        // `sendResize` captures `term.cols/rows` at call time, so the vote
        // carries the freshly fitted grid even though the send is debounced.
        //
        // Deliberately a soft vote, NOT `forceReassert`: a ForceResize evicts
        // every other client's size vote, so each reconnecting client
        // (another window, a phone, a headless probe) bulldozed the shared
        // PTY to its own grid — last connector wins, and a small background
        // viewer could pin an interactive session tiny with no way to win it
        // back. A soft vote fixes the cold-startup case just as well (min()
        // over a single client's vote is exactly that client's size) while
        // preserving the multi-client min semantics. The manual Reformat
        // button keeps force semantics — that one is an explicit user action.
        //
        // For panes whose container isn't on-screen yet (inactive tabs at
        // startup), defer to the hidden→visible edge in the ResizeObserver
        // and keep the existing soft `sendResize` as a best-effort ping while
        // detached.
        //
        // Skipped entirely when this pane has automatic reflow turned off:
        // the user has frozen its size, so we leave the PTY at whatever the
        // server restored it to rather than re-asserting the current grid.
        // (`sendResize` would self-gate too, but bail early for clarity.)
        if (entry.autoReflow) {
            if (entry.container.offsetParent != null) {
                // No local fit while the pane rides a 3D-world plane: there the
                // container is grid-derived, so a fit would propose a slightly
                // smaller grid (padding allowance) — the current grid IS the
                // truth to vote. See [isRidingSpikePlane].
                if (!isRidingSpikePlane(entry)) {
                    try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
                }
                sendResize(entry)
            } else {
                window.setTimeout({ sendResize(entry) }, 0)
            }
        }
    }
    socket.onmessage = { event ->
        val data = event.asDynamic().data
        if (data is String) {
            runCatching {
                val msg = windowJson.decodeFromString<PtyServerMessage>(data)
                when (msg) { is PtyServerMessage.Size -> applyServerSize(entry, msg.cols, msg.rows) }
            }
        } else {
            val buf = data as org.khronos.webgl.ArrayBuffer
            val bytes = org.khronos.webgl.Uint8Array(buf)
            if (entry.awaitingSnapshot) {
                // First binary frame of this connection = the server's
                // scrollback replay (the /pty protocol sends Size → snapshot
                // → live output, and WebSocket frames are ordered). On a
                // reconnect the grid still holds the previous connection's
                // transcript and the replay would append a second full copy,
                // so reset the terminal first (RIS) — parity with the native
                // client, which prepends ESC c on reconnect. A first attach
                // writes into an empty grid and needs no reset. Scroll
                // holding is irrelevant on both paths (empty or just-reset
                // grid), hence the direct write.
                entry.awaitingSnapshot = false
                if (entry.everConnected) entry.term.write("\u001bc")
                entry.everConnected = true
                // Gate keystrokes while xterm parses the replay: a query
                // sequence in replayed bytes would be answered via onData
                // and injected into the live shell as phantom input. The
                // server strips known query families; this closes the
                // window for anything it doesn't know about.
                entry.replaying = true
                entry.term.asDynamic().write(bytes) {
                    entry.replaying = false
                    updateScrollButton(entry)
                }
            } else {
                // Write while holding the viewport when the user has scrolled
                // up (pause), and advertising "New output" on the pill. Falls
                // back to a normal auto-following write at the bottom.
                writeHoldingScroll(entry, bytes)
            }
            if (containsShowCursor(bytes)) {
                val term = entry.term
                window.requestAnimationFrame {
                    try { term.asDynamic().refresh(0, term.rows - 1) } catch (_: Throwable) {}
                }
            }
        }
    }
    socket.onclose = { event ->
        entry.connected = false
        // A replay write whose completion callback never fired (socket died
        // mid-parse, pane torn down) must not leave input gated forever.
        entry.replaying = false
        entry.awaitingSnapshot = false
        if (terminals[entry.paneId] === entry) {
            connectionState[entry.sessionId] = "disconnected"
            updateAggregateStatus()
            val code = (event.asDynamic().code as? Number)?.toInt() ?: 0
            val reason = (event.asDynamic().reason as? String) ?: ""
            if (code == 1008) showDeviceRejectedOverlay(code, reason)
            else window.setTimeout({ connectPane(entry) }, 500)
        }
    }
    socket.onerror = { socket.close() }
}

/**
 * Returns the existing [TerminalEntry] for a pane, or creates a new xterm.js terminal
 * instance with a fit addon, connects it to the PTY WebSocket, and registers it
 * in the [terminals] registry.
 *
 * Also sets up a [ResizeObserver] to automatically refit the terminal when its
 * container dimensions change, and attaches drag-and-drop file handling.
 *
 * @param paneId the unique pane identifier
 * @param sessionId the PTY session identifier for the WebSocket connection
 * @return the existing or newly created [TerminalEntry]
 * @see connectPane
 */
fun ensureTerminal(paneId: String, sessionId: String): TerminalEntry {
    terminals[paneId]?.let { return it }

    val container = document.createElement("div") as HTMLElement
    container.className = "terminal"
    container.setAttribute("data-session", sessionId)
    val inner = document.createElement("div") as HTMLElement
    inner.className = "terminal-inner"
    container.appendChild(inner)

    // Floating "jump to bottom" pill. xterm.js leaves the viewport where the
    // user scrolled it when new output arrives, so scrolling up naturally
    // pauses auto-follow; this pill is the affordance to resume. Hidden by
    // default (no `.visible`), toggled by `updateScrollButton` on scroll and
    // after each write. Appended to `container` (the position:relative
    // `.terminal` wrapper) so it floats over the bottom-right of the pane.
    val scrollBtn = document.createElement("button") as HTMLElement
    scrollBtn.className = "scroll-to-bottom-btn"
    scrollBtn.setAttribute("type", "button")
    scrollBtn.setAttribute("title", "Jump to the bottom and resume auto-scroll")
    scrollBtn.innerHTML = "<span class=\"stb-label\">Jump to bottom</span>" +
        "<span class=\"stb-arrow\">↓</span>"
    container.appendChild(scrollBtn)

    val term = Terminal(kotlin.js.json(
        "cursorBlink" to true,
        "fontFamily" to resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily),
        "fontSize" to 13,
        "minimumContrastRatio" to 4.5,
        "theme" to buildXtermTheme()
    ))
    val fit = FitAddon()
    term.loadAddon(fit)
    term.open(inner)
    term.options.theme = buildXtermTheme()
    try { safeFit(term, fit) } catch (_: Throwable) {}
    // Deliberately NO `term.focus()` here. Terminal creation is not a
    // user focus gesture: this factory runs for every pane the toolkit
    // mounts (tab switches, new tabs, restored layouts), and a creation-
    // time focus steal fires `focusin` → `markPaneFocused` →
    // `SetFocusedPane` for whichever pane happened to mount last. With
    // several panes mounting in one render that seeds multiple
    // conflicting SetFocusedPane commands whose config pushes then
    // ping-pong focus between two panes (the "flickering selection"
    // loop). `WindowConnection.refocusActivePane` focuses the server's
    // focusedPaneId after every config render, which covers the
    // new-pane case without the steal.

    // Refit once webfonts are loaded. xterm.js caches cell metrics on the
    // first paint at `term.open`; for terminals created before a bundled
    // `@font-face` finishes loading, that cache is based on the fallback
    // font, so `fit.proposeDimensions()` returns fewer rows than fit and
    // the cell's background paints through below the canvas as a visible
    // gap. Re-assigning `fontFamily` forces xterm to recompute metrics,
    // and the subsequent `safeFit` picks the correct row count. Without
    // this, the first paint stays short until something else (theme
    // switch, sidebar toggle, window resize) triggers a fit pass.
    val fontsApi = document.asDynamic().fonts
    if (fontsApi?.ready != null) {
        fontsApi.ready.then({ _: dynamic ->
            try {
                term.options.fontFamily = resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily)
                if (container.offsetParent != null) {
                    fitPreservingScroll(term, fit)
                    // Cell metrics changed when the bundled webfont
                    // replaced the fallback; the refit above updated
                    // the local grid but the PTY is still at the
                    // pre-font size. Propagate the new grid to the
                    // server too so `top`/`htop` and similar full-
                    // screen programs don't end up with a phantom
                    // blank row at the bottom. `terminals[paneId]?`
                    // is the safe lookup — the entry is registered
                    // by `ensureTerminal` before this fonts callback
                    // can fire, but during teardown it may already
                    // be gone.
                    // Skipped for panes with automatic reflow off — the
                    // local refit above keeps metrics correct, but the
                    // frozen PTY is left untouched.
                    terminals[paneId]?.let { if (it.autoReflow) forceReassert(it) }
                } else {
                    safeFit(term, fit)
                }
            } catch (_: Throwable) {}
        }, { _: dynamic -> })
    }

    attachDragDrop(container, term)

    container.addEventListener("focusin", { _ ->
        // Authoritative "user wants to type here" signal. The DOM
        // `focusin` event bubbles from the xterm <textarea> up through
        // this container, so it fires whenever the user clicks into
        // the terminal, presses keys after a `term.focus()` restore,
        // or otherwise lands native focus inside. Recording the pane
        // id here lets the `focusout` safety net (below) refocus this
        // terminal when the browser detaches the textarea mid-render
        // for reasons other than a config push (the structural fix in
        // `WindowConnection.refocusActivePane` covers config-push
        // detachments). See plans/CD-FOCUS-LOSS-PLAN-V2.md.
        lastFocusedTerminalId = paneId
        val cell = container.asDynamic().closest(".terminal-cell") as? HTMLElement ?: return@addEventListener
        markPaneFocused(cell)
    })

    container.addEventListener("focusout", { ev ->
        // Secondary safety net for involuntary blurs that don't ride
        // a config push. If focus moved to nowhere or to <body>, the
        // browser likely just detached our textarea (toolkit re-render,
        // CSS transition reparenting, etc.) — re-focus on the next
        // frame so the new textarea is in the document tree first.
        // Voluntary blurs (clicking another input/button) carry a
        // non-null relatedTarget and are left alone.
        val fe = ev.unsafeCast<FocusEvent>()
        val related = fe.relatedTarget as? Node
        val isInvoluntary = related == null || related == document.body
        if (!isInvoluntary) return@addEventListener
        if (lastFocusedTerminalId != paneId) return@addEventListener
        // A press inside a *different* pane immediately before this
        // focusout is the user voluntarily switching panes. The clicked
        // target (e.g. another pane's title bar, file-browser chrome)
        // may not be focusable, so `relatedTarget` is null/<body> and
        // the involuntary-blur heuristic above misclassifies it. Reading
        // [lastPointerDownPaneId] — set by the document-level capture
        // pointerdown listener in `main.kt` — lets us bail before we
        // re-emit `SetFocusedPane` for this terminal and race the
        // toolkit's just-sent `SetFocusedPane` for the clicked pane.
        val pressed = lastPointerDownPaneId
        if (pressed != null && pressed != paneId) return@addEventListener
        window.requestAnimationFrame {
            // Programmatic restoration — must not echo SetFocusedPane
            // (see [suppressFocusCommands]).
            suppressFocusCommands = true
            try { terminals[paneId]?.term?.focus() } catch (_: Throwable) {} finally { suppressFocusCommands = false }
        }
    })

    val entry = TerminalEntry(paneId, sessionId, term, fit, container)
    // Freeze the effective automatic-reflow flag at creation time: the
    // per-pane override if the pane carries one, otherwise a *snapshot* of
    // the current global default. Snapshotting here (rather than evaluating
    // the global default live on every render) is what keeps already-open
    // terminals untouched when the user later flips "Automatic reformat
    // (future windows)" — only panes created afterwards pick up the change.
    entry.autoReflow = perPaneAutoReflowOverride(paneId) ?: globalAutoReformatDefault()
    entry.scrollButton = scrollBtn
    terminals[paneId] = entry
    connectionState[sessionId] = "connecting"
    updateAggregateStatus()

    // Input/resize handlers are registered exactly ONCE per xterm instance,
    // here — never in [connectPane]. xterm.js offers no listener unsubscribe
    // through our bindings, so per-connection registration accumulated one
    // extra listener per reconnect: after N reconnects every keystroke (and
    // every resize vote) was delivered to the PTY N times. Both handlers
    // resolve the *current* connection at call time — `onData` through the
    // [TerminalEntry.sendInput] slot (reassigned by each connectPane), and
    // [sendResize] through [TerminalEntry.socket] — so a reconnect swaps the
    // transport without touching the registrations. The `replaying` gate
    // drops input while a snapshot replay is being parsed, so terminal-query
    // answers xterm emits for replayed bytes never reach the live shell (see
    // [TerminalEntry.replaying]). The resize handler also forwards demo-mode
    // grid changes ([pushDemoSessionResize], a no-op outside demo mode —
    // this is the single registration demo panes rely on too).
    term.onData { data -> if (!entry.replaying) entry.sendInput?.invoke(data) }
    term.onResize { _ ->
        sendResize(entry)
        pushDemoSessionResize(entry)
        updateOobOverlay(entry)
    }

    // Keep the pill in sync with the user's scroll position, and let it
    // resume auto-follow on click. `onScroll` fires for both user scrolling
    // and programmatic scroll-to-bottom, so the pill hides itself once back
    // at the bottom without any extra bookkeeping.
    try { term.asDynamic().onScroll { _: dynamic -> updateScrollButton(entry) } } catch (_: Throwable) {}
    scrollBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        try { term.asDynamic().scrollToBottom() } catch (_: Throwable) {}
        updateScrollButton(entry)
        try { term.focus() } catch (_: Throwable) {}
    })

    // Make Shift+Enter insert a newline instead of submitting. By default
    // xterm.js emits a carriage return (`\r`) for Enter *and* Shift+Enter, so
    // a TUI running inside (Claude Code, REPLs, chat-style prompts) can't tell
    // them apart and treats Shift+Enter as "send". We intercept the keydown
    // and emit a line feed (`\n`, 0x0A) instead — byte-identical to Ctrl+J,
    // which such apps map to "insert newline", while a plain shell still reads
    // it as submit (no regression). We deliberately send a bare `\n` rather
    // than the CSI-u sequence (`\x1b[13;2u`) that the kitty keyboard protocol
    // would use: xterm.js doesn't negotiate that protocol, and Claude Code's
    // CSI-u decoder is unreliable (it can echo the literal escape) — `\n` is
    // the robust path (it's the same workaround Ghostty users apply via
    // `shift+enter=text:\n`). Returning `false` suppresses xterm's own `\r`;
    // `preventDefault` stops the hidden textarea from also inserting a newline
    // that would be re-sent. Only plain Shift+Enter is remapped — Ctrl/Alt/Meta
    // combinations fall through to xterm so existing bindings keep working.
    try {
        term.attachCustomKeyEventHandler { ev ->
            if (ev.type == "keydown" && ev.key == "Enter" &&
                ev.shiftKey && !ev.ctrlKey && !ev.altKey && !ev.metaKey
            ) {
                ev.preventDefault()
                entry.sendInput?.invoke("\n")
                false
            } else {
                true
            }
        }
    } catch (_: Throwable) {}

    val observer = ResizeObserver { _, _ ->
        try {
            val visible = entry.container.offsetParent != null
            if (visible) {
                // When automatic reflow is off, freeze the local grid (no
                // refit) so the terminal keeps its current cols/rows; the
                // out-of-bounds overlay below then surfaces the unused space
                // with its "press Reformat" tooltip as the user grows the
                // pane, exactly the affordance the manual path expects.
                //
                // Also frozen while the pane rides a 3D-world plane: there the
                // container is *derived from* the grid, so fitting the grid to
                // it is circular — the fit's padding allowance shrinks the
                // grid one step per pass and the Size broadcast re-presents a
                // smaller container, ratcheting the pane (on every client)
                // down to the minimum. See [isRidingSpikePlane].
                if (entry.autoReflow && !isRidingSpikePlane(entry)) {
                    fitPreservingScroll(entry.term, entry.fit)
                    // Hidden→visible transition: the toolkit's pane-chrome
                    // cache reattaches inactive-tab content on first tab
                    // activation, so a pane that was restored at startup
                    // into a not-yet-opened tab gets its first real
                    // dimensions here. Fire a one-shot reassert on each
                    // false→true edge so the PTY catches up to the grid
                    // — same fix as the startup-active-tab case in
                    // `connectPane.onopen`, just deferred to first
                    // activation. Tracking visibility across fires means
                    // a quick hide/show cycle re-fires correctly.
                    if (!entry.wasContainerVisible) {
                        forceReassert(entry)
                    }
                }
                // Hidden→visible edge: the toolkit reattaches the cached pane
                // element on tab activation, and the browser resets the
                // `.xterm-viewport` scrollTop to 0 on reattach while xterm
                // keeps rendering from its internal ydisp (still at the
                // bottom). Realign the DOM scrollbar with the buffer so the
                // first scroll isn't interpreted against a stale scrollTop=0
                // (which jerks the viewport to the top). Runs regardless of
                // autoReflow — scroll position is independent of PTY sizing.
                if (!entry.wasContainerVisible) {
                    resyncViewportScroll(entry)
                }
                updateOobOverlay(entry)
                // Demo mode: reflow the IRC TUI (and any other size-aware demo
                // session) to the freshly-fitted grid. The demo path bypasses the
                // real socket's resize plumbing, so this observer is where the new
                // size reaches the session. No-op outside demo / for fixed frames.
                pushDemoSessionResize(entry)
            }
            entry.wasContainerVisible = visible
        } catch (_: Throwable) {}
    }
    observer.observe(entry.container)
    entry.resizeObserver = observer
    connectPane(entry)
    return entry
}
/**
 * Per-pane content factory used by `mountAppShell`'s `paneContent` slot.
 *
 * Looks the live leaf descriptor up from the server config by [paneId]
 * and dispatches on its `content.kind` (`fileBrowser`, `git`, or default
 * `terminal`) to build the inner DOM the toolkit's `.dt-pane-content`
 * wrapper will host. The toolkit owns pane chrome (header, focus ring,
 * drag/resize, maximize/close) post-migration, so this factory returns
 * *just the body* — no `.terminal-cell` wrapper, no `buildPaneHeader`
 * call, no `markPaneFocused` mousedown listeners (the toolkit's
 * `LayoutRenderer` handles focus capture).
 *
 * The toolkit caches the returned element by [paneId] (see
 * `paneContentCache` in `AppShellMount`) and reattaches it on every
 * re-render — xterm canvas, scrollback, IME state, and PTY socket all
 * survive across tab switches and layout-preset changes.
 *
 * @param paneId stable pane identifier matching the toolkit snapshot.
 * @return root content element ready for the toolkit to append into
 *   `.dt-pane-content`. Returns a placeholder with the pane id when the
 *   pane is missing from the live config (race during teardown).
 *
 * @see se.soderbjorn.darkness.web.shell.AppShellSpec.paneContent
 * @see ensureTerminal
 */
fun mountPaneContent(paneId: String): HTMLElement {
    // Search every world's tabs, not just the legacy top-level `cfg.tabs`
    // mirror (which only carries the DEFAULT world's panes). A pane created
    // in a non-default world lives solely under `cfg.worlds[…].tabs`, so a
    // `cfg.tabs`-only scan would return `null` and pin this pane on the
    // `[window … — booting]` placeholder forever. See [findLeafDynamic].
    val foundLeaf: dynamic = findLeafDynamic(paneId)
    if (foundLeaf == null) {
        val placeholder = document.createElement("div") as HTMLElement
        placeholder.style.height = "100%"
        placeholder.style.display = "flex"
        placeholder.style.alignItems = "center"
        placeholder.style.justifyContent = "center"
        placeholder.style.color = "var(--t-text-tertiary, #888)"
        placeholder.style.fontFamily = "ui-monospace, monospace"
        placeholder.textContent = "[window $paneId — booting]"
        return placeholder
    }

    // Build the body-only DOM. Toolkit's `.dt-pane > .dt-pane-header +
    // .dt-pane-content` already wraps the returned element, so we must
    // NOT add another header here (would nest two). buildLeafCell does
    // include a buildPaneHeader call in each branch; we replicate the
    // per-content-kind branch here without the header.
    val leaf = foundLeaf
    val title = (leaf.title as? String) ?: paneId
    val contentKind: String = (leaf.content?.kind as? String) ?: "terminal"

    val cell = document.createElement("div") as HTMLElement
    cell.className = "terminal-cell tt-pane-body"
    cell.setAttribute("data-pane", paneId)
    cell.setAttribute("data-content-kind", contentKind)

    when (contentKind) {
        "fileBrowser" -> {
            // The legacy `buildFileBrowserView` takes a `header` element
            // because the toolbar/search bar attaches above the listing.
            // The toolkit's pane chrome supplies its own header; we still
            // need a small in-cell strip for the file-browser's local
            // controls (filter, sort). Pass a fresh empty `<div>` so the
            // builder has somewhere to attach its row, and prepend it
            // visually inside the cell.
            val localStrip = document.createElement("div") as HTMLElement
            localStrip.className = "fb-local-controls"
            cell.appendChild(localStrip)
            val fbView = buildFileBrowserView(paneId, leaf, localStrip)
            cell.appendChild(fbView)
            val fbRenderedEl = fbView.querySelector(".md-rendered") as? HTMLElement
            fbRenderedEl?.style?.fontSize = "${(appVm.stateFlow.value.paneFontSize ?: 14)}px"
        }
        "git" -> {
            val localStrip = document.createElement("div") as HTMLElement
            localStrip.className = "git-local-controls"
            cell.appendChild(localStrip)
            cell.appendChild(buildGitView(paneId, leaf, localStrip))
        }
        "webBrowser" -> {
            // Only the Electron host can embed a live page (a <webview> guest
            // on a CSS3D plane); every other client shows an "Open in browser"
            // link button that hands the URL to the OS default browser.
            if (isElectronWebHost) {
                cell.appendChild(buildWebBrowserView(paneId, leaf))
            } else {
                val url = leaf.content?.url as? String
                cell.appendChild(buildWebBrowserLinkButton(paneId, url))
            }
        }
        "agent" -> {
            val sessionId = leaf.sessionId as String
            val renderMode = (leaf.content?.renderMode as? String) ?: "transcript"
            if (renderMode == "screen") {
                // Screen mode reuses the full xterm.js terminal path — the
                // agent session's byte stream arrives over the same
                // /pty/{sessionId} socket a shell pane uses, and keystrokes
                // typed here flow back into the agent's input channel.
                val entry = ensureTerminal(paneId, sessionId)
                entry.term.options.fontSize = (appVm.stateFlow.value.paneFontSize ?: 14)
                entry.term.options.fontFamily = resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily)
                // See the terminal branch below: don't steal the container off a
                // 3D plane while the world is open. @see closeWorld3dSpike
                if (!spikeOpen) {
                    try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
                    cell.appendChild(entry.container)
                }
            } else {
                // Transcript mode: plain-DOM conversation list + input box
                // over the structured /agent/{sessionId} socket.
                cell.appendChild(ensureAgentTranscript(paneId, sessionId))
            }
        }
        else -> {
            val sessionId = (leaf.content?.sessionId as? String) ?: (leaf.sessionId as String)
            val entry = ensureTerminal(paneId, sessionId)
            // Honour an *explicit* per-pane override pushed in the config
            // (our own "this window" toggle echo, or a change from another
            // client). When the leaf has no override we deliberately leave
            // `entry.autoReflow` at the value frozen in `ensureTerminal`, so
            // a later global-default change never drifts this open pane.
            (leaf.content?.autoReflow as? Boolean)?.let { entry.autoReflow = it }
            entry.term.options.fontSize = (appVm.stateFlow.value.paneFontSize ?: 14)
            entry.term.options.fontFamily = resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily)
            // While the 3D world owns this terminal, its `entry.container` is
            // reparented onto a CSS3D plane. Do NOT append it into this (hidden)
            // 2D cell here: a toolkit pane-content REBUILD triggered mid-3D — an
            // in-3D world switch prunes the departing world's cache
            // (AppShellMount.pruneStalePaneContentCache) and rebuilds it on return
            // — would otherwise `appendChild` the live container out from under the
            // plane, blanking it (the race that left "not all" panes empty on world
            // return). Leave the cell empty while `spikeOpen`; [closeWorld3dSpike]
            // re-homes every live container into its cell on exit. On the normal 2D
            // path (world closed) this is the usual mount + refit.
            // @see closeWorld3dSpike @see se.soderbjorn.lunamux.spikeOpen
            if (!spikeOpen) {
                // Skip the mount-time refit for frozen panes so re-rendering the
                // chrome (tab switch, sidebar toggle) doesn't silently reformat a
                // terminal the user pinned; auto-reflow panes refit as before.
                if (entry.autoReflow) {
                    try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
                }
                cell.appendChild(entry.container)
            }
        }
    }

    return cell
}
