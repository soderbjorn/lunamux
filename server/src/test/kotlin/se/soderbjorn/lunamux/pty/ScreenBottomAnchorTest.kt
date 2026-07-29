/**
 * One invariant: **a resize must not change how far the cursor sits from the bottom of the
 * screen.**
 *
 * That is what makes a terminal feel like a terminal. Drag the window taller and the prompt
 * stays where it is at the bottom while older output is revealed above it; a terminal that
 * instead appends empty rows below the prompt looks broken, and was the "the input field is
 * halfway up the screen" report.
 *
 * The canonical grid has to be told to do this. A terminal whose history is its own transcript
 * reveals rows from there on a grow, but this grid deliberately has no transcript — its history
 * is [HistoryLog], outside the emulator, as logical lines no reflow can reach — so the vendored
 * grow path found nothing to reveal and padded below the cursor instead. [SessionGrid.resize]
 * now hands the rows back from history, and these tests pin both halves of that: the anchor
 * holds, and no content is duplicated, lost or re-wrapped by the handing back.
 *
 * Note that the slack does not only open on a *taller* screen: a *wider* one reflows the same
 * content into fewer rows, which strands the prompt exactly the same way.
 *
 * @see SessionGrid.resize
 * @see HistoryLog.popLast
 * @see com.termux.terminal.TerminalBuffer.backfillAboveScreen
 * @see PersistRestoreRoundTripTest the restore round trip, where this first showed up
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenBottomAnchorTest {

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /** Rows between the cursor and the bottom of the screen — the quantity a resize must not change. */
    private fun SessionGrid.slackBelowCursor(): Int = read { e -> e.mRows - 1 - e.cursorRow }

    /** Blank rows between the last content row and the bottom of the screen. */
    private fun SessionGrid.bandBelowContent(): Int =
        read { it.backfillCapacityAboveScreen(it.mRows) }

    /**
     * The session's content as logical lines, blank ones dropped: history + the pending line +
     * the screen with soft wraps rejoined. Width-independent by construction, which is what
     * lets a resize be checked for content preservation at all.
     */
    private fun SessionGrid.logicalLines(): List<String> =
        transcriptText().lines().map { it.trimEnd() }.filter { it.isNotEmpty() }

    /** A session with [committed] lines already scrolled off and a live prompt at the bottom. */
    private fun scrolled(cols: Int, rows: Int, committed: Int): SessionGrid {
        val grid = SessionGrid(cols, rows)
        for (i in 1..committed + rows) grid.feedText("line $i of output\r\n")
        grid.feedText("PROMPT> ")
        return grid
    }

    @Test
    fun `growing the screen reveals history above instead of blank rows below`() {
        val grid = scrolled(cols = 100, rows = 20, committed = 40)
        val before = grid.logicalLines()
        val historyBefore = grid.historyLines().size
        assertEquals(0, grid.slackBelowCursor(), "precondition: the prompt is on the bottom row")

        grid.resize(100, 34)

        assertEquals(0, grid.slackBelowCursor(), "the prompt must still be on the bottom row")
        assertEquals(before, grid.logicalLines(), "and no content may be gained or lost")
        assertEquals(
            historyBefore - 14, grid.historyLines().size,
            "the 14 new rows came out of history, so history is 14 lines shorter",
        )
    }

    @Test
    fun `widening reveals history too, because the content reflows into fewer rows`() {
        // No rows are gained here at all: the same content simply stops needing two rows per
        // line, which opens the same band of blank rows below the prompt.
        val grid = SessionGrid(40, 20)
        for (i in 1..40) grid.feedText("line $i " + "x".repeat(50) + "\r\n")
        grid.feedText("PROMPT> ")
        val before = grid.logicalLines()

        grid.resize(120, 20)

        assertEquals(0, grid.slackBelowCursor(), "widening must not strand the prompt either")
        assertEquals(before, grid.logicalLines())
    }

    @Test
    fun `shrinking reveals nothing`() {
        val grid = scrolled(cols = 100, rows = 30, committed = 40)
        val before = grid.logicalLines()
        val historyBefore = grid.historyLines().size

        grid.resize(100, 12)

        assertEquals(0, grid.slackBelowCursor())
        assertTrue(
            grid.historyLines().size > historyBefore,
            "a shrink pushes rows the other way, into history",
        )
        assertEquals(before, grid.logicalLines(), "and still changes no content")
    }

    @Test
    fun `a band the program left is preserved, not filled`() {
        // Only the rows *this resize* opened are filled. A program leaves bands of its own — zsh
        // repaints its prompt with ESC[J, which erases to the end of the screen — and those are
        // its business. So the gap below the content is what comes out unchanged, which is both
        // what leaves an erased screen erased and what stops the reveal from needing a heuristic
        // about who blanked what.
        val grid = scrolled(cols = 80, rows = 20, committed = 30)
        grid.feedText("[2A[J")       // up two rows, erase from there to the end of screen
        assertEquals(2, grid.bandBelowContent(), "precondition: the program left a 2-row band")

        grid.resize(80, 30)

        assertEquals(2, grid.bandBelowContent(), "the program's own band comes out unchanged")
        assertEquals(2, grid.slackBelowCursor(), "so the cursor keeps its distance too")
    }

    @Test
    fun `a band already open does not stop the next resize from filling its own rows`() {
        // The regression this exists for. Requiring the band to have been *empty* was sticky: the
        // restore path deliberately drops the live prompt line and leaves a row blank, that one
        // row disabled the reveal, and every later resize then inherited a non-empty band and
        // refused in turn — so the band only ever grew. On device: "the empty rows appear when
        // the window resizes, and the input is in the middle again", on a session that had plenty
        // of scrollback to fill them with.
        val grid = scrolled(cols = 115, rows = 38, committed = 60)
        grid.feedText("[2A[J")
        val before = grid.logicalLines()
        assertEquals(2, grid.bandBelowContent(), "precondition: a band is already open")

        grid.resize(144, 43)                               // the app going full-screen

        assertEquals(2, grid.bandBelowContent(), "the resize's own rows are filled regardless")
        assertEquals(before, grid.logicalLines())
    }

    @Test
    fun `every resize in a run fills its own rows, so no band accumulates`() {
        // The other half of stickiness: one refusal used to poison every resize after it.
        val grid = scrolled(cols = 80, rows = 20, committed = 120)
        grid.feedText("[1A[J")                     // a 1-row band, as the restore leaves
        val before = grid.logicalLines()

        for (rows in listOf(24, 28, 34, 40, 43)) {
            grid.resize(80, rows)
            assertEquals(1, grid.bandBelowContent(), "band must not accumulate at $rows rows")
        }

        assertEquals(before, grid.logicalLines())
    }

    /**
     * A session whose newest *committed* history line is longer than the screen — every row of
     * it has scrolled off — with the lines that displaced it still visible, so they are not
     * history yet. That makes the long line the first candidate any reveal has to consider.
     *
     * @param cols grid width. @param rows grid height. @param long the long line's text.
     */
    private fun withALongLineJustOffTheTop(cols: Int, rows: Int, long: String): SessionGrid {
        val grid = SessionGrid(cols, rows)
        for (i in 1..20) grid.feedText("prelude $i\r\n")
        grid.feedText("$long\r\n")
        for (i in 1..rows - 1) grid.feedText("short $i\r\n")
        grid.feedText("PROMPT> ")
        // Preconditions, or the test proves something else entirely.
        assertEquals(
            long, grid.historyLines().last().text,
            "test setup: the long line must be the newest committed line",
        )
        assertEquals(null, grid.pendingHistoryLine(), "test setup: no line may be mid-flight")
        return grid
    }

    @Test
    fun `a logical line too long for the room stays in history rather than being split`() {
        // Un-scrolling half a line would freeze the column the old width happened to wrap at
        // into the line itself — the same defect as committing a half-assembled line. So the
        // reveal stops, and the honest cost is the band of blank rows it could not fill.
        val long = ("LONG " + "wrapme ".repeat(60)).trimEnd()   // 424 chars ≈ 8 rows at 60 cols
        val grid = withALongLineJustOffTheTop(60, 10, long)
        val before = grid.logicalLines()
        val historyBefore = grid.historyLines()

        grid.resize(60, 14)                                // 4 rows of room; the line needs 8

        assertEquals(historyBefore, grid.historyLines(), "history is untouched")
        assertEquals(4, grid.slackBelowCursor(), "and the band it could not fill is still there")
        assertEquals(before, grid.logicalLines(), "above all, nothing is split or lost")
    }

    @Test
    fun `given room for all of it, a long line is revealed whole`() {
        val long = ("LONG " + "wrapme ".repeat(60)).trimEnd()
        val grid = withALongLineJustOffTheTop(60, 10, long)
        val before = grid.logicalLines()

        grid.resize(60, 24)                                // 14 rows of room; 8 + shorter lines

        assertEquals(0, grid.slackBelowCursor(), "the prompt is back on the bottom row")
        assertEquals(before, grid.logicalLines())
        assertTrue(
            grid.transcriptText().contains(long),
            "and the long line is one unbroken logical line on the screen:\n${grid.transcriptText()}",
        )
    }

    @Test
    fun `nothing is revealed while a logical line is still mid-flight`() {
        // With rows of an unfinished line held outside historyLines(), the tail of history is
        // not the row above the screen — those pending runs are — so revealing history there
        // would splice a stale line between a logical line and its own continuation.
        val grid = SessionGrid(60, 6)
        for (i in 1..6) grid.feedText("filler $i\r\n")
        grid.feedText("LIVE-HEAD " + "wrapme ".repeat(80))   // unterminated, ~10 rows
        assertNotNull(grid.pendingHistoryLine(), "precondition: a line is mid-flight")
        val before = grid.logicalLines()
        val historyBefore = grid.historyLines()

        grid.resize(60, 20)

        assertEquals(historyBefore, grid.historyLines(), "the reveal must have stood down")
        assertEquals(before, grid.logicalLines())
    }

    @Test
    fun `nothing is revealed into an alternate-buffer frame`() {
        // An alt frame is an absolutely-addressed picture with no scrollback behind it, and
        // the normal buffer underneath it must be left exactly as the program left it.
        val grid = scrolled(cols = 80, rows = 20, committed = 30)
        val before = grid.logicalLines()
        val historyBefore = grid.historyLines()
        grid.feedText("[?1049h[H- a full-screen program -")

        grid.resize(80, 34)

        assertEquals(historyBefore, grid.historyLines())
        grid.feedText("[?1049l")
        assertEquals(before, grid.logicalLines(), "the normal buffer comes back untouched")
    }

    @Test
    fun `revealed lines are committed again, once, when they scroll off a second time`() {
        // The reveal is a move, not a copy: the lines are gone from the log because they are
        // on the screen. If it were a copy, every window resize would duplicate a screenful of
        // scrollback.
        val grid = scrolled(cols = 80, rows = 20, committed = 40)
        val before = grid.logicalLines()

        grid.resize(80, 34)
        grid.feedText("\r\n")                                 // finish the prompt's own line
        for (i in 1..50) grid.feedText("after $i\r\n")

        val expected = before + (1..50).map { "after $it" }
        assertEquals(expected, grid.logicalLines())
    }

    @Test
    fun `a resize storm settles back to the same content`() {
        val grid = scrolled(cols = 100, rows = 24, committed = 60)
        val before = grid.logicalLines()

        for ((cols, rows) in listOf(60 to 40, 143 to 12, 80 to 34, 100 to 24)) grid.resize(cols, rows)

        assertEquals(before, grid.logicalLines())
        assertEquals(0, grid.slackBelowCursor())
    }
}
