/**
 * End-to-end state detection for Antigravity CLI panes, driven from real PTY
 * bytes rather than transcribed text.
 *
 * The two fixtures under `src/test/resources/antigravity/` are verbatim
 * recordings of what `agy` (Antigravity CLI 1.1.8) writes to a 170x44 pty,
 * captured for LMX-136:
 *
 *  - `idle-prompt.raw` — launch through to the banner and the empty `>` prompt,
 *    i.e. the state the bug report's screenshot shows.
 *  - `generating.raw` — the same session with a prompt submitted, cut while the
 *    spinner row and the `esc to cancel` footer are on screen.
 *
 * A third recording, `claude-trust-prompt.raw`, is Claude Code's own
 * folder-trust screen. It is here as the adversarial case for the way
 * Antigravity panes are identified: that screen draws a full-width rule of the
 * same box-drawing horizontals, so it pins that one rule is not enough to hand
 * a pane to Antigravity.
 *
 * They are fed through [ScreenEmulator] exactly as [TerminalSession.detectState]
 * does in production, so these tests pin the real pipeline: raw bytes → rendered
 * grid → [StateDetector]. That matters here because the bug was invisible in the
 * byte stream and only appeared in the rendered footer.
 *
 * @see StateDetectorTest for the readable, transcribed equivalents.
 * @see StateDetector
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntigravityScreenStateTest {

    /**
     * Render a captured PTY recording the way a live pane would, and return the
     * visible grid text.
     *
     * @param name file name under `src/test/resources/antigravity/`.
     * @return the rendered screen text, one row per line.
     */
    private fun renderFixture(name: String): String {
        val bytes = requireNotNull(
            javaClass.classLoader.getResourceAsStream("antigravity/$name")
        ) { "missing fixture antigravity/$name" }.use { it.readBytes() }
        // The capture was recorded on a 170x44 pty; the grid has to match or the
        // CLI's cursor addressing lands on the wrong rows.
        val screen = ScreenEmulator(initialCols = 170, initialRows = 44)
        screen.feed(bytes, bytes.size)
        return screen.snapshotVisibleText()
    }

    @Test
    fun idlePromptIsNotWaitingForInput() {
        val text = renderFixture("idle-prompt.raw")
        // Sanity-check the recording still renders the frame under test.
        assertTrue("? for shortcuts" in text, "expected Antigravity's idle footer")
        assertNull(
            StateDetector.detectState(text),
            "an Antigravity session resting at its prompt must carry no badge",
        )
    }

    @Test
    fun generatingIsWorkingNotWaiting() {
        val text = renderFixture("generating.raw")
        // The exact footer that used to be read as Claude's tool-running marker.
        assertTrue("esc to cancel" in text, "expected Antigravity's working footer")
        assertEquals(
            SessionState(cli = "antigravity", state = "working"),
            StateDetector.detectState(text),
            "the cancel affordance is an interrupt, not a question",
        )
    }

    @Test
    fun aClaudeScreenWithAHorizontalRuleStaysClaude() {
        // Claude Code's folder-trust screen draws one full-width rule of the same
        // "─" glyphs Antigravity's input box is made of, and blocks on a numbered
        // menu. It must keep reaching the Claude branch: Antigravity is claimed by
        // a PAIR of rules with its ">" prompt row under one of them, not by a
        // single rule anywhere on screen.
        val text = renderFixture("claude-trust-prompt.raw")
        assertTrue("─" in text, "expected the rule this test is about")
        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }
}
