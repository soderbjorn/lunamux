/**
 * Shared geometry primitives for the free-form pane layout. Every pane has a
 * position and size expressed as fractions of the tab content area, and those
 * fractions must snap to a 10% grid and stay fully inside `[0, 1]`. This
 * object is the single source of truth for that snap-plus-clamp policy so the
 * server and every client produce identical results.
 *
 * @see Pane the data class whose geometry these helpers enforce
 * @see WindowCommand.SetPaneGeom the wire message carrying raw fractions that
 *      the server normalises before persisting
 */
package se.soderbjorn.termtastic

import kotlin.math.round

/**
 * Snap-and-clamp utility object shared between client and server. The single
 * snap base (10%) and minimum size (20% = two snap steps) live here so the web
 * client, the Android client, and the server all agree on what a "snapped"
 * pane looks like.
 */
object PaneGeometry {
    /** Granularity of the snap grid on both axes. 5% = 1/20 of the tab area. */
    const val SNAP: Double = 0.05

    /**
     * Minimum size on either axis, expressed as a fraction. Chosen large enough
     * that a pane remains legible even when the tab area is small; with the
     * 5% snap grid this is exactly two snap steps.
     */
    const val MIN_SIZE: Double = 0.1

    /** Default size used when creating a new pane via the new-window icon. */
    const val DEFAULT_SIZE: Double = 0.4

    /**
     * Round [v] to the nearest [SNAP] increment. Multiplying/dividing by the
     * integer denominator (20) avoids the floating-point drift you get from
     * `round(v / 0.05) * 0.05` when the snap base is not a finite binary
     * fraction.
     */
    fun snap(v: Double): Double = round(v * 20.0) / 20.0

    /**
     * Snap and clamp a raw `(x, y, width, height)` tuple so the resulting box
     * fits fully inside the tab area: sizes are clamped to `[MIN_SIZE, 1.0]`
     * and the top-left is clamped so `x + width` and `y + height` never exceed
     * 1.0. Call this on the authoritative (server) side for every persisted
     * geometry change, and on the client during drag to give the user visible
     * snap feedback.
     *
     * @param x top-left x as a fraction of the tab area's width
     * @param y top-left y as a fraction of the tab area's height
     * @param w width as a fraction of the tab area's width
     * @param h height as a fraction of the tab area's height
     * @return a normalised [PaneBox] with snapped, clamped fields
     */
    fun normalize(x: Double, y: Double, w: Double, h: Double): PaneBox {
        val sw = snap(w).coerceIn(MIN_SIZE, 1.0)
        val sh = snap(h).coerceIn(MIN_SIZE, 1.0)
        val sx = snap(x).coerceIn(0.0, 1.0 - sw)
        val sy = snap(y).coerceIn(0.0, 1.0 - sh)
        return PaneBox(sx, sy, sw, sh)
    }
}

/**
 * Result of [PaneGeometry.normalize] — a snap-aligned, in-bounds rectangle
 * described by fractions of the tab area.
 */
data class PaneBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)
