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
    fun `a device switch round trip nets no archived copies at all`() {
        // Each narrowing archives the frame's top; each widening back reveals those same rows
        // onto the screen again — which is not a lunamux heuristic but what a terminal does
        // with its scrollback when the window grows, and what every client here does for
        // itself (see ScreenOnlyBufferTest.testBackfilledGrowShowsExactlyWhatATranscriptFulBufferShows).
        // The program then repaints the screen it owns, over the rows just revealed. So a full
        // round trip nets zero: switching back and forth does not pile up copies. The
        // *narrowing-only* count is pinned above; this pins that nothing accumulates.
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
            1,
            text.countOf(MARKER_TOP),
            "16 resizes, one copy: the live screen's — nothing accumulated",
        )
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the tail never scrolls off at these sizes")
    }

    @Test
    fun `a widening under a repainting program reaches no further back than the rows it reveals`() {
        // The cost of revealing scrollback on a grow, stated as a bound rather than denied: the
        // revealed rows are on the screen again, so a program that repaints the screen paints
        // over them, and they are gone — in Terminal.app, in tmux and here alike. What must
        // never happen is a reach *past* them, or a hole punched in the middle of history.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText((0 until 120).joinToString("") { "history line %03d\r\n".format(it) })
        grid.feedText(repaint(NARROW_ROWS))

        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        val text = grid.transcriptText()
        val survivors = (0 until 120).filter { text.contains("history line %03d".format(it)) }
        assertEquals(
            List(survivors.size) { it }, survivors,
            "survivors must be an unbroken run from the oldest line — no holes",
        )
        assertTrue(
            survivors.size >= 120 - WIDE_ROWS - NARROW_ROWS,
            "the reach is bounded by one screenful of reveal, kept ${survivors.size} of 120",
        )
    }

    /**
     * The viewport row indices the composite holds, in the order they are read.
     *
     * Counting markers alone cannot tell a faithful archive from a mangled one: a rotated ring
     * keeps every marker and every row, just not in the right order. Reading the row indices
     * back as a sequence is what makes order and multiplicity assertable.
     */
    private fun SessionGrid.viewportRowSequence(): List<Int> =
        VIEWPORT_ROW.findAll(transcriptText()).map { it.groupValues[1].toInt() }.toList()

    /**
     * Split an ordered index sequence at each restart, so a composite holding an archived frame
     * followed by a repainted one reads as two runs.
     *
     * @return one list per run, in order.
     */
    private fun runsOf(sequence: List<Int>): List<List<Int>> {
        val runs = mutableListOf<MutableList<Int>>()
        for (n in sequence) {
            val current = runs.lastOrNull()
            if (current == null || n <= current.last()) runs.add(mutableListOf(n)) else current.add(n)
        }
        return runs
    }

    /** Assert a run is a complete, gapless ascending count from zero. */
    private fun assertIntactRun(run: List<Int>, label: String) {
        assertEquals(List(run.size) { it }, run, "$label: rows must be intact, gapless and in order")
    }

    @Test
    fun `a two-step resize burst archives the frame top once, not per step`() {
        // A single take-over commonly fires two size changes: the cols change from the new
        // device, then a rows-only adjust as its soft keyboard settles. The frame's top leaves
        // the screen on the first shrink and cannot leave again on the second.
        //
        // The rows-only second step is also the one that used to corrupt the screen-only ring
        // while firing no client resync, so this asserts the *content* of both copies and not
        // just how many markers survived: a rotated ring keeps every marker.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS + 8) // cols change (device width), taller rows
        grid.resize(NARROW_COLS, NARROW_ROWS)     // rows-only adjust (keyboard settles)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(2, text.countOf(MARKER_TOP), "one archived copy + the repainted screen")
        assertEquals(1, text.countOf(MARKER_BOTTOM))

        val runs = runsOf(grid.viewportRowSequence())
        assertEquals(2, runs.size, "exactly two copies: the archived frame top and the repaint, got $runs")
        assertIntactRun(runs[0], "archived copy")
        assertIntactRun(runs[1], "repainted copy")
        assertEquals(
            List(NARROW_ROWS - 2) { it },
            runs[1],
            "the repainted copy is the whole narrow viewport",
        )
        assertTrue(
            runs[0].size < WIDE_ROWS - 2,
            "the archived copy is the frame's TOP — a prefix, not the whole frame — got ${runs[0].size} rows",
        )
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

        /** Captures the index off a `viewport row NN` line, for order assertions. */
        val VIEWPORT_ROW = Regex("""viewport row (\d\d)""")

        const val MARKER_TOP = "MARKER-TOP-OF-FRAME"
        const val MARKER_BOTTOM = "MARKER-BOTTOM-OF-FRAME"
        const val WIDE_COLS = 143
        const val WIDE_ROWS = 43
        const val NARROW_COLS = 67
        const val NARROW_ROWS = 24
    }
}
