/**
 * Unit tests for [HistoryLog] — the append-only, never-reflowed scrollback.
 *
 * The property that matters and is pinned here: **assembly** — soft-wrapped rows fuse
 * into one logical line, so what is stored is independent of the width the content
 * happened to be written at.
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
        // Content equality must not depend on where a line happened to wrap, or
        // everything downstream (round-trip tests, dedup-by-eye) would be width-bound.
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
    fun `clear drops everything including a half-assembled line`() {
        val log = HistoryLog()
        log.appendRow(row("committed"), wrapped = false)
        log.appendRow(row("half"), wrapped = true)
        log.clear()

        assertEquals(emptyList(), log.texts())
        log.appendRow(row("after"), wrapped = false)
        assertEquals(listOf("after"), log.texts(), "the half-assembled line must not resurface")
    }

    // ── reflow boundaries ─────────────────────────────────────────────────────

    @Test
    fun `closeOpenLine commits a line left open by a wrapped last eviction`() {
        // The reflow hole: a re-layout whose last eviction still carries the wrap flag left
        // its runs stranded in the open line — absent from lines(), so absent from every
        // paint — with nothing to finish them.
        val log = HistoryLog()
        log.appendRow(row("archived head"), wrapped = true)
        assertEquals(0, log.size, "still open before the boundary")

        log.closeOpenLine()

        assertEquals(listOf("archived head"), log.texts())
    }

    @Test
    fun `closeOpenLine breaks the line rather than fusing the next eviction onto it`() {
        // The semantic: the archived head is its own logical line. Its continuation was not
        // archived — it was rewrapped at the new width and is still on the live screen — so
        // whatever scrolls off next is unrelated content and must not join it.
        val log = HistoryLog()
        log.appendRow(row("old width tail"), wrapped = true)
        log.closeOpenLine()
        log.appendRow(row("later output"), wrapped = false)

        assertEquals(listOf("old width tail", "later output"), log.texts())
    }

    @Test
    fun `closeOpenLine is a no-op when nothing is half-assembled`() {
        // Every resize calls it, and most evict nothing or end on an unwrapped row; none of
        // those may plant a stray blank line in the scrollback.
        val log = HistoryLog()
        log.appendRow(row("done"), wrapped = false)

        log.closeOpenLine()
        log.closeOpenLine()

        assertEquals(listOf("done"), log.texts())
    }

    @Test
    fun `closeOpenLine keeps the styles of the head it commits`() {
        val log = HistoryLog()
        log.appendRow(listOf(StyledRun("bo", bold), StyledRun("ld", bold)), wrapped = true)
        log.closeOpenLine()

        assertEquals(listOf(LogicalLine(listOf(StyledRun("bold", bold)))), log.lines())
    }

    @Test
    fun `an empty logical line reports itself empty`() {
        assertTrue(LogicalLine(emptyList()).isEmpty)
        assertTrue(LogicalLine(listOf(StyledRun("", plain))).isEmpty)
        assertFalse(LogicalLine(listOf(StyledRun(" ", plain))).isEmpty)
    }
}
