/* AutoUpdater.kt
 * In-app auto-update lifecycle for the Lunamux Electron main process.
 *
 * A Kotlin/JS port of the acolite `app/updater.ts` pattern: it configures
 * electron-updater's [autoUpdater] singleton (declared in
 * [ElectronUpdaterExternals.kt]), relays each update lifecycle event to the
 * renderer over IPC, and exposes the three control entry points
 * ([checkForUpdates], [downloadUpdate], [quitAndInstallUpdate]) that the
 * renderer's Updates panel drives.
 *
 * The renderer half lives in `web/.../AutoUpdaterPanel.kt`; the IPC channel
 * names shared across main ↔ preload ↔ renderer are defined once in
 * [UpdateChannels]. Wiring (IPC handler registration, the startup check, and
 * the "Check for Updates…" menu item) is in [main] / `buildAppMenu`.
 */
package se.soderbjorn.lunamux.electron

/**
 * IPC channel names for the auto-update feature, shared conceptually with
 * `electron/preload.js` and the renderer (`web/.../AutoUpdaterPanel.kt`),
 * which use the same string literals. Grouped here so the main-process side
 * has a single source of truth.
 *
 * The `update:*` command channels flow renderer → main (via `ipcRenderer.invoke`
 * ⇄ `ipcMain.handle`); the remaining channels flow main → renderer (via
 * `webContents.send` ⇄ `ipcRenderer.on`). [SHOW_PANEL] is a main → renderer
 * signal that asks the renderer to open the Updates panel (fired by the
 * "Check for Updates…" menu item).
 */
object UpdateChannels {
    // ── renderer → main (commands) ──────────────────────────────────
    /** Ask the main process to check the provider for a newer version. */
    const val CHECK = "update:check"

    /** Ask the main process to download the available update. */
    const val DOWNLOAD = "update:download"

    /** Ask the main process to quit, install the update, and relaunch. */
    const val QUIT_AND_INSTALL = "update:quit-and-install"

    // ── main → renderer (lifecycle events) ──────────────────────────
    /** A check has started. */
    const val CHECKING = "update:checking"

    /** A newer version is available (payload: `{ version }`). */
    const val AVAILABLE = "update:available"

    /** No newer version is available. */
    const val NOT_AVAILABLE = "update:not-available"

    /** Download progress (payload: `{ percent, transferred, total, bytesPerSecond }`). */
    const val PROGRESS = "update:progress"

    /** The update has finished downloading and is ready to install (payload: `{ version }`). */
    const val DOWNLOADED = "update:downloaded"

    /** An update operation failed (payload: `{ message }`). */
    const val ERROR = "update:error"

    // ── main → renderer (navigation) ────────────────────────────────
    /** Ask the renderer to open the Updates panel (from the Help menu item). */
    const val SHOW_PANEL = "show-updates-panel"
}

/**
 * Guards one-time listener binding in [initAutoUpdater]. electron-updater's
 * `autoUpdater` is a process-wide singleton, so its lifecycle listeners must
 * be attached exactly once regardless of how many times a BrowserWindow is
 * (re)created.
 */
private var updaterListenersBound = false

/**
 * Supplier of the live main window, stored by [initAutoUpdater] so
 * [sendUpdateEvent] can reach the current renderer from anywhere in this file
 * (not just the listener closures). Resolved at send time, so a window rebuild
 * never severs event delivery.
 */
private var updaterWindowSupplier: (() -> BrowserWindow?)? = null

/**
 * Whether an update has been fully downloaded and is ready to install. Set by
 * the `update-downloaded` event; cleared again by `update-available` (a newly
 * discovered version invalidates the previous download, and the renderer's
 * banner drops back to "Download" at the same moment, keeping both sides in
 * agreement).
 *
 * Guards the `update:quit-and-install` IPC handler in [main]: without it, a
 * renderer whose state got ahead of reality could shut the bundled server down
 * for an install that [AutoUpdaterApi.quitAndInstall] will refuse, stranding a
 * running app with a dead server.
 */
private var updateDownloaded = false

/**
 * Whether a downloaded update is ready to install right now.
 *
 * Called by the `update:quit-and-install` IPC handler in [main] before it
 * commits to the pre-install teardown (quit-gate bypass + server shutdown).
 *
 * @return `true` once `update-downloaded` has fired for the currently-known
 *   version, `false` before any download or after a newer version was found.
 */
fun isUpdateDownloaded(): Boolean = updateDownloaded

/**
 * Sends one update lifecycle event to the current renderer, dropping it when
 * no live window exists. Shared by the [initAutoUpdater] listeners and the
 * out-of-band error paths ([reportUpdateError]).
 *
 * @param channel one of the main → renderer [UpdateChannels].
 * @param payload the plain-object payload for the channel, or `null`.
 */
private fun sendUpdateEvent(channel: String, payload: dynamic = null) {
    val w = updaterWindowSupplier?.invoke() ?: return
    if (!w.isDestroyed()) w.webContents.send(channel, payload)
}

/**
 * Surfaces an update failure to the renderer's banner on the standard
 * [UpdateChannels.ERROR] channel, exactly like electron-updater's own `error`
 * event.
 *
 * Called by the `update:quit-and-install` IPC handler in [main] when it
 * refuses an install (no downloaded update) and by [quitAndInstallUpdate]'s
 * failure path — cases that never pass through the autoUpdater singleton and
 * so would otherwise fail invisibly.
 *
 * @param message human-readable failure text, shown verbatim in the banner.
 */
fun reportUpdateError(message: String) {
    val payload = js("({})")
    payload.message = message
    sendUpdateEvent(UpdateChannels.ERROR, payload)
}

/**
 * Configures [autoUpdater] and binds its lifecycle listeners once, forwarding
 * each event to the current renderer.
 *
 * Called once from [main] during startup. Because a BrowserWindow can be
 * rebuilt (e.g. the title-bar-style toggle destroys and recreates it), events
 * are sent to whatever window [currentWindow] resolves at fire time rather
 * than to a window captured here — so update progress keeps reaching the live
 * renderer across a window swap.
 *
 * @param currentWindow supplier of the live main [BrowserWindow], or `null`
 *   when none exists yet; provided by [main] where the `mainWindow` global is
 *   in scope. Events are dropped when it yields `null` or a destroyed window.
 * @see UpdateChannels for the channels each event maps to.
 */
fun initAutoUpdater(currentWindow: () -> BrowserWindow?) {
    // Never download without an explicit user action from the Updates banner.
    autoUpdater.autoDownload = false
    // Updates are opt-in: a downloaded update installs ONLY when the user clicks
    // "Restart to install" (quitAndInstall), never silently on the next app quit
    // (electron-updater's default would auto-apply a pending download on quit).
    autoUpdater.autoInstallOnAppQuit = false

    updaterWindowSupplier = currentWindow
    if (updaterListenersBound) return
    updaterListenersBound = true

    autoUpdater.on("checking-for-update") { sendUpdateEvent(UpdateChannels.CHECKING) }

    autoUpdater.on("update-available") { info ->
        // A newly discovered version invalidates any previously downloaded one
        // (the renderer's banner falls back to "Download" on this same event).
        updateDownloaded = false
        val payload = js("({})")
        payload.version = info?.version
        payload.releaseNotesUrl = releaseNotesUrl(info?.version as? String)
        sendUpdateEvent(UpdateChannels.AVAILABLE, payload)
    }

    autoUpdater.on("update-not-available") { sendUpdateEvent(UpdateChannels.NOT_AVAILABLE) }

    autoUpdater.on("download-progress") { progress ->
        val payload = js("({})")
        payload.percent = progress?.percent
        payload.transferred = progress?.transferred
        payload.total = progress?.total
        payload.bytesPerSecond = progress?.bytesPerSecond
        sendUpdateEvent(UpdateChannels.PROGRESS, payload)
    }

    autoUpdater.on("update-downloaded") { info ->
        updateDownloaded = true
        val payload = js("({})")
        payload.version = info?.version
        payload.releaseNotesUrl = releaseNotesUrl(info?.version as? String)
        sendUpdateEvent(UpdateChannels.DOWNLOADED, payload)
    }

    autoUpdater.on("error") { err ->
        val payload = js("({})")
        payload.message = (err?.message as? String) ?: "Update failed"
        sendUpdateEvent(UpdateChannels.ERROR, payload)
    }
}

/**
 * Triggers an update check. No-ops in dev/demo launches: an unpackaged app has
 * no `app-update.yml`, so electron-updater would throw — the acolite original
 * lacked this guard and errored on every dev run.
 *
 * Called by the `update:check` IPC handler, the "Check for Updates…" menu item,
 * and once silently at startup (see [main]). Failures (offline, etc.) surface
 * to the renderer via the `error` event, so the promise rejection is swallowed
 * here to avoid an unhandled rejection.
 */
fun checkForUpdates() {
    if (!app.isPackaged) return
    try {
        autoUpdater.checkForUpdates().catch { /* surfaced via the "error" event */ }
    } catch (_: Throwable) {
        // Synchronous failures (misconfiguration) are non-fatal to the app.
    }
}

/**
 * Starts downloading the available update. Called by the `update:download` IPC
 * handler when the user clicks "Download" in the Updates panel. Progress and
 * completion are reported via the `download-progress` / `update-downloaded`
 * events bound in [initAutoUpdater].
 */
fun downloadUpdate() {
    try {
        autoUpdater.downloadUpdate().catch { /* surfaced via the "error" event */ }
    } catch (_: Throwable) {
    }
}

/**
 * Quits, installs the downloaded update, and relaunches.
 *
 * Called by the `update:quit-and-install` IPC handler after the caller has
 * cleared the app's quit-confirmation gate (an update install is an
 * already-confirmed quit). Deferred via [setImmediate] so the IPC reply is
 * delivered before the app tears down.
 *
 * If [AutoUpdaterApi.quitAndInstall] throws (e.g. no update is actually
 * downloaded), the failure is surfaced to the renderer's banner via
 * [reportUpdateError] and [onFailure] runs — without the try/catch the throw
 * would be an uncaught main-process exception (Electron's error dialog) in an
 * app whose server was just shut down.
 *
 * @param onFailure invoked when the install could not start and the app keeps
 *   running; the caller uses it to undo its pre-install teardown (re-arming
 *   the quit-confirmation gate).
 * @see AutoUpdaterApi.quitAndInstall
 */
fun quitAndInstallUpdate(onFailure: () -> Unit) {
    setImmediate {
        try {
            autoUpdater.quitAndInstall()
        } catch (t: Throwable) {
            reportUpdateError(t.message ?: "Installing the update failed")
            onFailure()
        }
    }
}

/** Cached `owner/repo`-derived GitHub release-tag URL base, resolved once. */
private var cachedReleaseTagBase: String? = null

/** Whether [releaseNotesUrl] has already attempted to resolve [cachedReleaseTagBase]. */
private var releaseTagBaseResolved = false

/**
 * Build the GitHub release page URL for [version] — the "What's new" link the
 * renderer's update banner opens — from the packaged `app-update.yml`
 * (`owner:` / `repo:`), which electron-updater already ships in
 * `Contents/Resources`. Resolved and cached on first call.
 *
 * @param version the release version (without the leading `v`), e.g. "1.4.1".
 * @return `https://github.com/<owner>/<repo>/releases/tag/v<version>`, or `null`
 *   when the manifest is absent (e.g. an unpackaged dev launch) or unparseable.
 */
private fun releaseNotesUrl(version: String?): String? {
    if (version.isNullOrBlank()) return null
    if (!releaseTagBaseResolved) {
        releaseTagBaseResolved = true
        try {
            val ymlPath = NodePath.join(process.resourcesPath, "app-update.yml")
            if (NodeFs.existsSync(ymlPath)) {
                val lines = NodeFs.readFileSync(ymlPath, "utf8").split("\n")
                val owner = lines.firstOrNull { it.trimStart().startsWith("owner:") }?.substringAfter(":")?.trim()
                val repo = lines.firstOrNull { it.trimStart().startsWith("repo:") }?.substringAfter(":")?.trim()
                if (!owner.isNullOrEmpty() && !repo.isNullOrEmpty()) {
                    cachedReleaseTagBase = "https://github.com/$owner/$repo/releases/tag"
                }
            }
        } catch (_: Throwable) {
            // Best-effort: no link rather than a crash.
        }
    }
    val base = cachedReleaseTagBase ?: return null
    return "$base/v$version"
}
