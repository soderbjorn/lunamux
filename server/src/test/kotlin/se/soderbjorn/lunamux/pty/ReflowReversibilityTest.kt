/**
 * Pins the property that makes take-over safe: resizing the canonical grid is
 * REVERSIBLE, so a narrow phase costs nothing permanently.
 *
 * Take-over resizes the shared PTY — a phone taking a laptop's session narrows the
 * canonical grid, and a later take-back widens it again. Everything hangs on whether
 * the wide content survives that round trip. If it does, a narrow phase only looks
 * wrong while it lasts and each device can keep editing at its own native size. If it
 * did not, every take-over would permanently degrade history for every client, and
 * the sizing model would have to be redesigned so that some device never got a native
 * grid. This test is the difference between those two worlds, so it is worth pinning.
 *
 * Box-drawn tables are the hard case: each row is its own hard line, so a naive
 * reflow that ignores wrap flags shreds them. Combined with GridSerializerRoundTripTest
 * (the synthesized redraw reproduces the grid faithfully), this establishes that a
 * client taking back over at its original width sees its content intact.
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals

class ReflowReversibilityTest {

    private fun SessionGrid.feedLine(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /** A box-drawn table: each row is its own hard line, the case that mangles. */
    private fun feedTable(grid: SessionGrid) {
        grid.feedLine("┌──────────────┬──────────────────────────────────────────────────┐\r\n")
        grid.feedLine("│ Part         │ What                                             │\r\n")
        grid.feedLine("├──────────────┼──────────────────────────────────────────────────┤\r\n")
        grid.feedLine("│ apps/backend │ Fastify API + BullMQ workers, PostgreSQL, Redis   │\r\n")
        grid.feedLine("│ apps/web     │ Electron + React 19 desktop app, Tailwind, shadcn │\r\n")
        grid.feedLine("└──────────────┴──────────────────────────────────────────────────┘\r\n")
    }

    @Test
    fun `wide table survives a narrow round trip`() {
        val grid = SessionGrid(100, 30)
        feedTable(grid)
        val original = grid.transcriptText().trimEnd()

        // Phone takes over: the PTY narrows and the canonical grid reflows.
        grid.resize(60, 30)
        // Laptop takes back over: the PTY widens again.
        grid.resize(100, 30)

        assertEquals(
            original,
            grid.transcriptText().trimEnd(),
            "a narrow→wide round trip must not damage history",
        )
    }

    @Test
    fun `repeated take-over churn does not accumulate damage`() {
        // Taking over back and forth is the reported scenario, so make sure the loss
        // is not merely small-per-resize and cumulative.
        val grid = SessionGrid(100, 30)
        feedTable(grid)
        val original = grid.transcriptText().trimEnd()

        repeat(8) {
            grid.resize(60, 30)
            grid.resize(100, 30)
        }

        assertEquals(
            original,
            grid.transcriptText().trimEnd(),
            "history must not erode across repeated take-overs",
        )
    }
}
