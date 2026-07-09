/**
 * Faithful, colored terminal-grid renderer for the 3D overview.
 *
 * Where [renderThumbnail] (the link-picker / back-card path) paints a
 * *monochrome, re-wrapped* miniature of a terminal's transcript, this file
 * paints the terminal's **current screen exactly as the live pane renders it**:
 * the same column/row grid, ANSI/256/truecolor foreground+background, the
 * common text attributes (bold/dim/italic/underline/inverse/invisible), and the
 * cursor block. It reads directly from a live xterm.js `Terminal`'s
 * `buffer.active` — the same instance the visible pane uses — so nothing is
 * re-parsed and, crucially, no PTY resize is ever triggered (reading a buffer
 * sends nothing to the server).
 *
 * Used by the 3D overview ([Overview3D]) for the **selected** tab's terminal
 * pane tiles (only the front tab renders at full fidelity; other ring cards
 * keep the cheaper [renderThumbnail] look). The output canvas backs a three.js
 * [se.soderbjorn.lunamux.three.CanvasTexture] exactly like the thumbnail
 * path, so a repaint is still just "paint canvas + flag texture dirty".
 *
 * To reproduce xterm.js's on-screen colors precisely, [buildTermPalette]
 * reconstructs xterm's resolved 256-color table: the 16 base ANSI colors from
 * the app's live [buildXtermTheme] (falling back to xterm's own defaults for
 * entries the theme leaves untouched), the standard 6×6×6 color cube, and the
 * 24-step grayscale ramp — the same construction xterm's renderer uses.
 *
 * @see renderThumbnail
 * @see Overview3D
 * @see buildXtermTheme
 */
package se.soderbjorn.lunamux

import org.w3c.dom.HTMLCanvasElement

/**
 * xterm.js's default 16 ANSI colors, used as the fallback for any of the 16
 * base entries the live theme ([buildXtermTheme]) does not explicitly override.
 * These are the exact values from xterm.js's `DEFAULT_ANSI_COLORS`, so a cell
 * that resolves to, say, palette index 2 gets the same green the DOM renderer
 * would draw. Order is the standard ANSI order: black, red, green, yellow,
 * blue, magenta, cyan, white, then the eight bright variants.
 */
private val XTERM_DEFAULT_16 = arrayOf(
    "#000000", "#cd3131", "#0dbc79", "#e5e510",
    "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
    "#666666", "#f14c4c", "#23d18b", "#f5f543",
    "#3b8eea", "#d670d6", "#29b8db", "#e5e5e5",
)

/**
 * The `buildXtermTheme()` JSON keys for the 16 base ANSI colors, in palette
 * index order (0..15). Any key absent from the theme falls back to
 * [XTERM_DEFAULT_16].
 */
private val XTERM_THEME_KEYS = arrayOf(
    "black", "red", "green", "yellow",
    "blue", "magenta", "cyan", "white",
    "brightBlack", "brightRed", "brightGreen", "brightYellow",
    "brightBlue", "brightMagenta", "brightCyan", "brightWhite",
)

/**
 * A resolved terminal color palette: the 256-entry lookup table plus the
 * default foreground / background / cursor colors. Built once per overview
 * open by [buildTermPalette] (the theme is stable during a session) and passed
 * to every [renderTerminalGrid] call.
 *
 * @property colors256 CSS color strings for palette indices 0..255.
 * @property defaultFg CSS color for cells with a default (unset) foreground.
 * @property defaultBg CSS color for cells with a default (unset) background.
 * @property cursor CSS color for the cursor block.
 */
internal class TermPalette(
    val colors256: Array<String>,
    val defaultFg: String,
    val defaultBg: String,
    val cursor: String,
)

/**
 * Formats a 24-bit RGB integer (`0xRRGGBB`) as a CSS `#rrggbb` string.
 *
 * Called by [buildTermPalette] (cube/grayscale entries) and by
 * [renderTerminalGrid] for truecolor cells (`isFgRGB` / `isBgRGB`).
 *
 * @param rgb packed color, red in the high byte.
 * @return the `#rrggbb` CSS string.
 */
private fun rgbToCss(rgb: Int): String {
    val r = (rgb shr 16) and 0xff
    val g = (rgb shr 8) and 0xff
    val b = rgb and 0xff
    val hex = ((1 shl 24) or (r shl 16) or (g shl 8) or b).toString(16).substring(1)
    return "#$hex"
}

/**
 * Reconstructs xterm.js's resolved 256-color palette (plus default fg/bg and
 * cursor) from the app's live theme, so [renderTerminalGrid] matches the DOM
 * renderer's colors.
 *
 * Layout: 0..15 = base ANSI (theme override, else [XTERM_DEFAULT_16]);
 * 16..231 = the standard 6×6×6 cube (channel levels 0, 95, 135, 175, 215, 255);
 * 232..255 = the 24-step grayscale ramp (8 + 10·i).
 *
 * Called by [openOverview3d] once per open; the result is cached in module
 * state and handed to each full-fidelity tile paint.
 *
 * @return the resolved [TermPalette].
 * @see buildXtermTheme
 */
internal fun buildTermPalette(): TermPalette {
    val theme = buildXtermTheme()
    val colors = Array(256) { "#000000" }

    // 0..15: theme override where present, else xterm's default 16. `theme` is
    // already a dynamic JS object (from buildXtermTheme), so index it directly.
    for (i in 0 until 16) {
        val fromTheme = (theme[XTERM_THEME_KEYS[i]] as? String)?.takeIf { it.isNotBlank() }
        colors[i] = fromTheme ?: XTERM_DEFAULT_16[i]
    }

    // 16..231: 6×6×6 color cube.
    val cube = intArrayOf(0, 95, 135, 175, 215, 255)
    var idx = 16
    for (r in 0 until 6) {
        for (g in 0 until 6) {
            for (b in 0 until 6) {
                colors[idx++] = rgbToCss((cube[r] shl 16) or (cube[g] shl 8) or cube[b])
            }
        }
    }

    // 232..255: grayscale ramp.
    for (i in 0 until 24) {
        val v = 8 + 10 * i
        colors[232 + i] = rgbToCss((v shl 16) or (v shl 8) or v)
    }

    val fg = (theme.foreground as? String)?.takeIf { it.isNotBlank() } ?: "#d4d4d4"
    val bg = (theme.background as? String)?.takeIf { it.isNotBlank() } ?: "#1e1e1e"
    val cursor = (theme.cursor as? String)?.takeIf { it.isNotBlank() } ?: fg
    return TermPalette(colors, fg, bg, cursor)
}

/**
 * Resolves a cell's foreground color to a CSS string, honoring xterm's
 * "bold as bright" behavior (a bold cell using one of the low 8 palette colors
 * is drawn in its bright counterpart, index + 8).
 *
 * @param cell the xterm `IBufferCell` (accessed dynamically).
 * @param palette the resolved palette.
 * @param bold whether the cell is bold (drives bold-as-bright).
 * @return the CSS foreground color.
 */
private fun resolveFg(cell: dynamic, palette: TermPalette, bold: Boolean): String {
    return when {
        cell.isFgDefault() != 0 -> palette.defaultFg
        cell.isFgRGB() != 0 -> rgbToCss((cell.getFgColor() as Number).toInt())
        cell.isFgPalette() != 0 -> {
            var i = (cell.getFgColor() as Number).toInt()
            if (bold && i in 0..7) i += 8
            palette.colors256.getOrElse(i) { palette.defaultFg }
        }
        else -> palette.defaultFg
    }
}

/**
 * Resolves a cell's background color to a CSS string.
 *
 * @param cell the xterm `IBufferCell` (accessed dynamically).
 * @param palette the resolved palette.
 * @return the CSS background color.
 */
private fun resolveBg(cell: dynamic, palette: TermPalette): String {
    return when {
        cell.isBgDefault() != 0 -> palette.defaultBg
        cell.isBgRGB() != 0 -> rgbToCss((cell.getBgColor() as Number).toInt())
        cell.isBgPalette() != 0 -> {
            val i = (cell.getBgColor() as Number).toInt()
            palette.colors256.getOrElse(i) { palette.defaultBg }
        }
        else -> palette.defaultBg
    }
}

/**
 * Paints a live terminal's **current viewport** onto [canvas] as a faithful,
 * colored character grid — the exact screen the pane shows, cell for cell.
 *
 * Reads `term.buffer.active`: the visible rows are `viewportY .. viewportY +
 * rows`, iterated left-to-right, top-to-bottom. Backgrounds are drawn first
 * (batched into same-color horizontal runs for speed), then glyphs with their
 * per-cell fg color and style, then (optionally) the cursor block. Wide (CJK)
 * cells occupy two columns; their trailing half (`getWidth() == 0`) is skipped.
 *
 * The grid starts at `y = topInsetPx` so the caller's opaque title strip can be
 * drawn above it without covering terminal text — mirroring the real pane's
 * header sitting above its terminal.
 *
 * Called by [ThumbView.paintGrid] for the selected tab's terminal tiles.
 *
 * @param canvas destination canvas (already sized to the tile's pixel area).
 * @param term the live xterm.js terminal whose buffer to render.
 * @param palette resolved colors from [buildTermPalette].
 * @param showCursor draw the cursor block (true only for the tab's focused pane).
 * @param topInsetPx pixels of clear space reserved at the top for the title bar.
 * @param padPx inner padding (device px) kept clear on the left/right/bottom
 *   (and below the strip) so text sits *inside* the pane border rather than
 *   running under it.
 * @see renderThumbnail
 */
/**
 * The device-px font [renderTerminalGrid] uses for one grid cell: the cell
 * filled as much as monospace metrics allow (advance ≈ 0.6em), clamped to the
 * cell height so tall cells don't overflow. Factored out so [gridFontPx] can
 * predict the size without painting.
 *
 * @param cellW cell width in device px. @param cellH cell height in device px.
 * @return the glyph font size in device px.
 */
private fun cellFontPx(cellW: Double, cellH: Double): Double =
    minOf(cellH * 0.92, cellW / 0.6 * 0.92)

/**
 * Predicts the device-px glyph font [renderTerminalGrid] would use for [term]
 * on a [canvas] of the given inset/padding, without painting anything. Lets
 * [ThumbSource.repaint] demote a tile to the re-wrapped thumbnail when the exact
 * grid would render illegibly small (a narrow tile crammed with a wide/tall
 * terminal), instead of showing microscopic text.
 *
 * @param canvas the destination tile canvas (device px).
 * @param term the live xterm.js terminal to be rendered.
 * @param topInsetPx pixels reserved at the top for the title strip.
 * @param padPx inner padding kept clear on the sides/bottom.
 * @return the predicted glyph font size in device px, or `0.0` if unsizeable.
 * @see renderTerminalGrid
 */
internal fun gridFontPx(
    canvas: HTMLCanvasElement,
    term: Terminal,
    topInsetPx: Double,
    padPx: Double,
): Double {
    val cols = (term.cols as? Number)?.toInt() ?: 0
    val rows = (term.rows as? Number)?.toInt() ?: 0
    if (cols <= 0 || rows <= 0) return 0.0
    val gridW = canvas.width.toDouble() - padPx * 2
    val gridH = canvas.height.toDouble() - (topInsetPx + padPx) - padPx
    if (gridW <= 0 || gridH <= 0) return 0.0
    return cellFontPx(gridW / cols, gridH / rows)
}

internal fun renderTerminalGrid(
    canvas: HTMLCanvasElement,
    term: Terminal,
    palette: TermPalette,
    showCursor: Boolean,
    topInsetPx: Double,
    padPx: Double,
) {
    val ctx = canvas.getContext("2d") ?: return
    val d = ctx.asDynamic()
    val width = canvas.width.toDouble()
    val height = canvas.height.toDouble()
    if (width <= 0 || height <= 0) return

    val cols = (term.cols as? Number)?.toInt() ?: 0
    val rows = (term.rows as? Number)?.toInt() ?: 0
    val buffer = term.asDynamic().buffer?.active ?: return
    if (cols <= 0 || rows <= 0) return

    // Fill the whole canvas with the default background first, so the padding
    // margin and the reserved title strip read as terminal background.
    d.fillStyle = palette.defaultBg
    d.fillRect(0.0, 0.0, width, height)

    // Content rect, inset by [padPx] on the sides/bottom and starting below the
    // title strip (+ a pad gap), so glyphs never cross the pane border.
    val gx0 = padPx
    val gy0 = topInsetPx + padPx
    val gridW = width - padPx * 2
    val gridH = height - gy0 - padPx
    if (gridW <= 0 || gridH <= 0) return
    val cellW = gridW / cols
    val cellH = gridH / rows

    // Glyph font: fill the cell as much as monospace metrics allow (advance is
    // ~0.6em), clamped to the cell height so tall cells don't overflow. Use the
    // *live terminal's own* font family so the overview matches what the pane
    // actually renders, not the thumbnail font.
    val fontPx = cellFontPx(cellW, cellH)
    val fontFamily = (term.asDynamic().options?.fontFamily as? String)?.takeIf { it.isNotBlank() }
        ?: THUMBNAIL_FONT_FAMILY
    val viewportY = (buffer.viewportY as? Number)?.toInt() ?: 0

    // Pass 1 — backgrounds, batched into runs of one color.
    var cell: dynamic = null
    for (row in 0 until rows) {
        val line = buffer.getLine(viewportY + row) ?: continue
        val yTop = gy0 + row * cellH
        var runStart = 0
        var runColor: String? = null
        var x = 0
        while (x < cols) {
            cell = line.getCell(x, cell)
            val bg = if (cell == null) palette.defaultBg else {
                val inverse = cell.isInverse() != 0
                if (inverse) resolveFg(cell, palette, cell.isBold() != 0) else resolveBg(cell, palette)
            }
            if (bg != runColor) {
                if (runColor != null && runColor != palette.defaultBg) {
                    d.fillStyle = runColor
                    d.fillRect(gx0 + runStart * cellW, yTop, (x - runStart) * cellW, cellH + 0.5)
                }
                runColor = bg
                runStart = x
            }
            x++
        }
        if (runColor != null && runColor != palette.defaultBg) {
            d.fillStyle = runColor
            d.fillRect(gx0 + runStart * cellW, yTop, (cols - runStart) * cellW, cellH + 0.5)
        }
    }

    // Pass 2 — glyphs.
    d.textBaseline = "top"
    d.textAlign = "left"
    var lastFont: String? = null
    var lastFill: String? = null
    for (row in 0 until rows) {
        val line = buffer.getLine(viewportY + row) ?: continue
        val yTop = gy0 + row * cellH
        var x = 0
        while (x < cols) {
            cell = line.getCell(x, cell)
            if (cell == null) { x++; continue }
            val cw = (cell.getWidth() as? Number)?.toInt() ?: 1
            if (cw == 0) { x++; continue } // trailing half of a wide glyph
            val chars = (cell.getChars() as? String) ?: ""
            if (chars.isNotEmpty() && chars != " ") {
                val bold = cell.isBold() != 0
                val italic = cell.isItalic() != 0
                val inverse = cell.isInverse() != 0
                val invisible = cell.isInvisible() != 0
                if (!invisible) {
                    val fg = if (inverse) resolveBg(cell, palette) else resolveFg(cell, palette, bold)
                    val font = (if (italic) "italic " else "") +
                        (if (bold) "600 " else "") +
                        "${fontPx}px $fontFamily"
                    if (font != lastFont) { d.font = font; lastFont = font }
                    if (cell.isDim() != 0) { d.globalAlpha = 0.6 } else { d.globalAlpha = 1.0 }
                    if (fg != lastFill) { d.fillStyle = fg; lastFill = fg }
                    d.fillText(chars, gx0 + x * cellW, yTop)
                    if (cell.isUnderline() != 0) {
                        d.fillRect(gx0 + x * cellW, yTop + cellH - maxOf(1.0, cellH * 0.06), cw * cellW, maxOf(1.0, cellH * 0.06))
                    }
                    d.globalAlpha = 1.0
                }
            }
            x += cw
        }
    }

    // Pass 3 — cursor block (only for the tab's focused pane).
    if (showCursor) {
        val cx = (buffer.cursorX as? Number)?.toInt() ?: -1
        val cy = (buffer.cursorY as? Number)?.toInt() ?: -1
        if (cx in 0 until cols && cy in 0 until rows) {
            val yTop = gy0 + cy * cellH
            d.globalAlpha = 1.0
            d.fillStyle = palette.cursor
            d.fillRect(gx0 + cx * cellW, yTop, cellW, cellH)
            // Redraw the covered glyph in the background color so it stays legible.
            val line = buffer.getLine(viewportY + cy)
            if (line != null) {
                cell = line.getCell(cx, cell)
                val chars = (cell?.getChars() as? String) ?: ""
                if (chars.isNotEmpty() && chars != " ") {
                    d.fillStyle = palette.defaultBg
                    d.font = "${fontPx}px $fontFamily"
                    lastFont = null; lastFill = null
                    d.fillText(chars, gx0 + cx * cellW, yTop)
                }
            }
        }
    }
}
