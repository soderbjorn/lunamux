/**
 * Canned terminal content for demo mode: the initial scrollback transcript of
 * every demo PTY session, the shell prompt, the command→response tables used
 * when the user types into a demo terminal, and the timed output scripts
 * ([DemoScriptStep]) that make the Claude sessions look *live* — the two
 * "working" sessions loop a feed of tool calls and status-line ticks, and the
 * finished session plays a one-shot work burst whenever the user talks to it.
 *
 * Everything in this file is static — nothing is randomised at runtime, so
 * the demo looks identical on every load and on every client. All timestamps,
 * fake command output, and the simulated Claude Code sessions are authored
 * here; tweak this file to change what the demo shows.
 *
 * @see DemoTerminalSession for the line-discipline simulation that replays
 *   these transcripts and answers typed commands
 * @see DemoFixtures for the workspace layout, file tree, and git fixtures
 */
package se.soderbjorn.termtastic.client.demo

/** ANSI escape introducer, interpolated into the transcript strings. */
private const val E = "\u001b"

/** Reset all attributes. */
private const val R = "$E[0m"

/** Bold green — the user@host segment of the prompt. */
private const val GREEN = "$E[1;32m"

/** Bold blue — directories in `ls` output and the cwd prompt segment. */
private const val BLUE = "$E[1;34m"

/** Bold cyan. */
private const val CYAN = "$E[1;36m"

/** Yellow — git modified markers. */
private const val YELLOW = "$E[33m"

/** Red — errors and git deletions. */
private const val RED = "$E[31m"

/** Plain green — additions, success markers. */
private const val GRN = "$E[32m"

/** Dim/faint text. */
private const val DIM = "$E[2m"

/** Bold. */
private const val B = "$E[1m"

/** Claude Code's warm accent colour (256-colour peach). */
private const val CLAUDE = "$E[38;5;215m"

/**
 * The zsh-style prompt every demo shell session shows. Static (always the
 * project directory) so typed commands and their canned outputs stay
 * coherent with the fixture file tree and git state.
 */
internal val DEMO_PROMPT: String =
    "$GREEN demo@orbit $R$BLUE~/code/orbit$R % "

/**
 * Prompt block shown by the simulated Claude Code session for typed input,
 * styled after the current Claude Code chrome: a dim rule, the
 * `⏵⏵ auto mode on` status line, then the `›` input caret. The caret is the
 * last thing printed so typed characters echo in the right place; the status
 * line rides above it (in the real TUI it sits below the input box, but a
 * scrollback simulation must keep the cursor line last).
 */
internal val DEMO_CLAUDE_PROMPT: String =
    "${DIM}─────────────────────────────────────────────────────────$R\r\n" +
    "$CYAN⏵⏵$R ${B}auto mode on$R ${DIM}(shift+tab to cycle) · ← for agents · esc to interrupt$R\r\n" +
    "$B›$R "

/**
 * Return-to-column-0 + erase-line — prefixed to script steps that rewrite
 * the Claude status line in place, mimicking how the real Claude Code
 * spinner ticks without scrolling.
 */
internal const val CLEAR_LINE: String = "\r$E[2K"

/**
 * One Claude Code status line in the current spinner style
 * (`✻ Frosting… (3m 16s · almost done thinking with high effort)`), written
 * without a trailing newline so the next [CLEAR_LINE]-prefixed step can
 * rewrite it in place.
 *
 * @param verb the whimsical present-participle activity label (`"Frosting"`).
 * @param secs elapsed seconds, rendered `Xs` or `Xm Ys` past the minute.
 * @param note the trailing note in the parenthetical (`"thinking with high
 *   effort"`), highlighted the way the live client renders it.
 * @return the ANSI-coloured status line.
 */
private fun claudeStatus(verb: String, secs: Int, note: String): String {
    val t = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
    return "$CLAUDE✻$R ${B}$verb…$R ${DIM}($t · $R$YELLOW$note$R${DIM})$R"
}

/**
 * One completed Claude Code tool-call block (`● Edit(file)` plus its `⎿`
 * result line and a trailing blank line), CRLF-terminated so a status line
 * can follow on the next row.
 *
 * @param tool the tool name (`"Edit"`, `"Bash"`, …).
 * @param arg the tool argument rendered in parentheses.
 * @param result the one-line result shown after `⎿`.
 * @return the ANSI-coloured block.
 */
private fun toolStep(tool: String, arg: String, result: String): String =
    "$B●$R ${B}$tool$R($arg)\r\n  ${DIM}⎿  $result$R\r\n\r\n"

/**
 * One aggregated tool-activity line in the current Claude Code style
 * (`● Searching for 5 patterns, reading 7 files, running 1 shell command…`
 * with a dim `⎿` detail row underneath), CRLF-terminated so a status line
 * can follow. The counts in [summary] should be pre-bolded by the caller.
 *
 * @param summary the one-line activity summary (may contain ANSI bolding).
 * @param detail the dim detail row (typically the file being read).
 * @return the ANSI-coloured block.
 */
private fun toolSummary(summary: String, detail: String): String =
    "$DIM●$R $summary\r\n  ${DIM}⎿  $detail$R\r\n\r\n"

/**
 * Joins transcript lines with the CRLF line endings a real PTY produces
 * (xterm.js and the mobile terminal views both expect `\r\n`).
 *
 * @param l the lines of the transcript, without trailing newlines.
 * @return the lines joined and terminated with `\r\n`.
 */
private fun lines(vararg l: String): String = l.joinToString("\r\n", postfix = "\r\n")

/**
 * One timed step of a scripted live-output feed: after [delayMs], [text] is
 * written to the session verbatim (ANSI escapes included, no newline is
 * appended). Steps typically start with [CLEAR_LINE] so a status line can be
 * rewritten in place the way Claude Code's spinner is.
 *
 * Played by [DemoTerminalSession]'s script player, either forever
 * ([DemoSessionSpec.liveScript]) or once per typed input line
 * ([DemoSessionSpec.inputScript]).
 *
 * @property delayMs how long to wait before writing this step.
 * @property text the raw bytes to write (as a string; may contain ANSI).
 */
internal class DemoScriptStep(
    val delayMs: Long,
    val text: String,
)

/**
 * Everything needed to seed one demo PTY session.
 *
 * @property sessionId the fixture session id (referenced from the demo
 *   [se.soderbjorn.termtastic.WindowConfig]).
 * @property transcript initial scrollback content, ANSI-coloured, CRLF line
 *   endings; replayed as the "ring buffer" snapshot when a client attaches.
 * @property prompt the prompt string printed after every simulated command.
 * @property startsAtPrompt `false` for sessions that look like they are
 *   running a foreground program (log tails, watchers): keystrokes are
 *   swallowed until the user presses Ctrl-C, which "stops" the program and
 *   drops to the shell prompt.
 * @property respond maps a typed command line to its canned output (ANSI,
 *   CRLF line endings, may be empty for no output).
 * @property liveScript when non-null, a looping feed of [DemoScriptStep]s
 *   played from the moment the session is created — the session looks like
 *   an agent that is *currently* working (tool calls appearing, the status
 *   line ticking). The transcript must end mid-status-line (no trailing
 *   newline) so the first [CLEAR_LINE] rewrite lands on it. Keystrokes are
 *   swallowed while the feed runs; Ctrl-C stops it and drops to [prompt].
 * @property inputScript when non-null, Enter at the prompt hands the typed
 *   line (plus a 0-based run counter for variant rotation) to this function
 *   and plays the returned steps once — the "Claude resumes working when
 *   you talk to it" behaviour. The last step should end with the prompt.
 *   While a run is playing, keystrokes are swallowed and Ctrl-C interrupts.
 */
internal class DemoSessionSpec(
    val sessionId: String,
    val transcript: String,
    val prompt: String,
    val startsAtPrompt: Boolean,
    val respond: (String) -> String,
    val liveScript: List<DemoScriptStep>? = null,
    val inputScript: ((line: String, runIndex: Int) -> List<DemoScriptStep>)? = null,
)

/**
 * Canned output for a typed shell command, shared by every demo shell
 * session. Deterministic: the same input always produces the same output.
 *
 * @param line the trimmed command line the user entered.
 * @return ANSI output ending with `\r\n`, or an empty string for commands
 *   that produce no output (e.g. `cd`).
 */
internal fun demoShellRespond(line: String): String {
    val cmd = line.trim()
    if (cmd.isEmpty()) return ""
    // Prefix commands first: echo and cd take arbitrary arguments.
    if (cmd == "echo") return lines("")
    if (cmd.startsWith("echo ")) return lines(cmd.removePrefix("echo ").trim('"', '\''))
    if (cmd == "cd" || cmd.startsWith("cd ")) return ""
    if (cmd.startsWith("cat ")) {
        val target = cmd.removePrefix("cat ").trim()
        demoCatOutputs[target]?.let { return it }
        return lines("cat: $target: No such file or directory")
    }
    demoCommandTable[cmd]?.let { return it }
    val first = cmd.substringBefore(' ')
    demoFullScreenCommands.firstOrNull { it == first }?.let {
        return lines("${DIM}demo:$R full-screen programs aren't available in this demo — try ${B}help$R")
    }
    return lines("zsh: command not found: $first")
}

/**
 * The looping live feed for the main rate-limiter Claude session
 * (`demo-s1`): status-line ticks rewritten in place, interleaved with tool
 * calls and short assistant remarks, so the pane genuinely looks like an
 * agent mid-task rather than a frozen screenshot. Played forever by
 * [DemoTerminalSession]; each loop iteration reads as "Claude doing the
 * next round of polish", so the repeat is unobtrusive.
 *
 * @return the steps of one loop iteration.
 */
private fun claudeMainLiveScript(): List<DemoScriptStep> = listOf(
    DemoScriptStep(1_700, CLEAR_LINE + claudeStatus("Frosting", 161, "thinking with high effort")),
    DemoScriptStep(1_900, CLEAR_LINE + claudeStatus("Frosting", 166, "thinking with high effort")),
    DemoScriptStep(
        1_600,
        CLEAR_LINE + toolStep("Edit", "README.md", "Updated README.md with 12 additions and 1 removal") +
            claudeStatus("Frosting", 168, "thinking with high effort"),
    ),
    DemoScriptStep(2_100, CLEAR_LINE + claudeStatus("Frosting", 172, "almost done thinking with high effort")),
    DemoScriptStep(
        1_500,
        CLEAR_LINE +
            "$B●$R README now documents the burst and refill settings. Adding the\r\n" +
            "  429 integration test next.\r\n\r\n" +
            claudeStatus("Simmering", 174, "writing the integration test"),
    ),
    DemoScriptStep(
        1_800,
        CLEAR_LINE + toolStep("Write", "src/api/ratelimit.integration.test.ts", "Wrote 34 lines") +
            claudeStatus("Simmering", 178, "running the suite"),
    ),
    DemoScriptStep(
        2_200,
        CLEAR_LINE + toolStep("Bash", "npx vitest run src/api", "17 passed (17)") +
            claudeStatus("Percolating", 183, "verifying the refill window"),
    ),
    DemoScriptStep(
        1_900,
        CLEAR_LINE +
            "$B●$R Integration test passes — a burst of 21 requests gets a 429 and\r\n" +
            "  recovers after the refill window.\r\n\r\n" +
            claudeStatus("Frosting", 186, "thinking with high effort"),
    ),
    DemoScriptStep(2_400, CLEAR_LINE + claudeStatus("Frosting", 191, "almost done thinking with high effort")),
)

/**
 * The looping live feed for the docs Claude session (`demo-s7`), same
 * mechanics as [claudeMainLiveScript] but slower-paced and centred on
 * documentation edits, so the two working panes never look synchronised.
 *
 * @return the steps of one loop iteration.
 */
private fun claudeDocsLiveScript(): List<DemoScriptStep> = listOf(
    DemoScriptStep(2_000, CLEAR_LINE + claudeStatus("Noodling", 75, "reading the deploy docs")),
    DemoScriptStep(
        1_700,
        CLEAR_LINE + toolSummary(
            "Searching for ${B}2$R patterns, reading ${B}3$R files…",
            "docs/deploy.md",
        ) + claudeStatus("Noodling", 78, "reading the deploy docs"),
    ),
    DemoScriptStep(2_300, CLEAR_LINE + claudeStatus("Noodling", 82, "thinking with high effort")),
    DemoScriptStep(
        1_600,
        CLEAR_LINE + toolStep("Edit", "README.md", "Added a Rate limiting section with the default limits") +
            claudeStatus("Marinating", 85, "updating the README"),
    ),
    DemoScriptStep(
        2_000,
        CLEAR_LINE +
            "$B●$R Cross-linking the new section from the architecture notes.\r\n\r\n" +
            claudeStatus("Marinating", 89, "cross-linking the docs"),
    ),
    DemoScriptStep(
        1_800,
        CLEAR_LINE + toolStep("Edit", "docs/architecture.md", "Updated the module table") +
            claudeStatus("Polishing", 93, "almost done thinking with high effort"),
    ),
    DemoScriptStep(2_500, CLEAR_LINE + claudeStatus("Polishing", 98, "almost done thinking with high effort")),
)

/**
 * The one-shot work burst played when the user types into the *finished*
 * Claude session (`demo-s6`): whatever was typed, Claude acknowledges it,
 * "works" for around nine seconds (tool calls + a ticking status line —
 * long enough for the working indicator to be clearly seen), then prints a
 * completion line and returns to the input prompt. Three canned variants
 * rotate on [runIndex] so repeated messages don't replay identical output.
 *
 * The typed line itself is deliberately ignored beyond having triggered the
 * run — the demo can't actually do the work, and any canned text that
 * pretended to parse the request would read as wrong more often than right.
 *
 * @param line the input line the user submitted (unused, see above).
 * @param runIndex 0-based count of bursts already played in this session.
 * @return the steps of one burst, ending with [DEMO_CLAUDE_PROMPT].
 */
private fun claudeDoneInputScript(line: String, runIndex: Int): List<DemoScriptStep> {
    val ack = listOf(
        "$B●$R On it — reworking that now.",
        "$B●$R Good idea. Let me fold that in.",
        "$B●$R Sure — taking another pass.",
    )
    val read = listOf(
        toolStep("Read", "src/api/metrics.ts", "Read 41 lines"),
        toolStep("Read", "src/api/sessions.ts", "Read 149 lines"),
        toolStep("Read", "docs/architecture.md", "Read 26 lines"),
    )
    val edit = listOf(
        toolStep("Edit", "src/api/metrics.ts", "Updated src/api/metrics.ts with 7 additions"),
        toolStep("Edit", "src/api/metrics.ts", "Updated src/api/metrics.ts with 3 additions and 1 removal"),
        toolStep("Edit", "docs/architecture.md", "Documented the /metrics endpoint"),
    )
    val done = listOf(
        "$B●$R Done — folded that in and the suite is still green.",
        "$B●$R That's in. All 19 tests still pass.",
        "$B●$R Done. The docs and the endpoint agree again; tests are green.",
    )
    val v = runIndex.mod(ack.size)
    return listOf(
        DemoScriptStep(400, "\r\n$CLAUDE✻$R ${DIM}Thinking…$R"),
        DemoScriptStep(1_300, CLEAR_LINE + ack[v] + "\r\n\r\n" + claudeStatus("Brewing", 3, "thinking with high effort")),
        DemoScriptStep(
            1_700,
            CLEAR_LINE + toolSummary(
                "Searching for ${B}3$R patterns, reading ${B}2$R files, running ${B}1$R shell command…",
                "src/api/metrics.ts",
            ) + claudeStatus("Brewing", 6, "thinking with high effort"),
        ),
        DemoScriptStep(1_500, CLEAR_LINE + read[v] + claudeStatus("Simmering", 8, "folding the change in")),
        DemoScriptStep(1_900, CLEAR_LINE + edit[v] + claudeStatus("Percolating", 10, "running the suite")),
        DemoScriptStep(
            2_100,
            CLEAR_LINE + toolStep("Bash", "npx vitest run", "19 passed (19)") +
                claudeStatus("Frosting", 13, "almost done thinking with high effort"),
        ),
        DemoScriptStep(1_400, CLEAR_LINE + done[v] + "\r\n\r\n" + DEMO_CLAUDE_PROMPT),
    )
}

/** Programs that would take over the screen; politely declined in the demo. */
private val demoFullScreenCommands = listOf(
    "top", "htop", "btop", "vim", "vi", "nvim", "nano", "emacs", "less", "more", "man", "tmux", "ssh",
)

/** `cat <file>` outputs for the files most likely to be poked at. */
private val demoCatOutputs: Map<String, String> = mapOf(
    "README.md" to lines(
        "# Orbit",
        "",
        "A tiny session service used as the termtastic demo workspace.",
        "",
        "## Quick start",
        "",
        "    npm install",
        "    npm run dev",
        "",
        "The API listens on http://localhost:8787. See docs/architecture.md",
        "for the request flow and docs/deploy.md for shipping it.",
    ),
    "package.json" to lines(
        "{",
        "  \"name\": \"orbit\",",
        "  \"version\": \"0.4.2\",",
        "  \"private\": true,",
        "  \"scripts\": {",
        "    \"dev\": \"tsx watch src/server.ts\",",
        "    \"build\": \"esbuild src/server.ts --bundle --platform=node --outfile=dist/server.js\",",
        "    \"watch\": \"npm run build -- --watch\",",
        "    \"test\": \"vitest run\"",
        "  }",
        "}",
    ),
    ".gitignore" to lines("node_modules/", "dist/", ".env"),
)

/**
 * Exact-match command table for the demo shell. Output strings are fully
 * static; see [demoShellRespond] for prefix commands (`echo`, `cd`, `cat`)
 * and the fallback.
 */
private val demoCommandTable: Map<String, String> = mapOf(
    "help" to lines(
        "${B}termtastic demo shell$R — canned commands you can try:",
        "",
        "  ls, ls -la, pwd, whoami, date, uname -a, uptime, history",
        "  git status, git log, git diff, git branch",
        "  cat README.md, cat package.json",
        "  npm test, npm run build, npm run dev",
        "  echo <text>, clear, claude",
        "",
        "${DIM}Anything else gets a perfectly honest 'command not found'.$R",
    ),
    "ls" to lines(
        "${BLUE}docs$R        ${BLUE}node_modules$R  ${BLUE}scratch$R  ${BLUE}src$R",
        "README.md   package.json  tsconfig.json",
    ),
    "ls -la" to lines(
        "total 64",
        "drwxr-xr-x   11 demo  staff    352 Jun 10 20:58 ${BLUE}.$R",
        "drwxr-xr-x    5 demo  staff    160 Jun 02 09:12 ${BLUE}..$R",
        "drwxr-xr-x   12 demo  staff    384 Jun 10 20:55 ${BLUE}.git$R",
        "-rw-r--r--    1 demo  staff     41 May 28 14:03 .gitignore",
        "-rw-r--r--    1 demo  staff    611 Jun 10 20:41 README.md",
        "drwxr-xr-x    4 demo  staff    128 Jun 04 18:26 ${BLUE}docs$R",
        "drwxr-xr-x  214 demo  staff   6848 Jun 02 09:13 ${BLUE}node_modules$R",
        "-rw-r--r--    1 demo  staff    402 Jun 02 09:13 package.json",
        "drwxr-xr-x    3 demo  staff     96 Jun 10 20:58 ${BLUE}scratch$R",
        "drwxr-xr-x    5 demo  staff    160 Jun 10 20:51 ${BLUE}src$R",
        "-rw-r--r--    1 demo  staff    289 May 28 14:03 tsconfig.json",
    ),
    "pwd" to lines("/Users/demo/code/orbit"),
    "whoami" to lines("demo"),
    "date" to lines("Wed Jun 10 21:14:07 CEST 2026"),
    "uname" to lines("Darwin"),
    "uname -a" to lines(
        "Darwin orbit.local 25.3.0 Darwin Kernel Version 25.3.0: " +
            "Tue Apr 14 11:22:46 PDT 2026; root:xnu-11417.81.4~2/RELEASE_ARM64_T8122 arm64",
    ),
    "uptime" to lines("21:14  up 9 days,  4:12, 2 users, load averages: 1.84 2.01 2.12"),
    "history" to lines(
        "  501  npm run dev",
        "  502  git checkout -b feature/rate-limit",
        "  503  claude",
        "  504  npm run build",
        "  505  git status",
    ),
    "git status" to lines(
        "On branch ${B}feature/rate-limit$R",
        "Changes to be committed:",
        "  (use \"git restore --staged <file>...\" to unstage)",
        "\t${GRN}new file:   src/api/ratelimit.ts$R",
        "",
        "Changes not staged for commit:",
        "  (use \"git add <file>...\" to update what will be committed)",
        "\t${RED}modified:   README.md$R",
        "\t${RED}modified:   src/api/sessions.ts$R",
        "\t${RED}modified:   src/server.ts$R",
        "",
        "Untracked files:",
        "  (use \"git add <file>...\" to include in what will be committed)",
        "\t${RED}scratch/notes.md$R",
    ),
    "git log" to lines(
        "${YELLOW}commit 9c2e417a51b6d2f08c9a3f4e21d7b8a90c5d1e22$R (${CYAN}HEAD -> $R${GRN}feature/rate-limit$R)",
        "Author: Demo User <demo@orbit.dev>",
        "Date:   Tue Jun 9 17:40:12 2026 +0200",
        "",
        "    sessions: log creation with structured fields",
        "",
        "${YELLOW}commit 4b81f309ad7e6c215f0d9821c43ab6790112ffe0$R (${GRN}main$R)",
        "Author: Demo User <demo@orbit.dev>",
        "Date:   Mon Jun 8 11:02:55 2026 +0200",
        "",
        "    server: graceful shutdown on SIGTERM",
    ),
    "git log --oneline" to lines(
        "${YELLOW}9c2e417$R (${CYAN}HEAD -> $R${GRN}feature/rate-limit$R) sessions: log creation with structured fields",
        "${YELLOW}4b81f30$R (${GRN}main$R) server: graceful shutdown on SIGTERM",
        "${YELLOW}d27a90c$R api: 404 for unknown session ids",
        "${YELLOW}311bd04$R initial import",
    ),
    "git branch" to lines("* ${GRN}feature/rate-limit$R", "  main"),
    "git diff" to lines(
        "${B}diff --git a/src/server.ts b/src/server.ts$R",
        "${B}--- a/src/server.ts$R",
        "${B}+++ b/src/server.ts$R",
        "${CYAN}@@ -1,8 +1,9 @@$R",
        " import { createServer } from \"node:http\";",
        " import { log } from \"./lib/log\";",
        " import { sessions } from \"./api/sessions\";",
        "${GRN}+import { users } from \"./api/users\";$R",
        " ",
        " const server = createServer((req, res) => {",
        "${RED}-  sessions.dispatch(req, res);$R",
        "${GRN}+  sessions.dispatch(req, res) || users.dispatch(req, res);$R",
        " });",
        "${DIM}(use the Git pane for the full side-by-side view)$R",
    ),
    "npm test" to demoVitestOutput(),
    "npm run test" to demoVitestOutput(),
    "npx vitest run" to demoVitestOutput(),
    "npm run build" to lines(
        "",
        "> orbit@0.4.2 build",
        "> esbuild src/server.ts --bundle --platform=node --outfile=dist/server.js",
        "",
        "  ${B}dist/server.js$R  ${CYAN}412.3kb$R",
        "",
        "${YELLOW}⚡$R ${GRN}Done in 38ms$R",
    ),
    "npm run dev" to lines(
        "${DIM}demo: the dev server is already running — see the services tab.$R",
    ),
    "claude" to lines(
        "$CLAUDE╭──────────────────────────────────────────────╮$R",
        "$CLAUDE│$R $CLAUDE✻$R ${B}Welcome to Claude Code!$R                     $CLAUDE│$R",
        "$CLAUDE│$R                                              $CLAUDE│$R",
        "$CLAUDE│$R   ${DIM}This demo already has a Claude session —$R   $CLAUDE│$R",
        "$CLAUDE│$R   ${DIM}check the big pane in the orbit tab.$R       $CLAUDE│$R",
        "$CLAUDE╰──────────────────────────────────────────────╯$R",
    ),
)

/** The vitest run shown by `npm test` and friends. */
private fun demoVitestOutput(): String = lines(
    "",
    "> orbit@0.4.2 test",
    "> vitest run",
    "",
    " ${B}RUN$R  ${CYAN}v3.2.1$R /Users/demo/code/orbit",
    "",
    " ${GRN}✓$R src/api/sessions.test.ts ${DIM}(8 tests)$R ${YELLOW}42ms$R",
    " ${GRN}✓$R src/api/ratelimit.test.ts ${DIM}(5 tests)$R ${YELLOW}11ms$R",
    " ${GRN}✓$R src/lib/config.test.ts ${DIM}(3 tests)$R ${YELLOW}6ms$R",
    "",
    " ${B}Test Files$R  ${GRN}3 passed$R ${DIM}(3)$R",
    "      ${B}Tests$R  ${GRN}16 passed$R ${DIM}(16)$R",
    "   ${B}Start at$R  21:05:40",
    "   ${B}Duration$R  1.24s",
)

/**
 * The simulated Claude Code session (the demo's centerpiece). A realistic
 * mid-task scrollback: the user asked for a rate limiter, Claude read and
 * edited files, ran the tests, and is currently "working".
 */
private fun claudeTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "$CLAUDE╭──────────────────────────────────────────────────────────────╮$R",
    "$CLAUDE│$R $CLAUDE✻$R ${B}Welcome to Claude Code!$R                                     $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   ${DIM}/help for help, /status for your current setup$R             $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   ${DIM}cwd: /Users/demo/code/orbit$R                                $CLAUDE│$R",
    "$CLAUDE╰──────────────────────────────────────────────────────────────╯$R",
    "",
    "$B>$R add a token-bucket rate limiter to the sessions API and wire it",
    "  into the mutating routes",
    "",
    "$B●$R I'll add a token-bucket rate limiter. Let me look at the sessions",
    "  API first to see how the routes are wired.",
    "",
    "$B●$R ${B}Read$R(src/api/sessions.ts)",
    "  ${DIM}⎿  Read 142 lines$R",
    "",
    "$B●$R The routes go through the small Router helper in src/lib/router.ts,",
    "  which already supports per-route middleware — so a reusable limiter",
    "  in ${B}src/api/ratelimit.ts$R can be passed straight into the route",
    "  registrations.",
    "",
    "$B●$R ${B}Write$R(src/api/ratelimit.ts)",
    "  ${DIM}⎿  Wrote 58 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/api/sessions.ts)",
    "  ${DIM}⎿  Updated src/api/sessions.ts with 9 additions and 2 removals$R",
    "",
    "$B●$R ${B}Bash$R(npx vitest run src/api)",
    "  ${DIM}⎿  16 passed (16)$R",
    "",
    "$B●$R Tests pass. The POST and DELETE routes are now limited to a burst",
    "  of 20 requests with a refill of 5/s per client. Next I'll update the",
    "  README and add a 429 integration test.",
    "",
    // The transcript ends mid-status-line (no trailing newline): the live
    // script's CLEAR_LINE rewrites tick this exact line in place.
) + claudeStatus("Frosting", 157, "thinking with high effort")

/** Shell session: a pull, a build, and a git status matching the fixtures. */
private fun buildShellTranscript(): String = lines(
    "$DEMO_PROMPT${"git pull"}",
    "Already up to date.",
    "$DEMO_PROMPT${"npm run build"}",
    "",
    "> orbit@0.4.2 build",
    "> esbuild src/server.ts --bundle --platform=node --outfile=dist/server.js",
    "",
    "  ${B}dist/server.js$R  ${CYAN}412.3kb$R",
    "",
    "${YELLOW}⚡$R ${GRN}Done in 38ms$R",
    "$DEMO_PROMPT${"git status -sb"}",
    "## ${GRN}feature/rate-limit$R",
    " ${GRN}A$R  src/api/ratelimit.ts",
    " ${RED}M$R README.md",
    " ${RED}M$R src/api/sessions.ts",
    " ${RED}M$R src/server.ts",
    "${RED}??$R scratch/notes.md",
) + DEMO_PROMPT

/** Dev-server log tail (no prompt — Ctrl-C "stops" it and drops to zsh). */
private fun devServerTranscript(): String = lines(
    "$DEMO_PROMPT${"npm run dev"}",
    "",
    "> orbit@0.4.2 dev",
    "> tsx watch src/server.ts",
    "",
    "${DIM}[21:02:11]$R ${B}orbit$R listening on ${CYAN}http://localhost:8787$R",
    "${DIM}[21:02:14]$R ${GRN}GET$R  /api/health ${GRN}200$R ${DIM}2ms$R",
    "${DIM}[21:02:31]$R ${YELLOW}POST$R /api/sessions ${GRN}201$R ${DIM}14ms$R",
    "${DIM}[21:02:31]$R ${GRN}GET$R  /api/sessions/se_01HZX4 ${GRN}200$R ${DIM}3ms$R",
    "${DIM}[21:02:48]$R ${YELLOW}POST$R /api/sessions ${GRN}201$R ${DIM}9ms$R",
    "${DIM}[21:03:02]$R ${YELLOW}POST$R /api/sessions ${RED}429$R ${DIM}1ms  rate limited (token bucket empty)$R",
    "${DIM}[21:03:09]$R ${GRN}GET$R  /api/sessions/se_01HZX4 ${GRN}200$R ${DIM}2ms$R",
    "${DIM}[21:05:40]$R ${RED}DELETE$R /api/sessions/se_01HZX4 ${GRN}204$R ${DIM}5ms$R",
    "${DIM}[21:08:17]$R ${GRN}GET$R  /api/health ${GRN}200$R ${DIM}1ms$R",
)

/**
 * A second Claude Code session stopped at a tool-permission prompt — the
 * fixture behind the "waiting for input" state, so the demo shows the
 * red/fading attention indicator next to the blue "working" one. No
 * trailing shell prompt: Claude owns the tty (Ctrl-C drops to zsh).
 */
private fun claudeWaitingTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R make the rate-limiter tests cover the refill path too",
    "",
    "$B●$R I'll extend the tests. Let me check the current coverage first.",
    "",
    "$B●$R ${B}Read$R(src/api/ratelimit.test.ts)",
    "  ${DIM}⎿  Read 61 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/api/ratelimit.test.ts)",
    "  ${DIM}⎿  Added 2 tests: refill restores tokens over time; never exceeds capacity$R",
    "",
    "$B●$R ${B}Bash$R(npx vitest run src/api/ratelimit.test.ts)",
    "",
    "$CLAUDE╭──────────────────────────────────────────────────────────────╮$R",
    "$CLAUDE│$R ${B}Do you want to run this command?$R                             $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   npx vitest run src/api/ratelimit.test.ts                   $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R $GRN❯ 1. Yes$R                                                     $CLAUDE│$R",
    "$CLAUDE│$R   2. Yes, and don't ask again for npx vitest in orbit        $CLAUDE│$R",
    "$CLAUDE│$R   3. No, and tell Claude what to do differently ${DIM}(esc)$R        $CLAUDE│$R",
    "$CLAUDE╰──────────────────────────────────────────────────────────────╯$R",
)

/**
 * A third Claude Code session mid-task — a second "working" state so the
 * demo shows several agents being watched at once. No trailing shell
 * prompt: Claude owns the tty (Ctrl-C drops to zsh).
 */
private fun claudeDocsTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R update docs/architecture.md and the README for the new rate limiter",
    "",
    "$B●$R I'll bring the docs in line with the limiter. Reading both files",
    "  first to match their tone.",
    "",
    "$B●$R ${B}Read$R(docs/architecture.md)",
    "  ${DIM}⎿  Read 24 lines$R",
    "",
    "$B●$R ${B}Edit$R(docs/architecture.md)",
    "  ${DIM}⎿  Added ratelimit to the module list$R",
    "",
    "$B●$R ${B}Write$R(scratch/notes.md)",
    "  ${DIM}⎿  Wrote 6 lines$R",
    "",
    // Ends mid-status-line, like claudeTranscript(), for the live script.
) + claudeStatus("Noodling", 71, "reading the deploy docs")

/**
 * A fourth Claude Code session that has just *finished* a task: the full
 * run is in the scrollback (request, tool calls, green test run, a Done
 * summary) and the session sits idle at the Claude input prompt. Typing
 * anything into it plays [claudeDoneInputScript] — Claude picks the work
 * back up and the pane flips to the "working" state until the burst ends.
 */
private fun claudeDoneTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R add a /metrics endpoint exposing request counters",
    "",
    "$B●$R I'll add a small metrics module and expose it on the API. Reading",
    "  the server entry first to see where it mounts.",
    "",
    "$B●$R ${B}Read$R(src/server.ts)",
    "  ${DIM}⎿  Read 88 lines$R",
    "",
    "$B●$R ${B}Write$R(src/api/metrics.ts)",
    "  ${DIM}⎿  Wrote 41 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/server.ts)",
    "  ${DIM}⎿  Updated src/server.ts with 4 additions$R",
    "",
    "$B●$R ${B}Bash$R(npx vitest run)",
    "  ${DIM}⎿  19 passed (19)$R",
    "",
    "$B●$R Done — ${B}GET /metrics$R now reports request totals, 429 counts, and",
    "  active sessions. All 19 tests pass. Tell me if you'd like histograms",
    "  or a Prometheus text format on top.",
    "",
) + DEMO_CLAUDE_PROMPT

/** esbuild watch output (no prompt — a foreground watcher). */
private fun watchTranscript(): String = lines(
    "$DEMO_PROMPT${"npm run watch"}",
    "",
    "> orbit@0.4.2 watch",
    "> esbuild src/server.ts --bundle --platform=node --outfile=dist/server.js --watch",
    "",
    "${DIM}[watch]$R build finished, watching for changes...",
    "${DIM}[21:06:02]$R rebuild succeeded in ${YELLOW}31ms$R ${DIM}(src/api/sessions.ts changed)$R",
    "${DIM}[21:08:17]$R rebuild succeeded in ${YELLOW}27ms$R ${DIM}(src/api/ratelimit.ts changed)$R",
)

/**
 * The session specs for every PTY session referenced by
 * [DemoFixtures.initialConfig]. Looked up by [DemoServer] when seeding its
 * session table.
 *
 * @return one spec per fixture session id.
 */
internal fun demoSessionSpecs(): List<DemoSessionSpec> = listOf(
    DemoSessionSpec(
        sessionId = "demo-s1",
        transcript = claudeTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false, // Claude owns the tty; Ctrl-C stops the feed
        respond = ::demoShellRespond,
        liveScript = claudeMainLiveScript(),
    ),
    DemoSessionSpec(
        sessionId = "demo-s2",
        transcript = buildShellTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = true,
        respond = ::demoShellRespond,
    ),
    DemoSessionSpec(
        sessionId = "demo-s3",
        transcript = devServerTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false,
        respond = ::demoShellRespond,
    ),
    DemoSessionSpec(
        sessionId = "demo-s4",
        transcript = claudeWaitingTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false, // Claude owns the tty until Ctrl-C
        respond = ::demoShellRespond,
    ),
    DemoSessionSpec(
        sessionId = "demo-s5",
        transcript = watchTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false,
        respond = ::demoShellRespond,
    ),
    DemoSessionSpec(
        sessionId = "demo-s6",
        transcript = claudeDoneTranscript(),
        prompt = DEMO_CLAUDE_PROMPT,
        startsAtPrompt = true, // idle at the Claude input prompt
        respond = ::demoShellRespond, // unused: inputScript takes the Enter path
        inputScript = ::claudeDoneInputScript,
    ),
    DemoSessionSpec(
        sessionId = "demo-s7",
        transcript = claudeDocsTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false, // Claude owns the tty until Ctrl-C
        respond = ::demoShellRespond,
        liveScript = claudeDocsLiveScript(),
    ),
)

/**
 * Spec for a session created at runtime (the user clicked "new terminal"
 * in the demo): a fresh shell showing just the login banner and a prompt.
 *
 * @param sessionId the id allocated by [DemoServer].
 * @return a spec for a pristine interactive shell.
 */
internal fun newShellSessionSpec(sessionId: String): DemoSessionSpec = DemoSessionSpec(
    sessionId = sessionId,
    transcript = lines("Last login: Wed Jun 10 21:14:07 on ttys00${sessionId.length % 10}") + DEMO_PROMPT,
    prompt = DEMO_PROMPT,
    startsAtPrompt = true,
    respond = ::demoShellRespond,
)
