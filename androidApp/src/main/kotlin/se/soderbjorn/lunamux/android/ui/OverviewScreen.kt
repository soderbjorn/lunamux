/**
 * Overview mode content for the Lunamux Android app.
 *
 * Renders a miniaturised, *interactive* replica of the web/Electron tabs-and-
 * panes experience (issues #42, #58): a "scaled exposé" of the active tab's
 * pane layout in the content area, with a horizontally-scrollable strip of tab
 * chips above it. Selecting a tab activates it server-side; tapping any pane
 * focuses it and drills into that pane's full-screen route.
 *
 * Window management (issue #58):
 *  - Tapping a pane also makes it the tab's active/focused pane.
 *  - Long-pressing a pane (or a dock chip) opens a **context menu** anchored to
 *    it with Open / Maximize / Restore / Minimize / Move or resize / Rename /
 *    Close.
 *  - "Move or resize" enters a single **edit-layout mode** where *every* pane in
 *    the tab can be freely dragged to move and resized by its bottom-right
 *    handle; a banner offers Done (and Back / tapping empty space exits).
 *  - Minimized panes leave the canvas and appear in a bottom dock strip; tapping
 *    a dock chip restores the pane.
 *
 * All decisions (geometry maths, LAYOUT_STATE authoring, the edit/drag state)
 * live in the shared [OverviewBackingViewModel] so iOS can render the same
 * model; this file is the Compose front-end.
 *
 * @see TreeScreen
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
 */
package se.soderbjorn.lunamux.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.AgentContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.client.closeTab
import se.soderbjorn.lunamux.client.renamePane
import se.soderbjorn.lunamux.client.renameTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.DockedPane
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.Drag
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewPane
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.UnlistedTab

/**
 * The overview content: tab strip + exposé canvas (+ dock) + window-management
 * affordances.
 *
 * @param vm                the shared overview model (hoisted from [TreeScreen]
 *   so the toolbar's New window / Layout actions share it).
 * @param onOpenTerminal    drill-in callback for a terminal pane (by session id).
 * @param onOpenFileBrowser drill-in callback for a file-browser pane (by pane id).
 * @param onOpenGit         drill-in callback for a git pane (by pane id).
 * @param modifier          layout modifier from [TreeScreen].
 */
@Composable
fun OverviewContent(
    vm: OverviewBackingViewModel,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val client = ConnectionHolder.client()
    if (client == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Disconnected", color = SidebarTextSecondary)
        }
        return
    }

    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val editTabId by vm.editTabId.collectAsStateWithLifecycle()
    val drag by vm.drag.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val miniTerminals = remember(client) { MiniTerminalRegistry(client, scope) }
    DisposableEffect(miniTerminals) {
        onDispose { miniTerminals.close() }
    }

    // Rename / close dialog targets raised from a pane's context menu.
    var renameTarget by remember { mutableStateOf<LeafNode?>(null) }
    var closeTarget by remember { mutableStateOf<LeafNode?>(null) }
    // Rename / close dialog targets raised from a tab chip's context menu.
    var renameTabTarget by remember { mutableStateOf<OverviewTab?>(null) }
    var closeTabTarget by remember { mutableStateOf<OverviewTab?>(null) }

    val tabs = state.tabs
    val activeIndex = tabs.indexOfFirst { it.isActive }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = activeIndex, pageCount = { tabs.size })

    LaunchedEffect(activeIndex) {
        if (activeIndex in tabs.indices && activeIndex != pagerState.currentPage) {
            pagerState.animateScrollToPage(activeIndex)
        }
    }
    val latestTabs = rememberUpdatedState(tabs)
    val latestActive = rememberUpdatedState(activeIndex)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val t = latestTabs.value
            if (page in t.indices && page != latestActive.value) {
                vm.setActiveTab(t[page].id)
            }
        }
    }

    // While editing layout, Back leaves edit mode rather than the screen.
    BackHandler(enabled = editTabId != null) { vm.exitEdit() }

    CompositionLocalProvider(LocalMiniTerminalRegistry provides miniTerminals) {
        Column(modifier) {
            // Hide the tab strip while editing layout: the move/resize gestures
            // own the screen, and the strip's chips would compete for taps.
            if (editTabId == null) {
                OverviewTabStrip(
                    tabs = tabs,
                    unlistedTabs = state.unlistedTabs,
                    activeIndex = activeIndex,
                    closeEnabled = tabs.size > 1,
                    onSelect = { id -> scope.launch { vm.setActiveTab(id) } },
                    onRename = { tab -> renameTabTarget = tab },
                    onToggleHidden = { tab ->
                        scope.launch { vm.setTabHidden(tab.id, !tab.isHidden) }
                    },
                    onToggleSidebarHidden = { tab ->
                        scope.launch {
                            vm.setTabHiddenFromSidebar(tab.id, !tab.isHiddenFromSidebar)
                        }
                    },
                    onClose = { tab -> closeTabTarget = tab },
                )
            } else {
                // Edit-mode banner: names the mode and offers an unambiguous exit.
                EditBanner(onDone = { vm.exitEdit() })
            }

            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No tabs", color = SidebarTextSecondary)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    // Don't steal horizontal drags from an edit-mode gesture.
                    userScrollEnabled = editTabId == null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    val tab = tabs[page]
                    ExposeCanvas(
                        tab = tab,
                        editing = editTabId == tab.id,
                        drag = drag?.takeIf { it.tabId == tab.id },
                        onOpenPane = { pane ->
                            scope.launch { vm.focusPane(tab.id, pane.leaf.id) }
                            openPane(pane.leaf, onOpenTerminal, onOpenFileBrowser, onOpenGit)
                        },
                        onToggleMaximize = { pane -> scope.launch { vm.toggleMaximize(tab.id, pane.leaf.id) } },
                        onMinimize = { pane -> scope.launch { vm.minimize(tab.id, pane.leaf.id) } },
                        onEnterEdit = { vm.enterEdit(tab.id) },
                        onRename = { leaf -> renameTarget = leaf },
                        onClose = { leaf -> closeTarget = leaf },
                        onBeginDrag = { pane -> vm.beginDrag(tab.id, pane.leaf.id) },
                        onDragMove = { dx, dy -> vm.dragMoveBy(dx, dy) },
                        onDragResize = { dw, dh -> vm.dragResizeBy(dw, dh) },
                        onDragEnd = { scope.launch { vm.endDrag() } },
                        onRestoreDock = { docked -> scope.launch { vm.restore(tab.id, docked.leaf.id) } },
                    )
                }
            }
        }
    }

    renameTarget?.let { leaf ->
        RenameDialog(
            title = "Rename window",
            initialValue = leaf.title,
            allowBlank = true,
            supportingText = "Leave empty to use the working directory",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                scope.launch { renamePane(socket, leaf.id, name) }
            },
        )
    }
    closeTarget?.let { leaf ->
        ConfirmCloseDialog(
            title = "Close “${leaf.title}”?",
            text = "The window's session will be ended.",
            confirmLabel = "Close",
            onDismiss = { closeTarget = null },
            onConfirm = {
                closeTarget = null
                scope.launch { vm.closePane(leaf.id) }
            },
        )
    }
    renameTabTarget?.let { tab ->
        RenameDialog(
            title = "Rename tab",
            initialValue = tab.title,
            allowBlank = false,
            onDismiss = { renameTabTarget = null },
            onConfirm = { name ->
                renameTabTarget = null
                if (name.isNotBlank()) {
                    val socket = ConnectionHolder.windowSocket() ?: return@RenameDialog
                    scope.launch { renameTab(socket, tab.id, name) }
                }
            },
        )
    }
    closeTabTarget?.let { tab ->
        ConfirmCloseDialog(
            title = "Close “${tab.title}”?",
            text = "All windows in this tab will be closed and their sessions ended.",
            confirmLabel = "Close tab",
            onDismiss = { closeTabTarget = null },
            onConfirm = {
                closeTabTarget = null
                val socket = ConnectionHolder.windowSocket() ?: return@ConfirmCloseDialog
                scope.launch { closeTab(socket, tab.id) }
            },
        )
    }
}

/** Route a pane open to the right full-screen drill-in. */
private fun openPane(
    leaf: LeafNode,
    onOpenTerminal: (String) -> Unit,
    onOpenFileBrowser: (String) -> Unit,
    onOpenGit: (String) -> Unit,
) {
    when (leaf.content) {
        is FileBrowserContent -> onOpenFileBrowser(leaf.id)
        is GitContent -> onOpenGit(leaf.id)
        // Agent consoles open the terminal screen bound to their session id:
        // the server mirrors both agent render modes into the /pty byte
        // stream (transcript mode with a cooked input line, screen mode as
        // the full grid), so the existing terminal screen is the renderer.
        is AgentContent -> onOpenTerminal(leaf.sessionId)
        else -> onOpenTerminal(leaf.sessionId)
    }
}

/**
 * The exposé canvas for one tab: lays out on-canvas panes by their geometry (or
 * the live edit-mode drag), hosts each pane's context menu, and shows the dock
 * strip beneath when the tab has minimized panes.
 *
 * @param tab              the tab to render.
 * @param editing          whether this tab is in edit-layout mode.
 * @param drag             the live drag iff it targets a pane in this tab.
 * @param onOpenPane       focus + drill into a pane.
 * @param onToggleMaximize maximize/restore a pane.
 * @param onMinimize       dock a pane.
 * @param onEnterEdit      enter edit-layout mode.
 * @param onRename         open the rename dialog for a pane.
 * @param onClose          confirm + close a pane.
 * @param onBeginDrag      start a move/resize drag on a pane.
 * @param onDragMove       forward a move drag delta (tab fractions).
 * @param onDragResize     forward a resize drag delta (tab fractions).
 * @param onDragEnd        commit the in-flight drag.
 * @param onRestoreDock    restore a docked pane.
 */
@Composable
private fun ExposeCanvas(
    tab: OverviewTab,
    editing: Boolean,
    drag: Drag?,
    onOpenPane: (OverviewPane) -> Unit,
    onToggleMaximize: (OverviewPane) -> Unit,
    onMinimize: (OverviewPane) -> Unit,
    onEnterEdit: () -> Unit,
    onRename: (LeafNode) -> Unit,
    onClose: (LeafNode) -> Unit,
    onBeginDrag: (OverviewPane) -> Unit,
    onDragMove: (Double, Double) -> Unit,
    onDragResize: (Double, Double) -> Unit,
    onDragEnd: () -> Unit,
    onRestoreDock: (DockedPane) -> Unit,
) {
    // The pane / dock chip whose context menu is currently open (by leaf id).
    var menuLeafId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab.panes.isEmpty() && tab.dock.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No windows in this tab", color = SidebarTextSecondary, fontSize = 13.sp)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
                    val canvasW = maxWidth
                    val canvasH = maxHeight
                    val density = LocalDensity.current
                    val canvasWpx = with(density) { canvasW.toPx() }
                    val canvasHpx = with(density) { canvasH.toPx() }

                    for (pane in tab.panes) {
                        val draggingThis = drag?.paneId == pane.leaf.id
                        val box = when {
                            draggingThis -> drag!!.box.let { Geom(it.x, it.y, it.width, it.height) }
                            pane.maximized -> FullBox
                            else -> Geom(pane.x, pane.y, pane.width, pane.height)
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = canvasW * box.x.toFloat(), y = canvasH * box.y.toFloat())
                                .size(width = canvasW * box.w.toFloat(), height = canvasH * box.h.toFloat())
                                .then(if (draggingThis) Modifier.zIndex(1f) else Modifier)
                                .padding(3.dp),
                        ) {
                            MiniPane(pane = pane, raised = draggingThis)

                            if (editing) {
                                // Whole-pane move drag.
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .pointerInput(pane.leaf.id) {
                                            detectDragGestures(
                                                onDragStart = { onBeginDrag(pane) },
                                                onDragEnd = { onDragEnd() },
                                                onDrag = { change, d ->
                                                    change.consume()
                                                    onDragMove((d.x / canvasWpx).toDouble(), (d.y / canvasHpx).toDouble())
                                                },
                                            )
                                        },
                                )
                                // Corner resize handle (drawn last so it wins touches).
                                ResizeHandle(
                                    modifier = Modifier.align(Alignment.BottomEnd),
                                    onStart = { onBeginDrag(pane) },
                                    onDrag = { dx, dy ->
                                        onDragResize((dx / canvasWpx).toDouble(), (dy / canvasHpx).toDouble())
                                    },
                                    onDragEnd = onDragEnd,
                                )
                            } else {
                                // Tap = focus + open; long-press = context menu.
                                PaneTapOverlay(
                                    onTap = { onOpenPane(pane) },
                                    onLongPress = { menuLeafId = pane.leaf.id },
                                )
                                PaneContextMenu(
                                    expanded = menuLeafId == pane.leaf.id,
                                    maximized = pane.maximized,
                                    minimized = false,
                                    onDismiss = { menuLeafId = null },
                                    onOpen = { menuLeafId = null; onOpenPane(pane) },
                                    onToggleMaximize = { menuLeafId = null; onToggleMaximize(pane) },
                                    onMinimize = { menuLeafId = null; onMinimize(pane) },
                                    onRestore = {},
                                    onEdit = { menuLeafId = null; onEnterEdit() },
                                    onRename = { menuLeafId = null; onRename(pane.leaf) },
                                    onClose = { menuLeafId = null; onClose(pane.leaf) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (tab.dock.isNotEmpty()) {
            DockStrip(
                dock = tab.dock,
                menuLeafId = menuLeafId,
                onRestore = onRestoreDock,
                onLongPress = { docked -> menuLeafId = docked.leaf.id },
                onDismissMenu = { menuLeafId = null },
                onRename = { leaf -> menuLeafId = null; onRename(leaf) },
                onClose = { leaf -> menuLeafId = null; onClose(leaf) },
            )
        }
    }
}

/** A lightweight geometry box in tab fractions. */
private data class Geom(val x: Double, val y: Double, val w: Double, val h: Double)

private val FullBox = Geom(0.0, 0.0, 1.0, 1.0)

/**
 * The per-pane context menu (anchored to the pane). Shows state-aware actions
 * with Mac-matching icons.
 *
 * @param expanded         whether this pane's menu is open.
 * @param maximized        whether the pane is maximized (Maximize ↔ Restore).
 * @param minimized        whether the pane is docked (shows Restore-only set).
 * @param onDismiss        dismiss without acting.
 * @param onOpen           enter the pane full-screen.
 * @param onToggleMaximize maximize / restore.
 * @param onMinimize       dock the pane.
 * @param onRestore        un-dock a minimized pane.
 * @param onEdit           enter edit-layout mode (free move + resize).
 * @param onRename         open the rename dialog.
 * @param onClose          confirm + close.
 */
@Composable
private fun PaneContextMenu(
    expanded: Boolean,
    maximized: Boolean,
    minimized: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleMaximize: () -> Unit,
    onMinimize: () -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(Icons.AutoMirrored.Filled.OpenInNew, "Open", onOpen)
        if (minimized) {
            MenuItem(Icons.Filled.OpenInFull, "Restore from dock", onRestore)
        } else {
            if (maximized) {
                MenuItem(Icons.Filled.CloseFullscreen, "Restore", onToggleMaximize)
            } else {
                MenuItem(Icons.Filled.OpenInFull, "Maximize", onToggleMaximize)
            }
            MenuItem(Icons.Filled.Minimize, "Minimize", onMinimize)
            MenuItem(Icons.Filled.OpenWith, "Move or resize", onEdit)
        }
        HorizontalDivider()
        MenuItem(Icons.Filled.Edit, "Rename…", onRename)
        MenuItem(Icons.Filled.Close, "Close", onClose, destructive = true)
    }
}

/** One context-menu row with a leading icon. */
@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val tint = if (destructive) SidebarWarn else SidebarTextBright
    DropdownMenuItem(
        text = { Text(label, color = tint) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/**
 * Transparent whole-pane tap target used in the idle (non-editing) state.
 *
 * @param onTap       invoked on a tap (focus + open).
 * @param onLongPress invoked on a long-press (open the context menu).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PaneTapOverlay(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    )
}

/**
 * The bottom-right resize handle shown on every pane in edit mode. A clearly-
 * exposed accent square with a diagonal grip, sized generously for touch.
 *
 * @param modifier  alignment modifier (caller anchors it bottom-end).
 * @param onStart   begin the drag (seed the pane's geometry).
 * @param onDrag    forward the pixel drag delta.
 * @param onDragEnd commit the resize.
 */
@Composable
private fun ResizeHandle(
    modifier: Modifier,
    onStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val handleColor = SidebarAccent
    val gripColor = SidebarBackground
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, bottomEnd = 6.dp))
            .background(handleColor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onDragEnd() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val s = size.minDimension
            for (f in listOf(0.35f, 0.7f)) {
                drawLine(
                    color = gripColor,
                    start = Offset(s * f, s),
                    end = Offset(s, s * f),
                    strokeWidth = s * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * The edit-layout banner shown above the canvas while a tab is being arranged.
 *
 * @param onDone leave edit mode.
 */
@Composable
private fun EditBanner(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarAccent.copy(alpha = 0.14f))
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Drag to move · drag a corner to resize",
            color = SidebarAccent,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDone) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = SidebarAccent, modifier = Modifier.size(16.dp))
            Text(" Done", color = SidebarAccent, fontSize = 12.sp)
        }
    }
}

/**
 * The dock strip: a horizontally-scrollable row of chips for the tab's
 * minimized panes. Tapping a chip restores it; long-pressing opens a context
 * menu (so a parked pane can be renamed/closed without restoring).
 *
 * @param dock          the minimized panes.
 * @param menuLeafId    the leaf id whose context menu is open, if any.
 * @param onRestore     restore a docked pane.
 * @param onLongPress   open the context menu for a docked pane.
 * @param onDismissMenu dismiss the open context menu.
 * @param onRename      open the rename dialog for a docked pane.
 * @param onClose       confirm + close a docked pane.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockStrip(
    dock: List<DockedPane>,
    menuLeafId: String?,
    onRestore: (DockedPane) -> Unit,
    onLongPress: (DockedPane) -> Unit,
    onDismissMenu: () -> Unit,
    onRename: (LeafNode) -> Unit,
    onClose: (LeafNode) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(dock, key = { it.leaf.id }) { docked ->
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, SidebarTextSecondary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .background(SidebarSurface)
                        .combinedClickable(
                            onClick = { onRestore(docked) },
                            onLongClick = { onLongPress(docked) },
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusDot(state = docked.sessionState, boxDp = 12)
                    PaneIcon(kind = leafKindOf(docked.leaf), floating = false, sizeDp = 12)
                    Text(
                        text = docked.leaf.title,
                        color = SidebarTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PaneContextMenu(
                    expanded = menuLeafId == docked.leaf.id,
                    maximized = false,
                    minimized = true,
                    onDismiss = onDismissMenu,
                    onOpen = { onDismissMenu(); onRestore(docked) },
                    onToggleMaximize = {},
                    onMinimize = {},
                    onRestore = { onRestore(docked) },
                    onEdit = {},
                    onRename = { onRename(docked.leaf) },
                    onClose = { onClose(docked.leaf) },
                )
            }
        }
    }
}

/** Map a leaf's content type to its [LeafKind] icon. */
private fun leafKindOf(leaf: LeafNode): LeafKind = when (leaf.content) {
    is FileBrowserContent -> LeafKind.FILE_BROWSER
    is GitContent -> LeafKind.GIT
    // Agent consoles render through the terminal surface (see openPane).
    is AgentContent -> LeafKind.TERMINAL
    else -> LeafKind.TERMINAL
}

/**
 * A single miniature pane: a themed, rounded card with a tiny title bar, the
 * type-specific live miniature, and the focused/accent outline. Purely visual —
 * input is layered on by [ExposeCanvas].
 *
 * @param pane   the projected pane.
 * @param raised whether to lift the card (used for the pane being dragged).
 */
@Composable
private fun MiniPane(
    pane: OverviewPane,
    raised: Boolean,
) {
    val focused = pane.isFocused
    val borderColor = if (focused || raised) SidebarAccent else SidebarTextSecondary.copy(alpha = 0.35f)
    val borderWidth = if (focused || raised) 2.dp else 1.dp
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (raised) Modifier.shadow(10.dp, shape) else Modifier)
            .clip(shape)
            .border(borderWidth, borderColor, shape)
            .background(SidebarSurface),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Distinct title-bar strip: an elevated `surfaceAlt` background with a
            // hairline divider below it, and the title always painted in the
            // brightest token regardless of focus — matching the web/Mac pane
            // headers, which never dim inactive panes (only the card border marks
            // focus). Slightly larger than the pane content for legibility.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SidebarSurfaceAlt)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(state = pane.sessionState, boxDp = 12)
                PaneIcon(kind = leafKindOf(pane.leaf), floating = false, sizeDp = 13)
                Text(
                    text = pane.leaf.title,
                    color = SidebarTextBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = SidebarBorder)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                when (pane.leaf.content) {
                    is FileBrowserContent -> MiniFileBrowserPane(pane.leaf.id, Modifier.fillMaxSize())
                    is GitContent -> MiniGitPane(pane.leaf.id, Modifier.fillMaxSize())
                    // Agent consoles preview through the terminal miniature —
                    // their byte stream is served like a PTY's.
                    is AgentContent -> MiniTerminalPane(pane.leaf.sessionId, Modifier.fillMaxSize())
                    else -> MiniTerminalPane(pane.leaf.sessionId, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * The top tab strip: a single horizontally-scrollable row of [FilterChip]s, the
 * active tab outlined in the accent colour with its aggregate status dot.
 *
 * Tapping a chip selects the tab; long-pressing it opens a context menu to
 * rename or close the tab, mirroring a pane's long-press menu.
 *
 * @param tabs         the tab summaries to render.
 * @param unlistedTabs hidden tabs not in [tabs]; surfaced via a trailing `⋮`
 *   menu so they can be re-activated. Empty hides the menu.
 * @param activeIndex  index of the active tab, or -1.
 * @param closeEnabled whether "Close tab" is offered (false for the last tab).
 * @param onSelect     invoked with a tab id when a chip is tapped.
 * @param onRename     open the rename dialog for the long-pressed tab.
 * @param onToggleHidden flip the long-pressed tab's hidden-from-tab-strip
 *   ("unlisted") flag.
 * @param onToggleSidebarHidden flip the long-pressed tab's hidden-from-sidebar
 *   flag (also hides it from the sessions list, which mirrors the sidebar).
 * @param onClose      confirm + close the long-pressed tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun OverviewTabStrip(
    tabs: List<OverviewTab>,
    unlistedTabs: List<UnlistedTab>,
    activeIndex: Int,
    closeEnabled: Boolean,
    onSelect: (String) -> Unit,
    onRename: (OverviewTab) -> Unit,
    onToggleHidden: (OverviewTab) -> Unit,
    onToggleSidebarHidden: (OverviewTab) -> Unit,
    onClose: (OverviewTab) -> Unit,
) {
    if (tabs.isEmpty()) return

    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    // The tab chip whose context menu is currently open (by tab id).
    var menuTabId by remember { mutableStateOf<String?>(null) }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs, key = { it.id }) { tab ->
            Box {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentEnforcement provides false,
                ) {
                    FilterChip(
                        selected = tab.isActive,
                        onClick = { onSelect(tab.id) },
                        label = {
                            Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = { StatusDot(state = tab.aggregateState, boxDp = 12) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SidebarBackground,
                            labelColor = SidebarTextSecondary,
                            selectedContainerColor = SidebarAccent.copy(alpha = 0.18f),
                            selectedLabelColor = SidebarAccent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = tab.isActive,
                            borderColor = SidebarTextSecondary.copy(alpha = 0.4f),
                            selectedBorderColor = SidebarAccent,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 2.dp,
                        ),
                    )
                }
                // Transparent overlay that catches the tap (select) and the
                // long-press (context menu), mirroring a pane's PaneTapOverlay.
                // It sits above the chip so the chip's own click never fires.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { onSelect(tab.id) },
                            onLongClick = { menuTabId = tab.id },
                        ),
                )
                TabContextMenu(
                    expanded = menuTabId == tab.id,
                    isHidden = tab.isHidden,
                    isHiddenFromSidebar = tab.isHiddenFromSidebar,
                    closeEnabled = closeEnabled,
                    onDismiss = { menuTabId = null },
                    onRename = { menuTabId = null; onRename(tab) },
                    onToggleHidden = { menuTabId = null; onToggleHidden(tab) },
                    onToggleSidebarHidden = { menuTabId = null; onToggleSidebarHidden(tab) },
                    onClose = { menuTabId = null; onClose(tab) },
                )
            }
        }

        // Trailing `⋮` menu listing the unlisted (hidden) tabs. Tapping a row
        // activates that tab — it then surfaces temporarily in the strip
        // (see OverviewBackingViewModel.project). Mirrors the web/Mac
        // far-right overflow menu. Only rendered when some tabs are unlisted.
        if (unlistedTabs.isNotEmpty()) {
            item(key = "__unlisted__") {
                UnlistedTabsMenu(unlistedTabs = unlistedTabs, onSelect = onSelect)
            }
        }
    }
}

/**
 * The trailing `⋮` button at the end of the overview tab strip that opens a
 * dropdown of the unlisted (hidden) tabs. Selecting one activates it, which
 * surfaces it temporarily among the visible tabs.
 *
 * @param unlistedTabs the hidden tabs to list.
 * @param onSelect     invoked with the chosen tab id.
 */
@Composable
private fun UnlistedTabsMenu(
    unlistedTabs: List<UnlistedTab>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Icon(
            Icons.Filled.MoreVert,
            contentDescription = "Unlisted tabs",
            tint = SidebarTextSecondary,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (tab in unlistedTabs) {
                DropdownMenuItem(
                    text = {
                        Text(
                            tab.title.ifBlank { "(untitled)" },
                            color = SidebarTextBright,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(tab.id)
                    },
                )
            }
        }
    }
}

/**
 * The per-tab context menu (anchored to a tab chip). Mirrors [PaneContextMenu]
 * but with the tab-level actions: rename, the two listing toggles (tab strip /
 * sidebar — orthogonal flags, each labelled by its current state), and close
 * (the whole tab).
 *
 * @param expanded     whether this tab's menu is open.
 * @param isHidden     whether the tab is currently hidden ("unlisted") from
 *   the tab strip; labels the strip toggle item.
 * @param isHiddenFromSidebar whether the tab is currently hidden from the
 *   sidebar tab tree; labels the sidebar toggle item.
 * @param closeEnabled whether "Close tab" is offered (false for the last tab).
 * @param onDismiss    dismiss without acting.
 * @param onRename     open the rename dialog.
 * @param onToggleHidden flip the hidden-from-tab-strip flag.
 * @param onToggleSidebarHidden flip the hidden-from-sidebar flag.
 * @param onClose      confirm + close the tab.
 */
@Composable
private fun TabContextMenu(
    expanded: Boolean,
    isHidden: Boolean,
    isHiddenFromSidebar: Boolean,
    closeEnabled: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleSidebarHidden: () -> Unit,
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuItem(Icons.Filled.Edit, "Rename…", onRename)
        // Wording mirrors the web/Electron overflow menu.
        MenuItem(
            if (isHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            if (isHidden) "Show in tab bar" else "Hide in tab bar",
            onToggleHidden,
        )
        MenuItem(
            if (isHiddenFromSidebar) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            if (isHiddenFromSidebar) "Show in side bar" else "Hide in side bar",
            onToggleSidebarHidden,
        )
        if (closeEnabled) {
            HorizontalDivider()
            MenuItem(Icons.Filled.Close, "Close tab", onClose, destructive = true)
        }
    }
}
