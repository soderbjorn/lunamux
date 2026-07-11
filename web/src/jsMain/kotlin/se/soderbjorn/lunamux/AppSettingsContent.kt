/**
 * AppSettingsContent.kt
 * ---------------------
 * Lunamux's body factory for the toolkit-supplied "App settings"
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
 *  2. A **General** section with persisted toggles for stable,
 *     enabled-by-default features that have graduated out of Experimental:
 *       - **Enable 3D app switcher** (ships on) plus its dependent **style**
 *         picker, shown only while the switcher is on.
 *       - **Use program-set terminal titles** (ships on): panes take the
 *         title the running program sets via OSC 0/2 (consumed
 *         server-side); its explanation lives behind a "?" help popover
 *         next to the label rather than an inline paragraph.
 *  3. An **Experimental features** section with persisted boolean toggles:
 *       - **Enable file browser** — when off, hides the File Browser
 *         entry from the topbar "New pane" hover dropdown.
 *       - **Enable Git change view** — same for the Git entry.
 *       - **Enable 3D mode** — when off, hides the topbar cube button and
 *         leaves the ⌥⌘← hotkey inert (gated at the [toggleWorld3dSpike]
 *         chokepoint). Ships on by default.
 *
 * The flags persist server-side under top-level keys in
 * `/api/ui-settings`:
 *   - `experimentalFileBrowser` (Boolean, default false)
 *   - `experimentalGitView` (Boolean, default false)
 *   - `experimentalWorld3d` (Boolean, default true)
 *   - `experimental3dSwitcher` (Boolean, default true)
 *   - `experimental3dSwitcherStyle` (String, default "rotunda") — which 3D
 *     switcher style the picker selects; only shown while the switcher is on.
 *   - `terminalProgramTitle` (Boolean, default true)
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
 * @see isTerminalProgramTitleEnabled
 */
package se.soderbjorn.lunamux

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

/** Persistence key for the experimental 3D world mode flag. */
private const val KEY_EXPERIMENTAL_WORLD3D = "experimentalWorld3d"

/** Persistence key for the experimental 3D tab/pane switcher flag. */
private const val KEY_EXPERIMENTAL_OVERVIEW_3D = "experimental3dSwitcher"

/**
 * Persistence key for which 3D-switcher *style* is active (carousel ring,
 * rotunda, exposé zoom, flip stack, corridor, orbit, or vertigo). Only consulted while
 * [KEY_EXPERIMENTAL_OVERVIEW_3D] is on. Read at open time by [openOverview3d];
 * see [experimental3dSwitcherStyle].
 */
private const val KEY_EXPERIMENTAL_OVERVIEW_3D_STYLE = "experimental3dSwitcherStyle"

/** Persisted value for the original carousel-ring style. */
internal const val OVERVIEW_3D_STYLE_CAROUSEL = "carousel"

/** Persisted value for the rotunda (inside-a-cylinder) style — the default. */
internal const val OVERVIEW_3D_STYLE_ROTUNDA = "rotunda"

/** Persisted value for the exposé-zoom (real-layout → grid) style. */
internal const val OVERVIEW_3D_STYLE_EXPOSE = "expose"

/** Persisted value for the flip-stack ("deck") style — a riffled pile of cards. */
internal const val OVERVIEW_3D_STYLE_FLIPSTACK = "flipstack"

/** Persisted value for the corridor ("gallery") style — a walkable hall of panes. */
internal const val OVERVIEW_3D_STYLE_CORRIDOR = "corridor"

/** Persisted value for the orbit ("cosmos") style — worlds in space, a curved fly-through. */
internal const val OVERVIEW_3D_STYLE_ORBIT = "orbit"

/** Persisted value for the vertigo ("tower") style — floors of a tower, a dolly-zoom lock. */
internal const val OVERVIEW_3D_STYLE_VERTIGO = "vertigo"

/** Persistence key for the 3D-world **window bobbing** toggle. @see isWindowBobbingEnabled */
private const val KEY_WORLD3D_WINDOW_BOBBING = "world3dWindowBobbing"

/**
 * Persistence key for the 3D-world **status indication** style (None / Glow /
 * Glow animation / Reactor). Stored as a [StatusIndication] enum name.
 * @see world3dStatusIndication
 */
private const val KEY_WORLD3D_STATUS_INDICATION = "world3dStatusIndication"

// The opt-in "use program-set terminal titles" flag persists under
// TERMINAL_PROGRAM_TITLE_KEY from the shared clientServer module — the server
// reads the same constant, so the contract is compiler-enforced.

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

/**
 * Typography "Aa" glyph for the "Appearance" navigation button — a verbatim
 * copy of the toolkit topbar's Appearance ("Aa") button glyph
 * (`ICON_APPEARANCE` in darkness-toolkit `TopBarActions.kt`) so the settings
 * row and the toolbar button that both open Appearance show the same mark.
 */
private const val ICON_APPEARANCE =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true"><text x="1" y="18" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif" font-size="14" font-weight="700" letter-spacing="-0.5">A</text><text x="12" y="18" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif" font-size="10" font-weight="500" letter-spacing="-0.3">a</text></svg>"""

/** Keyboard glyph for the "Hotkeys" navigation button. */
private const val ICON_HOTKEYS =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="6" width="20" height="12" rx="2"/><line x1="6" y1="10" x2="6" y2="10"/><line x1="10" y1="10" x2="10" y2="10"/><line x1="14" y1="10" x2="14" y2="10"/><line x1="18" y1="10" x2="18" y2="10"/><line x1="8" y1="14" x2="16" y2="14"/></svg>"""

/** Monitor glyph for the "Server Settings" navigation button. */
private const val ICON_SERVER_SETTINGS =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>"""

/**
 * Question-mark-in-a-circle glyph for the per-setting help trigger. Rendered
 * inside the small "?" button that [buildHelpPopover] builds next to a toggle
 * label; clicking that button reveals the setting's explanation popover.
 */
private const val ICON_HELP =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>"""

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
 * @param default returned when the key is missing entirely (used by flags
 *   that ship enabled-by-default, e.g. the 3D switcher).
 * @return the stored Boolean, [default] when the key is missing, or `false`
 *   when the stored value is neither a Boolean nor the literal string "true".
 */
private fun snapshotBoolean(key: String, default: Boolean = false): Boolean {
    val element = toolkitSettingsSnapshot[key] ?: return default
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
 * Whether the experimental **3D world** mode is enabled — the interactive
 * panes-on-3D-planes overview reached via the topbar cube button and the ⌥⌘←
 * hotkey.
 *
 * Ships **on by default**: when the key is unset this returns `true`, so the
 * cube button shows and the ⌥⌘← hotkey is live. Both gate through this flag —
 * the button via [applyWorld3dSpikeChromeVisibility] and the hotkey/menu via
 * the [toggleWorld3dSpike] chokepoint. A user who turns the App Settings
 * toggle off persists an explicit `false`, which overrides the default.
 *
 * Read live from [toolkitSettingsSnapshot] so flipping the toggle takes effect
 * without a reload.
 *
 * @return `true` unless the user has explicitly opted out.
 * @see KEY_EXPERIMENTAL_WORLD3D
 * @see toggleWorld3dSpike
 */
fun isExperimentalWorld3dEnabled(): Boolean =
    snapshotBoolean(KEY_EXPERIMENTAL_WORLD3D, default = true)

/**
 * Whether the 3D tab/pane switcher (the carousel-ring overview) is enabled.
 * Ships **on by default** (including in the Electron demo): when the key is
 * unset this returns `true`, so the topbar app-switcher button shows and the ⌥⌘→
 * hotkey is live. Both gate through this flag: the button via
 * [applyOverview3dChromeVisibility] and the hotkey/menu via the
 * [toggleOverview3d] chokepoint. A user who unchecks the App Settings toggle
 * persists an explicit `false`, which overrides the default.
 *
 * Read live from [toolkitSettingsSnapshot] so flipping the toggle takes
 * effect without a reload.
 *
 * @return `true` unless the user has explicitly opted out.
 * @see KEY_EXPERIMENTAL_OVERVIEW_3D
 * @see toggleOverview3d
 */
fun isExperimental3dSwitcherEnabled(): Boolean =
    snapshotBoolean(KEY_EXPERIMENTAL_OVERVIEW_3D, default = true)

/**
 * Read a String value from the in-memory server-settings snapshot, falling
 * back to [default] when the key is missing or isn't a JSON string. Mirrors
 * [snapshotBoolean] for string-valued settings (e.g. the 3D-switcher style).
 *
 * @param key the top-level key in [toolkitSettingsSnapshot].
 * @param default returned when the key is absent or not a string primitive.
 * @return the stored string, or [default].
 */
private fun snapshotString(key: String, default: String): String {
    val element = toolkitSettingsSnapshot[key] ?: return default
    val primitive = (element as? JsonPrimitive) ?: return default
    return if (primitive.isString) primitive.content else default
}

/**
 * The user's chosen 3D-switcher style, one of [OVERVIEW_3D_STYLE_ROTUNDA]
 * (the default), [OVERVIEW_3D_STYLE_CAROUSEL], [OVERVIEW_3D_STYLE_EXPOSE],
 * [OVERVIEW_3D_STYLE_FLIPSTACK], [OVERVIEW_3D_STYLE_CORRIDOR],
 * [OVERVIEW_3D_STYLE_ORBIT], or [OVERVIEW_3D_STYLE_VERTIGO].
 * Consulted by [openOverview3d] each time the overview opens, so changing the
 * dropdown takes effect on the next open without a reload. Only meaningful
 * while [isExperimental3dSwitcherEnabled] is true.
 *
 * @return the persisted style id, defaulting to the rotunda.
 * @see KEY_EXPERIMENTAL_OVERVIEW_3D_STYLE
 */
fun experimental3dSwitcherStyle(): String =
    snapshotString(KEY_EXPERIMENTAL_OVERVIEW_3D_STYLE, OVERVIEW_3D_STYLE_ROTUNDA)

/**
 * Whether panes should take the title the running program sets (OSC 0/2).
 * Read only to seed the toggle's initial state in [buildGeneralSection]; the
 * actual feature logic lives server-side (the title watcher reads the same
 * persisted key).
 *
 * Ships **on by default**: when the key is unset this returns `true`, matching
 * the server's `terminalProgramTitleEnabled` default. A user who turns the
 * toggle off persists an explicit `false`, which overrides the default.
 *
 * @return `true` unless the user has explicitly turned it off.
 * @see TERMINAL_PROGRAM_TITLE_KEY
 */
fun isTerminalProgramTitleEnabled(): Boolean =
    snapshotBoolean(TERMINAL_PROGRAM_TITLE_KEY, default = true)

/**
 * Whether the 3D world's idle **window bobbing** (and the free-flight spaceship
 * float) is on. Ships **on by default**. Read at open by
 * [syncWorld3dRuntimeFromSettings] to seed [spikeBobEnabled], and live by the
 * in-world settings panel. @see KEY_WORLD3D_WINDOW_BOBBING
 */
fun isWindowBobbingEnabled(): Boolean =
    snapshotBoolean(KEY_WORLD3D_WINDOW_BOBBING, default = true)

/**
 * The 3D world's chosen **status indication** style, one of [StatusIndication].
 * Defaults to [StatusIndication.REACTOR]. Stored as the enum's `name`; an
 * unrecognised stored value falls back to the default. Read at open by
 * [syncWorld3dRuntimeFromSettings] to seed [spikeStatusIndication].
 * @see KEY_WORLD3D_STATUS_INDICATION
 */
internal fun world3dStatusIndication(): StatusIndication =
    snapshotString(KEY_WORLD3D_STATUS_INDICATION, StatusIndication.REACTOR.name).let { raw ->
        runCatching { StatusIndication.valueOf(raw) }.getOrDefault(StatusIndication.REACTOR)
    }

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
 * Persist a single boolean key through Lunamux's existing REST bridge.
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
 * Mirror a single string key into [toolkitSettingsSnapshot] synchronously, so
 * a subsequent read (e.g. [experimental3dSwitcherStyle]) sees the new value
 * without waiting for the server echo. String counterpart of
 * [updateSnapshotBoolean].
 *
 * @param key   the top-level key to update.
 * @param value the new string value.
 */
private fun updateSnapshotString(key: String, value: String) {
    val merged = toolkitSettingsSnapshot.toMutableMap()
    merged[key] = JsonPrimitive(value)
    toolkitSettingsSnapshot = JsonObject(merged)
}

/**
 * Persist a single string key through the same `putJsonSettings` REST bridge
 * [putJsonBoolean] uses, so the value lands on the server as a real JSON
 * string. String counterpart of [putJsonBoolean].
 *
 * @param key   the top-level key to write.
 * @param value the string value to persist.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun putJsonString(key: String, value: String) {
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
 * [LunamuxToolkitBootstrap]. Invoked each time the sidebar opens so the
 * UI reflects current persisted state without needing an explicit
 * refresh hook.
 *
 * @return the freshly-built body element (a `<div>` containing the
 *   navigation buttons and the Experimental features section).
 */
fun buildAppSettingsContent(): HTMLElement {
    val container = document.createElement("div") as HTMLElement
    container.className = "lunamux-app-settings-body"

    container.appendChild(buildNavigationSection())
    container.appendChild(buildGeneralSection())
    container.appendChild(buildOverview3dSection())
    container.appendChild(buildWorld3dSection())
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
    nav.className = "lunamux-app-settings-nav"

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
    // "Hotkeys" opens the dedicated keyboard-shortcuts sidebar. Unlike
    // Themes / Appearance it isn't a topbar button, so we call the toolkit
    // handle directly (via [openHotkeysSidebar]); the handle animates this
    // App Settings sidebar closed as part of its mutual-exclusion hand-off.
    nav.appendChild(buildNavButton(
        label = "Keyboard Shortcuts",
        iconHtml = ICON_HOTKEYS,
        muted = false,
        onClick = { openHotkeysSidebar() },
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
    button.className = "lunamux-app-settings-nav-button" +
        if (muted) " lunamux-app-settings-nav-button--muted" else ""
    // innerHTML seeds the leading icon; the label then rides in its own
    // span so the flex row can give it the remaining width.
    button.innerHTML = iconHtml
    val labelSpan = document.createElement("span") as HTMLElement
    labelSpan.className = "lunamux-app-settings-nav-label"
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
 * The "General" section: stable, enabled-by-default features that used to live
 * under "Experimental features" but have since graduated. Built exactly like
 * [buildExperimentalSection] (labelled header + On/Off pill rows), and appended
 * before it in [buildAppSettingsContent].
 *
 * Contains:
 *  - **Enable 3D app switcher** (ships on) plus its dependent **style** picker,
 *    shown/hidden in lock-step with the toggle.
 *  - **Use program-set terminal titles** (ships on) with its "?" help popover.
 *
 * @return the freshly-built section element.
 * @see buildExperimentalSection
 */
private fun buildGeneralSection(): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunamux-app-settings-section"

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-app-settings-section-title"
    title.textContent = "General"
    section.appendChild(title)

    section.appendChild(buildToggleRow(
        labelText = "Use program-set terminal titles",
        initialValue = isTerminalProgramTitleEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(TERMINAL_PROGRAM_TITLE_KEY, v)
            putJsonBoolean(TERMINAL_PROGRAM_TITLE_KEY, v)
        },
        descriptionText = "Lets programs running in a terminal name its tab, using " +
            "the standard title sequence that terminals like iTerm2 honor. With " +
            "Claude Code this means the tab shows a short summary of what you " +
            "asked it to do instead of the folder name. Terminals you've renamed " +
            "yourself are never changed, and turning this off returns tabs to " +
            "their folder names.",
    ))

    return section
}

/**
 * The **3D world** settings box: enabling 3D mode plus its look/feel controls
 * (window bobbing, status indication). The bobbing/status rows come from the shared
 * [buildWorld3dSettingsRows] builder — the same controls the in-world settings panel
 * (⌥⌘,) shows — so both surfaces edit the same persisted settings identically. The
 * sidebar just persists (default no-op onChanged; the world re-reads on its next open).
 *
 * @return the freshly-built section element.
 */
private fun buildWorld3dSection(): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunamux-app-settings-section"

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-app-settings-section-title"
    title.textContent = "3D world"
    section.appendChild(title)

    section.appendChild(buildToggleRow(
        labelText = "Enable 3D mode",
        initialValue = isExperimentalWorld3dEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(KEY_EXPERIMENTAL_WORLD3D, v)
            putJsonBoolean(KEY_EXPERIMENTAL_WORLD3D, v)
            // Show/hide the topbar cube button live; the ⌥⌘← hotkey gates itself
            // through the toggleWorld3dSpike chokepoint, so it needs no work here.
            applyWorld3dSpikeChromeVisibility()
        },
    ))
    buildWorld3dSettingsRows(section)

    return section
}

/**
 * The **3D app switcher** settings box: the enable toggle plus its style picker
 * (rotunda / carousel / exposé …), shown/hidden in lock-step with the toggle.
 *
 * @return the freshly-built section element.
 */
private fun buildOverview3dSection(): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunamux-app-settings-section"

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-app-settings-section-title"
    title.textContent = "3D app switcher"
    section.appendChild(title)

    // The style dropdown only makes sense while the switcher is on, so it is
    // built up-front (to capture its onChange) but shown/hidden in lock-step
    // with the enable toggle below.
    val styleRow = buildChoiceRow(
        labelText = "3D app switcher style",
        options = listOf(
            "Rotunda" to OVERVIEW_3D_STYLE_ROTUNDA,
            "Carousel ring" to OVERVIEW_3D_STYLE_CAROUSEL,
            "Exposé zoom" to OVERVIEW_3D_STYLE_EXPOSE,
            // Temporarily hidden until tested more closely:
            // "Flip stack (deck)" to OVERVIEW_3D_STYLE_FLIPSTACK,
            // "Corridor (gallery)" to OVERVIEW_3D_STYLE_CORRIDOR,
            // "Orbit (cosmos)" to OVERVIEW_3D_STYLE_ORBIT,
            // "Vertigo (tower)" to OVERVIEW_3D_STYLE_VERTIGO,
        ),
        initialValue = experimental3dSwitcherStyle(),
        onChange = { v ->
            updateSnapshotString(KEY_EXPERIMENTAL_OVERVIEW_3D_STYLE, v)
            putJsonString(KEY_EXPERIMENTAL_OVERVIEW_3D_STYLE, v)
        },
    )
    styleRow.style.display = if (isExperimental3dSwitcherEnabled()) "" else "none"

    section.appendChild(buildToggleRow(
        labelText = "Enable 3D app switcher",
        initialValue = isExperimental3dSwitcherEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(KEY_EXPERIMENTAL_OVERVIEW_3D, v)
            putJsonBoolean(KEY_EXPERIMENTAL_OVERVIEW_3D, v)
            // Reveal the style picker in step with the toggle.
            styleRow.style.display = if (v) "" else "none"
            // Show/hide the topbar app-switcher button live; the ⌥⌘→ hotkey gates
            // itself through the toggleOverview3d chokepoint, so it needs no
            // work here.
            applyOverview3dChromeVisibility()
        },
    ))
    section.appendChild(styleRow)

    return section
}

/**
 * Append the two **3D-world** settings controls — *Window bobbing* and *Status
 * indication* — to [container]. Shared by the App Settings sidebar's General section
 * ([buildGeneralSection]) and the in-world settings overlay (⌥⌘,,
 * [buildWorld3dSettingsPanel]), so both render identical controls that persist
 * identically.
 *
 * Each row does the usual snapshot-update + persist and then runs [onChanged]. The
 * in-world panel passes `::syncWorld3dRuntimeFromSettings` so a change takes effect on
 * the live world immediately; the sidebar passes the default no-op (the world reads the
 * settings fresh on its next open).
 *
 * @param container the element to append the two rows to.
 * @param onChanged run after each change has been persisted; defaults to a no-op.
 * @see buildToggleRow @see buildChoiceRow @see world3dStatusIndication
 */
fun buildWorld3dSettingsRows(container: HTMLElement, onChanged: () -> Unit = {}) {
    container.appendChild(buildToggleRow(
        labelText = "Window bobbing",
        initialValue = isWindowBobbingEnabled(),
        onChange = { v ->
            updateSnapshotBoolean(KEY_WORLD3D_WINDOW_BOBBING, v)
            putJsonBoolean(KEY_WORLD3D_WINDOW_BOBBING, v)
            onChanged()
        },
        descriptionText = "Gently floats unfocused windows up and down — and, in free " +
            "flight, makes the spaceship camera bob so it feels like it's hovering.",
    ))
    container.appendChild(buildChoiceRow(
        labelText = "Status indication",
        options = listOf(
            "None" to StatusIndication.NONE.name,
            "Glow" to StatusIndication.GLOW.name,
            "Glow animation" to StatusIndication.GLOW_ANIMATION.name,
            "Reactor" to StatusIndication.REACTOR.name,
        ),
        initialValue = world3dStatusIndication().name,
        onChange = { v ->
            updateSnapshotString(KEY_WORLD3D_STATUS_INDICATION, v)
            putJsonString(KEY_WORLD3D_STATUS_INDICATION, v)
            onChanged()
        },
    ))
}

/**
 * The "Experimental features" section: a labelled header followed by On/Off
 * button rows styled like the appearance-modal pill rows (the selected option
 * gets a coloured rectangle around it via the toolkit's
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
    section.className = "lunamux-app-settings-section"

    val title = document.createElement("h3") as HTMLElement
    title.className = "lunamux-app-settings-section-title"
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
    // "Enable 3D mode" now lives in its own "3D world" section ([buildWorld3dSection]).

    return section
}

/**
 * A single labelled On/Off button row.
 *
 * Visually mirrors the toolkit's appearance-modal pill rows: an "On" and
 * an "Off" button sit side-by-side, and the currently-selected option gets
 * a coloured rectangle drawn around it via the toolkit's
 * `dt-settings-choice-btn.dt-selected` rule. The toolkit CSS bundle is
 * already loaded by Lunamux, so we just reuse those classes here
 * instead of restyling a checkbox.
 *
 * Selection is updated optimistically in the DOM on click so the
 * highlighted rectangle moves immediately, regardless of how long the
 * async server round-trip in [onChange] takes.
 *
 * @param labelText       the visible label text shown above the buttons.
 * @param initialValue    which option ("On" = true) starts selected.
 * @param onChange        invoked with the new value every time the user
 *   picks a different option.
 * @param descriptionText optional explanation of what the setting does. When
 *   supplied it is no longer rendered inline under the label; instead a small
 *   "?" help button sits next to the label and reveals this text in a popover
 *   on click (see [buildHelpPopover]). Omitted (`null`) renders no help affordance.
 * @return the freshly-built row element.
 */
private fun buildToggleRow(
    labelText: String,
    initialValue: Boolean,
    onChange: (Boolean) -> Unit,
    descriptionText: String? = null,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "lunamux-app-settings-toggle-row"

    // Label header. With a description, the label shares a row with a "?" help
    // trigger whose popover carries the explanation; without one, the label is
    // a plain full-width block as before.
    val header = document.createElement("div") as HTMLElement
    header.className = "lunamux-app-settings-toggle-header"

    val labelEl = document.createElement("span") as HTMLElement
    labelEl.className = "lunamux-app-settings-toggle-label"
    labelEl.textContent = labelText
    header.appendChild(labelEl)

    if (descriptionText != null) {
        header.appendChild(buildHelpPopover(descriptionText))
    }
    row.appendChild(header)

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

/**
 * A single labelled multiple-choice button row, the string-valued sibling of
 * [buildToggleRow]. Renders [labelText] above a `.dt-settings-button-row` of
 * pill buttons (one per entry of [options]); the button whose value equals the
 * current selection carries the toolkit's `dt-selected` highlight. Selection is
 * updated optimistically in the DOM on click so the highlight moves immediately,
 * regardless of the async persistence in [onChange].
 *
 * Used for the 3D-switcher **style** picker (carousel / rotunda / exposé), which
 * needs three options rather than the On/Off pair [buildToggleRow] hard-codes.
 *
 * @param labelText    the visible label shown above the buttons.
 * @param options      `(button label, persisted value)` pairs, left to right.
 * @param initialValue the value whose button starts selected.
 * @param onChange     invoked with the newly-picked value on each change.
 * @return the freshly-built row element.
 * @see buildToggleRow
 */
private fun buildChoiceRow(
    labelText: String,
    options: List<Pair<String, String>>,
    initialValue: String,
    onChange: (String) -> Unit,
): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "lunamux-app-settings-toggle-row"

    val header = document.createElement("div") as HTMLElement
    header.className = "lunamux-app-settings-toggle-header"
    val labelEl = document.createElement("span") as HTMLElement
    labelEl.className = "lunamux-app-settings-toggle-label"
    labelEl.textContent = labelText
    header.appendChild(labelEl)
    row.appendChild(header)

    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "dt-settings-button-row"

    val buttons = mutableListOf<HTMLElement>()
    for ((btnLabel, value) in options) {
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

/**
 * Build a "?" help affordance: a small circular icon button that toggles a
 * popover containing [text].
 *
 * Called by [buildToggleRow] for any toggle that carries a `descriptionText`,
 * replacing the old always-visible muted paragraph under the row. Keeping the
 * explanation behind a click declutters the Experimental features list while
 * still letting the user read what a setting does on demand.
 *
 * Interaction: clicking the trigger opens the popover (and registers a
 * document-level click listener that closes it again when the user clicks
 * anywhere outside the affordance); clicking the trigger a second time closes
 * it. The trigger's own click is stopped from bubbling so the freshly-added
 * outside-click listener doesn't immediately fire on the same event.
 *
 * @param text the explanation to show inside the popover.
 * @return a `<span>` wrapper element containing the trigger button and its
 *   (CSS-hidden-until-open) popover, ready to append next to a toggle label.
 * @see buildToggleRow
 */
private fun buildHelpPopover(text: String): HTMLElement {
    val wrapper = document.createElement("span") as HTMLElement
    wrapper.className = "lunamux-app-settings-help"

    val trigger = document.createElement("button") as HTMLElement
    (trigger.asDynamic()).type = "button"
    trigger.className = "lunamux-app-settings-help-trigger"
    trigger.setAttribute("aria-label", "What does this setting do?")
    trigger.setAttribute("aria-haspopup", "dialog")
    trigger.setAttribute("aria-expanded", "false")
    trigger.innerHTML = ICON_HELP

    val popover = document.createElement("div") as HTMLElement
    popover.className = "lunamux-app-settings-help-popover"
    popover.setAttribute("role", "tooltip")
    popover.textContent = text

    // Holds the active document-level outside-click listener while the popover
    // is open, so we can remove exactly that listener again on close.
    var outsideClick: ((Event) -> Unit)? = null

    fun close() {
        wrapper.classList.remove("is-open")
        trigger.setAttribute("aria-expanded", "false")
        outsideClick?.let { document.removeEventListener("click", it) }
        outsideClick = null
    }

    fun open() {
        wrapper.classList.add("is-open")
        trigger.setAttribute("aria-expanded", "true")
        val handler: (Event) -> Unit = handler@{ e: Event ->
            val target = e.target as? org.w3c.dom.Node
            if (target != null && wrapper.contains(target)) return@handler
            close()
        }
        outsideClick = handler
        document.addEventListener("click", handler)
    }

    trigger.addEventListener("click", { e: Event ->
        // Stop bubbling so the outside-click listener open() registers below
        // doesn't see this very click and close the popover immediately.
        e.stopPropagation()
        if (wrapper.classList.contains("is-open")) close() else open()
    })

    wrapper.appendChild(trigger)
    wrapper.appendChild(popover)
    return wrapper
}
