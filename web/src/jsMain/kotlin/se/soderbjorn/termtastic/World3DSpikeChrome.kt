/*
 * Split from World3DSpike.kt — overlay chrome: buttons, badges, nav label, shortcuts legend.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.termtastic

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
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.CSS3DRenderer
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.Scene

/** Builds the ✕ close button, the select badge, and the shortcuts legend. (Navigation is keyboard-only.) */
internal fun buildRingChrome(overlay: HTMLElement) {
    val close = document.createElement("button") as HTMLElement
    close.textContent = "✕"
    close.style.cssText = "position:absolute;top:14px;right:16px;z-index:3;pointer-events:auto;" +
        "width:34px;height:34px;border-radius:8px;border:1px solid #33445e;background:#111a26;" +
        "color:#cbd6e6;font-size:16px;cursor:pointer;"
    close.addEventListener("click", { closeWorld3dSpike() })
    overlay.appendChild(close)

    val badge = document.createElement("div") as HTMLElement
    badge.textContent = "SELECT MODE · drag to select · ⌥⌘S to exit"
    badge.style.cssText = "position:absolute;top:14px;left:24px;z-index:3;" +
        "pointer-events:none;padding:5px 12px;border-radius:14px;border:1px solid #1f5f42;" +
        "background:#0f2018cc;color:#4bd08b;font:600 12px ui-monospace,Menlo,monospace;" +
        "opacity:0;transition:opacity 160ms ease;"
    overlay.appendChild(badge)
    spikeModeBadge = badge

    val fly = document.createElement("div") as HTMLElement
    // Just the mode indicator — the full control list lives in the fly-mode
    // shortcuts legend (bottom-left, toggled with `k` like the navigate one).
    fly.textContent = "FREE FLY · F to land · k shortcuts"
    fly.style.cssText = "position:absolute;top:14px;left:50%;transform:translateX(-50%);z-index:3;" +
        "pointer-events:none;padding:5px 14px;border-radius:14px;border:1px solid #2a4b6e;" +
        "background:#0d1a2acc;color:#6bc6ff;font:600 12px ui-monospace,Menlo,monospace;white-space:nowrap;" +
        "opacity:0;transition:opacity 160ms ease;"
    overlay.appendChild(fly)
    spikeFlyBadge = fly

    // Amber confirm banner for the two-press pane/tab removal — bottom-centre, clear
    // of the top badges and the bottom-left legend. Hidden until a removal is armed.
    val confirm = document.createElement("div") as HTMLElement
    confirm.style.cssText = "position:absolute;bottom:22px;left:50%;transform:translateX(-50%);z-index:4;" +
        "pointer-events:none;padding:7px 16px;border-radius:14px;border:1px solid #7a4a1a;" +
        "background:#2a1a0dcc;color:#f2b661;font:600 12px ui-monospace,Menlo,monospace;white-space:nowrap;" +
        "box-shadow:0 4px 24px rgba(0,0,0,0.5);opacity:0;transition:opacity 140ms ease;"
    overlay.appendChild(confirm)
    spikeConfirmBadge = confirm

    // "Now showing" pane-name label — fades in on navigation, fades out fast.
    // Anchored top-left but pushed down enough to clear the macOS traffic lights.
    val nav = document.createElement("div") as HTMLElement
    nav.style.cssText = "position:absolute;top:48px;left:22px;z-index:4;pointer-events:none;" +
        "text-align:left;opacity:0;transition:opacity 150ms ease;"
    overlay.appendChild(nav)
    spikeNavLabel = nav

    buildShortcutsLegend(overlay)
}

/**
 * Flashes the current front pane's name in the big centre label: rebuilds its
 * content (pane title + tab name), fades it in, and schedules a quick fade-out —
 * a "now showing" cue as you cycle panes/tabs. Restarts the fade on every call so
 * rapid cycling always shows the latest.
 */
internal fun showNavLabel() {
    val label = spikeNavLabel ?: return
    val p = spikePanes.getOrNull(frontIndex()) ?: return
    while (label.firstChild != null) label.removeChild(label.firstChild!!)

    // Show the **tab name** prominently (falling back to the pane title when there
    // is no tab name); the pane title becomes the small subtitle for context.
    val big = document.createElement("div") as HTMLElement
    big.textContent = p.tabTitle.ifBlank { p.title }
    big.style.cssText = "font:700 26px ui-monospace,Menlo,monospace;color:#eef3fb;" +
        "letter-spacing:0.5px;text-shadow:0 2px 20px rgba(0,0,0,0.9);"
    label.appendChild(big)
    if (p.tabTitle.isNotBlank()) {
        val small = document.createElement("div") as HTMLElement
        small.textContent = p.title
        small.style.cssText = "margin-top:6px;font:600 15px ui-monospace,Menlo,monospace;" +
            "color:#9fb2d0;text-shadow:0 1px 12px rgba(0,0,0,0.85);"
        label.appendChild(small)
    }

    spikeNavLabelTimer?.let { window.clearTimeout(it) }
    label.style.transition = "opacity 150ms ease"
    label.style.opacity = "1"
    spikeNavLabelTimer = window.setTimeout({
        label.style.transition = "opacity 800ms ease"
        label.style.opacity = "0"
    }, 2200)
}

/**
 * One row of a shortcuts legend.
 *
 * @property id stable identifier used by [flashShortcut] to light this row
 *   up when its key is pressed; referenced from [buildKeyHandler] branches.
 * @property keys space-separated keycaps rendered in the key column.
 * @property action the human-readable action description.
 */
internal class SpikeShortcut(
    val id: String,
    val keys: String,
    val action: String,
)

/**
 * The canonical list of every world-mode keyboard shortcut, rendered into the
 * on-screen legend by [buildShortcutsLegend]. Kept as one table so the visible
 * documentation and the [buildKeyHandler] bindings can't silently drift — add a
 * key here whenever you add one to the handler (with an id, so the handler can
 * flash the row via [flashShortcut]).
 */
internal val SPIKE_SHORTCUTS: List<SpikeShortcut> = listOf(
    SpikeShortcut("pane", "← →", "Switch window"),
    SpikeShortcut("tabs", "↑ ↓", "Go around tabs"),
    SpikeShortcut("move-pane", "⇧ ← →", "Move window in tab"),
    SpikeShortcut("move-tab", "⇧ ↑ ↓", "Move tab"),
    SpikeShortcut("engage", "⏎", "Engage / type"),
    SpikeShortcut("disengage", "⌥ ⌘ X", "Back out (stop typing)"),
    SpikeShortcut("stash", "␣", "Stash window / unstash nearest"),
    SpikeShortcut("stash-view", "v", "Fly to stash shelf / back (no stash)"),
    SpikeShortcut("shelf-browse", "← →", "At the shelf: browse stashed windows"),
    SpikeShortcut("new-tab", "t", "New tab"),
    SpikeShortcut("new-pane", "n", "New window (this tab)"),
    SpikeShortcut("remove", "⌥ X ×2", "Remove window / empty tab (press twice)"),
    SpikeShortcut("zoom", "+ −", "Zoom window"),
    SpikeShortcut("zoom-reset", "0", "Reset zoom"),
    SpikeShortcut("zoom-preset", "⇧+ ⇧− ⇧0", "Zoom: fit screen / min / 1:1"),
    SpikeShortcut("grid-w", ", .", "Grid width (cols)"),
    SpikeShortcut("grid-h", "< >", "Grid height (rows)"),
    SpikeShortcut("reformat", "r", "Reformat window"),
    SpikeShortcut("selection", "⌥ ⌘ S", "Selection mode"),
    SpikeShortcut("signal", "w", "Cycle signal: off → working → needs-input"),
    SpikeShortcut("working-style", "g", "Working style: both → glow → dots"),
    SpikeShortcut("bob", "b", "Toggle window bob"),
    SpikeShortcut("fly", "F", "Free-fly camera"),
    SpikeShortcut("tilt", "j", "Tilt view (slight angle)"),
    SpikeShortcut("cam-home", "c", "Fly camera home"),
    SpikeShortcut("legend", "k", "Hide shortcuts"),
    SpikeShortcut("close", "⎋", "Close world"),
)

/**
 * Every free-fly-mode shortcut, rendered into the fly legend (shown in place
 * of [SPIKE_SHORTCUTS] while flying). Same drift rule: keep in lock-step
 * with the fly-mode branch of [buildKeyHandler] (held-key rows are flashed
 * via [flyShortcutIdForCode]).
 */
internal val SPIKE_FLY_SHORTCUTS: List<SpikeShortcut> = listOf(
    SpikeShortcut("fly-throttle", "W S", "Throttle forward / reverse"),
    SpikeShortcut("fly-strafe", "A D", "Strafe left / right"),
    SpikeShortcut("fly-updown", "␣ ⇧", "Up / down"),
    SpikeShortcut("fly-pitch", "↑ ↓", "Pitch"),
    SpikeShortcut("fly-yaw", "← →", "Yaw"),
    SpikeShortcut("fly-roll", "Q E", "Roll"),
    SpikeShortcut("fly-behind", "B", "Glide behind nearest window"),
    SpikeShortcut("fly-beside", "N", "Beside window (three-quarter)"),
    SpikeShortcut("fly-over", "O", "Over window"),
    SpikeShortcut("fly-under", "U", "Under window"),
    SpikeShortcut("fly-land", "F", "Land (back to navigate)"),
    SpikeShortcut("fly-legend", "k", "Hide shortcuts"),
    SpikeShortcut("fly-close", "⎋", "Close world"),
)

/**
 * Maps a held fly-movement key `code` ([FLY_KEY_CODES]) to its legend row id,
 * so [buildKeyHandler]'s fly branch can flash the matching entry.
 *
 * @param code the physical `KeyboardEvent.code`.
 * @return the [SPIKE_FLY_SHORTCUTS] id, or `null` for codes without a row.
 */
internal fun flyShortcutIdForCode(code: String): String? = when (code) {
    "KeyW", "KeyS" -> "fly-throttle"
    "KeyA", "KeyD" -> "fly-strafe"
    "Space", "ShiftLeft", "ShiftRight" -> "fly-updown"
    "ArrowUp", "ArrowDown" -> "fly-pitch"
    "ArrowLeft", "ArrowRight" -> "fly-yaw"
    "KeyQ", "KeyE" -> "fly-roll"
    else -> null
}

/**
 * Builds both bottom-left **shortcuts tables** — the navigate-mode legend of
 * [SPIKE_SHORTCUTS] and the fly-mode legend of [SPIKE_FLY_SHORTCUTS], titled,
 * translucent, with keycap-styled keys — so every binding is documented in the
 * 3D world itself. Purely informational (pointer-events none). Which panel is
 * visible is decided by [updateLegendVisibility] (mode + the shared
 * [spikeLegendHidden] flag).
 *
 * @param overlay the spike overlay to append the legends to.
 */
internal fun buildShortcutsLegend(overlay: HTMLElement) {
    spikeLegendRows.clear()
    spikeFlyLegendRows.clear()
    spikeLegendPanel = buildLegendPanel(overlay, "SHORTCUTS", SPIKE_SHORTCUTS, spikeLegendRows)
    spikeFlyLegendPanel = buildLegendPanel(overlay, "FREE FLY", SPIKE_FLY_SHORTCUTS, spikeFlyLegendRows)
    updateLegendVisibility()
}

/**
 * Builds one legend panel (heading + keycap table) and registers its rows
 * for the keypress flash.
 *
 * @param overlay the spike overlay to append to.
 * @param title the small uppercase heading.
 * @param shortcuts the rows to render.
 * @param rows the id→row map to fill for [flashShortcut].
 * @return the panel element.
 */
private fun buildLegendPanel(
    overlay: HTMLElement,
    title: String,
    shortcuts: List<SpikeShortcut>,
    rows: MutableMap<String, HTMLElement>,
): HTMLElement {
    val panel = document.createElement("div") as HTMLElement
    panel.style.cssText = "position:absolute;left:16px;bottom:14px;z-index:3;pointer-events:none;" +
        "padding:6px 8px;border-radius:10px;border:1px solid #2a3242;background:#0b0f16cc;" +
        "font:10px ui-monospace,Menlo,monospace;color:#a9bad4;box-shadow:0 6px 24px rgba(0,0,0,0.45);"

    val heading = document.createElement("div") as HTMLElement
    heading.textContent = title
    heading.style.cssText = "font-weight:700;font-size:9px;letter-spacing:1.5px;" +
        "color:#6d80a0;margin-bottom:5px;"
    panel.appendChild(heading)

    val table = document.createElement("table") as HTMLElement
    table.style.cssText = "border-collapse:separate;border-spacing:0 2px;"
    for (shortcut in shortcuts) {
        val row = document.createElement("tr") as HTMLElement
        val keyCell = document.createElement("td") as HTMLElement
        keyCell.style.cssText = "padding:0 8px 0 0;white-space:nowrap;vertical-align:middle;"
        for (k in shortcut.keys.split(" ")) {
            val cap = document.createElement("span") as HTMLElement
            cap.textContent = k
            cap.style.cssText = "display:inline-block;min-width:12px;text-align:center;margin-right:3px;" +
                "padding:1px 4px;border-radius:4px;border:1px solid #38445c;background:#171e2b;" +
                "color:#d3ddec;font-weight:600;"
            keyCell.appendChild(cap)
        }
        val actCell = document.createElement("td") as HTMLElement
        actCell.textContent = shortcut.action
        actCell.style.cssText = "vertical-align:middle;color:#a9bad4;"
        row.appendChild(keyCell)
        row.appendChild(actCell)
        table.appendChild(row)
        rows[shortcut.id] = row
    }
    panel.appendChild(table)
    overlay.appendChild(panel)
    return panel
}

/**
 * Shows/hides the two legend panels: the navigate legend when not flying,
 * the fly legend while flying — and neither when the user hid shortcuts
 * with `k` ([spikeLegendHidden], one flag for both).
 *
 * Called by [buildShortcutsLegend] on build, [toggleLegend] on `k`, and
 * [toggleFlyMode] on every mode change.
 */
internal fun updateLegendVisibility() {
    spikeLegendPanel?.style?.display = if (!spikeLegendHidden && !spikeFlyMode) "" else "none"
    spikeFlyLegendPanel?.style?.display = if (!spikeLegendHidden && spikeFlyMode) "" else "none"
}

/**
 * Flashes one legend row as pressed: the row of [id] in whichever legend is
 * active lights up immediately and fades back out shortly after, giving
 * visual feedback that the keypress was seen and what it did. Re-pressing
 * (or a held key's auto-repeat) restarts the flash, so a held fly key keeps
 * its row lit.
 *
 * Called from [buildKeyHandler] branches; a no-op when the legends are
 * hidden or the id has no row.
 *
 * @param id the [SpikeShortcut.id] of the row to flash.
 */
internal fun flashShortcut(id: String) {
    if (spikeLegendHidden) return
    val row = (if (spikeFlyMode) spikeFlyLegendRows else spikeLegendRows)[id] ?: return
    row.style.transition = "background-color 0ms"
    row.style.backgroundColor = "#2e4a75"
    (row.asDynamic().__flashTimer as? Int)?.let { window.clearTimeout(it) }
    row.asDynamic().__flashTimer = window.setTimeout({
        row.style.transition = "background-color 450ms ease"
        row.style.backgroundColor = "transparent"
    }, 160)
}
