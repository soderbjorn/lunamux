/**
 * The persist → restore contract: a session stored across a server restart comes back as the
 * same *content*, whatever width it is replayed at.
 *
 * This is the round trip that broke on device. Two independent defects made a restored session
 * read as mangled prose — wrong line breaks, words split down the middle, and fragments of
 * unrelated lines spliced into the middle of others:
 *
 *  1. **`serializeForPersist` homed the cursor after emitting history.** The row flow paints
 *     without erasing, so every column a screen row did not reach left the history text
 *     underneath showing through: rows came back as `screen text` + padding + `leftover
 *     history tail`. [GridSerializer.serialize] had the guard for exactly this
 *     (`homeFirst = history.isEmpty()`, with a comment); the persist form never got it.
 *  2. **The half-assembled history line was committed at reflow boundaries**, freezing the
 *     point the *old* width happened to wrap into a permanent hard break — `deliberately not
 *     C` / `ompose Multiplatform`. It is now emitted unterminated instead, so the screen flow
 *     continues the same logical line and the receiver rewraps all of it.
 *
 * The assertions here are therefore about *logical lines*, never about rows: rows are a
 * function of the width you happen to look at, and that is the whole point.
 *
 * @see GridSerializer.serializeForPersist
 * @see HistoryLog.pendingLine
 * @see RelativeRepaintPingPongTest the live-resize counterpart
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistRestoreRoundTripTest {

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /**
     * Prose long enough to soft-wrap several times at every width used here, so the round trip
     * has to reconstruct wrap points rather than copy short rows around.
     */
    private fun prose(): String = buildString {
        append("Mac/Electron/web, Android, iOS with native UI per platform, deliberately not ")
        append("Compose Multiplatform. Shared view models expose one state object per screen ")
        append("with thin platform wrappers; the Kotlin networking layer is reused everywhere.\r\n")
        append("Terminal guts: pty4j and JediTerm on the server, xterm.js on web, Termux on ")
        append("Android, SwiftTerm on iOS, three.js for 3D, and the Lunula toolkit for web UI.\r\n")
        append("Docs include a manual; development is tracked on the issue tracker rather than ")
        append("GitHub Issues. MIT licensed.\r\n")
        append("Agent-awareness: the UI shows which sessions are working and which are idle.\r\n")
    }

    /**
     * The session's content as logical lines, with the trailing padding a row carries stripped.
     *
     * Reads through [SessionGrid.transcriptText], which is history + the pending line + the
     * screen with soft wraps rejoined — i.e. exactly the width-independent view the round trip
     * has to preserve. Blank lines are dropped so a differing amount of *empty* screen below
     * the content cannot fail the comparison; the defects being pinned all corrupt text.
     */
    private fun SessionGrid.logicalLines(): List<String> =
        transcriptText().lines().map { it.trimEnd() }.filter { it.isNotEmpty() }

    /** Store [source] and replay it into a fresh grid of [cols]×[rows], as the session does. */
    private fun restore(source: SessionGrid, cols: Int, rows: Int): SessionGrid {
        val blob = source.synthesizeForPersist()
        val restored = SessionGrid(cols, rows)
        restored.feed(blob, blob.size)
        return restored
    }

    @Test
    fun `restoring at the width it was persisted at reproduces every logical line`() {
        val source = SessionGrid(80, 12)
        source.feedText(prose())
        source.feedText(prose())

        val restored = restore(source, 80, 12)

        assertEquals(source.logicalLines(), restored.logicalLines())
    }

    @Test
    fun `restoring at a wider grid reflows without splicing history under the screen`() {
        // Defect 1. The screen half of the blob is authored at the session's width while the
        // history half is width-free, so replaying wider used to leave the history text visible
        // past the end of every screen row — the "prose with fragments of other lines in it"
        // report. Content must survive a width change untouched; only its wrapping may differ.
        val source = SessionGrid(80, 12)
        source.feedText(prose())
        source.feedText(prose())

        val restored = restore(source, 143, 40)

        assertEquals(source.logicalLines(), restored.logicalLines())
    }

    @Test
    fun `restoring at a narrower grid keeps every logical line too`() {
        val source = SessionGrid(120, 14)
        source.feedText(prose())
        source.feedText(prose())

        val restored = restore(source, 60, 20)

        assertEquals(source.logicalLines(), restored.logicalLines())
    }

    /**
     * A session whose newest logical line is long enough to outgrow the screen **on its own**,
     * so its leading rows have been evicted while its tail is still displayed. That is the state
     * with a half-assembled history line — the one the persist form used to mishandle — and it
     * has to be built deliberately: a line that merely fits the screen never produces it, no
     * matter how much scrolled before it. Hence the precondition assert.
     *
     * @param cols the grid width. @param rows the grid height.
     * @param body the long line's text, written without a trailing newline.
     */
    private fun straddlingTheTop(cols: Int, rows: Int, body: String): SessionGrid {
        val grid = SessionGrid(cols, rows)
        for (i in 1..rows) grid.feedText("filler $i\r\n")
        grid.feedText(body)
        // Precondition: the line really is mid-flight, or these tests prove nothing.
        assertTrue(
            grid.pendingHistoryLine() != null,
            "test setup: expected a half-assembled history line at ${cols}x$rows",
        )
        return grid
    }

    @Test
    fun `the live wrapped line is dropped whole, never left truncated mid-word`() {
        // Persist deliberately omits the live (unterminated) line: the cursor's row is a prompt
        // plus whatever is half-typed, and the shell spawned on restore prints its own prompt
        // anyway. The defect was dropping only that line's LAST row — a live line that has
        // soft-wrapped continues from rows above, so the rest of it was persisted as a fragment
        // and came back as a sentence cut off mid-word.
        //
        // ~583 chars ≈ 10 rows at 60 cols, so the live line spans most of the screen and has
        // even had its own leading rows evicted.
        val body = "LIVE-HEAD " + "wrapme ".repeat(80) + "LIVE-TAIL"
        val source = straddlingTheTop(60, 6, body)

        val restored = restore(source, 100, 30)
        val text = restored.logicalLines().joinToString("\n")

        // Everything committed BEFORE the live line survives...
        assertTrue(text.contains("filler 1"), "committed content must survive:\n$text")
        // ...and the live line is absent in its entirety — not present, and above all not
        // present as a fragment ending mid-"wrapme".
        assertFalse(text.contains("LIVE-HEAD"), "the live line must be dropped whole:\n$text")
        assertFalse(text.contains("wrapme"), "no fragment of it may be persisted:\n$text")
    }

    @Test
    fun `a committed long line comes back whole, with no break at the old wrap columns`() {
        // The wrap point the old width happened to land on must not become permanent: a
        // committed logical line is stored width-free, so replaying it wider rewraps all of it.
        val sentence = "alpha bravo charlie delta echo foxtrot golf hotel india juliett " +
            "kilo lima mike november oscar papa quebec romeo sierra tango uniform victor " +
            "whiskey xray yankee zulu one two three four five six seven eight nine ten"
        val source = SessionGrid(40, 6)
        for (i in 1..6) source.feedText("filler $i\r\n")
        source.feedText("$sentence\r\n")   // terminated: a committed line, not the live one
        source.feedText("tail line\r\n")

        val restored = restore(source, 160, 12)
        val text = restored.logicalLines().joinToString("\n")

        assertTrue(
            text.contains(sentence),
            "the committed line must be whole, with no break at any old 40-column wrap:\n$text",
        )
    }

    @Test
    fun `a live paint carries the part of a wrapped line that has scrolled off`() {
        // The other half of the same story, and where the pending line actually matters: for a
        // LIVE client nothing may be missing. Rows evicted under an unbroken soft wrap sit
        // outside historyLines() until the line's tail arrives, and a reflow evicts a whole
        // screenful of them at once — so an attach redraw that skipped them showed a session
        // whose newest paragraph simply began part-way through.
        val body = "LIVE-HEAD " + "wrapme ".repeat(80) + "LIVE-TAIL"
        val source = straddlingTheTop(60, 6, body)
        assertTrue(source.pendingHistoryLine() != null, "precondition: a line is mid-flight")

        val redraw = source.synthesizeRedraw()
        val receiver = SessionGrid(100, 30)
        receiver.feed(redraw, redraw.size)
        val text = receiver.logicalLines().joinToString("\n")

        assertTrue(text.contains("LIVE-HEAD"), "the evicted head must be in the paint:\n$text")
        assertTrue(text.contains("LIVE-TAIL"), "so must the tail still on screen:\n$text")
        assertTrue(
            text.contains(body),
            "and as ONE logical line, rewrapped at the receiver's width:\n$text",
        )
    }

    @Test
    fun `a resize before persisting does not change what is stored`() {
        // The live path: a client attaches and votes, so the grid reflows before the shutdown
        // that persists it. The reflow evicts rows — possibly ending on a wrapped one — and that
        // must not alter the content, only its layout.
        val source = SessionGrid(100, 10)
        source.feedText(prose())
        source.feedText(prose())
        val before = source.logicalLines()

        source.resize(70, 10)
        assertEquals(before, source.logicalLines(), "the reflow itself must preserve content")

        val restored = restore(source, 143, 40)
        assertEquals(before, restored.logicalLines())
    }
}
