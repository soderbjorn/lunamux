/**
 * @file Lunamux — Electron preload script (context bridge).
 *
 * Runs in a sandboxed renderer context with `contextIsolation: true`. Exposes
 * a minimal `window.electronApi` object to the web app via Electron's
 * contextBridge, giving the Kotlin/JS frontend access to:
 *
 * - **getPathForFile** -- resolves a dragged-and-dropped `File` object to its
 *   absolute filesystem path (Electron 32+ replacement for the removed
 *   `File.path` property).
 *
 * No Node.js APIs are leaked to the renderer beyond what is listed here.
 */

const { contextBridge, webUtils, ipcRenderer } = require("electron");

// Authoritative window-chrome flag passed by ElectronMain.kt via
// `webPreferences.additionalArguments`. Mirrored onto `darknessApi.customTitleBar`
// below so darkness-toolkit's `autoApplyCustomTitleBarBodyClass` can
// synchronously toggle `dt-custom-titlebar` on the first frame, before
// Lunamux's server-backed settings hydrate over the WebSocket.
const customTitleBarArg = (process.argv || []).find(a => a && a.startsWith("--darkness-custom-titlebar="));
const customTitleBarBoot = customTitleBarArg
  ? customTitleBarArg.substring("--darkness-custom-titlebar=".length) === "true"
  : false;

// Desktop app version, forwarded from the main process via
// `webPreferences.additionalArguments`. `appVersionName` is the
// human-readable version (CFBundleShortVersionString, e.g. "1.0.1");
// `appVersionCode` is the build number (CFBundleVersion, e.g. "1"). Both are
// surfaced to the renderer's in-app About dialog. Empty when not supplied.
const versionNameArg = (process.argv || []).find(a => a && a.startsWith("--lunamux-version-name="));
const appVersionName = versionNameArg
  ? versionNameArg.substring("--lunamux-version-name=".length)
  : "";
const versionCodeArg = (process.argv || []).find(a => a && a.startsWith("--lunamux-version-code="));
const appVersionCode = versionCodeArg
  ? versionCodeArg.substring("--lunamux-version-code=".length)
  : "";

contextBridge.exposeInMainWorld("electronApi", {
  /**
   * Resolves a drag-and-dropped `File` object to its absolute filesystem path.
   *
   * Replacement for the removed `File.path` property (Electron 32+). Used by
   * the Kotlin/JS drag-and-drop handler to obtain the real path of a dropped
   * file so the server can open it.
   *
   * @param {File} file - A DOM `File` from a drop event's `DataTransfer`.
   * @returns {string} Absolute path on the local filesystem.
   */
  getPathForFile: (file) => webUtils.getPathForFile(file),

  /**
   * The desktop app's human-readable version name (CFBundleShortVersionString
   * on macOS, e.g. `"1.0.1"`), forwarded from the main process at boot.
   * Consumed by the renderer's in-app About dialog. Empty string when running
   * outside Electron or when the main process did not supply it.
   *
   * @type {string}
   */
  appVersionName,

  /**
   * The desktop app's build number / version code (CFBundleVersion on macOS,
   * e.g. `"1"`), forwarded from the main process at boot. Shown alongside
   * {@link appVersionName} in the About dialog. Empty string when unavailable.
   *
   * @type {string}
   */
  appVersionCode,

  /**
   * Opens an absolute filesystem path with the OS default application,
   * via `shell.openPath` in the Electron main process. For `.html` paths
   * this is typically the user's default web browser.
   *
   * Called by the file browser pane's "Open in default browser" button
   * when the renderer is hosted in Electron and the selected file is HTML.
   *
   * @param {string} absPath - Absolute filesystem path to open.
   * @returns {Promise<string>} Resolves to `""` on success or an error
   *   message string when the OS could not open the path.
   */
  openPath: (absPath) => ipcRenderer.invoke("open-path", absPath),

  /**
   * Opens an external http(s) URL in the user's default web browser via
   * `shell.openExternal` in the Electron main process, so it never loads
   * inside the app window.
   *
   * Called by the renderer's "New update available!" label (next to the app
   * logo) with the version manifest's per-platform "more info" URL.
   *
   * @param {string} url - Absolute http(s) URL to open externally.
   * @returns {Promise<string>} Resolves to `""` on success or an error
   *   message string when the OS could not open the URL.
   */
  openExternalUrl: (url) => ipcRenderer.invoke("open-external-url", url),

  /**
   * Captures the current window's full contents and writes a PNG to the user's
   * Desktop. Serviced by the Electron main process (`webContents.capturePage`),
   * since the renderer has no filesystem access under context isolation.
   *
   * Called by the 3D world's screenshot shortcut (`P`, live in both command
   * center and free flight).
   *
   * @returns {Promise<string>} Resolves to the saved absolute path on success,
   *   or a string starting with `"!"` carrying an error message on failure.
   */
  saveWindowScreenshot: () => ipcRenderer.invoke("save-window-screenshot"),

  /**
   * Resolves the desktop-capture source id of the current app window, so the
   * renderer can screen-record the 3D world. World3D is a CSS3DRenderer (real
   * DOM panes in 3D) with no WebGL canvas to `captureStream()`, so recording
   * goes through `getUserMedia({ chromeMediaSource: "desktop", chromeMediaSourceId })`
   * + `MediaRecorder` on the composited window. The main process enumerates
   * window sources (`desktopCapturer`) and returns this window's id.
   *
   * Called by the 3D world's record toggle (`⇧R`, live in both command center
   * and free flight) when starting a recording.
   *
   * @returns {Promise<string|null>} The `window:…` capture source id, or `null`
   *   when it could not be resolved.
   */
  getWindowRecordingSourceId: () =>
    ipcRenderer.invoke("get-window-recording-source-id"),

  /**
   * Writes a finished screen recording to the user's Desktop. Counterpart to
   * {@link getWindowRecordingSourceId}: the renderer records the window with
   * `MediaRecorder`, assembles the chunks into a Blob, and passes the raw bytes
   * here for the main process to persist (the renderer has no filesystem access
   * under context isolation). The renderer also passes the container extension so
   * the file is named to match the codec it actually recorded — `mp4` (H.264,
   * plays inline in Slack) when the platform supports it, else `webm`.
   *
   * Called by the 3D world's record toggle (`⇧R`) when stopping a recording.
   *
   * @param {Uint8Array} bytes - The complete video payload.
   * @param {string} [ext] - File extension without a dot (`"mp4"` or `"webm"`);
   *   the main process defaults to `webm` and only honours a known extension.
   * @returns {Promise<string>} Resolves to the saved absolute path on success,
   *   or a string starting with `"!"` carrying an error message on failure.
   */
  saveWindowRecording: (bytes, ext) =>
    ipcRenderer.invoke("save-window-recording", bytes, ext),

  /**
   * Writes a demo-movie timeline text file to the Desktop, named after the
   * just-saved recording so the pair share a stamp (the video's extension is
   * swapped for `.txt`). Each line is a beat's offset into the video followed by
   * the caption that was on screen. Called by the 3D world's recording finalizer
   * when a demo tour played during the recording.
   *
   * @param {string} videoPath - Absolute path of the saved recording; the `.txt`
   *   is written next to it with the same base name.
   * @param {string} text - The full timeline body (one `M:SS.d\tcaption` per line).
   * @returns {Promise<string>} Resolves to the saved absolute path on success, or a
   *   string starting with `"!"` carrying an error message on failure.
   */
  saveDemoTimeline: (videoPath, text) =>
    ipcRenderer.invoke("save-demo-timeline", videoPath, text),

  /**
   * Reports this app's macOS Screen Recording (TCC) authorization status, so the
   * 3D world's record toggle can avoid silently saving a black recording when the
   * permission hasn't been granted. Serviced by the main process
   * (`systemPreferences.getMediaAccessStatus("screen")`).
   *
   * @returns {Promise<string>} `"granted"` / `"denied"` / `"restricted"` /
   *   `"not-determined"` on macOS, or `"granted"` on platforms without this gate.
   */
  getScreenCaptureAccess: () => ipcRenderer.invoke("get-screen-capture-access"),

  /**
   * Nudges macOS to authorize Screen Recording for the app: registers it under
   * Privacy → Screen Recording (provoking the native prompt on first run) and
   * opens that Privacy pane so the user can enable it. No-op off macOS. Called by
   * the record toggle when {@link getScreenCaptureAccess} is not `"granted"`.
   *
   * @returns {Promise<void>}
   */
  openScreenRecordingSettings: () =>
    ipcRenderer.invoke("open-screen-recording-settings"),

  // --- Local JSON data files (renderer LocalStore) ------------------------

  /**
   * Reads a small JSON data file from the app's `userData` directory, backing
   * the renderer-side `LocalStore` (e.g. `local_state.json`). The renderer has
   * no filesystem access under context isolation, so the read is serviced by
   * the Electron main process.
   *
   * @param {string} name - File name within `userData` (e.g. `"local_state.json"`).
   * @returns {Promise<string|null>} Resolves to the UTF-8 file contents, or
   *   `null` when the file does not exist or could not be read.
   */
  readDataFile: (name) => ipcRenderer.invoke("read-data-file", name),

  /**
   * Writes a small JSON data file to the app's `userData` directory,
   * overwriting any existing content. Counterpart to {@link readDataFile}.
   *
   * @param {string} name - File name within `userData`.
   * @param {string} text - UTF-8 text to persist.
   * @returns {Promise<void>} Resolves once the main process has written the file.
   */
  writeDataFile: (name, text) => ipcRenderer.invoke("write-data-file", name, text),

  /**
   * Deletes a data file from the app's `userData` directory. A no-op when the
   * file does not exist.
   *
   * @param {string} name - File name within `userData`.
   * @returns {Promise<void>} Resolves once the main process has removed the file.
   */
  deleteDataFile: (name) => ipcRenderer.invoke("delete-data-file", name),

  // --- Network info --------------------------------------------------------

  /**
   * Reports the host machine's LAN IPv4 address(es) and the server port,
   * resolved by the Electron main process via Node's `os.networkInterfaces()`.
   * The renderer has no Node access under context isolation, so the lookup is
   * serviced over IPC.
   *
   * Called by the renderer's About dialog to tell the user which host to add
   * from the Android and iOS clients.
   *
   * @returns {Promise<{addresses: string[], port: number}>} Resolves to the
   *   distinct non-loopback IPv4 addresses (empty when there is no LAN
   *   interface) and the port the bundled server listens on.
   */
  getLocalIpAddresses: () => ipcRenderer.invoke("get-local-ip-addresses"),

  // --- Window chrome theming ----------------------------------------------

  /**
   * Tints the current BrowserWindow's background colour so the native macOS
   * title bar (which is translucent by default) picks up the theme colour.
   *
   * Called by the Kotlin/JS renderer from `applyAppearanceClass` whenever
   * the theme or appearance changes. The main process resolves which window
   * to paint from the IPC sender.
   *
   * @param {string} color - CSS hex colour like `#002b36` (no alpha).
   * @returns {Promise<void>}
   */
  setWindowBackgroundColor: (color) =>
    ipcRenderer.invoke("set-window-background-color", color),

  // --- App menu → renderer events -----------------------------------------

  /**
   * Subscribes to the "show About dialog" event sent by the main process when
   * the user picks "About Lunamux" from the macOS app menu. The renderer
   * uses this to open its themed in-app About modal instead of Electron's
   * default native panel.
   *
   * Only the channel name is forwarded; the IPC event object itself is not
   * leaked into the renderer for context-isolation safety.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function that detaches the listener.
   */
  onShowAboutDialog: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("show-about-dialog", wrapped);
    return () => ipcRenderer.removeListener("show-about-dialog", wrapped);
  },

  /**
   * Subscribes to the "show settings" event sent by the main process when
   * the user picks "Settings…" (⌘,) from the macOS app menu. The renderer
   * uses this to open its in-app App Settings sidebar.
   *
   * Only the channel name is forwarded; the IPC event object itself is not
   * leaked into the renderer for context-isolation safety.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function that detaches the listener.
   */
  onShowSettings: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("show-settings", wrapped);
    return () => ipcRenderer.removeListener("show-settings", wrapped);
  },

  /**
   * Subscribes to the "show hotkeys" event sent by the main process when the
   * user picks "Keyboard Shortcuts" from the macOS app menu. The renderer
   * uses this to open its in-app Hotkeys sidebar.
   *
   * Only the channel name is forwarded; the IPC event object itself is not
   * leaked into the renderer for context-isolation safety.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function that detaches the listener.
   */
  onShowHotkeys: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("show-hotkeys", wrapped);
    return () => ipcRenderer.removeListener("show-hotkeys", wrapped);
  },

  /**
   * Subscribes to the "web pane disengage" event sent by the main process
   * when the 3D world's disengage chord (⌥⌘X) is pressed while a web-pane
   * `<webview>` guest holds keyboard focus. Because guest keydowns never
   * reach the host window's key handler, the main process intercepts the
   * chord via `before-input-event` and forwards it here so the renderer can
   * blur the guest and leave engage mode.
   *
   * Only the channel name is forwarded; the IPC event object itself is not
   * leaked into the renderer for context-isolation safety.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function that detaches the listener.
   */
  onWebPaneDisengage: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("web-pane-disengage", wrapped);
    return () => ipcRenderer.removeListener("web-pane-disengage", wrapped);
  },

  /**
   * Subscribes to the "debug set pane state" event sent by the main
   * process when the user picks "Pane state: Working/Waiting/Clear"
   * from the macOS Debug submenu. The handler receives the requested
   * mode (`"working"`, `"waiting"`, `"auto"`).
   *
   * @param {(mode: string) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onDebugSetPaneState: (handler) => {
    const wrapped = (_event, mode) => handler(mode);
    ipcRenderer.on("debug-set-pane-state", wrapped);
    return () => ipcRenderer.removeListener("debug-set-pane-state", wrapped);
  },

  // --- Quit confirmation ---------------------------------------------------

  /**
   * Subscribes to the "show quit confirmation" event sent by the main
   * process when the user triggers any quit intent (Cmd-Q, menu Quit,
   * window close button). The renderer must respond by calling
   * `respondQuitConfirmation` with the user's choice; the main process
   * waits for that response before quitting.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function.
   */
  onShowQuitConfirmation: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("show-quit-confirmation", wrapped);
    return () => ipcRenderer.removeListener("show-quit-confirmation", wrapped);
  },

  /**
   * Sends the user's choice from the quit-confirmation modal back to the
   * Electron main process. The main process awaits this call; until it
   * arrives, the quit is held with `event.preventDefault()`.
   *
   * @param {{confirmed: boolean, killServer: boolean}} result
   *   `confirmed=false` cancels the quit and keeps the app open.
   *   `confirmed=true, killServer=false` quits Electron only (server
   *   keeps running). `confirmed=true, killServer=true` asks the
   *   server to gracefully shut down before the app quits.
   * @returns {Promise<void>}
   */
  respondQuitConfirmation: (result) =>
    ipcRenderer.invoke("quit-confirmation-result", result),

  /**
   * Subscribes to the "quit cancelled" event the main process sends when a
   * confirmed kill-server quit is abandoned (the server shutdown failed and
   * the user chose Cancel in the native dialog). The renderer suppressed its
   * "Connection lost" modal when it confirmed that quit; on this event it
   * must re-arm the modal, since the app keeps running.
   *
   * @param {() => void} handler - Called with no arguments on each event.
   * @returns {() => void} Unsubscribe function.
   */
  onQuitCancelled: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("quit-cancelled", wrapped);
    return () => ipcRenderer.removeListener("quit-cancelled", wrapped);
  },

  // --- macOS native fullscreen state ---------------------------------------

  /**
   * Subscribes to native macOS fullscreen state changes on the current
   * BrowserWindow. The handler receives `true` on `enter-full-screen`,
   * `false` on `leave-full-screen`, and once at boot reflecting the
   * window's initial fullscreen state (macOS may relaunch directly into
   * a restored fullscreen Space).
   *
   * Used by the renderer to toggle the toolkit's `dt-mac-fullscreen`
   * body class via `setDtMacFullscreenBodyClass`, which suppresses the
   * 80 px traffic-light reservation on `.dt-topbar` for the duration of
   * the fullscreen state (the OS hides the traffic-light cluster).
   *
   * @param {(enabled: boolean) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onFullscreenChange: (handler) => {
    const wrapped = (_event, enabled) => handler(enabled === true);
    ipcRenderer.on("fullscreen-changed", wrapped);
    return () => ipcRenderer.removeListener("fullscreen-changed", wrapped);
  },

  // --- Auto-update ---------------------------------------------------------
  // Renderer-driven controls (invoke) + main-process lifecycle subscriptions
  // (on). The channel strings match se.soderbjorn.lunamux.electron.UpdateChannels
  // on the main side. Serviced by AutoUpdater.kt; consumed by the renderer's
  // Updates panel (web/.../AutoUpdaterPanel.kt).

  /**
   * Asks the main process to check the release provider for a newer version.
   * A no-op in dev/unpackaged builds. Results arrive via the `onUpdate*`
   * subscriptions below. Called by the Updates panel on open and by the
   * "Check for Updates…" Help-menu item (see {@link onShowUpdatesPanel}).
   *
   * @returns {Promise<void>}
   */
  checkForUpdates: () => ipcRenderer.invoke("update:check"),

  /**
   * Asks the main process to download the available update. Progress and
   * completion arrive via {@link onUpdateProgress} / {@link onUpdateDownloaded}.
   * Called by the Updates panel's "Download" button.
   *
   * @returns {Promise<void>}
   */
  downloadUpdate: () => ipcRenderer.invoke("update:download"),

  /**
   * Asks the main process to quit, install the downloaded update, and
   * relaunch. Only meaningful after {@link onUpdateDownloaded} has fired.
   * Called by the Updates panel's "Restart to install" button.
   *
   * @returns {Promise<void>}
   */
  quitAndInstall: () => ipcRenderer.invoke("update:quit-and-install"),

  /**
   * Subscribes to the "checking for updates" lifecycle event.
   *
   * @param {() => void} handler - Called with no arguments.
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateChecking: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("update:checking", wrapped);
    return () => ipcRenderer.removeListener("update:checking", wrapped);
  },

  /**
   * Subscribes to the "update available" event.
   *
   * @param {(info: { version?: string }) => void} handler - Receives the
   *   available version info. The IPC event object is not leaked.
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateAvailable: (handler) => {
    const wrapped = (_event, info) => handler(info || {});
    ipcRenderer.on("update:available", wrapped);
    return () => ipcRenderer.removeListener("update:available", wrapped);
  },

  /**
   * Subscribes to the "no update available" event (app is up to date).
   *
   * @param {() => void} handler - Called with no arguments.
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateNotAvailable: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("update:not-available", wrapped);
    return () => ipcRenderer.removeListener("update:not-available", wrapped);
  },

  /**
   * Subscribes to download-progress events during an update download.
   *
   * @param {(progress: { percent?: number, transferred?: number, total?: number, bytesPerSecond?: number }) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateProgress: (handler) => {
    const wrapped = (_event, progress) => handler(progress || {});
    ipcRenderer.on("update:progress", wrapped);
    return () => ipcRenderer.removeListener("update:progress", wrapped);
  },

  /**
   * Subscribes to the "update downloaded / ready to install" event.
   *
   * @param {(info: { version?: string }) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateDownloaded: (handler) => {
    const wrapped = (_event, info) => handler(info || {});
    ipcRenderer.on("update:downloaded", wrapped);
    return () => ipcRenderer.removeListener("update:downloaded", wrapped);
  },

  /**
   * Subscribes to update error events (e.g. the download failed).
   *
   * @param {(error: { message?: string }) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onUpdateError: (handler) => {
    const wrapped = (_event, error) => handler(error || {});
    ipcRenderer.on("update:error", wrapped);
    return () => ipcRenderer.removeListener("update:error", wrapped);
  },

  /**
   * Subscribes to the request the main process sends when the user picks
   * "Check for Updates…" from the Help menu. The renderer responds by running a
   * user-initiated update check, whose result shows in the sidebar-footer update
   * banner. (Channel name is historical — it no longer opens a panel.)
   *
   * @param {() => void} handler - Called with no arguments.
   * @returns {() => void} Unsubscribe function.
   */
  onShowUpdatesPanel: (handler) => {
    const wrapped = () => handler();
    ipcRenderer.on("show-updates-panel", wrapped);
    return () => ipcRenderer.removeListener("show-updates-panel", wrapped);
  },
});

/**
 * Cross-app `darknessApi` namespace — shared by every Darkness app
 * (Lunamux, notegrow, ...) so the darkness-toolkit's renderer code can
 * speak to whichever Electron host is hosting it without app-specific
 * branching. Currently only carries the custom-titlebar toggle; the
 * toolkit's `AppShellMount` subscriber invokes it whenever the persisted
 * `ThemeSnapshot.useCustomTitleBar` changes.
 */
contextBridge.exposeInMainWorld("darknessApi", {
  /**
   * Boot-time custom-titlebar flag from the main process's
   * `electron-chrome.json` cache. Consumed by darkness-toolkit's
   * `autoApplyCustomTitleBarBodyClass` to set `dt-custom-titlebar`
   * synchronously, before server-backed settings hydrate.
   *
   * @type {boolean}
   */
  customTitleBar: customTitleBarBoot,
  /**
   * Toggles the custom (themed) title bar on the Electron main window.
   *
   * `titleBarStyle` is a creation-time BrowserWindow option in Electron
   * and cannot be mutated on an existing window, so the main process
   * destroys the current window and creates a new one with the requested
   * style. The server keeps all state (PTYs, layout, settings), so the
   * reload is purely visual.
   *
   * The value is cached in `userData/electron-chrome.json` so the next
   * cold start opens the window with the right chrome before the
   * renderer has had a chance to query the server (which remains the
   * canonical store).
   *
   * @param {boolean} enabled `true` to hide the native title bar and
   *   render the themed window chrome, `false` to show the native OS
   *   title bar.
   * @returns {Promise<void>}
   */
  setCustomTitleBar: (enabled) =>
    ipcRenderer.invoke("darkness:setCustomTitleBar", enabled),
  /**
   * Subscribe to macOS native fullscreen state changes from the Electron
   * main process. Mirror of `electronApi.onFullscreenChange` published on
   * the cross-app `darknessApi` namespace so darkness-toolkit's
   * `autoWireMacFullscreenBodyClass` (called from
   * `injectDarknessToolkitStyles`) can pick it up without Lunamux
   * having to manually wire `setDtMacFullscreenBodyClass` in main.kt.
   *
   * Fires once at boot reflecting the window's current state and again
   * on every `enter-full-screen` / `leave-full-screen` BrowserWindow
   * event.
   *
   * @param {(enabled: boolean) => void} handler
   * @returns {() => void} Unsubscribe function.
   */
  onFullscreenChange: (handler) => {
    const wrapped = (_event, enabled) => handler(enabled === true);
    ipcRenderer.on("fullscreen-changed", wrapped);
    return () => ipcRenderer.removeListener("fullscreen-changed", wrapped);
  },
});
