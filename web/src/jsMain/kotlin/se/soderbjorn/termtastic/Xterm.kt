/**
 * Kotlin/JS external declarations for the xterm.js terminal emulator library.
 *
 * Provides type-safe Kotlin bindings for the core [Terminal] class from the
 * "xterm" npm package. The terminal is used throughout the application to
 * render PTY output and capture user input.
 *
 * @see FitAddon
 * @see ensureTerminal
 */
@file:JsModule("xterm")
@file:JsNonModule

package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Kotlin external declaration for xterm.js's Terminal class.
 *
 * Wraps the JavaScript `Terminal` constructor and its key methods.
 * Created and managed by [ensureTerminal] in [LayoutBuilder].
 *
 * @param options optional configuration object (cursorBlink, fontFamily, fontSize, theme, etc.)
 * @see FitAddon
 */
external class Terminal(options: dynamic = definedExternally) {
    val cols: Int
    val rows: Int
    val options: dynamic
    fun open(parent: HTMLElement)
    fun write(data: dynamic)
    fun onData(cb: (String) -> Unit)
    fun onResize(cb: (dynamic) -> Unit)

    /**
     * Registers a listener that fires after a chunk of data has been fully
     * parsed into the terminal's buffer (i.e. the on-screen state may have
     * changed). Used by the 3D overview ([Overview3D]) to repaint a pane's
     * live-content tile and light its activity glow *without* opening a second
     * PTY socket — reading the already-live buffer is size-neutral.
     *
     * @param cb invoked (no args) after each processed write.
     * @return an xterm `IDisposable`; call `.dispose()` to unsubscribe (the
     *   overview does so on close so the live term keeps no overview state).
     */
    fun onWriteParsed(cb: () -> Unit): dynamic
    fun loadAddon(addon: dynamic)
    fun focus()
    fun paste(data: String)
    fun dispose()

    /**
     * Registers a custom key event handler that runs *before* xterm.js's own
     * key processing, letting the host veto or replace the default behaviour.
     *
     * Called by [ensureTerminal] in [LayoutBuilder] to remap Shift+Enter to a
     * line feed (so TUIs read it as "insert newline" rather than "submit").
     *
     * @param handler invoked for each `keydown`/`keyup`/`keypress`; return
     *   `true` to let xterm.js process the event normally, or `false` to
     *   suppress xterm's handling (e.g. so it does not emit `\r` for Enter).
     */
    fun attachCustomKeyEventHandler(handler: (KeyboardEvent) -> Boolean)
}
