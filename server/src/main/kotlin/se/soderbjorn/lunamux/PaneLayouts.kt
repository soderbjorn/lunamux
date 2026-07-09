/**
 * Pane-layout algorithm shim used by [PaneManager.applyLayout]. The
 * geometry itself lives in darkness-toolkit's `toolkit-core`
 * commonMain (`LayoutPreset.computeBoxes`), so Lunamux and notegrow
 * share a single source of truth for which preset produces which
 * arrangement. This file is a thin adapter that translates the
 * toolkit's `LayoutBox` rectangles into Lunamux's [PaneBox] type
 * and routes the `"auto"` key into the toolkit's auto preset.
 *
 * @see se.soderbjorn.darkness.web.layout.LayoutPreset
 * @see PaneManager.applyLayout
 */
package se.soderbjorn.lunamux

import se.soderbjorn.darkness.web.layout.GridSpec
import se.soderbjorn.darkness.web.layout.LayoutBox
import se.soderbjorn.darkness.web.layout.LayoutPreset

/**
 * Snap grid passed to the toolkit so auto-tiled panes land on the
 * same 5% grid Lunamux's drag-to-move/resize gestures already
 * snap to (`PaneGeometry.SNAP`). Keeps Auto layout
 * indistinguishable from hand-placed, snap-aligned panes.
 */
private val PANE_LAYOUT_GRID: GridSpec = GridSpec(cols = 20, rows = 20)

/**
 * Compute the list of pane boxes for [layout] with [n] total panes.
 * Index 0 is always the slot the caller should assign to the
 * focused (biggest) pane; subsequent indices are the remaining,
 * uniformly-sized sibling slots so the caller can assign area-ranked
 * panes in order.
 *
 * Unknown [layout] keys (and [LayoutPreset.Custom], which is a
 * sentinel rather than a layout target) fall back to `grid` for
 * robustness.
 *
 * @param layout the layout key — one of [LayoutPreset.key] (e.g.
 *   `"grid"`, `"auto"`, `"hero-left"`, …).
 * @param n the number of panes to place; must be ≥ 0.
 * @return a list of [PaneBox] of size [n] in rank order.
 *
 * @see se.soderbjorn.darkness.web.layout.LayoutPreset
 */
internal fun computePaneLayout(layout: String, n: Int): List<PaneBox> {
    if (n <= 0) return emptyList()
    val preset = LayoutPreset.fromKey(layout)
        ?.takeIf { it != LayoutPreset.Custom }
        ?: LayoutPreset.Grid
    return preset.computeBoxes(n, PANE_LAYOUT_GRID).map { it.toPaneBox() }
}

/** Convert the toolkit's normalized rectangle to Lunamux's. */
private fun LayoutBox.toPaneBox(): PaneBox = PaneBox(x = x, y = y, width = width, height = height)
