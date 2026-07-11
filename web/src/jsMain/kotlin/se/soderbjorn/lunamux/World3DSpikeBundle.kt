/*
 * Split from World3DSpike.kt — the "tab bundle" feature: unlisting a whole tab into the
 * spaceship as a merged stack of panes, and bringing it back.
 *
 * Where the stash feature ([World3DSpikeStash]) sends a *single* pane up to the dock shelf
 * (a 2D minimize), this feature sends a *whole tab* up to the **same shelf row**, sitting
 * alongside the single stashed panes as one merged stack, and unlists that tab in the 2D
 * world ([TabConfig.isHidden] via [WindowCommand.SetTabHidden]). Opt+V (in command center on
 * the front tab, or in free flight on the aimed pane's tab) triggers it; up at the dock the
 * stashed tabs are browsed among the stashed panes (←/→) and brought back down with Space
 * (the browsed item) or Opt+V (the nearest bundle).
 *
 * The visual is a pile of papers: on stash the whole tab is unlisted at once (so the command
 * center immediately stops enumerating it) and every pane flies to the location of the pane
 * the user selected, collapsing into a stack ordered by the tab's display order (first pane
 * on top / frontmost), each sheet offset slightly behind the one in front
 * ([bundleStackOffset]). Once merged the stack flies as one to its dock slot (reusing the
 * stash flight — the station door route [stashPanePath] and the camera flights
 * [flyStationEnter] / [flyCamTo]) and rests there merged. Unstashing flies the whole merged
 * stack back down before it separates — in original order, cascading — back onto the ring.
 *
 * Two channels, by design: a *pane* stash rides the toolkit `LAYOUT_STATE` `isMinimized`
 * flag ([persistPaneMinimized]); a *tab* unlist rides the server `WindowConfig`'s `isHidden`
 * flag ([WindowCommand.SetTabHidden]). So this file never touches [webSettingsPersister] /
 * `LAYOUT_STATE`; it sends a window command and lets the config broadcast sync every client,
 * reconciled inbound by [syncBundlesFromHidden].
 *
 * A committed bundle's panes are marked off-ring ([RingPane.tabOrd] = −1) so every
 * command-center navigation check — which filters panes by tab ordinal — excludes them
 * automatically, and [tickBundles] (called from [World3DSpikeRender]) owns their transform
 * end-to-end while [reconcileRing]'s death sweep spares them ([RingPane.bundleId]).
 *
 * See World3DSpike.kt for the module overview. Shared imports are carried verbatim; unused
 * ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.three.PerspectiveCamera

/**
 * The lifecycle phase of a [TabBundle] — a small state machine advanced one step per frame
 * by [tickBundles]. Progresses MERGING → FLYING_UP → PARKED on a stash, and
 * FLYING_DOWN → SEPARATING → (removed) on an unstash.
 *
 * @see tickBundles @see stashTab @see unstashTab
 */
internal enum class BundleState {
    /** Panes are collapsing from their ring slots onto the stack at the merge anchor. */
    MERGING,

    /** The merged stack is flying up to (and in through the station door to) its dock slot. */
    FLYING_UP,

    /** The merged stack is at rest on the dock row; the tab is unlisted (`isHidden`). */
    PARKED,

    /** The merged stack is flying back down from the dock to the ring. */
    FLYING_DOWN,

    /** Panes are spreading, in original order, off the stack back onto their ring slots. */
    SEPARATING,
}

/**
 * One tab held as a merged stack on the dock — the tab-level counterpart of an entry in
 * [spikeStashed]. Created by [stashTab], driven each frame by [tickBundles], removed when
 * separation completes.
 *
 * @property id this bundle's unique id, matched by [RingPane.bundleId].
 * @property tabId the unlisted tab. @property tabTitle its title (for the dock label).
 * @property paneIds the bundle's pane ids in stack order (index 0 = front sheet). The
 *   front pane is the one the camera follows on the flight.
 * @property mergeX/mergeY/mergeZ the world **merge anchor** — the position of the pane the
 *   user selected, captured when the bundle formed; the stack collapses onto it, flies from
 *   it to the dock, and (on unstash) flies back to near it before separating.
 * @property startPos each member's world position at the moment the bundle formed — the
 *   fan-in **source** during [BundleState.MERGING], since the committed panes are skipped by
 *   the render loop and so no longer have a loop-computed ring slot to fly out of.
 * @property state the current lifecycle phase.
 * @property mergeProg 0→1 fan-in progress during [BundleState.MERGING] (shared by all sheets).
 * @property flightProg 0 at the ring merge point, 1 at the dock slot — the stack's journey.
 * @property sepFrame elapsed frames into [BundleState.SEPARATING] (drives the per-sheet stagger).
 * @property committed `true` while [tickBundles] owns the panes' transform (MERGING through
 *   PARKED): the render loop skips them ([isParkedBundlePane]) so they fly / rest as a stack.
 *   Cleared on unstash so the loop places them again as they fly down and separate. This is *not*
 *   the same as the tab being unlisted — see [unlisted].
 * @property unlisted `true` once the tab has been formally unlisted ([WindowCommand.SetTabHidden])
 *   and the panes marked off-ring ([RingPane.tabOrd] = −1). Deliberately deferred from stash time
 *   to the PARKED landing ([commitBundleUnlist]): unlisting collapses the tab wheel — the adjacent
 *   tab rotates up into the vacated front latitude — so doing it at take-off sweeps a foreign
 *   pane across the view. Deferred, that re-index happens while the camera is away at the dock.
 * @see tickBundles @see commitBundleUnlist
 */
internal class TabBundle(
    val id: String,
    val tabId: String,
    val tabTitle: String,
    val paneIds: List<String>,
    val mergeX: Double,
    val mergeY: Double,
    val mergeZ: Double,
    val startPos: Map<String, Triple<Double, Double, Double>>,
    var state: BundleState = BundleState.MERGING,
    var mergeProg: Double = 0.0,
    var flightProg: Double = 0.0,
    var sepFrame: Double = 0.0,
    var committed: Boolean = true,
    var unlisted: Boolean = false,
)

/**
 * Every tab currently held as a merged stack on the dock, in insertion order. The tab-level
 * twin of [spikeStashed]; bundles occupy the shelf row *after* the single stashed panes
 * ([bundleShelfSlot]). @see stashTab @see tickBundles
 */
internal val spikeStashedTabs: MutableList<TabBundle> = mutableListOf()

/** Monotonic counter for [TabBundle.id]s (no `Date.now()`/random in this world's loop). */
private var spikeBundleSeq = 0

/**
 * The [TabBundle] with [id], or `null`. @param id the bundle id to look up.
 * @return the bundle, or `null` if it has been removed. @see RingPane.bundleId
 */
internal fun bundleById(id: String?): TabBundle? =
    if (id == null) null else spikeStashedTabs.firstOrNull { it.id == id }

/**
 * Whether [tickBundles] fully owns this pane's transform *and* the render loop must skip it —
 * true for a pane whose bundle is **committed** (the tab is unlisted, so the pane is off-ring
 * with [RingPane.tabOrd] = −1). Panes flying down / separating (re-listed, committed cleared)
 * keep a valid tabOrd, so the loop runs them and [tickBundles] merely overrides the result.
 *
 * @param p the pane to test. @return `true` if the render loop should skip [p].
 * @see World3DSpikeRender @see tickBundles
 */
internal fun isParkedBundlePane(p: RingPane): Boolean =
    p.bundleId != null && bundleById(p.bundleId)?.committed == true

/**
 * Whether a tab-bundle animation is in flight (merging, flying or separating) — the tab-stash
 * hotkey guards on this so a second press can't start a competing journey, as [stashBusy]
 * guards the pane stash. A [BundleState.PARKED] bundle is *not* busy (it can be unstashed).
 *
 * @return `true` if any bundle is mid-animation. @see toggleStashTab
 */
internal fun bundlesBusy(): Boolean =
    spikeStashedTabs.any { it.state != BundleState.PARKED }

/**
 * The dock shelf **slot** of tab bundle [b] — bundles share the single-pane dock row
 * ([stashShelfPos]) and sit *after* every stashed pane, so a bundle's slot is
 * `spikeStashed.size + its index`. This is the front sheet's slot; the rest of the stack
 * fans out behind it ([bundleStackOffset]). @see tickBundles @see dockItemCount
 */
internal fun bundleShelfSlot(b: TabBundle): Int =
    spikeStashed.size + spikeStashedTabs.indexOf(b).coerceAtLeast(0)

/**
 * The total number of items on the dock row — stashed panes plus tab bundles — so the dock
 * browse ([shelfBrowse]) and the nearest-item picks span both. @see nearestDockSlot
 */
internal fun dockItemCount(): Int = spikeStashed.size + spikeStashedTabs.size

/**
 * The per-sheet world offset of the pane at stack position [ord] relative to the bundle's
 * front (topmost) sheet: pushed right (+X), down (−Y) and away from the camera (−Z) by
 * [STACK_OFF_X]/[STACK_OFF_Y]/[STACK_OFF_Z] per step, so the collapsed tab reads as a fanned
 * pile of papers with the first pane on top. The front sheet ([ord] 0) sits at zero offset.
 *
 * @param ord the pane's [RingPane.mergeOrd]. @return its `(dx, dy, dz)` offset from the anchor.
 * @see tickBundles
 */
internal fun bundleStackOffset(ord: Int): Triple<Double, Double, Double> =
    Triple(ord * STACK_OFF_X, -ord * STACK_OFF_Y, -ord * STACK_OFF_Z)

/**
 * The dock slot nearest the camera's x, across **all** dock items (panes and bundles) — where
 * Space unstashes from when the browse cursor is unseeded. @return the nearest slot, or `-1`
 * if the dock is empty. @see unstashBrowsedDockItem
 */
internal fun nearestDockSlot(): Int {
    val n = dockItemCount()
    if (n == 0) return -1
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    var best = 0
    var bestD = Double.MAX_VALUE
    for (slot in 0 until n) {
        val (sx, _, _) = stashShelfPos(slot)
        val d = abs(sx - cx)
        if (d < bestD) { bestD = d; best = slot }
    }
    return best
}

/**
 * The tab bundle nearest the camera's x, or `null` if none are docked — Opt+V up at the dock
 * unstashes this one. @see toggleStashTab
 */
internal fun nearestBundle(): TabBundle? {
    if (spikeStashedTabs.isEmpty()) return null
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    return spikeStashedTabs.minByOrNull { abs(stashShelfPos(bundleShelfSlot(it)).first - cx) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selecting the target and the hotkey dispatchers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The pane whose **tab** Opt+V acts on: in free flight the pane at screen centre
 * ([actionTargetPane]); in command center the settled front pane ([frontIndex]). This is the
 * pane the whole tab merges *onto* (the merge anchor). @return the pane, or `null`.
 * @see toggleStashTab
 */
internal fun stashTabTarget(): RingPane? =
    if (spikeFlyMode) actionTargetPane() else spikePanes.getOrNull(frontIndex())

/**
 * The **Opt+V** handler — the tab counterpart of [toggleStash]. Down at the ring it unlists
 * the selected pane's whole tab ([stashTab]); up at the dock with bundles present it brings
 * the nearest one back down ([unstashTab]); up at the dock with no bundles it flies home. A
 * no-op while a pane-stash flight or tab-bundle animation is already playing.
 *
 * @see buildKeyHandler @see stashTab @see unstashTab
 */
internal fun toggleStashTab() {
    if (stashBusy() || bundlesBusy()) return
    when {
        cameraAtShelf() && spikeStashedTabs.isNotEmpty() -> nearestBundle()?.let { unstashTab(it, moveCamera = true) }
        cameraAtShelf() -> resetCamera()
        else -> stashTabTarget()?.let { stashTab(it) }
    }
}

/**
 * Unstashes the **browsed dock item** — the pane or tab bundle at the dock browse cursor
 * ([spikeShelfIndex], else the nearest slot). Called by [toggleStash] when Space is pressed up
 * at the dock, so one key brings back whatever you've browsed to, be it a single pane or a
 * whole tab. @see shelfBrowse
 */
internal fun unstashBrowsedDockItem() {
    val n = dockItemCount()
    if (n == 0) { resetCamera(); return }
    val slot = if (spikeShelfIndex in 0 until n) spikeShelfIndex else nearestDockSlot()
    if (slot < 0) return
    if (slot < spikeStashed.size) {
        val id = spikeStashed[slot]
        spikePanes.firstOrNull { it.paneId == id }?.let { unstashPane(it) }
    } else {
        spikeStashedTabs.getOrNull(slot - spikeStashed.size)?.let { unstashTab(it, moveCamera = true) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stash: unlist the tab, merge it, then fly it up
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unlists the whole tab of [anchor] onto the dock as a merged stack. Gathers every live pane
 * of the tab (in display order, [toolkitPaneOrder]), tags them into a new [TabBundle],
 * captures the **merge anchor** at [anchor]'s current position and each member's start
 * position, then **immediately** sends [WindowCommand.SetTabHidden] `true` and marks the panes
 * off-ring ([RingPane.tabOrd] = −1) — so the command center stops enumerating the tab the
 * instant you press the key, not when the flight lands. The [RingPane.bundleId] death-sweep
 * exemption keeps the panes alive while [tickBundles] flies and rests the stack.
 *
 * @param anchor the selected pane whose tab is unlisted; the stack merges onto its position.
 * @see toggleStashTab @see unstashTab @see tickBundles
 */
internal fun stashTab(anchor: RingPane) {
    val tabId = anchor.tabId
    if (spikeStashedTabs.any { it.tabId == tabId }) return // already bundled
    val order = toolkitPaneOrder(tabId)
    val members = spikePanes
        .filter { !it.dying && it.tabId == tabId && it.bundleId == null }
        .sortedWith(
            compareBy(
                // Smallest pane first → [RingPane.mergeOrd] 0 → fronts the docked stack, nearest
                // the camera (offset 0; the rest step back in −Z). With the small pane in front the
                // larger sheets behind it extend past its edges, so the pile reads as *several*
                // windows instead of one big pane hiding the rest. Size is the pane's **docked**
                // half-height — normalized side scale × persisted zoom, NOT its transient
                // command-center front-zoom — so ordering matches how big each sheet rests at the
                // dock. Ties fall back to sidebar display order, then pane ordinal.
                { p -> (p.baseCh + TITLE_H) * p.normScale * (spikeZoomByPane[p.paneId] ?: 1.0) },
                { p -> order.indexOf(p.paneId).let { if (it < 0) Int.MAX_VALUE else it } },
                { it.paneOrdInTab },
            ),
        )
    if (members.isEmpty()) {
        // An empty tab (only a ghost card): nothing to merge — just unlist it in the 2D world.
        runCatching { launchCmd(WindowCommand.SetTabHidden(tabId = tabId, hidden = true)) }
        return
    }

    leaveFrontPane() // disengage / exit selection before the tab leaves the ring

    val id = "bundle-${spikeBundleSeq++}"
    val startPos = mutableMapOf<String, Triple<Double, Double, Double>>()
    members.forEachIndexed { ord, p ->
        p.bundleId = id
        p.mergeOrd = ord
        p.mergeProg = 0.0
        startPos[p.paneId] = Triple(
            p.obj.position.x as Double, p.obj.position.y as Double, p.obj.position.z as Double,
        )
        // NB: the tab is *not* unlisted here and the panes keep their real [RingPane.tabOrd] —
        // both are deferred to the PARKED landing ([commitBundleUnlist]). Leaving the tab listed
        // holds every *other* tab's panes at their current latitude while the stack merges and
        // flies, so nothing sweeps up into the vacated front slot in front of the camera. The
        // committed bundle flag already takes these panes off the render loop ([isParkedBundlePane]).
        // A member that happened to be individually docked leaves the pane shelf — it now
        // travels as part of the tab stack, not as a lone shelved pane.
        spikeStashed.remove(p.paneId)
    }
    spikeStashedTabs.add(
        TabBundle(
            id = id,
            tabId = tabId,
            tabTitle = anchor.tabTitle,
            paneIds = members.map { it.paneId },
            mergeX = anchor.obj.position.x as Double,
            mergeY = anchor.obj.position.y as Double,
            mergeZ = anchor.obj.position.z as Double,
            startPos = startPos,
        ),
    )
    showNavLabelForBundle(spikeStashedTabs.last())
    spikeSettledIndex = -1
    // The camera flight is armed when the merge completes (in [tickBundles]); the camera holds
    // while the panes gather. The tab is formally unlisted only once the stack parks
    // ([commitBundleUnlist]), so the ring re-index stays off-screen at the dock.
}

/**
 * Formally unlists a bundle's tab **once its stack has parked at the dock** — the deferred second
 * half of [stashTab], run by [tickBundles] on the FLYING_UP → PARKED landing. Marks the panes
 * off-ring ([RingPane.tabOrd] = −1, so command-center navigation excludes them), sends
 * [WindowCommand.SetTabHidden] `true` (the 2D world drops the tab; the config broadcast reconciles
 * every client, our bundle surviving via the [RingPane.bundleId] death-sweep exemption), and lands
 * the command-center selection on a tab that still has ring panes. All of this collapses the tab
 * wheel — the neighbouring tab rotates up into the vacated front latitude — which is exactly the
 * distracting sweep [stashTab] avoids by deferring it to *now*, when the camera is up at the dock
 * and the ring is out of frame. Idempotent via [TabBundle.unlisted]. @see stashTab @see tickBundles
 *
 * @param b the bundle whose tab to unlist.
 */
private fun commitBundleUnlist(b: TabBundle) {
    if (b.unlisted) return
    b.unlisted = true
    for (p in spikePanes) if (p.bundleId == b.id) p.tabOrd = -1
    runCatching { launchCmd(WindowCommand.SetTabHidden(tabId = b.tabId, hidden = true)) }
    spikePanes.firstOrNull { !it.dying && it.bundleId == null && it.tabOrd >= 0 }?.let {
        if (it.tabOrd in spikeTabSel.indices) spikeTabIndex = it.tabOrd
    }
}

/**
 * Arms the camera flight that follows a just-merged stack up to its dock slot — called by
 * [tickBundles] on the MERGING → FLYING_UP transition. Reuses the **exact** pane-stash
 * flights: the [StashChase] chase cam ([armStashChase]) when the station is built — it trails
 * the front sheet's live position with a smooth trailing offset (and so never swings its aim
 * as the stack passes the camera's view axis inside the hull, the way a scripted
 * look-at-the-pane flight does) — else a scripted arc ([flyCamTo]) up to the shelf pose. The
 * chase reads the front sheet's [RingPane.stashProg], which [tickBundles] keeps equal to
 * [TabBundle.flightProg].
 *
 * @param b the bundle whose stack is now flying up. @see tickBundles
 */
private fun armBundleFlightUp(b: TabBundle) {
    val followId = b.paneIds.first()
    if (stationBuilt()) {
        armStashChase(followId, outbound = true)
    } else {
        val front = spikePanes.firstOrNull { it.paneId == followId }
        val halfH = front?.let { paneShelfHalfH(it) } ?: 0.0
        val pose = shelfArrivalPose(bundleShelfSlot(b), halfH)
        flyCamTo(
            pose.cx, pose.cy, pose.cz,
            pose.lx, pose.ly, pose.lz,
            landPristine = false, frames = STASH_CAM_FRAMES,
            pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
            followPaneId = followId,
            sway = STASH_CAM_SWAY, roll = STASH_CAM_ROLL,
            endLook = Triple(pose.lx, pose.ly, pose.lz),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unstash: fly the stack down, then separate it
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Brings a parked tab bundle back down and re-lists its tab. Sends
 * [WindowCommand.SetTabHidden] `false` immediately (so the tab is listed again and the panes
 * re-enter the config while still owned by [tickBundles]), clears the committed flag (the
 * render loop starts placing the panes again, so separation can ease them onto live ring
 * slots), and flips the bundle to [BundleState.FLYING_DOWN].
 *
 * @param b the parked bundle to bring back.
 * @param moveCamera `true` for a user press (fly the camera home following the stack); `false`
 *   for an inbound sync ([syncBundlesFromHidden]) where the tab was re-listed elsewhere and
 *   the local camera should not be hijacked.
 * @see toggleStashTab @see tickBundles @see syncBundlesFromHidden
 */
internal fun unstashTab(b: TabBundle, moveCamera: Boolean) {
    if (b.state != BundleState.PARKED) return
    runCatching { launchCmd(WindowCommand.SetTabHidden(tabId = b.tabId, hidden = false)) }
    b.committed = false // re-listed: the loop may run these panes and compute their ring slots
    b.state = BundleState.FLYING_DOWN
    if (moveCamera) {
        val followId = b.paneIds.first()
        if (stationBuilt()) {
            // The same chase cam a single unstash uses — it trails the front sheet home the
            // whole way (and eases onto the pristine view as it lands), so the stack is never
            // lost from frame. @see tickStashChase
            armStashChase(followId, outbound = false)
        } else {
            val homeZ = RING_R + perspDistance(window.innerHeight)
            val front = spikePanes.firstOrNull { it.paneId == followId }
            val lx = front?.obj?.position?.x as? Double ?: 0.0
            val ly = front?.obj?.position?.y as? Double ?: 0.0
            val lz = front?.obj?.position?.z as? Double ?: 0.0
            flyCamTo(
                0.0, 0.0, homeZ, lx, ly, lz,
                landPristine = true, frames = STASH_CAM_FRAMES,
                pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
                sway = -STASH_CAM_SWAY, roll = -STASH_CAM_ROLL,
                followPaneId = followId, endLook = Triple(0.0, 0.0, 0.0),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-frame animation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Advances every [TabBundle] one frame and writes its panes' world transforms — the tab-bundle
 * counterpart of the render loop's per-pane stash placement. Called from [World3DSpikeRender]
 * after the per-pane loop (so it overrides the ring-slot placement of re-listed bundle panes)
 * and before the scene renders. Runs the MERGING/FLYING/PARKED/SEPARATING state machine, arms
 * the flight on merge completion, and on separation completion hands the panes back to the ring.
 *
 * @param camera the scene camera (unused today; taken for parity with [tickWormhole]).
 * @param selectedBundleId the id of the tab bundle currently **browsed at the dock**
 *   ([World3DSpikeRender] resolves it from the dock cursor), or `null`. Its every sheet is
 *   ringed with the dashed target outline so the whole pack reads as the selection; all
 *   other bundles' sheets have the outline cleared. A lone stashed pane's outline is handled
 *   by the render loop's [applyTargetOutline]; a parked bundle's panes are skipped there
 *   ([isParkedBundlePane]), so their outline is driven here instead.
 * @see stashTab @see unstashTab @see applyTargetOutline
 */
@Suppress("UNUSED_PARAMETER")
internal fun tickBundles(camera: PerspectiveCamera, selectedBundleId: String? = null) {
    if (spikeStashedTabs.isEmpty()) return
    val mergeStep = 1.0 / BUNDLE_MERGE_FRAMES * spikeDtFrames
    val flightStep = 1.0 / STASH_CAM_FRAMES * spikeDtFrames
    // The dashed target-outline accent, matching the render loop's [applyTargetOutline].
    val selectAccent = spikeChromeColors?.accent ?: "#3f5f8f"
    val done = mutableListOf<TabBundle>()

    for (b in spikeStashedTabs) {
        // --- Advance the state machine. ---
        when (b.state) {
            BundleState.MERGING -> {
                b.mergeProg = (b.mergeProg + mergeStep).coerceAtMost(1.0)
                if (b.mergeProg >= 1.0) {
                    b.state = BundleState.FLYING_UP
                    b.flightProg = 0.0
                    armBundleFlightUp(b)
                }
            }
            BundleState.FLYING_UP -> b.flightProg = (b.flightProg + flightStep).coerceAtMost(1.0).also {
                if (it >= 1.0) {
                    b.state = BundleState.PARKED
                    commitBundleUnlist(b) // now parked at the dock — safe to collapse the ring wheel
                }
            }
            BundleState.PARKED -> { b.flightProg = 1.0; b.mergeProg = 1.0; commitBundleUnlist(b) }
            BundleState.FLYING_DOWN -> {
                // Point the ring wheel at the returning tab the instant it has a slot again (the
                // re-list [unstashTab] fired at the dock has reconciled and given the front pane a
                // real tabOrd) — while the camera is still high on its way down — so the wheel is
                // settled on the returning tab by touchdown and its panes separate right where the
                // camera lands, instead of the wheel rotating (and a neighbour tab sweeping) across
                // the view at the command-center landing. @see unstashTab @see commitBundleUnlist
                spikePanes.firstOrNull { it.paneId == b.paneIds.first() }?.let { front ->
                    if (front.tabOrd in spikeTabSel.indices && spikeTabIndex != front.tabOrd) {
                        spikeTabIndex = front.tabOrd
                        spikeTabSel[front.tabOrd] = front.paneOrdInTab
                    }
                }
                b.flightProg = (b.flightProg - flightStep).coerceAtLeast(0.0)
                if (b.flightProg <= 0.0) { b.state = BundleState.SEPARATING; b.sepFrame = 0.0 }
            }
            BundleState.SEPARATING -> b.sepFrame += spikeDtFrames
        }

        // Keep the front sheet's stashProg equal to the flight progress: the chase cam
        // ([tickStashChase], armed by [armBundleFlightUp] / [unstashTab] when the station is
        // built) reads it for its zoom-out hump *and* its arrival detection (done at sp≈1
        // outbound, sp≈0 inbound). Without this the committed sheet's stashProg stays 0 (the
        // render loop skips it), the chase never registers as arrived, and the camera stays
        // locked trailing the parked stack. @see tickStashChase
        spikePanes.firstOrNull { it.paneId == b.paneIds.first() }?.stashProg = b.flightProg

        // --- The stack anchor this frame (world position the front sheet sits at). ---
        val (bxx, byy, bzz) = stashShelfPos(bundleShelfSlot(b))
        val anchor: Triple<Double, Double, Double> = when (b.state) {
            BundleState.PARKED -> Triple(bxx, byy, bzz)
            BundleState.FLYING_UP, BundleState.FLYING_DOWN ->
                stashPanePath(b.mergeX, b.mergeY, b.mergeZ, bxx, byy, bzz, b.flightProg)
            else -> Triple(b.mergeX, b.mergeY, b.mergeZ) // MERGING, SEPARATING: near the ring anchor
        }

        var allSeparated = true
        for (p in spikePanes) {
            if (p.bundleId != b.id) continue
            // Per-sheet merge progress: uniform while merging / flying / parked; a staggered
            // fan-out (papers spreading in sequence) while separating.
            val mp = when (b.state) {
                BundleState.MERGING -> b.mergeProg
                BundleState.FLYING_UP, BundleState.PARKED, BundleState.FLYING_DOWN -> 1.0
                BundleState.SEPARATING -> {
                    val t = ((b.sepFrame - p.mergeOrd * BUNDLE_SEP_STAGGER) / BUNDLE_SEP_FRAMES).coerceIn(0.0, 1.0)
                    1.0 - t * t * t * (t * (t * 6.0 - 15.0) + 10.0) // 1 (stacked) → 0 (on the ring)
                }
            }
            p.mergeProg = mp
            if (mp > 0.001) allSeparated = false

            // Rigid-body rotation of the whole stack over the flight, composed from two axes:
            //  • a fast **reveal spin** about Y ([BUNDLE_FLIGHT_SPIN], a whole number of turns so
            //    it lands yaw-flat) that makes the fanned pile visibly spin as it travels — a lone
            //    pane spins with nothing to show, a real stack fans out; and
            //  • a **dock pitch** about X ([BUNDLE_DOCK_PITCH]) the stack eases into so it parks
            //    tilted ~45° back toward the camera, its stepped sheets fanning into view at rest.
            // Both are 0 at take-off / ring landing (flat, head-on) and full at the dock end, so
            // only the journey spins and only the parked stack is tilted. The per-sheet offset is
            // rotated the same way (yaw then pitch) and each sheet is yawed/pitched to match, so
            // the pile turns and tilts as one rigid body. @see BUNDLE_FLIGHT_SPIN @see BUNDLE_DOCK_PITCH
            // Constant-rate (linear in flightProg) rotation: strain from rotating the big CSS3D
            // sheets scales with angular speed, and the stutter was worst mid-flight, so this is
            // kept to a single slow turn ([BUNDLE_FLIGHT_SPIN] = 2π — the slowest that still lands
            // yaw-flat) rather than eased, which would only *peak* the speed mid-flight.
            val spinY = when (b.state) {
                BundleState.FLYING_UP, BundleState.FLYING_DOWN -> BUNDLE_FLIGHT_SPIN * b.flightProg
                else -> 0.0 // PARKED: whole turns ≡ 0 (yaw-flat); MERGING/SEPARATING: flat
            }
            val pitchX = when (b.state) {
                BundleState.FLYING_UP, BundleState.FLYING_DOWN -> BUNDLE_DOCK_PITCH * b.flightProg
                BundleState.PARKED -> BUNDLE_DOCK_PITCH
                else -> 0.0
            }
            val cy = cos(spinY)
            val sy = sin(spinY)
            val cx = cos(pitchX)
            val sx = sin(pitchX)
            val (rawOx, rawOy, rawOz) = bundleStackOffset(p.mergeOrd)
            // Yaw about Y, then pitch about X — the same order the sheet's own rotation is set in.
            val yx = rawOx * cy + rawOz * sy
            val yz = -rawOx * sy + rawOz * cy
            val ox = yx
            val oy = rawOy * cx - yz * sx
            val oz = rawOy * sx + yz * cx
            var ty = anchor.second + oy
            val tx = anchor.first + ox
            val tz = anchor.third + oz
            if (spikeBobEnabled && b.state == BundleState.PARKED) {
                ty += sin(spikeBobPhase + p.mergeOrd * BOB_STAGGER) * BOB_AMPLITUDE
            }
            // The lerp **source** is where the pane flies out of / back to: the captured start
            // during MERGING (the committed pane has no loop-computed slot), else its live
            // position (the loop places the re-listed pane on its ring slot for SEPARATING).
            val src = if (b.state == BundleState.MERGING) {
                b.startPos[p.paneId] ?: Triple(p.obj.position.x as Double, p.obj.position.y as Double, p.obj.position.z as Double)
            } else {
                Triple(p.obj.position.x as Double, p.obj.position.y as Double, p.obj.position.z as Double)
            }
            p.obj.position.set(
                src.first + (tx - src.first) * mp,
                src.second + (ty - src.second) * mp,
                src.third + (tz - src.third) * mp,
            )
            // Flatten to face the viewer as it merges (undo the sphere tilt/yaw); restore on
            // separate. During flight the residual sphere rotation is gone (mp≈1), so the sheet's
            // rotation is just the stack's rigid reveal-spin (`spinY`) + dock-pitch (`pitchX`) —
            // each sheet turns and tilts with the fan so the whole pile moves as one body rather
            // than the offsets sliding past static sheets. @see BUNDLE_FLIGHT_SPIN @see BUNDLE_DOCK_PITCH
            val rrx = (p.obj.rotation.x as Double) * (1.0 - mp) + pitchX
            val rry = (p.obj.rotation.y as Double) * (1.0 - mp) + spinY
            p.obj.rotation.set(rrx, rry, 0.0)
            // A bundle sheet is the subject — keep it fully visible off the ring where the loop's
            // focus/fly fade would hide it; fade back to the loop's opacity as it rejoins.
            val cur = runCatching { p.wrapper.style.opacity.toDouble() }.getOrNull() ?: 1.0
            var vis = (mp + (1.0 - mp) * cur).coerceIn(0.0, 1.0)
            // Transit fade: while fanning in / out, a sheet sweeping from a far ring slot to the
            // stack (or back) crosses in *screen* space over the pane you're looking at — same
            // depth, so a proximity test can't catch it, and the stack front is the display-order
            // head, not necessarily that pane, so an order test can't either. Fade by remaining
            // distance to this frame's target: the pane that starts at the merge point (the
            // anchor) barely moves and stays solid, while every far sheet stays faint until it
            // seats — nothing slams across the view at take-off or landing. @see BUNDLE_MERGE_FADE_DIST
            if (b.state == BundleState.MERGING || b.state == BundleState.SEPARATING) {
                // Distance to the *nearer* of the sheet's two endpoints (its start and its rest):
                // |target−src|·min(mp, 1−mp) is 0 at both ends and peaks mid-cross, so the sheet is
                // solid at its ring slot and solid on the stack, faint only while sweeping between —
                // symmetric for fan-in and fan-out, and continuous (no blink) at either end.
                val edge = minOf(mp, 1.0 - mp)
                val remx = (tx - src.first) * edge
                val remy = (ty - src.second) * edge
                val remz = (tz - src.third) * edge
                val remain = sqrt(remx * remx + remy * remy + remz * remz)
                vis *= (1.0 - remain / BUNDLE_MERGE_FADE_DIST).coerceIn(0.0, 1.0)
            }
            p.wrapper.style.setProperty("opacity", vis.toString())
            if (vis > 0.01) p.wrapper.style.setProperty("display", "")
            // The loop skipped a committed sheet, so drive its scale here.
            if (isParkedBundlePane(p)) {
                val s = p.birth * p.lscale
                p.obj.scale.set(s, s, s)
            }
            // Dock **selection outline**: when this bundle is the browsed dock item, ring
            // *every* sheet with the same dashed target outline a single stashed pane wears
            // ([applyTargetOutline]) — so the selection reads as the whole pack, not one pane.
            // Cleared on every other bundle's sheets. Committed sheets are skipped by the
            // render loop, so their outline can only be set (and cleared) from here.
            if (b.id == selectedBundleId) {
                p.wrapper.style.setProperty("outline", "2px dashed $selectAccent")
                p.wrapper.style.setProperty("outline-offset", "6px")
            } else {
                p.wrapper.style.setProperty("outline", "none")
                p.wrapper.style.setProperty("outline-offset", "0px")
            }
        }

        if (b.state == BundleState.SEPARATING && allSeparated && b.sepFrame > 1.0) {
            for (p in spikePanes) {
                if (p.bundleId == b.id) { p.bundleId = null; p.mergeProg = 0.0; p.mergeOrd = 0 }
            }
            done.add(b)
        }
    }

    if (done.isNotEmpty()) {
        spikeStashedTabs.removeAll(done)
        done.firstOrNull()?.let { b ->
            val landed = spikePanes.firstOrNull { !it.dying && it.paneId == b.paneIds.first() }
            if (landed != null && landed.tabOrd in spikeTabSel.indices) {
                spikeTabIndex = landed.tabOrd
                spikeTabSel[landed.tabOrd] = landed.paneOrdInTab
                spikeSettledIndex = -1
            }
        }
        showNavLabel()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dock label + inbound sync
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Flashes a tab bundle's name in the big top-left label — the dock counterpart of
 * [showNavLabelFor] for a whole stashed tab: the tab title, with a "N windows" subtitle so a
 * merged stack is clearly named (and clearly a *tab*, not a single pane) while you stash it or
 * browse to it at the dock. @param b the bundle to name. @see stashTab @see shelfBrowse
 */
internal fun showNavLabelForBundle(b: TabBundle) {
    val label = spikeNavLabel ?: return
    while (label.firstChild != null) label.removeChild(label.firstChild!!)
    val big = document.createElement("div") as HTMLElement
    big.textContent = b.tabTitle.ifBlank { "Tab" }
    big.style.cssText = "font:700 26px ui-monospace,Menlo,monospace;color:#eef3fb;" +
        "letter-spacing:0.5px;text-shadow:0 2px 20px rgba(0,0,0,0.9);"
    label.appendChild(big)
    val small = document.createElement("div") as HTMLElement
    val n = b.paneIds.size
    small.textContent = "unlisted tab · $n window${if (n == 1) "" else "s"}"
    small.style.cssText = "margin-top:6px;font:600 15px ui-monospace,Menlo,monospace;" +
        "color:#9fb2d0;text-shadow:0 1px 12px rgba(0,0,0,0.85);"
    label.appendChild(small)
    spikeNavLabelTimer?.let { window.clearTimeout(it) }
    label.style.transition = "opacity 150ms ease"
    label.style.opacity = "1"
    spikeNavLabelTimer = window.setTimeout({
        label.style.transition = "opacity 800ms ease"
        label.style.opacity = "0"
    }, 2200)
}

/**
 * Reconciles held tab bundles with the live [WindowConfig] — the inbound half of the
 * unlist ⇄ `isHidden` bridge, run on every config broadcast while the world is open. A parked
 * bundle whose tab has been **re-listed** elsewhere (it is back in [collectTabs]) is brought
 * down and separated without hijacking the local camera. Echoes of this world's own
 * [unstashTab] arrive already-matching (the bundle is no longer PARKED) and no-op.
 *
 * Note: a tab **unlisted** from the 2D UI or another client (a new `isHidden` tab we hold no
 * bundle for) is not materialized onto the dock here — its panes simply drop from the ring as
 * they always have ([collectPaneSpecs] filters hidden tabs). Only locally-initiated stashes
 * show the flying-stack visual; surfacing externally-unlisted tabs is a follow-up (it needs
 * the ring to build panes for hidden tabs). @see syncStashFromMinimized @see unstashTab
 */
internal fun syncBundlesFromHidden() {
    if (spikeStashedTabs.isEmpty()) return
    val listed = collectTabs().mapTo(mutableSetOf()) { it.first }
    spikeStashedTabs
        .filter { it.state == BundleState.PARKED && it.tabId in listed }
        .forEach { unstashTab(it, moveCamera = false) }
}
