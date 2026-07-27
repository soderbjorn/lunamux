package se.soderbjorn.lunamux.pty

import java.io.File
import kotlin.test.Test

/**
 * TEMPORARY. Replays a captured trace and reports, per resize, the new grid dims and how many
 * banner/prompt copies exist in history afterward — to see whether duplication is minted at
 * short (phone) heights, tall (laptop) heights, or on every resize. Gated on
 * LUNAMUX_HEIGHT_TRACE. Delete with PtyTrace.
 */
class TraceHeight {

    @Test
    fun analyze() {
        val path = System.getenv("LUNAMUX_HEIGHT_TRACE") ?: return
        val events = PtyTrace.read(File(path))
        val first = events.firstOrNull { it is PtyTraceEvent.Resize } as? PtyTraceEvent.Resize
        val grid = SessionGrid(first?.cols ?: 80, first?.rows ?: 24)

        val banner = "Claude Code v"
        fun bannerCount() = grid.historyLines().count { it.text.contains(banner) }

        println("=== HEIGHT vs DUPLICATION ===")
        var last = 0
        for (ev in events) when (ev) {
            is PtyTraceEvent.Output -> grid.feed(ev.bytes, ev.bytes.size)
            is PtyTraceEvent.Resize -> {
                grid.resize(ev.cols, ev.rows)
                // Let any window/backstop settle by reading (reads resolve).
                val n = bannerCount()
                val delta = n - last
                last = n
                println("  resize ${ev.cols}x${ev.rows}  bannerCopies=$n  (${if (delta > 0) "+$delta minted" else "no change"})")
            }
        }
        println("final banner copies in history: ${bannerCount()}")
    }
}
