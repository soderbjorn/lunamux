/**
 * Unit tests for [StateDetector].
 *
 * This file verifies that [StateDetector.detectState] correctly identifies
 * AI coding assistant states (working, waiting, idle) from rendered terminal
 * text. Tests cover Claude Code approval prompts (plan-mode, tool-approval,
 * boxed menus), the "esc to interrupt" / "esc to cancel" markers, idle-prompt
 * detection, Gemini CLI's "esc to cancel," variant, and the structural gating
 * of Gemini's confirmation overlay (so prose quoting "Apply this change?" is
 * not misreported as waiting).
 *
 * @see StateDetector
 * @see SessionState
 */
package se.soderbjorn.lunamux

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
    fun `working timer with down arrow token tail is claude working`() {
        // Issue #74: current Claude Code renders the spinner token tail with a
        // ↓ (output) arrow, not ↑. The medium-tier fallback must accept it for
        // panes where the gerund verb has scrolled off but the tail remains.
        val text = """
            ● Reading 1 file…
            (3m 37s · ↓ 14.6k tokens)
            ›
        """.trimIndent()
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `narrow pane with gerund and down-arrow token tail is claude working`() {
        // Reproduces the reported small-pane screenshot: a ↓-arrow token tail
        // under a thin "›" prompt (not the ❯ idle marker).
        val text = """
            · Perusing… (3m 37s · ↓ 14.6k tokens)
              Tip: Did you know you can drag and drop image files into your terminal?
            ─ mobile-theme-picker-sheet ─
            ›
            ▶▶ auto mode on (shift+tab to cycle) · esc…
        """.trimIndent()
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `down-arrow timer footer above idle prompt is idle`() {
        // The down-arrow tail must obey the same position-aware idle guard: a
        // stale footer scrolled above the ❯ prompt is not an active state.
        val text = """
            (3m 37s · ↓ 14.6k tokens)
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
    fun `waiting on background subagents is claude working even at idle prompt`() {
        // Reproduces issue #72: Claude dispatched work to background ("Task")
        // subagents and is blocked on them. The main loop parks at the idle ❯
        // input box, so the only on-screen signal of activity is the
        // "Waiting for N background agents to finish" spinner — which renders
        // *above* the input prompt.
        val text = """
            ✻ Waiting for 3 background agents to finish

            ❯

            ⏸ plan mode on (shift+tab to cycle) · ← for agents · ↓ to manage
              ● main
              ○ Explore  Explore theme system & thumbnails    2m 51s · ↓ 107.3k tokens
              ○ Explore  Explore server settings sync slots    2m 42s · ↓ 91.2k tokens
        """.trimIndent()
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `single background subagent is claude working`() {
        // Singular form ("1 background agent") must also match.
        val text = "✻ Waiting for 1 background agent to finish\n❯"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `background-agents line truncated after the agent word is claude working`() {
        // In a narrow pane the trailing "to finish" is replaced by Claude's
        // ellipsis; the "Waiting for N background agent(s)" prefix survives.
        val text = "✻ Waiting for 3 background agents…\n❯"
        assertEquals(
            SessionState(cli = "claude", state = "working"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `gemini confirmation overlay with option list is gemini waiting`() {
        // The genuine Gemini approval overlay: the prompt on its own line
        // followed by the cursor-selected option list. This must still be
        // detected as waiting after the structural gating change.
        val text = """
            Apply this change?
            ❯ Allow once
              Allow for this session
              No, suggest changes (esc)
        """.trimIndent()
        assertEquals(
            SessionState(cli = "gemini", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `agent prose quoting apply this change is not waiting`() {
        // Regression for the false-positive false "waiting" flag: an agent
        // describing an approval UI it built printed the exact Gemini marker
        // "Apply this change? [y/N]" as prose. Without the accompanying option
        // list ("Allow once" + "Allow for this session" / "No, suggest changes")
        // that prose must NOT be classified as a live Gemini confirmation. The
        // pane is parked at its input prompt — i.e. idle.
        val text = """
            Verified through the real button path (agent asks → askInput):

            - The hailing pane glows amber, leans out to the front, shows
              ? Apply this change? [y/N], and fires a big sonar ping radiating
              out across the sphere.
            - A blue charging pane (server · api, mid-cargo build) sits alongside
              for contrast — so at a glance: blue = working, amber = needs you.

            ❯ add the awaiting input state to issue #117
            ▶▶ auto mode on (shift+tab to cycle) · ← for agents
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `bare allow once in prose is not waiting`() {
        // "Allow once" is a generic phrase; on its own (no confirmation prompt,
        // no second menu option) it must not trip the Gemini waiting branch.
        val text = """
            the permission dialog lets you Allow once or allow always
            ❯
        """.trimIndent()
        assertNull(StateDetector.detectState(text))
    }

    @Test
    fun `codex approval overlay with prompt and option row is codex waiting`() {
        // A Codex approval overlay reaching the Codex-specific branch: the
        // "Would you like to …" prompt plus a cursor-selected option row, with
        // no "esc to cancel" footer (which the earlier Claude branch would
        // otherwise grab first). The option-row companion must confirm the
        // live overlay after the structural gating change.
        val text = """
            Would you like to run the following command?
                npm test
            ❯ Yes, proceed
              No, continue without running it
        """.trimIndent()
        assertEquals(
            SessionState(cli = "codex", state = "waiting"),
            StateDetector.detectState(text),
        )
    }

    @Test
    fun `codex overlay with esc to cancel footer is waiting`() {
        // The full overlay (with the "Press enter to confirm or esc to cancel"
        // footer) is caught by the earlier Claude "esc to cancel" branch: the
        // CLI attribution is claude but the STATE — the thing clients act on —
        // is correctly waiting. This documents that pre-existing interaction.
        val text = """
            Would you like to make the following edits?
            ❯ Yes, proceed
              No, continue without running it

            Press enter to confirm or esc to cancel
        """.trimIndent()
        assertEquals("waiting", StateDetector.detectState(text)?.state)
    }

    @Test
    fun `codex prompt phrase in prose without overlay is not waiting`() {
        // An agent describing the Codex approval flow prints the prompt phrase
        // as prose. Without a companion overlay element (footer or option row)
        // it must not be classified as a live Codex overlay.
        val text = """
            Codex will pop an overlay asking "would you like to run the following
            command" before executing anything, which you then approve.
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
