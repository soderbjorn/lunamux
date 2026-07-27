package se.soderbjorn.lunamux.pty

import java.io.File
import kotlin.test.Test

/**
 * TEMPORARY analysis harness. Replays a captured Claude trace through SessionGrid with the
 * window verdict callback wired, and dumps — per window — what the reconciliation saw and
 * decided, plus the final duplication. Points at a file via LUNAMUX_ANALYZE_TRACE. Delete
 * with PtyTrace.
 */
class TraceAnalysis {

    @Test
    fun analyze() {
        val path = System.getenv("LUNAMUX_ANALYZE_TRACE") ?: return
        val events = PtyTrace.read(File(path))
        val firstResize = events.firstOrNull { it is PtyTraceEvent.Resize } as? PtyTraceEvent.Resize
        val startCols = firstResize?.cols ?: 80
        val startRows = firstResize?.rows ?: 24

        val verdicts = mutableListOf<WindowVerdict>()
        val grid = SessionGrid(startCols, startRows, onWindowResolved = { verdicts.add(it) })
        var applied = 0
        for (ev in events) when (ev) {
            is PtyTraceEvent.Output -> grid.feed(ev.bytes, ev.bytes.size)
            is PtyTraceEvent.Resize -> { grid.resize(ev.cols, ev.rows); applied++ }
        }

        val history = grid.historyLines().map { it.text }
        val nonBlank = history.filter { it.isNotBlank() }
        val worst = nonBlank.groupingBy { it }.eachCount()
            .filter { it.value > 1 }.entries.sortedByDescending { it.value }.take(12)

        println("=== TRACE ANALYSIS ===")
        println("events=${events.size} resizes=$applied windows=${verdicts.size}")
        println("history: total=${history.size} nonBlank=${nonBlank.size} distinct=${nonBlank.toSet().size}")
        println("--- verdicts ---")
        verdicts.forEachIndexed { idx, v ->
            println("  #$idx ${v.trigger} pending=${v.pending} dropped=${v.dropped} kept=${v.kept}" +
                if (v.droppedSample.isNotEmpty()) "  dropped=${v.droppedSample.map { it.take(40) }}" else "")
        }
        println("--- most-duplicated history lines ---")
        for (e in worst) println("  x${e.value}  ${e.key.take(72)}")
    }
}
