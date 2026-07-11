/*
 * Split from World3DSpike.kt — the per-frame CSS3D render loop.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

import kotlin.js.json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
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
 * Paints a pane's **focus / current-target outline** each frame:
 *  - a **thick solid** accent outline on the pane you're *engaged* in (typing) or, in
 *    command center, selecting — matched by identity ([spikeLastEngagedPane]) rather than
 *    "is it the front pane", so the engaged pane in free flight (the one at screen centre,
 *    not necessarily the front) is the one that lights up — keeping the focus outline in
 *    lock-step with which terminal actually has the cursor;
 *  - a lighter **dashed, offset** outline on the **current target** pane (what an action
 *    would act on) when it isn't the engaged one — the "this is chosen" marker;
 *  - none otherwise.
 *
 * Uses `outline` (drawn outside the border box, immune to the wrapper's `overflow:hidden`)
 * so the 1:1 terminal content never shifts. Called per pane by [startSpikeLoop] for both
 * the reactor and glow/none status paths.
 *
 * @param p the pane. @param isFront whether it is the settled front pane (for selection
 *   mode, which is command-center / front-only). @param currentTargetId the id of the
 *   current target pane ([actionTargetPane]), or `null`. @param accent the theme accent.
 */
internal fun applyTargetOutline(p: RingPane, isFront: Boolean, currentTargetId: String?, accent: String) {
    val engaged = spikeEngaged && p.paneId == spikeLastEngagedPane
    val selecting = isFront && spikeSelectionMode
    val isCurrent = currentTargetId != null && p.paneId == currentTargetId
    when {
        engaged || selecting -> {
            p.wrapper.style.setProperty("outline", "3px solid $accent")
            p.wrapper.style.setProperty("outline-offset", "0px")
        }
        isCurrent -> {
            p.wrapper.style.setProperty("outline", "2px dashed $accent")
            p.wrapper.style.setProperty("outline-offset", "6px")
        }
        else -> {
            p.wrapper.style.setProperty("outline", "none")
            p.wrapper.style.setProperty("outline-offset", "0px")
        }
    }
}

/**
 * The render loop: eases both axes (horizontal pane fan + vertical tab scroll),
 * places every pane (front at 1:1 facing the camera), fades panes that fan/scroll
 * too far, and marks the front pane settled so Enter can engage it.
 */
internal fun startSpikeLoop() {
    val camera = spikeCamera ?: return
    val css = spikeCss3d ?: return
    val scene = spikeCssScene ?: return

    fun frame() {
        if (!spikeOpen) return

        // Frame-rate-normalised time step: how many 60fps-frames of real wall-clock time
        // passed since the last frame. Every per-frame animation clock (wormhole phase,
        // phaser bolts, cinematic camera return) advances by this instead of a flat 1.0, so
        // their duration is the SAME on a 60Hz and a 144Hz+ display — otherwise the whole
        // sequence races ~2-3× faster on a high-refresh screen. Clamped so a stalled/back-
        // grounded tab resumes smoothly rather than teleporting forward. @see spikeDtFrames
        run {
            val nowMs = window.performance.now()
            spikeDtFrames = if (spikeLastFrameMs.isNaN()) {
                1.0
            } else {
                ((nowMs - spikeLastFrameMs) / SPIKE_FRAME_MS).coerceIn(0.0, SPIKE_DT_MAX_FRAMES)
            }
            spikeLastFrameMs = nowMs
        }

        // Camera: fly it if flying, ease it home if returning, else hold the pose —
        // and while still "pristine" recompute the exact default each frame so the
        // front pane stays 1:1 through window resizes.
        val camH = window.innerHeight
        val defaultZ = RING_R + perspDistance(camH)
        if (spikeFlyMode && !spikeCamReturning) applyFlyStep()
        if (spikeCamReturning) {
            // Cinematic tour: advance normalized progress, ease it with a smootherstep
            // (soft launch + soft landing), and trace a quadratic Bézier start → apex →
            // target so the camera swings out to frame the scene then swoops in. It
            // *faces the tour's look point* the whole way. The `c` return targets the
            // home pose looking at the origin (ending nose −Z, roof +Y — the pristine
            // pose); a stash flight targets the shelf-view pose looking at the shelf.
            spikeCamReturnT = (spikeCamReturnT + spikeDtFrames / spikeCamTourFrames).coerceAtMost(1.0)
            val t = spikeCamReturnT
            val s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0) // smootherstep
            val u = 1.0 - s
            // B(s) = u²·start + 2u·s·apex + s²·target.
            spikeCamX = u * u * spikeCamReturnStartX + 2.0 * u * s * spikeCamReturnApexX + s * s * spikeCamTourTargetX
            spikeCamY = u * u * spikeCamReturnStartY + 2.0 * u * s * spikeCamReturnApexY + s * s * spikeCamTourTargetY
            spikeCamZ = u * u * spikeCamReturnStartZ + 2.0 * u * s * spikeCamReturnApexZ + s * s * spikeCamTourTargetZ

            // Cinematic sway: bow the path sideways along the horizontal perpendicular
            // of the straight start→target line — sin(πs) bulges mid-flight and is zero
            // at both endpoints, so launch and landing spots are untouched. Falls back
            // to world X when the flight is near-vertical (the stash climb often is).
            if (spikeCamTourSway != 0.0) {
                val pdx = spikeCamTourTargetX - spikeCamReturnStartX
                val pdz = spikeCamTourTargetZ - spikeCamReturnStartZ
                val pl = sqrt(pdx * pdx + pdz * pdz)
                val px = if (pl > 1.0) pdz / pl else 1.0
                val pz = if (pl > 1.0) -pdx / pl else 0.0
                val bulge = spikeCamTourSway * sin(PI * s)
                spikeCamX += px * bulge
                spikeCamZ += pz * bulge
            }

            // Face the look point: forward = normalize(look − pos). When the tour is
            // following a pane, the look point is that pane's live position (so the
            // camera keeps it centred as it flies); otherwise the fixed tour look point.
            // Up eases from the start roof toward +Y so any bank/roll unwinds; Gram–
            // Schmidt keeps the frame orthonormal. With look = origin and target = home
            // this reproduces the old return exactly (nose −Z, roof +Y at rest).
            var lookX = spikeCamTourLookX
            var lookY = spikeCamTourLookY
            var lookZ = spikeCamTourLookZ
            spikeCamTourFollowPaneId?.let { id ->
                spikePanes.firstOrNull { it.paneId == id }?.let { fp ->
                    lookX = fp.obj.position.x as Double
                    lookY = fp.obj.position.y as Double
                    lookZ = fp.obj.position.z as Double
                }
            }
            // Tail aim-ease: over the final CAM_TOUR_END_BLEND, swing the gaze from the
            // in-flight look (fixed point or the followed pane) to the arrival look point
            // — used to frame the destination beacon sign on touchdown while still having
            // tracked the pane the whole way up/down. The mirror of the launch blend below.
            if (spikeCamTourHasEndLook && s > 1.0 - CAM_TOUR_END_BLEND) {
                val r = (s - (1.0 - CAM_TOUR_END_BLEND)) / CAM_TOUR_END_BLEND
                val e = r * r * r * (r * (r * 6.0 - 15.0) + 10.0) // smootherstep
                lookX += (spikeCamTourEndLookX - lookX) * e
                lookY += (spikeCamTourEndLookY - lookY) * e
                lookZ += (spikeCamTourEndLookZ - lookZ) * e
            }
            val lx = lookX - spikeCamX
            val ly = lookY - spikeCamY
            val lz = lookZ - spikeCamZ
            val fl = sqrt(lx * lx + ly * ly + lz * lz)
            if (fl > 1e-6) {
                var nfx = lx / fl
                var nfy = ly / fl
                var nfz = lz / fl
                // Aim blend: over the first CAM_TOUR_LOOK_BLEND of the flight, swing
                // the nose from its launch direction toward the look point (nlerp +
                // renormalize, smootherstep-eased) instead of snapping there on frame 1
                // — e.g. `c` pressed while parked at the shelf gazing up, when the
                // return wants to gaze down at the origin. Exact tracking thereafter.
                if (s < CAM_TOUR_LOOK_BLEND) {
                    val r = s / CAM_TOUR_LOOK_BLEND
                    val e = r * r * r * (r * (r * 6.0 - 15.0) + 10.0)
                    nfx = spikeCamReturnStartFx + (nfx - spikeCamReturnStartFx) * e
                    nfy = spikeCamReturnStartFy + (nfy - spikeCamReturnStartFy) * e
                    nfz = spikeCamReturnStartFz + (nfz - spikeCamReturnStartFz) * e
                    val nl = sqrt(nfx * nfx + nfy * nfy + nfz * nfz)
                    if (nl > 1e-6) { nfx /= nl; nfy /= nl; nfz /= nl }
                }
                spikeCamFx = nfx; spikeCamFy = nfy; spikeCamFz = nfz
            }
            var ux = spikeCamReturnStartUx + (0.0 - spikeCamReturnStartUx) * s
            var uy = spikeCamReturnStartUy + (1.0 - spikeCamReturnStartUy) * s
            var uz = spikeCamReturnStartUz + (0.0 - spikeCamReturnStartUz) * s
            val d = ux * spikeCamFx + uy * spikeCamFy + uz * spikeCamFz
            ux -= d * spikeCamFx; uy -= d * spikeCamFy; uz -= d * spikeCamFz
            val ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul > 1e-6) { spikeCamUx = ux / ul; spikeCamUy = uy / ul; spikeCamUz = uz / ul }

            // Cinematic bank: roll the roof about the nose by rollAmp·sin(2πs) — lean
            // into the journey on the way out, unwind through the midpoint, counter-bank
            // on the approach; zero at both endpoints so every flight lands dead level.
            // (Rotating up toward the right vector is exact for an orthonormal frame.)
            if (spikeCamTourRoll != 0.0) {
                val bank = spikeCamTourRoll * sin(2.0 * PI * s)
                val rx = spikeCamFy * spikeCamUz - spikeCamFz * spikeCamUy
                val ry = spikeCamFz * spikeCamUx - spikeCamFx * spikeCamUz
                val rz = spikeCamFx * spikeCamUy - spikeCamFy * spikeCamUx
                val cb = cos(bank); val sb = sin(bank)
                val bux = spikeCamUx * cb + rx * sb
                val buy = spikeCamUy * cb + ry * sb
                val buz = spikeCamUz * cb + rz * sb
                spikeCamUx = bux; spikeCamUy = buy; spikeCamUz = buz
            }

            // Permanent landing bank: ease the roof into a fixed tilt that ramps to full
            // by touchdown (bank = landRoll·s) and *stays* — the parked pose holds it,
            // since the flown branch below reads spikeCamU* verbatim once the tour ends.
            // Unlike the sin(2πs) lean above this does not unwind, giving the [flyOverview]
            // hero shot its "slightly rotated" composition. @see spikeCamTourLandRoll
            if (spikeCamTourLandRoll != 0.0) {
                val bank = spikeCamTourLandRoll * s
                val rx = spikeCamFy * spikeCamUz - spikeCamFz * spikeCamUy
                val ry = spikeCamFz * spikeCamUx - spikeCamFx * spikeCamUz
                val rz = spikeCamFx * spikeCamUy - spikeCamFy * spikeCamUx
                val cb = cos(bank); val sb = sin(bank)
                val bux = spikeCamUx * cb + rx * sb
                val buy = spikeCamUy * cb + ry * sb
                val buz = spikeCamUz * cb + rz * sb
                spikeCamUx = bux; spikeCamUy = buy; spikeCamUz = buz
            }

            if (t >= 1.0) {
                spikeCamReturning = false
                spikeCamTourFollowPaneId = null // stop tracking; hold the landed pose
                // Land pristine (the `c` return) → hand the pose back to the recomputed
                // 1:1 default; otherwise stay parked at the flown target (the shelf).
                if (spikeCamTourLandPristine) {
                    spikeCamFlown = false
                }
                // Chain the next leg (the fly-through-the-door stash journeys sequence
                // approach → door transit → interior this way). Capture and clear first,
                // then invoke: the follow-on typically arms the next [flyCamTo], which
                // sets [spikeCamReturning] true again so the tour continues next frame.
                val next = spikeCamTourThen
                spikeCamTourThen = null
                next?.invoke()
            }
        }
        // Stash chase cam: while a pane flies to/from the station, trail it (this writes
        // the absolute pose the flown branch below applies). Skipped while a scripted tour
        // is playing (e.g. the home-settle flight armed when an unstash chase completes).
        if (spikeStashChase != null && !spikeCamReturning) tickStashChase()
        // Chase spotlight weight: rise toward 1 while chasing (the world's other panes fade
        // out below so none occludes the travelling one), fall back to 0 otherwise.
        spikeChaseFocus += ((if (spikeStashChase != null) 1.0 else 0.0) - spikeChaseFocus) * STASH_CHASE_FOCUS_EASE
        // Dock legend trim: when the camera crosses in/out of the dock, re-run the legend
        // visibility so the shortcuts table drops to (or restores from) its dock-only
        // subset. Edge-triggered off the cached flag so it touches the DOM only on the
        // crossing, not every frame. @see updateLegendVisibility @see SHELF_SHORTCUT_IDS
        val atShelfNow = cameraAtShelf()
        if (atShelfNow != spikeLegendAtShelf) {
            spikeLegendAtShelf = atShelfNow
            updateLegendVisibility()
        }
        // Shelf dolly: while browsing the dock ←/→ ([shelfBrowse]), truck the camera
        // straight sideways toward the browsed slot's x — fixed height, depth and
        // forward gaze — so the row slides past cleanly instead of the camera swinging
        // inward the way a look-at-destination tour did. Dormant during any tour/chase
        // (those own the pose and clear the target) and in free-fly. @see spikeShelfPanTargetX
        if (spikeShelfPanTargetX != null && !spikeCamReturning && !spikeFlyMode &&
            spikeStashChase == null && spikeCamFlown
        ) {
            val tx = spikeShelfPanTargetX!!
            spikeCamX += (tx - spikeCamX) * SHELF_PAN_EASE
            if (abs(tx - spikeCamX) < SHELF_PAN_STOP_EPS) {
                spikeCamX = tx
                spikeShelfPanTargetX = null
            }
        }
        if (!spikeCamFlown) {
            camera.up.set(0.0, 1.0, 0.0)
            camera.position.set(0.0, 0.0, defaultZ)
            camera.lookAt(0.0, 0.0, 0.0)
        } else {
            // Orient straight from the ship's basis: up = roof vector, look down the
            // nose. Roll is carried in the up-vector, so banks and loops are exact.
            camera.up.set(spikeCamUx, spikeCamUy, spikeCamUz)
            // Spaceship bob: while window bobbing is on, gently float the *presented*
            // camera pose (translate both eye and look-at by the same vertical offset, so
            // only the position bobs — the nose direction is untouched). Applied to the
            // presented pose only, never the stored spikeCam* state, so flight momentum
            // and the cinematic return math are unaffected. Suppressed during a return so
            // landings stay dead level, mirroring the panes' holdStill suppression.
            val camBob = if (spikeBobEnabled && !spikeCamReturning)
                sin(spikeBobPhase) * BOB_AMPLITUDE else 0.0
            val bx = spikeCamX
            val by = spikeCamY + camBob
            val bz = spikeCamZ
            camera.position.set(bx, by, bz)
            camera.lookAt(bx + spikeCamFx, by + spikeCamFy, bz + spikeCamFz)
        }

        // Ease scrolls + advance shared phases every frame — unconditionally, so
        // empty-tab cards keep animating even when there are no panes at all.
        val cur = spikeTabIndex
        spikeTabScroll += (cur - spikeTabScroll) * TAB_EASE
        val curSel = (spikeTabSel.getOrNull(cur) ?: 0).toDouble()
        spikePaneScroll += (curSel - spikePaneScroll) * PANE_EASE
        val settled = abs(spikeTabScroll - cur) < SETTLE_EPS && abs(spikePaneScroll - curSel) < SETTLE_EPS
        val fi = frontIndex()
        // The **current** pane — the one an action (focus, glide, grid, reformat, zoom,
        // stash) would act on: the command center's selected pane, or, in free flight, the
        // pane at screen centre ([paneAtScreenCenter]). Marked with a dashed target outline
        // below so you always see which pane is "chosen". Recomputed each frame (a cheap
        // ray-cast in fly mode) so it tracks the camera live.
        // The selected dock item may be a whole **tab bundle** rather than a single pane;
        // when it is, [selectedBundleId] names it so [tickBundles] can ring the *entire
        // stack* — every sheet — with the dashed target outline, instead of the outline
        // covering just one pane of the pack. It stays null whenever the selection is a lone
        // pane (the [currentTargetId] path below wears the outline for that case).
        var selectedBundleId: String? = null
        val currentTargetId = when {
            spikeFlyMode -> paneAtScreenCenter()?.paneId
            // Up at the dock, the *browsed* dock item is the selection (what ←/→ moves and
            // Space brings home), so it — not the hidden fronted ring pane far below — wears
            // the dashed target outline, matching command center. The dock row holds both
            // single stashed panes and tab bundles ([dockItemCount]); a bundle slot has no
            // single target pane, so it routes to [selectedBundleId] and leaves this null.
            cameraAtShelf() -> {
                val n = dockItemCount()
                val slot = if (spikeShelfIndex in 0 until n) spikeShelfIndex else nearestDockSlot()
                when {
                    slot < 0 -> null
                    slot < spikeStashed.size -> spikeStashed.getOrNull(slot)
                    else -> {
                        selectedBundleId = spikeStashedTabs.getOrNull(slot - spikeStashed.size)?.id
                        null
                    }
                }
            }
            else -> spikePanes.getOrNull(fi)?.paneId
        }

        // Ease the fade regime: while the camera is off its home pose, cross-dissolve
        // from the selection-focus fade toward the camera distance/facing fade so
        // flying reveals the whole sphere; ease back on landing / return-home.
        spikeFlyReveal += ((if (spikeCamFlown) 1.0 else 0.0) - spikeFlyReveal) * FLY_REVEAL_EASE

        // Snapshot the camera pose for the distance fade (matches what the camera was
        // set to above): the flown pose, or the pristine home pose looking down −Z.
        val fadeCamX = if (spikeCamFlown) spikeCamX else 0.0
        val fadeCamY = if (spikeCamFlown) spikeCamY else 0.0
        val fadeCamZ = if (spikeCamFlown) spikeCamZ else defaultZ
        val fadeFwdX = if (spikeCamFlown) spikeCamFx else 0.0
        val fadeFwdY = if (spikeCamFlown) spikeCamFy else 0.0
        val fadeFwdZ = if (spikeCamFlown) spikeCamFz else -1.0

        // "Working" breath + idle bob: shared per-frame phases, and a snapshot of
        // the live session state map, computed once and applied to every pane.
        spikePulsePhase += WORKING_PULSE_SPEED
        spikeBobPhase += BOB_SPEED
        spikeBorderDash -= WORKING_BORDER_SPEED // negative → dashes travel forward round the path
        spikeWaitPhase += WAITING_PULSE_SPEED
        // Warp-core wall-clock (seconds): the reactor breath / awaiting heartbeat / ping
        // cadence / discharge shaping are all spec'd in seconds. @see tickWarpCore
        spikeWarpClock += spikeDtFrames / 60.0
        // Home beacon: spin the crossed chevrons about the arrow's pointing axis so
        // the landmark reads volumetrically from any free-fly angle (glow pulse is
        // pure CSS — only the spin needs a per-frame write).
        spikeBeaconPhase += BEACON_SPIN_SPEED
        spikeBeaconSpin?.rotation?.set(0.0, spikeBeaconPhase, 0.0)
        // Stash beacon: same volumetric trick, counter-spun at its own cadence so the
        // two landmarks read as siblings, not copies.
        spikeStashBeaconPhase += STASH_BEACON_SPIN_SPEED
        spikeStashBeaconSpin?.rotation?.set(0.0, spikeStashBeaconPhase, 0.0)
        // Cosmos: billboard every planet/nebula/star-cluster to the camera and apply
        // its slow drift, so the flat gradient discs read as spheres from any angle.
        tickCosmos(camera)
        val pulse = WORKING_PULSE_MIN +
            (WORKING_PULSE_MAX - WORKING_PULSE_MIN) * (0.5 + 0.5 * sin(spikePulsePhase))
        // Green "working" halo alpha — the pulsating-green-light alternative to the
        // dotted border, breathing at the calm working cadence (shares [spikePulsePhase]).
        val workAlpha = WORKING_GLOW_MIN +
            (WORKING_GLOW_MAX - WORKING_GLOW_MIN) * (0.5 + 0.5 * sin(spikePulsePhase))
        // Urgent red "needs input" halo alpha — breathes faster than the working pulse.
        val waitAlpha = WAITING_GLOW_MIN +
            (WAITING_GLOW_MAX - WAITING_GLOW_MIN) * (0.5 + 0.5 * sin(spikeWaitPhase))
        val sessionStates = runCatching { lunamuxClient.windowState.states.value }.getOrNull()

        run {
            spikePanes.forEachIndexed { i, p ->
                // A pane whose tab is fully unlisted and now *parked* in the hangar bay is
                // owned end-to-end by [tickBundles] (position, scale, opacity) and its config
                // tab is gone, so its [RingPane.tabOrd] is frozen and may point past the live
                // tab count — running the sphere-placement math below would read [spikeTabSel]
                // out of range. Skip it here; [tickBundles] places it. Panes still merging,
                // flying or separating keep a valid tabOrd (their tab is still/again listed),
                // so they run the loop and [tickBundles] overrides the result. @see stashTab
                if (isParkedBundlePane(p)) return@forEachIndexed
                // Every pane rides one sphere of radius RING_R: the horizontal pane
                // fan is *longitude* (theta), the tab wheel is *latitude* (phi). The
                // fronted tab's selected pane sits at the pole facing the camera; ↑/↓
                // rotate the latitude so you "go around" the tabs on a real circle
                // instead of sliding a flat stack.
                val safeTabOrd = p.tabOrd.coerceIn(0, (spikeTabSel.size - 1).coerceAtLeast(0))
                val center = if (p.tabOrd == cur) spikePaneScroll else spikeTabSel.getOrElse(safeTabOrd) { 0 }.toDouble()
                // Ease the *displayed* ordinal toward the logical one so a survivor whose
                // neighbour just died (and was renumbered down by [reconcileRing]) glides
                // into the freed slot instead of snapping. A jump bigger than a single slot
                // (bundle separation, a large reorder) is snapped, not slid. @see RingPane.dispOrd
                val ordTarget = p.paneOrdInTab.toDouble()
                if (abs(ordTarget - p.dispOrd) > 2.0) p.dispOrd = ordTarget
                else p.dispOrd += (ordTarget - p.dispOrd) * PANE_EASE
                val slot = p.dispOrd - center
                val theta = slot * SLOT_ANGLE
                val tabRel = p.tabOrd - spikeTabScroll
                val phi = tabRel * TAB_ANGLE
                val ringCosT = cos(theta) * RING_R
                // Identity-based, not `i == fi`: in free flight the engaged pane is the one
                // at screen centre, which need not be the front pane.
                val engagedThis = spikeEngaged && p.paneId == spikeLastEngagedPane

                // Scale first: the front pane at its live zoom, every other pane at its
                // *remembered* zoom (so a pane you magnified stays magnified when it
                // drifts off-centre — it no longer snaps back to 1× when unfocused).
                val targetScale = if (i == fi) spikeZoomTarget
                    else (spikeZoomByPane[p.paneId] ?: 1.0) * p.normScale
                // Ease the *logical* scale (kept separate from the birth multiplier
                // below, so a growing/shrinking pane can't corrupt its own zoom ease).
                val curS = p.lscale
                // Selection mode snaps the front pane to an exact 1:1 (no ease) so
                // xterm's mouse→cell mapping is pixel-accurate for drag-select.
                // A ⇧ zoom preset glides the front pane at the much slower
                // ZOOM_PRESET_EASE (its one-jump target is far enough away that
                // SCALE_EASE reads as a snap); the glide flag drops once the pane
                // arrives so later target changes ease at the normal rate again.
                val ease = if (i == fi && spikeZoomGlide) ZOOM_PRESET_EASE else SCALE_EASE
                val ns = if (spikeSelectionMode && i == fi) 1.0 else curS + (targetScale - curS) * ease
                if (i == fi && spikeZoomGlide && abs(targetScale - ns) < 0.001) spikeZoomGlide = false
                p.lscale = ns
                // Birth/death: a just-created pane grows in from 0; a removed one
                // shrinks out (disposed by the sweep below once birth ≈ 0).
                val birthTarget = if (p.dying) 0.0 else 1.0
                p.birth += (birthTarget - p.birth) * (if (p.dying) DESPAWN_EASE else SPAWN_EASE)
                val bs = ns * p.birth.coerceIn(0.0, 1.0)
                // Latch flex: a one-shot decaying-sine spring played on the pane you
                // just engaged (outward) or left (inward). `env` starts and ends at ~0,
                // cresting once — a clean out/in and back — with a small spring
                // overshoot when FLEX_FREQ > 1. It drives a convex **bulge** (the SVG
                // displacement filter), a scale **lunge**, and a subtle **tilt** in
                // lock-step, then disarms and clears the filter once the envelope ends.
                var flexTilt = 0.0
                var flexScale = 1.0
                // The engage/disengage flex is suppressed while a phaser close owns the
                // bulge (below), so a leftover disengage spring can't fight the wound.
                if (p.flexPhase >= 0.0 && p.phaserPhase < 0.0) {
                    val t = p.flexPhase / FLEX_FRAMES
                    if (t >= 1.0) {
                        p.flexPhase = -1.0
                        // Envelope done: drop the (non-trivial) filter so the pane
                        // composites cheaply again at rest.
                        p.wrapper.style.removeProperty("filter")
                        p.bulgeMap?.setAttribute("scale", "0")
                    } else {
                        val env = sin(FLEX_FREQ * PI * t) * exp(-FLEX_DECAY * t)
                        val deflect = p.flexDir * env
                        flexScale = 1.0 + deflect * FLEX_AMPLITUDE
                        flexTilt = deflect * FLEX_TILT
                        // Bulge one way on engage, the opposite on disengage, via the
                        // per-pane displacement map's animated scale.
                        p.bulgeMap?.setAttribute("scale", (deflect * FLEX_BULGE).toString())
                        if (p.bulgeFilterId.isNotEmpty()) {
                            p.wrapper.style.setProperty("filter", "url(#${p.bulgeFilterId})")
                        }
                        p.flexPhase += 1.0
                    }
                }
                // Phaser-fire close: while the pane is being shot (phaserPhase >= 0) it
                // takes over the same fisheye bulge, driving it ever outward and jolting it
                // on each hit so the pane looks progressively wounded, then implodes it
                // inward as it collapses smoothly into its own centre and vanishes.
                var phaserScale = 1.0
                if (p.phaserPhase >= 0.0) {
                    p.phaserRecoil *= PHASER_RECOIL_DECAY
                    val recoil = p.phaserRecoil
                    if (p.phaserPhase <= PHASER_TOTAL_FRAMES) {
                        // Barrage: bulge swells START→MAX × FLEX_BULGE, plus a constant
                        // shudder and the per-hit recoil punch; the pane bloats by SWELL.
                        val prog = (p.phaserPhase / PHASER_TOTAL_FRAMES).coerceIn(0.0, 1.0)
                        val grow = PHASER_BULGE_START + (PHASER_BULGE_MAX - PHASER_BULGE_START) * prog
                        val tremor = sin(p.phaserPhase * PHASER_HURT_TREMOR_SPEED) * PHASER_HURT_TREMOR
                        p.bulgeMap?.setAttribute("scale", (FLEX_BULGE * grow + tremor + recoil * PHASER_RECOIL_BULGE).toString())
                        phaserScale = 1.0 + PHASER_SWELL * prog + recoil * PHASER_RECOIL_SCALE
                        flexTilt = sin(p.phaserPhase * PHASER_HURT_TREMOR_SPEED * 0.7) * PHASER_HURT_TILT
                    } else {
                        // Collapse: ease the swollen scale to 0 (accelerating in) while the
                        // bulge caves inward, so the pane implodes into its own centre.
                        val cp = ((p.phaserPhase - PHASER_TOTAL_FRAMES) / PHASER_COLLAPSE_FRAMES).coerceIn(0.0, 1.0)
                        val e = cp * cp
                        phaserScale = (1.0 + PHASER_SWELL) * (1.0 - e)
                        // Bulge: don't snap the swollen outward wound flat the instant the
                        // collapse starts (jarring, since the pane is still full size here).
                        // Blend from where the barrage left it (outward MAX) toward the inward
                        // implode on a late-weighted curve (cp³ holds the wound while the pane
                        // is big, then caves through flat only once it has shrunk small enough
                        // for the transition to go unnoticed).
                        val outwardBulge = FLEX_BULGE * PHASER_BULGE_MAX
                        val inwardBulge = -FLEX_BULGE * PHASER_IMPLODE
                        val caveIn = cp * cp * cp
                        p.bulgeMap?.setAttribute("scale", (outwardBulge + (inwardBulge - outwardBulge) * caveIn).toString())
                    }
                    if (p.bulgeFilterId.isNotEmpty()) {
                        p.wrapper.style.setProperty("filter", "url(#${p.bulgeFilterId})")
                    }
                }
                val fbs = bs * flexScale * phaserScale
                p.obj.scale.set(fbs, fbs, fbs)

                // Resolve this pane's live agent status *before* placing it, so the
                // warp-core awaiting **lean** can factor into its world z. The manual
                // `w` override wins over the live state (same as the visual block below).
                val working = spikeWorkingOverride[p.paneId] ?: (sessionStates?.get(p.sessionId) == "working")
                // Needs-input ("waiting") state: an agent has stopped and is blocking on
                // you. The manual override wins over the live state, mirroring above.
                val waiting = spikeWaitingOverride[p.paneId] ?: (sessionStates?.get(p.sessionId) == "waiting")

                // Position on the sphere, plus two touches: a slow idle **bob** on
                // every pane except the one you've grabbed (engaged), phase-staggered
                // per pane; and a size-scaled **z push-back** on non-front panes so a
                // big neighbour's near edge can't poke in front of the centred pane.
                // Stash flight: advance this pane's stashProg toward the shelf (1) while
                // it is in [spikeStashed], else back toward its ring slot (0) — at the
                // SAME fixed rate ([STASH_CAM_FRAMES]) and through the SAME smootherstep
                // curve the camera tour uses, so the camera travels in lockstep with the
                // pane the whole slow way up / down. `stashE` (the eased progress) lerps
                // position/rotation and lifts opacity so a shelved pane stays fully
                // visible off the ring. @see stashShelfPos @see flyCamTo
                val si = spikeStashed.indexOf(p.paneId)
                if (si >= 0) p.stashSlot = si
                val stashTgt = if (si >= 0) 1.0 else 0.0
                val stashStep = 1.0 / STASH_CAM_FRAMES
                p.stashProg = when {
                    p.stashProg < stashTgt -> (p.stashProg + stashStep).coerceAtMost(stashTgt)
                    p.stashProg > stashTgt -> (p.stashProg - stashStep).coerceAtLeast(stashTgt)
                    else -> p.stashProg
                }
                val sp = p.stashProg
                val stashE = sp * sp * sp * (sp * (sp * 6.0 - 15.0) + 10.0) // smootherstep
                val onShelf = p.stashProg > 0.5

                var py = -sin(phi) * ringCosT
                var pz = cos(phi) * ringCosT
                // Hold still when you're interacting with the front pane (engaged, or
                // in selection mode so a drag-select target isn't drifting), or when the
                // pane is up on / heading to the shelf so it rests there; otherwise bob.
                val holdStill = engagedThis || (i == fi && spikeSelectionMode) || onShelf
                if (spikeBobEnabled && !holdStill) py += sin(spikeBobPhase + i * BOB_STAGGER) * BOB_AMPLITUDE
                if (i != fi) {
                    pz -= SIDE_Z_PUSH * ns
                    // Hard occlusion guarantee: the fixed push above is aesthetic and
                    // cannot bound a wide/tall/zoomed neighbour, whose *near edge*
                    // protrudes toward the camera by halfW·sin(yaw) + halfH·sin(tilt).
                    // If that edge crossed the front pane's plane (z = RING_R) the
                    // browser would composite it over the centred pane — the paint-
                    // order raise below can't save an actually-nearer plane. Clamp the
                    // centre depth so the near edge always stays SIDE_NEAR_CLEARANCE
                    // behind the front plane.
                    val halfW = p.baseCw * 0.5 * fbs
                    val halfH = (p.baseCh + TITLE_H) * 0.5 * fbs
                    val protrude = halfW * abs(sin(theta * SIDE_YAW_FACTOR)) + halfH * abs(sin(phi))
                    pz = min(pz, RING_R - SIDE_NEAR_CLEARANCE - protrude)
                }
                var px = sin(theta) * RING_R
                // Move the whole plane between its ring slot and its shelf slot, so a
                // stashing pane visibly flies up (and an unstashing one sails back down).
                // With the station built the path routes up and *in through the bay door*
                // ([stashPanePath]); off-station it is the plain ring→shelf lerp.
                if (p.stashProg > 0.001) {
                    val (sx, sy, sz) = stashShelfPos(p.stashSlot)
                    val (npx, npy, npz) = stashPanePath(px, py, pz, sx, sy, sz, p.stashProg)
                    px = npx; py = npy; pz = npz
                    // Docked panes bob too: the ring bob above is applied to the pre-lerp
                    // ring-slot y and fades out as the pane climbs to the shelf, so add a
                    // fresh idle bob around the *shelf* rest position, faded in by stashE
                    // (full once parked, zero on the ring) and phase-staggered by shelf
                    // slot so neighbours drift out of sync — the same gentle bob the ring
                    // panes have, now on the dock. @see BOB_AMPLITUDE
                    if (spikeBobEnabled) {
                        py += sin(spikeBobPhase + p.stashSlot * BOB_STAGGER) * BOB_AMPLITUDE * stashE
                    }
                }
                p.obj.position.set(px, py, pz)
                // A shelved pane squarely faces the viewer (its sphere yaw/tilt eased to
                // flat by stashE); on the ring it keeps its normal sphere orientation.
                p.obj.rotation.set(
                    (phi + flexTilt) * (1.0 - stashE),
                    (theta * SIDE_YAW_FACTOR) * (1.0 - stashE),
                    0.0,
                )

                // Two fade regimes, cross-dissolved by [spikeFlyReveal]:
                //  • focus fade — opacity by fan/scroll distance from the selection,
                //    lighting only the neighbours (the resting, navigable view);
                //  • fly fade — opacity by distance from the camera and whether the
                //    pane sits ahead of the nose, so flight reveals the near
                //    hemisphere and drops the far side / anything behind you.
                val focusFade = min(edgeFade(slot, MAX_VISIBLE_SLOTS - 1.0, 1.0), edgeFade(tabRel, TAB_FADE_PLATEAU, TAB_FADE_EDGE))
                val fade = if (spikeFlyReveal <= 0.001) focusFade else {
                    val dx = px - fadeCamX
                    val dy = py - fadeCamY
                    val dz = pz - fadeCamZ
                    val dist = sqrt(dx * dx + dy * dy + dz * dz)
                    // Facing: cosine of the angle between the nose and the pane; soft
                    // band around ±90° so a pane swinging behind you dissolves.
                    val facing = if (dist > 0.0) (dx * fadeFwdX + dy * fadeFwdY + dz * fadeFwdZ) / dist else 1.0
                    val front = ((facing + FLY_BEHIND_BAND) / (2.0 * FLY_BEHIND_BAND)).coerceIn(0.0, 1.0)
                    val flyFade = edgeFade(dist, FLY_FADE_FULL, FLY_FADE_EDGE) * front
                    focusFade + (flyFade - focusFade) * spikeFlyReveal
                }
                // A stashing pane sits far off the ring where the focus/fly fade would
                // hide it, so lift its opacity toward full by its stash progress.
                var vis = fade + (1.0 - fade) * stashE
                // Chase spotlight: while a stash chase runs, fade the *ring* neighbour panes
                // (which sit right at the camera as it leaves / returns to the ring and would
                // occlude the travelling pane) so nothing hides it. The chased pane is held
                // lit. Panes already **parked on the shelf** are exempt — they are at the
                // destination, cannot occlude the traveller, and must stay visible so flying a
                // pane up to a shelf that already has windows doesn't blank the ones up there
                // until arrival. Eased by [spikeChaseFocus] so the world dissolves out and
                // back rather than popping.
                if (spikeChaseFocus > 0.001) {
                    vis = when {
                        spikeStashChase?.paneId == p.paneId -> maxOf(vis, spikeChaseFocus)
                        onShelf -> vis // already docked at the shelf — keep it shown
                        else -> vis * (1.0 - spikeChaseFocus * (1.0 - STASH_CHASE_OTHER_OPACITY))
                    }
                }
                p.wrapper.style.setProperty("opacity", vis.toString())
                p.wrapper.style.setProperty("display", if (vis <= 0.01) "none" else "")

                val isFront = settled && i == fi
                // Up at the shelf, the *browsed* slot is the selection: illuminate that
                // pane exactly like the ring's fronted pane (accent edge, veil lifted)
                // so ←/→ browsing clearly marks which pane Space will bring home.
                val browsedShelf = onShelf && p.stashSlot == spikeShelfIndex && cameraAtShelf()
                // Continuous illumination 0..1 instead of a settled/front boolean: full
                // at the front pole, fading linearly over one slot of longitude and one
                // tab of latitude. Derived from the same eased scrolls that position
                // the panes, so the veil/edge lighting below cross-fades in lock-step
                // with the swing — no sudden brightening the moment the scroll settles.
                // Scaled down by stash progress (a shelved pane keeps its ring slot but
                // must not read lit up on the shelf); the shelf's own browse highlight
                // is eased separately below and merged in.
                val ringLit = (1.0 - abs(slot)).coerceIn(0.0, 1.0) *
                    (1.0 - abs(tabRel)).coerceIn(0.0, 1.0) * (1.0 - stashE)
                // The shelf highlight eases at the browse glide's own rate (linear
                // progress through the same smootherstep the stash flight uses), so
                // up at the shelf the light slides between slots with the camera
                // instead of snapping to the new browse cursor.
                val shelfTgt = if (browsedShelf) 1.0 else 0.0
                val shelfStep = 1.0 / SHELF_BROWSE_FRAMES
                p.shelfLit = when {
                    p.shelfLit < shelfTgt -> (p.shelfLit + shelfStep).coerceAtMost(shelfTgt)
                    p.shelfLit > shelfTgt -> (p.shelfLit - shelfStep).coerceAtLeast(shelfTgt)
                    else -> p.shelfLit
                }
                val sl = p.shelfLit
                val lit = max(ringLit, sl * sl * sl * (sl * (sl * 6.0 - 15.0) + 10.0))
                // Pointer input only on the front pane while engaged (click/type) or in
                // selection mode (drag-select) — otherwise a stray click would
                // half-focus a pane you can't type in yet.
                val interactiveFront = isFront && (spikeEngaged || spikeSelectionMode)
                p.wrapper.style.setProperty("pointer-events", if (interactiveFront) "auto" else "none")
                p.dim.style.opacity = (PANE_DIM_OPACITY * (1.0 - lit)).toString()
                val chrome = spikeChromeColors
                val frontCol = chrome?.accent ?: "#3f5f8f"
                val restCol = chrome?.border ?: "#26374e"
                // The edge rides the same continuous litness, blending rest → accent.
                val edgeCol = when {
                    lit >= 0.999 -> frontCol
                    lit <= 0.001 -> restCol
                    else -> {
                        val a = parseCssColor(restCol)
                        val b = parseCssColor(frontCol)
                        if (a != null && b != null) cssRgb(mixRgb(a, b, lit))
                        else if (lit >= 0.5) frontCol else restCol
                    }
                }

                // Working/awaiting visual treatment, driven by the persisted **Status
                // indication** setting ([spikeStatusIndication]). Under REACTOR the
                // reactor *supersedes* the normal signals for this pane — [tickWarpCore]
                // owns its border, outward halo and inner heat (blue charge / amber HOLD),
                // the discharge + pings + HUD painted later by [tickWarpCoreOverlay].
                // Otherwise (GLOW / GLOW_ANIMATION / NONE) the glow-halo + optional dotted
                // border apply, gated by [spikeStatusShowGlow] / [spikeStatusShowDots]:
                // NONE paints neither, so a working/waiting pane shows no status visual.
                // `working` / `waiting` were resolved above (before placement, for the lean).
                if (spikeStatusIndication == StatusIndication.REACTOR) {
                    tickWarpCore(p, i, working, waiting, edgeCol)
                    // The focus / current-target outline is orthogonal to the reactor state.
                    applyTargetOutline(p, isFront, currentTargetId, frontCol)
                    // No travelling dots / green breath veil while the reactor owns the pane.
                    p.border.root.asDynamic().style.opacity = "0"
                    p.glow.style.opacity = "0"
                } else {
                    // The dotted border shows whenever the pane is working — *including*
                    // the engaged/focused pane (it rides the edge, not the content, so it
                    // never disturbs typing). The legacy veil still exempts the engaged
                    // pane below, since that one dims the content itself.
                    val signalling = working
                    // While the dotted-border signal is showing, it *replaces* the solid
                    // border (which goes transparent) and takes the border's own colour —
                    // so the edge simply turns into travelling dots. A *waiting* pane instead
                    // paints a solid red edge (it has no travelling dots — the outward halo
                    // below is its signal). Otherwise the normal solid border colour is used.
                    val dotBorder = WORKING_BORDER_ENABLED && signalling && spikeStatusShowDots
                    val borderCol = when {
                        // A waiting pane's edge is now **amber** (not red) while a glow style
                        // is active; under NONE it carries no special edge (plain edgeCol).
                        waiting && spikeStatusShowGlow -> WARP_AMBER_HEX
                        dotBorder -> "transparent"
                        else -> edgeCol
                    }
                    p.wrapper.style.setProperty("border-color", borderCol)

                    // "Needs input" red halo: an outward box-shadow bloom layered over the
                    // pane's resting depth shadow, pulsing at [waitAlpha]. Because it bleeds
                    // *outside* the pane it stays visible when the pane is small and far
                    // across the ring — spot a pane waiting on you from clear across the
                    // world. Non-waiting panes carry the base depth shadow alone.
                    // A working pane whose style shows the glow paints the same outward bloom
                    // in the reactor's working **blue** ([WORKING_GLOW_COLOR] = [WARP_CORE_COLOR]),
                    // so working reads the same in a glow style as under the reactor; a waiting
                    // pane's amber halo wins if both apply, being the more urgent signal.
                    val workGlowThis = signalling && spikeStatusShowGlow
                    // A waiting pane's halo is **amber** (the reactor's awaiting colour), and —
                    // like the working glow — only shown while a glow style is active, so NONE
                    // leaves the pane with just its base depth shadow.
                    val amberPulseThis = waiting && spikeStatusShowGlow
                    p.wrapper.style.setProperty(
                        "box-shadow",
                        when {
                            amberPulseThis -> "$PANE_BASE_SHADOW, 0 0 ${WAITING_GLOW_BLUR}px ${WAITING_GLOW_SPREAD}px " +
                                "rgba($WARP_AMBER_COLOR,$waitAlpha)"
                            workGlowThis -> "$PANE_BASE_SHADOW, 0 0 ${WORKING_GLOW_BLUR}px ${WORKING_GLOW_SPREAD}px " +
                                "rgba($WORKING_GLOW_COLOR,$workAlpha)"
                            else -> PANE_BASE_SHADOW
                        },
                    )

                    // Focus / current-target outline (thick solid = engaged/selecting,
                    // dashed = the current target pane). Via outline, not border, so the
                    // 1:1 terminal content underneath never shifts.
                    applyTargetOutline(p, isFront, currentTargetId, frontCol)

                    if (WORKING_BORDER_ENABLED) {
                        // Dotted border sits on the pane edge in the border's own colour,
                        // its dash offset scrolled (advanced once per frame below) so the
                        // dots drift slowly around. (root is an SVGElement — style via
                        // asDynamic, not an HTMLElement cast.) Shown unless the working style is
                        // GLOW-only, where the green halo (box-shadow) above is the sole signal.
                        val showDots = signalling && spikeStatusShowDots
                        p.border.root.asDynamic().style.opacity = if (showDots) "1" else "0"
                        if (showDots) {
                            // Always the *accent* colour, not the plain border colour: on a
                            // side pane the border is deliberately dim and the dots would be
                            // hard to see, so we use the theme's emphasis colour (what the
                            // fronted border already uses) — visible on every pane.
                            p.border.path.setAttribute("stroke", frontCol)
                            p.border.path.setAttribute("stroke-dashoffset", spikeBorderDash.toString())
                        }
                        p.glow.style.opacity = "0"
                    } else {
                        // Legacy breath veil — exempts the engaged pane (it dims content).
                        p.glow.style.opacity = if (signalling && !engagedThis) pulse.toString() else "0"
                        p.border.root.asDynamic().style.opacity = "0"
                    }
                }
            }

            // Keep the centred pane last in the paint order so no other plane (a big
            // neighbour, a later tab) can composite over it — the centre always shows.
            spikePanes.getOrNull(fi)?.let { front ->
                val parent = front.wrapper.parentElement
                if (parent != null && parent.lastElementChild !== front.wrapper) parent.appendChild(front.wrapper)
            }

            spikeSettledIndex = if (settled) fi else -1
            // A pane no longer auto-reformats when it settles at the front: that
            // reflow could blank some panes' content on arrival. The user now presses
            // `r` to reformat the front pane on demand. See [reformatFront].
        }

        // Empty-tab placeholder cards: each rides its tab's latitude at the pane pole
        // (theta = 0), grows in / shrinks out with its birth factor, and fades by
        // latitude exactly like a pane so distant tabs recede.
        for (c in spikeEmptyTabs) {
            val tabRel = c.tabOrd - spikeTabScroll
            val phi = tabRel * TAB_ANGLE
            val birthTarget = if (c.dying) 0.0 else 1.0
            c.birth += (birthTarget - c.birth) * (if (c.dying) DESPAWN_EASE else SPAWN_EASE)
            val bs = c.birth.coerceIn(0.0, 1.0)
            c.obj.scale.set(bs, bs, bs)
            var py = -sin(phi) * RING_R
            val pz = cos(phi) * RING_R
            if (spikeBobEnabled) py += sin(spikeBobPhase + c.tabOrd * BOB_STAGGER) * BOB_AMPLITUDE
            c.obj.position.set(0.0, py, pz)
            c.obj.rotation.set(phi, 0.0, 0.0)
            // Same two-regime fade as the panes (see the pane loop above): latitude
            // focus at rest, camera distance/facing once flying.
            val focusFade = edgeFade(tabRel, TAB_FADE_PLATEAU, TAB_FADE_EDGE)
            val fade = if (spikeFlyReveal <= 0.001) focusFade else {
                val dx = 0.0 - fadeCamX
                val dy = py - fadeCamY
                val dz = pz - fadeCamZ
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                val facing = if (dist > 0.0) (dx * fadeFwdX + dy * fadeFwdY + dz * fadeFwdZ) / dist else 1.0
                val front = ((facing + FLY_BEHIND_BAND) / (2.0 * FLY_BEHIND_BAND)).coerceIn(0.0, 1.0)
                val flyFade = edgeFade(dist, FLY_FADE_FULL, FLY_FADE_EDGE) * front
                focusFade + (flyFade - focusFade) * spikeFlyReveal
            }
            c.wrapper.style.setProperty("opacity", fade.toString())
            c.wrapper.style.setProperty("display", if (fade <= 0.01) "none" else "")
        }

        // Death sweep: dispose panes/cards that have finished shrinking out.
        if (spikePanes.any { it.dying && it.birth < SPAWN_GONE_EPS }) {
            spikePanes.filter { it.dying && it.birth < SPAWN_GONE_EPS }.forEach { disposeRingPane(it) }
            spikePanes = spikePanes.filter { !(it.dying && it.birth < SPAWN_GONE_EPS) }.toMutableList()
        }
        if (spikeEmptyTabs.any { it.dying && it.birth < SPAWN_GONE_EPS }) {
            spikeEmptyTabs.filter { it.dying && it.birth < SPAWN_GONE_EPS }.forEach { disposeEmptyCard(it) }
            spikeEmptyTabs = spikeEmptyTabs.filter { !(it.dying && it.birth < SPAWN_GONE_EPS) }.toMutableList()
        }

        if (spikeNeedsInitialLayout) {
            spikeNeedsInitialLayout = false
            postOpenLayout()
        }

        try {
            // Tab bundles: override the transforms of panes belonging to an unlisting /
            // unlisted tab so the whole tab flies as one merged stack to / from the hangar
            // bay (and rests there). Must run BEFORE render — like the wormhole below — so
            // its 3D writes take effect this frame; after the per-pane loop so it overrides
            // the ring-slot placement the loop computed for still-listed bundle panes.
            // Inside the try so a throw here can never break the RAF chain. @see tickBundles
            tickBundles(camera, selectedBundleId)
            // Wormhole spawn (feature-flagged): overrides newborn panes' transforms and
            // spirals the vortex — must run BEFORE render so its 3D writes take effect
            // this frame (unlike the phaser's post-render screen-space pass). Inside the
            // try so a throw here can never break the RAF chain and freeze the world.
            tickWormhole(camera)
            // Freeze-to-canvas: swap the live terminal body of any pane currently flying to /
            // from the stash shelf (a lone pane, or a whole tab bundle including its front
            // sheet) for a one-shot static snapshot, so the moving CSS3D plane re-samples one
            // cached raster instead of re-rasterizing live DOM every frame — a direct relief on
            // the compositor tile budget. Restored to live at rest. Runs after tickBundles /
            // tickWormhole so every pane's flight state is final, and is a single idempotent
            // pass so the render loop and tickBundles can't churn the snapshot. @see tickPaneFreeze
            tickPaneFreeze()
            css.render(scene, camera)
            // Phaser-fire close (feature-flagged): drawn in screen space over the freshly
            // rendered scene, so the camera matrices its projection uses are current.
            // Inside the try so a throw here can never break the RAF chain and freeze the world.
            tickPhaser(camera)
            // Warp-core (runtime-toggled): discharge blooms, thruster plumes, sonar pings
            // and the reactor-load HUD, all in screen space over the rendered scene — so
            // its projection uses current camera matrices, exactly like the phaser pass.
            tickWarpCoreOverlay(camera)
        } catch (t: Throwable) {
            window.asDynamic().console.error("[world3d-spike] frame error", t)
        }

        spikeRaf = window.requestAnimationFrame { frame() }
    }
    // Fresh loop start: clear the frame-delta baseline so the first frame steps by exactly
    // one 60fps-frame instead of a huge delta measured against a stale timestamp.
    spikeLastFrameMs = Double.NaN
    spikeRaf = window.requestAnimationFrame { frame() }
}
