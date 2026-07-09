/**
 * Front-end-friendly catalogue of the toolkit's layout presets.
 *
 * The Android overview reaches straight into [LayoutPreset] (it shares the JVM
 * runtime), but the SwiftUI iOS layer would otherwise have to navigate the
 * bridged Kotlin enum + companion to enumerate presets, compute their preview
 * boxes, and read their labels. This file flattens all of that into a plain
 * [LayoutTile] list the Swift `LayoutSheet` renders directly — the same
 * `computeBoxes`/`label`/`emphasizedSlotCount` the write path uses, so the
 * preview the user sees matches the layout they get.
 *
 * @see se.soderbjorn.darkness.web.layout.LayoutPreset
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.applyLayoutByKey
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.darkness.web.layout.DEFAULT_LAYOUT_GRID
import se.soderbjorn.darkness.web.layout.LayoutBox
import se.soderbjorn.darkness.web.layout.LayoutPreset

/**
 * One renderable tile in the layout-preset picker.
 *
 * @property key        the preset's stable [LayoutPreset.key]; pass back to
 *   [se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.applyLayoutByKey].
 * @property label      the human-readable [LayoutPreset.label].
 * @property emphasizedSlotCount how many leading slots get privileged geometry
 *   ([LayoutPreset.emphasizedSlotCount]); colours the miniature's ranks.
 * @property isAuto     whether this is [LayoutPreset.Auto] (rendered as a wand
 *   glyph rather than a fixed geometry, and the only preset ever marked active).
 * @property boxes      the preview rectangles for the active tab's pane count,
 *   already snapped to [DEFAULT_LAYOUT_GRID]; empty for [isAuto].
 */
data class LayoutTile(
    val key: String,
    val label: String,
    val emphasizedSlotCount: Int,
    val isAuto: Boolean,
    val boxes: List<LayoutBox>,
)

/**
 * Builds the layout-sheet tiles for a tab holding [paneCount] on-canvas panes,
 * in [LayoutPreset.DROPDOWN_ORDER]. Mirrors the data the Android `LayoutSheet`
 * derives inline from [LayoutPreset].
 *
 * @see LayoutTile
 */
object LayoutPresetCatalog {
    /**
     * @param paneCount the active tab's on-canvas pane count; each non-Auto
     *   tile's [LayoutTile.boxes] is computed for this many panes.
     * @return one [LayoutTile] per dropdown preset, in display order.
     */
    fun tiles(paneCount: Int): List<LayoutTile> =
        LayoutPreset.DROPDOWN_ORDER.map { preset ->
            LayoutTile(
                key = preset.key,
                label = preset.label,
                emphasizedSlotCount = preset.emphasizedSlotCount,
                isAuto = preset == LayoutPreset.Auto,
                boxes = if (preset == LayoutPreset.Auto) emptyList()
                else preset.computeBoxes(paneCount, DEFAULT_LAYOUT_GRID),
            )
        }

    /** Whether [presetKey] is [LayoutPreset.Auto] — the only preset the sheet
     *  marks as the live layout (see the Android `LayoutSheet` note, issue #59). */
    fun isAutoKey(presetKey: String?): Boolean = presetKey == LayoutPreset.Auto.key
}
