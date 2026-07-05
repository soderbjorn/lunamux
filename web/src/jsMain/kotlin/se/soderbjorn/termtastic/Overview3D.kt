/**
 * 3D tab overview for the Termtastic web/Electron frontend — the shared core.
 *
 * A task-switcher-style full-screen overlay presenting every visible tab and its
 * panes in 3D. This file owns everything **style-independent**: the cached
 * renderer / scene / camera / overlay, capturing each pane's live content into a
 * textured plane (terminal grid, file listing, git status — every pane type),
 * selection state ([ovSelected] / [ovPaneSelected]), and the open / close /
 * render-loop / input-routing lifecycle. The *spatial arrangement and motion* is
 * pluggable via [Overview3DStyle]: [CarouselStyle] (the turning ring),
 * [RotundaStyle] (inside a cylinder, the default), [ExposeStyle] (real-layout →
 * grid zoom), [FlipStackStyle] (a riffled deck receding in depth),
 * [CorridorStyle] (a walkable gallery hall), [OrbitStyle] (worlds in space with
 * a curved fly-through), and [VertigoStyle] (a glass tower with a dolly-zoom),
 * chosen at open time from [experimental3dSwitcherStyle]. Both keyboard
 * and mouse/trackpad drive navigation (see [openOverview3d] and [ensureOverlay]).
 *
 * The carousel — described below — is the reference style; the same capture and
 * lifecycle machinery serves all three. Content is sourced directly from each
 * pane's **live** xterm.js instance in the [terminals] registry — the exact
 * buffer the visible pane renders — so no second `/pty` socket is opened and
 * the PTY is never resized (reading a buffer is size-neutral). A [ThumbSource]
 * subscribes to the live term's `onWriteParsed` and repaints on output, which
 * also drives a fading accent glow so active sessions "breathe" in the ring.
 * Sources are deduplicated per pane id in [ovSources]; one pane can feed both
 * a tab card and its pane tile.
 *
 * **Fidelity:** the **selected** (front) tab's terminal pane tiles render at
 * full fidelity — the pane's exact colored cell grid (ANSI/256/truecolor +
 * attributes + cursor) via [renderTerminalGrid] / [buildTermPalette] — while
 * the rest of the ring keeps the cheaper monochrome re-wrapped thumbnail
 * ([renderThumbnail]) for cost. [applyFidelity] promotes/demotes tiles as the
 * selection rotates.
 *
 * **Split-into-panes:** when a card settles at the front of the ring it
 * crossfades into its individual panes — one tile per pane, arranged exactly
 * per the tab's *real* layout geometry (read live from the toolkit via
 * [AppShellHandle.currentLayoutStateJson], falling back to the wire-model
 * [Pane] rectangle). The tiles ease apart, lift toward the camera, and bow
 * into a shallow inward curve (see [CURVE_K]); the tab's focused pane keeps a
 * persistent accent ring. Rotating away reassembles them into the flat card.
 *
 * Interaction model:
 *  - `←`/`→` or mouse wheel rotates the ring one card at a time.
 *  - `Enter` (or clicking the front card) activates that tab: the command is
 *    sent immediately and the camera *dives into* the card while the overlay
 *    fades, landing on the real tab.
 *  - Clicking a pane tile of the front card activates the tab AND focuses +
 *    raises that specific pane.
 *  - Clicking a non-front card rotates it to the front; clicking empty space
 *    or pressing `Esc` closes the overview.
 *
 * Theming: thumbnail colors come from the live xterm theme, the glow/halo
 * from the app accent, and the overlay backdrop gradient is derived from
 * the theme background ([applyThemedBackdrop]) so entering the 3D view
 * feels like the same app, not a different one.
 *
 * Performance: the WebGL renderer, scene, and camera are created once and
 * cached for the app's lifetime — [prewarmOverview3d] pays context creation
 * and first-shader compilation at boot, so opening the overview later is
 * just "build cards + unhide". Per-open resources (live-term subscriptions,
 * textures, tile geometry) are torn down on close. The
 * render loop traps per-frame exceptions so a single bad frame can never
 * kill the animation (a lesson learned: see [pickUserData]).
 *
 * Wired from [bootViaToolkitShell]: [registerOverview3dHotkey] binds the
 * configurable toggle chord (default ⌥⌘→) to [toggleOverview3d].
 *
 * @see LinkThumbnailRenderer
 * @see PaneTypeModal
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

/** DOM id of the full-screen overlay element (also its CSS hook). */
private const val OVERLAY_ID = "overview3d-overlay"

/**
 * Reference card plane size in world units (3.2 : 2 matches the canvas aspect).
 * The tab-card thumbnail and the pane-tile world sizes ([buildPaneTile]) are
 * derived from this; styles read [PaneTile.worldW]/[PaneTile.worldH] rather than
 * these constants directly.
 */
internal const val CARD_W = 3.2
internal const val CARD_H = 2.0

/**
 * Supersampling factor for thumbnail textures. Canvas backing stores are
 * sized this many times their logical dimensions and painted with a matching
 * [renderThumbnail] `scale`, so text density is unchanged but each glyph gets
 * this many times the pixels — crisp instead of jagged when a card fills the
 * viewport on a high-DPI display. Trades GPU texture memory for sharpness.
 */
private const val RES = 3.0

/** Tab-card canvas *logical* size (px); physical backing store is ×[RES]. */
private const val CARD_CANVAS_W = 512
private const val CARD_CANVAS_H = 320

/** Logical height (px, ×[RES] physical) of a card's tab-title bar. */
private const val TITLE_STRIP_PX = 26

/** Logical height (px, ×[RES] physical) of a tile's pane-title bar. */
private const val TILE_STRIP_PX = 18

/**
 * Minimum device-px glyph size ([RES]-supersampled) below which a tile falls back
 * from the exact colored terminal grid ([renderTerminalGrid]) to the re-wrapped
 * monochrome thumbnail ([renderThumbnail]). Kept deliberately low: the 3D styles
 * (rotunda/exposé especially) show panes up close, where the accurate, correctly
 * sized, *colored* cell grid is the whole point — so we render it for panes of
 * essentially any real size and only demote a degenerately tiny tile (a wide/tall
 * terminal crammed into a sliver) where a micro-grid would be pure noise. Read in
 * [ThumbSource.repaint].
 */
private const val MIN_GRID_FONT_PX = 4.0

/** Monospace font stack for thumbnail title bars / placeholders. */
private const val THUMB_FONT = "Menlo, Monaco, 'Courier New', monospace"

/**
 * Duration of the dive-in activation transition (ms; matches the CSS fade).
 * The core closes the overview when this elapses after [beginDive]; styles may
 * animate a landing camera over the same window.
 */
internal const val DIVE_MS = 340.0

/**
 * The camera's default vertical field of view (degrees), matching the
 * [PerspectiveCamera] constructed in [ensureRenderer]. Reset before every open
 * (see [openOverview3d]) so a style that animates `fov` (the vertigo dolly-zoom,
 * the orbit warp) can't leak a warped lens into the next style's open.
 */
internal const val OVERVIEW_BASE_FOV = 50.0

/** World-units gap shaved off each pane tile so split panes read as tiles. */
private const val TILE_GAP = 0.05

/** How far apart (scale factor) tiles spread when the card is split. */
private const val SPLIT_SPREAD = 1.28

/** How far tiles lift toward the camera when split (world units). */
private const val SPLIT_LIFT = 0.16

/** Plane subdivisions per axis for the dished pane geometry ([dishGeometry]). */
private const val DISH_SEGMENTS = 14

/** Depth (world units) the pane corners recede in the dish curvature. */
private const val DISH_DEPTH = 0.34

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

/**
 * What a pane tile shows, which decides how its thumbnail is sourced and
 * painted: a live PTY transcript, a fetched file-browser listing, a fetched
 * git status, or a static placeholder.
 */
internal enum class TileKind { TERMINAL, FILE_BROWSER, GIT, OTHER }

/**
 * A pane's fractional rectangle within its tab (0..1 of the tab area) plus
 * stacking order. Resolved by [resolvePaneRects] from the toolkit's live
 * layout state, falling back to the wire-model [Pane] geometry.
 *
 * @property x left edge. @property y top edge. @property w width.
 * @property h height. @property z stacking order (higher = on top).
 */
private class PaneRect(val x: Double, val y: Double, val w: Double, val h: Double, val z: Int)

/**
 * One pane tile of a card: a small textured plane positioned per the pane's
 * real layout rectangle, child of the card mesh so it inherits the carousel
 * transform. Animated between its assembled ("home", flush with the card)
 * and split (spread + lifted) placements by the card's split factor.
 *
 * @property paneId server pane (leaf) id — dispatched on click / Enter.
 * @property paneTitle pane title, shown in the overlay readout when selected.
 * @property mesh the tile plane (child of the card mesh).
 * @property material tile material; opacity follows the split factor.
 * @property geometry per-tile plane geometry (sizes differ per pane; bent into
 *   a shallow dish for real curvature).
 * @property view the tile's thumbnail view (its canvas carries the pane border
 *   and header, so no separate glow plane is needed).
 * @property source live source feeding [view], or `null` for placeholders.
 * @property homeX/homeY/homeZ assembled position (card-local).
 * @property splitX/splitY/splitZ split position (card-local).
 * @property worldW/worldH the tile plane's world-space size. Their ratio is the
 *   pane's **true on-screen aspect** (it fills its pane, so the pane rectangle's
 *   aspect is the content's). Styles other than the carousel read these to
 *   letterbox the pane into their own slot — scaling uniformly by
 *   `min(slotW / worldW, slotH / worldH)` — so a pane is never stretched away
 *   from the geometry we capture it at.
 */
internal class PaneTile(
    val paneId: String,
    val paneTitle: String,
    val contentKind: TileKind,
    val mesh: Mesh,
    val material: MeshBasicMaterial,
    val geometry: PlaneGeometry,
    val view: ThumbView,
    val source: ThumbSource?,
    val homeX: Double, val homeY: Double, val homeZ: Double,
    val splitX: Double, val splitY: Double, val splitZ: Double,
    val worldW: Double, val worldH: Double,
) {
    /** Releases the tile's GPU resources (textures, materials, geometries). */
    fun dispose() {
        view.dispose()
        runCatching { material.dispose() }
        runCatching { geometry.dispose() }
    }
}

/**
 * One card on the ring: a tab's identity, its full-card thumbnail view, the
 * glow halo, and the per-pane tiles it splits into at the front.
 *
 * Created by [openOverview3d] (one per visible tab), disposed by
 * [closeOverview3d]. Live PTY plumbing lives in the shared [ThumbSource]s,
 * not here.
 *
 * @property tabId server tab id — dispatched via [WindowCommand.SetActiveTab].
 * @property title tab title (canvas strip + overlay readout).
 * @property mesh the card plane in the ring (userData carries the index).
 * @property cardMaterial card material; fades out as the card splits.
 * @property glowMaterial halo material; opacity animated per frame.
 * @property cardView the full-card thumbnail view.
 * @property source live source feeding [cardView], or `null` (placeholder).
 * @property tiles the pane tiles, in tab pane order.
 * @property focusedPaneId the tab's active pane, marked with a persistent
 *   accent ring in the split view.
 */
internal class OverviewCard(
    val tabId: String,
    val title: String,
    val mesh: Mesh,
    val cardMaterial: MeshBasicMaterial,
    val glowMaterial: MeshBasicMaterial,
    val cardView: ThumbView,
    val source: ThumbSource?,
    val tiles: List<PaneTile>,
    val focusedPaneId: String?,
) {
    /** Split factor, eased 0 (assembled card) → 1 (exploded panes). */
    var split: Double = 0.0

    /**
     * Reveal factor, eased 0 (flat whole-tab thumbnail) → 1 (pane tiles shown,
     * assembled and flush). Driven by how centered this card is on the ring (see
     * [REVEAL_ARC]) rather than by [split], so the pane borders + active-pane
     * accent header dissolve in *with* the incoming rotation — as one unit with
     * the panes — instead of popping in after the flat card has already split.
     */
    var reveal: Double = 0.0

    /** Latest activity timestamp across the card's + tiles' sources. */
    fun latestActivity(): Double {
        var last = source?.lastActivity ?: -1e9
        for (tile in tiles) {
            val a = tile.source?.lastActivity ?: continue
            if (a > last) last = a
        }
        return last
    }

    /** Releases the card's GPU resources (sources are shared — not here). */
    fun dispose() {
        cardView.dispose()
        runCatching { cardMaterial.dispose() }
        runCatching { glowMaterial.dispose() }
        for (tile in tiles) tile.dispose()
    }
}

/* ---------------------------------------------------------------------- */
/* Pluggable switcher styles.                                              */
/*                                                                          */
/* The overview core owns everything style-independent: the renderer /      */
/* scene / camera / overlay, capturing each pane's live content into a      */
/* [ThumbView] (terminal grid, file listing, git status — all pane types),  */
/* selection state ([ovSelected] / [ovPaneSelected]), and the open/close/    */
/* render-loop/event-routing lifecycle. A style only decides the *spatial*   */
/* arrangement and motion: where the [OverviewCard]s and their [PaneTile]s   */
/* sit in the scene, how they animate, how navigation maps, and what the     */
/* camera does. Seven ship — [CarouselStyle], [RotundaStyle], [ExposeStyle], */
/* [FlipStackStyle], [CorridorStyle], [OrbitStyle], [VertigoStyle] — chosen  */
/* at open time from [experimental3dSwitcherStyle].                          */
/* ---------------------------------------------------------------------- */

/**
 * Directional navigation intent, produced by the core key handler and handed to
 * the active [Overview3DStyle] so every style shares one vocabulary while
 * interpreting it in its own geometry (e.g. the carousel treats LEFT/RIGHT as
 * "previous/next tab", the rotunda as "spin the wall", the exposé grid as
 * "move one column"). [PANE_NEXT]/[PANE_PREV] come from Tab / Shift+Tab.
 */
internal enum class OvNav { LEFT, RIGHT, UP, DOWN, PANE_NEXT, PANE_PREV }

/**
 * One spatial arrangement + interaction model for the 3D overview. Implemented
 * as a stateless-per-open object; per-open scene state it owns (groups, angles,
 * gesture latches) lives in the implementation's own module vars, reset in
 * [build]. The core calls these hooks against the shared state it has already
 * populated (the [ovCards], [ovSelected], [ovPaneSelected], the cached
 * [ovScene] / [ovCamera]).
 *
 * Contract for implementations:
 *  - **Aspect ratio:** never distort a pane. Each [PaneTile] carries its true
 *    world size ([PaneTile.worldW]/[PaneTile.worldH]); scale tiles *uniformly*
 *    to fit a slot (letterbox), never set a non-uniform scale.
 *  - **All pane types:** tiles are pre-built with terminal / file-browser / git
 *    content by the core — just place `card.tiles`; don't special-case kinds.
 *  - **Both input modalities:** implement [nav] (keyboard) *and* [wheel] / [drag]
 *    / [click] (mouse/trackpad) so tabs and panes can be switched either way.
 *  - **Selection:** drive [ovSelected] (front tab) and [ovPaneSelected] (pane
 *    within it) through the shared [stepSelection] / [cyclePane] / [selectCard]
 *    helpers so [applyFidelity] and the title readout stay in sync.
 *  - **Activation:** call [beginDive] with [selectedPaneId] to open the pick.
 */
internal interface Overview3DStyle {
    /**
     * Builds this style's scene graph for the just-opened overview: parent the
     * pre-built [ovCards] / their [PaneTile] meshes into [scene] and position
     * them. [ovCards], [ovSelected] and [ovPaneSelected] are already set.
     *
     * @param scene the cached scene to add objects to.
     * @param camera the cached camera (usually configured in [resetCamera]).
     */
    fun build(scene: Scene, camera: PerspectiveCamera)

    /**
     * Positions the camera for the opening pose (called once after [build]).
     *
     * @param camera the cached camera.
     */
    fun resetCamera(camera: PerspectiveCamera)

    /**
     * Per-frame update, called each animation frame before the core renders.
     * Eases layout/motion toward the current selection and, while a dive is in
     * progress ([ovDiveStart] not NaN), may animate the camera (the core closes
     * the overview when the dive completes).
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera.
     */
    fun tick(now: Double, camera: PerspectiveCamera)

    /**
     * Handles a directional keyboard navigation.
     *
     * @param dir the intent (see [OvNav]).
     */
    fun nav(dir: OvNav)

    /**
     * Handles a wheel / trackpad delta along the dominant axis. Discrete styles
     * (carousel) latch this into single steps; continuous styles (rotunda floor
     * ride, exposé zoom) integrate it. Default: ignore.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (for gesture timing).
     */
    fun wheel(delta: Double, nowMs: Double) {}

    /**
     * Handles a pointer drag while the button is held (look-around / orbit).
     * Default: ignore.
     *
     * @param dx horizontal movement (px) since the last event.
     * @param dy vertical movement (px) since the last event.
     */
    fun drag(dx: Double, dy: Double) {}

    /**
     * The meshes the core should raycast for click/hover picking (nearest hit's
     * `userData` wins). Typically the cards, plus the front card's pane tiles.
     *
     * @return the pickable meshes for the current frame.
     */
    fun pickables(): Array<dynamic>

    /**
     * Handles a click on the picked `userData` (`null` = empty space). The
     * payload carries `index` (card) and, on a tile, `paneId`.
     *
     * @param userData the nearest raycast hit's payload, or `null`.
     */
    fun click(userData: dynamic)

    /**
     * Releases scene objects this style added (groups, decorations). The core
     * disposes the shared cards / tiles / sources itself.
     *
     * @param scene the cached scene to remove objects from.
     */
    fun teardown(scene: Scene)

    /**
     * The one-line key/gesture hint shown in the overlay footer while this style
     * is active. Each style's input mapping differs (the carousel rotates a ring,
     * the rotunda spins a wall and rides floors, the exposé zooms a grid), so the
     * footer must describe *this* style's controls rather than a single generic
     * line. Defaults to the carousel's mapping; other styles override.
     *
     * @return the footer hint text for this style.
     */
    fun hint(): String = "← → tabs · ↑ ↓ panes · ⏎ open · click a pane to jump · esc close"
}

/* ---------------------------------------------------------------------- */
/* Module state. The renderer / scene / camera / overlay are app-lifetime  */
/* caches (see file kdoc); everything else is rebuilt per open.            */
/* ---------------------------------------------------------------------- */

/** App-lifetime cached WebGL renderer (created by [ensureRenderer]). */
private var ovRenderer: WebGLRenderer? = null

/** App-lifetime cached scene. */
private var ovScene: Scene? = null

/** App-lifetime cached camera. */
private var ovCamera: PerspectiveCamera? = null

/** App-lifetime cached overlay element (hidden while closed). */
private var ovOverlay: HTMLElement? = null

/** Selected-tab title readout at the bottom of the overlay. */
private var ovTitleEl: HTMLElement? = null

/** Footer key/gesture hint element; its text is set per style each open. */
private var ovHintEl: HTMLElement? = null

/**
 * The active switcher style for the current open, chosen in [openOverview3d]
 * from [experimental3dSwitcherStyle]; `null` while closed. Every lifecycle and
 * input hook routes through it.
 */
private var ovActiveStyle: Overview3DStyle? = null

/**
 * Per-open card list, in config tab order. Populated by [buildCards]; the active
 * [Overview3DStyle] parents these into the scene and arranges them.
 */
internal val ovCards = mutableListOf<OverviewCard>()

/** Per-open live content sources, deduplicated by pane id. */
private val ovSources = mutableMapOf<String, ThumbSource>()

/**
 * Resolved terminal color palette for the current open ([buildTermPalette]),
 * used by full-fidelity tiles. Rebuilt each open (the theme is stable during a
 * session) and cleared on close.
 */
private var ovPalette: TermPalette? = null

/** Shared card plane geometry (per open). */
private var ovCardGeometry: PlaneGeometry? = null

/** Shared glow plane geometry (per open). */
private var ovGlowGeometry: PlaneGeometry? = null

/**
 * Index (into [ovCards]) of the card currently selected — the front card on the
 * carousel ring, the current floor in the rotunda, the tab in front in exposé.
 * Shared across styles so [applyFidelity] and the readout stay style-agnostic.
 */
internal var ovSelected = 0

/**
 * Index (into the front card's [OverviewCard.tiles]) of the currently
 * highlighted pane, or `-1` for "the whole tab, no specific pane". Driven by
 * ↑/↓/Tab and by hovering a pane tile; reset to `-1` whenever the front tab
 * changes. `Enter` jumps to this pane (or the whole tab when `-1`).
 */
internal var ovPaneSelected = -1

/** rAF handle of the render loop while open. */
private var ovAnimHandle: Int? = null

/** True between [openOverview3d] and the end of [closeOverview3d]. */
private var ovOpen = false

/**
 * `performance.now()` when the dive-in activation started; NaN = not diving.
 * Styles read this to freeze input and animate a landing camera during the
 * transition; the core closes the overview once [DIVE_MS] elapses.
 */
internal var ovDiveStart = Double.NaN

/** Capture-phase key handler while open (removed on close). */
private var ovKeyHandler: ((Event) -> Unit)? = null

/** Window resize handler while open (removed on close). */
private var ovResizeHandler: ((Event) -> Unit)? = null

/** Last pointer position in normalized device coordinates (for hover/picking). */
internal val ovPointerNdc = Vector2(0.0, -2.0)

/** Shared raycaster for click/hover picking (see [raycastPointer]). */
private val ovRaycaster = Raycaster()

/**
 * Set on every pointer move, consumed once per frame. Gates hover-driven pane
 * selection on actual pointer movement, so a mouse resting over one tile
 * doesn't keep yanking the highlight back while you navigate with ↑/↓.
 */
internal var ovPointerMoved = false

/** True while a pointer button is held over the overlay (drag in progress). */
private var ovPointerDown = false

/** Last pointer client X/Y, for computing per-move drag deltas. */
private var ovPointerLastX = 0.0
private var ovPointerLastY = 0.0

/**
 * Accumulated `|dx|+|dy|` (px) since pointer-down. A release under
 * [OV_CLICK_MOVE_THRESHOLD] is treated as a click (select/activate); past it the
 * gesture was a drag (look-around / orbit) and no click fires — so styles that
 * both drag *and* click never mistake one for the other.
 */
private var ovPointerMovedPx = 0.0

/** Pointer-travel (px) under which a release counts as a click, not a drag. */
private const val OV_CLICK_MOVE_THRESHOLD = 7.0

/**
 * Toggles the 3D overview: opens it if closed, closes it if open. The
 * target of the configurable ⌥⌘→ hotkey ([registerOverview3dHotkey] in
 * [TermtasticToolkitBootstrap]).
 *
 * This is the single gate for the experimental **3D app switcher**: every
 * entry point (hotkey, topbar globe button, Electron menu) routes here, so
 * when [isExperimental3dSwitcherEnabled] is off the feature is fully inert
 * regardless of a stale binding or hidden button. The toolbar button's
 * *visibility* is handled separately by [applyOverview3dChromeVisibility].
 *
 * @see openOverview3d
 * @see closeOverview3d
 * @see isExperimental3dSwitcherEnabled
 */
internal fun toggleOverview3d() {
    if (!isExperimental3dSwitcherEnabled()) return
    if (ovOpen) closeOverview3d() else openOverview3d()
}

/**
 * Pre-warms the app-lifetime 3D machinery at boot: creates the WebGL
 * renderer, scene, and camera, and renders one empty frame so context
 * creation and the first shader compile happen *now* (hidden, at startup)
 * instead of on the first hotkey press. Chromium additionally caches
 * compiled shaders on disk, so even this cost shrinks across app launches.
 *
 * Called once from [bootViaToolkitShell] (deferred with a timeout so it
 * never competes with first paint). Idempotent.
 */
internal fun prewarmOverview3d() {
    runCatching {
        val r = ensureRenderer()
        r.setSize(2, 2, false)
        r.render(ovScene!!, ovCamera!!)
    }
}

/**
 * Creates (or returns) the cached renderer/scene/camera triple.
 *
 * @return the app-lifetime [WebGLRenderer]; [ovScene] and [ovCamera] are
 *   non-null after this returns.
 */
private fun ensureRenderer(): WebGLRenderer {
    ovRenderer?.let { return it }
    val renderer = WebGLRenderer(json("antialias" to true, "alpha" to true))
    renderer.setPixelRatio(window.devicePixelRatio)
    ovRenderer = renderer
    ovScene = Scene()
    ovCamera = PerspectiveCamera(OVERVIEW_BASE_FOV, 1.0, 0.1, 100.0)
    return renderer
}

/**
 * Builds (or returns) the cached full-screen overlay: a fixed-position
 * backdrop hosting the renderer's canvas, the selected-tab title readout,
 * and the key-hint footer. Hidden via `display: none` while closed so
 * reopening never re-creates DOM.
 *
 * @return the overlay element, appended to `document.body`.
 */
private fun ensureOverlay(): HTMLElement {
    ovOverlay?.let { return it }
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = OVERLAY_ID
    overlay.tabIndex = -1

    overlay.appendChild(ensureRenderer().domElement)

    val title = document.createElement("div") as HTMLElement
    title.className = "overview3d-title"
    overlay.appendChild(title)
    ovTitleEl = title

    val hint = document.createElement("div") as HTMLElement
    hint.className = "overview3d-hint"
    // Text is set per active style in openOverview3d (each style's controls
    // differ); seed with the carousel default so it's never blank pre-open.
    hint.textContent = "← → tabs · ↑ ↓ panes · ⏎ open · click a pane to jump · esc close"
    overlay.appendChild(hint)
    ovHintEl = hint

    // Wheel / trackpad: forward the dominant-axis delta to the active style,
    // which decides what it means — the carousel latches it into single tab
    // steps, the rotunda rides between floors, the exposé zooms continuously.
    // passive=false so the page never scrolls and macOS back/forward-swipe
    // navigation is suppressed.
    overlay.addEventListener("wheel", { ev: Event ->
        ev.preventDefault()
        if (!ovDiveStart.isNaN()) return@addEventListener
        val we = ev as WheelEvent
        val delta = if (abs(we.deltaX) > abs(we.deltaY)) we.deltaX else we.deltaY
        ovActiveStyle?.wheel(delta, window.performance.now())
    }, json("passive" to false))

    // Pointer move: always update the hover NDC (the render loop highlights the
    // pane under the cursor). While a button is held it's also a drag — feed the
    // per-move delta to the style's drag() (look-around / orbit) and tally the
    // travel so the release can tell a click from a drag.
    overlay.addEventListener("pointermove", { ev: Event ->
        val me = ev as MouseEvent
        ovPointerNdc.x = me.clientX / window.innerWidth.toDouble() * 2.0 - 1.0
        ovPointerNdc.y = -(me.clientY / window.innerHeight.toDouble() * 2.0 - 1.0)
        ovPointerMoved = true
        if (ovPointerDown && ovDiveStart.isNaN()) {
            val dx = me.clientX - ovPointerLastX
            val dy = me.clientY - ovPointerLastY
            ovPointerLastX = me.clientX.toDouble()
            ovPointerLastY = me.clientY.toDouble()
            ovPointerMovedPx += abs(dx) + abs(dy)
            ovActiveStyle?.drag(dx, dy)
        }
    })

    // Pointer down: arm a gesture and capture the pointer so drags that leave
    // the overlay keep flowing here.
    overlay.addEventListener("pointerdown", { ev: Event ->
        if (!ovDiveStart.isNaN()) return@addEventListener
        val me = ev as MouseEvent
        ovPointerDown = true
        ovPointerMovedPx = 0.0
        ovPointerLastX = me.clientX.toDouble()
        ovPointerLastY = me.clientY.toDouble()
        runCatching { overlay.asDynamic().setPointerCapture(me.asDynamic().pointerId) }
    })

    // Pointer up: a release that barely moved is a click — raycast the style's
    // pickables and hand the hit (or `null` for empty space) to its click(); a
    // release that dragged past the threshold was a look-around, so no click
    // fires (the style already consumed the motion via drag()).
    overlay.addEventListener("pointerup", { ev: Event ->
        if (!ovPointerDown) return@addEventListener
        ovPointerDown = false
        if (!ovDiveStart.isNaN()) return@addEventListener
        val me = ev as MouseEvent
        ovPointerNdc.x = me.clientX / window.innerWidth.toDouble() * 2.0 - 1.0
        ovPointerNdc.y = -(me.clientY / window.innerHeight.toDouble() * 2.0 - 1.0)
        if (ovPointerMovedPx < OV_CLICK_MOVE_THRESHOLD) {
            val ud = raycastPointer(ovActiveStyle?.pickables() ?: emptyArray<dynamic>())
            ovActiveStyle?.click(ud)
        }
    })

    document.body?.appendChild(overlay)
    ovOverlay = overlay
    return overlay
}

/**
 * Opens the overview: builds one card (plus pane tiles) per visible tab from
 * the current [latestWindowConfig], lays them out on the ring, attaches each
 * pane's live-term source, promotes the front tab to full fidelity, wires the
 * key handlers, and starts the render loop. No-op when already open or before
 * the first config arrives.
 *
 * @see closeOverview3d
 */
internal fun openOverview3d() {
    if (ovOpen) return
    val cfg = latestWindowConfig ?: return
    val tabs = cfg.tabs.filter { !it.isHidden }

    ovOpen = true
    ovDiveStart = Double.NaN
    ovPointerDown = false
    ovPointerMovedPx = 0.0
    val renderer = ensureRenderer()
    val scene = ovScene!!
    val camera = ovCamera!!
    val overlay = ensureOverlay()
    overlay.classList.remove("overview3d-closing")
    overlay.style.display = ""

    // Theme-integrated colors, all from the live theme so the 3D view reads
    // as the same app rather than a separate blue-tinted world:
    //  - thumbnails use the xterm foreground/background (as the link picker),
    //  - the glow, title-bar underline, and backdrop tint use the theme
    //    *accent* — `buildXtermTheme().cursor` is `ResolvedTheme.accent`, so a
    //    yellow theme yields a yellow glow, a green theme a green glow, etc.
    //    (The `--accent` CSS var is a fixed UI accent that does NOT track the
    //    active theme — reading it was the source of the stray blue.)
    val theme = buildXtermTheme()
    val fg = (theme.foreground as? String) ?: "#d4d4d4"
    val bg = (theme.background as? String) ?: "#1e1e1e"
    val accent = (theme.cursor as? String)?.takeIf { it.isNotBlank() } ?: fg
    applyThemedBackdrop(overlay, bg, accent)

    // Resolved 256-color palette for full-fidelity terminal tiles (front tab).
    ovPalette = buildTermPalette()

    // Shared plane geometries consumed by buildCardContent (the tab card + its
    // glow halo). Pane tiles get their own per-pane geometry in buildPaneTile.
    ovCardGeometry = PlaneGeometry(CARD_W, CARD_H)
    ovGlowGeometry = PlaneGeometry(CARD_W + 0.24, CARD_H + 0.24)

    // Build style-independent content for every visible tab (thumbnails, live
    // sources, pane tiles for all pane types), then seed the selection on the
    // active tab and its focused pane so the first ↑/↓/Tab steps to a neighbour.
    buildCards(tabs, fg, bg, accent)
    ovSelected = tabs.indexOfFirst { it.id == cfg.activeTabId }.takeIf { it >= 0 } ?: 0
    ovPaneSelected = focusedTileIndex(ovSelected)

    // Hand off to the chosen style: it arranges the cards/tiles in the scene
    // and poses the camera. Everything above is shared across all styles. Reset
    // the lens + roll first so a prior style that animated them (the vertigo
    // dolly-zoom's fov, the orbit bank's up vector) can't leak into this open.
    camera.fov = OVERVIEW_BASE_FOV
    camera.up.set(0.0, 1.0, 0.0)
    camera.updateProjectionMatrix()
    val style = styleFor(experimental3dSwitcherStyle())
    ovActiveStyle = style
    style.build(scene, camera)
    style.resetCamera(camera)
    ovHintEl?.textContent = style.hint()

    updateTitleReadout()
    applyFidelity()

    layoutRenderer()
    val resizeHandler: (Event) -> Unit = { layoutRenderer() }
    ovResizeHandler = resizeHandler
    window.addEventListener("resize", resizeHandler)

    // Capture-phase keys: the overlay steals focus from the terminal on open,
    // but capture also guarantees Escape/arrows never leak to other handlers
    // underneath while the overview is up. Directional keys route to the active
    // style (which maps them to its own geometry); Enter/Escape are uniform.
    val keyHandler: (Event) -> Unit = handler@{ ev ->
        if (!ovOpen || !ovDiveStart.isNaN()) return@handler
        val ke = ev as KeyboardEvent
        val st = ovActiveStyle
        when (ke.key) {
            "Escape" -> { ke.preventDefault(); ke.stopPropagation(); closeOverview3d() }
            "ArrowLeft" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.LEFT) }
            "ArrowRight" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.RIGHT) }
            "ArrowDown" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.DOWN) }
            "ArrowUp" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.UP) }
            "Tab" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(if (ke.shiftKey) OvNav.PANE_PREV else OvNav.PANE_NEXT) }
            "Enter" -> { ke.preventDefault(); ke.stopPropagation(); beginDive(selectedPaneId()) }
        }
    }
    ovKeyHandler = keyHandler
    window.addEventListener("keydown", keyHandler, true)
    overlay.focus()

    // Fetch + paint file-browser / git tiles (terminals stream on their own).
    primeDataTiles()

    startRenderLoop(renderer, scene, camera)
}

/**
 * Builds the style-independent content for every visible tab into [ovCards]:
 * one [OverviewCard] per tab (via [buildCardContent]), tagging each card mesh
 * and pane-tile mesh with the `userData` the raycaster reads for click/hover
 * picking (`index` = card index; `paneId` on tiles). The active style then
 * places these in the scene.
 *
 * @param tabs the visible tabs, in order.
 * @param fg thumbnail foreground color. @param bg background color.
 * @param accent theme accent color.
 */
private fun buildCards(tabs: List<TabConfig>, fg: String, bg: String, accent: String) {
    tabs.forEachIndexed { i, tab ->
        val card = buildCardContent(tab, fg, bg, accent)
        card.mesh.userData = json("index" to i)
        for (tile in card.tiles) tile.mesh.userData = json("index" to i, "paneId" to tile.paneId)
        ovCards.add(card)
    }
}

/**
 * Resolves the persisted style id (see [experimental3dSwitcherStyle]) to its
 * [Overview3DStyle] implementation, defaulting to the carousel for any
 * unrecognised value.
 *
 * @param id one of [OVERVIEW_3D_STYLE_CAROUSEL] / [OVERVIEW_3D_STYLE_ROTUNDA] /
 *   [OVERVIEW_3D_STYLE_EXPOSE] / [OVERVIEW_3D_STYLE_FLIPSTACK] /
 *   [OVERVIEW_3D_STYLE_CORRIDOR] / [OVERVIEW_3D_STYLE_ORBIT] /
 *   [OVERVIEW_3D_STYLE_VERTIGO].
 * @return the matching style object.
 */
private fun styleFor(id: String): Overview3DStyle = when (id) {
    OVERVIEW_3D_STYLE_ROTUNDA -> RotundaStyle
    OVERVIEW_3D_STYLE_EXPOSE -> ExposeStyle
    OVERVIEW_3D_STYLE_FLIPSTACK -> FlipStackStyle
    OVERVIEW_3D_STYLE_CORRIDOR -> CorridorStyle
    OVERVIEW_3D_STYLE_ORBIT -> OrbitStyle
    OVERVIEW_3D_STYLE_VERTIGO -> VertigoStyle
    else -> CarouselStyle
}

/**
 * Resolves the fractional layout rectangle of every pane in [tab], in pane
 * order. Primary source is the toolkit's *live* layout state
 * ([AppShellHandle.currentLayoutStateJson] — reflects in-session drags);
 * per-pane fallback is the wire-model [Pane] geometry. A maximized pane
 * reports the full tab area on top, mirroring how the real layout draws it.
 *
 * @param tab the tab whose panes to resolve.
 * @return one [PaneRect] per entry of `tab.panes`, same order.
 */
private fun resolvePaneRects(tab: TabConfig): List<PaneRect> {
    val tabGeom: dynamic = runCatching {
        val jsonStr = appShellHandle?.currentLayoutStateJson() ?: return@runCatching null
        val parsed: dynamic = JSON.parse(jsonStr)
        val byTab = parsed.geometryByTab
        if (byTab == null) null else byTab[tab.id]
    }.getOrNull()

    return tab.panes.map { pane ->
        val fromToolkit: PaneRect? = runCatching {
            val o = if (tabGeom == null) null else tabGeom[pane.leaf.id]
            if (o == null || o == undefined) return@runCatching null
            if (o.isMaximized == true) {
                PaneRect(0.0, 0.0, 1.0, 1.0, Int.MAX_VALUE)
            } else {
                PaneRect(
                    (o.xPct as Number).toDouble(),
                    (o.yPct as Number).toDouble(),
                    (o.widthPct as Number).toDouble(),
                    (o.heightPct as Number).toDouble(),
                    ((o.zIndex as? Number)?.toInt()) ?: 1,
                )
            }
        }.getOrNull()
        fromToolkit ?: PaneRect(pane.x, pane.y, pane.width, pane.height, pane.z.toInt())
    }
}

/**
 * Returns (creating on first use) the shared content source for a pane,
 * deduplicated per pane id in [ovSources].
 *
 * Prefers the pane's **live** xterm from the [terminals] registry — the same
 * buffer the visible pane renders, read size-neutrally (no socket, no resize),
 * and full-fidelity capable. Mounted tabs (the active tab and any previously
 * visited) have one, including in demo mode ([connectDemoPane]). Tabs never
 * mounted yet have no registry term, so the overview falls back to a hidden
 * **mirror** term fed by a read-only preview socket (or [attachDemoPreview] in
 * demo) — the link picker's proven pattern — so every tab still previews.
 *
 * @param paneId the pane (leaf) id — the dedup key and registry key.
 * @param sessionId the PTY session id (used by the mirror fallback).
 * @return the shared source (live or mirror).
 */
private fun ensureSource(paneId: String, sessionId: String): ThumbSource {
    ovSources[paneId]?.let { return it }

    val liveTerm = terminals[paneId]?.term
    val source: ThumbSource = if (liveTerm != null) {
        ThumbSource(liveTerm, live = true, hiddenHost = null)
    } else {
        // Unmounted tab: mirror the session into a hidden term (cheap preview),
        // matching the link picker's hidden-host setup exactly.
        val hiddenHost = document.createElement("div") as HTMLElement
        hiddenHost.className = "link-preview-hidden-host"
        document.body?.appendChild(hiddenHost)
        val term = Terminal(json(
            "cursorBlink" to false, "disableStdin" to true,
            "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
            "fontSize" to 9, "cols" to 120, "rows" to 40,
            "scrollback" to 200, "theme" to buildXtermTheme(),
        ))
        term.open(hiddenHost)
        val s = ThumbSource(term, live = false, hiddenHost = hiddenHost)
        if (isDemoClient) {
            attachDemoPreview(term, sessionId)
        } else {
            val url = "$proto://${window.location.host}/pty/$sessionId?$authQueryParam"
            val socket = WebSocket(url)
            socket.asDynamic().binaryType = "arraybuffer"
            socket.onmessage = { event ->
                val data = event.asDynamic().data
                if (data !is String) term.write(Uint8Array(data as ArrayBuffer))
            }
            s.socket = socket
        }
        s
    }
    source.subscribe()
    source.scheduleRepaint()
    ovSources[paneId] = source
    return source
}

/**
 * Resolves which PTY session a pane mirrors, or `null` for non-terminal
 * panes.
 *
 * @param pane the pane to inspect.
 * @return the session id, or `null` (placeholder thumbnail).
 */
private fun paneSessionId(pane: Pane): String? {
    val content = pane.leaf.content
    val terminalKind = content == null || content is TerminalContent
    return if (terminalKind && pane.leaf.sessionId.isNotEmpty()) pane.leaf.sessionId else null
}

/**
 * Builds the style-independent **content** for one tab: the full-card
 * thumbnail (the focused / first terminal pane), the glow halo (child of the
 * card mesh), and one [PaneTile] per pane — each with its captured content
 * (terminal grid / file listing / git status), live source, and true world
 * size. It does *not* place anything in the scene: the active [Overview3DStyle]
 * (via [buildCards] → [Overview3DStyle.build]) parents the card / tile meshes
 * and arranges them. Pane tiles are built at their real layout geometry
 * ([resolvePaneRects]); a style that wants a different arrangement repositions
 * the tile meshes.
 *
 * @param tab the tab to build content for.
 * @param fg thumbnail foreground color. @param bg thumbnail background color.
 * @param accent CSS accent color for the glow halo.
 * @return the assembled card content, already painted once.
 */
private fun buildCardContent(tab: TabConfig, fg: String, bg: String, accent: String): OverviewCard {
    // Full-card view mirrors the focused pane's terminal (or the first
    // terminal pane), titled with the tab name.
    val focusPane = tab.panes.firstOrNull { it.leaf.id == tab.focusedPaneId && paneSessionId(it) != null }
        ?: tab.panes.firstOrNull { paneSessionId(it) != null }
    val cardSource = focusPane?.let { ensureSource(it.leaf.id, paneSessionId(it)!!) }

    val cardCanvas = document.createElement("canvas") as HTMLCanvasElement
    cardCanvas.width = (CARD_CANVAS_W * RES).roundToInt()
    cardCanvas.height = (CARD_CANVAS_H * RES).roundToInt()
    val cardTexture = CanvasTexture(cardCanvas)
    cardTexture.colorSpace = "srgb"
    val cardView = ThumbView(
        cardCanvas, cardTexture, tab.title, TITLE_STRIP_PX, 12, fg, bg, RES, accent,
        // No "no terminal" placeholder on the card: a tab with only
        // file-browser/git panes would flash that text during the split
        // crossfade before its tiles fade in. A plain dark panel morphs cleanly
        // into the tiles.
        placeholder = null,
        // Round the card's own corners so, while it sits behind the split tiles,
        // its opaque square corners never peek out past the tiles' rounded ones.
        roundCorners = true,
    )

    // FrontSide (side 0): cards on the far half of the ring face away and are
    // back-face-culled — otherwise their DoubleSide backface showed through as
    // a dark, *mirrored* filled rectangle that flashed during tab switches.
    // transparent=true because the card fades out as it splits into panes;
    // depthWrite=false so the (invisible, faded) card never depth-occludes the
    // pane tiles that lift in front of it during the split.
    val cardMaterial = MeshBasicMaterial(json(
        "map" to cardTexture, "side" to 0, "transparent" to true,
        "depthWrite" to false,
    ))
    val mesh = Mesh(ovCardGeometry!!, cardMaterial)

    val glowMaterial = MeshBasicMaterial(json(
        "color" to accent, "transparent" to true, "opacity" to 0.0,
        "depthWrite" to false, "side" to 0,
    ))
    val glow = Mesh(ovGlowGeometry!!, glowMaterial)
    glow.position.set(0.0, 0.0, -0.02)
    mesh.add(glow)

    // Pane tiles: one per pane, laid out per the real geometry. Stacking
    // order ranks drive a small z stagger so overlapping panes keep their
    // relative depth when split.
    val rects = resolvePaneRects(tab)
    val zRank = rects.withIndex().sortedBy { it.value.z }.withIndex()
        .associate { (rank, e) -> e.index to rank }
    val tiles = tab.panes.mapIndexed { pi, pane ->
        val rect = rects[pi]
        val rank = zRank[pi] ?: 0
        buildPaneTile(pane, rect, rank, fg, bg, accent)
    }
    // Tiles are left unparented here; the active style parents them (the
    // carousel adds them to the card mesh, the rotunda/exposé to their own
    // scene groups).

    val card = OverviewCard(tab.id, tab.title, mesh, cardMaterial, glowMaterial, cardView, cardSource, tiles, tab.focusedPaneId)
    if (cardSource != null) cardSource.views.add(cardView)
    cardView.paint(null)
    cardSource?.scheduleRepaint()
    return card
}

/**
 * Builds one pane tile: proportional canvas + texture + plane, attached to
 * the shared session source (or painted once as a placeholder), with its
 * assembled and split placements precomputed from [rect].
 *
 * @param pane the pane the tile represents.
 * @param rect the pane's fractional rectangle within the tab.
 * @param zRank the pane's rank in the tab's stacking order (0 = bottom).
 * @param fg thumbnail foreground color. @param bg thumbnail background color.
 * @param accent theme accent color for the tile's title-bar underline.
 * @return the assembled tile (initially invisible; the split animation
 *   reveals it).
 */
private fun buildPaneTile(
    pane: Pane,
    rect: PaneRect,
    zRank: Int,
    fg: String,
    bg: String,
    accent: String,
): PaneTile {
    val sessionId = paneSessionId(pane)
    val source = sessionId?.let { ensureSource(pane.leaf.id, it) }

    // Physical backing store is the pane's fraction of the (supersampled)
    // card canvas — so a tile is as crisp as the full card would be there.
    val wPx = max(96, (rect.w * CARD_CANVAS_W * RES).roundToInt())
    val hPx = max(64, (rect.h * CARD_CANVAS_H * RES).roundToInt())
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = wPx
    canvas.height = hPx
    val texture = CanvasTexture(canvas)
    texture.colorSpace = "srgb"

    val content = pane.leaf.content
    val kind = when {
        source != null -> TileKind.TERMINAL
        content is FileBrowserContent -> TileKind.FILE_BROWSER
        content is GitContent -> TileKind.GIT
        else -> TileKind.OTHER
    }
    val placeholder = when (kind) {
        TileKind.TERMINAL -> null
        TileKind.FILE_BROWSER -> "file browser"
        TileKind.GIT -> "git"
        TileKind.OTHER -> "no terminal"
    }
    // File-browser / git thumbnails read top-down (a listing), unlike the
    // bottom-anchored terminal tail.
    val topAnchored = kind == TileKind.FILE_BROWSER || kind == TileKind.GIT
    // Only tall-enough tiles get a title bar (a bar on a sliver of a tile
    // just eats the whole thumbnail). Threshold is in physical px.
    val stripPx = if (hPx >= 80 * RES) TILE_STRIP_PX else 0
    val view = ThumbView(
        canvas, texture, pane.leaf.title, stripPx, 9, fg, bg, RES, accent, topAnchored, placeholder,
        paneBorder = true,
    )

    val tileW = max(0.1, rect.w * CARD_W - TILE_GAP)
    val tileH = max(0.1, rect.h * CARD_H - TILE_GAP)
    // Subdivided + dished so the pane reads as a genuinely curved surface (its
    // outer edges bow away from the camera), not just a flat tilted quad.
    val geometry = PlaneGeometry(tileW, tileH, DISH_SEGMENTS, DISH_SEGMENTS)
    dishGeometry(geometry, tileW, tileH, DISH_DEPTH)
    // FrontSide (side 0) + depthWrite so the curved sheet never z-fights its
    // own backface (which punched a lens-shaped "hole" through the pane under
    // DoubleSide) and overlapping tiles occlude correctly. Tiles are only ever
    // viewed from the front (their card is at the ring front when split), so
    // the backface is never needed.
    val material = MeshBasicMaterial(json(
        "map" to texture, "side" to 0, "transparent" to true,
        "opacity" to 0.0, "depthWrite" to true,
    ))
    val mesh = Mesh(geometry, material)
    mesh.visible = false

    // Card-local placements: assembled = flush with the card at the pane's
    // layout position; split = spread outward from the card center + lifted
    // toward the viewer, staggered by stacking rank.
    val homeX = (rect.x + rect.w / 2.0 - 0.5) * CARD_W
    val homeY = (0.5 - rect.y - rect.h / 2.0) * CARD_H
    val homeZ = 0.02 + zRank * 0.006
    val splitZ = SPLIT_LIFT + zRank * 0.05
    mesh.position.set(homeX, homeY, homeZ)

    val tile = PaneTile(
        pane.leaf.id, pane.leaf.title, kind, mesh, material, geometry, view, source,
        homeX, homeY, homeZ,
        homeX * SPLIT_SPREAD, homeY * SPLIT_SPREAD, splitZ,
        tileW, tileH,
    )
    if (source != null) source.views.add(view) else view.paint(null)
    return tile
}

/**
 * Bends a subdivided [PlaneGeometry] into a shallow dish so a pane tile reads as
 * a curved surface: the center bulges toward the camera and the outer
 * edges/corners bow away, in proportion to squared distance from the center.
 *
 * The displacement is kept **entirely non-negative** (center at `+depth`,
 * corners at `0`) so every vertex sits *in front of* the flat card plane behind
 * it — otherwise a receding corner would dip behind the (faded) card and get
 * depth-clipped into a visible hole. Recomputes normals so the curvature is
 * well-formed (harmless for the unlit material).
 *
 * @param geometry the subdivided plane geometry (mutated in place).
 * @param w the pane width in world units. @param h the pane height.
 * @param depth how far (world units) the center bulges forward of the corners.
 */
private fun dishGeometry(geometry: PlaneGeometry, w: Double, h: Double, depth: Double) {
    runCatching {
        val pos = geometry.asDynamic().attributes.position
        val count = (pos.count as Number).toInt()
        val hx = w / 2.0
        val hy = h / 2.0
        for (i in 0 until count) {
            val x = (pos.getX(i) as Number).toDouble()
            val y = (pos.getY(i) as Number).toDouble()
            val nx = if (hx > 0) x / hx else 0.0
            val ny = if (hy > 0) y / hy else 0.0
            // Normalized squared radius (0 at center, up to 2 at the corners),
            // mapped so center = +depth (forward) and corners = 0.
            pos.setZ(i, depth * (1.0 - (nx * nx + ny * ny) / 2.0))
        }
        pos.needsUpdate = true
        geometry.asDynamic().computeVertexNormals()
    }
}

/**
 * Builds the thumbnail text lines for a file-browser tile from the cached
 * root directory listing ([fileBrowserPaneStates]`[paneId].dirListings[""]`),
 * directories first then files, each row a name with a leading marker.
 *
 * @param paneId the file-browser pane's id.
 * @return the lines to paint, or `null` when no listing has been cached yet
 *   (the tile keeps its placeholder until the fetch replies).
 */
private fun fileBrowserThumbLines(paneId: String): List<String>? {
    val listing = fileBrowserPaneStates[paneId]?.dirListings?.get("") ?: return null
    val entries = listing.unsafeCast<Array<dynamic>>()
    val dirs = ArrayList<String>()
    val files = ArrayList<String>()
    for (e in entries) {
        val name = e.name as? String ?: continue
        if (e.isDir as? Boolean == true) dirs.add("> $name") else files.add("  $name")
    }
    val lines = dirs + files
    return if (lines.isEmpty()) listOf("(empty)") else lines
}

/**
 * Builds the thumbnail text lines for a git tile from the cached changed-file
 * list ([gitPaneStates]`[paneId].entries`), one `X  path` row per file where
 * `X` is the status letter (A/M/D/R/?), matching the full git list.
 *
 * @param paneId the git pane's id.
 * @return the lines to paint, or `null` when no list has been cached yet.
 */
private fun gitThumbLines(paneId: String): List<String>? {
    val entries = gitPaneStates[paneId]?.entries ?: return null
    val arr = entries.unsafeCast<Array<dynamic>>()
    if (arr.size == 0) return listOf("(no changes)")
    val out = ArrayList<String>()
    for (e in arr) {
        val path = e.filePath as? String ?: continue
        val c = when (e.status as? String) {
            "Added" -> "A"; "Modified" -> "M"; "Deleted" -> "D"
            "Renamed" -> "R"; "Untracked" -> "?"; else -> "·"
        }
        out.add("$c  $path")
    }
    return out
}

/**
 * Repaints a file-browser / git tile from its currently-cached data (or its
 * placeholder when nothing is cached yet). Called on open for any already-
 * cached data and again from [onPaneContentUpdated] as fetches reply.
 *
 * @param tile the non-terminal tile to repaint.
 */
private fun repaintDataTile(tile: PaneTile) {
    val lines = when (tile.contentKind) {
        TileKind.FILE_BROWSER -> fileBrowserThumbLines(tile.paneId)
        TileKind.GIT -> gitThumbLines(tile.paneId)
        else -> return
    }
    tile.view.paint(lines)
}

/**
 * Kicks off thumbnail data for every file-browser / git tile in the freshly
 * built ring: installs the [onPaneContentUpdated] listener that repaints a
 * tile when its data arrives, paints any already-cached data immediately,
 * and issues a fetch ([WindowCommand.FileBrowserListDir] / [WindowCommand.GitList])
 * for tiles with no cached data yet. Terminal tiles are untouched (they
 * stream over their own PTY sockets).
 *
 * Called at the end of [openOverview3d]; the listener is cleared in
 * [closeOverview3d].
 */
private fun primeDataTiles() {
    onPaneContentUpdated = { paneId ->
        for (card in ovCards) for (tile in card.tiles) {
            if (tile.paneId == paneId &&
                (tile.contentKind == TileKind.FILE_BROWSER || tile.contentKind == TileKind.GIT)
            ) {
                repaintDataTile(tile)
            }
        }
    }
    for (card in ovCards) for (tile in card.tiles) {
        when (tile.contentKind) {
            TileKind.FILE_BROWSER -> {
                repaintDataTile(tile)
                if (fileBrowserPaneStates[tile.paneId]?.dirListings?.get("") == null) {
                    launchCmd(WindowCommand.FileBrowserListDir(paneId = tile.paneId, dirRelPath = ""))
                }
            }
            TileKind.GIT -> {
                repaintDataTile(tile)
                if (gitPaneStates[tile.paneId]?.entries == null) {
                    launchCmd(WindowCommand.GitList(paneId = tile.paneId))
                }
            }
            else -> {}
        }
    }
}

/**
 * Rotates the selection by [delta] cards (wrapping), updating the title
 * readout; the render loop eases the ring to the new target.
 *
 * @param delta +1 = next card (clockwise), -1 = previous.
 */
internal fun stepSelection(delta: Int) {
    if (ovCards.isEmpty()) return
    selectCard(((ovSelected + delta) % ovCards.size + ovCards.size) % ovCards.size)
}

/**
 * Makes [index] the selected (front-facing) card, clearing any pane
 * highlight (the new tab starts on "whole tab").
 *
 * @param index card index into [ovCards].
 */
internal fun selectCard(index: Int) {
    if (index !in ovCards.indices) return
    ovSelected = index
    // Start the pane highlight on the tab's *active* pane so the first ↑/↓/Tab
    // moves to its neighbour (not from a "whole tab" slot the user has to step
    // off first). Falls back to "whole tab" (-1) if there's no focused pane.
    ovPaneSelected = focusedTileIndex(index)
    updateTitleReadout()
    applyFidelity()
}

/**
 * The tile index of a card's active (focused) pane, or `-1` when the card has
 * no focused pane (so the selection starts on the "whole tab" slot). Used to
 * seed [ovPaneSelected] on open / tab switch so pane navigation is relative to
 * the active pane.
 *
 * @param cardIndex index into [ovCards].
 * @return the focused pane's tile index, or `-1`.
 */
internal fun focusedTileIndex(cardIndex: Int): Int {
    val card = ovCards.getOrNull(cardIndex) ?: return -1
    val focused = card.focusedPaneId ?: return -1
    return card.tiles.indexOfFirst { it.paneId == focused }
}

/**
 * Recomputes every tile view's render state and repaints, so it shows at once:
 *  - **fidelity**: *every* live-terminal tile paints the exact colored cell grid
 *    ([renderTerminalGrid]) — not just the selected tab's. The rotunda and exposé
 *    show several tabs' panes at once, so limiting the accurate grid to the front
 *    tab left the rest as the ugly re-wrapped monochrome thumbnail; rendering all
 *    mounted panes keeps the whole scene true-to-form. Only mirror fallbacks
 *    (unmounted tabs, fixed 120×40 — not the pane's real geometry) stay on the
 *    cheap thumbnail, since their grid would be the wrong size.
 *  - **highlights**: each tile's `paneActive` (its tab's focused pane → steady
 *    accent glow + tinted header) and `paneSelected` (the front tab's current
 *    keyboard/hover pane → brighter, thicker glow) flags, drawn into the tile
 *    canvas as a border/header by [ThumbView].
 *
 * Called on open and on every tab/pane selection change ([selectCard],
 * [cyclePane], [setPaneSelection]).
 */
internal fun applyFidelity() {
    ovCards.forEachIndexed { i, card ->
        val front = i == ovSelected
        card.tiles.forEachIndexed { ti, tile ->
            val term = tile.contentKind == TileKind.TERMINAL
            // Any live registry term (real pane geometry) renders at full colored
            // fidelity, in whatever style and whichever tab it belongs to; only
            // mirror fallbacks (fixed 120×40) stay on the cheap thumbnail.
            val full = term && tile.source?.live == true
            tile.view.fullFidelity = full
            tile.view.paneActive = tile.paneId == card.focusedPaneId
            tile.view.paneSelected = front && ti == ovPaneSelected
            tile.view.showCursor = full && tile.view.paneActive
        }
    }
    for (source in ovSources.values) source.repaint()
    // Non-terminal tiles have no source, so repaint them directly to reflect
    // highlight changes (data tiles keep their cached listing; others show the
    // placeholder).
    for (card in ovCards) for (tile in card.tiles) {
        if (tile.source == null) {
            when (tile.contentKind) {
                TileKind.FILE_BROWSER, TileKind.GIT -> repaintDataTile(tile)
                else -> tile.view.paint(null)
            }
        }
    }
}

/**
 * Cycles the front tab's pane highlight by [delta], wrapping through a
 * "whole tab" slot: `-1 → 0 → 1 → … → last → -1`. No-op when the front tab
 * has no panes.
 *
 * @param delta +1 = next pane (↓ / Tab), -1 = previous (↑ / Shift+Tab).
 */
internal fun cyclePane(delta: Int) {
    val card = ovCards.getOrNull(ovSelected) ?: return
    val n = card.tiles.size
    if (n == 0) return
    ovPaneSelected = if (delta > 0) {
        if (ovPaneSelected + 1 >= n) -1 else ovPaneSelected + 1
    } else {
        if (ovPaneSelected <= -1) n - 1 else ovPaneSelected - 1
    }
    updateTitleReadout()
    applyFidelity()
}

/**
 * Sets the pane highlight to [index] (or `-1` for none) and refreshes the
 * readout, but only if it actually changed. Called from hover so the mouse
 * and keyboard share one highlight.
 *
 * @param index tile index into the front card's tiles, or `-1`.
 */
internal fun setPaneSelection(index: Int) {
    if (index == ovPaneSelected) return
    ovPaneSelected = index
    updateTitleReadout()
    applyFidelity()
}

/**
 * The server pane (leaf) id currently highlighted on the front card, or
 * `null` when the whole tab is selected (`ovPaneSelected == -1`).
 *
 * @return the highlighted pane id, or `null`.
 */
internal fun selectedPaneId(): String? =
    ovCards.getOrNull(ovSelected)?.tiles?.getOrNull(ovPaneSelected)?.paneId

/**
 * Mirrors the selection into the overlay readout: the tab title alone, or
 * `Tab › Pane` when a specific pane is highlighted.
 */
internal fun updateTitleReadout() {
    val card = ovCards.getOrNull(ovSelected)
    if (card == null) { ovTitleEl?.textContent = ""; return }
    val tile = card.tiles.getOrNull(ovPaneSelected)
    ovTitleEl?.textContent = if (tile != null) "${card.title}  ›  ${tile.paneTitle}" else card.title
}

/**
 * Activates the selected tab with the dive-in transition: dispatches
 * [WindowCommand.SetActiveTab] (plus focus/raise for a specific pane)
 * immediately — the tab switches behind the overlay — then starts the
 * camera dive and CSS fade; the render loop calls [closeOverview3d] when
 * the dive completes.
 *
 * @param paneId specific pane to focus and raise (a clicked pane tile), or
 *   `null` to fall back to the card's own focused pane so the tab is not left
 *   merely active-but-unfocused.
 */
internal fun beginDive(paneId: String?) {
    val card = ovCards.getOrNull(ovSelected) ?: return
    if (!ovDiveStart.isNaN()) return
    launchCmd(WindowCommand.SetActiveTab(card.tabId))
    // Whole-tab selections (Enter with no tile highlighted, or a click on empty
    // card space) arrive with paneId == null; fall back to the card's focused
    // pane so the selection lands focused rather than just active.
    val focusPaneId = paneId ?: card.focusedPaneId
    if (focusPaneId != null) {
        // Same pair the sidebar pane click sends (see termtasticTabSource's
        // onPaneSelect): focus alone would leave the pane buried.
        launchCmd(WindowCommand.SetFocusedPane(tabId = card.tabId, paneId = focusPaneId))
        launchCmd(WindowCommand.RaisePane(paneId = focusPaneId))
    }
    ovDiveStart = window.performance.now()
    ovOverlay?.classList?.add("overview3d-closing")
}

/**
 * Raycasts the current pointer position ([ovPointerNdc]) against [meshes] and
 * returns the nearest hit's `userData` payload (`index` always present, `paneId`
 * only on tiles), or `null` over empty space. The style decides *which* meshes
 * are pickable via [Overview3DStyle.pickables]; this is the shared ray logic.
 *
 * @param meshes candidate meshes to intersect (cards, tiles, …).
 * @return the nearest hit's `userData`, or `null`.
 */
internal fun raycastPointer(meshes: Array<dynamic>): dynamic {
    val camera = ovCamera ?: return null
    if (meshes.isEmpty()) return null
    ovRaycaster.setFromCamera(ovPointerNdc, camera)
    val hits = ovRaycaster.intersectObjects(meshes, false)
    if (hits.isEmpty()) return null
    // NOTE: hits[0] is already `dynamic` — do NOT call .asDynamic() on it.
    // On a dynamic receiver that compiles to a literal JS `.asDynamic()`
    // method call (which doesn't exist) instead of the Kotlin intrinsic;
    // the resulting TypeError once killed the render loop the moment the
    // pointer hovered a card. Index access reads the `object` property
    // (a Kotlin keyword) directly.
    val hit = hits[0]
    return hit["object"].userData
}

/** Sizes the renderer's drawing buffer + camera aspect to the viewport. */
private fun layoutRenderer() {
    val renderer = ovRenderer ?: return
    val camera = ovCamera ?: return
    val w = window.innerWidth
    val h = window.innerHeight
    renderer.setSize(w, h, false)
    camera.aspect = w.toDouble() / h.toDouble()
    camera.updateProjectionMatrix()
}

/** Smoothstep ease shared by the styles' split / dive / zoom animations. */
internal fun ease(p: Double): Double = p * p * (3.0 - 2.0 * p)

/**
 * Starts the per-frame animation loop. Each frame delegates all layout/motion
 * to the active [Overview3DStyle.tick] (ring easing + split for the carousel,
 * cylinder spin for the rotunda, zoom for the exposé), then the core handles
 * the uniform parts: completing a dive-in ([ovDiveStart]) after [DIVE_MS] by
 * closing the overview, and rendering.
 *
 * Each frame's body runs inside a try/catch: a per-frame error is logged and
 * the loop keeps running — a single bad frame must never freeze the overview
 * (which is exactly what the [raycastPointer] TypeError used to do).
 *
 * @param renderer the cached renderer.
 * @param scene the cached scene.
 * @param camera the cached camera.
 */
private fun startRenderLoop(renderer: WebGLRenderer, scene: Scene, camera: PerspectiveCamera) {
    fun tick(now: Double) {
        ovActiveStyle?.tick(now, camera)

        // Dive-in completion is uniform across styles: once the fade window has
        // elapsed, land on the real tab (the command already dispatched in
        // [beginDive]). Styles may animate a camera during the same window.
        if (!ovDiveStart.isNaN() && (now - ovDiveStart) >= DIVE_MS) {
            closeOverview3d()
            return
        }

        renderer.render(scene, camera)
    }

    fun frame() {
        if (!ovOpen) return
        try {
            tick(window.performance.now())
        } catch (t: Throwable) {
            console.error("[overview3d] frame error", t)
        }
        if (ovOpen) ovAnimHandle = window.requestAnimationFrame { frame() }
    }
    ovAnimHandle = window.requestAnimationFrame { frame() }
}

/**
 * Closes the overview and releases every per-open resource: stops the
 * render loop, detaches key/resize handlers, disposes the content sources
 * (unsubscribing from the live terms — which are left running — and freeing
 * only the demo hidden terms), the cards' and tiles' GPU resources, and hides
 * the overlay. The renderer/scene/camera/overlay caches stay warm so the next
 * open is instant.
 *
 * @see openOverview3d
 */
internal fun closeOverview3d() {
    if (!ovOpen) return
    ovOpen = false
    ovDiveStart = Double.NaN

    ovAnimHandle?.let { window.cancelAnimationFrame(it) }
    ovAnimHandle = null
    ovKeyHandler?.let { window.removeEventListener("keydown", it, true) }
    ovKeyHandler = null
    ovResizeHandler?.let { window.removeEventListener("resize", it) }
    ovResizeHandler = null
    onPaneContentUpdated = null

    // Let the style remove its own scene objects (groups, decorations) before
    // the shared card / tile GPU resources are disposed below.
    ovScene?.let { scene -> ovActiveStyle?.teardown(scene) }
    ovActiveStyle = null

    for (source in ovSources.values) source.dispose()
    ovSources.clear()
    ovPalette = null
    for (card in ovCards) card.dispose()
    ovCards.clear()
    ovCardGeometry?.dispose()
    ovCardGeometry = null
    ovGlowGeometry?.dispose()
    ovGlowGeometry = null

    ovOverlay?.let {
        it.classList.remove("overview3d-closing")
        it.style.display = "none"
    }
}

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
private fun applyThemedBackdrop(overlay: HTMLElement, bg: String, accent: String) {
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
private fun parseCssColor(color: String): IntArray? = runCatching {
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
private fun mixRgb(a: IntArray, b: IntArray, t: Double): IntArray = IntArray(3) { i ->
    (a[i] + (b[i] - a[i]) * t).roundToInt().coerceIn(0, 255)
}

/**
 * Scales an RGB triple toward black.
 *
 * @param c the color. @param f brightness factor (1 = unchanged, 0 = black).
 * @return the dimmed color.
 */
private fun dimRgb(c: IntArray, f: Double): IntArray = IntArray(3) { i ->
    (c[i] * f).roundToInt().coerceIn(0, 255)
}

/**
 * Formats an RGB triple as a CSS `rgb()` color.
 *
 * @param c the color.
 * @return the CSS string.
 */
private fun cssRgb(c: IntArray): String = "rgb(${c[0]}, ${c[1]}, ${c[2]})"
