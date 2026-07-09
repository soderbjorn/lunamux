/*
 * Split from Overview3D.kt — overview data types and the pluggable Overview3DStyle contract.
 * See Overview3D.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.three.CanvasTexture
import se.soderbjorn.lunamux.three.Mesh
import se.soderbjorn.lunamux.three.MeshBasicMaterial
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.PlaneGeometry
import se.soderbjorn.lunamux.three.Raycaster
import se.soderbjorn.lunamux.three.Scene
import se.soderbjorn.lunamux.three.Vector2
import se.soderbjorn.lunamux.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
internal class PaneRect(val x: Double, val y: Double, val w: Double, val h: Double, val z: Int)

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
