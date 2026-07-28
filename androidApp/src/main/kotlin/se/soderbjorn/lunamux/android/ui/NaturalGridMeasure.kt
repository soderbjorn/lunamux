/**
 * Measuring the phone's *natural* terminal grid without resizing anything.
 *
 * This file contains [measureNaturalGrid], the Android counterpart of the web's
 * `measureNaturalGrid`: the grid this view would fit at the **user's own font**, computed
 * arithmetically and applied to nothing.
 *
 * Why it exists. The phone is a pure renderer now — its emulator is sized only by the
 * server's `Size` frames — so a layout pass can no longer answer itself by resizing the
 * grid, and the grid can no longer be read back as "what this phone fits". But the phone
 * still needs that number for three things: the take-over target (the grid a force asks
 * for), the baseline the mirror font-fit is measured against, and the grid put on the
 * connect URL so the server authors its attach redraw at the right width.
 *
 * The previous source of that number was [com.termux.view.TerminalView]'s own grid-size
 * listener, which reports the grid the view computed **at the applied font**. While
 * mirroring, the applied font is deliberately shrunken to fit a wider server grid, so that
 * listener reports a grid several times too large — and it fires as a *consequence* of the
 * view having already resized the emulator, which is exactly what a pure renderer must not
 * do. Measuring at the user font, on demand, replaces it.
 *
 * @see TerminalScreen the sole caller, from the view's layout-change listener
 * @see com.termux.view.TerminalView.updateSize the formula this reproduces
 */
package se.soderbjorn.lunamux.android.ui

import android.graphics.Typeface
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalView
import kotlin.math.max

/**
 * The grid [view] would fit at [userFontSizePx], measured and discarded — nothing is
 * resized and no listener fires.
 *
 * Reproduces [TerminalView.updateSize]'s arithmetic exactly (`width / fontWidth`,
 * `(height − lineSpacingAndAscent) / lineSpacing`, both floored at 4) against a throwaway
 * [TerminalRenderer] built at the requested font size, so the answer is the grid the view
 * *would* report if that font were applied. Using the view's own renderer instead would
 * answer for whatever font is applied right now — the shrunken mirror font while another
 * device drives.
 *
 * @param view the terminal view whose box to measure; its current font is irrelevant.
 * @param userFontSizePx the user's configured terminal font size in px.
 * @param typeface the terminal typeface, which decides the cell width.
 * @return the natural grid, or null while the view has no box yet (pre-layout), in which
 *   case the caller must keep whatever it already had rather than concluding anything.
 */
internal fun measureNaturalGrid(
    view: TerminalView,
    userFontSizePx: Int,
    typeface: Typeface?,
): AndroidGridDims? {
    val viewWidth = view.width
    val viewHeight = view.height
    if (viewWidth <= 0 || viewHeight <= 0) return null
    if (userFontSizePx <= 0) return null
    val renderer = runCatching { TerminalRenderer(userFontSizePx, typeface) }.getOrNull() ?: return null
    val fontWidth = renderer.fontWidth
    val lineSpacing = renderer.fontLineSpacing
    if (fontWidth <= 0f || lineSpacing <= 0) return null
    val cols = max(4, (viewWidth / fontWidth).toInt())
    val rows = max(4, (viewHeight - renderer.fontLineSpacingAndAscent) / lineSpacing)
    return AndroidGridDims(cols = cols, rows = rows)
}
