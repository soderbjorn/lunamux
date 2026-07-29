/**
 * AI coding assistant state detection from terminal screen text.
 *
 * This file contains [StateDetector] and the [SessionState] data class.
 * The detector scans rendered terminal text (provided by [ScreenEmulator])
 * for distinctive UI patterns of Claude Code, OpenAI Codex CLI, Gemini CLI
 * and Antigravity CLI to determine whether an AI assistant is actively
 * working, waiting for user confirmation, or idle.
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
package se.soderbjorn.lunamux

/**
 * Detects the state of AI coding assistants (Claude Code, OpenAI Codex CLI,
 * Gemini CLI, Antigravity CLI) by scanning recent terminal output for known
 * status indicators.
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
 *
 * ### Antigravity CLI
 *
 * Google's `agy` CLI (which drives Gemini and Claude models). It pins an
 * input box to the bottom of the pane — a full-width rule of box-drawing
 * horizontals above and below a `>` prompt row — with a two-slot footer
 * underneath:
 * ```
 *   ⣷  Generating...
 *   ──────────────────────────────────────────────────────────
 *   >
 *   ──────────────────────────────────────────────────────────
 *   esc to cancel                            Gemini 3.6 Flash · high
 * ```
 * Working: the braille spinner row (`⣷  Generating...`, `⣟  Editing
 * code...`, …) and/or the footer's left slot reading `esc to cancel` —
 * which is Antigravity's *cancel the in-flight turn* affordance, not a
 * confirmation prompt.
 *
 * Idle: the same box with `? for shortcuts` in the footer's left slot and
 * no spinner row.
 *
 * Waiting (permission overlay — user must grant an action):
 * ```
 *   Requesting permission for:
 *     Read /Users/me/project/.env
 *
 *   Allow access to this file?
 *   > Yes, allow access
 *     No, deny access
 * ```
 * Because the box identifies the pane as Antigravity's, its state is
 * resolved from Antigravity's own vocabulary and the other CLIs' markers
 * are never consulted — see [detectState] for why that matters (issue
 * LMX-136: `esc to cancel` used to be read as Claude's tool-running
 * marker, so every Antigravity turn painted the "needs you" badge).
 */
object StateDetector {

    /**
     * Markers that mean a CLI has returned to its idle input prompt. Matched
     * against the lowercased screen text by [idleMarkerAfter], which is how the
     * position-aware guards in [detectState] tell a live working footer from a
     * stale one the prompt has already overtaken.
     */
    private val IDLE_MARKERS = listOf(
        "\u276f",   // ❯ — the input prompt character
        "? for shortcuts",   // Antigravity CLI's idle footer hint
    )

    /**
     * Whether an idle marker appears after position [idx] in the screen text —
     * i.e. whether the CLI has moved on from the working footer found at [idx].
     *
     * Called by every position-aware branch of [detectState].
     *
     * @param lower the lowercased screen text (every marker is lowercase, and
     *   the one non-alphabetic marker is case-invariant).
     * @param idx index of the working marker being validated.
     * @return true when the pane has since returned to an input prompt.
     * @see IDLE_MARKERS
     */
    private fun idleMarkerAfter(lower: String, idx: Int): Boolean =
        IDLE_MARKERS.any { marker -> lower.lastIndexOf(marker) > idx }

    // Antigravity pins its input box to the bottom of the pane: two full-width
    // rules of box-drawing horizontals with the ">" prompt row between them. A
    // line consisting of nothing but "─" is the anchor. Codex and Gemini CLI draw
    // their boxes with corner and side glyphs (╭ ─ ╮ │ ╰ ╯), so their rules never
    // occupy a line alone, and Claude's inline separators ("─ my-branch ─") carry
    // a label — but Claude Code's folder-trust screen DOES print a lone
    // full-width rule, which is why [isAntigravity] wants a pair of them with the
    // prompt row underneath rather than any single rule. The width threshold is
    // deliberately low so a narrow pane, whose rule is only as wide as the pane
    // itself, is still recognised.
    private val ANTIGRAVITY_RULE = Regex("─{8,}\\s*")

    // Antigravity's working-spinner row: a braille spinner frame followed by a
    // status label — "⣷  Generating...", "⠿  Editing code...", "⡿  Reading
    // file...". The wording varies per tool, so anchor on the spinner glyph plus
    // a label word rather than on any one verb. A spinner is by definition an
    // operation in flight, i.e. the opposite of waiting for the user.
    private val ANTIGRAVITY_SPINNER = Regex("(?m)^\\s*[⠀-⣿]\\s+\\p{L}")

    // Antigravity's permission-overlay prompts. Unlike its "esc to cancel"
    // footer, these genuinely block on the user, so they are the pane's only
    // waiting signal.
    private val ANTIGRAVITY_PERMISSION_PROMPTS = listOf(
        "requesting permission for:",
        "allow access to this file?",
        "allow creation of this file?",
        "allow sandbox bypass for command execution?",
        "do you want to proceed?",
    )

    // A cursor-selectable row from one of those overlays' option lists. Required
    // to co-occur with a prompt, so an Antigravity transcript that merely
    // *prints* one of the prompt phrases (agents quote their own UI constantly)
    // is not mistaken for a live overlay — the same structural gating the
    // Claude, Codex and Gemini overlay checks already apply.
    private val ANTIGRAVITY_OPTION_ROW = Regex(
        "(?m)^[\\s>❯]*(?:yes, allow|no, deny|yes, accept this change)\\b",
        RegexOption.IGNORE_CASE,
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

    // Gemini CLI's approval overlay renders its confirmation prompt on its own
    // line followed by a fixed option list, the first row cursor-selected:
    //
    //   Apply this change?
    //   > Allow once
    //     Allow for this session
    //     No, suggest changes (esc)
    //
    // This anchors on the "Allow once" option row (line-start, optionally
    // prefixed by the selection cursor ">"/"❯" and whitespace). It exists
    // so the Gemini "waiting" classification can be gated structurally on the
    // presence of the live option list, the same way the Claude approval-menu
    // detection requires numbered options. Without it, an agent merely *printing*
    // the prose phrase "Apply this change?" (e.g. describing an approval UI it is
    // building) would trip a bare substring match and be misreported as waiting.
    private val GEMINI_MENU_ALLOW_ONCE = Regex(
        """(?m)^[\s>❯]*allow once\b""",
        RegexOption.IGNORE_CASE,
    )

    // Codex CLI's approval-overlay prompt lines. The overlay renders one of
    // these "Would you like to …" questions at the top, an option list, and a
    // "Press enter to confirm or esc to cancel" footer:
    //
    //   Would you like to run the following command?
    //       npm test
    //   > Yes, proceed
    //     No, continue without running it
    //
    //   Press enter to confirm or esc to cancel
    //
    // Matched as the prompt half of a structurally-gated waiting classification
    // (see [detectState]); on its own the phrase can appear in prose (an agent
    // describing the Codex approval flow), so it is required to co-occur with a
    // companion overlay element before it counts.
    private val CODEX_CONFIRM_PROMPT = Regex(
        """would you like to (?:run the following command|make the following edits|grant these permissions)""",
        RegexOption.IGNORE_CASE,
    )

    // A Codex overlay option row — the cursor-selectable "Yes, proceed" /
    // "No, continue without …" choices, line-anchored (optionally prefixed by
    // the "❯"/">" selection cursor or a "│" box border). Used as one of the
    // companion anchors that confirms a live Codex overlay rather than quoted
    // prose.
    private val CODEX_OPTION_ROW = Regex(
        """(?m)^[\s│>❯]*(?:yes, proceed|no, continue without)""",
        RegexOption.IGNORE_CASE,
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

        // ── Antigravity CLI ─────────────────────────────────────────
        // Resolved from Antigravity's own vocabulary, and — crucially — resolved
        // EXCLUSIVELY: once its input box identifies the pane, none of the
        // branches below run. Two reasons.
        //
        // First, its working footer is the bare phrase "esc to cancel", which the
        // Claude branch below reads as "a tool is running" and reports as
        // *waiting* — so before this branch existed, every Antigravity turn
        // painted the ⚠ "needs you" badge for its whole duration (LMX-136).
        // Antigravity's cancel affordance is an interrupt, not a question.
        //
        // Second, the pane below the box is an agent transcript, and the other
        // CLIs' markers are ordinary English ("Apply this change?", "Allow
        // once", "Would you like to run the following command?"). An agent
        // discussing approval UIs would otherwise trip them.
        if (isAntigravity(text)) {
            return detectAntigravityState(text, lower)
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
            if (!idleMarkerAfter(lower, activeIdx)) {
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
            if (!idleMarkerAfter(lower, timerMatch.range.first)) {
                return SessionState(cli = "claude", state = "working")
            }
        }

        // Even-narrower-pane fallback: panes too narrow to fit the "· ↑ tokens)"
        // tail leave only "<Verb>ing… (<time>". The gerund + Unicode ellipsis
        // + paren-timer combination is unique to Claude's spinner.
        val timerNarrowMatch = CLAUDE_WORKING_TIMER_NARROW.findAll(text).lastOrNull()
        if (timerNarrowMatch != null) {
            if (!idleMarkerAfter(lower, timerNarrowMatch.range.first)) {
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
        // for the user to confirm an action. As with the Gemini overlay below,
        // gate structurally rather than on a bare substring: an agent describing
        // the Codex approval flow can print these phrases into its own output,
        // which a single-phrase match would misreport as waiting. Require the
        // "Would you like to …" prompt to co-occur with a companion overlay
        // element — the "Press enter to confirm or esc to cancel" footer or a
        // cursor-selectable "Yes, proceed" / "No, continue without …" option
        // row — before classifying the pane as waiting.
        val codexPrompt = CODEX_CONFIRM_PROMPT.containsMatchIn(lower)
        val codexCompanion =
            "press enter to confirm or esc to cancel" in lower ||
                CODEX_OPTION_ROW.containsMatchIn(text)
        if (codexPrompt && codexCompanion) {
            return SessionState(cli = "codex", state = "waiting")
        }

        // ── Gemini CLI ──────────────────────────────────────────────
        // Gemini shows "Thinking..." or "Working..." as a status label
        // while the model is generating.
        if ("thinking..." in lower || "working..." in lower) {
            return SessionState(cli = "gemini", state = "working")
        }
        // Gemini confirmation overlay. Unlike the status labels above, the
        // approval prompt phrases here ("Apply this change?", "Allow once")
        // are short and readily appear in normal prose — an agent describing
        // or building an approval UI will print them into its own output. A
        // bare substring match therefore yields false "waiting" positives, so
        // gate structurally (mirroring the Claude approval-menu guard above):
        // require BOTH the confirmation prompt / cursor-selected "Allow once"
        // row AND a second, distinctive menu option ("Allow for this session"
        // or "No, suggest changes") to co-occur — the shape of the live overlay,
        // not just a quoted phrase.
        val geminiPrompt =
            "apply this change?" in lower || GEMINI_MENU_ALLOW_ONCE.containsMatchIn(text)
        val geminiMenuOption =
            "allow for this session" in lower || "no, suggest changes" in lower
        if (geminiPrompt && geminiMenuOption) {
            return SessionState(cli = "gemini", state = "waiting")
        }
        // Gemini's own "Waiting for user confirmation…" status label is a
        // distinctive full phrase (not a generic prose fragment), so it stands
        // on its own as a waiting signal.
        if ("waiting for user confirmation" in lower) {
            return SessionState(cli = "gemini", state = "waiting")
        }

        return null
    }

    /**
     * Whether the rendered screen shows Antigravity CLI's input box — two
     * full-width rules of box-drawing horizontals wrapping a `>` prompt row,
     * pinned to the bottom of the pane.
     *
     * Called by [detectState] to decide whether the pane belongs to Antigravity,
     * in which case [detectAntigravityState] resolves the state on its own and
     * the other CLIs' markers are skipped entirely.
     *
     * Two conditions, both required: at least two whole-line rules (a single one
     * can plausibly come from ordinary command output), and one of them
     * immediately followed by the `>` prompt row — the box's top edge. That pair
     * is the shape no other agent CLI in this detector draws.
     *
     * @param text the rendered screen text, one row per line.
     * @return true when Antigravity's input box is on screen.
     * @see ANTIGRAVITY_RULE
     */
    private fun isAntigravity(text: String): Boolean {
        val lines = text.lines()
        var rules = 0
        var boxTop = false
        for ((i, line) in lines.withIndex()) {
            if (!ANTIGRAVITY_RULE.matches(line)) continue
            rules++
            // The prompt row carries whatever the user has typed, so match only
            // its leading ">".
            if (lines.getOrNull(i + 1)?.startsWith(">") == true) boxTop = true
        }
        return rules >= 2 && boxTop
    }

    /**
     * Classify an Antigravity CLI pane from its own status vocabulary.
     *
     * Called by [detectState] once [isAntigravity] has claimed the pane.
     *
     * Waiting is tested first: a permission overlay replaces the status row
     * while it is up, and "the user is blocked" outranks "something is running"
     * on the rare frame that shows both. It is gated structurally — a prompt
     * phrase AND an option row from the same overlay — because Antigravity's own
     * transcript is full of prose that quotes such phrases.
     *
     * @param text the rendered screen text, one row per line.
     * @param lower the same text lowercased.
     * @return `"waiting"` on a live permission overlay, `"working"` while a turn
     *   is in flight, or `null` when the pane is parked at its input prompt.
     * @see ANTIGRAVITY_PERMISSION_PROMPTS
     * @see ANTIGRAVITY_SPINNER
     */
    private fun detectAntigravityState(text: String, lower: String): SessionState? {
        val permissionPrompt = ANTIGRAVITY_PERMISSION_PROMPTS.any { it in lower }
        if (permissionPrompt && ANTIGRAVITY_OPTION_ROW.containsMatchIn(text)) {
            return SessionState(cli = "antigravity", state = "waiting")
        }
        // A live spinner row, or the footer's cancel/interrupt affordance, means
        // the turn is still running. Both are position-checked against the idle
        // footer ("? for shortcuts") so a half-repainted frame — the footer
        // already flipped back to idle while the row above still reads
        // "esc to cancel" — is reported as idle rather than as work in flight.
        val busyIdx = maxOf(
            lower.lastIndexOf("esc to cancel"),
            lower.lastIndexOf("esc to interrupt"),
            ANTIGRAVITY_SPINNER.findAll(text).lastOrNull()?.range?.first ?: -1,
        )
        if (busyIdx >= 0 && !idleMarkerAfter(lower, busyIdx)) {
            return SessionState(cli = "antigravity", state = "working")
        }
        return null
    }
}

/**
 * The detected state of an AI coding assistant in a terminal session.
 *
 * @property cli   Which CLI was detected: `"claude"`, `"codex"`, `"gemini"`, or
 *                 `"antigravity"`.
 * @property state What the CLI is doing: `"working"` (actively generating or
 *                 executing) or `"waiting"` (blocked on user confirmation).
 */
data class SessionState(val cli: String, val state: String)
