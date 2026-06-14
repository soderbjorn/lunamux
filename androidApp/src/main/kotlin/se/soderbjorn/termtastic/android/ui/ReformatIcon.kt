/**
 * The "reformat" toolbar glyph for the Termtastic Android app.
 *
 * Draws the same icon the Electron/web client uses for its reformat-on-resize
 * action: a terminal rectangle with a chevron tucked against each side wall,
 * reading as "reflow the contents to fit the width". This replaces the earlier
 * circular refresh icon, which looked like a reload, so the reformat affordance
 * is visually consistent across the web, desktop and mobile clients.
 *
 * @see se.soderbjorn.termtastic.android.ui.TerminalScreen
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Renders the reformat glyph, stroked in [tint], inside an icon-sized box.
 *
 * Called from [TerminalScreen]'s top-bar reformat [androidx.compose.material3.IconButton];
 * mirrors the web client's 24×24 SVG (a `rect` plus two inward chevrons) so the
 * stroke geometry matches pixel-for-pixel after scaling to the available size.
 *
 * @param tint stroke colour for the rectangle and chevrons (theme accent).
 * @param modifier layout modifier; sized to 24dp by default to match Material
 *   [androidx.compose.material3.Icon] dimensions.
 */
@Composable
fun ReformatIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = "Reformat" },
    ) {
        // The source artwork is authored in a 24×24 viewport; derive a uniform
        // scale so the icon stays crisp at whatever size the layout grants.
        val s = size.minDimension / 24f
        val stroke = Stroke(
            width = 2f * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        // Terminal frame: <rect x="3" y="5" width="18" height="14" rx="1.5"/>.
        drawRoundRect(
            color = tint,
            topLeft = Offset(3f * s, 5f * s),
            size = Size(18f * s, 14f * s),
            cornerRadius = CornerRadius(1.5f * s, 1.5f * s),
            style = stroke,
        )
        // Left chevron: <polyline points="7 10 4 12 7 14"/>.
        drawPath(
            path = Path().apply {
                moveTo(7f * s, 10f * s)
                lineTo(4f * s, 12f * s)
                lineTo(7f * s, 14f * s)
            },
            color = tint,
            style = stroke,
        )
        // Right chevron: <polyline points="17 10 20 12 17 14"/>.
        drawPath(
            path = Path().apply {
                moveTo(17f * s, 10f * s)
                lineTo(20f * s, 12f * s)
                lineTo(17f * s, 14f * s)
            },
            color = tint,
            style = stroke,
        )
    }
}
