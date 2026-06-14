/**
 * Unit tests for [StateDetector].
 *
 * This file verifies that [StateDetector.detectState] correctly identifies
 * AI coding assistant states (working, waiting, idle) from rendered terminal
 * text. Tests cover Claude Code approval prompts (plan-mode, tool-approval,
 * boxed menus), the "esc to interrupt" / "esc to cancel" markers, idle-prompt
 * detection, and Gemini CLI's "esc to cancel," variant.
 *
 * @see StateDetector
 * @see SessionState
 */
package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [StateDetector.detectState] covering Claude Code, Codex CLI,
 * and Gemini CLI state patterns.
 */
class StateDetectorTest {

    @Test
    fun `plan-mode approval with selection cursor is detected as waiting`() {
        val text = """
            ╭────────────────────────────────────────╮
            │  Ready to code?                        │
            ╰────────────────────────────────────────╯

            Would you like to proceed?

            ❯ 1. Yes, auto-accept edits
              2. Yes, manually approve edits
              3. No, tell Claude what to change
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `plan-mode approval rendered inside box border is detected as waiting`() {
        val text = """
            ╭────────────────────────────────────────╮
            │  Would you like to proceed?            │
            │                                        │
            │  ❯ 1. Yes, auto-accept edits           │
            │    2. Yes, manually approve edits      │
            │    3. No, keep planning                │
            ╰────────────────────────────────────────╯
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `tool approval prompt is detected as waiting`() {
        val text = """
            Do you want to proceed?

            ❯ 1. Yes
              2. Yes, and don't ask again
              3. No, tell Claude what to do differently
        """.trimIndent()

        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `proceed phrase without numbered options is not detected`() {
        val text = """
            earlier in the chat the user said "would you like to proceed?"
            and now we are back at the prompt
            ❯
        """.trimIndent()

        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `esc to interrupt without idle marker is working`() {
        val text = "Generating...                esc to interrupt"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `esc to interrupt followed by idle prompt is idle`() {
        val text = """
            old output                   esc to interrupt
            done.
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `esc to cancel without idle marker is claude waiting`() {
        val text = "Running tool...              esc to cancel"
        assertEquals(
            SessionState(cli = "claude", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `gemini esc to cancel with comma is gemini working`() {
        val text = "(esc to cancel, 5s)"
        assertEquals(
            SessionState(cli = "gemini", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `narrow-pane truncated esc with working timer footer is claude working`() {
        // Reproduces the narrow-pane case: Claude Code truncates "esc to
        // interrupt" with its own ellipsis, but the spinner timer/token tail
        // remains visible above the input box.
        val text = """
            ● Searching for 1 pattern, reading 1 file…
            * Pollinating… (17m 48s · ↑ 51.6k tokens)

            ›
            ⏸ plan mode on (shift+tab to cycle) · esc…
        """.trimIndent()
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `working timer footer above idle prompt is idle`() {
        // Stale spinner-footer text scrolled above the ❯ prompt must not be
        // mistaken for an active working state.
        val text = """
            * Pollinating… (17m 48s · ↑ 51.6k tokens)
            done.
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `working timer with hour component is detected`() {
        val text = "* Crafting… (1h 4m 12s · ↑ 120.3k tokens)"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `very narrow pane with only gerund and paren-timer is claude working`() {
        // Even narrower than the previous case: the "· ↑ tokens)" tail is also
        // truncated, leaving only "Synthesizing… (4m 33s)" as the working signal.
        val text = """
            + Synthesizing… (4m 33s)
              Tip: Ask Claude to create a todo list when working on complex tasks
            ─ fix-pane-maximize-flash ─
            ›
            ▶▶ auto mode on
        """.trimIndent()
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `narrow gerund timer above idle prompt is idle`() {
        // Stale narrow-pane spinner above the ❯ prompt must not be mistaken
        // for an active working state.
        val text = """
            + Synthesizing… (4m 33s)
            done.
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `paren-timer without gerund-ellipsis is not detected`() {
        // Bare paren-timer text in chat history must not trigger the narrow
        // fallback — the gerund + Unicode ellipsis anchor is required.
        val text = """
            the build took (4m 33s) according to CI
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `codex working footer does not trigger claude timer fallback`() {
        // Codex shows "esc to interrupt" with no "↑ … tokens)" tail, so it
        // matches the primary Claude branch (intentional — same state
        // meaning) and must not be reclassified by the timer fallback.
        val text = "• Working (12s • esc to interrupt)"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }
}
