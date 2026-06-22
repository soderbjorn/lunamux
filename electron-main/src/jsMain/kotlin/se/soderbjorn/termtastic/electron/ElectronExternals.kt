/* ElectronExternals.kt
 * Minimal `external` declarations for the Electron APIs the
 * termtastic main process touches: app lifecycle + paths, BrowserWindow
 * + WebContents, ipcMain, Menu, globalShortcut, session permissions.
 * Loaded as a CommonJS module via `@JsModule("electron")`. Mirrors
 * notegrow's electron-main externals where the surface overlaps.
 */
@file:JsModule("electron")
@file:JsNonModule

package se.soderbjorn.termtastic.electron

import kotlin.js.Promise

external val app: ElectronApp
external val ipcMain: IpcMain
external val globalShortcut: GlobalShortcut
external val session: SessionRoot
external val dialog: ElectronDialog

/**
 * Electron `protocol` module — used to register the `tt-file://` custom
 * scheme that the renderer's HTML preview iframe loads files through.
 * Declared as `dynamic` to avoid pinning to a specific Electron typing of
 * `registerSchemesAsPrivileged` / `handle`; we always call these via the
 * documented JS shape.
 */
external val protocol: dynamic

/**
 * Electron `shell` module — used to invoke the OS default application
 * for a given path (e.g. `shell.openPath(absPath)` opens an `.html` file
 * in the user's default web browser).
 */
external val shell: dynamic

external interface ElectronApp {
    val isPackaged: Boolean
    val dock: ElectronDock?
    fun setName(name: String)
    fun getName(): String
    fun getVersion(): String
    fun getAppPath(): String
    fun getPath(name: String): String
    fun setPath(name: String, path: String)
    fun requestSingleInstanceLock(): Boolean
    fun quit()
    fun focus(opts: dynamic = definedExternally)
    fun on(event: String, listener: (dynamic, dynamic) -> Unit): ElectronApp
    fun whenReady(): Promise<Unit>
    fun setLoginItemSettings(settings: dynamic)
    fun getLoginItemSettings(): dynamic
}

external interface ElectronDock {
    fun setIcon(icon: String)
}

external interface IpcMain {
    fun handle(channel: String, listener: (event: dynamic, arg: dynamic) -> dynamic)
    fun handle(channel: String, listener: (event: dynamic, arg1: dynamic, arg2: dynamic) -> dynamic)
}

external interface GlobalShortcut {
    fun register(accelerator: String, callback: () -> Unit): Boolean
    fun unregisterAll()
}

external interface SessionRoot {
    val defaultSession: ElectronSession
}

external interface ElectronSession {
    fun setPermissionRequestHandler(handler: (webContents: dynamic, permission: String, callback: (Boolean) -> Unit) -> Unit)
    fun setPermissionCheckHandler(handler: (webContents: dynamic, permission: String) -> Boolean)
}

@JsName("BrowserWindow")
external class BrowserWindow(options: dynamic = definedExternally) {
    val webContents: WebContents
    fun isDestroyed(): Boolean
    fun isMinimized(): Boolean
    fun isVisible(): Boolean
    fun isFocused(): Boolean
    fun isFullScreen(): Boolean
    fun restore()
    fun show()
    fun hide()
    fun focus()
    fun destroy()
    fun loadURL(url: String, options: dynamic = definedExternally): Promise<Unit>
    fun setBackgroundColor(color: String)
    fun on(event: String, listener: (event: dynamic) -> Unit): BrowserWindow

    companion object {
        fun fromWebContents(contents: dynamic): BrowserWindow?
        fun getFocusedWindow(): BrowserWindow?
        fun getAllWindows(): Array<BrowserWindow>
    }
}

external interface ElectronDialog {
    fun showMessageBoxSync(window: BrowserWindow?, options: dynamic): Int
}

external interface WebContents {
    fun on(event: String, listener: (event: dynamic) -> Unit)
    fun send(channel: String, vararg args: dynamic)
}

@JsName("Menu")
external object Menu {
    fun setApplicationMenu(menu: dynamic)
    fun buildFromTemplate(template: Array<dynamic>): dynamic
}
