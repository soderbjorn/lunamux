/*
 * Custom, hand-drawn toolbar glyphs shared across the Lunamux clients.
 *
 * Both icons in this file are drawn from scratch with a [Canvas] using the
 * same 24-unit coordinate geometry as their iOS (`LayoutGridIcon` /
 * `PlusIcon` in `ToolbarIcons.swift`) and web (`ICON_LAYOUT` / `ICON_NEW_TAB`
 * inline SVGs in darkness-toolkit) counterparts, so every platform shows an
 * identical mark. They replace the previous Material icons (`Icons.Outlined.
 * Dashboard` and `Icons.Filled.Add`) in the Sessions toolbar.
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The "layout presets" glyph: four unequal panes in a 2×2 "Mondrian" tiling
 * with roomy 3-unit gaps.
 *
 * Drawn from scratch. Called by `TreeScreen`'s toolbar in overview mode where
 * it opens the layout preset sheet; replaces the former
 * `Icons.Outlined.Dashboard`.
 *
 * @param contentDescription accessibility label for the glyph (e.g. "Layout").
 * @param tint stroke colour for the three pane outlines.
 * @param modifier optional layout modifier; defaults to a 24.dp box matching
 *   the standard Material icon size.
 * @see PlusIcon
 */
@Composable
fun LayoutGridIcon(
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .size(24.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 1.5f * u)
        val radius = CornerRadius(1.6f * u, 1.6f * u)
        fun pane(x: Float, y: Float, w: Float, h: Float) =
            drawRoundRect(
                color = tint,
                topLeft = Offset(x * u, y * u),
                size = Size(w * u, h * u),
                cornerRadius = radius,
                style = stroke,
            )
        pane(3f, 3f, 9.5f, 8f)
        pane(15.5f, 3f, 5.5f, 8f)
        pane(3f, 14f, 5f, 7f)
        pane(11f, 14f, 10f, 7f)
    }
}

/**
 * A clean "+" glyph with rounded line caps.
 *
 * Drawn from scratch but inspired by the iOS SF Symbol `plus`. Called by
 * `TreeScreen`'s toolbar "Add" menu button; replaces the former
 * `Icons.Filled.Add` so the add affordance matches across platforms.
 *
 * @param contentDescription accessibility label for the glyph (e.g. "Add").
 * @param tint stroke colour for the two crossing strokes.
 * @param modifier optional layout modifier; defaults to a 24.dp box matching
 *   the standard Material icon size.
 * @see LayoutGridIcon
 */
@Composable
fun PlusIcon(
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .size(24.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round)
        drawLine(tint, Offset(12f * u, 5f * u), Offset(12f * u, 19f * u), strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(tint, Offset(5f * u, 12f * u), Offset(19f * u, 12f * u), strokeWidth = stroke.width, cap = stroke.cap)
    }
}
