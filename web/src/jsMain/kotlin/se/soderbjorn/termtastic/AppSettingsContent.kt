/**
 * AppSettingsContent.kt
 * ---------------------
 * Termtastic's body factory for the toolkit-supplied "App settings"
 * right-sidebar slot (see
 * [se.soderbjorn.darkness.web.shell.AppShellSpec.appSettingsContent]).
 *
 * The toolkit owns the sidebar chrome (header, close affordance,
 * slide-in animation, mutual exclusion with the Theme Manager and
 * Appearance Settings panels). This file builds the inner body element
 * the toolkit slots into that chrome.
 *
 * Contents (in order):
 *  1. A **navigation** section of full-width buttons that jump to the
 *     other settings surfaces:
 *       - **"Themes"** / **"Appearance"** — activate the toolkit's topbar
 *         Theme Manager / Appearance buttons (via [activateTopbarButton]),
 *         so they have the exact same effect — including the mutual
 *         exclusion that closes this sidebar — as clicking those toolbar
 *         icons directly.
 *       - **"Server Settings"** — dispatches the same
 *         [WindowCommand.OpenSettings] the old macOS app-menu entry did,
 *         surfacing the JVM Swing Settings dialog. Rendered last, as a
 *         deliberately low-prominence ("muted") button, and only shown
 *         when running inside the bundled Electron app ([isElectronClient])
 *         — the dialog opens on the server's desktop, which a remote
 *         browser can't see.
 *  2. An **Experimental features** section with two persisted boolean
 *     toggles:
 *       - **Enable file browser** — when off, hides the File Browser
 *         entry from the topbar "New pane" hover dropdown.
 *       - **Enable Git change view** — same for the Git entry.
 *
 * The flags persist server-side under two new top-level keys in
 * `/api/ui-settings`:
 *   - `experimentalFileBrowser` (Boolean, default false)
 *   - `experimentalGitView` (Boolean, default false)
 *
 * Reads consult [toolkitSettingsSnapshot] (already mirrored from the
 * server's payload via [updateToolkitSettingsSnapshot]); writes
 * round-trip through the same `webSettingsPersister` REST bridge
 * everything else uses, and update the snapshot synchronously so
 * subsequent menu-rebuilds reflect the new value without waiting for a
 * server echo.
 *
 * @see buildAppSettingsContent
 * @see isExperimentalFileBrowserEnabled
 * @see isExperimentalGitViewEnabled
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** Persistence key for the experimental file-browser flag. */
private const val KEY_EXPERIMENTAL_FILE_BROWSER = "experimentalFileBrowser"

/** Persistence key for the experimental Git-view flag. */
private const val KEY_EXPERIMENTAL_GIT_VIEW = "experimentalGitView"

/**
 * Tooltip/`title` of the toolkit's topbar Theme Manager (palette) button.
 * Used by [activateTopbarButton] to locate and click it. Mirrors the
 * default tooltip set by the toolkit's `buildThemeManagerButton`.
 */
private const val TOPBAR_TITLE_THEMES = "Theme manager"

/**
 * Tooltip/`title` of the toolkit's topbar Appearance ("Aa") button.
 * Mirrors the default tooltip set by the toolkit's `buildSettingsGearButton`.
 */
private const val TOPBAR_TITLE_APPEARANCE = "Appearance"

/**
 * Tooltip/`title` of the toolkit's topbar App Settings (gear) button — the
 * one that opens *this* sidebar. Mirrors the default tooltip set by the
 * toolkit's `buildAppSettingsButton`. Used by [openAppSettingsSidebar].
 */
private const val TOPBAR_TITLE_APP_SETTINGS = "App settings"

/** Palette glyph for the "Themes" navigation button. */
private const val ICON_THEMES =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="13.5" cy="6.5" r="1.2"/><circle cx="17.5" cy="10.5" r="1.2"/><circle cx="8.5" cy="7.5" r="1.2"/><circle cx="6.5" cy="12.5" r="1.2"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c1.7 0 3-1.3 3-3 0-.8-.3-1.5-.8-2-.5-.5-.8-1.2-.8-2 0-1.7 1.3-3 3-3h2c2.2 0 4-1.8 4-4 0-4.4-4.5-8-10-8z"/></svg>"""

/** "Format text" glyph for the "Appearance" navigation button. */
private const val ICON_APPEARANCE =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/></svg>"""

/** Monitor glyph for the "Server Settings" navigation button. */
private const val ICON_SERVER_SETTINGS =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>"""

/**
 * Read a Boolean flag from the in-memory server-settings snapshot.
 *
 * Tolerates both JSON-Boolean and JSON-String shapes ("true" / "false")
 * because flat-KV writes that route through `putSetting(String, String)`
 * land in the store as string primitives, while writes that route through
 * [putJsonBoolean] below land as real Booleans. Either way `true` reads
 * as on.
 *
 * @param key the top-level key in [toolkitSettingsSnapshot].
 * @return the stored Boolean, or `false` when the key is missing or
 *   neither a Boolean nor the literal string "true".
 */
private fun snapshotBoolean(key: String): Boolean {
    val element = toolkitSettingsSnapshot[key] ?: return false
    val primitive = (element as? JsonPrimitive) ?: return false
    primitive.booleanOrNull?.let { return it }
    if (primitive.isString) return primitive.content == "true"
    return false
}

/**
 * Whether the "File browser" pane flavour should appear in the topbar
 * "New pane" hover dropdown. Reads through to [toolkitSettingsSnapshot]
 * on every call — `paneAddMenuItems` is evaluated each time the menu
 * opens, so a write that updates the snapshot synchronously is visible
 * on the next hover without any rerender plumbing.
 *
 * @return `true` when the user has opted into the experimental flavour.
 * @see KEY_EXPERIMENTAL_FILE_BROWSER
 */
fun isExperimentalFileBrowserEnabled(): Boolean =
    snapshotBoolean(KEY_EXPERIMENTAL_FILE_BROWSER)

/**
 * Whether the "Git" pane flavour should appear in the topbar "New pane"
 * hover dropdown. Mirrors [isExperimentalFileBrowserEnabled] for the
 * Git-view flag.
 *
 * @return `true` when the user has opted into the experimental flavour.
 * @see KEY_EXPERIMENTAL_GIT_VIEW
 */
fun isExperimentalGitViewEnabled(): Boolean =
    snapshotBoolean(KEY_EXPERIMENTAL_GIT_VIEW)

/**
 * Mirror a single boolean key into [toolkitSettingsSnapshot] without
 * waiting for the server to echo the write back. Keeps the snapshot in
 * sync with what we just persisted so `paneAddMenuItems` on the next
 * hover sees the new value immediately.
 *
 * @param key   the top-level key to update.
 * @param value the new boolean value.
 */
private fun updateSnapshotBoolean(key: String, value: Boolean) {
    val merged = toolkitSettingsSnapshot.toMutableMap()
    merged[key] = JsonPrimitive(value)
    toolkitSettingsSnapshot = JsonObject(merged)
}

/**
 * Persist a single boolean key through termtastic's existing REST bridge.
 * Uses the `putJsonSettings` path so the value lands on the server as a
 * real JSON Boolean (not the stringified-blob fallback `putSetting(String,
 * String)` would produce).
 *
 * @param key   the top-level key to write.
 * @param value the boolean value to persist.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun putJsonBoolean(key: String, value: Boolean) {
    GlobalScope.launch {
        webSettingsPersister.putJsonSettings(buildJsonObject {
            put(key, JsonPrimitive(value))
        })
    }
}

/**
 * Build the body element the toolkit should mount inside the App Settings
 * sidebar slot.
 *
 * Wired via `AppShellSpec.appSettingsContent` in
 * [TermtasticToolkitBootstrap]. Invoked each time the sidebar opens so the
 * UI reflects current persisted state without needing an explicit
 * refresh hook.
 *
 * @return the freshly-built body element (a `<div>` containing the
 *   navigation buttons and the Experimental features section).
 */
fun buildAppSettingsContent(): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.className = "termtastic-app-settings-body"

    container.appendChild(buildNavigationSection())
    container.appendChild(buildExperimentalSection())

    return container
}

/**
 * The top navigation section: a stack of full-width jump buttons.
 *
 * Order:
 *  1. **"Themes"** — same effect as the toolkit's topbar palette button.
 *  2. **"Appearance"** — same effect as the toolkit's topbar "Aa" button.
 *  3. **"Server Settings"** (muted, Electron-only) — opens the JVM
 *     Swing dialog on the server's desktop. Useful only when the client IS
 *     the server's desktop (the bundled Electron app); for a remote browser
 *     the dialog would pop on another machine, so the button is hidden.
 *
 * The Themes / Appearance buttons are shown on every client (the toolkit
 * panels they open work in a plain browser too); only the server-settings
 * jump is gated behind [isElectronClient].
 *
 * @return the freshly-built navigation container element.
 */
private fun buildNavigationSection(): HTMLElement {
    val nav = document.createElement("div") as HTMLElement
    nav.className = "termtastic-app-settings-nav"

    nav.appendChild(buildNavButton(
        label = "Themes",
        iconHtml = ICON_THEMES,
        muted = false,
        onClick = { activateTopbarButton(TOPBAR_TITLE_THEMES) },
    ))
    nav.appendChild(buildNavButton(
        label = "Appearance",
        iconHtml = ICON_APPEARANCE,
        muted = false,
        onClick = { activateTopbarButton(TOPBAR_TITLE_APPEARANCE) },
    ))
    if (isElectronClient) {
        nav.appendChild(buildNavButton(
            label = "Server Settings",
            iconHtml = ICON_SERVER_SETTINGS,
            muted = true,
            onClick = { launchCmd(WindowCommand.OpenSettings) },
        ))
    }

    return nav
}

/**
 * Builds one full-width navigation button (leading icon + label).
 *
 * @param label    the visible button text.
 * @param iconHtml inline SVG markup for the leading glyph.
 * @param muted    when `true`, applies the low-prominence modifier class so
 *   the button recedes visually (used for the secondary "Server Settings"
 *   jump).
 * @param onClick  invoked on click.
 * @return the freshly-built button element.
 */
private fun buildNavButton(
    label: String,
    iconHtml: String,
    muted: Boolean,
    onClick: () -> Unit,
): HTMLElement {
    val button = document.createElement("button") as HTMLElement
    (button.asDynamic()).type = "button"
    button.className = "termtastic-app-settings-nav-button" +
        if (muted) " termtastic-app-settings-nav-button--muted" else ""
    // innerHTML seeds the leading icon; the label then rides in its own
    // span so the flex row can give it the remaining width.
    button.innerHTML = iconHtml
    val labelSpan = document.createElement("span") as HTMLElement
    labelSpan.className = "termtastic-app-settings-nav-label"
    labelSpan.textContent = label
    button.appendChild(labelSpan)
    button.addEventListener("click", { _: Event -> onClick() })
    return button
}

/**
 * Locates a toolkit topbar icon button by its tooltip/`title` and clicks
 * it, so a navigation button here produces the *exact* same effect as the
 * user clicking that toolbar icon — including the toolkit's mutual-exclusion
 * handling, which closes this App Settings sidebar before opening the target
 * panel.
 *
 * Dispatching a real click (rather than calling the toolkit's
 * `toggleThemeManagerSidebar` / `toggleSettingsSidebar` directly) is
 * deliberate: those functions need the shell's private `rerender` callback,
 * which isn't reachable from app code. The rendered button already closes
 * over it.
 *
 * @param title the topbar button's `title` attribute — one of
 *   [TOPBAR_TITLE_THEMES] or [TOPBAR_TITLE_APPEARANCE].
 */
private fun activateTopbarButton(title: String) {
    val button = document.querySelector(
        ".dt-topbar-icon-button[title=\"$title\"]",
    ) as? HTMLElement ?: return
    button.click()
}

/**
 * Opens (never closes) the App Settings sidebar by activating the toolkit's
 * topbar "App settings" gear button.
 *
 * Public entry point for the macOS "Settings…" app-menu item: the Electron
 * main process sends a `show-settings` IPC, which `main.kt` routes here. The
 * toolkit marks the gear button with `dt-active` while the sidebar is open,
 * and clicking it again would *close* it — so we no-op when it's already
 * showing, making this a pure "open" rather than a toggle.
 *
 * @see activateTopbarButton
 */
fun openAppSettingsSidebar() {
    val button = document.querySelector(
        ".dt-topbar-icon-button[title=\"$TOPBAR_TITLE_APP_SETTINGS\"]",
    ) as? HTMLElement ?: return
    if (button.classList.contains("dt-active")) return
    button.click()
}

/**
 * The "Experimental features" section: a labelled header followed by two
 * On/Off button rows styled like the appearance-modal pill rows (the
 * selected option gets a coloured rectangle around it via the toolkit's
 * `dt-settings-choice-btn.dt-selected` rule). Each click writes to the
 * server (and to the in-memory snapshot) immediately; the
 * `paneAddMenuItems` callback re-evaluates the flag on its next
 * invocation, so the topbar "New pane" hover dropdown picks up the new
 * gating on the next hover without any shell rerender.
 *
 * @return the freshly-built section element.
 */
private fun buildExperimentalSection(): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "termtastic-app-settings-section"

    val title = document.createElement("h3") as HTMLElement
    title.className = "termtastic-app-settings-section-title"
    title.textContent = "Experimental features"
    section.appendChild(title)

    section.appendChild(buildToggleRow(
        labelText = "Enable file browser",
        initialValue = isExperimentalFileBrowserEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(KEY_EXPERIMENTAL_FILE_BROWSER, v)
            putJsonBoolean(KEY_EXPERIMENTAL_FILE_BROWSER, v)
        },
    ))
    section.appendChild(buildToggleRow(
        labelText = "Enable Git change view",
        initialValue = isExperimentalGitViewEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(KEY_EXPERIMENTAL_GIT_VIEW, v)
            putJsonBoolean(KEY_EXPERIMENTAL_GIT_VIEW, v)
        },
    ))

    return section
}

/**
 * A single labelled On/Off button row.
 *
 * Visually mirrors the toolkit's appearance-modal pill rows: an "On" and
 * an "Off" button sit side-by-side, and the currently-selected option gets
 * a coloured rectangle drawn around it via the toolkit's
 * `dt-settings-choice-btn.dt-selected` rule. The toolkit CSS bundle is
 * already loaded by termtastic, so we just reuse those classes here
 * instead of restyling a checkbox.
 *
 * Selection is updated optimistically in the DOM on click so the
 * highlighted rectangle moves immediately, regardless of how long the
 * async server round-trip in [onChange] takes.
 *
 * @param labelText    the visible label text shown above the buttons.
 * @param initialValue which option ("On" = true) starts selected.
 * @param onChange     invoked with the new value every time the user
 *   picks a different option.
 * @return the freshly-built row element.
 */
private fun buildToggleRow(
    labelText: String,
    initialValue: Boolean,
    onChange: (Boolean) -> Unit,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "termtastic-app-settings-toggle-row"

    val labelEl = document.createElement("div") as HTMLElement
    labelEl.className = "termtastic-app-settings-toggle-label"
    labelEl.textContent = labelText
    row.appendChild(labelEl)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "dt-settings-button-row"

    val buttons = mutableListOf<HTMLElement>()
    for ((btnLabel, value) in listOf("On" to true, "Off" to false)) {
        val btn = document.createElement("button") as HTMLElement
        (btn.asDynamic()).type = "button"
        btn.className = "dt-settings-choice-btn" + if (value == initialValue) " dt-selected" else ""
        btn.textContent = btnLabel
        btn.addEventListener("click", { _: Event ->
            buttons.forEach { it.classList.remove("dt-selected") }
            btn.classList.add("dt-selected")
            onChange(value)
        })
        buttons.add(btn)
        btnRow.appendChild(btn)
    }
    row.appendChild(btnRow)

    return row
}
