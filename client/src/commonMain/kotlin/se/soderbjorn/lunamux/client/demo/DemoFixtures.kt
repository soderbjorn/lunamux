/**
 * Static workspace fixtures for demo mode: the initial tab/pane layout, the
 * fake project file tree (with rendered file contents), the git change list
 * with pre-computed diffs, the per-session AI states, and the Claude usage
 * snapshot.
 *
 * Everything here is deterministic and authored by hand — there is no
 * runtime randomisation anywhere in the demo. The fictional project is
 * "lastlight", an Amiga 500 demoscene production ("LAST LIGHT" by the
 * equally fictional group PHOSPHOR) at `~/code/lastlight`: 68k assembly
 * effects, a ProTracker module, a C64 BASIC sine-table generator (because
 * tradition), and an ARexx crunch script. The repo sits mid-way through a
 * `feature/sine-scroller` branch so the git pane has something interesting
 * to show.
 *
 * @see DemoServer for the command handling that serves these fixtures
 * @see DemoTranscripts.kt for the terminal scrollback content
 */
package se.soderbjorn.lunamux.client.demo

import se.soderbjorn.lunamux.ClaudeModelUsage
import se.soderbjorn.lunamux.ClaudeUsageData
import se.soderbjorn.lunamux.DiffHunk
import se.soderbjorn.lunamux.DiffLine
import se.soderbjorn.lunamux.DiffLineType
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.FileBrowserEntry
import se.soderbjorn.lunamux.FileContentKind
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.GitDiffMode
import se.soderbjorn.lunamux.GitFileEntry
import se.soderbjorn.lunamux.GitFileStatus
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.SyntaxHighlighter
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TerminalContent
import se.soderbjorn.lunamux.WindowConfig

/**
 * Rendered content for one file in the demo file browser, mirroring what the
 * real server's `FileBrowserCatalog.readFile` would return.
 *
 * @property kind the rendering treatment (markdown vs highlighted text).
 * @property html the pre-rendered HTML the preview pane shows.
 * @property language detected language for text files, or `null`.
 */
internal class DemoFileContent(
    val kind: FileContentKind,
    val html: String,
    val language: String? = null,
)

/**
 * Pre-computed diff for one changed file in the demo git pane, mirroring
 * what the real server's `GitCatalog.diffFile` would return.
 *
 * @property hunks parsed unified-diff hunks.
 * @property language detected language for syntax highlighting.
 * @property oldContent full pre-change file content (for split mode).
 * @property newContent full post-change file content (for split mode).
 */
internal class DemoGitDiff(
    val hunks: List<DiffHunk>,
    val language: String?,
    val oldContent: String?,
    val newContent: String?,
)

/**
 * All static demo data, exposed as one object so [DemoServer] (and the demo
 * transports) have a single place to read from.
 */
internal object DemoFixtures {
    /** The demo user's home directory; used to prettify pane titles. */
    const val HOME = "/Users/demo"

    /** Working directory of every fixture pane. */
    const val CWD = "$HOME/code/lastlight"

    /** Fixed timestamp base for file mtimes: 2026-06-10 around 21:00 CEST. */
    private const val MTIME = 1_781_204_400_000L

    /** One minute in milliseconds, for spacing fixture mtimes. */
    private const val MIN = 60_000L

    /**
     * Replace the demo home directory with `~` the way the real server's
     * title prettifier does.
     *
     * @param path an absolute path or `null`.
     * @return the prettified path, or `null` when [path] is `null`.
     */
    fun prettify(path: String?): String? = path?.replace(HOME, "~")

    /**
     * The workspace every demo client starts with: four tabs showing
     * different layouts and pane types.
     *
     *  - **Compo** (active, hero-left): a large, live "working" Claude Code
     *    session tightening the scroller on the left; on the right a build
     *    shell above a *finished* Claude session (the greetings part) that
     *    resumes working when typed into.
     *  - **Trackmo** (grid): a ProTracker playback pane, a raster-budget
     *    test run, an assembler watcher, and the NFO-writing session.
     *  - **Assets** (hero-left): the file browser opened on the scrolltext,
     *    beside a *finished* Claude session that just converted the logo to
     *    interleaved bitplanes (resumes working when typed into).
     *  - **Delta** (hero-left): the git pane with the side-by-side P4Diff-style
     *    split view, beside a *finished* Claude session that just mirrored the
     *    copper bars (resumes working when typed into).
     *
     * @return the initial [WindowConfig] pushed to every demo client.
     */
    fun initialConfig(): WindowConfig = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "demo-t1",
                title = "Compo",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p1",
                            sessionId = "demo-s1",
                            title = "Claude Code",
                            customName = "Claude Code",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s1"),
                        ),
                        x = 0.0, y = 0.0, width = 0.6, height = 1.0, z = 1,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p2",
                            sessionId = "demo-s2",
                            title = "~/code/lastlight",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s2"),
                        ),
                        x = 0.6, y = 0.0, width = 0.4, height = 0.5, z = 2,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p9",
                            sessionId = "demo-s6",
                            title = "claude: greets",
                            customName = "claude: greets",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s6"),
                        ),
                        x = 0.6, y = 0.5, width = 0.4, height = 0.5, z = 3,
                    ),
                ),
                focusedPaneId = "demo-p1",
                layoutPreset = "hero-left",
            ),
            TabConfig(
                id = "demo-t2",
                title = "Trackmo",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p4",
                            sessionId = "demo-s3",
                            title = "protracker",
                            customName = "protracker",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s3"),
                        ),
                        x = 0.0, y = 0.0, width = 0.5, height = 0.5, z = 1,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p5",
                            sessionId = "demo-s4",
                            title = "claude: plasma",
                            customName = "claude: plasma",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s4"),
                        ),
                        x = 0.5, y = 0.0, width = 0.5, height = 0.5, z = 2,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p6",
                            sessionId = "demo-s5",
                            title = "watch",
                            customName = "watch",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s5"),
                        ),
                        x = 0.0, y = 0.5, width = 0.5, height = 0.5, z = 3,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p7",
                            sessionId = "demo-s7",
                            title = "claude: nfo",
                            customName = "claude: nfo",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s7"),
                        ),
                        x = 0.5, y = 0.5, width = 0.5, height = 0.5, z = 4,
                    ),
                ),
                focusedPaneId = "demo-p4",
                layoutPreset = "grid",
            ),
            TabConfig(
                id = "demo-t3",
                title = "Assets",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p8",
                            sessionId = "",
                            title = "files: lastlight",
                            customName = "files: lastlight",
                            cwd = CWD,
                            content = FileBrowserContent(
                                selectedRelPath = "scroller.txt",
                                expandedDirs = setOf("", "src", "src/fx", "tools"),
                                leftColumnWidthPx = 260,
                            ),
                        ),
                        x = 0.0, y = 0.0, width = 0.6, height = 1.0, z = 1,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p10",
                            sessionId = "demo-s8",
                            title = "claude: logo",
                            customName = "claude: logo",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s8"),
                        ),
                        x = 0.6, y = 0.0, width = 0.4, height = 1.0, z = 2,
                    ),
                ),
                focusedPaneId = "demo-p8",
                layoutPreset = "hero-left",
            ),
            TabConfig(
                id = "demo-t4",
                title = "Delta",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p3",
                            sessionId = "",
                            title = "git: lastlight",
                            customName = "git: lastlight",
                            cwd = CWD,
                            content = GitContent(
                                selectedFilePath = "src/main.s",
                                diffMode = GitDiffMode.Split,
                                graphicalDiff = true,
                            ),
                        ),
                        x = 0.0, y = 0.0, width = 0.6, height = 1.0, z = 1,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p11",
                            sessionId = "demo-s9",
                            title = "claude: copper",
                            customName = "claude: copper",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s9"),
                        ),
                        x = 0.6, y = 0.0, width = 0.4, height = 1.0, z = 2,
                    ),
                ),
                focusedPaneId = "demo-p3",
                layoutPreset = "hero-left",
            ),
        ),
        activeTabId = "demo-t1",
    )

    /**
     * Initial per-session AI-state map, showing every agent state the UI
     * can render: the main Claude session and the NFO session pulse blue
     * ("working" — both also stream a scripted live feed, see
     * `DemoSessionSpec.liveScript`), while the plasma session shows the
     * fading red attention indicator ("waiting" — it sits at a
     * tool-permission prompt). The finished Claude session (`demo-s6`)
     * starts stateless and flips to "working" at runtime when the user
     * types into it, and the plain shells (and the tracker) stay stateless.
     */
    val initialStates: Map<String, String?> = mapOf(
        "demo-s1" to "working",
        "demo-s4" to "waiting",
        "demo-s7" to "working",
    )

    /**
     * The Claude usage snapshot shown in the bottom usage bar. Carries two
     * model-specific rows so the demo exercises the variable-row rendering
     * (plans can surface Fable, Sonnet, or both).
     */
    val claudeUsage: ClaudeUsageData = ClaudeUsageData(
        sessionPercent = 37,
        sessionResetTime = "Jun 11, 1:00 AM",
        weeklyAllPercent = 62,
        weeklyAllResetTime = "Jun 16, 9:00 AM",
        weeklySonnetPercent = 41,
        extraUsageEnabled = false,
        extraUsageInfo = null,
        fetchedAt = "2026-06-10T21:14:07Z",
        modelUsages = listOf(
            ClaudeModelUsage(label = "Fable", percent = 70),
            ClaudeModelUsage(label = "Sonnet", percent = 41),
        ),
    )

    /** Shorthand for a directory entry in [dirListings]. */
    private fun dir(name: String, relPath: String, ageMin: Long): FileBrowserEntry =
        FileBrowserEntry(name = name, relPath = relPath, isDir = true, sizeBytes = 0, mtimeEpochMs = MTIME - ageMin * MIN)

    /** Shorthand for a file entry in [dirListings]. */
    private fun file(name: String, relPath: String, size: Long, ageMin: Long): FileBrowserEntry =
        FileBrowserEntry(name = name, relPath = relPath, isDir = false, sizeBytes = size, mtimeEpochMs = MTIME - ageMin * MIN)

    /**
     * One-level directory listings for the fake project tree, keyed by the
     * directory's relative path (`""` is the project root). Served in reply
     * to `FileBrowserListDir`.
     */
    val dirListings: Map<String, List<FileBrowserEntry>> = mapOf(
        "" to listOf(
            dir("gfx", "gfx", 9_000),
            dir("mods", "mods", 5_100),
            dir("scratch", "scratch", 16),
            dir("src", "src", 23),
            dir("tools", "tools", 47),
            file(".gitignore", ".gitignore", 38, 19_000),
            file("Makefile", "Makefile", 742, 12_000),
            file("README.md", "README.md", 604, 33),
            file("file_id.diz", "file_id.diz", 311, 68),
            file("scroller.txt", "scroller.txt", 693, 21),
        ),
        "gfx" to listOf(
            file("font16.iff", "gfx/font16.iff", 3_968, 9_100),
            file("logo.iff", "gfx/logo.iff", 14_442, 9_000),
        ),
        "mods" to listOf(
            file("lastlight.mod", "mods/lastlight.mod", 118_694, 5_100),
        ),
        "scratch" to listOf(
            file("notes.md", "scratch/notes.md", 292, 16),
        ),
        "src" to listOf(
            dir("fx", "src/fx", 23),
            file("main.s", "src/main.s", 566, 23),
            file("startup.s", "src/startup.s", 918, 2_900),
        ),
        "src/fx" to listOf(
            file("copperbars.s", "src/fx/copperbars.s", 731, 2_900),
            file("greets.s", "src/fx/greets.s", 1_380, 190),
            file("plasma.s", "src/fx/plasma.s", 655, 2_850),
            file("scroller.s", "src/fx/scroller.s", 1_004, 26),
        ),
        "tools" to listOf(
            file("crunch.rexx", "tools/crunch.rexx", 402, 12_000),
            file("sinetable.bas", "tools/sinetable.bas", 297, 47),
        ),
    )

    /**
     * Render a plain-text source preview exactly as the real server does for
     * languages [SyntaxHighlighter] doesn't tokenise (68k assembly, C64
     * BASIC, ARexx, Makefiles): the output is HTML-escaped text with no
     * `<pre><code>` wrapper — the web client's `renderFileBrowserContent`
     * adds that wrapper itself, mirroring the `FileBrowserOpenFile` → `Text`
     * fallback path in `WindowRoutes`.
     *
     * @param src the raw source text to escape.
     * @return escaped inner HTML suitable for the `html` field of a
     *   [DemoFileContent] with [FileContentKind.Text].
     */
    private fun text(src: String): String = SyntaxHighlighter.highlight(src, "text")

    /**
     * Rendered file contents keyed by relative path, served in reply to
     * `FileBrowserOpenFile`. Markdown files are pre-rendered to HTML; source
     * files are escaped text previews. The two binary assets (the IFF logo
     * and the ProTracker module) get hand-authored info previews — an ASCII
     * proof and the module's sample-name graffiti — since the demo can't
     * render real binaries.
     */
    val fileContents: Map<String, DemoFileContent> = mapOf(
        "README.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>LAST LIGHT</h1>
                <p>A four-channel trackmo for the stock Amiga 500 (OCS, 512K chip +
                512K slow) by <strong>PHOSPHOR</strong>. First shown at AFTERGLOW 2026.</p>
                <h2>Build</h2>
                <pre><code>make        # assemble (vasm) and link (vlink)
                make adf    # bootable disk image (xdftool)
                make run    # boot the image in FS-UAE</code></pre>
                <h2>Parts</h2>
                <ul>
                <li>copper bars</li>
                <li>plasma</li>
                <li>one-pixel sine scroller</li>
                <li>greetings over a starfield</li>
                </ul>
                <h2>Raster budget</h2>
                <p>Every part must fit inside one PAL frame (312 rasterlines).
                <code>make test</code> measures each part and fails when the frame is blown.</p>
            """.trimIndent(),
        ),
        "scroller.txt" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(SCROLLER_TXT),
            language = "text",
        ),
        "file_id.diz" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(FILE_ID_DIZ),
            language = "text",
        ),
        "Makefile" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(MAKEFILE),
            language = "text",
        ),
        ".gitignore" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text("build/\n*.adf\n*.shr\nfs-uae.log\n"),
            language = "text",
        ),
        "scratch/notes.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>compo notes</h1>
                <ul>
                <li>entries close saturday 22:00 — lock the party version friday night</li>
                <li>sine table regenerated at 1024 entries, amplitude 40</li>
                <li>TODO: NTSC check — the plasma blows the 262-line frame at 60Hz</li>
                <li>TODO: greets order — alphabetical, or biggest crew last?</li>
                </ul>
            """.trimIndent(),
        ),
        "src/main.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(MAIN_S_NEW),
            language = "text",
        ),
        "src/startup.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(STARTUP_S),
            language = "text",
        ),
        "src/fx/copperbars.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(COPPERBARS_S),
            language = "text",
        ),
        "src/fx/plasma.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(PLASMA_S),
            language = "text",
        ),
        "src/fx/scroller.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(SCROLLER_S),
            language = "text",
        ),
        "src/fx/greets.s" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(GREETS_S),
            language = "text",
        ),
        "tools/sinetable.bas" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(SINETABLE_BAS_NEW),
            language = "text",
        ),
        "tools/crunch.rexx" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(CRUNCH_REXX),
            language = "text",
        ),
        "gfx/logo.iff" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(LOGO_IFF_PREVIEW),
            language = "text",
        ),
        "gfx/font16.iff" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(
                "IFF ILBM — 256 × 128, 1 bitplane\n\n" +
                    "16×16 font: A–Z 0–9 .!?()- and the big star.\n" +
                    "Stored pre-interleaved so the blitter can fetch glyph\n" +
                    "columns without a conversion pass (see src/fx/scroller.s).\n",
            ),
            language = "text",
        ),
        "mods/lastlight.mod" to DemoFileContent(
            kind = FileContentKind.Text,
            html = text(MOD_PREVIEW),
            language = "text",
        ),
    )

    /**
     * The uncommitted changes shown by the git pane, consistent with the
     * `git status` canned command and [gitDiffs].
     */
    val gitEntries: List<GitFileEntry> = listOf(
        GitFileEntry(filePath = "README.md", status = GitFileStatus.Modified, directory = ""),
        GitFileEntry(filePath = "scratch/notes.md", status = GitFileStatus.Untracked, directory = "scratch"),
        GitFileEntry(filePath = "src/fx/scroller.s", status = GitFileStatus.Added, directory = "src/fx"),
        GitFileEntry(filePath = "src/main.s", status = GitFileStatus.Modified, directory = "src"),
        GitFileEntry(filePath = "tools/sinetable.bas", status = GitFileStatus.Modified, directory = "tools"),
    )

    /** Shorthand context [DiffLine]. */
    private fun ctx(old: Int, new: Int, s: String) =
        DiffLine(type = DiffLineType.Context, oldLineNo = old, newLineNo = new, content = s)

    /** Shorthand addition [DiffLine]. */
    private fun add(new: Int, s: String) =
        DiffLine(type = DiffLineType.Addition, oldLineNo = null, newLineNo = new, content = s)

    /** Shorthand deletion [DiffLine]. */
    private fun del(old: Int, s: String) =
        DiffLine(type = DiffLineType.Deletion, oldLineNo = old, newLineNo = null, content = s)

    /**
     * Build an all-additions diff for a brand-new file (Added/Untracked).
     *
     * @param content the new file's full content.
     * @param language detected language for highlighting.
     * @return a [DemoGitDiff] with one hunk covering the whole file.
     */
    private fun newFileDiff(content: String, language: String?): DemoGitDiff {
        val ls = content.trimEnd('\n').split("\n")
        return DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 0, oldCount = 0, newStart = 1, newCount = ls.size,
                    lines = ls.mapIndexed { i, s -> add(i + 1, s) },
                ),
            ),
            language = language,
            oldContent = "",
            newContent = content,
        )
    }

    /**
     * Pre-computed diffs keyed by file path, served in reply to `GitDiff`.
     * Hand-authored to stay consistent with [fileContents] and the canned
     * `git status` / `git diff` shell output. The assembly and BASIC diffs
     * carry a `null` language — [SyntaxHighlighter] doesn't tokenise them,
     * matching the real server's detector for those extensions.
     */
    val gitDiffs: Map<String, DemoGitDiff> = mapOf(
        "src/main.s" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 4, oldCount = 8, newStart = 4, newCount = 10,
                    lines = listOf(
                        ctx(4, 4, "        include \"startup.s\""),
                        ctx(5, 5, "        include \"fx/copperbars.s\""),
                        ctx(6, 6, "        include \"fx/plasma.s\""),
                        add(7, "        include \"fx/scroller.s\""),
                        ctx(7, 8, ""),
                        ctx(8, 9, "main:   bsr     takeover            ; startup.s: quiet the OS, own the hardware"),
                        ctx(9, 10, "        bsr     copperbars_init"),
                        ctx(10, 11, "        bsr     plasma_init"),
                        add(12, "        bsr     scroller_init"),
                        ctx(11, 13, ""),
                    ),
                ),
                DiffHunk(
                    oldStart = 12, oldCount = 5, newStart = 14, newCount = 6,
                    lines = listOf(
                        ctx(12, 14, ".frame: bsr     waitvbl"),
                        ctx(13, 15, "        bsr     copperbars_frame"),
                        ctx(14, 16, "        bsr     plasma_frame"),
                        add(17, "        bsr     scroller_frame"),
                        ctx(15, 18, "        btst    #6,${D}bfe001          ; left mouse button exits"),
                        ctx(16, 19, "        bne.s   .frame"),
                    ),
                ),
            ),
            language = null,
            oldContent = MAIN_S_OLD,
            newContent = MAIN_S_NEW,
        ),
        "tools/sinetable.bas" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 2, oldCount = 3, newStart = 2, newCount = 4,
                    lines = listOf(
                        ctx(2, 2, "20 REM PHOSPHOR -- RUN ON THE 64, SAVE TO DISK 8"),
                        del(3, "30 N=256:A=24"),
                        add(3, "30 N=1024:A=40"),
                        add(4, "35 REM 1024 ENTRIES SO THE ONE-PIXEL SCROLL STOPS SHIMMERING"),
                        ctx(4, 5, "40 OPEN 2,8,2,\"@0:SINETAB,S,W\""),
                    ),
                ),
            ),
            language = null,
            oldContent = SINETABLE_BAS_OLD,
            newContent = SINETABLE_BAS_NEW,
        ),
        "README.md" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 14, oldCount = 3, newStart = 14, newCount = 9,
                    lines = listOf(
                        ctx(14, 14, "- copper bars"),
                        ctx(15, 15, "- plasma"),
                        add(16, "- one-pixel sine scroller"),
                        ctx(16, 17, "- greetings over a starfield"),
                        add(18, ""),
                        add(19, "## Raster budget"),
                        add(20, ""),
                        add(21, "Every part must fit inside one PAL frame (312 rasterlines)."),
                        add(22, "`make test` measures each part and fails when the frame is blown."),
                    ),
                ),
            ),
            language = "markdown",
            oldContent = README_OLD,
            newContent = README_NEW,
        ),
        "src/fx/scroller.s" to newFileDiff(SCROLLER_S, null),
        "scratch/notes.md" to newFileDiff(NOTES_MD, "markdown"),
    )

    /** Default reply for `GetWorktreeDefaults` in the demo. */
    const val WORKTREE_REPO_NAME = "lastlight"

    /** Sibling-style default worktree path. */
    const val WORKTREE_SIBLING = "$HOME/code/lastlight-branch"

    /** `.worktrees/`-style default worktree path. */
    const val WORKTREE_DOTDIR = "$CWD/.worktrees/branch"
}

// ---------------------------------------------------------------------------
// Full file contents referenced by both the file browser and the git diffs.
// The sources are 68k assembly (vasm motorola syntax), C64 BASIC, and ARexx —
// `$` is Kotlin's template introducer, so hex literals use the [D] constant.
// ---------------------------------------------------------------------------

/** A literal dollar sign, for `$dff000`-style hex literals in raw strings. */
private const val D = "$"

/** Post-change `src/main.s` (matches the Modified diff). */
private val MAIN_S_NEW = """
; main.s — frame loop and effect chain for LAST LIGHT
; PHOSPHOR 2026. Assembles with vasm (mot syntax), links with vlink.

        include "startup.s"
        include "fx/copperbars.s"
        include "fx/plasma.s"
        include "fx/scroller.s"

main:   bsr     takeover            ; startup.s: quiet the OS, own the hardware
        bsr     copperbars_init
        bsr     plasma_init
        bsr     scroller_init

.frame: bsr     waitvbl
        bsr     copperbars_frame
        bsr     plasma_frame
        bsr     scroller_frame
        btst    #6,${D}bfe001          ; left mouse button exits
        bne.s   .frame

        bsr     handback            ; startup.s: restore the OS
        moveq   #0,d0
        rts
""".trimStart()

/** Pre-change `src/main.s`. */
private val MAIN_S_OLD = """
; main.s — frame loop and effect chain for LAST LIGHT
; PHOSPHOR 2026. Assembles with vasm (mot syntax), links with vlink.

        include "startup.s"
        include "fx/copperbars.s"
        include "fx/plasma.s"

main:   bsr     takeover            ; startup.s: quiet the OS, own the hardware
        bsr     copperbars_init
        bsr     plasma_init

.frame: bsr     waitvbl
        bsr     copperbars_frame
        bsr     plasma_frame
        btst    #6,${D}bfe001          ; left mouse button exits
        bne.s   .frame

        bsr     handback            ; startup.s: restore the OS
        moveq   #0,d0
        rts
""".trimStart()

/** The brand-new `src/fx/scroller.s` (Added). */
private val SCROLLER_S = """
; fx/scroller.s — one-pixel sine scroller for LAST LIGHT
; sine table generated by tools/sinetable.bas (1024 entries, amplitude 40)

SCROLLY equ     200                 ; top rasterline of the scroll area
SPEED   equ     2                   ; pixels per frame

scroller_init:
        lea     scrolltext,a0
        move.l  a0,textptr
        clr.w   sinepos
        rts

scroller_frame:
        ; move the glyphs while the beam is still in the border — the
        ; blitter is ours until the copper wakes up at line SCROLLY
        lea     ${D}dff000,a5
        move.w  sinepos,d0
        addq.w  #SPEED,d0
        and.w   #1023,d0
        move.w  d0,sinepos
        lea     sinetab,a0
        move.b  (a0,d0.w),d1        ; this column's wobble
        ; ... one 16px glyph column blitted per iteration (elided)
        bsr     nextglyph
        rts

nextglyph:
        move.l  textptr,a0
        tst.b   (a0)+
        bne.s   .ok
        lea     scrolltext,a0       ; zero byte: wrap, the text loops forever
.ok:    move.l  a0,textptr
        rts

sinepos:    dc.w    0
textptr:    dc.l    0
sinetab:    incbin  "build/sinetab"
scrolltext: incbin  "scroller.txt"
            dc.b    0
""".trimStart()

/** `src/startup.s` (unchanged file, browsable). */
private val STARTUP_S = """
; startup.s — take the machine over politely, hand it back on exit
; saves the DMA and interrupt state so Workbench survives the demo

DMACON  equ     ${D}96
INTENA  equ     ${D}9a

takeover:
        move.l  4.w,a6              ; ExecBase
        jsr     -132(a6)            ; Forbid()
        lea     ${D}dff000,a5
        move.w  ${D}dff002,olddma      ; DMACONR
        move.w  ${D}dff01c,oldint      ; INTENAR
        move.w  #${D}7fff,DMACON(a5)   ; everything off
        move.w  #${D}7fff,INTENA(a5)
        move.w  #${D}87e0,DMACON(a5)   ; copper, blitter, bitplanes back on
        rts

handback:
        lea     ${D}dff000,a5
        move.w  #${D}7fff,DMACON(a5)
        move.w  olddma,d0
        or.w    #${D}8000,d0
        move.w  d0,DMACON(a5)
        move.w  oldint,d0
        or.w    #${D}8000,d0
        move.w  d0,INTENA(a5)
        move.l  4.w,a6
        jsr     -138(a6)            ; Permit()
        rts

waitvbl:
        move.l  ${D}dff004,d0          ; VPOSR
        and.l   #${D}1ff00,d0
        cmp.l   #303<<8,d0
        bne.s   waitvbl
        rts

olddma: dc.w    0
oldint: dc.w    0
""".trimStart()

/** `src/fx/copperbars.s` (unchanged file, browsable). */
private val COPPERBARS_S = """
; fx/copperbars.s — the classic: mirrored gradient bars, zero CPU per
; frame once the list is built. the copper does all the work.

BARS    equ     4

copperbars_init:
        lea     coplist,a0
        ; build BARS gradients, each mirrored around its centre line,
        ; one WAIT + COLOR00 pair per rasterline (elided)
        move.l  #coplist,${D}dff080    ; COP1LC
        rts

copperbars_frame:
        ; bounce each bar on its own offset into the sine table
        ; (same table the scroller rides — tools/sinetable.bas)
        rts

coplist:
        dc.w    ${D}2c01,${D}fffe        ; WAIT line ${D}2c
        dc.w    ${D}0180,${D}0000        ; COLOR00 := black
        ; ... generated at init (elided)
        dc.l    ${D}fffffffe           ; end of list
""".trimStart()

/** `src/fx/plasma.s` (unchanged file, browsable). */
private val PLASMA_S = """
; fx/plasma.s — 16-colour plasma, halfbrite trick for the second gradient

plasma_init:
        lea     plasmatab,a0
        ; sum two sine sweeps into the per-scanline offset table (elided)
        rts

plasma_frame:
        ; the byte-flip mirror walks half the table and flips for the
        ; bottom — halves the work, which NTSC will thank us for
        ; (see scratch/notes.md: the 262-line frame is still too tight)
        rts

plasmatab:
        ds.b    512
""".trimStart()

/** `src/fx/greets.s` (unchanged file, browsable — the finished session's work). */
private val GREETS_S = """
; fx/greets.s — greetings part: 96-star starfield, one greet per beat
; the fade uses the same copper gradient trick as the bars

STARS   equ     96

greets_init:
        lea     starfield,a0
        ; seed STARS x/y/speed triplets from the sine table (elided)
        rts

greets_frame:
        ; move the stars, then fade the current greet in over 16 frames,
        ; advancing on the kick drum (ptplayer row callback)
        rts

greetstext:
        dc.b    "VECTORIDE",0
        dc.b    "RASTER ROMANTICS",0
        dc.b    "NEON HARBOR",0
        dc.b    "THE BYTE FOUNDRY",0
        dc.b    "SINUS CLUB",0
        dc.b    "DATAFROST",0
        dc.b    0                   ; end of greets

starfield:
        ds.b    STARS*6
""".trimStart()

/** Post-change `tools/sinetable.bas` (matches the Modified diff). */
private val SINETABLE_BAS_NEW = """
10 REM SINE TABLE FOR THE SCROLLER
20 REM PHOSPHOR -- RUN ON THE 64, SAVE TO DISK 8
30 N=1024:A=40
35 REM 1024 ENTRIES SO THE ONE-PIXEL SCROLL STOPS SHIMMERING
40 OPEN 2,8,2,"@0:SINETAB,S,W"
50 FOR I=0 TO N-1
60 V=INT(A*SIN(I*6.28319/N)+A+0.5)
70 PRINT#2,CHR$(V);
80 NEXT I
90 CLOSE 2
100 PRINT "DONE.";N;"BYTES."
""".trimStart()

/** Pre-change `tools/sinetable.bas`. */
private val SINETABLE_BAS_OLD = """
10 REM SINE TABLE FOR THE SCROLLER
20 REM PHOSPHOR -- RUN ON THE 64, SAVE TO DISK 8
30 N=256:A=24
40 OPEN 2,8,2,"@0:SINETAB,S,W"
50 FOR I=0 TO N-1
60 V=INT(A*SIN(I*6.28319/N)+A+0.5)
70 PRINT#2,CHR$(V);
80 NEXT I
90 CLOSE 2
100 PRINT "DONE.";N;"BYTES."
""".trimStart()

/** `tools/crunch.rexx` (unchanged file, browsable). */
private val CRUNCH_REXX = """
/* crunch.rexx — pack the party version and nag about the file_id   */
/* usage: rx crunch.rexx [exe]                                      */
parse arg exe
if exe = '' then exe = 'build/lastlight'
address command
'shrinkler -9 -p' exe exe'.shr'
if rc ~= 0 then do
    say 'shrinkler fell over, rc =' rc
    exit rc
end
say 'crunched. remember the file_id.diz before you lha it.'
""".trimStart()

/** `Makefile` (unchanged file, browsable). */
private val MAKEFILE = """
# LAST LIGHT — PHOSPHOR
# cross-build: vasm + vlink, disk image via xdftool, runs in FS-UAE

VASM    = vasmm68k_mot
VFLAGS  = -kick1hunks -quiet -Fhunk

all: build/lastlight

build/main.o: src/main.s src/startup.s src/fx/*.s build/sinetab
	$(VASM) $(VFLAGS) -o $@ src/main.s

build/lastlight: build/main.o
	vlink -bamigahunk -s -o $@ $<
	shrinkler -9 $@ $@.shr

# tradition: the sine table comes off a real C64 running
# tools/sinetable.bas. x64sc does it too when the 64 is in the shop.
build/sinetab: tools/sinetable.bas
	x64sc -silent -autostart $< -export $@

adf: build/lastlight
	xdftool build/lastlight.adf format "LAST LIGHT" + boot install + write build/lastlight.shr

run: adf
	fs-uae --amiga-model=A500 --floppy-drive-0=build/lastlight.adf

test: build/lastlight
	tools/rasterbudget build/lastlight   # fails the build past 312 lines
""".trimStart()

/** `scroller.txt` — the scrolltext, browsable and selected by default. */
private val SCROLLER_TXT = """
YOU ARE NOW ROCKING WITH  * P H O S P H O R *  AND THIS IS
-- L A S T   L I G H T --  OUR FIRST TRACKMO FOR THE MIGHTY AMIGA 500 ...

CODE BY DELTRON ... PIXELS BY MIRAGE ... MUSIC BY QWAVE ...

BIG GREETINGS FLY OUT TO   VECTORIDE - RASTER ROMANTICS - NEON HARBOR -
THE BYTE FOUNDRY - SINUS CLUB - DATAFROST   ... YOU KEEP THE SCENE WARM ...

THE SINE YOU ARE RIDING WAS COMPUTED ON A REAL C64, BECAUSE TRADITION ...

IF YOU CAN READ THIS YOU ARE STANDING TOO CLOSE TO THE BEAM ...
SEE YOU AT AFTERGLOW ...   WRAP!
""".trimStart()

/** `file_id.diz` — BBS-style release blurb, browsable. */
private val FILE_ID_DIZ = """
 .-------------------------------.
 |  L A S T   L I G H T          |
 |  a trackmo for the stock A500 |
 |       by  P H O S P H O R     |
 |                               |
 |  code deltron . gfx mirage    |
 |  music qwave                  |
 |  released at AFTERGLOW 2026   |
 `-------------------------------'
""".trimStart()

/** Info preview for the binary `gfx/logo.iff`. */
private val LOGO_IFF_PREVIEW = """
IFF ILBM — 320 × 112, 5 bitplanes (32 colours), ByteRun1 compressed

ascii proof:

  ▄▄▄▄ ▄  ▄ ▄▄▄▄ ▄▄▄▄ ▄▄▄▄ ▄  ▄ ▄▄▄▄ ▄▄▄▄
  █  █ █  █ █  █ █    █  █ █  █ █  █ █  █
  █▀▀▀ █▀▀█ █  █ ▀▀▀█ █▀▀▀ █▀▀█ █  █ █▀▀▄
  ▀    ▀  ▀ ▀▀▀▀ ▀▀▀▀ ▀    ▀  ▀ ▀▀▀▀ ▀  ▀

painted by mirage in Deluxe Paint III, one coffee per bitplane.
""".trimStart()

/** Info preview for the binary `mods/lastlight.mod` — sample-name graffiti included. */
private val MOD_PREVIEW = """
ProTracker module (M.K.) — 4 channels, 31 samples, 24 patterns, 42 positions

title: last light

samples:
  01 lead-square        09 arp-minor
  02 chipbass           10 strings-st01
  03 kick               11 ....................
  04 snare              12 .. LAST LIGHT ......
  05 hat-closed         13 .. music by qwave ..
  06 hat-open           14 .. of PHOSPHOR .....
  07 tom-lo             15 .. greets to all ...
  08 tom-hi             16 .. northern crews ..
""".trimStart()

/** Post-change `README.md` source. */
private val README_NEW = """
# LAST LIGHT

A four-channel trackmo for the stock Amiga 500 (OCS, 512K chip +
512K slow) by PHOSPHOR. First shown at AFTERGLOW 2026.

## Build

    make        # assemble (vasm) and link (vlink)
    make adf    # bootable disk image (xdftool)
    make run    # boot the image in FS-UAE

## Parts

- copper bars
- plasma
- one-pixel sine scroller
- greetings over a starfield

## Raster budget

Every part must fit inside one PAL frame (312 rasterlines).
`make test` measures each part and fails when the frame is blown.
""".trimStart()

/** Pre-change `README.md` source. */
private val README_OLD = """
# LAST LIGHT

A four-channel trackmo for the stock Amiga 500 (OCS, 512K chip +
512K slow) by PHOSPHOR. First shown at AFTERGLOW 2026.

## Build

    make        # assemble (vasm) and link (vlink)
    make adf    # bootable disk image (xdftool)
    make run    # boot the image in FS-UAE

## Parts

- copper bars
- plasma
- greetings over a starfield
""".trimStart()

/** The untracked `scratch/notes.md`. */
private val NOTES_MD = """
# compo notes

- entries close saturday 22:00 — lock the party version friday night
- sine table regenerated at 1024 entries, amplitude 40
- TODO: NTSC check — the plasma blows the 262-line frame at 60Hz
- TODO: greets order — alphabetical, or biggest crew last?
""".trimStart()
