/**
 * Executable statement of acceptance criterion 2 — restated — plus the safety property
 * any future change must not break.
 *
 * The original criterion was "no duplicated output when switching devices". The
 * investigation (see `docs/server-side-screen.md`) landed on an upstream root cause:
 * Claude Code answers *any* PTY resize by re-emitting its whole view into the normal
 * buffer without clearing the previous frame (anthropics/claude-code#49086, reproduced
 * in plain Terminal.app and Ghostty), so whatever the resize archived into scrollback is
 * duplicated by the program's own bytes. Every server-side reconciliation tried against
 * real traces either never fired or risked eating genuine output, so the criterion is
 * restated: **a device switch adds nothing beyond what one standalone-terminal resize of
 * the running program produces; lunamux itself never duplicates, drops, or rewrites
 * output.**
 *
 * These tests pin both halves of that sentence against [SessionGrid]'s public surface:
 *
 *  - **faithfulness**: the archived frame top a repainting program duplicates is *kept*,
 *    verbatim — exactly one archived copy per narrowing, exactly what a real terminal's
 *    scrollback holds. If a heuristic dedup ever creeps back in, these counts drop below
 *    the faithful value and fail loudly.
 *  - **safety**: committed history is never lost to a resize, with or without a repaint.
 *    It is far worse to eat a user's scrollback than to record a duplicate frame in it.
 *
 * @see ReflowReversibilityTest the companion property: reflow itself is lossless
 * @see SessionGridTest the grid's basic contract
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TakeOverDuplicationTest {

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /**
     * One screen's worth of a full-screen app's viewport: `rows` short lines (no wrap at
     * either test width), the first tagged [MARKER_TOP] and the last [MARKER_BOTTOM]. The
     * body between them is width-independent text a real TUI would recompute per size.
     */
    private fun viewport(rows: Int): List<String> = buildList {
        add(MARKER_TOP)
        repeat(rows - 2) { i -> add("viewport row %02d".format(i)) }
        add(MARKER_BOTTOM)
    }

    /**
     * How a full-screen TUI redraws itself after a SIGWINCH: hide cursor, home, erase every
     * visible row, home, then paint exactly one screen. Captured from a live Claude Code
     * session — every post-SIGWINCH chunk opened `ESC[?25l` … `ESC[H` followed by exactly
     * `rows` × (`ESC[2K` `ESC[1B`) and a second `ESC[H`, with the erase count tracking the
     * new screen height across all 22 observed resizes in both directions.
     *
     * The per-row erase reaches only the screen. Whatever a narrowing already archived into
     * history is out of its reach, which is precisely how the duplicate is minted.
     */
    private fun repaint(rows: Int): String = buildString {
        append("$ESC[?25l")
        append("$ESC[H")
        repeat(rows) { append("$ESC[2K$ESC[1B") }
        append("$ESC[H")
        append(viewport(rows).joinToString("\r\n"))
        append("$ESC[?25h")
    }

    private fun String.countOf(needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }

    // ── faithfulness: the duplicate is recorded, never reconciled away ────────

    @Test
    fun `a take-over repaint is recorded faithfully, archived copy and all`() {
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // The program paints its viewport at the laptop's native size.
        grid.feedText(repaint(WIDE_ROWS))
        assertEquals(1, grid.transcriptText().countOf(MARKER_TOP), "precondition: one copy")

        // Phone takes over: the PTY narrows, the shorter screen archives the frame's top
        // into history, and the program repaints the whole frame on the screen below it.
        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(
            2,
            text.countOf(MARKER_TOP),
            "one archived copy + one repainted copy — exactly what a real terminal holds",
        )
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the frame's tail never left the screen")
    }

    @Test
    fun `repeated take-over archives exactly one copy per narrowing`() {
        // Immutable history has no reabsorption, so each narrowing's archive stays. The
        // count pins the faithful value from both directions: more would mean lunamux is
        // adding duplication of its own; fewer would mean a heuristic is eating scroll-off.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        repeat(8) {
            grid.resize(NARROW_COLS, NARROW_ROWS)
            grid.feedText(repaint(NARROW_ROWS))
            grid.resize(WIDE_COLS, WIDE_ROWS)
            grid.feedText(repaint(WIDE_ROWS))
        }

        val text = grid.transcriptText()
        assertEquals(
            9,
            text.countOf(MARKER_TOP),
            "8 narrowings archive 8 copies; the 9th is the live screen's",
        )
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the tail never scrolls off at these sizes")
    }

    @Test
    fun `a two-step resize burst archives the frame top once, not per step`() {
        // A single take-over commonly fires two size changes: the cols change from the new
        // device, then a rows-only adjust as its soft keyboard settles. The frame's top
        // leaves the screen on the first shrink and cannot leave again on the second.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS + 8) // cols change (device width), taller rows
        grid.resize(NARROW_COLS, NARROW_ROWS)     // rows-only adjust (keyboard settles)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(2, text.countOf(MARKER_TOP), "one archived copy + the repainted screen")
        assertEquals(1, text.countOf(MARKER_BOTTOM))
    }

    @Test
    fun `widening then repainting keeps one copy`() {
        // Widening un-wraps rather than overflowing, so nothing is archived and no
        // duplicate is minted. Pins that the artifact really is specific to narrowing.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        assertEquals(1, grid.transcriptText().countOf(MARKER_BOTTOM), "one copy after widening")
    }

    // ── safety: no resize may lose committed output ───────────────────────────

    @Test
    fun `committed history above the viewport survives a take-over`() {
        // The safety direction, and the one the first (reverted) fix attempt broke: a real
        // session has committed history above the live viewport, and nothing may reach it.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // Enough committed lines that many scroll off the top into the transcript before
        // the full-screen app draws its first frame.
        val history = (0 until 90).joinToString("") { "history line $it\r\n" }
        grid.feedText(history)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        for (line in listOf(0, 13, 27, 40)) {
            assertTrue(
                text.contains("history line $line\n"),
                "committed history must survive take-over (line $line)",
            )
        }
    }

    @Test
    fun `a resize with no repaint never loses committed output`() {
        // The shell case: real committed output, a resize, and no full-screen redraw.
        // Nothing may be dropped — the majority of sessions look like this.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        val committed = (0 until 60).map { "committed line $it " + "x".repeat(120) }
        grid.feedText(committed.joinToString("\r\n") + "\r\n$ ")

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText("ls\r\n")

        val text = grid.transcriptText()
        for (line in listOf(0, 17, 42, 59)) {
            assertTrue(
                text.contains("committed line $line "),
                "committed output must survive a resize with no repaint (line $line)",
            )
        }
    }

    @Test
    fun `reads between the resize and the repaint change nothing`() {
        // Attaches, resyncs and MCP scrollback reads can all land in the gap between a
        // resize and the program's answer. A read must be a pure function of the grid.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        repeat(5) {
            grid.transcriptText()
            grid.synthesizeRedraw()
        }
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(2, text.countOf(MARKER_TOP), "reads must not change what is committed")
        assertEquals(1, text.countOf(MARKER_BOTTOM))
    }

    private companion object {
        /** The ESC byte, spelled out so no editor or copy-paste can silently eat it. */
        const val ESC = "\u001b"

        const val MARKER_TOP = "MARKER-TOP-OF-FRAME"
        const val MARKER_BOTTOM = "MARKER-BOTTOM-OF-FRAME"
        const val WIDE_COLS = 143
        const val WIDE_ROWS = 43
        const val NARROW_COLS = 67
        const val NARROW_ROWS = 24
    }
}
