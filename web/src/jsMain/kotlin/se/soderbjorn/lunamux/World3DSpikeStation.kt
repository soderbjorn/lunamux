/**
 * The **stash station** — the giant enclosing spaceship / space station hull that wraps
 * the floating stash shelf, turning the bare patch of sky high above the pane sphere
 * ([STASH_SHELF_Y]) into a cavernous hangar bay. A huge **open bay door** is cut into the
 * hull's front (+Z) face; the shelf flights ([stashFront], [unstashNearest],
 * [toggleStashView]) become two-leg cinematics that fly *in through the door* to drop a
 * terminal off on a shelf and *out through the door* to pick one back up.
 *
 * Like the beacons, the scene is pure CSS3D — real DOM planes, no WebGL meshes — so each
 * wall is a flat styled `<div>` wrapped in a [CSS3DObject] and oriented in world space by
 * a fixed rotation. Crucially the walls are **not** billboarded: their transforms are set
 * once and never rewritten, so they don't suffer the per-frame re-rasterization flicker
 * that shelved [buildCosmos] (whose bodies chase the camera every frame). The hull is a
 * box centred on the shelf ([STATION_CX]/[STATION_CY]/[STATION_CZ], half-extents
 * [STATION_HW]/[STATION_HH]/[STATION_HD]) built from four solid walls (back, ceiling, floor,
 * two sides) plus a front wall framed around the **bay door** — the single opening both the
 * panes and the camera enter and leave through (a pane climbing from the ring routes up and
 * *in through the door*, see [stashPanePath], so it never clips the solid deck).
 *
 * Scene-graph shape:
 * ```
 * scene
 * └─ station Group — position (0, 0, 0); children carry absolute world positions
 *    ├─ CSS3DObject(back wall)      +Z-facing, at z = CZ − HD
 *    ├─ CSS3DObject(ceiling)        rot.x = +π/2, at y = CY + HH
 *    ├─ CSS3DObject(floor)          rot.x = −π/2, at y = CY − HH
 *    ├─ CSS3DObject(left wall)      rot.y = +π/2, at x = CX − HW
 *    ├─ CSS3DObject(right wall)     rot.y = −π/2, at x = CX + HW
 *    ├─ CSS3DObject(front jamb ×2, lintel, sill)  at z = CZ + HD (the bay-door frame)
 *    └─ CSS3DObject(door rim)       glowing accent outline around the opening
 * ```
 *
 * Gated on [SPIKE_STASH_STATION_ENABLED]: when off, [buildStashStation] is a no-op and the
 * shelf flights keep their original open-sky single-arc choreography.
 *
 * @see buildStashStation
 * @see clearStashStation
 * @see flyStationEnter
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.Scene

/**
 * Builds the stash station hull and adds it to the CSS3D scene. Called once per open from
 * [openWorld3dSpike], **before** the beacons, cosmos and ring panes so the hull is the
 * backmost DOM (its role is the enclosure everything else sits inside; real 3D depth
 * sorting keeps the far walls behind the shelf regardless, but backmost DOM order is the
 * right tie-breaker for coplanar cases).
 *
 * No-op when [SPIKE_STASH_STATION_ENABLED] is off.
 *
 * @param scene the CSS3D scene to add the hull into.
 * @param chrome the resolved theme colours; the door rim and interior trim glow in
 *   [SpikeChrome.accent] so the station keys to the active theme like the beacons.
 * @see clearStashStation
 * @see buildStationFront
 */
internal fun buildStashStation(scene: Scene, chrome: SpikeChrome) {
    if (!SPIKE_STASH_STATION_ENABLED) return

    val station = Group()
    station.position.set(0.0, 0.0, 0.0)

    /**
     * Wraps a styled hull [panel] in a [CSS3DObject] at (x, y, z) with the given Euler
     * rotation and registers it on the station group. Local +Z is the panel's front
     * normal; the rotation aims that normal into the interior. The object is scaled up by
     * [STATION_TEX_SCALE] — the panel was built at `world / STATION_TEX_SCALE` pixels — so
     * the DOM layer stays small (within tile/texture limits) while filling the world size.
     */
    fun place(panel: HTMLElement, x: Double, y: Double, z: Double, rx: Double, ry: Double) {
        val obj = CSS3DObject(panel)
        obj.position.set(x, y, z)
        obj.rotation.set(rx, ry, 0.0)
        obj.scale.set(STATION_TEX_SCALE, STATION_TEX_SCALE, STATION_TEX_SCALE)
        station.add(obj)
    }

    val w2 = STATION_HW * 2.0
    val h2 = STATION_HH * 2.0
    val d2 = STATION_HD * 2.0

    // Back wall (−Z): faces +Z into the bay, behind the shelf. No rotation.
    place(buildStationWall(w2, h2, chrome), STATION_CX, STATION_CY, STATION_CZ - STATION_HD, 0.0, 0.0)
    // Ceiling: lie the panel flat overhead, normal pointing down (−Y) into the bay.
    place(buildStationWall(w2, d2, chrome), STATION_CX, STATION_CY + STATION_HH, STATION_CZ, PI / 2.0, 0.0)
    // Floor: a solid deck (normal up, +Y, into the bay). The panes enter and leave through
    // the front bay door, not the floor, so no hatch is cut — the deck is unbroken.
    place(buildStationWall(w2, d2, chrome), STATION_CX, STATION_CY - STATION_HH, STATION_CZ, -PI / 2.0, 0.0)
    // Left wall (−X): stand it on edge, normal pointing +X into the bay.
    place(buildStationWall(d2, h2, chrome), STATION_CX - STATION_HW, STATION_CY, STATION_CZ, 0.0, PI / 2.0)
    // Right wall (+X): normal pointing −X into the bay.
    place(buildStationWall(d2, h2, chrome), STATION_CX + STATION_HW, STATION_CY, STATION_CZ, 0.0, -PI / 2.0)

    // Front (+Z) wall — the four hull pieces framing the open bay door.
    buildStationFront(chrome) { panel, x, y -> place(panel, x, y, STATION_CZ + STATION_HD, 0.0, 0.0) }
    // The glowing door rim, nudged just out of the wall plane so it doesn't z-fight the frame.
    place(buildStationDoorRim(chrome), STATION_DOOR_CX, STATION_DOOR_CY, STATION_CZ + STATION_HD + STATION_RIM_Z_LIFT, 0.0, 0.0)

    scene.add(station)
    spikeStashStation = station
}

/**
 * Builds the front (+Z) wall as four hull pieces framing the [STATION_DOOR_W]×
 * [STATION_DOOR_H] opening — a left jamb, a right jamb, a top lintel and a bottom sill —
 * then the glowing door rim, handing each finished panel to [place] to position on the
 * front wall plane. Splitting the wall into a frame (rather than one panel) is what leaves
 * the door a genuine hole the camera flies through rather than painting over it.
 *
 * @param chrome the theme colours (jambs use the hull palette; the rim uses the accent).
 * @param place callback positioning a finished panel at front-wall (x, y) — z is fixed by
 *   the caller ([buildStashStation]) to the front wall plane.
 * @see buildStationDoorRim
 */
private fun buildStationFront(chrome: SpikeChrome, place: (HTMLElement, Double, Double) -> Unit) {
    val wallL = STATION_CX - STATION_HW
    val wallR = STATION_CX + STATION_HW
    val wallB = STATION_CY - STATION_HH
    val wallT = STATION_CY + STATION_HH
    val doorL = STATION_DOOR_CX - STATION_DOOR_W / 2.0
    val doorR = STATION_DOOR_CX + STATION_DOOR_W / 2.0
    val doorB = STATION_DOOR_CY - STATION_DOOR_H / 2.0
    val doorT = STATION_DOOR_CY + STATION_DOOR_H / 2.0

    // Left jamb: full-height slab from the wall's left edge to the door's left edge.
    val leftW = doorL - wallL
    if (leftW > 1.0) {
        place(buildStationWall(leftW, wallT - wallB, chrome), (wallL + doorL) / 2.0, STATION_CY)
    }
    // Right jamb: door's right edge to the wall's right edge.
    val rightW = wallR - doorR
    if (rightW > 1.0) {
        place(buildStationWall(rightW, wallT - wallB, chrome), (doorR + wallR) / 2.0, STATION_CY)
    }
    // Top lintel: spans the door width, from the door's top up to the ceiling line.
    val lintelH = wallT - doorT
    if (lintelH > 1.0) {
        place(buildStationWall(STATION_DOOR_W, lintelH, chrome), STATION_DOOR_CX, (doorT + wallT) / 2.0)
    }
    // Bottom sill: door's bottom down to the floor line.
    val sillH = doorB - wallB
    if (sillH > 1.0) {
        place(buildStationWall(STATION_DOOR_W, sillH, chrome), STATION_DOOR_CX, (wallB + doorB) / 2.0)
    }
}


/**
 * Builds one **hull panel** as a DOM element: a dark metallic slab with faint riveted
 * panel seams (two cheap repeating-linear-gradients — no blur, so nothing re-rasterizes)
 * over a diagonal metal gradient, an inset shadow for depth, and a subtle accent sheen
 * bleeding in from the edges so the walls key to the theme without shouting.
 *
 * The element is built at `world / STATION_TEX_SCALE` **pixels** (the caller scales the
 * [CSS3DObject] back up by that factor) so even the ceiling — huge in world units — is a
 * small, cheap DOM layer that stays within the browser's tile/texture cap and doesn't
 * flicker or drop out. Style lengths are authored in this small build-pixel space, so the
 * seam pitch, shadow blur and border render [STATION_TEX_SCALE]× larger on screen.
 *
 * @param worldW panel width in world units. @param worldH panel height in world units.
 * @param chrome the theme colours; [SpikeChrome.accent] tints the edge sheen.
 * @return the panel `<div>`, sized in reduced build pixels.
 * @see buildStationWall @see STATION_TEX_SCALE
 */
private fun buildStationWall(worldW: Double, worldH: Double, chrome: SpikeChrome): HTMLElement {
    val wPx = (worldW / STATION_TEX_SCALE).roundToInt().coerceAtLeast(1)
    val hPx = (worldH / STATION_TEX_SCALE).roundToInt().coerceAtLeast(1)
    val el = document.createElement("div") as HTMLElement
    el.style.cssText =
        "width:${wPx}px;height:${hPx}px;pointer-events:none;" +
            // Panel seams: hairline light lines on a large pitch (in build px), both axes.
            "background:" +
            "repeating-linear-gradient(0deg,rgba(150,180,220,0.06) 0 1px,transparent 1px 22px)," +
            "repeating-linear-gradient(90deg,rgba(150,180,220,0.06) 0 1px,transparent 1px 30px)," +
            "linear-gradient(160deg,$STATION_HULL_LIGHT,$STATION_HULL_MID 55%,$STATION_HULL_DARK 100%);" +
            // Deep inset shadow (cavernous) + a faint accent sheen hugging the edges.
            "box-shadow:inset 0 0 24px rgba(0,0,0,0.75),inset 0 0 6px ${chrome.accent}1f;" +
            "border:1px solid rgba(120,150,190,0.12);"
    return el
}

/**
 * Builds the **door rim** — a bright accent-glowing rectangular outline around the bay
 * door opening (transparent centre, so it never occludes the view through the door), with
 * a doubled neon bloom and a slow pure-CSS pulse ([STATION_DOOR_PULSE_S]) matching the
 * beacons' breathing glow. This is the iconic hangar-mouth light frame the camera aims
 * for on approach.
 *
 * Built at `world / STATION_TEX_SCALE` pixels (the caller scales the [CSS3DObject] back up)
 * so the rim stays a small, cheap DOM layer — border, radius and glow are authored in that
 * build-pixel space and render [STATION_TEX_SCALE]× larger.
 *
 * @param chrome the theme colours ([SpikeChrome.accent] strokes and glows the rim).
 * @return the rim `<div>`, sized in reduced build pixels.
 * @see buildStationFront @see STATION_TEX_SCALE
 */
private fun buildStationDoorRim(chrome: SpikeChrome): HTMLElement {
    val wrapper = document.createElement("div") as HTMLElement
    // The pulse keyframe is document-scoped once attached; a uniquely-named rule so it
    // can't collide with the beacon pulses.
    val style = document.createElement("style")
    style.textContent =
        "@keyframes spike-station-door-pulse{0%,100%{opacity:0.55;}50%{opacity:1;}}"
    wrapper.appendChild(style)

    val wPx = (STATION_DOOR_W / STATION_TEX_SCALE).roundToInt().coerceAtLeast(1)
    val hPx = (STATION_DOOR_H / STATION_TEX_SCALE).roundToInt().coerceAtLeast(1)
    val rim = document.createElement("div") as HTMLElement
    rim.style.cssText =
        "width:${wPx}px;height:${hPx}px;" +
            "box-sizing:border-box;pointer-events:none;border-radius:5px;" +
            "border:1px solid ${chrome.accent};background:transparent;" +
            "box-shadow:0 0 4px ${chrome.accent},0 0 12px ${chrome.accent}," +
            "inset 0 0 6px ${chrome.accent}66;" +
            "animation:spike-station-door-pulse ${STATION_DOOR_PULSE_S}s ease-in-out infinite;"
    wrapper.appendChild(rim)
    return wrapper
}

/**
 * Clears the stash-station globals on close. Called from [closeWorld3dSpike] alongside
 * [clearHomeBeacon] / [clearCosmos]; no scene/DOM teardown is needed because the whole
 * CSS3D scene is discarded and the overlay removed wholesale there — the hull owns its
 * DOM outright, so nothing has to be reparented back.
 *
 * @see buildStashStation
 */
internal fun clearStashStation() {
    spikeStashStation = null
}

// ─────────────────────────────────────────────────────────────────────────────
// Fly-through-the-door cinematic
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Whether the fly-through-the-door cinematic is active — the feature is enabled *and* the
 * hull was actually built this open. The shelf flights branch on this to pick the two-leg
 * door journey over the classic single arc. @see flyStationEnter @see stashFront
 */
internal fun stationBuilt(): Boolean = SPIKE_STASH_STATION_ENABLED && spikeStashStation != null

/**
 * The **outside staging pose** — where an entering flight parks (and an exiting flight
 * aims for) just beyond the front door wall: dead ahead of the door centre,
 * [STATION_APPROACH] world-units out on +Z, looking back through the door toward the
 * shelf so the glowing door mouth frames the destination on the approach.
 *
 * @return the staging camera pose. @see flyStationEnter @see resetCamera
 */
internal fun stationStagingPose(): CamPose {
    val z = STATION_CZ + STATION_HD + STATION_APPROACH
    return CamPose(STATION_DOOR_CX, STATION_DOOR_CY, z, STATION_DOOR_CX, STATION_DOOR_CY, STATION_CZ)
}

/**
 * The **interior park pose** for shelf [slot] — the same sign-revealing framing as the
 * open-sky [shelfArrivalPose], but with its standoff capped to [STATION_INTERIOR_STANDOFF]
 * so the camera always lands *inside* the front door wall rather than poking back out
 * through it.
 *
 * @param slot the shelf slot to frame.
 * @param paneHalfH the shelved pane's world half-height ([paneShelfHalfH]); `0.0` for none.
 * @return the interior camera pose. @see flyStationEnter @see shelfBrowse
 */
internal fun stationInteriorPose(slot: Int, paneHalfH: Double): CamPose =
    shelfArrivalPose(slot, paneHalfH, maxStandoff = STATION_INTERIOR_STANDOFF)

/**
 * The **rest pose a stash chase settles on** for shelf [slot] — the exact pose
 * [tickStashChase] leaves the camera parked at when an outbound stash finishes (its
 * hump `w = 0` at journey's end): [STASH_CHASE_NEAR_DIST] in front of the shelved
 * pane's face along the level `(OFF_X, 0, OFF_Z)` chase direction, looking straight
 * at the pane's centre. Used by [toggleStashView] so flying up to the dock with `v`
 * lands at the **same close, centred framing** a stash does, instead of the higher
 * sign-reveal pose ([stationInteriorPose]) — the two ways of reaching the shelf now
 * settle in the same spot.
 *
 * @param slot the shelf slot to frame.
 * @return the camera pose the chase rests at. @see tickStashChase @see toggleStashView
 */
internal fun stationChaseRestPose(slot: Int): CamPose {
    val (sx, sy, sz) = stashShelfPos(slot)
    // Mirror tickStashChase's end state: hump w = 0 ⇒ offY = 0, dist = NEAR_DIST.
    val ol = sqrt(STASH_CHASE_OFF_X * STASH_CHASE_OFF_X + STASH_CHASE_OFF_Z * STASH_CHASE_OFF_Z)
    val cx = sx + STASH_CHASE_OFF_X / ol * STASH_CHASE_NEAR_DIST
    val cz = sz + STASH_CHASE_OFF_Z / ol * STASH_CHASE_NEAR_DIST
    return CamPose(cx, sy, cz, sx, sy, sz)
}

/**
 * The world position of a stashing / unstashing pane at journey progress [sp] (its
 * [RingPane.stashProg]: 0 at the ring slot, 1 at the shelf) — routed **through the bay
 * door** when the station is built, so the pane enters and leaves the hangar the same way
 * the camera does and never clips the solid floor. Off-station it is the plain
 * smootherstep lerp from ring to shelf the render loop used before.
 *
 * The door route is two legs, split at [STATION_PANE_LEG_A]: leg 1 climbs from the ring
 * slot up to a waypoint just *outside* the bay door ([STATION_PANE_DOOR_OUT] beyond the
 * front wall, at door centre and door height) — outside the front of the hull, so the climb
 * clears the solid deck — and leg 2 flies in through the doorway to the shelf slot. Each leg
 * is smootherstep-eased so the corner at the door is soft.
 *
 * @param rx/ry/rz the pane's ring-slot world position (leg-1 start).
 * @param sx/sy/sz the pane's shelf-slot world position (leg-2 end).
 * @param sp the pane's [RingPane.stashProg].
 * @return the pane's world position at that progress.
 * @see stashFront @see tickStashChase
 */
internal fun stashPanePath(
    rx: Double, ry: Double, rz: Double,
    sx: Double, sy: Double, sz: Double,
    sp: Double,
): Triple<Double, Double, Double> {
    if (!stationBuilt()) {
        val e = sp * sp * sp * (sp * (sp * 6.0 - 15.0) + 10.0)
        return Triple(rx + (sx - rx) * e, ry + (sy - ry) * e, rz + (sz - rz) * e)
    }
    val wx = STATION_DOOR_CX
    val wy = STATION_DOOR_CY
    val wz = STATION_CZ + STATION_HD + STATION_PANE_DOOR_OUT
    val f = STATION_PANE_LEG_A
    return if (sp <= f) {
        // Leg 1: climb from the ring up to just outside the bay door.
        val t = (sp / f).coerceIn(0.0, 1.0)
        val e = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
        Triple(rx + (wx - rx) * e, ry + (wy - ry) * e, rz + (wz - rz) * e)
    } else {
        // Leg 2: fly in through the doorway to the shelf.
        val t = ((sp - f) / (1.0 - f)).coerceIn(0.0, 1.0)
        val e = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
        Triple(wx + (sx - wx) * e, wy + (sy - wy) * e, wz + (sz - wz) * e)
    }
}

/**
 * Flies the camera **in through the bay door** to an interior park in two chained legs:
 * leg A sweeps up to the outside [stationStagingPose] over [STASH_CAM_FRAMES]·[STATION_LEG_A_FRAC]
 * (the long climb, framing the door), then leg B punches forward through the doorway to
 * [interior] over the short [STATION_DOCK_TRANSIT_FRAMES] — a brief budget so the settle in
 * through the door stays crisp rather than crawling (the door-to-shelf hop is a small
 * distance, which the tracer's soft-ended ease would otherwise stretch into a slow creep).
 *
 * When [followPaneId] is set both legs track that pane; the interior leg then swings the
 * gaze to the sign on touchdown ([endLook]). When it is null (the bare [toggleStashView]
 * `v` visit — the only current caller) leg A frames the door and leg B frames the shelf.
 *
 * @param interior the interior park pose to land on ([stationInteriorPose]).
 * @param followPaneId the pane to keep centred through the flight, or `null`.
 * @see stashFront @see toggleStashView @see resetCamera
 */
internal fun flyStationEnter(interior: CamPose, followPaneId: String?) {
    val stage = stationStagingPose()
    flyCamTo(
        stage.cx, stage.cy, stage.cz,
        stage.lx, stage.ly, stage.lz,
        landPristine = false, frames = STASH_CAM_FRAMES * STATION_LEG_A_FRAC,
        pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
        followPaneId = followPaneId,
        sway = STASH_CAM_SWAY, roll = STASH_CAM_ROLL,
        then = {
            flyCamTo(
                interior.cx, interior.cy, interior.cz,
                interior.lx, interior.ly, interior.lz,
                landPristine = false, frames = STATION_DOCK_TRANSIT_FRAMES,
                pullout = STATION_ENTER_PULLOUT, rise = 0.0,
                followPaneId = followPaneId,
                endLook = Triple(interior.lx, interior.ly, interior.lz),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Chase cam — trail the pane the whole way to / from the station
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One in-flight **stash chase** — the camera trailing a pane on its journey to or from the
 * station. Held in [spikeStashChase] while active; the render loop drives the camera from
 * it each frame ([tickStashChase]).
 *
 * @property paneId the pane being chased (its live position is followed each frame).
 * @property outbound `true` while stashing (ring → shelf), `false` while unstashing.
 * @property landPristineAtEnd on arrival, whether to settle onto the pristine home view (an
 *   unstash lands home) or leave the camera parked at the pulled-back reveal pose (a stash
 *   ends up at the station).
 * @property startX/startY/startZ the camera position the chase eases *out of*, so the
 *   trailing pose blends in from wherever the camera sat rather than snapping on frame 1.
 * @property frames how many frames the chase has run (drives the ease-in). @see armStashChase
 */
internal class StashChase(
    val paneId: String,
    val outbound: Boolean,
    val landPristineAtEnd: Boolean,
    val startX: Double,
    val startY: Double,
    val startZ: Double,
) {
    var frames: Int = 0
}

/**
 * Whether a stash camera move is in progress — a scripted [flyCamTo] tour **or** a live
 * [StashChase]. The stash hotkeys guard on this so a second press can't fire a competing
 * flight mid-journey. @see toggleStash @see toggleStashView @see shelfBrowse
 */
internal fun stashBusy(): Boolean = spikeCamReturning || spikeStashChase != null

/**
 * Arms a [StashChase]: cancels any scripted flight, marks the camera flown, and hands the
 * camera to [tickStashChase] to trail [paneId] until it reaches its destination. Called by
 * [stashFront] (outbound) and [unstashNearest] (inbound) when the station is built.
 *
 * @param paneId the pane to chase. @param outbound `true` stashing, `false` unstashing.
 * @see tickStashChase
 */
internal fun armStashChase(paneId: String, outbound: Boolean) {
    clearFlyVelocity()
    spikeShelfPanTargetX = null // a chase overrides any pending shelf dolly
    // Capture the pose the chase eases out of — the live flown pose, or the pristine home
    // pose if the camera was still at rest — so the trailing view blends in smoothly.
    val sx: Double; val sy: Double; val sz: Double
    if (spikeCamFlown) { sx = spikeCamX; sy = spikeCamY; sz = spikeCamZ } else {
        sx = 0.0; sy = 0.0; sz = RING_R + perspDistance(window.innerHeight)
    }
    spikeCamReturning = false
    spikeCamTourThen = null
    spikeCamFlown = true
    // Snap the chase spotlight fully on immediately (not eased from 0) so the ring's
    // neighbour panes are already hidden on the very first frame — otherwise the pane that
    // rotates into the front slot occludes the departing pane before the fade ramps up.
    spikeChaseFocus = 1.0
    spikeStashChase = StashChase(paneId, outbound, landPristineAtEnd = !outbound, startX = sx, startY = sy, startZ = sz)
}

/**
 * Advances the active [spikeStashChase] one frame — the chase-cam controller. Parks the
 * camera at a fixed offset from the pane's **live** position (mostly below it and a little
 * in front of its face, so you look up at the readable terminal), and **pulls the camera
 * back** from [STASH_CHASE_NEAR_DIST] to [STASH_CHASE_FAR_DIST] as the pane climbs into the
 * [STASH_CHASE_ZOOM_BAND] below the shelf — revealing the whole station in frame on the
 * approach. The aim rises toward the station by [STASH_CHASE_LOOK_UP] as it pulls back so
 * the hull tilts into view. Writes the absolute camera pose ([spikeCamX]…/[spikeCamFx]…),
 * which the render loop's flown branch then applies.
 *
 * Runs from the render loop while [spikeStashChase] is set and no scripted tour is playing.
 * Ends when the pane reaches its destination: an unstash then eases onto the pristine home
 * view ([STASH_CHASE_SETTLE_FRAMES]); a stash leaves the camera parked at the reveal pose.
 * If the chased pane has vanished (closed mid-flight) the chase simply drops. @see armStashChase
 */
internal fun tickStashChase() {
    val ch = spikeStashChase ?: return
    val p = spikePanes.firstOrNull { it.paneId == ch.paneId && !it.dying }
    if (p == null) { spikeStashChase = null; return }
    val px = p.obj.position.x as Double
    val py = p.obj.position.y as Double
    val pz = p.obj.position.z as Double

    // Cinematic zoom-out/in **hump**: close-chase the pane leaving the ring, pull way back
    // mid-journey — as it approaches the door — to reveal the whole cargo ship in frame,
    // then dolly back in to follow it through the doorway to its shelf. A single hump over
    // journey progress (0 at the origin end → peak at [STASH_CHASE_PEAK] → 0 at the far
    // end), so it's symmetric for stash (rising) and unstash (falling).
    val sp = p.stashProg
    val jp = if (ch.outbound) sp else 1.0 - sp
    val hraw = if (jp < STASH_CHASE_PEAK) jp / STASH_CHASE_PEAK
    else (1.0 - jp) / (1.0 - STASH_CHASE_PEAK)
    val w = hraw * hraw * (3.0 - 2.0 * hraw) // smoothstep hump 0 → 1 → 0
    val dist = STASH_CHASE_NEAR_DIST + (STASH_CHASE_FAR_DIST - STASH_CHASE_NEAR_DIST) * w

    // Normalized camera offset from the pane: in front of its face (+Z) with a below-bias
    // (−Y) that **scales with the hump** — level with the pane near the ends of the journey,
    // tilting below only for the mid-journey ship reveal. Staying level at the ring end is
    // what keeps the ring's own panes from sitting between the camera and the pane as it
    // leaves (stash start) or arrives (unstash landing) — a below camera there looks up
    // *through* the ring and the pane is briefly occluded.
    val offY = STASH_CHASE_OFF_Y * w
    val ol = sqrt(
        STASH_CHASE_OFF_X * STASH_CHASE_OFF_X + offY * offY + STASH_CHASE_OFF_Z * STASH_CHASE_OFF_Z,
    )
    val rawCx = px + STASH_CHASE_OFF_X / ol * dist
    val rawCy = py + offY / ol * dist
    val rawCz = pz + STASH_CHASE_OFF_Z / ol * dist

    // Ease the trailing position in from the pose the chase started at, so frame 1 doesn't
    // snap the camera to the offset. After [STASH_CHASE_EASE_IN] frames it's the pure chase.
    ch.frames += 1
    val ein = (ch.frames / STASH_CHASE_EASE_IN).coerceIn(0.0, 1.0)
    val eb = ein * ein * (3.0 - 2.0 * ein) // smoothstep
    val cx = ch.startX + (rawCx - ch.startX) * eb
    var cy = ch.startY + (rawCy - ch.startY) * eb
    val cz = ch.startZ + (rawCz - ch.startZ) * eb

    // Occlusion safety: while the camera is *inside* the hull footprint it must stay above
    // the deck — dropping below it would put the solid floor between the camera and a pane
    // up in the bay, hiding it. Below the deck is only allowed *outside* the hull (the
    // exterior wide shot, in open space beside/under the ship). Rarely triggers given the
    // front-biased offset; it's a hard backstop so the pane can't be lost on the transit.
    //
    // Crucially this only applies while the **pane itself is up at/above the deck** (in the
    // bay): only then does the solid floor sit between a below-deck camera and the pane. For
    // the whole long climb from the ring — the pane far below the station (py ≪ deck) — there
    // is no floor between them, and raising the camera to the deck would strand it ~20k units
    // *above* the climbing pane, aimed down at a distant speck against the void. That was the
    // "I can see nothing" dropout at the start of a stash (and just before an unstash lands):
    // the footprint test alone is true from frame 1, so the camera teleported to the deck
    // while the pane was still down at the ring. Gating on py keeps the camera trailing the
    // pane the whole way up and only guards the deck once the pane has actually entered the bay.
    val floorClear = STATION_CY - STATION_HH + 200.0
    val insideHull = cz < STATION_CZ + STATION_HD && cz > STATION_CZ - STATION_HD &&
        cx > STATION_CX - STATION_HW && cx < STATION_CX + STATION_HW
    if (insideHull && py > floorClear && cy < floorClear) cy = floorClear

    // Aim at the pane, rising toward the station as we pull back so it tilts into frame.
    val lx = px
    val ly = py + STASH_CHASE_LOOK_UP * w
    val lz = pz
    var fx = lx - cx; var fy = ly - cy; var fz = lz - cz
    val fl = sqrt(fx * fx + fy * fy + fz * fz)
    if (fl > 1e-6) { fx /= fl; fy /= fl; fz /= fl }
    // Up = world up, orthogonalized against the nose (Gram–Schmidt).
    var ux = 0.0; var uy = 1.0; var uz = 0.0
    val dot = ux * fx + uy * fy + uz * fz
    ux -= dot * fx; uy -= dot * fy; uz -= dot * fz
    val ul = sqrt(ux * ux + uy * uy + uz * uz)
    if (ul > 1e-6) { ux /= ul; uy /= ul; uz /= ul }

    spikeCamX = cx; spikeCamY = cy; spikeCamZ = cz
    spikeCamFx = fx; spikeCamFy = fy; spikeCamFz = fz
    spikeCamUx = ux; spikeCamUy = uy; spikeCamUz = uz
    spikeCamFlown = true

    // Done when the pane has reached its destination.
    val done = if (ch.outbound) sp >= 0.999 else sp <= 0.06
    if (done) {
        spikeStashChase = null
        if (ch.landPristineAtEnd) {
            // Unstash: the pane is nearly home — ease the trailing pose onto the pristine
            // 1:1 view so the landing is smooth rather than a snap. Keep tracking the pane
            // through the settle (followPaneId) and only swing the gaze to the clean home
            // framing (endLook = origin) at the very end, so the pane stays in view right up
            // to touchdown instead of being dropped for the whole home flight.
            val homeZ = RING_R + perspDistance(window.innerHeight)
            flyCamTo(
                0.0, 0.0, homeZ, px, py, pz,
                landPristine = true, frames = STASH_CHASE_SETTLE_FRAMES, pullout = 0.0, rise = 0.0,
                followPaneId = ch.paneId, endLook = Triple(0.0, 0.0, 0.0),
            )
        }
        // Stash: leave the camera parked (flown) at the final pulled-back reveal pose.
    }
}
