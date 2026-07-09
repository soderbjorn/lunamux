/* HotkeysSidebarContent.kt (jsMain)
 *
 * Body factory for Lunamux's "Keyboard shortcuts" right-sidebar (the
 * toolkit-supplied [se.soderbjorn.darkness.web.settings.buildHotkeysSidebar]
 * slot, wired via `AppShellSpec.hotkeysContent` in
 * [bootViaToolkitShell] and opened through
 * `AppShellHandle.openHotkeysSidebar`).
 *
 * This is Lunamux's single keyboard-shortcut reference: every shortcut
 * is listed with availability badges — a shortcut in both runtimes shows
 * both a **Mac** (bundled Electron app) and a **Web** (browser)
 * pill; a runtime-specific one shows only its pill — and, for the one
 * shortcut whose chord differs between runtimes (positional tab switching),
 * both chords are shown side by side and labelled. The toolkit owns the
 * sidebar chrome (header, close, slide-in); this file only builds the inner
 * body.
 *
 * Rows backed by a configurable toolkit action (pane focus, tab cycling)
 * are **clickable**: clicking opens the toolkit's capture-based
 * [openHotkeyConfigDialog] where the user can add several shortcuts per
 * action, remove them, or reset to defaults. Those rows render their
 * *effective* chords ([HotkeyBindings.effectiveChords]) — the user's
 * custom set when present, the defaults otherwise — with an "edited" tag
 * when customized, and a per-chord **Mac only** tag when a recorded chord
 * is one the browser reserves for itself. Custom bindings persist in the
 * server-managed UI settings (see `onServerUiSettingsApplied` in
 * `main.kt`), so they sync across clients and survive restarts.
 *
 * When a new chord is wired anywhere in Lunamux's web frontend, add a
 * corresponding [HotkeyRow] here so the reference stays accurate.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.hotkey.Hotkey
import se.soderbjorn.darkness.web.hotkey.HotkeyBindings
import se.soderbjorn.darkness.web.hotkey.ToolkitHotkeyIds
import se.soderbjorn.darkness.web.hotkey.isBrowserReservedChord
import se.soderbjorn.darkness.web.hotkey.openHotkeyConfigDialog
import se.soderbjorn.darkness.web.hotkey.tabSwitchHotkeyEntry
import se.soderbjorn.darkness.web.hotkey.toChordLabel
import se.soderbjorn.darkness.web.hotkey.webTabSwitchHotkeyEntry

/**
 * Where a shortcut is available. Derived per row from which of the two
 * runtime chords are present; drives the row badge(s). A shortcut present in
 * both runtimes renders one [DESKTOP] and one [WEB] pill rather than a single
 * combined badge.
 */
private enum class HotkeyScope { DESKTOP, WEB }

/**
 * One shortcut row in the Hotkeys sidebar. Two flavours:
 *
 * - **Configurable** ([actionId] non-null): backed by a toolkit
 *   [se.soderbjorn.darkness.web.hotkey.HotkeyActionSpec]. Chords are
 *   resolved live from [HotkeyBindings.effectiveChords] at render time
 *   (so custom bindings show through) and the row opens the config
 *   dialog on click. [desktopChord] / [webChord] are unused.
 * - **Fixed** ([actionId] null): a hand-written reference row using the
 *   pre-formatted chord label lists, exactly as before.
 *
 * @property label        human-readable action name.
 * @property actionId     toolkit action id when the row is configurable.
 * @property desktopChord cap labels for the bundled Electron desktop app, or
 *   `null` when the shortcut isn't available there (fixed rows only).
 * @property webChord     cap labels for the browser, or `null` when the
 *   shortcut isn't available there (fixed rows only).
 */
private class HotkeyRow(
    val label: String,
    val actionId: String? = null,
    val desktopChord: List<String>? = null,
    val webChord: List<String>? = null,
)

/** A titled group of [HotkeyRow]s, rendered as one section. */
private class HotkeyGroupModel(val title: String, val rows: List<HotkeyRow>)

/** Row backed by a user-configurable toolkit action. */
private fun actionRow(label: String, actionId: String) =
    HotkeyRow(label, actionId = actionId)

/** Fixed row available identically in both runtimes (same chord). */
private fun bothRow(label: String, chord: List<String>) =
    HotkeyRow(label, desktopChord = chord, webChord = chord)

/** Fixed row available in both runtimes but with a different chord in each. */
private fun splitRow(label: String, desktop: List<String>, web: List<String>) =
    HotkeyRow(label, desktopChord = desktop, webChord = web)

/** Fixed row available only in the bundled Electron desktop app. */
private fun desktopOnlyRow(label: String, chord: List<String>) =
    HotkeyRow(label, desktopChord = chord, webChord = null)

/**
 * The full shortcut model, grouped for display. Configurable rows carry
 * only their toolkit action id — the chords are read from
 * [HotkeyBindings] at render time so the reference can't drift from the
 * live bindings. Fixed rows are built from the same [StandardHotkeys] /
 * entry helpers the live bindings use.
 *
 * @return the ordered groups shown in the sidebar.
 */
private fun hotkeyGroups(): List<HotkeyGroupModel> = listOf(
    HotkeyGroupModel(
        "Windows",
        listOf(
            // Ctrl+Opt+Arrow (default) spatially focuses the pane in that
            // direction. Configurable — click to rebind.
            actionRow("Focus window left", ToolkitHotkeyIds.PANE_FOCUS_LEFT),
            actionRow("Focus window right", ToolkitHotkeyIds.PANE_FOCUS_RIGHT),
            actionRow("Focus window up", ToolkitHotkeyIds.PANE_FOCUS_UP),
            actionRow("Focus window down", ToolkitHotkeyIds.PANE_FOCUS_DOWN),
            // Opt+Cmd+Up/Down (default) step the focused window through
            // its three states: docked ⇄ normal ⇄ maximized. Toolkit
            // actions — see LayoutRenderer's cyclePaneStateUp/Down.
            actionRow("Expand window (docked → normal → max)", ToolkitHotkeyIds.PANE_EXPAND),
            actionRow("Collapse window (max → normal → docked)", ToolkitHotkeyIds.PANE_COLLAPSE),
            // App-specific (not a toolkit action): reflow the active terminal
            // to fit its pane. Default ⌃⌥R; configurable + registered in
            // `registerReformatHotkey`. See [REFORMAT_HOTKEY_ACTION_ID].
            actionRow("Reformat terminal", REFORMAT_HOTKEY_ACTION_ID),
        ),
    ),
    HotkeyGroupModel(
        "Tabs",
        listOf(
            actionRow("Previous tab", ToolkitHotkeyIds.TAB_PREVIOUS),
            actionRow("Next tab", ToolkitHotkeyIds.TAB_NEXT),
            // The only chord that differs by runtime: a real browser reserves
            // plain Cmd/Ctrl+digit for its own tabs, so the web build adds an
            // Alt/Option modifier. Not configurable (nine chords behind one
            // conceptual action).
            splitRow(
                "Switch to tab 1–9 (9 = last)",
                desktop = tabSwitchHotkeyEntry().chord,
                web = webTabSwitchHotkeyEntry().chord,
            ),
            // App-specific (not a toolkit action): toggle the 3D tab
            // overview (carousel ring of live tab thumbnails). Default
            // ⌃⌥O; configurable + registered in `registerOverview3dHotkey`.
            // See [OVERVIEW3D_HOTKEY_ACTION_ID].
            actionRow("3D tab overview", OVERVIEW3D_HOTKEY_ACTION_ID),
            // Sibling of the above: ⌥⌘← opens the 3D world spike (panes
            // rotunda) instead of the app switcher. See
            // [WORLD3D_SPIKE_HOTKEY_ACTION_ID] / `registerWorld3dSpikeHotkey`.
            actionRow("3D mode", WORLD3D_SPIKE_HOTKEY_ACTION_ID),
        ),
    ),
    HotkeyGroupModel(
        "Dialogs",
        listOf(
            bothRow("Confirm / submit", listOf("⏎")),
            bothRow("Dismiss / cancel", listOf("Esc")),
        ),
    ),
    HotkeyGroupModel(
        "App",
        listOf(
            // OS-level global hotkey owned by the desktop app (ElectronMain):
            // summons the window from anywhere, or hides it if it's already
            // frontmost. Not available to a browser tab, and not configurable
            // from here (it's registered in the Electron main process).
            desktopOnlyRow("Summon / hide window", summonChord()),
        ),
    ),
)

/**
 * Chord for the desktop app's global summon/hide hotkey. Mirrors
 * `SUMMON_ACCELERATOR` ("Control+Alt+Command+Space") in the Electron main
 * process, rendered with the current platform's modifier glyphs.
 */
private fun summonChord(): List<String> =
    Hotkey(key = " ", ctrl = true, alt = true, meta = true).toChordLabel()

/**
 * Open the dedicated Hotkeys sidebar.
 *
 * Routes through the toolkit's [se.soderbjorn.darkness.web.shell.AppShellHandle],
 * which animates any other right-side panel closed first and mounts the
 * [buildHotkeysSidebarContent] body. Shared entry point for the App Settings
 * "Hotkeys" button and the Electron "Keyboard Shortcuts" menu item (the
 * latter via the `show-hotkeys` IPC routed in `main.kt`). No-op until the
 * shell has mounted ([appShellHandle] is null before then).
 *
 * @see buildHotkeysSidebarContent
 */
fun openHotkeysSidebar() {
    appShellHandle?.openHotkeysSidebar()
}

/**
 * Build the body element for the Hotkeys sidebar.
 *
 * Wired via `AppShellSpec.hotkeysContent` in [bootViaToolkitShell]; invoked
 * each time the sidebar opens so chords reflect the current platform and
 * the user's current custom bindings. The body re-renders itself after a
 * config-dialog save so edits show immediately.
 *
 * @return the freshly-built body `<div>`.
 * @see openHotkeysSidebar
 */
fun buildHotkeysSidebarContent(): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.className = "lunamux-hotkeys-body"
    renderHotkeysInto(container)
    return container
}

/**
 * (Re)fill [container] with the intro + shortcut groups. Called once at
 * build time and again (via the config dialog's `onSaved` callback) after
 * a binding edit, so the sidebar reflects the just-saved chords.
 */
private fun renderHotkeysInto(container: HTMLElement) {
    container.innerHTML = ""

    val intro = document.createElement("p") as HTMLElement
    intro.className = "lunamux-hotkeys-intro"
    intro.textContent = "Window and tab shortcuts work even when a terminal " +
        "is focused. Click a shortcut to customize it."
    container.appendChild(intro)

    val rerender: () -> Unit = { renderHotkeysInto(container) }
    for (group in hotkeyGroups()) {
        container.appendChild(buildGroupSection(group, rerender))
    }
}

/** Build one titled group section (header + rows). */
private fun buildGroupSection(group: HotkeyGroupModel, rerender: () -> Unit): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunamux-hotkeys-group"

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-hotkeys-group-title"
    title.textContent = group.title
    section.appendChild(title)

    val list = document.createElement("div") as HTMLElement
    list.className = "lunamux-hotkeys-list"
    for (row in group.rows) list.appendChild(buildRow(row, rerender))
    section.appendChild(list)

    return section
}

/**
 * Build one shortcut row: label on the left, chord(s) + availability badge
 * on the right.
 *
 * Configurable rows ([HotkeyRow.actionId] non-null) render their effective
 * chords with per-chord Mac / Web pills (Web omitted for browser-reserved
 * chords), get an "edited" tag when customized, and open the toolkit's
 * hotkey-config dialog on click ([rerender] refreshes the pane after a
 * save).
 *
 * For fixed rows: when the desktop and web chords match, the chord is shown
 * once followed by both a "Mac" and a "Web" pill. When they differ, both
 * chords are stacked, each tagged with its runtime. A desktop- or web-only
 * shortcut shows its single chord with the matching badge.
 */
private fun buildRow(row: HotkeyRow, rerender: () -> Unit): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.className = "lunamux-hotkeys-row"

    val label = document.createElement("span") as HTMLElement
    label.className = "lunamux-hotkeys-label"
    label.textContent = row.label
    el.appendChild(label)

    val right = document.createElement("div") as HTMLElement
    right.className = "lunamux-hotkeys-right"

    val actionId = row.actionId
    if (actionId != null) {
        fillConfigurableRow(el, right, row.label, actionId, rerender)
        el.appendChild(right)
        return el
    }

    val desktop = row.desktopChord
    val web = row.webChord
    when {
        desktop != null && web != null && desktop == web -> {
            right.appendChild(buildChord(desktop))
            val badges = document.createElement("div") as HTMLElement
            badges.className = "lunamux-hotkeys-badges"
            badges.appendChild(buildBadge(HotkeyScope.DESKTOP))
            badges.appendChild(buildBadge(HotkeyScope.WEB))
            right.appendChild(badges)
        }
        desktop != null && web != null -> {
            // Differing chords — show each labelled with its runtime.
            right.appendChild(buildTaggedChord(desktop, HotkeyScope.DESKTOP))
            right.appendChild(buildTaggedChord(web, HotkeyScope.WEB))
        }
        desktop != null -> {
            right.appendChild(buildChord(desktop))
            right.appendChild(buildBadge(HotkeyScope.DESKTOP))
        }
        web != null -> {
            right.appendChild(buildChord(web))
            right.appendChild(buildBadge(HotkeyScope.WEB))
        }
    }

    el.appendChild(right)
    return el
}

/**
 * Populate a configurable row: effective chords from [HotkeyBindings]
 * (each with Mac + Web pills, Web dropped for browser-reserved chords),
 * an "edited" tag when the user has custom bindings, an "unbound" note
 * when the user removed every chord, and a click handler that opens the
 * toolkit's config dialog for [actionId].
 */
private fun fillConfigurableRow(
    rowEl: HTMLElement,
    right: HTMLElement,
    label: String,
    actionId: String,
    rerender: () -> Unit,
) {
    rowEl.classList.add("lunamux-hotkeys-row--configurable")
    rowEl.title = "Click to customize this shortcut"
    rowEl.addEventListener("click", {
        openHotkeyConfigDialog(actionId, label) { rerender() }
    })

    val chords = HotkeyBindings.effectiveChords(actionId)
    if (chords.isEmpty()) {
        val none = document.createElement("span") as HTMLElement
        none.className = "lunamux-hotkeys-unbound"
        none.textContent = "unbound"
        right.appendChild(none)
    }
    for (chord in chords) {
        val line = document.createElement("div") as HTMLElement
        line.className = "lunamux-hotkeys-tagged"
        line.appendChild(buildChord(chord.toChordLabel()))
        val badges = document.createElement("div") as HTMLElement
        badges.className = "lunamux-hotkeys-badges"
        badges.appendChild(buildBadge(HotkeyScope.DESKTOP))
        if (isBrowserReservedChord(chord)) {
            // The browser keeps this combo for itself: Electron-only.
            badges.appendChild(buildMacOnlyBadge())
        } else {
            badges.appendChild(buildBadge(HotkeyScope.WEB))
        }
        line.appendChild(badges)
        right.appendChild(line)
    }
    if (HotkeyBindings.isCustomized(actionId)) {
        val edited = document.createElement("span") as HTMLElement
        edited.className = "lunamux-hotkeys-edited"
        edited.title = "This shortcut has been customized"
        edited.textContent = "edited"
        right.appendChild(edited)
    }
}

/** A chord line paired with a runtime tag (used for the split tab-switch row). */
private fun buildTaggedChord(caps: List<String>, scope: HotkeyScope): HTMLElement {
    val line = document.createElement("div") as HTMLElement
    line.className = "lunamux-hotkeys-tagged"
    line.appendChild(buildChord(caps))
    line.appendChild(buildBadge(scope))
    return line
}

/** Render a chord as a row of keycap pills. */
private fun buildChord(caps: List<String>): HTMLElement {
    val chord = document.createElement("span") as HTMLElement
    chord.className = "lunamux-hotkeys-chord"
    for (cap in caps) {
        val capEl = document.createElement("kbd") as HTMLElement
        capEl.className = "lunamux-hotkeys-cap"
        capEl.textContent = cap
        chord.appendChild(capEl)
    }
    return chord
}

/** Render the availability badge for a [scope]. */
private fun buildBadge(scope: HotkeyScope): HTMLElement {
    val badge = document.createElement("span") as HTMLElement
    val (text, mod) = when (scope) {
        HotkeyScope.DESKTOP -> "Mac" to "desktop"
        HotkeyScope.WEB -> "Web" to "web"
    }
    badge.className = "lunamux-hotkeys-badge lunamux-hotkeys-badge--$mod"
    badge.textContent = text
    return badge
}

/**
 * Amber "Mac only" badge for a custom chord the browser reserves for
 * itself (so it can't fire in the web client but works in the bundled
 * Electron app).
 */
private fun buildMacOnlyBadge(): HTMLElement {
    val badge = document.createElement("span") as HTMLElement
    badge.className = "lunamux-hotkeys-badge lunamux-hotkeys-badge--maconly"
    badge.title = "Browsers reserve this key combination for themselves; " +
        "it only works in the Mac desktop app."
    badge.textContent = "Mac only"
    return badge
}
