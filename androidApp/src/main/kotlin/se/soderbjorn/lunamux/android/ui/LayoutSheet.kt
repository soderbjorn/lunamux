/**
 * Layout-preset picker bottom sheet for the Android overview (issue #58).
 *
 * Presents the same preset palette the Mac/web "Layout" dropdown offers
 * ([LayoutPreset.DROPDOWN_ORDER]) as a scrolling 3-column grid of tiles. Each
 * tile draws a miniature of the preset's geometry for the active tab's current
 * pane count via [LayoutPreset.computeBoxes] — the *same* computation the write
 * path uses — so the preview the user sees matches the layout they get. Slot 0
 * (where the focused pane lands) is painted in the accent colour, the other
 * emphasized slots in a translucent accent, and the rest dim, mirroring the
 * web tiles' primary/secondary/other treatment. [LayoutPreset.Auto] shows a
 * wand glyph rather than a fixed geometry.
 *
 * Selecting a tile routes to [OverviewBackingViewModel.applyLayout], which
 * authors the toolkit `LAYOUT_STATE` blob.
 *
 * @see se.soderbjorn.darkness.web.layout.LayoutPreset
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel.applyLayout
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.darkness.web.layout.DEFAULT_LAYOUT_GRID
import se.soderbjorn.darkness.web.layout.LayoutPreset

/**
 * The layout-preset bottom sheet.
 *
 * @param paneCount the active tab's on-canvas pane count, used to render each
 *   tile's miniature. When `0` the sheet shows an empty hint.
 * @param activePreset the tab's persisted layout preset, or `null`. Only
 *   [LayoutPreset.Auto] is rendered as the encircled "current layout" — every
 *   other preset is a one-shot the toolkit stops tracking after the next manual
 *   edit, so marking it active would be misleading (mirrors the web dropdown,
 *   issue #59).
 * @param onSelect  invoked with the chosen preset; the host applies it.
 * @param onDismiss dismiss without choosing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutSheet(
    paneCount: Int,
    activePreset: LayoutPreset?,
    onSelect: (LayoutPreset) -> Unit,
    onDismiss: () -> Unit,
) {
    // Match the web dropdown: only Auto is ever encircled as "active".
    val activeMark = activePreset?.takeIf { it == LayoutPreset.Auto }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SidebarSurface,
    ) {
        Text(
            text = "Layout",
            color = SidebarTextBright,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )
        if (paneCount <= 0) {
            Text(
                text = "No windows in this tab",
                color = SidebarTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 24.dp),
            )
            return@ModalBottomSheet
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            items(LayoutPreset.DROPDOWN_ORDER, key = { it.key }) { preset ->
                LayoutTile(
                    preset = preset,
                    paneCount = paneCount,
                    isActive = preset == activeMark,
                    onClick = { onSelect(preset) },
                )
            }
        }
    }
}

/**
 * A single preset tile: a miniature preview plus the preset's label.
 *
 * @param preset    the preset to draw.
 * @param paneCount how many panes the miniature should depict.
 * @param isActive  whether this preset is the tab's live layout; when `true`
 *   the preview is encircled with an accent ring (issue #59).
 * @param onClick   invoked when the tile is tapped.
 */
@Composable
private fun LayoutTile(
    preset: LayoutPreset,
    paneCount: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val previewShape = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(previewShape)
                .background(SidebarBackground)
                .then(
                    if (isActive) Modifier.border(1.5.dp, SidebarAccent, previewShape)
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (preset == LayoutPreset.Auto) {
                Icon(
                    Icons.Filled.AutoFixHigh,
                    contentDescription = null,
                    tint = SidebarAccent,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                PresetMiniature(preset, paneCount, Modifier.fillMaxWidth().aspectRatio(1.5f).padding(6.dp))
            }
        }
        Text(
            text = preset.label,
            color = if (isActive) SidebarAccent else SidebarTextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

/**
 * Draws [preset]'s geometry boxes for [paneCount] panes as rounded rects,
 * ranked by colour (slot 0 accent, other emphasized slots translucent accent,
 * the rest dim) — the Compose analogue of the web tiles' SVG miniature.
 *
 * @param preset    the preset whose boxes to draw.
 * @param paneCount how many boxes to draw.
 * @param modifier  layout modifier (caller sets the aspect ratio).
 */
@Composable
private fun PresetMiniature(
    preset: LayoutPreset,
    paneCount: Int,
    modifier: Modifier,
) {
    val boxes = preset.computeBoxes(paneCount, DEFAULT_LAYOUT_GRID)
    val emphasized = preset.emphasizedSlotCount
    val primary = SidebarAccent
    val secondary = SidebarAccent.copy(alpha = 0.45f)
    val other = SidebarTextSecondary.copy(alpha = 0.4f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val gap = 1.5f
        boxes.forEachIndexed { i, b ->
            val color = when {
                i == 0 -> primary
                i < emphasized -> secondary
                else -> other
            }
            val x = (b.x * w + gap).toFloat()
            val y = (b.y * h + gap).toFloat()
            val bw = (b.width * w - gap * 2).toFloat().coerceAtLeast(2f)
            val bh = (b.height * h - gap * 2).toFloat().coerceAtLeast(2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(bw, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            )
        }
    }
}
