/**
 * Entry point for the Lunamux Kotlin/JS web frontend.
 *
 * Bootstraps the application by initializing authentication, creating the
 * [LunamuxClient] and [WindowSocket], setting up the [AppBackingViewModel],
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
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.client.ClientIdentity
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunamux.client.viewmodel.AppBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.SettingsPersister
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
 * @see se.soderbjorn.lunamux.client.viewmodel.resolvedTheme
 */
internal fun refreshAndApplyActiveTheme() {
    applyAll()
    // Re-tint the 3D world's pane chrome/beacons if it is open — its colours
    // are otherwise baked at overlay-open time (no-op when closed).
    restyleWorldChrome()
    refreshThemeManager()
    // Push the live selection into the toolkit's stored snapshot. Lunamux
    // owns theme resolution outside the toolkit (slot picks write through
    // [appVm] via [LunamuxThemeManagerHost]), so without this sync the
    // toolkit's snapshot stays frozen at whatever it read from the persister
    // at mount. The topbar appearance-cycle button persists from that stale
    // snapshot — re-emitting the default slot names and clobbering the
    // light/dark theme the user picked. See [AppShellHandle.setThemeSnapshot].
    // Push the EFFECTIVE snapshot (global selection with the active world's
    // theme pair overlaid, if any) so the toolkit chrome repaints in the
    // active world's theme too.
    appShellHandle?.setThemeSnapshot(effectiveThemeSnapshot())
}

/**
 * Initializes the Lunamux web application.
 *
 * Steps:
 * 1. Inject toolkit styles
 * 2. Ensure an auth token and detect the client kind (Electron vs Web)
 * 3. Create [LunamuxClient], [WindowSocket], [AppBackingViewModel]
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
    isElectronClient = window.navigator.userAgent.contains("Electron", ignoreCase = true)
    // The `dt-electron-mac` body class is added by darkness-toolkit's
    // `autoApplyElectronMacBodyClass` (called from `injectDarknessToolkitStyles`
    // above), and `dt-mac-fullscreen` is wired automatically by
    // `autoWireMacFullscreenBodyClass` against `darknessApi.onFullscreenChange`.
    // No manual UA sniff or fullscreen subscriber needed here.

    // Server-state plumbing. In demo mode the "server" is the in-process
    // simulation selected by the magic demo host — no network anywhere.
    isDemoClient = detectDemoUrl()
    // Secret Electron-only hotkey (Cmd/Ctrl+Alt+Shift+D) that reboots the
    // renderer into/out of demo mode by toggling the #demo marker and
    // reloading. See installDemoSwitchHotkey for the full story.
    if (isElectronClient) installDemoSwitchHotkey()
    val loc = window.location
    // Resolve the backend authority. A `?backend=host:port` URL param points the
    // UI at a *different* live server — always TLS, since the server is TLS-only
    // (see ServerUrl). Used by scripts/run-electron-to-prod-server.sh to run this branch's
    // bundle against an already-running server. Absent the param, we talk to the
    // origin we were served from, so every other path behaves exactly as before.
    val backendOverride = runCatching {
        (js("new URLSearchParams(window.location.search).get('backend')") as? String)
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
    val bHost: String
    val bPort: Int
    val bSecure: Boolean
    if (backendOverride != null) {
        val parts = backendOverride.split(":")
        bHost = parts[0]
        bPort = parts.getOrNull(1)?.toIntOrNull() ?: 8443
        bSecure = true
    } else {
        bHost = loc.hostname
        bPort = loc.port.toIntOrNull() ?: if (loc.protocol == "https:") 443 else 80
        bSecure = loc.protocol == "https:"
    }
    backendHost = "$bHost:$bPort"
    proto = if (bSecure) "wss" else "ws"
    val serverUrl = if (isDemoClient) {
        ServerUrl(host = se.soderbjorn.lunamux.client.demo.DEMO_HOST, port = 0)
    } else {
        ServerUrl(host = bHost, port = bPort)
    }
    lunamuxClient = LunamuxClient(
        serverUrl = serverUrl,
        authToken = authTokenForSending(),
        // Report the version baked into this bundle at build time
        // ([LUNAMUX_VERSION]). The bundle is served by the same server it
        // connects to (browser) or a locally-bundled one (Electron), so this is
        // the true version of the running renderer — and a stale cached bundle
        // honestly reports its own older version, exactly what the server's
        // agent-pane capability gate needs.
        identity = ClientIdentity(type = clientTypeAtStart, version = LUNAMUX_VERSION),
    )
    windowSocket = lunamuxClient.openWindowSocket()

    webSettingsPersister = if (isDemoClient) object : SettingsPersister {
        // Demo mode: settings changes apply for the page's lifetime but are
        // never persisted anywhere (and never leave the browser).
        override suspend fun putSetting(key: String, value: String) = Unit
        override suspend fun putSettings(settings: Map<String, String>) = Unit
        override fun fireAndForgetPutSettings(settings: Map<String, String>) = Unit
        override suspend fun putJsonSettings(settings: kotlinx.serialization.json.JsonObject) = Unit
    } else object : SettingsPersister {
        /**
         * Tail of the in-flight LAYOUT_STATE POST chain, or `null` when no
         * layout write is pending. Each toolkit layout persist replaces
         * the whole `darkness.layoutState` blob on the server, and a
         * single gesture (Auto re-tile of an n-pane tab) fans out into a
         * burst of writes. Fired as independent `fetch`es they can arrive
         * at the server out of order — an older blob (e.g. the cascade
         * seed geometry written just before the re-tile) then lands last
         * and becomes what the next page load / other clients hydrate,
         * reverting a freshly tiled pane to a random floating rectangle
         * (observed while debugging issue #86). Chaining each layout POST
         * behind the previous one guarantees last-write-wins matches the
         * client's in-memory state. Only the layout key is chained: other
         * settings keep the fire-immediately behaviour that the
         * `electronCustomTitleBar` teardown path relies on.
         */
        private var layoutStatePostChain: dynamic = null

        /**
         * POSTs [body] to `/api/ui-settings` and returns the in-flight
         * fetch promise (already `.catch`-ed, so it always settles) so
         * callers can chain ordered writes off it.
         */
        private fun postSettings(body: dynamic): dynamic {
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
            return window.fetch("/api/ui-settings", init)
                .asDynamic()
                .catch { _: dynamic -> }
        }

        override suspend fun putSetting(key: String, value: String) {
            // Serialize *every* layout key (flat default-world LAYOUT_STATE and
            // per-world `LAYOUT_STATE.world.<id>` keys alike) through one chain
            // so a burst of layout writes lands in order — see
            // [layoutStatePostChain].
            if (key.startsWith(se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE)) {
                // Serialize layout-state posts — see [layoutStatePostChain].
                val chain = layoutStatePostChain
                layoutStatePostChain = if (chain == null) {
                    postSettings(json(key to value))
                } else {
                    chain.then { postSettings(json(key to value)) }
                }
            } else {
                postSettings(json(key to value))
            }
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
        windowState = lunamuxClient.windowState,
        settingsPersister = webSettingsPersister,
    )

    // Keep the darkness-toolkit's custom hotkey bindings in sync with the
    // server-managed UI settings. The blob lives under
    // `PersistKeys.HOTKEY_BINDINGS` in the same per-app settings file as
    // theme/appearance (server-side `SettingsRepository`); this hook covers
    // the initial REST hydration *and* every live `UiSettings` WebSocket
    // push, so a rebinding made on one client takes effect on all of them
    // without a reload. A missing key means "no customizations" and resets
    // the toolkit to its default chords. Applying an unchanged blob is a
    // no-op inside the toolkit, so server echoes of our own writes are
    // harmless.
    appVm.onServerUiSettingsApplied = { payload ->
        val raw = (payload[PersistKeys.HOTKEY_BINDINGS]
            as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
        se.soderbjorn.darkness.web.hotkey.HotkeyBindings.applyCustomBindingsJson(raw)
    }

    // Toolkit-shape persister adapter. Reads serve from the in-memory
    // snapshot that `applyServerUiSettings` populates; writes round-trip
    // through `webSettingsPersister.putSetting` (the same REST bridge
    // Lunamux has always used).
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
            // applyServerUiSettings so Lunamux's own painters (xterm,
            // section overlays) match the chrome's dark default — mirroring
            // the hydrated-from-server path above.
            val snapshot = demoToolkitSettingsSnapshot(lunamuxClient.windowState.config.value)
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
        // `LunamuxToolkitBootstrap.buildSidebarFooter()` when the toolkit
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
        // second call must wait until Lunamux's legacy section overlay
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
        var prevFavorites = appVm.stateFlow.value.favoriteThemeNames
        appVm.stateFlow.collect { state ->
            val slotChanged = state.lightThemeName != prevLight || state.darkThemeName != prevDark
            // Structural equality (`!=`), not identity (`!==`). Server
            // echoes land freshly-parsed list references via
            // `SettingsViewModel.applyServerUiSettings`, so reference checks
            // would flip on every round-trip even when the data is identical.
            val customChanged = state.customThemes != prevCustomThemes
            val appearanceChanged = state.appearance != prevAppearance
            // Favorites don't affect the *active* resolved theme, but they do
            // change the Theme Manager's list ordering and star icons — so a
            // toggle (local or synced) must repaint the open manager (issue #107).
            val favoritesChanged = state.favoriteThemeNames != prevFavorites
            if (slotChanged || appearanceChanged || customChanged || favoritesChanged) {
                prevLight = state.lightThemeName
                prevDark = state.darkThemeName
                prevCustomThemes = state.customThemes
                prevAppearance = state.appearance
                prevFavorites = state.favoriteThemeNames
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
        var prevPaneHeaderFamily: String? = appVm.stateFlow.value.paneHeaderFontFamily
        var prevPaneHeaderSize: Int? = appVm.stateFlow.value.paneHeaderFontSizePx
        // Chrome font family/size fold in the default fonts (JetBrains Mono /
        // 12px) via `effectiveFontKey` / `effectiveChromeSize`, matching the
        // host getters so the applied chrome and the Settings highlight agree.
        se.soderbjorn.darkness.web.applyMonoFontSizePx(prevPaneSize)
        prevPaneSize?.let { applyGlobalFontSize(it) }
        se.soderbjorn.darkness.web.applySidebarFontFamily(effectiveFontKey(prevSidebarFamily))
        se.soderbjorn.darkness.web.applySidebarFontSizePx(effectiveChromeSize(prevSidebarSize))
        se.soderbjorn.darkness.web.applyTabbarFontFamily(effectiveFontKey(prevTabbarFamily))
        se.soderbjorn.darkness.web.applyTabbarFontSizePx(effectiveChromeSize(prevTabbarSize))
        // Window (pane) title font: mirror the sidebar/tabbar treatment so a
        // persisted choice is re-applied at startup, and so a server push from
        // another client repaints the title bars without waiting for the next
        // `mountAppShell.applyHostFontVars` rebuild. (Issue #51: this font was
        // applied live but never persisted/restored — the host adapter now
        // round-trips it through `appVm`.)
        se.soderbjorn.darkness.web.applyPaneHeaderFontFamily(effectiveFontKey(prevPaneHeaderFamily))
        se.soderbjorn.darkness.web.applyPaneHeaderFontSizePx(effectiveChromeSize(prevPaneHeaderSize))
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
            if (state.paneHeaderFontFamily != prevPaneHeaderFamily) {
                prevPaneHeaderFamily = state.paneHeaderFontFamily
                se.soderbjorn.darkness.web.applyPaneHeaderFontFamily(effectiveFontKey(prevPaneHeaderFamily))
            }
            if (state.paneHeaderFontSizePx != prevPaneHeaderSize) {
                prevPaneHeaderSize = state.paneHeaderFontSizePx
                se.soderbjorn.darkness.web.applyPaneHeaderFontSizePx(effectiveChromeSize(prevPaneHeaderSize))
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

        // Wire the in-app auto-updater (electron-updater). Subscribes to the main
        // process's update lifecycle events, reflects "update available" onto the
        // top-bar bell, and feeds the Updates panel in App Settings. No-op when the
        // preload lacks the updater bridge. See AutoUpdaterPanel.kt.
        initAutoUpdaterListeners()

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

    // macOS app menu → "About Lunamux" routes to the in-app dialog.
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

    // macOS app menu → "Keyboard Shortcuts" opens the in-app Hotkeys
    // sidebar. Forwarded from the Electron main process via the
    // `show-hotkeys` IPC.
    if (electronApi?.onShowHotkeys != null) {
        electronApi.onShowHotkeys({ openHotkeysSidebar() })
    }

    // macOS Help menu → "Check for Updates…" opens the in-app Updates panel
    // (App Settings sidebar). Forwarded from the Electron main process via the
    // `show-updates-panel` IPC.
    if (electronApi?.onShowUpdatesPanel != null) {
        // Help menu → "Check for Updates…": run a user-initiated check; the result
        // shows in the sidebar-footer update banner. See triggerUserUpdateCheck.
        electronApi.onShowUpdatesPanel({ triggerUserUpdateCheck() })
    }

    // 3D world → leave engage mode from inside a web pane. A `<webview>`
    // guest swallows its own keydowns, so the world's ⌥⌘X disengage chord
    // never reaches the host key handler while the page holds focus. The
    // Electron main process intercepts the chord via `before-input-event`
    // and forwards it here. We blur the guest (handing keyboard control back
    // to the host so navigate-mode chords work again), then disengage.
    if (electronApi?.onWebPaneDisengage != null) {
        electronApi.onWebPaneDisengage({
            if (spikeEngaged) {
                // disengage() blurs the focused element inside the engaged
                // spike pane (here, the <webview>) and refocuses the overlay.
                disengage()
            } else {
                // Flat 2D layout: no engage state to leave, but still release
                // the guest's focus grab so global app keys work again.
                (document.activeElement as? HTMLElement)?.blur()
            }
        })
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
                // Stopping the server drops the socket; don't flash the "Connection
                // lost" modal in the moment before the app quits.
                if (result.confirmed && result.killServer) {
                    suppressDisconnectedModal = true
                    hideDisconnectedModal()
                }
                electronApi.respondQuitConfirmation(payload)
            }
        })
    }

    // A confirmed kill-server quit was abandoned in the main process (server
    // shutdown failed, user chose Cancel in the native dialog) — the app keeps
    // running, so re-arm the "Connection lost" modal suppressed above and
    // re-evaluate the current connection state. Without this, every later
    // genuine disconnect for the rest of the session would fail silently.
    if (electronApi?.onQuitCancelled != null) {
        electronApi.onQuitCancelled({
            suppressDisconnectedModal = false
            updateAggregateStatus()
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

    // Close any open lunamux-side popovers on outside press. The toolkit
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
        // A press on a pane-resize surface starts a resize gesture: gate
        // automatic PTY size votes until release so mid-drag transient
        // widths never reach the PTY (see [resizeGestureActive]).
        if (node != null && node.closest(".dt-pane-corner-resize, .dt-pane-separator") != null) {
            resizeGestureActive = true
        }
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

    // End of a pane-resize gesture: the pointer released or was cancelled
    // anywhere in the document (capture phase, so pane-internal handlers
    // that stopPropagation can't hide it), or the window lost focus with the
    // button still down (release outside the window). Clearing the flag
    // re-opens the [sendResize] gate; the flush below is belt-and-braces for
    // gestures that end *without* a committed geometry change — the normal
    // path is the toolkit's onGeometryChanged → forceReassert, which sends
    // the final size regardless of this gate.
    val endResizeGesture: (dynamic) -> Unit = { _: dynamic ->
        if (resizeGestureActive) {
            resizeGestureActive = false
            for (entry in terminals.values) {
                if (entry.autoReflow && entry.container.offsetParent != null) sendResize(entry)
            }
        }
    }
    document.asDynamic().addEventListener("pointerup", endResizeGesture, js("({ capture: true })"))
    document.asDynamic().addEventListener("pointercancel", endResizeGesture, js("({ capture: true })"))
    window.asDynamic().addEventListener("blur", endResizeGesture)

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
    // `LunamuxTabSource` push channel.
    connectWindow()
}
