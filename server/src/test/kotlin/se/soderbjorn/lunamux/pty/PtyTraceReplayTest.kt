/**
 * Replays a captured real PTY session ([PtyTrace]) through [SessionGrid] and reports what
 * the canonical grid's history looks like afterwards.
 *
 * This is the discipline the reverted attempt lacked and the first split rule also lacked:
 * the reconciliation was written against a synthetic full-screen repaint, and on device it
 * matched the wrong thing because a real program (Claude Code, React + Ink) does not repaint
 * one screenful — it re-emits its whole managed view, most of which scrolls straight back
 * off. A byte-exact trace turns "what does it actually emit" from a guess into a fixture.
 *
 * To use: capture a run with `LUNAMUX_PTY_TRACE=/tmp/session.ptytrace`, then point
 * `LUNAMUX_REPLAY_TRACE` at that file and run this test. Without the env var the test is a
 * skip-style no-op, so it never fails CI; it is a development harness, not a gate. Remove
 * with [PtyTrace] before upstream.
 *
 * @see PtyTrace the recorder
 */
package se.soderbjorn.lunamux.pty

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PtyTraceReplayTest {

    @Test
    fun `replay a captured session and report duplication`() {
        val tracePath = System.getenv("LUNAMUX_REPLAY_TRACE")
            ?: System.getProperty("lunamux.replayTrace")
        if (tracePath == null) {
            println("PtyTraceReplayTest: no LUNAMUX_REPLAY_TRACE set — skipping")
            return
        }
        val file = File(tracePath)
        assertTrue(file.exists(), "trace file not found: $tracePath")

        val events = PtyTrace.read(file)
        val resizes = events.count { it is PtyTraceEvent.Resize }
        val firstResize = events.firstOrNull { it is PtyTraceEvent.Resize } as? PtyTraceEvent.Resize
        // The grid must start at the width the capture began at, or the first chunks (authored
        // for that width before any resize) mis-wrap. Fall back to a common width if the trace
        // opens with output before its first resize.
        val startCols = firstResize?.cols ?: 80
        val startRows = firstResize?.rows ?: 24

        val grid = SessionGrid(startCols, startRows)
        for (ev in events) when (ev) {
            is PtyTraceEvent.Output -> grid.feed(ev.bytes, ev.bytes.size)
            is PtyTraceEvent.Resize -> grid.resize(ev.cols, ev.rows)
        }

        val history = grid.historyLines()
        val transcript = grid.transcriptText()

        // Report the shape so a run is legible without a debugger: total lines, how many are
        // distinct, and the worst repeated blocks — a total running well ahead of distinct is
        // duplication.
        val lines = history.map { it.text }
        val nonBlank = lines.filter { it.isNotBlank() }
        val distinct = nonBlank.toHashSet().size
        val worst = nonBlank.groupingBy { it }.eachCount()
            .filter { it.value > 1 }
            .entries.sortedByDescending { it.value }
            .take(8)

        println("── PtyTraceReplay ──")
        println("events=${events.size} resizes=$resizes start=${startCols}x$startRows")
        println("history lines=${lines.size} nonBlank=${nonBlank.size} distinct=$distinct")
        println("transcript chars=${transcript.length}")
        println("most-repeated history lines:")
        for (e in worst) println("  ×${e.value}  ${e.key.take(80)}")

        // Not an assertion on the count — the whole point is to SEE it — but a guard that the
        // replay produced something, so a broken trace fails loudly rather than silently.
        assertTrue(lines.isNotEmpty(), "replay produced no history")
    }
}
