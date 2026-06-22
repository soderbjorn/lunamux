/* PaneNavigation.kt (jsMain)
 *
 * Directional (vim-style) pane focus for the Mac/Electron app. Binds
 * Ctrl+Option+H/J/K/L and Ctrl+Option+arrow keys to move keyboard focus
 * to the pane left / below / above / right of the currently focused pane
 * in the active tab, with wrap-around at the edges.
 *
 * Replaces the darkness-toolkit's linear pane *cycle* (which lives on the
 * same Ctrl+Alt+Left/Right chord): we install a `window` capture-phase
 * keydown listener BEFORE `bootViaToolkitShell()` registers the toolkit's
 * own window-capture dispatcher, and call `stopImmediatePropagation()` on
 * the chords we handle so the toolkit's later same-target listener never
 * runs for them. The toolkit's tab cycle (Ctrl+Alt+Shift+arrows) is left
 * alone — our `!shiftKey` guard lets it fall through.
 *
 * Pane geometry is toolkit-owned (the server config coordinates can be
 * stale), so the spatial logic reads the live on-screen rectangles of the
 * `.dt-pane` elements instead. See
 * docs/superpowers/specs/2026-06-22-directional-pane-navigation-design.md.
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.math.abs
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** The four directions pane focus can move. */
internal enum class PaneDirection { LEFT, DOWN, UP, RIGHT }

/**
 * A pane's on-screen bounding box (viewport pixels). Only the geometry the
 * directional math needs; decoupled from the DOM so [pickPaneInDirection]
 * stays pure and testable.
 */
internal data class PaneRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

/**
 * Pure spatial selection: from the pane at [currentIndex], return the
 * index of the pane to move focus to in [dir], or `null` for a no-op.
 *
 * Two passes:
 *  1. **In-direction** — panes strictly in [dir] of the current pane's
 *     center, scored by `axisDistance + 2*perpendicularOffset` so a pane
 *     directly in line beats an equally-near but offset one. Closest wins.
 *  2. **Wrap** (only when no in-direction candidate and [wrap] is true) —
 *     jump to the farthest pane on the opposite edge, tie-broken by the
 *     nearest perpendicular alignment (so wrapping right from the top row
 *     lands on the top-left pane, not the bottom-left).
 *
 * Returns `null` when there are fewer than two panes or [currentIndex] is
 * out of range. Called by [navigatePane].
 *
 * @param rects every visible pane's box, in DOM order.
 * @param currentIndex index of the focused pane within [rects].
 * @param dir direction to move.
 * @param wrap whether to wrap around at the edges.
 * @return target index, or `null` for no movement.
 */
internal fun pickPaneInDirection(
    rects: List<PaneRect>,
    currentIndex: Int,
    dir: PaneDirection,
    wrap: Boolean = true,
): Int? {
    if (rects.size < 2 || currentIndex !in rects.indices) return null
    val cur = rects[currentIndex]

    fun inDirection(r: PaneRect): Boolean = when (dir) {
        PaneDirection.LEFT -> r.centerX < cur.centerX
        PaneDirection.RIGHT -> r.centerX > cur.centerX
        PaneDirection.UP -> r.centerY < cur.centerY
        PaneDirection.DOWN -> r.centerY > cur.centerY
    }
    fun primaryDist(r: PaneRect): Double = when (dir) {
        PaneDirection.LEFT, PaneDirection.RIGHT -> abs(r.centerX - cur.centerX)
        PaneDirection.UP, PaneDirection.DOWN -> abs(r.centerY - cur.centerY)
    }
    fun perpDist(r: PaneRect): Double = when (dir) {
        PaneDirection.LEFT, PaneDirection.RIGHT -> abs(r.centerY - cur.centerY)
        PaneDirection.UP, PaneDirection.DOWN -> abs(r.centerX - cur.centerX)
    }

    val inDir = rects.indices.filter { it != currentIndex && inDirection(rects[it]) }
    if (inDir.isNotEmpty()) {
        return inDir.minByOrNull { primaryDist(rects[it]) + 2.0 * perpDist(rects[it]) }
    }
    if (!wrap) return null

    // Wrap: the opposite-edge extreme dominates (weighted far above any
    // perpendicular offset), with perpendicular alignment as the tiebreak.
    val others = rects.indices.filter { it != currentIndex }
    return others.minByOrNull { idx ->
        val r = rects[idx]
        val extreme = when (dir) {
            PaneDirection.RIGHT -> r.centerX       // wrap to leftmost
            PaneDirection.LEFT -> -r.centerX       // wrap to rightmost
            PaneDirection.DOWN -> r.centerY        // wrap to topmost
            PaneDirection.UP -> -r.centerY         // wrap to bottommost
        }
        extreme * 1_000_000.0 + perpDist(r)
    }
}

/**
 * Collects the active tab's pane wrappers (`.dt-pane`). Only the active
 * tab's panes are in the DOM, so no tab filtering is needed. Minimized
 * panes are already absent (the toolkit renders them as dock items, not
 * `.dt-pane`). Zero-size rects are dropped defensively so a pane that is
 * mid-collapse-animation can't become a target. Note a sibling *covered*
 * by a maximized pane keeps its full rect here and IS a valid target —
 * focusing it un-maximizes the cover server-side (see [navigatePane]).
 */
private fun visiblePaneElements(): List<HTMLElement> {
    val nodes = document.querySelectorAll(".dt-pane")
    val out = ArrayList<HTMLElement>()
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as? HTMLElement ?: continue
        val r = el.getBoundingClientRect()
        if ((r.right - r.left) > 0.0 && (r.bottom - r.top) > 0.0) out.add(el)
    }
    return out
}

/**
 * Resolves the focused pane's index among [els]: the `.dt-pane-focused`
 * element if present, else the active tab's persisted `focusedPaneId`. If
 * neither resolves (focus is on non-pane chrome, or the snapshot lags the
 * DOM), it returns 0 as a hard last-resort anchor so the next directional
 * press still does something predictable rather than no-op'ing.
 */
private fun focusedIndex(els: List<HTMLElement>, tabId: String): Int {
    val byClass = els.indexOfFirst { it.classList.contains("dt-pane-focused") }
    if (byClass >= 0) return byClass
    val saved = savedFocusedPaneId(tabId)
    val byAttr = if (saved != null) els.indexOfFirst { it.getAttribute("data-pane-id") == saved } else -1
    return if (byAttr >= 0) byAttr else 0
}

/**
 * Moves keyboard focus to the pane in [dir]. No-op when there is no active
 * tab, fewer than two visible panes, or no distinct target.
 *
 * Sends `SetFocusedPane` only — deliberately NOT `RaisePane`. This matches
 * the darkness-toolkit's own pane-cycle hotkeys (the Ctrl+Alt+Left/Right
 * binding this replaces), which changed focus without re-stacking z-order;
 * only an explicit pane *click* (sidebar `onPaneSelect`) raises. It also
 * means focus echoes don't churn the persisted layout z-order on every
 * keystroke. If the target is currently covered by a *maximized* sibling,
 * the server's [se.soderbjorn.termtastic.WindowCommand.SetFocusedPane]
 * handler clears that sibling's maximize flag, so the newly-focused pane
 * becomes visible (again matching the cycle's auto-unmaximize).
 *
 * Called by the keydown listener installed in [installDirectionalPaneNav].
 */
private fun navigatePane(dir: PaneDirection) {
    val tabId = latestWindowConfig?.activeTabId ?: return
    val els = visiblePaneElements()
    if (els.size < 2) return
    val rects = els.map {
        val r = it.getBoundingClientRect()
        PaneRect(left = r.left, top = r.top, right = r.right, bottom = r.bottom)
    }
    val targetIdx = pickPaneInDirection(rects, focusedIndex(els, tabId), dir, wrap = true) ?: return
    val targetId = els[targetIdx].getAttribute("data-pane-id") ?: return
    launchCmd(WindowCommand.SetFocusedPane(tabId = tabId, paneId = targetId))
}

/**
 * Maps a physical key [code] to a [PaneDirection]. We key off
 * `KeyboardEvent.code` (not `.key`) because macOS Option mutates `.key`
 * (⌥H → "˙") while `.code` stays layout/modifier independent.
 */
private fun directionForCode(code: String): PaneDirection? = when (code) {
    "KeyH", "ArrowLeft" -> PaneDirection.LEFT
    "KeyJ", "ArrowDown" -> PaneDirection.DOWN
    "KeyK", "ArrowUp" -> PaneDirection.UP
    "KeyL", "ArrowRight" -> PaneDirection.RIGHT
    else -> null
}

/** Guards against double-installation across hot reloads / re-entry. */
private var paneNavInstalled = false

/**
 * Installs the directional pane-navigation keydown listener.
 *
 * MUST be called from [start] BEFORE [bootViaToolkitShell] so this
 * `window` capture-phase listener is registered ahead of the toolkit's
 * own window-capture hotkey dispatcher; combined with
 * `stopImmediatePropagation()` on handled chords, that lets us preempt
 * the toolkit's pane-cycle binding on Ctrl+Alt+Left/Right. Gated to the
 * Electron/Mac app at the call site.
 *
 * Handles only `Ctrl+Alt+(H/J/K/L | arrow)` with neither Meta nor Shift;
 * everything else (plain terminal input, the Ctrl+Alt+Shift tab cycle)
 * passes through untouched.
 */
internal fun installDirectionalPaneNav() {
    if (paneNavInstalled) return
    paneNavInstalled = true
    window.asDynamic().addEventListener("keydown", { ev: Event ->
        val e = ev as? KeyboardEvent ?: return@addEventListener
        if (!e.ctrlKey || !e.altKey || e.metaKey || e.shiftKey) return@addEventListener
        val dir = directionForCode(e.code) ?: return@addEventListener
        e.preventDefault()
        // stopImmediatePropagation (not just stopPropagation) blocks every
        // OTHER window-capture keydown listener for this chord — that is how
        // we preempt the toolkit's pane-cycle dispatcher. Load-bearing
        // caveat: because we registered first, ANY window keydown listener
        // added later (toolkit or termtastic) will never see Ctrl+Alt+
        // (H/J/K/L|arrows). Nothing else needs them today; if that changes,
        // narrow this swallow.
        e.stopImmediatePropagation()
        navigatePane(dir)
    }, true)
}
