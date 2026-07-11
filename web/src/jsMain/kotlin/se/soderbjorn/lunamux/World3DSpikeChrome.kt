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
    // Anchored top-left, pushed down below the traffic lights *and* clear of the warp-core
    // status banner (top:16px, ~33px tall) that now sits at the top-left corner.
    val nav = document.createElement("div") as HTMLElement
    nav.style.cssText = "position:absolute;top:82px;left:22px;z-index:4;pointer-events:none;" +
        "text-align:left;opacity:0;transition:opacity 150ms ease;"
    overlay.appendChild(nav)
    spikeNavLabel = nav

    buildShortcutsLegend(overlay)
}

/**
 * Flashes the current **front ring pane's** name in the big top-left label — the
 * "now showing" cue at the command center. Thin wrapper over [showNavLabelFor].
 */
internal fun showNavLabel() {
    val p = spikePanes.getOrNull(frontIndex()) ?: return
    showNavLabelFor(p)
}

/**
 * Flashes [p]'s name in the big top-left label: rebuilds its content (tab name +
 * pane title), fades it in, and schedules a quick fade-out — a "now showing" cue.
 * Restarts the fade on every call so rapid cycling always shows the latest. Used at
 * the command center for the fronted ring pane ([showNavLabel]) *and* up at the dock
 * for the browsed/stashed pane ([shelfBrowse]/[stashFront]/[toggleStashView]), so the
 * dock names its focused window in the same white text the command center does.
 *
 * @param p the pane whose name to display.
 * @see showNavLabel @see shelfBrowse
 */
internal fun showNavLabelFor(p: RingPane) {
    val label = spikeNavLabel ?: return
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
 * A titled cluster of related [SpikeShortcut] rows within a legend panel. The
 * legend renders each section's rows under a small caption, with a thin divider
 * line separating one section from the next ([buildLegendPanel]), so related keys
 * read as a group instead of one long flat list.
 *
 * @property title the small uppercase caption shown above the section's rows.
 * @property shortcuts the section's rows, in display order.
 */
internal class SpikeShortcutSection(
    val title: String,
    val shortcuts: List<SpikeShortcut>,
)

/**
 * The canonical list of every command-center keyboard shortcut, grouped into
 * intuitive sections and rendered into the on-screen legend by
 * [buildShortcutsLegend]. Kept as one table so the visible documentation and the
 * [buildKeyHandler] bindings can't silently drift — add a key here whenever you
 * add one to the handler (with an id, so the handler can flash the row via
 * [flashShortcut]). Ordered the way you actually reach for the keys: move through
 * the ring, arrange tabs/windows, size the selected window, swing the camera, then
 * the session/system keys.
 */
internal val SPIKE_SHORTCUT_SECTIONS: List<SpikeShortcutSection> = listOf(
    SpikeShortcutSection("NAVIGATE", listOf(
        SpikeShortcut("pane", "← →", "Switch window"),
        SpikeShortcut("tabs", "↑ ↓", "Go around tabs"),
    )),
    SpikeShortcutSection("ARRANGE", listOf(
        SpikeShortcut("new-tab", "t", "New tab"),
        SpikeShortcut("new-pane", "n", "New window (this tab)"),
        SpikeShortcut("move-pane", "⇧ ← →", "Move window in tab"),
        SpikeShortcut("move-tab", "⇧ ↑ ↓", "Move tab"),
        SpikeShortcut("remove", "⌥ X ×2", "Remove window / empty tab (press twice)"),
    )),
    SpikeShortcutSection("WINDOW", listOf(
        SpikeShortcut("engage", "⏎", "Engage / type"),
        SpikeShortcut("disengage", "⌥ ⌘ X", "Back out (stop typing)"),
        SpikeShortcut("stash", "␣", "Dock or undock window"),
        SpikeShortcut("stash-tab", "⌃ ␣", "Unlist tab to dock / bring back"),
        SpikeShortcut("zoom", "+ −", "Zoom window"),
        SpikeShortcut("zoom-reset", "0", "Reset zoom"),
        SpikeShortcut("zoom-preset", "⇧+ ⇧− ⇧0", "Zoom: fit screen / min / 1:1"),
        SpikeShortcut("grid-w", ", .", "Grid width (cols)"),
        SpikeShortcut("grid-h", "< >", "Grid height (rows)"),
        SpikeShortcut("reformat", "r", "Reformat window"),
    )),
    SpikeShortcutSection("CAMERA", listOf(
        SpikeShortcut("fly-front", "H", "Front of window"),
        SpikeShortcut("fly-behind", "B", "Behind window"),
        SpikeShortcut("fly-beside", "G", "Beside window"),
        SpikeShortcut("fly-over", "O", "Over window"),
        SpikeShortcut("fly-under", "U", "Under window"),
        SpikeShortcut("tilt", "j", "Tilt view (slight angle)"),
        SpikeShortcut("overview", "m", "Overview of whole world"),
        SpikeShortcut("cam-home", "c", "Fly camera home"),
        SpikeShortcut("fly", "F", "Free flight"),
        SpikeShortcut("stash-view", "v", "Fly to dock ship / back"),
        SpikeShortcut("shelf-browse", "← →", "At the dock ship: browse windows"),
    )),
    SpikeShortcutSection("SYSTEM", listOf(
        SpikeShortcut("selection", "⌥ ⌘ S", "Selection mode"),
        SpikeShortcut("signal", "w", "Cycle signal: off → working → needs-input"),
        SpikeShortcut("screenshot", "P", "Screenshot to Desktop"),
        SpikeShortcut("recording", "⇧R", "Record to Desktop (toggle)"),
        SpikeShortcut("settings", "⌥ ⌘ ,", "3D settings"),
        SpikeShortcut("legend", "k", "Hide shortcuts"),
        SpikeShortcut("close", "⎋", "Close world"),
    )),
)

/**
 * Every free-fly-mode shortcut, grouped into sections and rendered into the fly
 * legend (shown in place of [SPIKE_SHORTCUT_SECTIONS] while flying). Same drift
 * rule: keep in lock-step with the fly-mode branch of [buildKeyHandler] (held-key
 * rows are flashed via [flyShortcutIdForCode]). Ordered as you fly: pilot the
 * ship, swing the camera / land, act on the window you're nearest, then system.
 */
internal val SPIKE_FLY_SHORTCUT_SECTIONS: List<SpikeShortcutSection> = listOf(
    SpikeShortcutSection("FLY", listOf(
        SpikeShortcut("fly-throttle", "W S", "Throttle forward / reverse"),
        SpikeShortcut("fly-strafe", "A D", "Strafe left / right"),
        SpikeShortcut("fly-down", "⇧", "Descend"),
        SpikeShortcut("fly-pitch", "↑ ↓", "Pitch"),
        SpikeShortcut("fly-yaw", "← →", "Yaw"),
        SpikeShortcut("fly-roll", "Q E", "Roll"),
    )),
    SpikeShortcutSection("CAMERA", listOf(
        SpikeShortcut("fly-front", "H", "Front of window"),
        SpikeShortcut("fly-behind", "B", "Behind window"),
        SpikeShortcut("fly-beside", "G", "Beside window"),
        SpikeShortcut("fly-over", "O", "Over window"),
        SpikeShortcut("fly-under", "U", "Under window"),
        SpikeShortcut("tilt", "j", "Tilt view"),
        SpikeShortcut("overview", "m", "Overview of whole world"),
        SpikeShortcut("cam-home", "c", "Fly camera home"),
        SpikeShortcut("fly-land", "F", "Command center (land)"),
        SpikeShortcut("stash-view", "v", "Fly to dock ship / back"),
    )),
    SpikeShortcutSection("WINDOW", listOf(
        SpikeShortcut("engage", "⏎", "Engage / type"),
        SpikeShortcut("stash", "␣", "Dock or undock window"),
        SpikeShortcut("stash-tab", "⌃ ␣", "Unlist tab to dock / bring back"),
        SpikeShortcut("zoom", "+ −", "Zoom window"),
        SpikeShortcut("zoom-reset", "0", "Reset zoom"),
        SpikeShortcut("grid-w", ", .", "Grid width (cols)"),
        SpikeShortcut("grid-h", "< >", "Grid height (rows)"),
        SpikeShortcut("reformat", "r", "Reformat window"),
    )),
    SpikeShortcutSection("SYSTEM", listOf(
        SpikeShortcut("screenshot", "P", "Screenshot to Desktop"),
        SpikeShortcut("recording", "⇧R", "Record to Desktop (toggle)"),
        SpikeShortcut("settings", "⌥ ⌘ ,", "3D settings"),
        SpikeShortcut("fly-legend", "k", "Hide shortcuts"),
        SpikeShortcut("fly-close", "⎋", "Close world"),
    )),
)

/**
 * The **engage / type** legend — the third presentation mode alongside command center
 * ([SPIKE_SHORTCUT_SECTIONS]) and free flight ([SPIKE_FLY_SHORTCUT_SECTIONS]), shown while
 * a pane is engaged ([spikeEngaged]). Engaging hands every keystroke to the focused
 * terminal, so the only world shortcut still live is the ⌥⌘X disengage chord (matched in
 * [buildKeyHandler]'s engaged branch before it returns) — everything else types. The single
 * section's caption states that plainly so the near-empty table reads as intentional.
 */
internal val SPIKE_ENGAGE_SHORTCUT_SECTIONS: List<SpikeShortcutSection> = listOf(
    SpikeShortcutSection("EVERY OTHER KEY TYPES INTO THE WINDOW", listOf(
        SpikeShortcut("disengage", "⌥ ⌘ X", "Back out (stop typing)"),
    )),
)

/**
 * Maps a held fly-movement key `code` ([FLY_KEY_CODES]) to its legend row id,
 * so [buildKeyHandler]'s fly branch can flash the matching entry.
 *
 * @param code the physical `KeyboardEvent.code`.
 * @return the [SPIKE_FLY_SHORTCUT_SECTIONS] id, or `null` for codes without a row.
 */
internal fun flyShortcutIdForCode(code: String): String? = when (code) {
    "KeyW", "KeyS" -> "fly-throttle"
    "KeyA", "KeyD" -> "fly-strafe"
    "ShiftLeft", "ShiftRight" -> "fly-down"
    "ArrowUp", "ArrowDown" -> "fly-pitch"
    "ArrowLeft", "ArrowRight" -> "fly-yaw"
    "KeyQ", "KeyE" -> "fly-roll"
    else -> null
}

/**
 * Builds both bottom-left **shortcuts tables** — the navigate-mode legend of
 * [SPIKE_SHORTCUT_SECTIONS] and the fly-mode legend of [SPIKE_FLY_SHORTCUT_SECTIONS], titled,
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
/**
 * Shortcut ids that only work in the bundled **desktop app** (Electron) — the
 * screenshot and screen-recording keys, both of which reach the filesystem
 * through `electronApi`. Their key bindings ([buildKeyHandler]) and their legend
 * rows ([hostVisibleSections]) are both suppressed outside Electron so a plain
 * browser (e.g. the web demo) never advertises or fires a key it can't service.
 */
internal val DESKTOP_ONLY_SHORTCUT_IDS = setOf("screenshot", "recording")

/**
 * Returns [sections] as-is inside the Electron desktop app, or — in a plain
 * browser — with every [DESKTOP_ONLY_SHORTCUT_IDS] row stripped out (and any
 * section left empty by that removal dropped). Keeps the on-screen legend in
 * lock-step with the host-gated key bindings so the two never advertise a key
 * the other won't honour. Called by [buildShortcutsLegend].
 *
 * @param sections the full section list for a legend panel.
 * @return the host-appropriate section list.
 */
private fun hostVisibleSections(sections: List<SpikeShortcutSection>): List<SpikeShortcutSection> {
    if (isElectronClient) return sections
    return sections.mapNotNull { sec ->
        val kept = sec.shortcuts.filter { it.id !in DESKTOP_ONLY_SHORTCUT_IDS }
        if (kept.isEmpty()) null else SpikeShortcutSection(sec.title, kept)
    }
}

internal fun buildShortcutsLegend(overlay: HTMLElement) {
    spikeLegendRows.clear()
    spikeFlyLegendRows.clear()
    spikeEngageLegendRows.clear()
    spikeLegendSectionRows.clear()
    // Bottom-left flex column: the tour button (when present) stacks directly
    // above whichever legend panel is visible, tracking that panel's height
    // instead of overlapping it at a guessed offset.
    val column = document.createElement("div") as HTMLElement
    column.style.cssText = "position:absolute;left:16px;bottom:14px;z-index:3;pointer-events:none;" +
        "display:flex;flex-direction:column;align-items:flex-start;gap:10px;"
    overlay.appendChild(column)
    if (isDemoClient && !isElectronClient) buildDemoTourButton(column)
    spikeLegendPanel = buildLegendPanel(
        column, "COMMAND CENTER", hostVisibleSections(SPIKE_SHORTCUT_SECTIONS), spikeLegendRows, spikeLegendSectionRows,
    )
    spikeFlyLegendPanel = buildLegendPanel(
        column, "FREE FLIGHT", hostVisibleSections(SPIKE_FLY_SHORTCUT_SECTIONS), spikeFlyLegendRows, sectionRows = null,
    )
    spikeEngageLegendPanel = buildLegendPanel(
        column, "ENGAGE / TYPE", SPIKE_ENGAGE_SHORTCUT_SECTIONS, spikeEngageLegendRows, sectionRows = null,
    )
    updateLegendVisibility()
}

/**
 * Opens or closes the **in-world 3D settings panel** (⌥⌘, — [buildKeyHandler]): a small
 * floating window over the 3D view carrying the very same controls as the App Settings
 * sidebar's 3D rows ([buildWorld3dSettingsRows]) — Window bobbing and Status indication —
 * so you can change them and see the effect on the world immediately (each change
 * re-syncs the live runtime flags via [syncWorld3dRuntimeFromSettings]). Toggling while
 * open closes it; a ✕ button does the same. The panel is a child of [spikeOverlay], so it
 * is torn down with the world in [closeWorld3dSpike].
 *
 * @see buildWorld3dSettingsRows @see spikeSettingsPanel
 */
internal fun toggleWorld3dSettingsPanel() {
    spikeSettingsPanel?.let { it.remove(); spikeSettingsPanel = null; return }
    val overlay = spikeOverlay ?: return

    val panel = document.createElement("div") as HTMLElement
    panel.style.cssText = "position:absolute;top:64px;left:50%;transform:translateX(-50%);z-index:6;" +
        "pointer-events:auto;min-width:320px;max-width:440px;padding:14px 16px 6px;border-radius:14px;" +
        "border:1px solid #2a3242;background:#0b0f16f2;color:#cdd8ea;" +
        "box-shadow:0 18px 60px rgba(0,0,0,0.6);font:13px ui-monospace,Menlo,monospace;"

    val header = document.createElement("div") as HTMLElement
    header.style.cssText = "display:flex;align-items:center;justify-content:space-between;" +
        "margin-bottom:6px;"
    val title = document.createElement("div") as HTMLElement
    title.textContent = "3D settings"
    title.style.cssText = "font-weight:700;font-size:11px;letter-spacing:1.5px;color:#8ea3c2;"
    val closeBtn = document.createElement("button") as HTMLElement
    (closeBtn.asDynamic()).type = "button"
    closeBtn.textContent = "✕"
    closeBtn.style.cssText = "cursor:pointer;border:none;background:transparent;color:#8ea3c2;" +
        "font:700 14px ui-monospace,Menlo,monospace;padding:2px 6px;border-radius:6px;"
    closeBtn.addEventListener("click", { toggleWorld3dSettingsPanel() })
    header.appendChild(title)
    header.appendChild(closeBtn)
    panel.appendChild(header)

    // The shared rows — same builder the App Settings sidebar uses. Each change persists
    // and then re-syncs the live world so the effect shows right away.
    val body = document.createElement("div") as HTMLElement
    buildWorld3dSettingsRows(body, ::syncWorld3dRuntimeFromSettings)
    panel.appendChild(body)

    overlay.appendChild(panel)
    spikeSettingsPanel = panel
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
 * For its first ~15 s the button pulses gently (a slow scale + accent-glow
 * swell) to draw the visitor's eye, then holds still
 * ([spikeDemoTourPulseTimer]); starting the tour ends the pulse early
 * ([updateDemoTourButton]). A small dim line under the button notes the tour
 * is optional — the legend keys are live for the visitor's own hands.
 *
 * @param parent the bottom-left chrome column to append the button to.
 */
private fun buildDemoTourButton(parent: HTMLElement) {
    // Wear the current theme's accent (matching the rest of the ring chrome —
    // title underlines, beacon glow) instead of a hardcoded green, so the
    // button reads as part of the themed world rather than a stray element.
    val accent = spikeChrome().accent

    // The attention pulse needs @keyframes, which inline `style=` cannot
    // declare — so the keyframes ride in a <style> that lives and dies with
    // the chrome column.
    // A slow, gentle swell — attention-drawing without being disruptive.
    val pulse = document.createElement("style") as HTMLElement
    pulse.textContent = "@keyframes tt-demo-tour-pulse{" +
        "0%,100%{transform:scale(1);box-shadow:0 6px 24px rgba(0,0,0,0.45);}" +
        "50%{transform:scale(1.04);box-shadow:0 0 14px $accent,0 6px 24px rgba(0,0,0,0.45);}}"
    parent.appendChild(pulse)

    val btn = document.createElement("button") as HTMLElement
    btn.textContent = PLAY_TOUR_LABEL
    btn.style.cssText = "pointer-events:auto;cursor:pointer;padding:12px 22px;border-radius:12px;" +
        "border:1px solid $accent;background:#0d1420e6;color:$accent;" +
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
 * Builds one legend panel (title heading + a keycap table grouped into
 * [SpikeShortcutSection]s) and registers its shortcut rows for the keypress
 * flash. Each section shows a small caption, and every section after the first
 * is preceded by a thin divider line, so related keys read as a cluster instead
 * of one long list. Positioning comes from the bottom-left flex column built by
 * [buildShortcutsLegend], not from the panel itself.
 *
 * @param parent the chrome column to append to.
 * @param title the small uppercase panel heading.
 * @param sections the grouped rows to render, in display order.
 * @param rows the id→row map to fill for [flashShortcut].
 * @param sectionRows if non-null, collects each section's caption + divider rows
 *   so [updateLegendVisibility] can hide them when the trimmed shelf legend is
 *   shown (only the COMMAND CENTER panel needs this).
 * @return the panel element.
 */
private fun buildLegendPanel(
    parent: HTMLElement,
    title: String,
    sections: List<SpikeShortcutSection>,
    rows: MutableMap<String, HTMLElement>,
    sectionRows: MutableList<HTMLElement>?,
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
    for ((sectionIdx, section) in sections.withIndex()) {
        // Section caption, carried on a divider-styled row so the whole group's
        // header hides together at the shelf. A thin rule sits above every
        // section but the first, visually separating one cluster from the next.
        val capRow = document.createElement("tr") as HTMLElement
        val capCell = document.createElement("td") as HTMLElement
        (capCell.asDynamic()).colSpan = 2
        val topRule = if (sectionIdx == 0) "" else
            "border-top:1px solid #232c3c;margin-top:5px;padding-top:6px;"
        capCell.style.cssText = "font-weight:700;font-size:8px;letter-spacing:1.2px;" +
            "color:#5c6f8e;padding-bottom:1px;$topRule"
        capCell.textContent = section.title
        capRow.appendChild(capCell)
        table.appendChild(capRow)
        sectionRows?.add(capRow)

        for (shortcut in section.shortcuts) {
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
    }
    panel.appendChild(table)
    parent.appendChild(panel)
    return panel
}

/**
 * The subset of navigate-mode [SPIKE_SHORTCUT_SECTIONS] ids that stay live — and visible in
 * the legend — while the camera is **up at the dock/stash shelf** ([cameraAtShelf]).
 * Up here you can only browse the docked windows, unstash one, fly back down without
 * a stash, drop into free-fly, hide the legend or close the world; the command-center
 * actions (pane/tab navigation, zoom, grid, selection, reformat, new/remove, …) have
 * no meaning at the dock, so [buildKeyHandler] ignores them and [updateLegendVisibility]
 * hides their rows. @see cameraAtShelf @see buildKeyHandler
 */
internal val SHELF_SHORTCUT_IDS: Set<String> = setOf(
    "shelf-browse", "stash", "stash-tab", "stash-view", "fly", "cam-home", "legend", "close",
)

/**
 * Shows/hides the three legend panels — one per presentation mode, at most one visible:
 * the **engage / type** legend when a pane is engaged ([spikeEngaged], which takes
 * precedence since it can be entered from either other mode), else the **free flight**
 * legend while flying ([spikeFlyMode]), else the **command center** navigate legend — and
 * none when the user hid shortcuts with `k` ([spikeLegendHidden], one flag for all). While
 * the navigate legend is up **and the camera is at the dock** ([cameraAtShelf]), it further
 * trims the table to just the dock-relevant rows ([SHELF_SHORTCUT_IDS]) — the same actions
 * [buildKeyHandler] still honours up there — so the legend never advertises a key that does
 * nothing at the dock.
 *
 * Called by [buildShortcutsLegend] on build, [toggleLegend] on `k`, [toggleFlyMode] on every
 * mode change, [activatePane]/[disengage] on engage/disengage, and the render loop when the
 * camera crosses in/out of the dock.
 */
internal fun updateLegendVisibility() {
    val engaged = spikeEngaged
    val navVisible = !spikeLegendHidden && !spikeFlyMode && !engaged
    spikeLegendPanel?.style?.display = if (navVisible) "" else "none"
    spikeFlyLegendPanel?.style?.display = if (!spikeLegendHidden && spikeFlyMode && !engaged) "" else "none"
    spikeEngageLegendPanel?.style?.display = if (!spikeLegendHidden && engaged) "" else "none"
    if (navVisible) {
        val atShelf = cameraAtShelf()
        for ((id, row) in spikeLegendRows) {
            row.style.display = if (!atShelf || id in SHELF_SHORTCUT_IDS) "" else "none"
        }
        // The section captions + dividers only make sense over the full legend; at
        // the dock the table collapses to a sparse subset, so hide the group chrome
        // rather than leave orphan headings and rules behind the surviving rows.
        for (row in spikeLegendSectionRows) {
            row.style.display = if (atShelf) "none" else ""
        }
    }
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
    val rows = when {
        spikeEngaged -> spikeEngageLegendRows
        spikeFlyMode -> spikeFlyLegendRows
        else -> spikeLegendRows
    }
    val row = rows[id] ?: return
    row.style.transition = "background-color 0ms"
    row.style.backgroundColor = "#2e4a75"
    (row.asDynamic().__flashTimer as? Int)?.let { window.clearTimeout(it) }
    row.asDynamic().__flashTimer = window.setTimeout({
        row.style.transition = "background-color 450ms ease"
        row.style.backgroundColor = "transparent"
    }, 160)
}

/**
 * Flashes a brief **status toast** centred low in the 3D overlay — a one-off confirmation
 * (e.g. "Screenshot saved to Desktop"). Fades in, holds, then fades out and removes itself.
 * Purely informational (pointer-events none). No-op if the world is closed.
 *
 * Only **one** toast is shown at a time: an earlier toast still on screen is
 * dismissed immediately (via [dismissSpikeToast]) before this one appears, so
 * confirmations never stack. The live toast is tracked in [spikeActiveToast]
 * (and its fade/removal timers in [spikeActiveToastTimers]) so it can also be
 * torn down on demand — see [captureWindowScreenshot], which clears a lingering
 * pill before snapping the window.
 *
 * @param text the message to show.
 * @see captureWindowScreenshot
 * @see dismissSpikeToast
 */
internal fun showSpikeToast(text: String) {
    val overlay = spikeOverlay ?: return
    // Never stack: drop any toast still on screen before showing the new one.
    dismissSpikeToast()
    val toast = document.createElement("div") as HTMLElement
    toast.textContent = text
    toast.style.cssText = "position:absolute;bottom:64px;left:50%;transform:translateX(-50%);z-index:6;" +
        "pointer-events:none;padding:8px 18px;border-radius:14px;border:1px solid #2a3242;" +
        "background:#0b0f16f2;color:#cdd8ea;font:600 12px ui-monospace,Menlo,monospace;white-space:nowrap;" +
        "box-shadow:0 8px 30px rgba(0,0,0,0.55);opacity:0;transition:opacity 140ms ease;"
    overlay.appendChild(toast)
    spikeActiveToast = toast
    spikeActiveToastTimers = mutableListOf()
    spikeActiveToastTimers.add(window.setTimeout({ toast.style.opacity = "1" }, 10))
    spikeActiveToastTimers.add(window.setTimeout({
        toast.style.transition = "opacity 500ms ease"
        toast.style.opacity = "0"
        spikeActiveToastTimers.add(window.setTimeout({
            toast.remove()
            if (spikeActiveToast === toast) { spikeActiveToast = null }
        }, 520))
    }, 2400))
}

/**
 * Removes the current status toast ([spikeActiveToast]) from the DOM at once and
 * cancels its pending fade/removal timers, if any. Called by [showSpikeToast]
 * before showing a new toast (so they never stack) and by [captureWindowScreenshot]
 * before it snaps the window, so an earlier "…saved to Desktop" pill can't appear
 * in the screenshot. No-op when no toast is showing.
 *
 * @see showSpikeToast
 */
internal fun dismissSpikeToast() {
    for (t in spikeActiveToastTimers) window.clearTimeout(t)
    spikeActiveToastTimers = mutableListOf()
    spikeActiveToast?.remove()
    spikeActiveToast = null
}

/**
 * Captures the **whole window** and saves a PNG to the Desktop, via the Electron main
 * process (`electronApi.saveWindowScreenshot`, which calls `webContents.capturePage`), then
 * confirms with a [showSpikeToast]. Wired to the `P` shortcut in both command center and
 * free flight ([buildKeyHandler]). Outside the bundled desktop app there is no Electron
 * bridge (e.g. the web demo), so it shows a "needs the desktop app" toast instead of failing
 * silently.
 */
internal fun captureWindowScreenshot() {
    val api = window.asDynamic().electronApi
    if (api == null || api.saveWindowScreenshot == null) {
        showSpikeToast("Screenshot needs the desktop app")
        return
    }
    // Clear any lingering "…saved to Desktop" pill from an earlier screenshot
    // before we snap, so a stale toast never ends up baked into the image. We
    // wait one animation frame after removing it so the compositor has repainted
    // the toast-free window before the main process runs `capturePage`.
    dismissSpikeToast()
    window.requestAnimationFrame {
        val promise: dynamic = api.saveWindowScreenshot()
        promise.then({ result: dynamic ->
            val path = (result as? String) ?: ""
            if (path.startsWith("!")) showSpikeToast("Screenshot failed")
            else showSpikeToast("Screenshot saved to Desktop")
        })
        promise.catch({ _: dynamic -> showSpikeToast("Screenshot failed") })
    }
}

/**
 * Toggles **screen recording** of the 3D world, saving a `.webm` to the Desktop
 * when stopped. Wired to the `⇧R` shortcut in both command center and free flight
 * ([buildKeyHandler]). First press starts recording (no toast, so the start isn't
 * captured in the video); second press stops it, writes the file, and confirms
 * with a [showSpikeToast].
 *
 * World3D is a Three.js `CSS3DRenderer` — the panes are real DOM elements, so
 * there is no WebGL canvas to `captureStream()`. Recording therefore captures
 * the composited **window**: the Electron main process resolves this window's
 * desktop-capture source id ([getWindowRecordingSourceId][saveWindowRecording]),
 * the renderer feeds it to `getUserMedia({ chromeMediaSource: "desktop" })` and a
 * `MediaRecorder`, and on stop the chunks are shipped back to main to persist.
 *
 * Outside the bundled desktop app there is no Electron bridge (e.g. the web
 * demo), so it shows a "needs the desktop app" toast instead of failing silently.
 *
 * @see startWindowRecording
 * @see stopWindowRecording
 */
internal fun toggleWindowRecording() {
    val api = window.asDynamic().electronApi
    if (api == null || api.getWindowRecordingSourceId == null || api.saveWindowRecording == null) {
        showSpikeToast("Recording needs the desktop app")
        return
    }
    if (spikeRecording) stopWindowRecording() else startWindowRecording(api)
}

/**
 * Entry point for starting a recording: first gates on the macOS **Screen
 * Recording permission** so we never silently save a black `.webm`, then hands off
 * to [acquireAndRecord]. `getScreenCaptureAccess` reports `"granted"` on platforms
 * without this OS gate (and the `.catch` proceeds anyway if the check itself
 * errors), so only a *definitively* unauthorized macOS returns early — there we
 * point the user at System Settings via [openScreenRecordingSettings] instead of
 * recording. On an older bridge lacking the check, we record directly.
 * Called only by [toggleWindowRecording] when idle.
 *
 * @param api the `window.electronApi` bridge (already null-checked by the caller).
 */
private fun startWindowRecording(api: dynamic) {
    val accessCheck: dynamic = if (api.getScreenCaptureAccess != null) api.getScreenCaptureAccess() else null
    if (accessCheck == null) { acquireAndRecord(api); return }
    accessCheck.then({ status: dynamic ->
        if ((status as? String) == "granted") {
            acquireAndRecord(api)
        } else {
            showSpikeToast("Enable Screen Recording for Lunamux in System Settings, then relaunch")
            if (api.openScreenRecordingSettings != null) api.openScreenRecordingSettings()
        }
    })
    accessCheck.catch({ _: dynamic -> acquireAndRecord(api) })
}

/**
 * Acquires the window capture stream and starts the recorder: resolves this
 * window's desktop-capture source id via the Electron bridge, opens a `getUserMedia`
 * desktop stream for it, and starts a `MediaRecorder` collecting `video/webm` chunks
 * into [spikeRecordingChunks]. No toast is shown on start (so the recording doesn't
 * open on a confirmation pill). Any failure along the way surfaces a single
 * "Recording failed" toast and leaves [spikeRecording] false. Called by
 * [startWindowRecording] once the Screen Recording permission is confirmed.
 *
 * @param api the `window.electronApi` bridge.
 */
private fun acquireAndRecord(api: dynamic) {
    // Optimistically mark recording so a fast second ⇧R can't spawn a parallel
    // recorder while the source id / stream promises are still resolving.
    spikeRecording = true
    val sourceP: dynamic = api.getWindowRecordingSourceId()
    sourceP.then({ sourceId: dynamic ->
        val id = sourceId as? String
        if (id == null || id.isEmpty()) {
            spikeRecording = false
            showSpikeToast("Recording failed")
        } else {
            // Electron's legacy desktop-capture constraint form: pin the capture
            // to this window's source id rather than prompting for a picker.
            val mandatory: dynamic = js("({})")
            mandatory.chromeMediaSource = "desktop"
            mandatory.chromeMediaSourceId = id
            val video: dynamic = js("({})")
            video.mandatory = mandatory
            val constraints: dynamic = js("({})")
            constraints.audio = false
            constraints.video = video
            val streamP: dynamic = window.navigator.asDynamic().mediaDevices.getUserMedia(constraints)
            streamP.then({ stream: dynamic -> beginRecorder(stream) })
            streamP.catch({ _: dynamic ->
                spikeRecording = false
                showSpikeToast("Recording failed")
            })
        }
    })
    sourceP.catch({ _: dynamic ->
        spikeRecording = false
        showSpikeToast("Recording failed")
    })
}

/**
 * Wires and starts a `MediaRecorder` over an open desktop-capture [stream],
 * stashing it and the stream in [spikeMediaRecorder]/[spikeRecordingStream] and
 * routing its data chunks into [spikeRecordingChunks]. When the recorder stops
 * (from [stopWindowRecording]) its `onstop` runs [finalizeRecording] to write the
 * file. Picks the best-supported `video/webm` codec, falling back to the browser
 * default. Called by [startWindowRecording] once the stream is live.
 *
 * @param stream the `MediaStream` from `getUserMedia`.
 */
private fun beginRecorder(stream: dynamic) {
    try {
        spikeRecordingChunks = js("[]")
        val recorder: dynamic = makeMediaRecorder(stream)
        recorder.ondataavailable = { e: dynamic ->
            val data = e.data
            if (data != null && ((data.size as? Int) ?: 0) > 0) spikeRecordingChunks.push(data)
            Unit
        }
        recorder.onstop = {
            finalizeRecording()
            Unit
        }
        recorder.start()
        spikeMediaRecorder = recorder
        spikeRecordingStream = stream
        spikeRecording = true
    } catch (_: Throwable) {
        // Couldn't construct/start the recorder — release the stream and reset.
        try {
            val tracks = stream.getTracks()
            val n = (tracks.length as? Int) ?: 0
            var i = 0
            while (i < n) { tracks[i].stop(); i++ }
        } catch (_: Throwable) {}
        spikeMediaRecorder = null
        spikeRecordingStream = null
        spikeRecordingChunks = null
        spikeRecording = false
        showSpikeToast("Recording failed")
    }
}

/**
 * Constructs a `MediaRecorder` for [stream], preferring VP9 then VP8 in a WebM
 * container and falling back to the browser's default when neither MIME type is
 * reported as supported. Kotlin/JS can't `new` a `dynamic`, so the constructor is
 * invoked through a small JS factory.
 *
 * @param stream the `MediaStream` to record.
 * @return a started-capable `MediaRecorder` instance (dynamic).
 */
private fun makeMediaRecorder(stream: dynamic): dynamic {
    val factory: dynamic = js(
        "(function(s, mime){ return mime ? new MediaRecorder(s, { mimeType: mime }) : new MediaRecorder(s); })"
    )
    val isSupported: dynamic = js(
        "(function(m){ try { return !!(window.MediaRecorder && MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(m)); } catch (e) { return false; } })"
    )
    val mime: String? = when {
        isSupported("video/webm;codecs=vp9") == true -> "video/webm;codecs=vp9"
        isSupported("video/webm;codecs=vp8") == true -> "video/webm;codecs=vp8"
        isSupported("video/webm") == true -> "video/webm"
        else -> null
    }
    return factory(stream, mime)
}

/**
 * Stops the in-flight recording. Flips [spikeRecording] off at once (so a rapid
 * third ⇧R can't race the async teardown) and calls `MediaRecorder.stop()`; the
 * recorder's `onstop` then runs [finalizeRecording] to assemble and save the file.
 * A no-op when nothing is recording. Called by [toggleWindowRecording] while
 * recording, and by [closeWorld3dSpike] so a recording isn't orphaned on close.
 */
internal fun stopWindowRecording() {
    val recorder = spikeMediaRecorder
    spikeRecording = false
    if (recorder == null) return
    try { recorder.stop() } catch (_: Throwable) { finalizeRecording() }
}

/**
 * Finalizes a stopped recording: stops the capture stream's tracks, assembles the
 * collected [spikeRecordingChunks] into a single `video/webm` Blob, ships its
 * bytes to the Electron main process to write to the Desktop, and confirms with a
 * [showSpikeToast] ("Recording saved to Desktop" or "Recording failed"). Clears
 * all recording state. Invoked from the recorder's `onstop` (see [beginRecorder]).
 */
private fun finalizeRecording() {
    val stream = spikeRecordingStream
    val chunks = spikeRecordingChunks
    // Release the OS capture as soon as we stop, so window capture doesn't linger.
    if (stream != null) {
        try {
            val tracks = stream.getTracks()
            val n = (tracks.length as? Int) ?: 0
            var i = 0
            while (i < n) { tracks[i].stop(); i++ }
        } catch (_: Throwable) {}
    }
    spikeMediaRecorder = null
    spikeRecordingStream = null
    spikeRecordingChunks = null
    spikeRecording = false

    val api = window.asDynamic().electronApi
    if (chunks == null || api == null || api.saveWindowRecording == null) {
        showSpikeToast("Recording failed")
        return
    }
    val makeBlob: dynamic = js("(function(parts){ return new Blob(parts, { type: 'video/webm' }); })")
    val toBytes: dynamic = js("(function(buf){ return new Uint8Array(buf); })")
    val blob: dynamic = makeBlob(chunks)
    if (((blob.size as? Int) ?: 0) <= 0) {
        showSpikeToast("Recording failed")
        return
    }
    val bufP: dynamic = blob.arrayBuffer()
    bufP.then({ buf: dynamic ->
        val saveP: dynamic = api.saveWindowRecording(toBytes(buf))
        saveP.then({ result: dynamic ->
            val path = (result as? String) ?: ""
            if (path.startsWith("!")) showSpikeToast("Recording failed")
            else showSpikeToast("Recording saved to Desktop")
        })
        saveP.catch({ _: dynamic -> showSpikeToast("Recording failed") })
    })
    bufP.catch({ _: dynamic -> showSpikeToast("Recording failed") })
}
