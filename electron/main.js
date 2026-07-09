/* main.js — stub that hands control to the Kotlin/JS Node bundle.
 *
 * The real main-process logic (single-instance lock, embedded server
 * bootstrap, BrowserWindow setup, app menu, IPC handlers, custom
 * title-bar toggle, global summon hotkey, macOS lifecycle handling)
 * lives in the :electron-main Gradle module and is compiled to
 * resources/main/ by `copyMainBundle`. This file just `require()`s
 * the entry bundle so Electron's `main` field still points at a
 * standard CommonJS file. */
require("./resources/main/Lunamux-electron-main.js");
