/*
 * Split from Overview3D.kt — theme-derived backdrop gradient and CSS color helpers.
 * See Overview3D.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import se.soderbjorn.termtastic.three.CanvasTexture
import se.soderbjorn.termtastic.three.Mesh
import se.soderbjorn.termtastic.three.MeshBasicMaterial
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.PlaneGeometry
import se.soderbjorn.termtastic.three.Raycaster
import se.soderbjorn.termtastic.three.Scene
import se.soderbjorn.termtastic.three.Vector2
import se.soderbjorn.termtastic.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------- */
/* Theme-derived backdrop                                                  */
/* ---------------------------------------------------------------------- */

/**
 * Derives the overlay's radial backdrop gradient from the live theme so
 * the 3D view reads as the same app: center = theme background nudged
 * toward the accent, edges = the background dimmed. Works for light themes
 * too (a light workspace gets a light room). Leaves the stylesheet's
 * fallback gradient untouched when a color fails to parse.
 *
 * @param overlay the overlay element to style.
 * @param bg the theme background CSS color (from the xterm theme).
 * @param accent the app accent CSS color.
 */
internal fun applyThemedBackdrop(overlay: HTMLElement, bg: String, accent: String) {
    val bgRgb = parseCssColor(bg) ?: return
    val accentRgb = parseCssColor(accent) ?: bgRgb
    val center = mixRgb(bgRgb, accentRgb, 0.14)
    val mid = dimRgb(bgRgb, 0.82)
    val edge = dimRgb(bgRgb, 0.55)
    overlay.style.background =
        "radial-gradient(ellipse at 50% 42%, ${cssRgb(center)} 0%, ${cssRgb(mid)} 62%, ${cssRgb(edge)} 100%)"
}

/**
 * Parses `#rgb`, `#rrggbb`, and `rgb(r, g, b)` CSS colors.
 *
 * @param color the CSS color string.
 * @return `[r, g, b]` components 0–255, or `null` when unparseable.
 */
internal fun parseCssColor(color: String): IntArray? = runCatching {
    val c = color.trim()
    when {
        c.startsWith("#") && c.length == 7 -> intArrayOf(
            c.substring(1, 3).toInt(16), c.substring(3, 5).toInt(16), c.substring(5, 7).toInt(16),
        )
        c.startsWith("#") && c.length == 4 -> intArrayOf(
            "${c[1]}${c[1]}".toInt(16), "${c[2]}${c[2]}".toInt(16), "${c[3]}${c[3]}".toInt(16),
        )
        c.startsWith("rgb") -> {
            val parts = c.substringAfter('(').substringBefore(')').split(',')
            intArrayOf(
                parts[0].trim().toDouble().roundToInt(),
                parts[1].trim().toDouble().roundToInt(),
                parts[2].trim().toDouble().roundToInt(),
            )
        }
        else -> null
    }
}.getOrNull()

/**
 * Linear blend of two RGB triples.
 *
 * @param a start color. @param b end color. @param t blend factor 0..1.
 * @return the mixed color.
 */
internal fun mixRgb(a: IntArray, b: IntArray, t: Double): IntArray = IntArray(3) { i ->
    (a[i] + (b[i] - a[i]) * t).roundToInt().coerceIn(0, 255)
}

/**
 * Scales an RGB triple toward black.
 *
 * @param c the color. @param f brightness factor (1 = unchanged, 0 = black).
 * @return the dimmed color.
 */
internal fun dimRgb(c: IntArray, f: Double): IntArray = IntArray(3) { i ->
    (c[i] * f).roundToInt().coerceIn(0, 255)
}

/**
 * Formats an RGB triple as a CSS `rgb()` color.
 *
 * @param c the color.
 * @return the CSS string.
 */
internal fun cssRgb(c: IntArray): String = "rgb(${c[0]}, ${c[1]}, ${c[2]})"
