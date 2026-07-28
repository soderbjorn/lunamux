/**
 * Tests for [SessionGrid] — the canonical server-side screen wrapping the
 * vendored Termux emulator:
 *  - fed bytes are interpreted into a readable transcript;
 *  - a width change runs the emulator's reflow without losing content;
 *  - device queries are discarded while no answer sink is armed (proving nothing can
 *    escape toward a PTY on the restore path), and reach the sink in order once armed —
 *    the grid is the session's single answerer;
 *  - alternate-buffer state and the new serialization getters are readable;
 *  - a malformed sequence never throws out of [SessionGrid.feed].
 */
package se.soderbjorn.lunamux.pty

import se.soderbjorn.lunamux.TerminalInputClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionGridTest {

    private val esc = "\u001b"

    private fun SessionGrid.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    @Test
    fun `feed produces readable transcript`() {
        val grid = SessionGrid(80, 24)
        grid.feed("hello\r\nworld")
        val text = grid.transcriptText()
        assertTrue(text.contains("hello") && text.contains("world"), "transcript should contain fed text, was: $text")
    }

    @Test
    fun `resize reflows without losing content`() {
        val grid = SessionGrid(120, 24)
        // 80 'a's fit on one row at 120 cols (no pending wrap since 80 < 120).
        grid.feed("a".repeat(80))
        assertEquals(80, grid.transcriptText().count { it == 'a' })
        // Narrowing forces the emulator's wrap-flag-faithful reflow; content is preserved.
        grid.resize(40, 24)
        assertEquals(80, grid.transcriptText().count { it == 'a' }, "all 80 chars survive the reflow")
    }

    @Test
    fun `device queries are discarded while no answer sink is armed`() {
        val grid = SessionGrid(80, 24)
        // OSC 10 color query — the emulator replies (see OperatingSystemControlTest);
        // that reply lands in the discard sink and is counted, proving the grid
        // answers-and-drops rather than leaking to any PTY (it has no PTY handle).
        grid.feed("$esc]10;?")
        grid.feed("after")
        assertTrue(grid.discardedOutputBytes > 0, "emulator answered the query into the sink")
        assertTrue(grid.transcriptText().contains("after"), "text after a query still renders")
    }

    @Test
    fun `an armed sink receives the answers in order`() {
        // The grid is the terminal's single answerer: its replies are what actually reaches
        // the shell, so they must arrive complete and in the order the emulator produced them.
        val answers = mutableListOf<String>()
        val grid = SessionGrid(80, 24) { answers.add(it.toString(Charsets.UTF_8)) }

        grid.feed("$esc[6n")   // DSR-6: cursor position report
        grid.feed("$esc[5n")   // DSR-5: device status report
        grid.feed("$esc[0c")   // DA1: device attributes

        assertEquals(3, answers.size, "one answer per query, got $answers")
        assertTrue(answers[0].endsWith("R"), "first answer is the cursor position report: ${answers[0]}")
        assertEquals("$esc[0n", answers[1], "second answer is the device status report")
        assertTrue(answers[2].endsWith("c"), "third answer is the device attributes report: ${answers[2]}")
        assertEquals(0L, grid.discardedOutputBytes, "nothing is discarded once a sink is armed")
    }

    @Test
    fun `answers reaching the sink are exactly what the shared classifier calls a device reply`() {
        // The classifier is what the server uses to drop the clients' duplicate answers; the
        // grid's own answers must be the same shape, or the two halves of the single-answerer
        // rule would disagree about what a device reply is.
        val answers = mutableListOf<ByteArray>()
        val grid = SessionGrid(80, 24) { answers.add(it) }

        grid.feed("$esc[6n$esc[5n$esc[>0c")

        assertTrue(answers.isNotEmpty(), "the grid answered")
        for (answer in answers)
            assertTrue(
                TerminalInputClassifier.isDeviceReply(answer),
                "the classifier must recognise the grid's own answer: ${answer.toString(Charsets.UTF_8)}",
            )
    }

    @Test
    fun `a restore feed containing a query produces no sink output`() {
        // The restore path replays a DEAD session's bytes, and a legacy raw blob can contain
        // queries. Answering one would push a reply for a query nobody asked into the fresh
        // shell's stdin, where ZLE reads it as typed input — so the sink is armed only after
        // the restore feed, and this pins that ordering.
        val answers = mutableListOf<ByteArray>()
        val grid = SessionGrid(80, 24)

        // A properly terminated OSC query (BEL), as a legacy raw blob would carry it: left
        // unterminated the emulator would swallow the rest of the blob as OSC payload.
        grid.feed("restored scrollback $esc[6n$esc]10;?\u0007 and more\r\n")
        grid.armAnswerSink { answers.add(it) }

        assertTrue(answers.isEmpty(), "a query replayed from history must never be answered")
        assertTrue(grid.discardedOutputBytes > 0, "it was answered into nothing, not left unparsed")

        // Live queries after arming do reach the sink.
        grid.feed("$esc[6n")
        assertEquals(1, answers.size, "the sink is live from here on")
    }

    @Test
    fun `alternate buffer state is readable`() {
        val grid = SessionGrid(80, 24)
        assertFalse(grid.read { it.isAlternateBufferActive() })
        grid.feed("$esc[?1049h")
        assertTrue(grid.read { it.isAlternateBufferActive() })
        grid.feed("$esc[?1049l")
        assertFalse(grid.read { it.isAlternateBufferActive() })
    }

    @Test
    fun `serialization getters reflect fed state`() {
        val grid = SessionGrid(80, 24)
        assertTrue(grid.read { it.isAutoWrapEnabled() })
        grid.feed("$esc[?7l")
        assertFalse(grid.read { it.isAutoWrapEnabled() })
        grid.feed("$esc[?2004h")
        assertTrue(grid.read { it.isBracketedPasteMode() })
    }

    @Test
    fun `malformed sequence does not throw`() {
        val grid = SessionGrid(80, 24)
        // Truncated/garbage CSI followed by normal text must not propagate.
        grid.feed("$esc[999999999999999999;;;;x normal")
        assertTrue(grid.transcriptText().contains("normal"))
    }

    @Test
    fun `sub-minimum resize is ignored`() {
        val grid = SessionGrid(80, 24)
        grid.feed("keep")
        grid.resize(1, 1) // below the emulator's 2x2 floor — no-op, no throw
        assertTrue(grid.transcriptText().contains("keep"))
    }

    @Test
    fun `synthesizeRedraw reconstructs the grid into a fresh grid`() {
        val grid = SessionGrid(40, 8)
        grid.feed("hello${esc}[31m red${esc}[0m\r\nsecond")
        val redraw = grid.synthesizeRedraw()
        assertTrue(redraw.isNotEmpty())

        val fresh = SessionGrid(40, 8)
        fresh.feed(redraw, redraw.size)
        assertEquals(grid.transcriptText(), fresh.transcriptText())
        // The synthesized redraw is constructed paint — it must carry no device queries.
        assertEquals(0L, fresh.discardedOutputBytes)
    }

    @Test
    fun `attach snapshot carries scrollback, not just the visible screen`() {
        // Regression: attachPayload() used to serialize the emulator alone, so a
        // fresh attach painted only the visible screen and showed no scrollback
        // until the next cols change happened to fire a resync.
        val grid = SessionGrid(40, 5)
        for (i in 1..12) grid.feed("line-%02d\r\n".format(i))
        val snap = grid.attachSnapshot()
        val text = snap.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("line-01"), "a line long since scrolled off must be in the attach paint")
        assertTrue(text.contains("line-12"), "the visible screen must be in the attach paint")
        assertEquals(40, snap.cols)
        assertEquals(5, snap.rows)
    }

    @Test
    fun `synthesizeForPersist round-trips committed scrollback into a fresh grid`() {
        // This is the Phase 4 persistence contract: persistSnapshot() bytes fed into a
        // fresh grid on restart reconstruct the scrollback. Scoped to *committed*
        // lines — everything up to the last newline. The live line (the row the cursor
        // sits on) is deliberately excluded, because the shell spawned on restore
        // re-emits its own prompt and persisting the old one stacked a duplicate; see
        // `persist form restores history followed by exactly one fresh prompt`.
        // Every line here is newline-terminated, so all of it is committed.
        val grid = SessionGrid(40, 8)
        grid.feed("line one\r\nline two\r\nline three\r\n")
        val blob = grid.synthesizeForPersist()

        val restored = SessionGrid(40, 8)
        restored.feed(blob, blob.size)
        assertEquals(grid.transcriptText(), restored.transcriptText())
    }

    @Test
    fun `persist form restores history followed by exactly one fresh prompt`() {
        // The persist form deliberately carries no cursor epilogue (#91), so wherever
        // its row flow stops is where the restored grid's cursor is left — and the
        // shell spawned on restore writes its first prompt from there. Two faults fed
        // the same symptom, a stacked prompt accumulating on every restore:
        //  - emitting the screen's trailing blank rows parked the cursor at the bottom
        //    of a screenful of blanks, so the new prompt appeared far below with a big
        //    gap above it; and
        //  - persisting the cursor's own row committed the LIVE prompt line to
        //    history, which the fresh shell then duplicated immediately below.
        val grid = SessionGrid(40, 20)
        grid.feed("line one\r\nline two\r\nprompt$ ")
        val blob = grid.synthesizeForPersist()

        val restored = SessionGrid(40, 20)
        restored.feed(blob, blob.size)

        // Committed lines only: rows 0..1. The live prompt row is not history, and the
        // cursor is left right after the last committed line — not on row 19.
        assertEquals(1, restored.read { it.cursorRow }, "cursor must sit after the committed content")
        assertFalse(
            restored.transcriptText().contains("prompt$"),
            "the live prompt line must not be persisted as history",
        )

        // No leading blank rows either: the restored history must start at its first
        // real line, not be pushed down by empty rows above it.
        assertFalse(
            restored.transcriptText().trimEnd().startsWith("\n"),
            "restored history must not begin with blank rows",
        )

        // The shell spawned on restore prints its prompt; it must land directly under
        // the restored history, exactly once and with no blank gap.
        restored.feed("\r\nprompt$ ")
        val text = restored.transcriptText().trimEnd()
        assertEquals(
            1,
            text.split("\n").count { it.contains("prompt$") },
            "exactly one prompt line after restore: <$text>",
        )
        assertFalse(text.contains("\n\n"), "no blank rows between history and the prompt: <$text>")
    }

    @Test
    fun `persist form does not resurrect terminal modes`() {
        // A dead full-screen app must not re-enable sticky modes on restore (#91):
        // serializeForPersist carries no mode epilogue.
        val grid = SessionGrid(40, 8)
        // Newline-terminated so the text is committed scrollback: the persist form
        // carries only committed lines, so a bare "content" would be the live line and
        // legitimately absent from the restore.
        grid.feed("$esc[?2004h$esc[?1000h$esc[?1006hcontent\r\n")   // bracketed paste + mouse on
        assertTrue(grid.read { it.isBracketedPasteMode })

        val blob = grid.synthesizeForPersist()
        val restored = SessionGrid(40, 8)
        restored.feed(blob, blob.size)
        assertFalse(restored.read { it.isBracketedPasteMode }, "bracketed paste must not resurrect")
        assertFalse(restored.read { it.isMouseTrackingPressRelease }, "mouse tracking must not resurrect")
        assertTrue(restored.transcriptText().contains("content"))
    }

    @Test
    fun `ED3 clears external history so a cleared session does not re-emit it`() {
        // The split regressed `clear`: ESC[3J wipes the emulator transcript (empty here,
        // since history lives outside it), but the external HistoryLog survived and the next
        // redraw re-emitted everything — the "my earlier prompts came back" report.
        val grid = SessionGrid(80, 24)
        val a = "first prompt output\r\n".toByteArray()
        val b = "second prompt output\r\n".toByteArray()
        // Enough lines to push content into history, not just the visible screen.
        repeat(40) { grid.feed(a, a.size) }
        grid.feed(b, b.size)
        assertTrue(grid.transcriptText().contains("first prompt output"), "precondition: history holds it")

        // `clear` emits home + erase-screen + erase-scrollback.
        val clear = "\u001b[H\u001b[2J\u001b[3J".toByteArray()
        grid.feed(clear, clear.size)

        val text = grid.transcriptText()
        assertTrue(!text.contains("first prompt output"), "cleared history must not survive")
        assertTrue(!text.contains("second prompt output"), "nor the most recent lines")
    }

    @Test
    fun `a redraw synthesized after clear carries no scrollback`() {
        val grid = SessionGrid(80, 24)
        val line = "some earlier work\r\n".toByteArray()
        repeat(50) { grid.feed(line, line.size) }
        val clear = "\u001b[H\u001b[2J\u001b[3J".toByteArray()
        grid.feed(clear, clear.size)

        val redraw = grid.synthesizeRedraw()
        val fresh = SessionGrid(80, 24)
        fresh.feed(redraw, redraw.size)
        assertTrue(
            !fresh.transcriptText().contains("some earlier work"),
            "a client attaching after clear must not receive the cleared history",
        )
    }
}
