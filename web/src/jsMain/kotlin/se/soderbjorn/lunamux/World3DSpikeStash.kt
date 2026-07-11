/*
 * Split from World3DSpike.kt — the "stash" feature: sending panes up to a floating
 * shelf and bringing them back, each a cinematic camera journey.
 *
 * A pane you press Space on in navigate mode is **stashed**: added to [spikeStashed]
 * and flown up to the [stashShelfPos] of its slot on a shelf high above the sphere,
 * while the camera swoops up ([flyCamTo]) to frame the flight. Once the camera is up at
 * the shelf ([cameraAtShelf]), Space instead **unstashes** the shelved pane nearest the
 * camera — it sails back down to its original ring slot and the camera follows it home.
 * The stash toggle is camera-proximity-contextual so one key does both. While up at the
 * shelf, ←/→ **browse** the row ([shelfBrowse]): the camera glides flat from slot to
 * slot — the shelf counterpart of rotating the ring — so "nearest the camera" is always
 * the pane you parked at, no free-flying required (though pointing by free-fly works too).
 *
 * The actual pane motion is not driven here: [World3DSpikeRender]'s per-frame loop
 * lerps every pane between its ring slot and its shelf slot by its [RingPane.stashProg],
 * which eases toward 1 while the pane is in [spikeStashed] and toward 0 otherwise. This
 * file only mutates the stash *state*; the render loop animates it.
 *
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
 * The current toolkit `LAYOUT_STATE` blob as a JSON string, or `null` when none is
 * available. Prefers the mounted 2D shell's live state
 * ([AppShellHandle.currentLayoutStateJson] — includes gestures not yet echoed by the
 * server) and falls back to the last server broadcast
 * ([se.soderbjorn.lunamux.client.WindowStateRepository.rawLayoutState], which may
 * arrive as a [JsonObject] or a JSON-string [JsonPrimitive] — same duality
 * [toolkitPaneOrder] handles).
 *
 * @return the blob JSON, or `null` if the shell isn't mounted and no broadcast has
 *   arrived. @see minimizedPaneIds @see persistPaneMinimized
 */
private fun layoutStateJson(): String? {
    appShellHandle?.let { h -> runCatching { h.currentLayoutStateJson() }.getOrNull()?.let { return it } }
    val raw = runCatching { lunamuxClient.windowState.rawLayoutState.value }.getOrNull() ?: return null
    return when {
        raw is JsonObject -> raw.toString()
        raw is JsonPrimitive && raw.isString -> raw.content
        else -> null
    }
}

/**
 * The ids of every pane currently **minimized (docked)** in the 2D layout, read from
 * the toolkit-owned `LAYOUT_STATE` blob (`geometryByTab.{tabId}.{paneId}.isMinimized`)
 * — the same per-pane flag the 2D dock and the mobile client's dock row are driven by.
 * This is the persisted twin of [spikeStashed]: [openWorld3dSpike] seeds the shelf
 * from it and [syncStashFromMinimized] keeps the two reconciled while the world is
 * open.
 *
 * @return the minimized pane ids, or an empty set when no layout state exists (a
 *   fresh install) or the blob doesn't parse.
 * @see persistPaneMinimized
 */
internal fun minimizedPaneIds(): Set<String> {
    val raw = layoutStateJson() ?: return emptySet()
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptySet()
    val geomByTab = obj["geometryByTab"] as? JsonObject ?: return emptySet()
    val out = mutableSetOf<String>()
    for ((_, tabGeom) in geomByTab) {
        val tg = tabGeom as? JsonObject ?: continue
        for ((paneId, geom) in tg) {
            val minimized = ((geom as? JsonObject)?.get("isMinimized") as? JsonPrimitive)?.content
            if (minimized == "true") out.add(paneId)
        }
    }
    return out
}

/**
 * Writes a stash/unstash through to the 2D world: sets `isMinimized` on [paneId]'s
 * entry in the toolkit `LAYOUT_STATE` blob, applies the new blob to the mounted 2D
 * shell (live — the pane docks/restores immediately, no reload) and persists it via
 * [webSettingsPersister] (the server stores it with the other UI settings and
 * broadcasts it to every client). This is what makes a 3D stash *be* a 2D minimize —
 * and what makes the shelf survive app restarts, since [openWorld3dSpike] re-seeds
 * [spikeStashed] from the persisted flags.
 *
 * Minimizing also clears `isMaximized`, mirroring the mobile client's `setMinimized`
 * (a docked pane can't meaningfully stay fullscreen). A pane the toolkit has no
 * geometry entry for yet (created this tick) is skipped with a console warning — the
 * stash still works visually for the session, it just isn't persisted.
 *
 * @param paneId the pane being stashed or unstashed.
 * @param minimized `true` on stash (dock it), `false` on unstash (restore it).
 * @see stashFront @see unstashNearest @see syncStashFromMinimized
 */
internal fun persistPaneMinimized(paneId: String, minimized: Boolean) {
    val raw = layoutStateJson()
    val obj = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    val geomByTab = obj?.get("geometryByTab") as? JsonObject
    val tabId = geomByTab?.entries?.firstOrNull { (_, tg) ->
        (tg as? JsonObject)?.containsKey(paneId) == true
    }?.key
    val tabGeom = tabId?.let { geomByTab[it] as? JsonObject }
    val paneGeom = tabGeom?.get(paneId) as? JsonObject
    if (obj == null || geomByTab == null || tabId == null || tabGeom == null || paneGeom == null) {
        console.warn("[world3d-spike] stash not persisted: no LAYOUT_STATE geometry for pane $paneId")
        return
    }
    var newPaneGeom = paneGeom + ("isMinimized" to JsonPrimitive(minimized))
    if (minimized) newPaneGeom = newPaneGeom + ("isMaximized" to JsonPrimitive(false))
    val newBlob = JsonObject(
        obj + ("geometryByTab" to JsonObject(
            geomByTab + (tabId to JsonObject(tabGeom + (paneId to JsonObject(newPaneGeom))))
        ))
    ).toString()
    // Live-apply to the mounted 2D shell first (instant dock/restore — the adopt
    // path suppresses the toolkit's own persistence, so no competing write races
    // ours), then persist what the shell actually adopted: [applyExternalLayoutState]
    // may repair the blob against the live snapshot (reconcileAdoptedLayoutState),
    // and persisting the read-back keeps the server copy identical to the shell's
    // truth. The server broadcast then echoes back through both the shell's
    // LAYOUT_STATE collector and [syncStashFromMinimized]; both no-op on a match.
    runCatching { appShellHandle?.applyExternalLayoutState(newBlob) }
    val persistBlob = appShellHandle?.let { runCatching { it.currentLayoutStateJson() }.getOrNull() } ?: newBlob
    GlobalScope.launch {
        runCatching {
            webSettingsPersister.putSetting(se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE, persistBlob)
        }
    }
}

/**
 * Writes a new within-tab pane display order through to the 2D world: replaces
 * `paneOrderByTab[tabId]` in the toolkit `LAYOUT_STATE` blob with [order], applies
 * the new blob to the mounted 2D shell (the sidebar reorders live) and persists it
 * via [webSettingsPersister] — the same live-apply + persist path as
 * [persistPaneMinimized].
 *
 * Called by [movePaneSlot] (⇧←/⇧→) alongside its [WindowCommand.MovePaneWithinTab]
 * dispatch, because the ring sorts a tab's panes by this blob entry
 * ([toolkitPaneOrder]), **not** by the config's `tab.panes` list the command
 * reorders: without this write the next `LAYOUT_STATE`/config broadcast would
 * re-sort the ring straight back and the move would visibly do nothing.
 *
 * A blob that merely lacks the `paneOrderByTab` key gets it added (the key only
 * appears after the first sidebar drag); but with **no blob at all** the write is
 * skipped with a console warning — persisting a from-scratch blob holding only the
 * order could clobber a server-side blob whose broadcast simply hasn't arrived yet,
 * wiping every tab's geometry. Skipping is safe: with no blob the ring falls back
 * to config order, which the accompanying [WindowCommand.MovePaneWithinTab]
 * already reorders.
 *
 * @param tabId the tab whose pane order changed.
 * @param order the tab's pane ids in their new display order.
 * @see toolkitPaneOrder @see movePaneSlot
 */
internal fun persistPaneOrder(tabId: String, order: List<String>) {
    if (tabId.isEmpty()) return
    val obj = layoutStateJson()
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    if (obj == null) {
        console.warn("[world3d-spike] pane order not persisted: no LAYOUT_STATE blob yet")
        return
    }
    val orderByTab = obj["paneOrderByTab"] as? JsonObject ?: JsonObject(emptyMap())
    val newBlob = JsonObject(
        obj + ("paneOrderByTab" to JsonObject(
            orderByTab + (tabId to JsonArray(order.map { JsonPrimitive(it) }))
        ))
    ).toString()
    runCatching { appShellHandle?.applyExternalLayoutState(newBlob) }
    val persistBlob = appShellHandle?.let { runCatching { it.currentLayoutStateJson() }.getOrNull() } ?: newBlob
    GlobalScope.launch {
        runCatching {
            webSettingsPersister.putSetting(se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE, persistBlob)
        }
    }
}

/**
 * Nudges every pane queued in [spikePinLastPanes] to the **end** of its tab's display
 * order, so a pane created with `n` ([createPane]) stays at the tail of the row rather
 * than sliding in beside the current pane once the toolkit orders it. Called from
 * [reconcileRing] and the `LAYOUT_STATE` collector ([spikeLayoutJob]) — i.e. whenever
 * either the config or the toolkit order changes.
 *
 * Per pinned id, this is a **one-shot**: the id is dropped from the set the moment it is
 * resolved, so this can't fight a later manual sidebar drag. Resolution cases:
 * - pane gone (closed / never built) → drop, nothing to do;
 * - no toolkit blob yet, or the pane not in it → leave pinned and wait (the ring's
 *   tail fallback already renders it last meanwhile);
 * - already the last id in its tab's order → drop, no rewrite;
 * - otherwise → move it to the end via [persistPaneOrder] and drop it.
 *
 * @see spikePinLastPanes @see createPane @see persistPaneOrder @see toolkitPaneOrder
 */
internal fun pinNewPanesLast() {
    if (spikePinLastPanes.isEmpty()) return
    val it = spikePinLastPanes.iterator()
    while (it.hasNext()) {
        val pid = it.next()
        val pane = spikePanes.firstOrNull { p -> !p.dying && p.paneId == pid }
        if (pane == null) { it.remove(); continue } // closed or not on the ring — give up
        val order = toolkitPaneOrder(pane.tabId)
        if (order.isEmpty() || pid !in order) continue // toolkit hasn't ordered it yet — wait
        if (order.last() == pid) { it.remove(); continue } // already last — nothing to do
        persistPaneOrder(pane.tabId, order.filterNot { id -> id == pid } + pid)
        it.remove()
    }
}

/**
 * Reconciles [spikeStashed] with the minimized (docked) panes in the toolkit layout
 * state — the live inbound half of the stash ⇄ minimize bridge, run on every
 * `LAYOUT_STATE` broadcast while the world is open ([spikeLayoutJob]). A pane docked
 * from the 2D app (or another client) is appended to the shelf and flies up; a pane
 * restored elsewhere is removed and sails home. Echoes of this world's own
 * [persistPaneMinimized] writes arrive already-matching and no-op. Restricted to
 * panes currently on the ring so a stale blob entry can't occupy a shelf slot; if the
 * *front* pane was docked out from under us, the selection hops to a ring neighbour
 * (the same invariant [stashFront] maintains).
 *
 * @see minimizedPaneIds @see spikeLayoutJob
 */
internal fun syncStashFromMinimized() {
    val known = spikePanes.filterNot { it.dying }.mapTo(mutableSetOf()) { it.paneId }
    val want = minimizedPaneIds().filterTo(mutableSetOf()) { it in known }
    if (want == spikeStashed.toSet()) return
    spikeStashed.retainAll { it in want }
    for (p in spikePanes) {
        if (!p.dying && p.paneId in want && p.paneId !in spikeStashed) spikeStashed.add(p.paneId)
    }
    spikePanes.getOrNull(frontIndex())?.let { front ->
        if (front.paneId in spikeStashed) {
            leaveFrontPane()
            selectNearestUnstashedInTab(front.tabOrd, front.paneOrdInTab)
        }
    }
}

/**
 * The world position of shelf **slot** [slot] — the resting spot of the stashed pane at
 * that index in [spikeStashed]. The shelf is a left-anchored horizontal row (slot 0 at
 * [STASH_ROW_X0], growing toward +X by [STASH_ROW_GAP]) floating at height
 * [STASH_SHELF_Y] and biased slightly forward in +Z ([STASH_SHELF_Z]) so the row faces
 * the camera's stash-view pose. Read by the render loop (to lerp a stashing pane toward
 * it) and by [unstashNearest] (to find the shelved pane closest to the camera).
 *
 * @param slot the pane's index in [spikeStashed] (or its retained [RingPane.stashSlot]).
 * @return the `(x, y, z)` world position of that shelf slot.
 * @see toggleStash
 */
internal fun stashShelfPos(slot: Int): Triple<Double, Double, Double> =
    Triple(STASH_ROW_X0 + slot * STASH_ROW_GAP, STASH_SHELF_Y, STASH_SHELF_Z)

/**
 * Whether the camera is currently **up at the stash shelf** — decided from the live
 * camera height, not a mode flag, so it is right however you got there (the stash
 * journey parks you here, but a free-fly up counts too). This is what makes Space
 * unstash when you are looking at the shelf and stash when you are down at the ring.
 *
 * @return `true` if the camera has risen past halfway to the shelf.
 * @see toggleStash
 */
internal fun cameraAtShelf(): Boolean = spikeCamFlown && spikeCamY > STASH_SHELF_Y * 0.5

/**
 * The **Space** key handler in navigate mode — the camera-proximity stash toggle. Down
 * at the ring it [stashFront]s the active pane; up at the shelf ([cameraAtShelf]) with
 * panes stashed it [unstashNearest]s the one closest to the camera; up at the shelf with
 * nothing left to unstash it just flies home ([resetCamera]). A no-op while a camera
 * journey is still playing, so a rapid double-tap can't fire a second flight mid-air.
 *
 * @see buildKeyHandler @see stashFront @see unstashNearest
 */
internal fun toggleStash() {
    if (stashBusy() || bundlesBusy()) return // a journey is in flight — ignore until it lands
    when {
        // Up at the dock, Space brings back the **browsed dock item** — a single stashed pane
        // or a whole tab bundle, whichever the ←/→ cursor sits on — so panes and tabs are
        // unstashed from one enumerated row. @see unstashBrowsedDockItem
        cameraAtShelf() && dockItemCount() > 0 -> unstashBrowsedDockItem()
        cameraAtShelf() -> resetCamera() // at the dock, nothing to unstash → come home
        else -> stashFront()
    }
}

/**
 * One camera pose: the world position to stand at and the point to look at. Returned by
 * [shelfArrivalPose] so the shelf flights ([stashFront], [toggleStashView], [shelfBrowse])
 * share one framing rule.
 *
 * @property cx/cy/cz where the camera parks.
 * @property lx/ly/lz what it looks at from there.
 */
internal class CamPose(
    val cx: Double, val cy: Double, val cz: Double,
    val lx: Double, val ly: Double, val lz: Double,
)

/**
 * A shelved pane's **world half-height** at rest on the shelf — the same
 * `(content + titlebar) · ½ · scale` the render loop uses for its occlusion bound
 * ([World3DSpikeRender]), read from the pane's *live* CSS3D scale so it is correct for
 * whatever size that particular pane is (panes differ: side-normalization, per-pane
 * zoom). Used by [shelfArrivalPose] to know how much room the pane needs below the sign.
 *
 * @param p the pane to measure.
 * @return its world half-height (world units), or a nominal default if scale is unreadable.
 * @see shelfArrivalPose
 */
internal fun paneShelfHalfH(p: RingPane): Double {
    val scale = (p.obj.scale.y as? Double) ?: 1.0
    return (p.baseCh + TITLE_H) * 0.5 * scale
}

/**
 * The **sign-revealing arrival pose** for landing at shelf [slot]: stands the camera back
 * on the shelf's +Z side, looking horizontally at the mid-point of the vertical span from
 * the shelved pane's bottom up to the [STASH_LABEL_TEXT] sign's top, far enough that the
 * whole span (plus [SIGN_REVEAL_MARGIN]) fits the [SPIKE_FOV] frustum — so the pane sits
 * in the lower frame and the sign crowns it, instead of the sign being cropped off the top
 * (the old close "pane fills the view" landing never showed the beacon). Deriving the
 * standoff from the span makes it self-adjust to variable pane sizes and window heights.
 *
 * @param slot the shelf slot to frame.
 * @param paneHalfH the shelved pane's world half-height ([paneShelfHalfH]); `0.0` for an
 *   empty shelf (frame the sign and the bare shelf spot).
 * @param maxStandoff caps the camera→shelf standoff so the pose stays inside a bound —
 *   used by the station's interior park ([STATION_INTERIOR_STANDOFF]) to keep the camera
 *   from poking back out through the front door wall. Defaults to unbounded (the classic
 *   open-sky framing). @return the camera pose to fly to.
 * @see stashFront @see toggleStashView @see shelfBrowse @see stationInteriorPose
 */
internal fun shelfArrivalPose(
    slot: Int,
    paneHalfH: Double,
    maxStandoff: Double = Double.MAX_VALUE,
): CamPose {
    val (shx, shy, shz) = stashShelfPos(slot)
    val signHalfH = STASH_LABEL_FONT_PX * 0.62 // one-line banner ≈ 1.24·font tall
    val top = shy + STASH_LABEL_RISE + signHalfH
    val bot = shy - paneHalfH
    val lookY = (top + bot) * 0.5
    val spanHalf = ((top - bot) * 0.5 + SIGN_REVEAL_MARGIN).coerceAtLeast(SIGN_REVEAL_MIN_HALF)
    val d = (spanHalf / tan(SPIKE_FOV * PI / 360.0)).coerceAtMost(maxStandoff)
    return CamPose(shx, lookY, shz + d, shx, lookY, shz)
}

/**
 * The **camera-only** stash-view toggle — a hotkey that flies you up to the shelf
 * (or back home if you are already there) **without stashing or unstashing anything**.
 * Lands close on the row's centre slot, then ←/→ browses along the row and Space
 * unstashes the browsed pane, or this key again (or `c`) comes home. A no-op while a
 * journey is in flight.
 *
 * @see cameraAtShelf @see toggleStash @see flyCamTo
 */
internal fun toggleStashView() {
    if (stashBusy()) return
    // `v` **always flies to the spaceship**, never home — pressing it again at the dock just
    // re-centres on the row. Home is `c` (a clean split: `v` → ship, `c` → command center).
    // Fly up and park *close* on the row's centre slot — the same near pose a stash
    // flight lands on ([STASH_CAM_LAND_DIST]), so the shelved pane fills most of the
    // view on arrival and ←/→ then browses its neighbours from equal footing. With
    // nothing stashed yet this simply visits the (empty) shelf under its beacon.
    // Centre over **all** dock items (stashed panes + tab bundles), which share the row.
    val dockN = dockItemCount()
    val centerSlot = if (dockN == 0) 0 else (dockN - 1) / 2
    spikeShelfIndex = centerSlot // seed ←/→ browsing from the framed centre
    // Frame the centre slot's item *and* the STASH sign above it (empty dock → just the
    // sign over the bare spot). No pane to track here, so the fixed look is the reveal look.
    val centerPane = spikeStashed.getOrNull(centerSlot)
        ?.let { id -> spikePanes.firstOrNull { it.paneId == id } }
    val centerBundle = if (centerSlot >= spikeStashed.size) spikeStashedTabs.getOrNull(centerSlot - spikeStashed.size) else null
    val paneHalfH = centerPane?.let { paneShelfHalfH(it) }
        ?: centerBundle?.let { b -> spikePanes.firstOrNull { it.paneId == b.paneIds.first() }?.let { paneShelfHalfH(it) } }
        ?: 0.0
    // Name the framed item at the dock, matching the command center's "now showing" cue.
    centerPane?.let { showNavLabelFor(it) }
    centerBundle?.let { showNavLabelForBundle(it) }
    if (stationBuilt()) {
        // A camera-only visit still flies in through the bay door (no pane to follow), but
        // it lands at the **same close, centred pose a stash chase rests on**
        // ([stationChaseRestPose]) so reaching the dock with `v` settles in the same spot as
        // stashing a pane — not the higher sign-reveal framing.
        flyStationEnter(stationChaseRestPose(centerSlot), followPaneId = null)
        return
    }
    val pose = shelfArrivalPose(centerSlot, paneHalfH)
    flyCamTo(
        pose.cx, pose.cy, pose.cz,
        pose.lx, pose.ly, pose.lz,
        landPristine = false, frames = STASH_VIEW_CAM_FRAMES, // camera-only peek — brisk, no pane to pace
        pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
        sway = STASH_CAM_SWAY, roll = STASH_CAM_ROLL,
    )
}

/**
 * Stashes the active (front) pane: appends it to [spikeStashed] (so the render loop
 * flies it up to its shelf slot), moves the ring selection onto a neighbouring pane
 * that stays on the ring (so the fronted slot is never a shelved pane), and launches
 * the camera up to frame the shelf ([flyCamTo], parking flown so Space then unstashes).
 * No-op if there is no front pane or it is already stashed.
 *
 * @see toggleStash @see selectNearestUnstashedInTab
 */
internal fun stashFront() {
    val fi = frontIndex()
    if (fi < 0) return
    stashPane(spikePanes[fi])
}

/**
 * The **free-flight Space** stash/unstash — acts on the pane **at screen centre**
 * ([actionTargetPane], a ray-cast in free flight), the same pane every other free-flight
 * action targets (the command center's Space acts on the selected front pane via
 * [toggleStash]). If that centred pane is already on the shelf it flies home
 * ([unstashPane]); otherwise it flies up to the shelf ([stashPane]). The pane the decision
 * picks is the exact pane that travels. No-op while a camera journey is in flight.
 * @see toggleStash @see stashPane @see unstashPane
 */
internal fun toggleStashNearest() {
    if (stashBusy()) return
    val p = actionTargetPane() ?: return
    // The pane the decision picks (screen centre) is the exact pane that acts — a shelved
    // one flies home, a ring one flies up.
    if (p.paneId in spikeStashed) unstashPane(p) else stashPane(p)
}

/**
 * Stashes a specific pane [p] — the shared body of [stashFront] (command center → the
 * selected front pane) and [toggleStashNearest] (free flight → the nearest pane).
 * Appends it to [spikeStashed] (so the render loop flies it up to its shelf slot), hops
 * the ring selection off it onto a neighbour still on the ring (so the fronted slot is
 * never a shelved pane), and launches the camera up to frame the shelf. No-op if [p] is
 * already stashed. @see toggleStash @see selectNearestUnstashedInTab
 */
internal fun stashPane(p: RingPane) {
    if (p.paneId in spikeStashed) return

    leaveFrontPane() // disengage / exit selection before the pane leaves the ring
    val slot = spikeStashed.size
    spikeStashed.add(p.paneId)
    p.stashSlot = slot
    persistPaneMinimized(p.paneId, minimized = true) // stash == 2D minimize (dock)

    // Keep the ring's fronted slot valid: hop selection to the nearest pane still on
    // the ring in this pane's tab (the shelved pane's ring slot is left as an empty gap).
    selectNearestUnstashedInTab(p.tabOrd, p.paneOrdInTab)

    // Fly up alongside the pane, tracking it the whole way so it stays centred, and
    // park *close* in front of the slot it lands on ([STASH_CAM_LAND_DIST] — the pane
    // fills most of the view on arrival, so the journey ends feeling *arrived*, gazing
    // slightly up at it from [STASH_CAM_LAND_DROP] below). The look point is the *live
    // pane* (followPaneId), so from take-off near the sphere to touchdown on the shelf
    // you watch it sail across the air. Sway bows the climb into a sweeping lateral
    // curve and roll banks into it, so the long haul plays as a flight, not an elevator
    // ride. Tunable.
    spikeShelfIndex = slot // arrive browsing the slot the pane lands on
    // Track the pane the whole climb (watch it sail up), then over the final stretch swing
    // the gaze to the reveal pose so the STASH sign crowns the just-parked pane on arrival
    // (endLook). The camera stands back far enough to frame both — this pane's own size is
    // measured, so a tall/short pane is framed correctly.
    if (stationBuilt()) {
        // Chase the terminal the whole way up to the station, pulling back to reveal the
        // hangar as it arrives (see [tickStashChase]).
        armStashChase(p.paneId, outbound = true)
    } else {
        val pose = shelfArrivalPose(slot, paneShelfHalfH(p))
        flyCamTo(
            pose.cx, pose.cy, pose.cz,
            pose.lx, pose.ly, pose.lz,
            landPristine = false, frames = STASH_CAM_FRAMES,
            pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
            followPaneId = p.paneId,
            sway = STASH_CAM_SWAY, roll = STASH_CAM_ROLL,
            endLook = Triple(pose.lx, pose.ly, pose.lz),
        )
    }
    spikeSettledIndex = -1
    // Name the just-stashed window at the dock (not the ring neighbour selection hopped
    // to) — it's the pane we're flying up to frame. @see showNavLabelFor
    showNavLabelFor(p)
}

/**
 * Unstashes the shelved pane **nearest the camera**: removes it from [spikeStashed] (so
 * the render loop sails it back down to its original ring slot), lands the ring
 * selection on it so you arrive fronted on it, and flies the camera home ([resetCamera]-
 * style, landing pristine). If the shelf is already empty it just returns the camera
 * home. @see toggleStash @see stashFront
 */
internal fun unstashNearest() {
    if (spikeStashed.isEmpty()) {
        resetCamera() // no-op if already home
        return
    }
    // Camera world position: the flown pose, or the pristine home pose.
    val homeZ = RING_R + perspDistance(window.innerHeight)
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    val cy = if (spikeCamFlown) spikeCamY else 0.0
    val cz = if (spikeCamFlown) spikeCamZ else homeZ

    // Pick the shelved pane whose slot is closest to the camera, then unstash *that* one.
    var bestId: String? = null
    var bestD = Double.MAX_VALUE
    for ((slot, id) in spikeStashed.withIndex()) {
        val (sx, sy, sz) = stashShelfPos(slot)
        val d = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy) + (sz - cz) * (sz - cz)
        if (d < bestD) { bestD = d; bestId = id }
    }
    val p = bestId?.let { id -> spikePanes.firstOrNull { it.paneId == id } } ?: return
    unstashPane(p)
}

/**
 * Unstashes a **specific** shelved pane [p] — the shared body of [unstashNearest] (command
 * center / dock browse → the shelved pane nearest the camera) and the free-flight Space
 * ([toggleStashNearest] → the pane **at screen centre**). Removes it from [spikeStashed]
 * (so the render loop sails it back down to its ring slot), lands the ring selection on it
 * so you arrive fronted on it, and flies the camera home ([flyCamTo] / station chase,
 * landing pristine). Ensures the pane the stash/unstash *decision* picked is the exact same
 * pane that flies home. No-op if [p] is not currently stashed.
 *
 * @param p the shelved pane to bring home. @see toggleStashNearest @see stashPane
 */
internal fun unstashPane(p: RingPane) {
    if (p.paneId !in spikeStashed) return
    val id = p.paneId
    val homeZ = RING_R + perspDistance(window.innerHeight)
    spikeStashed.remove(id) // render loop now eases its stashProg → 0 (flies home)
    persistPaneMinimized(id, minimized = false) // unstash == 2D restore from the dock

    // Land selection on the returning pane so the camera home-pose fronts it.
    if (spikeTabSel.isNotEmpty()) {
        spikeTabIndex = p.tabOrd.coerceIn(0, spikeTabSel.size - 1)
        spikeTabSel[spikeTabIndex] = p.paneOrdInTab
        spikePaneScroll = p.paneOrdInTab.toDouble()
    }

    spikeSettledIndex = -1
    spikeShelfIndex = -1 // slots shift after removal and we're leaving — drop the browse cursor
    // Fly home the reverse of the stash trip (negative sway, so the return isn't a mirrored
    // replay). The gaze rides the COMMAND CENTER sign over the home beacon through the
    // descent — so you're flying *into* the command center with its banner leading you in —
    // then eases down to the ring (endLook = origin) to settle into the clean home view,
    // landing pristine fronted on the pane, which sails back to its ring slot in-world and
    // slides into the lower frame as the aim drops. (A gaze can only hold one subject: the
    // sign reveal takes the descent; the pane's return is watched in-world, not centre-locked.)
    if (stationBuilt()) {
        // Chase the returning terminal home from the station the whole way down; the chase
        // eases onto the pristine view as it lands (see [tickStashChase]).
        armStashChase(id, outbound = false)
    } else {
        flyCamTo(
            0.0, 0.0, homeZ,
            0.0, BEACON_Y + BEACON_LABEL_RISE, homeZ,
            landPristine = true, frames = STASH_CAM_FRAMES,
            pullout = STASH_CAM_PULLOUT, rise = STASH_CAM_RISE,
            sway = -STASH_CAM_SWAY, roll = -STASH_CAM_ROLL,
            endLook = Triple(0.0, 0.0, 0.0),
        )
    }
    showNavLabel()
}

/**
 * The shelf slot **nearest the camera's x** — where ←/→ browsing starts from when the
 * camera arrived at the shelf without a browse cursor (a free-fly up, or a stash-view
 * flight that framed the whole row). Only x matters: the shelf is a horizontal row, so
 * "which slot am I in front of" is purely lateral.
 *
 * @return the nearest slot index, or `-1` if nothing is stashed.
 * @see shelfBrowse
 */
internal fun nearestShelfSlot(): Int {
    if (spikeStashed.isEmpty()) return -1
    val cx = if (spikeCamFlown) spikeCamX else 0.0
    var best = 0
    var bestD = Double.MAX_VALUE
    for (slot in spikeStashed.indices) {
        val (sx, _, _) = stashShelfPos(slot)
        val d = abs(sx - cx)
        if (d < bestD) { bestD = d; best = slot }
    }
    return best
}

/**
 * **←/→ while up at the stash shelf** — steps the browse cursor ([spikeShelfIndex]) one
 * slot along the row and glides the camera to park in front of it (the same viewing
 * pose a stash flight lands on), the shelf counterpart of [rotatePane] down at the
 * ring. The shelf is a flat row, so this is a short straight slide sideways — no arc,
 * no sway, no bank ([SHELF_BROWSE_FRAMES] frames) — and because the camera parks in
 * front of the browsed pane, Space's nearest-to-camera unstash naturally picks it.
 * Free-fly pointing still works too: [unstashNearest] measures the live camera, and a
 * browse after a free-fly reseeds the cursor from wherever you flew ([nearestShelfSlot]).
 *
 * Retargeting mid-glide is safe ([flyCamTo] launches from the live pose), so holding
 * the key walks the row smoothly. No-op with an empty shelf.
 *
 * @param delta `-1` to step toward slot 0 (left), `+1` toward the row's end (right);
 *   clamped at the row ends.
 * @see buildKeyHandler @see cameraAtShelf
 */
internal fun shelfBrowse(delta: Int) {
    val dockN = dockItemCount()
    if (dockN == 0) return
    if (spikeStashChase != null) return // a stash chase is in flight — not browsing yet
    // A full stash/unstash/stash-view journey is still playing — let it land rather than
    // yanking the camera off mid-cinematic. The pan below then takes over, retargetable.
    if (spikeCamReturning) return
    val cur = if (spikeShelfIndex in 0 until dockN) spikeShelfIndex else nearestDockSlot()
    val next = (cur + delta).coerceIn(0, dockN - 1)
    if (next == cur && spikeShelfIndex in 0 until dockN) return // already parked at the row's end
    spikeShelfIndex = next
    // Pure **lateral dolly**: keep the camera's height, depth and straight-ahead gaze
    // fixed and just truck sideways to the new slot's x (the render loop eases
    // [spikeCamX] toward [spikeShelfPanTargetX] at [SHELF_PAN_EASE]). This replaced a
    // [flyCamTo] tour that flew to each pane's own reveal pose: slots of differing pane
    // sizes sat at different depths, and — worse — the tour kept the gaze locked on the
    // *destination* slot while sliding, so the camera swung inward as it moved, which
    // read as jerky. A fixed forward gaze (straight down −Z at the shelf) makes browsing
    // glide cleanly along the row like sliding on a rail. The arrival flight already set
    // this straight pose, so seeding the basis here is a no-op that just guarantees it.
    resetFlyBasis() // nose straight at the shelf (−Z), roof +Y — the row's viewing gaze
    spikeCamFlown = true
    val (shx, _, _) = stashShelfPos(next)
    spikeShelfPanTargetX = shx
    // Name the browsed item in the big top-left label — the dock's echo of the command
    // center's "now showing" cue. A slot below [spikeStashed]'s size is a single stashed
    // pane; beyond it, a whole tab bundle. @see showNavLabelFor @see showNavLabelForBundle
    if (next < spikeStashed.size) {
        spikeStashed.getOrNull(next)
            ?.let { id -> spikePanes.firstOrNull { it.paneId == id } }
            ?.let { showNavLabelFor(it) }
    } else {
        spikeStashedTabs.getOrNull(next - spikeStashed.size)?.let { showNavLabelForBundle(it) }
    }
}

/**
 * Moves the ring selection in [tab] onto the pane still on the ring (not in
 * [spikeStashed]) whose ordinal is nearest [ord] — used after stashing the front pane
 * so the fronted slot is never a shelved pane. No-op if every pane in the tab is
 * stashed (the caller's fronted slot then simply shows a gap).
 *
 * @param tab the tab (latitude) ordinal to reselect within.
 * @param ord the ordinal to search outward from (the just-stashed pane's slot).
 * @see stashFront
 */
internal fun selectNearestUnstashedInTab(tab: Int, ord: Int) {
    val best = spikePanes
        .filter { !it.dying && it.tabOrd == tab && it.paneId !in spikeStashed }
        .minByOrNull { abs(it.paneOrdInTab - ord) }
        ?: return
    if (tab in spikeTabSel.indices) {
        spikeTabSel[tab] = best.paneOrdInTab
        loadFrontZoom()
    }
}
