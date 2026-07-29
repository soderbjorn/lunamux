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
import se.soderbjorn.lunamux.client.PtyPresentation
import se.soderbjorn.lunula.web.themeeditor.resolveFontFamilyCss

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
 * The automatic PTY size vote: measure the grid this pane would like, then ask the server
 * for it through the ack-clocked pipeline.
 *
 * The single chokepoint every automatic size request funnels through, so the guards below
 * (frozen pane, cold-restore settle, mid-drag gesture) are stated once. Reads
 * [TerminalEntry.socket] at call time instead of closing over a specific connection:
 * xterm.js offers no listener unsubscribe through our bindings, so a per-connection
 * closure would leave one extra listener behind on every reconnect and multiply each vote.
 *
 * What changed with the pure-renderer model: this used to read `term.cols/rows`, i.e. the
 * result of a local fit that had already happened, and send it behind a 200 ms trailing
 * debounce. There is no local fit any more — the grid is whatever the server last said —
 * so the vote is measured ([measureNaturalGrid]) instead of read back, and the debounce is
 * gone: [requestPtyGrid] paces the votes against the server's answers.
 *
 * @param entry the terminal whose grid to measure and vote.
 * @see requestPtyGrid @see ensureTerminal @see forceReassert
 */
fun sendResize(entry: TerminalEntry) {
    if (entry.applyingServerSize) return
    // Demo mode has no server to mandate a grid, so a demo pane is its own size authority:
    // it fits locally — the one sanctioned exception to server-driven geometry — and tells
    // the simulated session. Routed through this chokepoint so every converted call site
    // works in both modes without restating the branch. @see pushDemoSessionResize
    if (isDemoClient) {
        if (!entry.autoReflow || resizeGestureActive) return
        try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
        refreshNaturalGrid(entry)
        pushDemoSessionResize(entry)
        return
    }
    // Measure unconditionally, ahead of the gates below. The natural grid drives the
    // mirror scale, the take-over target and the out-of-bounds hatch, so it has to track
    // the pane's box even when the *ask* is suppressed (frozen pane, cold restore,
    // mid-drag). Measuring resizes nothing, so there is nothing to suppress about it —
    // only the request is gated.
    refreshNaturalGrid(entry)
    // Cold-restore settling window: swallow the vote. The pane's split geometry and
    // webfont metrics are still moving, so a measurement taken now can be a column or two
    // off the width the scrollback was persisted at. Voting that transient width would
    // make the arbiter rebroadcast it and xterm reflow the just-replayed transcript to the
    // wrong width (lossy for cursor-positioned TUI output). The single
    // [finishRestoreSettle] pass casts the one authoritative vote once the pane is stable.
    // See [TerminalEntry.restoreSettling].
    if (entry.restoreSettling) return
    // Per-pane "stop automatic reflow": when off, never push a resize to
    // the PTY automatically. This is the single chokepoint every automatic size request
    // funnels through, so gating here freezes the remote PTY size regardless of which
    // geometry path asked. The manual Reformat button
    // bypasses this by calling `forceReassert`, which sends a
    // `ForceResize` directly. See [TerminalEntry.autoReflow].
    if (!entry.autoReflow) return
    // Mid-gesture suppression: while the user drags a split bar or resize corner, nothing
    // is voted to the PTY — programs hard-wrap output at whatever transient COLUMNS they
    // see, and no reflow can undo a hard wrap, so a mid-drag width that reaches the PTY
    // leaves a permanent half-width scar in scrollback. Kept even though votes are now
    // ack-clocked: the clock bounds how MANY votes a drag sends, not whether the sizes it
    // passes through are ones the PTY should ever see. The final size is asserted on
    // release (the gesture-end flush in main.kt, plus the toolkit's onGeometryChanged →
    // forceReassert, which bypasses this gate). See [resizeGestureActive].
    if (resizeGestureActive) return
    if (!entryOpen(entry)) return
    // Re-measure first: with the grid pinned to the server, `term.cols/rows` is whatever
    // the server last mandated, so voting it would only ever echo the current PTY size
    // back — including a driver's size while this pane mirrors. What this pane wants is
    // its NATURAL grid: the one it would fit at the user's own font.
    //
    // A mirror does still vote. It is a soft vote, and the arbiter only hands governance
    // over on an explicit force or on real input, so this loses to a client that is
    // actually driving and wins by `driverFallback` when nobody is — which is the whole
    // point: the earlier "a mirror never votes" guard deadlocked a lone client. On restore
    // the server holds the session at its *persisted* width, so the only client attached
    // could differ from it, latch to passive, and then never be able to say so: no vote,
    // no refit, no take-over. It sat "Mirroring another device" with no other device in
    // existence.
    val cols = entry.naturalCols
    val rows = entry.naturalRows
    if (cols < 2 || rows < 2) return
    // While the pane rides a 3D-world plane, this automatic vote lands on the
    // *same* socket/clientId as the 3D world's explicit vote — fired by the pane's
    // ResizeObserver as the plane re-presents, and again on every fresh socket-open
    // re-seed. Vote at the pane's **tier** so it
    // *reinforces* rather than clobbers: a pane carrying a `grid3d` override
    // re-votes THREE_D at the same grid (a plain NORMAL Resize here would
    // silently drop the override to the NORMAL tier — the "counter-voted back"
    // failure the `isRidingSpikePlane` doc describes), while any other riding
    // pane, and every 2D pane, votes NORMAL. The circular fit that used to ratchet the
    // grid down is gone by construction now — nothing fits the grid to a container that
    // was itself derived from the grid — but the riding pane is still excluded from
    // measuring for that reason (see the ResizeObserver guard). Muting the vote entirely
    // instead would strand a pane that reconnects or re-mounts in 3D with no size vote at
    // all (blank on world round-trip).
    // @see setPaneGrid @see isRidingSpikePlane @see SizePriority
    val priority =
        if (isRidingSpikePlane(entry) && entry.paneId in spikeGrid3dByPane) SizePriority.THREE_D
        else SizePriority.NORMAL
    requestPtyGrid(entry, cols, rows, priority, force = false)
}

/**
 * Whether [entry]'s current socket exists and is in the OPEN ready state.
 * Shared guard for [sendResize] and the input path.
 */
private fun entryOpen(entry: TerminalEntry): Boolean =
    entry.socket?.readyState?.toInt() == org.w3c.dom.WebSocket.OPEN.toInt()

/** Quiet period (ms) the container must hold before a restored pane is refit. */
private const val RESTORE_SETTLE_QUIET_MS = 250

/**
 * Max [scheduleRestoreSettle] reschedules spent waiting for the webfont to
 * finish loading before settling anyway (≈ [RESTORE_SETTLE_QUIET_MS] × this ≈
 * 3 s), so a font that never reports `"loaded"` can't strand a pane in the
 * settling state forever.
 */
private const val RESTORE_SETTLE_MAX_ATTEMPTS = 12

/**
 * (Re)arm the post-restore settle debounce for [entry].
 *
 * Called from every path that changes a freshly cold-restored pane's geometry
 * or metrics during the startup window — the snapshot-draw completion
 * ([connectPane]'s message handler), the webfont-load callback, and the
 * container `ResizeObserver` (including a tab's first activation). Each call
 * resets the timer, so the pane is only treated as "settled" once the
 * container has been quiet for [RESTORE_SETTLE_QUIET_MS]; then
 * [finishRestoreSettle] runs the single reconciling fit + vote. A no-op once
 * settling has ended (guarding against a stray late `ResizeObserver` fire).
 *
 * @param entry the restored pane whose settle to (re)schedule.
 * @see finishRestoreSettle @see TerminalEntry.restoreSettling
 */
fun scheduleRestoreSettle(entry: TerminalEntry) {
    if (!entry.restoreSettling) return
    entry.settleTimer?.let { window.clearTimeout(it) }
    entry.settleTimer = window.setTimeout({
        entry.settleTimer = null
        finishRestoreSettle(entry)
    }, RESTORE_SETTLE_QUIET_MS)
}

/**
 * End the cold-restore settling window for [entry] with a single fit + soft
 * size vote, so the restored transcript is reflowed at most once and only to
 * the pane's genuinely-settled width.
 *
 * Preconditions are checked here rather than by callers:
 *  - the container must be visible (`offsetParent != null`); a pane restored
 *    into a not-yet-activated tab has no real dimensions until first
 *    activation, so it stays [TerminalEntry.restoreSettling] and the
 *    `ResizeObserver`'s hidden→visible edge re-arms it via
 *    [scheduleRestoreSettle];
 *  - the webfont should have loaded (`document.fonts.status == "loaded"`),
 *    else the fit would measure fallback-font cells and land on the wrong
 *    column count; reschedule up to [RESTORE_SETTLE_MAX_ATTEMPTS] times, then
 *    proceed regardless so a stuck font can't hang the pane.
 *
 * When the layout matches the pre-quit session the fitted width equals the
 * restored width and [fitPreservingScroll] is a no-op — no reflow. If the
 * window reopened at a different size it reflows exactly once, to the correct
 * width, and [sendResize] propagates it to the PTY. The soft vote (not
 * `forceReassert`) mirrors `onopen`'s multi-client reasoning: it must not evict
 * other clients' votes.
 *
 * @param entry the restored pane to settle.
 * @see scheduleRestoreSettle @see sendResize @see connectPane
 */
fun finishRestoreSettle(entry: TerminalEntry) {
    if (!entry.restoreSettling) return
    if (entry.container.offsetParent == null) return // wait for first-visible edge
    val fontsLoaded = document.asDynamic().fonts?.status == "loaded"
    if (!fontsLoaded && entry.settleAttempts < RESTORE_SETTLE_MAX_ATTEMPTS) {
        entry.settleAttempts++
        entry.settleTimer?.let { window.clearTimeout(it) }
        entry.settleTimer = window.setTimeout({
            entry.settleTimer = null
            finishRestoreSettle(entry)
        }, RESTORE_SETTLE_QUIET_MS)
        return
    }
    entry.restoreSettling = false
    entry.settleTimer = null
    if (entry.autoReflow && !isRidingSpikePlane(entry)) {
        // The single reconciling request: measure the settled geometry and vote it. A no-op
        // on the server when it equals the restored width (the common case); otherwise the
        // server answers with a Size frame and that — not a local fit — is what reflows the
        // replayed transcript, exactly once, at a width the server agrees with.
        sendResize(entry)
    }
}

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
    // Declare the grid this pane currently renders so the server synthesizes the attach
    // redraw at our width (the server-authoritative-screen model) — but only when this pane
    // HAS a grid of its own to declare.
    //
    // A first connect does not. [ensureTerminal] builds the container detached from the
    // document, runs its one creation-time fit there — where the element has no box, so the
    // fit cannot measure anything — and calls this function as its last statement; the caller
    // appends the container to its layout cell only afterwards. So `term.cols/rows` is still
    // xterm's untouched 80×24 default, and declaring it made the server resize the *shared
    // PTY* to 80×24: a real SIGWINCH at a width nobody asked for, a redraw synthesized at it,
    // another when the settle vote corrected it, and the "opening a tab loads the transcript
    // several times, a line or two more each time" flicker. Measured off a screen recording:
    // the first paint wrapped at column 81 and filled exactly 24 rows. It was also a hazard
    // for a second window — the arbiter takes min() over votes, so a fresh pane's 80 columns
    // would have shrunk a session another window was driving until its settle vote landed.
    //
    // With the params absent the server synthesizes at the session's current grid, which for a
    // restored session is the width its scrollback was persisted at — exactly what the
    // restore-settle design wants rendered first, with the single reconciling vote following
    // from [finishRestoreSettle] once the geometry is stable. A *reconnect* does declare: by
    // then `term.cols/rows` is the grid the server last mandated, so it re-asserts a size the
    // arbiter already holds rather than inventing one.
    //
    // @see finishRestoreSettle
    val declaredGrid =
        if (entry.everConnected) "&cols=${entry.term.cols}&rows=${entry.term.rows}" else ""
    val url = "$proto://$backendHost/pty/${entry.sessionId}?$authQueryParam$declaredGrid"
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
            if (!entry.everConnected) {
                // First attach = cold restore. The pane's split geometry and
                // webfont metrics are still settling, so a fit sampled now can
                // land a column or two off the width the scrollback was
                // persisted at. Fitting + voting that transient width makes the
                // server rebroadcast it and xterm reflow the just-replayed
                // transcript to the wrong width — and that reflow is lossy for
                // cursor-positioned TUI output (Claude Code, top, vim), which
                // is the "mangled restore in split panes" bug. So don't fit or
                // vote here: hold the grid, let the server's Size + snapshot
                // render at the persisted width, and defer to a single
                // reconciling fit + vote once the geometry is stable
                // ([scheduleRestoreSettle], armed after the snapshot draws /
                // on first tab activation → [finishRestoreSettle]). When the
                // layout is unchanged the settled fit equals the restored width
                // and nothing reflows; a genuine size change reflows exactly
                // once, to the correct width. A single full-window pane always
                // hit W_c == W_p here, which is why it never showed the bug.
                entry.restoreSettling = true
                entry.settleAttempts = 0
            } else if (entry.container.offsetParent != null) {
                // Reconnect (the transcript is already settled at the live width):
                // re-measure and soft-vote. No measurement while the pane rides a
                // 3D-world plane — there the container is grid-derived, so measuring it
                // would propose a slightly smaller grid (padding allowance) and the
                // current grid IS the truth to vote. See [isRidingSpikePlane].
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
                when (msg) {
                    is PtyServerMessage.Size ->
                        applyServerSize(entry, msg.cols, msg.rows)
                    is PtyServerMessage.Governance -> {
                        // The server names the governor; an ungoverned session
                        // (`governed = false`) restores the width-comparison
                        // fallback rather than pinning us to a stale verdict.
                        entry.driving = if (msg.governed) msg.driving else null
                        applyMirrorPresentation(entry)
                    }
                }
            }
        } else {
            val buf = data as org.khronos.webgl.ArrayBuffer
            val bytes = org.khronos.webgl.Uint8Array(buf)
            if (entry.awaitingSnapshot) {
                // First binary frame of this connection = the server's
                // synthesized attach redraw (the /pty protocol sends Governance,
                // then Size, then the redraw, and WebSocket frames are ordered).
                // It is self-clearing —
                // a RIS (ESC c) + ED3 (CSI 3 J) prefix resets the emulator and
                // clears scrollback before repainting — so, unlike the old ring
                // replay, no explicit pre-reset is needed even on a reconnect: the
                // old grid's transcript is wiped by the redraw's own RIS+ED3.
                // Scroll holding is irrelevant against a just-reset grid, hence the
                // direct write.
                entry.awaitingSnapshot = false
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
                    // Cold restore: the transcript is now on screen at the
                    // server-restored width. Start the settle debounce — once
                    // the container has been quiet for a beat (and the webfont
                    // has loaded), the single reconciling fit + vote runs. A
                    // pane restored into an inactive tab draws here too but
                    // stays settling until first activation. See
                    // [scheduleRestoreSettle].
                    if (entry.restoreSettling) scheduleRestoreSettle(entry)
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

    // Take-over pill: shown while this pane renders another client's grid (see
    // [applyMirrorPresentation]). Clicking it is an input-free take-over — fit the
    // shared PTY to this window. Neutral copy: the size broadcast doesn't say which
    // device is driving.
    val takeOverBadge = document.createElement("button") as HTMLElement
    takeOverBadge.className = "take-over-badge"
    takeOverBadge.setAttribute("type", "button")
    takeOverBadge.setAttribute(
        "title",
        "Another device is driving this session at a different size — " +
            "click to fit it to this window"
    )
    takeOverBadge.innerHTML = "<span class=\"tob-label\">Mirroring another device</span>" +
        "<span class=\"tob-action\">Take over</span>"
    container.appendChild(takeOverBadge)

    val term = Terminal(kotlin.js.json(
        "cursorBlink" to true,
        "fontFamily" to resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily),
        "fontSize" to 13,
        "minimumContrastRatio" to 4.5,
        // xterm's default is 1000 lines, which a single verbose command can
        // blow through — and a reconnect RIS-resets the grid and reseeds it
        // from the server ring, so this is the ceiling on retained history
        // rather than a soft cap over a longer local buffer.
        "scrollback" to 10_000,
        "theme" to buildXtermTheme()
    ))
    val fit = FitAddon()
    term.loadAddon(fit)
    term.open(inner)
    term.options.theme = buildXtermTheme()
    // The one creation-time fit: a brand-new terminal has no socket and no server grid yet,
    // so this gives it a sane starting size (and seeds the natural grid below). From the
    // first `Size` frame on, the grid is the server's — see [applyServerSize].
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
                val settling = terminals[paneId]?.restoreSettling == true
                if (settling) {
                    // Mid cold-restore: the bundled webfont just replaced the
                    // fallback, changing cell metrics. Don't fit or reassert
                    // now — that would reflow the drawn transcript at a
                    // transient grid. Re-arm the settle debounce so the single
                    // reconciling fit runs against the correct metrics.
                    terminals[paneId]?.let { scheduleRestoreSettle(it) }
                } else if (container.offsetParent != null) {
                    // Cell metrics changed when the bundled webfont replaced the fallback,
                    // so the grid this pane fits has changed even though its box has not.
                    // Re-measure and vote; the server's answer reflows the terminal. Doing
                    // it the other way round — refit locally, then tell the server — is
                    // what left `top`/`htop` with a phantom blank row whenever the two
                    // disagreed. `terminals[paneId]?` is the safe lookup: the entry is
                    // registered by `ensureTerminal` before this fonts callback can fire,
                    // but during teardown it may already be gone.
                    //
                    // Skipped for panes with automatic reflow off — the user froze the
                    // size, so the new metrics simply render at the existing grid. A *soft*
                    // reassert: a webfont load is not a user "reformat" gesture, so it
                    // votes rather than seizing the grid from a phone driver.
                    terminals[paneId]?.let { if (it.autoReflow) reassertSoft(it) }
                } else {
                    // Hidden: nothing measurable, and nothing to render. The natural grid
                    // is re-measured on the hidden→visible edge.
                    terminals[paneId]?.let { refreshNaturalGrid(it) }
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
    // Seed the natural grid from the creation-time fit above. `term.onResize` (which
    // records it thereafter) is registered further down, so that first fit would
    // otherwise leave it at 0 — and a 0 natural width makes `isPassive` undecidable,
    // so the pane can neither judge whether it is mirroring nor vote a sane grid
    // until some later ambient refit happens to fire.
    entry.naturalCols = term.cols
    entry.naturalRows = term.rows
    entry.baseFontSize = appVm.stateFlow.value.paneFontSize ?: 14
    // No vote-pending grace is armed here any more. The mirror-flash it existed to
    // suppress is now covered by a fact rather than a clock: until the first server `Size`
    // frame arrives `entry.ptyCols` is null, and [isAwaitingOwnSize] treats that as "this
    // pane has not been told anything yet" — which is exactly the startup window, and
    // needs no deadline to expire. Pre-latching `awaitingVoteAnswer` would now also hold
    // the ack-clocked pipeline shut before the first request. @see requestPtyGrid
    // Freeze the effective automatic-reflow flag at creation time: the
    // per-pane override if the pane carries one, otherwise a *snapshot* of
    // the current global default. Snapshotting here (rather than evaluating
    // the global default live on every render) is what keeps already-open
    // terminals untouched when the user later flips "Automatic reformat
    // (future windows)" — only panes created afterwards pick up the change.
    entry.autoReflow = perPaneAutoReflowOverride(paneId) ?: globalAutoReformatDefault()
    entry.scrollButton = scrollBtn
    entry.takeOverBadge = takeOverBadge
    takeOverBadge.addEventListener("click", { ev ->
        ev.stopPropagation()
        forceReassert(entry)
        try { term.focus() } catch (_: Throwable) {}
    })
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
    // Outbound bytes are classified three ways, exactly as the Android client does
    // (shared rules in [PtyPresentation]) — the difference only matters while this
    // pane is a passive mirror:
    //  - device replies (cursor position, device attributes, colour/mode reports)
    //    are xterm answering a query the *remote program* sent. They must be
    //    delivered — the program is blocked on them — but they are not user intent,
    //    and treating them as such made a mirroring client seize the PTY whenever
    //    the running program happened to probe the terminal.
    //  - ambient mouse/focus reports the mirror's own view generates are dropped:
    //    neither input nor a take-over, or scrolling a mirror would steal the grid.
    //  - anything else is real input, so take over first (fit the shared PTY to this
    //    window) and then send, so the keystroke lands at this pane's width.
    term.onData { data ->
        if (!entry.replaying) {
            val bytes = data.encodeToByteArray()
            when {
                PtyPresentation.isDeviceReply(bytes) -> entry.sendInput?.invoke(data)
                entry.passive && PtyPresentation.isAmbientReport(bytes) -> Unit
                else -> {
                    if (entry.passive) forceReassert(entry)
                    entry.sendInput?.invoke(data)
                }
            }
        }
    }
    term.onResize { _ ->
        // Purely a "the grid changed, refresh what depends on it" hook now. Nothing local
        // reflows the terminal any more, so this fires for a server Size frame
        // ([applyServerSize]), the 3D world following one, the creation-time fit, and the
        // demo path's own fit — never for a fit this pane chose in answer to its own box.
        //
        // Which is why it no longer records the natural grid or votes. The natural grid is
        // MEASURED ([refreshNaturalGrid]) rather than read back from a fit that already
        // happened, and voting here would echo a server-mandated size straight back at the
        // server — the feedback loop `applyingServerSize` existed to suppress.
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
                    if (entry.restoreSettling) {
                        // Cold restore still settling: the container is still
                        // changing (split geometry applying, or this being the
                        // tab's first activation). Don't fit — that would
                        // reflow the drawn transcript at a transient width.
                        // Each change just extends the quiet period; the
                        // debounce firing is the "settled" signal that runs the
                        // single fit ([finishRestoreSettle], which also handles
                        // the not-yet-visible → visible case).
                        scheduleRestoreSettle(entry)
                    } else {
                        // Measure the new box and vote it; the grid follows when the server
                        // answers. Nothing is fitted here — a client that refits itself is
                        // what let two devices disagree about the grid a redraw was authored
                        // at.
                        sendResize(entry)
                        // Hidden→visible transition: the toolkit's pane-chrome
                        // cache reattaches inactive-tab content on first tab
                        // activation, so a pane that was restored at startup
                        // into a not-yet-opened tab gets its first real
                        // dimensions here. Fire a one-shot reassert on each
                        // false→true edge so the PTY catches up to the grid
                        // — same fix as the startup-active-tab case in
                        // `connectPane.onopen`, just deferred to first
                        // activation. Tracking visibility across fires means
                        // a quick hide/show cycle re-fires correctly. A *soft*
                        // reassert: first tab activation is not a user
                        // "reformat" gesture, so it votes rather than seizes the
                        // grid from a phone driver.
                        if (!entry.wasContainerVisible) {
                            reassertSoft(entry)
                        }
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
 * @see se.soderbjorn.lunula.web.shell.AppShellSpec.paneContent
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
                try { setPaneFontSize(entry, appVm.stateFlow.value.paneFontSize ?: 14) } catch (_: Throwable) {}
                entry.term.options.fontFamily = resolveFontFamilyCss(appVm.stateFlow.value.paneFontFamily)
                // See the terminal branch below: don't steal the container off a
                // 3D plane while the world is open. @see closeWorld3dSpike
                if (!spikeOpen) {
                    // Mounting is a geometry change: measure the cell's box and vote it.
                    // Skipped while a cold-restored pane is still settling — a tab switch
                    // onto it would otherwise vote a transient width ahead of
                    // [finishRestoreSettle], and the server's answer to that would reflow
                    // the drawn transcript at the wrong width.
                    if (!entry.restoreSettling) {
                        try { sendResize(entry) } catch (_: Throwable) {}
                    }
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
            try { setPaneFontSize(entry, appVm.stateFlow.value.paneFontSize ?: 14) } catch (_: Throwable) {}
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
                // Skip the mount-time vote for frozen panes so re-rendering the chrome
                // (tab switch, sidebar toggle) doesn't silently reformat a terminal the
                // user pinned; auto-reflow panes ask as before. Also skip while a
                // cold-restored pane is still settling: a tab switch onto it would vote a
                // transient width ahead of [finishRestoreSettle].
                if (entry.autoReflow && !entry.restoreSettling) {
                    try { sendResize(entry) } catch (_: Throwable) {}
                }
                cell.appendChild(entry.container)
            }
        }
    }

    return cell
}
