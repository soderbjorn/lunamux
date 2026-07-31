/**
 * Tests for resizing the canonical grid while a full-screen TUI holds the alternate
 * buffer — the LMX-145 fault and its quieter sibling.
 *
 * Upstream `TerminalEmulator.resizeScreen()` resizes only the *active* screen, leaving the
 * inactive one at its old width until `?1049h`/`?1049l` swaps it back. `GridSerializer`,
 * however, reads every buffer at `e.mColumns`. While a TUI is up those two widths disagree,
 * and the disagreement has two faces:
 *
 *  - **a widen throws** — the stale main-buffer rows are indexed past their own arrays,
 *    which killed every `/pty` attach for that session and produced an endless reconnect
 *    loop (`ArrayIndexOutOfBoundsException` out of `TerminalRow.findStartOfColumn`);
 *  - **a narrow silently truncates** — the stale rows are read at too *few* columns, so
 *    real content is dropped out of the redraw and out of persisted scrollback.
 *
 * The fix reflows the inactive main buffer eagerly, so the two widths can never disagree.
 * These tests pin the fault, the truncation, and the two things the eager reflow must not
 * break: the main buffer's content, and its *saved* cursor.
 *
 * @see se.soderbjorn.lunamux.pty.SessionGrid
 * @see se.soderbjorn.lunamux.pty.GridSerializer
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AltBufferResizeTest {

    private val esc = "\u001b"

    private fun SessionGrid.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /** A grid holding scrollback, with a TUI then taking the alternate buffer. */
    private fun gridInAltBufferAt(cols: Int, scrollback: String = "scrollback one\r\nscrollback two\r\n"): SessionGrid {
        val grid = SessionGrid(cols, 24)
        grid.feed(scrollback)
        grid.feed("$esc[?1049h")
        grid.feed("TUI frame")
        return grid
    }

    @Test
    fun `widening under a TUI does not throw`() {
        val grid = gridInAltBufferAt(80)
        grid.resize(200, 24)
        // Both read-back paths walk the main buffer; both crashed before the fix.
        assertTrue(grid.synthesizeRedraw().isNotEmpty(), "redraw should serialize after a widen under a TUI")
        assertTrue(grid.synthesizeForPersist().isNotEmpty(), "persist should serialize after a widen under a TUI")
    }

    @Test
    fun `narrowing under a TUI keeps the whole scrollback line`() {
        // One 150-char line: a single row at 200 cols, two rows at 80.
        val long = "x".repeat(150)
        val grid = gridInAltBufferAt(200, scrollback = "$long\r\n")
        grid.resize(80, 24)
        val persisted = String(grid.synthesizeForPersist(), Charsets.UTF_8)
        val recovered = persisted.filter { it == 'x' }.length
        // Reading the stale 200-wide row at 80 columns dropped the tail on the floor.
        assertEquals(150, recovered, "the whole 150-char line should survive a narrow under a TUI")
    }

    @Test
    fun `main buffer content survives a widen under a TUI`() {
        val grid = gridInAltBufferAt(80)
        grid.resize(200, 24)
        grid.feed("$esc[?1049l")
        val text = grid.transcriptText()
        assertTrue(text.contains("scrollback one"), "first scrollback line should survive, was: $text")
        assertTrue(text.contains("scrollback two"), "second scrollback line should survive, was: $text")
    }

    @Test
    fun `the saved main cursor survives a widen under a TUI`() {
        // Main cursor parks after "prompt> " — row 2, col 8.
        val grid = SessionGrid(80, 24)
        grid.feed("line one\r\nline two\r\nprompt> ")
        grid.feed("$esc[?1049h")
        // Move the ALT cursor somewhere clearly different, so carrying the wrong one shows.
        grid.feed("${esc}[10;40H")
        grid.resize(200, 24)
        grid.feed("$esc[?1049l")
        val (row, col) = grid.read { it.cursorRow to it.cursorCol }
        assertEquals(2, row, "main cursor row should be restored, not the alt buffer's")
        assertEquals(8, col, "main cursor column should be restored, not the alt buffer's")
    }

    @Test
    fun `widening with no TUI is unaffected`() {
        val grid = SessionGrid(80, 24)
        grid.feed("scrollback one\r\nscrollback two\r\n")
        grid.resize(200, 24)
        assertTrue(grid.synthesizeRedraw().isNotEmpty(), "the no-TUI path should be untouched")
    }
}
