/**
 * Client-to-server mutation commands and PTY control messages for the
 * Lunamux terminal emulator. These sealed hierarchies define every
 * structured JSON message a client can send to the server over the
 * `/window` and `/pty/{id}` WebSocket connections.
 *
 * @see WindowEnvelope for the server-to-client direction
 * @see windowJson for the shared serialization configuration
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Mutation commands sent client → server over the `/window` websocket as
 * JSON Text frames. Each subclass maps 1:1 to a branch of the server's
 * `handleWindowCommand` `when` block. The discriminator is `"type"` (not the
 * global `"kind"` [windowJson] uses for nested pane hierarchies).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WindowCommand {
    /**
     * Request a one-level directory listing from the server for the file browser.
     * The server responds with a [WindowEnvelope.FileBrowserDir] message.
     *
     * @param paneId the file browser pane requesting the listing
     * @param dirRelPath relative path of the directory to list (`""` for root)
     */
    @Serializable
    @SerialName("fileBrowserListDir")
    data class FileBrowserListDir(val paneId: String, val dirRelPath: String) : WindowCommand()

    /**
     * Request rendered content for a file selected in the file browser.
     * The server responds with a [WindowEnvelope.FileBrowserContentMsg].
     *
     * @param paneId the file browser pane requesting the content
     * @param relPath relative path of the file to open and render
     */
    @Serializable
    @SerialName("fileBrowserOpenFile")
    data class FileBrowserOpenFile(val paneId: String, val relPath: String) : WindowCommand()

    /**
     * Toggle the expanded/collapsed state of a directory in the file browser tree.
     *
     * @param paneId the file browser pane containing the directory
     * @param dirRelPath relative path of the directory to expand or collapse
     * @param expanded `true` to expand, `false` to collapse
     */
    @Serializable
    @SerialName("fileBrowserSetExpanded")
    data class FileBrowserSetExpanded(
        val paneId: String,
        val dirRelPath: String,
        val expanded: Boolean,
    ) : WindowCommand()

    /**
     * Persist a new width for the file browser's left tree column after a drag resize.
     *
     * @param paneId the file browser pane being resized
     * @param px new width of the left column in pixels
     */
    @Serializable
    @SerialName("setFileBrowserLeftWidth")
    data class SetFileBrowserLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    /**
     * Enable or disable automatic refresh of the file browser tree when the
     * filesystem changes.
     *
     * @param paneId the file browser pane to configure
     * @param enabled `true` to enable auto-refresh, `false` to disable
     */
    @Serializable
    @SerialName("setFileBrowserAutoRefresh")
    data class SetFileBrowserAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Set a filename filter for directory listings in the file browser.
     * Supports glob syntax (`*`, `?`). An empty string clears the filter.
     *
     * @param paneId the file browser pane to configure
     * @param filter glob pattern to apply, or `""` to show all files
     * @see FileBrowserContent.fileFilter
     */
    @Serializable
    @SerialName("setFileBrowserFilter")
    data class SetFileBrowserFilter(val paneId: String, val filter: String) : WindowCommand()

    /**
     * Change the sort order of entries within each directory listing in the file browser.
     *
     * @param paneId the file browser pane to configure
     * @param sort the desired sort order
     * @see FileBrowserSort
     */
    @Serializable
    @SerialName("setFileBrowserSort")
    data class SetFileBrowserSort(val paneId: String, val sort: FileBrowserSort) : WindowCommand()

    /**
     * Recursively list every directory under the pane's cwd (subject to caps)
     * and mark them as expanded. The server replies with one [WindowEnvelope.FileBrowserDir]
     * per visited directory; the resulting expandedDirs set is pushed back via
     * the regular WindowConfig flow.
     */
    @Serializable
    @SerialName("fileBrowserExpandAll")
    data class FileBrowserExpandAll(val paneId: String) : WindowCommand()

    /**
     * Collapse every expanded directory in the file browser tree, resetting
     * the expanded set to empty.
     *
     * @param paneId the file browser pane to collapse
     */
    @Serializable
    @SerialName("fileBrowserCollapseAll")
    data class FileBrowserCollapseAll(val paneId: String) : WindowCommand()

    /**
     * Change the font size used in the file browser's preview pane.
     *
     * @param paneId the file browser pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setFileBrowserFontSize")
    data class SetFileBrowserFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Request the list of uncommitted changes for the git pane's working directory.
     * The server responds with a [WindowEnvelope.GitList] message.
     *
     * @param paneId the git pane requesting the file list
     */
    @Serializable
    @SerialName("gitList")
    data class GitList(val paneId: String) : WindowCommand()

    /**
     * Request the diff for a specific file in the git pane.
     * The server responds with a [WindowEnvelope.GitDiffResult] message.
     *
     * @param paneId the git pane requesting the diff
     * @param filePath path of the file to diff (relative to the working directory)
     */
    @Serializable
    @SerialName("gitDiff")
    data class GitDiff(val paneId: String, val filePath: String) : WindowCommand()

    /**
     * Persist a new width for the git pane's left file list column after a drag resize.
     *
     * @param paneId the git pane being resized
     * @param px new width of the left column in pixels
     */
    @Serializable
    @SerialName("setGitLeftWidth")
    data class SetGitLeftWidth(val paneId: String, val px: Int) : WindowCommand()

    /**
     * Switch the git pane between inline (unified) and split (side-by-side) diff modes.
     *
     * @param paneId the git pane to configure
     * @param mode the desired diff display mode
     * @see GitDiffMode
     */
    @Serializable
    @SerialName("setGitDiffMode")
    data class SetGitDiffMode(val paneId: String, val mode: GitDiffMode) : WindowCommand()

    /**
     * Enable or disable the graphical diff view (P4Merge-style with SVG connector
     * lines) when the git pane is in [GitDiffMode.Split] mode.
     *
     * @param paneId the git pane to configure
     * @param enabled `true` to enable the graphical view, `false` for plain text
     */
    @Serializable
    @SerialName("setGitGraphicalDiff")
    data class SetGitGraphicalDiff(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Change the font size used in the git pane's diff content area.
     *
     * @param paneId the git pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setGitDiffFontSize")
    data class SetGitDiffFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Enable or disable automatic refresh of the git file list when the
     * working tree changes.
     *
     * @param paneId the git pane to configure
     * @param enabled `true` to enable auto-refresh, `false` to disable
     */
    @Serializable
    @SerialName("setGitAutoRefresh")
    data class SetGitAutoRefresh(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Change the font size used in a terminal pane.
     *
     * @param paneId the terminal pane to configure
     * @param size font size in pixels
     */
    @Serializable
    @SerialName("setTerminalFontSize")
    data class SetTerminalFontSize(val paneId: String, val size: Int) : WindowCommand()

    /**
     * Enable or disable automatic reflow ("Reformat") for a single terminal
     * pane. When disabled, the client stops re-asserting the PTY size to the
     * pane on window/pane resize, tab activation, reconnect and font load;
     * the explicit Reformat button still works. Sent from the reformat
     * button's hover popup ("Automatic reformat (this window)") and persisted
     * in [TerminalContent.autoReflow].
     *
     * @param paneId the terminal pane to configure
     * @param enabled `true` to keep auto-reflow on for this pane, `false` to
     *   freeze it until the user manually reformats
     * @see TerminalContent.autoReflow
     */
    @Serializable
    @SerialName("setTerminalAutoReflow")
    data class SetTerminalAutoReflow(val paneId: String, val enabled: Boolean) : WindowCommand()

    /**
     * Close a single pane. If the pane is a terminal, its PTY session is
     * terminated (unless other linked views still reference it).
     *
     * @param paneId the pane to close
     */
    @Serializable
    @SerialName("close")
    data class Close(val paneId: String) : WindowCommand()

    /** Close every pane that references [sessionId]. Used when closing a
     *  terminal that has linked panes — all views of the session go away. */
    @Serializable
    @SerialName("closeSession")
    data class CloseSession(val sessionId: String) : WindowCommand()

    /**
     * Set a custom display name for a pane, overriding the default cwd-based title.
     *
     * @param paneId the pane to rename
     * @param title the new custom display name
     * @see LeafNode.customName
     */
    @Serializable
    @SerialName("rename")
    data class Rename(val paneId: String, val title: String) : WindowCommand()

    /** Create a new empty tab and append it to the tab bar. */
    @Serializable
    @SerialName("addTab")
    object AddTab : WindowCommand()

    /**
     * Close a tab and all panes within it.
     *
     * @param tabId the id of the tab to close
     */
    @Serializable
    @SerialName("closeTab")
    data class CloseTab(val tabId: String) : WindowCommand()

    /**
     * Change the display title of a tab in the tab bar.
     *
     * @param tabId the id of the tab to rename
     * @param title the new display title
     */
    @Serializable
    @SerialName("renameTab")
    data class RenameTab(val tabId: String, val title: String) : WindowCommand()

    /**
     * Reorder a tab by moving it before or after another tab via drag-and-drop.
     *
     * @param tabId the id of the tab being moved
     * @param targetTabId the id of the reference tab
     * @param before `true` to place [tabId] before [targetTabId], `false` to place it after
     */
    @Serializable
    @SerialName("moveTab")
    data class MoveTab(
        val tabId: String,
        val targetTabId: String,
        val before: Boolean,
    ) : WindowCommand()

    /**
     * Mark a tab as hidden or visible in the tab strip. Hidden tabs keep all
     * their panes and PTY sessions alive — the web client simply skips them
     * when rendering tab buttons and lists them in the tab-bar overflow menu
     * instead, from where they can be unhidden or activated.
     *
     * Dispatched from the far-right "⋮" tab-bar menu added in the tab-bar
     * revamp. When hiding the currently-active tab, the server picks the
     * next visible neighbour (or falls back to the first visible tab) so
     * the user always has something selected.
     *
     * @param tabId the id of the tab to hide or unhide
     * @param hidden `true` to hide the tab, `false` to make it visible again
     * @see TabConfig.isHidden
     */
    @Serializable
    @SerialName("setTabHidden")
    data class SetTabHidden(val tabId: String, val hidden: Boolean) : WindowCommand()

    /**
     * Mark a tab as hidden or visible in the left sidebar's tab tree. Hidden-
     * from-sidebar tabs keep all their panes and PTY sessions alive and stay
     * reachable through the tab strip; the web client simply skips them when
     * rendering the sidebar so the user can declutter it independently of the
     * tab bar. Unlike [SetTabHidden] there is no overflow-menu surfacing on
     * the sidebar itself — the tab bar remains the way to reach a sidebar-
     * hidden tab.
     *
     * Dispatched from the same far-right "⋮" tab-bar overflow menu as
     * [SetTabHidden], via the "Show in side bar" / "Hide in side bar" entry.
     *
     * @param tabId the id of the tab to hide or unhide in the sidebar
     * @param hidden `true` to hide the tab from the sidebar, `false` to show it
     * @see TabConfig.isHiddenFromSidebar
     */
    @Serializable
    @SerialName("setTabHiddenFromSidebar")
    data class SetTabHiddenFromSidebar(val tabId: String, val hidden: Boolean) : WindowCommand()

    // ---- World lifecycle (world-aware ≥1.9 clients) --------------------
    // These mutate the container one level above tabs. Sent only by
    // world-aware clients; an old client never sends them (and would ignore
    // them if echoed back — its frame decode drops unknown "type"s). The
    // server routes all *tab*-level commands into the world that owns the
    // referenced tab (by id lookup), or, for tab-*creating* commands, into
    // the resolved active world (old clients always resolve to the first
    // world). So tab commands need no worldId field. See handleWindowCommand.

    /**
     * Create a new world and make it active. The server mints its id
     * (`"w<n>-<nonce>"`), seeds one empty tab, and copies the current
     * default/global theme pair into the new world's [WorldConfig.themeSelection].
     *
     * @param name the display name for the new world.
     * @see WorldConfig
     */
    @Serializable
    @SerialName("addWorld")
    data class AddWorld(val name: String) : WindowCommand()

    /**
     * Rename an existing world.
     *
     * @param worldId the id of the world to rename.
     * @param name    the new display name.
     */
    @Serializable
    @SerialName("renameWorld")
    data class RenameWorld(val worldId: String, val name: String) : WindowCommand()

    /**
     * Switch the active world. Changes which world's tabs a world-aware
     * client renders; the legacy tab mirror (for old clients) is unaffected
     * because it always tracks the *default* world, never the active one.
     *
     * @param worldId the id of the world to activate.
     */
    @Serializable
    @SerialName("setActiveWorld")
    data class SetActiveWorld(val worldId: String) : WindowCommand()

    /**
     * Set a world's theme **pair** (dark + light slots). Applies to the
     * target world only; if the target is the default world the server also
     * mirrors it into the legacy global `THEME_V2_SELECTION` key so old
     * clients follow along. Appearance (Auto/Dark/Light) is global and not
     * carried here.
     *
     * @param worldId   the id of the world whose theme pair is being set.
     * @param selection the dark + light theme names.
     * @see WorldThemeSelection
     */
    @Serializable
    @SerialName("setWorldTheme")
    data class SetWorldTheme(
        val worldId: String,
        val selection: WorldThemeSelection,
    ) : WindowCommand()

    /**
     * Close a world, cascading to every tab and PTY session inside it
     * (reusing the same per-tab teardown [CloseTab] uses). The server
     * refuses to close the last remaining world.
     *
     * @param worldId the id of the world to close.
     */
    @Serializable
    @SerialName("closeWorld")
    data class CloseWorld(val worldId: String) : WindowCommand()

    /**
     * Move an entire tab (with all its panes and their live PTY sessions) out
     * of the world that currently owns it and into another world, appending it
     * to that world's tab list. Sessions are untouched — only the tab's
     * ownership changes — so a running agent keeps running as the tab travels.
     *
     * Dispatched from a tab's dot-menu "Move to world" submenu. The server
     * refuses to move a world's last remaining tab (that would leave the source
     * world empty) and ignores a move into the tab's current world or an
     * unknown world. If the moved tab was the active tab of its source world,
     * the server promotes a surviving sibling to active there.
     *
     * @param tabId   the id of the tab to move.
     * @param worldId the id of the destination world.
     */
    @Serializable
    @SerialName("moveTabToWorld")
    data class MoveTabToWorld(val tabId: String, val worldId: String) : WindowCommand()

    /**
     * Add a new terminal pane to an existing tab (creates a new PTY session).
     *
     * @param tabId the id of the tab to add the pane to
     * @param cwd the directory the new shell should start in. The client
     *   reads this from the local [WindowConfig] snapshot at click time —
     *   usually the focused pane's [LeafNode.cwd] — so what the user sees
     *   on screen is what the new pane inherits. `null` means "no cwd"
     *   and the server falls back to `$HOME` for the PTY start dir.
     */
    @Serializable
    @SerialName("addPaneToTab")
    data class AddPaneToTab(
        val tabId: String,
        val cwd: String? = null,
    ) : WindowCommand()

    /**
     * Add a new file browser pane to an existing tab.
     *
     * @param tabId the id of the tab to add the file browser to
     * @param cwd the directory the file browser should root at. Resolved
     *   by the client from its local [WindowConfig]; `null` means "no
     *   cwd" and the server falls back to its own working directory so
     *   the pane is never created with an empty listing. See [AddPaneToTab].
     */
    @Serializable
    @SerialName("addFileBrowserToTab")
    data class AddFileBrowserToTab(
        val tabId: String,
        val cwd: String? = null,
    ) : WindowCommand()

    /**
     * Add a new git overview pane to an existing tab.
     *
     * @param tabId the id of the tab to add the git pane to
     * @param cwd the directory the git overview should root at. Resolved
     *   client-side from the local [WindowConfig]; `null` falls back to
     *   the server's working directory. See [AddPaneToTab].
     */
    @Serializable
    @SerialName("addGitToTab")
    data class AddGitToTab(
        val tabId: String,
        val cwd: String? = null,
    ) : WindowCommand()

    /**
     * Add a new web browser pane to an existing tab.
     *
     * Unlike the other `add*ToTab` commands there is no `cwd` — a web pane is
     * not rooted in the filesystem. `url` seeds the pane's initial page; the
     * "New Web Browser" menu item dispatches this with `url = null` so the
     * pane opens on the client's blank start page and the user types a URL in
     * the pane's own address bar.
     *
     * @param tabId the id of the tab to add the web browser to
     * @param url the initial URL to load, or `null` for the blank start page
     * @see WebBrowserContent
     */
    @Serializable
    @SerialName("addWebBrowserToTab")
    data class AddWebBrowserToTab(
        val tabId: String,
        val url: String? = null,
    ) : WindowCommand()

    /**
     * Persist a web pane's current navigation state (client → server).
     *
     * Dispatched by the Electron webview view whenever the guest commits a
     * navigation (`did-navigate` / `did-navigate-in-page`) or the page title
     * changes, so the pane restores at its last URL after a restart and the
     * ring/overview labels stay current. The server only mutates the leaf's
     * [WebBrowserContent]; it starts no process.
     *
     * @param paneId the web pane to update
     * @param url the newly committed URL
     * @param title the current page title, or `null` if unknown
     * @see WebBrowserContent
     */
    @Serializable
    @SerialName("webBrowserSetUrl")
    data class WebBrowserSetUrl(
        val paneId: String,
        val url: String,
        val title: String? = null,
    ) : WindowCommand()

    /**
     * Add a linked view of an existing terminal session to a tab.
     *
     * @param tabId the id of the tab to add the link to
     * @param targetSessionId the PTY session id to create a linked view of
     * @see LeafNode.isLink
     */
    @Serializable
    @SerialName("addLinkToTab")
    data class AddLinkToTab(val tabId: String, val targetSessionId: String) : WindowCommand()

    /**
     * Update the position and size of a pane after the user drags or
     * resizes it. All values are fractions of the tab area dimensions;
     * the server snaps and clamps via [PaneGeometry.normalize] before
     * persisting, so raw cursor-derived fractions are acceptable here.
     *
     * @param paneId the pane to reposition
     * @param x top-left x as a fraction of the tab area's width
     * @param y top-left y as a fraction of the tab area's height
     * @param width width as a fraction of the tab area's width
     * @param height height as a fraction of the tab area's height
     * @see Pane
     * @see PaneGeometry.normalize
     */
    @Serializable
    @SerialName("setPaneGeom")
    data class SetPaneGeom(
        val paneId: String,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) : WindowCommand()

    /**
     * Set the pane's persisted **3D-world visual zoom** multiplier. Sent by
     * the 3D world whenever the user zooms the front pane (`+`/`−`) or resets
     * it (`0`), so the magnification survives app restarts instead of living
     * only in the renderer's memory. A pure presentation value — the server
     * just clamps and stores it in [Pane.zoom]; nothing reflows.
     *
     * @param paneId the pane whose zoom changed
     * @param zoom the new zoom multiplier (1.0 = unzoomed); the server clamps
     *   it to a sane range and ignores non-finite values
     * @see Pane.zoom
     */
    @Serializable
    @SerialName("setPaneZoom")
    data class SetPaneZoom(val paneId: String, val zoom: Double) : WindowCommand()

    /**
     * Set (or clear) the pane's persisted **3D-world grid override**. Sent by
     * the 3D world whenever the user grows/shrinks a pane's grid there, so the
     * chosen size survives app restarts and re-entering the world, and cleared
     * (both [cols] and [rows] `null`) by the 3D world's "restore native grid"
     * hotkey.
     *
     * Unlike [SetPaneZoom] this override *reflows* the PTY — but only while the
     * 3D world is the active size-driving view (via [SizePriority.THREE_D] size
     * votes); the 2D and mobile clients ignore [Pane.grid3d] entirely. The
     * server just clamps and stores it in [Pane.grid3d]; the actual reflow is
     * driven separately by the 3D world's size votes over the `/pty` socket.
     *
     * @param paneId the pane whose 3D grid override changed
     * @param cols the override column count, or `null` to clear the override
     * @param rows the override row count, or `null` to clear the override
     * @see Pane.grid3d
     * @see SizePriority.THREE_D
     */
    @Serializable
    @SerialName("setPaneGrid3d")
    data class SetPaneGrid3d(
        val paneId: String,
        val cols: Int? = null,
        val rows: Int? = null,
    ) : WindowCommand()

    /**
     * Bring a pane to the top of the stacking order (raise its z-index to
     * `maxZ + 1` within its containing tab). Dispatched on pane activation
     * so clicking any pane surfaces it above its overlapping neighbours.
     *
     * @param paneId the pane to raise
     * @see Pane.z
     */
    @Serializable
    @SerialName("raisePane")
    data class RaisePane(val paneId: String) : WindowCommand()

    /**
     * Toggle [Pane.maximized] on [paneId]. Maximizing a pane unsets any other
     * maximized pane in the same tab and bumps the pane's [Pane.z] to the
     * top of the stacking order; restoring simply clears the flag and leaves
     * the pane's stored x/y/width/height unchanged so it returns to its
     * prior geometry.
     *
     * @param paneId the pane to toggle
     */
    @Serializable
    @SerialName("toggleMaximized")
    data class ToggleMaximized(val paneId: String) : WindowCommand()

    /**
     * Arrange every pane in [tabId] according to [layout], giving
     * [primaryPaneId] (if provided and present in the tab) the largest
     * region for layouts that distinguish a primary. Other panes are placed
     * in the remaining slots in their current order. Also clears any
     * maximized flag so the new layout is actually visible.
     *
     * @param tabId the tab to rearrange
     * @param layout one of `auto`, `grid`, `columns`, `rows`, `hero-left`,
     *               `hero-right`, `hero-top`, `hero-bottom`, `split-h`,
     *               `split-v`, `sidebar-left`, `sidebar-right`, `sidebar-top`,
     *               `sidebar-bottom`, `t-shape`, `t-shape-inv`, `l-shape`,
     *               `l-shape-tr`, `l-shape-bl`, `l-shape-br`, `big-2-stack`,
     *               `big-2-stack-right`, `big-2-stack-bottom`. The `auto`
     *               key selects the toolkit's auto preset, which the server
     *               also re-applies whenever a pane is added or closed (see
     *               `WindowState`).
     * @param primaryPaneId the pane to prioritise, or null/invalid to use
     *                      the tab's first pane
     */
    @Serializable
    @SerialName("applyLayout")
    data class ApplyLayout(
        val tabId: String,
        val layout: String,
        val primaryPaneId: String? = null,
    ) : WindowCommand()

    /**
     * Move a pane from its current tab to a different tab.
     *
     * @param paneId the pane to move
     * @param targetTabId the id of the destination tab
     */
    @Serializable
    @SerialName("movePaneToTab")
    data class MovePaneToTab(val paneId: String, val targetTabId: String) : WindowCommand()

    /**
     * Swap the positions and sizes of two panes that share the same tab.
     * The dragged pane (A) inherits B's geometry and is raised to the top
     * of the stacking order; pane B takes A's former geometry. No-op if
     * either pane is missing or they live in different tabs — cross-tab
     * moves go through [MovePaneToTab] instead.
     *
     * Dispatched from the web client when the user drops a pane's header
     * icon onto another pane in the same tab.
     *
     * @param paneAId the dragged (source) pane id
     * @param paneBId the drop-target pane id
     */
    @Serializable
    @SerialName("swapPanes")
    data class SwapPanes(val paneAId: String, val paneBId: String) : WindowCommand()

    /**
     * Reorder a pane **within its own tab** by moving it immediately before or
     * after another pane in the same tab — the pane-list analogue of [MoveTab].
     * Unlike [SwapPanes] (which trades geometry but keeps list positions), this
     * changes the pane's index in `TabConfig.panes`, so auto-tiled layouts re-flow
     * and any consumer that reads pane order (e.g. the 3D world ring) sees the
     * pane's slot change. No-op if the panes are missing or live in different tabs.
     *
     * @param paneId the pane being moved
     * @param targetPaneId the reference pane in the same tab
     * @param before `true` to place [paneId] before [targetPaneId], `false` for after
     * @param retile `true` (default) re-applies the tab's remembered layout preset
     *   after the reorder — the 2D behavior, where Auto re-flows tiles to match the
     *   new list order. The 3D world sends `false`: there the reorder is a ring-slot
     *   change and a preset re-tile would rewrite every pane's 2D geometry underneath
     *   the world, kicking off resize churn (each client refits its PTYs to the new
     *   boxes) that visibly snaps ring panes to sizes the user never chose. Old
     *   clients omit the field and get the 2D default.
     */
    @Serializable
    @SerialName("movePaneWithinTab")
    data class MovePaneWithinTab(
        val paneId: String,
        val targetPaneId: String,
        val before: Boolean,
        val retile: Boolean = true,
    ) : WindowCommand()

    /**
     * Switch the active (visible) tab. Persisted so tab selection survives restarts.
     *
     * @param tabId the id of the tab to activate
     * @see WindowConfig.activeTabId
     */
    @Serializable
    @SerialName("setActiveTab")
    data class SetActiveTab(val tabId: String) : WindowCommand()

    /**
     * Record which pane has keyboard focus within a tab. Persisted so focus
     * is restored when the user switches back to this tab.
     *
     * @param tabId the tab containing the pane
     * @param paneId the pane that received focus
     * @see TabConfig.focusedPaneId
     */
    @Serializable
    @SerialName("setFocusedPane")
    data class SetFocusedPane(val tabId: String, val paneId: String) : WindowCommand()

    /**
     * Override the display state mode for a terminal session (e.g. force a
     * particular rendering mode). The server broadcasts the updated state
     * to all clients viewing this session.
     *
     * @param sessionId the PTY session to override
     * @param mode the state mode string to set
     */
    @Serializable
    @SerialName("setStateOverride")
    data class SetStateOverride(val sessionId: String, val mode: String) : WindowCommand()

    /** Request the server to send the current UI settings to the client. */
    @Serializable
    @SerialName("openSettings")
    object OpenSettings : WindowCommand()

    /** Request a fresh Claude API usage snapshot from the server. */
    @Serializable
    @SerialName("refreshUsage")
    object RefreshUsage : WindowCommand()

    /**
     * Request default values for creating a git worktree from the pane's
     * current working directory. The server responds with a
     * [WindowEnvelope.WorktreeDefaults] containing suggested paths and
     * dirty-state information so the dialog can be pre-populated.
     *
     * @param paneId the pane requesting the defaults
     */
    @Serializable
    @SerialName("getWorktreeDefaults")
    data class GetWorktreeDefaults(val paneId: String) : WindowCommand()

    /**
     * Create a new git worktree with a new branch, optionally migrating
     * uncommitted changes from the current working directory into the
     * new worktree via `git stash`.
     *
     * @param paneId the pane initiating the worktree creation
     * @param branchName name for the new git branch
     * @param worktreePath absolute filesystem path for the new worktree directory
     * @param migrateChanges `true` to stash and move uncommitted changes to the new worktree
     * @see WindowEnvelope.WorktreeCreated
     * @see WindowEnvelope.WorktreeError
     */
    @Serializable
    @SerialName("createWorktree")
    data class CreateWorktree(
        val paneId: String,
        val branchName: String,
        val worktreePath: String,
        val migrateChanges: Boolean,
    ) : WindowCommand()
}

/**
 * The **priority tier** a client's size vote carries in the server's PTY-size
 * aggregation. The server picks the single highest tier present among all live
 * votes and takes `min()` *within* that tier — so a higher tier fully overrides
 * every lower one rather than being clamped down by it (plain `min()` across all
 * clients would let any small viewport shrink a deliberately-enlarged one).
 *
 * Tiers, low → high (ordinal order is the ranking):
 *  - [NORMAL] — an ordinary 2D client fitting a terminal to its viewport. The
 *    historical behaviour: all-NORMAL votes reduce to the classic
 *    "smallest attached viewport wins" min().
 *  - [THREE_D] — the 3D world asserting a pane's [Pane.grid3d] override. Beats
 *    the 2D clients so the 3D size actually takes, without evicting anyone; the
 *    vote lives only as long as the 3D socket, so leaving/crashing 3D drops it
 *    and the PTY falls back to the 2D size automatically.
 *  - [MOBILE] — a phone/tablet client (classified server-side from the reported
 *    client type). A small mobile viewport must always win so the terminal
 *    stays readable on the phone, overriding even a 3D override.
 *
 * Only [NORMAL] and [THREE_D] ever travel on the wire (from web/desktop
 * clients); the server elevates a vote to [MOBILE] itself based on the
 * connection's client type, so mobile clients need no protocol change.
 *
 * @see PtyControl.Resize.priority
 * @see Pane.grid3d
 */
@Serializable
enum class SizePriority { NORMAL, THREE_D, MOBILE }

/**
 * Control-frame message sent client → server over the `/pty/{id}` websocket
 * as a JSON Text frame. The only control message today is `resize`. Uses
 * `"type"` as the discriminator (same reasoning as [WindowCommand]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class PtyControl {
    /**
     * Normal resize from the client's layout: updates this client's
     * contribution to the server's size aggregation. Other clients' sizes are
     * left alone.
     *
     * The [priority] tier decides how this vote competes with other clients'
     * (see [SizePriority]): the server arbitrates latest-active-client-wins
     * — a [SizePriority.NORMAL] vote applies when this client is the one the
     * user most recently used (typed on / reformatted), the 3D world sends
     * [SizePriority.THREE_D] to assert a pane's [Pane.grid3d] override over
     * the 2D clients, and a [SizePriority.MOBILE] vote claims the size
     * immediately so the terminal is readable the moment a phone opens it.
     * With no recorded activity the classic tiered min() decides. Old
     * clients omit the field, so it defaults to [SizePriority.NORMAL].
     */
    @Serializable
    @SerialName("resize")
    data class Resize(
        val cols: Int,
        val rows: Int,
        val priority: SizePriority = SizePriority.NORMAL,
    ) : PtyControl()

    /**
     * "Reformat" from the client's UI: force the PTY to this client's
     * cols/rows, evicting every *other* client's size entry from the
     * aggregation and making this client the size governor. The next
     * auto-resize those clients send re-populates their votes, but ambient
     * re-votes no longer steal the size back — governance moves when the
     * user acts on another client (types there, reformats there, or opens
     * the session on a phone).
     */
    @Serializable
    @SerialName("forceResize")
    data class ForceResize(val cols: Int, val rows: Int) : PtyControl()

    /**
     * "Reset terminal" from the pane menu: asks the server to broadcast
     * DECRST sequences that cancel sticky client-side terminal modes
     * (mouse tracking, focus reporting, bracketed paste, application
     * cursor keys, alt screen) to every attached client and stamp them
     * into the replay ring buffer. Escape hatch for terminals wedged by
     * a dead full-screen app — e.g. after a killed-server session
     * restore replays the app's DECSET sequences (issue #91).
     */
    @Serializable
    @SerialName("resetModes")
    object ResetModes : PtyControl()
}

/**
 * Control-frame message sent server → client over the `/pty/{id}` websocket
 * as a JSON Text frame. Lets clients know the authoritative PTY size so they
 * can highlight any grid area that falls outside it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class PtyServerMessage {
    /**
     * Informs the client of the current authoritative PTY grid dimensions.
     * The client uses this to visually indicate which portion of its viewport
     * falls within the active PTY area.
     *
     * @param cols number of columns in the PTY grid
     * @param rows number of rows in the PTY grid
     */
    @Serializable
    @SerialName("size")
    data class Size(val cols: Int, val rows: Int) : PtyServerMessage()
}
