/**
 * Canned terminal content for demo mode: the initial scrollback transcript of
 * every demo PTY session, the shell prompt, the command→response tables used
 * when the user types into a demo terminal, and the timed output scripts
 * ([DemoScriptStep]) that make the Claude sessions look *live* — the two
 * "working" sessions loop a feed of tool calls and status-line ticks, the
 * finished session plays a one-shot work burst whenever the user talks to it,
 * and the ProTracker pane loops a playback feed (rows scrolling, VU meters
 * ticking) without ever being an "agent".
 *
 * Everything in this file is static — nothing is randomised at runtime, so
 * the demo looks identical on every load and on every client. All timestamps,
 * fake command output, and the simulated Claude Code sessions are authored
 * here; tweak this file to change what the demo shows. The fiction is an
 * Amiga demoscene production (see [DemoFixtures]): the shell is a modern
 * cross-dev box building 68k assembly with vasm, and the easter eggs
 * (`guru`, `load"*",8,1`) lean into it.
 *
 * @see DemoTerminalSession for the line-discipline simulation that replays
 *   these transcripts and answers typed commands
 * @see DemoFixtures for the workspace layout, file tree, and git fixtures
 */
package se.soderbjorn.lunamux.client.demo

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

/** Red — errors, git deletions, and the Guru Meditation. */
private const val RED = "$E[31m"

/** Plain green — additions, success markers. */
private const val GRN = "$E[32m"

/** Dim/faint text. */
private const val DIM = "$E[2m"

/** Bold. */
private const val B = "$E[1m"

/** Claude Code's warm accent colour (256-colour peach). */
private const val CLAUDE = "$E[38;5;215m"

/** A literal dollar sign — `$bfe001`-style hex would otherwise interpolate. */
private const val D = "$"

/**
 * The zsh-style prompt every demo shell session shows. Static (always the
 * project directory) so typed commands and their canned outputs stay
 * coherent with the fixture file tree and git state.
 */
internal val DEMO_PROMPT: String =
    "$GREEN demo@a500 $R$BLUE~/code/lastlight$R % "

/**
 * Prompt block shown by the simulated Claude Code session for typed input,
 * matching the real Claude Code chrome exactly: the `›` input caret sits in a
 * box (a dim rule above and below it) with the `⏵⏵ auto mode on` status line
 * *underneath* the box — the true on-screen order.
 *
 * Rendering that order in an append-only scrollback sim takes one trick: the
 * caret is not the last thing printed (the status line is), so the block ends
 * with an ANSI cursor move (`ESC[2A` up two rows, then column 3) that parks the
 * cursor right after `› ` — so typed characters still echo at the caret while
 * the status line shows below. On submit, [DemoTerminalSession] erases from the
 * caret line downward (`ESC[0J`) before the reply so the box chrome (lower rule
 * + status line) doesn't linger under the typed prompt. @see DemoTerminalSession
 */
internal val DEMO_CLAUDE_PROMPT: String =
    "${DIM}─────────────────────────────────────────────────────────$R\r\n" +
    "$B›$R \r\n" +
    "${DIM}─────────────────────────────────────────────────────────$R\r\n" +
    "$CYAN⏵⏵$R ${B}auto mode on$R ${DIM}(shift+tab to cycle) · esc to interrupt · ← for agents$R" +
    "$E[2A\r$E[2C"

/**
 * Return-to-column-0 + erase-line — prefixed to script steps that rewrite
 * the Claude status line (or the tracker's position line) in place,
 * mimicking how the real Claude Code spinner ticks without scrolling.
 */
internal const val CLEAR_LINE: String = "\r$E[2K"

/**
 * One Claude Code status line in the current spinner style
 * (`✻ Blitting… (3m 16s · almost done thinking with high effort)`), written
 * without a trailing newline so the next [CLEAR_LINE]-prefixed step can
 * rewrite it in place.
 *
 * @param verb the whimsical present-participle activity label (`"Blitting"`
 *   — the demo's verbs leant scene-wards on purpose).
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
 *   [se.soderbjorn.lunamux.WindowConfig]).
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
 *   a program that is *currently* running (an agent working, or the tracker
 *   playing). The transcript must end mid-status-line (no trailing newline)
 *   so the first [CLEAR_LINE] rewrite lands on it. Keystrokes are swallowed
 *   while the feed runs; Ctrl-C stops it and drops to [prompt].
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
 * The looping live feed for the main scroller Claude session (`demo-s1`):
 * status-line ticks rewritten in place, interleaved with tool calls and
 * short assistant remarks, so the pane genuinely looks like an agent
 * mid-task rather than a frozen screenshot. Played forever by
 * [DemoTerminalSession]; each loop iteration reads as "Claude doing the
 * next round of rasterline hunting", so the repeat is unobtrusive.
 *
 * @return the steps of one loop iteration.
 */
private fun claudeMainLiveScript(): List<DemoScriptStep> = listOf(
    DemoScriptStep(1_700, CLEAR_LINE + claudeStatus("Racing the beam", 161, "counting rasterlines")),
    DemoScriptStep(1_900, CLEAR_LINE + claudeStatus("Racing the beam", 166, "counting rasterlines")),
    DemoScriptStep(
        1_600,
        CLEAR_LINE + toolStep("Edit", "src/fx/scroller.s", "Updated src/fx/scroller.s with 6 additions and 9 removals") +
            claudeStatus("Blitting", 168, "moving the glyph fetch into the border"),
    ),
    DemoScriptStep(2_100, CLEAR_LINE + claudeStatus("Blitting", 172, "almost done thinking with high effort")),
    DemoScriptStep(
        1_500,
        CLEAR_LINE +
            "$B●$R The glyph fetch now rides the top border — 11 rasterlines back.\r\n" +
            "  Pre-shifting the font next so the odd pixels stop shimmering.\r\n\r\n" +
            claudeStatus("Pre-shifting", 174, "rebuilding the font strip"),
    ),
    DemoScriptStep(
        1_800,
        CLEAR_LINE + toolStep("Edit", "src/fx/scroller.s", "Updated src/fx/scroller.s with 4 additions") +
            claudeStatus("Pre-shifting", 178, "running the raster budget"),
    ),
    DemoScriptStep(
        2_200,
        CLEAR_LINE + toolStep("Bash", "make test", "4 parts within budget (35 lines spare)") +
            claudeStatus("Racing the beam", 183, "verifying at 50 fps"),
    ),
    DemoScriptStep(
        1_900,
        CLEAR_LINE +
            "$B●$R 277/312 — that's enough headroom to double the starfield in the\r\n" +
            "  greets part if we feel like showing off.\r\n\r\n" +
            claudeStatus("Racing the beam", 186, "thinking with high effort"),
    ),
    DemoScriptStep(2_400, CLEAR_LINE + claudeStatus("Racing the beam", 191, "almost done thinking with high effort")),
)

/**
 * The looping live feed for the NFO-writing Claude session (`demo-s7`), same
 * mechanics as [claudeMainLiveScript] but slower-paced and centred on the
 * release paperwork (file_id.diz, README credits), so the two working panes
 * never look synchronised.
 *
 * @return the steps of one loop iteration.
 */
private fun claudeDocsLiveScript(): List<DemoScriptStep> = listOf(
    DemoScriptStep(2_000, CLEAR_LINE + claudeStatus("Inking", 75, "lining up the ascii border")),
    DemoScriptStep(
        1_700,
        CLEAR_LINE + toolSummary(
            "Searching for ${B}2$R patterns, reading ${B}3$R files…",
            "scroller.txt",
        ) + claudeStatus("Inking", 78, "lining up the ascii border"),
    ),
    DemoScriptStep(2_300, CLEAR_LINE + claudeStatus("Inking", 82, "thinking with high effort")),
    DemoScriptStep(
        1_600,
        CLEAR_LINE + toolStep("Edit", "file_id.diz", "Centred the frame — 31 columns, safe for any BBS") +
            claudeStatus("Pixeling", 85, "updating the README credits"),
    ),
    DemoScriptStep(
        2_000,
        CLEAR_LINE +
            "$B●$R Cross-checking the greets against the scrolltext so nobody\r\n" +
            "  gets missed. Crews remember.\r\n\r\n" +
            claudeStatus("Pixeling", 89, "cross-checking the greets"),
    ),
    DemoScriptStep(
        1_800,
        CLEAR_LINE + toolStep("Edit", "README.md", "Added the credits section") +
            claudeStatus("Polishing", 93, "almost done thinking with high effort"),
    ),
    DemoScriptStep(2_500, CLEAR_LINE + claudeStatus("Polishing", 98, "almost done thinking with high effort")),
)

/**
 * The one-shot work burst played when the user types into the *finished*
 * Claude session (`demo-s6`, the greetings part): whatever was typed,
 * Claude acknowledges it, "works" for around nine seconds (tool calls + a
 * ticking status line — long enough for the working indicator to be clearly
 * seen), then prints a completion line and returns to the input prompt.
 * Three canned variants rotate on [runIndex] so repeated messages don't
 * replay identical output.
 *
 * The typed line itself is deliberately ignored beyond having triggered the
 * run — the demo can't actually do the work, and any canned text that
 * pretended to parse the request would read as wrong more often than right.
 * One recognized wink is the exception: a line mentioning `POKE` (or the
 * address 53280 — the demo tour types "add a POKE 53280,0" here) selects a
 * themed variant where Claude translates the C64 border poke into its Amiga
 * equivalent (COLOR00 at `$DFF180`), which is what a real Claude would do
 * with that request on this A500 project.
 *
 * @param line the input line the user submitted (only sniffed for the POKE
 *   wink, see above).
 * @param runIndex 0-based count of bursts already played in this session.
 * @return the steps of one burst, ending with [DEMO_CLAUDE_PROMPT].
 */
private fun claudeDoneInputScript(line: String, runIndex: Int): List<DemoScriptStep> {
    val poke = line.contains("poke", ignoreCase = true) || line.contains("53280")
    val ack = listOf(
        "$B●$R On it — reshuffling the greets part now.",
        "$B●$R Good call. Let me fold that in.",
        "$B●$R Sure — taking another pass.",
    )
    val read = listOf(
        toolStep("Read", "src/fx/greets.s", "Read 52 lines"),
        toolStep("Read", "scroller.txt", "Read 12 lines"),
        toolStep("Read", "src/main.s", "Read 23 lines"),
    )
    val edit = listOf(
        toolStep("Edit", "src/fx/greets.s", "Updated src/fx/greets.s with 4 additions"),
        toolStep("Edit", "src/fx/greets.s", "Re-ordered the greets — biggest crew last"),
        toolStep("Edit", "scroller.txt", "Scrolltext and greets agree again"),
    )
    val done = listOf(
        "$B●$R Done — folded that in; the frame still closes at 288/312.",
        "$B●$R That's in. Budget untouched — 24 rasterlines spare.",
        "$B●$R Done. Greets and scrolltext agree; the budget stays green.",
    )
    val v = runIndex.mod(ack.size)
    val ackLine = if (poke) "$B●$R That POKE is C64 for border-black — on this Amiga that's COLOR00 (\$DFF180). On it." else ack[v]
    val readStep = if (poke) toolStep("Read", "src/main.s", "Read 23 lines") else read[v]
    val editStep = if (poke) toolStep("Edit", "src/main.s", "move.w #\$000,\$dff180 — border black at init") else edit[v]
    val doneLine = if (poke) "$B●$R Done — border's black before frame one. Wrong machine, right instinct." else done[v]
    val summaryFile = if (poke) "src/main.s" else "src/fx/greets.s"
    return listOf(
        DemoScriptStep(400, "\r\n$CLAUDE✻$R ${DIM}Thinking…$R"),
        DemoScriptStep(1_300, CLEAR_LINE + ackLine + "\r\n\r\n" + claudeStatus("Guru meditating", 3, "thinking with high effort")),
        DemoScriptStep(
            1_700,
            CLEAR_LINE + toolSummary(
                "Searching for ${B}3$R patterns, reading ${B}2$R files, running ${B}1$R shell command…",
                summaryFile,
            ) + claudeStatus("Guru meditating", 6, "thinking with high effort"),
        ),
        DemoScriptStep(1_500, CLEAR_LINE + readStep + claudeStatus("Blitting", 8, "folding the change in")),
        DemoScriptStep(1_900, CLEAR_LINE + editStep + claudeStatus("Crunching", 10, "running the raster budget")),
        DemoScriptStep(
            2_100,
            CLEAR_LINE + toolStep("Bash", "make test", "4 parts within budget (24 lines spare)") +
                claudeStatus("Racing the beam", 13, "almost done thinking with high effort"),
        ),
        DemoScriptStep(1_400, CLEAR_LINE + doneLine + "\r\n\r\n" + DEMO_CLAUDE_PROMPT),
    )
}

/** Programs that would take over the screen; politely declined in the demo. */
private val demoFullScreenCommands = listOf(
    "top", "htop", "btop", "vim", "vi", "nvim", "nano", "emacs", "less", "more", "man", "tmux", "ssh",
    "protracker", "fs-uae", "x64sc",
)

/** `cat <file>` outputs for the files most likely to be poked at. */
private val demoCatOutputs: Map<String, String> = mapOf(
    "README.md" to lines(
        "# LAST LIGHT",
        "",
        "A four-channel trackmo for the stock Amiga 500 (OCS, 512K chip +",
        "512K slow) by PHOSPHOR. First shown at AFTERGLOW 2026.",
        "",
        "## Build",
        "",
        "    make        # assemble (vasm) and link (vlink)",
        "    make adf    # bootable disk image (xdftool)",
        "    make run    # boot the image in FS-UAE",
    ),
    "scroller.txt" to lines(
        "YOU ARE NOW ROCKING WITH  * P H O S P H O R *  AND THIS IS",
        "-- L A S T   L I G H T --  OUR FIRST TRACKMO FOR THE MIGHTY AMIGA 500 ...",
        "",
        "CODE BY DELTRON ... PIXELS BY MIRAGE ... MUSIC BY QWAVE ...",
        "",
        "IF YOU CAN READ THIS YOU ARE STANDING TOO CLOSE TO THE BEAM ...",
        "SEE YOU AT AFTERGLOW ...   WRAP!",
    ),
    "file_id.diz" to lines(
        " .-------------------------------.",
        " |  L A S T   L I G H T          |",
        " |  a trackmo for the stock A500 |",
        " |       by  P H O S P H O R     |",
        " |                               |",
        " |  code deltron . gfx mirage    |",
        " |  music qwave                  |",
        " |  released at AFTERGLOW 2026   |",
        " `-------------------------------'",
    ),
    ".gitignore" to lines("build/", "*.adf", "*.shr", "fs-uae.log"),
)

/**
 * Exact-match command table for the demo shell. Output strings are fully
 * static; see [demoShellRespond] for prefix commands (`echo`, `cd`, `cat`)
 * and the fallback. Includes two period-appropriate easter eggs: `guru`
 * (the Guru Meditation) and `load"*",8,1` (wrong machine, nice reflexes).
 */
private val demoCommandTable: Map<String, String> = mapOf(
    "help" to lines(
        "${B}lunamux demo shell$R — canned commands you can try:",
        "",
        "  ls, ls -la, pwd, whoami, date, uname -a, uptime, history",
        "  git status, git log, git diff, git branch",
        "  cat README.md, cat scroller.txt, cat file_id.diz",
        "  make, make test, make adf, make run",
        "  echo <text>, clear, claude, guru, load\"*\",8,1",
        "",
        "${DIM}Anything else gets a perfectly honest 'command not found'.$R",
    ),
    "ls" to lines(
        "${BLUE}build$R     ${BLUE}gfx$R   ${BLUE}mods$R  ${BLUE}scratch$R  ${BLUE}src$R  ${BLUE}tools$R",
        "Makefile  README.md  file_id.diz  scroller.txt",
    ),
    "ls -la" to lines(
        "total 72",
        "drwxr-xr-x   13 demo  staff    416 Jun 10 20:58 ${BLUE}.$R",
        "drwxr-xr-x    5 demo  staff    160 Jun 02 09:12 ${BLUE}..$R",
        "drwxr-xr-x   12 demo  staff    384 Jun 10 20:55 ${BLUE}.git$R",
        "-rw-r--r--    1 demo  staff     38 May 28 14:03 .gitignore",
        "-rw-r--r--    1 demo  staff    742 Jun 02 09:13 Makefile",
        "-rw-r--r--    1 demo  staff    604 Jun 10 20:41 README.md",
        "drwxr-xr-x    4 demo  staff    128 Jun 10 20:51 ${BLUE}build$R",
        "-rw-r--r--    1 demo  staff    311 Jun 10 20:06 file_id.diz",
        "drwxr-xr-x    4 demo  staff    128 Jun 04 18:26 ${BLUE}gfx$R",
        "drwxr-xr-x    3 demo  staff     96 Jun 07 12:02 ${BLUE}mods$R",
        "drwxr-xr-x    3 demo  staff     96 Jun 10 20:58 ${BLUE}scratch$R",
        "-rw-r--r--    1 demo  staff    693 Jun 10 20:53 scroller.txt",
        "drwxr-xr-x    5 demo  staff    160 Jun 10 20:51 ${BLUE}src$R",
        "drwxr-xr-x    4 demo  staff    128 Jun 10 20:27 ${BLUE}tools$R",
    ),
    "pwd" to lines("/Users/demo/code/lastlight"),
    "whoami" to lines("demo"),
    "date" to lines("Wed Jun 10 21:14:07 CEST 2026"),
    "uname" to lines("Darwin"),
    "uname -a" to lines(
        "Darwin a500.local 25.3.0 Darwin Kernel Version 25.3.0: " +
            "Tue Apr 14 11:22:46 PDT 2026; root:xnu-11417.81.4~2/RELEASE_ARM64_T8122 arm64",
    ),
    "uptime" to lines("21:14  up 9 days,  4:12, 2 users, load averages: 1.84 2.01 2.12"),
    "history" to lines(
        "  501  make run",
        "  502  git checkout -b feature/sine-scroller",
        "  503  claude",
        "  504  make test",
        "  505  git status",
    ),
    "git status" to lines(
        "On branch ${B}feature/sine-scroller$R",
        "Changes to be committed:",
        "  (use \"git restore --staged <file>...\" to unstage)",
        "\t${GRN}new file:   src/fx/scroller.s$R",
        "",
        "Changes not staged for commit:",
        "  (use \"git add <file>...\" to update what will be committed)",
        "\t${RED}modified:   README.md$R",
        "\t${RED}modified:   src/main.s$R",
        "\t${RED}modified:   tools/sinetable.bas$R",
        "",
        "Untracked files:",
        "  (use \"git add <file>...\" to include in what will be committed)",
        "\t${RED}scratch/notes.md$R",
    ),
    "git log" to lines(
        "${YELLOW}commit 9c2e417a51b6d2f08c9a3f4e21d7b8a90c5d1e22$R (${CYAN}HEAD -> $R${GRN}feature/sine-scroller$R)",
        "Author: deltron <deltron@phosphor.scene>",
        "Date:   Tue Jun 9 17:40:12 2026 +0200",
        "",
        "    fx: plasma — halfbrite gradient and byte-flip mirror",
        "",
        "${YELLOW}commit 4b81f309ad7e6c215f0d9821c43ab6790112ffe0$R (${GRN}main$R)",
        "Author: deltron <deltron@phosphor.scene>",
        "Date:   Mon Jun 8 11:02:55 2026 +0200",
        "",
        "    startup: take the machine over politely, hand it back",
    ),
    "git log --oneline" to lines(
        "${YELLOW}9c2e417$R (${CYAN}HEAD -> $R${GRN}feature/sine-scroller$R) fx: plasma — halfbrite gradient and byte-flip mirror",
        "${YELLOW}4b81f30$R (${GRN}main$R) startup: take the machine over politely, hand it back",
        "${YELLOW}d27a90c$R fx: copper bars with mirrored gradients",
        "${YELLOW}311bd04$R initial import: makefile, startup, boot block",
    ),
    "git branch" to lines("* ${GRN}feature/sine-scroller$R", "  main"),
    "git diff" to lines(
        "${B}diff --git a/src/main.s b/src/main.s$R",
        "${B}--- a/src/main.s$R",
        "${B}+++ b/src/main.s$R",
        "${CYAN}@@ -12,5 +14,6 @@$R",
        " .frame: bsr     waitvbl",
        "         bsr     copperbars_frame",
        "         bsr     plasma_frame",
        "${GRN}+        bsr     scroller_frame$R",
        "         btst    #6,${D}bfe001          ; left mouse button exits",
        "         bne.s   .frame",
        "${DIM}(use the Git pane for the full side-by-side view)$R",
    ),
    "make" to demoMakeOutput(),
    "make test" to demoMakeTestOutput(),
    "make adf" to lines(
        "xdftool build/lastlight.adf format \"LAST LIGHT\" + boot install + write build/lastlight.shr",
        "  ${B}build/lastlight.adf$R  ${CYAN}880K, bootable$R",
    ),
    "make run" to lines(
        "${DIM}demo: fs-uae is already running — see the trackmo tab.$R",
    ),
    "claude" to lines(
        "$CLAUDE╭──────────────────────────────────────────────╮$R",
        "$CLAUDE│$R $CLAUDE✻$R ${B}Welcome to Claude Code!$R                     $CLAUDE│$R",
        "$CLAUDE│$R                                              $CLAUDE│$R",
        "$CLAUDE│$R   ${DIM}This demo already has a Claude session —$R   $CLAUDE│$R",
        "$CLAUDE│$R   ${DIM}check the big pane in the compo tab.$R       $CLAUDE│$R",
        "$CLAUDE╰──────────────────────────────────────────────╯$R",
    ),
    "guru" to lines(
        "",
        "$RED┌────────────────────────────────────────────────────────────┐$R",
        "$RED│   Software Failure.  Press left mouse button to continue.  │$R",
        "$RED│              Guru Meditation #00000004.0000AAC0            │$R",
        "$RED└────────────────────────────────────────────────────────────┘$R",
        "",
        "${DIM}(the left mouse button does nothing. it never did.)$R",
    ),
    "load\"*\",8,1" to demoC64LoadOutput(),
    "LOAD\"*\",8,1" to demoC64LoadOutput(),
)

/** The vasm/vlink/shrinkler build shown by `make`. */
private fun demoMakeOutput(): String = lines(
    "vasmm68k_mot -kick1hunks -quiet -Fhunk -o build/main.o src/main.s",
    "vlink -bamigahunk -s -o build/lastlight build/main.o",
    "shrinkler -9 build/lastlight build/lastlight.shr",
    "  37248 -> ${B}14970$R bytes ${GRN}(40.2%)$R",
)

/** The raster-budget check shown by `make test` (the demo's "test suite"). */
private fun demoMakeTestOutput(): String = lines(
    "raster budget — PAL frame, 312 lines:",
    "",
    "  copperbars    64  ${GRN}[ok]$R",
    "  plasma       118  ${GRN}[ok]$R",
    "  scroller      41  ${GRN}[ok]$R",
    "  greets        65  ${GRN}[ok]$R",
    "  ------------------------",
    "  total        288  ${YELLOW}[24 spare]$R",
    "",
    "${GRN}4 parts within budget$R",
)

/** The other machine answers `load"*",8,1` — briefly. */
private fun demoC64LoadOutput(): String = lines(
    "SEARCHING FOR *",
    "LOADING",
    "READY.",
    "${DIM}(wrong machine — but nice reflexes. try$R ${B}guru$R ${DIM}for local flavour.)$R",
)

/**
 * One ProTracker VU meter: [n] of 8 cells lit, green over dim.
 *
 * @param n lit cells, 0–8.
 * @return the ANSI-coloured meter.
 */
private fun vu(n: Int): String = GRN + "▮".repeat(n) + R + DIM + "▯".repeat(8 - n) + R

/**
 * The tracker's in-place position line (`pos 11/42  pat 09  row 48` plus
 * four channel VU meters), written without a trailing newline so the live
 * feed can rewrite it with [CLEAR_LINE] the way the Claude spinner is.
 *
 * @param pos song position (of 42).
 * @param pat pattern number, two hex-ish digits as ProTracker shows them.
 * @param row row within the pattern, 0–63.
 * @param vus lit-cell counts for the four channel meters.
 * @return the ANSI-coloured position line.
 */
private fun ptPosition(pos: Int, pat: String, row: Int, vus: List<Int>): String {
    val r = row.toString().padStart(2, '0')
    return "  ${B}pos$R $pos/42  ${B}pat$R $pat  ${B}row$R $r   " + vus.joinToString(" ") { vu(it) }
}

/**
 * One scrolled-past pattern row in the tracker feed (`09|48  C-3 03 A08 |
 * …`), dim like history should be, CRLF-terminated.
 *
 * @param s the row text after the indent.
 * @return the dim row line.
 */
private fun ptRow(s: String): String = "  ${DIM}$s$R\r\n"

/**
 * The looping playback feed for the ProTracker pane (`demo-s3`): the
 * position line rewritten in place with ticking rows and dancing VU meters,
 * with a pattern row scrolling past every few steps. Deliberately *not* an
 * agent — the session isn't in [DemoFixtures.initialStates] and the
 * activity callback isn't wired at construction time, so the pane plays
 * music without pulsing blue.
 *
 * @return the steps of one loop iteration.
 */
private fun trackerLiveScript(): List<DemoScriptStep> = listOf(
    DemoScriptStep(850, CLEAR_LINE + ptPosition(11, "09", 52, listOf(6, 3, 5, 2))),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(11, "09", 56, listOf(4, 6, 3, 5))),
    DemoScriptStep(
        850,
        CLEAR_LINE + ptRow("09|60  D#3 03 ... | A#2 05 ... | --- .. ... | A-2 02 ...") +
            ptPosition(11, "09", 60, listOf(7, 4, 2, 6)),
    ),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(11, "09", 63, listOf(5, 2, 4, 3))),
    DemoScriptStep(
        850,
        CLEAR_LINE + ptRow("0A|00  C-3 03 F06 | G-2 05 ... | E-3 07 C30 | --- .. ...") +
            ptPosition(12, "0A", 0, listOf(8, 6, 7, 4)),
    ),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(12, "0A", 4, listOf(6, 5, 5, 3))),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(12, "0A", 8, listOf(4, 3, 6, 2))),
    DemoScriptStep(
        850,
        CLEAR_LINE + ptRow("0A|12  --- .. ... | A#2 05 ... | --- .. ... | A-2 02 A08") +
            ptPosition(12, "0A", 12, listOf(7, 5, 3, 6)),
    ),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(12, "0A", 16, listOf(5, 4, 4, 2))),
    DemoScriptStep(850, CLEAR_LINE + ptPosition(11, "09", 48, listOf(6, 4, 5, 3))),
)

/**
 * The simulated Claude Code session (the demo's centerpiece). A realistic
 * mid-task scrollback: the user asked for a sine scroller, Claude read the
 * effect chain, regenerated the sine table, wrote the effect, ran the
 * raster-budget check, and is currently "working" on the inner loop.
 */
private fun claudeTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "$CLAUDE╭──────────────────────────────────────────────────────────────╮$R",
    "$CLAUDE│$R $CLAUDE✻$R ${B}Welcome to Claude Code!$R                                     $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   ${DIM}/help for help, /status for your current setup$R             $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   ${DIM}cwd: /Users/demo/code/lastlight$R                            $CLAUDE│$R",
    "$CLAUDE╰──────────────────────────────────────────────────────────────╯$R",
    "",
    "$B>$R add a one-pixel sine scroller over the copper bars — it has to",
    "  hold 50 fps on a stock A500",
    "",
    "$B●$R I'll add the scroller. Let me read the effect chain and check the",
    "  raster budget first.",
    "",
    "$B●$R ${B}Read$R(src/main.s)",
    "  ${DIM}⎿  Read 23 lines$R",
    "",
    "$B●$R The frame has 65 rasterlines spare after the plasma. A one-pixel",
    "  scroller fits if the blitter moves the glyphs during the top border",
    "  — and the sine table needs to be wider to stop the shimmer, so I'll",
    "  regenerate it at 1024 entries with the group's C64 BASIC generator.",
    "",
    "$B●$R ${B}Edit$R(tools/sinetable.bas)",
    "  ${DIM}⎿  1024 entries, amplitude 40$R",
    "",
    "$B●$R ${B}Write$R(src/fx/scroller.s)",
    "  ${DIM}⎿  Wrote 38 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/main.s)",
    "  ${DIM}⎿  Updated src/main.s with 3 additions$R",
    "",
    "$B●$R ${B}Bash$R(make test)",
    "  ${DIM}⎿  4 parts within budget (24 lines spare)$R",
    "",
    "$B●$R Scroller's in at 41 rasterlines. Now tightening the inner loop —",
    "  I want more headroom before the greets part fades in.",
    "",
    // The transcript ends mid-status-line (no trailing newline): the live
    // script's CLEAR_LINE rewrites tick this exact line in place.
) + claudeStatus("Racing the beam", 157, "counting rasterlines")

/** Shell session: a pull, a build, and a git status matching the fixtures. */
private fun buildShellTranscript(): String = lines(
    "$DEMO_PROMPT${"git pull"}",
    "Already up to date.",
    "$DEMO_PROMPT${"make"}",
    "vasmm68k_mot -kick1hunks -quiet -Fhunk -o build/main.o src/main.s",
    "vlink -bamigahunk -s -o build/lastlight build/main.o",
    "shrinkler -9 build/lastlight build/lastlight.shr",
    "  37248 -> ${B}14970$R bytes ${GRN}(40.2%)$R",
    "$DEMO_PROMPT${"git status -sb"}",
    "## ${GRN}feature/sine-scroller$R",
    " ${GRN}A$R  src/fx/scroller.s",
    " ${RED}M$R README.md",
    " ${RED}M$R src/main.s",
    " ${RED}M$R tools/sinetable.bas",
    "${RED}??$R scratch/notes.md",
) + DEMO_PROMPT

/**
 * The ProTracker playback pane: module info in the scrollback, then a live
 * position line the [trackerLiveScript] rewrites in place. No prompt —
 * Ctrl-C "stops" the player and drops to zsh.
 */
private fun trackerTranscript(): String = lines(
    "$DEMO_PROMPT${"ptplay mods/lastlight.mod"}",
    "",
    "${B}ptplay 0.9$R — ProTracker (M.K.) player, Paula timing",
    "",
    "  ${B}title:$R    last light",
    "  ${B}format:$R   4 channels, 31 samples, 24 patterns, 42 positions",
    "  ${B}length:$R   3:12 at 125 BPM",
    "",
    "  ${GRN}▶$R 50Hz PAL vblank timing ${DIM}— ctrl-c stops the player$R",
    "",
    "  ${DIM}09|36  C-3 03 A08 | G-2 05 ... | E-3 07 C20 | --- .. ...$R",
    "  ${DIM}09|40  --- .. ... | G-2 05 ... | --- .. ... | A-2 02 ...$R",
    "  ${DIM}09|44  C-3 03 ... | --- .. ... | E-3 07 ... | --- .. ...$R",
    // Ends mid-position-line (no trailing newline) for the live feed.
) + ptPosition(11, "09", 48, listOf(6, 4, 5, 3))

/**
 * A second Claude Code session stopped at a tool-permission prompt — the
 * fixture behind the "waiting for input" state, so the demo shows the
 * red/fading attention indicator next to the blue "working" one. No
 * trailing shell prompt: Claude owns the tty (Ctrl-C drops to zsh).
 */
private fun claudeWaitingTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R the plasma blows the 262-line NTSC frame — get the demo under",
    "  budget on both timings",
    "",
    "$B●$R PAL gives us 312 rasterlines but NTSC only 262, and the frame",
    "  closes at 288 — the plasma is the fattest part, so it diets first.",
    "",
    "$B●$R ${B}Read$R(src/fx/plasma.s)",
    "  ${DIM}⎿  Read 15 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/fx/plasma.s)",
    "  ${DIM}⎿  Byte-flip mirror: walk half the table, flip for the bottom$R",
    "",
    "$B●$R ${B}Bash$R(make test)",
    "",
    "$CLAUDE╭──────────────────────────────────────────────────────────────╮$R",
    "$CLAUDE│$R ${B}Do you want to run this command?$R                             $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R   make test                                                  $CLAUDE│$R",
    "$CLAUDE│$R                                                              $CLAUDE│$R",
    "$CLAUDE│$R $GRN❯ 1. Yes$R                                                     $CLAUDE│$R",
    "$CLAUDE│$R   2. Yes, and don't ask again for make in lastlight          $CLAUDE│$R",
    "$CLAUDE│$R   3. No, and tell Claude what to do differently ${DIM}(esc)$R        $CLAUDE│$R",
    "$CLAUDE╰──────────────────────────────────────────────────────────────╯$R",
)

/**
 * A third Claude Code session mid-task — a second "working" state so the
 * demo shows several agents being watched at once: this one is on release
 * paperwork (file_id.diz, README credits). No trailing shell prompt:
 * Claude owns the tty (Ctrl-C drops to zsh).
 */
private fun claudeDocsTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R write the file_id.diz and a credits section for the readme",
    "",
    "$B●$R I'll match the group's usual ascii style. Reading the scrolltext",
    "  first so the credits agree with it.",
    "",
    "$B●$R ${B}Read$R(scroller.txt)",
    "  ${DIM}⎿  Read 12 lines$R",
    "",
    "$B●$R ${B}Write$R(file_id.diz)",
    "  ${DIM}⎿  Wrote 9 lines$R",
    "",
    "$B●$R ${B}Write$R(scratch/notes.md)",
    "  ${DIM}⎿  Wrote 6 lines$R",
    "",
    // Ends mid-status-line, like claudeTranscript(), for the live script.
) + claudeStatus("Inking", 71, "lining up the ascii border")

/**
 * A fourth Claude Code session that has just *finished* a task — the
 * greetings part: the full run is in the scrollback (request, tool calls,
 * green budget check, a Done summary) and the session sits idle at the
 * Claude input prompt. Typing anything into it plays
 * [claudeDoneInputScript] — Claude picks the work back up and the pane
 * flips to the "working" state until the burst ends.
 */
private fun claudeDoneTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R add the greetings part — starfield behind, one greet per beat",
    "",
    "$B●$R I'll run it after the plasma. Reading the frame loop to see where",
    "  it slots in.",
    "",
    "$B●$R ${B}Read$R(src/main.s)",
    "  ${DIM}⎿  Read 23 lines$R",
    "",
    "$B●$R ${B}Write$R(src/fx/greets.s)",
    "  ${DIM}⎿  Wrote 52 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/main.s)",
    "  ${DIM}⎿  Updated src/main.s with 2 additions$R",
    "",
    "$B●$R ${B}Bash$R(make test)",
    "  ${DIM}⎿  4 parts within budget (24 lines spare)$R",
    "",
    "$B●$R Done — six greets fade in over a 96-star field, one per beat, and",
    "  the frame still closes at 288/312. Tell me if the order should",
    "  change, or if a crew is missing.",
    "",
) + DEMO_CLAUDE_PROMPT

/**
 * A fifth Claude Code session that has just *finished* the logo graphics —
 * converting the PHOSPHOR logo to interleaved bitplanes — sitting in the
 * **Assets** tab beside the file browser. Like [claudeDoneTranscript] the whole
 * run is in the scrollback and the session rests idle at the Claude input
 * prompt; typing into it plays [claudeLogoInputScript] and the pane flips to
 * "working" for the burst. Kept out of [DemoFixtures.initialStates] so it starts
 * stateless (a legitimate "talk to me" target, unlike the working/waiting ones).
 */
private fun claudeLogoTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R convert the phosphor logo to 4 bitplanes — 320x64, interleaved for",
    "  the blitter",
    "",
    "$B●$R I'll palette-reduce it to the 16-colour demo set, then pack it",
    "  interleaved so the blitter shifts all four planes in one pass.",
    "  Reading the source IFF first.",
    "",
    "$B●$R ${B}Read$R(gfx/logo.iff)",
    "  ${DIM}⎿  320x64, 27 colours$R",
    "",
    "$B●$R ${B}Bash$R(python tools/iff2bpl.py gfx/logo.iff --planes 4 --interleaved)",
    "  ${DIM}⎿  wrote gfx/logo.bpl — 10240 bytes, 4 planes$R",
    "",
    "$B●$R ${B}Edit$R(src/fx/logo.s)",
    "  ${DIM}⎿  Updated src/fx/logo.s with 5 additions$R",
    "",
    "$B●$R ${B}Bash$R(make test)",
    "  ${DIM}⎿  4 parts within budget (24 lines spare)$R",
    "",
    "$B●$R Done — logo's four planes, interleaved, 10K on chip. The palette",
    "  folds into the copper so the bars bleed through the letters. Say the",
    "  word if you want it halved to 2 planes for more copper colours.",
    "",
) + DEMO_CLAUDE_PROMPT

/**
 * The one-shot work burst played when the user types into the finished logo
 * session (`demo-s8`): same shape and ~9 s pacing as [claudeDoneInputScript]
 * but themed to the graphics pipeline (palette, bitplanes, the copper bleed),
 * so the pane reads as a gfx agent picking the task back up. Three variants
 * rotate on [runIndex]; the typed [line] is only sniffed for the shared POKE
 * wink (border-black → Amiga COLOR00). Ends at [DEMO_CLAUDE_PROMPT].
 *
 * @param line the submitted input line (only sniffed for the POKE wink).
 * @param runIndex 0-based count of bursts already played this session.
 * @return the steps of one burst, ending with [DEMO_CLAUDE_PROMPT].
 */
private fun claudeLogoInputScript(line: String, runIndex: Int): List<DemoScriptStep> {
    val poke = line.contains("poke", ignoreCase = true) || line.contains("53280")
    val ack = listOf(
        "$B●$R On it — repacking the logo now.",
        "$B●$R Good call. Let me re-dither that.",
        "$B●$R Sure — another pass on the palette.",
    )
    val edit = listOf(
        toolStep("Edit", "src/fx/logo.s", "Re-dithered the gradient — banding gone"),
        toolStep("Edit", "gfx/logo.pal", "Remapped to the 16-entry demo palette"),
        toolStep("Edit", "src/fx/logo.s", "Interleaved the planes for a single blit"),
    )
    val done = listOf(
        "$B●$R Done — logo's crisp and the bars still bleed through. 10K on chip.",
        "$B●$R That's in. Palette folds into the copper; budget stays green.",
        "$B●$R Done. Four planes, one blit, 24 rasterlines spare.",
    )
    val v = runIndex.mod(ack.size)
    val ackLine = if (poke) "$B●$R That POKE is C64 for border-black — on this Amiga it's COLOR00 (\$DFF180). On it." else ack[v]
    val editStep = if (poke) toolStep("Edit", "src/main.s", "move.w #\$000,\$dff180 — border black at init") else edit[v]
    val doneLine = if (poke) "$B●$R Done — border's black before frame one. Wrong machine, right instinct." else done[v]
    return listOf(
        DemoScriptStep(400, "\r\n$CLAUDE✻$R ${DIM}Thinking…$R"),
        DemoScriptStep(1_300, CLEAR_LINE + ackLine + "\r\n\r\n" + claudeStatus("Pixeling", 3, "thinking with high effort")),
        DemoScriptStep(
            1_700,
            CLEAR_LINE + toolSummary(
                "Reading ${B}2$R files, running ${B}1$R shell command…",
                "gfx/logo.iff",
            ) + claudeStatus("Pixeling", 6, "counting the colours"),
        ),
        DemoScriptStep(1_500, CLEAR_LINE + toolStep("Read", "gfx/logo.iff", "320x64, 27 colours") + claudeStatus("Blitting", 8, "folding the change in")),
        DemoScriptStep(1_900, CLEAR_LINE + editStep + claudeStatus("Crunching", 10, "repacking the planes")),
        DemoScriptStep(
            2_100,
            CLEAR_LINE + toolStep("Bash", "make test", "4 parts within budget (24 lines spare)") +
                claudeStatus("Racing the beam", 13, "almost done thinking with high effort"),
        ),
        DemoScriptStep(1_400, CLEAR_LINE + doneLine + "\r\n\r\n" + DEMO_CLAUDE_PROMPT),
    )
}

/**
 * A sixth Claude Code session that has just *finished* the copper bars —
 * mirroring the gradient at the centre — sitting in the **Delta** (git) tab
 * beside the diff view. Same finished-and-idle shape as [claudeDoneTranscript]:
 * the run is in the scrollback, the session rests at the Claude input prompt,
 * and typing plays [claudeCopperInputScript]. Kept out of
 * [DemoFixtures.initialStates] so it starts stateless.
 */
private fun claudeCopperTranscript(): String = lines(
    "$DEMO_PROMPT${"claude"}",
    "",
    "$B>$R the copper bars should mirror at the centre — top gradient flipped",
    "  for the bottom half",
    "",
    "$B●$R Right — I'll walk half the gradient table and reuse it flipped for",
    "  the lower half, so the bars meet in a seam-free mirror. Reading the",
    "  copper list first.",
    "",
    "$B●$R ${B}Read$R(src/fx/copper.s)",
    "  ${DIM}⎿  Read 40 lines$R",
    "",
    "$B●$R ${B}Edit$R(src/fx/copper.s)",
    "  ${DIM}⎿  Byte-flip mirror: 32 colours down, the same 32 back up$R",
    "",
    "$B●$R ${B}Bash$R(make test)",
    "  ${DIM}⎿  4 parts within budget (24 lines spare)$R",
    "",
    "$B●$R Done — 64 copper moves, mirrored at line 128. The seam's gone and",
    "  it still fits the WAIT budget. Want the mirror point to breathe with",
    "  the music?",
    "",
) + DEMO_CLAUDE_PROMPT

/**
 * The one-shot burst played when the user types into the finished copper-bars
 * session (`demo-s9`): same ~9 s shape as [claudeDoneInputScript], themed to
 * the copper list (gradient table, WAIT budget, the seam mirror). Three
 * variants rotate on [runIndex]; the typed [line] is sniffed only for the
 * shared POKE wink. Ends at [DEMO_CLAUDE_PROMPT].
 *
 * @param line the submitted input line (only sniffed for the POKE wink).
 * @param runIndex 0-based count of bursts already played this session.
 * @return the steps of one burst, ending with [DEMO_CLAUDE_PROMPT].
 */
private fun claudeCopperInputScript(line: String, runIndex: Int): List<DemoScriptStep> {
    val poke = line.contains("poke", ignoreCase = true) || line.contains("53280")
    val ack = listOf(
        "$B●$R On it — reworking the gradient now.",
        "$B●$R Good call. Let me re-tune the ramp.",
        "$B●$R Sure — another pass on the copper list.",
    )
    val edit = listOf(
        toolStep("Edit", "src/fx/copper.s", "Eased the ramp — 32 steps, no banding"),
        toolStep("Edit", "src/fx/copper.s", "Moved the mirror seam to line 130"),
        toolStep("Edit", "src/fx/copper.s", "Halved the WAIT count — one move per two lines"),
    )
    val done = listOf(
        "$B●$R Done — bars are smooth and the seam's invisible. Budget green.",
        "$B●$R That's in. 64 moves, mirrored, 24 rasterlines spare.",
        "$B●$R Done. The gradient meets itself clean at the centre.",
    )
    val v = runIndex.mod(ack.size)
    val ackLine = if (poke) "$B●$R That POKE is C64 for border-black — on this Amiga it's COLOR00 (\$DFF180). On it." else ack[v]
    val editStep = if (poke) toolStep("Edit", "src/main.s", "move.w #\$000,\$dff180 — border black at init") else edit[v]
    val doneLine = if (poke) "$B●$R Done — border's black before frame one. Wrong machine, right instinct." else done[v]
    return listOf(
        DemoScriptStep(400, "\r\n$CLAUDE✻$R ${DIM}Thinking…$R"),
        DemoScriptStep(1_300, CLEAR_LINE + ackLine + "\r\n\r\n" + claudeStatus("Coppering", 3, "thinking with high effort")),
        DemoScriptStep(
            1_700,
            CLEAR_LINE + toolSummary(
                "Reading ${B}1$R file, running ${B}1$R shell command…",
                "src/fx/copper.s",
            ) + claudeStatus("Coppering", 6, "walking the gradient table"),
        ),
        DemoScriptStep(1_500, CLEAR_LINE + toolStep("Read", "src/fx/copper.s", "Read 40 lines") + claudeStatus("Blitting", 8, "folding the change in")),
        DemoScriptStep(1_900, CLEAR_LINE + editStep + claudeStatus("Crunching", 10, "checking the WAIT budget")),
        DemoScriptStep(
            2_100,
            CLEAR_LINE + toolStep("Bash", "make test", "4 parts within budget (24 lines spare)") +
                claudeStatus("Racing the beam", 13, "almost done thinking with high effort"),
        ),
        DemoScriptStep(1_400, CLEAR_LINE + doneLine + "\r\n\r\n" + DEMO_CLAUDE_PROMPT),
    )
}

/** Assembler watch output (no prompt — a foreground watcher). */
private fun watchTranscript(): String = lines(
    "$DEMO_PROMPT${"watchexec -e s,bas -- make"}",
    "${DIM}[watchexec]$R watching: src, tools",
    "vasmm68k_mot -kick1hunks -quiet -Fhunk -o build/main.o src/main.s",
    "vlink -bamigahunk -s -o build/lastlight build/main.o",
    "${DIM}[21:06:02]$R rebuild ok in ${YELLOW}0.6s$R ${DIM}(src/fx/scroller.s changed)$R",
    "${DIM}[21:08:17]$R rebuild ok in ${YELLOW}0.5s$R ${DIM}(tools/sinetable.bas changed)$R",
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
        transcript = trackerTranscript(),
        prompt = DEMO_PROMPT,
        startsAtPrompt = false, // the player owns the tty; Ctrl-C stops it
        respond = ::demoShellRespond,
        liveScript = trackerLiveScript(),
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
    DemoSessionSpec(
        sessionId = "demo-s8",
        transcript = claudeLogoTranscript(),
        prompt = DEMO_CLAUDE_PROMPT,
        startsAtPrompt = true, // idle at the Claude input prompt (finished)
        respond = ::demoShellRespond, // unused: inputScript takes the Enter path
        inputScript = ::claudeLogoInputScript,
    ),
    DemoSessionSpec(
        sessionId = "demo-s9",
        transcript = claudeCopperTranscript(),
        prompt = DEMO_CLAUDE_PROMPT,
        startsAtPrompt = true, // idle at the Claude input prompt (finished)
        respond = ::demoShellRespond, // unused: inputScript takes the Enter path
        inputScript = ::claudeCopperInputScript,
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
