/**
 * Terminal-related helper functions for the Termtastic web frontend.
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
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket

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
 *   ([se.soderbjorn.termtastic.TerminalContent.autoReflow]) if set, otherwise
 *   a snapshot of the user's global default — so a later "future windows"
 *   change leaves this open pane untouched. When `false`, the automatic
 *   reflow paths (geometry change, container resize, tab activation,
 *   reconnect, font load) skip re-asserting the PTY size, freezing the
 *   terminal until the user clicks Reformat. Updated only by an explicit
 *   per-pane override (the "this window" toggle, including its config echo).
 *   Defaults to `true` so a pane behaves as "reflow on" until told otherwise.
 *   See [forceReassert] (the manual path, which ignores this flag).
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
    var oobOverlayRight: HTMLElement? = null,
    var oobOverlayBottom: HTMLElement? = null,
    var scrollButton: HTMLElement? = null,
    var sendInput: ((String) -> Unit)? = null,
    var wasContainerVisible: Boolean = false,
    var demoJob: kotlinx.coroutines.Job? = null,
    var autoReflow: Boolean = true,
)

/**
 * Fits the terminal to its container while preserving the user's scroll position.
 *
 * Records the distance from the bottom of the scrollback before fitting, then
 * restores that distance after the resize so that content does not jump.
 *
 * @param term the xterm.js [Terminal] instance
 * @param fit the [FitAddon] to use for dimension calculation
 * @see safeFit
 */
fun fitPreservingScroll(term: Terminal, fit: FitAddon) {
    val buffer = term.asDynamic().buffer.active
    val baseYBefore = (buffer.baseY as? Number)?.toInt() ?: 0
    val viewportYBefore = (buffer.viewportY as? Number)?.toInt() ?: 0
    val distanceFromBottom = baseYBefore - viewportYBefore
    safeFit(term, fit)
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
 * Safely fits the terminal to its container, with extra validation to prevent
 * over-sizing that causes rendering artifacts.
 *
 * Uses the fit addon's `proposeDimensions()` to calculate target cols/rows,
 * then cross-checks with the actual viewport height to avoid allocating more
 * rows than physically fit. Skips the resize if dimensions haven't changed.
 *
 * @param term the xterm.js [Terminal] instance
 * @param fit the [FitAddon] to use for dimension calculation
 */
fun safeFit(term: Terminal, fit: FitAddon) {
    val proposed = fit.asDynamic().proposeDimensions() ?: return
    var targetCols = (proposed.cols as? Number)?.toInt() ?: return
    var targetRows = (proposed.rows as? Number)?.toInt() ?: return
    if (targetCols < 1 || targetRows < 1) return

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

    if (targetCols == term.cols && targetRows == term.rows) return
    term.asDynamic().resize(targetCols, targetRows)
}

/**
 * Updates or creates out-of-bounds (OOB) overlay elements that shade unused
 * terminal space when the PTY grid does not fill the container.
 *
 * Overlays appear on the right and/or bottom edges and include a tooltip
 * suggesting the "Reformat" action to reclaim the space.
 *
 * @param entry the [TerminalEntry] to update overlays for
 * @see forceReassert
 */
fun updateOobOverlay(entry: TerminalEntry) {
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
            "Unused by the current PTY size \u2014 press Reformat to reclaim this space"
        )
        entry.container.appendChild(el)
        if (slot == "right") entry.oobOverlayRight = el else entry.oobOverlayBottom = el
        return el
    }

    val screen = entry.term.asDynamic().element
        ?.asDynamic()?.querySelector(".xterm-screen") as? HTMLElement
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
    val screenWidth = (screenRect.width as? Number)?.toDouble() ?: 0.0
    val screenHeight = (screenRect.height as? Number)?.toDouble() ?: 0.0

    val gapRight = containerWidth - (screenLeft + screenWidth)
    val gapBottom = containerHeight - (screenTop + screenHeight)
    val minGap = 4.0

    if (gapRight > minGap && screenHeight > 0) {
        val right = ensure("right")
        right.style.display = "block"
        right.style.left = "${screenLeft + screenWidth}px"
        right.style.top = "${screenTop}px"
        right.style.width = "${gapRight}px"
        right.style.height = "${screenHeight}px"
    } else {
        entry.oobOverlayRight?.style?.display = "none"
    }

    if (gapBottom > minGap && containerWidth > 0) {
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
 * back to the server in a feedback loop, then updates the OOB overlay.
 *
 * @param entry the [TerminalEntry] to resize
 * @param cols the server-mandated column count
 * @param rows the server-mandated row count
 */
fun applyServerSize(entry: TerminalEntry, cols: Int, rows: Int) {
    entry.ptyCols = cols
    entry.ptyRows = rows
    if (cols != entry.term.cols || rows != entry.term.rows) {
        entry.applyingServerSize = true
        try {
            runCatching { entry.term.asDynamic().resize(cols, rows) }
        } finally {
            entry.applyingServerSize = false
        }
    }
    updateOobOverlay(entry)
}

/**
 * Forces the terminal to refit to its container and sends a [PtyControl.ForceResize]
 * command to the server to update the PTY size.
 *
 * Called by the "Reformat" button in the pane header *and* automatically
 * from [se.soderbjorn.darkness.web.shell.AppShellSpec.onGeometryChanged]
 * after every committed geometry change (split-bar resize end,
 * maximize/restore, layout-preset apply) to reclaim unused terminal
 * space without requiring the user to click Reformat.
 *
 * Uses [fitPreservingScroll] (not [safeFit]) so the user's scroll
 * position survives the refit — specifically, "I was at the bottom"
 * stays at the bottom because the helper restores the
 * distance-from-`baseY` rather than an absolute viewport offset.
 * `baseY` shifts when scrollback grows / shrinks during the resize,
 * and an absolute restore would land mid-scrollback when content
 * height changed.
 *
 * @param entry the [TerminalEntry] to refit and reassert
 * @see fitPreservingScroll
 */
fun forceReassert(entry: TerminalEntry) {
    val socket = entry.socket ?: return
    if (socket.readyState.toInt() != WebSocket.OPEN.toInt()) return
    // Skip while a server-mandated resize is in flight so we don't echo
    // the PTY's just-applied size back to it as a "forced" value — that
    // would lock the PTY at the size it just told us it has, defeating
    // the purpose of the reassert (forcing the PTY to match our grid).
    if (entry.applyingServerSize) return
    try { fitPreservingScroll(entry.term, entry.fit) } catch (_: Throwable) {}
    runCatching {
        socket.send(
            windowJson.encodeToString<PtyControl>(
                PtyControl.ForceResize(cols = entry.term.cols, rows = entry.term.rows)
            )
        )
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
 * Writes PTY output to the terminal while holding the viewport on the *same
 * content line* when the user has scrolled up (auto-scroll "pause").
 *
 * The pause must keep what the user is reading completely still — not merely
 * stop short of the bottom. The anchor is therefore the absolute top line
 * (`viewportY`), NOT the distance from the bottom: if we held the distance,
 * every appended line would push the read line up by one and the content would
 * drift past the reader every time output arrives.
 *
 * xterm.js's own `BufferService` already holds `ydisp` when its internal
 * `isUserScrolling` flag is set, so normally a write keeps the line still on
 * its own. We re-assert it defensively in the write callback (after the data
 * is parsed): if anything caused the viewport to follow the bottom, scrolling
 * back up to `viewportYBefore` restores the line *and* re-sets
 * `isUserScrolling` (a negative `scrollLines` sets it), so subsequent writes
 * stay pinned. When xterm already held the line, `delta` is 0 and this is a
 * no-op.
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
        markScrollButtonNewOutput(entry)
        term.asDynamic().write(bytes) {
            val after = term.asDynamic().buffer.active
            val viewportYAfter = (after.viewportY as? Number)?.toInt() ?: 0
            // Restore the same top line. delta < 0 (scroll back up) when the
            // write followed the bottom; that also re-sets isUserScrolling.
            val delta = viewportYBefore - viewportYAfter
            if (delta != 0) term.asDynamic().scrollLines(delta)
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
