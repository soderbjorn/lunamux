/**
 * Overview mode content for the Lunamux Android app.
 *
 * Renders the tabs-and-panes model in the **app-switcher idiom**: one rounded
 * card per tab (each card a "scaled exposé" of that tab's pane layout) in a
 * free-flinging, center-snapping row — moving across many tabs is one gesture
 * — with a labelled tab dock at the bottom for orientation and direct jumps
 * (see [TabDock]). Browsing the row never changes server state; diving into a
 * pane (tap on the centered card) activates the tab, focuses the pane, and
 * drills into that pane's full-screen route.
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * Fraction of the switcher row's width one card occupies.
 *
 * Nearly all of it. The OS switcher spends a fifth of its width on peeking
 * neighbours because its cards are app screenshots you recognise at a glance; a
 * card here is a terminal you have to *read*, so the screen goes to the text and
 * the neighbours are reduced to a sliver at the edges. The row still snaps and
 * flings the same way.
 *
 */
internal const val SWITCHER_CARD_FRACTION = 0.89f

/**
 * The switcher's vertical rhythm: the gap above the cards, between the cards and
 * the dock, and below the dock, all the same.
 *
 * The dock used to sit flush against the bottom edge with the card row's leftover
 * slack above it, so it read as stuck to the bottom of the screen rather than as
 * one of three evenly spaced bands. Cards now fill their row exactly and this is
 * the only vertical spacing in the switcher.
 */
internal val SwitcherEdgeGap = 12.dp

/** Corner radius of a switcher card. */
internal val SwitcherCardCorner = 20.dp

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
                // App-switcher card row: one card per tab at ~70% width in the
                // screen's aspect, free momentum flinging with center snap,
                // neighbors peeking in from both sides.
                SwitcherCardRow(
                    tabs = tabs,
                    rowListState = rowListState,
                    centeredIndex = centeredIndex,
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
 * How long after a dive another one is ignored. Long enough that the two handlers
 * which can see one mid-settle tap cannot both navigate, short enough that a dive
 * interrupted by Back does not leave the switcher unable to open anything.
 */
private const val DIVE_DEBOUNCE_NANOS = 400_000_000L

/**
 * How far the row may move between press and lift — in cards — for the gesture to
 * still count as a tap rather than a drag.
 *
 * Generous on purpose: a press cannot stop the settle before the next frame is
 * produced, and at a brisk settle speed the row still travels a few percent of a
 * card in that frame. A drag moves it far more than this, because the row follows
 * the finger.
 */
private const val CARD_TAP_SCROLL_TOLERANCE = 0.2f

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
 * The app-switcher card row: one rounded card per tab, nearly filling the surface,
 * in a snapping [LazyRow] with free momentum flinging — so moving across many tabs
 * is one gesture, not one swipe per tab. The neighbours are reduced to slivers at
 * the edges, because a card here is a terminal to be read rather than an app
 * screenshot to be recognised.
 *
 * Browsing is passive: it never activates a tab server-side. What a tap does
 * depends on how much of the card is on screen — a sliver only centres it, a card
 * you can actually read opens it — and while the row is moving each card watches
 * the pointer itself, because a press that stops a settle can be cancelled before
 * any clickable sees it. Selection semantics live in the caller
 * ([OverviewContent]); the motion constants are documented where they are declared.
 *
 * @param tabs          the tabs to render, one card each.
 * @param rowListState  the hoisted row state ([OverviewContent] re-keys it per
 *   world and drives centering from server echoes).
 * @param centeredIndex index of the card the row is on; its panes take their own
 *   taps, every other card is covered by a gate.
 * @param onCenter      center the card at the given index.
 * @param onDiveTab     open a tab's own target pane (its focused, else topmost).
 * @param onDiveAt      open whatever pane sits at the given fraction of a card,
 *   used by the mid-settle tap watcher so a multi-pane card opens what was touched.
 * @param modifier      layout modifier from the caller.
 * @param cardContent   the card's content for a tab (the tab's exposé canvas).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherCardRow(
    tabs: List<OverviewTab>,
    rowListState: LazyListState,
    centeredIndex: Int,
    onCenter: (Int) -> Unit,
    onDiveTab: (OverviewTab) -> Unit,
    onDiveAt: (OverviewTab, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    cardContent: @Composable (OverviewTab) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cardWidth = maxWidth * SWITCHER_CARD_FRACTION
        // The card fills the row it is given; the breathing room around the
        // switcher is [SwitcherEdgeGap], applied once by the caller.
        val cardHeight = maxHeight
        val sidePadding = (maxWidth - cardWidth) / 2
        val cardShape = RoundedCornerShape(SwitcherCardCorner)

        LazyRow(
            state = rowListState,
            flingBehavior = rememberSwitcherFlingBehavior(rowListState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Symmetric padding of (viewport - card)/2 makes item offset 0 the
            // centered position, so snap positions and scrollToItem(i) both
            // land cards dead-center — including the first and last.
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
                        .height(cardHeight)
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
                        ),
                ) {
                    cardContent(tab)
                    // A press that lands while the row is moving is the case that
                    // fell through the cracks: it stops the scroll, and stopping the
                    // scroll can cancel the press before either the pane's
                    // combinedClickable or the gate below ever sees a click — so
                    // tapping the card that fills the screen did nothing at all.
                    //
                    // This layer consumes nothing (a drag still scrolls the row) and
                    // is composed unconditionally: gating it on "is scrolling" meant
                    // it left composition the moment the press stopped the scroll,
                    // cancelling the very gesture it was watching. Whether it acts is
                    // decided from the state at the PRESS instead, and only then, so
                    // a tap on a resting row is left to the handlers below.
                    //
                    // On lift it asks whether what happened was a tap: quick, and
                    // with the row's position barely moved. Local pointer movement
                    // cannot be that test — the card moves under a stationary finger
                    // during a settle and follows the finger during a drag, so the
                    // two are the wrong way round.
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(index) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    val movingAtPress = rowListState.isScrollInProgress
                                    val focusAtDown = switcherFocusIndex(rowListState)
                                    var lift: PointerInputChange? = null
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            lift = change
                                            break
                                        }
                                    }
                                    val up = lift ?: return@awaitEachGesture
                                    if (!movingAtPress) return@awaitEachGesture
                                    val heldMs = up.uptimeMillis - down.uptimeMillis
                                    val moved = abs(
                                        switcherFocusIndex(rowListState) - focusAtDown,
                                    )
                                    if (heldMs > CARD_TAP_MAX_HOLD_MS ||
                                        moved > CARD_TAP_SCROLL_TOLERANCE
                                    ) {
                                        return@awaitEachGesture
                                    }
                                    if (visibleFraction(rowListState, index) <
                                        CARD_TAP_DIVE_FRACTION
                                    ) {
                                        onCenter(index)
                                        return@awaitEachGesture
                                    }
                                    // Open what was actually touched. Diving "into
                                    // the tab" picks its focused pane, which on a
                                    // multi-pane card is the wrong terminal whenever
                                    // the finger was on another one.
                                    onDiveAt(
                                        tab,
                                        up.position.x / size.width.toFloat(),
                                        up.position.y / size.height.toFloat(),
                                    )
                                }
                            },
                    )

                    if (index != centeredIndex) {
                        // A card that is not the centred one still gets a gate, so
                        // a pane's own taps and long-press menu cannot fire on a
                        // card the row is not on. What the gate DOES depends on how
                        // much of that card you can see: a sliver at the edge only
                        // centres, but a card already filling most of the screen —
                        // which is what the incoming card looks like for the whole
                        // length of a settle — opens, because tapping the thing you
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
            }
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
