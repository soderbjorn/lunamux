/* ElectronMain.kt
 * Termtastic Electron main process — Kotlin/JS port of the previous
 * `electron/main.js` (835 lines). Mirrors notegrow's `electron-main`
 * module layout: this file is the entry point that runs at app
 * startup; node externals live in [NodeExternals.kt]; Electron API
 * externals live in [ElectronExternals.kt].
 *
 * Responsibilities:
 *  - Bootstraps the embedded Ktor server jar (or connects to an
 *    already-running instance) and creates the main BrowserWindow once
 *    the server is reachable.
 *  - Enforces single-instance: a second launch refocuses the existing
 *    window.
 *  - Registers a global hotkey (Ctrl+Alt+Cmd+Space) to summon the app.
 *  - Builds the application menu (including a "Launch at Login"
 *    toggle on macOS).
 *  - Handles macOS app-lifecycle conventions (keep alive on last
 *    window close, recreate window on dock click).
 *  - Owns the `set-window-background-color` and
 *    `darkness:setCustomTitleBar` IPC handlers.
 */
package se.soderbjorn.termtastic.electron

import kotlin.js.Promise

// ── Constants ───────────────────────────────────────────────────────

/** Production server port — mirrors shared `Constants.SERVER_TLS_PORT`. */
private const val PROD_PORT = 8443

/** Global hotkey accelerator that summons the app from any context. */
private const val SUMMON_ACCELERATOR = "Control+Alt+Command+Space"

private val URL_OVERRIDE: String? = (process.env.TERMTASTIC_URL as? String)?.takeIf { it.isNotEmpty() }
// Server is HTTPS-only with a self-signed cert generated on first boot
// (see :server's CertStore). The renderer loads from loopback, so the cert
// cannot be MITM'd; the `certificate-error` handler installed in [main]
// accepts any cert for loopback hosts without prompting the user to bypass
// a browser warning. Using `127.0.0.1` instead of `localhost` avoids the
// IPv4/IPv6 dual-stack ambiguity that occasionally lands a connect on `::1`
// against a listener bound to v4.
private val TARGET_URL: String = URL_OVERRIDE ?: "https://127.0.0.1:$PROD_PORT"
private val IS_DEV_LAUNCH: Boolean = URL_OVERRIDE != null

/**
 * Whether this is a demo launch — the in-process fake server with no backend,
 * started by `scripts/run-electron-demo.sh`. The script sets the
 * `TERMTASTIC_DEMO` env var so the window/menu label and, crucially, the
 * isolated userData dir + single-instance lock (see [main]) are distinct from
 * both prod and a regular dev launch, letting all three coexist.
 */
private val IS_DEMO: Boolean = (process.env.TERMTASTIC_DEMO as? String) == "1"
private val APP_NAME: String = when {
    IS_DEMO -> "Termtastic Demo"
    IS_DEV_LAUNCH -> "Termtastic Dev"
    else -> "Termtastic"
}

/**
 * Port the bundled (or dev) server actually listens on. In production
 * this is always [PROD_PORT]; in a dev launch (TERMTASTIC_URL set) we
 * parse the port from the override URL so `/admin/shutdown` and the
 * port-already-listening check both target the running dev server.
 */
private val SERVER_PORT: Int = run {
    val override = URL_OVERRIDE ?: return@run PROD_PORT
    try {
        val u: dynamic = js("new URL(override)")
        (u.port as? String)?.toIntOrNull() ?: PROD_PORT
    } catch (_: Throwable) {
        PROD_PORT
    }
}

// ── Globals ─────────────────────────────────────────────────────────

private var mainWindow: BrowserWindow? = null

/** Current cached chrome preference (custom title bar on/off). */
private var chromePrefs: ChromePrefs = loadChromePrefs()

private data class ChromePrefs(val customTitleBar: Boolean)

// ── Window-chrome preference cache ───────────────────────────────────

private fun chromePrefsPath(): String = NodePath.join(app.getPath("userData"), "electron-chrome.json")

private fun loadChromePrefs(): ChromePrefs = try {
    val raw = NodeFs.readFileSync(chromePrefsPath(), "utf8")
    val parsed: dynamic = js("JSON.parse(raw)")
    ChromePrefs(customTitleBar = parsed.customTitleBar == true)
} catch (_: Throwable) {
    ChromePrefs(customTitleBar = false)
}

/**
 * Reads the macOS bundle build number (`build.mac.bundleVersion` in
 * `package.json`, surfaced as `CFBundleVersion` in the packaged app) so the
 * renderer's About dialog can show a "version code" alongside the
 * human-readable version name from `app.getVersion`.
 *
 * Called by [createWindow] when assembling the preload `additionalArguments`.
 * The `package.json` sits two directories above the compiled main script
 * (mirrors the relative path used for `preload.js`).
 *
 * @return the bundle version string (e.g. `"1"`), or `""` if it cannot be read.
 */
private fun readBundleVersion(): String = try {
    val pkgPath = NodePath.join(__dirname, "..", "..", "package.json")
    val raw = NodeFs.readFileSync(pkgPath, "utf8")
    val parsed: dynamic = js("JSON.parse(raw)")
    (parsed.build?.mac?.bundleVersion as? String) ?: ""
} catch (_: Throwable) {
    ""
}

private fun saveChromePrefs(prefs: ChromePrefs) {
    try {
        NodeFs.mkdirSync(NodePath.dirname(chromePrefsPath()), js("({recursive: true})"))
        val payload = js("({})")
        payload.customTitleBar = prefs.customTitleBar
        NodeFs.writeFileSync(chromePrefsPath(), js("JSON.stringify(payload)") as String)
    } catch (_: Throwable) {
        // Cosmetic; the next launch just forgets the preference.
    }
}

// ── Embedded server bootstrap ────────────────────────────────────────

private fun isPortListening(port: Int, host: String = "127.0.0.1", timeoutMs: Int = 250): Promise<Boolean> {
    return Promise<Boolean> { resolve, _ ->
        val socket = NodeNet.NetSocket()
        var settled = false
        fun done(result: Boolean) {
            if (settled) return
            settled = true
            try { socket.destroy() } catch (_: Throwable) {}
            resolve(result)
        }
        socket.setTimeout(timeoutMs)
        socket.once("connect") { done(true) }
        socket.once("timeout") { done(false) }
        socket.once("error") { done(false) }
        try {
            socket.connect(port, host)
        } catch (_: Throwable) {
            done(false)
        }
    }
}

private fun waitForPort(port: Int, timeoutMs: Int = 30000): Promise<Boolean> = Promise { resolve, _ ->
    val deadline = js("Date.now()") as Double + timeoutMs
    fun loop() {
        if ((js("Date.now()") as Double) >= deadline) {
            resolve(false); return
        }
        isPortListening(port).then { ok ->
            if (ok) resolve(true)
            else js("setTimeout")({ loop() }, 200)
        }
    }
    loop()
}

private fun findServerJar(): String? {
    val candidates = if (app.isPackaged) {
        arrayOf(NodePath.join(process.resourcesPath, "server.jar"))
    } else {
        arrayOf(NodePath.join(__dirname, "..", "..", "resources", "server.jar"))
    }
    for (p in candidates) {
        if (NodeFs.existsSync(p)) return p
    }
    return null
}

private fun resolveJavaBinary(): String {
    val binName = if (process.platform == "win32") "java.exe" else "java"

    // Prefer the JRE bundled inside the packaged app (jlink'd by the
    // :electron:bundleJre Gradle task and staged at Contents/Resources/jre)
    // so end users need no system Java install. process.resourcesPath points
    // at Contents/Resources in production; in dev the path is absent and we
    // fall through to the developer's system Java below.
    if (app.isPackaged) {
        val bundled = NodePath.join(process.resourcesPath, "jre", "bin", binName)
        if (NodeFs.existsSync(bundled)) return bundled
    }

    val javaHome = process.env.JAVA_HOME as? String
    if (!javaHome.isNullOrEmpty()) {
        val c = NodePath.join(javaHome, "bin", binName)
        if (NodeFs.existsSync(c)) return c
    }

    if (process.platform == "darwin") {
        try {
            val home = NodeChildProcess.execFileSync("/usr/libexec/java_home", js("({encoding:'utf8'})")).trim()
            if (home.isNotEmpty()) {
                val c = NodePath.join(home, "bin", "java")
                if (NodeFs.existsSync(c)) return c
            }
        } catch (_: Throwable) {
            // java_home exits non-zero with no JDKs registered — fall through.
        }
    }

    val wellKnown = mutableListOf<String>()
    when (process.platform) {
        "darwin" -> wellKnown.addAll(listOf(
            "/opt/homebrew/opt/openjdk/bin/java",
            "/opt/homebrew/opt/openjdk@21/bin/java",
            "/opt/homebrew/opt/openjdk@17/bin/java",
            "/usr/local/opt/openjdk/bin/java",
            "/usr/local/opt/openjdk@21/bin/java",
            "/usr/local/opt/openjdk@17/bin/java",
            "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java",
            "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java",
        ))
        "linux" -> wellKnown.addAll(listOf(
            "/usr/lib/jvm/default-java/bin/java",
            "/usr/lib/jvm/java-21-openjdk/bin/java",
            "/usr/lib/jvm/java-17-openjdk/bin/java",
            "/usr/bin/java",
        ))
    }
    for (c in wellKnown) {
        if (NodeFs.existsSync(c)) return c
    }
    return binName
}

private fun serverLogPath(): String {
    val logsDir = app.getPath("logs")
    NodeFs.mkdirSync(logsDir, js("({recursive: true})"))
    return NodePath.join(logsDir, "server.log")
}

private data class Spawned(
    val java: String,
    val spawnError: Promise<dynamic>,
    val logPath: String,
)

private fun spawnEmbeddedServer(jarPath: String): Spawned {
    val java = resolveJavaBinary()
    val javaArgs = mutableListOf<String>()
    if (process.platform == "darwin") {
        javaArgs.add("-Dapple.awt.UIElement=true")
    }
    javaArgs.add("-Dtermtastic.port=$PROD_PORT")
    javaArgs.add("-jar")
    javaArgs.add(jarPath)

    val logPath = serverLogPath()
    val logFd = NodeFs.openSync(logPath, "a")
    val opts = js("({})")
    opts.detached = true
    opts.stdio = arrayOf<dynamic>("ignore", logFd, logFd)
    val child = NodeChildProcess.spawn(java, javaArgs.toTypedArray(), opts)
    NodeFs.closeSync(logFd)
    child.unref()

    val spawnError = Promise<dynamic> { resolve, _ ->
        child.once("error") { err -> resolve(err) }
        child.once("exit") { code ->
            val codeNum = code as? Int
            if (codeNum != null && codeNum != 0) {
                val err = js("new Error('Embedded server exited with code ' + code)")
                resolve(err)
            }
        }
    }
    return Spawned(java = java, spawnError = spawnError, logPath = logPath)
}

// ── Login-item toggle (macOS) ────────────────────────────────────────

private fun isLoginItemEnabled(): Boolean = try {
    app.getLoginItemSettings().openAtLogin == true
} catch (_: Throwable) {
    false
}

private fun toggleLoginItem() {
    val next = !isLoginItemEnabled()
    val settings = js("({})")
    settings.openAtLogin = next
    settings.openAsHidden = true
    app.setLoginItemSettings(settings)
    buildAppMenu()
}

// ── Quit confirmation ────────────────────────────────────────────────

/**
 * One-shot guard: set to `true` once the user has confirmed a quit (and
 * any opted-in server shutdown has completed) so the next quit attempt
 * passes through `before-quit` without re-prompting.
 */
private var quitConfirmed: Boolean = false

/**
 * Re-entrancy guard for the quit-confirmation flow. While the modal is
 * up (or the shutdown POST is in flight), additional quit-intent events
 * (a second Cmd-Q, redundant window-close events) are coalesced.
 */
private var quitInProgress: Boolean = false

/**
 * Pending resolver for the in-flight quit-confirmation request. The
 * renderer responds via `electronApi.respondQuitConfirmation(...)`,
 * which lands in the `quit-confirmation-result` IPC handler installed
 * in [main]; the handler invokes this resolver and clears it.
 */
private var pendingQuitResolver: ((dynamic) -> Unit)? = null

/**
 * True when there is a main window whose renderer can be expected to
 * respond to the `show-quit-confirmation` IPC.
 *
 * Returns false when no window exists, the window is destroyed, or the
 * window is showing the [showUnreachable] data-URL fallback — that
 * static HTML has no IPC listeners, so the modal would never resolve
 * and the user would be unable to close the app.
 *
 * @return whether the renderer can host the quit-confirmation modal
 */
private fun isRendererQuitCapable(): Boolean {
    val w = mainWindow ?: return false
    if (w.isDestroyed()) return false
    val url = try { w.webContents.asDynamic().getURL() as? String } catch (_: Throwable) { null }
    if (url == null || url.startsWith("data:")) return false
    return true
}

/**
 * Show the quit-confirmation modal in the renderer and resolve the
 * Promise with the user's choice payload `{ confirmed, killServer }`.
 *
 * If there's no usable BrowserWindow we resolve as if the user
 * confirmed without ticking the kill-server checkbox so the app can
 * actually quit (otherwise we'd deadlock with no window to display
 * the modal).
 *
 * @return Promise resolved with the renderer's response object
 */
private fun askQuitConfirmation(): Promise<dynamic> = Promise { resolve, _ ->
    val win = mainWindow?.takeIf { !it.isDestroyed() }
        ?: BrowserWindow.getFocusedWindow()
    if (win == null || win.isDestroyed()) {
        val fallback: dynamic = js("({})")
        fallback.confirmed = true
        fallback.killServer = false
        resolve(fallback)
        return@Promise
    }
    pendingQuitResolver = resolve
    if (!win.isVisible()) win.show()
    win.focus()
    win.webContents.send("show-quit-confirmation")
}

/**
 * POST `/admin/shutdown` to the embedded server and resolve the Promise
 * with `true` only if the server returns `200 OK` within [timeoutMs].
 * Any error, non-200 status, or timeout resolves to `false` so the
 * caller can surface the failure to the user.
 *
 * @param timeoutMs upper bound on the request, in milliseconds
 * @return Promise resolved with whether the server confirmed shutdown
 */
private fun postShutdown(timeoutMs: Int = 5000): Promise<Boolean> = Promise { resolve, _ ->
    // The embedded server is HTTPS-only with a self-signed loopback cert; the
    // renderer already accepts it via the `certificate-error` handler in
    // [main]. Same trust policy here — `rejectUnauthorized = false` is safe
    // because the connection cannot leave 127.0.0.1.
    val http: dynamic = js("require('https')")
    val opts: dynamic = js("({})")
    opts.host = "127.0.0.1"
    opts.port = SERVER_PORT
    opts.path = "/admin/shutdown"
    opts.method = "POST"
    opts.timeout = timeoutMs
    opts.rejectUnauthorized = false
    var settled = false
    fun done(ok: Boolean) {
        if (settled) return
        settled = true
        resolve(ok)
    }
    val req: dynamic = http.request(opts) { res: dynamic ->
        val ok = (res.statusCode as? Int) == 200
        // Drain so the underlying socket can close cleanly.
        res.on("data") { _: dynamic -> }
        res.on("end") { _: dynamic -> done(ok) }
    }
    req.on("error") { _: dynamic -> done(false) }
    req.on("timeout") { _: dynamic ->
        try { req.destroy() } catch (_: Throwable) {}
        done(false)
    }
    req.end()
}

/**
 * Show a native fallback dialog when `/admin/shutdown` did not return
 * `200 OK` in time. The user picks between forcing the Electron quit
 * (server keeps running, can be retried later) or cancelling the quit.
 *
 * @return `true` if the user chose to force-quit Electron; `false` to cancel
 */
private fun showShutdownFailedDialog(): Boolean {
    val opts: dynamic = js("({})")
    opts.type = "warning"
    opts.title = "Couldn't stop the server"
    opts.message = "Termtastic couldn't shut down the background server cleanly."
    opts.detail = "You can quit Termtastic without stopping the server " +
        "(the server keeps running and can be stopped again next time), or cancel and try again."
    opts.buttons = arrayOf("Cancel", "Quit Termtastic only")
    opts.defaultId = 0
    opts.cancelId = 0
    val win = mainWindow?.takeIf { !it.isDestroyed() }
    val choice = dialog.showMessageBoxSync(win, opts)
    return choice == 1
}

/**
 * Orchestrate a quit intent: show the modal, optionally stop the
 * server, then call [ElectronApp.quit] with the [quitConfirmed] flag
 * set so the next `before-quit` lets the quit through.
 *
 * Idempotent: re-entry while a previous request is in flight is a no-op.
 * Called by the `before-quit` handler and the main window's `close`
 * handler — both quit intents funnel through here so they share UI.
 */
private fun requestQuit() {
    if (quitConfirmed) {
        app.quit()
        return
    }
    if (quitInProgress) return
    quitInProgress = true
    if (!isRendererQuitCapable()) {
        // No live renderer to host the confirmation modal — the server
        // never came up or the window is on the showUnreachable
        // fallback page. Let the quit through; don't try to POST
        // /admin/shutdown (the server can't answer).
        quitConfirmed = true
        app.quit()
        return
    }
    askQuitConfirmation().then { result: dynamic ->
        if (result?.confirmed != true) {
            // User cancelled — leave the app running, allow another
            // quit attempt to re-show the modal.
            quitInProgress = false
            return@then
        }
        if (result.killServer == true) {
            postShutdown(5000).then { ok: Boolean ->
                if (ok) {
                    quitConfirmed = true
                    app.quit()
                } else if (showShutdownFailedDialog()) {
                    // User chose to quit Electron only; server keeps running.
                    quitConfirmed = true
                    app.quit()
                } else {
                    quitInProgress = false
                }
            }
        } else {
            quitConfirmed = true
            app.quit()
        }
    }
}

// ── About dialog dispatch ────────────────────────────────────────────

private fun showAboutDialog() {
    val focused = BrowserWindow.getFocusedWindow()
    val mw = mainWindow
    val target: BrowserWindow = focused
        ?: (if (mw != null && !mw.isDestroyed()) mw else null)
        ?: return
    if (!target.isVisible()) target.show()
    target.focus()
    target.webContents.send("show-about-dialog")
}

// ── Application menu ─────────────────────────────────────────────────

private fun buildAppMenu() {
    val isMac = process.platform == "darwin"
    val template = mutableListOf<dynamic>()

    if (isMac) {
        val aboutItem: dynamic = js("({})")
        aboutItem.label = "About $APP_NAME"
        aboutItem.click = { showAboutDialog() }

        val loginItem: dynamic = js("({})")
        loginItem.label = "Launch at Login"
        loginItem.type = "checkbox"
        loginItem.checked = isLoginItemEnabled()
        loginItem.click = { toggleLoginItem() }

        val appSubmenu = arrayOf<dynamic>(
            aboutItem,
            js("({type:'separator'})"),
            loginItem,
            js("({type:'separator'})"),
            js("({role:'hide'})"),
            js("({role:'hideOthers'})"),
            js("({role:'unhide'})"),
            js("({type:'separator'})"),
            js("({role:'quit'})"),
        )
        val appMenu: dynamic = js("({})")
        appMenu.label = APP_NAME
        appMenu.submenu = appSubmenu
        template.add(appMenu)
    }

    template.add(js("({label:'Edit', submenu:[{role:'undo'},{role:'redo'},{type:'separator'},{role:'cut'},{role:'copy'},{role:'paste'},{role:'selectAll'}]})"))
    template.add(js("({label:'View', submenu:[{role:'reload'},{role:'forceReload'},{role:'toggleDevTools'},{type:'separator'},{role:'resetZoom'},{role:'zoomIn'},{role:'zoomOut'},{type:'separator'},{role:'togglefullscreen'}]})"))

    val windowSubmenu: Array<dynamic> = if (isMac) {
        arrayOf<dynamic>(
            js("({role:'minimize'})"),
            js("({role:'zoom'})"),
            js("({type:'separator'})"),
            js("({role:'front'})"),
        )
    } else {
        arrayOf<dynamic>(
            js("({role:'minimize'})"),
            js("({role:'zoom'})"),
            js("({role:'close'})"),
        )
    }
    val windowMenu: dynamic = js("({})")
    windowMenu.label = "Window"
    windowMenu.submenu = windowSubmenu
    template.add(windowMenu)

    if (isMac) {
        val workingItem: dynamic = js("({})")
        workingItem.label = "Pane state: Working"
        workingItem.click = { sendDebugSetPaneState("working") }
        val waitingItem: dynamic = js("({})")
        waitingItem.label = "Pane state: Waiting"
        waitingItem.click = { sendDebugSetPaneState("waiting") }
        val clearItem: dynamic = js("({})")
        clearItem.label = "Pane state: Clear"
        clearItem.click = { sendDebugSetPaneState("auto") }

        val debugMenu: dynamic = js("({})")
        debugMenu.label = "Debug"
        debugMenu.submenu = arrayOf<dynamic>(workingItem, waitingItem, clearItem)
        template.add(debugMenu)
    }

    Menu.setApplicationMenu(Menu.buildFromTemplate(template.toTypedArray()))
}

/**
 * Sends the `debug-set-pane-state` IPC to the renderer with [mode]
 * (`"working"`, `"waiting"`, or `"auto"`). The renderer scopes the
 * override to its currently-focused pane.
 */
private fun sendDebugSetPaneState(mode: String) {
    val win = mainWindow ?: return
    win.webContents.send("debug-set-pane-state", mode)
}

// ── BrowserWindow ────────────────────────────────────────────────────

private fun createWindow() {
    val opts = js("({})")
    opts.width = 1280
    opts.height = 800
    opts.minWidth = 720
    opts.minHeight = 480
    opts.title = "Termtastic"
    opts.icon = NodePath.join(__dirname, "..", "..", "icons", "icon.png")
    opts.titleBarStyle = if (chromePrefs.customTitleBar) "hiddenInset" else "default"
    val webPrefs = js("({})")
    webPrefs.preload = NodePath.join(__dirname, "..", "..", "preload.js")
    webPrefs.contextIsolation = true
    webPrefs.nodeIntegration = false
    // Pass the authoritative chrome flag to the renderer at boot so
    // darkness-toolkit's `autoApplyCustomTitleBarBodyClass` can toggle
    // `dt-custom-titlebar` synchronously on the first frame. Termtastic's
    // server-backed settings would supply this eventually, but only after
    // the WebSocket round-trip — and even one frame of missing 80 px
    // traffic-light reservation visibly jitters the topbar.
    // Forward the desktop app version to the renderer so the in-app About
    // dialog can show the version name (CFBundleShortVersionString, e.g.
    // "1.0.1") and version code (CFBundleVersion, e.g. "1"). `app.getVersion()`
    // returns the short version; the build number is read from package.json.
    webPrefs.additionalArguments = arrayOf(
        "--darkness-custom-titlebar=${chromePrefs.customTitleBar}",
        "--termtastic-version-name=${app.getVersion()}",
        "--termtastic-version-code=${readBundleVersion()}",
    )
    opts.webPreferences = webPrefs

    val w = BrowserWindow(opts)
    mainWindow = w
    // Intercept the window close button so it goes through the same
    // quit-confirmation modal as Cmd-Q. On macOS this is a deliberate
    // departure from the platform default (where closing the last
    // window leaves the app alive in the dock) — the user has asked
    // for a single quit-intent surface that always confirms.
    w.on("close") { event: dynamic ->
        if (quitConfirmed) return@on
        if (!isRendererQuitCapable()) {
            // Don't trap the user on the "server unreachable" page
            // (or any state where the renderer can't host the modal):
            // allow the native close to proceed. Marking
            // quitConfirmed prevents `before-quit` from looping back
            // through requestQuit().
            quitConfirmed = true
            return@on
        }
        try { event.preventDefault() } catch (_: Throwable) {}
        requestQuit()
    }
    w.loadURL(TARGET_URL)
    // did-fail-load: Electron's signature is
    //   (event, errorCode, errorDescription, validatedURL, isMainFrame, ...).
    // We register a 4-arg JS-shape handler via asDynamic().on so we can
    // pick errorCode/errorDescription off the positional args directly.
    val failHandler: (dynamic, dynamic, dynamic) -> Unit = { _, errorCode, errorDescription ->
        val codeInt = (errorCode as? Int) ?: 0
        if (codeInt != -3) {
            showUnreachable((errorDescription as? String) ?: "", null)
        }
    }
    w.webContents.asDynamic().on("did-fail-load", failHandler)

    // Forward macOS native fullscreen state to the renderer so the
    // toolkit can drop its 80 px traffic-light reservation while the
    // OS hides the traffic-light cluster (see
    // `setDtMacFullscreenBodyClass` in darkness-toolkit). Listeners are
    // attached on every window construction because `darkness:setCustomTitleBar`
    // recreates the BrowserWindow.
    w.on("enter-full-screen") { _ ->
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", true)
    }
    w.on("leave-full-screen") { _ ->
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", false)
    }
    // Initial-state emit: macOS may relaunch directly into a restored
    // fullscreen Space, so wait for the renderer to be ready and push
    // the current value once. Subsequent changes flow via the events
    // above.
    w.webContents.asDynamic().on("did-finish-load") {
        if (!w.isDestroyed()) w.webContents.send("fullscreen-changed", w.isFullScreen())
    }
}

private fun showAndFocus() {
    val w = mainWindow
    if (w == null || w.isDestroyed()) {
        createWindow(); return
    }
    if (w.isMinimized()) w.restore()
    w.show()
    w.focus()
    if (process.platform == "darwin") {
        app.focus(js("({steal: true})"))
    }
}

private fun showUnreachable(errorDescription: String, hint: String?) {
    val hintHtml = hint?.let { "<p>$it</p>" }
        ?: "<p>Start the server with:</p><p><code>./gradlew :server:run</code></p>"
    val html = """
        <!doctype html>
        <html><head><meta charset="utf-8"/><title>Termtastic — server unreachable</title>
        <style>
        body{font-family:-apple-system,system-ui,sans-serif;background:#1e1e1e;color:#e0e0e0;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}
        .card{max-width:520px;padding:32px;background:#2a2a2a;border-radius:8px;box-shadow:0 4px 16px rgba(0,0,0,0.4);}
        h1{margin-top:0;font-size:18px;}
        code{background:#111;padding:2px 6px;border-radius:4px;font-size:13px;}
        button{margin-top:16px;padding:8px 16px;background:#0a84ff;color:white;border:none;border-radius:4px;cursor:pointer;font-size:14px;}
        button:hover{background:#006fe0;}
        .err{color:#888;font-size:12px;margin-top:12px;}
        </style></head><body><div class="card">
        <h1>Can't reach the Termtastic server</h1>
        <p>Tried to load <code>$TARGET_URL</code>.</p>
        $hintHtml
        <button onclick="location.reload()">Retry</button>
        <div class="err">$errorDescription</div>
        </div></body></html>
    """.trimIndent()
    val w = mainWindow ?: return
    w.loadURL("data:text/html;charset=utf-8," + js("encodeURIComponent")(html) as String)
}

// ── Server bootstrap orchestration ───────────────────────────────────

private fun ensureServerThenCreateWindow(): Promise<Unit> = Promise { resolve, _ ->
    buildAppMenu()

    if (URL_OVERRIDE != null) {
        createWindow(); resolve(Unit); return@Promise
    }

    isPortListening(PROD_PORT).then { listening: Boolean ->
        if (listening) {
            createWindow(); resolve(Unit); return@then
        }
        val jarPath = findServerJar()
        if (jarPath == null) {
            createWindow(); resolve(Unit); return@then
        }
        val spawned = try {
            spawnEmbeddedServer(jarPath)
        } catch (err: Throwable) {
            createWindow()
            showUnreachable(
                err.message ?: "spawn failed",
                "Couldn't launch the embedded server. Make sure Java 17+ is installed (Homebrew: <code>brew install openjdk</code>) and discoverable via <code>JAVA_HOME</code> or <code>/usr/libexec/java_home</code>.",
            )
            resolve(Unit); return@then
        }
        // Race port poll vs spawn-error so a missing JDK fails fast.
        // Drop down to JS Promise APIs via asDynamic() so we don't have
        // to fight Kotlin's parametric Promise inference for ad-hoc
        // result envelopes.
        val portPromise: dynamic = waitForPort(PROD_PORT, 30000).then { ok: Boolean ->
            val r: dynamic = js("({})"); r.kind = "port"; r.ok = ok; r
        }
        val errorPromise: dynamic = spawned.spawnError.then { err: dynamic ->
            val r: dynamic = js("({})"); r.kind = "error"; r.err = err; r
        }
        val raced: dynamic = js("Promise").race(arrayOf<dynamic>(portPromise, errorPromise))
        raced.then { result: dynamic ->
            createWindow()
            val kind = result.kind as String
            if (kind == "error") {
                val err = result.err
                val msg = (err.message as? String) ?: err.toString()
                showUnreachable(
                    "$msg (tried java at: ${spawned.java})",
                    "Couldn't launch the embedded server. Make sure Java 17+ is installed (Homebrew: <code>brew install openjdk</code>) and discoverable via <code>JAVA_HOME</code> or <code>/usr/libexec/java_home</code>.",
                )
            } else if (result.ok != true) {
                val logHint = "Check the server log at <code>${spawned.logPath}</code> for details."
                showUnreachable(
                    "Timed out waiting for the embedded server on port $PROD_PORT.",
                    "The embedded server didn't come up in time. $logHint",
                )
            }
            resolve(Unit)
        }
    }
}

private fun registerGlobalShortcut() {
    val ok = globalShortcut.register(SUMMON_ACCELERATOR) { showAndFocus() }
    if (!ok) {
        console.warn("Failed to register global shortcut: $SUMMON_ACCELERATOR")
    }
}

// ── tt-file:// custom protocol ───────────────────────────────────────

/**
 * Synchronously declares the `tt-file://` scheme as "privileged" so
 * Chromium gives it standard URL semantics. Must run BEFORE
 * `app.whenReady` resolves — Electron silently ignores this call once
 * the app is ready.
 *
 * Called from [main] at the very top of bootstrap, alongside the
 * single-instance lock setup.
 *
 * @see registerTtFileSchemeHandler
 */
private fun registerTtFileSchemePrivileged() {
    try {
        // `electron.protocol.registerSchemesAsPrivileged([{ scheme, privileges }])`
        val entry: dynamic = js("({})")
        entry.scheme = "tt-file"
        val privileges: dynamic = js("({})")
        privileges.standard = true
        privileges.secure = true
        privileges.supportFetchAPI = true
        privileges.corsEnabled = true
        privileges.stream = true
        entry.privileges = privileges
        protocol.registerSchemesAsPrivileged(arrayOf(entry))
    } catch (_: Throwable) {
        // Non-fatal: the renderer falls back to `srcdoc` rendering when
        // `tt-file://` isn't available, which still shows the page (without
        // relative assets resolving).
    }
}

/**
 * Registers the actual `tt-file://` request handler. URLs of the form
 * `tt-file://localhost/<url-encoded-absolute-path>` are resolved back to
 * the local filesystem and served via `net.fetch("file://...")`, which
 * Electron handles with proper MIME-type detection and byte-range support.
 *
 * Called from [main] inside the `app.whenReady` continuation, after
 * [registerTtFileSchemePrivileged] has run at app startup.
 *
 * Path-traversal note: the Electron main runs as the user. The renderer
 * can only request files the user can read anyway, so no extra guarding
 * is added here.
 */
private fun registerTtFileSchemeHandler() {
    try {
        val handler: (dynamic) -> dynamic = { request ->
            val rawUrl = (request.url as? String) ?: ""
            // Parse the URL, decode the pathname, ensure a leading '/'.
            val parsed: dynamic = js("new URL(rawUrl)")
            val decoded: String = (js("decodeURI")(parsed.pathname) as? String) ?: ""
            val absPath = if (decoded.startsWith("/")) decoded else "/$decoded"
            val encoded: String = js("encodeURI")(absPath) as String
            js("require('electron')").net.fetch("file://$encoded")
        }
        protocol.handle("tt-file", handler)
    } catch (_: Throwable) {
        // Non-fatal — see comment in [registerTtFileSchemePrivileged].
    }
}

// ── Bootstrap ────────────────────────────────────────────────────────

/**
 * Entry point. Called by `electron/main.js` (a thin stub) immediately
 * after `require("./resources/main/Termtastic-electron-main.js")`.
 *
 * Owns the same lifecycle the legacy JS file did: single-instance
 * lock, app menu, server bootstrap, BrowserWindow creation, IPC
 * handlers, global shortcut, and the standard macOS/Linux window
 * lifecycle event handlers.
 */
fun main() {
    app.setName(APP_NAME)

    // A dev/demo launch must not share prod's single-instance lock or its
    // on-disk state. setName() alone does NOT redirect the userData path that
    // requestSingleInstanceLock() keys off: that path resolves from the npm
    // package name ("termtastic-electron"), which is identical for an
    // unpackaged dev/demo launch and the packaged prod app. Without an isolated
    // dir, a dev/demo launch silently loses the lock to a running prod instance
    // and quits before opening a window. Pin one up front so prod / dev / demo
    // each own an independent lock and can run side by side.
    //
    // The demo additionally lives under the OS temp dir and is WIPED here at
    // startup, so every demo launch begins clean and nothing depends on
    // exit-time cleanup (the launcher script may be killed before it can run a
    // trap). Assumes one demo at a time; a second concurrent demo launch would
    // reset the first's scratch state.
    val dataDir = when {
        IS_DEMO -> NodePath.join(app.getPath("temp"), "termtastic-demo")
        IS_DEV_LAUNCH -> NodePath.join(app.getPath("appData"), APP_NAME)
        else -> null
    }
    if (dataDir != null) {
        if (IS_DEMO) {
            try {
                NodeFs.rmSync(dataDir, js("({ recursive: true, force: true })"))
            } catch (_: Throwable) {
                // Best effort: a leftover file just carries into this run.
            }
        }
        app.setPath("userData", dataDir)
    }

    if (!app.requestSingleInstanceLock()) {
        app.quit()
        return
    }
    app.on("second-instance") { _, _ -> showAndFocus() }

    // Register the `tt-file://` custom scheme so the renderer's inline HTML
    // preview iframe can load local files with relative-asset resolution.
    // Must be called BEFORE `app.whenReady` resolves — Electron rejects
    // `registerSchemesAsPrivileged` after the app is ready. The scheme is
    // declared "standard" so Chromium uses normal URL semantics (relative
    // path resolution against the document's base URL), "secure" so it can
    // host scripts/iframes without mixed-content warnings, and
    // "supportFetchAPI" so the file's CSS/JS resources can be fetched.
    // Path-traversal guard is unnecessary: the Electron main runs as the
    // same user as the renderer, and a user already has read access to
    // every file the iframe could request via this protocol.
    registerTtFileSchemePrivileged()

    // IPC: tint the BrowserWindow background so the macOS native title
    // bar adopts the theme colour.
    ipcMain.handle("set-window-background-color") { event, color ->
        val c = color as? String
        if (!c.isNullOrEmpty()) {
            val win = BrowserWindow.fromWebContents(event.sender)
            if (win != null && !win.isDestroyed()) win.setBackgroundColor(c)
        }
        Unit
    }

    // IPC: toggle the custom (themed) title bar. Because `titleBarStyle`
    // is immutable post-creation we destroy the current main window and
    // build a fresh one with the new style. The server keeps every bit
    // of user state (PTY sessions, layout, focus, settings). Channel name
    // matches notegrow's so both apps share the same `darknessApi`
    // preload bridge — see `darkness-toolkit`'s `AppShellMount`
    // subscriber and termtastic's own server-driven subscriber in
    // `main.kt`, both of which invoke this channel.
    ipcMain.handle("darkness:setCustomTitleBar") { _, enabled ->
        val next = enabled == true
        if (next != chromePrefs.customTitleBar) {
            chromePrefs = ChromePrefs(customTitleBar = next)
            saveChromePrefs(chromePrefs)
            val old = mainWindow
            createWindow()
            if (old != null && !old.isDestroyed()) old.destroy()
        }
        Unit
    }

    // Accept the server's self-signed TLS cert silently when (and only when)
    // it's served from loopback. Loopback traffic cannot be MITM'd, so
    // pinning here would only complicate the dev cycle and the bundled-app
    // first-launch experience. Remote hosts still get the default rejection
    // — a `TERMTASTIC_URL` pointing at a remote box would surface a fatal
    // `did-fail-load`, which is the right behaviour until the renderer
    // learns to fingerprint-pin (out of scope for the Electron shell;
    // native mobile clients do the pinning via :client).
    //
    // Register on `app` (not `session.defaultSession`) and before any
    // `BrowserWindow` is created so the first navigation against TARGET_URL
    // gets the handler in place.
    val certHandler: (dynamic, dynamic, dynamic, dynamic, dynamic, dynamic) -> Unit =
        { event, _, url, _, _, callback ->
            val host: String? = try {
                val parsed: dynamic = js("new URL(url)")
                parsed.hostname as? String
            } catch (_: Throwable) {
                null
            }
            val isLoopback = host == "127.0.0.1" || host == "::1" || host == "localhost"
            if (isLoopback) {
                try { event.preventDefault() } catch (_: Throwable) {}
                callback(true)
            } else {
                callback(false)
            }
        }
    app.asDynamic().on("certificate-error", certHandler)

    app.whenReady().then {
        if (process.platform == "darwin" && app.dock != null) {
            try {
                val devIcon = NodePath.join(__dirname, "..", "..", "icons", "icon.png")
                val packagedIcon = NodePath.join(process.resourcesPath, "icon.icns")
                val iconPath = if (app.isPackaged) packagedIcon else devIcon
                app.dock?.setIcon(iconPath)
            } catch (_: Throwable) {
                // Cosmetic — never let a missing icon abort startup.
            }
        }

        // Notification permissions — Electron denies by default; allow for our origin.
        session.defaultSession.setPermissionRequestHandler { _, permission, callback ->
            callback(permission == "notifications")
        }
        session.defaultSession.setPermissionCheckHandler { _, permission ->
            permission == "notifications"
        }

        // Bind the `tt-file://` protocol handler now that the app is ready
        // (the privilege flags were registered synchronously up top, before
        // `app.whenReady`). The handler maps `tt-file://localhost/<absPath>`
        // back to the local filesystem and returns the file via `net.fetch`
        // so Electron emits proper MIME types and range support for the
        // iframe load.
        registerTtFileSchemeHandler()

        ensureServerThenCreateWindow().then {
            if (!IS_DEV_LAUNCH) registerGlobalShortcut()
        }
    }

    // Quit-confirmation gate. Every Cmd-Q / menu Quit lands here first.
    // We hold the quit with preventDefault and route through the
    // renderer modal; once the user has confirmed (and any opted-in
    // server shutdown finished), `quitConfirmed` is true and the
    // quit proceeds.
    app.on("before-quit") { event, _ ->
        if (quitConfirmed) return@on
        try { event.preventDefault() } catch (_: Throwable) {}
        requestQuit()
    }

    // IPC: receive the renderer's response from the quit-confirmation
    // modal. The payload shape matches QuitConfirmationResult on the
    // renderer side: `{ confirmed: Boolean, killServer: Boolean }`.
    ipcMain.handle("quit-confirmation-result") { _, payload ->
        val resolver = pendingQuitResolver
        pendingQuitResolver = null
        resolver?.invoke(payload)
        Unit
    }

    // IPC: open a filesystem path with the OS default application. Used by
    // the file browser pane's "Open in default browser" button to open the
    // selected HTML file in the user's default web browser. Returns the
    // promise `shell.openPath` produces (resolves to `""` on success, or
    // an error message string the renderer can surface if needed).
    ipcMain.handle("open-path") { _, pathArg ->
        val absPath = pathArg as? String
        if (absPath.isNullOrBlank()) "Invalid path"
        else shell.openPath(absPath)
    }

    // IPC: open an external http(s) URL in the user's default browser. Used by
    // the "New update available!" label next to the app logo, which carries
    // the manifest's per-platform "more info" URL. `shell.openExternal` (not
    // `openPath`, which is for filesystem paths) hands the URL to the OS so it
    // never loads inside the Electron window.
    ipcMain.handle("open-external-url") { _, urlArg ->
        val url = urlArg as? String
        if (url.isNullOrBlank()) "Invalid URL"
        else shell.openExternal(url)
    }

    app.on("will-quit") { _, _ -> globalShortcut.unregisterAll() }

    app.on("window-all-closed") { _, _ ->
        if (process.platform != "darwin") app.quit()
    }

    app.on("activate") { _, _ ->
        if (BrowserWindow.getAllWindows().isEmpty()) createWindow()
    }
}
