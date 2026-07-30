/**
 * Data model for the free-form pane layout in Lunamux. Each tab owns a
 * flat list of [Pane]s, each with an absolute `(x, y, width, height, z)` in
 * fractions of the tab content area. Panes may overlap; higher [Pane.z] wins
 * the stacking order. There is no split tree — geometry is the only layout
 * language now.
 *
 * The server is the single source of truth. Clients render this model and
 * send [WindowCommand] mutations; the server applies them and broadcasts the
 * updated [WindowConfig] via [WindowEnvelope.Config].
 *
 * @see WindowCommand for client-to-server mutations
 * @see WindowEnvelope.Config for the server-to-client push
 * @see PaneGeometry for the snap-and-clamp policy every pane obeys
 * @see windowJson for the shared serialization configuration
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Window configuration: an ordered list of tabs, each holding a flat list of
 * absolutely-positioned panes.
 *
 * The server is the single source of truth for this structure. Clients render
 * it and send mutation commands; the server pushes the updated config back to
 * every connected client.
 */
@Serializable
data class WindowConfig(
    /**
     * LEGACY mirror of the **default** (first) world's tabs. The server
     * keeps this continuously equal to `worlds.first().tabs` so that
     * pre-1.9 ("world-unaware") clients — which never read [worlds] —
     * keep working unchanged. World-aware (≥1.9) clients ignore this and
     * read [worlds] instead. Defaulted to empty so a bare `WindowConfig()`
     * and old-shaped blobs (only these two legacy fields) both decode.
     */
    val tabs: List<TabConfig> = emptyList(),
    /**
     * LEGACY mirror of the default world's active tab id — for pre-1.9
     * clients only. `null` (or an unknown id) means "let the client
     * default to the first tab". Kept in lockstep with
     * `worlds.first().activeTabId`.
     */
    val activeTabId: String? = null,
    /**
     * The source of truth for **world-aware** (≥1.9) clients: the ordered
     * list of worlds, each owning its own tabs and per-world theme pair.
     * Empty on old-shaped persisted blobs (which the V3→V4 migration wraps
     * into one default world) and after [flattenToFirstWorld] strips it for
     * a pre-1.9 client. The first world is the conventional **default**
     * world that the legacy [tabs]/[activeTabId] mirror.
     *
     * @see WorldConfig
     */
    val worlds: List<WorldConfig> = emptyList(),
    /**
     * The id of the active world for world-aware clients, or `null` to
     * default to the first world. Never sent to pre-1.9 clients (see
     * [flattenToFirstWorld]).
     */
    val activeWorldId: String? = null,
)

/**
 * The active world for world-aware (≥1.9) clients — resolved by
 * [WindowConfig.activeWorldId], falling back to the first world. Returns `null`
 * for pre-1.9 ("world-unaware") configs that carry no [WindowConfig.worlds];
 * callers then fall back to the legacy flat [WindowConfig.tabs].
 *
 * Caller context: every client that renders the active world's tabs — the
 * Android session list ([se.soderbjorn.lunamux] Android `TreeScreen`) and the
 * shared overview model ([se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel]).
 * Mirrors the web toolkit's `activeWorldOrNull()` and the demo server's
 * `activeWorld`.
 *
 * @return the active [WorldConfig], or `null` when the config has no worlds.
 * @see effectiveTabs
 */
fun WindowConfig.activeWorldOrNull(): WorldConfig? =
    worlds.firstOrNull { it.id == activeWorldId } ?: worlds.firstOrNull()

/**
 * The tabs the user should actually see: the active world's tabs for
 * world-aware configs, or the legacy flat [WindowConfig.tabs] for pre-1.9
 * configs (which carry no worlds).
 *
 * **Prefer this over reading [WindowConfig.tabs] directly.** The flat [tabs] is
 * only a mirror of the *first* world (kept for pre-1.9 clients) and does NOT
 * track the active world, so reading it renders the wrong world's tabs after a
 * world switch (the bug this property exists to prevent).
 *
 * @return the active world's tabs, or the legacy flat tabs when world-unaware.
 * @see activeWorldOrNull
 * @see effectiveActiveTabId
 */
val WindowConfig.effectiveTabs: List<TabConfig>
    get() = activeWorldOrNull()?.tabs ?: tabs

/**
 * The active tab id within [effectiveTabs]: the active world's `activeTabId`
 * for world-aware configs, else the legacy flat [WindowConfig.activeTabId].
 * May be `null` (or an id not present in [effectiveTabs]) — callers default to
 * the first visible tab in that case.
 *
 * @return the active tab id, or `null` to let the caller pick a default.
 * @see effectiveTabs
 */
val WindowConfig.effectiveActiveTabId: String?
    get() = activeWorldOrNull()?.activeTabId ?: activeTabId

/**
 * A **world**: a named workspace one level above tabs, owning its own tab
 * list and (optionally) its own theme pair. The server-authoritative
 * source of truth for world-aware clients; rides the [WindowConfig]
 * broadcast.
 *
 * Mirrors the toolkit's `WorldState`. The default (first) world's [tabs]
 * and [activeTabId] are also mirrored into the legacy top-level
 * [WindowConfig.tabs] / [WindowConfig.activeTabId] for pre-1.9 clients.
 *
 * @property id             stable world id, minted `"w<n>-<nonce>"` server
 *   side (mirroring `newTabId()`).
 * @property name           human-readable world name shown in the switcher.
 * @property tabs           this world's tabs (same [TabConfig] model tabs
 *   use everywhere).
 * @property activeTabId    id of this world's active tab, or `null`.
 * @property themeSelection this world's theme pair, or `null` to follow the
 *   global selection.
 */
@Serializable
data class WorldConfig(
    val id: String,
    val name: String,
    val tabs: List<TabConfig> = emptyList(),
    val activeTabId: String? = null,
    val themeSelection: WorldThemeSelection? = null,
)

/**
 * A world's theme **pair** — the dark-slot and light-slot theme names for
 * one world. Deliberately carries no appearance (Auto/Dark/Light): the
 * light↔dark mode is a single **global** setting shared across worlds, so a
 * world owns only "which dark theme and which light theme", while the
 * global appearance decides which of the two is live. Custom-theme
 * *definitions* + favorites likewise stay global.
 *
 * Mirrors the toolkit's `WorldThemeSelection`.
 *
 * @property darkThemeName  theme bound to this world's dark slot.
 * @property lightThemeName theme bound to this world's light slot.
 */
@Serializable
data class WorldThemeSelection(
    val darkThemeName: String,
    val lightThemeName: String,
)

/**
 * Configuration for a single tab. Each tab contains zero or more free-form
 * [Pane]s (ordered by creation; stacking is determined by [Pane.z]).
 *
 * @see WindowConfig.tabs
 */
@Serializable
data class TabConfig(
    val id: String,
    val title: String,
    /**
     * Every visible pane in this tab. May be empty (the client renders an
     * empty-state placeholder). Panes may overlap; the rendering client sorts
     * by [Pane.z] ascending so higher-z panes draw on top.
     */
    val panes: List<Pane> = emptyList(),
    /**
     * The id of the pane that was last focused in this tab. Used by the
     * client to restore focus when the user switches back to this tab, and
     * persisted across server restarts. `null` (or an unknown id) means
     * "let the client default to the first pane in DOM order".
     */
    val focusedPaneId: String? = null,
    /**
     * Whether this tab is hidden from the tab strip. Hidden tabs still exist
     * with all their panes and PTY sessions intact, but the web client skips
     * them when rendering the tab buttons. They are surfaced in the tab-bar
     * overflow menu instead and can be unhidden from there via
     * [WindowCommand.SetTabHidden]. Defaults to `false` so legacy persisted
     * configs round-trip as fully visible.
     *
     * @see WindowCommand.SetTabHidden
     */
    val isHidden: Boolean = false,
    /**
     * Whether this tab is hidden from the left sidebar's tab tree. Hidden-
     * from-sidebar tabs still exist with all their panes and PTY sessions
     * intact and still participate in the tab bar — the web client merely
     * skips them when rendering the sidebar tree so the user can declutter
     * the sidebar independently of the tab strip. Toggled from the tab-bar
     * overflow menu's "Show in side bar" / "Hide in side bar" entry via
     * [WindowCommand.SetTabHiddenFromSidebar]. Defaults to `false` so
     * legacy persisted configs round-trip as fully visible.
     *
     * This is orthogonal to [isHidden]: a tab can be shown in the strip but
     * hidden from the sidebar, and vice versa. Unlike [isHidden] there is
     * no "overflow menu" surfacing for sidebar-hidden tabs — the tab bar
     * remains the authoritative place to reach them.
     *
     * @see WindowCommand.SetTabHiddenFromSidebar
     */
    val isHiddenFromSidebar: Boolean = false,
    /**
     * Last layout preset applied to this tab — one of the keys from
     * `LayoutPreset.key` (e.g. `"auto"`, `"grid"`, `"hero-left"`, …).
     * `null` means no preset is driving (manual placement / fresh
     * tab). Persisted so the server can re-engage `"auto"` re-tile
     * after a restart without forcing the user to re-pick from the
     * dropdown. Drives `WindowState.activeLayoutByTab`.
     */
    val layoutPreset: String? = null,
)

/**
 * A free-form pane within a tab. Position and size are fractions of the
 * tab content area's width and height; [z] is the stacking key (higher
 * renders on top). All geometry is snapped to a 10% grid — see
 * [PaneGeometry].
 */
@Serializable
data class Pane(
    /** The content descriptor for this pane (terminal, file browser, git). */
    val leaf: LeafNode,
    /** Top-left x as a fraction of the tab area's width, snapped to [PaneGeometry.SNAP]. */
    val x: Double,
    /** Top-left y as a fraction of the tab area's height, snapped to [PaneGeometry.SNAP]. */
    val y: Double,
    /** Width as a fraction of the tab area's width, snapped. */
    val width: Double,
    /** Height as a fraction of the tab area's height, snapped. */
    val height: Double,
    /** Stacking order key — higher renders on top. Raised by [WindowCommand.RaisePane]. */
    val z: Long,
    /**
     * Whether this pane is currently maximized (drawn fullscreen on top of its
     * tab regardless of [x]/[y]/[width]/[height]). The stored geometry is
     * preserved unchanged so toggling this off restores the pane to its prior
     * size and position. At most one pane per tab should have this set; the
     * server enforces the mutual exclusion in `toggleMaximized`.
     */
    val maximized: Boolean = false,
    /**
     * The pane's **visual zoom** multiplier in the 3D world (1.0 = unzoomed).
     * A pure GPU magnification applied by the 3D world's render loop — it does
     * not reflow the PTY or affect the 2D layout, which is why it lives here
     * beside the other presentation flags rather than in [LeafContent].
     * Written by [WindowCommand.SetPaneZoom] (the 3D world's `+`/`−`/`0` keys)
     * and read back by the 3D world when it seeds a pane's remembered zoom.
     * Defaulted so configs persisted before this field existed round-trip.
     */
    val zoom: Double = 1.0,
    /**
     * The pane's **3D-world grid override** — a per-pane terminal size (cols ×
     * rows) that only the 3D world applies, distinct from the pane's "native"
     * 2D size. `null` (the default, so old configs round-trip) means "no
     * override": in 3D the pane uses whatever size the 2D clients settle on.
     *
     * Unlike [zoom] this one *does* reflow the PTY — but only while the 3D
     * world is the size-driving view. The mechanism is [PtyControl] size
     * *priority*: the 3D world votes this size at [SizePriority.THREE_D], which
     * outranks the 2D clients' [SizePriority.NORMAL] votes but loses to an
     * explicit take-over (a Reformat / phone force wins even over the 3D tier).
     * Because that vote is tied to the 3D socket's liveness, a crashed/closed 3D
     * view simply drops the vote and the PTY falls back to the 2D size with no
     * explicit cleanup — see `TerminalSessionManager.applyEffectiveSize`.
     *
     * Written by [WindowCommand.SetPaneGrid3d] (the 3D world's grid keys), and
     * cleared (set back to `null`) by the 3D world's "restore native grid"
     * hotkey. Seeded back into the 3D world on open so an override survives app
     * restarts, exactly like [zoom].
     *
     * @see PaneGrid
     * @see WindowCommand.SetPaneGrid3d
     */
    val grid3d: PaneGrid? = null,
)

/**
 * A terminal **grid size** — a column/row pair. Used by [Pane.grid3d] to carry
 * a pane's 3D-world size override in the persisted [WindowConfig], and echoed
 * over [WindowCommand.SetPaneGrid3d] when the 3D world changes it.
 *
 * @property cols number of columns (terminal width in cells), ≥ 1.
 * @property rows number of rows (terminal height in cells), ≥ 1.
 */
@Serializable
data class PaneGrid(
    val cols: Int,
    val rows: Int,
)

/**
 * The content descriptor for a [Pane]. Each leaf carries its own id
 * (distinct from the tab and from other leaves in the same tab), the PTY
 * session id (for terminal leaves), a display title, and content-specific
 * state in [content].
 *
 * Until the free-form refactor this was a subtype of a sealed `PaneNode`
 * class alongside `SplitNode`. Splits are gone; leaves are now stand-alone.
 */
@Serializable
data class LeafNode(
    val id: String,
    /**
     * For terminal leaves, the live PTY session id. Kept as a top-level field
     * (rather than only inside [TerminalContent]) so persisted blobs from
     * before the [content] field existed still deserialize, and so existing
     * code paths that read `leaf.sessionId` keep working unchanged. New code
     * that needs to dispatch on pane kind should read [content] instead.
     *
     * Empty for non-terminal leaves.
     */
    val sessionId: String,
    /**
     * Display title shown in the pane header. Always equals
     * `customName ?: programTitle ?: prettifyPath(cwd) ?: defaultLabel` — kept
     * denormalized so the websocket payload doesn't have to compute it
     * client-side and so persisted blobs round-trip without recomputation.
     */
    val title: String,
    /** User-set name. `null` means "fall back to the cwd-based title". */
    val customName: String? = null,
    /**
     * Title set by the program running in the terminal via the standard OSC 0/2
     * escape sequence (e.g. Claude Code's live task summary), sanitized
     * server-side. Ranks below [customName] and above the cwd-based title —
     * see `computeLeafTitle`. `null` when no program has set one (or the
     * feature is off); persisted like [cwd] so restored layouts keep it.
     */
    val programTitle: String? = null,
    /** Last known shell working directory, learned from OSC 7 / proc polling. */
    val cwd: String? = null,
    /**
     * What this pane currently displays. `null` on persisted blobs predating
     * the field — server rehydrate synthesizes a [TerminalContent] from the
     * legacy top-level [sessionId] in that case.
     */
    val content: LeafContent? = null,
    /**
     * `true` when this pane is a linked view into another pane's session
     * (created via [WindowCommand.AddLinkToTab]). Closing a link only removes
     * that single view; closing the *original* (non-link) pane cascades to
     * close all linked views of the same session.
     */
    val isLink: Boolean = false,
    /**
     * Agent-activity note shown as a small badge on the pane. Set by the
     * MCP `annotate_window` tool, and auto-set (to a generic marker) when
     * an MCP agent mutates the window or writes to its session — so an
     * agent acting across devices is never invisible. `null` (the default,
     * so legacy blobs round-trip) means no badge.
     *
     * @see WindowEnvelope.AgentNotify for transient agent notifications
     */
    val agentNote: String? = null,
)

/**
 * What a [LeafNode] is actually showing. The discriminator is the shared
 * `"kind"` field configured in [windowJson].
 */
@Serializable
sealed class LeafContent

/**
 * Content descriptor for a pane displaying a live terminal (PTY session).
 *
 * @see LeafContent
 */
@Serializable
@SerialName("terminal")
data class TerminalContent(
    /** Live PTY session id. Mirrors [LeafNode.sessionId] for terminal leaves. */
    val sessionId: String,
    /** Font size in pixels. Null = client default (13). */
    val fontSize: Int? = null,
    /**
     * Per-pane override for automatic reflow (a.k.a. "Reformat"): when `true`
     * the client re-asserts the PTY size to match the pane on every window /
     * pane resize, tab activation, reconnect and font load; when `false` the
     * terminal is frozen at its current size and only the explicit "Reformat"
     * button reflows it. `null` means "inherit the user's global default"
     * (the `autoReformatDefault` UI setting, itself defaulting to `true` so
     * the factory behaviour stays "reflow every new pane").
     *
     * Toggled via [WindowCommand.SetTerminalAutoReflow] from the reformat
     * button's hover popup ("Automatic reformat (this window)"). Persisted as
     * part of the window config alongside the other per-pane session settings.
     */
    val autoReflow: Boolean? = null,
) : LeafContent()

/**
 * File browser pane: a collapsible tree of every file under the leaf's cwd
 * plus a rendered/highlighted preview pane on the right. Folders start
 * collapsed; expanding one triggers a one-level directory listing from the
 * server. Selecting a file loads its content (markdown-rendered, syntax-
 * highlighted, or a binary placeholder). `""` represents the root directory
 * in [expandedDirs] and in listing requests.
 */
@Serializable
@SerialName("fileBrowser")
data class FileBrowserContent(
    /** Relative path of the file currently shown on the right side, or null. */
    val selectedRelPath: String? = null,
    /** Relative paths of directories the user has expanded in the tree. */
    val expandedDirs: Set<String> = emptySet(),
    /** Width of the left file list in pixels. Null = client default (240). */
    val leftColumnWidthPx: Int? = null,
    /** Reserved for future filesystem-watch support. */
    val autoRefresh: Boolean = false,
    /** Font size in pixels for the rendered preview. Null = client default. */
    val fontSize: Int? = null,
    /**
     * Filename filter applied server-side when listing directories. Glob
     * syntax (`*`, `?`); empty/null means "show all files". Directories are
     * always shown regardless of filter so the user can navigate into them.
     */
    val fileFilter: String? = null,
    /** Sort order for entries within each directory listing. */
    val sortBy: FileBrowserSort = FileBrowserSort.NAME,
) : LeafContent()

/** How the file-browser tree orders entries inside each directory. */
@Serializable
enum class FileBrowserSort {
    @SerialName("name") NAME,
    @SerialName("mtime") MTIME,
}

/**
 * Git overview pane: a list of uncommitted changes on the left and a diff
 * viewer on the right. Mirrors the [FileBrowserContent] two-pane pattern.
 */
@Serializable
@SerialName("git")
data class GitContent(
    /** Path of the file currently shown in the diff viewer, or null. */
    val selectedFilePath: String? = null,
    /** Width of the left file list in pixels. Null = client default (280). */
    val leftColumnWidthPx: Int? = null,
    /** Inline (unified) vs. split (side-by-side P4Diff-style) diff mode. */
    val diffMode: GitDiffMode = GitDiffMode.Inline,
    /** When true and [diffMode] is [GitDiffMode.Split], render the P4Merge-style
     *  graphical view with independent scrolling and SVG connector lines. */
    val graphicalDiff: Boolean = false,
    /** Font size in pixels for the diff content area. Null = client default. */
    val diffFontSize: Int? = null,
    /** If true, the server pushes a fresh file list on working tree changes. */
    val autoRefresh: Boolean = false,
) : LeafContent()

/**
 * Web browser pane: an embedded, interactive web page. Rendered live only in
 * the Electron client (via a `<webview>` guest); web and mobile clients show
 * an "Open in browser" link button instead. Unlike terminal/git/file-browser
 * panes the server spawns no process and streams no data for this kind — it
 * only stores the last-committed URL and page title so the pane can be
 * restored after a restart and labelled in the ring/overview.
 *
 * The client persists navigation back to the server with
 * [WindowCommand.WebBrowserSetUrl]; the server creates panes of this kind via
 * [WindowCommand.AddWebBrowserToTab].
 *
 * @see LeafContent
 */
@Serializable
@SerialName("webBrowser")
data class WebBrowserContent(
    /** Last-committed URL, used to restore the pane after a restart. Null =
     *  open on the client's local start page (blank, address bar focused). */
    val url: String? = null,
    /** Last observed page title, used to label the pane in the 3D ring and
     *  the overview tiles. Null = fall back to the URL/host. */
    val title: String? = null,
) : LeafContent()

/**
 * Agent console pane: a PTY-less virtual session driven by an MCP client
 * (an AI agent) through the server's `open_console` / `console_*` /
 * `screen_*` tools rather than by a shell.
 *
 * Two render modes:
 *  - `"transcript"` — a scrolling conversation log plus a line-input box.
 *    The web client renders it as plain DOM over the `/agent/{id}` socket;
 *    mobile clients render the mirrored terminal stream through their
 *    existing terminal views (the server echoes transcript text and runs a
 *    cooked line discipline for typed input).
 *  - `"screen"` — an addressable VT grid painted via `screen_draw` /
 *    `screen_present` / `console_write_raw`. Every client reuses its
 *    terminal renderer, attached to the same `/pty/{sessionId}` socket a
 *    shell pane uses (the backing `AgentSession` implements the same
 *    session interface).
 *
 * Agent consoles are **ephemeral**: a PTY-less session cannot be
 * reattached, so the server tears the pane down when the owning MCP
 * client disconnects and drops persisted agent panes on restart.
 *
 * @see LeafContent
 */
@Serializable
@SerialName("agent")
data class AgentContent(
    /** `"transcript"` or `"screen"` — see the class doc. */
    val renderMode: String = "transcript",
    /** Requested grid width for screen mode; null = client-driven. */
    val cols: Int? = null,
    /** Requested grid height for screen mode; null = client-driven. */
    val rows: Int? = null,
) : LeafContent()

/**
 * Display mode for the git diff viewer.
 */
@Serializable
enum class GitDiffMode {
    /** Unified diff with additions and deletions interleaved. */
    Inline,
    /** Side-by-side diff with old content on the left and new on the right. */
    Split
}
