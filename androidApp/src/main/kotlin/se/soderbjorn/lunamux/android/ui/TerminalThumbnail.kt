/**
 * Miniature "screenshot" renderer for a terminal screen snapshot.
 *
 * [TerminalThumbnail] draws a [TerminalFrame] as the terminal's exact colored
 * cell grid — the server's cols×rows, the session's real wrapping, per-run
 * ANSI colors and the cursor — scaled to *fill the height* with every row, with
 * the columns that do not fit cropped away.
 *
 * That fit is not a choice about looks; it is parity with the full-screen
 * terminal. A phone mirroring a laptop-width session fills its height with the
 * session's rows and pans over the overflowing columns
 * (`MirrorFit.solveFillHeightFont` in TerminalScreen). A thumbnail that fitted
 * *both* axes instead squeezed those same 200 columns into a card's width,
 * leaving a thin band of unreadable text between two empty halves — and the dive
 * transition then had to jump from that band to the height-filled real thing.
 * Cropping from the right matches where the mirror's pan starts, so a preview
 * and the terminal it dives into show the same window of the session.
 *
 * Drawing goes straight to the native canvas with a pair of remembered
 * [android.graphics.Paint]s: glyph metrics are measured once per typeface at a
 * fixed reference size, the whole grid is laid out in those reference units,
 * and a single canvas scale maps it into the box. Runs are column-anchored
 * (`startCol × cellW`), so grid alignment is exact at run granularity even
 * where glyph advances drift within a run (wide glyphs, emoji) — invisible at
 * thumbnail scale.
 *
 * @see TerminalFrame
 * @see MiniTerminalPane
 */
package se.soderbjorn.lunamux.android.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * Reference font size (px) the grid is laid out at before the uniform
 * scale-to-fit. Any value works — metrics and scale cancel out — but a
 * moderate size keeps glyph rasterization crisp after downscaling.
 */
private const val THUMB_REF_FONT_PX = 16f

/**
 * Paints plus the cell geometry they imply, at one font size.
 *
 * Built at [THUMB_REF_FONT_PX] for the scale-to-fit path, and at the terminal
 * view's own font size for the pinned path (see [ThumbViewGeometry]), where the
 * cell numbers are overridden with the view's rather than derived here.
 */
private class ThumbMetrics(typeface: Typeface, textSizePx: Float = THUMB_REF_FONT_PX) {
    /** Paint used for glyphs (color/flags mutated per run while drawing). */
    val textPaint = Paint().apply {
        this.typeface = typeface
        textSize = textSizePx
        isAntiAlias = true
    }

    /** Paint used for background/cursor rects. */
    val rectPaint = Paint()

    /** One grid cell's width in reference units. */
    val cellW: Float = textPaint.measureText("X")

    /** One grid cell's height in reference units. */
    val cellH: Float

    /** Baseline offset from a cell's top edge. */
    val baseline: Float

    init {
        val fm = textPaint.fontMetrics
        cellH = fm.descent - fm.ascent
        baseline = -fm.ascent
    }
}

/**
 * The exact geometry a live [com.termux.view.TerminalView] draws its grid at, so
 * a thumbnail can be pinned to it instead of fitting the frame itself.
 *
 * Used for the terminal screen's own placeholder. Both renderings fill the
 * height, but not identically: the view's font size is an *integer* px chosen by
 * `MirrorFit.solveFillHeightFont`, which leaves up to a line of slack that
 * `MirrorFit.centreOffsetY` then centres, while a fitted thumbnail scales
 * continuously and fills the box exactly. The couple of percent between them is
 * what made the placeholder visibly shift and resize as it handed over to the
 * live view at the end of a dive. Pinned, the two are the same pixels.
 *
 * @property fontPx                 the view's applied font size in px.
 * @property cellWidthPx            `TerminalRenderer.fontWidth` at that size.
 * @property lineSpacingPx          `TerminalRenderer.fontLineSpacing` — one row's height.
 * @property lineSpacingAndAscentPx `TerminalRenderer.fontLineSpacingAndAscent` —
 *   the grid's top inset, and the offset that turns a row into a text baseline.
 * @property contentOffsetY         the view's `setContentOffsetY` (the centring slack).
 * @property panX                   the view's horizontal pan; 0 for a freshly
 *   opened terminal, which is the only time a placeholder is on screen.
 */
data class ThumbViewGeometry(
    val fontPx: Int,
    val cellWidthPx: Float,
    val lineSpacingPx: Int,
    val lineSpacingAndAscentPx: Int,
    val contentOffsetY: Float,
    val panX: Float,
)

/**
 * Draw [frame] as the terminal's exact colored cell grid — scaled to fill this
 * composable's box, or pinned to a live view's geometry when [pinnedTo] is given.
 *
 * Composed by [MiniTerminalPane] (and by any future card surface that needs a
 * truthful terminal snapshot). Render-only: no gesture handling, no state.
 *
 * @param frame              the snapshot to draw; while `null` (before the
 *   first frame arrives after attach) the box is filled with
 *   [fallbackBackground] only.
 * @param fallbackBackground fill color used until the first frame lands,
 *   normally the resolved theme background so the placeholder matches the
 *   frame that replaces it.
 * @param modifier           layout modifier from the enclosing pane.
 * @param pinnedTo           when non-null, draw at exactly this view geometry
 *   (same font size, cell grid and centring) instead of fitting the box — the
 *   terminal screen's placeholder does this so its handoff to the live view moves
 *   nothing.
 */
@Composable
fun TerminalThumbnail(
    frame: TerminalFrame?,
    fallbackBackground: Color,
    modifier: Modifier = Modifier,
    pinnedTo: ThumbViewGeometry? = null,
) {
    val context = LocalContext.current
    val metrics = remember(context, pinnedTo?.fontPx) {
        val typeface = TerminalFont.typeface(context)
        if (pinnedTo == null) {
            ThumbMetrics(typeface)
        } else {
            ThumbMetrics(typeface, pinnedTo.fontPx.toFloat())
        }
    }
    val fallbackArgb = fallbackBackground.toArgb()
    Box(
        modifier.drawBehind {
            val canvas = drawContext.canvas.nativeCanvas
            val bg = frame?.defaultBg ?: fallbackArgb
            metrics.rectPaint.color = bg
            canvas.drawRect(0f, 0f, size.width, size.height, metrics.rectPaint)
            if (frame == null || frame.cols <= 0 || frame.rows <= 0) return@drawBehind

            val save = canvas.save()
            try {
                canvas.clipRect(0f, 0f, size.width, size.height)
                if (pinnedTo != null) {
                    // The view's own origin: pan, centring slack, and the grid's
                    // top inset. Row r's cell then starts at r * lineSpacing, and
                    // its baseline sits (lineSpacing - lineSpacingAndAscent) —
                    // i.e. -ascent — below the cell top, exactly as
                    // TerminalRenderer walks its rows.
                    canvas.translate(
                        -pinnedTo.panX,
                        pinnedTo.contentOffsetY + pinnedTo.lineSpacingAndAscentPx,
                    )
                    drawFrame(
                        canvas = canvas,
                        frame = frame,
                        metrics = metrics,
                        cellW = pinnedTo.cellWidthPx,
                        cellH = pinnedTo.lineSpacingPx.toFloat(),
                        baseline = (pinnedTo.lineSpacingPx - pinnedTo.lineSpacingAndAscentPx).toFloat(),
                    )
                } else {
                    val gridW = frame.cols * metrics.cellW
                    val gridH = frame.rows * metrics.cellH
                    // Every row, filling the height — the full-screen mirror's own fit.
                    val scale = size.height / gridH
                    val scaledW = gridW * scale
                    // Wider than the box: keep the left edge, crop the overflow,
                    // which is the window the mirror's pan opens on. Narrower (a
                    // session at this phone's own width): center it, so the
                    // letterbox is symmetric.
                    val dx = if (scaledW <= size.width) (size.width - scaledW) / 2f else 0f
                    canvas.translate(dx, 0f)
                    canvas.scale(scale, scale)
                    drawFrame(canvas, frame, metrics, metrics.cellW, metrics.cellH, metrics.baseline)
                }
            } finally {
                canvas.restoreToCount(save)
            }
        },
    )
}

/**
 * Paint [frame] onto [canvas] in cell units (the caller has already applied the
 * fit or pin transform): non-default background rects, the cursor block, then
 * glyph runs.
 *
 * @param canvas   the transformed native canvas.
 * @param frame    the snapshot to paint.
 * @param metrics  the paints to draw with.
 * @param cellW    one cell's width in the canvas' current units.
 * @param cellH    one cell's height.
 * @param baseline distance from a cell's top edge to its text baseline.
 */
private fun drawFrame(
    canvas: android.graphics.Canvas,
    frame: TerminalFrame,
    metrics: ThumbMetrics,
    cellW: Float,
    cellH: Float,
    baseline: Float,
) {
    val rectPaint = metrics.rectPaint
    val textPaint = metrics.textPaint

    // Background rects first, only where a run's bg differs from the default
    // (the box fill already painted the default everywhere).
    frame.lines.forEachIndexed { rowIdx, runs ->
        val top = rowIdx * cellH
        for (run in runs) {
            if (run.bg != frame.defaultBg) {
                rectPaint.color = run.bg
                val left = run.startCol * cellW
                canvas.drawRect(left, top, left + run.widthCols * cellW, top + cellH, rectPaint)
            }
        }
    }

    // Cursor block under the text, like the real renderer's block cursor. The
    // real renderer also inverts the glyph inside a block cursor; splitting a
    // run to repaint that one cell is not worth it at thumbnail scale, so a
    // theme whose cursor color equals its foreground hides that character
    // behind a solid block — which still reads correctly as a cursor.
    if (frame.cursorRow in 0 until frame.rows) {
        rectPaint.color = frame.cursorColor
        val left = frame.cursorCol * cellW
        val top = frame.cursorRow * cellH
        canvas.drawRect(left, top, left + cellW, top + cellH, rectPaint)
    }

    // Glyph runs, column-anchored so grid alignment survives advance drift.
    frame.lines.forEachIndexed { rowIdx, runs ->
        val y = rowIdx * cellH + baseline
        for (run in runs) {
            if (run.text.isEmpty()) continue
            textPaint.color = run.fg
            textPaint.isFakeBoldText = run.bold
            textPaint.isUnderlineText = run.underline
            // Same skew the real renderer uses for SGR 3; a preview that dropped
            // it lost the distinction between an agent's italic asides and its
            // ordinary output.
            textPaint.textSkewX = if (run.italic) -0.35f else 0f
            textPaint.isStrikeThruText = run.strikethrough
            canvas.drawText(run.text, run.startCol * cellW, y, textPaint)
        }
    }
}
