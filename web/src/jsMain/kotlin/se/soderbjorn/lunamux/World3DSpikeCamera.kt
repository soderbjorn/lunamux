/*
 * Split from World3DSpike.kt — free-fly camera flight model and view toggles.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.CSS3DRenderer
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene

/**
 * Toggles free-fly camera mode (`F`). Entering seeds the pose from the current
 * pristine default (so flight starts exactly where the camera sits); exiting
 * fixates the camera where it is and hands the arrows back to pane navigation.
 */
internal fun toggleFlyMode() {
    spikeStashChase = null // taking manual control cancels any stash chase
    if (spikeFlyMode) {
        spikeFlyMode = false
        spikeFlyKeys.clear()
        clearFlyVelocity() // land: kill momentum so the fixated pose holds still
    } else {
        if (!spikeCamFlown) {
            spikeCamX = 0.0; spikeCamY = 0.0
            spikeCamZ = RING_R + perspDistance(window.innerHeight)
            resetFlyBasis()
        }
        clearFlyVelocity()
        spikeCamReturning = false
        spikeFlyMode = true
    }
    // Swap the bottom-left legend to match the mode (navigate ↔ fly),
    // honouring the shared `k` hidden flag.
    updateLegendVisibility()
}

/**
 * Arms a **cinematic camera tour**: an out-and-up Bézier flight from the camera's
 * current pose to [tx]/[ty]/[tz], facing [lookX]/[lookY]/[lookZ] the whole way, that
 * the render loop's [spikeCamReturning] tracer plays out over [frames] frames. The
 * generalized engine behind both the `c` return ([resetCamera] — fly home, face the
 * origin, land pristine) and a **stash flight** ([stashFront] — fly up to frame the
 * shelf, face the shelf, park flown). Snapshots the start pose, computes the apex
 * (midpoint of start→target pushed radially outward from the origin by
 * [CAM_RETURN_PULLOUT] and lifted +Y by [CAM_RETURN_RISE], so the arc pulls back to
 * reveal the scene then descends to land), and stores the target/look/land mode.
 *
 * @param tx/ty/tz the world pose the arc lands on.
 * @param lookX/lookY/lookZ the point the camera faces throughout the flight.
 * @param landPristine on arrival, `true` drops the camera to the pristine 1:1 default
 *   ([spikeCamFlown] = false, as `c` does); `false` leaves it parked at the flown target.
 * @param frames journey length in frames (~/60 s).
 * @param pullout how far the apex bulges radially out from the origin (arc width).
 * @param rise how far the apex lifts in +Y (the descend-to-land height).
 * @param followPaneId if non-null, the camera tracks that pane's live position instead
 *   of the fixed look point, keeping it centred through the flight. @see startSpikeLoop
 * @param sway horizontal bulge (world units, mid-flight) bowing the path sideways along
 *   the perpendicular of the straight start→target line; sign picks the side, `0.0` flies
 *   the classic in-plane arc. @see STASH_CAM_SWAY
 * @param roll max bank (radians) about the camera's own nose, `sin 2πs` profile — leans
 *   into the journey, unwinds, counter-banks, lands level. @see STASH_CAM_ROLL
 * @param landRoll a **permanent** bank (radians) the flight eases into and **holds** at
 *   landing — unlike [roll] it does not unwind, so the parked pose stays tilted. `0.0`
 *   lands level; the [flyOverview] hero shot sets it for the "slightly rotated" picture.
 *   @see spikeCamTourLandRoll
 * @param endLook if non-null, the aim eases from the in-flight look (fixed point or
 *   [followPaneId] pane) toward this point over the final [CAM_TOUR_END_BLEND] of the
 *   flight — swinging the gaze to frame the destination sign on touchdown while still
 *   tracking the pane on the way. @see spikeCamTourHasEndLook
 * @param then optional follow-on the render loop runs the frame this flight lands —
 *   chains a second leg onto the journey (the fly-through-the-door stash flights use it
 *   to sequence approach → transit → interior). @see spikeCamTourThen @see flyStationEnter
 */
internal fun flyCamTo(
    tx: Double, ty: Double, tz: Double,
    lookX: Double, lookY: Double, lookZ: Double,
    landPristine: Boolean,
    frames: Double = CAM_RETURN_FRAMES,
    pullout: Double = CAM_RETURN_PULLOUT,
    rise: Double = CAM_RETURN_RISE,
    followPaneId: String? = null,
    sway: Double = 0.0,
    roll: Double = 0.0,
    landRoll: Double = 0.0,
    endLook: Triple<Double, Double, Double>? = null,
    then: (() -> Unit)? = null,
) {
    clearFlyVelocity()
    spikeShelfPanTargetX = null // a scripted flight overrides any pending shelf dolly

    // If the camera is still at the pristine default (never flown), seed the flight
    // start from that pose so the arc launches from where it visually sits.
    if (!spikeCamFlown) {
        spikeCamX = 0.0; spikeCamY = 0.0
        spikeCamZ = RING_R + perspDistance(window.innerHeight)
        resetFlyBasis()
    }
    // Mark the camera flown so the render loop drives it from the tour-updated pose
    // ([spikeCamX]/Y/Z) instead of snapping back to the pristine default each frame.
    // A `landPristine` tour clears this again on arrival; a shelf tour stays parked.
    spikeCamFlown = true

    // Journey start = wherever the camera currently sits — and wherever it currently
    // *points* (the tracer blends the aim from this nose, so launch is snap-free).
    spikeCamReturnStartX = spikeCamX; spikeCamReturnStartY = spikeCamY; spikeCamReturnStartZ = spikeCamZ
    spikeCamReturnStartUx = spikeCamUx; spikeCamReturnStartUy = spikeCamUy; spikeCamReturnStartUz = spikeCamUz
    spikeCamReturnStartFx = spikeCamFx; spikeCamReturnStartFy = spikeCamFy; spikeCamReturnStartFz = spikeCamFz

    // Apex = midpoint of start→target, pushed radially outward from the origin (pull
    // back to reveal the scene) and lifted in +Y (so the final leg descends).
    val mx = (spikeCamReturnStartX + tx) * 0.5
    val my = (spikeCamReturnStartY + ty) * 0.5
    val mz = (spikeCamReturnStartZ + tz) * 0.5
    val ml = sqrt(mx * mx + my * my + mz * mz)
    val ox = if (ml > 1.0) mx / ml else 0.0
    val oy = if (ml > 1.0) my / ml else 0.0
    val oz = if (ml > 1.0) mz / ml else 1.0
    spikeCamReturnApexX = mx + ox * pullout
    spikeCamReturnApexY = my + oy * pullout + rise
    spikeCamReturnApexZ = mz + oz * pullout

    spikeCamTourTargetX = tx; spikeCamTourTargetY = ty; spikeCamTourTargetZ = tz
    spikeCamTourLookX = lookX; spikeCamTourLookY = lookY; spikeCamTourLookZ = lookZ
    spikeCamTourLandPristine = landPristine
    spikeCamTourFrames = frames
    spikeCamTourFollowPaneId = followPaneId
    spikeCamTourSway = sway
    spikeCamTourRoll = roll
    spikeCamTourLandRoll = landRoll
    spikeCamTourHasEndLook = endLook != null
    endLook?.let { (ex, ey, ez) ->
        spikeCamTourEndLookX = ex; spikeCamTourEndLookY = ey; spikeCamTourEndLookZ = ez
    }

    spikeCamTourThen = then

    spikeCamReturnT = 0.0
    spikeCamReturning = true
}

/**
 * Kicks off the **cinematic `c` return** to the pristine 1:1 pose — a [flyCamTo] home
 * (target the home pose, face the origin, land pristine). No-op if the camera is
 * already home. Returning to the ring (camera low) is also what makes Space stash
 * again rather than unstash. @see cameraAtShelf @see CAM_RETURN_FRAMES
 */
internal fun resetCamera() {
    spikeStashChase = null // `c` cancels a stash chase and flies home
    spikeShelfPanTargetX = null // …and cancels any pending dock dolly so nothing tugs it back
    if (!spikeCamFlown) return
    val homeZ = RING_R + perspDistance(window.innerHeight)
    if (cameraAtShelf()) {
        if (stationBuilt()) {
            // No-pane hangar return — a "backing out of the hangar" descent. The camera
            // keeps the whole glowing cargo ship framed the *entire* way: it noses out
            // through the bay door while still looking back at the hangar (leg A — it backs
            // out facing the door, which fills the screen), then descends home watching the
            // ship recede grandly (leg B). Both legs aim at the ship because it is the only
            // thing big enough to see on the long drop to the ring — the command center and
            // ring are distant specks until the very end, and the cosmos is off, so aiming
            // anywhere else gives a black void (the earlier "exit looking outward, then whip
            // 180° to the ship" did exactly that: the outward look and the turn were ~6 s of
            // empty space). Only the final stretch eases the aim down to the ring (origin)
            // for the clean 1:1 landing.
            val stage = stationStagingPose()
            flyCamTo(
                stage.cx, stage.cy, stage.cz,
                STATION_CX, STATION_CY, STATION_CZ, // look back at the ship as we back out the door — no void, no whip
                landPristine = false, frames = STATION_DOCK_TRANSIT_FRAMES,
                pullout = STATION_ENTER_PULLOUT, rise = 0.0,
                then = {
                    flyCamTo(
                        0.0, 0.0, homeZ,
                        STATION_CX, STATION_CY, STATION_CZ, // keep watching the hangar recede on the way down
                        landPristine = true, frames = STASH_CAM_FRAMES * STATION_RETURN_SPEED,
                        pullout = 0.0, rise = 0.0, // straight descent; the shrinking ship is the shot, not an arc into void
                        endLook = Triple(0.0, 0.0, 0.0), // swing down to the ring only at the end, for the landing
                    )
                },
            )
        } else {
            // Open-sky shelf (no hangar): the classic single arc home, gazing at the
            // COMMAND CENTER sign through the descent and easing to the ring to settle.
            // Camera-only (no pane rides home from a bare shelf view), so it flies at the
            // brisk stash-view cadence rather than the slow pane-lockstep one.
            flyCamTo(
                0.0, 0.0, homeZ,
                0.0, BEACON_Y + BEACON_LABEL_RISE, homeZ,
                landPristine = true, frames = STASH_VIEW_CAM_FRAMES,
                pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
                endLook = Triple(0.0, 0.0, 0.0),
            )
        }
        return
    }
    flyCamTo(0.0, 0.0, homeZ, 0.0, 0.0, 0.0, landPristine = true)
}

/**
 * The **tilt view** camera move (`j`) — no longer a mode, just a nudge: a quick
 * [flyCamTo] that steps the camera a modest distance sideways and up **from wherever it
 * currently sits** ([TILT_SIDE] / [TILT_UP] fractions of the perspective distance),
 * still looking straight at the target pane, so the pane reads at a gentle three-quarter
 * angle instead of the flat 1:1 view (typing into an angled terminal is the whole
 * point). Available in **both** command center and free flight; the target is the
 * command center's selected pane in navigate, the nearest pane in free flight
 * ([actionTargetPane]). Each press tilts again from the new pose — to come back level,
 * press `c` (fly home) or `F`. No-op while a journey is in flight or with no pane to
 * frame.
 *
 * @see actionTargetPane @see TILT_SIDE @see buildKeyHandler
 */
internal fun tiltCamera() {
    if (stashBusy()) return
    val p = actionTargetPane() ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val d = perspDistance(window.innerHeight)
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    val cy = if (spikeCamFlown) spikeCamY else 0.0
    val cz = if (spikeCamFlown) spikeCamZ else RING_R + d
    // The pane→camera direction and its horizontal perpendicular: the step goes
    // sideways along the latter (deterministic side) so the tilt never moves the
    // camera toward or away from the pane, only around it.
    var vx = cx - px
    var vz = cz - pz
    val vl = sqrt(vx * vx + vz * vz).coerceAtLeast(1e-6)
    vx /= vl; vz /= vl
    flyCamTo(
        cx + vz * d * TILT_SIDE, cy + d * TILT_UP, cz - vx * d * TILT_SIDE,
        px, py, pz,
        landPristine = false, frames = TILT_FRAMES, pullout = 0.0, rise = 0.0,
    )
}

/**
 * The **overview** camera move (`m`) — flies the camera out to a fixed cinematic "hero
 * shot" of the *whole* command-center world and parks it there, gazing down at the
 * world's centre so every tab and window frames as one nice picture. Unlike the pane
 * fly-bys this frames the scene, not a pane: the target pose is derived purely from the
 * world's geometry ([RING_R] + the perspective distance) and a fixed three-quarter
 * direction ([OVERVIEW_DIR_SIDE]/[OVERVIEW_DIR_UP]/[OVERVIEW_DIR_FRONT]), so it lands the
 * same composed shot no matter where you trigger it — command center or free flight.
 *
 * Reached from both handler branches in [buildKeyHandler]. A no-op while another journey
 * is in flight so a mid-tour press can't fight the tracer. Lands parked at the flown pose
 * ([flyCamTo] with `landPristine = false`); press `c` ([resetCamera]) to fly home again.
 *
 * @see flyCamTo @see OVERVIEW_DIST @see tiltCamera @see buildKeyHandler
 */
internal fun flyOverview() {
    if (spikeCamReturning) return
    val d = perspDistance(window.innerHeight)
    val dist = (RING_R + d) * OVERVIEW_DIST
    // Normalize the fixed look direction, then park `dist` out along it from the origin,
    // gazing back at the origin — the centre the whole pane sphere is built around.
    val len = sqrt(
        OVERVIEW_DIR_SIDE * OVERVIEW_DIR_SIDE +
            OVERVIEW_DIR_UP * OVERVIEW_DIR_UP +
            OVERVIEW_DIR_FRONT * OVERVIEW_DIR_FRONT,
    ).coerceAtLeast(1e-6)
    flyCamTo(
        OVERVIEW_DIR_SIDE / len * dist,
        OVERVIEW_DIR_UP / len * dist,
        OVERVIEW_DIR_FRONT / len * dist,
        0.0, 0.0, 0.0,
        landPristine = false, frames = OVERVIEW_FRAMES,
        pullout = OVERVIEW_PULLOUT, rise = OVERVIEW_RISE,
        roll = OVERVIEW_ROLL, landRoll = OVERVIEW_LAND_ROLL,
    )
}

/**
 * The live (non-dying) pane **nearest the camera's world position** — the subject of
 * the fly-mode pane fly-bys (`B`/`N`), so "the pane" is always the one you flew up to,
 * whether it hangs on the ring or rests on the stash shelf. Measured against the
 * pane's *live* [RingPane.obj] position (bob, stash flight and all).
 *
 * @return the nearest pane, or `null` if the world holds none.
 * @see flyBehindPane @see flyBesidePane
 */
internal fun nearestPaneToCamera(): RingPane? {
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    val cy = if (spikeCamFlown) spikeCamY else 0.0
    val cz = if (spikeCamFlown) spikeCamZ else RING_R + perspDistance(window.innerHeight)
    return spikePanes.filter { !it.dying && it.bundleId == null }.minByOrNull { p ->
        val dx = (p.obj.position.x as Double) - cx
        val dy = (p.obj.position.y as Double) - cy
        val dz = (p.obj.position.z as Double) - cz
        dx * dx + dy * dy + dz * dz
    }
}

/**
 * The live pane **at the centre of the screen** — the free-flight target for every
 * pane action (focus, glides, grid, reformat, zoom, stash). A ray is cast from the
 * camera along its nose (the screen-centre direction) and tested against each pane's
 * oriented quad; the pane the ray actually **pierces** wins, and if several overlap the
 * centre the **nearest hit** (smallest ray distance) is chosen — so "aim at it to act on
 * it", and a near pane in front of a far one takes priority. When the ray misses every
 * pane it falls back to the pane whose centre sits **closest to the centre of view** (the
 * smallest angle off the nose, front hemisphere only), and finally to the nearest pane by
 * distance so an action never silently no-ops when panes are around.
 *
 * @return the centred pane, or `null` if the world holds none.
 * @see actionTargetPane @see nearestPaneToCamera @see paneFacingNormal
 */
internal fun paneAtScreenCenter(): RingPane? {
    val ox = if (spikeCamFlown) spikeCamX else 0.0
    val oy = if (spikeCamFlown) spikeCamY else 0.0
    val oz = if (spikeCamFlown) spikeCamZ else RING_R + perspDistance(window.innerHeight)
    val fx = if (spikeCamFlown) spikeCamFx else 0.0
    val fy = if (spikeCamFlown) spikeCamFy else 0.0
    val fz = if (spikeCamFlown) spikeCamFz else -1.0

    var bestHit: RingPane? = null
    var bestT = Double.MAX_VALUE // nearest ray-quad intersection
    var bestCentred: RingPane? = null
    var bestCos = -1.0 // most-centred pane in the front hemisphere (ray-miss fallback)

    for (p in spikePanes) {
        // Skip a pane whose whole tab is unlisted into a hangar stack — it is not a live
        // ring pane you can aim at, even while it flies to / rests on the dock. @see stashTab
        if (p.dying || p.bundleId != null) continue
        val px = p.obj.position.x as Double
        val py = p.obj.position.y as Double
        val pz = p.obj.position.z as Double
        val dx = px - ox; val dy = py - oy; val dz = pz - oz
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        if (dl < 1e-6) return p // camera sits on the pane

        // Most-centred fallback: cosine of the angle between the nose and the pane centre.
        val cosAng = (dx * fx + dy * fy + dz * fz) / dl
        if (cosAng > bestCos) { bestCos = cosAng; bestCentred = p }

        // Exact ray → oriented-quad intersection. Plane: point = pane centre, normal N.
        val (nx, ny, nz) = paneFacingNormal(p)
        val denom = fx * nx + fy * ny + fz * nz
        if (abs(denom) < 1e-6) continue // ray parallel to the plane
        val t = (dx * nx + dy * ny + dz * nz) / denom // ((centre − O)·N) / (F·N)
        if (t <= 0.0) continue // intersection behind the camera
        // Hit point relative to the pane centre.
        val hx = ox + fx * t - px
        val hy = oy + fy * t - py
        val hz = oz + fz * t - pz
        // Pane up axis: Rx(rx)·(0,1,0) = (0, cos rx, sin rx). Right = up × normal.
        val rxA = p.obj.rotation.x as Double
        val ux = 0.0; val uy = cos(rxA); val uz = sin(rxA)
        var rrx = uy * nz - uz * ny
        var rry = uz * nx - ux * nz
        var rrz = ux * ny - uy * nx
        val rl = sqrt(rrx * rrx + rry * rry + rrz * rrz).coerceAtLeast(1e-6)
        rrx /= rl; rry /= rl; rrz /= rl
        val along = hx * rrx + hy * rry + hz * rrz // horizontal offset within the quad
        val up = hx * ux + hy * uy + hz * uz // vertical offset within the quad
        val sclX = (p.obj.scale.x as? Double) ?: 1.0
        val sclY = (p.obj.scale.y as? Double) ?: 1.0
        val halfW = p.baseCw * 0.5 * sclX
        val halfH = (p.baseCh + TITLE_H) * 0.5 * sclY
        if (abs(along) <= halfW && abs(up) <= halfH && t < bestT) {
            bestT = t; bestHit = p
        }
    }
    // A true hit wins; else the most-centred front pane; else nearest by distance.
    return bestHit ?: bestCentred?.takeIf { bestCos > 0.0 } ?: nearestPaneToCamera()
}

/**
 * The pane's **facing normal** in world space — the unit vector its front face looks
 * along, derived from the live CSS3D Euler rotation the render loop set (`z` is always
 * 0 there): rotating (0,0,1) by `Rx(rx)·Ry(ry)` gives
 * `(sin ry, −sin rx·cos ry, cos rx·cos ry)`. On the ring this points radially outward
 * (a front pane faces +Z); on the stash shelf it is exactly (0,0,1).
 *
 * @param p the pane to read.
 * @return the unit facing normal `(x, y, z)`.
 * @see flyBehindPane @see flyBesidePane
 */
internal fun paneFacingNormal(p: RingPane): Triple<Double, Double, Double> {
    val rx = p.obj.rotation.x as Double
    val ry = p.obj.rotation.y as Double
    return Triple(sin(ry), -sin(rx) * cos(ry), cos(rx) * cos(ry))
}

/**
 * Fly-mode **`B` — glide behind the nearest pane**: a slow cinematic [flyCamTo] that
 * swings the camera around the pane's nearer flank (sway signed toward the side you
 * already occupy, so it arcs around rather than punching through the plane) and parks
 * [PANE_BEHIND_DIST] behind its back, looking at it — the through-the-looking-glass
 * view (CSS3D backfaces are visible, so you see its mirrored content). Stays in fly
 * mode; any movement key mid-flight cancels the tour and returns control. A no-op
 * while another journey is in flight or with no panes in the world.
 *
 * @see flyBesidePane @see nearestPaneToCamera @see buildKeyHandler
 */
internal fun flyBehindPane() {
    if (spikeCamReturning) return
    val p = actionTargetPane() ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val (nx, ny, nz) = paneFacingNormal(p)
    val side = paneFlankSign(px, pz, nx, nz)
    flyCamTo(
        px - nx * PANE_BEHIND_DIST, py - ny * PANE_BEHIND_DIST, pz - nz * PANE_BEHIND_DIST,
        px, py, pz,
        landPristine = false, frames = PANE_TOUR_FRAMES,
        pullout = PANE_TOUR_PULLOUT, rise = PANE_TOUR_RISE,
        sway = side * PANE_TOUR_SWAY, roll = side * PANE_TOUR_ROLL,
    )
}

/**
 * Fly-mode **`H` — glide to the front of the nearest pane**: the mirror of [flyBehindPane],
 * a slow cinematic [flyCamTo] that arcs around the pane's nearer flank and parks
 * [PANE_FRONT_DIST] out in front of its face, looking straight at it — the head-on reading
 * view. Stays in fly mode; any movement key mid-flight cancels the tour and returns control.
 * A no-op while another journey is in flight or with no panes in the world.
 *
 * @see flyBehindPane @see nearestPaneToCamera @see buildKeyHandler
 */
internal fun flyFrontPane() {
    if (spikeCamReturning) return
    val p = actionTargetPane() ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val (nx, ny, nz) = paneFacingNormal(p)
    val side = paneFlankSign(px, pz, nx, nz)
    flyCamTo(
        px + nx * PANE_FRONT_DIST, py + ny * PANE_FRONT_DIST, pz + nz * PANE_FRONT_DIST,
        px, py, pz,
        landPristine = false, frames = PANE_TOUR_FRAMES,
        pullout = PANE_TOUR_PULLOUT, rise = PANE_TOUR_RISE,
        sway = side * PANE_TOUR_SWAY, roll = side * PANE_TOUR_ROLL,
    )
}

/**
 * Fly-mode **`N` — glide to the nearest pane's flank**: a slow cinematic [flyCamTo]
 * that parks the camera at a **three-quarter view** — [PANE_SIDE_ANGLE] off the pane's
 * facing normal toward whichever flank the camera is already nearest, [PANE_SIDE_DIST]
 * away and lifted [PANE_SIDE_LIFT] of that above the pane — looking at the pane, so it
 * reads at a raking angle like a canvas on an easel. Stays in fly mode; any movement
 * key mid-flight cancels the tour and returns control. A no-op while another journey
 * is in flight or with no panes in the world.
 *
 * @see flyBehindPane @see nearestPaneToCamera @see buildKeyHandler
 */
internal fun flyBesidePane() {
    if (spikeCamReturning) return
    val p = actionTargetPane() ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val (nx, ny, nz) = paneFacingNormal(p)
    val side = paneFlankSign(px, pz, nx, nz)
    // The pane's horizontal right vector: up × normal, i.e. (nz, 0, −nx) normalized.
    val rl = sqrt(nx * nx + nz * nz)
    val rx = if (rl > 1e-6) nz / rl else 1.0
    val rz = if (rl > 1e-6) -nx / rl else 0.0
    // Three-quarter pose: rotate the offset PANE_SIDE_ANGLE off the normal toward the
    // chosen flank, then lift it so the camera also looks slightly down at the pane.
    val ca = cos(PANE_SIDE_ANGLE)
    val sa = sin(PANE_SIDE_ANGLE) * side
    flyCamTo(
        px + (nx * ca + rx * sa) * PANE_SIDE_DIST,
        py + ny * ca * PANE_SIDE_DIST + PANE_SIDE_DIST * PANE_SIDE_LIFT,
        pz + (nz * ca + rz * sa) * PANE_SIDE_DIST,
        px, py, pz,
        landPristine = false, frames = PANE_TOUR_FRAMES,
        pullout = PANE_TOUR_PULLOUT, rise = PANE_TOUR_RISE,
        sway = side * PANE_TOUR_SWAY, roll = side * PANE_TOUR_ROLL,
    )
}

/**
 * Fly-mode **`O` — glide above the nearest pane** (see [flyVerticalPane]). @see flyBelowPane
 */
internal fun flyAbovePane() = flyVerticalPane(1.0)

/**
 * Fly-mode **`U` — glide below the nearest pane** (see [flyVerticalPane]). @see flyAbovePane
 */
internal fun flyBelowPane() = flyVerticalPane(-1.0)

/**
 * The shared **over/under fly-by**: a slow cinematic [flyCamTo] that perches the camera
 * [PANE_VERT_DIST] along the pane's own up (or down) axis, leaned [PANE_VERT_ANGLE]
 * toward its front, looking back at the pane. The lean is essential: a pane is a flat
 * plane, so from *exactly* above it is edge-on and invisible — the perch keeps enough
 * of the face in view to read as "hovering visibly over/under it". Swings around the
 * flank the camera already occupies (sway/roll signed) like the other fly-bys, stays
 * in fly mode, and any movement key mid-flight cancels the tour. A no-op while another
 * journey is in flight or with no panes in the world.
 *
 * @param vert `+1.0` to perch above the pane, `−1.0` below.
 * @see flyBehindPane @see flyBesidePane @see buildKeyHandler
 */
private fun flyVerticalPane(vert: Double) {
    if (spikeCamReturning) return
    val p = actionTargetPane() ?: return
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double
    val (nx, ny, nz) = paneFacingNormal(p)
    // The pane's own up vector: Rx(rx)·Ry(ry)·(0,1,0) = (0, cos rx, sin rx) — Ry leaves
    // (0,1,0) untouched, so only the latitude tilt matters. Orthogonal to the normal.
    val rxAngle = p.obj.rotation.x as Double
    val ux = 0.0
    val uy = cos(rxAngle)
    val uz = sin(rxAngle)
    val side = paneFlankSign(px, pz, nx, nz)
    val cv = cos(PANE_VERT_ANGLE) * vert
    val sv = sin(PANE_VERT_ANGLE)
    flyCamTo(
        px + (ux * cv + nx * sv) * PANE_VERT_DIST,
        py + (uy * cv + ny * sv) * PANE_VERT_DIST,
        pz + (uz * cv + nz * sv) * PANE_VERT_DIST,
        px, py, pz,
        landPristine = false, frames = PANE_TOUR_FRAMES,
        pullout = PANE_TOUR_PULLOUT, rise = PANE_TOUR_RISE,
        sway = side * PANE_TOUR_SWAY, roll = side * PANE_TOUR_ROLL,
    )
}

/**
 * Which of the pane's two horizontal flanks the camera currently occupies: `+1.0` for
 * the pane's right (along up × normal), `−1.0` for its left. Both fly-bys route their
 * swing (sway/roll sign, and the `N` pose's flank) through the side you're already on,
 * so the tour arcs the *short* way around instead of crossing the pane's face.
 *
 * @param px/pz the pane's world x/z. @param nx/nz the pane's facing normal x/z.
 * @return `+1.0` or `−1.0`. @see flyBehindPane @see flyBesidePane
 */
private fun paneFlankSign(px: Double, pz: Double, nx: Double, nz: Double): Double {
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    val cz = if (spikeCamFlown) spikeCamZ else RING_R + perspDistance(window.innerHeight)
    // dot(cam − pane, right) with right = (nz, 0, −nx); only the sign matters.
    val d = (cx - px) * nz - (cz - pz) * nx
    return if (d >= 0.0) 1.0 else -1.0
}

/** Zeroes all inertial flight velocity so the camera stops gliding. */
internal fun clearFlyVelocity() {
    spikeCamVX = 0.0; spikeCamVY = 0.0; spikeCamVZ = 0.0
    spikeCamPitchVel = 0.0; spikeCamYawVel = 0.0; spikeCamRollVel = 0.0
}

/** Resets the orientation basis to the default pose: nose down −Z, roof up +Y. */
internal fun resetFlyBasis() {
    spikeCamFx = 0.0; spikeCamFy = 0.0; spikeCamFz = -1.0
    spikeCamUx = 0.0; spikeCamUy = 1.0; spikeCamUz = 0.0
}

/**
 * Advances the inertial spaceship flight model one frame.
 *
 * Steering keys apply *angular thrust* about the ship's own local axes — ↑/↓
 * pitch (nose up/down about the right vector), ←/→ yaw (about up), `Q`/`E` roll
 * (about the nose) — which builds up angular velocity; that velocity then spins the
 * orientation basis ([spikeCamFx]…/[spikeCamUx]…) in place. Because the rotations are
 * applied to the live basis rather than accumulated Euler angles, there is no gimbal
 * lock: you can loop, barrel-roll, and point anywhere.
 *
 * The throttle keys `W`/`S` fire the main engine forward/reverse along the *current*
 * nose direction — `W` is the gas pedal, `S` reverses — and `A`/`D` fire lateral
 * thrusters along the ship's right vector
 * (forward × up) to strafe left/right from wherever it currently sits — both relative
 * to the live orientation, so "sideways" is always the ship's own sideways. Drag then
 * decays every velocity component so releasing the keys coasts the camera to a smooth
 * stop rather than snapping.
 *
 * Runs every frame while flying — even with no keys held — so residual momentum
 * keeps gliding. Any thrust marks the camera "flown" so the absolute pose takes over.
 *
 * @see FLY_ACCEL @see FLY_DAMPING @see spikeCamFx
 */
internal fun applyFlyStep() {
    fun held(c: String) = spikeFlyKeys.contains(c)

    // Angular thrust from the steering keys (radians/frame², about local axes).
    if (held("ArrowUp")) { spikeCamPitchVel += FLY_ROT_ACCEL; spikeCamFlown = true }
    if (held("ArrowDown")) { spikeCamPitchVel -= FLY_ROT_ACCEL; spikeCamFlown = true }
    if (held("ArrowLeft")) { spikeCamYawVel += FLY_ROT_ACCEL; spikeCamFlown = true }
    if (held("ArrowRight")) { spikeCamYawVel -= FLY_ROT_ACCEL; spikeCamFlown = true }
    if (held("KeyQ")) { spikeCamRollVel += FLY_ROT_ACCEL; spikeCamFlown = true }
    if (held("KeyE")) { spikeCamRollVel -= FLY_ROT_ACCEL; spikeCamFlown = true }

    // Spin the orientation basis by the angular velocities, about its local axes.
    rotateFlyBasis(spikeCamPitchVel, spikeCamYawVel, spikeCamRollVel)

    // Main-engine throttle: thrust only ever along the freshly rotated nose —
    // W is the gas pedal (accelerate where you point), S fires the retro/reverse thruster.
    if (held("KeyW")) { spikeCamVX += spikeCamFx * FLY_ACCEL; spikeCamVY += spikeCamFy * FLY_ACCEL; spikeCamVZ += spikeCamFz * FLY_ACCEL; spikeCamFlown = true }
    if (held("KeyS")) { spikeCamVX -= spikeCamFx * FLY_ACCEL; spikeCamVY -= spikeCamFy * FLY_ACCEL; spikeCamVZ -= spikeCamFz * FLY_ACCEL; spikeCamFlown = true }

    // Lateral thrusters (A/D): strafe along the ship's right vector (forward × up),
    // derived from the freshly rotated basis so "sideways" tracks the live orientation.
    if (held("KeyA") || held("KeyD")) {
        val rx = spikeCamFy * spikeCamUz - spikeCamFz * spikeCamUy
        val ry = spikeCamFz * spikeCamUx - spikeCamFx * spikeCamUz
        val rz = spikeCamFx * spikeCamUy - spikeCamFy * spikeCamUx
        if (held("KeyA")) { spikeCamVX -= rx * FLY_ACCEL; spikeCamVY -= ry * FLY_ACCEL; spikeCamVZ -= rz * FLY_ACCEL; spikeCamFlown = true }
        if (held("KeyD")) { spikeCamVX += rx * FLY_ACCEL; spikeCamVY += ry * FLY_ACCEL; spikeCamVZ += rz * FLY_ACCEL; spikeCamFlown = true }
    }

    // Vertical thruster (Shift): strafe *down* along the ship's own roof vector. (Space
    // used to strafe up but is now the stash/unstash-nearest key, so up-thrust is done by
    // pitching the nose up and throttling; Shift keeps the quick descend.)
    if (held("ShiftLeft") || held("ShiftRight")) { spikeCamVX -= spikeCamUx * FLY_ACCEL; spikeCamVY -= spikeCamUy * FLY_ACCEL; spikeCamVZ -= spikeCamUz * FLY_ACCEL; spikeCamFlown = true }

    // Integrate linear velocity → position.
    spikeCamX += spikeCamVX; spikeCamY += spikeCamVY; spikeCamZ += spikeCamVZ

    // Drag: decay velocities so motion glides to a stop; snap tiny residuals to 0.
    spikeCamVX *= FLY_DAMPING; spikeCamVY *= FLY_DAMPING; spikeCamVZ *= FLY_DAMPING
    spikeCamPitchVel *= FLY_ROT_DAMPING; spikeCamYawVel *= FLY_ROT_DAMPING; spikeCamRollVel *= FLY_ROT_DAMPING
    if (abs(spikeCamVX) < FLY_STOP_EPS) spikeCamVX = 0.0
    if (abs(spikeCamVY) < FLY_STOP_EPS) spikeCamVY = 0.0
    if (abs(spikeCamVZ) < FLY_STOP_EPS) spikeCamVZ = 0.0
}

/**
 * Rotates the ship's orientation basis ([spikeCamFx]…forward, [spikeCamUx]…up) in
 * place by small per-frame angles about its own **local** axes, then re-orthonormalizes
 * to shed accumulated floating-point drift. Called once per fly frame by [applyFlyStep].
 *
 * Each rotation acts in the plane of the two basis vectors it touches (the third —
 * the axis — is unchanged), which is exact for orthonormal vectors and free of gimbal
 * lock. The right vector is recomputed as forward × up as needed rather than stored.
 *
 * @param pitch radians to pitch the nose up (+) / down (−), about the local right axis.
 * @param yaw radians to yaw the nose left (+) / right (−), about the local up axis.
 * @param roll radians to roll left (+) / right (−), about the local forward axis.
 * @see applyFlyStep
 */
internal fun rotateFlyBasis(pitch: Double, yaw: Double, roll: Double) {
    var fx = spikeCamFx; var fy = spikeCamFy; var fz = spikeCamFz
    var ux = spikeCamUx; var uy = spikeCamUy; var uz = spikeCamUz

    // Pitch: rotate forward toward up (about the right axis, which is left untouched).
    if (pitch != 0.0) {
        val c = cos(pitch); val s = sin(pitch)
        val nfx = fx * c + ux * s; val nfy = fy * c + uy * s; val nfz = fz * c + uz * s
        ux = -fx * s + ux * c; uy = -fy * s + uy * c; uz = -fz * s + uz * c
        fx = nfx; fy = nfy; fz = nfz
    }
    // Yaw: rotate forward toward −right (turn left) about up (untouched).
    if (yaw != 0.0) {
        val rx = fy * uz - fz * uy; val ry = fz * ux - fx * uz; val rz = fx * uy - fy * ux
        val c = cos(yaw); val s = sin(yaw)
        fx = fx * c - rx * s; fy = fy * c - ry * s; fz = fz * c - rz * s
        // right is derived, no need to keep the rotated copy.
    }
    // Roll: rotate up toward −right (bank left) about forward (untouched).
    if (roll != 0.0) {
        val rx = fy * uz - fz * uy; val ry = fz * ux - fx * uz; val rz = fx * uy - fy * ux
        val c = cos(roll); val s = sin(roll)
        ux = ux * c - rx * s; uy = uy * c - ry * s; uz = uz * c - rz * s
    }

    // Re-orthonormalize (Gram–Schmidt): normalize forward, make up ⟂ forward, normalize.
    val fl = sqrt(fx * fx + fy * fy + fz * fz)
    if (fl > 1e-9) { fx /= fl; fy /= fl; fz /= fl }
    val d = ux * fx + uy * fy + uz * fz
    ux -= d * fx; uy -= d * fy; uz -= d * fz
    val ul = sqrt(ux * ux + uy * uy + uz * uz)
    if (ul > 1e-9) { ux /= ul; uy /= ul; uz /= ul }

    spikeCamFx = fx; spikeCamFy = fy; spikeCamFz = fz
    spikeCamUx = ux; spikeCamUy = uy; spikeCamUz = uz
}

/**
 * Resets the settled front pane's visual zoom to 1× (`0`), locally and on the
 * server ([WindowCommand.SetPaneZoom]) so the reset persists like a zoom does.
 * Glides at the slow [ZOOM_PRESET_EASE] like the ⇧ presets ([spikeZoomGlide]) —
 * the trip back from a preset extreme is just as long as the trip out.
 */
internal fun resetFrontZoom() {
    val fi = frontIndex()
    if (spikeSettledIndex != fi || fi < 0) return
    if (spikeSelectionMode) exitSelectionMode()
    spikeZoomTarget = 1.0
    // Coming back from a preset extreme is as long a jump as going there — glide it too.
    spikeZoomGlide = true
    spikePanes.getOrNull(fi)?.let {
        spikeZoomByPane[it.paneId] = 1.0
        runCatching { launchCmd(WindowCommand.SetPaneZoom(it.paneId, 1.0)) }
    }
}

/**
 * Re-reads the persisted 3D-world settings into the live runtime flags — window bobbing
 * ([spikeBobEnabled]) and status indication ([spikeStatusIndication]). Called once at
 * [openWorld3dSpike] to seed them, and again from the in-world settings panel (⌥⌘,) on
 * every change so an edit takes effect on the running world immediately (the render loop
 * reads both flags each frame). Bob is also cleared of any lingering reactor visuals when
 * the mode leaves REACTOR, so a pane can't freeze mid-glow.
 * @see isWindowBobbingEnabled @see world3dStatusIndication @see resetWarpCoreVisuals
 */
internal fun syncWorld3dRuntimeFromSettings() {
    spikeBobEnabled = isWindowBobbingEnabled()
    val prev = spikeStatusIndication
    spikeStatusIndication = world3dStatusIndication()
    // Leaving the reactor mode: clear its lingering per-pane visuals so nothing freezes.
    if (prev == StatusIndication.REACTOR && spikeStatusIndication != StatusIndication.REACTOR) {
        resetWarpCoreVisuals()
    }
}

/**
 * Hides/shows the shortcut legends (`k`, in navigate *and* fly mode).
 * Flips the shared [spikeLegendHidden] flag — one preference for both
 * panels — and lets [updateLegendVisibility] apply it to whichever legend
 * the current mode shows.
 */
internal fun toggleLegend() {
    spikeLegendHidden = !spikeLegendHidden
    updateLegendVisibility()
}

/**
 * **Cycles** the manual signal-state override on the settled front pane (the `w` key)
 * through the three states in order: **normal → working → needs-input → normal**. One
 * key walks all three so you can preview each look — the blue working dots, then the red
 * "needs input" halo, then off — without a live agent driving them. Works by writing both
 * [spikeWorkingOverride] and [spikeWaitingOverride] in lock-step; the render loop reads an
 * override in preference to the pane's real session state. Seeds the cycle from whatever
 * the pane shows *now* (override, else live state) so the first press advances from the
 * current look. No-op unless a front pane is settled.
 */
internal fun cycleSignalOverride() {
    val fi = frontIndex()
    if (spikeSettledIndex != fi || fi < 0) return
    val p = spikePanes.getOrNull(fi) ?: return
    val states = runCatching { lunamuxClient.windowState.states.value }.getOrNull()
    // Effective current look: an explicit override wins, else the live session state.
    val working = spikeWorkingOverride[p.paneId] ?: (states?.get(p.sessionId) == "working")
    val waiting = spikeWaitingOverride[p.paneId] ?: (states?.get(p.sessionId) == "waiting")
    // Advance one step around normal → working → needs-input → normal.
    val (nextWorking, nextWaiting) = when {
        !working && !waiting -> true to false   // normal → working
        working -> false to true                // working → needs-input
        else -> false to false                  // needs-input → normal
    }
    spikeWorkingOverride[p.paneId] = nextWorking
    spikeWaitingOverride[p.paneId] = nextWaiting
}

/**
 * Disengages (⌥⌘X / before any navigation): flips [spikeEngaged] off — after
 * which navigate-mode keys are intercepted regardless of DOM focus — and makes a
 * best-effort blur so the terminal cursor stops looking active.
 */
internal fun disengage() {
    // Only flex-off if we were actually latched on — a bare disengage (e.g. a
    // navigate while nothing is engaged) shouldn't play the signal. Flex the pane we
    // actually engaged (in free flight that is the nearest pane, not necessarily the
    // front one), falling back to the front pane.
    if (spikeEngaged) {
        (spikePanes.firstOrNull { it.paneId == spikeLastEngagedPane }
            ?: spikePanes.getOrNull(frontIndex()))
            ?.let { startFlex(it, FLEX_DIR_IN) }
    }
    spikeEngaged = false
    // Swap the legend back to whichever mode we return to (command center or free flight).
    // @see updateLegendVisibility
    updateLegendVisibility()
    (document.activeElement as? HTMLElement)?.let { el ->
        if (spikePanes.any { it.container.contains(el as Node) }) el.blur()
    }
    runCatching { spikeOverlay?.focus() }
}
