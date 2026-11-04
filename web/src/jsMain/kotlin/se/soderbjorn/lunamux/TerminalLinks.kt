/**
 * Hyperlink activation for xterm.js terminals.
 *
 * Programs such as Claude Code and Codex print URLs as OSC 8 hyperlinks,
 * which xterm.js underlines and makes clickable. Left to its default
 * handler, xterm.js answers a click with a native `confirm()` and then a
 * bare `window.open()` whose URL it only assigns afterwards. The Electron
 * main window denies every `window.open` that does not carry an http(s)
 * URL (see `setWindowOpenHandler` in ElectronMain.kt), so after the user
 * clicked OK nothing opened.
 *
 * This file holds [terminalLinkHandler], the replacement handler: a
 * Cmd-click (Ctrl-click off macOS) opens the link in the user's default
 * browser through [openExternalUrl], with no dialog, as Terminal.app,
 * iTerm2 and VS Code do. A plain click does nothing, so clicking to place a
 * selection never launches a browser by accident.
 *
 * @see openExternalUrl
 * @see ensureTerminal
 */
package se.soderbjorn.lunamux

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunula.web.hotkey.isMacPlatform
import kotlin.js.json

/**
 * Whether [event] carries the platform's "follow link" modifier: Cmd on
 * macOS, Ctrl everywhere else.
 *
 * Called by [terminalLinkHandler]'s `activate` callback to tell a deliberate
 * link click from an ordinary click into the terminal.
 *
 * @param event the click xterm.js reports on a link.
 * @return `true` when the click should open the link.
 */
private fun isFollowLinkClick(event: MouseEvent): Boolean =
    if (isMacPlatform()) event.metaKey else event.ctrlKey

/**
 * Builds the xterm.js `linkHandler` option shared by every on-screen
 * terminal.
 *
 * Passed as the `linkHandler` option by [ensureTerminal] (every interactive
 * pane) and by the 3D world's mirror terminals in World3DSpikeRing.kt, so no
 * terminal Lunamux shows the user falls back to xterm.js's native confirm.
 *
 * - `activate` opens the URI through [openExternalUrl] on a Cmd-click
 *   (Ctrl-click off macOS) and ignores any other click.
 * - `hover` / `leave` set and clear a tooltip on the terminal element
 *   naming the modifier and the target, so the user can see where a link
 *   goes before following it (OSC 8 link text need not match its URI).
 * - `allowNonHttpProtocols` stays `false`, so xterm.js only offers http and
 *   https links and anything else (`file:`, custom schemes) never reaches
 *   `activate`.
 *
 * @return a fresh `ILinkHandler` object for the xterm.js `Terminal` options.
 * @see isFollowLinkClick
 */
internal fun terminalLinkHandler(): dynamic {
    val modifierLabel = if (isMacPlatform()) "⌘" else "Ctrl"
    return json(
        "activate" to { event: MouseEvent, uri: String, _: dynamic ->
            if (isFollowLinkClick(event)) {
                event.preventDefault()
                openExternalUrl(uri)
            }
        },
        "hover" to { event: MouseEvent, uri: String, _: dynamic ->
            terminalElementOf(event)?.setAttribute("title", "$modifierLabel-click to open $uri")
        },
        "leave" to { event: MouseEvent, _: String, _: dynamic ->
            terminalElementOf(event)?.removeAttribute("title")
        },
        "allowNonHttpProtocols" to false,
    )
}

/**
 * Finds the `.xterm` root element a link event happened in, which is where
 * [terminalLinkHandler] hangs its tooltip.
 *
 * @param event a hover or leave event xterm.js reports on a link.
 * @return the terminal's root element, or `null` when the event target is
 *   not inside one.
 */
private fun terminalElementOf(event: MouseEvent): HTMLElement? =
    (event.target as? HTMLElement)?.closest(".xterm") as? HTMLElement
