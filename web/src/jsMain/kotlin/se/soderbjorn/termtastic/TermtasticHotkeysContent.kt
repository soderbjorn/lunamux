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
                }
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
            entries = listOf(
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
