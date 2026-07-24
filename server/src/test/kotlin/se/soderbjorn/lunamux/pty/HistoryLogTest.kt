/**
 * Unit tests for [HistoryLog] — the append-only, never-reflowed scrollback.
 *
 * Two properties matter and are pinned here:
 *  - **assembly**: soft-wrapped rows fuse into one logical line, so what is stored is
 *    independent of the width the content happened to be written at;
 *  - **window safety**: a reconciliation window can only ever affect lines that left the
 *    screen inside it. Established history must be unreachable from a window verdict —
 *    that is the whole difference from the transcript truncation this replaces.
 *
 * @see HistoryLog
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryLogTest {

    private val plain = 0L
    private val bold = 1L

    private fun row(text: String, style: Long = 0L) = listOf(StyledRun(text, style))

    private fun HistoryLog.texts(): List<String> = lines().map { it.text }

    // ── assembly ──────────────────────────────────────────────────────────────

    @Test
    fun `an unwrapped row commits one line`() {
        val log = HistoryLog()
        log.appendRow(row("hello"), wrapped = false)
        assertEquals(listOf("hello"), log.texts())
    }

    @Test
    fun `wrapped rows fuse into a single logical line`() {
        // The same content written at a narrow width arrives as several rows; history
        // must hold one line, or the width it was authored at would be baked in.
        val log = HistoryLog()
        log.appendRow(row("the quick "), wrapped = true)
        log.appendRow(row("brown fox "), wrapped = true)
        log.appendRow(row("jumps"), wrapped = false)
        assertEquals(listOf("the quick brown fox jumps"), log.texts())
    }

    @Test
    fun `a line spanning several rows equals the same line written in one row`() {
        // The property the window reconciliation depends on: whether two identical lines
        // compare equal must not depend on where they wrapped.
        val wide = HistoryLog()
        wide.appendRow(row("abcdefghij"), wrapped = false)

        val narrow = HistoryLog()
        narrow.appendRow(row("abcde"), wrapped = true)
        narrow.appendRow(row("fghij"), wrapped = false)

        assertEquals(wide.lines(), narrow.lines())
    }

    @Test
    fun `styles survive assembly and adjacent same-style runs merge`() {
        val log = HistoryLog()
        log.appendRow(listOf(StyledRun("bo", bold), StyledRun("ld", bold)), wrapped = true)
        log.appendRow(listOf(StyledRun(" plain", plain)), wrapped = false)

        assertEquals(
            listOf(LogicalLine(listOf(StyledRun("bold", bold), StyledRun(" plain", plain)))),
            log.lines(),
        )
    }

    @Test
    fun `a blank row is a real empty history line`() {
        // Blank lines are content: a program's spacing must survive, or restored history
        // reads as a wall of text.
        val log = HistoryLog()
        log.appendRow(row("above"), wrapped = false)
        log.appendRow(emptyList(), wrapped = false)
        log.appendRow(row("below"), wrapped = false)
        assertEquals(listOf("above", "", "below"), log.texts())
    }

    @Test
    fun `an unfinished line is not committed until it ends`() {
        val log = HistoryLog()
        log.appendRow(row("start"), wrapped = true)
        assertEquals(0, log.size, "a still-wrapping line is not history yet")
        log.appendRow(row("end"), wrapped = false)
        assertEquals(listOf("startend"), log.texts())
    }

    @Test
    fun `retention evicts the oldest lines`() {
        val log = HistoryLog(maxLines = 3)
        for (i in 1..5) log.appendRow(row("line $i"), wrapped = false)
        assertEquals(listOf("line 3", "line 4", "line 5"), log.texts())
    }

    @Test
    fun `tail returns the newest lines oldest-first`() {
        val log = HistoryLog()
        for (i in 1..5) log.appendRow(row("line $i"), wrapped = false)
        assertEquals(listOf("line 4", "line 5"), log.tail(2).map { it.text })
        assertEquals(5, log.tail(99).size, "asking for more than exists returns all")
        assertEquals(emptyList(), log.tail(0))
    }

    // ── the window ────────────────────────────────────────────────────────────

    @Test
    fun `lines that leave inside a window are held, not committed`() {
        val log = HistoryLog()
        log.appendRow(row("history"), wrapped = false)

        log.beginWindow()
        log.appendRow(row("frame top"), wrapped = false)

        assertEquals(listOf("history"), log.texts(), "pending lines are not history yet")
        assertEquals(listOf("frame top"), log.pendingLines().map { it.text })
    }

    @Test
    fun `commitWindow keeps the pending lines`() {
        val log = HistoryLog()
        log.appendRow(row("history"), wrapped = false)
        log.beginWindow()
        log.appendRow(row("scrolled off for real"), wrapped = false)
        log.commitWindow()

        assertEquals(listOf("history", "scrolled off for real"), log.texts())
        assertFalse(log.windowOpen)
    }

    @Test
    fun `discardWindow drops the pending lines and nothing else`() {
        // The safety property. A discard is the verdict for a program that re-rendered
        // what scrolled off; it must be incapable of reaching the history above it.
        val log = HistoryLog()
        log.appendRow(row("established 1"), wrapped = false)
        log.appendRow(row("established 2"), wrapped = false)

        log.beginWindow()
        log.appendRow(row("redundant frame top"), wrapped = false)
        log.discardWindow()

        assertEquals(listOf("established 1", "established 2"), log.texts())
        assertFalse(log.windowOpen)
    }

    @Test
    fun `closeWindow keeps only the lines from the given index`() {
        // The middle verdict: the repaint reclaimed the first two lines, the third
        // genuinely scrolled away and must survive.
        val log = HistoryLog()
        log.appendRow(row("established"), wrapped = false)

        log.beginWindow()
        log.appendRow(row("reclaimed 1"), wrapped = false)
        log.appendRow(row("reclaimed 2"), wrapped = false)
        log.appendRow(row("genuine"), wrapped = false)
        log.closeWindow(keepFrom = 2)

        assertEquals(listOf("established", "genuine"), log.texts())
    }

    @Test
    fun `closeWindow bounds are forgiving in both directions`() {
        val keepAll = HistoryLog()
        keepAll.beginWindow()
        keepAll.appendRow(row("a"), wrapped = false)
        keepAll.closeWindow(keepFrom = -5)
        assertEquals(listOf("a"), keepAll.texts())

        val keepNone = HistoryLog()
        keepNone.beginWindow()
        keepNone.appendRow(row("a"), wrapped = false)
        keepNone.closeWindow(keepFrom = 99)
        assertEquals(emptyList(), keepNone.texts())
    }

    @Test
    fun `beginWindow is idempotent across a resize burst`() {
        // A take-over commonly fires a cols change and then a rows-only keyboard adjust.
        // Re-arming on the second would open a window that captures only the tail of the
        // program's response, leaving the first resize's overflow already committed —
        // which is exactly how the reverted approach failed.
        val log = HistoryLog()
        log.beginWindow()
        log.appendRow(row("from the first resize"), wrapped = false)
        log.beginWindow() // second resize in the same burst
        log.appendRow(row("from the second"), wrapped = false)

        assertEquals(
            listOf("from the first resize", "from the second"),
            log.pendingLines().map { it.text },
            "one window must span the whole burst",
        )
        log.discardWindow()
        assertEquals(emptyList(), log.texts())
    }

    @Test
    fun `a line half-assembled when the window opens still commits as one line`() {
        val log = HistoryLog()
        log.appendRow(row("start of a "), wrapped = true)
        log.beginWindow()
        log.appendRow(row("wrapped line"), wrapped = false)
        log.commitWindow()
        assertEquals(listOf("start of a wrapped line"), log.texts())
    }

    @Test
    fun `verdict calls with no window open are no-ops`() {
        val log = HistoryLog()
        log.appendRow(row("history"), wrapped = false)
        log.commitWindow()
        log.discardWindow()
        log.closeWindow(keepFrom = 0)
        assertEquals(listOf("history"), log.texts())
    }

    @Test
    fun `clear drops everything including a half-assembled line`() {
        val log = HistoryLog()
        log.appendRow(row("committed"), wrapped = false)
        log.appendRow(row("half"), wrapped = true)
        log.beginWindow()
        log.clear()

        assertEquals(emptyList(), log.texts())
        assertFalse(log.windowOpen)
        log.appendRow(row("after"), wrapped = false)
        assertEquals(listOf("after"), log.texts(), "the half-assembled line must not resurface")
    }

    @Test
    fun `an empty logical line reports itself empty`() {
        assertTrue(LogicalLine(emptyList()).isEmpty)
        assertTrue(LogicalLine(listOf(StyledRun("", plain))).isEmpty)
        assertFalse(LogicalLine(listOf(StyledRun(" ", plain))).isEmpty)
    }
}
