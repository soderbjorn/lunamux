/**
 * Overview mode content for the Lunamux Android app.
 *
 * Renders the tabs-and-panes model in the **app-switcher idiom**: one rounded
 * card per tab (each card a "scaled exposé" of that tab's pane layout) in a
 * free-flinging, center-snapping row — moving across many tabs is one gesture
 * — with a labelled tab dock at the bottom for orientation and direct jumps
 * (see [TabDock]). Pinching the row zooms it between three card sizes
 * ([SwitcherZoom]), from one card to read to a row to survey. Browsing the row
 * never changes server state; diving into a pane (tap on the centered card)
 * activates the tab, focuses the pane, and drills into that pane's full-screen
 * route.
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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.AgentContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.android.data.AppLocalRepository
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
 * Fraction of the switcher row's width one card occupies at [SwitcherZoom.In] —
 * the widest a card is ever drawn, and the reference every other level is a
 * fraction of.
 *
 * This was once the only card size, on the reasoning that a card here is a
 * terminal you have to read rather than an app screenshot you recognise at a
 * glance. Reading one card is not what a switcher is for, though: at nearly the
 * full width there is no seeing how many tabs there are or where you are among
 * them, which is the whole point of the idiom. It is now the zoomed-*in*
 * posture, and [SwitcherZoom.Default] — the OS switcher's own proportions — is
 * what the row opens at.
 */
internal const val SWITCHER_CARD_FRACTION = 0.89f

/**
 * The three postures the card row snaps between under a pinch, as the fraction
 * of the row's width one card takes up **on screen**.
 *
 * Cards scale in BOTH dimensions, never width alone. A thumbnail fits its box by
 * filling the height and cropping the columns that overflow the width (see
 * [TerminalThumbnail]), so a card squeezed horizontally at full height would show
 * *less of the terminal* the further you zoomed out — the exact opposite of what
 * zooming out is for. Scaling uniformly keeps the same window of text in view at
 * every level and only changes how large it is drawn.
 *
 * A level is a real layout — the cards are measured at its size, not drawn
 * shrunken from a bigger one — and only the pinch that moves between two levels
 * is a transform. [SwitcherCardRow] says why that distinction is load-bearing
 * and what it costs to keep.
 *
 * What a level does change is the stride, and with it the scroll offset that
 * centres a given card, so committing one re-centres the row. Everything else
 * the row measures off its own geometry — the fling's edge maths, the dock's
 * fractional focus, a tap's visible fraction — is expressed in strides and
 * viewports and needs no adjusting at all.
 *
 * @property cardFraction the on-screen card width as a fraction of the row's.
 */
internal enum class SwitcherZoom(val cardFraction: Float) {
    /**
     * Both neighbours mostly in view either side of the centred card — a bit over
     * two tabs on screen, read as shapes and colours (where the panes sit, which
     * one is a wall of output) with their titles above them.
     *
     * Not smaller than this, however tempting three-across is. Cards keep their
     * aspect, so the width you take off comes off the height too, and much below
     * here the row is a thin band of postage stamps stranded in the middle of an
     * empty screen.
     */
    Out(0.45f),

    /**
     * The OS switcher's own proportions, near enough: one card still large enough
     * to read, with both neighbours showing at the edges — which is what says at
     * a glance that there are more tabs and which way they lie.
     */
    Default(0.62f),

    /** One card at nearly the full width — the reading posture. */
    In(SWITCHER_CARD_FRACTION),
    ;

    /**
     * How much of an [In] card this level's card measures, in **both**
     * dimensions — the factor a card's height is taken down by, since its width
     * is already [cardFraction] of a row whose height it no longer fills.
     */
    val cardScale: Float get() = cardFraction / SWITCHER_CARD_FRACTION

    companion object {
        /**
         * The level [name] names, or [Default] for anything else.
         *
         * Deliberately total rather than throwing: the name comes off disk (see
         * `LocalState.switcherZoom`), and a file written by a build with a
         * different set of levels should land on the default, not crash the
         * overview.
         *
         * @param name a persisted level name, or `null`/`""` when none was stored.
         * @return the level to open at.
         */
        fun ofName(name: String?): SwitcherZoom =
            entries.firstOrNull { it.name == name } ?: Default

        /**
         * The level whose [cardFraction] is nearest [fraction] — where a pinch
         * lands when the fingers lift.
         *
         * @param fraction the card width the gesture ended on, as a fraction of
         *   the row's.
         * @return the level to settle to.
         */
        fun nearest(fraction: Float): SwitcherZoom =
            entries.minByOrNull { abs(it.cardFraction - fraction) } ?: Default
    }
}

/**
 * The switcher's vertical rhythm: the gap above the cards, between the cards and
 * the dock, and below the dock, all the same.
 *
 * The dock used to sit flush against the bottom edge with the card row's leftover
 * slack above it, so it read as stuck to the bottom of the screen rather than as
 * one of three evenly spaced bands. This is now the only vertical spacing the
 * switcher applies: the card row fills the band it is given, and the air around
 * the cards inside it belongs to the zoom level (see [SwitcherZoom]).
 */
internal val SwitcherEdgeGap = 12.dp

/** Corner radius of a switcher card. */
internal val SwitcherCardCorner = 20.dp

/**
 * Distance between two cards. Constant across the zoom levels rather than scaled
 * with them: at [SwitcherZoom.Out] a proportional gap left the cards nearly
 * touching, and what separates them there is a job for a fixed few dp.
 */
private val SwitcherCardGap = 16.dp

/**
 * Type size of the tab title drawn above a card.
 *
 * The title is what makes zooming out navigable: at [SwitcherZoom.Out] a card's
 * text is closer to shape than to words, so its name is what says which tab you
 * are looking at — the same job the app name does above a card in the OS
 * switcher, and it goes in the same place.
 * It has no room at [SwitcherZoom.In], where the card fills its row, and needs
 * none: there is only the one card to be looking at.
 */
private val SwitcherLabelSize = 13.sp

/** Gap between a card's top edge and its title. @see SwitcherLabelSize */
private val SwitcherLabelGap = 7.dp

/** Line height of a card's title. @see SwitcherLabelSize */
private val SwitcherLabelHeight = 18.dp

/**
 * The overview content: the switcher card row + bottom tab dock (or, while
 * editing a layout, that tab's full-surface exposé canvas) + window-management
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
    onBrowsedTabChanged: (String?) -> Unit = {},
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

    // Theme the headless thumbnail emulators: their default fg/bg/cursor slots
    // are Termux stock until overridden (the full-screen terminal gets the same
    // treatment via applyTerminalColors). Re-runs when the resolved theme
    // changes so existing thumbnails repaint.
    val thumbnailTheme = rememberTerminalPalette(client, "overview-thumbnails")
    LaunchedEffect(miniTerminals, thumbnailTheme) {
        miniTerminals.setDefaultColors(thumbnailTheme)
    }

    // The single pane whose card anchors the dive transition (see
    // DiveTransition.kt). Set at tap time, before navigation, so the shared
    // bounds are registered when the flight starts; saveable so the restored
    // overview re-attaches the same anchor for the reverse flight on pop.
    // Keyed by leaf id, not session id: linked panes share a session, and two
    // cards registering one shared-bounds key would clash.
    var divePaneId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rename / close dialog targets raised from a pane's context menu.
    var renameTarget by remember { mutableStateOf<LeafNode?>(null) }
    var closeTarget by remember { mutableStateOf<LeafNode?>(null) }
    // Rename / close dialog targets raised from a tab chip's context menu.
    var renameTabTarget by remember { mutableStateOf<OverviewTab?>(null) }
    var closeTabTarget by remember { mutableStateOf<OverviewTab?>(null) }

    // The zoom posture the switcher opens at, read from local state ONCE rather
    // than observed: from here on the row owns the level (a pinch is the only
    // thing that changes it), and a flow collected into the layout would fight
    // the gesture that just wrote to it. Hydration is a file read started at
    // process launch, long finished by the time there is a connection to show
    // tabs from — and the fallback if it somehow is not is the default level the
    // user would have been given anyway, so nothing can flash the wrong size.
    val localRepository = remember { AppLocalRepository.instance }
    val initialZoom = remember {
        SwitcherZoom.ofName(localRepository.state.value?.switcherZoom)
    }

    val tabs = state.tabs
    val activeIndex = tabs.indexOfFirst { it.isActive }.coerceAtLeast(0)
    // Re-key the card row on the active world. Each world shows a disjoint tab
    // list, so a world switch must give the row a *fresh* state seeded to the
    // new world's active index (the old pager's stale-settled-page ping-pong
    // bug, avoided the same way).
    val rowListState = key(state.worldId) {
        rememberLazyListState(initialFirstVisibleItemIndex = activeIndex)
    }

    // The card the row is on. Browsing is centering, not committing: unlike the old
    // pager, scrolling the row NEVER sends setActiveTab — only diving into a pane
    // commits the tab (see divePane below), matching the app-switcher idiom this
    // row replicates.
    //
    // Follows the row *while it moves*, changing as each card takes over most of
    // the screen, so a card can be tapped the moment it is the one on screen rather
    // than only after the settle finishes. Quantised on purpose: a snapshotFlow of
    // the rounded position emits once per card crossed, where the continuously
    // derived value it replaces recomposed on every frame of a fling — this screen
    // holds every card's canvas and live thumbnails, so that difference is the
    // difference between one hitch per card and a stutter throughout.
    var centeredIndex by remember(rowListState) { mutableStateOf(0) }
    LaunchedEffect(rowListState) {
        snapshotFlow { switcherFocusIndex(rowListState).roundToInt() }
            .collect { index -> centeredIndex = index }
    }

    // One-way server→row sync: an external active-tab change (desktop, another
    // phone) re-centers the row, but never mid-gesture — a fling in progress
    // wins over a remote echo. Keyed on WHICH tab is active, not on its index:
    // closing a tab ahead of the active one shifts that index without changing
    // what is active, and re-centering then would yank the row away from the
    // card the user had browsed to.
    val activeTabId = tabs.firstOrNull { it.isActive }?.id
    LaunchedEffect(activeTabId) {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index >= 0 && !rowListState.isScrollInProgress && centeredIndex != index) {
            rowListState.animateScrollToItem(index)
        }
    }

    // Publish the browsed card so the screen's toolbar actions (new pane,
    // layout preset) target what the user is looking at. Browsing deliberately
    // never activates a tab server-side, so the active tab is NOT that target.
    val browsedTabId = tabs.getOrNull(centeredIndex)?.id
    LaunchedEffect(browsedTabId) { onBrowsedTabChanged(browsedTabId) }
    DisposableEffect(Unit) { onDispose { onBrowsedTabChanged(null) } }

    // While editing layout, Back leaves edit mode rather than the screen.
    BackHandler(enabled = editTabId != null) { vm.exitEdit() }

    // A tab being edited can go away under us — closed or hidden from another
    // client, or a world switch. Nothing would then render the banner or the edit
    // surface, while Back kept being swallowed by the handler above, so the screen
    // looked normal and refused to leave. Leaving edit mode is the only sane answer.
    LaunchedEffect(editTabId, tabs) {
        if (editTabId != null && tabs.none { it.id == editTabId }) vm.exitEdit()
    }

    // Diving into a pane is the ONLY thing that commits a tab server-side:
    // activate the tab (browsing never did — see centeredIndex above), focus
    // the pane, and navigate immediately (openPane is synchronous, so the dive
    // transition starts this frame; the server round-trip stays async and
    // non-blocking). One launch keeps the two commands' send order.
    // Diving navigates, so it must happen once per gesture: while the row is
    // settling a tap is watched for at the card level as well as by the pane's own
    // handler (see SwitcherCardRow), and two navigations would stack two terminals
    // on the back stack. A short debounce rather than a latch, deliberately: a
    // latch set before navigating stays set if that navigation is interrupted —
    // Back pressed during the dive's fade leaves the tree composed — and then every
    // later tap is swallowed with no way back.
    var lastDiveNanos by remember { mutableStateOf(0L) }
    val divePane: (OverviewTab, OverviewPane) -> Unit = { tab, pane ->
        val now = System.nanoTime()
        if (now - lastDiveNanos > DIVE_DEBOUNCE_NANOS) {
            lastDiveNanos = now
            divePaneId = pane.leaf.id
            scope.launch {
                if (!tab.isActive) vm.setActiveTab(tab.id)
                vm.focusPane(tab.id, pane.leaf.id)
            }
            openPane(pane.leaf, onOpenTerminal, onOpenFileBrowser, onOpenGit)
        }
    }

    // Diving into a tab rather than a specific pane: its focused pane, or the
    // topmost one. Shared by the dock's centred chip and by a tap on a card the
    // row has not finished settling on — both mean "open the tab I can see".
    val diveIntoTab: (OverviewTab) -> Unit = { tab ->
        val target = tab.panes.firstOrNull { it.isFocused } ?: tab.panes.maxByOrNull { it.z }
        if (target != null) {
            divePane(tab, target)
        } else if (!tab.isActive) {
            // Every pane is docked, so there is nothing to dive into — activate
            // the tab instead, or the tap reads as a dead button. Restoring a
            // pane is one tap away on the card's dock strip.
            scope.launch { vm.setActiveTab(tab.id) }
        }
    }

    // One canvas parameterization shared by the two hosts below (a switcher
    // card, or the full-screen edit surface), so the 12 callbacks stay in sync.
    val canvasFor: @Composable (OverviewTab, Boolean) -> Unit = { tab, editing ->
        ExposeCanvas(
            tab = tab,
            editing = editing,
            drag = drag?.takeIf { it.tabId == tab.id },
            divePaneId = divePaneId,
            onOpenPane = { pane -> divePane(tab, pane) },
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

    CompositionLocalProvider(LocalMiniTerminalRegistry provides miniTerminals) {
        Column(modifier) {
            val editingTab = tabs.firstOrNull { it.id == editTabId }
            if (editingTab != null) {
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
            } else if (editingTab != null) {
                // Editing expands the tab to the full surface (the pre-switcher
                // full-width layout), so move/resize keep today's precision; the
                // dock is hidden because its chips would compete for taps.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    canvasFor(editingTab, true)
                }
            } else {
                // App-switcher card row: one card per tab, at whichever of the
                // three pinch levels the user last settled on, free momentum
                // flinging with center snap, neighbours peeking in from both
                // sides.
                SwitcherCardRow(
                    tabs = tabs,
                    rowListState = rowListState,
                    centeredIndex = centeredIndex,
                    initialZoom = initialZoom,
                    onZoomChanged = { level ->
                        scope.launch { localRepository.setSwitcherZoom(level.name) }
                    },
                    onCenter = { index -> scope.launch { rowListState.animateScrollToItem(index) } },
                    onDiveTab = diveIntoTab,
                    onDiveAt = { tab, fractionX, fractionY ->
                        // The exposé canvas lays panes out in fractions of its own
                        // box, so a tap's fraction of the card lands in the same
                        // space (the canvas' few dp of inset is far below a
                        // fingertip). Whatever pane contains the point wins; a tap
                        // on bare canvas falls back to the tab's own target.
                        val touched = tab.panes
                            .filter { pane ->
                                fractionX >= pane.x && fractionX <= pane.x + pane.width &&
                                    fractionY >= pane.y && fractionY <= pane.y + pane.height
                            }
                            .maxByOrNull { it.z }
                        if (touched != null) divePane(tab, touched) else diveIntoTab(tab)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = SwitcherEdgeGap),
                ) { tab -> canvasFor(tab, false) }

                // The bottom tab dock — the switcher's app-icon-row analog:
                // one labelled chip per tab for orientation and direct jumps.
                TabDock(
                    tabs = tabs,
                    unlistedTabs = state.unlistedTabs,
                    focusIndex = { switcherFocusIndex(rowListState) },
                    centeredIndex = centeredIndex,
                    closeEnabled = tabs.size > 1,
                    onCenter = { index -> scope.launch { rowListState.animateScrollToItem(index) } },
                    onDive = diveIntoTab,
                    onActivateUnlisted = { id -> scope.launch { vm.setActiveTab(id) } },
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
                    modifier = Modifier.padding(vertical = SwitcherEdgeGap),
                )
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

/**
 * Velocity, in dp/s, above which a release is a *flick* rather than a let-go.
 * Deliberately tiny: the OS switcher moves on with the smallest deliberate
 * push, and a row that instead rubber-banded back to where you started felt
 * like it was refusing the gesture.
 */
internal const val SWITCHER_FLICK_INTENT_DP = 80f

/**
 * The velocity a flick is reported as when it clears [SWITCHER_FLICK_INTENT_DP]
 * but falls under Foundation's own "advance a card" threshold (400 dp/s). Raising
 * it to exactly that line is what turns every deliberate flick into one card
 * forward instead of a bounce back.
 */
private const val SWITCHER_ADVANCE_VELOCITY_DP = 400f

/**
 * Stiffness of the settle, and its damping ratio. Chosen on the device with the
 * tuning sliders: a very soft spring, but *overdamped*, so it eases to a stop
 * without ever looking pulled into place. Foundation's default (400, damping 1)
 * arrived before the gesture felt finished, and the same softness at damping 1
 * felt weightless.
 */
internal const val SWITCHER_SNAP_STIFFNESS = 50f

/** Damping ratio of the settle; above 1 approaches the target without overshoot. */
internal const val SWITCHER_SNAP_DAMPING = 1.2f

/**
 * The card row's fling: the platform's own momentum, an edge-aware approach, and a
 * soft overdamped settle.
 *
 * The decay is the platform spline — the same friction every Android scroller
 * uses, which is what the OS switcher is being compared against; a lower-friction
 * decay tried earlier coasted further than any of them. What is *not* borrowed is
 * the ending: Foundation's snap would let the decay target a position past the
 * first or last card and leave the scroll container to clamp it, which arrives as
 * a wall. [calculateApproachOffset] instead never proposes more than the distance
 * that actually remains, so a fling toward either end decays into the edge card
 * and the spring lands it.
 *
 * The two decisions stay separate: how *far* a fling travels is the decay's job,
 * while whether a release advances at all is the snap's — and any deliberate flick
 * advances (see [SWITCHER_FLICK_INTENT_DP]). Only a release with essentially no
 * velocity falls back to "whichever card is nearest", which is what a slow drag
 * deserves.
 *
 * @param rowListState the row's list state, whose centred snap positions the
 *   behaviour snaps to and whose layout tells it how much room is left.
 * @return the fling behaviour to hand [LazyRow].
 */
@Composable
private fun rememberSwitcherFlingBehavior(rowListState: LazyListState): TargetedFlingBehavior {
    val density = LocalDensity.current
    val splineDecay = rememberSplineBasedDecay<Float>()
    return remember(rowListState, density, splineDecay) {
        val base = SnapLayoutInfoProvider(rowListState, SnapPosition.Center)
        val intentPx = with(density) { SWITCHER_FLICK_INTENT_DP.dp.toPx() }
        val advancePx = with(density) { SWITCHER_ADVANCE_VELOCITY_DP.dp.toPx() }
        val provider = object : SnapLayoutInfoProvider {
            override fun calculateSnapOffset(velocity: Float): Float {
                // A flick keeps its direction and is reported as at least fast
                // enough to count; only a genuine let-go reports nothing and lets
                // position decide.
                val reported = when {
                    velocity > intentPx -> maxOf(velocity, advancePx)
                    velocity < -intentPx -> minOf(velocity, -advancePx)
                    else -> 0f
                }
                return base.calculateSnapOffset(reported)
            }

            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float {
                val proposed = base.calculateApproachOffset(velocity, decayOffset)
                val room = roomToEdge(rowListState, forward = proposed >= 0f)
                    ?: return proposed
                // Cards are uniform, so "how far can the row still scroll" is
                // exact rather than estimated. Never propose more than that: the
                // decay then eases into the first or last card instead of running
                // at speed into the scroll container's clamp.
                return if (proposed >= 0f) proposed.coerceAtMost(room) else proposed.coerceAtLeast(-room)
            }
        }
        snapFlingBehavior(
            snapLayoutInfoProvider = provider,
            decayAnimationSpec = splineDecay,
            snapAnimationSpec = spring(
                dampingRatio = SWITCHER_SNAP_DAMPING,
                stiffness = SWITCHER_SNAP_STIFFNESS,
            ),
        )
    }
}

/**
 * How much of a card has to be on screen for a tap on it to open it rather than
 * merely centre it. Above half, it is the card the user is looking at.
 */
private const val CARD_TAP_DIVE_FRACTION = 0.55f

/** Longest press still treated as a tap by the row's mid-settle tap watcher, in ms. */
private const val CARD_TAP_MAX_HOLD_MS = 500L

/**
 * How far the finger may travel, in multiples of the platform's touch slop, for a
 * press during the row's motion to still be a tap. See [midSettleTapWatcher] for
 * why this is a backstop rather than the test itself, and why it is generous.
 */
private const val CARD_TAP_TRAVEL_SLOPS = 3f

/**
 * How long after a dive another one is ignored. Long enough that the two handlers
 * which can see one mid-settle tap cannot both navigate, short enough that a dive
 * interrupted by Back does not leave the switcher unable to open anything.
 */
private const val DIVE_DEBOUNCE_NANOS = 400_000_000L

/**
 * Watch a switcher card for a tap that lands while the row is still moving, and
 * open (or centre) it without waiting for the settle to finish.
 *
 * A press during a settle stops the scroll, and stopping the scroll can cancel the
 * press before the card's own handlers ever see a click — so tapping the card
 * filling the screen did nothing at all. This modifier is that missing handler.
 *
 * **It goes on the card, never over it.** A pointer modifier in an overlay is not
 * hit-transparent: Compose hands a pointer to the topmost sibling that wants it and
 * to nobody behind it, whether or not that sibling consumes anything. An overlay
 * watcher therefore swallowed every gesture it decided *not* to act on, which is
 * every tap on a resting row — the card at either end of the row settles instantly
 * (there is no distance left to travel), so those were dead on arrival while the
 * cards in the middle, with their long soft settle, mostly still worked. As part of
 * the card's own modifier chain it is an ancestor of the panes instead: it sees
 * every gesture in the [PointerEventPass.Initial] pass, consumes none of it, and
 * leaves the panes' own taps, menus and the edge cards' gate untouched.
 *
 * What counts as a tap is the **finger's** travel, corrected for the card sliding
 * out from under it. Neither raw signal works alone: local pointer movement calls a
 * stationary finger a drag during a settle, and the row's own displacement calls a
 * flick a tap — a light flick is a few dozen px of finger over ~100 ms, and all the
 * row movement it causes happens *after* the lift. Undoing the row's displacement
 * from the local movement leaves the finger's own path, which a tap keeps inside
 * the touch slop. A drag the row acted on is also consumed by its scroll, seen here
 * in the [PointerEventPass.Final] pass, which is the row itself saying the same
 * thing — and that is the precise half of the test, because the row scrolls only
 * from a gesture it took. The travel test is the backstop, and its threshold is
 * deliberately several times the slop: the row's position is republished once per
 * layout while pointer events arrive between them, so the correction can be one
 * frame of settle out of date, which at speed is tens of px.
 *
 * @param index        the card's index in the row.
 * @param rowListState the row's list state, read for its motion and geometry.
 * @param onCenter     centre the card at the given index — what a tap on a sliver
 *   peeking in at the edge means.
 * @param onTapAt      open whatever sits at the given fraction of the card, so a
 *   multi-pane card opens the pane that was actually touched.
 * @return this modifier, with the watcher attached.
 * @see SwitcherCardRow
 * @see visibleFraction
 */
private fun Modifier.midSettleTapWatcher(
    index: Int,
    rowListState: LazyListState,
    onCenter: (Int) -> Unit,
    onTapAt: (Float, Float) -> Unit,
): Modifier = pointerInput(index) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Decided at the press and only there: a tap on a row already at rest is
        // the panes' own business, and stealing it is what broke them.
        val movingAtPress = rowListState.isScrollInProgress
        val focusAtDown = switcherFocusIndex(rowListState)
        val stride = switcherStride(rowListState) ?: 0f
        val travelLimit = viewConfiguration.touchSlop * CARD_TAP_TRAVEL_SLOPS
        var dragged = false
        var lift: PointerInputChange? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            // The card slides under a stationary finger while the row settles, which
            // arrives as local movement; the row's own displacement over the same
            // interval is exactly that much, so subtracting it leaves the finger.
            val rowShift = (switcherFocusIndex(rowListState) - focusAtDown) * stride
            val travelX = change.position.x - down.position.x - rowShift
            val travelY = change.position.y - down.position.y
            if (abs(travelX) > travelLimit || abs(travelY) > travelLimit) dragged = true
            if (change.positionChange() != Offset.Zero && change.isConsumed) dragged = true
            // A second finger means a pinch (see switcherPinch), never a tap. The
            // consumption test above catches one that has already claimed the
            // gesture, but a pinch still short of its slop has consumed nothing
            // yet, and two fingers put down and lifted together would otherwise
            // dive.
            if (event.changes.count { it.pressed } > 1) dragged = true
            if (!change.pressed) {
                lift = change
                break
            }
        }
        val up = lift ?: return@awaitEachGesture
        if (!movingAtPress || dragged) return@awaitEachGesture
        if (up.uptimeMillis - down.uptimeMillis > CARD_TAP_MAX_HOLD_MS) return@awaitEachGesture
        if (visibleFraction(rowListState, index) < CARD_TAP_DIVE_FRACTION) {
            onCenter(index)
            return@awaitEachGesture
        }
        onTapAt(up.position.x / size.width.toFloat(), up.position.y / size.height.toFloat())
    }
}

/**
 * How much of the card at [index] is inside the row's viewport, 0..1.
 *
 * Measured at tap time rather than tracked, because it only matters then: it is
 * what tells a deliberate tap on the incoming card of a settle apart from a tap on
 * a sliver peeking at the edge.
 *
 * @param rowListState the row's list state.
 * @param index the card's index.
 * @return the visible fraction, or 0 when the card is not laid out.
 */
private fun visibleFraction(rowListState: LazyListState, index: Int): Float {
    val info = rowListState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
    if (item.size <= 0) return 0f
    val start = maxOf(item.offset, info.viewportStartOffset)
    val end = minOf(item.offset + item.size, info.viewportEndOffset)
    return ((end - start).toFloat() / item.size).coerceIn(0f, 1f)
}

/**
 * The row's position as a *fractional* card index — 1.4 meaning "40% of the way
 * from card 1 to card 2".
 *
 * The cards are uniform, so this is just the scroll position over the stride. Read
 * at draw time by [TabDock], whose chips follow the row continuously rather than
 * animating when the centred card changes.
 *
 * @param rowListState the row's list state.
 * @return the fractional index, or 0 while the row has no layout yet.
 */
private fun switcherFocusIndex(rowListState: LazyListState): Float {
    val stride = switcherStride(rowListState) ?: return 0f
    return rowListState.firstVisibleItemIndex + rowListState.firstVisibleItemScrollOffset / stride
}

/**
 * The distance from one card's start to the next, in px, or `null` before the row
 * has been laid out. Cards are all one width, so a single visible pair (or a
 * single item) is enough to know it.
 *
 * @param rowListState the row's list state.
 * @return the stride in px, or `null` when it cannot be known yet.
 */
private fun switcherStride(rowListState: LazyListState): Float? {
    val visible = rowListState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    val stride = if (visible.size >= 2) {
        (visible[1].offset - visible[0].offset).toFloat()
    } else {
        visible[0].size.toFloat()
    }
    return stride.takeIf { it > 0f }
}

/**
 * How much further the row can scroll before the first or last card is centred,
 * in px.
 *
 * The cards are all one width, so the row's scrollable range is exactly
 * `(count - 1) × stride` and its position `index × stride + offset` — no
 * estimation, and no need to have the far end composed. Used by
 * [rememberSwitcherFlingBehavior] to keep a fling from targeting past an end.
 *
 * @param rowListState the row's list state.
 * @param forward whether to measure toward the last card (true) or the first.
 * @return the remaining distance, or `null` while the row has no layout yet (in
 *   which case a caller should not clamp anything).
 */
private fun roomToEdge(rowListState: LazyListState, forward: Boolean): Float? {
    val info = rowListState.layoutInfo
    if (info.totalItemsCount <= 1) return null
    val stride = switcherStride(rowListState) ?: return null
    val position = rowListState.firstVisibleItemIndex * stride +
        rowListState.firstVisibleItemScrollOffset
    val range = (info.totalItemsCount - 1) * stride
    val room = if (forward) range - position else position
    return room.coerceAtLeast(0f)
}

/**
 * Stiffness of the settle onto a zoom level when the fingers lift, and its
 * damping ratio.
 *
 * Much firmer than the row's own settle ([SWITCHER_SNAP_STIFFNESS]): that spring
 * is soft because it carries a card most of the way across the screen, while this
 * one only closes the few percent of scale between where the pinch stopped and
 * the nearest level. At the row's stiffness the cards went on creeping long after
 * the fingers were gone.
 */
private const val SWITCHER_ZOOM_STIFFNESS = 400f

/** Damping ratio of the zoom settle; 1 lands on the level without overshoot. */
private const val SWITCHER_ZOOM_DAMPING = 1f

/**
 * The app-switcher card row: one rounded card per tab in a snapping [LazyRow]
 * with free momentum flinging — so moving across many tabs is one gesture, not
 * one swipe per tab — pinchable between the three [SwitcherZoom] levels.
 *
 * **A level is a real layout; only the gesture itself is a transform.** At rest
 * the cards are *measured* at the level's size, so a thumbnail is drawn at that
 * size rather than sampled down from a larger one, and — the reason it matters
 * most — the dive's shared bounds are the card's true layout bounds. Shared
 * elements take their bounds from the lookahead pass, which does not see an
 * ancestor's [graphicsLayer], so a row left permanently scaled would fly the
 * dive from a rect that is not the card you tapped.
 *
 * While a pinch is in flight the row instead previews the new size with exactly
 * that transform, and lands the layout on it when the fingers lift. That keeps
 * the gesture cheap — the cards' constraints never change during it, so no card
 * is remeasured or recomposed while fingers are down, only re-placed — and the
 * transform is gone (an exact identity) by the time anything can be tapped. The
 * row is measured at the inverse of the preview so that the cards the transform
 * is about to reveal are already composed, rather than sliding in from a blank
 * margin at the commit.
 *
 * The commit changes the stride, which is the one thing zooming does that the
 * row's own maths cannot absorb, so it re-centres in the same breath — with
 * [LazyListState.requestScrollToItem], applied by the very measure pass the new
 * size triggers, rather than by a scroll a frame later.
 *
 * Browsing is passive: it never activates a tab server-side. What a tap does
 * depends on how much of the card is on screen — a sliver only centres it, a card
 * you can actually read opens it, which zoomed out is every card you can see, as
 * in the OS switcher. While the row is moving each card watches the pointer
 * itself, because a press that stops a settle can be cancelled before any
 * clickable sees it. Selection semantics live in the caller ([OverviewContent]);
 * the motion constants are documented where they are declared.
 *
 * @param tabs          the tabs to render, one card each.
 * @param rowListState  the hoisted row state ([OverviewContent] re-keys it per
 *   world and drives centering from server echoes).
 * @param centeredIndex index of the card the row is on; its panes take their own
 *   taps, every other card is covered by a gate, and a zoom re-centres on it.
 * @param initialZoom   the level to open at, read from local state by the caller.
 *   The row owns the level from then on — a pinch is the only thing that moves it.
 * @param onZoomChanged a pinch settled on a level other than the one it started
 *   from; the caller persists it.
 * @param onCenter      center the card at the given index.
 * @param onDiveTab     open a tab's own target pane (its focused, else topmost).
 * @param onDiveAt      open whatever pane sits at the given fraction of a card,
 *   used by the mid-settle tap watcher so a multi-pane card opens what was touched.
 * @param modifier      layout modifier from the caller.
 * @param cardContent   the card's content for a tab (the tab's exposé canvas).
 * @see SwitcherZoom
 * @see switcherPinch
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherCardRow(
    tabs: List<OverviewTab>,
    rowListState: LazyListState,
    centeredIndex: Int,
    initialZoom: SwitcherZoom,
    onZoomChanged: (SwitcherZoom) -> Unit,
    onCenter: (Int) -> Unit,
    onDiveTab: (OverviewTab) -> Unit,
    onDiveAt: (OverviewTab, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (OverviewTab) -> Unit,
) {
    // The level the row is laid out at, and the in-gesture preview of the next
    // one as a multiple of it — exactly 1 whenever no pinch is in flight.
    var zoom by remember { mutableStateOf(initialZoom) }
    var preview by remember { mutableFloatStateOf(1f) }
    val zoomScope = rememberCoroutineScope()
    // The settle after a pinch, held so the next pinch can take the scale back
    // off it mid-flight instead of the two writing over each other.
    var settle by remember { mutableStateOf<Job?>(null) }

    // The level the titles are dressed for. Set the moment a pinch is released
    // rather than when the layout lands on it, so a title fades out over the
    // settle instead of after it — at In there is no slack above a card, and a
    // title still fading there would be doing it above the row entirely.
    var titledZoom by remember { mutableStateOf(initialZoom) }

    // Faded rather than popped, and held as a State read in the *draw* phase, so
    // the fade costs no recomposition of the cards it is drawn over.
    val labelAlpha = animateFloatAsState(
        targetValue = if (titledZoom == SwitcherZoom.In) 0f else 1f,
        label = "switcher-card-title",
    )

    BoxWithConstraints(modifier) {
        val cardWidth = maxWidth * zoom.cardFraction
        // Both dimensions, always: see SwitcherZoom for what happens to a
        // thumbnail in a card narrowed at full height.
        val cardHeight = maxHeight * zoom.cardScale
        // Sized against the preview so the row composes what the preview reveals;
        // at rest `preview` is 1 and these are the surface itself.
        val rowWidth = maxWidth / preview
        val rowHeight = maxHeight / preview
        // Symmetric padding of (viewport - card)/2 makes item offset 0 the
        // centered position, so snap positions and scrollToItem(i) both
        // land cards dead-center — including the first and last.
        val sidePadding = (rowWidth - cardWidth) / 2
        val cardShape = RoundedCornerShape(SwitcherCardCorner)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .requiredSize(rowWidth, rowHeight)
                .graphicsLayer {
                    scaleX = preview
                    scaleY = preview
                }
                .switcherPinch(
                    onPinch = { step ->
                        settle?.cancel()
                        // Clamped in the level's own terms: the preview may reach
                        // the outermost and innermost levels and no further, so a
                        // pinch pushed past one stops dead rather than banking up
                        // travel it would have to give back before responding.
                        preview = (preview * step).coerceIn(
                            SwitcherZoom.Out.cardFraction / zoom.cardFraction,
                            SwitcherZoom.In.cardFraction / zoom.cardFraction,
                        )
                    },
                    onPinchEnd = {
                        val target = SwitcherZoom.nearest(zoom.cardFraction * preview)
                        titledZoom = target
                        settle = zoomScope.launch {
                            animate(
                                initialValue = preview,
                                targetValue = target.cardFraction / zoom.cardFraction,
                                animationSpec = spring(
                                    dampingRatio = SWITCHER_ZOOM_DAMPING,
                                    stiffness = SWITCHER_ZOOM_STIFFNESS,
                                ),
                            ) { value, _ -> preview = value }
                            // Hand the preview over to the layout. Both writes land
                            // before the next frame is composed, so the transform is
                            // never seen off a size it no longer matches; the
                            // re-centre rides the measure pass they trigger, because
                            // a new card width is a new stride and the scroll offset
                            // that centred this card is no longer the same number.
                            val changed = target != zoom
                            zoom = target
                            preview = 1f
                            rowListState.requestScrollToItem(centeredIndex)
                            if (changed) onZoomChanged(target)
                        }
                    },
                ),
        ) {
            LazyRow(
                state = rowListState,
                flingBehavior = rememberSwitcherFlingBehavior(rowListState),
                horizontalArrangement = Arrangement.spacedBy(SwitcherCardGap),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = sidePadding),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                    // No distance-from-centre scale or fade: the carousel effect was
                    // one more thing moving during a fling, and it read as the row
                    // fighting its own settle rather than as depth. Cards are flat and
                    // identical; the dock carries the depth cue instead.
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardHeight),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(cardShape)
                                .background(SidebarSurface.copy(alpha = 0.35f))
                                // A hairline, never the accent: the panes inside draw
                                // their own outline (accented when focused), so an
                                // accent ring around the card read as a double border.
                                // Which tab is server-active is said by its dock chip.
                                .border(
                                    width = 1.dp,
                                    color = SidebarTextSecondary.copy(alpha = 0.22f),
                                    shape = cardShape,
                                )
                                // Watches for a tap that lands while the row is moving.
                                // On the card, NOT laid over it — see the modifier's doc.
                                .midSettleTapWatcher(
                                    index = index,
                                    rowListState = rowListState,
                                    onCenter = onCenter,
                                ) { fractionX, fractionY -> onDiveAt(tab, fractionX, fractionY) },
                        ) {
                            cardContent(tab)
                            if (index != centeredIndex) {
                                // A card that is not the centred one still gets a gate, so
                                // a pane's own taps and long-press menu cannot fire on a
                                // card the row is not on. What the gate DOES depends on how
                                // much of that card you can see: a sliver at the edge only
                                // centres, but a card already filling most of the screen —
                                // which is what the incoming card looks like for the whole
                                // length of a settle, and what every neighbour looks like
                                // once zoomed out — opens, because tapping the thing you
                                // are looking at should not have to wait for an animation.
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .combinedClickable(
                                            onClick = {
                                                if (visibleFraction(rowListState, index) >= CARD_TAP_DIVE_FRACTION) {
                                                    onDiveTab(tab)
                                                } else {
                                                    onCenter(index)
                                                }
                                            },
                                            onLongClick = { onCenter(index) },
                                        ),
                                )
                            }
                        }

                        // The tab's name, in the slack above the card that every
                        // level but In leaves. Offset out of the card rather than
                        // laid out above it, so the card's box stays exactly the
                        // card — which is what a tap's fractions and the dive's
                        // shared bounds are measured against.
                        Text(
                            text = tab.title,
                            color = SidebarTextSecondary,
                            fontSize = SwitcherLabelSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = -(SwitcherLabelGap + SwitcherLabelHeight))
                                .padding(horizontal = SwitcherCardCorner)
                                .graphicsLayer { alpha = labelAlpha.value },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Watch for a two-finger pinch and report it, taking the gesture off the row it
 * is attached to for as long as one lasts.
 *
 * Sits on the row's container rather than over it: pointer events reach an
 * ancestor in the [PointerEventPass.Initial] pass before the [LazyRow] inside
 * ever sees them, so this can claim a gesture the row would otherwise scroll on
 * without an overlay swallowing everything else (see [midSettleTapWatcher] for
 * what an overlay costs here).
 *
 * A pinch has to earn the gesture. Until the fingers' separation has changed by
 * more than the touch slop, relative to how far apart they are, nothing is
 * consumed and the row scrolls as usual — two fingers dragging together are a
 * scroll, and treating every second finger as a zoom made the row jitter whenever
 * a thumb brushed it. Once it *is* a pinch it stays one until every finger is up,
 * even while only one of them is left: handing the tail of the gesture back would
 * fling the row out from under a zoom the user is still finishing.
 *
 * Reported as a per-event *step* rather than as a total, so a pinch picks up from
 * wherever the row is drawn — mid-settle from the last one included — and so a
 * pinch held past a limit has no travel banked up to undo before it responds
 * again in the other direction.
 *
 * The callbacks are held through [rememberUpdatedState] rather than keyed into
 * [pointerInput]: they close over the row's live state, and a key that changed
 * with them would tear down the gesture loop mid-pinch, while a stale copy would
 * settle the zoom against whatever the row looked like when the switcher opened.
 *
 * @param onPinch    a frame's scale change, as a multiplier of the current scale.
 * @param onPinchEnd every finger is up and the scale should settle onto a level.
 * @return this modifier, with the watcher attached.
 * @see SwitcherCardRow
 */
@Composable
private fun Modifier.switcherPinch(
    onPinch: (Float) -> Unit,
    onPinchEnd: () -> Unit,
): Modifier {
    val pinch by rememberUpdatedState(onPinch)
    val pinchEnd by rememberUpdatedState(onPinchEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var ratio = 1f
            var pinching = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                if (event.changes.count { it.pressed } >= 2) {
                    // Both helpers ignore a pointer that was not already down last
                    // event, so the frame a second finger arrives reports no change
                    // rather than a jump from a one-finger "distance" of zero.
                    val step = event.calculateZoom()
                    ratio *= step
                    if (!pinching) {
                        val span = event.calculateCentroidSize(useCurrent = false)
                        if (span > 0f && abs(1f - ratio) * span > viewConfiguration.touchSlop) {
                            pinching = true
                        }
                    } else if (step != 1f) {
                        pinch(step)
                    }
                }
                if (pinching) {
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
            if (pinching) pinchEnd()
        }
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
 * @param divePaneId       leaf id of the pane anchoring the dive transition
 *   (the last-tapped card); only that card attaches [diveSharedBounds].
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
    divePaneId: String?,
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
                            // Only the tapped terminal-like pane carries the
                            // shared-bounds anchor: unique key per flight, and
                            // git/files panes navigate to non-terminal routes
                            // where the other end never exists.
                            val diveModifier =
                                if (pane.leaf.id == divePaneId && leafKindOf(pane.leaf) == LeafKind.TERMINAL) {
                                    Modifier.diveSharedBounds(diveKey(pane.leaf.sessionId))
                                } else {
                                    Modifier
                                }
                            // The anchor goes on the pane's CONTENT, not the whole
                            // card: the other end of the flight is the terminal's
                            // content box, and a card rect that also contains a
                            // title bar and a border is a different rectangle. Fly
                            // the whole card and the text lands a title-bar's height
                            // off and a few percent small, which is exactly the jump
                            // the transition looked broken for. The card's chrome
                            // stays behind and fades with the route.
                            MiniPane(
                                pane = pane,
                                raised = draggingThis,
                                contentModifier = diveModifier,
                            )

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
 * @param pane            the projected pane.
 * @param raised          whether to lift the card (used for the pane being
 *   dragged).
 * @param contentModifier modifier on the miniature *inside* the chrome —
 *   [ExposeCanvas] attaches the dive transition's shared bounds here, because
 *   the far end of that flight is the terminal's content box and only this box
 *   is the same rectangle.
 */
@Composable
private fun MiniPane(
    pane: OverviewPane,
    raised: Boolean,
    contentModifier: Modifier = Modifier,
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
                modifier = contentModifier
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
 * The trailing `⋮` button at the end of the tab dock that opens a dropdown of
 * the unlisted (hidden) tabs. Selecting one activates it, which surfaces it
 * temporarily among the visible tabs.
 *
 * Internal so [TabDock] (the strip's switcher-era successor) can reuse it.
 *
 * @param unlistedTabs the hidden tabs to list.
 * @param onSelect     invoked with the chosen tab id.
 */
@Composable
internal fun UnlistedTabsMenu(
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
 * Internal so [TabDock] (the strip's switcher-era successor) can reuse it.
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
internal fun TabContextMenu(
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
