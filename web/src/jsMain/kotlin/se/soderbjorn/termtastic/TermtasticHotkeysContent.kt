/* TermtasticHotkeysContent.kt (jsMain)
 *
 * Curated keyboard cheatsheet content for termtastic. Built into a
 * [HotkeysModalSpec] consumed by the toolkit's [ToolkitHotkeysModal].
 *
 * Termtastic doesn't have notegrow's outline / editor chord set, so
 * the list is short: pane / tab navigation (the four toolkit-supplied
 * `StandardHotkeys`) plus the conventional dialog dismiss / confirm
 * pair. The cheatsheet itself is opened with `Cmd/Ctrl+/` (registered
 * in [bootViaToolkitShell] via `installCheatsheetHotkey`).
 *
 * When a new chord is wired anywhere in termtastic's web frontend,
 * add the corresponding entry here so the cheatsheet stays accurate.
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.hotkey.HotkeyEntry
import se.soderbjorn.darkness.web.hotkey.HotkeyGroup
import se.soderbjorn.darkness.web.hotkey.HotkeysModalSpec
import se.soderbjorn.darkness.web.hotkey.StandardHotkeys
import se.soderbjorn.darkness.web.hotkey.toChordLabel

/** Build termtastic's cheatsheet spec. Pure — called once at boot. */
internal fun termtasticHotkeysSpec(): HotkeysModalSpec = HotkeysModalSpec(
    groups = listOf(
        HotkeyGroup(
            title = "Windows & tabs",
            entries = buildList {
                // "New tab" (⌘T) is wired through the Electron app menu only
                // (see ElectronMain.buildAppMenu / main.kt onNewTab), so it is
                // listed solely when running inside the bundled app — a plain
                // browser tab has no such binding (⌘T is the browser's own).
                if (isElectronClient) {
                    add(
                        HotkeyEntry(
                            label = "New tab",
                            chord = listOf(if (isMacUserAgent()) "⌘" else "Ctrl", "T"),
                        ),
                    )
                    add(
                        HotkeyEntry(
                            label = "New terminal",
                            chord = listOf(if (isMacUserAgent()) "⌘" else "Ctrl", "D"),
                        ),
                    )
                    // In the app, Ctrl+Option+arrows move focus by DIRECTION —
                    // see PaneNavigation.kt. These override the toolkit's
                    // Previous/Next-window cycle on the same chord, so the
                    // directional bindings are listed here in place of the
                    // cycle. The equivalent Ctrl+Option+H/J/K/L vim bindings
                    // also work but are intentionally left out of the
                    // cheatsheet as a power-user feature.
                    val ctrl = if (isMacUserAgent()) "⌃" else "Ctrl"
                    val opt = if (isMacUserAgent()) "⌥" else "Alt"
                    add(HotkeyEntry(label = "Focus pane left", chord = listOf(ctrl, opt, "←")))
                    add(HotkeyEntry(label = "Focus pane down", chord = listOf(ctrl, opt, "↓")))
                    add(HotkeyEntry(label = "Focus pane up", chord = listOf(ctrl, opt, "↑")))
                    add(HotkeyEntry(label = "Focus pane right", chord = listOf(ctrl, opt, "→")))
                } else {
                    // Plain browser build: the toolkit's linear pane cycle is
                    // still active (the directional override is app-only), so
                    // show it.
                    add(
                        HotkeyEntry(
                            label = "Previous window",
                            chord = StandardHotkeys.PreviousPane.toChordLabel(),
                        ),
                    )
                    add(
                        HotkeyEntry(
                            label = "Next window",
                            chord = StandardHotkeys.NextPane.toChordLabel(),
                        ),
                    )
                }
                add(
                    HotkeyEntry(
                        label = "Previous tab",
                        chord = StandardHotkeys.PreviousTab.toChordLabel(),
                    ),
                )
                add(
                    HotkeyEntry(
                        label = "Next tab",
                        chord = StandardHotkeys.NextTab.toChordLabel(),
                    ),
                )
            },
        ),
        HotkeyGroup(
            title = "Dialogs",
            entries = listOf(
                HotkeyEntry(label = "Confirm / submit", chord = listOf("⏎")),
                HotkeyEntry(label = "Dismiss / cancel", chord = listOf("Esc")),
            ),
        ),
        HotkeyGroup(
            title = "App",
            entries = listOfNotNull(
                HotkeyEntry(
                    label = "Show this hotkeys cheatsheet",
                    chord = run {
                        val isMac: Boolean = run {
                            val ua = js(
                                "(typeof navigator !== 'undefined' && navigator.userAgent) || ''",
                            ) as String
                            ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
                        }
                        listOf(if (isMac) "⌘" else "Ctrl", "/")
                    },
                ),
                // Global Quake-style show/hide toggle — registered by the
                // Electron main process (ElectronMain.QUAKE_ACCELERATOR), so it
                // exists only in the bundled app (also bound to
                // Ctrl+Alt+Cmd+Space). Listed for discoverability.
                if (isElectronClient) {
                    HotkeyEntry(
                        label = "Show / hide Termtastic",
                        chord = listOf(if (isMacUserAgent()) "⌃" else "Ctrl", "`"),
                    )
                } else {
                    null
                },
            ),
        ),
    ),
    footerNote = "Window and tab chords work even when a terminal is focused.",
)

/**
 * True when the running browser/Electron user-agent looks like macOS (or
 * iOS). Used to pick the ⌘ vs Ctrl glyph for the "New tab" cheatsheet
 * entry. Mirrors the inline check the "App" group uses for the cheatsheet
 * chord itself.
 */
private fun isMacUserAgent(): Boolean {
    val ua = js(
        "(typeof navigator !== 'undefined' && navigator.userAgent) || ''",
    ) as String
    return ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
}
