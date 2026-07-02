/**
 * Tree overview screen for the Termtastic Android app.
 *
 * Displays the server's window layout as a flat list of tab headers and leaf
 * pane rows, mirroring the sidebar in the web/Electron client. Each leaf is
 * annotated with a type icon (terminal, file browser, git, floating, or empty)
 * and a status indicator (a foreground-coloured dot that breathes for
 * "working" and becomes a pulsing warning triangle for "waiting" — see
 * [StatusDot]) reflecting the server-pushed session state. Tapping a leaf
 * navigates to [TerminalScreen], [FileBrowserListScreen],
 * or [GitListScreen] depending on the pane type.
 *
 * Tabs the user has hidden from the sidebar on the web client
 * (`TabConfig.isHiddenFromSidebar`) are excluded from the list by default
 * (issue #52); a "Show hidden tabs" link reveals them at the bottom under a
 * small "Hidden" headline, and a "Hide hidden tabs" link re-hides them. The
 * reveal state is held in memory by [SessionsViewModeStore.showHiddenTabs] so it
 * survives navigation but resets on app restart.
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
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
import se.soderbjorn.termtastic.client.WindowLayoutState
import se.soderbjorn.termtastic.client.addPaneToTab
import se.soderbjorn.termtastic.client.addTab
import se.soderbjorn.termtastic.client.closePane
import se.soderbjorn.termtastic.client.closeTab
import se.soderbjorn.termtastic.client.renamePane
import se.soderbjorn.termtastic.client.renameTab
import se.soderbjorn.termtastic.client.setTabHidden
import se.soderbjorn.termtastic.client.setTabHiddenFromSidebar
import se.soderbjorn.termtastic.client.viewmodel.HttpSettingsPersister
import se.soderbjorn.termtastic.client.viewmodel.OverviewBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SessionsViewModeStore
import se.soderbjorn.termtastic.client.viewmodel.ThemeBackingViewModel
import se.soderbjorn.darkness.web.layout.LayoutPreset

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
     * A tappable affordance that reveals or re-hides the sidebar-hidden tabs
     * (issue #52). Rendered only when the layout actually contains hidden tabs;
     * tapping it flips [SessionsViewModeStore.showHiddenTabs].
     *
     * @property showing `true` when the hidden tabs are currently revealed (the
     *   row reads "Hide hidden tabs"); `false` when they are omitted (the row
     *   reads "Show hidden tabs").
     */
    data class HiddenToggle(val showing: Boolean) : TreeRow()

    /**
     * Section header for a tab, showing the tab title and an aggregate state dot.
     *
     * @property tabId the server-side tab identifier.
     * @property title the display title of the tab.
     * @property aggregateState "working", "waiting", or null -- the worst-case state
     *   across all leaves in this tab.
     * @property isHidden whether the tab is hidden ("unlisted") from the tab
     *   strip ([se.soderbjorn.termtastic.TabConfig.isHidden]). Labels the tab
     *   menu's hide/show-in-tab-bar item.
     * @property isHiddenFromSidebar whether the tab is hidden from the sidebar
     *   tab tree — and therefore parked under this list's "Hidden" reveal
     *   (issue #52). Orthogonal to [isHidden]; labels the tab menu's
     *   hide/show-in-sidebar item.
     */
    data class TabHeader(
        val tabId: String,
        val title: String,
        val aggregateState: String?,
        val isHidden: Boolean,
        val isHiddenFromSidebar: Boolean,
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
internal enum class LeafKind { TERMINAL, FILE_BROWSER, GIT, EMPTY }

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
 * ([TabConfig.isHiddenFromSidebar]) are excluded from the list by default
 * (issue #52): when any exist, a single [TreeRow.HiddenToggle] "Show hidden
 * tabs" affordance is appended instead, keeping the primary list decluttered
 * like the web sidebar (which omits them entirely). When [showHiddenTabs] is
 * `true` the hidden tabs are revealed at the bottom under a "Hidden"
 * [TreeRow.SectionHeader], and the affordance flips to "Hide hidden tabs". Tab
 * order within each group is preserved.
 *
 * For each tab, collects all leaves, computes an aggregate state dot
 * (waiting > working > null), and emits a [TreeRow.TabHeader] followed by
 * [TreeRow.Leaf] rows.
 *
 * @param config the current window configuration from the server.
 * @param states map of session ID to state string ("working", "waiting", or null).
 * @param minimizedPaneIds ids of panes minimized on the web client (dimmed here).
 * @param showHiddenTabs whether the sidebar-hidden tabs should currently be
 *   revealed (issue #52); when `false` they are omitted and only the toggle row
 *   appears.
 * @return ordered list of tree rows: visible tabs, then (if any hidden tabs
 *   exist) a [TreeRow.HiddenToggle] and, when revealed, a "Hidden" header
 *   followed by the sidebar-hidden tabs.
 */
private fun flatten(
    config: WindowConfig,
    states: Map<String, String?>,
    minimizedPaneIds: Set<String>,
    showHiddenTabs: Boolean,
): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    val (hiddenTabs, visibleTabs) = config.tabs.partition { it.isHiddenFromSidebar }

    for (tab in visibleTabs) {
        appendTab(tab, states, minimizedPaneIds, rows)
    }
    if (hiddenTabs.isNotEmpty()) {
        rows.add(TreeRow.HiddenToggle(showing = showHiddenTabs))
        if (showHiddenTabs) {
            rows.add(TreeRow.SectionHeader("Hidden"))
            for (tab in hiddenTabs) {
                appendTab(tab, states, minimizedPaneIds, rows)
            }
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

    rows.add(
        TreeRow.TabHeader(
            tabId = tab.id,
            title = tab.title,
            aggregateState = tabState,
            isHidden = tab.isHidden,
            isHiddenFromSidebar = tab.isHiddenFromSidebar,
        )
    )
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
 * @param themeVm the shared appearance/theme view-model (null until a
 *   connection exists); drives the toolbar's appearance/theme picker. Edits made
 *   through it write the canonical server selection and repaint the app live.
 * @param onOpenTerminal callback invoked with a session ID to open a terminal.
 * @param onOpenFileBrowser callback invoked with a pane ID to open the file browser.
 * @param onOpenGit callback invoked with a pane ID to open the git view.
 * @param onDisconnect callback invoked when the user disconnects from the server.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see TerminalScreen
 * @see FileBrowserListScreen
 * @see GitListScreen
 * @see AppearanceSheet
 */
/**
 * Smallest device width (dp) at or above which the Sessions top bar still has
 * room to show its "Sessions" title alongside the full action cluster.
 *
 * The Sessions bar carries many actions (add, layout, view toggle, appearance,
 * news, and the shared [AboutMenu]); on narrow phones the Material top bar
 * sacrifices the title first, crushing it to an unreadable sliver. Below this
 * threshold we drop the title entirely (the back arrow plus the tab chips still
 * convey context) so the icons get the width; at or above it the title fills
 * what would otherwise read as awkward empty space between the back arrow and
 * the icons.
 *
 * Gated on `smallestScreenWidthDp` (a stable per-device value) rather than the
 * current width so the title never flickers in and out as the phone rotates.
 *
 * @see AboutMenu
 */
private const val SESSIONS_TITLE_MIN_WIDTH_DP = 440

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(
    themeVm: ThemeBackingViewModel?,
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

    // Shared overview model — hoisted here so the overview content *and* the
    // toolbar's "New window" / "Layout" actions drive the same instance
    // (issue #58). Null until the window socket exists. Owns all the geometry
    // logic (move/resize/maximize/minimize/layout) in commonMain so iOS can
    // render the same model later.
    val windowSocket = ConnectionHolder.windowSocket()
    val overviewVm = remember(client, windowSocket) {
        windowSocket?.let { sock ->
            OverviewBackingViewModel(
                windowSocket = sock,
                geometryByTab = client.windowState.geometryByTab,
                rawLayoutState = client.windowState.rawLayoutState,
                settingsPersister = HttpSettingsPersister(client),
            )
        }
    }
    LaunchedEffect(overviewVm) { overviewVm?.run() }

    // The active (non-hidden) tab and its on-canvas pane count drive the
    // overview toolbar actions (add pane → this tab; layout miniatures →
    // this pane count). Derived from the already-collected config + minimized
    // set so we don't need a second state subscription.
    val overviewActiveTabId = config?.let { cfg ->
        cfg.activeTabId?.takeIf { id -> cfg.tabs.any { it.id == id && !it.isHidden } }
            ?: cfg.tabs.firstOrNull { !it.isHidden }?.id
    }
    val overviewActivePaneCount = config?.tabs
        ?.firstOrNull { it.id == overviewActiveTabId }
        ?.panes?.count { it.leaf.id !in minimizedPaneIds } ?: 0

    // The active tab's persisted layout preset, read from the toolkit-owned
    // `LAYOUT_STATE` blob (`presetByTab`). Drives the encircled "current layout"
    // marker in the layout sheet so the user can see which preset is live —
    // in practice that's [LayoutPreset.Auto], the only preset the toolkit keeps
    // re-applying; one-shot presets relax to "custom" on the next manual edit
    // (issue #59). Null when no preset is recorded for the tab.
    val rawLayout by client.windowState.rawLayoutState.collectAsStateWithLifecycle()
    val overviewActivePreset = overviewActiveTabId?.let { tabId ->
        WindowLayoutState.parse(rawLayout).presetByTab[tabId]?.let { LayoutPreset.fromKey(it) }
    }

    // Overview toolbar UI state.
    var showNewWindowMenu by remember { mutableStateOf(false) }
    var showLayoutSheet by remember { mutableStateOf(false) }
    var showAppearanceSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Shared news/update checker — drives the toolbar bell's visibility (shown
    // only when there is news or an available update).
    val context = LocalContext.current
    val newsUpdatesVm = remember { NewsUpdatesController.ensureStarted(context) }
    val newsUpdatesState by newsUpdatesVm.stateFlow.collectAsStateWithLifecycle()

    // --- View mode: flat session list vs. the miniaturised overview. Seeded
    // from (and written back to) the process-wide [SessionsViewModeStore] so the
    // choice survives navigating away from the Sessions screen and back — and
    // even survives this composable leaving composition entirely — while still
    // resetting to the default on the next app launch (issue #54). The local
    // mutableStateOf still drives recomposition; the store is just the durable
    // backing value behind it.
    var viewMode by remember {
        mutableStateOf(
            if (SessionsViewModeStore.overviewMode) SessionsViewMode.OVERVIEW
            else SessionsViewMode.LIST
        )
    }

    // --- Hidden tabs: whether the sidebar-hidden tabs are currently revealed.
    // Seeded from (and written back to) the same process-wide
    // [SessionsViewModeStore] as [viewMode], so the choice survives navigating
    // away from the Sessions screen and back while resetting to the default
    // (hidden) on the next app launch (issue #52). Memory-only — never persisted
    // to disk.
    var showHiddenTabs by remember {
        mutableStateOf(SessionsViewModeStore.showHiddenTabs)
    }

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

    if (showLayoutSheet) {
        LayoutSheet(
            paneCount = overviewActivePaneCount,
            activePreset = overviewActivePreset,
            onSelect = { preset ->
                showLayoutSheet = false
                val tabId = overviewActiveTabId ?: return@LayoutSheet
                scope.launch { overviewVm?.applyLayout(tabId, preset) }
            },
            onDismiss = { showLayoutSheet = false },
        )
    }

    if (showAppearanceSheet && themeVm != null) {
        AppearanceSheet(
            vm = themeVm,
            onDismiss = { showAppearanceSheet = false },
        )
    }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            // Compact (non-collapsing) bar to save vertical space — important
            // in landscape and for the overview's exposé. The List/Overview
            // switcher lives in the title slot instead of taking its own row.
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { ConnectionHolder.disconnect() }
                        onDisconnect()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Disconnect",
                            // Same tint as every other bar action — mixed
                            // per-icon tints read as a bug on light themes
                            // (issue #96).
                            tint = SidebarTextPrimary,
                        )
                    }
                },
                title = {
                    // Drop the title on narrow phones so the action-heavy bar
                    // doesn't crush it; keep it on wide screens where it also
                    // fills the gap the back arrow would otherwise leave.
                    if (LocalConfiguration.current.smallestScreenWidthDp >=
                        SESSIONS_TITLE_MIN_WIDTH_DP
                    ) {
                        Text("Sessions", color = SidebarTextPrimary)
                    }
                },
                actions = {
                    // Combined "+" menu: always offers "New tab"; in overview
                    // mode it also offers adding a pane to the current tab
                    // (issue #58). Replaces the former separate "New window"
                    // button so the toolbar carries a single add affordance.
                    // Placed first so it sits leftmost in the action cluster.
                    Box {
                        IconButton(onClick = { showNewWindowMenu = true }) {
                            PlusIcon(
                                contentDescription = "Add",
                                tint = SidebarTextPrimary,
                            )
                        }
                        DropdownMenu(
                            expanded = showNewWindowMenu,
                            onDismissRequest = { showNewWindowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("New tab") },
                                onClick = {
                                    showNewWindowMenu = false
                                    showTabName = true
                                },
                            )
                            if (viewMode == SessionsViewMode.OVERVIEW) {
                                DropdownMenuItem(
                                    text = { Text("New terminal") },
                                    onClick = {
                                        showNewWindowMenu = false
                                        val tabId = overviewActiveTabId ?: return@DropdownMenuItem
                                        scope.launch { overviewVm?.addPane(tabId, "terminal") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("New file browser") },
                                    onClick = {
                                        showNewWindowMenu = false
                                        val tabId = overviewActiveTabId ?: return@DropdownMenuItem
                                        scope.launch { overviewVm?.addPane(tabId, "fileBrowser") }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("New git") },
                                    onClick = {
                                        showNewWindowMenu = false
                                        val tabId = overviewActiveTabId ?: return@DropdownMenuItem
                                        scope.launch { overviewVm?.addPane(tabId, "git") }
                                    },
                                )
                            }
                        }
                    }
                    // Overview-only layout preset picker (issue #58). Hidden in
                    // list mode where it has no spatial meaning. Adding panes is
                    // folded into the combined "+" menu above.
                    if (viewMode == SessionsViewMode.OVERVIEW) {
                        IconButton(onClick = { showLayoutSheet = true }) {
                            LayoutGridIcon(
                                contentDescription = "Layout",
                                tint = SidebarTextPrimary,
                            )
                        }
                    }
                    // Single toggle between the list and the overview. The icon
                    // shows the mode you'll switch *to*; the glyph swap alone
                    // signals the mode — no accent tint on the active state, so
                    // the bar keeps a single icon colour (issue #96).
                    IconButton(onClick = {
                        viewMode = when (viewMode) {
                            SessionsViewMode.LIST -> SessionsViewMode.OVERVIEW
                            SessionsViewMode.OVERVIEW -> SessionsViewMode.LIST
                        }
                        // Persist the new mode in memory so it survives leaving
                        // and returning to this screen (issue #54).
                        SessionsViewModeStore.overviewMode = viewMode == SessionsViewMode.OVERVIEW
                    }) {
                        when (viewMode) {
                            SessionsViewMode.LIST -> Icon(
                                Icons.Filled.GridView,
                                contentDescription = "Switch to overview",
                                tint = SidebarTextPrimary,
                            )
                            SessionsViewMode.OVERVIEW -> Icon(
                                Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = "Switch to list",
                                tint = SidebarTextPrimary,
                            )
                        }
                    }
                    // Appearance + theme picker (parity with the Mac/Electron
                    // app's appearance toggle + theme manager). Available in both
                    // list and overview modes; disabled until the theme model is
                    // ready. Writes the same canonical server selection the
                    // desktop does, so the choice syncs across every client.
                    if (themeVm != null) {
                        IconButton(onClick = { showAppearanceSheet = true }) {
                            Icon(
                                Icons.Outlined.Palette,
                                contentDescription = "Appearance & theme",
                                tint = SidebarTextPrimary,
                            )
                        }
                    }
                    NewsBellButton(
                        onClick = onOpenNews,
                        shouldPulse = newsUpdatesState.hasNews,
                        muted = !newsUpdatesState.hasContent,
                    )
                    // Shared info menu → support forum, website, legal pages.
                    // Same control as the Hosts top bar so both primary screens
                    // expose the same links from the same place.
                    AboutMenu()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val cfg = config
            if (cfg == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Connecting…", color = SidebarTextSecondary)
                }
                return@Column
            }

            when (viewMode) {
                SessionsViewMode.OVERVIEW -> if (overviewVm == null) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Connecting…", color = SidebarTextSecondary)
                    }
                } else {
                    OverviewContent(
                        vm = overviewVm,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onOpenTerminal = onOpenTerminal,
                        onOpenFileBrowser = onOpenFileBrowser,
                        onOpenGit = onOpenGit,
                    )
                }
                SessionsViewMode.LIST -> {
                    val rows = flatten(cfg, states, minimizedPaneIds, showHiddenTabs)
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        items(rows, key = { row ->
                            when (row) {
                                is TreeRow.SectionHeader -> "section-${row.title}"
                                is TreeRow.HiddenToggle -> "hidden-toggle"
                                is TreeRow.TabHeader -> "tab-${row.tabId}"
                                is TreeRow.Leaf -> "leaf-${row.paneId}"
                            }
                        }) { row ->
                            when (row) {
                                is TreeRow.SectionHeader -> SectionHeaderRow(row)
                                is TreeRow.HiddenToggle -> HiddenToggleRow(
                                    row = row,
                                    onClick = {
                                        showHiddenTabs = !showHiddenTabs
                                        // Persist in memory so it survives
                                        // leaving and returning (issue #52).
                                        SessionsViewModeStore.showHiddenTabs = showHiddenTabs
                                    },
                                )
                                is TreeRow.TabHeader -> TabHeaderRow(
                                    row = row,
                                    closeEnabled = cfg.tabs.size > 1,
                                    onRename = { renameTabTarget = row },
                                    onToggleHidden = {
                                        val socket = ConnectionHolder.windowSocket() ?: return@TabHeaderRow
                                        scope.launch { setTabHidden(socket, row.tabId, !row.isHidden) }
                                    },
                                    onToggleSidebarHidden = {
                                        val socket = ConnectionHolder.windowSocket() ?: return@TabHeaderRow
                                        scope.launch {
                                            setTabHiddenFromSidebar(socket, row.tabId, !row.isHiddenFromSidebar)
                                        }
                                    },
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
        }
    }
}

/**
 * The two presentations of the Sessions screen.
 *
 * @property LIST     the flat, scrollable tab/pane tree (the original view).
 * @property OVERVIEW the miniaturised tabs-and-panes replica (issue #42).
 */
enum class SessionsViewMode { LIST, OVERVIEW }

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
 * Renders the tappable "Show hidden tabs" / "Hide hidden tabs" link that toggles
 * whether the sidebar-hidden tabs are revealed in the list (issue #52). Styled
 * as an accent-coloured link to read as an action distinct from the dim,
 * non-interactive "Hidden" [SectionHeaderRow]. Preceded by a hairline rule so it
 * visually separates from the visible tabs above.
 *
 * @param row the toggle row data; its [TreeRow.HiddenToggle.showing] flag picks
 *   the label and reflects whether the hidden tabs are currently revealed.
 * @param onClick invoked when the user taps the link; flips the in-memory
 *   show/hide state.
 * @see flatten for where this row is emitted.
 */
@Composable
private fun HiddenToggleRow(row: TreeRow.HiddenToggle, onClick: () -> Unit) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = SidebarTextSecondary.copy(alpha = 0.18f),
    )
    Text(
        text = if (row.showing) "Hide hidden tabs" else "Show hidden tabs",
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (row.showing) "Hide hidden tabs" else "Show hidden tabs",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        style = MaterialTheme.typography.labelMedium.copy(
            color = SidebarAccent,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

/**
 * Renders a tab section header row with the tab title in uppercase and an
 * aggregate state dot. Long-pressing the row opens the tab's menu with
 * tab-level actions (rename, listing toggles, add panes, close), mirroring the
 * long-press gesture on the pane rows below. A plain tap also opens the menu, since the
 * header has no other action and is the only entry point for adding panes.
 *
 * @param row the tab header data to render.
 * @param closeEnabled false when this is the last tab (the server refuses
 *   to close it, so the menu item is disabled).
 * @param onRename callback invoked when the user picks "Rename".
 * @param onToggleHidden callback invoked when the user picks the tab-strip
 *   listing toggle ("Hide in tab bar" / "Show in tab bar").
 * @param onToggleSidebarHidden callback invoked when the user picks the
 *   sidebar listing toggle ("Hide in side bar" / "Show in side bar").
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
    onToggleHidden: () -> Unit,
    onToggleSidebarHidden: () -> Unit,
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
            // Tab-aggregated status indicator (issue #38), painted in the theme
            // foreground colour: idle = solid dot, working = breathing dot,
            // waiting = pulsing warning triangle.
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
            // Listing toggles: the tab-strip flag and the sidebar flag are
            // orthogonal, so each gets its own item labelled by its current
            // state. The wording mirrors the web/Electron overflow menu and
            // the overview tab chip's context menu.
            DropdownMenuItem(
                text = { Text(if (row.isHidden) "Show in tab bar" else "Hide in tab bar") },
                onClick = { menuOpen = false; onToggleHidden() },
            )
            DropdownMenuItem(
                text = {
                    Text(if (row.isHiddenFromSidebar) "Show in side bar" else "Hide in side bar")
                },
                onClick = { menuOpen = false; onToggleSidebarHidden() },
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
            // Leading: pane-type icon (terminal / git / file browser / …). The
            // status dot and the pane-type icon swapped places (issue #43), so
            // each row now reads [pane icon] [title] [status].
            PaneIcon(kind = row.kind, floating = row.floating)
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
            // Trailing: per-row status dot — working = breathing dot, waiting =
            // pulsing warning triangle; idle renders nothing (issue #43).
            StatusDot(state = state)
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
 * Pane-type icon mirroring the SVGs in main/web/.../main.kt sidebar:
 *  - terminal: rounded rect with a chevron + line ("terminal prompt")
 *  - markdown: document with folded corner and two text lines
 *  - empty: dashed rounded rect placeholder
 *  - floating: rounded rect with a center line and a small taskbar
 *
 * Used by [LeafRow] in the session list and by the overview pane thumbnails
 * ([MiniPane]) at a smaller [sizeDp] (issue #43).
 *
 * @param kind the pane content kind selecting which glyph to draw.
 * @param floating whether the pane lives in a floating window (brighter tint,
 *   distinct floating glyph).
 * @param sizeDp the square canvas size in dp; the drawing scales to fit so any
 *   size renders cleanly. Defaults to 16dp (the sidebar row size).
 */
@Composable
internal fun PaneIcon(kind: LeafKind, floating: Boolean, sizeDp: Int = 16) {
    val tint = SidebarTextSecondary.copy(alpha = if (floating) 0.9f else 0.6f)
    val desc = when {
        kind == LeafKind.FILE_BROWSER -> "File browser pane"
        kind == LeafKind.GIT -> "Git pane"
        kind == LeafKind.EMPTY -> "Undecided pane"
        floating -> "Floating pane"
        else -> "Pane"
    }
    Canvas(modifier = Modifier.size(sizeDp.dp).semantics { contentDescription = desc }) {
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
