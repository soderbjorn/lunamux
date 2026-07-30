/**
 * Tests for [MirrorFit] — the passive mirror's fit, pan and zoom arithmetic.
 *
 * The metrics lambda is fabricated on purpose, with the same integer `ceil` behaviour a real
 * terminal renderer has, because that rounding is the whole reason the fit is solved by search
 * instead of by inverting a formula.
 */
package se.soderbjorn.lunamux.client

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MirrorFitTest {

    /**
     * Metrics shaped like a real monospace renderer: cell width 0.6 em, row height 1.28 em
     * rounded **up** to whole pixels, and an ascent term of 0.28 em likewise.
     */
    private val metrics: (Int) -> CellMetrics = { px ->
        CellMetrics(
            cellWidthPx = px * 0.6f,
            lineSpacingPx = ceil(px * 1.28).toInt(),
            lineSpacingAndAscentPx = ceil(px * 0.28).toInt(),
        )
    }

    private val viewWidth = 1080
    private val viewHeight = 1900
    private val serverCols = 143
    private val serverRows = 40

    @Test
    fun fillHeightFontDrawsEveryRowInsideTheBox() {
        val font = MirrorFit.solveFillHeightFont(viewHeight, serverRows, 6, 48, metrics)
        assertTrue(
            MirrorFit.contentHeightPx(serverRows, metrics(font)) <= viewHeight,
            "font $font overflows the box",
        )
        assertTrue(MirrorFit.rowsThatFit(viewHeight, metrics(font)) >= serverRows)
    }

    /**
     * The off-by-one that motivates solving by measurement. A closed-form inverse gives
     * `1900 / (40 * 1.28) = 37.1`, i.e. 37 px — but `ceil` makes a 37 px row 48 px tall, so 40
     * rows plus the ascent term need 1931 px and the last row, the prompt, falls below the
     * fold. 36 px is the honest answer.
     */
    @Test
    fun fillHeightFontIsNotTheNaiveLinearInverse() {
        assertEquals(36, MirrorFit.solveFillHeightFont(viewHeight, serverRows, 6, 48, metrics))
        assertTrue(MirrorFit.contentHeightPx(serverRows, metrics(37)) > viewHeight)
    }

    @Test
    fun fillHeightFontIsLargerThanFittingBothAxes() {
        // The point of the whole change: fitting both axes lets the cols ratio bind, which on
        // this geometry is ~0.42 of the user's font. Filling the height beats it several times
        // over — and the columns that no longer fit are what panning is for.
        val fillHeight = MirrorFit.solveFillHeightFont(viewHeight, serverRows, 6, 48, metrics)
        val fitWidth = MirrorFit.solveFitWidthFont(viewWidth, serverCols, 6, 48, metrics)
        assertTrue(fillHeight > fitWidth * 2, "fillHeight=$fillHeight fitWidth=$fitWidth")
    }

    @Test
    fun fitWidthFontShowsEveryColumn() {
        val font = MirrorFit.solveFitWidthFont(viewWidth, serverCols, 6, 48, metrics)
        assertTrue(MirrorFit.colsThatFit(viewWidth, metrics(font)) >= serverCols)
        assertTrue(MirrorFit.colsThatFit(viewWidth, metrics(font + 1)) < serverCols)
    }

    @Test
    fun aGridTooTallForTheFloorFontFallsBackToTheFloor() {
        // 400 rows cannot be drawn legibly in 1900 px at any font; the floor is returned rather
        // than something illegible or a crash, and the offset bottom-anchors (below).
        assertEquals(6, MirrorFit.solveFillHeightFont(viewHeight, serverRows = 400, 6, 48, metrics))
    }

    @Test
    fun contentIsCentredWhileItFitsAndBottomAnchoredWhenItDoesNot() {
        val fits = metrics(20)
        val slack = viewHeight - MirrorFit.contentHeightPx(serverRows, fits)
        assertTrue(slack > 0f)
        assertEquals(slack / 2f, MirrorFit.centreOffsetY(viewHeight, serverRows, fits))

        // Taller than the box: the offset goes negative by the exact overflow, which puts the
        // LAST row at the bottom edge — the prompt stays visible, the oldest rows clip.
        val overflows = metrics(40)
        val overflow = viewHeight - MirrorFit.contentHeightPx(serverRows, overflows)
        assertTrue(overflow < 0f)
        assertEquals(overflow, MirrorFit.centreOffsetY(viewHeight, serverRows, overflows))
    }

    @Test
    fun panRangeIsTheOverflowAndZeroWhenTheGridFits() {
        val wide = MirrorFit.contentWidthPx(serverCols, metrics(36).cellWidthPx)
        assertEquals(wide - viewWidth, MirrorFit.panRangePx(wide, viewWidth))

        // A driving client's grid fits its own view, so the range is 0 and pan is inert with no
        // separate flag to keep in sync.
        val narrow = MirrorFit.contentWidthPx(40, metrics(36).cellWidthPx)
        assertTrue(narrow < viewWidth)
        assertEquals(0f, MirrorFit.panRangePx(narrow, viewWidth))
    }

    @Test
    fun panClampsToBothEnds() {
        val content = MirrorFit.contentWidthPx(serverCols, metrics(36).cellWidthPx)
        val range = MirrorFit.panRangePx(content, viewWidth)
        assertEquals(0f, MirrorFit.clampPan(-500f, content, viewWidth))
        assertEquals(range, MirrorFit.clampPan(range + 500f, content, viewWidth))
        assertEquals(120f, MirrorFit.clampPan(120f, content, viewWidth))
    }

    @Test
    fun focalAnchorHoldsTheColumnUnderTheFingers() {
        val oldCell = metrics(20).cellWidthPx
        val newCell = metrics(30).cellWidthPx
        val oldPan = 400f
        val focus = 300f
        val columnUnderFocus = (oldPan + focus) / oldCell

        val newPan = MirrorFit.focalAnchoredPan(oldPan, focus, oldCell, newCell)

        assertEquals(columnUnderFocus, (newPan + focus) / newCell, absoluteTolerance = 0.001f)
    }

    @Test
    fun focalAnchorIsInertWithoutUsableMetrics() {
        assertEquals(77f, MirrorFit.focalAnchoredPan(77f, 100f, 0f, 12f))
        assertEquals(77f, MirrorFit.focalAnchoredPan(77f, 100f, 12f, 0f))
    }

    @Test
    fun zoomFloorReachesTheOverviewAndCollapsesWhenNothingOverflows() {
        val fillHeight = MirrorFit.solveFillHeightFont(viewHeight, serverRows, 6, 48, metrics)
        val fitWidth = MirrorFit.solveFitWidthFont(viewWidth, serverCols, 6, 48, metrics)
        val floor = MirrorFit.zoomFloor(fillHeight, fitWidth)
        assertTrue(floor < 0.5f, "floor=$floor must reach below the old 0.5 constant")
        assertEquals(fitWidth, (fillHeight * floor).toInt())

        // Landscape: the width already fits at fill-height, so there is nothing to zoom out to.
        assertEquals(1f, MirrorFit.zoomFloor(fillHeightFontPx = 18, fitWidthFontPx = 22))
    }
}
