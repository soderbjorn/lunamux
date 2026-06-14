/**
 * Static workspace fixtures for demo mode: the initial tab/pane layout, the
 * fake project file tree (with rendered file contents), the git change list
 * with pre-computed diffs, the per-session AI states, and the Claude usage
 * snapshot.
 *
 * Everything here is deterministic and authored by hand — there is no
 * runtime randomisation anywhere in the demo. The fictional project is
 * "orbit", a small TypeScript session service at `~/code/orbit`, mid-way
 * through a `feature/rate-limit` branch so the git pane has something
 * interesting to show.
 *
 * @see DemoServer for the command handling that serves these fixtures
 * @see DemoTranscripts.kt for the terminal scrollback content
 */
package se.soderbjorn.termtastic.client.demo

import se.soderbjorn.termtastic.ClaudeUsageData
import se.soderbjorn.termtastic.DiffHunk
import se.soderbjorn.termtastic.DiffLine
import se.soderbjorn.termtastic.DiffLineType
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.FileBrowserEntry
import se.soderbjorn.termtastic.FileContentKind
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.GitDiffMode
import se.soderbjorn.termtastic.GitFileEntry
import se.soderbjorn.termtastic.GitFileStatus
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.Pane
import se.soderbjorn.termtastic.SyntaxHighlighter
import se.soderbjorn.termtastic.TabConfig
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowConfig

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
    const val CWD = "$HOME/code/orbit"

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
     *  - **Orbit** (active, hero-left): a large Claude Code session on the
     *    left and a build shell on the right.
     *  - **Services** (grid): dev-server logs, a test run, a build watcher,
     *    and a plain shell.
     *  - **Files** (single): a full-screen file browser.
     *  - **Changes** (single): a full-screen git pane with the side-by-side
     *    P4Diff-style split view enabled by default.
     *
     * @return the initial [WindowConfig] pushed to every demo client.
     */
    fun initialConfig(): WindowConfig = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "demo-t1",
                title = "Orbit",
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
                            title = "~/code/orbit",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s2"),
                        ),
                        x = 0.6, y = 0.0, width = 0.4, height = 1.0, z = 2,
                    ),
                ),
                focusedPaneId = "demo-p1",
                layoutPreset = "hero-left",
            ),
            TabConfig(
                id = "demo-t2",
                title = "Services",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p4",
                            sessionId = "demo-s3",
                            title = "dev server",
                            customName = "dev server",
                            cwd = CWD,
                            content = TerminalContent(sessionId = "demo-s3"),
                        ),
                        x = 0.0, y = 0.0, width = 0.5, height = 0.5, z = 1,
                    ),
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p5",
                            sessionId = "demo-s4",
                            title = "claude: tests",
                            customName = "claude: tests",
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
                            title = "claude: docs",
                            customName = "claude: docs",
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
                title = "Files",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p8",
                            sessionId = "",
                            title = "files: orbit",
                            customName = "files: orbit",
                            cwd = CWD,
                            content = FileBrowserContent(
                                selectedRelPath = "README.md",
                                expandedDirs = setOf("", "src", "src/api", "docs"),
                                leftColumnWidthPx = 260,
                            ),
                        ),
                        x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1,
                    ),
                ),
                focusedPaneId = "demo-p8",
                layoutPreset = "auto",
            ),
            TabConfig(
                id = "demo-t4",
                title = "Changes",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(
                            id = "demo-p3",
                            sessionId = "",
                            title = "git: orbit",
                            customName = "git: orbit",
                            cwd = CWD,
                            content = GitContent(
                                selectedFilePath = "src/api/sessions.ts",
                                diffMode = GitDiffMode.Split,
                                graphicalDiff = true,
                            ),
                        ),
                        x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1,
                    ),
                ),
                focusedPaneId = "demo-p3",
                layoutPreset = "auto",
            ),
        ),
        activeTabId = "demo-t1",
    )

    /**
     * Initial per-session AI-state map, showing every agent state the UI
     * can render: the main Claude session and the docs session pulse blue
     * ("working"), while the tests session shows the fading red attention
     * indicator ("waiting" — it sits at a tool-permission prompt). The
     * plain shells stay stateless.
     */
    val initialStates: Map<String, String?> = mapOf(
        "demo-s1" to "working",
        "demo-s4" to "waiting",
        "demo-s7" to "working",
    )

    /** The Claude usage snapshot shown in the bottom usage bar. */
    val claudeUsage: ClaudeUsageData = ClaudeUsageData(
        sessionPercent = 37,
        sessionResetTime = "Jun 11, 1:00 AM",
        weeklyAllPercent = 62,
        weeklyAllResetTime = "Jun 16, 9:00 AM",
        weeklySonnetPercent = 41,
        extraUsageEnabled = false,
        extraUsageInfo = null,
        fetchedAt = "2026-06-10T21:14:07Z",
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
            dir("docs", "docs", 9_000),
            dir("scratch", "scratch", 16),
            dir("src", "src", 23),
            file(".gitignore", ".gitignore", 41, 19_000),
            file("README.md", "README.md", 611, 33),
            file("package.json", "package.json", 402, 12_000),
            file("tsconfig.json", "tsconfig.json", 289, 19_000),
        ),
        "docs" to listOf(
            file("architecture.md", "docs/architecture.md", 1_204, 9_000),
            file("deploy.md", "docs/deploy.md", 845, 9_100),
        ),
        "scratch" to listOf(
            file("notes.md", "scratch/notes.md", 312, 16),
        ),
        "src" to listOf(
            dir("api", "src/api", 23),
            dir("lib", "src/lib", 2_900),
            file("server.ts", "src/server.ts", 644, 47),
        ),
        "src/api" to listOf(
            file("ratelimit.ts", "src/api/ratelimit.ts", 1_388, 26),
            file("sessions.ts", "src/api/sessions.ts", 1_052, 23),
            file("users.ts", "src/api/users.ts", 980, 2_900),
        ),
        "src/lib" to listOf(
            file("config.ts", "src/lib/config.ts", 512, 2_900),
            file("log.ts", "src/lib/log.ts", 433, 2_900),
            file("router.ts", "src/lib/router.ts", 1_870, 2_900),
        ),
    )

    /**
     * Syntax-highlight a source preview exactly as the real server does.
     *
     * Mirrors the `FileBrowserOpenFile` → `Text` path in `WindowRoutes`: the
     * returned HTML is the highlighted *inner* content only (`<span
     * class="hl-…">` tokens over escaped text), with no `<pre><code>` wrapper —
     * the web client's `renderFileBrowserContent` adds that wrapper itself. For
     * languages [SyntaxHighlighter] doesn't tokenise (e.g. `json`, `text`) the
     * output is plain HTML-escaped text, identical to the server's fallback.
     *
     * @param language the highlighter language id (as in [SyntaxHighlighter]).
     * @param src the raw source code to highlight.
     * @return highlighted inner HTML suitable for the `html` field of a
     *   [DemoFileContent] with [FileContentKind.Text].
     */
    private fun code(language: String, src: String): String =
        SyntaxHighlighter.highlight(src, language)

    /**
     * Rendered file contents keyed by relative path, served in reply to
     * `FileBrowserOpenFile`. Markdown files are pre-rendered to HTML; source
     * files are escaped text previews.
     */
    val fileContents: Map<String, DemoFileContent> = mapOf(
        "README.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>Orbit</h1>
                <p>A tiny session service used as the termtastic demo workspace.</p>
                <h2>Quick start</h2>
                <pre><code>npm install
                npm run dev</code></pre>
                <p>The API listens on <code>http://localhost:8787</code>. See
                <code>docs/architecture.md</code> for the request flow and
                <code>docs/deploy.md</code> for shipping it.</p>
            """.trimIndent(),
        ),
        "docs/architecture.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>Architecture</h1>
                <p>Orbit is a single Node process. Requests flow through a tiny
                router (<code>src/lib/router.ts</code>) into per-resource modules
                under <code>src/api/</code>.</p>
                <ul>
                <li><strong>sessions</strong> — create/fetch/delete ephemeral sessions</li>
                <li><strong>users</strong> — read-only user lookups</li>
                <li><strong>ratelimit</strong> — token-bucket middleware (new!)</li>
                </ul>
                <p>State lives in memory; persistence is out of scope for the demo.</p>
            """.trimIndent(),
        ),
        "docs/deploy.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>Deploying</h1>
                <ol>
                <li><code>npm run build</code> bundles to <code>dist/server.js</code></li>
                <li>Ship the bundle to the host</li>
                <li><code>node dist/server.js</code> behind your reverse proxy</li>
                </ol>
                <p>Health checks: <code>GET /api/health</code> returns 200.</p>
            """.trimIndent(),
        ),
        "scratch/notes.md" to DemoFileContent(
            kind = FileContentKind.Markdown,
            html = """
                <h1>notes</h1>
                <ul>
                <li>limiter: capacity 20, refill 5/s — tune after load test</li>
                <li>TODO: integration test for the 429 path</li>
                <li>TODO: README section on rate limits</li>
                </ul>
            """.trimIndent(),
        ),
        ".gitignore" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("text", "node_modules/\ndist/\n.env\n"),
            language = "text",
        ),
        "package.json" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code(
                "json",
                """
                {
                  "name": "orbit",
                  "version": "0.4.2",
                  "private": true,
                  "scripts": {
                    "dev": "tsx watch src/server.ts",
                    "build": "esbuild src/server.ts --bundle --platform=node --outfile=dist/server.js",
                    "watch": "npm run build -- --watch",
                    "test": "vitest run"
                  }
                }
                """.trimIndent(),
            ),
            language = "json",
        ),
        "tsconfig.json" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code(
                "json",
                """
                {
                  "compilerOptions": {
                    "target": "ES2022",
                    "module": "NodeNext",
                    "strict": true,
                    "outDir": "dist"
                  },
                  "include": ["src"]
                }
                """.trimIndent(),
            ),
            language = "json",
        ),
        "src/server.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", SERVER_TS_NEW),
            language = "typescript",
        ),
        "src/api/sessions.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", SESSIONS_TS_NEW),
            language = "typescript",
        ),
        "src/api/ratelimit.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", RATELIMIT_TS),
            language = "typescript",
        ),
        "src/api/users.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", USERS_TS),
            language = "typescript",
        ),
        "src/lib/config.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", CONFIG_TS),
            language = "typescript",
        ),
        "src/lib/log.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", LOG_TS),
            language = "typescript",
        ),
        "src/lib/router.ts" to DemoFileContent(
            kind = FileContentKind.Text,
            html = code("typescript", ROUTER_TS),
            language = "typescript",
        ),
    )

    /**
     * The uncommitted changes shown by the git pane, consistent with the
     * `git status` canned command and [gitDiffs].
     */
    val gitEntries: List<GitFileEntry> = listOf(
        GitFileEntry(filePath = "README.md", status = GitFileStatus.Modified, directory = ""),
        GitFileEntry(filePath = "scratch/notes.md", status = GitFileStatus.Untracked, directory = "scratch"),
        GitFileEntry(filePath = "src/api/ratelimit.ts", status = GitFileStatus.Added, directory = "src/api"),
        GitFileEntry(filePath = "src/api/sessions.ts", status = GitFileStatus.Modified, directory = "src/api"),
        GitFileEntry(filePath = "src/server.ts", status = GitFileStatus.Modified, directory = "src"),
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
     * `git status` / `git diff` shell output.
     */
    val gitDiffs: Map<String, DemoGitDiff> = mapOf(
        "src/api/sessions.ts" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 1, oldCount = 7, newStart = 1, newCount = 10,
                    lines = listOf(
                        ctx(1, 1, "import { Router } from \"../lib/router\";"),
                        ctx(2, 2, "import { log } from \"../lib/log\";"),
                        ctx(3, 3, "import { createSession, getSession, deleteSession } from \"../lib/store\";"),
                        add(4, "import { rateLimit } from \"./ratelimit\";"),
                        ctx(4, 5, ""),
                        add(6, "const limiter = rateLimit({ capacity: 20, refillPerSecond: 5 });"),
                        add(7, ""),
                        ctx(5, 8, "export const sessions = new Router();"),
                        ctx(6, 9, ""),
                        del(7, "sessions.post(\"/api/sessions\", async (req, res) => {"),
                        add(10, "sessions.post(\"/api/sessions\", limiter, async (req, res) => {"),
                    ),
                ),
                DiffHunk(
                    oldStart = 17, oldCount = 6, newStart = 20, newCount = 6,
                    lines = listOf(
                        ctx(17, 20, "});"),
                        ctx(18, 21, ""),
                        del(19, "sessions.delete(\"/api/sessions/:id\", async (req, res) => {"),
                        add(22, "sessions.delete(\"/api/sessions/:id\", limiter, async (req, res) => {"),
                        ctx(20, 23, "  await deleteSession(req.params.id);"),
                        ctx(21, 24, "  res.status(204).end();"),
                        ctx(22, 25, "});"),
                    ),
                ),
            ),
            language = "typescript",
            oldContent = SESSIONS_TS_OLD,
            newContent = SESSIONS_TS_NEW,
        ),
        "src/server.ts" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 1, oldCount = 8, newStart = 1, newCount = 9,
                    lines = listOf(
                        ctx(1, 1, "import { createServer } from \"node:http\";"),
                        ctx(2, 2, "import { log } from \"./lib/log\";"),
                        ctx(3, 3, "import { sessions } from \"./api/sessions\";"),
                        add(4, "import { users } from \"./api/users\";"),
                        ctx(4, 5, ""),
                        ctx(5, 6, "const server = createServer((req, res) => {"),
                        del(6, "  sessions.dispatch(req, res);"),
                        add(7, "  sessions.dispatch(req, res) || users.dispatch(req, res);"),
                        ctx(7, 8, "});"),
                        ctx(8, 9, ""),
                    ),
                ),
            ),
            language = "typescript",
            oldContent = SERVER_TS_OLD,
            newContent = SERVER_TS_NEW,
        ),
        "README.md" to DemoGitDiff(
            hunks = listOf(
                DiffHunk(
                    oldStart = 10, oldCount = 2, newStart = 10, newCount = 7,
                    lines = listOf(
                        ctx(10, 10, "The API listens on http://localhost:8787. See docs/architecture.md"),
                        ctx(11, 11, "for the request flow and docs/deploy.md for shipping it."),
                        add(12, ""),
                        add(13, "## Rate limits"),
                        add(14, ""),
                        add(15, "Mutating session routes are limited to a burst of 20 requests"),
                        add(16, "with a refill of 5/s per client (HTTP 429 when exceeded)."),
                    ),
                ),
            ),
            language = "markdown",
            oldContent = README_OLD,
            newContent = README_NEW,
        ),
        "src/api/ratelimit.ts" to newFileDiff(RATELIMIT_TS, "typescript"),
        "scratch/notes.md" to newFileDiff(NOTES_MD, "markdown"),
    )

    /** Default reply for `GetWorktreeDefaults` in the demo. */
    const val WORKTREE_REPO_NAME = "orbit"

    /** Sibling-style default worktree path. */
    const val WORKTREE_SIBLING = "$HOME/code/orbit-branch"

    /** `.worktrees/`-style default worktree path. */
    const val WORKTREE_DOTDIR = "$CWD/.worktrees/branch"
}

// ---------------------------------------------------------------------------
// Full file contents referenced by both the file browser and the git diffs.
// ---------------------------------------------------------------------------

/** Post-change `src/api/sessions.ts` (matches the Modified diff). */
private val SESSIONS_TS_NEW = """
import { Router } from "../lib/router";
import { log } from "../lib/log";
import { createSession, getSession, deleteSession } from "../lib/store";
import { rateLimit } from "./ratelimit";

const limiter = rateLimit({ capacity: 20, refillPerSecond: 5 });

export const sessions = new Router();

sessions.post("/api/sessions", limiter, async (req, res) => {
  const session = await createSession(req.body);
  log.info("session created", { id: session.id });
  res.status(201).json(session);
});

sessions.get("/api/sessions/:id", async (req, res) => {
  const session = await getSession(req.params.id);
  if (!session) return res.status(404).json({ error: "not found" });
  res.json(session);
});

sessions.delete("/api/sessions/:id", limiter, async (req, res) => {
  await deleteSession(req.params.id);
  res.status(204).end();
});
""".trimStart()

/** Pre-change `src/api/sessions.ts`. */
private val SESSIONS_TS_OLD = """
import { Router } from "../lib/router";
import { log } from "../lib/log";
import { createSession, getSession, deleteSession } from "../lib/store";

export const sessions = new Router();

sessions.post("/api/sessions", async (req, res) => {
  const session = await createSession(req.body);
  log.info("session created", { id: session.id });
  res.status(201).json(session);
});

sessions.get("/api/sessions/:id", async (req, res) => {
  const session = await getSession(req.params.id);
  if (!session) return res.status(404).json({ error: "not found" });
  res.json(session);
});

sessions.delete("/api/sessions/:id", async (req, res) => {
  await deleteSession(req.params.id);
  res.status(204).end();
});
""".trimStart()

/** The brand-new `src/api/ratelimit.ts` (Added). */
private val RATELIMIT_TS = """
/** Token-bucket rate limiter middleware. */
import type { Middleware } from "../lib/router";

export interface RateLimitOptions {
  capacity: number;
  refillPerSecond: number;
}

interface Bucket {
  tokens: number;
  lastRefill: number;
}

export function rateLimit(opts: RateLimitOptions): Middleware {
  const buckets = new Map<string, Bucket>();

  return (req, res, next) => {
    const key = req.socket.remoteAddress ?? "unknown";
    const now = Date.now();
    const bucket = buckets.get(key) ?? { tokens: opts.capacity, lastRefill: now };
    const elapsed = (now - bucket.lastRefill) / 1000;
    bucket.tokens = Math.min(opts.capacity, bucket.tokens + elapsed * opts.refillPerSecond);
    bucket.lastRefill = now;

    if (bucket.tokens < 1) {
      buckets.set(key, bucket);
      res.status(429).json({ error: "rate limited" });
      return;
    }

    bucket.tokens -= 1;
    buckets.set(key, bucket);
    next();
  };
}
""".trimStart()

/** Post-change `src/server.ts`. */
private val SERVER_TS_NEW = """
import { createServer } from "node:http";
import { log } from "./lib/log";
import { sessions } from "./api/sessions";
import { users } from "./api/users";

const server = createServer((req, res) => {
  sessions.dispatch(req, res) || users.dispatch(req, res);
});

const port = Number(process.env.PORT ?? 8787);
server.listen(port, () => log.info("orbit listening", { port }));

process.on("SIGTERM", () => {
  log.info("shutting down");
  server.close(() => process.exit(0));
});
""".trimStart()

/** Pre-change `src/server.ts`. */
private val SERVER_TS_OLD = """
import { createServer } from "node:http";
import { log } from "./lib/log";
import { sessions } from "./api/sessions";

const server = createServer((req, res) => {
  sessions.dispatch(req, res);
});

const port = Number(process.env.PORT ?? 8787);
server.listen(port, () => log.info("orbit listening", { port }));

process.on("SIGTERM", () => {
  log.info("shutting down");
  server.close(() => process.exit(0));
});
""".trimStart()

/** `src/api/users.ts` (unchanged file, browsable). */
private val USERS_TS = """
import { Router } from "../lib/router";
import { getUser } from "../lib/store";

export const users = new Router();

users.get("/api/users/:id", async (req, res) => {
  const user = await getUser(req.params.id);
  if (!user) return res.status(404).json({ error: "not found" });
  res.json(user);
});
""".trimStart()

/** `src/lib/config.ts` (unchanged file, browsable). */
private val CONFIG_TS = """
export interface Config {
  port: number;
  logLevel: "debug" | "info" | "warn" | "error";
}

export const config: Config = {
  port: Number(process.env.PORT ?? 8787),
  logLevel: (process.env.LOG_LEVEL as Config["logLevel"]) ?? "info",
};
""".trimStart()

/** `src/lib/log.ts` (unchanged file, browsable). */
private val LOG_TS = """
type Fields = Record<string, unknown>;

const ts = () => new Date().toISOString().slice(11, 19);

export const log = {
  info: (msg: string, fields?: Fields) =>
    console.log(`[${'$'}{ts()}] ${'$'}{msg}`, fields ?? ""),
  error: (msg: string, fields?: Fields) =>
    console.error(`[${'$'}{ts()}] ${'$'}{msg}`, fields ?? ""),
};
""".trimStart()

/** `src/lib/router.ts` (unchanged file, browsable). */
private val ROUTER_TS = """
import type { IncomingMessage, ServerResponse } from "node:http";

export type Middleware = (req: any, res: any, next: () => void) => void;
type Handler = (req: any, res: any) => void | Promise<void>;

export class Router {
  private routes: { method: string; pattern: string; chain: (Middleware | Handler)[] }[] = [];

  post(pattern: string, ...chain: (Middleware | Handler)[]) { this.add("POST", pattern, chain); }
  get(pattern: string, ...chain: (Middleware | Handler)[]) { this.add("GET", pattern, chain); }
  delete(pattern: string, ...chain: (Middleware | Handler)[]) { this.add("DELETE", pattern, chain); }

  private add(method: string, pattern: string, chain: (Middleware | Handler)[]) {
    this.routes.push({ method, pattern, chain });
  }

  dispatch(req: IncomingMessage, res: ServerResponse): boolean {
    // pattern matching + middleware chain elided for the demo
    return this.routes.length > 0;
  }
}
""".trimStart()

/** Post-change `README.md` source. */
private val README_NEW = """
# Orbit

A tiny session service used as the termtastic demo workspace.

## Quick start

    npm install
    npm run dev

The API listens on http://localhost:8787. See docs/architecture.md
for the request flow and docs/deploy.md for shipping it.

## Rate limits

Mutating session routes are limited to a burst of 20 requests
with a refill of 5/s per client (HTTP 429 when exceeded).
""".trimStart()

/** Pre-change `README.md` source. */
private val README_OLD = """
# Orbit

A tiny session service used as the termtastic demo workspace.

## Quick start

    npm install
    npm run dev

The API listens on http://localhost:8787. See docs/architecture.md
for the request flow and docs/deploy.md for shipping it.
""".trimStart()

/** The untracked `scratch/notes.md`. */
private val NOTES_MD = """
# notes

- limiter: capacity 20, refill 5/s — tune after load test
- TODO: integration test for the 429 path
- TODO: README section on rate limits
""".trimStart()
