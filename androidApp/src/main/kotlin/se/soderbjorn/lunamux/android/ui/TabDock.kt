/**
 * Bottom tab dock for the switcher-style overview.
 *
 * The overview renders tabs as an app-switcher card row ([SwitcherCardRow] in
 * OverviewScreen.kt); this dock is the analog of the OS switcher's app-icon
 * row beneath the cards: one compact labelled chip per tab, so every tab stays
 * visible and directly reachable no matter where the card row is flung.
 *
 * Semantics mirror the row's browse-vs-commit split:
 *  - tapping a **non-centered** chip only centers that tab's card (no server
 *    command);
 *  - tapping the **centered** chip dives into that tab's focused pane — the
 *    same commit a tap on the centered card performs;
 *  - long-pressing a chip opens the tab context menu (rename / listing
 *    toggles / close), unchanged from the old tab strip;
 *  - a trailing `⋮` lists unlisted (hidden) tabs, selecting one activates it
 *    server-side so it surfaces in the row.
 *
 * This replaces the former top `OverviewTabStrip`; the strip's context menu
 * and unlisted-tabs menu composables are reused as-is (now internal in
 * OverviewScreen.kt).
 *
 * @see OverviewContent
 * @see TabContextMenu
 * @see UnlistedTabsMenu
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.OverviewTab
import se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.UnlistedTab

/** Scale of a chip a full card away from the focus. */
internal const val DOCK_SIBLING_SCALE = 0.78f

/** Opacity of a chip a full card away from the focus. */
internal const val DOCK_SIBLING_ALPHA = 0.50f

/** Gap between chips, in dp. Uniform whatever their scale — see [TabDock]. */
internal const val DOCK_CHIP_GAP_DP = 6f

/**
 * How quickly focus falls off with distance, per card. 1 hands the emphasis over
 * completely across one card's worth of row movement.
 */
internal const val DOCK_FALLOFF = 1f

/**
 * Scratch for the dock's scaled-width walk, memoised on the focus it was computed
 * for.
 *
 * Every slot's `graphicsLayer` needs the whole centres array, and within one frame
 * they all ask about the same focus, so the answer is computed once and handed out.
 * Not thread-safe and does not need to be: it is only ever touched from the draw
 * phase of one dock.
 */
private class DockWalk {
    private var focus = Float.NaN
    private var centres = FloatArray(0)

    /**
     * The visual centre of every slot when the row sits at [focus].
     *
     * @param focus  the row's fractional card index.
     * @param widths each slot's measured width, in px.
     * @param gapPx  the gap to leave between slots, in px.
     * @return the centres, one per slot; the array is reused between calls and must
     *   not be retained.
     */
    fun centresFor(focus: Float, widths: List<Float>, gapPx: Float): FloatArray {
        if (focus == this.focus && centres.size == widths.size) return centres
        if (centres.size != widths.size) centres = FloatArray(widths.size)
        var left = 0f
        for (i in widths.indices) {
            val scaled = widths[i] * dockChipScale(i, focus)
            centres[i] = left + scaled / 2f
            left += scaled + gapPx
        }
        this.focus = focus
        return centres
    }
}

/**
 * How far out of focus the chip at [index] is when the row sits at [focus], as
 * 1 (fully focused) down to 0.
 *
 * @param index the chip's index.
 * @param focus the row's fractional card index.
 * @return the emphasis, 0..1.
 */
private fun dockChipEmphasis(index: Int, focus: Float): Float =
    (1f - abs(index - focus) * DOCK_FALLOFF).coerceIn(0f, 1f)

/**
 * The scale the chip at [index] is drawn at when the row sits at [focus]: full
 * size at the focus, [DOCK_SIBLING_SCALE] a card away.
 *
 * Also what the strip's layout is computed from, so the two can never disagree.
 *
 * @param index the chip's index.
 * @param focus the row's fractional card index.
 * @return the scale factor.
 */
private fun dockChipScale(index: Int, focus: Float): Float {
    val sibling = DOCK_SIBLING_SCALE
    return sibling + (1f - sibling) * dockChipEmphasis(index, focus)
}

/**
 * The switcher's bottom tab dock: one chip per tab (status dot + title), the
 * focused tab at the front, with the tab context menu on long-press and the
 * unlisted-tabs `⋮` at the end.
 *
 * The strip is not a scroller. It follows the card row's *continuous* position:
 * the chip under the visible card sits in the middle of the dock, and while a
 * drag or fling is in flight the whole strip slides with it, the focus handing
 * over between two chips fractionally rather than jumping when the centred card
 * changes. That is what the OS switcher does, and it is why this is a plain [Row]
 * translated at draw time rather than a lazy row being animate-scrolled: one
 * scroll animation per centred-card change was both the snappiness and the
 * stutter a multi-card fling showed.
 *
 * Composed by [OverviewContent] below the card row (hidden while editing a
 * layout, whose gestures own the screen).
 *
 * @param tabs           the visible tabs, in row order.
 * @param unlistedTabs   hidden tabs not in [tabs]; surfaced via the trailing
 *   `⋮` menu so they can be re-activated. Empty hides the menu.
 * @param focusIndex     the card row's position as a *fractional* tab index, read
 *   at draw time so following it costs no recomposition. 1.4 means "40% of the way
 *   from tab 1 to tab 2".
 * @param centeredIndex  index of the card-row's centered tab; its chip's tap
 *   dives instead of centering.
 * @param closeEnabled   whether "Close tab" is offered (false for the last tab).
 * @param onCenter       center the card at the given index (no server command).
 * @param onDive         dive into the given tab's focused pane (commits the tab).
 * @param onActivateUnlisted activate an unlisted tab by id (server round-trip;
 *   the tab then surfaces in the row).
 * @param onRename       open the rename dialog for the long-pressed tab.
 * @param onToggleHidden flip the long-pressed tab's hidden-from-tab-strip
 *   ("unlisted") flag.
 * @param onToggleSidebarHidden flip the long-pressed tab's hidden-from-sidebar
 *   flag (also hides it from the sessions list, which mirrors the sidebar).
 * @param onClose        confirm + close the long-pressed tab.
 * @param modifier       layout modifier from [OverviewContent], which spaces the
 *   dock evenly between the cards above it and the screen's bottom edge.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TabDock(
    tabs: List<OverviewTab>,
    unlistedTabs: List<UnlistedTab>,
    focusIndex: () -> Float,
    centeredIndex: Int,
    closeEnabled: Boolean,
    onCenter: (Int) -> Unit,
    onDive: (OverviewTab) -> Unit,
    onActivateUnlisted: (String) -> Unit,
    onRename: (OverviewTab) -> Unit,
    onToggleHidden: (OverviewTab) -> Unit,
    onToggleSidebarHidden: (OverviewTab) -> Unit,
    onClose: (OverviewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    // The tab chip whose context menu is currently open (by tab id).
    var menuTabId by remember { mutableStateOf<String?>(null) }

    // Each slot's measured width and the centre the Row placed it at, reported as
    // they are placed. Both are needed because the chips are drawn somewhere else
    // than they are laid out: the widths drive the scaled-width walk that decides
    // where a chip *should* appear, and the layout centres say how far to slide it
    // from where it actually is.
    // One slot per chip, plus a last one for the trailing `⋮`. The overflow menu
    // has to travel with the strip like everything else: left at its raw layout
    // position it sat past the dock's right edge, where clipToBounds erased it —
    // and with it the only route back to a hidden tab.
    val slotCount = tabs.size + 1
    val slotWidths = remember(slotCount) { mutableStateListOf<Float>() }
    val slotLayoutCentres = remember(slotCount) { mutableStateListOf<Float>() }
    if (slotWidths.size != slotCount) {
        slotWidths.clear()
        slotLayoutCentres.clear()
        repeat(slotCount) {
            slotWidths.add(0f)
            slotLayoutCentres.add(0f)
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .clipToBounds(),
    ) {
        val density = LocalDensity.current
        val dockCentre = with(density) { maxWidth.toPx() } / 2f
        val gapPx = with(density) { DOCK_CHIP_GAP_DP.dp.toPx() }
        // Where every chip should *appear*, given that the out-of-focus ones are
        // drawn smaller. Laying the strip out from scaled widths is the whole
        // point: a chip shrunk about its own centre leaves half its lost width as
        // a hole on each side, and since the titles differ in length a wide chip
        // left a bigger hole than a narrow one — the gaps came out uneven. Walking
        // the scaled widths instead keeps every visible gap equal to [gapPx].
        // Memoised per focus value, not per chip: every slot's layer needs the whole
        // array, and they all read the same focus within one frame, so the first to
        // draw computes it and the rest reuse it. Recomputing per slot made dock
        // layout O(slots²) with an allocation each, every frame of every fling.
        val walk = remember(slotCount) { DockWalk() }
        val visualCentres: () -> FloatArray = {
            walk.centresFor(
                focus = focusIndex(),
                widths = slotWidths,
                gapPx = gapPx,
            )
        }
        // The point the dock centres on: one chip's visual centre, or a blend of
        // two while the row is between cards. Only the tab slots can be focused —
        // the `⋮` is chrome that rides along at the end.
        val focusCentre: (FloatArray) -> Float = { centres ->
            val position = focusIndex().coerceIn(0f, (tabs.size - 1).toFloat())
            val low = floor(position).toInt().coerceIn(0, tabs.size - 1)
            val high = (low + 1).coerceAtMost(tabs.size - 1)
            val fraction = position - low
            centres[low] * (1f - fraction) + centres[high] * fraction
        }
        // What every slot does with that: report where it was placed, then slide
        // from there to where the walk says it belongs, scaled and dimmed by how far
        // out of focus it is.
        val slotModifier: (Int) -> Modifier = { index ->
            Modifier
                .onPlaced { coords ->
                    val width = coords.size.width.toFloat()
                    val centre = coords.positionInParent().x + width / 2f
                    if (index < slotWidths.size) {
                        if (slotWidths[index] != width) slotWidths[index] = width
                        if (slotLayoutCentres[index] != centre) {
                            slotLayoutCentres[index] = centre
                        }
                    }
                }
                .graphicsLayer {
                    val focus = focusIndex()
                    val scale = dockChipScale(index, focus)
                    scaleX = scale
                    scaleY = scale
                    alpha = DOCK_SIBLING_ALPHA +
                        (1f - DOCK_SIBLING_ALPHA) * dockChipEmphasis(index, focus)
                    val centres = visualCentres()
                    if (index < centres.size && index < slotLayoutCentres.size) {
                        translationX = dockCentre - focusCentre(centres) + centres[index] -
                            slotLayoutCentres[index]
                    }
                }
        }

    Row(
        modifier = Modifier
            .wrapContentWidth(align = Alignment.Start, unbounded = true)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(DOCK_CHIP_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val centered = index == centeredIndex
            // Depth, computed entirely at draw time from the row's fractional
            // position: the chip the row is on stands at the front, full size and
            // brightness, and the further a chip is from it the further back it
            // sits — smaller, dimmer, drawn in towards the focus. Nothing here
            // animates on its own; the motion *is* the row's, which is why it can
            // neither lag the drag nor fight the settle.
            Box(slotModifier(index)) {
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides 0.dp,
                ) {
                    FilterChip(
                        selected = centered,
                        onClick = {},
                        label = {
                            Text(
                                tab.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                        },
                        leadingIcon = { StatusDot(state = tab.aggregateState, boxDp = 12) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SidebarBackground,
                            // The server-active tab's label carries the accent even
                            // when the row is browsing elsewhere — the only cue left
                            // for it once the cards stopped ringing themselves in
                            // accent, and distinct from the centred chip's filled
                            // container + accent border.
                            labelColor = if (tab.isActive) {
                                SidebarAccent
                            } else {
                                SidebarTextSecondary
                            },
                            selectedContainerColor = SidebarAccent.copy(alpha = 0.18f),
                            selectedLabelColor = SidebarAccent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = centered,
                            borderColor = SidebarTextSecondary.copy(alpha = 0.4f),
                            selectedBorderColor = SidebarAccent,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 2.dp,
                        ),
                    )
                }
                // Transparent overlay that catches the tap and the long-press,
                // sitting above the chip so its own click never fires. Tap on
                // the centered chip commits (dive); on any other it centers.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { if (centered) onDive(tab) else onCenter(index) },
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
        // activates that tab — it then surfaces temporarily in the dock (see
        // OverviewBackingViewModel.project). Mirrors the web/Mac far-right
        // overflow menu. Only rendered when some tabs are unlisted.
        if (unlistedTabs.isNotEmpty()) {
            Box(slotModifier(tabs.size)) {
                UnlistedTabsMenu(unlistedTabs = unlistedTabs, onSelect = onActivateUnlisted)
            }
        }
    }
    }
}
