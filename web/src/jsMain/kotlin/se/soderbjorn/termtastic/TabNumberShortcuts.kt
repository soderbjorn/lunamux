/* TabNumberShortcuts.kt (jsMain)
 *
 * Wires the `Cmd+1` … `Cmd+9` keyboard shortcuts that jump straight to
 * a tab by position. `Cmd+1`–`Cmd+8` activate the 1st–8th tab; `Cmd+9`
 * activates the last tab (the Safari / Chrome / iTerm convention).
 *
 * The pure index mapping lives in commonMain
 * ([se.soderbjorn.termtastic.client.resolveTabSwitchIndex], unit-tested
 * there); this file only owns the DOM event side — matching the chord,
 * resolving it against the live [latestWindowConfig], and firing the
 * `SetActiveTab` command.
 *
 * Installed from [bootViaToolkitShell] and gated to the Electron
 * desktop client: in a real browser `Cmd+<digit>` is reserved for
 * switching browser tabs and can't be reliably overridden, so we don't
 * try to fight it there.
 */
package se.soderbjorn.termtastic

import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.termtastic.client.resolveTabSwitchIndex

/**
 * Installs the window-level `Cmd+<digit>` tab-switch key handler.
 *
 * Listens at the capture phase so the chord is consumed before xterm's
 * own textarea handler sees it. Only plain `Cmd+<digit>` (no Ctrl / Alt
 * / Shift) is handled; any other modifier combination is left untouched
 * so existing chords aren't shadowed. A matching press that resolves to
 * a real tab fires [WindowCommand.SetActiveTab] via [launchCmd] and
 * stops further propagation / default handling; a press that maps to no
 * tab (e.g. `Cmd+5` with three tabs) is left to fall through as a
 * harmless no-op.
 *
 * Called once at boot from [bootViaToolkitShell]; should only be invoked
 * for the Electron client (see file header).
 */
internal fun installTabNumberShortcuts() {
    val handler: (Event) -> Unit = handler@{ ev ->
        val k = ev as KeyboardEvent
        // Plain Cmd only — bail on any other modifier combination.
        if (!k.metaKey || k.ctrlKey || k.altKey || k.shiftKey) return@handler
        // Match physical top-row digit keys ("Digit1".."Digit9"), so the
        // chord is layout-independent and unaffected by what character the
        // key would otherwise produce.
        val code = k.code
        if (code.length != 6 || !code.startsWith("Digit")) return@handler
        val digit = code.substring(5).toIntOrNull() ?: return@handler
        if (digit < 1) return@handler

        val config = latestWindowConfig ?: return@handler
        val index = resolveTabSwitchIndex(digit = digit, tabCount = config.tabs.size) ?: return@handler

        k.preventDefault()
        k.stopImmediatePropagation()
        launchCmd(WindowCommand.SetActiveTab(tabId = config.tabs[index].id))
    }
    window.addEventListener("keydown", handler, true)
}
