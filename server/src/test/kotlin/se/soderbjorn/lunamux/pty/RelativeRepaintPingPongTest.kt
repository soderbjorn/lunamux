/**
 * The umbrella regression test for canonical-state integrity across a device ping-pong.
 *
 * Every canonical-state mangling report from on-device testing — spliced echo, duplicated
 * prompt lines, interleaved transcript — reduced to the same question: after a session has
 * been resized back and forth between a laptop and a phone, does the canonical state still
 * hold every line the session wrote, exactly once, in the order it was written? The two
 * structural holes behind those reports were both invisible to narrower tests:
 *
 *  - a rows-only shrink corrupted the screen-only ring silently, and fires no client resync,
 *    so nothing surfaced until a later columns change baked it into immutable history;
 *  - a reflow whose last eviction was a wrapped row left that row absent from every paint
 *    until an unrelated later eviction fused onto it.
 *
 * So this test asserts the **composite** — committed history plus the live screen, read as
 * one document — rather than either half, and drives it with the repaint shape a plain shell
 * actually uses: zsh's ZLE answers SIGWINCH *relatively* (`\r`, `ESC[<n>A`, `ESC[J`, reprint),
 * not with the absolute `ESC[H` full-frame repaint [TakeOverDuplicationTest] models for
 * full-screen TUIs. A relative repaint erases only what it is about to rewrite, so unlike the
 * TUI case there is no faithful duplicate to account for: every line must appear exactly once.
 *
 * Each scenario also asserts the state survives serialization, since a client never sees the
 * grid — it sees the synthesized redraw.
 *
 * @see TakeOverDuplicationTest the full-screen-TUI counterpart, where one duplicate per
 *   narrowing is the faithful answer
 * @see ReflowReversibilityTest reflow losslessness on its own
 * @see GridSerializerRoundTripTest the cell-for-cell serializer contract
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelativeRepaintPingPongTest {

    private fun SessionGrid.feedText(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    // ── the session under test ────────────────────────────────────────────────

    /**
     * Command output, one uniquely tagged line per row. Short enough never to wrap at either
     * test width, so a line's identity can never be confused with the way a width happened to
     * fold it — this test is about presence and order, and [ReflowReversibilityTest] owns
     * wrapping.
     */
    private fun outputLines(range: IntRange): String =
        range.joinToString("") { "L%03d output line\r\n".format(it) }

    /**
     * How zsh's ZLE answers SIGWINCH: return to column 0, walk back up over the rows the
     * prompt block occupies, erase from there to the end of the screen, and reprint. Relative
     * throughout — it never homes the cursor — so it rewrites only its own prompt and leaves
     * the command output above it alone.
     *
     * @param promptRowsUp how many rows up the prompt block starts from the cursor.
     * @param editBuffer the command line being edited, reprinted after the prompt.
     */
    private fun zshRepaint(promptRowsUp: Int, editBuffer: String): String = buildString {
        append("\r")
        if (promptRowsUp > 0) append("$ESC[${promptRowsUp}A")
        append("$ESC[J")
        append(PROMPT)
        append(editBuffer)
    }

    // ── reading the composite ─────────────────────────────────────────────────

    /**
     * The session as one document: committed logical lines followed by the live screen — the
     * only view that can catch a line falling into the gap between the two halves.
     */
    private fun SessionGrid.composite(): String = transcriptText()

    /**
     * Every tagged output line in the composite, in the order it is read. Presence, order and
     * multiplicity all fall out of comparing this to the range that was written.
     */
    private fun SessionGrid.taggedLines(): List<String> =
        TAG.findAll(composite()).map { it.value }.toList()

    private fun tagsFor(range: IntRange): List<String> = range.map { "L%03d".format(it) }

    /**
     * Assert the composite holds exactly [range], once each and in order.
     *
     * Reported as a single list comparison rather than per-line assertions on purpose: a
     * dropped line, a duplicate and a rotation all read differently in the diff, and the
     * rotated-ring bug produced the third.
     */
    private fun assertHoldsExactly(grid: SessionGrid, range: IntRange, label: String) {
        assertEquals(tagsFor(range), grid.taggedLines(), "$label: composite lines")
    }

    /**
     * Assert a client would see the same session: serialize the grid, feed the bytes to a
     * fresh grid of the same dims, and compare both halves of the composite. A client never
     * reads the grid — it reads this redraw — so canonical state that cannot be serialized is
     * canonical state no client will ever show.
     */
    private fun assertSurvivesSerialization(grid: SessionGrid, label: String) {
        val bytes = grid.synthesizeRedraw()
        val dims = grid.read { it.mColumns to it.mRows }
        val receiver = SessionGrid(dims.first, dims.second)
        receiver.feed(bytes, bytes.size)

        assertEquals(grid.historyLines(), receiver.historyLines(), "$label: history round-trips")
        assertEquals(grid.taggedLines(), receiver.taggedLines(), "$label: composite round-trips")
    }

    // ── scenarios ─────────────────────────────────────────────────────────────

    @Test
    fun `a laptop-phone-laptop ping-pong holds every line exactly once and in order`() {
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(outputLines(1..40))
        grid.feedText(PROMPT + EDIT_BUFFER)

        // Phone takes over: the cols change from the new device, then a rows-only adjust as
        // its soft keyboard settles. The rows-only step is the one that fires no resync.
        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(zshRepaint(0, EDIT_BUFFER))
        grid.resize(NARROW_COLS, KEYBOARD_ROWS)
        grid.feedText(zshRepaint(0, EDIT_BUFFER))

        assertHoldsExactly(grid, 1..40, "on the phone")

        // Keyboard hides again, then the laptop takes it back.
        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(zshRepaint(0, EDIT_BUFFER))
        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(zshRepaint(0, EDIT_BUFFER))

        assertHoldsExactly(grid, 1..40, "back on the laptop")
        assertSurvivesSerialization(grid, "after the ping-pong")
    }

    @Test
    fun `a rows-only keyboard flutter loses and reorders nothing`() {
        // The specific trigger of the ring corruption: rows change, columns do not, repeatedly.
        // Every one of these took the fast path, and none of them fires a client resync, so a
        // rotated or aliased ring stayed invisible until a later columns change baked it in.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText(outputLines(1..30))
        grid.feedText(PROMPT + EDIT_BUFFER)

        repeat(6) {
            grid.resize(NARROW_COLS, KEYBOARD_ROWS)
            grid.feedText(zshRepaint(0, EDIT_BUFFER))
            grid.resize(NARROW_COLS, NARROW_ROWS)
            grid.feedText(zshRepaint(0, EDIT_BUFFER))
        }

        assertHoldsExactly(grid, 1..30, "after six keyboard flutters")

        // And the corruption's real payload: the columns change that used to bake it into
        // immutable history and repaint every attached client from it.
        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(zshRepaint(0, EDIT_BUFFER))

        assertHoldsExactly(grid, 1..30, "after the columns change that follows")
        assertSurvivesSerialization(grid, "after the flutter")
    }

    @Test
    fun `output written between resizes lands in order`() {
        // Interleaving is the third reported symptom. A session does not sit still across a
        // take-over: commands finish and print while the geometry is still settling.
        val grid = SessionGrid(WIDE_COLS, WIDE_ROWS)
        grid.feedText(outputLines(1..12))

        grid.resize(NARROW_COLS, NARROW_ROWS)
        grid.feedText(zshRepaint(0, ""))
        grid.feedText("\r\n")
        grid.feedText(outputLines(13..24))

        grid.resize(NARROW_COLS, KEYBOARD_ROWS)
        grid.feedText(zshRepaint(0, ""))
        grid.feedText("\r\n")
        grid.feedText(outputLines(25..36))

        grid.resize(WIDE_COLS, WIDE_ROWS)
        grid.feedText(zshRepaint(0, ""))
        grid.feedText("\r\n")
        grid.feedText(outputLines(37..48))

        assertHoldsExactly(grid, 1..48, "output interleaved with resizes")
        assertSurvivesSerialization(grid, "interleaved")
    }

    @Test
    fun `a wrapped line evicted by a reflow is in the paint immediately`() {
        // The HistoryLog boundary hole. A reflow whose last eviction still carried the wrap
        // flag left that row half-assembled: absent from lines(), so absent from history and
        // from every paint, until some unrelated later eviction happened to fuse onto it — at
        // which point two unrelated fragments read as one line.
        //
        // The shape that lands the last eviction on a wrapped row: ONE logical line long
        // enough that narrowing folds it into more rows than the screen holds. Every row the
        // reflow then pushes off the top is a soft-wrap continuation of the same line, so the
        // final eviction necessarily carries the wrap flag.
        val grid = SessionGrid(WIDE_COLS, 4)
        grid.feedText("W001 " + "wrapme ".repeat(24))

        grid.resize(TINY_COLS, 4)

        // No later eviction and no further feed: whatever the reflow archived has to be in the
        // paint already. Reading the composite right here is the whole point — the hole was
        // invisible precisely because a later, unrelated eviction eventually flushed it.
        val composite = grid.composite()
        assertTrue(
            composite.contains("W001"),
            "the wrapped line the reflow archived must be in the paint at once, composite was:\n$composite",
        )
        assertSurvivesSerialization(grid, "wrapped eviction")

        // And it is its own logical line. The rest of that line was not archived — it was
        // rewrapped and is still on the live screen — so the next thing to scroll off is
        // unrelated content and must not be fused onto the archived head.
        grid.feedText("\r\n")
        grid.feedText(outputLines(1..8))
        val head = grid.historyLines().first { it.text.contains("W001") }
        assertFalse(
            head.text.contains("L001"),
            "the archived head must not absorb later output, was: '${head.text}'",
        )
    }

    @Test
    fun `the cursor stays on the prompt it is editing across a rows-only shrink`() {
        // A rows-only shrink used to leave the cursor pointing at a row it had rotated away
        // from, so the next keystroke echoed into the middle of the transcript — the spliced
        // echo. The prompt row is the cursor row, so a shrink must trim from the top and take
        // the cursor down with its own content.
        val grid = SessionGrid(NARROW_COLS, NARROW_ROWS)
        grid.feedText(outputLines(1..25))
        grid.feedText(PROMPT + EDIT_BUFFER)

        grid.resize(NARROW_COLS, KEYBOARD_ROWS)

        val onPromptRow = grid.read { e ->
            val row = e.screen.getLineOrNull(e.cursorRow) ?: return@read ""
            String(row.mText, 0, row.spaceUsed)
        }
        assertTrue(
            onPromptRow.contains(EDIT_BUFFER),
            "the cursor must still be on the line being edited, was: '$onPromptRow'",
        )
        assertHoldsExactly(grid, 1..25, "cursor on its prompt")
    }

    private companion object {
        /** The ESC byte, spelled out so no editor or copy-paste can silently eat it. */
        const val ESC = "\u001b"

        /** A coloured two-part prompt, as a real zsh theme emits it. */
        const val PROMPT = "$ESC[32m~/code/lunamux$ESC[0m $ "

        /** What the user has half-typed when the geometry changes under them. */
        const val EDIT_BUFFER = "git commit --amend"

        /** Matches the tag each output line carries. */
        val TAG = Regex("""L\d{3}""")

        const val WIDE_COLS = 100
        const val WIDE_ROWS = 30
        const val NARROW_COLS = 60
        const val NARROW_ROWS = 20

        /** The phone's row count once its soft keyboard is up. */
        const val KEYBOARD_ROWS = 12

        /** Narrow enough that one long logical line folds into more rows than the screen holds. */
        const val TINY_COLS = 20
    }
}
