/**
 * Canned terminal content for demo mode: the initial scrollback transcript of
 * every demo PTY session, the shell prompt, and the command→response tables
 * used when the user types into a demo terminal.
 *
 * Everything in this file is static — nothing is randomised at runtime, so
 * the demo looks identical on every load and on every client. All timestamps,
 * fake command output, and the simulated Claude Code session are authored
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

/** Prompt shown by the simulated Claude Code session for typed input. */
internal val DEMO_CLAUDE_PROMPT: String = "$B>$R "

/**
 * Joins transcript lines with the CRLF line endings a real PTY produces
 * (xterm.js and the mobile terminal views both expect `\r\n`).
 *
 * @param l the lines of the transcript, without trailing newlines.
 * @return the lines joined and terminated with `\r\n`.
 */
private fun lines(vararg l: String): String = l.joinToString("\r\n", postfix = "\r\n")

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
 */
internal class DemoSessionSpec(
    val sessionId: String,
    val transcript: String,
    val prompt: String,
    val startsAtPrompt: Boolean,
    val respond: (String) -> String,
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
 * Canned reply used when the user types into the simulated Claude Code
 * session. Keeps the Claude look-and-feel without pretending to be live.
 *
 * @param line the trimmed input line.
 * @return a Claude-style canned response block.
 */
internal fun demoClaudeRespond(line: String): String {
    if (line.isBlank()) return ""
    return lines(
        "",
        "$CLAUDE✻$R ${DIM}Thinking…$R",
        "",
        "$B●$R This is the canned demo session — I can't take new requests here,",
        "  but everything above is what a real Claude Code run looks like in",
        "  termtastic. Connect to a real server to go interactive.",
        "",
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
    "$CLAUDE✻$R ${DIM}Polishing… (37s · ↑ 2.4k tokens · esc to interrupt)$R",
    "",
) + DEMO_CLAUDE_PROMPT

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
    "$CLAUDE✻$R ${DIM}Documenting… (12s · ↑ 1.1k tokens · esc to interrupt)$R",
)

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
        prompt = DEMO_CLAUDE_PROMPT,
        startsAtPrompt = true,
        respond = ::demoClaudeRespond,
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
        sessionId = "demo-s7",
        transcript = claudeDocsTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false, // Claude owns the tty until Ctrl-C
        respond = ::demoShellRespond,
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
