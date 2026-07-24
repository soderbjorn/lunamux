/**
 * Executable statement of acceptance criterion 2 — "no duplicated output when switching
 * devices" — plus the safety property any future fix must not break.
 *
 * Why this happens at all. One PTY has one winsize, so a take-over resizes it, and a
 * resize is a SIGWINCH. A full-screen program answers a SIGWINCH by redrawing its
 * viewport: it homes, erases every visible row, and paints one screen's worth. But
 * narrowing first runs the emulator's reflow, which rewraps the *old* viewport into more
 * rows than the new screen holds and archives the overflow — the top of the old frame —
 * into scrollback, where a per-row erase cannot reach it. The program then repaints the
 * same content on-screen, so that stranded top is now shown twice. This is exactly the
 * "duplicated the Claude ASCII logo + my prompt lines" a user reported when taking a
 * session back and forth.
 *
 * The tests are written against [SessionGrid]'s public surface only — feed bytes, resize,
 * read the transcript — so they survive whatever mechanism satisfies them. They are
 * deliberately *not* written against a repaint classifier, a truncation, or any other
 * particular fix; the first attempt at one was reverted precisely because the tests had
 * grown to encode it (see `docs/server-side-screen.md`).
 *
 * They were written failing, as the definition of done for the history-model work, and are
 * met by the live-screen/history split: a resize touches only the live screen, and what it
 * pushes off is held until the program's response shows whether it was reclaimed. The
 * second group is the safety direction — no fix may buy the first group with these.
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
     * Painting `rows` lines with a trailing newline on each fills the screen and scrolls the
     * top row into history by exactly one — matching a real redraw, which commits one line
     * of history as it wraps onto the last row. The point of the test is that this leaves
     * ONE copy, not that it leaves zero.
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

    @Test
    fun `a repaint after take-over leaves exactly one copy of the frame`() {
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // The program paints its viewport at the laptop's native size.
        grid.feedText(repaint(WIDE_ROWS))
        assertEquals(1, grid.transcriptText().countOf(MARKER_BOTTOM), "precondition: one copy")

        // Phone takes over: the PTY narrows, the grid reflows, and the program repaints.
        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "the frame's top must not be duplicated")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "the frame's tail must not be duplicated")
    }

    @Test
    fun `stale in-flight output before the repaint does not defeat the fix`() {
        // On device the program's SIGWINCH response does not always land in the very next
        // chunk: a spinner frame or partial write already in the pipe arrives first. A fix
        // that only inspects the chunk immediately following the resize will miss this.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText("${ESC}7$ESC[2;1H$ESC[38;5;180mIncubating…${ESC}8") // stale spinner
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "top must not be duplicated across a stale chunk")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "tail must not be duplicated across a stale chunk")
    }

    @Test
    fun `a two-step resize burst still leaves one copy`() {
        // A single take-over commonly fires two size changes: the cols change from the new
        // device, then a rows-only adjust as its soft keyboard settles. Both land before the
        // program's repaint, so a fix must treat the burst as one event.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS + 8) // cols change (device width), taller rows
        grid.resize(NARROW_COLS, NARROW_ROWS)     // rows-only adjust (keyboard settles)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "top must not survive the burst as a duplicate")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "tail must not survive the burst as a duplicate")
    }

    @Test
    fun `repeated take-over does not accumulate copies`() {
        // Before the split this passed for the wrong reason: the duplicate was minted by
        // the narrowing and then *reabsorbed* by the widening, which pulled those rows back
        // onto the taller screen for the next repaint to erase. Immutable history has no
        // reabsorption, so this now genuinely requires each switch to be reconciled — it
        // failed with eight copies until a window was resolved per switch rather than
        // accumulating across all of them.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        repeat(8) {
            grid.resize(NARROW_COLS, NARROW_ROWS)
            grid.feedText(repaint(NARROW_ROWS))
            grid.resize(WIDE_COLS, WIDE_ROWS)
            grid.feedText(repaint(WIDE_ROWS))
        }

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "top must not scale with the number of switches")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "tail must not scale with the number of switches")
    }

    @Test
    fun `a read between the resize and the repaint does not decide anything`() {
        // The device race this guards. On a real take-over the debounced resync fires
        // ~100ms after the resize and reads the grid. If that read forced a verdict, it
        // would be taken before Claude Code's repaint had landed — nothing on screen to
        // match, so the reflow's overflow would be committed, and with immutable history
        // there is no later reabsorption to heal it. The duplicate would be permanent, and
        // intermittent, because it depends on whether the program answered within 100ms.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)

        // The early read: correct for this instant — those rows really have scrolled off —
        // and, crucially, not a decision.
        val early = grid.transcriptText()
        assertEquals(1, early.countOf(MARKER_BOTTOM), "the frame's tail is on screen exactly once")

        // The repaint lands late, and the answer corrects itself.
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "an early read must not have committed the overflow")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "nor duplicated the tail")
    }

    @Test
    fun `repeated early reads still do not decide anything`() {
        // A phone attaching, a resync firing, an MCP tool reading scrollback — several
        // reads can land in the gap. None may be load-bearing.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        repeat(5) {
            grid.transcriptText()
            grid.synthesizeRedraw()
        }
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        assertEquals(1, text.countOf(MARKER_TOP), "top must survive five early reads")
        assertEquals(1, text.countOf(MARKER_BOTTOM), "tail must survive five early reads")
    }

    @Test
    fun `an early read loses nothing when the program never repaints`() {
        // The other direction: a shell scrolls real output off during a resize and a read
        // lands in the gap. Answering from the current screen must not drop it either.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        val committed = (0 until 60).map { "committed line $it " + "x".repeat(120) }
        grid.feedText(committed.joinToString("\r\n") + "\r\n$ ")

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.transcriptText() // early read, no repaint coming
        grid.feedText("ls\r\n")

        val text = grid.transcriptText()
        for (line in listOf(0, 17, 42, 59)) {
            assertTrue(
                text.contains("committed line $line "),
                "an early read must not drop scrolled-off output (line $line)",
            )
        }
    }

    @Test
    fun `committed history above the viewport survives a take-over`() {
        // The safety direction, and the one the first fix attempt broke: a real session has
        // committed history above the live viewport, and no de-duplication may reach it.
        // Whatever satisfies the ignored tests above must keep this passing — it is far
        // worse to eat a user's scrollback than to leave a duplicate frame in it.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        // Enough committed lines that many scroll off the top into the transcript before
        // the full-screen app draws its first frame — otherwise the repaint's erase simply
        // wipes them off the screen and there is nothing in scrollback to protect.
        val history = (0 until 90).joinToString("") { "history line $it\r\n" }
        grid.feedText(history)
        grid.feedText(repaint(WIDE_ROWS))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        val text = grid.transcriptText()
        // Lines below the ~47 that were already in the transcript when the app started.
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
        // Nothing may be dropped — the majority of sessions look like this, and a fix
        // aimed at full-screen repainters must be inert here.
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
    fun `widening then repainting keeps one copy`() {
        // Widening un-wraps rather than overflowing, so the reflow archives nothing and no
        // duplicate is minted. This passes today and pins that the bug really is specific
        // to the narrowing direction.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText(repaint(NARROW_ROWS))

        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(repaint(WIDE_ROWS))

        assertEquals(1, grid.transcriptText().countOf(MARKER_BOTTOM), "one copy after widening")
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
