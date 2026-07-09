/**
 * Rotunda style for the 3D tab overview — the "you stand inside a cylinder"
 * [Overview3DStyle].
 *
 * The whole overview is one tall rotunda you stand at the centre of. Each visible
 * tab is a **floor** of the building; that tab's panes are panels papered around
 * the cylinder **wall** at that floor's height. Two motions, one metaphor:
 *  - **Look around** the current floor — drag left/right (mouse / trackpad) or
 *    press `←`/`→` — spins the wall so the previous / next panel swings to face
 *    you; the panel dead ahead is the selection.
 *  - **Ride the elevator** between floors (tabs) — the wheel / a trackpad swipe,
 *    or `↑`/`↓` / `Tab` — glides you vertically to the previous / next floor,
 *    the wall re-papered with that tab's panes.
 *
 * Off-axis panels (turned away from you) and off-floor panels (above / below the
 * floor you're on) dim, so the panel you're looking at on the floor you're on is
 * always the brightest — mirroring the CSS-3D pitch's `cos(world)` squareness ×
 * floor-distance fade. Clicking a panel (or `Enter`) dives the camera through it
 * into the real tab; clicking empty space (or `Esc`) closes.
 *
 * Like every style, this file owns *only* the rotunda's spatial arrangement and
 * gesture handling; all content capture (live terminal grids, file/git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]). The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style parents the pane-tile meshes into
 * its cylinder group and animates the group (yaw = look-around,
 * `position.y` = elevator).
 *
 * True aspect ratio is preserved: each pane tile is letterboxed into a fixed
 * wall slot by a *uniform* scale ([SLOT_W]/[SLOT_H] via `min` of the two fits),
 * never stretched.
 *
 * @see Overview3DStyle
 * @see CarouselStyle
 * @see ExposeStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Radius of the cylinder wall (world units). Sized well clear of a pane slot
 * ([SLOT_W]) so panels at the ~[PANE_STEP_MAX] spacing never overlap, yet close
 * enough that the front panel fills the view. Tuned against `CARD_W ≈ 3.2`.
 */
private const val WALL_RADIUS = 4.0

/**
 * Vertical distance between adjacent floors (world units). Comfortably larger
 * than a pane slot's height ([SLOT_H]) so a floor you ride to lands clearly
 * above/below the one you left and neighbouring floors read as separate storeys.
 */
private const val FLOOR_HEIGHT = 2.6

/** World-space width of the wall slot each pane is letterboxed into. */
private const val SLOT_W = 3.0

/** World-space height of the wall slot each pane is letterboxed into. */
private const val SLOT_H = 1.95

/**
 * Largest angular gap between adjacent panels on a floor (radians ≈ 50°). Floors
 * with few panes fan them out at this comfortable spacing near the front (the
 * rest of the wall stays bare); a floor with enough panes to wrap the full circle
 * falls back to an even `2π / paneCount` so the last panel doesn't overrun the
 * first. @see paneAngle
 */
private const val PANE_STEP_MAX = 50.0 * PI / 180.0

/**
 * Camera offset back from the cylinder axis (world units, +Z). Essentially "at
 * the axis" (the rotunda's centre) but nudged back a hair so the front panel's
 * neighbours peek into frame at the edges rather than sitting entirely off to
 * the sides. @see resetCamera
 */
private const val CAMERA_Z = 0.9

/** Per-frame easing fraction the wall yaw moves toward its target each tick. */
private const val YAW_EASE = 0.16

/** Per-frame easing fraction the elevator (floor height) moves each tick. */
private const val FLOOR_EASE = 0.09

/** Radians of wall spin per pixel of horizontal drag (look-around sensitivity). */
private const val DRAG_YAW_K = 0.006

/**
 * Accumulated wheel/swipe delta (either axis) needed to ride one floor. Matches
 * the carousel's feel so a light trackpad flick doesn't skip several tabs.
 */
private const val WHEEL_STEP = 90.0

/**
 * Idle gap (ms) between wheel events that ends one gesture and re-arms the floor
 * accumulator, so a decaying momentum tail can't ride an extra floor.
 */
private const val WHEEL_GESTURE_GAP_MS = 220.0

/**
 * How sharply a panel dims as it turns away from you: the squared cosine of its
 * facing angle is multiplied by this floor-distance falloff. `1 - fd × this`
 * (clamped ≥ 0) reaches zero ~1.8 floors away, so only the current floor and its
 * immediate neighbours show through the wall.
 */
private const val FLOOR_FALLOFF = 0.55

/** Uniform scale bump given to the currently selected (front) panel. */
private const val SELECTED_POP = 1.05

/**
 * The rotunda [Overview3DStyle]. A stateless singleton apart from the per-open
 * cylinder state it resets in [build] (its wall group, eased yaw + elevator
 * height, and the wheel-gesture floor accumulator).
 */
internal object RotundaStyle : Overview3DStyle {

    /** Per-open group = the cylinder wall; spun via `rotation.y`, raised via `position.y`. */
    private var group: Group? = null

    /** Current wall rotation (radians); eased toward [yawTarget]. */
    private var yaw = 0.0

    /** Wall rotation that brings the selected panel to front (or free-look target). */
    private var yawTarget = 0.0

    /** Current elevator height applied to the group (world units); eased toward the floor. */
    private var floorY = 0.0

    /** Wheel delta accumulator toward the next single-floor ride. */
    private var wheelAcc = 0.0

    /** `performance.now()` of the last observed wheel event (gesture-gap timing). */
    private var wheelLastTs = 0.0

    /**
     * Builds the cylinder: parents every card's pane tiles into one wall group,
     * placing each tile at its floor height and wall angle (tangent, facing
     * inward toward you) and letterboxing it into the wall slot with a uniform
     * scale. Seeds the pane selection (a rotunda always faces a specific panel,
     * never the "whole tab" slot), snaps the yaw to face it, and drops the
     * elevator onto the selected floor. Resets the wheel accumulator.
     *
     * Called once by [openOverview3d] right after the core populates [ovCards] /
     * [ovSelected] / [ovPaneSelected].
     *
     * @param scene the cached scene to add the wall group to.
     * @param camera the cached camera (posed separately in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        group = g
        scene.add(g)

        ovCards.forEachIndexed { i, card ->
            card.tiles.forEachIndexed { j, tile ->
                val theta = paneAngle(i, j)
                // Tangent to the wall at (floor height, wall angle), rotated to
                // face the axis (you) — plane normal +Z spun by -theta points
                // inward. Floor i sits at y = -i·FLOOR_HEIGHT so the *first* tab is
                // the top floor and later tabs descend. See paneAngle for the angle.
                tile.mesh.position.set(WALL_RADIUS * sin(theta), -i * FLOOR_HEIGHT, -WALL_RADIUS * cos(theta))
                tile.mesh.rotation.set(0.0, -theta, 0.0)
                val s = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
                tile.mesh.scale.set(s, s, 1.0)
                tile.mesh.visible = false
                g.add(tile.mesh)
            }
        }

        // Face a concrete panel from the first frame (never the -1 "whole tab").
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null && sel.tiles.isNotEmpty() && ovPaneSelected !in sel.tiles.indices) {
            setPaneSelection(0)
        }
        val pane = if (ovPaneSelected >= 0) ovPaneSelected else 0
        yaw = paneAngle(ovSelected, pane)
        yawTarget = yaw
        floorY = ovSelected * FLOOR_HEIGHT
        g.rotation.y = yaw
        g.position.y = floorY

        wheelAcc = 0.0
        wheelLastTs = 0.0
    }

    /**
     * Poses the camera at the cylinder axis (nudged back by [CAMERA_Z]) looking
     * straight out along -Z at the wall. Look-around is done by rotating the wall
     * group, never the camera. Called once after [build].
     *
     * @param camera the cached camera.
     */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.position.set(0.0, 0.0, CAMERA_Z)
        camera.lookAt(0.0, 0.0, -1.0)
    }

    /**
     * Per-frame update: eases the wall yaw toward [yawTarget] (shortest arc) and
     * the elevator toward the selected floor, then fades each panel by how
     * squarely it faces you (`cos²`) times how near its floor is, drives
     * hover-to-select so the mouse and `↑`/`↓` share one highlight, and — while a
     * dive is animating — flies the camera through the front panel into the tab.
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera (moved only during the dive).
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        val g = group ?: return

        // Ease the wall toward the target angle along the shortest arc, and the
        // elevator toward the selected floor's height.
        val d = atan2(sin(yawTarget - yaw), cos(yawTarget - yaw))
        yaw += d * YAW_EASE
        val floorTarget = ovSelected * FLOOR_HEIGHT
        floorY += (floorTarget - floorY) * FLOOR_EASE
        g.rotation.y = yaw
        g.position.y = floorY

        // Hover pick (skipped mid-dive). Hovering a current-floor panel drives the
        // same highlight the keyboard uses, so mouse and ↑/↓ agree.
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

        // Fade each panel by facing squareness × floor proximity, and pop the
        // selected panel a touch. Positions/rotations are fixed (set in build);
        // only the group transform, opacity, visibility, and selection scale move.
        ovCards.forEachIndexed { i, card ->
            val worldFloorY = -i * FLOOR_HEIGHT + floorY
            val floorFade = (1.0 - abs(worldFloorY) / FLOOR_HEIGHT * FLOOR_FALLOFF).coerceIn(0.0, 1.0)
            card.tiles.forEachIndexed { j, tile ->
                val theta = paneAngle(i, j)
                val face = max(0.0, cos(theta - yaw))
                val o = face * face * floorFade
                tile.material.opacity = o
                tile.mesh.visible = o > 0.02

                val selected = i == ovSelected && j == ovPaneSelected
                val base = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
                val target = base * (if (selected) SELECTED_POP else 1.0)
                val cur = tile.mesh.scale.x as Double
                val ns = cur + (target - cur) * 0.2
                tile.mesh.scale.set(ns, ns, 1.0)
            }
        }

        // Dive-in: fly the camera along the axis through the front panel while the
        // CSS fade runs; the core closes the overview when the window completes.
        if (!ovDiveStart.isNaN()) {
            val p = ((now - ovDiveStart) / DIVE_MS).coerceIn(0.0, 1.0)
            val ez = ease(p)
            val targetZ = -(WALL_RADIUS - 0.7)
            camera.position.set(0.0, 0.0, CAMERA_Z + (targetZ - CAMERA_Z) * ez)
        }
    }

    /**
     * Keyboard navigation, matched to the rotunda's geometry so a key moves the
     * view the way it points. `←`/`→` (and Tab / Shift+Tab) spin the wall one
     * panel — the panes of the current floor — and wrap around the circle.
     * `↑`/`↓` ride the elevator: `↑` to the floor **above** (the next tab up the
     * stack), `↓` to the floor **below**. Floor riding is *clamped* at the ground
     * and top floors — it never wraps — because a rotunda is a finite building,
     * not a loop, and wrapping is exactly what made `↓` at the top jump all the
     * way to the bottom. `Enter` is handled by the core.
     *
     * @param dir the directional intent from the core key handler.
     */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT, OvNav.PANE_PREV -> stepPane(-1)
            OvNav.RIGHT, OvNav.PANE_NEXT -> stepPane(1)
            // Floor i sits at y = -i·FLOOR_HEIGHT, so the first tab is the top
            // floor and higher indices descend: ↑ = -1 floor (toward the top), ↓ = +1.
            OvNav.UP -> rideFloor(-1)
            OvNav.DOWN -> rideFloor(1)
        }
    }

    /**
     * Wheel / trackpad ride: accumulates the dominant-axis delta and glides one
     * floor each time it crosses ±[WHEEL_STEP]; the accumulator re-arms after
     * [WHEEL_GESTURE_GAP_MS] of silence so a momentum tail can't ride an extra
     * floor.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (gesture timing).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        if (nowMs - wheelLastTs > WHEEL_GESTURE_GAP_MS) wheelAcc = 0.0
        wheelLastTs = nowMs
        wheelAcc += delta
        // Scroll down (positive delta) rides down a floor (toward higher tab
        // indices), scroll up rides up — matching the ↑/↓ keys. rideFloor clamps,
        // so a flick can't wrap.
        if (wheelAcc > WHEEL_STEP) { rideFloor(1); wheelAcc = 0.0 }
        if (wheelAcc < -WHEEL_STEP) { rideFloor(-1); wheelAcc = 0.0 }
    }

    /**
     * Pointer look-around: horizontal drag spins the wall directly (free-look,
     * no snap-back), like grabbing the wall and turning it. Vertical drag is left
     * to the wheel / keyboard for floor riding, avoiding a mixed-axis drag
     * yanking the yaw target.
     *
     * @param dx horizontal movement (px) since the last event.
     * @param dy vertical movement (px) since the last event (currently unused).
     */
    override fun drag(dx: Double, dy: Double) {
        yaw -= dx * DRAG_YAW_K
        yawTarget = yaw
    }

    /** The current floor's pane-tile meshes (dim/back-facing ones are skipped by the raycaster while invisible). */
    override fun pickables(): Array<dynamic> {
        val card = ovCards.getOrNull(ovSelected) ?: return emptyArray()
        return card.tiles.map { it.mesh.asDynamic() }.toTypedArray()
    }

    /**
     * Click handling: a panel → select it, face it, and dive into that pane; empty
     * space → close the overview.
     *
     * @param userData the nearest raycast hit's payload (`index` = card, `paneId`
     *   = tile), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx != ovSelected) { selectCard(idx); return }
        val pid = userData.paneId as? String
        if (pid != null) {
            val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == pid }
            if (ti >= 0) { setPaneSelection(ti); yawTarget = paneAngle(ovSelected, ti) }
        }
        beginDive(pid)
    }

    /** Removes the wall group from the scene (tiles/cards disposed by the core). */
    override fun teardown(scene: Scene) {
        group?.let { scene.remove(it) }
        group = null
    }

    /**
     * Footer hint for the rotunda's controls, which differ from the carousel:
     * the wall is horizontal (so `←`/`→` and drag move between *panes*) and the
     * floors are vertical (so `↑`/`↓` and the wheel move between *tabs*).
     */
    override fun hint(): String =
        "← → panes · ↑ ↓ tabs · drag to look · scroll to change tab · ⏎ open · esc close"

    /**
     * Looks around the current floor by [delta] panels: wraps the pane selection
     * within the floor (never the -1 "whole tab" slot) and points [yawTarget] at
     * the newly selected panel so the wall spins it to the front.
     *
     * @param delta -1 = previous panel (←), +1 = next panel (→).
     */
    private fun stepPane(delta: Int) {
        val card = ovCards.getOrNull(ovSelected) ?: return
        val m = card.tiles.size
        if (m == 0) return
        val cur = if (ovPaneSelected in 0 until m) ovPaneSelected else 0
        val next = ((cur + delta) % m + m) % m
        setPaneSelection(next)
        yawTarget = paneAngle(ovSelected, next)
    }

    /**
     * Rides the elevator by [delta] floors, **clamped** to `[0, floors-1]` (never
     * wrapping — a building has a ground and a top), then seeds a concrete panel
     * on the arrival floor and aims [yawTarget] at it so the wall re-orients as
     * the elevator glides (the elevator height itself is eased in [tick] off
     * [ovSelected]). A press at the ground/top floor that would leave the building
     * is a no-op.
     *
     * @param delta +1 = one floor up (↑), -1 = one floor down (↓).
     */
    private fun rideFloor(delta: Int) {
        val n = ovCards.size
        if (n == 0) return
        val next = (ovSelected + delta).coerceIn(0, n - 1)
        if (next != ovSelected) selectCard(next)
        val card = ovCards.getOrNull(ovSelected) ?: return
        if (card.tiles.isNotEmpty() && ovPaneSelected !in card.tiles.indices) setPaneSelection(0)
        val pane = if (ovPaneSelected in card.tiles.indices) ovPaneSelected else 0
        yawTarget = paneAngle(ovSelected, pane)
    }

    /**
     * The wall angle (radians) of pane [tileIndex] on floor [cardIndex]. Panels
     * fan out at [PANE_STEP_MAX] spacing from the front (pane 0 at angle 0),
     * falling back to an even `2π / paneCount` once there are enough panes to wrap
     * the circle. A panel at angle θ faces the front (you) when the wall yaw
     * equals θ, so `cos(θ - yaw)` is its facing squareness.
     *
     * @param cardIndex floor (index into [ovCards]).
     * @param tileIndex pane (index into the floor's tiles).
     * @return the pane's angle around the wall, or 0 for a single-pane floor.
     */
    private fun paneAngle(cardIndex: Int, tileIndex: Int): Double {
        val card = ovCards.getOrNull(cardIndex) ?: return 0.0
        val m = card.tiles.size
        if (m <= 1) return 0.0
        val step = min(PANE_STEP_MAX, 2.0 * PI / m)
        return tileIndex * step
    }
}
