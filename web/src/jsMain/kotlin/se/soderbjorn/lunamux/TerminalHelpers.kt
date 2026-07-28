/**
 * Terminal-related helper functions for the Lunamux web frontend.
 *
 * Provides utilities for xterm.js terminal management: fitting terminals to their
 * containers while preserving scroll position, applying server-mandated PTY sizes,
 * managing out-of-bounds overlays for unused terminal space, forcing terminal
 * resize reassertion, and detecting ANSI show-cursor escape sequences.
 *
 * @see TerminalEntry
 * @see ensureTerminal
 * @see connectPane
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import se.soderbjorn.lunamux.client.PtyPresentation

/**
 * Kotlin/JS external declaration for the browser's ResizeObserver API.
 *
 * Used to detect when a terminal container's dimensions change (e.g. due to
 * split resizing or window resize) and trigger a terminal refit.
 *
 * @param callback invoked with entries and observer when observed elements resize
 */
@JsName("ResizeObserver")
external class ResizeObserver(callback: (dynamic, dynamic) -> Unit) {
    fun observe(target: HTMLElement)
    fun disconnect()
}

/**
 * Holds all state for a single terminal pane: the xterm.js instance, its fit addon,
 * the DOM container, the WebSocket connection, and PTY size tracking.
 *
 * @property paneId the unique pane identifier
 * @property sessionId the PTY session identifier used for the WebSocket URL
 * @property term the xterm.js [Terminal] instance
 * @property fit the xterm.js [FitAddon] for auto-sizing
 * @property container the DOM element wrapping the terminal
 * @property socket the WebSocket connection to the PTY endpoint, or null if not yet connected
 * @property connected whether the WebSocket is currently open
 * @property resizeObserver the [ResizeObserver] watching the container for size changes
 * @property ptyCols the last known PTY column count from the server
 * @property ptyRows the last known PTY row count from the server
 * @property applyingServerSize true while applying a server-mandated resize (suppresses sending resize back)
 * @property naturalCols the grid width this pane fits at the user's own font — the
 *   width it renders at while driving, sampled on every self-initiated fit. The
 *   baseline the passive mirror is scaled against and the take-over target.
 * @property naturalRows the row counterpart of [naturalCols]
 * @property baseFontSize the user's configured pane font size in px; while mirroring
 *   the applied font is shrunk below this and restored from it on take-over
 * @property passive true while another client is driving, so this pane renders a
 *   read-only, font-scaled mirror of the server grid and drops the ambient reports
 *   its own view generates (see [applyMirrorPresentation])
 * @property driving the server's governance verdict for this connection: true when
 *   this pane governs the PTY, false when another client does, null when the server
 *   has not said (no client governs yet, or it is too old to send the signal). The
 *   server decides governance, so this is authoritative; the width comparison in
 *   [applyMirrorPresentation] is only the fallback for null.
 * @property awaitingVoteAnswer true between casting a size vote and the server
 *   resolving it — a `Size` broadcast, or the discovery that the grid we want is the
 *   grid the server already has. This is the "one vote in flight" latch of the
 *   ack-clocked pipeline ([requestPtyGrid]) as well as the signal the size-mismatch
 *   affordances key off: a width mismatch in that gap is just the server not having
 *   answered us yet, not another client driving.
 * @property votePendingUntil safety-valve deadline (epoch ms) for
 *   [awaitingVoteAnswer]. A vote that loses to a THREE_D or governor decision
 *   produces no `Size` broadcast at all, so the wait needs an upper bound or the
 *   pipeline would latch shut. It is a valve, not a pacing mechanism — see
 *   [requestPtyGrid].
 * @property desiredCols the grid this pane wants the PTY to be, remembered while a
 *   vote is in flight, or 0 when there is nothing outstanding. Only the LATEST desire
 *   is kept: intermediate sizes from a drag are of no interest once superseded.
 * @property desiredRows row half of [desiredCols].
 * @property desiredPriority the tier [desiredCols]×[desiredRows] should be voted at.
 * @property desiredForce true when the outstanding desire is a take-over (a
 *   `ForceResize`) rather than a vote, so a deliberate user gesture deferred by an
 *   in-flight vote is still delivered as a force.
 * @property takeOverBadge floating "Mirroring another device · Take over" pill shown
 *   while [passive], or null before it is created
 * @property oobOverlayRight DOM element for the right out-of-bounds overlay, or null
 * @property oobOverlayBottom DOM element for the bottom out-of-bounds overlay, or null
 * @property scrollButton floating "jump to bottom" pill shown while the user has
 *   scrolled up into the scrollback, or null before it is created (see
 *   [updateScrollButton])
 * @property sendInput function to send user input to the PTY, or null if not connected
 * @property wasContainerVisible last observed visibility of [container] (tracked by
 *   the per-pane `ResizeObserver` to detect a hidden→visible edge, which triggers
 *   a one-shot [forceReassert] so panes restored into not-yet-activated tabs get
 *   their PTY size aligned with the rendered grid the first time the user
 *   switches into the tab — same problem as the startup-active-tab case, just
 *   deferred to first activation)
 * @property demoJob in demo mode, the coroutine mirroring the simulated
 *   session's output into the terminal (see [connectDemoPane]); cancelled on
 *   pane teardown the same way [socket] is closed. `null` outside demo mode.
 * @property autoReflow the *effective* automatic-reflow setting for this
 *   pane. Frozen at pane creation to the per-pane override
 *   ([se.soderbjorn.lunamux.TerminalContent.autoReflow]) if set, otherwise
 *   a snapshot of the user's global default — so a later "future windows"
 *   change leaves this open pane untouched. When `false`, the automatic
 *   reflow paths (geometry change, container resize, tab activation,
 *   reconnect, font load) skip re-asserting the PTY size, freezing the
 *   terminal until the user clicks Reformat. Updated only by an explicit
 *   per-pane override (the "this window" toggle, including its config echo).
 *   Defaults to `true` so a pane behaves as "reflow on" until told otherwise.
 *   See [forceReassert] (the manual path, which ignores this flag).
 * @property everConnected whether this entry has completed at least one
 *   snapshot replay — gates the reconnect-time terminal reset in
 *   [connectPane]'s message handler (a first attach writes into an empty
 *   grid; a reconnect must clear the previous connection's transcript
 *   first or the replay appends a duplicate copy)
 * @property awaitingSnapshot true from socket open until the first binary
 *   frame of that connection arrives — identifies the server's snapshot
 *   replay frame (the `/pty` protocol sends Size → snapshot → live output,
 *   and WebSocket frames are ordered)
 * @property replaying true while xterm.js is parsing a snapshot replay
 *   frame; the shared `onData` handler drops input during this window so
 *   any terminal-query answer xterm emits for replayed bytes is not
 *   injected into the live shell as phantom keystrokes
 * @property pendingResizeTimer handle for the in-flight vote's safety-valve timer,
 *   or null when no vote is outstanding (lives on the entry, not in a
 *   per-connection closure, so the handler can be registered once per
 *   xterm instance). This used to be a 200 ms trailing debounce on every vote; the
 *   pipeline is ack-clocked now, so the timer only exists to unlatch a vote the
 *   server never answers. See [requestPtyGrid].
 * @property restoreSettling true on a cold-restore first attach, from socket
 *   open until the pane's split geometry and webfont metrics settle. While
 *   set, the automatic fit/vote paths hold the grid at the server-restored
 *   width instead of refitting, so a transient startup width can't be voted
 *   back and reflow the just-replayed transcript — the split-pane "mangled
 *   restore" bug, where xterm's lossy reflow of cursor-positioned TUI output
 *   scrambled it. Cleared by [finishRestoreSettle] after the single
 *   reconciling fit + vote.
 * @property settleTimer debounce timer handle for the restore-settle pass, or
 *   null when none is pending (see [scheduleRestoreSettle])
 * @property settleAttempts number of settle reschedules spent waiting for the
 *   webfont to finish loading; capped ([RESTORE_SETTLE_MAX_ATTEMPTS]) so a
 *   font that never reports "loaded" cannot strand the pane in
 *   [restoreSettling] forever
 */
class TerminalEntry(
    val paneId: String,
    val sessionId: String,
    val term: Terminal,
    val fit: FitAddon,
    val container: HTMLElement,
    var socket: WebSocket? = null,
    var connected: Boolean = false,
    var resizeObserver: dynamic = null,
    var ptyCols: Int? = null,
    var ptyRows: Int? = null,
    var applyingServerSize: Boolean = false,
    var naturalCols: Int = 0,
    var naturalRows: Int = 0,
    var baseFontSize: Int = 13,
    var passive: Boolean = false,
    var driving: Boolean? = null,
    var awaitingVoteAnswer: Boolean = false,
    var votePendingUntil: Double = 0.0,
    var desiredCols: Int = 0,
    var desiredRows: Int = 0,
    var desiredPriority: SizePriority = SizePriority.NORMAL,
    var desiredForce: Boolean = false,
    var takeOverBadge: HTMLElement? = null,
    var oobOverlayRight: HTMLElement? = null,
    var oobOverlayBottom: HTMLElement? = null,
    var scrollButton: HTMLElement? = null,
    var sendInput: ((String) -> Unit)? = null,
    var wasContainerVisible: Boolean = false,
    var demoJob: kotlinx.coroutines.Job? = null,
    var autoReflow: Boolean = true,
    var everConnected: Boolean = false,
    var awaitingSnapshot: Boolean = false,
    var replaying: Boolean = false,
    var pendingResizeTimer: Int? = null,
    var restoreSettling: Boolean = false,
    var settleTimer: Int? = null,
    var settleAttempts: Int = 0,
)

/**
 * Fits the terminal to its container while preserving the user's scroll position.
 *
 * Records the distance from the bottom of the scrollback before fitting, then restores that
 * distance after the resize so that content does not jump.
 *
 * **Not an ambient path any more.** A connected client's grid is set only by a server `Size`
 * frame ([applyServerSize]); the only caller left is the demo branch of [sendResize], where
 * there is no server to mandate a grid. Everything that used to fit in answer to its own
 * layout now measures ([measureNaturalGrid]) and asks ([requestPtyGrid]) instead.
 *
 * @param term the xterm.js [Terminal] instance
 * @param fit the [FitAddon] to use for dimension calculation
 * @see safeFit @see preservingScroll
 */
fun fitPreservingScroll(term: Terminal, fit: FitAddon) {
    preservingScroll(term) { safeFit(term, fit) }
}

/**
 * Run [block] — anything that changes the grid — and restore the user's scroll position
 * afterwards.
 *
 * Preserves the distance from the *bottom* of the scrollback rather than an absolute
 * viewport offset, because `baseY` shifts whenever the resize grows or shrinks the
 * scrollback: an absolute restore lands mid-scrollback the moment content height
 * changes, while "I was at the bottom" has to stay at the bottom.
 *
 * Factored out of [fitPreservingScroll] so the one remaining grid-changing path —
 * [applyServerSize] — gets the same treatment. It previously had none: the local fits
 * that carried it are gone, so without this every server-driven reflow would jump a
 * scrolled-back viewport.
 *
 * @param term the xterm.js [Terminal] whose scroll position to hold.
 * @param block the grid change to perform.
 */
fun preservingScroll(term: Terminal, block: () -> Unit) {
    val buffer = term.asDynamic().buffer.active
    val baseYBefore = (buffer.baseY as? Number)?.toInt() ?: 0
    val viewportYBefore = (buffer.viewportY as? Number)?.toInt() ?: 0
    val distanceFromBottom = baseYBefore - viewportYBefore
    block()
    val bufferAfter = term.asDynamic().buffer.active
    val baseYAfter = (bufferAfter.baseY as? Number)?.toInt() ?: 0
    val viewportYAfter = (bufferAfter.viewportY as? Number)?.toInt() ?: 0
    val targetViewportY = (baseYAfter - distanceFromBottom).coerceIn(0, baseYAfter)
    val delta = targetViewportY - viewportYAfter
    if (delta != 0) {
        term.asDynamic().scrollLines(delta)
    }
}

/**
 * Computes the grid a [safeFit] would apply: the fit addon's `proposeDimensions()`
 * proposal with the row count cross-checked against the actual viewport height,
 * so xterm's occasional one-row overshoot never survives.
 *
 * Shared by [safeFit] (which applies the result) and [updateOobOverlay] (which
 * must judge reclaimable space by the *same math* the Reformat action uses —
 * gating the overlay on the raw proposal left a permanent one-row "unused space"
 * strip at the bottom that no reformat could ever clear, because the reformat
 * clamps that row away).
 *
 * When the raw proposal is unavailable, a one-shot cell-metrics re-measure is
 * attempted before giving up ([remeasureCellMetrics]): a terminal `open()`ed
 * while detached (a pane in a tab that has never been activated) caches 0×0
 * cell metrics, and `proposeDimensions()` returns `undefined` on those — so
 * the first tab activation's refit silently left the grid at the 80×24 boot
 * default inside a smaller box, clipping the bottom rows beyond the reach of
 * any scrolling.
 *
 * @param term the xterm.js [Terminal] instance
 * @param fit the [FitAddon] to use for dimension calculation
 * @return the clamped `(cols, rows)` target, or `null` when no usable proposal
 *   is available (terminal not attached/measurable yet).
 * @see safeFit @see updateOobOverlay @see remeasureCellMetrics
 */
fun proposeSafeDimensions(term: Terminal, fit: FitAddon): Pair<Int, Int>? {
    val proposed = fit.asDynamic().proposeDimensions()
        ?: (if (remeasureCellMetrics(term)) fit.asDynamic().proposeDimensions() else null)
        ?: return null
    val targetCols = (proposed.cols as? Number)?.toInt() ?: return null
    var targetRows = (proposed.rows as? Number)?.toInt() ?: return null
    if (targetCols < 1 || targetRows < 1) return null

    val el = term.asDynamic().element as? HTMLElement
    if (el != null && targetRows > 1) {
        val viewport = el.querySelector(".xterm-viewport") as? HTMLElement
        val core = term.asDynamic()._core
        val cellHeight = (core?._renderService?.dimensions?.css?.cell?.height as? Number)?.toDouble()
        val viewportHeight = (viewport?.getBoundingClientRect()?.height as? Number)?.toDouble()
        if (cellHeight != null && cellHeight > 0.0 && viewportHeight != null) {
            val fitRows = kotlin.math.floor(viewportHeight / cellHeight).toInt()
            if (targetRows > fitRows && fitRows >= 1) {
                targetRows = fitRows
            }
        }
    }
    return targetCols to targetRows
}

/**
 * Force xterm to re-measure its cached cell metrics, returning whether usable
 * (non-zero) metrics are available afterwards.
 *
 * xterm measures its character cell once at `Terminal.open()` and only
 * re-measures on explicit triggers (font option change, renderer swap). Every
 * pane living in a tab that has never been activated is `open()`ed on a
 * detached container (`mountPaneContent` builds all pane bodies up front; the
 * toolkit attaches them on first tab activation), so the measurement runs on
 * an element outside the render tree and caches **0×0**. The fit addon's
 * `proposeDimensions()` refuses to propose on zero metrics, so the first
 * activation's refit did nothing and the grid stayed at the 80×24 default —
 * taller than the pane box, with the bottom rows clipped below the fold and
 * unreachable by scrolling (xterm believes it is already at the bottom).
 *
 * Called only by [proposeSafeDimensions], and only after `proposeDimensions()`
 * has returned `undefined`. Bails without measuring when the terminal is
 * still detached or hidden (`offsetParent == null`) — measuring there would
 * just re-cache 0×0 — or when the cached metrics are already non-zero (the
 * proposal failed for some other reason a re-measure cannot help). The
 * measure itself goes through xterm's internal `_charSizeService`, whose
 * change event synchronously refreshes the renderer dimensions that
 * `proposeDimensions()` reads.
 *
 * @param term the xterm.js [Terminal] instance to re-measure
 * @return `true` when the terminal now has non-zero cell metrics and a fit
 *   retry is worthwhile; `false` when re-measuring is impossible or futile.
 * @see proposeSafeDimensions
 */
private fun remeasureCellMetrics(term: Terminal): Boolean = runCatching {
    val el = term.asDynamic().element as? HTMLElement ?: return false
    // Hidden/detached: the measure span has no box, so measuring now would
    // only re-cache zeros. Let a later (visible) fit attempt retry instead.
    if (el.offsetParent == null) return false
    val core = term.asDynamic()._core ?: return false
    // NB: dynamic receivers compile member calls verbatim — `cell.get(name)`
    // would be a real `.get()` call on a plain JS object (TypeError). Use
    // bracket indexing for the by-name reads instead.
    fun cellDim(name: String): Double {
        val cell = core._renderService?.dimensions?.css?.cell ?: return 0.0
        return (cell[name] as? Number)?.toDouble() ?: 0.0
    }
    if (cellDim("width") > 0.0 && cellDim("height") > 0.0) return false
    core._charSizeService.measure()
    cellDim("width") > 0.0 && cellDim("height") > 0.0
}.getOrDefault(false)

/**
 * Safely fits the terminal to its container, with extra validation to prevent over-sizing
 * that causes rendering artifacts.
 *
 * Applies the clamped target from [proposeSafeDimensions]. Skips the resize if dimensions
 * haven't changed.
 *
 * **Two callers only**, both outside the server-driven path: the creation-time fit (which
 * gives a brand-new terminal a sane grid before any socket exists) and the demo branch of
 * [sendResize] (no server, so a demo pane is its own authority). A connected pane's grid is
 * the server's — see [applyServerSize] — and the ambient fit callers this used to serve now
 * measure with [measureNaturalGrid] and ask with [requestPtyGrid].
 *
 * @param term the xterm.js [Terminal] instance
 * @param fit the [FitAddon] to use for dimension calculation
 * @see proposeSafeDimensions @see measureNaturalGrid
 */
fun safeFit(term: Terminal, fit: FitAddon) {
    // Never refit a mirror. Its grid is the server's — bending it to this container
    // would rewrap a redraw authored for another width — and the proposal here is
    // measured at the shrunken mirror font, so it would resize to a wildly larger
    // grid. The many ambient fit callers (tab mount, font change, world close…) pass
    // a bare Terminal, so the guard lives here rather than at each call site.
    // @see applyMirrorPresentation
    if (isMirroringTerm(term)) return
    val (targetCols, targetRows) = proposeSafeDimensions(term, fit) ?: return
    if (targetCols == term.cols && targetRows == term.rows) return
    term.asDynamic().resize(targetCols, targetRows)
}

/**
 * Updates or creates out-of-bounds (OOB) overlay elements that shade unused
 * terminal space when the PTY grid does not fill the container.
 *
 * Overlays appear on the right and/or bottom edges and include a tooltip
 * suggesting the "Reformat" action to reclaim the space. They show only when a
 * reformat would actually grow the grid by at least one column/row (judged via
 * the fit addon's `proposeDimensions()`), so structural insets around the xterm
 * element — padding, scrollbar gutter, sub-cell remainder — never read as
 * reclaimable space.
 *
 * Hidden and skipped entirely while the pane rides a 3D-world plane
 * ([isRidingSpikePlane]): there the pane box is grid-derived so there *is* no
 * unused space, and this function's `getBoundingClientRect` math is measured
 * through the CSS3D transform — it painted a bogus hatch strip over live pane
 * content whenever a resize event fired after [presentPaneToGrid] had hidden
 * the overlays.
 *
 * @param entry the [TerminalEntry] to update overlays for
 * @see forceReassert
 */
fun updateOobOverlay(entry: TerminalEntry) {
    // Nothing is "unused" while mirroring: the grid is deliberately the server's and
    // the empty margin around it is the letterbox, not space this pane could reclaim.
    // The gates below would also read it wrong — they compare against a fit proposal
    // measured at the shrunken mirror font, which reports a far larger grid than the
    // user's own font would, so the hatch would paint straight over the mirror.
    if (isRidingSpikePlane(entry) || entry.passive || isAwaitingOwnSize(entry)) {
        entry.oobOverlayRight?.style?.display = "none"
        entry.oobOverlayBottom?.style?.display = "none"
        return
    }
    fun ensure(slot: String): HTMLElement {
        val existing = when (slot) {
            "right" -> entry.oobOverlayRight
            else -> entry.oobOverlayBottom
        }
        if (existing != null) return existing
        val el = document.createElement("div") as HTMLElement
        el.className = "oob-overlay oob-overlay-$slot"
        el.setAttribute(
            "title",
            "Unused by the current PTY size \u2014 type in this pane, or press " +
                "Reformat, to fit the terminal to it"
        )
        entry.container.appendChild(el)
        if (slot == "right") entry.oobOverlayRight = el else entry.oobOverlayBottom = el
        return el
    }

    // NB: `?.asDynamic()` on an already-dynamic receiver compiles to a *real*
    // `.asDynamic()` method call on the JS object (a TypeError at runtime) — cast
    // to a static type first. This threw on every call with a live element,
    // aborting each caller mid-resize (e.g. inside xterm's synchronous onResize).
    val screen = (entry.term.asDynamic().element as? HTMLElement)
        ?.querySelector(".xterm-screen") as? HTMLElement
    if (screen == null) {
        entry.oobOverlayRight?.style?.display = "none"
        entry.oobOverlayBottom?.style?.display = "none"
        return
    }

    val containerRect = entry.container.asDynamic().getBoundingClientRect()
    val screenRect = screen.asDynamic().getBoundingClientRect()
    val containerLeft = (containerRect.left as? Number)?.toDouble() ?: 0.0
    val containerTop = (containerRect.top as? Number)?.toDouble() ?: 0.0
    val containerWidth = (containerRect.width as? Number)?.toDouble() ?: 0.0
    val containerHeight = (containerRect.height as? Number)?.toDouble() ?: 0.0
    val screenLeft = ((screenRect.left as? Number)?.toDouble() ?: 0.0) - containerLeft
    val screenTop = ((screenRect.top as? Number)?.toDouble() ?: 0.0) - containerTop
    // Size the *used* area arithmetically from the live grid (cols × cellWidth),
    // not from the screen's DOM rect: xterm repaints its DOM asynchronously, so
    // right after a resize/reformat the rect still reports the old size — the
    // overlay would recompute against stale geometry and never clear. The DOM
    // rect is used only as a fallback until the renderer has cell dimensions.
    val oobCell = entry.term.asDynamic()._core?._renderService?.dimensions?.css?.cell
    val cellW = (oobCell?.width as? Number)?.toDouble()?.takeIf { it > 1.0 }
    val cellH = (oobCell?.height as? Number)?.toDouble()?.takeIf { it > 1.0 }
    val screenWidth =
        if (cellW != null) entry.term.cols * cellW
        else (screenRect.width as? Number)?.toDouble() ?: 0.0
    val screenHeight =
        if (cellH != null) entry.term.rows * cellH
        else (screenRect.height as? Number)?.toDouble() ?: 0.0

    val gapRight = containerWidth - (screenLeft + screenWidth)
    val gapBottom = containerHeight - (screenTop + screenHeight)
    // Right dead zone: with the width ratchet ([applyServerSize]) the render grid
    // (term.cols) can be wider than the live PTY (entry.ptyCols) — a phone took
    // over at a narrower width, or wide replay history holds the grid open.
    // Hatch the columns [ptyCols, term.cols), sized arithmetically from cells so
    // the scrollbar gutter / padding is never miscounted as dead space. Width is
    // no longer *reclaimable* here: the ratchet keeps the render grid at least
    // the container fit, so a reformat can never add columns — only the bottom
    // rows (below) still reclaim, since rows track the PTY rather than ratchet.
    val liveCols = entry.ptyCols ?: entry.term.cols
    val deadCols = entry.term.cols - liveCols
    if (deadCols >= 1 && cellW != null && screenHeight > 0) {
        val right = ensure("right")
        right.style.display = "block"
        right.style.left = "${screenLeft + liveCols * cellW}px"
        right.style.top = "${screenTop}px"
        right.style.width = "${deadCols * cellW}px"
        right.style.height = "${screenHeight}px"
    } else {
        entry.oobOverlayRight?.style?.display = "none"
    }

    // Bottom reclaim gap: gate on the fit proposal (the same math safeFit uses,
    // including its viewport row clamp) so structural insets aren't miscounted;
    // show nothing when no proposal is available (terminal not measurable yet).
    val reclaimRows =
        (runCatching { proposeSafeDimensions(entry.term, entry.fit) }.getOrNull()?.second ?: 0) - entry.term.rows

    if (reclaimRows >= 1 && gapBottom > 0 && containerWidth > 0) {
        val bottom = ensure("bottom")
        bottom.style.display = "block"
        bottom.style.left = "${screenLeft}px"
        bottom.style.top = "${screenTop + screenHeight}px"
        bottom.style.width = "${maxOf(screenWidth, 0.0) + maxOf(gapRight, 0.0)}px"
        bottom.style.height = "${gapBottom}px"
    } else {
        entry.oobOverlayBottom?.style?.display = "none"
    }
}

/**
 * Applies a PTY size received from the server to the local xterm.js terminal.
 *
 * Sets the [applyingServerSize] flag to prevent the resize from being echoed
 * back to the server in a feedback loop, then updates the OOB overlay. If the
 * 3D world is open and this terminal rides a ring plane, the plane is
 * re-presented at the new grid too ([spikeOnServerSize]) so the pane stays a
 * truthful window onto the PTY.
 *
 * The local grid always follows the server **exactly**. This replaced the old
 * *width ratchet*, which held xterm's render grid open at the desktop's own fit
 * whenever another client (a phone) governed the PTY narrower, so wide scrollback
 * was never rewrapped. That trade no longer applies — and had become actively
 * wrong: with the server-authoritative model the bytes on the wire are a
 * *synthesized redraw* authored for the server's grid, and a soft-wrapped row is
 * encoded as a full row of cells with no CRLF so the receiver's own deferred
 * autowrap recreates the wrap. Reconstructing that in a grid held wider than the
 * one it was authored at simply never wraps: rows run together and the flow's row
 * count stops matching the cursor epilogue, which is what put blank bands and
 * misplaced repaints in the desktop's scrollback after a few take-overs.
 *
 * Rendering the server grid 1:1 means a pane whose own fit is a different width is
 * now showing someone else's grid; [applyMirrorPresentation] turns that into a
 * deliberate, legible mirror rather than a mismatched viewport.
 *
 * @param entry the [TerminalEntry] to resize
 * @param cols the server-mandated column count (the live PTY width)
 * @param rows the server-mandated row count
 * @see applyMirrorPresentation @see spikeOnServerSize
 */
fun applyServerSize(entry: TerminalEntry, cols: Int, rows: Int) {
    // While the world is open, every server-mandated size is logged: a grid key
    // sends ForceResize(new) and, if some other client (or this client's own 2D
    // machinery) counter-votes, the very next broadcast arrives with the old
    // size — the console then shows the send and the revert back to back.
    if (spikeOpen && (cols != entry.term.cols || rows != entry.term.rows)) {
        console.log(
            "[world3d-spike] server Size ${cols}x$rows for pane ${entry.paneId} " +
                "(local grid was ${entry.term.cols}x${entry.term.rows}) — following"
        )
    }
    entry.ptyCols = cols
    entry.ptyRows = rows
    if (cols != entry.term.cols || rows != entry.term.rows) {
        entry.applyingServerSize = true
        try {
            // Hold the viewport across the reflow. This is now the ONLY path that changes
            // the grid, so it is the only place scroll preservation can live; the local
            // fits that used to carry it (fitPreservingScroll) no longer resize anything.
            preservingScroll(entry.term) {
                runCatching { entry.term.asDynamic().resize(cols, rows) }
            }
        } finally {
            entry.applyingServerSize = false
        }
    }
    // The server has spoken, so whatever we asked for has now been answered. This is the
    // ack that clocks the vote pipeline — it may send the next vote, so it runs after the
    // grid has been applied and ptyCols/ptyRows updated, never before.
    resolvePendingVote(entry)
    applyMirrorPresentation(entry) // also refreshes the out-of-bounds overlay
    runCatching { spikeOnServerSize(entry) }
}

/**
 * Measure the grid this pane *would* fit at the user's own font — without resizing
 * anything.
 *
 * The pure-renderer counterpart of a local fit. A client's grid is now set only by a
 * server `Size` frame, so a layout change cannot answer itself by refitting; all it may
 * do is measure what it would like and ask. This is that measurement.
 *
 * While mirroring, the fit proposal is measured at the *shrunken mirror font*, so it is
 * scaled back by the shown/base font ratio to express what the user's own font would
 * fit. Without that, a mirror's proposal describes a grid several times too large.
 *
 * @param entry the pane to measure.
 * @return the (cols, rows) this pane would fit at the user's font, or null while the
 *   container is hidden or unmeasurable (in which case nothing may be concluded).
 * @see refreshNaturalGrid @see requestPtyGrid
 */
fun measureNaturalGrid(entry: TerminalEntry): Pair<Int, Int>? {
    val proposed = runCatching { proposeSafeDimensions(entry.term, entry.fit) }.getOrNull() ?: return null
    val shown = (entry.term.options.fontSize as? Number)?.toDouble()
    if (shown == null || shown <= 0.0 || entry.baseFontSize <= 0) return proposed
    val k = shown / entry.baseFontSize.toDouble()
    if (k == 1.0) return proposed
    return (proposed.first * k).toInt().coerceAtLeast(2) to (proposed.second * k).toInt().coerceAtLeast(2)
}

/**
 * Re-measure [entry]'s natural grid and record it on the entry.
 *
 * [TerminalEntry.naturalCols]/[TerminalEntry.naturalRows] used to be a side effect of
 * `term.onResize` — i.e. of a local fit having already happened. With the grid pinned to
 * the server there is no such fit, so every geometry, font or visibility change calls
 * this instead: it is what keeps the mirror scale, the out-of-bounds hatch and the
 * take-over target tracking the pane's real box.
 *
 * @param entry the pane to re-measure.
 * @return true when a measurement was taken; false leaves the previous values in place.
 */
fun refreshNaturalGrid(entry: TerminalEntry): Boolean {
    val (cols, rows) = measureNaturalGrid(entry) ?: return false
    if (cols <= 0 || rows <= 0) return false
    entry.naturalCols = cols
    entry.naturalRows = rows
    return true
}

/**
 * Safety-valve timeout (ms) for a vote the server never answers.
 *
 * Not a pacing mechanism — the pipeline is clocked by the server's `Size` frames. A vote
 * that *loses* (to a THREE_D override, or to a governing client) produces no broadcast
 * at all, and without an upper bound the "one vote in flight" latch would stay shut
 * forever. It replaces nothing: the 200 ms trailing debounce it sits next to in history
 * was a guess at how long a drag lasts, which is exactly the kind of magic number the
 * ack clock exists to remove.
 */
private const val VOTE_ACK_TIMEOUT_MS = 1_000

/**
 * Ask the server to make the PTY [cols]×[rows] — the single entry point for every size
 * request this client makes.
 *
 * **Ack-clocked**, not debounced. A request is sent immediately when nothing is
 * outstanding; while a vote is in flight only the LATEST desire is remembered, and it is
 * sent when the in-flight one resolves ([resolvePendingVote]). One vote in flight at a
 * time is what keeps a drag from turning into a vote storm, without guessing how long a
 * drag lasts — the server's answer sets the pace, so a fast server means fast votes and a
 * slow one means fewer.
 *
 * A request for the grid the server already has is not sent at all; it resolves the
 * pipeline instead, since there is nothing to wait for.
 *
 * @param entry the pane making the request.
 * @param cols desired columns. @param rows desired rows.
 * @param priority the tier to vote at; ignored when [force].
 * @param force true for a deliberate take-over (`ForceResize`, which seizes governance),
 *   false for a plain vote. A force is a user gesture, so it is never dropped — only
 *   deferred behind an in-flight vote, and it stays a force when it goes out.
 * @see resolvePendingVote @see sendResizeVote @see sendForceResize
 */
fun requestPtyGrid(entry: TerminalEntry, cols: Int, rows: Int, priority: SizePriority, force: Boolean) {
    if (cols < 2 || rows < 2) return
    val socket = entry.socket ?: return
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return

    // Nothing to ask for: the server is already where we want it. Resolving rather than
    // returning matters — this is the second of the two ack conditions, and it is what
    // unlatches a pipeline whose in-flight vote will never draw a Size frame because it
    // asked for the size the server was already at.
    if (!force && cols == entry.ptyCols && rows == entry.ptyRows) {
        entry.desiredCols = 0
        entry.desiredRows = 0
        clearVoteInFlight(entry)
        return
    }

    if (entry.awaitingVoteAnswer) {
        // Coalesce: keep only the latest desire. A force outranks a queued vote — it is a
        // user gesture and must not be downgraded by a later ambient measurement.
        entry.desiredCols = cols
        entry.desiredRows = rows
        entry.desiredPriority = priority
        entry.desiredForce = entry.desiredForce || force
        return
    }

    entry.desiredCols = 0
    entry.desiredRows = 0
    entry.desiredForce = false
    if (force) sendForceResize(socket, cols, rows)
    else sendResizeVote(socket, cols, rows, priority)

    entry.awaitingVoteAnswer = true
    entry.votePendingUntil = kotlin.js.Date.now() + VOTE_ACK_TIMEOUT_MS
    entry.pendingResizeTimer?.let { window.clearTimeout(it) }
    entry.pendingResizeTimer = window.setTimeout({
        entry.pendingResizeTimer = null
        // The valve: a vote that lost draws no Size frame, so unlatch and let any
        // remembered desire through.
        resolvePendingVote(entry)
    }, VOTE_ACK_TIMEOUT_MS)
}

/**
 * Resolve the in-flight vote and send the remembered desire if it still differs from the
 * server's grid.
 *
 * Called on every `Size` frame ([applyServerSize]) — the normal clock — and from the
 * safety-valve timeout for a vote the server never answers.
 *
 * @param entry the pane whose pipeline to advance.
 */
fun resolvePendingVote(entry: TerminalEntry) {
    clearVoteInFlight(entry)
    val cols = entry.desiredCols
    val rows = entry.desiredRows
    if (cols <= 0 || rows <= 0) return
    if (cols == entry.ptyCols && rows == entry.ptyRows && !entry.desiredForce) {
        entry.desiredCols = 0
        entry.desiredRows = 0
        return
    }
    val priority = entry.desiredPriority
    val force = entry.desiredForce
    entry.desiredCols = 0
    entry.desiredRows = 0
    entry.desiredForce = false
    requestPtyGrid(entry, cols, rows, priority, force)
}

/** Drop the "one vote in flight" latch and its safety-valve timer. */
private fun clearVoteInFlight(entry: TerminalEntry) {
    entry.awaitingVoteAnswer = false
    entry.votePendingUntil = 0.0
    entry.pendingResizeTimer?.let { window.clearTimeout(it) }
    entry.pendingResizeTimer = null
}

/**
 * Whether [entry] is still waiting for the PTY to reach the size it asked for.
 *
 * True while a cold-restored pane is settling (during which it deliberately casts no
 * vote) and for a grace period after each vote is scheduled. In that window the
 * server still holds the session at its *previous* — typically persisted — grid, so
 * the pane legitimately differs from it while the handshake completes.
 *
 * Both size-mismatch affordances key off this, because during startup neither is
 * telling the user anything true: the take-over mirror would claim another device is
 * driving when none is, and the out-of-bounds hatch would advertise space to reclaim
 * that the in-flight vote is already reclaiming. Both resolve on their own a moment
 * later, so showing them just makes initialisation look broken.
 *
 * @param entry the pane to test.
 * @return true while the pane's own size request is outstanding.
 * @see applyMirrorPresentation @see updateOobOverlay
 */
private fun isAwaitingOwnSize(entry: TerminalEntry): Boolean =
    // Never told anything yet: no `Size` frame has arrived on this connection, so there is
    // no server grid to compare against and any mismatch is meaningless. This replaces the
    // vote-pending grace that used to be armed at pane creation — a fact rather than a
    // deadline, and one that cannot expire while startup is still in progress.
    entry.ptyCols == null ||
        entry.restoreSettling ||
        (entry.awaitingVoteAnswer && entry.votePendingUntil > kotlin.js.Date.now())

/** Smallest / largest font (px) the passive mirror will scale the pane text to. */
private const val MIRROR_FONT_MIN_PX = 4.0
private const val MIRROR_FONT_MAX_PX = 40.0

/**
 * Reconcile this pane's presentation with the server grid it is rendering: decide
 * whether it is driving or mirroring, and size the font accordingly.
 *
 * A pane is **mirroring** when the server's grid is a different width than the one
 * this pane fits at the user's own font ([TerminalEntry.naturalCols]) — i.e. another
 * client is driving the shared PTY. The grid itself is never bent to fit (that is
 * what mangles a synthesized redraw, see [applyServerSize]); instead the *font* is
 * scaled so the foreign grid fits the pane box, and the take-over pill is shown. On
 * the way back to driving the user's own font is restored.
 *
 * Called from [applyServerSize] (the grid changed under us), [setPaneFontSize] (the
 * user's font changed) and [reassertGrid] (the pane box changed).
 *
 * @param entry the pane to reconcile.
 * @see PtyPresentation.isPassive @see forceReassert
 */
fun applyMirrorPresentation(entry: TerminalEntry) {
    val serverCols = entry.ptyCols ?: 0
    val serverRows = entry.ptyRows ?: 0
    // A width mismatch means "somebody else's grid" only once our OWN vote has had a
    // chance to land. On attach the server still holds the session at its persisted
    // width, so every pane would briefly look passive and flash the mirror before the
    // vote is answered. While the vote is outstanding, keep driving; if the deadline
    // passes and the width still differs, another client really is governing.
    val awaitingOurVote = isAwaitingOwnSize(entry)
    val matchesOurVote = serverCols == entry.naturalCols
    if (matchesOurVote) entry.votePendingUntil = 0.0
    val verdict = entry.driving
    val passive = if (verdict != null) {
        // The server named the governor. No width guessing, and no vote-in-flight
        // grace either: the verdict is already correct during the gap, because
        // governance does not wait for the grid to move.
        PtyPresentation.isPassive(entry.naturalCols, serverCols, verdict)
    } else {
        PtyPresentation.isPassive(entry.naturalCols, serverCols) && !awaitingOurVote
    }
    entry.passive = passive

    val target: Double = if (passive && entry.naturalRows > 0 && serverRows > 0) {
        // Fit BOTH axes: with the grid pinned to the server, a taller foreign grid
        // would otherwise overflow the pane box and clip the newest rows off the
        // bottom (the same trap the Android mirror hit).
        val ratio = minOf(
            entry.naturalCols.toDouble() / serverCols.toDouble(),
            entry.naturalRows.toDouble() / serverRows.toDouble(),
        )
        (entry.baseFontSize.toDouble() * ratio).coerceIn(MIRROR_FONT_MIN_PX, MIRROR_FONT_MAX_PX)
    } else {
        entry.baseFontSize.toDouble()
    }
    // Guarded: assigning fontSize rebuilds xterm's renderer and remeasures every
    // glyph, so it must fire only on a real change.
    // NB: `Terminal.options` is declared `dynamic`, so calling `.asDynamic()` on it
    // emits a REAL `.asDynamic()` call on the JS object and throws — see the same
    // warning in [updateOobOverlay]. Address it directly.
    val current = (entry.term.options.fontSize as? Number)?.toDouble()
    if (current == null || kotlin.math.abs(current - target) > 0.01) {
        runCatching { entry.term.options.fontSize = target }
    }
    entry.takeOverBadge?.let { badge ->
        if (passive) badge.classList.add("visible") else badge.classList.remove("visible")
    }
    // The verdict gates the out-of-bounds hatch ([updateOobOverlay] hides it for a
    // mirror), and a verdict can flip with no resize event to piggyback on — a
    // Governance frame carries no Size — so re-evaluate the overlay on every
    // reconcile or it displays the previous verdict's answer until something else
    // happens to resize the pane.
    updateOobOverlay(entry)
}

/**
 * Apply the user's configured pane font size, honouring the mirror scale.
 *
 * The render path assigns the pane font on every config push; routing those
 * assignments through here records the user's size as the *base* and lets
 * [applyMirrorPresentation] derive what is actually shown, so a config push can no
 * longer stomp the shrunken mirror font back to full size.
 *
 * @param entry the pane whose font to set.
 * @param px the user's configured font size in px.
 */
fun setPaneFontSize(entry: TerminalEntry, px: Int) {
    entry.baseFontSize = px
    applyMirrorPresentation(entry)
}

/**
 * Forces the terminal to refit to its container and sends a [PtyControl.ForceResize]
 * command to the server to seize the PTY size.
 *
 * Reserved for **explicit** user reformats — the "Reformat" button in the pane
 * header and the ⌃⌥R hotkey — because a force seizes governance and would
 * overthrow a phone that has taken over the session. The *automatic* refits
 * (webfont load, tab activation, `onGeometryChanged`) use [reassertSoft], which
 * votes instead of seizing.
 *
 * Uses [fitPreservingScroll] (not [safeFit]) so the user's scroll
 * position survives the refit — specifically, "I was at the bottom"
 * stays at the bottom because the helper restores the
 * distance-from-`baseY` rather than an absolute viewport offset.
 * `baseY` shifts when scrollback grows / shrinks during the resize,
 * and an absolute restore would land mid-scrollback when content
 * height changed.
 *
 * A no-op while the pane rides a 3D-world plane ([isRidingSpikePlane]) — see the
 * body comment for why fitting there is circular.
 *
 * @param entry the [TerminalEntry] to refit and reassert
 * @see reassertSoft @see fitPreservingScroll
 */
fun forceReassert(entry: TerminalEntry) {
    reassertGrid(entry, force = true)
}

/**
 * The **soft** counterpart of [forceReassert]: refit to the container and send
 * a plain [PtyControl.Resize] size **vote** (at [SizePriority.NORMAL]) instead
 * of a [PtyControl.ForceResize].
 *
 * Used by the *automatic* reasserts — webfont load, hidden→visible tab
 * activation, and the toolkit's `onGeometryChanged` — none of which is a
 * deliberate "reformat to my size" gesture. Forcing from those paths would
 * seize governance with no user intent and overthrow a phone that has taken
 * over the session (see the sizing plan's viewer/driver model). A soft vote
 * still refits this desktop's own grid and applies immediately when this client
 * is the governor (the common single-desktop case), but leaves a phone driver's
 * size alone; the desktop reclaims by *typing*, not by an ambient resize.
 *
 * The explicit Reformat button / ⌃⌥R hotkey keep calling [forceReassert].
 *
 * @param entry the [TerminalEntry] to refit and soft-vote.
 * @see forceReassert @see sendResizeVote
 */
fun reassertSoft(entry: TerminalEntry) {
    reassertGrid(entry, force = false)
}

/**
 * Shared body of [forceReassert] / [reassertSoft]: apply the common guards, measure the
 * grid this pane would like, then ask the server for it — as a [PtyControl.ForceResize]
 * when [force], else as a soft [PtyControl.Resize] vote.
 *
 * No longer fits the terminal. The grid is set only by a server `Size` frame
 * ([applyServerSize]), so a reassert is purely "measure and ask": the reflow arrives when
 * the server answers, which is the tmux round trip Otto accepted for the driving client
 * too. Fitting first also made the *request* wrong — the fit had already moved
 * `term.cols` to the value being asked for, so a force read back its own answer.
 *
 * @param entry the [TerminalEntry] to re-measure and reassert.
 * @param force `true` to seize the PTY size (explicit Reformat), `false` to
 *   only register a vote (automatic refit).
 */
private fun reassertGrid(entry: TerminalEntry, force: Boolean) {
    val socket = entry.socket ?: return
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    if (entry.passive) {
        // A mirror never seizes: its grid is the *server's*, so voting a grid measured
        // from this container would take the PTY from whoever is driving simply because a
        // pane got resized. An automatic reassert instead just rescales the mirror into
        // the new box — and re-measures, so the natural grid (and with it the mirror
        // scale and the take-over target) tracks the box.
        if (!force) {
            refreshNaturalGrid(entry)
            applyMirrorPresentation(entry)
            // Still cast the soft vote of the natural grid. Returning without voting is
            // what let a lone client latch to passive with nothing able to release it.
            sendResize(entry)
            return
        }
        // Forced = an explicit take-over; handled below, after the guards. It used
        // to drop out of mirror mode right here — before the guards — so a guard
        // bailing left a half-taken-over pane: no longer presented as a mirror,
        // but with the ForceResize never sent, the PTY still at the other client's
        // grid, and the out-of-bounds hatch painted over live content. The guards
        // must run before any presentation is touched.
    }
    // Skip while a server-mandated resize is in flight so we don't echo
    // the PTY's just-applied size back to it — that would lock the PTY at the
    // size it just told us it has, defeating the purpose of the reassert.
    if (entry.applyingServerSize) return
    // Stand down while the 3D world is open — for ANY pane, not just one the ring
    // currently holds. While the world is up it is the sole size authority
    // (setPaneGrid sends its own ForceResize) and the 2D shell is hidden, so every
    // 2D-driven reassert here fits the grid to a hidden/transitional container and
    // resizes the shared PTY, reverting the 3D grid. A forced take-over stands
    // down too — its pane stays a mirror, and typing still moves governance
    // server-side ([TermSession.noteClientInput]), which is what flips the
    // presentation once the verdict comes back.
    //
    // The old guard bailed only for panes *currently in the ring*
    // ([isRidingSpikePlane] = `spikeOpen && paneId in spikePanes`). But a **world
    // switch** (⌥⌘O, or the 2D world switcher) briefly drops the departing/arriving
    // world's panes out of the ring while the toolkit rebuilds the 2D shell and
    // fires `maybeReapplyPreset` → this reassert on them (see the console
    // stack). Those slipped past the ring check and resized the PTY out from
    // under the still-mounted 3D term — leaving the pane blank on return, curable
    // only by a real 2D re-fit (a 2D world switch) after close. Guarding on
    // `spikeOpen` alone closes that window. The world-close 2D restore still runs:
    // [closeWorld3dSpike] clears `spikeOpen` before its deferred reassert.
    // @see isRidingSpikePlane @see maybeReapplyPreset
    if (spikeOpen) return
    // Hold off while a cold-restored pane is still settling: its grid is
    // pinned to the server-restored width and a reassert here would fit to a
    // transient container size and reflow the just-replayed transcript. The
    // one-shot [finishRestoreSettle] pass does the reconciling fit + vote once
    // the geometry is stable. A *forced* reassert overrides the settle instead:
    // every force is a deliberate user gesture (Reformat, ⌃⌥R, the take-over
    // pill, typing on a mirror), and dropping it left the pane wedged — the
    // exact "hatch over a pane I can type into" report. See
    // [TerminalEntry.restoreSettling].
    if (entry.restoreSettling) {
        if (!force) return
        entry.restoreSettling = false
    }
    if (entry.passive) {
        // Explicit take-over: drop out of mirror mode and restore the user's own font
        // FIRST — the measurement below reads the live glyph size, so measuring while
        // still shrunken would ask for a grid several times too large. Only reached with
        // [force]: the soft branch returned above.
        entry.passive = false
        entry.takeOverBadge?.classList?.remove("visible")
        runCatching { entry.term.options.fontSize = entry.baseFontSize.toDouble() }
    }
    // Measure, then ask. The grid follows when the server answers.
    refreshNaturalGrid(entry)
    val cols = entry.naturalCols
    val rows = entry.naturalRows
    if (cols >= 2 && rows >= 2) requestPtyGrid(entry, cols, rows, SizePriority.NORMAL, force)
    // Nothing resized locally, so no onResize fires to refresh the hatch — and the
    // presentation may just have flipped above.
    updateOobOverlay(entry)
}

/**
 * Sends a [PtyControl.ForceResize] for an explicit [cols]×[rows] grid over [socket] (a
 * no-op if the socket is not open) — the socket-level core of [forceReassert], factored
 * out so a caller that holds a live PTY socket but no [TerminalEntry] can reassert the
 * session size the same way.
 *
 * This is what lets a 3D world **preview** pane reformat: a preview is a second live
 * client attached to the same `/pty/<session>` socket (exactly like a phone viewing a
 * pane the desktop also has open), so it can drive the shared PTY's size itself instead
 * of waiting to be promoted to the mounted terminal.
 *
 * Takes explicit dims rather than reading `term.cols`: with the grid pinned to the server
 * the local terminal is showing whatever the *server* last said, so a force derived from
 * it would only ever re-assert the size we are trying to change. What a take-over wants is
 * the grid this pane would fit at the user's own font ([measureNaturalGrid]).
 *
 * @param socket the PTY WebSocket to send the resize over.
 * @param cols the column count to seize. @param rows the row count to seize.
 * @see forceReassert @see requestPtyGrid @see se.soderbjorn.lunamux.reformatPane
 */
fun sendForceResize(socket: WebSocket, cols: Int, rows: Int) {
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    runCatching {
        socket.send(
            windowJson.encodeToString<PtyControl>(
                PtyControl.ForceResize(cols = cols, rows = rows)
            )
        )
    }
}

/**
 * Sends a soft [PtyControl.Resize] size **vote** for an explicit [cols]×[rows]
 * grid at the given [priority] tier over [socket] (a no-op if the socket is not
 * open). Unlike [sendForceResize] this evicts nobody — it just registers this
 * client's vote, letting the server's tiered aggregation decide (see
 * [se.soderbjorn.lunamux.SizePriority]).
 *
 * This is the 3D world's channel for asserting a pane's [Pane.grid3d] override:
 * it votes at [SizePriority.THREE_D], which outranks the 2D clients' NORMAL
 * votes without clobbering them, so the override takes effect while the world is
 * open and is dropped automatically when the socket closes. Reverting a pane to
 * native instead votes at [SizePriority.NORMAL].
 *
 * @param socket the PTY WebSocket to send the vote over.
 * @param cols target column count. @param rows target row count.
 * @param priority the tier this vote competes in.
 * @see sendForceResize @see se.soderbjorn.lunamux.setPaneGrid
 */
fun sendResizeVote(socket: WebSocket, cols: Int, rows: Int, priority: SizePriority) {
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    runCatching {
        socket.send(
            windowJson.encodeToString<PtyControl>(
                PtyControl.Resize(cols = cols, rows = rows, priority = priority)
            )
        )
    }
}

/**
 * Ask the server to reset sticky terminal modes for this session.
 *
 * Sends a [PtyControl.ResetModes] control frame over the pane's PTY
 * WebSocket; the server answers by broadcasting DECRST sequences (mouse
 * tracking, focus reporting, bracketed paste, application cursor keys,
 * alt screen) to every client attached to the session and stamping them
 * into the replay ring buffer.
 *
 * Called by the pane kebab menu's "Reset terminal" item — the user-facing
 * escape hatch for a terminal wedged in mouse-reporting mode, e.g. after
 * a killed-server restore replayed a dead full-screen app's DECSET
 * sequences (issue #91).
 *
 * @param entry the [TerminalEntry] whose session should be reset
 */
fun sendModeReset(entry: TerminalEntry) {
    val socket = entry.socket ?: return
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    runCatching {
        socket.send(windowJson.encodeToString<PtyControl>(PtyControl.ResetModes))
    }
}

/**
 * Whether the user has scrolled the terminal up off the bottom of the
 * scrollback (i.e. auto-scroll-to-bottom is effectively paused).
 *
 * xterm.js keeps the viewport pinned where the user left it when new output
 * arrives, so "the viewport is above the latest line" is the same condition
 * the "jump to bottom" pill keys off. Reads the same `buffer.active` fields
 * used by [fitPreservingScroll].
 *
 * @param term the xterm.js [Terminal] instance
 * @return true when `viewportY < baseY` (scrolled up), false at the bottom
 */
fun isScrolledUp(term: Terminal): Boolean {
    val buffer = term.asDynamic().buffer.active
    val baseY = (buffer.baseY as? Number)?.toInt() ?: 0
    val viewportY = (buffer.viewportY as? Number)?.toInt() ?: 0
    return viewportY < baseY
}

/**
 * Shows or hides the [TerminalEntry.scrollButton] pill based on whether the
 * user is currently scrolled up. Called from the terminal's `onScroll`
 * subscription and after each write of PTY output.
 *
 * When the terminal snaps back to the bottom (scrolled-up → false) the
 * "new output" highlight is cleared too, so the pill resets for next time.
 *
 * @param entry the [TerminalEntry] whose pill to update
 * @see isScrolledUp
 * @see markScrollButtonNewOutput
 */
fun updateScrollButton(entry: TerminalEntry) {
    val btn = entry.scrollButton ?: return
    if (isScrolledUp(entry.term)) {
        btn.classList.add("visible")
    } else {
        btn.classList.remove("visible")
        btn.classList.remove("has-new-output")
        (btn.querySelector(".stb-label") as? HTMLElement)?.textContent = "Jump to bottom"
    }
}

/**
 * Flags the pill to advertise that fresh PTY output arrived while the user
 * was scrolled up (CSS `.has-new-output` swaps the label to "New output").
 * No-op when the user is at the bottom (nothing is hidden from them).
 *
 * @param entry the [TerminalEntry] whose pill to flag
 */
fun markScrollButtonNewOutput(entry: TerminalEntry) {
    val btn = entry.scrollButton ?: return
    if (isScrolledUp(entry.term)) {
        btn.classList.add("has-new-output")
        (btn.querySelector(".stb-label") as? HTMLElement)?.textContent = "New output"
    }
}

/**
 * Realigns the `.xterm-viewport` DOM scroll position with the terminal
 * buffer's logical scroll offset (`buffer.active.viewportY`, i.e. xterm's
 * `ydisp`), fixing the desync that follows a detach/reattach of the pane's
 * DOM.
 *
 * Caller context: the toolkit caches each pane's content element by pane id
 * and *reattaches* the cached element whenever it re-renders — on tab
 * activation (the previously-hidden tab's panes come back into the tree) and
 * on every config push (the active tab's panes are reattached in place, e.g.
 * after a window refocus). The browser resets a scrollable element's
 * `scrollTop` to 0 whenever it is removed from and re-inserted into the DOM,
 * but xterm.js renders purely from its internal `ydisp`, so the grid keeps
 * showing the correct line (usually the latest output at the bottom) while the
 * native scrollbar silently sits at the top. The two are now out of sync:
 *
 *  - the first wheel-up does nothing (`scrollTop` is already 0),
 *  - the first wheel-down is read against `scrollTop == 0` and jerks the
 *    viewport to the top of the scrollback, and
 *  - [updateScrollButton] reads `viewportY == baseY` (not scrolled up) so the
 *    "New output" pill never appears.
 *
 * This restores `scrollTop = viewportY * cellHeight` — the exact value xterm's
 * own `Viewport._innerRefresh` would write — so the native scrollbar matches
 * the rendered content again. It works whether the user was at the bottom or
 * scrolled up into history (it keys off `viewportY`, not "at bottom"), is a
 * no-op when already aligned, and produces no visible jump: setting `scrollTop`
 * to precisely `ydisp * cellHeight` makes the resulting `scroll` event a
 * zero-delta no-op inside xterm. Runs independently of [TerminalEntry.autoReflow]
 * because scroll position is orthogonal to PTY sizing.
 *
 * Restoring `scrollTop` isn't enough on its own: the scroll *area* it moves
 * within can also be stale. PTY output keeps arriving while a tab is hidden, so
 * xterm's `Viewport._innerRefresh` runs those writes with the element's
 * `offsetHeight == 0` and sizes the scroll area one screen short; a same-size
 * switch never recomputes it, so the `scrollTop` set below would clamp a screen
 * above the bottom (first wheel-up jumps a screen, and you can't scroll back
 * down). So we call `syncScrollArea(true)` first to force a re-measure, and the
 * manual set just backstops it.
 *
 * Called from the per-pane `ResizeObserver` on each hidden→visible edge (tab
 * activation, where the container gains a non-zero size) and from
 * [renderConfig] after every config push (covers in-place reattaches — e.g.
 * window refocus — that keep the same size, so the `ResizeObserver` never
 * fires). Reads the same `_core._renderService.dimensions.css.cell.height`
 * path as [safeFit], and degrades to a no-op if those internals are absent.
 *
 * @param entry the [TerminalEntry] whose DOM viewport scroll to realign
 * @see isScrolledUp
 * @see updateScrollButton
 * @see fitPreservingScroll
 */
fun resyncViewportScroll(entry: TerminalEntry) {
    val term = entry.term
    val el = term.asDynamic().element as? HTMLElement ?: return
    val viewportEl = el.querySelector(".xterm-viewport") as? HTMLElement ?: return
    val core = term.asDynamic()._core
    val cellHeight = (core?._renderService?.dimensions?.css?.cell?.height as? Number)?.toDouble() ?: return
    if (cellHeight <= 0.0) return
    // Recompute xterm's scroll-area height first, with the now-visible
    // dimensions: writes to a hidden tab ran Viewport._innerRefresh with
    // offsetHeight == 0, leaving the area one screen short, and a same-size
    // switch never fixes it — so the scrollTop below would otherwise clamp a
    // screen above the bottom. No-op when already in sync.
    try { core.viewport.syncScrollArea(true) } catch (_: Throwable) {}
    val buffer = term.asDynamic().buffer.active
    val viewportY = (buffer.viewportY as? Number)?.toInt() ?: return
    val target = viewportY.toDouble() * cellHeight
    // Backstop the syncScrollArea above (and the pre-`viewport`-handle case):
    // only touch the DOM when actually misaligned, so an in-sync pane doesn't
    // churn scrollTop on every push.
    if (kotlin.math.abs(viewportEl.scrollTop - target) > 0.5) {
        viewportEl.scrollTop = target
    }
    // Re-assert the pill against the (unchanged) scroll offset, so a reattach
    // that happened to drop the class leaves it consistent with isScrolledUp.
    updateScrollButton(entry)
}

/**
 * Writes PTY output to the terminal while holding the viewport on the *same
 * content line* when the user has scrolled up (auto-scroll "pause").
 *
 * The pause must keep what the user is reading completely still — not merely
 * stop short of the bottom. xterm.js already does this on its own while its
 * internal `isUserScrolling` flag is set, and it does so correctly in **both**
 * scrollback regimes (see `BufferService.scroll`):
 *
 *  - **Growing** (scrollback not yet full): each appended line increments
 *    `ybase` but leaves `ydisp` put, so the read line keeps its absolute index
 *    and stays on screen.
 *  - **Full** (scrollback at capacity): each appended line trims the oldest
 *    line off the top, so xterm *decrements* `ydisp` to track that shift and
 *    keep the read line stationary.
 *
 * The subtlety is that the correct anchor differs between the two regimes — the
 * absolute `viewportY` is stable only while growing; once trimming starts the
 * absolute index of the read line decreases every write. So we re-assert the
 * absolute `viewportYBefore` **only while the scrollback is still growing**
 * (`baseY` increased across the write). There it is both correct and useful: if
 * a write momentarily followed the bottom, scrolling back up to
 * `viewportYBefore` restores the line *and* re-sets `isUserScrolling` (a
 * negative `scrollLines` sets it) so subsequent writes stay pinned; when xterm
 * already held the line, the delta is 0 and this is a no-op.
 *
 * Once the scrollback is **full** (`baseY` unchanged), we must NOT re-assert:
 * re-anchoring to the now-stale absolute `viewportYBefore` would cancel out
 * xterm's per-trim `ydisp` decrement and march the viewport up one line per
 * write — the "tailing a live feed keeps scrolling away" bug. In that regime we
 * trust xterm's own compensation and leave the scroll position alone.
 *
 * When the user is at the bottom, output is written normally so the terminal
 * keeps auto-following the latest line.
 *
 * @param entry the [TerminalEntry] whose terminal to write to
 * @param bytes the raw PTY output bytes
 * @see isScrolledUp
 * @see fitPreservingScroll
 */
fun writeHoldingScroll(entry: TerminalEntry, bytes: Uint8Array) {
    val term = entry.term
    if (isScrolledUp(term)) {
        val buffer = term.asDynamic().buffer.active
        val viewportYBefore = (buffer.viewportY as? Number)?.toInt() ?: 0
        val baseYBefore = (buffer.baseY as? Number)?.toInt() ?: 0
        markScrollButtonNewOutput(entry)
        term.asDynamic().write(bytes) {
            val after = term.asDynamic().buffer.active
            val viewportYAfter = (after.viewportY as? Number)?.toInt() ?: 0
            val baseYAfter = (after.baseY as? Number)?.toInt() ?: 0
            // Only re-anchor while the scrollback is still growing (no trimming
            // yet, so the absolute viewportYBefore is still the right line).
            // Once full (baseY unchanged) xterm already decremented ydisp to
            // hold the line as it trimmed; re-asserting here would undo that and
            // drift the viewport upward every write.
            if (baseYAfter > baseYBefore) {
                val delta = viewportYBefore - viewportYAfter
                if (delta != 0) term.asDynamic().scrollLines(delta)
            }
            updateScrollButton(entry)
        }
    } else {
        term.write(bytes)
        updateScrollButton(entry)
    }
}

/**
 * Scans a byte array for the ANSI "show cursor" escape sequence (ESC[?25h).
 *
 * Used to detect when the PTY output includes a show-cursor command, which
 * triggers a terminal refresh to work around an xterm.js rendering issue
 * where the cursor may not appear after certain programs exit.
 *
 * @param bytes the raw PTY output bytes to scan
 * @return true if the ESC[?25h sequence is found
 */
fun containsShowCursor(bytes: Uint8Array): Boolean {
    val n = bytes.length
    if (n < 6) return false
    val d = bytes.asDynamic()
    var i = 0
    while (i <= n - 6) {
        if (d[i] == 0x1b &&
            d[i + 1] == 0x5b &&
            d[i + 2] == 0x3f &&
            d[i + 3] == 0x32 &&
            d[i + 4] == 0x35 &&
            d[i + 5] == 0x68
        ) {
            return true
        }
        i++
    }
    return false
}
