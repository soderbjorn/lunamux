/**
 * AI coding assistant state detection from terminal screen text.
 *
 * This file contains [StateDetector] and the [SessionState] data class.
 * The detector scans rendered terminal text (provided by [ScreenEmulator])
 * for distinctive UI patterns of Claude Code, OpenAI Codex CLI, and
 * Gemini CLI to determine whether an AI assistant is actively working,
 * waiting for user confirmation, or idle.
 *
 * Called by:
 *  - [TerminalSession.detectState] on each session-state polling cycle
 *    (every 3 seconds, driven from [TerminalSessions.resolveStates] in
 *    Application.kt).
 *  - The detected states are broadcast to connected clients via
 *    [WindowEnvelope.State] messages over the `/window` WebSocket.
 *
 * @see ScreenEmulator
 * @see TerminalSession
 * @see SessionState
 */
package se.soderbjorn.termtastic

/**
 * Detects the state of AI coding assistants (Claude Code, OpenAI Codex CLI,
 * Gemini CLI) by scanning recent terminal output for known status indicators.
 *
 * Each CLI renders distinctive text in the terminal while it is actively
 * working or waiting for user confirmation. This detector checks the tail of
 * a PTY ring buffer for those patterns and returns a [SessionState] describing
 * which CLI is active and what it is doing.
 *
 * ## Detected CLIs and their patterns
 *
 * ### Claude Code
 *
 * Working (generating or executing a tool):
 * ```
 *   ╭─────────────────────────────────────────╮
 *   │  ...model output...                     │
 *   │                                         │
 *   │                        esc to interrupt  │
 *   ╰─────────────────────────────────────────╯
 * ```
 *
 * Narrow panes: when the terminal is too narrow, Claude Code truncates its
 * footer with a unicode ellipsis (e.g. `⏸ plan mode on (shift+tab to cycle) ·
 * esc…`), so the literal "esc to interrupt" substring is no longer present.
 * As a secondary signal we also match Claude's working-spinner timer/token
 * tail, e.g. `Pollinating… (17m 48s · ↓ 51.6k tokens)` — the
 * `<arrow> … tokens)` shape is Claude-specific and survives the truncation
 * that hides the primary marker. The arrow direction varies (`↑`/`↓` for
 * input/output tokens), so both are matched. In even narrower panes the
 * `· … tokens)` tail is also truncated, leaving only `Synthesizing… (4m 33s)`;
 * the gerund + Unicode ellipsis + paren-timer combination is matched as a
 * third-tier signal.
 *
 * Background subagents: when Claude dispatches work to background ("Task")
 * agents and waits on them, the main loop parks at the idle `❯` input prompt
 * and shows a spinner line instead of the "esc to interrupt" footer:
 * ```
 *   ✻ Waiting for 3 background agents to finish
 * ```
 * This is matched as a working signal — the subagents are still active even
 * though the foreground prompt is idle.
 *
 * Waiting (waiting for a tool result or user action):
 * ```
 *   ╭─────────────────────────────────────────╮
 *   │  ...tool running...                     │
 *   │                                         │
 *   │                           esc to cancel  │
 *   ╰─────────────────────────────────────────╯
 * ```
 * or a confirmation menu:
 * ```
 *   Do you want to proceed?
 *   1. Yes
 *   2. Yes, and don't ask again
 *   3. No, tell Claude what to do differently
 * ```
 * or a plan-mode approval:
 * ```
 *   Would you like to proceed?
 *   1. Yes, auto-accept edits
 *   2. Yes, manually approve edits
 *   3. No, keep planning
 * ```
 *
 * ### OpenAI Codex CLI
 *
 * Working (model is generating or executing):
 * ```
 *   • Working (12s • esc to interrupt)
 * ```
 * or:
 * ```
 *   • Thinking (3s • esc to interrupt)
 * ```
 *
 * Waiting (approval overlay — user must confirm an action):
 * ```
 *   Would you like to run the following command?
 *       npm test
 *   > Yes, proceed
 *     No, continue without running it
 *
 *   Press enter to confirm or esc to cancel
 * ```
 * or:
 * ```
 *   Would you like to make the following edits?
 * ```
 *
 * ### Gemini CLI
 *
 * Working (model is generating or executing a tool):
 * ```
 *   Thinking...
 * ```
 * or:
 * ```
 *   Working...
 * ```
 * or with elapsed timer:
 * ```
 *   (esc to cancel, 5s)
 * ```
 *
 * Waiting (confirmation prompt — user must approve a change):
 * ```
 *   Apply this change?
 *   > Allow once
 *     Allow for this session
 *     No, suggest changes (esc)
 * ```
 * or:
 * ```
 *   Waiting for user confirmation...
 * ```
 */
object StateDetector {

    /** Markers that appear when Claude Code returns to idle (input prompt). */
    private val CLAUDE_IDLE_MARKERS = listOf(
        "\u276f",   // ❯ — the input prompt character
    )

    // Claude Code renders approval menus inside a rounded box, so each row
    // begins with a `│` border before any indentation. Allow the vertical bar
    // (and the surrounding whitespace) in the prefix so the numbered-option
    // anchors still match inside a boxed menu.
    private val CLAUDE_MENU_OPTION_1 = Regex("(?m)^[\\s\u2502]*(?:\u276f\\s*)?1\\.\\s")
    private val CLAUDE_MENU_OPTION_2 = Regex("(?m)^[\\s\u2502]*(?:\u276f\\s*)?2\\.\\s")

    // Claude's working-spinner footer, e.g. "Pollinating\u2026 (17m 48s \u00b7 \u2193 51.6k tokens)".
    // The "<arrow> <num>[k] tokens)" tail is Claude-specific and survives the
    // narrow-pane truncation that hides the "esc to interrupt" affordance. The
    // arrow direction varies (\u2191/\u2b06 up for input, \u2193/\u2b07 down for
    // output tokens \u2014 current Claude Code renders the down arrow), so accept any
    // of them; only the glyph changes, the surrounding shape stays Claude-unique.
    private val CLAUDE_WORKING_TIMER = Regex(
        """\(\s*(?:\d+h\s+)?(?:\d+m\s+)?\d+s\s*[\u00b7\u2022]\s*[\u2191\u2b06\u2193\u2b07]\s*[\d.]+k?\s*tokens\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    // Even narrower panes truncate the "\u00b7 \u2191 ... tokens)" tail entirely,
    // leaving only the spinner verb and the paren-timer, e.g. "Synthesizing\u2026 (4m 33s)".
    // Anchor on the gerund + Unicode ellipsis + paren-timer shape \u2014 no other
    // CLI in this detector renders that combination, so it's safe to attribute
    // to Claude.
    private val CLAUDE_WORKING_TIMER_NARROW = Regex(
        """\b\w{2,}ing\u2026\s*\(\s*(?:\d+h\s+)?(?:\d+m\s+)?\d+s""",
        RegexOption.IGNORE_CASE,
    )

    // Claude Code "Task" subagents running in the background. When the main loop
    // dispatches work to background agents and blocks on them, it parks at the
    // normal "\u276f" input prompt and renders a spinner line
    // ("\u273b Waiting for N background agents to finish") instead of the usual
    // "esc to interrupt" footer \u2014 so none of the markers above fire even
    // though Claude is still actively working. Anchor on "Waiting for [N]
    // background agent(s)": this leading phrase survives the right-edge ellipsis
    // truncation that narrow panes apply to the trailing "to finish". The count
    // is optional and singular/plural are both matched. We deliberately require
    // the full word "agent" (not just "background") to avoid colliding with
    // unrelated "waiting for N background <jobs/tasks>" output.
    private val CLAUDE_BACKGROUND_AGENTS = Regex(
        """waiting for (?:\d+ )?background agents?""",
    )

    /**
     * Scan the given [text] (typically the tail of a PTY ring buffer, decoded
     * as UTF-8) for known CLI state indicators.
     *
     * The caller is expected to pass ANSI-stripped text so that escape
     * sequences don't break substring matching.
     *
     * @return a [SessionState] if a known CLI is detected, or `null` if the
     *         terminal appears idle (no recognisable AI assistant UI).
     */
    fun detectState(text: String): SessionState? {
        val lower = text.lowercase()

        // ── Claude Code background ("Task") subagents ────────────────
        // Background agents leave the main loop idle at the "❯" prompt, so the
        // "esc to interrupt" / spinner-timer markers below never appear — the
        // "Waiting for N background agents to finish" spinner is the only
        // on-screen signal that Claude is still working. Check it first, before
        // the idle-aware markers: this text routinely renders *above* the idle
        // input box, so the position-aware ❯ guards below would otherwise
        // misclassify it as idle. snapshotVisibleText() scans only the live
        // screen, so a stale line can't linger here — no idle guard is needed.
        if (CLAUDE_BACKGROUND_AGENTS.containsMatchIn(lower)) {
            return SessionState(cli = "claude", state = "working")
        }

        // ── Claude Code ─────────────────────────────────────────────
        // Claude uses "esc to interrupt" while generating and "esc to cancel"
        // while a tool is running. These are the most reliable indicators
        // because they appear in the bottom-right of the Claude Code TUI
        // and are unlikely to collide with normal shell output.
        //
        // Note: Codex CLI also uses "esc to interrupt", but the state
        // meaning is identical (working), so attributing it to Claude is
        // harmless — the important thing is the state, not the CLI name.
        //
        // Position-aware matching: the raw PTY buffer may contain stale
        // "esc to interrupt" text from a previous working phase. If an
        // idle indicator (e.g. the ❯ input prompt) appears AFTER the last
        // working indicator, Claude has finished and is idle.

        val interruptIdx = lower.lastIndexOf("esc to interrupt")
        val cancelIdx = lower.lastIndexOf("esc to cancel")
        val activeIdx = maxOf(interruptIdx, cancelIdx)

        if (activeIdx >= 0) {
            // Check if an idle marker appears after the last active indicator.
            val idleAfter = CLAUDE_IDLE_MARKERS.any { marker ->
                text.lastIndexOf(marker) > activeIdx
            }
            if (!idleAfter) {
                if (cancelIdx > interruptIdx) {
                    // Gemini CLI uses "esc to cancel," (with trailing comma and
                    // elapsed time). If the comma-form is present, this is Gemini
                    // working, not Claude waiting. Check Gemini first.
                    if ("esc to cancel," in lower) {
                        return SessionState(cli = "gemini", state = "working")
                    }
                    return SessionState(cli = "claude", state = "waiting")
                }
                return SessionState(cli = "claude", state = "working")
            }
            // Idle marker found after active indicator — Claude is idle,
            // fall through to check other CLIs or return null.
        }

        // Narrow-pane fallback: when the terminal pane is too narrow, Claude
        // Code truncates "esc to interrupt" with its own ellipsis ("esc…"),
        // so the primary substring match above returns -1. The working
        // spinner's timer/token footer (e.g. "(17m 48s · ↓ 51.6k tokens)")
        // is rendered higher in the viewport and survives the truncation.
        // Match it as a secondary signal, applying the same position-aware
        // idle check so a stale footer scrolled above a ❯ prompt doesn't
        // count.
        val timerMatch = CLAUDE_WORKING_TIMER.findAll(text).lastOrNull()
        if (timerMatch != null) {
            val timerIdx = timerMatch.range.first
            val idleAfterTimer = CLAUDE_IDLE_MARKERS.any { marker ->
                text.lastIndexOf(marker) > timerIdx
            }
            if (!idleAfterTimer) {
                return SessionState(cli = "claude", state = "working")
            }
        }

        // Even-narrower-pane fallback: panes too narrow to fit the "· ↑ tokens)"
        // tail leave only "<Verb>ing… (<time>". The gerund + Unicode ellipsis
        // + paren-timer combination is unique to Claude's spinner.
        val timerNarrowMatch = CLAUDE_WORKING_TIMER_NARROW.findAll(text).lastOrNull()
        if (timerNarrowMatch != null) {
            val timerIdx = timerNarrowMatch.range.first
            val idleAfterTimer = CLAUDE_IDLE_MARKERS.any { marker ->
                text.lastIndexOf(marker) > timerIdx
            }
            if (!idleAfterTimer) {
                return SessionState(cli = "claude", state = "working")
            }
        }

        // Claude Code confirmation menus don't render "esc to cancel" in
        // the input box, so the branch above misses them. Match the
        // prompt text directly. "Do you want to proceed?" is the normal
        // tool-approval prompt; "Would you like to proceed?" is the
        // plan-mode approval prompt.
        //
        // We cannot gate on ❯ being absent: Claude uses ❯ both for the
        // idle input box and as the selection cursor inside approval
        // menus, so the menu's own ❯ would hide the wait. Instead gate
        // structurally — require a numbered option list ("1. " and
        // "2. " on their own lines) to appear after the phrase. Every
        // Claude approval menu has this shape, and the ^-anchor rules
        // out chat history that merely quotes the phrase.
        val proceedIdx = listOf("do you want to proceed?", "would you like to proceed?")
            .map { lower.lastIndexOf(it) }
            .filter { it >= 0 }
            .maxOrNull()
        if (proceedIdx != null) {
            val tail = lower.substring(proceedIdx)
            if (CLAUDE_MENU_OPTION_1.containsMatchIn(tail) &&
                CLAUDE_MENU_OPTION_2.containsMatchIn(tail)
            ) {
                return SessionState(cli = "claude", state = "waiting")
            }
        }

        // ── OpenAI Codex CLI ────────────────────────────────────────
        // Codex approval overlays show distinctive prompt text when waiting
        // for the user to confirm an action.
        if ("would you like to run the following command" in lower ||
            "would you like to make the following edits" in lower ||
            "would you like to grant these permissions" in lower ||
            "press enter to confirm or esc to cancel" in lower
        ) {
            return SessionState(cli = "codex", state = "waiting")
        }

        // ── Gemini CLI ──────────────────────────────────────────────
        // Gemini shows "Thinking..." or "Working..." as a status label
        // while the model is generating.
        if ("thinking..." in lower || "working..." in lower) {
            return SessionState(cli = "gemini", state = "working")
        }
        // Gemini confirmation prompts.
        if ("apply this change?" in lower ||
            "waiting for user confirmation" in lower ||
            "allow once" in lower
        ) {
            return SessionState(cli = "gemini", state = "waiting")
        }

        return null
    }
}

/**
 * The detected state of an AI coding assistant in a terminal session.
 *
 * @property cli   Which CLI was detected: `"claude"`, `"codex"`, or `"gemini"`.
 * @property state What the CLI is doing: `"working"` (actively generating or
 *                 executing) or `"waiting"` (blocked on user confirmation).
 */
data class SessionState(val cli: String, val state: String)
