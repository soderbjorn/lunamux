/*
 * Split from World3DSpike.kt — ring data types (RingPane, EmptyTabCard, WorkingBorder, PaneSpec) and small geometry helpers.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.termtastic

import kotlin.js.json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.ImageData
import org.w3c.dom.Node
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.CSS3DRenderer
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.Scene

/**
 * One reparented pane on the ring. Most fields are `var` because a **mirror**
 * preview can be hot-swapped in place for the real terminal when its tab is
 * engaged (see [tryPromoteMirror]).
 *
 * @property paneId the pane (leaf) id. @property tabId the owning tab's id.
 * @property sessionId the backing session id (keys the `"working"` state map).
 * @property title/tabTitle strip labels. @property tabOrd the pane's tab ordinal.
 * @property paneOrdInTab the pane's index within its tab.
 * @property kind the pane's content kind. Non-[PaneKind.TERMINAL] planes carry a live
 *   git / file-browser DOM view instead of an xterm, so [term]/[fit] are `null` and the
 *   term-specific reformat/grid/zoom-font paths are skipped for them.
 * @property term the xterm on show (real registry term or owned mirror), or `null` for a
 *   git / file-browser plane.
 * @property fit the fit addon (to reformat), or `null`.
 * @property container the reparented element. @property wrapper the CSS3D plane.
 * @property dim the dim overlay. @property glow the "working" breath veil (pulsed each frame).
 * @property border the animated jagged "working" border (dash-offset run each frame).
 * @property previewTag the "preview" chip (mirrors), or `null`.
 * @property obj the [CSS3DObject] positioned each frame.
 * @property entry the real [TerminalEntry] if mounted, else `null` (mirror).
 * @property origParent/origNext where a real container came from (for restore).
 * @property mirrorSocket the preview socket for a mirror (closed on teardown), else `null`.
 * @property interactive `true` if the front pane can take keyboard input (real, mounted).
 * @property needsRefit `true` if [postOpenLayout] should reformat this pane.
 * @property baseFont/baseCw/baseCh native sizing, scaled with the font on zoom.
 * @property normScale side-pane normalization: the scale (≤ 1) that fits this pane's
 *   native box inside the common screen box ([spikeScreenW]×[spikeScreenH]), so a
 *   fullscreen-sized PTY can't dwarf the ring and slice through its neighbours'
 *   planes. Recomputed on every present; the front pane ignores it (1:1 PTY truth).
 *   @see presentPaneToGrid
 * @property birth the birth/death animation factor (0 = gone, 1 = full); the render
 *   loop eases it toward 1 (or 0 when [dying]) and multiplies the pane's scale by it,
 *   so a newly-created pane grows in and a removed one shrinks out. @see reconcileRing
 * @property dying `true` once the pane's backing pane/session is gone from the config
 *   and it is animating out; when [birth] reaches ~0 it is disposed and dropped.
 */
internal class RingPane(
    val paneId: String,
    val tabId: String,
    val sessionId: String,
    val title: String,
    val tabTitle: String,
    var tabOrd: Int,
    var paneOrdInTab: Int,
    val kind: PaneKind,
    var term: Terminal?,
    var fit: FitAddon?,
    var container: HTMLElement,
    val wrapper: HTMLElement,
    val dim: HTMLElement,
    val glow: HTMLElement,
    val border: WorkingBorder,
    val previewTag: HTMLElement?,
    val obj: CSS3DObject,
    var entry: TerminalEntry?,
    var origParent: Node?,
    var origNext: Node?,
    var mirrorSocket: WebSocket?,
    var interactive: Boolean,
    var needsRefit: Boolean,
    var baseFont: Int,
    var baseCw: Int,
    var baseCh: Int,
    var normScale: Double,
    var birth: Double = 1.0,
    var dying: Boolean = false,
    var lscale: Double = 1.0,
    /** Elapsed frames into the current latch-flex; `< 0` when idle. @see startFlex */
    var flexPhase: Double = -1.0,
    /** Sign of the current flex deflection: [FLEX_DIR_OUT] engage, [FLEX_DIR_IN] disengage. */
    var flexDir: Double = FLEX_DIR_OUT,
    /** `url(#…)` id of this pane's bulge filter, applied to the wrapper while flexing. @see createBulgeFilter */
    var bulgeFilterId: String = "",
    /** This pane's live `feDisplacementMap` node; its `scale` attr is animated per frame. */
    var bulgeMap: Element? = null,
    /**
     * **Stash** flight progress: `0` = at its home ring slot, `1` = at rest on the
     * stash shelf. The render loop eases it toward 1 while the pane is in [spikeStashed]
     * (else toward 0) and lerps the pane's world position between its ring slot and
     * [stashShelfPos] by it, so the pane visibly flies up to / down from the shelf.
     * @see toggleStash
     */
    var stashProg: Double = 0.0,
    /**
     * The pane's last shelf **slot** index — set each frame while it is stashed, and
     * retained while it flies home (after removal from [spikeStashed]) so the outbound
     * lerp keeps interpolating from the correct shelf position. @see stashShelfPos
     */
    var stashSlot: Int = 0,
    /**
     * Shelf-browse **highlight** progress: `0` = unhighlighted, `1` = this pane is the
     * browsed shelf slot, fully lit. The render loop steps it toward its target at the
     * shelf-browse glide rate (1/[SHELF_BROWSE_FRAMES] per frame) so the accent light
     * slides from slot to slot at the same speed the camera glides between them,
     * rather than snapping on arrival. @see shelfBrowse
     */
    var shelfLit: Double = 0.0,
    /**
     * **Phaser-fire close** progress in elapsed frames, or `< 0` when the pane is not
     * being phaser-killed. Armed by [startPhaserDeath] (only under [PHASER_CLOSE_ENABLED]);
     * [tickPhaser] advances it each frame, ramping [phaserTint] and spawning bolts, and
     * once it reaches [PHASER_TOTAL_FRAMES] sets [dying] so the normal shrink-out collapses
     * the pane. A phasering pane is deliberately *not* marked [dying] by [reconcileRing]
     * when its Close lands, so it lingers at the ring front getting shot for the full
     * several seconds. @see tickPhaser
     */
    var phaserPhase: Double = -1.0,
    /** Frames until the next irregular phaser-bolt volley (counts down each frame). @see tickPhaser */
    var phaserNextBolt: Double = 0.0,
    /**
     * Transient **recoil** jolt from phaser hits: [tickPhaser] bumps it by
     * [PHASER_RECOIL_PER_HIT] each time a bolt lands and the render loop decays it by
     * [PHASER_RECOIL_DECAY]/frame, punching the pane's bulge and scale so every strike
     * visibly rocks the wounded pane. @see tickPhaser
     */
    var phaserRecoil: Double = 0.0,
    /**
     * The deepening-red "heat" veil laid over the wrapper during a phaser close, or
     * `null` when not phasering. Created by [startPhaserDeath] and removed on collapse;
     * its opacity is driven per frame by [tickPhaser]. @see startPhaserDeath
     */
    var phaserTint: HTMLElement? = null,
    /**
     * **Wormhole spawn** progress in elapsed frames, or `< 0` when the pane is not
     * being born through a wormhole. Armed by [armWormholeSpawn] (only under
     * [WORMHOLE_SPAWN_ENABLED]); [tickWormhole] advances it each frame and, while it is
     * live, fully overrides the pane's world transform — holding it hidden inside the
     * vortex through the camera-focus and open legs, then lerping it out of the portal
     * to the ring slot the render loop computed this frame — so the pane appears to
     * emerge from the rift. Cleared back to `-1` on completion, handing the pane back to
     * normal ring placement seamlessly (the emergence ends exactly at the ring slot).
     * @see tickWormhole @see armWormholeSpawn
     */
    var spawnPhase: Double = -1.0,
)

/**
 * A ghost placeholder plane for an **empty tab** (a tab with no panes). Empty tabs
 * are invisible in the pane-based sphere layout, so this card gives them a body on
 * the ring: you can rotate to one, drop a pane into it (`n`), or remove it (`x`). It
 * rides the same latitude ([tabOrd]) as a real tab's panes would, at the pane pole.
 *
 * @property tabId the backing tab's id (used to target `AddPaneToTab` / `CloseTab`).
 * @property tabOrd the tab's latitude ordinal, kept in sync by [reconcileRing].
 * @property title the tab's display title, shown on the card.
 * @property wrapper the CSS3D plane element. @property obj the positioned [CSS3DObject].
 * @property birth/[dying] the same grow-in / shrink-out animation as [RingPane.birth].
 * @see buildEmptyTabCard @see reconcileRing
 */
internal class EmptyTabCard(
    val tabId: String,
    var tabOrd: Int,
    var title: String,
    val wrapper: HTMLElement,
    val obj: CSS3DObject,
    var birth: Double = 1.0,
    var dying: Boolean = false,
)

/**
 * The animated jagged "working" border overlay for one pane: an inline `<svg>` root
 * (positioned over the pane, hidden by default) holding a single spiky `<path>` whose
 * `stroke-dashoffset` the render loop advances each frame so the dashes appear to
 * *run* around the perimeter. Kept together so the loop can toggle visibility and
 * scroll the offset without re-querying the DOM.
 *
 * @property root the `<svg>` element appended to the pane wrapper.
 * @property path the jagged perimeter `<path>` whose dash offset is animated.
 * @see createWorkingBorder @see jaggedRectPath
 */
internal class WorkingBorder(
    val root: Element,
    val path: Element,
)

/**
 * Builds a [WorkingBorder] sized to a pane of the given pixel dimensions: an SVG
 * overlay with a closed rounded-rectangle path stroked in [WORKING_BORDER_COLOR] as
 * round-capped dots ([WORKING_BORDER_DASH]). Starts hidden (`opacity:0`); the render
 * loop shows it and slowly scrolls its dash offset while the pane is working, so the
 * dots drift around the perimeter. Called once per pane in [reparentPane].
 *
 * @param w pane wrapper width in px. @param h pane wrapper height in px (incl. title).
 * @return the assembled overlay, ready to append to the wrapper.
 * @see WORKING_BORDER_ENABLED
 */
internal fun createWorkingBorder(w: Int, h: Int): WorkingBorder {
    val svgNs = "http://www.w3.org/2000/svg"
    val svg = document.createElementNS(svgNs, "svg")
    svg.setAttribute("viewBox", "0 0 $w $h")
    svg.setAttribute("preserveAspectRatio", "none")
    // NB: an SVG element is an SVGElement, NOT an HTMLElement — `as HTMLElement`
    // would throw a ClassCastException in Kotlin/JS. Set style via setAttribute.
    svg.setAttribute(
        "style",
        "position:absolute;inset:0;width:100%;height:100%;" +
            "pointer-events:none;opacity:0;z-index:3;" +
            // Neutral dark halo (not a fixed hue) so the dots stay legible on any
            // background without clashing with the theme accent they're drawn in.
            "filter:drop-shadow(0 0 2px rgba(0,0,0,0.7));",
    )
    val path = document.createElementNS(svgNs, "path")
    path.setAttribute("d", roundedRectPath(w.toDouble(), h.toDouble(), WORKING_BORDER_PAD, WORKING_BORDER_RADIUS))
    path.setAttribute("fill", "none")
    path.setAttribute("stroke", WORKING_BORDER_COLOR)
    path.setAttribute("stroke-width", WORKING_BORDER_WIDTH.toString())
    path.setAttribute("stroke-linecap", "round") // round caps turn tiny dashes into dots
    path.setAttribute("stroke-dasharray", WORKING_BORDER_DASH)
    svg.appendChild(path)
    return WorkingBorder(root = svg, path = path)
}

/**
 * Resizes an existing [WorkingBorder] to a new pane size: updates the SVG viewBox
 * and regenerates the perimeter path. Called from [presentPaneToGrid] whenever a
 * pane's grid (and thus pixel size) changes — the border was previously built once
 * at the pane's birth size and never resized, so after any grid change its
 * travelling dots no longer tracked the real pane edge (visible as a stray
 * accent-coloured dot floating mid-edge).
 *
 * @param b the border overlay to resize.
 * @param w new pane wrapper width px. @param h new wrapper height px (incl. title).
 * @see createWorkingBorder @see presentPaneToGrid
 */
internal fun resizeWorkingBorder(b: WorkingBorder, w: Int, h: Int) {
    runCatching {
        b.root.setAttribute("viewBox", "0 0 $w $h")
        b.path.setAttribute("d", roundedRectPath(w.toDouble(), h.toDouble(), WORKING_BORDER_PAD, WORKING_BORDER_RADIUS))
    }
}

/**
 * Generates the SVG `d` for a closed rounded rectangle inset by `pad` from a `w`×`h`
 * box, with corner radius `r` (clamped so it never exceeds half the shorter side).
 * Used as the dotted "working" outline; the round corners keep the drifting dots from
 * bunching at sharp turns.
 *
 * @param w box width px. @param h box height px. @param pad inset px. @param r corner radius px.
 * @return the path data string.
 * @see createWorkingBorder
 */
internal fun roundedRectPath(w: Double, h: Double, pad: Double, r: Double): String {
    val x0 = pad; val y0 = pad; val x1 = w - pad; val y1 = h - pad
    val rr = minOf(r, (x1 - x0) / 2, (y1 - y0) / 2)
    fun n(v: Double) = (v * 10).toInt() / 10.0
    return "M${n(x0 + rr)} ${n(y0)} " +
        "H${n(x1 - rr)} A${n(rr)} ${n(rr)} 0 0 1 ${n(x1)} ${n(y0 + rr)} " +
        "V${n(y1 - rr)} A${n(rr)} ${n(rr)} 0 0 1 ${n(x1 - rr)} ${n(y1)} " +
        "H${n(x0 + rr)} A${n(rr)} ${n(rr)} 0 0 1 ${n(x0)} ${n(y1 - rr)} " +
        "V${n(y0 + rr)} A${n(rr)} ${n(rr)} 0 0 1 ${n(x0 + rr)} ${n(y0)} Z"
}

/**
 * The content kind of a ring plane. Terminals reparent their live xterm (or a mirror
 * term fed by a preview `/pty` socket); git / file-browser panes carry a **live DOM
 * view** built by [buildGitView] / [buildFileBrowserView] — those self-register in
 * [gitPaneViews] / [fileBrowserPaneViews] and self-fire their fetch commands, so the
 * plane streams updates over the `/window` channel exactly as the 2D pane does, with
 * no per-pane view-model or socket of its own.
 *
 * @see collectPaneSpecs @see buildRingPane @see disposeRingPane
 */
internal enum class PaneKind { TERMINAL, GIT, FILE_BROWSER }

/** A pane the ring should show, before it is reparented onto a plane. */
internal class PaneSpec(
    val paneId: String,
    val tabId: String,
    val title: String,
    val tabTitle: String,
    val sessionId: String,
    val entry: TerminalEntry?,
    var tabOrd: Int,
    val paneOrdInTab: Int,
    val kind: PaneKind = PaneKind.TERMINAL,
)

/**
 * The camera distance at which a CSS3D plane at the ring's front appears at 1:1.
 *
 * @param height viewport height in CSS px. @return the 1:1 perspective distance.
 */
internal fun perspDistance(height: Int): Double = 0.5 * height / tan(SPIKE_FOV * PI / 360.0)

/**
 * A soft edge fade: `1.0` up to `plateau`, ramping to `0.0` over `edge` beyond it.
 *
 * @param v the signed distance. @param plateau full-opacity radius. @param edge ramp width.
 * @return the opacity in `0.0..1.0`.
 */
internal fun edgeFade(v: Double, plateau: Double, edge: Double): Double =
    (1.0 - (abs(v) - plateau) / edge).coerceIn(0.0, 1.0)
