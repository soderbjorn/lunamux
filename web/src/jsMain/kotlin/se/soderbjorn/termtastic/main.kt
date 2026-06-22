/**
 * Entry point for the Termtastic Kotlin/JS web frontend.
 *
 * Bootstraps the application by initializing authentication, creating the
 * [TermtasticClient] and [WindowSocket], setting up the [AppBackingViewModel],
 * hydrating UI settings from the server, and mounting the UI through the
 * darkness-toolkit's `mountAppShell` (see [bootViaToolkitShell]). All
 * chrome-side concerns (top bar, tab strip, sidebar tree, pane drag/resize,
 * layout-preset dropdown, theme manager) are toolkit-owned now; this file
 * keeps only the bits that genuinely belong to the app: auth, the
 * server-state plumbing, the reactive theme / font / appearance collectors,
 * the Electron-mac body tag, and the cross-pane "click outside any open
 * popover to close it" handler that the SettingsPanel and pane-palette
 * menus still rely on.
 *
 * @see start
 * @see bootViaToolkitShell
 * @see connectWindow
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.injectDarknessToolkitStyles
import se.soderbjorn.darkness.web.setDtCustomTitleBarBodyClass

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.client.ClientIdentity
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SettingsPersister
import kotlin.js.json
import kotlin.time.Duration.Companion.seconds

/**
 * Kotlin/JS main entry point. Imports the xterm.js CSS and schedules [start]
 * to run on `window.onload`.
 */
fun main() {
    @Suppress("UNUSED_VARIABLE")
    val css = xtermCss
    window.onload = { start() }
}

/**
 * Mirrors the server's UI-settings payload into
 * [toolkitSettingsSnapshot] so the toolkit-shape [Persister] adapter
 * always sees the latest snapshot. Called from [start] after every
 * successful `/api/ui-settings` round-trip. The payload is the
 * canonical-nested JsonObject the server returns; the adapter reads
 * it directly without re-flattening.
 *
 * @param payload the settings JSON object the server returned.
 */
internal fun updateToolkitSettingsSnapshot(payload: kotlinx.serialization.json.JsonObject) {
    toolkitSettingsSnapshot = payload
}

/**
 * Returns whether the host reports a dark system colour-scheme preference.
 * Consulted only when the user's [Appearance] is [Appearance.Auto] — in
 * [Appearance.Dark] / [Appearance.Light] the explicit choice always wins.
 *
 * @return `true` when `prefers-color-scheme: dark` matches.
 */
internal fun isSystemDark(): Boolean {
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    return mql.matches
}

/**
 * Resolve the active [se.soderbjorn.darkness.core.ResolvedTheme] from the
 * ViewModel's current light/dark slot selection and appearance, then apply it
 * to the DOM (`--t-*` CSS vars via the toolkit's `applyTheme`) and the
 * xterm.js instances.
 *
 * Called from every code path that changes which slot is live or what that
 * slot points at: appearance toggle, media-query change, theme selection in
 * the Theme Manager, custom-theme edits, etc.
 *
 * Persistence is owned by the [AppBackingViewModel] setters themselves (they
 * write the v2 selection / custom blobs on every change), so this helper only
 * needs to repaint and poke the open editor.
 *
 * @see se.soderbjorn.termtastic.client.viewmodel.resolvedTheme
 */
internal fun refreshAndApplyActiveTheme() {
    val s = appVm.stateFlow.value
    console.log(
        "[theme] refresh done —" +
            " light=${s.lightThemeName} dark=${s.darkThemeName}" +
            " appearance=${s.appearance}" +
            " custom=${s.customThemes.size}"
    )
    applyAll()
    refreshThemeManager()
    // Push the live selection into the toolkit's stored snapshot. Termtastic
    // owns theme resolution outside the toolkit (slot picks write through
    // [appVm] via [TermtasticThemeManagerHost]), so without this sync the
    // toolkit's snapshot stays frozen at whatever it read from the persister
    // at mount. The topbar appearance-cycle button persists from that stale
    // snapshot — re-emitting the default slot names and clobbering the
    // light/dark theme the user picked. See [AppShellHandle.setThemeSnapshot].
    appShellHandle?.setThemeSnapshot(s.toThemeSnapshot())
}

/**
 * Initializes the Termtastic web application.
 *
 * Steps:
 * 1. Inject toolkit styles
 * 2. Ensure an auth token and detect the client kind (Electron vs Web)
 * 3. Create [TermtasticClient], [WindowSocket], [AppBackingViewModel]
 * 4. Construct the REST-backed [SettingsPersister] and the toolkit
 *    [SettingsPersisterAdapter]
 * 5. Hydrate UI settings from the server via REST
 * 6. Mount the UI through the toolkit's `mountAppShell` (via
 *    [bootViaToolkitShell])
 * 7. Wire reactive theme / font / appearance / electron title-bar
 *    collectors that drive xterm.js theming and IPC bridges (these are
 *    independent of the chrome and run regardless of which shell hosts
 *    the panes)
 * 8. Connect the window WebSocket for envelope routing
 *
 * @see bootViaToolkitShell
 * @see connectWindow
 */
private fun start() {
    injectDarknessToolkitStyles()
    ensureAuthToken()

    clientTypeAtStart =
        if (window.navigator.userAgent.contains("Electron", ignoreCase = true)) "Computer" else "Web"
    authQueryParam = "auth=" + encodeUriComponent(authTokenForSending()) +
        "&clientType=" + encodeUriComponent(clientTypeAtStart)
    proto = if (window.location.protocol == "https:") "wss" else "ws"
    isElectronClient = window.navigator.userAgent.contains("Electron", ignoreCase = true)
    // The `dt-electron-mac` body class is added by darkness-toolkit's
    // `autoApplyElectronMacBodyClass` (called from `injectDarknessToolkitStyles`
    // above), and `dt-mac-fullscreen` is wired automatically by
    // `autoWireMacFullscreenBodyClass` against `darknessApi.onFullscreenChange`.
    // No manual UA sniff or fullscreen subscriber needed here.

    // Server-state plumbing. In demo mode the "server" is the in-process
    // simulation selected by the magic demo host — no network anywhere.
    isDemoClient = detectDemoUrl()
    val loc = window.location
    val port = loc.port.toIntOrNull() ?: if (loc.protocol == "https:") 443 else 80
    val serverUrl = if (isDemoClient) {
        ServerUrl(host = se.soderbjorn.termtastic.client.demo.DEMO_HOST, port = 0)
    } else {
        ServerUrl(host = loc.hostname, port = port)
    }
    termtasticClient = TermtasticClient(
        serverUrl = serverUrl,
        authToken = authTokenForSending(),
        identity = ClientIdentity(type = clientTypeAtStart),
    )
    windowSocket = termtasticClient.openWindowSocket()

    webSettingsPersister = if (isDemoClient) object : SettingsPersister {
        // Demo mode: settings changes apply for the page's lifetime but are
        // never persisted anywhere (and never leave the browser).
        override suspend fun putSetting(key: String, value: String) = Unit
        override suspend fun putSettings(settings: Map<String, String>) = Unit
        override fun fireAndForgetPutSettings(settings: Map<String, String>) = Unit
        override suspend fun putJsonSettings(settings: kotlinx.serialization.json.JsonObject) = Unit
    } else object : SettingsPersister {
        private fun postSettings(body: dynamic) {
            val init: dynamic = js("({})")
            init.method = "POST"
            init.headers = json(
                "Content-Type" to "application/json",
                "X-Termtastic-Auth" to authTokenForSending(),
                "X-Termtastic-Client-Type" to clientTypeAtStart,
            )
            init.body = JSON.stringify(body)
            // keepalive lets the POST outlive the renderer. Required for
            // settings whose side effect tears the renderer down before the
            // request can complete — notably electronCustomTitleBar, where
            // the state change triggers a BrowserWindow rebuild in the main
            // process. Without this, the cancelled POST never reaches the
            // server and the new renderer re-hydrates from stale DB state.
            init.keepalive = true
            // Swallow rejections: a fetch initiated immediately before the
            // renderer is torn down (e.g. electronCustomTitleBar triggering
            // a BrowserWindow rebuild) rejects with "Failed to fetch" even
            // when keepalive carries the request through. We don't want
            // those expected rejections to surface as uncaught promise
            // errors in the console.
            window.fetch("/api/ui-settings", init)
                .asDynamic()
                .catch { _: dynamic -> }
        }

        override suspend fun putSetting(key: String, value: String) {
            postSettings(json(key to value))
        }

        override suspend fun putSettings(settings: Map<String, String>) {
            val obj: dynamic = js("({})")
            for ((k, v) in settings) { obj[k] = v }
            postSettings(obj)
        }

        override fun fireAndForgetPutSettings(settings: Map<String, String>) {
            val obj: dynamic = js("({})")
            for ((k, v) in settings) { obj[k] = v }
            postSettings(obj)
        }

        override suspend fun putJsonSettings(settings: kotlinx.serialization.json.JsonObject) {
            // Round-trip through the runtime JSON parser so nested
            // objects/arrays reach the server as real JSON — not the
            // stringified-blob fallback from the default implementation.
            val body = js("JSON.parse")(settings.toString())
            postSettings(body)
        }
    }
    appVm = AppBackingViewModel(
        windowSocket = windowSocket,
        windowState = termtasticClient.windowState,
        settingsPersister = webSettingsPersister,
    )

    // Toolkit-shape persister adapter. Reads serve from the in-memory
    // snapshot that `applyServerUiSettings` populates; writes round-trip
    // through `webSettingsPersister.putSetting` (the same REST bridge
    // termtastic has always used).
    toolkitPersister = SettingsPersisterAdapter(
        settingsPersister = webSettingsPersister,
        snapshot = { toolkitSettingsSnapshot },
        appVm = appVm,
    )

    // Launch the backing VM's run loop.
    GlobalScope.launch { appVm.run() }

    // Hydrate UI settings from server via REST *before* mounting the
    // toolkit shell. The toolkit reads its `LAYOUT_STATE` blob (and
    // every other persisted UI-settings key) once at mount, through
    // `SettingsPersisterAdapter` → `toolkitSettingsSnapshot`. If the
    // snapshot is still empty when the shell mounts, the toolkit paints
    // with default geometry and never re-reads — so pane positions and
    // sizes from the previous session are silently dropped on every
    // client restart, even though the server has them on disk.
    //
    // The 2-second timeout is a fallback for the unreachable-server
    // case so we don't hang at a blank screen — we mount with defaults
    // in that case, and any later `WindowEnvelope.UiSettings` push over
    // the WebSocket can take effect once the connection lands.
    val uiSettingsInit: dynamic = js("({})")
    uiSettingsInit.headers = json(
        "X-Termtastic-Auth" to authTokenForSending(),
        "X-Termtastic-Client-Type" to clientTypeAtStart,
    )
    GlobalScope.launch {
        // Demo mode never fetches: it mounts straight away with default
        // settings (a fresh-install look), keeping the boot instant and
        // network-free even when no server exists behind the page.
        val hydrated: kotlinx.serialization.json.JsonObject? = if (isDemoClient) null else withTimeoutOrNull(2.seconds) {
            runCatching {
                val resp = window.fetch("/api/ui-settings", uiSettingsInit).await()
                if (!(resp.asDynamic().ok as Boolean)) return@runCatching null
                val data = resp.json().await()
                val jsonStr = JSON.stringify(data)
                kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                    as kotlinx.serialization.json.JsonObject
            }.getOrNull()
        }
        if (hydrated != null) {
            appVm.applyServerUiSettings(hydrated)
            updateToolkitSettingsSnapshot(hydrated)
        } else if (isDemoClient) {
            // Demo always boots dark, with the fixture's pane arrangements.
            // demoToolkitSettingsSnapshot bakes a dark-appearance UI_SETTINGS
            // blob (so the toolkit chrome mounts dark regardless of the
            // visitor's OS colour-scheme) alongside the LAYOUT_STATE geometry
            // (toolkit-owned on web). The same object is fed to the appVm via
            // applyServerUiSettings so termtastic's own painters (xterm,
            // section overlays) match the chrome's dark default — mirroring
            // the hydrated-from-server path above.
            val snapshot = demoToolkitSettingsSnapshot(termtasticClient.windowState.config.value)
            updateToolkitSettingsSnapshot(snapshot)
            appVm.applyServerUiSettings(snapshot)
        } else {
            console.warn("[ui-settings] hydration failed or timed out — mounting with defaults")
        }

        // Always resolve the active theme from current state and apply
        // (CSS vars + xterm theme) — also on the no-hydration / clean-
        // install path — so the `--t-*` vars and the xterm palette match
        // the chrome on first frame instead of waiting for the first
        // settings echo.
        refreshAndApplyActiveTheme()

        // The Claude usage bar element is built and cached into `usageBar` by
        // `TermtasticToolkitBootstrap.buildSidebarFooter()` when the toolkit
        // mounts the left-sidebar footer (below), so there's nothing to cache
        // from `index.html` here anymore.

        // Mount the UI through the toolkit's `mountAppShell`. This builds
        // the entire app frame (top bar, tab strip, sidebar, layout root,
        // pane chrome) inside `#app`; the toolkit bottom bar is disabled
        // (showBottomBar = false). The bespoke chrome that
        // used to live here is gone — see TERMTASTIC-TOOLKIT-MIGRATION.md
        // for the migration boundary and the regressions documented there.
        val appEl = document.getElementById("app") as HTMLElement
        bootViaToolkitShell(appEl)

        // Re-apply theme paint AFTER the toolkit shell mounts its panes.
        // [bootViaToolkitShell] returns synchronously but `mountAppShell`
        // does its DOM build inside a `scope.launch` coroutine, so when
        // this line is first reached, neither `.dt-pane` nor `.terminal-cell`
        // exists yet. The first `refreshAndApplyActiveTheme()` call at
        // line ~294 above ran before mountAppShell even started; this
        // second call must wait until termtastic's legacy section overlay
        // targets (`.terminal-cell`, `.app-header`, `#sidebar`, …) are
        // actually in the DOM, otherwise `applyAppearanceClass`'s
        // querySelectorAll walks find nothing and the section paint
        // silently no-ops — leaving the user staring at chrome-section
        // titlebar colour around the terminal pane content until they
        // re-select a theme.
        //
        // Poll for the first `.terminal-cell` to appear (the cell carries
        // the windows-section paint target). 50 ms tick; ~3 s upper bound
        // as a defensive cap so we don't loop forever if pane mount fails
        // outright. Once the cell exists the toolkit's first rerender is
        // complete and `applyAppearanceClass` can stamp every section
        // overlay.
        var elapsed = 0
        while (document.querySelectorAll(".terminal-cell").length == 0 && elapsed < 3000) {
            kotlinx.coroutines.delay(50)
            elapsed += 50
        }
        refreshAndApplyActiveTheme()
    }

    // Request notification permission (Electron grants via setPermissionRequestHandler in main.js).
    // Skipped in demo mode: a marketing-site iframe must not pop a
    // notification-permission prompt at visitors.
    if (!isDemoClient && js("typeof Notification !== 'undefined'") as Boolean) {
        if ((js("Notification.permission") as String) != "granted") {
            js("Notification.requestPermission()")
        }
    }

    // Show/hide the disconnected modal based on the main server WebSocket state.
    // Skip the initial `false` emission — we only show the modal after the
    // connection has been established at least once and then drops.
    GlobalScope.launch {
        var wasConnected = false
        windowSocket.connected.collect { connected ->
            if (connected) {
                wasConnected = true
                windowSocketConnected = true
                updateAggregateStatus()
            } else if (wasConnected) {
                windowSocketConnected = false
                updateAggregateStatus()
            }
        }
    }

    // Reactively apply visual changes when the VM's appearance/theme state changes.
    // We track the light/dark slot names, appearance, and the custom-theme list
    // so any upstream change that could affect which [ResolvedTheme] is live
    // triggers a refresh. `refreshAndApplyActiveTheme()` resolves the active
    // slot and repaints the DOM (and the xterm.js instances); persistence is
    // owned by the appVm setters, so no extra mirror-write is needed here.
    GlobalScope.launch {
        var prevAppearance = appVm.stateFlow.value.appearance
        var prevLight = appVm.stateFlow.value.lightThemeName
        var prevDark = appVm.stateFlow.value.darkThemeName
        var prevCustomThemes = appVm.stateFlow.value.customThemes
        appVm.stateFlow.collect { state ->
            val slotChanged = state.lightThemeName != prevLight || state.darkThemeName != prevDark
            // Structural equality (`!=`), not identity (`!==`). Server
            // echoes land freshly-parsed list references via
            // `SettingsViewModel.applyServerUiSettings`, so reference checks
            // would flip on every round-trip even when the data is identical.
            val customChanged = state.customThemes != prevCustomThemes
            val appearanceChanged = state.appearance != prevAppearance
            if (slotChanged || appearanceChanged || customChanged) {
                prevLight = state.lightThemeName
                prevDark = state.darkThemeName
                prevCustomThemes = state.customThemes
                prevAppearance = state.appearance
                refreshAndApplyActiveTheme()
            }
        }
    }

    // Reactively push the persisted font family into xterm.js instances and
    // the `--t-font-mono` CSS var whenever it changes — covers initial
    // hydration (null → server value) and cross-tab sync through the
    // UiSettings envelope.
    GlobalScope.launch {
        // `effectiveFontKey` folds in the default (JetBrains Mono) when the
        // user hasn't picked a monospaced font, matching the host getter.
        var prevFontFamily: String? = appVm.stateFlow.value.paneFontFamily
        applyGlobalFontFamily(effectiveFontKey(prevFontFamily))
        se.soderbjorn.darkness.web.applyMonoFontFamily(effectiveFontKey(prevFontFamily))
        appVm.stateFlow.collect { state ->
            if (state.paneFontFamily != prevFontFamily) {
                prevFontFamily = state.paneFontFamily
                applyGlobalFontFamily(effectiveFontKey(prevFontFamily))
                se.soderbjorn.darkness.web.applyMonoFontFamily(effectiveFontKey(prevFontFamily))
            }
        }
    }

    // Reactively sync sidebar / tabbar font face + size onto the toolkit's
    // `--dt-font-*` CSS variables. The toolkit chrome (`.dt-sidebar`,
    // `.dt-topbar`, `.dt-tabbar`) reads through these vars; without this
    // collector the chrome would only pick up the persisted preference on
    // the next `mountAppShell.applyUi` rebuild. Pane font *size* is also
    // mirrored to `--dt-font-mono-size` for any pane CSS that has been
    // migrated to the toolkit chain.
    GlobalScope.launch {
        var prevPaneSize: Int? = appVm.stateFlow.value.paneFontSize
        var prevSidebarFamily: String? = appVm.stateFlow.value.sidebarFontFamily
        var prevSidebarSize: Int? = appVm.stateFlow.value.sidebarFontSizePx
        var prevTabbarFamily: String? = appVm.stateFlow.value.tabbarFontFamily
        var prevTabbarSize: Int? = appVm.stateFlow.value.tabbarFontSizePx
        // Chrome font family/size fold in the default fonts (JetBrains Mono /
        // 12px) via `effectiveFontKey` / `effectiveChromeSize`, matching the
        // host getters so the applied chrome and the Settings highlight agree.
        se.soderbjorn.darkness.web.applyMonoFontSizePx(prevPaneSize)
        prevPaneSize?.let { applyGlobalFontSize(it) }
        se.soderbjorn.darkness.web.applySidebarFontFamily(effectiveFontKey(prevSidebarFamily))
        se.soderbjorn.darkness.web.applySidebarFontSizePx(effectiveChromeSize(prevSidebarSize))
        se.soderbjorn.darkness.web.applyTabbarFontFamily(effectiveFontKey(prevTabbarFamily))
        se.soderbjorn.darkness.web.applyTabbarFontSizePx(effectiveChromeSize(prevTabbarSize))
        appVm.stateFlow.collect { state ->
            if (state.paneFontSize != prevPaneSize) {
                prevPaneSize = state.paneFontSize
                se.soderbjorn.darkness.web.applyMonoFontSizePx(prevPaneSize)
                // xterm.js, the file-browser markdown preview, and the
                // git diff pane all set their font-size via JS API or
                // inline style — the `--dt-font-mono-size` CSS var
                // alone doesn't reach them. `applyGlobalFontSize`
                // walks every live terminal/file-browser/git pane and
                // pushes the new size into the appropriate sink.
                prevPaneSize?.let { applyGlobalFontSize(it) }
            }
            if (state.sidebarFontFamily != prevSidebarFamily) {
                prevSidebarFamily = state.sidebarFontFamily
                se.soderbjorn.darkness.web.applySidebarFontFamily(effectiveFontKey(prevSidebarFamily))
            }
            if (state.sidebarFontSizePx != prevSidebarSize) {
                prevSidebarSize = state.sidebarFontSizePx
                se.soderbjorn.darkness.web.applySidebarFontSizePx(effectiveChromeSize(prevSidebarSize))
            }
            if (state.tabbarFontFamily != prevTabbarFamily) {
                prevTabbarFamily = state.tabbarFontFamily
                se.soderbjorn.darkness.web.applyTabbarFontFamily(effectiveFontKey(prevTabbarFamily))
            }
            if (state.tabbarFontSizePx != prevTabbarSize) {
                prevTabbarSize = state.tabbarFontSizePx
                se.soderbjorn.darkness.web.applyTabbarFontSizePx(effectiveChromeSize(prevTabbarSize))
            }
        }
    }

    // Reconcile Electron's cached `titleBarStyle` with the server value. The
    // setting is the only one whose effect happens in the Electron main
    // process (outside the renderer), so any change — whether from this
    // device or another client — needs to be forwarded via IPC. The main
    // process diffs against its own cache and only rebuilds the window when
    // the value actually flipped.
    if (isElectronClient) {
        // Start the shared news/update checker for the desktop build; it
        // mirrors its result onto both the "New update available!" logo
        // label and the "News" pill beside it. See NewsLabel.kt. The demo
        // build runs this too: the news/updates icon and its check are part
        // of the showcase, so they appear in demo mode just like in a real
        // build.
        startNewsUpdatesChecker()

        GlobalScope.launch {
            var prev: Boolean? = null
            appVm.stateFlow
                .filter { it.uiSettingsHydrated }
                .collect { state ->
                    val value = state.electronCustomTitleBar
                    if (value != prev) {
                        prev = value
                        // Tell the toolkit to reserve space for the macOS
                        // traffic-light cluster only while we're using
                        // `titleBarStyle: "hiddenInset"` (custom titlebar).
                        // The CSS is gated on `dt-electron-mac` too, so this
                        // is a no-op on non-mac Electron.
                        setDtCustomTitleBarBodyClass(value)
                        // Push the new value to the Electron main process via
                        // the cross-app `darknessApi` namespace so the BrowserWindow
                        // can rebuild with the right `titleBarStyle`. Shared with
                        // notegrow and the darkness-toolkit's own `AppShellMount`
                        // subscriber.
                        val darknessApi = window.asDynamic().darknessApi
                        if (darknessApi?.setCustomTitleBar != null) {
                            darknessApi.setCustomTitleBar(value)
                        }
                    }
                }
        }

        // macOS native fullscreen → `dt-mac-fullscreen` body class is
        // wired by darkness-toolkit's `autoWireMacFullscreenBodyClass`
        // against `darknessApi.onFullscreenChange` (which the preload
        // mirrors from `electronApi.onFullscreenChange`). No subscriber
        // needed here.
    }

    // macOS app menu → "About Termtastic" routes to the in-app dialog.
    val electronApi = window.asDynamic().electronApi
    if (electronApi?.onShowAboutDialog != null) {
        electronApi.onShowAboutDialog({ showAboutDialog() })
    }

    // macOS app menu → "Settings…" (⌘,) opens the in-app App Settings
    // sidebar. Forwarded from the Electron main process via the
    // `show-settings` IPC.
    if (electronApi?.onShowSettings != null) {
        electronApi.onShowSettings({ openAppSettingsSidebar() })
    }

    // macOS app menu → "File → New Tab" (⌘T) adds a tab. Forwarded from the
    // Electron main process via the `new-tab` IPC; fires the same AddTab
    // command the "+" tab-strip button uses, so the shortcut and the button
    // behave identically.
    if (electronApi?.onNewTab != null) {
        electronApi.onNewTab({ launchCmd(WindowCommand.AddTab) })
    }

    // macOS Debug menu → per-pane state override (Working / Waiting /
    // Clear). Forwarded from the Electron main process via the
    // `debug-set-pane-state` IPC; the renderer-side helper looks up
    // the focused pane and dispatches a SetStateOverride command.
    if (electronApi?.onDebugSetPaneState != null) {
        electronApi.onDebugSetPaneState({ mode: String? ->
            if (mode != null) applyDebugPaneStateOverride(mode)
        })
    }

    // Quit confirmation: the Electron main process intercepts every
    // quit intent (Cmd-Q, menu Quit, window close) and asks the
    // renderer to display the modal. We post the user's choice back
    // via `respondQuitConfirmation`; until then, the quit is held.
    if (electronApi?.onShowQuitConfirmation != null) {
        electronApi.onShowQuitConfirmation({
            showQuitConfirmationDialog { result ->
                val payload = js("({})")
                payload.confirmed = result.confirmed
                payload.killServer = result.killServer
                electronApi.respondQuitConfirmation(payload)
            }
        })
    }

    // Browser dev console fallback for the macOS Debug menu (the menu
    // exists only inside Electron). Power users can still drive the
    // override from `window.__ttDebugSetPaneState('working')` etc.
    window.asDynamic().__ttDebugSetPaneState = { mode: String? ->
        if (mode != null) applyDebugPaneStateOverride(mode)
    }

    // React to system color scheme changes when in Auto.
    val mql = window.matchMedia("(prefers-color-scheme: dark)")
    mql.asDynamic().addEventListener("change", {
        if (appVm.stateFlow.value.appearance == Appearance.Auto) refreshAndApplyActiveTheme()
    })

    // Close any open termtastic-side popovers on outside press. The toolkit
    // closes its own dropdowns; this keeps the SettingsPanel + pane palette
    // popovers + tab-bar overflow menus closing as expected. Capture phase
    // so it fires before pane-internal handlers that `stopPropagation()` on
    // their own mouse events.
    //
    // The same listener also records the `.dt-pane` ancestor of the press
    // target into [lastPointerDownPaneId] so the terminal `focusout`
    // safety net in [LayoutBuilder.ensureTerminal] can tell a real
    // toolkit-driven detach apart from a deliberate cross-pane click on
    // a non-focusable element (e.g. another pane's title bar). Without
    // this signal, clicking a FileBrowser / Git pane title bar to focus
    // it caused the previously-focused terminal's focusout handler to
    // refocus itself on the next frame, racing the toolkit's just-sent
    // `SetFocusedPane` and reverting the user's selection.
    document.asDynamic().addEventListener("pointerdown", { ev: dynamic ->
        var node: dynamic = ev.target
        while (node != null && node.nodeType != 1) node = node.parentNode
        // `.dt-sidebar-row` is included alongside `.dt-pane`: a press on
        // a sidebar pane row is just as much a deliberate "focus that
        // pane" gesture as a press inside the pane itself (both carry
        // `data-pane-id`). Without it, clicking a pane in the sidebar
        // left [lastPointerDownPaneId] null, the previously-focused
        // terminal's focusout safety net classified the blur as
        // involuntary and re-focused itself — racing the sidebar's
        // SetFocusedPane and kicking off the selection flicker loop.
        val pane = if (node != null) node.closest(".dt-pane, .dt-sidebar-row") as? HTMLElement else null
        lastPointerDownPaneId = pane?.getAttribute("data-pane-id")
        val insideMenu = node != null && node.closest(
            ".tab-bar-menu, .tab-bar-menu-list, .pane-menu, .pane-split-flyout, .pane-flyout-wrap"
        ) != null
        if (insideMenu) return@addEventListener
        val openMenus = document.querySelectorAll(
            ".pane-menu.open, .tab-bar-menu.open, .tab-bar-menu-list.open, .pane-split-flyout.open, .pane-flyout-wrap.open"
        )
        for (i in 0 until openMenus.length) {
            (openMenus.item(i) as HTMLElement).classList.remove("open")
        }
    }, js("({ capture: true })"))

    // Swallow stray drops that escape pane-internal handlers (file drag
    // anywhere on the page should not navigate the renderer).
    document.addEventListener("dragover", { event -> event.preventDefault() })
    document.addEventListener("drop", { event -> event.preventDefault() })
    updateAggregateStatus()

    // Connect the window-state envelope loop. Pre-migration this also
    // drove `renderConfig`'s big DOM build; post-migration the toolkit
    // owns chrome and `connectWindow` only routes pane-content envelopes
    // (file-browser dir / git diff / etc.) and updates the Claude usage
    // badge. The actual tab/pane rendering happens through the
    // `TermtasticTabSource` push channel.
    connectWindow()
}
