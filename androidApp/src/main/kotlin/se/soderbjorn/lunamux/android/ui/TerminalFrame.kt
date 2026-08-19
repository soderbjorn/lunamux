/**
 * Immutable, style-resolved snapshot of a live terminal screen, plus the
 * extraction that produces one from a headless Termux emulator.
 *
 * A [TerminalFrame] is what a terminal thumbnail draws: the current screen
 * buffer (never scrollback) as rows of same-style runs whose colors are
 * already resolved to ARGB — palette lookups, bright-bold, inverse video and
 * dim are applied here, at snapshot time, so the renderer is a dumb painter.
 * Snapshots are deep-immutable and safe to hand across threads; the mutable
 * emulator is only touched inside [snapshotFrame], which the caller must run
 * on the emulator's own dispatcher under its lock (see [MiniTerminalRegistry]).
 *
 * The run extraction follows the server's `GridSerializer.rowRuns` rules
 * (split when the packed style long changes, keep surrogates/combining marks/
 * wide glyphs intact, trim trailing default-styled blanks) but not its code:
 * the server asks `findStartOfColumn` per cell, which restarts from column 0
 * on every call, while this walk carries a running char index — a snapshot
 * runs at up to 10 fps per visible session here, so the per-row cost has to be
 * linear. The color resolution is a port of the vendored
 * `TerminalRenderer.drawTextRun` (terminal-view) including DECSCNM reverse
 * video — ported rather than referenced because that code is welded to its
 * Canvas pass and vendored files stay unmodified.
 *
 * @see MiniTerminalRegistry
 * @see TerminalThumbnail
 */
package se.soderbjorn.lunamux.android.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * A run of consecutive same-style cells within one screen row.
 *
 * Produced by [snapshotFrame]; drawn by [TerminalThumbnail]. Colors are fully
 * resolved ARGB — no palette indices survive past extraction.
 *
 * @property startCol  0-based grid column the run starts at (background rects
 *   and text are both anchored here, so grid alignment survives any glyph
 *   advance drift inside the run).
 * @property widthCols number of grid cells the run covers (wide glyphs count
 *   their real cell width).
 * @property text      the run's characters; empty for an SGR-invisible run,
 *   whose background still paints.
 * @property fg        resolved ARGB foreground (bright-bold, inverse and dim
 *   already applied).
 * @property bg        resolved ARGB background (inverse already applied).
 * @property bold          whether to draw with fake-bold.
 * @property underline     whether to underline.
 * @property italic        whether to skew the glyphs (SGR 3).
 * @property strikethrough whether to strike the glyphs through (SGR 9).
 */
data class ThumbRun(
    val startCol: Int,
    val widthCols: Int,
    val text: String,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val underline: Boolean,
    val italic: Boolean,
    val strikethrough: Boolean,
)

/**
 * One immutable snapshot of a live terminal screen (current buffer only — for
 * an alt-screen TUI this is the alt screen, which is exactly what the session
 * looks like).
 *
 * @property cols        the grid width the frame was captured at (the server's
 *   authoritative PTY width — a thumbnail never votes its own).
 * @property rows        the grid height the frame was captured at.
 * @property defaultBg   resolved ARGB default background; fills the letterbox
 *   and every cell no run covers (the default *foreground* when the session
 *   has DECSCNM reverse video on, mirroring the real renderer's canvas fill).
 * @property lines       exactly [rows] entries; each row's runs are ordered by
 *   [ThumbRun.startCol] with trailing default-styled blanks trimmed (an empty
 *   list is a blank row).
 * @property cursorRow   cursor row, or -1 when the cursor is hidden (DECTCEM).
 * @property cursorCol   cursor column (meaningless when [cursorRow] is -1).
 * @property cursorColor resolved ARGB cursor color.
 * @property revision    monotonic per-session counter; makes [equals] cheap for
 *   StateFlow conflation and stamps frames for debugging.
 */
data class TerminalFrame(
    val cols: Int,
    val rows: Int,
    val defaultBg: Int,
    val lines: List<List<ThumbRun>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorColor: Int,
    val revision: Long,
) {
    /**
     * Cheap equality via [revision]: two frames published by one session's
     * publisher never share a revision, so identity of stamp implies identity
     * of content — which is what the frame [kotlinx.coroutines.flow.StateFlow]
     * conflates on.
     *
     * Valid only *within* one publisher's stream. Revisions restart at 0 for a
     * rebuilt registry entry and two sessions number theirs independently, so
     * never compare frames across sessions or registry generations (no such
     * comparison exists today — see [MiniTerminalRegistry.frameFor], which
     * hands every generation its own flow).
     *
     * @param other the value to compare against.
     * @return true when [other] is a frame with the same revision and grid size.
     */
    override fun equals(other: Any?): Boolean =
        other is TerminalFrame && other.revision == revision && other.cols == cols && other.rows == rows

    /**
     * Hash consistent with [equals] — the revision alone, for the same reason.
     *
     * @return the revision's hash.
     */
    override fun hashCode(): Int = revision.hashCode()
}

/**
 * Snapshot [emulator]'s current screen into an immutable [TerminalFrame].
 *
 * MUST be called on the emulator's single-thread dispatcher while holding the
 * emulator lock — it reads live rows that the PTY event collector mutates.
 * Called only by [MiniTerminalRegistry]'s per-entry publisher.
 *
 * @param emulator the headless, externally-fed emulator to read.
 * @param revision monotonic stamp for the produced frame.
 * @return the resolved snapshot; rows out of bounds degrade to blank rather
 *   than throwing (a read-only observer must never take the preview down).
 */
internal fun snapshotFrame(emulator: TerminalEmulator, revision: Long): TerminalFrame {
    val palette = emulator.mColors.mCurrentColors
    // DECSCNM (`ESC [ ?5h`, replayed by the server's attach epilogue) is a
    // whole-screen fg/bg swap. The vendored renderer applies it by filling the
    // canvas with the default *foreground* and flipping every run's inverse
    // bit (TerminalRenderer.drawText/drawTextRun); the snapshot does the same,
    // or a reverse-video session thumbnails as its own negative.
    val reverseVideo = emulator.isReverseVideo
    val defaultBg =
        palette[if (reverseVideo) TextStyle.COLOR_INDEX_FOREGROUND else TextStyle.COLOR_INDEX_BACKGROUND]
    val rows = emulator.mRows
    val cols = emulator.mColumns
    val screen = emulator.screen
    val lines = ArrayList<List<ThumbRun>>(rows)
    for (y in 0 until rows) {
        val row = runCatching { screen.getRow(y) }.getOrNull()
        lines.add(if (row == null) emptyList() else rowRuns(row, cols, palette, reverseVideo))
    }
    val cursorVisible = emulator.shouldCursorBeVisible()
    return TerminalFrame(
        cols = cols,
        rows = rows,
        defaultBg = defaultBg,
        lines = lines,
        cursorRow = if (cursorVisible) emulator.cursorRow else -1,
        cursorCol = emulator.cursorCol,
        cursorColor = palette[TextStyle.COLOR_INDEX_CURSOR],
        revision = revision,
    )
}

/**
 * Convert one screen row into resolved [ThumbRun]s in a single left-to-right
 * pass: split when the packed style changes, and emit each run only up to its
 * last content cell so trailing default-styled blanks (padding) are dropped. A
 * styled trailing space — a colored status bar, say — is content: a
 * non-default style makes every cell in the run count.
 *
 * The walk carries a running char index instead of calling
 * `TerminalRow.findStartOfColumn` per cell. That lookup restarts from column 0
 * on every call, which made a row O(cols²) — millions of codepoint-width steps
 * per second across the overview at 10 fps and 200+ server columns, all while
 * holding the emulator lock the PTY collector needs. The vendored
 * `TerminalRenderer`'s own row loop keeps the same running index.
 *
 * @param row          the live row to read.
 * @param cols         the emulator's width, clamped to what the row actually has
 *   (a mid-reflow row can be narrower; degrade to a short row, never throw).
 * @param palette      the emulator's current 256+3 color table.
 * @param reverseVideo whether DECSCNM is set, flipping every run's inverse bit.
 * @return the row's runs, empty for a blank row.
 */
private fun rowRuns(row: TerminalRow, cols: Int, palette: IntArray, reverseVideo: Boolean): List<ThumbRun> {
    val width = minOf(cols, row.columnCount)
    if (width <= 0) return emptyList()
    val text = row.mText
    val charLimit = row.spaceUsed
    val runs = ArrayList<ThumbRun>()
    val sb = StringBuilder()
    var runStyle = row.getStyle(0)
    var runIsDefaultStyle = isDefaultStyle(runStyle)
    var runStartCol = 0
    // The current run's last content cell: its exclusive end column and the
    // matching prefix length of [sb]. Both stay 0 for an all-blank run, which
    // is then never emitted.
    var contentEndCol = 0
    var contentLen = 0
    var col = 0
    var charIndex = 0
    while (col < width && charIndex < charLimit) {
        val style = row.getStyle(col)
        if (style != runStyle) {
            if (contentLen > 0) {
                runs.add(resolveRun(runStartCol, contentEndCol - runStartCol, sb.substring(0, contentLen), runStyle, palette, reverseVideo))
            }
            sb.setLength(0)
            contentLen = 0
            contentEndCol = 0
            runStartCol = col
            runStyle = style
            runIsDefaultStyle = isDefaultStyle(style)
        }
        // One cell: its code point plus the zero-width combining marks that
        // follow it, so surrogate pairs and marks stay welded to their glyph
        // (and a mark is never read as the next cell's content).
        val cellStart = charIndex
        val first = text[charIndex]
        val isHighSurrogate = Character.isHighSurrogate(first)
        val cp = if (isHighSurrogate) Character.toCodePoint(first, text[charIndex + 1]) else first.code
        charIndex += if (isHighSurrogate) 2 else 1
        while (charIndex < charLimit && WcWidth.width(text, charIndex) <= 0) {
            charIndex += if (Character.isHighSurrogate(text[charIndex])) 2 else 1
        }
        var w = WcWidth.width(cp)
        if (w < 1) w = 1
        sb.append(text, cellStart, charIndex - cellStart)
        col += w
        if (cp != ' '.code || !runIsDefaultStyle) {
            contentEndCol = col
            contentLen = sb.length
        }
    }
    if (contentLen > 0) {
        runs.add(resolveRun(runStartCol, contentEndCol - runStartCol, sb.substring(0, contentLen), runStyle, palette, reverseVideo))
    }
    return runs
}

/**
 * Resolve one run's packed style long into a [ThumbRun] with final ARGB
 * colors. Port of the color pipeline in `TerminalRenderer.drawTextRun`
 * (terminal-view): indexed → palette with bright-bold promotion, inverse
 * fg/bg swap (SGR 7 xor DECSCNM, as the renderer's `reverseVideoHere`), xterm
 * dim (×2/3 RGB), invisible → empty text, and the bold/underline/italic/
 * strikethrough flags the painter applies verbatim.
 *
 * @param startCol     grid column the run starts at.
 * @param widthCols    grid cells the run covers.
 * @param text         the run's characters.
 * @param style        the packed Termux style long shared by every cell in the run.
 * @param palette      the emulator's current color table.
 * @param reverseVideo whether DECSCNM is set, flipping the run's inverse bit.
 * @return the resolved run.
 */
private fun resolveRun(
    startCol: Int,
    widthCols: Int,
    text: String,
    style: Long,
    palette: IntArray,
    reverseVideo: Boolean,
): ThumbRun {
    var fg = TextStyle.decodeForeColor(style)
    var bg = TextStyle.decodeBackColor(style)
    val effect = TextStyle.decodeEffect(style)
    val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
    val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
    val italic = (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0
    val strikethrough = (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0
    val invisible = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0
    if ((fg and -0x1000000) != -0x1000000) {
        // Indexed color; bold promotes the first 8 to their bright variants.
        if (bold && fg in 0..7) fg += 8
        fg = palette[fg]
    }
    if ((bg and -0x1000000) != -0x1000000) {
        bg = palette[bg]
    }
    if (((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) xor reverseVideo) {
        val tmp = fg
        fg = bg
        bg = tmp
    }
    if ((effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
        // xterm/libvte dim: scale each channel to 2/3.
        val red = (fg shr 16 and 0xFF) * 2 / 3
        val green = (fg shr 8 and 0xFF) * 2 / 3
        val blue = (fg and 0xFF) * 2 / 3
        fg = -0x1000000 + (red shl 16) + (green shl 8) + blue
    }
    return ThumbRun(
        startCol = startCol,
        widthCols = widthCols,
        text = if (invisible) "" else text,
        fg = fg,
        bg = bg,
        bold = bold,
        underline = underline,
        italic = italic,
        strikethrough = strikethrough,
    )
}

/** Whether [style] is the default style (default fg/bg indices, no effects). */
private fun isDefaultStyle(style: Long): Boolean =
    TextStyle.decodeForeColor(style) == TextStyle.COLOR_INDEX_FOREGROUND &&
        TextStyle.decodeBackColor(style) == TextStyle.COLOR_INDEX_BACKGROUND &&
        TextStyle.decodeEffect(style) == 0
