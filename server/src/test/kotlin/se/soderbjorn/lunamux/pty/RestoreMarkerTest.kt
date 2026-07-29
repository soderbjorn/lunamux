/**
 * Tests for [RestoreMarker] — the terminal-mode reset stamped after a cross-restart restore.
 *
 * The regression under test is upstream LMX-2's "restore twice and everything is gone". There,
 * the marker was persisted with a *byte ring* and re-read by `AltScreenTracker` on the next
 * restore; while it ended in an alternate-screen exit it looked like an orphaned span, and the
 * ingest path answered by dropping every byte recorded before it.
 *
 * **This branch has neither the ring nor the tracker.** A restored blob is fed to the canonical
 * [SessionGrid], and blobs are synthesized from its cells rather than accumulated as bytes. So
 * upstream's four tests that drove the marker through `AltScreenTracker` + `ReplaySanitizer`
 * cannot compile here; they are replaced by ones pinning the same *user-visible* property
 * against the mechanism this branch actually has — restore repeatedly, and the first session's
 * history is still there. Upstream's two pure [RestoreMarker] units are kept verbatim.
 *
 * @see RestoreMarker
 * @see SessionGrid.synthesizeForPersist
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoreMarkerTest {

    private val esc = "\u001b"

    /** The marker exactly as servers before the fix wrote it. */
    private val legacyMarker =
        "$esc[?9;1000;1001;1002;1003;1005;1006;1015l$esc[?1004l$esc[?2004l$esc[?1l$esc[?1047l$esc>"

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /**
     * Replay [blob] into a fresh grid the way `TerminalSession.init` does: the stored bytes, then
     * the mode marker, then the line break that keeps the new shell's prompt off the restored
     * content.
     */
    private fun restore(blob: String): SessionGrid {
        val grid = SessionGrid(80, 12)
        grid.feedText(blob)
        grid.feed(RestoreMarker.MODE_RESET, RestoreMarker.MODE_RESET.size)
        grid.feedText("\r\n")
        return grid
    }

    // ── upstream's marker units, unchanged ────────────────────────────────────

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
    fun blobWithoutAnyMarkerIsReturnedUnchanged() {
        val blob = "plain scrollback\r\n".toByteArray(Charsets.UTF_8)
        assertTrue(RestoreMarker.neutralizeLegacy(blob) === blob, "no-op must not copy")
    }

    // ── the same property, pinned against this branch's restore path ──────────

    @Test
    fun `restoring three generations keeps the first session's history`() {
        // LMX-2 stated in this branch's terms, and why the marker's shape still matters here:
        // every restore stamps another marker into the grid and every shutdown re-persists it.
        // If a marker could destroy what precedes it, generation 3 would come back holding only
        // generation 2 — "shut down, restart, quit, start again → every pane empty".
        var blob = "history from the first session\r\n"
        for (generation in 0..2) {
            val grid = restore(blob)
            grid.feedText("output from generation $generation\r\n")
            blob = String(grid.synthesizeForPersist(), Charsets.UTF_8)
        }

        val text = restore(blob).transcriptText()
        assertTrue(
            text.contains("history from the first session"),
            "the first session's history must survive every later restore:\n$text",
        )
        for (generation in 0..2) {
            assertTrue(
                text.contains("output from generation $generation"),
                "generation $generation was lost:\n$text",
            )
        }
    }

    @Test
    fun `a legacy marker in an old blob is inert here, not destructive`() {
        // Blobs written before the fix still carry the `ESC[?1047l` form. Fed to the grid it
        // selects a normal buffer that is already active — a no-op — which is why
        // [RestoreMarker.neutralizeLegacy] is deliberately not called on this path.
        val blob = "history from the first session\r\n$legacyMarker\r\n\r\nsecond session\r\n"

        val text = restore(blob).transcriptText()

        assertTrue(text.contains("history from the first session"), "history lost:\n$text")
        assertTrue(text.contains("second session"), "later output lost:\n$text")
    }

    @Test
    fun `several accumulated legacy markers are all inert`() {
        // A pane restored three times by a pre-fix server accumulated three markers in one blob.
        val blob = "gen1\r\n$legacyMarker gen2\r\n$legacyMarker gen3\r\n$legacyMarker gen4"

        val text = restore(blob).transcriptText()

        for (gen in listOf("gen1", "gen2", "gen3", "gen4")) {
            assertTrue(text.contains(gen), "$gen must survive:\n$text")
        }
    }

    @Test
    fun `a stray alternate-screen exit destroys nothing`() {
        // Upstream's tracker reads an unmatched exit as evidence that everything before it was
        // orphaned TUI paint, and drops it. The canonical grid takes the opposite,
        // faithful-recorder view, deliberately: it cannot mint that state itself (a persisted
        // blob is synthesized from cells, and a frozen TUI frame is inert styled text), so the
        // only way to meet one is a very old blob — and dropping a user's scrollback on the
        // strength of a single escape sequence is the worse failure.
        val blob = "earlier shell history${esc}[?1049lreal shell history\r\n"

        val text = restore(blob).transcriptText()

        assertTrue(text.contains("real shell history"), "later history lost:\n$text")
        assertTrue(
            text.contains("earlier shell history"),
            "earlier history must not be dropped:\n$text",
        )
    }

    @Test
    fun `the marker leaves no cells, so it cannot accumulate in a persisted blob`() {
        // The structural reason the hazard cannot return here whatever the marker contains:
        // blobs are synthesized from the grid's cells, and the marker is pure mode housekeeping.
        val grid = restore("visible history\r\n")

        val blob = String(grid.synthesizeForPersist(), Charsets.UTF_8)

        assertTrue(blob.contains("visible history"), "content must be persisted:\n$blob")
        assertFalse(blob.contains("$esc[?2004l"), "the marker must not be re-persisted:\n$blob")
        assertFalse(blob.contains("$esc[?1004l"), "nor any part of it — a marker in a blob is LMX-2")
    }
}
