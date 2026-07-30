/**
 * ReformatPopup.kt (jsMain)
 * -------------------------
 * The hover-triggered settings popup for the per-pane "Reformat" button in
 * the toolkit pane header. Hovering the reformat button for ~1.5 seconds
 * opens a small popup anchored beneath it with two checkboxes:
 *
 *   • **Automatic reformat (this window)** — toggles automatic reflow for the
 *     hovered terminal pane only. Persisted as the per-pane
 *     [TerminalContent.autoReflow] override via
 *     [WindowCommand.SetTerminalAutoReflow] (i.e. alongside the other
 *     per-pane session settings).
 *   • **Automatic reformat (future windows)** — toggles the user's global
 *     default for *newly opened* terminals (each pane snapshots it at
 *     creation, so already-open panes are left untouched). Persisted as the
 *     top-level `autoReformatDefault` UI setting through the same
 *     `/api/ui-settings` REST bridge the rest of the global settings use.
 *
 * "Reflow" / "Reformat" here means re-asserting the remote PTY size to match
 * the pane on resize, tab activation, reconnect and font load. Those automatic
 * reasserts are *soft votes* (see [reassertSoft]) so they never seize the grid
 * from a phone driver; the explicit Reformat button / hotkey still *force* it
 * ([forceReassert]). Turning it off freezes a terminal at its current size; the
 * explicit Reformat button click still reflows it on demand.
 *
 * Wiring strategy: the toolkit owns (and rebuilds) the pane chrome on every
 * rerender, so rather than re-attaching listeners to each freshly built
 * button we install **document-level event delegation** once at boot (see
 * [installReformatHoverPopup]). The handlers key off the
 * `.tt-pane-action-reformat` class the reformat [PaneAction] is tagged with.
 *
 * @see TerminalEntry.autoReflow
 * @see effectiveAutoReflow
 * @see LunamuxToolkitBootstrap (bootViaToolkitShell installs this)
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Top-level UI-settings key holding the global automatic-reflow default. */
private const val KEY_AUTO_REFORMAT_DEFAULT = "autoReformatDefault"

/** CSS class the reformat [PaneAction] button carries (set as its extraClass). */
private const val REFORMAT_BUTTON_CLASS = "tt-pane-action-reformat"

/** CSS class on the popup root, used for hit-testing during hover tracking. */
private const val REFORMAT_POPUP_CLASS = "tt-reformat-popup"

/** Hover dwell time before the popup opens (1.5s spec, shortened by a third). */
private const val HOVER_OPEN_DELAY_MS = 1000

/**
 * Grace period before a popup closes once the pointer leaves both the button
 * and the popup, so travelling across the small gap between them (or briefly
 * grazing an edge) does not dismiss it.
 */
private const val CLOSE_GRACE_MS = 160

/**
 * Read the global automatic-reflow default from the in-memory settings
 * snapshot ([toolkitSettingsSnapshot]).
 *
 * Tolerates both real JSON Booleans (written via [persistGlobalAutoReformatDefault])
 * and the stringified `"true"`/`"false"` shape a flat-KV write can produce,
 * mirroring `AppSettingsContent.snapshotBoolean`. **Defaults to `true`** when
 * the key is absent so the factory behaviour stays "reflow every new pane".
 *
 * @return the user's global default for automatic reflow.
 * @see KEY_AUTO_REFORMAT_DEFAULT
 */
fun globalAutoReformatDefault(): Boolean {
    val element = toolkitSettingsSnapshot[KEY_AUTO_REFORMAT_DEFAULT] ?: return true
    val primitive = (element as? JsonPrimitive) ?: return true
    primitive.booleanOrNull?.let { return it }
    if (primitive.isString) return primitive.content != "false"
    return true
}

/**
 * Read the per-pane automatic-reflow override (ignoring the global default)
 * for [paneId] from the live config, or `null` when the pane has never set
 * one. Used by `ensureTerminal` to seed a fresh pane's frozen
 * [TerminalEntry.autoReflow] (override if present, else a snapshot of the
 * current global default).
 *
 * @param paneId the terminal pane to inspect.
 * @return the explicit per-pane value, or `null` if it inherits the default.
 */
internal fun perPaneAutoReflowOverride(paneId: String): Boolean? {
    val cfg: dynamic = currentConfig ?: return null
    val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabsArr) {
        val panes = (tab.panes as? Array<dynamic>) ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) {
                return p.leaf?.content?.autoReflow as? Boolean
            }
        }
    }
    return null
}

/**
 * Persist the global automatic-reflow default both to the in-memory snapshot
 * (so later reads see it immediately) and to the server via the shared
 * `/api/ui-settings` JSON bridge.
 *
 * @param value the new global default.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun persistGlobalAutoReformatDefault(value: Boolean) {
    val merged = toolkitSettingsSnapshot.toMutableMap()
    merged[KEY_AUTO_REFORMAT_DEFAULT] = JsonPrimitive(value)
    toolkitSettingsSnapshot = JsonObject(merged)
    GlobalScope.launch {
        webSettingsPersister.putJsonSettings(buildJsonObject {
            put(KEY_AUTO_REFORMAT_DEFAULT, JsonPrimitive(value))
        })
    }
}

/**
 * Apply a per-pane automatic-reflow choice from the popup: persist it,
 * update the live [TerminalEntry] flag immediately, and — when turning
 * reflow back on — reformat the pane right away so it catches up to its
 * current size instead of waiting for the next geometry event.
 *
 * @param paneId the terminal pane to update.
 * @param enabled the new per-pane value.
 */
private fun applyPerPaneAutoReflow(paneId: String, enabled: Boolean) {
    launchCmd(WindowCommand.SetTerminalAutoReflow(paneId = paneId, enabled = enabled))
    val entry = terminals[paneId] ?: return
    entry.autoReflow = enabled
    if (enabled) {
        // Soft reassert: re-enabling auto-reflow catches this pane's own grid
        // up to its container, but is not a user "reformat" gesture — it votes
        // rather than seizing the grid from a phone driver. @see reassertSoft
        try { reassertSoft(entry) } catch (_: Throwable) {}
    } else {
        // Refresh the overlay so any "unused space" hint reflects the now
        // frozen grid immediately.
        try { updateOobOverlay(entry) } catch (_: Throwable) {}
    }
}

/**
 * Apply a global-default change from the popup. Persists the new default
 * only; it intentionally does **not** touch any already-open terminal.
 * Existing panes keep the value frozen into their [TerminalEntry] at
 * creation, so the change applies solely to terminals opened afterwards
 * ("future windows").
 *
 * @param enabled the new global default.
 */
private fun applyGlobalAutoReformatDefault(enabled: Boolean) {
    persistGlobalAutoReformatDefault(enabled)
}

/**
 * Resolve the terminal pane id for a reformat button by walking up to its
 * enclosing toolkit pane (`.dt-pane`) and reading the `data-pane` attribute
 * stamped on the content cell [mountPaneContent] builds.
 *
 * @param button the hovered `.tt-pane-action-reformat` element.
 * @return the pane id, or `null` if the structure can't be resolved.
 */
private fun paneIdForReformatButton(button: HTMLElement): String? {
    val pane = button.asDynamic().closest(".dt-pane") as? HTMLElement ?: return null
    val cell = pane.querySelector("[data-pane]") as? HTMLElement ?: return null
    return cell.getAttribute("data-pane")
}

// ── Hover-tracking + popup lifecycle ─────────────────────────────────────

/** The currently open popup element, or `null` when none is shown. */
private var openPopup: HTMLElement? = null

/** The button the open popup belongs to (for re-entrancy checks). */
private var openPopupButton: HTMLElement? = null

/** Pending "open after dwell" timer id, or `null` when not arming. */
private var armTimerId: Int? = null

/** The button currently being armed (hovered, dwell not yet elapsed). */
private var armButton: HTMLElement? = null

/** Pending "close after grace" timer id, or `null`. */
private var closeTimerId: Int? = null

/** Cancel any pending open-dwell timer. */
private fun cancelArm() {
    armTimerId?.let { window.clearTimeout(it) }
    armTimerId = null
    armButton = null
}

/** Cancel any pending grace-period close. */
private fun cancelClose() {
    closeTimerId?.let { window.clearTimeout(it) }
    closeTimerId = null
}

/** Close and remove the open popup, if any. */
private fun closeReformatPopup() {
    cancelClose()
    openPopup?.let { it.parentElement?.removeChild(it) }
    openPopup = null
    openPopupButton = null
}

/** Schedule a close after the grace period (bridges the button↔popup gap). */
private fun scheduleClose() {
    if (openPopup == null) return
    cancelClose()
    closeTimerId = window.setTimeout({ closeTimerId = null; closeReformatPopup() }, CLOSE_GRACE_MS)
}

/**
 * Build one labelled checkbox row for the popup.
 *
 * The whole row is a single `<label>` (so the generous click target toggles
 * the box), with the checkbox on the left and a bold title plus a muted
 * one-line description stacked on the right. The description spells out the
 * scope of each toggle so the two visually similar options can't be confused
 * at a glance.
 *
 * @param titleText the bold primary label.
 * @param descriptionText the muted explanatory line beneath the title.
 * @param checked the initial checkbox state.
 * @param onChange invoked with the new value whenever the user toggles it.
 * @return the freshly-built row element.
 */
private fun buildCheckboxRow(
    titleText: String,
    descriptionText: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
): HTMLElement {
    val row = document.createElement("label") as HTMLElement
    row.className = "tt-reformat-popup-row"

    val box = document.createElement("input") as HTMLInputElement
    box.type = "checkbox"
    box.className = "tt-reformat-popup-checkbox"
    box.checked = checked
    box.addEventListener("change", { _: Event -> onChange(box.checked) })

    val textCol = document.createElement("span") as HTMLElement
    textCol.className = "tt-reformat-popup-text"

    val title = document.createElement("span") as HTMLElement
    title.className = "tt-reformat-popup-title"
    title.textContent = titleText

    val desc = document.createElement("span") as HTMLElement
    desc.className = "tt-reformat-popup-desc"
    desc.textContent = descriptionText

    textCol.appendChild(title)
    textCol.appendChild(desc)
    row.appendChild(box)
    row.appendChild(textCol)
    return row
}

/**
 * Build and show the popup anchored beneath [button] for [paneId].
 *
 * Positioned `fixed` just below the button and clamped to the viewport's
 * right edge. Replaces any already-open popup.
 *
 * @param button the reformat button to anchor under.
 * @param paneId the terminal pane the "(this window)" checkbox controls.
 */
private fun openReformatPopup(button: HTMLElement, paneId: String) {
    closeReformatPopup()

    val popup = document.createElement("div") as HTMLElement
    popup.className = REFORMAT_POPUP_CLASS

    val heading = document.createElement("div") as HTMLElement
    heading.className = "tt-reformat-popup-heading"
    heading.textContent = "Automatic reformat"
    popup.appendChild(heading)

    val hint = document.createElement("div") as HTMLElement
    hint.className = "tt-reformat-popup-hint"
    hint.textContent = "Reflow the terminal to fit when the pane is resized."
    popup.appendChild(hint)

    popup.appendChild(buildCheckboxRow(
        titleText = "This window",
        descriptionText = "Applies to this terminal only.",
        checked = terminals[paneId]?.autoReflow ?: globalAutoReformatDefault(),
        onChange = { v -> applyPerPaneAutoReflow(paneId, v) },
    ))

    val divider = document.createElement("div") as HTMLElement
    divider.className = "tt-reformat-popup-divider"
    popup.appendChild(divider)

    popup.appendChild(buildCheckboxRow(
        titleText = "Future windows",
        descriptionText = "Default for newly opened terminals.",
        checked = globalAutoReformatDefault(),
        onChange = { v -> applyGlobalAutoReformatDefault(v) },
    ))

    // Keep the popup alive while the pointer is inside it.
    popup.addEventListener("pointerenter", { _: Event -> cancelClose() })
    popup.addEventListener("pointerleave", { _: Event -> scheduleClose() })

    document.body?.appendChild(popup)
    openPopup = popup
    openPopupButton = button

    // Position after attaching so offsetWidth is measurable.
    val rect = button.getBoundingClientRect()
    popup.style.position = "fixed"
    popup.style.top = "${rect.bottom + 6.0}px"
    val width = popup.offsetWidth.toDouble()
    val maxLeft = window.innerWidth.toDouble() - width - 8.0
    val left = rect.left.coerceIn(8.0, if (maxLeft < 8.0) 8.0 else maxLeft)
    popup.style.left = "${left}px"
}

/**
 * Install the global hover/keyboard/outside-click listeners that drive the
 * reformat popup. Called once from `bootViaToolkitShell` after the shell
 * mounts. Uses event delegation on `document`, so it transparently handles
 * the toolkit rebuilding pane-header buttons on every rerender.
 *
 * @see openReformatPopup
 */
fun installReformatHoverPopup() {
    // pointerover: entering a reformat button arms the open timer; entering
    // the open popup cancels any pending close.
    document.addEventListener("pointerover", { ev: Event ->
        val target = ev.target as? Element ?: return@addEventListener
        val btn = target.asDynamic().closest(".$REFORMAT_BUTTON_CLASS") as? HTMLElement
        if (btn != null) {
            cancelClose()
            if (openPopupButton === btn || armButton === btn) return@addEventListener
            cancelArm()
            armButton = btn
            armTimerId = window.setTimeout({
                armTimerId = null
                val armed = armButton ?: return@setTimeout
                armButton = null
                val paneId = paneIdForReformatButton(armed) ?: return@setTimeout
                openReformatPopup(armed, paneId)
            }, HOVER_OPEN_DELAY_MS)
            return@addEventListener
        }
        if (target.asDynamic().closest(".$REFORMAT_POPUP_CLASS") != null) cancelClose()
    })

    // pointerout: leaving the armed button cancels the dwell; leaving both
    // the button and the popup schedules a graceful close.
    document.addEventListener("pointerout", { ev: Event ->
        val from = ev.target as? Element
        val related = ev.asDynamic().relatedTarget as? Element
        val fromBtn = from?.asDynamic()?.closest(".$REFORMAT_BUTTON_CLASS") as? HTMLElement
        val toBtn = related?.asDynamic()?.closest(".$REFORMAT_BUTTON_CLASS") as? HTMLElement
        val toPopup = related?.asDynamic()?.closest(".$REFORMAT_POPUP_CLASS")
        if (armButton != null && fromBtn === armButton && toBtn !== armButton) cancelArm()
        if (openPopup != null && toBtn == null && toPopup == null) scheduleClose()
    })

    // Outside click and Escape dismiss the popup.
    document.addEventListener("pointerdown", { ev: Event ->
        val popup = openPopup ?: return@addEventListener
        val target = ev.target as? Element
        val inPopup = target?.asDynamic()?.closest(".$REFORMAT_POPUP_CLASS") != null
        val onButton = target?.asDynamic()?.closest(".$REFORMAT_BUTTON_CLASS") === openPopupButton
        if (!inPopup && !onButton) closeReformatPopup()
    }, true)

    document.addEventListener("keydown", { ev: Event ->
        if (openPopup == null) return@addEventListener
        if ((ev as KeyboardEvent).key == "Escape") closeReformatPopup()
    })
}
