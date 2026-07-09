/**
 * Vertigo ("Tower") style for the 3D tab overview — the "ride a glass elevator up
 * a skyscraper" [Overview3DStyle].
 *
 * Every visible tab is a **floor** of a glass tower, stacked vertically along Y
 * ([FLOOR_STEP] apart). Each floor is a translucent slab showing the tab; you see
 * through the floors above and below it to the ones beyond. Its panes lay out as
 * a **row across the floor**. This is the only style whose primary motion is
 * *vertical* — a crane up and down the shaft — rather than a lateral spin
 * (carousel), a surrounding cylinder (rotunda), or a dolly through a hall
 * (corridor).
 *
 * The signature is the **Hitchcock dolly-zoom**. Each time you lock onto a floor
 * the camera tracks *backward* while its field of view *widens* — held so that
 * `distance × tan(fov/2)` stays constant ([dollyK]) — which keeps the selected
 * slab exactly the same size in frame while the rest of the tower rushes and
 * stretches around it: the *vertigo effect*, a camera-lens trick none of the flat
 * or ring styles can do. And it *opens* on a **drone shot** — the camera starts
 * high above the tower ([DRONE_HEIGHT]) looking down the shaft, then cranes down
 * and levels out onto your floor, establishing the whole stack before you move.
 *
 * Two motions, one metaphor — one for tabs, one for panes:
 *  - **Ride the shaft (tabs)** — `↑`/`↓` (and the wheel / a trackpad swipe) crane
 *    the camera one floor up / down, gliding past the glass slabs; arrival fires
 *    the dolly-zoom lock. Clamped at the top and ground floors — a tower has ends.
 *  - **Cross the floor (panes)** — `←`/`→` (and `Tab` / `Shift+Tab`, via the
 *    shared [cyclePane], which also visits the "whole floor" slot so `Enter` can
 *    dive the whole tab) step across the floor's pane row; the camera *trucks*
 *    sideways to sit in front of the chosen pane, which lifts toward you.
 *
 * As with every style, this file owns *only* the tower's spatial arrangement and
 * gesture handling; all content capture (live terminal grids, file/git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]). The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style stacks the slab meshes in its tower
 * group, parents each floor's pane tiles onto its slab, and cranes the camera.
 *
 * True aspect ratio is preserved: each pane is letterboxed into a fixed row slot
 * ([ROW_SLOT_W]×[ROW_SLOT_H]) by a *uniform* scale, never stretched.
 *
 * @see Overview3DStyle
 * @see OrbitStyle
 * @see CorridorStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/** Vertical gap (world units) between adjacent tower floors. */
private const val FLOOR_STEP = 3.2

/** The camera's resting Z distance back from the glass slabs (fov at [VERTIGO_BASE_FOV]). */
private const val FLOOR_CAM_DIST = 6.5

/** How far back (× resting distance) the dolly-zoom pulls at the start of a lock. */
private const val DOLLY_PULL = 1.85

/** Duration (ms) of one dolly-zoom lock as the camera settles onto a floor. */
private const val LOCK_MS = 640.0

/** Per-frame lerp the camera cranes vertically toward the selected floor. */
private const val CRANE_EASE = 0.1

/** Per-frame lerp the camera trucks sideways toward the selected pane. */
private const val TRUCK_EASE = 0.14

/** The camera's resting fov (degrees); the dolly-zoom animates it against distance. */
private const val VERTIGO_BASE_FOV = 50.0

/** How high above the selected floor the opening drone shot starts. */
private const val DRONE_HEIGHT = 9.0

/** Base slab opacity for an in-focus neighbouring floor (glass you see through). */
private const val SLAB_ALPHA = 0.5

/** Selected-floor slab opacity — dimmed to glass so its pane row reads in front. */
private const val SLAB_SEL_ALPHA = 0.28

/** Floors beyond this many away from the selected one have faded fully out. */
private const val VISIBLE_FLOORS = 5.0

/** Radians (≈ -3°) the selected slab tilts toward the camera for parallax. */
private const val SLAB_SEL_TILT = -0.05

/** Horizontal spacing (world units) between adjacent panes in the floor row. */
private const val ROW_DX = 1.9

/** How far the pane row lifts off the slab toward the camera (+Z local). */
private const val ROW_LIFT = 0.9

/** Extra forward lift the highlighted pane gets so it stands proud of the row. */
private const val ROW_SEL_LIFT = 0.5

/** Uniform scale bump applied to the highlighted pane. */
private const val ROW_SEL_POP = 1.06

/** World-space width the pane tiles are letterboxed into. */
private const val ROW_SLOT_W = 1.7

/** World-space height the pane tiles are letterboxed into. */
private const val ROW_SLOT_H = 1.15

/** Per-frame lerp each pane / slab glides toward its target transform. */
private const val ROW_EASE = 0.18

/** Radians of free-look pan per pixel of horizontal drag (decays back). */
private const val LOOK_PAN_K = 0.004

/** Radians of free-look pitch per pixel of vertical drag (decays back). */
private const val LOOK_PITCH_K = 0.004

/** Accumulated wheel/swipe delta needed to ride one floor. Matches the other styles. */
private const val VERTIGO_WHEEL_STEP = 90.0

/** Idle gap (ms) between wheel events that ends one gesture and re-arms the ride. */
private const val VERTIGO_WHEEL_GAP_MS = 220.0

/**
 * The vertigo ("tower") [Overview3DStyle]. A stateless singleton apart from the
 * per-open scene state it resets in [build] (its tower group, the eased crane Y /
 * truck X / dolly Z, the dolly-zoom lock latch, the drag free-look, and the
 * wheel-gesture accumulator).
 */
internal object VertigoStyle : Overview3DStyle {

    /** Per-open group holding every floor slab (fixed in the shaft; the camera cranes). */
    private var tower: Group? = null

    /** Current camera height (world units); eased toward the selected floor. */
    private var camY = 0.0

    /** Current camera lateral offset (world units); eased toward the selected pane. */
    private var camX = 0.0

    /** Current camera Z distance from the slabs; driven by the dolly-zoom during a lock. */
    private var camZ = FLOOR_CAM_DIST

    /** True while a dolly-zoom lock is settling onto the selected floor. */
    private var lockActive = false

    /** `performance.now()` the current lock began, or `NaN` when one is pending its first tick. */
    private var lockStart = Double.NaN

    /** The dolly-zoom invariant `distance × tan(fov/2)` that holds the slab size constant. */
    private var dollyK = FLOOR_CAM_DIST * tan(VERTIGO_BASE_FOV * 0.5 * PI / 180.0)

    /** Drag free-look horizontal pan (radians of look-target x offset); decays to 0. */
    private var lookPan = 0.0

    /** Drag free-look vertical pitch (radians of look-target y offset); decays to 0. */
    private var lookPitch = 0.0

    /** Wheel delta accumulator toward the next single-floor ride. */
    private var wheelAcc = 0.0

    /** `performance.now()` of the last observed wheel event (gesture-gap timing). */
    private var wheelLastTs = 0.0

    /**
     * Builds the tower: stacks each slab at its floor height, parents every
     * floor's pane tiles onto its slab (letterboxed, hidden until the floor is
     * selected), poses the camera high above for the opening drone shot, and arms
     * the first dolly-zoom lock. Resets the truck / free-look / wheel state.
     *
     * @param scene the cached scene to add the tower group to.
     * @param camera the cached camera (posed separately in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        tower = g
        scene.add(g)

        ovCards.forEachIndexed { i, card ->
            card.mesh.position.set(0.0, floorY(i), 0.0)
            card.mesh.rotation.set(0.0, 0.0, 0.0)
            g.add(card.mesh)
            val n = card.tiles.size
            card.tiles.forEachIndexed { j, tile ->
                val s = min(ROW_SLOT_W / tile.worldW, ROW_SLOT_H / tile.worldH)
                tile.mesh.scale.set(s, s, 1.0)
                tile.mesh.position.set(rowX(j, n), 0.0, ROW_LIFT)
                tile.mesh.visible = false
                tile.material.opacity = 0.0
                card.mesh.add(tile.mesh)
            }
        }

        camX = 0.0
        camY = floorY(ovSelected) + DRONE_HEIGHT   // start high for the drone shot
        camZ = FLOOR_CAM_DIST * DOLLY_PULL
        lockActive = true
        lockStart = Double.NaN
        lookPan = 0.0
        lookPitch = 0.0
        wheelAcc = 0.0
        wheelLastTs = 0.0
        dollyK = FLOOR_CAM_DIST * tan(VERTIGO_BASE_FOV * 0.5 * PI / 180.0)
    }

    /** Poses the camera high above the selected floor, looking down the shaft (the drone shot). */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.up.set(0.0, 1.0, 0.0)
        camera.position.set(camX, camY, camZ)
        camera.lookAt(camX, floorY(ovSelected), 0.0)
        camera.fov = VERTIGO_BASE_FOV
        camera.updateProjectionMatrix()
    }

    /**
     * Per-frame update: cranes the camera to the selected floor and trucks it to
     * the selected pane, runs the dolly-zoom lock (pulling the distance in while
     * widening the fov to hold the slab size), fades floors by depth, dissolves the
     * selected slab into its pane row, and advances the dive-in camera.
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera.
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        // Hover pick (skipped mid-dive): hovering a pane on the selected floor
        // drives the same highlight the keyboard uses, so mouse and ←/→ agree.
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

        val diving = !ovDiveStart.isNaN()
        val floorYSel = floorY(ovSelected)

        // Crane vertically to the floor and truck laterally to the selected pane.
        camY += (floorYSel - camY) * CRANE_EASE
        val m = ovCards.getOrNull(ovSelected)?.tiles?.size ?: 0
        val targetX = if (ovPaneSelected in 0 until m) rowX(ovPaneSelected, m) else 0.0
        camX += (targetX - camX) * TRUCK_EASE

        // Dolly-zoom lock: pull the distance from far→resting while widening the
        // fov so the slab stays the same size and the tower warps around it. While
        // diving, override with a rush-in toward the slab.
        var fov = VERTIGO_BASE_FOV
        if (diving) {
            camZ += (0.9 - camZ) * 0.16
        } else if (lockActive) {
            if (lockStart.isNaN()) lockStart = now
            val p = ((now - lockStart) / LOCK_MS).coerceIn(0.0, 1.0)
            val ez = ease(p)
            camZ = FLOOR_CAM_DIST * DOLLY_PULL + (FLOOR_CAM_DIST - FLOOR_CAM_DIST * DOLLY_PULL) * ez
            fov = 2.0 * atan(dollyK / camZ) * 180.0 / PI
            if (p >= 1.0) { lockActive = false; lockStart = Double.NaN }
        } else {
            camZ += (FLOOR_CAM_DIST - camZ) * 0.14
        }

        // Free-look pan/pitch decays back to a level gaze on the slab centre.
        val lookX = camX + lookPan
        val lookY = floorYSel + lookPitch
        lookPan *= 0.9
        lookPitch *= 0.9
        camera.position.set(camX, camY, camZ)
        camera.lookAt(lookX, lookY, 0.0)
        camera.fov = fov
        camera.updateProjectionMatrix()

        // Fade floors by depth (glass you see through), dissolve the selected slab
        // into its pane row, and lay that row out.
        ovCards.forEachIndexed { i, card ->
            val selected = i == ovSelected
            val rank = abs(i - ovSelected)
            val depthFade = (1.0 - rank / VISIBLE_FLOORS).coerceIn(0.0, 1.0)
            val slabTarget = if (selected) SLAB_SEL_ALPHA else SLAB_ALPHA * depthFade
            val co = card.cardMaterial.opacity
            card.cardMaterial.opacity = co + (slabTarget - co) * ROW_EASE
            card.mesh.visible = card.cardMaterial.opacity > 0.02 || selected

            val tiltTarget = if (selected) SLAB_SEL_TILT else 0.0
            val ctilt = card.mesh.rotation.x as Double
            card.mesh.rotation.set(ctilt + (tiltTarget - ctilt) * ROW_EASE, 0.0, 0.0)

            val glowTarget = if (selected) 0.35 else 0.0
            card.glowMaterial.opacity += (glowTarget - card.glowMaterial.opacity) * 0.12

            layoutRow(card, selected)
        }
    }

    /** `↑`/`↓` (and the wheel) ride floors; `←`/`→` and `Tab` cross the floor's pane row. */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.UP -> rideFloor(-1)     // up the tower = the floor above
            OvNav.DOWN -> rideFloor(1)
            OvNav.LEFT, OvNav.PANE_PREV -> cyclePane(-1)
            OvNav.RIGHT, OvNav.PANE_NEXT -> cyclePane(1)
        }
    }

    /**
     * Wheel / trackpad ride: accumulates the dominant-axis delta and rides one
     * floor each time it crosses ±[VERTIGO_WHEEL_STEP]; the accumulator re-arms
     * after [VERTIGO_WHEEL_GAP_MS] of silence so a momentum tail can't ride an
     * extra floor. Scrolling down rides down; [rideFloor] clamps at the ends.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (gesture timing).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        if (nowMs - wheelLastTs > VERTIGO_WHEEL_GAP_MS) wheelAcc = 0.0
        wheelLastTs = nowMs
        wheelAcc += delta
        if (wheelAcc > VERTIGO_WHEEL_STEP) { rideFloor(1); wheelAcc = 0.0 }
        if (wheelAcc < -VERTIGO_WHEEL_STEP) { rideFloor(-1); wheelAcc = 0.0 }
    }

    /**
     * Pointer drag — a free-look that pans / pitches the gaze around the shaft
     * (decaying back to a level look at the slab centre in [tick] on release), so
     * you can glance up and down the tower without leaving your floor.
     *
     * @param dx horizontal movement (px) since the last event.
     * @param dy vertical movement (px) since the last event.
     */
    override fun drag(dx: Double, dy: Double) {
        lookPan = (lookPan + dx * LOOK_PAN_K).coerceIn(-2.0, 2.0)
        lookPitch = (lookPitch + dy * LOOK_PITCH_K).coerceIn(-2.5, 2.5)
    }

    /** The selected floor's visible panes, plus every slab mesh (to ride to another floor). */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        for (card in ovCards) meshes.add(card.mesh.asDynamic())
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null) for (tile in sel.tiles) if (tile.mesh.visible) meshes.add(tile.mesh.asDynamic())
        return meshes.toTypedArray()
    }

    /**
     * Click handling: a pane on the selected floor → focus it and dive that pane;
     * the selected slab → dive the whole tab; another floor → ride to it; empty
     * space → close.
     *
     * @param userData the nearest raycast hit's payload (`index` = floor,
     *   `paneId` = pane), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx != ovSelected) { rideFloorTo(idx); return }
        val pid = userData.paneId as? String
        if (pid != null) {
            val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == pid }
            if (ti >= 0) setPaneSelection(ti)
        }
        beginDive(pid)
    }

    /** Removes the tower group from the scene (slabs/panes disposed by the core). */
    override fun teardown(scene: Scene) {
        tower?.let { scene.remove(it) }
        tower = null
    }

    /**
     * Footer hint for the tower's controls: `↑`/`↓` (and the wheel) ride floors,
     * the arrows cross the floor's panes, and a drag glances up and down the shaft.
     */
    override fun hint(): String =
        "← → panes · ↑ ↓ ride floors · drag to look · scroll to ride · ⏎ enter · esc close"

    /**
     * Rides [delta] floors up / down the shaft, **clamped** to `[0, floors-1]` (a
     * tower has a top and a ground floor and does not loop), then fires the
     * dolly-zoom lock on arrival. A press at either end is a no-op.
     *
     * @param delta -1 = up (the floor above, ↑), +1 = down (↓).
     */
    private fun rideFloor(delta: Int) {
        val n = ovCards.size
        if (n == 0) return
        rideFloorTo((ovSelected + delta).coerceIn(0, n - 1))
    }

    /**
     * Selects floor [index] and arms a dolly-zoom lock onto it. A no-op if already
     * selected (so a clamped ride at an end doesn't re-trigger the lock).
     *
     * @param index floor index into [ovCards].
     */
    private fun rideFloorTo(index: Int) {
        if (index == ovSelected || index !in ovCards.indices) return
        selectCard(index)
        lockActive = true
        lockStart = Double.NaN
    }

    /**
     * Lays out one floor's pane tiles as a horizontal row in the slab's local
     * plane, opened only for the selected floor; the highlighted pane lifts proud
     * of the row and pops. Non-selected floors fade their panes out and hide them
     * (their glass slab shows instead).
     *
     * @param card the floor whose panes to lay out.
     * @param selected whether this is the selected (row-bearing) floor.
     */
    private fun layoutRow(card: OverviewCard, selected: Boolean) {
        val n = card.tiles.size
        if (n == 0) return
        card.tiles.forEachIndexed { j, tile ->
            val sel = selected && j == ovPaneSelected
            val tz = ROW_LIFT + (if (sel) ROW_SEL_LIFT else 0.0)
            val mp = tile.mesh
            val cz = mp.position.z as Double
            mp.position.set(rowX(j, n), 0.0, cz + (tz - cz) * ROW_EASE)

            val base = min(ROW_SLOT_W / tile.worldW, ROW_SLOT_H / tile.worldH)
            val ts = base * (if (sel) ROW_SEL_POP else 1.0)
            val cs = mp.scale.x as Double
            val ns = cs + (ts - cs) * ROW_EASE
            mp.scale.set(ns, ns, 1.0)

            val opTarget = if (selected) 1.0 else 0.0
            tile.material.opacity += (opTarget - tile.material.opacity) * ROW_EASE
            mp.visible = tile.material.opacity > 0.03
        }
    }

    /** The height (world units) of floor [i] — floor 0 at the top, later floors below. */
    private fun floorY(i: Int): Double = -i * FLOOR_STEP

    /** The local x of pane [j] of [n] in the floor row, centred on the slab. */
    private fun rowX(j: Int, n: Int): Double = (j - (n - 1) / 2.0) * ROW_DX
}
