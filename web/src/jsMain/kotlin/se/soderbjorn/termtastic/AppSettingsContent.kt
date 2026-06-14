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
 *  1. A jump button **"Open server settings…"** — dispatches the same
 *     [WindowCommand.OpenSettings] the old macOS app-menu entry did,
 *     surfacing the JVM Swing Settings dialog. Only shown when running
 *     inside the bundled Electron app ([isElectronClient]) — the dialog
 *     opens on the server's desktop, which a remote browser can't see.
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
 *   "Open server settings…" jump button and the Experimental features
 *   section).
 */
fun buildAppSettingsContent(): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.className = "termtastic-app-settings-body"

    // The jump button opens the JVM Swing dialog on the server's desktop —
    // useful only when the client IS the server's desktop (the bundled
    // Electron app). For a remote browser the dialog would pop on another
    // machine, so hide the affordance entirely.
    if (isElectronClient) {
        container.appendChild(buildOpenServerSettingsRow())
    }
    container.appendChild(buildExperimentalSection())

    return container
}

/**
 * One row containing a single full-width button that dispatches
 * [WindowCommand.OpenSettings] — the same command the old "Server
 * Settings…" macOS app-menu entry sent. The server-side route
 * (`/window`'s `OpenSettings` branch) opens the JVM Swing dialog.
 *
 * @return the freshly-built row element.
 */
private fun buildOpenServerSettingsRow(): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "termtastic-app-settings-row"

    val button = document.createElement("button") as HTMLElement
    (button.asDynamic()).type = "button"
    button.className = "dt-settings-jump-button"
    button.textContent = "Open server settings…"
    button.addEventListener("click", { _: Event ->
        launchCmd(WindowCommand.OpenSettings)
    })
    row.appendChild(button)

    return row
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
