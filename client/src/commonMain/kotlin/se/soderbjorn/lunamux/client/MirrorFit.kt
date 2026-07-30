/**
 * The geometry of a passive mirror: how large to draw the server's grid on a client
 * whose screen is a different shape, and where the window onto it sits.
 *
 * This file contains [CellMetrics] (the cell geometry one font size implies) and
 * [MirrorFit] (the pure fit, pan and zoom arithmetic built on it).
 *
 * ## Why the fit is rows-only
 *
 * One grid cannot fill both screens. A portrait phone at a laptop's column count has room
 * for roughly three and a half times the rows the session has, so fitting **both** axes —
 * what [PtyPresentation.passiveFontSize] did — makes the *columns* ratio bind: the text
 * shrinks to a fraction of the user's own font and most of the screen height goes unused.
 * Fitting the **rows** instead fills the height at a legible size (on a phone mirroring a
 * 143x40 laptop grid, larger than the phone's own driving font) and lets the columns
 * overflow, reachable by panning.
 *
 * The cost is stated rather than denied: about a third of each line is visible in portrait,
 * so the mirror is for monitoring at a glance and landscape — where the rows bind and
 * everything fits with no pan at all — is for reading in full. What it buys is fidelity:
 * the mirror stays line-for-line identical to the driving client, which is exactly what a
 * reflowed reading view would have destroyed.
 *
 * ## What this must NOT be confused with
 *
 * Tombstone, because the distinction is load-bearing: the *emulator grid* pin holds both
 * axes and must continue to. A mirror renders a stream that is absolutely cursor-addressed
 * for exactly the server's screen, so a client whose emulator has different rows lands every
 * address in the wrong place — measured on device as typed characters splicing into the
 * middle of the transcript. Only the **window** onto that pinned grid moves here: the font
 * it is drawn at, the horizontal offset, and the vertical centring. No function in this file
 * may ever feed a client's measurement back into the grid.
 *
 * ## Why the fit is solved by measurement rather than by a formula
 *
 * Cell metrics come out of the platform renderer as integers with a `ceil`, so inverting
 * "rows x lineSpacing fits the viewport" in closed form lands one pixel too large often
 * enough to matter — and one pixel too large means the last row, which is the prompt, falls
 * below the fold. [MirrorFit.solveFillHeightFont] therefore searches candidate font sizes
 * and asks the platform for real metrics, using the identical row expression the client's
 * natural-grid measurement uses, so the two can never disagree about what fits.
 *
 * @see PtyPresentation the mirror/driving mode machine these numbers are applied under
 * @see docs/server-side-screen.md the design record
 */
package se.soderbjorn.lunamux.client

/**
 * The cell geometry implied by one font size, as the platform's terminal renderer reports it.
 *
 * Supplied by the caller through a lambda ([MirrorFit.solveFillHeightFont]) so this module
 * stays free of platform types and unit-testable with fabricated metrics.
 *
 * @property cellWidthPx one character cell's advance width in pixels.
 * @property lineSpacingPx one row's height in pixels.
 * @property lineSpacingAndAscentPx the renderer's row height plus font ascent — the constant
 *   vertical slack a grid costs on top of its rows, and the term that makes the row formula
 *   an integer relation rather than a simple division.
 */
data class CellMetrics(
    val cellWidthPx: Float,
    val lineSpacingPx: Int,
    val lineSpacingAndAscentPx: Int,
)

/**
 * Pure fit, pan and zoom arithmetic for a passive mirror. No platform types: callers pass a
 * metrics lambda and translate the results into font sizes and canvas offsets.
 */
object MirrorFit {

    /**
     * How many rows of [metrics] fit in [viewHeightPx].
     *
     * The same relation the clients' natural-grid measurement uses
     * (`(height - lineSpacingAndAscent) / lineSpacing`), deliberately **without** its
     * floor-at-4: that floor makes a grid usable, whereas this answers the narrower question
     * "would this many rows actually be drawn inside the box", where a lie would clip the
     * prompt.
     *
     * @param viewHeightPx the view's height in pixels.
     * @param metrics the cell geometry of the candidate font.
     * @return the row count that fits, which may be zero or negative for a tiny box.
     */
    fun rowsThatFit(viewHeightPx: Int, metrics: CellMetrics): Int {
        if (metrics.lineSpacingPx <= 0) return 0
        return (viewHeightPx - metrics.lineSpacingAndAscentPx) / metrics.lineSpacingPx
    }

    /**
     * How many columns of [metrics] fit in [viewWidthPx].
     *
     * @param viewWidthPx the view's width in pixels.
     * @param metrics the cell geometry of the candidate font.
     * @return the column count that fits, zero for a degenerate cell width.
     */
    fun colsThatFit(viewWidthPx: Int, metrics: CellMetrics): Int {
        if (metrics.cellWidthPx <= 0f) return 0
        return (viewWidthPx / metrics.cellWidthPx).toInt()
    }

    /**
     * The largest font size in `[minPx, maxPx]` at which all [serverRows] rows are drawn
     * inside [viewHeightPx] — the mirror's default, because it fills the screen's height.
     *
     * Binary search, valid because [rowsThatFit] is monotonically non-increasing in font
     * size. When even [minPx] cannot fit the rows (a server grid taller than the client can
     * draw legibly at all), [minPx] is returned and the overflow is handled by
     * [centreOffsetY], which bottom-anchors so the newest rows stay visible.
     *
     * @param viewHeightPx the view's height in pixels.
     * @param serverRows the pinned server grid's row count — every one of which must be drawn.
     * @param minPx smallest legible font size.
     * @param maxPx largest font size worth considering.
     * @param metrics real cell geometry for a candidate font size.
     * @return the font size to draw the mirror at, always within `[minPx, maxPx]`.
     */
    fun solveFillHeightFont(
        viewHeightPx: Int,
        serverRows: Int,
        minPx: Int,
        maxPx: Int,
        metrics: (Int) -> CellMetrics,
    ): Int = solveLargestFitting(minPx, maxPx) { px ->
        rowsThatFit(viewHeightPx, metrics(px)) >= serverRows
    }

    /**
     * The largest font size in `[minPx, maxPx]` at which all [serverCols] columns fit
     * [viewWidthPx] — the zoomed-*out* overview end of the mirror's zoom range, where the
     * whole line is visible at once (illegibly small on a phone, but it is the "where am I"
     * map that makes panning navigable).
     *
     * @param viewWidthPx the view's width in pixels.
     * @param serverCols the pinned server grid's column count.
     * @param minPx smallest font size worth considering.
     * @param maxPx largest font size worth considering.
     * @param metrics real cell geometry for a candidate font size.
     * @return the font size at which the full width fits, or [minPx] when it never does.
     */
    fun solveFitWidthFont(
        viewWidthPx: Int,
        serverCols: Int,
        minPx: Int,
        maxPx: Int,
        metrics: (Int) -> CellMetrics,
    ): Int = solveLargestFitting(minPx, maxPx) { px ->
        colsThatFit(viewWidthPx, metrics(px)) >= serverCols
    }

    /**
     * The full drawn width of the grid, which is what the pan range is measured against.
     *
     * @param serverCols the pinned grid's column count.
     * @param cellWidthPx the applied font's cell width.
     * @return the content width in pixels.
     */
    fun contentWidthPx(serverCols: Int, cellWidthPx: Float): Float =
        if (serverCols <= 0 || cellWidthPx <= 0f) 0f else serverCols * cellWidthPx

    /**
     * The full drawn height of the grid — the exact inverse of [rowsThatFit], so a font that
     * solves [solveFillHeightFont] produces a height no greater than the viewport.
     *
     * @param serverRows the pinned grid's row count.
     * @param metrics the applied font's cell geometry.
     * @return the content height in pixels.
     */
    fun contentHeightPx(serverRows: Int, metrics: CellMetrics): Float =
        if (serverRows <= 0) 0f
        else serverRows * metrics.lineSpacingPx.toFloat() + metrics.lineSpacingAndAscentPx

    /**
     * The vertical offset to draw the grid at: **centred** while it fits the viewport, so
     * zooming out keeps the content in the middle of the screen rather than parked against
     * the top edge.
     *
     * When the content is taller than the viewport the offset goes negative and
     * bottom-anchors instead. That case only arises when even the smallest legible font
     * cannot fit the server's rows, and bottom is the right edge to keep: the prompt and the
     * newest output live there, and a terminal that hides them is useless.
     *
     * @param viewHeightPx the view's height in pixels.
     * @param serverRows the pinned grid's row count.
     * @param metrics the applied font's cell geometry.
     * @return the pixel offset to translate the canvas by; >= 0 when the grid fits.
     */
    fun centreOffsetY(viewHeightPx: Int, serverRows: Int, metrics: CellMetrics): Float {
        val contentH = contentHeightPx(serverRows, metrics)
        val slack = viewHeightPx - contentH
        return if (slack >= 0f) slack / 2f else slack
    }

    /**
     * How far the mirror can pan: zero when the grid already fits, so a driving client —
     * whose grid fits its own view by definition — needs no "is panning allowed" flag.
     *
     * @param contentWidthPx from [contentWidthPx].
     * @param viewWidthPx the view's width in pixels.
     * @return the maximum pan offset in pixels, never negative.
     */
    fun panRangePx(contentWidthPx: Float, viewWidthPx: Int): Float {
        val range = contentWidthPx - viewWidthPx
        return if (range > 0f) range else 0f
    }

    /**
     * Hold a pan offset inside the range the current content and viewport allow.
     *
     * Called on every gesture step and again whenever the font, the zoom or the view's box
     * changes, since all three move the range under a pan that was legal a moment ago.
     *
     * @param panPx the desired offset.
     * @param contentWidthPx from [contentWidthPx].
     * @param viewWidthPx the view's width in pixels.
     * @return the offset clamped to `[0, panRangePx]`.
     */
    fun clampPan(panPx: Float, contentWidthPx: Float, viewWidthPx: Int): Float =
        panPx.coerceIn(0f, panRangePx(contentWidthPx, viewWidthPx))

    /**
     * The pan offset that keeps the content under a pinch's focal point stationary while the
     * font changes size.
     *
     * Without this a pinch scales around the left edge, so the thing being zoomed towards
     * slides out from under the fingers — the single largest reason the existing mirror zoom
     * did not feel native. Unclamped by design: the caller clamps against the *new* content
     * width, which it only knows after applying the font.
     *
     * @param oldPanPx the pan offset before the font change.
     * @param focusXPx the pinch centroid, in view pixels from the left edge.
     * @param oldCellWidthPx the cell width the old font drew at.
     * @param newCellWidthPx the cell width the new font draws at.
     * @return the pan offset that holds the focal column in place.
     */
    fun focalAnchoredPan(
        oldPanPx: Float,
        focusXPx: Float,
        oldCellWidthPx: Float,
        newCellWidthPx: Float,
    ): Float {
        if (oldCellWidthPx <= 0f || newCellWidthPx <= 0f) return oldPanPx
        val focalColumn = (oldPanPx + focusXPx) / oldCellWidthPx
        return focalColumn * newCellWidthPx - focusXPx
    }

    /**
     * The smallest zoom multiplier worth offering, expressed against the fill-height baseline
     * where `1.0` means "fills the screen".
     *
     * The floor is the overview: zooming out further than the font at which the whole width
     * fits reveals nothing and only shrinks the text. It is exactly `1.0` when the width
     * already fits at fill-height — a phone in landscape — where there is nothing to zoom out
     * to and nothing to pan.
     *
     * @param fillHeightFontPx from [solveFillHeightFont].
     * @param fitWidthFontPx from [solveFitWidthFont].
     * @return the zoom floor in `(0, 1]`.
     */
    fun zoomFloor(fillHeightFontPx: Int, fitWidthFontPx: Int): Float {
        if (fillHeightFontPx <= 0) return 1f
        val floor = fitWidthFontPx.toFloat() / fillHeightFontPx.toFloat()
        return if (floor >= 1f) 1f else floor
    }

    /**
     * Largest candidate in `[minPx, maxPx]` satisfying a monotone predicate, by binary
     * search; [minPx] when none does.
     */
    private inline fun solveLargestFitting(minPx: Int, maxPx: Int, fits: (Int) -> Boolean): Int {
        if (maxPx <= minPx) return minPx
        var low = minPx
        var high = maxPx
        var best = minPx
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (fits(mid)) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
