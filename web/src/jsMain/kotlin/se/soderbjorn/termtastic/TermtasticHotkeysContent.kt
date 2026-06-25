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
                add(HotkeyEntry(
                    label = "Previous window",
                    chord = StandardHotkeys.PreviousPane.toChordLabel(),
                ))
                add(HotkeyEntry(
                    label = "Next window",
                    chord = StandardHotkeys.NextPane.toChordLabel(),
                ))
                add(HotkeyEntry(
                    label = "Previous tab",
                    chord = StandardHotkeys.PreviousTab.toChordLabel(),
                ))
                add(HotkeyEntry(
                    label = "Next tab",
                    chord = StandardHotkeys.NextTab.toChordLabel(),
                ))
                // Cmd+1..9 jump straight to a tab by position (Cmd+9 =
                // last tab). Desktop-only (see installTabNumberShortcuts,
                // wired in bootViaToolkitShell), so the cheatsheet only
                // advertises it on the Electron client.
                if (isElectronClient) {
                    add(HotkeyEntry(
                        label = "Switch to tab 1–9 (9 = last)",
                        chord = listOf("⌘", "1…9"),
                    ))
                }
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
