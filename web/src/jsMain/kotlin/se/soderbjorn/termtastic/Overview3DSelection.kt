/*
 * Split from Overview3D.kt — selection/fidelity/readout/dive + raycast + renderer layout helpers.
 * See Overview3D.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import se.soderbjorn.termtastic.three.CanvasTexture
import se.soderbjorn.termtastic.three.Mesh
import se.soderbjorn.termtastic.three.MeshBasicMaterial
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.PlaneGeometry
import se.soderbjorn.termtastic.three.Raycaster
import se.soderbjorn.termtastic.three.Scene
import se.soderbjorn.termtastic.three.Vector2
import se.soderbjorn.termtastic.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Rotates the selection by [delta] cards (wrapping), updating the title
 * readout; the render loop eases the ring to the new target.
 *
 * @param delta +1 = next card (clockwise), -1 = previous.
 */
internal fun stepSelection(delta: Int) {
    if (ovCards.isEmpty()) return
    selectCard(((ovSelected + delta) % ovCards.size + ovCards.size) % ovCards.size)
}

/**
 * Makes [index] the selected (front-facing) card, clearing any pane
 * highlight (the new tab starts on "whole tab").
 *
 * @param index card index into [ovCards].
 */
internal fun selectCard(index: Int) {
    if (index !in ovCards.indices) return
    ovSelected = index
    // Start the pane highlight on the tab's *active* pane so the first ↑/↓/Tab
    // moves to its neighbour (not from a "whole tab" slot the user has to step
    // off first). Falls back to "whole tab" (-1) if there's no focused pane.
    ovPaneSelected = focusedTileIndex(index)
    updateTitleReadout()
    applyFidelity()
}

/**
 * The tile index of a card's active (focused) pane, or `-1` when the card has
 * no focused pane (so the selection starts on the "whole tab" slot). Used to
 * seed [ovPaneSelected] on open / tab switch so pane navigation is relative to
 * the active pane.
 *
 * @param cardIndex index into [ovCards].
 * @return the focused pane's tile index, or `-1`.
 */
internal fun focusedTileIndex(cardIndex: Int): Int {
    val card = ovCards.getOrNull(cardIndex) ?: return -1
    val focused = card.focusedPaneId ?: return -1
    return card.tiles.indexOfFirst { it.paneId == focused }
}

/**
 * Recomputes every tile view's render state and repaints, so it shows at once:
 *  - **fidelity**: *every* live-terminal tile paints the exact colored cell grid
 *    ([renderTerminalGrid]) — not just the selected tab's. The rotunda and exposé
 *    show several tabs' panes at once, so limiting the accurate grid to the front
 *    tab left the rest as the ugly re-wrapped monochrome thumbnail; rendering all
 *    mounted panes keeps the whole scene true-to-form. Only mirror fallbacks
 *    (unmounted tabs, fixed 120×40 — not the pane's real geometry) stay on the
 *    cheap thumbnail, since their grid would be the wrong size.
 *  - **highlights**: each tile's `paneActive` (its tab's focused pane → steady
 *    accent glow + tinted header) and `paneSelected` (the front tab's current
 *    keyboard/hover pane → brighter, thicker glow) flags, drawn into the tile
 *    canvas as a border/header by [ThumbView].
 *
 * Called on open and on every tab/pane selection change ([selectCard],
 * [cyclePane], [setPaneSelection]).
 */
internal fun applyFidelity() {
    ovCards.forEachIndexed { i, card ->
        val front = i == ovSelected
        card.tiles.forEachIndexed { ti, tile ->
            val term = tile.contentKind == TileKind.TERMINAL
            // Any live registry term (real pane geometry) renders at full colored
            // fidelity, in whatever style and whichever tab it belongs to; only
            // mirror fallbacks (fixed 120×40) stay on the cheap thumbnail.
            val full = term && tile.source?.live == true
            tile.view.fullFidelity = full
            tile.view.paneActive = tile.paneId == card.focusedPaneId
            tile.view.paneSelected = front && ti == ovPaneSelected
            tile.view.showCursor = full && tile.view.paneActive
        }
    }
    for (source in ovSources.values) source.repaint()
    // Non-terminal tiles have no source, so repaint them directly to reflect
    // highlight changes (data tiles keep their cached listing; others show the
    // placeholder).
    for (card in ovCards) for (tile in card.tiles) {
        if (tile.source == null) {
            when (tile.contentKind) {
                TileKind.FILE_BROWSER, TileKind.GIT -> repaintDataTile(tile)
                else -> tile.view.paint(null)
            }
        }
    }
}

/**
 * Cycles the front tab's pane highlight by [delta], wrapping through a
 * "whole tab" slot: `-1 → 0 → 1 → … → last → -1`. No-op when the front tab
 * has no panes.
 *
 * @param delta +1 = next pane (↓ / Tab), -1 = previous (↑ / Shift+Tab).
 */
internal fun cyclePane(delta: Int) {
    val card = ovCards.getOrNull(ovSelected) ?: return
    val n = card.tiles.size
    if (n == 0) return
    ovPaneSelected = if (delta > 0) {
        if (ovPaneSelected + 1 >= n) -1 else ovPaneSelected + 1
    } else {
        if (ovPaneSelected <= -1) n - 1 else ovPaneSelected - 1
    }
    updateTitleReadout()
    applyFidelity()
}

/**
 * Sets the pane highlight to [index] (or `-1` for none) and refreshes the
 * readout, but only if it actually changed. Called from hover so the mouse
 * and keyboard share one highlight.
 *
 * @param index tile index into the front card's tiles, or `-1`.
 */
internal fun setPaneSelection(index: Int) {
    if (index == ovPaneSelected) return
    ovPaneSelected = index
    updateTitleReadout()
    applyFidelity()
}

/**
 * The server pane (leaf) id currently highlighted on the front card, or
 * `null` when the whole tab is selected (`ovPaneSelected == -1`).
 *
 * @return the highlighted pane id, or `null`.
 */
internal fun selectedPaneId(): String? =
    ovCards.getOrNull(ovSelected)?.tiles?.getOrNull(ovPaneSelected)?.paneId

/**
 * Mirrors the selection into the overlay readout: the tab title alone, or
 * `Tab › Pane` when a specific pane is highlighted.
 */
internal fun updateTitleReadout() {
    val card = ovCards.getOrNull(ovSelected)
    if (card == null) { ovTitleEl?.textContent = ""; return }
    val tile = card.tiles.getOrNull(ovPaneSelected)
    ovTitleEl?.textContent = if (tile != null) "${card.title}  ›  ${tile.paneTitle}" else card.title
}

/**
 * Activates the selected tab with the dive-in transition: dispatches
 * [WindowCommand.SetActiveTab] (plus focus/raise for a specific pane)
 * immediately — the tab switches behind the overlay — then starts the
 * camera dive and CSS fade; the render loop calls [closeOverview3d] when
 * the dive completes.
 *
 * @param paneId specific pane to focus and raise (a clicked pane tile), or
 *   `null` to fall back to the card's own focused pane so the tab is not left
 *   merely active-but-unfocused.
 */
internal fun beginDive(paneId: String?) {
    val card = ovCards.getOrNull(ovSelected) ?: return
    if (!ovDiveStart.isNaN()) return
    launchCmd(WindowCommand.SetActiveTab(card.tabId))
    // Whole-tab selections (Enter with no tile highlighted, or a click on empty
    // card space) arrive with paneId == null; fall back to the card's focused
    // pane so the selection lands focused rather than just active.
    val focusPaneId = paneId ?: card.focusedPaneId
    if (focusPaneId != null) {
        // Same pair the sidebar pane click sends (see termtasticTabSource's
        // onPaneSelect): focus alone would leave the pane buried.
        launchCmd(WindowCommand.SetFocusedPane(tabId = card.tabId, paneId = focusPaneId))
        launchCmd(WindowCommand.RaisePane(paneId = focusPaneId))
    }
    ovDiveStart = window.performance.now()
    ovOverlay?.classList?.add("overview3d-closing")
}

/**
 * Raycasts the current pointer position ([ovPointerNdc]) against [meshes] and
 * returns the nearest hit's `userData` payload (`index` always present, `paneId`
 * only on tiles), or `null` over empty space. The style decides *which* meshes
 * are pickable via [Overview3DStyle.pickables]; this is the shared ray logic.
 *
 * @param meshes candidate meshes to intersect (cards, tiles, …).
 * @return the nearest hit's `userData`, or `null`.
 */
internal fun raycastPointer(meshes: Array<dynamic>): dynamic {
    val camera = ovCamera ?: return null
    if (meshes.isEmpty()) return null
    ovRaycaster.setFromCamera(ovPointerNdc, camera)
    val hits = ovRaycaster.intersectObjects(meshes, false)
    if (hits.isEmpty()) return null
    // NOTE: hits[0] is already `dynamic` — do NOT call .asDynamic() on it.
    // On a dynamic receiver that compiles to a literal JS `.asDynamic()`
    // method call (which doesn't exist) instead of the Kotlin intrinsic;
    // the resulting TypeError once killed the render loop the moment the
    // pointer hovered a card. Index access reads the `object` property
    // (a Kotlin keyword) directly.
    val hit = hits[0]
    return hit["object"].userData
}

/** Sizes the renderer's drawing buffer + camera aspect to the viewport. */
internal fun layoutRenderer() {
    val renderer = ovRenderer ?: return
    val camera = ovCamera ?: return
    val w = window.innerWidth
    val h = window.innerHeight
    renderer.setSize(w, h, false)
    camera.aspect = w.toDouble() / h.toDouble()
    camera.updateProjectionMatrix()
}

/** Smoothstep ease shared by the styles' split / dive / zoom animations. */
internal fun ease(p: Double): Double = p * p * (3.0 - 2.0 * p)
