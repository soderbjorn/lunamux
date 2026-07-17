/* ElectronUpdaterExternals.kt
 * Minimal `external` declarations for the `electron-updater` package's
 * `autoUpdater` singleton — the only part of the library the Lunamux
 * main process touches. Loaded as a CommonJS module via
 * `@JsModule("electron-updater")`, mirroring the `@file:JsModule`
 * convention used for the Electron API in [ElectronExternals.kt].
 *
 * The actual update lifecycle wiring (event listeners → renderer IPC,
 * check/download/install control) lives in [AutoUpdater.kt]; this file only
 * describes the JS surface.
 */
@file:JsModule("electron-updater")
@file:JsNonModule

package se.soderbjorn.lunamux.electron

import kotlin.js.Promise

/**
 * The `electron-updater` `autoUpdater` singleton (a named export of the
 * package). Resolves updates against the GitHub Releases `publish` provider
 * baked into the app's `app-update.yml` at build time.
 *
 * Consumed only by [AutoUpdater.kt], which configures it, subscribes to its
 * lifecycle events, and exposes check/download/install entry points to the
 * rest of the main process.
 *
 * @see AutoUpdaterApi for the subset of methods/properties declared here.
 */
external val autoUpdater: AutoUpdaterApi

/**
 * The subset of electron-updater's `AppUpdater` surface the main process uses.
 *
 * Event payloads are intentionally left `dynamic`: [AutoUpdater.kt] reads only
 * a couple of fields off them (`version`, `percent`, …) and forwards a
 * hand-built plain object to the renderer, so pinning full electron-updater
 * typings here would add surface with no benefit.
 */
external interface AutoUpdaterApi {
    /**
     * When `false`, an available update is not downloaded until
     * [downloadUpdate] is called. Lunamux sets this so the user explicitly
     * opts into the download from the Updates panel (mirrors the acolite flow).
     */
    var autoDownload: Boolean

    /**
     * When `true` (electron-updater's default) a downloaded-but-not-installed
     * update is applied automatically on the next app quit. Lunamux sets this
     * `false` in [initAutoUpdater] so updates are **opt-in** — a downloaded update
     * applies only when the user clicks "Restart to install", never silently on
     * quit.
     */
    var autoInstallOnAppQuit: Boolean

    /**
     * Checks the configured provider for a newer version. On a hit it emits
     * `update-available`; otherwise `update-not-available`. Errors (e.g. the
     * user being offline) surface via the `error` event and reject the promise.
     *
     * @return a promise resolving to the check result (shape unused here), or
     *   rejecting on a network/config error.
     */
    fun checkForUpdates(): Promise<dynamic>

    /**
     * Downloads the update discovered by [checkForUpdates]. Emits
     * `download-progress` events during transfer and `update-downloaded` on
     * completion.
     *
     * @return a promise resolving when the download finishes.
     */
    fun downloadUpdate(): Promise<dynamic>

    /**
     * Quits the app and installs the downloaded update, then relaunches. Must
     * only be called after `update-downloaded`. The caller is responsible for
     * clearing any quit gate first (see the `quit:` IPC handler in [main]).
     *
     * @param isSilent on Windows, install without showing the installer UI.
     * @param isForceRunAfter relaunch the app after a silent install.
     */
    fun quitAndInstall(isSilent: Boolean = definedExternally, isForceRunAfter: Boolean = definedExternally)

    /**
     * Subscribes to an update lifecycle event
     * (`checking-for-update`, `update-available`, `update-not-available`,
     * `download-progress`, `update-downloaded`, `error`).
     *
     * @param event the electron-updater event name.
     * @param listener called with the event payload (`dynamic`; absent for
     *   the no-arg events).
     */
    fun on(event: String, listener: (info: dynamic) -> Unit)

    /** Detaches all previously-registered lifecycle listeners. */
    fun removeAllListeners()
}
