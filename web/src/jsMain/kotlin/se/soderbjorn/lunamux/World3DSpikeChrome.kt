/*
 * Split from World3DSpike.kt — overlay chrome: buttons, badges, nav label, shortcuts legend.
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
 * In the **web demo** — demo mode in a plain browser ([isDemoClient] without
 * [isElectronClient]), i.e. the marketing site's embedded iframe — the same
 * bottom-left column also carries the big **"Play demo tour" button**
 * ([buildDemoTourButton]) stacked above whichever legend is visible. The
 * Electron demo launch keeps the tour reachable through the secret ⌥⌘M chord
 * only, and outside demo mode the tour has no simulated sessions to drive,
 * so no button is built in either case.
 *
 * @param overlay the spike overlay to append the legends to.
 */
internal fun buildShortcutsLegend(overlay: HTMLElement) {
    spikeLegendRows.clear()
    spikeFlyLegendRows.clear()
    // Bottom-left flex column: the tour button (when present) stacks directly
    // above whichever legend panel is visible, tracking that panel's height
    // instead of overlapping it at a guessed offset.
    val column = document.createElement("div") as HTMLElement
    column.style.cssText = "position:absolute;left:16px;bottom:14px;z-index:3;pointer-events:none;" +
        "display:flex;flex-direction:column;align-items:flex-start;gap:10px;"
    overlay.appendChild(column)
    if (isDemoClient && !isElectronClient) buildDemoTourButton(column)
    spikeLegendPanel = buildLegendPanel(column, "SHORTCUTS", SPIKE_SHORTCUTS, spikeLegendRows)
    spikeFlyLegendPanel = buildLegendPanel(column, "FREE FLY", SPIKE_FLY_SHORTCUTS, spikeFlyLegendRows)
    updateLegendVisibility()
}

/** Idle label of the demo-tour button. @see updateDemoTourButton */
private const val PLAY_TOUR_LABEL = "▶ Play demo tour"

/** Label of the demo-tour button while the tour runs. @see updateDemoTourButton */
private const val STOP_TOUR_LABEL = "■ Stop demo tour"

/**
 * Builds the big **"Play demo tour"** button above the shortcuts legend — the
 * clickable twin of the secret ⌥⌘M chord, wired straight to [toggleDemoMovie].
 * Web demo only ([isDemoClient] and not [isElectronClient], checked by the
 * caller [buildShortcutsLegend]) — in the Electron demo the tour stays a
 * hotkey-only secret.
 * For its first ~15 s the button pulses gently (a slow scale + green-glow
 * swell) to draw the visitor's eye, then holds still
 * ([spikeDemoTourPulseTimer]); starting the tour ends the pulse early
 * ([updateDemoTourButton]). A small dim line under the button notes the tour
 * is optional — the legend keys are live for the visitor's own hands.
 *
 * @param parent the bottom-left chrome column to append the button to.
 */
private fun buildDemoTourButton(parent: HTMLElement) {
    // The attention pulse needs @keyframes, which inline `style=` cannot
    // declare — so the keyframes ride in a <style> that lives and dies with
    // the chrome column.
    // A slow, gentle swell — attention-drawing without being disruptive.
    val pulse = document.createElement("style") as HTMLElement
    pulse.textContent = "@keyframes tt-demo-tour-pulse{" +
        "0%,100%{transform:scale(1);box-shadow:0 6px 24px rgba(0,0,0,0.45);}" +
        "50%{transform:scale(1.04);box-shadow:0 0 14px rgba(75,208,139,0.45),0 6px 24px rgba(0,0,0,0.45);}}"
    parent.appendChild(pulse)

    val btn = document.createElement("button") as HTMLElement
    btn.textContent = PLAY_TOUR_LABEL
    btn.style.cssText = "pointer-events:auto;cursor:pointer;padding:12px 22px;border-radius:12px;" +
        "border:1px solid #1f5f42;background:#0f2018e6;color:#4bd08b;" +
        "font:700 15px ui-monospace,Menlo,monospace;box-shadow:0 6px 24px rgba(0,0,0,0.45);" +
        "animation:tt-demo-tour-pulse 3s ease-in-out infinite;"
    btn.addEventListener("click", {
        // Drop focus so the button can't be re-activated by a later keypress.
        btn.blur()
        toggleDemoMovie()
    })
    parent.appendChild(btn)
    spikeDemoTourButton = btn

    // Small reassurance under the button: the tour is optional, the keys in
    // the legend below are live for the visitor's own hands.
    val hint = document.createElement("div") as HTMLElement
    hint.textContent = "or explore yourself — the keys below all work"
    hint.style.cssText = "pointer-events:none;margin-top:-4px;" +
        "font:400 10px ui-monospace,Menlo,monospace;color:#6d80a0;"
    parent.appendChild(hint)
    spikeDemoTourHint = hint

    spikeDemoTourPulseTimer = window.setTimeout({ stopDemoTourPulse() }, 15_000)
}

/**
 * Ends the tour button's attention pulse: clears the 15 s timer and removes
 * the keyframe animation. Idempotent — reached from the timer itself and
 * from [updateDemoTourButton] when the tour starts.
 */
private fun stopDemoTourPulse() {
    spikeDemoTourPulseTimer?.let { window.clearTimeout(it) }
    spikeDemoTourPulseTimer = null
    spikeDemoTourButton?.style?.removeProperty("animation")
}

/**
 * Syncs the demo-tour button's label with the tour state ([spikeMovieJob]):
 * "Play" when idle, "Stop" while the tour runs — so the one button both
 * starts and stops the tour, exactly like ⌥⌘M. A running tour also ends the
 * attention pulse; the button has done its job. Called from [toggleDemoMovie]
 * on start and [movieCleanup] on every stop; a no-op when there is no button
 * (non-demo mode, or the world is closed).
 */
internal fun updateDemoTourButton() {
    val btn = spikeDemoTourButton ?: return
    val running = spikeMovieJob != null
    btn.textContent = if (running) STOP_TOUR_LABEL else PLAY_TOUR_LABEL
    // The "explore yourself" line promises live keys — false while the tour
    // has them locked out, so it hides for the duration.
    spikeDemoTourHint?.style?.display = if (running) "none" else ""
    if (running) stopDemoTourPulse()
}

/**
 * Builds one legend panel (heading + keycap table) and registers its rows
 * for the keypress flash. Positioning comes from the bottom-left flex column
 * built by [buildShortcutsLegend], not from the panel itself.
 *
 * @param parent the chrome column to append to.
 * @param title the small uppercase heading.
 * @param shortcuts the rows to render.
 * @param rows the id→row map to fill for [flashShortcut].
 * @return the panel element.
 */
private fun buildLegendPanel(
    parent: HTMLElement,
    title: String,
    shortcuts: List<SpikeShortcut>,
    rows: MutableMap<String, HTMLElement>,
): HTMLElement {
    val panel = document.createElement("div") as HTMLElement
    panel.style.cssText = "pointer-events:none;" +
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
    parent.appendChild(panel)
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
