/**
 * Tests for [RestoreMarker] — the terminal-mode reset stamped into a session's
 * replay ring after a cross-restart restore.
 *
 * The regression under test is LMX-2's "restore twice and everything is gone":
 * the marker is persisted with the ring, so it is re-read by [AltScreenTracker]
 * on the next restore. While it ended in an alternate-screen exit that read as
 * an orphaned span, the ingest path dropped every byte recorded before it.
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoreMarkerTest {

    private val esc = "\u001b"

    /** The marker exactly as servers before the fix wrote it. */
    private val legacyMarker =
        "$esc[?9;1000;1001;1002;1003;1005;1006;1015l$esc[?1004l$esc[?2004l$esc[?1l$esc[?1047l$esc>"

    /**
     * Accumulates the normal-buffer side of a partitioned stream, mirroring
     * `TerminalSession.ringSink`: an orphan exit clears what came before,
     * exactly as `mainRing.clear()` does during a restored-blob ingest.
     */
    private class Ingest : AltScreenTracker.Sink {
        val main = StringBuilder()
        var orphanExits = 0
        override fun onSegment(chunk: ByteArray, from: Int, until: Int, alt: Boolean) {
            if (!alt) main.append(String(chunk, from, until - from, Charsets.UTF_8))
        }
        override fun onSpanClosed() {}
        override fun onOrphanExit() {
            orphanExits++
            main.setLength(0)
        }
    }

    /** Run a blob through the restored-scrollback ingest path. */
    private fun ingest(blob: String): Ingest {
        val bytes = ReplaySanitizer.stripQueries(
            RestoreMarker.neutralizeLegacy(blob.toByteArray(Charsets.UTF_8))
        )
        return Ingest().also { AltScreenTracker().feed(bytes, bytes.size, it) }
    }

    @Test
    fun currentMarkerCarriesNoBufferSwitch() {
        val marker = String(RestoreMarker.MODE_RESET, Charsets.US_ASCII)
        assertFalse(marker.contains("1047"), "marker must not exit the alternate buffer")
        assertFalse(marker.contains("1049"), "marker must not exit the alternate buffer")
        assertFalse(marker.contains("[?47"), "marker must not exit the alternate buffer")
        // The modes it does exist to cancel are all still there.
        for (mode in listOf("1000", "1004", "2004")) {
            assertTrue(marker.contains(mode), "marker should still cancel mode $mode")
        }
    }

    @Test
    fun ingestingOurOwnMarkerKeepsTheHistoryBeforeIt() {
        val current = String(RestoreMarker.MODE_RESET, Charsets.US_ASCII)
        val blob = "history from the first session\r\n" + current + "\r\n\r\nsecond session\r\n"

        val ingest = ingest(blob)

        assertEquals(0, ingest.orphanExits, "our own marker must not read as an orphaned span")
        assertTrue(
            ingest.main.contains("history from the first session"),
            "history recorded before the marker must survive: '${ingest.main}'",
        )
        assertTrue(ingest.main.contains("second session"))
    }

    @Test
    fun legacyMarkerIsRewrittenSoOldBlobsKeepTheirHistory() {
        val blob = "history from the first session\r\n" + legacyMarker + "\r\n\r\nsecond session\r\n"

        val ingest = ingest(blob)

        assertEquals(0, ingest.orphanExits)
        assertTrue(
            ingest.main.contains("history from the first session"),
            "a blob written before the fix must not lose its history: '${ingest.main}'",
        )
    }

    @Test
    fun everyGenerationOfMarkerInAnOlderBlobIsRewritten() {
        // A pane restored three times accumulates three markers in one blob.
        val blob = "gen1\r\n$legacyMarker gen2\r\n$legacyMarker gen3\r\n$legacyMarker gen4"

        val ingest = ingest(blob)

        assertEquals(0, ingest.orphanExits)
        for (gen in listOf("gen1", "gen2", "gen3", "gen4")) {
            assertTrue(ingest.main.contains(gen), "$gen must survive: '${ingest.main}'")
        }
    }

    @Test
    fun aGenuineOrphanedAlternateSpanIsStillRecognized() {
        // A pre-span-tracking blob whose `?1049h` was evicted by the ring: the
        // surviving redraw bytes really are orphaned TUI paint and must go.
        val blob = "dead TUI redraw bytes$esc[?1049lreal shell history\r\n"

        val ingest = ingest(blob)

        assertEquals(1, ingest.orphanExits, "a real orphaned exit must still clean the ring")
        assertFalse(ingest.main.contains("dead TUI redraw"))
        assertTrue(ingest.main.contains("real shell history"))
    }

    @Test
    fun blobWithoutAnyMarkerIsReturnedUnchanged() {
        val blob = "plain scrollback\r\n".toByteArray(Charsets.UTF_8)
        assertTrue(RestoreMarker.neutralizeLegacy(blob) === blob, "no-op must not copy")
    }
}
