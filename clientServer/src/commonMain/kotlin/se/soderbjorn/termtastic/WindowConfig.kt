/**
 * Data model for the free-form pane layout in Termtastic. Each tab owns a
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
package se.soderbjorn.termtastic

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
    val tabs: List<TabConfig>,
    /**
     * The id of the currently-selected tab. Persisted so a fresh process boot
     * restores the user's last view instead of always defaulting to the first
     * tab. `null` (or an unknown id) means "let the client default to the
     * first tab".
     */
    val activeTabId: String? = null,
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
