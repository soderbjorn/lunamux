/**
 * Serializes a canonical server-side [TerminalEmulator] grid into a self-contained
 * byte stream that reconstructs it cell-for-cell when fed to a fresh emulator (or
 * a client terminal) of the same width.
 *
 * This file contains [GridSerializer], the core of the server-authoritative-screen
 * model. Instead of replaying the raw PTY byte ring — which is bound to the width
 * it was authored at and mangles when reinterpreted at another width — the server
 * reads its interpreted grid and *synthesizes* a redraw. Because the redraw is
 * constructed paint (RIS-prefixed, styled SGR runs, wrap-faithful line flow, an
 * explicit cursor/mode epilogue) it is width-correct by construction and contains
 * no device queries, so it is indistinguishable from ordinary output to any client
 * and carries no re-answer hazard.
 *
 * Two forms:
 *  - [serialize] — the attach/resync form: full mode + cursor + title epilogue so a
 *    live client resumes exactly where the session is.
 *  - [serializeForPersist] — the killed-server-restore form: scrollback plus, when a
 *    TUI holds the alternate buffer, an inert frozen frame; deliberately no mode
 *    epilogue and no alternate-buffer switch, so a dead session cannot resurrect
 *    mouse/paste/focus modes on restore (issue #91).
 *
 * The fidelity contract is pinned by GridSerializerRoundTripTest: serialize → feed a
 * fresh emulator → assert cell-identical (chars, styles, wrap flags, cursor, modes,
 * margins, tabs, title, palette), plus a fixpoint (re-serializing the copy is
 * byte-identical).
 *
 * @see SessionGrid the wrapper that owns the emulator and calls this
 * @see com.termux.terminal.TerminalEmulator the emulator whose state is read
 */
package se.soderbjorn.lunamux.pty

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

object GridSerializer {

    private const val ESC = "\u001b"
    private const val CSI = "\u001b["
    private const val OSC = "\u001b]"
    private const val BEL = "\u0007"

    /** Top byte set (0xFF000000) marks a 24-bit color; otherwise the value is a palette index. */
    private const val TRUECOLOR_MASK = -0x1000000

    /** Palette a fresh emulator starts with — the diff baseline for [emitPaletteDiffs]. */
    private val DEFAULT_COLORS: IntArray = TerminalColors().mCurrentColors.clone()

    /**
     * Serialize [e] as an attach/resync redraw: a fresh terminal fed these bytes at
     * the same width ends up cell-identical, with the same cursor, modes, margins,
     * tab stops, palette and title.
     *
     * Called (via [SessionGrid.synthesizeRedraw]) when a client attaches or when a
     * cols change forces a broadcast resync.
     *
     * @param e the source emulator (caller holds the grid monitor).
     * @return UTF-8 bytes: RIS + ED3, styled row flow, then the state epilogue.
     */
    fun serialize(e: TerminalEmulator, history: List<LogicalLine> = emptyList()): ByteArray {
        val sb = StringBuilder(4096)
        sb.append(ESC).append("c")   // RIS — reset modes/screen/cursor
        sb.append(CSI).append("3J")  // ED3 — clear scrollback
        // History first, unwrapped: the receiver re-wraps it at its own width. Then the
        // live screen, which the emulator has already laid out at this grid.
        emitHistory(sb, history)
        // History and the live screen are ONE continuous line stream. The screen flow must
        // not home first when history precedes it: homing would paint the screen over the
        // tail of the history just emitted, so those lines would never scroll off into the
        // receiver's own log and both halves would be short.
        val homeFirst = history.isEmpty()
        if (!e.isAlternateBufferActive) {
            emitBufferFlow(sb, e.mainBuffer, e.mColumns, e.mRows, includeTranscript = true, homeFirst = homeFirst)
        } else {
            // Scrollback-under-a-live-TUI: paint the normal buffer's screen, enter the
            // alternate buffer, then paint the live alt rows. Mirrors what a reconnecting
            // client should see — real scrollback behind the running TUI.
            emitBufferFlow(sb, e.mainBuffer, e.mColumns, e.mRows, includeTranscript = true, homeFirst = homeFirst)
            sb.append(CSI).append("?1049h")
            emitBufferFlow(sb, e.altBuffer, e.mColumns, e.mRows, includeTranscript = false)
        }
        emitEpilogue(sb, e)
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Serialize [e] for persistence across a server restart: normal-buffer scrollback
     * as styled text, plus — only when a TUI holds the alternate buffer — an inert
     * frozen image of that frame appended below. Deliberately omits the cursor/mode
     * epilogue and never enters the alternate buffer, so restoring a dead session
     * cannot re-enable modes it left set (issue #91). Fed into a fresh grid at the
     * recorded width on restore.
     *
     * @param e the source emulator (caller holds the grid monitor).
     * @return UTF-8 bytes safe to store and later replay into a fresh grid.
     */
    fun serializeForPersist(e: TerminalEmulator, history: List<LogicalLine> = emptyList()): ByteArray {
        val sb = StringBuilder(4096)
        sb.append(ESC).append("c")
        sb.append(CSI).append("3J")
        emitHistory(sb, history)
        emitBufferFlow(
            sb, e.mainBuffer, e.mColumns, e.mRows,
            includeTranscript = true,
            // Persist mode: no cursor epilogue follows (see the kdoc), so the flow
            // must not leave the cursor stranded below the content it painted, and
            // the live prompt line must not be committed to history.
            persistCursorRow = e.cursorRow,
        )
        if (e.isAlternateBufferActive) {
            sb.append("\r\n")
            emitAltFrameInert(sb, e.altBuffer, e.mColumns, e.mRows)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Convert one screen row into the styled runs [HistoryLog] stores.
     *
     * Called from [SessionGrid]'s row-eviction hook, synchronously, because the emulator
     * recycles the row as soon as the hook returns.
     *
     * A wrapped row is taken in full — every column is real content, and the logical line
     * continues into the next row. An unwrapped row is trimmed of trailing default-styled
     * blanks, which are padding rather than content; a styled trailing space (a coloured
     * status bar) is kept, the same rule [lastContentColumn] applies everywhere else.
     *
     * @param row the row leaving the screen.
     * @param cols the grid width the row was authored at.
     * @param wrapped whether the row soft-wraps into the next.
     * @return the row's styled runs, empty for a blank row.
     */
    fun rowRuns(row: TerminalRow, cols: Int, wrapped: Boolean): List<StyledRun> {
        val lastCol = if (wrapped) cols - 1 else lastContentColumn(row, cols)
        if (lastCol < 0) return emptyList()
        val runs = mutableListOf<StyledRun>()
        val sb = StringBuilder()
        var runStyle = row.getStyle(0)
        var col = 0
        while (col <= lastCol) {
            val style = row.getStyle(col)
            if (style != runStyle && sb.isNotEmpty()) {
                runs.add(StyledRun(sb.toString(), runStyle))
                sb.setLength(0)
            }
            runStyle = style
            col += emitCell(sb, row, col, cols)
        }
        if (sb.isNotEmpty()) runs.add(StyledRun(sb.toString(), runStyle))
        return runs
    }

    /**
     * Emit committed history ahead of the live screen.
     *
     * Each logical line is emitted as its styled runs followed by CRLF, with no wrapping
     * computed here at all: the receiving terminal wraps at its own width, which is
     * exactly what makes one stored history correct for clients of different widths.
     *
     * @param sb the redraw being built.
     * @param lines the committed logical lines, oldest first.
     */
    private fun emitHistory(sb: StringBuilder, lines: List<LogicalLine>) {
        for (line in lines) {
            for (run in line.runs) {
                emitSgrForStyle(sb, run.style)
                sb.append(run.text)
            }
            sb.append("\r\n")
        }
    }

    // ── Row flow ──────────────────────────────────────────────────────────────

    /**
     * Emit the buffer's rows from the top of the transcript to the bottom of the
     * screen. Non-wrapped rows are CRLF-terminated (trailing default-styled blanks
     * trimmed); a wrapped row emits all [cols] cells with no CRLF, so the receiver's
     * deferred autowrap reconstructs the soft wrap exactly where it was — the reason
     * no serializer-side rewrap is ever needed. The final row gets no CRLF; the
     * epilogue positions the cursor.
     */
    private fun emitBufferFlow(
        sb: StringBuilder,
        buffer: TerminalBuffer,
        cols: Int,
        screenRows: Int,
        includeTranscript: Boolean,
        persistCursorRow: Int? = null,
        homeFirst: Boolean = true,
    ) {
        // Home before drawing so the flow is deterministic — unless history was just
        // emitted, in which case this flow continues that stream and must not rewind over it.
        if (homeFirst) sb.append(CSI).append("H")
        val transcript = if (includeTranscript) buffer.activeTranscriptRows else 0
        var lastRow = screenRows - 1
        var firstRow = -transcript
        if (persistCursorRow != null) {
            lastRow = lastNonBlankRow(buffer, cols, screenRows, transcript)
            // Drop the live line. The cursor's row holds the shell's prompt plus
            // anything typed but not yet entered — transient screen state, not
            // committed history — and the shell spawned on restore re-emits its own
            // prompt regardless, so persisting this row guarantees a duplicate.
            // Persist committed lines only; the restored session then reads as
            // history followed by exactly one fresh prompt.
            if (lastRow == persistCursorRow) lastRow--
            // Skip leading blank rows too, for the same reason the trailing ones are
            // skipped: with no cursor epilogue they are emitted as bare CRLFs, so a
            // grid whose top rows happen to be empty restored as blank lines pushed
            // above the first real content.
            firstRow = firstNonBlankRow(buffer, cols, lastRow, transcript)
        }
        var y = firstRow
        while (y <= lastRow) {
            val wrapped = emitRow(sb, buffer, y, cols)
            if (y != lastRow && !wrapped) sb.append("\r\n")
            y++
        }
    }

    /**
     * The last row holding any content, searching up from the bottom of the screen;
     * `-transcript - 1` when every row is blank (so the caller emits nothing).
     *
     * Used only by [serializeForPersist]. The attach form must emit the screen in
     * full — its epilogue restores the cursor afterwards, so trailing blanks are
     * faithful screen content — but the persist form deliberately carries no cursor
     * epilogue (issue #91), so whatever the flow emits last is where the cursor is
     * left. Emitting the trailing blanks parked it at the bottom of a screenful of
     * empty rows, and the shell spawned on restore then printed its first prompt
     * there, far below the restored content: a stacked prompt with a large gap above
     * it, growing by one on every restore.
     */
    /**
     * The first row holding any content, searching down from the top of the
     * transcript; [lastRow] + 1 when every row up to it is blank.
     *
     * Persist-only, and the mirror of [lastNonBlankRow] — leading blank rows would
     * otherwise be emitted as bare CRLFs and restore as empty lines above the first
     * real content.
     */
    private fun firstNonBlankRow(
        buffer: TerminalBuffer,
        cols: Int,
        lastRow: Int,
        transcript: Int,
    ): Int {
        var y = -transcript
        while (y <= lastRow) {
            val row = buffer.getLineOrNull(y)
            if (row != null && lastContentColumn(row, cols) >= 0) return y
            y++
        }
        return lastRow + 1
    }

    private fun lastNonBlankRow(
        buffer: TerminalBuffer,
        cols: Int,
        screenRows: Int,
        transcript: Int,
    ): Int {
        var y = screenRows - 1
        while (y >= -transcript) {
            val row = buffer.getLineOrNull(y)
            if (row != null && lastContentColumn(row, cols) >= 0) return y
            y--
        }
        return -transcript - 1
    }

    /**
     * Emit one external row [y] as SGR runs + text. Returns whether the row is soft-
     * wrapped (so the caller suppresses the CRLF). A null (never-written) row and a
     * fully default-blank row emit nothing.
     */
    private fun emitRow(sb: StringBuilder, buffer: TerminalBuffer, y: Int, cols: Int): Boolean {
        val row = buffer.getLineOrNull(y)
        val wrapped = row != null && buffer.getLineWrap(y)
        val lastCol = when {
            row == null -> -1
            wrapped -> cols - 1            // wrapped: emit every column verbatim
            else -> lastContentColumn(row, cols)
        }
        if (row == null || lastCol < 0) return wrapped

        var col = 0
        var haveStyle = false
        var curStyle = 0L
        while (col <= lastCol) {
            val style = row.getStyle(col)
            if (!haveStyle || style != curStyle) {
                emitSgrForStyle(sb, style)
                curStyle = style
                haveStyle = true
            }
            col += emitCell(sb, row, col, cols)
        }
        return wrapped
    }

    /**
     * Append the character(s) occupying column [col] (base glyph plus any combining
     * marks; a whole wide glyph in one go) and return its display width so the caller
     * can advance past a wide cell's trailing column.
     */
    private fun emitCell(sb: StringBuilder, row: TerminalRow, col: Int, cols: Int): Int {
        val text = row.mText
        val start = row.findStartOfColumn(col)
        val cp = Character.codePointAt(text, start)
        var w = WcWidth.width(cp)
        if (w < 1) w = 1
        val end = row.findStartOfColumn(minOf(col + w, cols))
        val len = end - start
        if (len <= 0) {
            sb.append(' ')
        } else {
            sb.append(String(text, start, len))
        }
        return w
    }

    /** Last column (0-based) that is not a default-styled space; -1 if the row is entirely blank. */
    private fun lastContentColumn(row: TerminalRow, cols: Int): Int {
        var c = cols - 1
        while (c >= 0) {
            val cp = Character.codePointAt(row.mText, row.findStartOfColumn(c))
            if (cp != ' '.code || !isDefaultStyle(row.getStyle(c))) return c
            c--
        }
        return -1
    }

    private fun isDefaultStyle(style: Long): Boolean =
        TextStyle.decodeForeColor(style) == TextStyle.COLOR_INDEX_FOREGROUND &&
            TextStyle.decodeBackColor(style) == TextStyle.COLOR_INDEX_BACKGROUND &&
            TextStyle.decodeEffect(style) == 0

    // ── SGR / color encoding ────────────────────────────────────────────────────

    private fun emitSgrForStyle(sb: StringBuilder, style: Long) {
        emitSgr(
            sb,
            TextStyle.decodeForeColor(style),
            TextStyle.decodeBackColor(style),
            TextStyle.decodeEffect(style),
        )
    }

    /**
     * Emit an absolute SGR run (`ESC[0;…m`): a full reset then the attributes and
     * colors for this style. Absolute rather than differential because runs are
     * pasted into a terminal whose attribute state we don't want to track. The
     * PROTECTED attribute is set via DECSCA (not an SGR) after the reset.
     */
    private fun emitSgr(sb: StringBuilder, fore: Int, back: Int, effect: Int) {
        sb.append(CSI).append("0")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) sb.append(";1")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) sb.append(";2")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) sb.append(";3")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0) sb.append(";4")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_BLINK != 0) sb.append(";5")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) sb.append(";7")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) sb.append(";8")
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0) sb.append(";9")
        appendColor(sb, fore, foreground = true)
        appendColor(sb, back, foreground = false)
        sb.append("m")
        // SGR 0 above already cleared PROTECTED; re-arm it via DECSCA if set.
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED != 0) sb.append(CSI).append("1\"q")
    }

    /**
     * Append the SGR color parameters for [color] (indexed or 24-bit). The default
     * foreground (256) / background (257) need nothing — the leading `0` already
     * selected them.
     */
    private fun appendColor(sb: StringBuilder, color: Int, foreground: Boolean) {
        if ((color and TRUECOLOR_MASK) == TRUECOLOR_MASK) {
            val r = (color shr 16) and 0xff
            val g = (color shr 8) and 0xff
            val b = color and 0xff
            sb.append(if (foreground) ";38;2;" else ";48;2;").append(r).append(";").append(g).append(";").append(b)
            return
        }
        when (color) {
            in 0..7 -> sb.append(";").append((if (foreground) 30 else 40) + color)
            in 8..15 -> sb.append(";").append((if (foreground) 90 else 100) + (color - 8))
            in 16..255 -> sb.append(if (foreground) ";38;5;" else ";48;5;").append(color)
            // 256/257 (default fore/back) and 258 (cursor) → nothing to emit.
        }
    }

    // ── Epilogue ──────────────────────────────────────────────────────────────

    /**
     * Emit the state that the row flow can't carry, in an order where each step's
     * side effects are absorbed by a later one: palette → tab stops → scroll margins
     * (DECSTBM homes the cursor) → origin mode + cursor position (+ deferred-autowrap
     * re-arm) → current SGR → DEC private modes → title.
     */
    private fun emitEpilogue(sb: StringBuilder, e: TerminalEmulator) {
        val cols = e.mColumns
        val rows = e.mRows

        emitPaletteDiffs(sb, e.mColors)
        emitTabStops(sb, e.tabStops)

        val leftRight = e.isLeftRightMarginModeEnabled
        val top = e.topMargin
        val bottom = e.bottomMargin
        if (leftRight) sb.append(CSI).append("?69h")
        if (top != 0 || bottom != rows) sb.append(CSI).append(top + 1).append(";").append(bottom).append("r")
        if (leftRight) {
            val left = e.leftMargin
            val right = e.rightMargin
            if (left != 0 || right != cols) sb.append(CSI).append(left + 1).append(";").append(right).append("s")
        }

        val originMode = e.isOriginMode
        if (originMode) sb.append(CSI).append("?6h")

        val cr = e.cursorRow
        val cc = e.cursorCol
        val leftM = if (leftRight) e.leftMargin else 0
        val cupRow = if (originMode) cr - top + 1 else cr + 1
        val cupCol = if (originMode) cc - leftM + 1 else cc + 1
        sb.append(CSI).append(cupRow).append(";").append(cupCol).append("H")

        // Deferred autowrap: the source cursor is parked in the last column with a
        // pending wrap. CUP cleared that; re-arm it by rewriting the last cell's char.
        if (e.isAboutToAutoWrap) {
            val row = e.screen.getLineOrNull(cr)
            if (row != null) {
                emitSgrForStyle(sb, row.getStyle(cols - 1))
                emitCell(sb, row, cols - 1, cols)
            }
        }

        // Current pen for subsequent program output.
        emitSgr(sb, e.currentForeColor, e.currentBackColor, e.currentEffect)

        // DEC private modes. Defaults after RIS: autowrap ON, cursor ON — so emit only
        // the enables and the two disables.
        if (e.isCursorKeysApplicationMode) sb.append(CSI).append("?1h")
        if (e.isKeypadApplicationMode) sb.append(ESC).append("=")
        if (e.isReverseVideo) sb.append(CSI).append("?5h")
        if (!e.isAutoWrapEnabled) sb.append(CSI).append("?7l")
        if (!e.isCursorEnabled) sb.append(CSI).append("?25l")
        if (e.isMouseTrackingPressRelease) sb.append(CSI).append("?1000h")
        if (e.isMouseTrackingButtonEvent) sb.append(CSI).append("?1002h")
        if (e.isMouseProtocolSgr) sb.append(CSI).append("?1006h")
        if (e.isFocusEventsEnabled) sb.append(CSI).append("?1004h")
        if (e.isBracketedPasteMode) sb.append(CSI).append("?2004h")

        val title = e.title
        if (title != null) sb.append(OSC).append("2;").append(title).append(BEL)
    }

    /** Emit OSC palette overrides for every entry that differs from the fresh default. */
    private fun emitPaletteDiffs(sb: StringBuilder, colors: TerminalColors) {
        val cur = colors.mCurrentColors
        for (i in 0..255) {
            if (cur[i] != DEFAULT_COLORS[i]) sb.append(OSC).append("4;").append(i).append(";").append(hexColor(cur[i])).append(BEL)
        }
        if (cur[TextStyle.COLOR_INDEX_FOREGROUND] != DEFAULT_COLORS[TextStyle.COLOR_INDEX_FOREGROUND])
            sb.append(OSC).append("10;").append(hexColor(cur[TextStyle.COLOR_INDEX_FOREGROUND])).append(BEL)
        if (cur[TextStyle.COLOR_INDEX_BACKGROUND] != DEFAULT_COLORS[TextStyle.COLOR_INDEX_BACKGROUND])
            sb.append(OSC).append("11;").append(hexColor(cur[TextStyle.COLOR_INDEX_BACKGROUND])).append(BEL)
        if (cur[TextStyle.COLOR_INDEX_CURSOR] != DEFAULT_COLORS[TextStyle.COLOR_INDEX_CURSOR])
            sb.append(OSC).append("12;").append(hexColor(cur[TextStyle.COLOR_INDEX_CURSOR])).append(BEL)
    }

    private fun hexColor(argb: Int): String {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return "#%02x%02x%02x".format(r, g, b)
    }

    /**
     * Reproduce the tab stops: clear all (TBC 3), then set each by moving the cursor
     * to that column and emitting HTS. Placed before cursor positioning, so the
     * cursor churn here is overwritten by the later CUP.
     */
    private fun emitTabStops(sb: StringBuilder, tabStops: BooleanArray) {
        sb.append(CSI).append("3g")
        for (col in tabStops.indices) {
            if (tabStops[col]) {
                sb.append(CSI).append("1;").append(col + 1).append("H")
                sb.append(ESC).append("H")
            }
        }
    }

    /**
     * Append the alternate buffer's visible rows as inert styled text (no cursor, no
     * modes, no buffer switch), trimming trailing blank rows. Used only by
     * [serializeForPersist] to freeze a dead TUI's last frame below the scrollback.
     */
    private fun emitAltFrameInert(sb: StringBuilder, buffer: TerminalBuffer, cols: Int, rows: Int) {
        var last = rows - 1
        while (last >= 0) {
            val row = buffer.getLineOrNull(last)
            if (row != null && lastContentColumn(row, cols) >= 0) break
            last--
        }
        for (y in 0..last) {
            if (y > 0) sb.append("\r\n")
            emitRow(sb, buffer, y, cols)
        }
        sb.append(CSI).append("0m")
    }
}
