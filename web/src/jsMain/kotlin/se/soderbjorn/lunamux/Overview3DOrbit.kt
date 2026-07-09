/**
 * Orbit ("Cosmos") style for the 3D tab overview — the "fly between worlds in
 * deep space" [Overview3DStyle].
 *
 * Every visible tab is a **world**: a lit panel floating at a fixed point on a
 * sphere-shell of stars ([CLOUD_R]) scattered by a golden-angle spiral, each one
 * turned to face outward from the shell's centre. Its panes are **moons** — a
 * ring of tiles orbiting the selected world, hanging just in front of it toward
 * the camera. You are a spacecraft: you never spin a ring (carousel), ride a
 * cylinder (rotunda), or dolly a hall (corridor) — you *fly*, on a long curved
 * arc, from one world to the next.
 *
 * The signature is the **fly-through**. Selecting a world doesn't cut or ease in
 * a straight line: the camera captures its current position and sweeps to the
 * new world along a quadratic Bézier whose control point bulges outward past the
 * shell ([ARC_LIFT]), so it swings *wide* around the constellation, decelerating
 * (ease-out) as it settles into a standoff facing the world. Two flourishes ride
 * on top, both impossible in the flat/ring styles: the camera **banks** — it
 * rolls its up-vector into the turn in proportion to its sideways velocity
 * ([BANK_K]) — and its **field of view widens** at the fastest part of the arc
 * ([FOV_WARP_K]) for a hyperspace-punch that relaxes back as it arrives.
 *
 * Two motions, one metaphor — one for tabs, one for panes:
 *  - **Fly to a world (tabs)** — `←`/`→` (and the wheel / a trackpad swipe) pick
 *    the previous / next world (wrapping — space has no ends) and launch the arc.
 *  - **Turn the moons (panes)** — `↑`/`↓` (and `Tab` / `Shift+Tab`, via the
 *    shared [cyclePane], which also visits the "whole world" slot so `Enter` can
 *    dive the whole tab) rotate the moon-ring so the chosen pane swings to the
 *    front and pops toward you.
 *
 * As with every style, this file owns *only* the cosmos's spatial arrangement and
 * gesture handling; all content capture (live terminal grids, file/git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]). The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style places each world in its cosmos
 * group, parents every world's moons onto its world mesh, and flies the camera.
 *
 * True aspect ratio is preserved: each moon is letterboxed into a fixed slot
 * ([SLOT_W]×[SLOT_H]) by a *uniform* scale, never stretched.
 *
 * @see Overview3DStyle
 * @see VertigoStyle
 * @see CorridorStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Radius (world units) of the star-shell every world sits on, around the origin. */
private const val CLOUD_R = 7.0

/** How far out (world units) from a world the camera stands when facing it. */
private const val STANDOFF = 5.2

/** Standoff the camera closes to while diving into a world / moon. */
private const val ORBIT_DIVE_STANDOFF = 0.7

/** The camera's resting fov (degrees); widened transiently by the warp punch. */
private const val ORBIT_BASE_FOV = 50.0

/** Degrees of fov widening per world-unit/frame of camera speed (the warp punch). */
private const val FOV_WARP_K = 34.0

/** Ceiling (degrees) on the warp-punch fov widening, so the lens never over-blows. */
private const val FOV_WARP_MAX = 22.0

/** Duration (ms) of one world-to-world fly-through arc. */
private const val FLIGHT_MS = 900.0

/** How far past the shell the Bézier control point bulges, so the arc swings wide. */
private const val ARC_LIFT = 4.0

/** Per-frame lerp the camera uses to track drag-orbit / standoff changes when not flying. */
private const val IDLE_EASE = 0.12

/** Per-frame lerp the camera closes its dive standoff during an [beginDive]. */
private const val ORBIT_DIVE_EASE = 0.16

/** Radians of bank roll per world-unit/frame of sideways camera velocity. */
private const val BANK_K = 2.6

/** Ceiling (radians ≈ 32°) on the bank roll into a turn. */
private const val MAX_BANK = 0.55

/** Per-frame lerp the bank roll eases toward its velocity-driven target. */
private const val BANK_EASE = 0.12

/** The golden angle (radians) that spaces worlds evenly over the shell. */
private const val GOLDEN_ANGLE = 2.399963229728653

/** Radius (world-local units) of the moon-ring around a selected world. */
private const val MOON_RING_R = 2.7

/** How far the whole moon-ring floats off the world face toward the camera (+Z local). */
private const val MOON_LIFT = 0.5

/** Extra forward lift the highlighted moon gets so it stands proud of the ring. */
private const val MOON_SEL_LIFT = 0.7

/** Uniform scale bump applied to the highlighted moon. */
private const val MOON_SEL_POP = 1.08

/** Ring angle (radians, -90° = bottom-front) the selected moon rotates to. */
private const val FRONT_ANGLE = -PI / 2.0

/** World-space width the moon tiles are letterboxed into. */
private const val SLOT_W = 1.2

/** World-space height the moon tiles are letterboxed into. */
private const val SLOT_H = 0.85

/** Per-frame lerp the moon-ring phase / each moon's transform glides toward target. */
private const val RING_EASE = 0.18

/** Radians of drag-orbit azimuth per pixel of horizontal drag. */
private const val DRAG_AZ_K = 0.006

/** Radians of drag-orbit elevation per pixel of vertical drag. */
private const val DRAG_EL_K = 0.006

/** Accumulated wheel/swipe delta needed to fly to the next world. Matches other styles. */
private const val ORBIT_WHEEL_STEP = 90.0

/** Idle gap (ms) between wheel events that ends one gesture and re-arms the fly. */
private const val ORBIT_WHEEL_GAP_MS = 220.0

/* --- tiny 3-vector helpers (DoubleArray of 3), file-private ------------- */

/** Component-wise sum `a + b`. */
private fun add3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

/** Component-wise difference `a - b`. */
private fun sub3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

/** Scalar multiple `a * s`. */
private fun scale3(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)

/** Dot product `a · b`. */
private fun dot3(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

/** Cross product `a × b`. */
private fun cross3(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

/** Euclidean length `|a|`. */
private fun len3(a: DoubleArray) = sqrt(dot3(a, a))

/** Unit vector in the direction of [a] (or `(0,0,1)` if [a] is ~zero). */
private fun norm3(a: DoubleArray): DoubleArray {
    val l = len3(a)
    return if (l < 1e-6) doubleArrayOf(0.0, 0.0, 1.0) else scale3(a, 1.0 / l)
}

/**
 * The orbit ("cosmos") [Overview3DStyle]. A stateless singleton apart from the
 * per-open scene state it resets in [build] (its cosmos group, the world
 * positions, the eased camera position / bank / fov, the drag-orbit offsets, the
 * moon-ring phase, and the wheel + flight latches).
 */
internal object OrbitStyle : Overview3DStyle {

    /** Per-open group holding every world mesh (fixed in space; the camera flies). */
    private var cosmos: Group? = null

    /** Fixed world positions, one per card, scattered over the star-shell (set in [build]). */
    private var worlds: Array<DoubleArray> = emptyArray()

    /** Current camera position (world units); flown along the arc / eased each tick. */
    private var camPos = doubleArrayOf(0.0, 0.0, CLOUD_R + STANDOFF)

    /** Previous frame's camera position, for the velocity that drives bank + warp. */
    private var prevPos = camPos.copyOf()

    /** True while a world-to-world fly-through arc is in progress. */
    private var flightActive = false

    /** `performance.now()` the current arc began, or `NaN` when one is pending its first tick. */
    private var flightStart = Double.NaN

    /** Camera position the current arc launched from (its Bézier start point). */
    private var flightFrom = camPos.copyOf()

    /** Drag-orbit azimuth offset (radians) added to every world's facing — persists across worlds. */
    private var dragAz = 0.0

    /** Drag-orbit elevation offset (radians) added to every world's facing. */
    private var dragEl = 0.0

    /** Current eased bank roll (radians) of the camera's up-vector. */
    private var bank = 0.0

    /** Current eased fov (degrees); the warp punch pushes it above [ORBIT_BASE_FOV]. */
    private var fov = ORBIT_BASE_FOV

    /** Current eased moon-ring phase (radians) that rotates the selected moon to the front. */
    private var ringPhase = 0.0

    /** Wheel delta accumulator toward the next single-world fly. */
    private var wheelAcc = 0.0

    /** `performance.now()` of the last observed wheel event (gesture-gap timing). */
    private var wheelLastTs = 0.0

    /**
     * Builds the cosmos: scatters the worlds over the star-shell (golden-angle
     * spiral), turns each to face outward, parents every world's moon tiles onto
     * its world mesh (letterboxed, hidden until the world is selected), and drops
     * the camera at the selected world's standoff. Resets drag / bank / fov /
     * flight state.
     *
     * @param scene the cached scene to add the cosmos group to.
     * @param camera the cached camera (posed separately in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        cosmos = g
        scene.add(g)

        val n = ovCards.size
        worlds = Array(n) { i -> fibonacciShell(i, n) }

        ovCards.forEachIndexed { i, card ->
            val wp = worlds[i]
            card.mesh.position.set(wp[0], wp[1], wp[2])
            // Turn the world to face outward from the shell centre (≈ toward the
            // camera when this world is the selected one).
            val d = norm3(wp)
            card.mesh.rotation.set(-asin(d[1].coerceIn(-1.0, 1.0)), atan2(d[0], d[2]), 0.0)
            g.add(card.mesh)

            // Moons: parent every tile onto its world so it inherits the world's
            // outward orientation; laid out in a ring only while the world is
            // selected (see [layoutMoons]).
            for (tile in card.tiles) {
                val s = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
                tile.mesh.scale.set(s, s, 1.0)
                tile.mesh.position.set(0.0, 0.0, MOON_LIFT)
                tile.mesh.visible = false
                tile.material.opacity = 0.0
                card.mesh.add(tile.mesh)
            }
        }

        dragAz = 0.0
        dragEl = 0.0
        bank = 0.0
        fov = ORBIT_BASE_FOV
        ringPhase = 0.0
        flightActive = false
        flightStart = Double.NaN
        wheelAcc = 0.0
        wheelLastTs = 0.0
        camPos = camTargetFor(ovSelected, diving = false)
        prevPos = camPos.copyOf()
    }

    /** Poses the camera at the selected world's standoff, looking at that world. */
    override fun resetCamera(camera: PerspectiveCamera) {
        camPos = camTargetFor(ovSelected, diving = false)
        prevPos = camPos.copyOf()
        camera.up.set(0.0, 1.0, 0.0)
        camera.position.set(camPos[0], camPos[1], camPos[2])
        val wp = worlds.getOrNull(ovSelected) ?: doubleArrayOf(0.0, 0.0, 0.0)
        camera.lookAt(wp[0], wp[1], wp[2])
        fov = ORBIT_BASE_FOV
        camera.fov = fov
        camera.updateProjectionMatrix()
    }

    /**
     * Per-frame update: flies the camera along the Bézier arc toward the selected
     * world (or eases it when idle / diving), banks it into the turn, warps the
     * fov by speed, then fades every world by distance and lays out the selected
     * world's moon-ring (rotating the chosen moon to the front and popping it).
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera.
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        // Hover pick (skipped mid-dive): hovering a moon on the selected world
        // drives the same highlight the keyboard uses, so mouse and ↑/↓ agree.
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
        val wp = worlds.getOrNull(ovSelected) ?: doubleArrayOf(0.0, 0.0, 0.0)
        val target = camTargetFor(ovSelected, diving)

        // Fly the arc, or ease toward the standoff when settled / diving.
        if (flightActive && !diving) {
            if (flightStart.isNaN()) { flightStart = now; flightFrom = camPos.copyOf() }
            val p = ((now - flightStart) / FLIGHT_MS).coerceIn(0.0, 1.0)
            camPos = bezier3(flightFrom, arcControl(flightFrom, target), target, ease(p))
            if (p >= 1.0) { flightActive = false; flightStart = Double.NaN }
        } else {
            val k = if (diving) ORBIT_DIVE_EASE else IDLE_EASE
            camPos = add3(camPos, scale3(sub3(target, camPos), k))
            if (diving) { flightActive = false; flightStart = Double.NaN }
        }

        // Bank into the turn from the sideways component of this frame's velocity,
        // then roll the up-vector by that angle around the view axis.
        val vel = sub3(camPos, prevPos)
        val view = norm3(sub3(wp, camPos))
        val right = norm3(cross3(view, doubleArrayOf(0.0, 1.0, 0.0)))
        val bankTarget = (-dot3(vel, right) * BANK_K).coerceIn(-MAX_BANK, MAX_BANK)
        bank += (bankTarget - bank) * BANK_EASE
        val up = rollUp(view, bank)
        camera.up.set(up[0], up[1], up[2])
        camera.position.set(camPos[0], camPos[1], camPos[2])
        camera.lookAt(wp[0], wp[1], wp[2])

        // Warp punch: widen the fov with speed, relax it back as the arc settles.
        val fovTarget = ORBIT_BASE_FOV + min(len3(vel) * FOV_WARP_K, FOV_WARP_MAX)
        fov += (fovTarget - fov) * 0.15
        camera.fov = fov
        camera.updateProjectionMatrix()

        // Fade worlds by distance (the selected one dimmed a touch so its bright
        // moons read in front of it), and lay out the selected world's moon-ring.
        ovCards.forEachIndexed { i, card ->
            val selected = i == ovSelected
            val dist = len3(sub3(camPos, worlds[i]))
            val distOp = (1.25 - dist / (CLOUD_R * 2.0)).coerceIn(0.32, 0.9)
            val faceOp = if (selected) 0.5 else distOp
            val co = card.cardMaterial.opacity
            card.cardMaterial.opacity = co + (faceOp - co) * 0.16

            val glowTarget = if (selected) 0.4 else 0.0
            card.glowMaterial.opacity += (glowTarget - card.glowMaterial.opacity) * 0.12

            layoutMoons(card, selected)
        }
        prevPos = camPos.copyOf()
    }

    /** `←`/`→` fly between worlds; `↑`/`↓` and `Tab` / `Shift+Tab` turn the moon-ring. */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT -> flyWorld(-1)
            OvNav.RIGHT -> flyWorld(1)
            OvNav.UP, OvNav.PANE_PREV -> cyclePane(-1)
            OvNav.DOWN, OvNav.PANE_NEXT -> cyclePane(1)
        }
    }

    /**
     * Wheel / trackpad fly: accumulates the dominant-axis delta and flies one world
     * each time it crosses ±[ORBIT_WHEEL_STEP]; the accumulator re-arms after
     * [ORBIT_WHEEL_GAP_MS] of silence so a momentum tail can't fly an extra world.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (gesture timing).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        if (nowMs - wheelLastTs > ORBIT_WHEEL_GAP_MS) wheelAcc = 0.0
        wheelLastTs = nowMs
        wheelAcc += delta
        if (wheelAcc > ORBIT_WHEEL_STEP) { flyWorld(1); wheelAcc = 0.0 }
        if (wheelAcc < -ORBIT_WHEEL_STEP) { flyWorld(-1); wheelAcc = 0.0 }
    }

    /**
     * Pointer drag — a persistent orbit control: horizontal drag swings the camera
     * around the selected world in azimuth, vertical drag in elevation, so you can
     * inspect a world from any angle. The offsets carry over to the next world.
     *
     * @param dx horizontal movement (px) since the last event.
     * @param dy vertical movement (px) since the last event.
     */
    override fun drag(dx: Double, dy: Double) {
        dragAz += dx * DRAG_AZ_K
        dragEl = (dragEl - dy * DRAG_EL_K).coerceIn(-1.2, 1.2)
    }

    /** The selected world's visible moons, plus every world mesh (to fly to another). */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        for (card in ovCards) meshes.add(card.mesh.asDynamic())
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null) for (tile in sel.tiles) if (tile.mesh.visible) meshes.add(tile.mesh.asDynamic())
        return meshes.toTypedArray()
    }

    /**
     * Click handling: a moon on the selected world → focus it and dive that pane;
     * the selected world's face → dive the whole tab; another world → fly to it;
     * empty space → close.
     *
     * @param userData the nearest raycast hit's payload (`index` = world,
     *   `paneId` = moon), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx != ovSelected) { flyWorldTo(idx); return }
        val pid = userData.paneId as? String
        if (pid != null) {
            val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == pid }
            if (ti >= 0) setPaneSelection(ti)
        }
        beginDive(pid)
    }

    /** Removes the cosmos group from the scene (worlds/moons disposed by the core). */
    override fun teardown(scene: Scene) {
        cosmos?.let { scene.remove(it) }
        cosmos = null
        worlds = emptyArray()
    }

    /**
     * Footer hint for the cosmos's controls: the arrows fly between worlds, `↑`/`↓`
     * turn the moon-ring, a drag orbits the current world, and the wheel flies too.
     */
    override fun hint(): String =
        "← → fly worlds · ↑ ↓ panes · drag to orbit · scroll to fly · ⏎ enter · esc close"

    /**
     * Flies [delta] worlds around the constellation (wrapping — space has no ends)
     * and launches the arc. A press with only one world is a no-op.
     *
     * @param delta -1 = previous world (←), +1 = next (→).
     */
    private fun flyWorld(delta: Int) {
        val n = ovCards.size
        if (n <= 1) return
        flyWorldTo(((ovSelected + delta) % n + n) % n)
    }

    /**
     * Selects world [index] and launches a fly-through arc to it (capturing the
     * current camera position as the arc's start). A no-op if already selected.
     *
     * @param index world index into [ovCards].
     */
    private fun flyWorldTo(index: Int) {
        if (index == ovSelected || index !in ovCards.indices) return
        selectCard(index)
        flightActive = true
        flightStart = Double.NaN
        flightFrom = camPos.copyOf()
    }

    /**
     * The camera standoff position for facing world [i]: the world's outward
     * facing (rotated by the drag-orbit offsets) stepped out by the standoff
     * distance ([STANDOFF], or [ORBIT_DIVE_STANDOFF] while diving).
     *
     * @param i world index into [worlds].
     * @param diving whether an activation dive is in progress.
     * @return the target camera position in world units.
     */
    private fun camTargetFor(i: Int, diving: Boolean): DoubleArray {
        val wp = worlds.getOrNull(i) ?: return doubleArrayOf(0.0, 0.0, CLOUD_R + STANDOFF)
        val d0 = norm3(wp)
        val az = atan2(d0[0], d0[2]) + dragAz
        val el = (asin(d0[1].coerceIn(-1.0, 1.0)) + dragEl).coerceIn(-1.35, 1.35)
        val dir = doubleArrayOf(cos(el) * sin(az), sin(el), cos(el) * cos(az))
        val standoff = if (diving) ORBIT_DIVE_STANDOFF else STANDOFF
        return add3(wp, scale3(dir, standoff))
    }

    /**
     * Lays out one world's moon tiles as a ring in the world's local plane, opened
     * only for the selected world; the chosen moon rotates to the front
     * ([FRONT_ANGLE]) and lifts + pops toward the camera. Non-selected worlds fade
     * their moons out and hide them (their world face shows instead).
     *
     * @param card the world whose moons to lay out.
     * @param selected whether this is the selected (moon-bearing) world.
     */
    private fun layoutMoons(card: OverviewCard, selected: Boolean) {
        val m = card.tiles.size
        if (m == 0) return
        if (selected) {
            val selIdx = if (ovPaneSelected in 0 until m) ovPaneSelected else -1
            if (selIdx >= 0) {
                val targetPhase = FRONT_ANGLE - selIdx.toDouble() / m * (2.0 * PI)
                ringPhase += atan2(sin(targetPhase - ringPhase), cos(targetPhase - ringPhase)) * RING_EASE
            }
        }
        card.tiles.forEachIndexed { j, tile ->
            val show = selected
            val sel = selected && j == ovPaneSelected
            val a = j.toDouble() / m * (2.0 * PI) + ringPhase
            val tx = if (show) cos(a) * MOON_RING_R else 0.0
            val ty = if (show) sin(a) * MOON_RING_R else 0.0
            val tz = MOON_LIFT + (if (sel) MOON_SEL_LIFT else 0.0)
            val mp = tile.mesh
            val cx = mp.position.x as Double
            val cy = mp.position.y as Double
            val cz = mp.position.z as Double
            mp.position.set(cx + (tx - cx) * RING_EASE, cy + (ty - cy) * RING_EASE, cz + (tz - cz) * RING_EASE)

            val base = min(SLOT_W / tile.worldW, SLOT_H / tile.worldH)
            val ts = base * (if (sel) MOON_SEL_POP else 1.0)
            val cs = mp.scale.x as Double
            val ns = cs + (ts - cs) * RING_EASE
            mp.scale.set(ns, ns, 1.0)

            val opTarget = if (show) 1.0 else 0.0
            tile.material.opacity += (opTarget - tile.material.opacity) * RING_EASE
            mp.visible = tile.material.opacity > 0.03
        }
    }

    /**
     * A world's fixed position on the star-shell via a spherical Fibonacci
     * (golden-angle) spiral, so any tab count spreads evenly over the sphere.
     *
     * @param i world index. @param n total world count.
     * @return the world position in world units (on the shell of radius [CLOUD_R]).
     */
    private fun fibonacciShell(i: Int, n: Int): DoubleArray {
        val count = if (n <= 0) 1 else n
        val y = 1.0 - 2.0 * (i + 0.5) / count
        val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
        val theta = i * GOLDEN_ANGLE
        return doubleArrayOf(cos(theta) * r * CLOUD_R, y * CLOUD_R, sin(theta) * r * CLOUD_R)
    }

    /**
     * The quadratic-Bézier control point for the arc from [from] to [to]: the
     * midpoint pushed outward past the shell by [ARC_LIFT] so the flight swings
     * wide around the constellation rather than cutting straight across.
     *
     * @param from the arc's start position. @param to the arc's end position.
     * @return the control point in world units.
     */
    private fun arcControl(from: DoubleArray, to: DoubleArray): DoubleArray {
        val mid = scale3(add3(from, to), 0.5)
        val ml = len3(mid)
        val base = if (ml < 1e-3) norm3(from) else scale3(mid, 1.0 / ml)
        return scale3(base, maxOf(ml, CLOUD_R + STANDOFF) + ARC_LIFT)
    }

    /**
     * A point on the quadratic Bézier `(1-t)²a + 2(1-t)t c + t²b`.
     *
     * @param a start point. @param c control point. @param b end point.
     * @param t curve parameter in `[0, 1]`.
     * @return the interpolated point.
     */
    private fun bezier3(a: DoubleArray, c: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        val mt = 1.0 - t
        val w0 = mt * mt
        val w1 = 2.0 * mt * t
        val w2 = t * t
        return doubleArrayOf(
            a[0] * w0 + c[0] * w1 + b[0] * w2,
            a[1] * w0 + c[1] * w1 + b[1] * w2,
            a[2] * w0 + c[2] * w1 + b[2] * w2,
        )
    }

    /**
     * The world up-vector `(0,1,0)` rolled by [angle] around the view axis [view]
     * (Rodrigues rotation), producing the banked up the camera leans into a turn.
     *
     * @param view the (unit) view direction the roll is about.
     * @param angle the bank roll in radians.
     * @return the rolled, unit-length up-vector.
     */
    private fun rollUp(view: DoubleArray, angle: Double): DoubleArray {
        val wUp = doubleArrayOf(0.0, 1.0, 0.0)
        val c = cos(angle)
        val s = sin(angle)
        val term1 = scale3(wUp, c)
        val term2 = scale3(cross3(view, wUp), s)
        val term3 = scale3(view, dot3(view, wUp) * (1.0 - c))
        return norm3(add3(add3(term1, term2), term3))
    }
}
