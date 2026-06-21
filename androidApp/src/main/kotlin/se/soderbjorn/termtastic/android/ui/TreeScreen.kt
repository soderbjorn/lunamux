/**
 * Tree overview screen for the Termtastic Android app.
 *
 * Displays the server's window layout as a flat list of tab headers and leaf
 * pane rows, mirroring the sidebar in the web/Electron client. Each leaf is
 * annotated with a type icon (terminal, file browser, git, floating, or empty)
 * and a status indicator (spinner for "working", fading warning triangle for
 * "waiting" — see [StateIndicator]) reflecting the server-pushed session state. Tapping a leaf navigates to [TerminalScreen], [FileBrowserListScreen],
 * or [GitListScreen] depending on the pane type.
 *
 * Tabs the user has hidden from the sidebar on the web client
 * (`TabConfig.isHiddenFromSidebar`) are grouped at the bottom of the list under
 * a "Hidden" headline rather than being interleaved or dropped, keeping their
 * sessions reachable while decluttering the main list.
 *
 * Also hosts the "+" button for creating a new tab. Panes are created from
 * each tab header's menu (see [TabNameDialog], [RenameDialog],
 * [ConfirmCloseDialog] in PaneActions.kt).
 *
 * @see TerminalScreen
 * @see FileBrowserListScreen
 * @see GitListScreen
 * @see PaneActions
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.TabConfig
import se.soderbjorn.termtastic.TerminalContent
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.android.net.NewsUpdatesController
import se.soderbjorn.termtastic.client.addPaneToTab
import se.soderbjorn.termtastic.client.addTab
import se.soderbjorn.termtastic.client.closePane
import se.soderbjorn.termtastic.client.closeTab
import se.soderbjorn.termtastic.client.renamePane
import se.soderbjorn.termtastic.client.renameTab

// Palette tokens live in SidebarPalette.kt — shared with MarkdownListScreen.

/**
 * Sealed hierarchy representing the kinds of rows in the tree list.
 *
 * [SectionHeader] is a standalone divider label (e.g. "Hidden") grouping the
 * tabs below it; [TabHeader] is a section header for a tab; [Leaf] is a
 * clickable pane row.
 */
private sealed class TreeRow {
    /**
     * A standalone group label separating a run of tabs from the ones above
     * it — currently only used for the "Hidden" group that collects tabs the
     * user hid from the sidebar (see [TabConfig.isHiddenFromSidebar]).
     *
     * @property title the uppercase-rendered label, e.g. "Hidden".
     */
    data class SectionHeader(val title: String) : TreeRow()

    /**
     * Section header for a tab, showing the tab title and an aggregate state dot.
     *
     * @property tabId the server-side tab identifier.
     * @property title the display title of the tab.
     * @property aggregateState "working", "waiting", or null -- the worst-case state
     *   across all leaves in this tab.
     */
    data class TabHeader(
        val tabId: String,
        val title: String,
        val aggregateState: String?,
    ) : TreeRow()

    /**
     * A leaf pane row representing a terminal, file browser, git pane, or empty placeholder.
     *
     * @property paneId the server-side pane identifier.
     * @property sessionId the PTY session identifier (used to open [TerminalScreen]).
     * @property title the display title of the pane.
     * @property kind the type of content this pane holds.
     * @property floating true if the pane is in a floating window.
     */
    data class Leaf(
        val paneId: String,
        val sessionId: String,
        val title: String,
        val kind: LeafKind,
        val floating: Boolean,
        /**
         * `true` when this pane is minimized (docked) on the web client.
         * Mobile has no dock, so the row is dimmed to signal the pane is
         * parked. Sourced from [WindowStateRepository.minimizedPaneIds].
         */
        val minimized: Boolean,
    ) : TreeRow()
}

/** Discriminator for the type of content a leaf pane holds. */
private enum class LeafKind { TERMINAL, FILE_BROWSER, GIT, EMPTY }

/**
 * Intermediate data holder used during tree flattening before being
 * converted to [TreeRow.Leaf] rows.
 */
private data class CollectedLeaf(
    val paneId: String,
    val sessionId: String,
    val title: String,
    val kind: LeafKind,
    val floating: Boolean,
    val minimized: Boolean,
)

/**
 * Flattens the hierarchical [WindowConfig] into a linear list of [TreeRow] items
 * suitable for a [LazyColumn].
 *
 * Tabs the user has chosen to hide from the sidebar
 * ([TabConfig.isHiddenFromSidebar]) are pulled out of the normal flow and
 * grouped at the bottom under a single "Hidden" [TreeRow.SectionHeader], so the
 * primary list stays decluttered while the hidden sessions remain reachable —
 * mirroring the web sidebar, which omits them entirely. Tab order within each
 * group is preserved.
 *
 * For each tab, collects all leaves, computes an aggregate state dot
 * (waiting > working > null), and emits a [TreeRow.TabHeader] followed by
 * [TreeRow.Leaf] rows.
 *
 * @param config the current window configuration from the server.
 * @param states map of session ID to state string ("working", "waiting", or null).
 * @param minimizedPaneIds ids of panes minimized on the web client (dimmed here).
 * @return ordered list of tree rows: visible tabs, then (if any) a "Hidden"
 *   header followed by the sidebar-hidden tabs.
 */
private fun flatten(
    config: WindowConfig,
    states: Map<String, String?>,
    minimizedPaneIds: Set<String>,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    val (hiddenTabs, visibleTabs) = config.tabs.partition { it.isHiddenFromSidebar }

    for (tab in visibleTabs) {
        appendTab(tab, states, minimizedPaneIds, rows)
    }
    if (hiddenTabs.isNotEmpty()) {
        rows.add(TreeRow.SectionHeader("Hidden"))
        for (tab in hiddenTabs) {
            appendTab(tab, states, minimizedPaneIds, rows)
        }
    }
    return rows
}

/**
 * Emits a tab's [TreeRow.TabHeader] followed by its [TreeRow.Leaf] rows into
 * [rows], computing the tab's aggregate state dot along the way. Shared by both
 * the visible and hidden passes of [flatten] so the two groups render
 * identically.
 *
 * @param tab the tab to flatten.
 * @param states map of session ID to state string ("working", "waiting", or null).
 * @param minimizedPaneIds ids of panes minimized on the web client.
 * @param rows accumulator the header and leaf rows are appended to.
 */
private fun appendTab(
    tab: TabConfig,
    states: Map<String, String?>,
    minimizedPaneIds: Set<String>,
    rows: MutableList<TreeRow>,
) {
    val leaves = mutableListOf<CollectedLeaf>()
    for (p in tab.panes) {
        addLeaf(p.leaf, floating = false, minimized = p.leaf.id in minimizedPaneIds, out = leaves)
    }

    // "waiting" wins over "working" — matches updateStateIndicators() in WebState.kt.
    var tabState: String? = null
    for (leaf in leaves) {
        when (states[leaf.sessionId]) {
            "waiting" -> { tabState = "waiting"; break }
            "working" -> if (tabState != "working") tabState = "working"
        }
    }

    rows.add(TreeRow.TabHeader(tab.id, tab.title, tabState))
    for (leaf in leaves) {
        rows.add(
            TreeRow.Leaf(
                paneId = leaf.paneId,
                sessionId = leaf.sessionId,
                title = leaf.title,
                kind = leaf.kind,
                floating = leaf.floating,
                minimized = leaf.minimized,
            )
        )
    }
}

/**
 * Converts a single [LeafNode] into a [CollectedLeaf] and appends it to [out].
 *
 * Determines the [LeafKind] from the leaf's content type.
 *
 * @param leaf the leaf node to convert.
 * @param floating whether this leaf is inside a floating window.
 * @param out accumulator list for collected leaves.
 */
private fun addLeaf(
    leaf: LeafNode,
    floating: Boolean,
    minimized: Boolean,
    out: MutableList<CollectedLeaf>,
) {
    val kind = when (leaf.content) {
        is TerminalContent, null -> LeafKind.TERMINAL
        is FileBrowserContent -> LeafKind.FILE_BROWSER
        is GitContent -> LeafKind.GIT
    }
    out.add(
        CollectedLeaf(
            paneId = leaf.id,
            sessionId = leaf.sessionId,
            title = leaf.title,
            kind = kind,
            floating = floating,
            minimized = minimized,
        )
    )
}

/**
 * Tree overview screen showing the server's window layout.
 *
 * Renders a flat list of tab headers and leaf pane rows, each with a type icon
 * and optional pulsing state dot. Tapping a leaf navigates to the appropriate
 * screen based on its [LeafKind]; long-pressing it opens a rename/close menu.
 * Tapping a tab header opens the tab's menu (rename, create panes inside it,
 * close). The "+" button in the top bar creates a new tab.
 *
 * The back/disconnect button tears down the connection via [ConnectionHolder]
 * and returns to [HostsScreen].
 *
 * @param onOpenTerminal callback invoked with a session ID to open a terminal.
 * @param onOpenFileBrowser callback invoked with a pane ID to open the file browser.
 * @param onOpenGit callback invoked with a pane ID to open the git view.
 * @param onDisconnect callback invoked when the user disconnects from the server.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see TerminalScreen
 * @see FileBrowserListScreen
 * @see GitListScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(
    onOpenTerminal: (sessionId: String) -> Unit,
    onOpenFileBrowser: (paneId: String) -> Unit,
    onOpenGit: (paneId: String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        onDisconnect()
        return
    }

    val config by client.windowState.config.collectAsStateWithLifecycle()
    val states by client.windowState.states.collectAsStateWithLifecycle()
    val minimizedPaneIds by client.windowState.minimizedPaneIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Shared news/update checker — drives the toolbar bell's visibility (shown
    // only when there is news or an available update).
    val context = LocalContext.current
    val newsUpdatesVm = remember { NewsUpdatesController.ensureStarted(context) }
    val newsUpdatesState by newsUpdatesVm.stateFlow.collectAsStateWithLifecycle()

    // --- Creation dialog state ---
    var showTabName by remember { mutableStateOf(false) }

    // --- Row action state: rename / close targets picked from row menus ---
    var renamePaneTarget by remember { mutableStateOf<TreeRow.Leaf?>(null) }
    var renameTabTarget by remember { mutableStateOf<TreeRow.TabHeader?>(null) }
    var closePaneTarget by remember { mutableStateOf<TreeRow.Leaf?>(null) }
    var closeTabTarget by remember { mutableStateOf<TreeRow.TabHeader?>(null) }

    if (showTabName) {
        TabNameDialog(
            onDismiss = { showTabName = false },
            onConfirm = { name ->
                showTabName = false
                val socket = ConnectionHolder.windowSocket() ?: return@TabNameDialog
                scope.launch { addTab(client, socket, name) }
            },
        )
    }
    renamePaneTarget?.let { target ->
        RenameDialog(
            title = "Rename window",
            initialValue = target.title,
            allowBlank = true,
            supportingText = "Leave empty to use the working directory",
            onDismiss = { renamePaneTarget = null },
            onConfirm = { name ->
                renamePaneTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                scope.launch { renamePane(socket, target.paneId, name) }
            },
        )
    }
    renameTabTarget?.let { target ->
        RenameDialog(
            title = "Rename tab",
            initialValue = target.title,
            allowBlank = false,
            onDismiss = { renameTabTarget = null },
            onConfirm = { name ->
                renameTabTarget = null
                if (name.isNotBlank()) {
                    val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                    scope.launch { renameTab(socket, target.tabId, name) }
                }
            },
        )
    }
    closePaneTarget?.let { target ->
        ConfirmCloseDialog(
            title = "Close “${target.title}”?",
            text = "The window's session will be ended.",
            confirmLabel = "Close",
            onDismiss = { closePaneTarget = null },
            onConfirm = {
                closePaneTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@ConfirmCloseDialog
                scope.launch { closePane(socket, target.paneId) }
            },
        )
    }
    closeTabTarget?.let { target ->
        ConfirmCloseDialog(
            title = "Close “${target.title}”?",
            text = "All windows in this tab will be closed and their sessions ended.",
            confirmLabel = "Close tab",
            onDismiss = { closeTabTarget = null },
            onConfirm = {
                closeTabTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@ConfirmCloseDialog
                scope.launch { closeTab(socket, target.tabId) }
            },
        )
    }

    // Collapsing large title — expands to a tall "Sessions" header at rest and
    // shrinks to an inline bar as the pane list scrolls, mirroring the iOS
    // session list. Wired to the Scaffold via nestedScroll below.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SidebarBackground,
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { ConnectionHolder.disconnect() }
                        onDisconnect()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Disconnect",
                            tint = SidebarTextSecondary,
                        )
                    }
                },
                title = { Text("Sessions", color = SidebarTextPrimary) },
                actions = {
                    NewsBellButton(
                        onClick = onOpenNews,
                        shouldPulse = newsUpdatesState.hasNews,
                        muted = !newsUpdatesState.hasContent,
                    )
                    IconButton(onClick = { showTabName = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "New tab",
                            tint = SidebarTextPrimary,
                        )
                    }
                },
                // Keep the bar the sidebar colour in both expanded and collapsed
                // states so the title region never flashes the default surface
                // tint as it scrolls.
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SidebarBackground,
                    scrolledContainerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val cfg = config
        if (cfg == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connecting…", color = SidebarTextSecondary)
            }
            return@Scaffold
        }

        val rows = flatten(cfg, states, minimizedPaneIds)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            items(rows, key = { row ->
                when (row) {
                    is TreeRow.SectionHeader -> "section-${row.title}"
                    is TreeRow.TabHeader -> "tab-${row.tabId}"
                    is TreeRow.Leaf -> "leaf-${row.paneId}"
                }
            }) { row ->
                when (row) {
                    is TreeRow.SectionHeader -> SectionHeaderRow(row)
                    is TreeRow.TabHeader -> TabHeaderRow(
                        row = row,
                        closeEnabled = cfg.tabs.size > 1,
                        onRename = { renameTabTarget = row },
                        onAddPane = { kind ->
                            val socket = ConnectionHolder.windowSocket() ?: return@TabHeaderRow
                            val cfgSnapshot = config
                            scope.launch { addPaneToTab(socket, row.tabId, kind, cfgSnapshot) }
                        },
                        onClose = { closeTabTarget = row },
                    )
                    is TreeRow.Leaf -> LeafRow(
                        row = row,
                        state = states[row.sessionId],
                        onClick = {
                            when (row.kind) {
                                LeafKind.TERMINAL -> onOpenTerminal(row.sessionId)
                                LeafKind.FILE_BROWSER -> onOpenFileBrowser(row.paneId)
                                LeafKind.GIT -> onOpenGit(row.paneId)
                                LeafKind.EMPTY -> {} // undecided pane — no action
                            }
                        },
                        onRename = { renamePaneTarget = row },
                        onClose = { closePaneTarget = row },
                    )
                }
            }
        }
    }
}

/**
 * Renders a standalone group label (currently only "Hidden") that separates the
 * sidebar-hidden tabs from the visible ones above them. Non-interactive — it is
 * purely a visual divider with a dim uppercase caption, preceded by a hairline
 * rule so the grouping reads at a glance even when the list is long.
 *
 * @param row the section header data carrying the label text.
 * @see flatten for where the "Hidden" group is assembled.
 */
@Composable
private fun SectionHeaderRow(row: TreeRow.SectionHeader) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = SidebarTextSecondary.copy(alpha = 0.18f),
    )
    Text(
        text = row.title.uppercase(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            color = SidebarTextSecondary.copy(alpha = 0.7f),
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/**
 * Renders a tab section header row with the tab title in uppercase and an
 * aggregate state dot. Long-pressing the row opens the tab's menu with
 * tab-level actions (rename, add panes, close), mirroring the long-press
 * gesture on the pane rows below. A plain tap also opens the menu, since the
 * header has no other action and is the only entry point for adding panes.
 *
 * @param row the tab header data to render.
 * @param closeEnabled false when this is the last tab (the server refuses
 *   to close it, so the menu item is disabled).
 * @param onRename callback invoked when the user picks "Rename".
 * @param onAddPane callback invoked with the wire kind ("terminal",
 *   "fileBrowser", or "git") to add a pane to this tab.
 * @param onClose callback invoked when the user picks "Close tab".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabHeaderRow(
    row: TreeRow.TabHeader,
    closeEnabled: Boolean,
    onRename: () -> Unit,
    onAddPane: (kind: String) -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        // Long-pressing (or tapping) the header opens the tab's menu — the tab
        // is the creation point for panes, so this works for empty tabs too.
        // New panes are placed by the server exactly like the web client's "+"
        // button. The long-press matches the gesture on the pane rows below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { menuOpen = true },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "Tab ${row.title}, opens tab menu"
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Dim section header — pane rows below carry the bright primary
            // color, matching the web sidebar's emphasis hierarchy.
            Text(
                text = row.title.uppercase(),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = SidebarTextSecondary,
                    letterSpacing = 0.5.sp,
                ),
            )
            Spacer(Modifier.width(4.dp))
            // Tab-aggregated status dot (replaces the spinner/alert): idle green,
            // working green-pulse, waiting red-pulse.
            StatusDot(state = row.aggregateState)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = { menuOpen = false; onRename() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New terminal") },
                onClick = { menuOpen = false; onAddPane("terminal") },
            )
            DropdownMenuItem(
                text = { Text("New file browser") },
                onClick = { menuOpen = false; onAddPane("fileBrowser") },
            )
            DropdownMenuItem(
                text = { Text("New git") },
                onClick = { menuOpen = false; onAddPane("git") },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        "Close tab",
                        color = if (closeEnabled) MaterialTheme.colorScheme.error
                        else Color.Unspecified,
                    )
                },
                enabled = closeEnabled,
                onClick = { menuOpen = false; onClose() },
            )
        }
    }
}

/**
 * Renders a clickable leaf pane row with a type icon, spinner, and title.
 * Tapping opens the pane; long-pressing opens a contextual menu with
 * pane-level actions (rename, close), mirroring the iOS context menu.
 * Pane creation lives on the tab header's menu instead.
 *
 * @param row the leaf pane data to render.
 * @param state the current state string ("working", "waiting", or null).
 * @param onClick callback invoked when the row is tapped to open the pane.
 * @param onRename callback invoked when the user picks "Rename".
 * @param onClose callback invoked when the user picks "Close".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeafRow(
    row: TreeRow.Leaf,
    state: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Dim minimized (docked-on-web) panes so the row reads as
                // "parked"; the row stays fully interactive (tap still opens
                // the pane). Mirrors the web sidebar's dimmed minimized row.
                .alpha(if (row.minimized) 0.45f else 1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "${row.title}, ${row.kind.name.lowercase()}"
                }
                .padding(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading: per-row status dot (idle green / working green-pulse /
            // waiting red-pulse), mirroring the web sidebar. The pane-type icon
            // moved to the trailing edge (issue #35 follow-up).
            StatusDot(state = state)
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SidebarTextPrimary,
                ),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            // Trailing: pane-type icon (terminal / git / file browser / …).
            PaneIcon(kind = row.kind, floating = row.floating)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename…") },
                onClick = { menuOpen = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Close", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onClose() },
            )
        }
    }
}

/**
 * 14dp icon mirroring the SVGs in main/web/.../main.kt sidebar:
 *  - terminal: rounded rect with a chevron + line ("terminal prompt")
 *  - markdown: document with folded corner and two text lines
 *  - empty: dashed rounded rect placeholder
 *  - floating: rounded rect with a center line and a small taskbar
 */
@Composable
private fun PaneIcon(kind: LeafKind, floating: Boolean) {
    val tint = SidebarTextSecondary.copy(alpha = if (floating) 0.9f else 0.6f)
    val desc = when {
        kind == LeafKind.FILE_BROWSER -> "File browser pane"
        kind == LeafKind.GIT -> "Git pane"
        kind == LeafKind.EMPTY -> "Undecided pane"
        floating -> "Floating pane"
        else -> "Pane"
    }
    Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = desc }) {
        val w = size.width
        val px = w / 16f
        val stroke = Stroke(
            width = 1.3f * px,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when {
            kind == LeafKind.FILE_BROWSER -> {
                // Document with folded corner and two text lines
                val doc = Path().apply {
                    moveTo(3f * px, 1.75f * px)
                    lineTo(9.5f * px, 1.75f * px)
                    lineTo(13f * px, 5.25f * px)
                    lineTo(13f * px, 14.25f * px)
                    cubicTo(13f * px, 14.53f * px, 12.78f * px, 14.75f * px, 12.5f * px, 14.75f * px)
                    lineTo(3f * px, 14.75f * px)
                    cubicTo(2.72f * px, 14.75f * px, 2.5f * px, 14.53f * px, 2.5f * px, 14.25f * px)
                    lineTo(2.5f * px, 2.25f * px)
                    cubicTo(2.5f * px, 1.97f * px, 2.72f * px, 1.75f * px, 3f * px, 1.75f * px)
                    close()
                }
                drawPath(doc, color = tint, style = stroke)
                // Fold
                val fold = Path().apply {
                    moveTo(9.25f * px, 1.75f * px)
                    lineTo(9.25f * px, 5.25f * px)
                    lineTo(13f * px, 5.25f * px)
                }
                drawPath(fold, color = tint, style = stroke)
                // Text lines
                drawLine(tint, Offset(5f * px, 8.5f * px), Offset(10.5f * px, 8.5f * px), 1.2f * px, StrokeCap.Round)
                drawLine(tint, Offset(5f * px, 11f * px), Offset(10.5f * px, 11f * px), 1.2f * px, StrokeCap.Round)
            }
            kind == LeafKind.GIT -> {
                // Git branch icon: vertical line with two branches and three circles
                val r = 1.5f * px
                // Main vertical line
                drawLine(tint, Offset(8f * px, 3f * px), Offset(8f * px, 13f * px), 1.3f * px, StrokeCap.Round)
                // Branch line going top-right
                val branch1 = Path().apply {
                    moveTo(8f * px, 5f * px)
                    lineTo(11f * px, 3f * px)
                }
                drawPath(branch1, color = tint, style = stroke)
                // Branch line going bottom-right
                val branch2 = Path().apply {
                    moveTo(8f * px, 10f * px)
                    lineTo(11f * px, 12f * px)
                }
                drawPath(branch2, color = tint, style = stroke)
                // Circle at top of main line
                drawCircle(tint, radius = r, center = Offset(8f * px, 3f * px), style = stroke)
                // Circle at branch endpoint top-right
                drawCircle(tint, radius = r, center = Offset(11f * px, 3f * px), style = stroke)
                // Circle at bottom of main line
                drawCircle(tint, radius = r, center = Offset(8f * px, 13f * px), style = stroke)
            }
            kind == LeafKind.EMPTY -> {
                // Dashed rounded rect placeholder
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(2f * px, 2f * px),
                    size = Size(12f * px, 12f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = Stroke(
                        width = 1.3f * px,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(2.5f * px, 2f * px), 0f,
                        ),
                    ),
                )
            }
            floating -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(2f * px, 1f * px),
                    size = Size(12f * px, 10f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = stroke,
                )
                drawLine(tint, Offset(4f * px, 8f * px), Offset(10f * px, 8f * px), 1.2f * px, StrokeCap.Round)
                drawRoundRect(
                    color = tint.copy(alpha = tint.alpha * 0.5f),
                    topLeft = Offset(4f * px, 13f * px),
                    size = Size(8f * px, 1.2f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.6f * px, 0.6f * px),
                )
            }
            else -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(1f * px, 2f * px),
                    size = Size(14f * px, 12f * px),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * px, 1.5f * px),
                    style = stroke,
                )
                val chevron = Path().apply {
                    moveTo(4f * px, 7f * px)
                    lineTo(6f * px, 5f * px)
                    lineTo(4f * px, 3f * px)
                }
                drawPath(chevron, color = tint, style = stroke)
                drawLine(tint, Offset(7f * px, 7f * px), Offset(11f * px, 7f * px), 1.2f * px, StrokeCap.Round)
            }
        }
    }
}
