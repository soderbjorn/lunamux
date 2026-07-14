/**
 * Web-side glue for demo mode: URL detection, the demo replacement for the
 * raw-WebSocket PTY path ([connectDemoPane]), and the demo variant of the
 * link-picker terminal previews.
 *
 * The shared simulation itself lives in `:client`
 * ([se.soderbjorn.lunamux.client.demo.DemoServer] and friends); this file
 * only adapts it to the web client's xterm.js plumbing, which talks raw
 * browser WebSockets instead of [se.soderbjorn.lunamux.client.PtySocket].
 *
 * Also hosts the secret Electron-only hotkey ([installDemoSwitchHotkey])
 * that reboots the running renderer into (or out of) demo mode by toggling
 * the `#demo` URL marker and reloading.
 *
 * @see detectDemoUrl
 * @see connectDemoPane
 * @see connectPane
 */
package se.soderbjorn.lunamux

import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.lunamux.client.demo.DemoSession
import se.soderbjorn.lunamux.client.demo.DemoTerminalSession

/**
 * Whether the current page URL asks for demo mode.
 *
 * Accepted spellings, so the demo works both when served by a Lunamux
 * server (SPA fallback routes `/demo` to the bundle) and when the bundle is
 * hosted statically under an arbitrary path (e.g. inside the marketing
 * site's iframe):
 *  - a path of `/demo` (or any path ending in `/demo`),
 *  - a `demo` query parameter (`?demo` or `?demo=1`),
 *  - a `#demo` hash.
 *
 * Called once from `start()` before the [lunamuxClient] is constructed.
 *
 * @return `true` when this page should boot against the in-process demo.
 */
internal fun detectDemoUrl(): Boolean {
    val loc = window.location
    if (loc.pathname == "/demo" || loc.pathname.endsWith("/demo")) return true
    if (loc.hash == "#demo") return true
    val query = loc.search.removePrefix("?")
    return query.split('&').any { it == "demo" || it.startsWith("demo=") }
}

/**
 * Install the secret demo-switch hotkey: Cmd/Ctrl+Alt+Shift+D.
 *
 * Pressing it in the Electron app reboots the renderer into demo mode by
 * appending the `#demo` marker (one of the spellings [detectDemoUrl]
 * accepts) and reloading the page. The reload tears down every live
 * server connection — the window WebSocket and all per-pane PTY sockets —
 * and the rebooted client runs entirely against the in-process
 * [se.soderbjorn.lunamux.client.demo.DemoServer], never touching the
 * network again. Server-side sessions are unaffected; they merely see a
 * client disconnect, exactly as if the window had been closed.
 *
 * The same combo pressed while already in demo mode strips the marker and
 * reloads back against the real server, so an accidental press is
 * recoverable without restarting the app. Restarting Electron always
 * returns to the real server anyway, because the main process loads its
 * `TARGET_URL` fresh (no hash).
 *
 * Called once from `start()` in `main.kt`, and only when
 * [isElectronClient] is true — a plain browser tab already has the
 * `/demo` URL for this, and a secret combo that silently disconnects a
 * remote web session would be a trap, not a feature.
 *
 * Registered on `window` in the capture phase so it wins over xterm.js's
 * own keydown handling regardless of which pane has focus. Matches on
 * `KeyboardEvent.code` (physical `KeyD`) rather than `key`, because
 * Alt-modified `key` values are layout-mangled on macOS (Alt+D → "∂").
 *
 * @see detectDemoUrl for how the rebooted page re-enters demo mode.
 */
internal fun installDemoSwitchHotkey() {
    window.addEventListener("keydown", { ev ->
        val e = ev.unsafeCast<org.w3c.dom.events.KeyboardEvent>()
        val comboDown = (e.metaKey || e.ctrlKey) && e.altKey && e.shiftKey && e.code == "KeyD"
        if (!comboDown) return@addEventListener
        e.preventDefault()
        e.stopPropagation()
        val loc = window.location
        if (isDemoClient) {
            // Toggle back: drop the marker and reboot against the real server.
            // Assigning an empty hash leaves a trailing "#", which
            // detectDemoUrl ignores, so a plain reload suffices.
            loc.hash = ""
        } else {
            loc.hash = "demo"
        }
        loc.reload()
    }, true)
}

/**
 * Build the demo's toolkit-settings snapshot: a dark-appearance v2 theme
 * selection blob ([PersistKeys.THEME_V2_SELECTION]) plus a `darkness.layoutState`
 * blob derived from the fixture [se.soderbjorn.lunamux.WindowConfig] so the
 * toolkit shell mounts in dark mode with the intended pane geometry.
 *
 * The demo always boots dark, regardless of the visitor's OS
 * `prefers-color-scheme`: the marketing hero is designed for a dark
 * canvas, so a light-mode visitor must not land on a half-lit workspace.
 * We bake an explicit [Appearance.Dark] v2 selection blob into the snapshot
 * the toolkit chrome reads once at mount; the appVm side is brought in line by
 * `applyServerUiSettings(...)` over this same object in [start], which reads
 * the appearance + slot names out of the same selection blob.
 *
 * Pane geometry on web is toolkit-owned: the shell reads
 * [PersistKeys.LAYOUT_STATE] (shape: `presetByTab` / `paneOrderByTab` /
 * `geometryByTab`, see toolkit-web's `PersistedLayoutState`) once at mount
 * through the settings snapshot. Without this seed every demo pane would
 * paint at the toolkit's default cascade instead of the fixture's
 * hero-left / grid / split-h arrangements. Deriving the blob from the
 * config keeps `DemoFixtures.initialConfig()` the single source of truth
 * for the demo workspace.
 *
 * @param config the demo fixture config (already in the window-state
 *   repo), or `null` if it hasn't been seeded yet — in which case only the
 *   dark-appearance blob is emitted (the dark default never depends on
 *   geometry being available).
 * @return a snapshot object to install as [toolkitSettingsSnapshot].
 */
internal fun demoToolkitSettingsSnapshot(config: se.soderbjorn.lunamux.WindowConfig?): JsonObject =
    buildJsonObject {
        // Dark appearance with the built-in default slots. `applyServerUiSettings`
        // reads `appearance` + `lightThemeName`/`darkThemeName` out of this v2
        // selection blob, so a light-mode visitor still lands on the dark hero.
        put(
            PersistKeys.THEME_V2_SELECTION,
            ThemeSnapshotV2(appearance = Appearance.Dark).selectionJson(),
        )
        // Seed the Electron custom-title-bar opt-in from the main process's
        // boot value (mirrored onto `darknessApi.customTitleBar` by the
        // preload, sourced from the persisted electron-chrome.json). Demo mode
        // has no server to persist or echo this pref, so without seeding it the
        // value resets to its default on the renderer reload that toggling the
        // title bar triggers — reverting the BrowserWindow rebuild and making
        // the toggle appear to do nothing. It is a flat top-level key that
        // `applyServerUiSettings` reads directly. Undefined — and so `false` —
        // outside Electron, so this is a no-op for the web demo.
        put("electronCustomTitleBar", JsonPrimitive(demoCustomTitleBarBoot()))
        if (config == null) return@buildJsonObject
        put(
            PersistKeys.LAYOUT_STATE,
            buildJsonObject {
                put("presetByTab", buildJsonObject {
                    for (tab in config.tabs) tab.layoutPreset?.let { put(tab.id, it) }
                })
                put("paneOrderByTab", buildJsonObject {
                    for (tab in config.tabs) {
                        // Head of the order list is the primary slot —
                        // the focused pane, then the rest in tab order.
                        val ordered = tab.panes.sortedByDescending { it.leaf.id == tab.focusedPaneId }
                        put(tab.id, buildJsonArray { for (p in ordered) add(p.leaf.id) })
                    }
                })
                put("geometryByTab", buildJsonObject {
                    for (tab in config.tabs) {
                        put(tab.id, buildJsonObject {
                            for (p in tab.panes) {
                                put(p.leaf.id, buildJsonObject {
                                    put("xPct", p.x)
                                    put("yPct", p.y)
                                    put("widthPct", p.width)
                                    put("heightPct", p.height)
                                    put("zIndex", p.z.toInt())
                                    put("isMaximized", p.maximized)
                                    put("isMinimized", false)
                                })
                            }
                        })
                    }
                })
            },
        )
    }

/**
 * The Electron main process's boot-time custom-title-bar flag, as mirrored
 * onto `window.darknessApi.customTitleBar` by the preload (see
 * `electron/preload.js`). Used by [demoToolkitSettingsSnapshot] to seed the
 * demo's `electronCustomTitleBar` so the toggle survives the renderer reload
 * that a title-bar change triggers in Electron.
 *
 * @return the boot value when running inside Electron; `false` otherwise
 *   (no preload → no `darknessApi`), making it a no-op for the web demo.
 */
private fun demoCustomTitleBarBoot(): Boolean =
    window.asDynamic().darknessApi?.customTitleBar == true

/** Wrap a Kotlin [ByteArray] as a [Uint8Array] view for `term.write`. */
private fun toUint8(bytes: ByteArray): Uint8Array {
    val i8 = bytes.unsafeCast<Int8Array>()
    return Uint8Array(i8.buffer, i8.byteOffset, i8.length)
}

/**
 * Demo-mode replacement for [connectPane]: instead of opening a WebSocket to
 * `/pty/{sessionId}`, attach the xterm.js terminal to the in-process
 * [DemoTerminalSession]. The session replays its canned scrollback as one
 * snapshot frame (just like the server's ring-buffer replay) and then
 * streams simulated output; keystrokes are fed into its line discipline.
 *
 * @param entry the terminal registry entry created by [ensureTerminal].
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun connectDemoPane(entry: TerminalEntry) {
    val demo = lunamuxClient.demoServer ?: return
    val session = demo.session(entry.sessionId)

    entry.connected = true
    connectionState[entry.sessionId] = "connected"
    updateAggregateStatus()

    fun sendInput(data: String) {
        session.inputText(data)
    }
    // Route input through the [TerminalEntry.sendInput] slot only — the
    // one-time `onData` handler registered in [ensureTerminal] invokes it, so
    // registering another `onData` here would double-send every keystroke.
    entry.sendInput = ::sendInput

    // Notify size-aware sessions (the IRC TUI reflows its frame to fill the pane;
    // fixed-frame shell/Claude sessions ignore it) of the terminal's live cell
    // grid — the web demo attaches straight to the DemoSession and never goes
    // through DemoPtySocket. Push the current size once now; subsequent refits
    // are forwarded by the one-time `onResize` handler in [ensureTerminal]
    // (via [pushDemoSessionResize]).
    pushDemoSessionResize(entry)

    entry.demoJob = GlobalScope.launch {
        session.output().collect { bytes ->
            writeHoldingScroll(entry, toUint8(bytes))
        }
    }
}

/**
 * Forward a demo pane's **current terminal grid** to its [DemoSession] so a
 * size-aware session (the IRC TUI) reflows its full-screen frame to fill the
 * pane. Called from the terminal [ResizeObserver] in [buildTerminalPaneView]
 * right after each refit — the reliable resize signal, since the demo path does
 * not go through the real socket's `onResize`→resize plumbing and xterm's own
 * `onResize` can miss the fit that the observer just performed. No-op outside
 * demo mode, before the grid is measurable, or for fixed-frame sessions (their
 * [DemoSession.resize] default ignores it).
 *
 * @param entry the terminal registry entry whose grid changed.
 */
internal fun pushDemoSessionResize(entry: TerminalEntry) {
    if (!isDemoClient) return
    val demo = lunamuxClient.demoServer ?: return
    val cols = entry.term.cols
    val rows = entry.term.rows
    if (cols <= 0 || rows <= 0) return
    val session = demo.session(entry.sessionId)
    GlobalScope.launch { session.resize(cols, rows) }
}

/**
 * Demo-mode replacement for the link-picker's preview sockets: writes the
 * simulated session's current scrollback into the (read-only) preview
 * terminal once, without subscribing to live output — previews are
 * short-lived and torn down when the modal closes.
 *
 * @param previewTerm the small read-only xterm.js instance in the picker card.
 * @param sessionId the demo session whose scrollback to show.
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun attachDemoPreview(previewTerm: Terminal, sessionId: String) {
    val demo = lunamuxClient.demoServer ?: return
    val session: DemoSession = demo.session(sessionId)
    GlobalScope.launch {
        // Take just the first frame (the snapshot) and stop collecting.
        var done = false
        session.output().collect { bytes ->
            if (!done) {
                done = true
                previewTerm.write(toUint8(bytes))
                throw kotlinx.coroutines.CancellationException("preview snapshot complete")
            }
        }
    }
}
