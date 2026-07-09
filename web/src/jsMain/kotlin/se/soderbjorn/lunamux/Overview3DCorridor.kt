/**
 * Corridor ("Gallery") style for the 3D tab overview — the "walk down a hall of
 * paintings" [Overview3DStyle].
 *
 * The overview is a one-point-perspective **hallway** you stroll along. Each
 * visible tab is a *bay* — a station down the corridor — and that tab's panes
 * hang like framed paintings on the left and right walls of its bay, stepping
 * away into the depth of the hall and yawed inward to face the centreline you
 * walk. You stand in the middle and move by locomotion, not rotation: this is
 * the only style whose motion is *travelling through* the scene rather than
 * spinning a ring (carousel), riding a cylinder (rotunda), or zooming a plane
 * (exposé). Bays ahead of you recede toward the vanishing point, dimmed by
 * distance, so you can see where you are about to walk.
 *
 * Two motions, one metaphor — one for tabs, one for panes:
 *  - **Walk the hall (tabs)** — `↑`/`↓` (and the wheel / a trackpad swipe) dolly
 *    you forward / back one bay, gliding the camera to the next / previous tab.
 *    Clamped at the two ends — a corridor has a start and a finish, it does not
 *    loop.
 *  - **Look at a painting (panes)** — `←`/`→` (and `Tab` / `Shift+Tab`) move the
 *    selection between the current bay's paintings; the chosen one swings to
 *    face you, brightens, and steps out from its wall, while the camera turns its
 *    head to look at it.
 *
 * As with every style, this file owns *only* the corridor's spatial arrangement
 * and gesture handling; all content capture (live terminal grids, file/git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]). The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style parents every pane tile into its
 * hall group, places each on its wall, and walks the camera.
 *
 * True aspect ratio is preserved: each painting is letterboxed into a fixed wall
 * slot ([SLOT_W]×[SLOT_H]) by a *uniform* scale, never stretched.
 *
 * @see Overview3DStyle
 * @see RotundaStyle
 * @see FlipStackStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Distance down the hall (world units, -Z) between adjacent tab bays. */
private const val BAY_DEPTH = 5.0

/** How far back (+Z) from a bay the camera stands, so the bay's paintings are ahead. */
private const val CAM_BACK = 3.0

/** Eye height the camera walks at, and the height paintings hang at. */
private const val EYE_Y = 0.0

/** Horizontal offset of each wall from the centreline (paintings' |x|). */
private const val WALL_X = 2.3

/** Depth (world units) each successive same-wall painting steps down the hall. */
private const val PANE_DEPTH_STEP = 1.8

/** World-space width of the wall slot each painting is letterboxed into. */
private const val SLOT_W = 2.1

/** World-space height of the wall slot each painting is letterboxed into. */
private const val SLOT_H = 1.4

/** Inward yaw (radians ≈ 34°) that turns a wall painting to face the hall centre. */
private const val FACE_YAW = 34.0 * PI / 180.0

/** Fraction of [FACE_YAW] the *selected* painting keeps — it turns most of the way to you. */
private const val SEL_FACE_FRAC = 0.35

/** How far the selected painting steps in off its wall toward the centreline. */
private const val SEL_PULL = 0.7

/** Uniform scale bump applied to the selected painting. */
private const val SEL_POP = 1.08

/** Distance past which a painting has faded fully into the corridor haze. */
private const val FOG_FAR = 11.0

/** Look-ahead distance the camera's gaze point sits down the hall. */
private const val LOOK_D = 6.0

/** Per-frame lerp factor the walk (camera Z) moves toward the target bay. */
private const val Z_EASE = 0.09

/** Per-frame lerp factor the camera's head-turn (yaw) moves toward the selection. */
private const val YAW_EASE = 0.14

/** Per-frame lerp factor a painting's pull / turn / scale glides toward its target. */
private const val TILE_EASE = 0.2

/** Radians of free-look head-turn per pixel of horizontal drag. */
private const val DRAG_YAW_K = 0.0016

/** Standoff (+Z) the camera lands in front of a painting when diving into it. */
private const val DIVE_STANDOFF = 0.8

/** Accumulated wheel/swipe delta needed to walk one bay. Matches the other styles. */
private const val WHEEL_STEP = 90.0

/** Idle gap (ms) between wheel events that ends one gesture and re-arms the walk. */
private const val WHEEL_GESTURE_GAP_MS = 220.0

/**
 * The corridor [Overview3DStyle]. A stateless singleton apart from the per-open
 * scene state it resets in [build] (its hall group, the eased camera walk-Z and
 * head-turn yaw, the drag free-look, and the wheel-gesture accumulator).
 */
internal object CorridorStyle : Overview3DStyle {

    /** Per-open group holding every painting mesh (fixed in world; the camera moves). */
    private var hall: Group? = null

    /** Current camera Z (world units); eased toward the selected bay (or a dive). */
    private var camZ = 0.0

    /** Current camera head-turn yaw (radians, 0 = straight down -Z); eased to the selection. */
    private var yaw = 0.0

    /** Drag-driven free-look yaw added on top of [yaw]; decays back to 0 each tick. */
    private var freeYaw = 0.0

    /** Wheel delta accumulator toward the next single-bay walk. */
    private var wheelAcc = 0.0

    /** `performance.now()` of the last observed wheel event (gesture-gap timing). */
    private var wheelLastTs = 0.0

    /**
     * Builds the hall: parents every card's pane tiles into one group, placing
     * each painting on its wall at its bay depth (yawed inward and letterboxed
     * into the wall slot). Seeds a concrete pane on the selected bay (a corridor
     * always faces a painting, never a "whole tab" slot), and drops the camera at
     * the selected bay looking down the hall. Resets the wheel accumulator.
     *
     * @param scene the cached scene to add the hall group to.
     * @param camera the cached camera (posed separately in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        hall = g
        scene.add(g)

        ovCards.forEachIndexed { i, card ->
            card.tiles.forEachIndexed { j, tile ->
                tile.mesh.position.set(baseX(j), EYE_Y, baseZ(i, j))
                tile.mesh.rotation.set(0.0, baseYaw(j), 0.0)
                val s = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
                tile.mesh.scale.set(s, s, 1.0)
                tile.mesh.visible = false
                g.add(tile.mesh)
            }
        }

        // Face a concrete painting from the first frame (never the -1 "whole tab").
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null && sel.tiles.isNotEmpty() && ovPaneSelected !in sel.tiles.indices) {
            setPaneSelection(0)
        }

        camZ = bayZ(ovSelected) + CAM_BACK
        yaw = 0.0
        freeYaw = 0.0
        wheelAcc = 0.0
        wheelLastTs = 0.0
    }

    /** Poses the camera on the centreline at the selected bay, looking down the hall. */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.position.set(0.0, EYE_Y, camZ)
        camera.lookAt(0.0, EYE_Y, camZ - LOOK_D)
    }

    /**
     * Per-frame update: eases the camera along the hall toward the selected bay
     * (or, mid-dive, up to the selected painting) and turns its head toward the
     * selection, drives hover-to-select on the visible paintings, then fades and
     * eases every painting — the current bay's brightest, the selected one pulled
     * off its wall and turned to face you, far bays fogged toward the vanishing point.
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera.
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        // Hover pick (skipped mid-dive): hovering a current-bay painting drives the
        // same highlight the keyboard uses, so mouse and ←/→ agree.
        if (ovDiveStart.isNaN() && ovPointerMoved) {
            val ud = raycastPointer(pickables())
            if (ud != null && (ud.index as? Int) == ovSelected) {
                val pid = ud.paneId as? String
                if (pid != null) {
                    val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == pid }
                    if (ti >= 0) setPaneSelection(ti)
                }
            }
        }
        ovPointerMoved = false

        // Where the selected painting hangs (its pulled-in x, its bay depth).
        val m = ovCards.getOrNull(ovSelected)?.tiles?.size ?: 0
        val ps = if (ovPaneSelected in 0 until m) ovPaneSelected else 0
        val selPx = selectedX(ps)
        val selPz = baseZ(ovSelected, ps)

        // Walk: glide the camera to the selected bay, or in front of the picked
        // painting while a dive lands. Turn the head toward the selected painting.
        val diving = !ovDiveStart.isNaN()
        val targetCamZ = if (diving) selPz + DIVE_STANDOFF else bayZ(ovSelected) + CAM_BACK
        camZ += (targetCamZ - camZ) * (if (diving) 0.16 else Z_EASE)
        val yawTo = atan2(selPx, maxOf(0.001, camZ - selPz))
        yaw += atan2(sin(yawTo - yaw), cos(yawTo - yaw)) * YAW_EASE
        val lookYaw = yaw + freeYaw
        freeYaw *= 0.9
        camera.position.set(0.0, EYE_Y, camZ)
        camera.lookAt(sin(lookYaw) * LOOK_D, EYE_Y, camZ - cos(lookYaw) * LOOK_D)

        // Fade + ease every painting. Position Z / Y are fixed (set in build); only
        // the selected painting's pull-in x, inward-turn yaw, scale, plus every
        // tile's fog opacity move.
        ovCards.forEachIndexed { i, card ->
            card.tiles.forEachIndexed { j, tile ->
                val selected = i == ovSelected && j == ovPaneSelected
                val mp = tile.mesh
                val tx = if (selected) selectedX(j) else baseX(j)
                val cx = mp.position.x as Double
                mp.position.set(cx + (tx - cx) * TILE_EASE, EYE_Y, baseZ(i, j))
                val tYaw = baseYaw(j) * (if (selected) SEL_FACE_FRAC else 1.0)
                val cYaw = mp.rotation.y as Double
                mp.rotation.set(0.0, cYaw + (tYaw - cYaw) * TILE_EASE, 0.0)

                val base = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
                val target = base * (if (selected) SEL_POP else 1.0)
                val cs = mp.scale.x as Double
                val ns = cs + (target - cs) * TILE_EASE
                mp.scale.set(ns, ns, 1.0)

                // Fog: fade in just ahead of the camera, out toward the far haze;
                // the current bay reads brightest, other bays recede behind it.
                val dist = camZ - baseZ(i, j)
                var op = min((dist - 0.15) / 1.0, (FOG_FAR - dist) / 2.5).coerceIn(0.0, 1.0)
                op *= if (i == ovSelected) (if (j == ovPaneSelected) 1.0 else 0.9) else 0.6
                if (selected) op = maxOf(op, 0.85)
                tile.material.opacity = op
                mp.visible = op > 0.02
            }
        }
    }

    /** `←`/`→` and `Tab` look between the bay's paintings; `↑`/`↓` walk the hall between tabs. */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT, OvNav.PANE_PREV -> stepPane(-1)
            OvNav.RIGHT, OvNav.PANE_NEXT -> stepPane(1)
            OvNav.UP -> walk(1)   // forward, deeper into the hall = next bay
            OvNav.DOWN -> walk(-1)
        }
    }

    /**
     * Wheel / trackpad walk: accumulates the dominant-axis delta and steps one bay
     * each time it crosses ±[WHEEL_STEP]; the accumulator re-arms after
     * [WHEEL_GESTURE_GAP_MS] of silence so a momentum tail can't walk an extra bay.
     * Scrolling down walks forward (deeper); [walk] clamps, so a flick can't wrap.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (gesture timing).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        if (nowMs - wheelLastTs > WHEEL_GESTURE_GAP_MS) wheelAcc = 0.0
        wheelLastTs = nowMs
        wheelAcc += delta
        if (wheelAcc > WHEEL_STEP) { walk(1); wheelAcc = 0.0 }
        if (wheelAcc < -WHEEL_STEP) { walk(-1); wheelAcc = 0.0 }
    }

    /**
     * Pointer drag — a free-look head-turn: horizontal drag yaws the camera's gaze
     * down the hall (decaying back to the selection in [tick] on release), so you
     * can glance around without leaving your spot.
     *
     * @param dx horizontal movement (px) since the last event.
     * @param dy vertical movement (px) since the last event (unused).
     */
    override fun drag(dx: Double, dy: Double) {
        freeYaw = (freeYaw - dx * DRAG_YAW_K).coerceIn(-0.6, 0.6)
    }

    /** The paintings currently bright enough to be worth picking (visible in the hall). */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        for (card in ovCards) for (tile in card.tiles) {
            if (tile.material.opacity > 0.15) meshes.add(tile.mesh.asDynamic())
        }
        return meshes.toTypedArray()
    }

    /**
     * Click handling: a painting in the current bay → select it, turn to face it,
     * and dive into that pane; a painting in another bay → walk to that tab; empty
     * space → close.
     *
     * @param userData the nearest raycast hit's payload (`index` = card/bay,
     *   `paneId` = painting), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx != ovSelected) { selectCard(idx); seedPane(); return }
        val pid = userData.paneId as? String
        if (pid != null) {
            val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == pid }
            if (ti >= 0) setPaneSelection(ti)
        }
        beginDive(pid)
    }

    /** Removes the hall group from the scene (tiles/cards disposed by the core). */
    override fun teardown(scene: Scene) {
        hall?.let { scene.remove(it) }
        hall = null
    }

    /**
     * Footer hint for the corridor's controls: the arrows look between the bay's
     * paintings while `↑`/`↓` (and the wheel) walk the hall between tabs, and a
     * drag glances around.
     */
    override fun hint(): String =
        "← → panes · ↑ ↓ walk tabs · drag to look · scroll to walk · ⏎ enter · esc close"

    /**
     * Looks along the current bay by [delta] paintings, wrapping within the bay
     * (never a -1 "whole tab" slot — a gallery always faces a painting). The
     * camera turns to face the newly selected painting in [tick].
     *
     * @param delta -1 = previous painting (←), +1 = next (→).
     */
    private fun stepPane(delta: Int) {
        val card = ovCards.getOrNull(ovSelected) ?: return
        val m = card.tiles.size
        if (m == 0) return
        val cur = if (ovPaneSelected in 0 until m) ovPaneSelected else 0
        setPaneSelection(((cur + delta) % m + m) % m)
    }

    /**
     * Walks [delta] bays down the hall, **clamped** to `[0, tabs-1]` (a corridor
     * has two ends and does not loop), then seeds a concrete painting on the
     * arrival bay so the camera has something to face. A press at either end that
     * would leave the hall is a no-op.
     *
     * @param delta +1 = forward / deeper (↑), -1 = back (↓).
     */
    private fun walk(delta: Int) {
        val n = ovCards.size
        if (n == 0) return
        val next = (ovSelected + delta).coerceIn(0, n - 1)
        if (next != ovSelected) selectCard(next)
        seedPane()
    }

    /** Seeds pane 0 on the selected bay when no concrete painting is highlighted. */
    private fun seedPane() {
        val card = ovCards.getOrNull(ovSelected) ?: return
        if (card.tiles.isNotEmpty() && ovPaneSelected !in card.tiles.indices) setPaneSelection(0)
    }

    /** The z (depth) of bay [cardIndex] — bay 0 nearest the hall entrance, later bays deeper. */
    private fun bayZ(cardIndex: Int): Double = -cardIndex * BAY_DEPTH

    /** The wall a painting hangs on: -1 = left, +1 = right (even panes left, odd right). */
    private fun paneSide(tileIndex: Int): Double = if (tileIndex % 2 == 0) -1.0 else 1.0

    /** The resting x of painting [tileIndex] on its wall (before any selection pull-in). */
    private fun baseX(tileIndex: Int): Double = paneSide(tileIndex) * WALL_X

    /** The x of painting [tileIndex] when it is the selection — stepped in off its wall. */
    private fun selectedX(tileIndex: Int): Double = paneSide(tileIndex) * (WALL_X - SEL_PULL)

    /** The inward yaw that turns painting [tileIndex] on its wall to face the hall centre. */
    private fun baseYaw(tileIndex: Int): Double = -paneSide(tileIndex) * FACE_YAW

    /**
     * The z (depth) of painting [tileIndex] on bay [cardIndex]: same-wall paintings
     * step further down the hall so a bay with many panes fills a stretch of
     * corridor rather than crowding one plane.
     *
     * @param cardIndex the bay (index into [ovCards]).
     * @param tileIndex the painting (index into the bay's tiles).
     * @return the painting's z in world units.
     */
    private fun baseZ(cardIndex: Int, tileIndex: Int): Double =
        bayZ(cardIndex) - (tileIndex / 2) * PANE_DEPTH_STEP
}
