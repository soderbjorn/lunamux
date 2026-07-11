/*
 * Split from World3DSpike.kt — navigation and pane/tab create/move/remove commands.
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

/** The number of (non-dying) panes in the currently-fronted tab. */
internal fun currentTabPaneCount(): Int = spikePanes.count { !it.dying && it.tabOrd == spikeTabIndex }

/** `true` when the fronted tab has no panes (its latitude holds an invisible [EmptyTabCard]). */
internal fun currentTabIsEmpty(): Boolean = currentTabPaneCount() == 0

/**
 * The [spikePanes] index of the front pane (current tab's selected pane), or -1.
 *
 * @return the front pane index.
 */
internal fun frontIndex(): Int {
    val sel = spikeTabSel.getOrNull(spikeTabIndex) ?: return -1
    return spikePanes.indexOfFirst { !it.dying && it.tabOrd == spikeTabIndex && it.paneOrdInTab == sel }
}

/**
 * The pane a "pane action" (focus, tilt, glide, grid, reformat, zoom) should act on,
 * resolved by the **one targeting rule**:
 *  - **Command Center** (`!spikeFlyMode`) → the selected/front pane, but only when it is
 *    settled at the front ([spikeSettledIndex] == [frontIndex]); `null` mid-rotation so an
 *    action can't fire while the ring is still swinging.
 *  - **Free Flight** ([spikeFlyMode]) → the pane **at the centre of the screen**
 *    ([paneAtScreenCenter]): a ray cast along the camera's nose, so you aim at a pane to
 *    act on it. The ring is static in flight, so no settle guard is needed.
 *
 * @return the target pane, or `null` when there is none / the front pane isn't settled.
 * @see paneAtScreenCenter @see nearestPaneToCamera @see buildKeyHandler
 */
internal fun actionTargetPane(): RingPane? {
    if (spikeFlyMode) return paneAtScreenCenter()
    val fi = frontIndex()
    if (fi < 0 || spikeSettledIndex != fi) return null
    return spikePanes.getOrNull(fi)
}

/**
 * Un-bakes any selection-mode font on the leaving pane and disengages a focused
 * pane (so navigation never leaves a hidden pane focused). Called *before* every
 * navigation. The pane's **visual zoom is not discarded** — it lives on in
 * [spikeZoomByPane] and is re-loaded by [loadFrontZoom] when you return; the
 * leaving pane simply eases from its zoom to its side-pane scale as it drifts off
 * front (the render loop only zooms `i == fi`).
 */
internal fun leaveFrontPane() {
    if (spikeSelectionMode) {
        spikeSelectionMode = false
        updateModeBadge()
    }
    disengage()
}

/**
 * Restores the new front pane's remembered visual zoom into [spikeZoomTarget]
 * (default 1.0), so navigating onto a pane you previously `+`/`−`'d brings its
 * magnification back. Called right after a navigation updates the selection.
 */
internal fun loadFrontZoom() {
    val id = spikePanes.getOrNull(frontIndex())?.paneId
    spikeZoomTarget = id?.let { spikeZoomByPane[it] } ?: 1.0
    // Restoring a remembered zoom on navigation is not a ⇧ preset jump — keep the
    // snappy ease even if a preset glide was still in flight on the previous pane.
    spikeZoomGlide = false
}

/**
 * Seeds [spikeZoomByPane] from the persisted [Pane.zoom] values in [cfg], for
 * panes the map does not know yet. Local-first: an entry already in the map is
 * never overwritten, so a zoom made this session (including the config-broadcast
 * echo of our own [WindowCommand.SetPaneZoom] write-through) can't be reverted
 * by a server push — the persisted value only matters on a fresh app run, where
 * it restores what the last session saved. Entries at the 1.0 default are
 * skipped to keep the map lean (an absent entry already means "unzoomed").
 *
 * Called from [openWorld3dSpike]'s reset block (before [loadFrontZoom] picks the
 * starting pane's zoom) and from its config collector (panes can be created
 * while the world is open).
 *
 * @param cfg the window config to read persisted zooms from.
 * @see loadFrontZoom @see zoomFront @see resetFrontZoom
 */
internal fun seedZoomFromConfig(cfg: WindowConfig) {
    for (tab in cfg.tabs) for (pane in tab.panes) {
        if (pane.zoom != 1.0 && pane.leaf.id !in spikeZoomByPane) {
            spikeZoomByPane[pane.leaf.id] = pane.zoom
        }
    }
}

/** Whether [p] is currently stashed (up on / heading to the shelf). @see toggleStash */
internal fun isStashed(p: RingPane): Boolean = p.paneId in spikeStashed

/**
 * Cycles the panes within the current tab (←/→), **skipping stashed panes** — a pane
 * up on the shelf leaves its ring slot as an empty gap that navigation steps over, so
 * `←/→` never centres on a shelved pane (and its original slot is preserved for when it
 * returns).
 *
 * @param delta -1 = previous pane, +1 = next.
 */
internal fun rotatePane(delta: Int) {
    val cnt = currentTabPaneCount()
    if (cnt <= 1) return
    val cur = spikeTabSel[spikeTabIndex]
    // Step in `delta`, skipping stashed panes, and clamp (no wrap): if there is no
    // un-stashed pane further in that direction, stay put.
    var next = cur
    var probe = cur + delta
    while (probe in 0 until cnt) {
        val cand = spikePanes.firstOrNull {
            !it.dying && it.tabOrd == spikeTabIndex && it.paneOrdInTab == probe
        }
        if (cand != null && !isStashed(cand)) { next = probe; break }
        probe += delta
    }
    if (next == cur) return
    leaveFrontPane()
    spikeTabSel[spikeTabIndex] = next
    loadFrontZoom()
    showNavLabel()
    spikeSettledIndex = -1
}

/**
 * Cycles the fronted tab (Alt+↑/↓ or ▲/▼); the ring slides vertically to the new
 * "floor". Snaps the horizontal scroll to the new tab's selected pane so tabs
 * change with no incidental horizontal spin.
 *
 * @param delta -1 = previous tab (above), +1 = next tab (below).
 */
internal fun switchTab(delta: Int) {
    val cnt = spikeTabSel.size
    if (cnt <= 1) return
    // Clamp (no wrap): at the first/last tab, ↑ / ↓ stop instead of rolling around.
    val next = (spikeTabIndex + delta).coerceIn(0, cnt - 1)
    if (next == spikeTabIndex) return
    leaveFrontPane()
    spikeTabIndex = next
    spikePaneScroll = spikeTabSel[spikeTabIndex].toDouble()
    loadFrontZoom()
    showNavLabel()
    spikeSettledIndex = -1
}

/**
 * The backing tab id at latitude [ord] — read from any live pane on that latitude,
 * or from its empty-tab card. Empty for the anonymous registry-fallback tab (which
 * has no real id), which the callers treat as "not reorderable".
 *
 * @param ord the tab (latitude) ordinal.
 * @return the tab's id, or `null` if none can be resolved.
 */
internal fun tabIdForOrd(ord: Int): String? =
    (spikePanes.firstOrNull { !it.dying && it.tabOrd == ord }?.tabId
        ?: spikeEmptyTabs.firstOrNull { !it.dying && it.tabOrd == ord }?.tabId)
        ?.takeIf { it.isNotEmpty() }

/**
 * Moves the fronted **tab** one latitude toward the previous/next slot (⇧↑/⇧↓),
 * swapping it with its neighbour. Dispatches [WindowCommand.MoveTab] to keep the 2D
 * app in sync, then optimistically swaps the two latitudes' pane/card ordinals and
 * their remembered pane selections so the ring slides at once; [reconcileRing]
 * confirms the new order. Clamped (no wrap) and a no-op at the ends.
 *
 * @param delta -1 = toward the previous tab (up), +1 = toward the next (down).
 * @see WindowCommand.MoveTab
 */
internal fun moveTabSlot(delta: Int) {
    val cnt = spikeTabSel.size
    val from = spikeTabIndex
    val to = from + delta
    if (cnt <= 1 || to < 0 || to >= cnt) return
    val fromId = tabIdForOrd(from) ?: return
    val toId = tabIdForOrd(to) ?: return
    // before=true when moving up (place before the predecessor); false moving down.
    launchCmd(WindowCommand.MoveTab(tabId = fromId, targetTabId = toId, before = delta < 0))
    // Optimistic adjacent swap of the two latitudes (reconcile is authoritative).
    val retag = { cur: Int -> when (cur) { from -> to; to -> from; else -> cur } }
    for (p in spikePanes) if (!p.dying) p.tabOrd = retag(p.tabOrd)
    for (c in spikeEmptyTabs) if (!c.dying) c.tabOrd = retag(c.tabOrd)
    val sel = spikeTabSel[from]; spikeTabSel[from] = spikeTabSel[to]; spikeTabSel[to] = sel
    spikeTabIndex = to
    spikePaneScroll = spikeTabSel[to].toDouble()
    spikeSettledIndex = -1
    showNavLabel()
}

/**
 * Moves the fronted **pane** one slot toward the previous/next position within its
 * tab (⇧←/⇧→), swapping it with its neighbour. Dispatches
 * [WindowCommand.MovePaneWithinTab] (config list order) **and** writes the new order
 * through to the toolkit `LAYOUT_STATE` blob via [persistPaneOrder] — the ring (and
 * the 2D sidebar) sort a tab's panes by that blob ([toolkitPaneOrder]), not by the
 * config list, so without the blob write the next broadcast would re-sort the ring
 * straight back and the move would appear to do nothing. Then optimistically swaps
 * the two panes' ordinals (and keeps the moved pane fronted) so the ring turns at
 * once; [reconcileRing] confirms. No-op on an empty tab or at the ring's ends.
 *
 * @param delta -1 = toward the previous pane (left), +1 = toward the next (right).
 * @see WindowCommand.MovePaneWithinTab @see persistPaneOrder
 */
internal fun movePaneSlot(delta: Int) {
    val fi = frontIndex()
    if (fi < 0) return // empty tab: no pane to move
    val p = spikePanes[fi]
    val sel = p.paneOrdInTab
    val to = sel + delta
    if (to < 0 || to >= currentTabPaneCount()) return
    val neighbor = spikePanes.firstOrNull {
        !it.dying && it.tabOrd == spikeTabIndex && it.paneOrdInTab == to
    } ?: return
    // retile = false: a preset-driven tab (every tab is stamped Auto at birth)
    // would otherwise be re-tiled by the server on this reorder, rewriting all
    // its panes' 2D geometry — which every attached client then refits its PTYs
    // to, visibly snapping ring panes to sizes the user never chose.
    launchCmd(
        WindowCommand.MovePaneWithinTab(
            paneId = p.paneId, targetPaneId = neighbor.paneId, before = delta < 0, retile = false,
        )
    )
    // Persist the display order the ring actually sorts by: start from the blob's
    // current list (keeping any ids it ranks that aren't on the ring), append ring
    // panes it doesn't know yet in their current ring order, then swap the mover
    // and its neighbour — under rank sorting that reproduces the ring swap exactly.
    if (p.tabId.isNotEmpty()) {
        val ringIds = spikePanes.filter { !it.dying && it.tabOrd == spikeTabIndex }
            .sortedBy { it.paneOrdInTab }.map { it.paneId }
        val merged = (toolkitPaneOrder(p.tabId) + ringIds).distinct().toMutableList()
        val pi = merged.indexOf(p.paneId)
        val ni = merged.indexOf(neighbor.paneId)
        if (pi >= 0 && ni >= 0) {
            merged[pi] = neighbor.paneId
            merged[ni] = p.paneId
            persistPaneOrder(p.tabId, merged)
        }
    }
    p.paneOrdInTab = to
    neighbor.paneOrdInTab = sel
    spikeTabSel[spikeTabIndex] = to // keep the moved pane fronted
    spikeSettledIndex = -1
    showNavLabel()
}

/**
 * The backing tab id of the currently-fronted latitude — from a live pane there, or
 * from an empty-tab card. `null` for the anonymous registry-fallback tab (which has
 * no real id, so panes can't be added to it).
 *
 * @return the current tab's id, or `null` if there is none to target.
 * @see createPane @see confirmRemove
 */
internal fun currentTabId(): String? {
    spikePanes.firstOrNull { !it.dying && it.tabOrd == spikeTabIndex }
        ?.tabId?.takeIf { it.isNotEmpty() }?.let { return it }
    return spikeEmptyTabs.firstOrNull { !it.dying && it.tabOrd == spikeTabIndex }?.tabId
}

/**
 * Creates a new empty tab (`t`): asks the server for one and flags [reconcileRing] to
 * front it (as an empty-tab card) the moment the config reflects it. The card then
 * animates in on its own new latitude.
 *
 * @see reconcileRing @see WindowCommand.AddTab
 */
internal fun createTab() {
    spikePendingFocusNewTab = true
    launchCmd(WindowCommand.AddTab)
}

/**
 * Adds a new terminal pane (`n`) to the current tab — including an empty one, which
 * turns its placeholder card into a real pane. Flags [reconcileRing] to front the
 * new pane once the server mints it (it grows in via its birth animation). No-op if
 * there is no real tab to target (the registry-fallback tab).
 *
 * @see reconcileRing @see WindowCommand.AddPaneToTab
 */
internal fun createPane() {
    val tabId = currentTabId() ?: return
    spikePendingFocusTab = tabId
    launchCmd(WindowCommand.AddPaneToTab(tabId = tabId, cwd = cwdForNewPaneIn(tabId)))
}

/**
 * Handles the first-and-second **⌥X**: closing a pane or tab is destructive, so the
 * first press only **arms** the removal (showing the confirm banner) and the second
 * press actually [confirmRemove]s it. No-op when there is nothing removable at the
 * front. Called from [buildKeyHandler] for every ⌥X in navigate mode; the arm is
 * cancelled by any other key or by the [REMOVE_ARM_MS] timeout.
 *
 * @see confirmRemove @see cancelRemoveArm @see armRemove
 */
internal fun requestRemoveFocused() {
    if (spikeRemoveArmed) { confirmRemove(); return }
    // Only arm if there is genuinely something removable at the front.
    val fi = frontIndex()
    val label = when {
        fi >= 0 -> "this window"
        spikeEmptyTabs.any { !it.dying && it.tabOrd == spikeTabIndex } -> "this empty tab"
        else -> return
    }
    armRemove(label)
}

/**
 * Arms a removal: shows the amber confirm banner naming the [target] and starts the
 * [REMOVE_ARM_MS] safety timeout that auto-cancels if the user does nothing.
 *
 * @param target human phrase for what a second ⌥X will remove (e.g. "this pane").
 */
internal fun armRemove(target: String) {
    spikeRemoveArmed = true
    spikeConfirmBadge?.let {
        it.textContent = "Remove $target?  ·  press ⌥X again to confirm  ·  any other key cancels"
        it.style.setProperty("opacity", "1")
    }
    spikeRemoveArmTimer?.let { window.clearTimeout(it) }
    spikeRemoveArmTimer = window.setTimeout({ cancelRemoveArm() }, REMOVE_ARM_MS)
}

/** Clears an armed removal and fades the confirm banner out. Safe to call unarmed. */
internal fun cancelRemoveArm() {
    spikeRemoveArmed = false
    spikeRemoveArmTimer?.let { window.clearTimeout(it) }
    spikeRemoveArmTimer = null
    spikeConfirmBadge?.style?.setProperty("opacity", "0")
}

/**
 * Removes the thing at the front (confirming second ⌥X): the fronted pane if there
 * is one (its shell is closed), otherwise the fronted **empty** tab — a tab is only
 * removable here once it has no panes, which is exactly when a card (not a pane) is
 * fronted. The target is marked dying for an immediate shrink-out; the matching
 * server Close/CloseTab keeps the 2D app in sync and [reconcileRing] confirms it.
 *
 * @see WindowCommand.Close @see WindowCommand.CloseTab @see reconcileRing
 */
internal fun confirmRemove() {
    cancelRemoveArm()
    val fi = frontIndex()
    if (fi >= 0) {
        val p = spikePanes[fi]
        leaveFrontPane()
        // Under the phaser-fire feature flag the pane isn't shrunk out at once: it
        // lingers at the front getting shot for several seconds, then [tickPhaser] sets
        // it dying. Otherwise it's the classic optimistic shrink-out; either way the
        // config round-trip confirms the close.
        if (PHASER_CLOSE_ENABLED) startPhaserDeath(p) else p.dying = true
        launchCmd(WindowCommand.Close(p.paneId))
        return
    }
    // No front pane ⇒ the current tab is empty; remove its card and the (empty) tab.
    val card = spikeEmptyTabs.firstOrNull { !it.dying && it.tabOrd == spikeTabIndex } ?: return
    card.dying = true
    launchCmd(WindowCommand.CloseTab(card.tabId))
}
