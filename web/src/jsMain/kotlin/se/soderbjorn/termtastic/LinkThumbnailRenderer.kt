/**
 * Canvas-based terminal thumbnail renderer for the link picker.
 *
 * This is a Kotlin/JS port of the Android overview's terminal miniature
 * (`MiniTerminalPane` / `MiniTerminalRegistry.extractRecentLines`). The
 * Android renderer is "more comprehensive" than the web's previous approach
 * because it does not merely shrink a live terminal to a tiny font (which
 * keeps the *source* terminal's column count and clips the right edge);
 * instead it:
 *
 *  1. reads the emulator's joined-line **transcript** (scrollback + screen),
 *     where terminal-width hard wraps are collapsed back into single logical
 *     lines, then
 *  2. **re-wraps** each logical line to the *thumbnail's own* (narrow) width,
 *     so long commands/output reflow to fit the small card rather than being
 *     cut off, and
 *  3. anchors the newest line to the bottom and grows upward, clipping older
 *     lines off the top — exactly like tailing a real terminal.
 *
 * On the web the data source is a hidden, read-only xterm.js instance that
 * already receives the session's PTY output (the link picker keeps one live
 * per card). xterm.js's buffer exposes the same information the Android
 * emulator does: each row's text plus an `isWrapped` flag that marks a row as
 * the continuation of the previous one. [readLogicalLines] joins those rows
 * back into logical lines (the transcript), and [renderThumbnail] re-wraps and
 * paints them onto an HTML5 canvas.
 *
 * Called by [openTerminalLinkPicker] in [PaneTypeModal]: each preview card gets
 * a [LinkThumbnail] that owns the canvas and re-paints whenever PTY output
 * arrives on its hidden terminal.
 *
 * @see PaneTypeModal
 * @see Terminal
 */
package se.soderbjorn.termtastic

import org.w3c.dom.HTMLCanvasElement

/** Monospace font size (px) used for thumbnail glyphs, matching Android's ~9sp. */
private const val THUMBNAIL_FONT_PX = 9

/** Line height (px) between thumbnail rows (font + small leading). */
private const val THUMBNAIL_LINE_PX = 11

/** Left/right inner padding (px) of the thumbnail content area. */
private const val THUMBNAIL_PAD_X = 4

/** Top/bottom inner padding (px) of the thumbnail content area. */
private const val THUMBNAIL_PAD_Y = 2

/** Approximate width of one monospace glyph at [THUMBNAIL_FONT_PX] (~0.6 em). */
private const val THUMBNAIL_CHAR_PX = 5.4

/**
 * Max logical lines kept/scanned from the buffer, mirroring Android's
 * `MINI_REGISTRY_MAX_LINES` so very long scrollbacks stay cheap to render.
 */
private const val THUMBNAIL_MAX_LINES = 80

/** Monospace font stack matching the live terminal previews. */
internal const val THUMBNAIL_FONT_FAMILY = "Menlo, Monaco, 'Courier New', monospace"

// --- Cross-platform consolidation opportunity --------------------------------
// The terminal-thumbnail pipeline is currently reimplemented once per platform:
//
//   Android  MiniTerminalRegistry.extractRecentLines  (TerminalEmulator.transcriptText)
//            + MiniTerminalPane  (reverseLayout LazyColumn; Compose Text auto-wraps)
//   iOS      MiniTerminalRegistry.extractRecentLines  (SwiftTerm.getBufferAsData)
//            + MiniTerminalPane  (bottom-aligned overlay; SwiftUI Text auto-wraps)
//   Web      readLogicalLines + wrapLine + renderThumbnail  (xterm.js buffer → canvas)
//
// Only the two ends are irreducibly platform-specific: the *buffer read* (three
// unrelated native emulators — termux TerminalEmulator on the JVM, SwiftTerm on
// iOS, xterm.js on JS, none reachable from commonMain) and the *paint* (Compose
// LazyColumn / SwiftUI overlay / HTML5 canvas). The transform in the middle is
// pure and identical in intent, and could live once in `client/commonMain`
// (e.g. an object `TerminalThumbnailModel`) shared by all three:
//
//   1. trimRecentLines(transcript, maxLines): split on '\n', drop trailing blank
//      lines, keep the last N. (All three already do exactly this; see the bodies
//      of the two extractRecentLines functions and readLogicalLines below.)
//   2. rewrapToWidth(logicalLines, cols): hard-wrap each logical line to a column
//      count — what `wrapLine` does here.
//   3. bottomAnchorVisibleRows(rows, maxVisible): keep only the trailing rows that
//      fit — the tail logic inside `renderThumbnail`.
//
// Each platform would feed its emulator's transcript text into (1), then:
//   - Web needs (2) and (3) explicitly, because a canvas has no auto-wrap and no
//     bottom anchor.
//   - Android/iOS get (2)+(3) for free from the UI toolkit (Compose/SwiftUI Text
//     wrap; reverseLayout / bottom overlay anchor), so they would only consume (1).
// iOS is Swift, so it would reach the Kotlin common code through the generated
// `Client` framework — exactly as it already does for `SessionsViewModeStore`.
//
// One divergence to reconcile BEFORE sharing step (2): Android and iOS resize the
// headless emulator to the *real* PTY column count and rely on toolkit wrapping,
// whereas web runs a hidden xterm at a fixed 120 cols and re-wraps to the
// thumbnail's own narrow width via `wrapLine`. Hoisting (2) into common would make
// all three reflow identically (the web behaviour), which is the desired end state
// but a visible behaviour change on mobile — do it deliberately, not incidentally.
// -----------------------------------------------------------------------------

/**
 * Reads the joined logical lines (the transcript) from a hidden xterm.js
 * terminal's active buffer.
 *
 * xterm.js stores the visible screen *plus* scrollback in `buffer.active`,
 * indexed `0 until (baseY + rows)`. Rows that are continuations of a
 * terminal-width-wrapped logical line carry `isWrapped == true`; this function
 * concatenates such rows onto the previous logical line so that a single long
 * command (hard-wrapped to the source terminal's columns) becomes one logical
 * line again — the same "transcript" the Android renderer re-wraps. Trailing
 * blank lines are trimmed and only the last [THUMBNAIL_MAX_LINES] are kept.
 *
 * Called by [LinkThumbnail.repaint] before each paint.
 *
 * @param term the hidden, read-only xterm.js instance fed by the session PTY.
 * @return the trailing logical lines, oldest-to-newest (right-trimmed).
 * @see renderThumbnail
 */
internal fun readLogicalLines(term: Terminal): List<String> {
    val buffer = term.asDynamic().buffer?.active ?: return emptyList()
    val baseY = (buffer.baseY as? Number)?.toInt() ?: 0
    val rows = (term.rows as? Number)?.toInt() ?: 0
    val total = baseY + rows
    if (total <= 0) return emptyList()

    val logical = ArrayList<String>()
    for (y in 0 until total) {
        val line = buffer.getLine(y) ?: continue
        // translateToString(trimRight) gives the row's text; trim trailing
        // blanks so width-padded rows don't bloat every logical line.
        val text = (line.translateToString(true) as? String) ?: ""
        val wrapped = (line.isWrapped as? Boolean) ?: false
        if (wrapped && logical.isNotEmpty()) {
            logical[logical.size - 1] = logical[logical.size - 1] + text
        } else {
            logical.add(text)
        }
    }

    // Trim trailing blank logical lines (a fresh shell screen is mostly empty).
    var end = logical.size
    while (end > 0 && logical[end - 1].isBlank()) end--
    val trimmed = if (end < logical.size) logical.subList(0, end) else logical

    return if (trimmed.size > THUMBNAIL_MAX_LINES) {
        trimmed.subList(trimmed.size - THUMBNAIL_MAX_LINES, trimmed.size).toList()
    } else {
        trimmed.toList()
    }
}

/**
 * Hard-wraps a single logical line to a maximum character width, breaking on
 * the column boundary (terminal-style wrap, like Android's narrow re-wrap).
 *
 * A blank logical line yields a single empty visual row so vertical spacing
 * is preserved.
 *
 * CONSOLIDATION: this is step (2) of the shared pipeline described in the file
 * header — a pure function with no web dependency. It is the prime candidate to
 * move into `client/commonMain` (`TerminalThumbnailModel.rewrapToWidth`); only
 * web currently calls it (canvas has no auto-wrap), but sharing it would let
 * Android/iOS opt into identical reflow.
 *
 * @param line the logical line text.
 * @param cols the maximum number of characters per visual row.
 * @return the visual rows the line occupies, top-to-bottom.
 */
private fun wrapLine(line: String, cols: Int): List<String> {
    if (cols <= 0) return listOf(line)
    if (line.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var i = 0
    while (i < line.length) {
        val endIdx = minOf(i + cols, line.length)
        out.add(line.substring(i, endIdx))
        i = endIdx
    }
    return out
}

/**
 * Renders the given logical lines onto a canvas, word/column-wrapped to the
 * canvas width and bottom-anchored (newest line at the bottom, older lines
 * filling upward and clipping off the top) — the web equivalent of Android's
 * reverse-layout `LazyColumn`.
 *
 * @param canvas the destination canvas (already sized to the card preview).
 * @param logicalLines the transcript lines, oldest-to-newest, from
 *   [readLogicalLines].
 * @param fg foreground (text) CSS color.
 * @param bg background CSS color.
 * @param scale supersampling multiplier applied to every glyph/layout metric
 *   (font, line height, char width, padding). At `1.0` the output matches the
 *   original small-canvas rendering (the link picker's use). The 3D overview
 *   ([Overview3D]) sizes its canvases larger by the same factor and passes a
 *   matching `scale` so the *content density* (columns/rows) is unchanged
 *   while the pixel resolution — and hence text crispness on a large or
 *   high-DPI display — goes up proportionally.
 * @param topAnchored when `false` (default) the newest line sits at the bottom
 *   and older lines fill upward, clipping off the top — the terminal-tail
 *   behaviour the link picker and terminal tiles want. When `true` the first
 *   line sits at the top and later lines fill downward, clipping off the
 *   bottom — right for a file listing or git status, which read top-down. The
 *   3D overview passes `true` for file-browser / git tiles.
 * @param topInset extra space (px, already in device units) left clear at the
 *   top for the opaque title bar the caller paints afterward — reserved for
 *   *both* anchors so terminal content never renders under the header.
 * @param sidePad extra padding (device px) added on the left/right/bottom so
 *   content clears the pane border in the 3D overview (0 for the link picker).
 * @see LinkThumbnail
 */
internal fun renderThumbnail(
    canvas: HTMLCanvasElement,
    logicalLines: List<String>,
    fg: String,
    bg: String,
    scale: Double = 1.0,
    topAnchored: Boolean = false,
    topInset: Double = 0.0,
    sidePad: Double = 0.0,
) {
    // Use a dynamic 2D context so the string-typed canvas state properties
    // (fillStyle, font, textBaseline) assign cleanly under Kotlin/JS.
    val ctx = canvas.getContext("2d") ?: return
    val width = canvas.width
    val height = canvas.height
    if (width <= 0 || height <= 0) return

    // All layout metrics scale together, so a larger canvas at a higher
    // [scale] keeps the same column/row count but more pixels per glyph. The
    // extra [sidePad] (device px) keeps content clear of the pane border in the
    // 3D overview.
    val fontPx = THUMBNAIL_FONT_PX * scale
    val linePx = THUMBNAIL_LINE_PX * scale
    val charPx = THUMBNAIL_CHAR_PX * scale
    val padX = THUMBNAIL_PAD_X * scale + sidePad
    val padY = THUMBNAIL_PAD_Y * scale + sidePad

    val d = ctx.asDynamic()
    d.fillStyle = bg
    d.fillRect(0.0, 0.0, width.toDouble(), height.toDouble())

    val contentWidth = width - padX * 2
    val cols = maxOf(1, (contentWidth / charPx).toInt())

    // Flatten logical lines into visual rows (oldest-to-newest), re-wrapped to
    // the thumbnail's own column width.
    val rows = ArrayList<String>()
    for (line in logicalLines) {
        rows.addAll(wrapLine(line, cols))
    }
    if (rows.isEmpty()) return

    // Reserve the caller's inset at the top for *both* anchors so terminal
    // content never sits under an opaque title bar (the top-anchored listing
    // starts below it; the bottom-anchored tail is clipped so its topmost
    // visible row also clears it).
    val inset = topInset
    val availableHeight = height - padY * 2 - inset
    val maxVisible = maxOf(1, (availableHeight / linePx).toInt())
    // Keep only the rows that fit: the leading rows (top clip off the bottom)
    // when top-anchored, otherwise the trailing rows (like a terminal tail).
    val visible = if (rows.size > maxVisible) {
        if (topAnchored) rows.subList(0, maxVisible) else rows.subList(rows.size - maxVisible, rows.size)
    } else {
        rows
    }

    d.font = "${fontPx}px $THUMBNAIL_FONT_FAMILY"
    d.fillStyle = fg
    d.textBaseline = "top"

    // Top-anchor starts below the inset+padding; bottom-anchor puts the newest
    // visible row at the bottom with older rows filling upward — but never
    // higher than the reserved title-bar inset.
    var y = if (topAnchored) padY + inset else height - padY - visible.size * linePx
    if (y < padY + inset) y = padY + inset
    for (row in visible) {
        d.fillText(row, padX, y)
        y += linePx
    }
}

/**
 * A single link-picker thumbnail: owns the visible canvas and the hidden
 * data-source xterm.js instance, and re-paints the canvas from the terminal's
 * transcript whenever fresh PTY output arrives.
 *
 * The hidden terminal is the same kind of read-only xterm.js the picker
 * previously displayed directly; here it is kept off-screen purely as a
 * faithful ANSI/buffer model, and the visible representation is the
 * re-wrapped, bottom-anchored canvas produced by [renderThumbnail].
 *
 * Created by [openTerminalLinkPicker]; disposed (terminal + repaint coalescing)
 * via [dispose], driven by the picker's existing [disposeAllPreviews] cleanup.
 *
 * @property term the hidden xterm.js model fed by the session PTY.
 * @property canvas the visible thumbnail canvas in the picker card.
 * @property fg foreground CSS color for glyphs.
 * @property bg background CSS color.
 */
internal class LinkThumbnail(
    val term: Terminal,
    val canvas: HTMLCanvasElement,
    private val fg: String,
    private val bg: String,
) {
    private var rafHandle: Int? = null

    /**
     * Schedules a coalesced repaint on the next animation frame. Safe to call
     * for every PTY chunk; rapid bursts collapse to one paint per frame.
     *
     * @see renderThumbnail
     */
    fun scheduleRepaint() {
        if (rafHandle != null) return
        rafHandle = kotlinx.browser.window.requestAnimationFrame {
            rafHandle = null
            repaint()
        }
    }

    /**
     * Reads the current transcript and paints it onto the canvas immediately.
     *
     * @see readLogicalLines
     * @see renderThumbnail
     */
    fun repaint() {
        val lines = runCatching { readLogicalLines(term) }.getOrDefault(emptyList())
        renderThumbnail(canvas, lines, fg, bg)
    }

    /**
     * Cancels any pending repaint and disposes the hidden terminal.
     */
    fun dispose() {
        rafHandle?.let { kotlinx.browser.window.cancelAnimationFrame(it) }
        rafHandle = null
        runCatching { term.dispose() }
    }
}
