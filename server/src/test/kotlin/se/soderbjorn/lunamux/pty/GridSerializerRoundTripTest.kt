/**
 * The fidelity backbone for [GridSerializer]: for each scripted grid state, serialize
 * the source emulator, feed the bytes to a fresh emulator of the same width, and
 * assert the two are identical — committed history, then the live screen cell for cell:
 * chars, styles, wrap flags, cursor + pending-wrap, alternate-buffer state, every DEC private mode,
 * scroll margins, tab stops, title, current SGR pen and palette. Also asserts the
 * fixpoint: re-serializing the reconstructed grid yields byte-identical output.
 *
 * If any script fails, the serializer — not the test — is wrong; this is the contract
 * that lets the wire path trust a synthesized redraw.
 */
package se.soderbjorn.lunamux.pty

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridSerializerRoundTripTest {

    private val esc = "\u001b"

    private fun SessionGrid.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /**
     * Run the full round-trip + fixpoint assertion for one script.
     *
     * @param resizeTo when non-null, resize the source grid to these dims after the
     *   script (before serializing) to exercise the emulator's reflow.
     */
    private fun roundTrip(
        name: String,
        cols: Int = 40,
        rows: Int = 8,
        resizeTo: Pair<Int, Int>? = null,
        script: SessionGrid.() -> Unit,
    ) {
        val src = SessionGrid(cols, rows)
        src.script()
        val effectiveCols = resizeTo?.first ?: cols
        val effectiveRows = resizeTo?.second ?: rows
        if (resizeTo != null) src.resize(effectiveCols, effectiveRows)

        // The full redraw, history included: a session is its committed logical lines plus
        // its live screen, and a round trip that dropped the first half would only be
        // testing the second.
        val bytes = src.synthesizeRedraw()

        val dst = SessionGrid(effectiveCols, effectiveRows)
        dst.feed(bytes, bytes.size)

        src.read { s -> dst.read { d -> assertGridsEqual(s, d, name) } }
        assertEquals(src.historyLines(), dst.historyLines(), "$name: history")

        val bytes2 = dst.synthesizeRedraw()
        assertContentEquals(bytes, bytes2, "$name: serialize is a fixpoint")
    }

    // ── Scripts ────────────────────────────────────────────────────────────────

    @Test fun plainText() = roundTrip("plain") {
        feed("hello world\r\nsecond line\r\nthird")
    }

    @Test fun deepTranscriptScroll() = roundTrip("scroll", rows = 6) {
        for (i in 1..40) feed("line $i\r\n")
    }

    @Test fun softWrapAcrossScreenBoundary() = roundTrip("wrap", cols = 12, rows = 5) {
        // A long line that soft-wraps several times, then more lines to push the wrap
        // point across the transcript/screen boundary.
        feed("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF\r\n")
        for (i in 1..8) feed("row$i\r\n")
    }

    @Test fun exactlyColsHardTerminated() = roundTrip("exact-cols", cols = 10, rows = 4) {
        feed("0123456789\r\nnext")     // fills row 0 exactly, hard CR/LF (not a soft wrap)
    }

    @Test fun sgrRainbow() = roundTrip("sgr") {
        feed("${esc}[1mBOLD ${esc}[0m${esc}[31mred ${esc}[42mgrnbg ${esc}[0m")
        feed("${esc}[38;5;196m256 ${esc}[38;2;10;20;30mtrue ${esc}[0m")
        feed("${esc}[3;4;9mmix${esc}[0m")
    }

    @Test fun styledTrailingSpaces() = roundTrip("styled-trailing", cols = 20, rows = 4) {
        // Background color extended across trailing spaces must survive (not trimmed).
        feed("${esc}[44mword     ${esc}[0m")
    }

    @Test fun wideChars() = roundTrip("wide", cols = 12, rows = 4) {
        feed("hi你好\r\n")             // CJK (each width 2)
        feed("ab😀cd\r\n")          // emoji (width 2)
    }

    @Test fun wideCharWrapAtEdge() = roundTrip("wide-edge", cols = 8, rows = 4) {
        feed("aaa你好世界")   // wide chars run past the right edge and wrap
    }

    @Test fun clearWithDeepTranscript() = roundTrip("clear", rows = 6) {
        for (i in 1..30) feed("l$i\r\n")
        feed("${esc}[2J${esc}[Hafter clear")   // clear screen; transcript remains
    }

    @Test fun hiddenCursor() = roundTrip("hidden-cursor") {
        feed("x${esc}[?25l")
    }

    @Test fun altScreen() = roundTrip("alt", cols = 20, rows = 6) {
        feed("scrollback line 1\r\nscrollback line 2\r\n")
        feed("${esc}[?1049h")                  // enter alternate buffer
        feed("${esc}[HTUI top\r\nTUI body")
    }

    @Test fun decsetSoup() = roundTrip("decset") {
        feed("${esc}[?1h${esc}[?5h${esc}[?7l${esc}[?1000h${esc}[?1006h${esc}[?1004h${esc}[?2004h${esc}=")
        feed("content")
    }

    @Test fun tabStops() = roundTrip("tabs", cols = 40, rows = 4) {
        feed("${esc}[3g")                       // clear default tab stops
        feed("${esc}[1;5H${esc}H")              // set a stop at column 5
        feed("${esc}[1;13H${esc}H")             // and column 13
        feed("${esc}[Hx\ty\tz")
    }

    @Test fun scrollRegion() = roundTrip("decstbm", rows = 8) {
        feed("${esc}[2;6r")                      // scroll region rows 2..6
        feed("${esc}[3;1Hinside region")
    }

    @Test fun originMode() = roundTrip("origin", rows = 8) {
        feed("${esc}[2;6r${esc}[?6h")            // scroll region + origin mode
        feed("${esc}[1;1Hhome-in-margins")
    }

    @Test fun palette() = roundTrip("palette") {
        feed("${esc}]4;1;#123456")         // recolor palette index 1
        feed("${esc}]10;#abcdef")           // default foreground
        feed("${esc}]11;#010203")           // default background
        feed("colored")
    }

    @Test fun title() = roundTrip("title") {
        feed("${esc}]2;My Session Titlehi")
    }

    @Test fun pendingWrap() = roundTrip("pending-wrap", cols = 10, rows = 4) {
        feed("0123456789")                        // fills last column; cursor parked, pending wrap
    }

    @Test fun reflowNarrow() = roundTrip("reflow", cols = 120, rows = 8, resizeTo = 80 to 8) {
        feed("A very long line that is definitely wider than eighty columns once we keep typing and typing and typing past 120.\r\n")
        feed("short\r\n")
    }

    @Test fun combiningChars() = roundTrip("combining", cols = 20, rows = 4) {
        // Base letters plus combining marks in the same cell (e + acute, n + tilde).
        feed("café piñata done")
    }

    @Test fun cursorParkedMidScreen() = roundTrip("cursor-mid", cols = 20, rows = 8) {
        feed("row0\r\nrow1\r\nrow2\r\nrow3\r\nrow4")
        feed("${esc}[2;3H")   // move cursor up into the middle of the screen
    }

    @Test fun fullReverseVideo() = roundTrip("reverse", cols = 20, rows = 5) {
        feed("${esc}[?5h")             // whole-screen reverse video
        feed("${esc}[7minverse run${esc}[0m normal")
    }

    @Test fun styledBlankRowsAboveContent() = roundTrip("styled-blank-rows", cols = 16, rows = 6) {
        // A background-colored region then a jump down — leaves styled cells with the
        // cursor well below, exercising trailing/interior style runs on several rows.
        feed("${esc}[41mAAAA${esc}[0m\r\n${esc}[42mBBBB${esc}[0m\r\n\r\n\r\ntail")
    }

    // ── Assertions ───────────────────────────────────────────────────────────

    private fun assertGridsEqual(s: TerminalEmulator, d: TerminalEmulator, label: String) {
        assertEquals(s.mColumns, d.mColumns, "$label: cols")
        assertEquals(s.mRows, d.mRows, "$label: rows")
        assertEquals(s.isAlternateBufferActive, d.isAlternateBufferActive, "$label: alt-active")

        compareBuffer(s.screen, d.screen, s.mColumns, s.mRows, "$label/screen")
        if (s.isAlternateBufferActive) {
            compareBuffer(s.mainBuffer, d.mainBuffer, s.mColumns, s.mRows, "$label/main")
        }

        assertEquals(s.cursorRow, d.cursorRow, "$label: cursorRow")
        assertEquals(s.cursorCol, d.cursorCol, "$label: cursorCol")
        assertEquals(s.isAboutToAutoWrap, d.isAboutToAutoWrap, "$label: pendingWrap")

        assertEquals(s.isAutoWrapEnabled, d.isAutoWrapEnabled, "$label: autowrap")
        assertEquals(s.isOriginMode, d.isOriginMode, "$label: originMode")
        assertEquals(s.isCursorEnabled, d.isCursorEnabled, "$label: cursorEnabled")
        assertEquals(s.isReverseVideo, d.isReverseVideo, "$label: reverseVideo")
        assertEquals(s.isCursorKeysApplicationMode, d.isCursorKeysApplicationMode, "$label: appCursorKeys")
        assertEquals(s.isKeypadApplicationMode, d.isKeypadApplicationMode, "$label: appKeypad")
        assertEquals(s.isMouseTrackingPressRelease, d.isMouseTrackingPressRelease, "$label: mouse1000")
        assertEquals(s.isMouseTrackingButtonEvent, d.isMouseTrackingButtonEvent, "$label: mouse1002")
        assertEquals(s.isMouseProtocolSgr, d.isMouseProtocolSgr, "$label: mouse1006")
        assertEquals(s.isFocusEventsEnabled, d.isFocusEventsEnabled, "$label: focusEvents")
        assertEquals(s.isBracketedPasteMode, d.isBracketedPasteMode, "$label: bracketedPaste")

        assertEquals(s.topMargin, d.topMargin, "$label: topMargin")
        assertEquals(s.bottomMargin, d.bottomMargin, "$label: bottomMargin")
        assertEquals(s.isLeftRightMarginModeEnabled, d.isLeftRightMarginModeEnabled, "$label: lrMarginMode")
        if (s.isLeftRightMarginModeEnabled) {
            assertEquals(s.leftMargin, d.leftMargin, "$label: leftMargin")
            assertEquals(s.rightMargin, d.rightMargin, "$label: rightMargin")
        }

        assertTrue(s.tabStops.contentEquals(d.tabStops), "$label: tabStops")
        assertEquals(s.title, d.title, "$label: title")
        assertEquals(s.currentForeColor, d.currentForeColor, "$label: currentFore")
        assertEquals(s.currentBackColor, d.currentBackColor, "$label: currentBack")
        assertEquals(s.currentEffect, d.currentEffect, "$label: currentEffect")
        assertTrue(s.mColors.mCurrentColors.contentEquals(d.mColors.mCurrentColors), "$label: palette")
    }

    private fun compareBuffer(a: TerminalBuffer, b: TerminalBuffer, cols: Int, rows: Int, label: String) {
        assertEquals(a.activeTranscriptRows, b.activeTranscriptRows, "$label: transcriptRows")
        for (y in -a.activeTranscriptRows until rows) {
            assertEquals(wrapOf(a, y), wrapOf(b, y), "$label: wrap row $y")
            for (col in 0 until cols) {
                assertEquals(cp(a, y, col), cp(b, y, col), "$label: char row $y col $col")
                assertEquals(a.getStyleAt(y, col), b.getStyleAt(y, col), "$label: style row $y col $col")
            }
        }
    }

    private fun wrapOf(buffer: TerminalBuffer, y: Int): Boolean =
        buffer.getLineOrNull(y) != null && buffer.getLineWrap(y)

    private fun cp(buffer: TerminalBuffer, y: Int, col: Int): Int {
        val row = buffer.getLineOrNull(y) ?: return ' '.code
        return Character.codePointAt(row.mText, row.findStartOfColumn(col))
    }
}
