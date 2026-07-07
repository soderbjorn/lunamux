/*
 * Split from Overview3D.kt — thumbnail capture: ThumbView (canvas painting) and ThumbSource (live/mirror term feed).
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

/**
 * One thumbnail canvas + GPU texture, painted from a terminal transcript
 * (or a static placeholder) with an optional title strip. Several views can
 * share one [ThumbSource] — e.g. a tab card and that pane's tile.
 *
 * @property canvas the 2D canvas the texture samples from.
 * @property texture GPU texture over [canvas]; flagged dirty on paint.
 * @property stripTitle title painted across the top, or `null` for none.
 * @property stripPx logical title-bar height in px (0 = no bar); ×[res] physical.
 * @property stripFontPx logical title font size in px; ×[res] physical.
 * @property fg thumbnail foreground CSS color.
 * @property bg thumbnail background CSS color.
 * @property res supersampling factor — canvas is [res]× its logical size, so
 *   every metric here (fonts, bar height) is multiplied by [res] when painted.
 * @property accent theme accent CSS color, used for the title-bar underline.
 * @property topAnchored anchor lines to the top (file listing / git status)
 *   rather than the bottom (terminal tail). Forwarded to [renderThumbnail].
 * @property placeholder centered placeholder text painted when the view has
 *   no content yet (non-terminal panes / tabs), or `null`.
 * @property paneBorder whether to stroke a pane outline into the canvas — true
 *   for pane tiles (they read as little windows), false for the whole-tab card.
 * @property roundCorners clip the body + strip to the rounded pane rect without
 *   drawing a border. Set on the whole-tab card so its opaque corners don't
 *   square off *behind* the split tiles' own rounded corners; pane tiles get
 *   this implicitly via [paneBorder].
 */
internal class ThumbView(
    val canvas: HTMLCanvasElement,
    val texture: CanvasTexture,
    val stripTitle: String?,
    val stripPx: Int,
    val stripFontPx: Int,
    val fg: String,
    val bg: String,
    val res: Double,
    val accent: String,
    val topAnchored: Boolean = false,
    val placeholder: String? = null,
    val paneBorder: Boolean = false,
    val roundCorners: Boolean = false,
) {
    /**
     * When `true`, this view paints its terminal's *exact* colored cell grid
     * via [paintGrid] instead of the monochrome re-wrapped [renderThumbnail].
     * Only the selected tab's terminal pane tiles are promoted (set by
     * [applyFidelity]); back cards and the whole-tab card stay on the cheap
     * path.
     */
    var fullFidelity: Boolean = false

    /**
     * Whether [paintGrid] should draw the cursor block — true only for the
     * tab's focused pane, so the overview shows one cursor where the real tab
     * would.
     */
    var showCursor: Boolean = false

    /**
     * `true` when this tile is its tab's active (focused) pane — gives the
     * header an accent tint and the border a steady accent glow, mirroring the
     * real pane chrome.
     */
    var paneActive: Boolean = false

    /**
     * `true` when this tile is the current keyboard/hover selection in the
     * overview — a brighter, thicker glowing border than [paneActive].
     */
    var paneSelected: Boolean = false

    /** Inner content padding (device px) so glyphs clear the pane border. */
    private val contentPad: Double get() = 7.0 * res

    /**
     * Paints the view: transcript body (supersampled at [res]) when [lines]
     * is non-null, placeholder body otherwise, then the themed title bar and
     * pane border; flags the texture for re-upload.
     *
     * @param lines transcript logical lines from [readLogicalLines], or
     *   `null` to paint the placeholder body.
     */
    fun paint(lines: List<String>?) {
        val d = canvas.getContext("2d").asDynamic() ?: return
        d.save()
        if (lines != null) {
            // Reserve the title-bar height so content never sits under it, and
            // pad the sides/bottom so text clears the border.
            val inset = if (stripPx > 0 && stripTitle != null) stripPx * res else 0.0
            renderThumbnail(canvas, lines, fg, bg, res, topAnchored, inset, contentPad)
        } else {
            d.fillStyle = bg
            d.fillRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
            if (placeholder != null) {
                d.font = "${13.0 * res}px $THUMB_FONT"
                d.fillStyle = fg
                d.globalAlpha = 0.55
                d.textAlign = "center"
                d.textBaseline = "middle"
                d.fillText(placeholder, canvas.width / 2.0, canvas.height / 2.0)
                d.globalAlpha = 1.0
                d.textAlign = "left"
            }
        }
        drawStrip(d)
        finishChrome(d)
        d.restore()
        texture.needsUpdate = true
    }

    /**
     * Paints the *faithful* body: the live terminal's exact colored cell grid
     * ([renderTerminalGrid]) below the title strip, then the strip and border.
     * Used for the selected tab's terminal tiles.
     *
     * @param term the live xterm.js terminal (from the pane registry).
     * @param palette resolved colors ([buildTermPalette]).
     */
    fun paintGrid(term: Terminal, palette: TermPalette) {
        val d = canvas.getContext("2d").asDynamic() ?: return
        val topInset = if (stripPx > 0 && stripTitle != null) stripPx * res else 0.0
        d.save()
        renderTerminalGrid(canvas, term, palette, showCursor, topInset, contentPad)
        drawStrip(d)
        finishChrome(d)
        d.restore()
        texture.needsUpdate = true
    }

    /**
     * Rounds the tile's corners and (for bordered tiles) strokes the pane
     * border, shared by [paint] and [paintGrid]. Order matters:
     *  1. Stroke the border — its accent [shadowBlur] glow blooms both inward
     *     and outward from the rounded outline.
     *  2. Mask (destination-in) to the border's **outer** edge — this both
     *     rounds the opaque body/strip corners (so the terminal background never
     *     squares off) *and* clips the outward glow, which would otherwise hit
     *     the square canvas edge (the border sits only ~1px from it) and paint a
     *     lit rectangle around the pane. The inner glow survives, so the active
     *     pane still reads as lit — just without a boxy halo.
     * A borderless card ([roundCorners]) simply rounds to the pane rect.
     *
     * @param d the tile canvas 2D context (dynamic).
     */
    private fun finishChrome(d: dynamic) {
        if (paneBorder) {
            drawPaneBorder(d)
            maskTo(d) { paneOuterRectPath(d) }
        } else if (roundCorners) {
            maskTo(d) { paneRectPath(d) }
        }
    }

    /**
     * The device-px font size [renderTerminalGrid] would use for this tile's
     * live terminal, or `0.0` if it can't be sized. Lets [ThumbSource.repaint]
     * skip full-fidelity for panes whose exact grid would render illegibly small
     * (a narrow tile crammed with a wide terminal), falling back to the
     * re-wrapped thumbnail instead. @see gridFontPx
     */
    fun gridFontPx(term: Terminal): Double {
        val topInset = if (stripPx > 0 && stripTitle != null) stripPx * res else 0.0
        return gridFontPx(canvas, term, topInset, contentPad)
    }

    /**
     * Erases everything already painted on [d] that falls *outside* the rounded
     * sub-path traced by [path], leaving transparent corners — so the opaque
     * terminal background / title strip (and any outward glow) can never square
     * off past the curve. Uses `destination-in` compositing with a `fill()` of
     * the rounded path rather than `clip()`, because a `clip()` of a `roundRect`
     * sub-path was clipping to its square bounding box in this engine (while
     * `stroke()`/`fill()` of the same path round correctly).
     *
     * @param d the tile canvas 2D context (dynamic).
     * @param path traces the rounded keep-region onto [d] (no fill/stroke).
     */
    private inline fun maskTo(d: dynamic, path: () -> Unit) {
        d.globalCompositeOperation = "destination-in"
        d.globalAlpha = 1.0
        d.fillStyle = "#000"
        d.beginPath()
        path()
        d.fill()
        d.globalCompositeOperation = "source-over"
    }

    /**
     * Appends the rounded pane-rect sub-path (the border stroke's *centre-line*)
     * to [d], using the same inset and radius the border stroke uses. Masking to
     * this rounds a borderless card; the border itself strokes along it.
     *
     * @param d the tile canvas 2D context (dynamic).
     */
    private fun paneRectPath(d: dynamic) {
        val lw = (if (paneSelected) 3.0 else 2.0) * res
        val inset = lw / 2.0 + res
        val radius = 6.0 * res
        d.roundRect(
            inset, inset,
            canvas.width.toDouble() - 2.0 * inset, canvas.height.toDouble() - 2.0 * inset,
            radius,
        )
    }

    /**
     * Appends the rounded pane-rect sub-path at the border stroke's *outer* edge
     * (half a line-width out from [paneRectPath]). Masking to this keeps the full
     * border stroke while erasing the outward glow beyond it, so the glow can't
     * paint a boxy halo where it meets the square canvas edge.
     *
     * @param d the tile canvas 2D context (dynamic).
     */
    private fun paneOuterRectPath(d: dynamic) {
        val lw = (if (paneSelected) 3.0 else 2.0) * res
        val inset = res
        val radius = 6.0 * res + lw / 2.0
        d.roundRect(
            inset, inset,
            canvas.width.toDouble() - 2.0 * inset, canvas.height.toDouble() - 2.0 * inset,
            radius,
        )
    }

    /**
     * Draws the opaque themed title strip (background + accent underline +
     * title) across the top of the canvas, shared by [paint] and [paintGrid].
     * Active/selected panes get an accent-tinted header, matching how the real
     * pane chrome distinguishes the focused pane. Painted full-width; the caller
     * ([finishChrome]) then masks the corners round, so the header background
     * follows the curve instead of squaring off outside it.
     *
     * @param d the tile canvas 2D context (dynamic).
     */
    private fun drawStrip(d: dynamic) {
        if (stripPx <= 0 || stripTitle == null) return
        val w = canvas.width.toDouble()
        val h = stripPx * res
        // Fully opaque base — terminal content must never show through.
        d.globalAlpha = 1.0
        d.fillStyle = bg
        d.fillRect(0.0, 0.0, w, h)
        // Accent-tinted header for the active / selected pane.
        if (paneActive || paneSelected) {
            d.globalAlpha = if (paneSelected) 0.22 else 0.13
            d.fillStyle = accent
            d.fillRect(0.0, 0.0, w, h)
        }
        // Accent underline (brighter when active/selected).
        d.globalAlpha = if (paneActive || paneSelected) 1.0 else 0.65
        d.fillStyle = accent
        d.fillRect(0.0, h - res, w, res)
        d.globalAlpha = 1.0
        d.font = "600 ${stripFontPx * res}px $THUMB_FONT"
        d.fillStyle = fg
        d.textAlign = "left"
        d.textBaseline = "middle"
        // Extra left padding so the title clears the pane border; nudged a hair
        // below centre so the cap-line clears the top border curve.
        d.fillText(stripTitle, contentPad + 8.0 * res, h / 2.0 + res)
    }

    /**
     * Strokes a rounded pane outline into the canvas so the border hugs the
     * content exactly and curves/foreshortens with the tile (unlike a separate
     * glow plane, which detaches under the tilt). The active pane gets a steady
     * accent glow, the selected pane a brighter/thicker one, others a faint
     * neutral edge. The caller ([finishChrome]) masks to [paneOuterRectPath]
     * afterward, trimming the outward glow so it can't box off the corners.
     *
     * @param d the tile canvas 2D context (dynamic).
     */
    private fun drawPaneBorder(d: dynamic) {
        val lw = (if (paneSelected) 3.0 else 2.0) * res
        d.save()
        d.globalAlpha = 1.0
        d.lineWidth = lw
        when {
            paneSelected -> { d.strokeStyle = accent; d.shadowColor = accent; d.shadowBlur = 16.0 * res }
            paneActive -> { d.strokeStyle = accent; d.shadowColor = accent; d.shadowBlur = 9.0 * res }
            else -> { d.strokeStyle = fg; d.globalAlpha = 0.22; d.shadowBlur = 0.0 }
        }
        d.beginPath()
        paneRectPath(d)
        d.stroke()
        d.restore()
    }

    /** Frees the GPU texture. */
    fun dispose() {
        runCatching { texture.dispose() }
    }
}

/**
 * One content source for the overview, fanned out to any number of
 * [ThumbView]s (a tab card + that pane's tile). Two flavors, distinguished by
 * [live]:
 *
 *  - **Live** ([live] = `true`): wraps the pane's real xterm.js [Terminal] from
 *    the [terminals] registry — the exact instance the visible pane renders.
 *    Reading its buffer is size-neutral (sends nothing to the server, never
 *    resizes the PTY) and it can drive **full-fidelity** colored tiles. Used
 *    for mounted tabs (the active tab and any previously visited); in demo mode
 *    the registry terms stream the in-process simulation (see [connectDemoPane]).
 *    The term is owned by the registry, so [dispose] must not dispose it.
 *  - **Mirror** ([live] = `false`): a small hidden xterm this source owns, fed
 *    by a read-only preview `/pty` socket (or [attachDemoPreview] in demo mode)
 *    — the same proven pattern the link picker uses. This is the fallback for
 *    tabs that have never been mounted (so have no registry term yet), so the
 *    overview can still preview *every* tab. Mirror sources stay on the cheap
 *    thumbnail path (their fixed 120×40 size isn't the pane's real geometry);
 *    [dispose] tears down the socket, term, and host.
 *
 * Both flavors repaint on the term's `onWriteParsed`, which also lights the
 * activity glow. Deduplicated per pane id in [ovSources].
 *
 * @property term the xterm.js buffer model (live registry term, or owned mirror).
 * @property live `true` for a registry term (full-fidelity capable, not owned).
 * @property hiddenHost off-screen host for a mirror term, else `null`.
 */
internal class ThumbSource(
    val term: Terminal,
    val live: Boolean,
    private val hiddenHost: HTMLElement?,
) {
    /** Preview PTY socket feeding a mirror [term]; `null` for live/demo sources. */
    var socket: WebSocket? = null

    /** Views repainted whenever fresh output arrives. */
    val views = mutableListOf<ThumbView>()

    /** `performance.now()` of the last PTY chunk — drives the glow decay. */
    var lastActivity: Double = -1e9

    /** The `onWriteParsed` disposable, released on [dispose]. */
    private var listener: dynamic = null

    /** Pending rAF handle for a coalesced repaint, if any. */
    private var rafHandle: Int? = null

    /**
     * Subscribes to the term's output so the overview repaints on new data.
     * For live sources this reads the pane's own buffer (no socket); for mirror
     * sources the preview socket writes into [term] and this fires on the parse.
     * Called once after construction.
     */
    fun subscribe() {
        listener = runCatching {
            term.onWriteParsed {
                lastActivity = window.performance.now()
                scheduleRepaint()
            }
        }.getOrNull()
    }

    /**
     * Schedules a coalesced repaint of all attached views (one per animation
     * frame at most). Safe to call for every PTY chunk.
     */
    fun scheduleRepaint() {
        if (rafHandle != null) return
        rafHandle = window.requestAnimationFrame {
            rafHandle = null
            repaint()
        }
    }

    /**
     * Repaints every attached view from the live buffer: full-fidelity views
     * ([ThumbView.fullFidelity]) get the exact colored grid, the rest the cheap
     * re-wrapped transcript (computed once, lazily, and shared).
     */
    fun repaint() {
        var lines: List<String>? = null
        for (view in views) {
            // Full fidelity only when the exact grid stays legible; a narrow tile
            // crammed with a wide/tall terminal would shrink the glyphs to
            // nothing, so fall back to the re-wrapped (fixed-font) thumbnail.
            val fullLegible = view.fullFidelity && ovPalette != null &&
                view.gridFontPx(term) >= MIN_GRID_FONT_PX
            if (fullLegible) {
                runCatching { view.paintGrid(term, ovPalette!!) }
            } else {
                if (lines == null) lines = runCatching { readLogicalLines(term) }.getOrDefault(emptyList())
                view.paint(lines)
            }
        }
    }

    /**
     * Cancels pending repaints and unsubscribes. A live term is owned by the
     * pane registry and left running; a mirror term (with its socket and host)
     * is fully torn down here.
     */
    fun dispose() {
        rafHandle?.let { window.cancelAnimationFrame(it) }
        rafHandle = null
        runCatching { listener?.dispose() }
        listener = null
        if (!live) {
            runCatching { socket?.close() }
            socket = null
            runCatching { term.dispose() }
            runCatching { hiddenHost?.remove() }
        }
    }
}
