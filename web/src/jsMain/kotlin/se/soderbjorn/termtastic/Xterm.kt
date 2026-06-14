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
    fun loadAddon(addon: dynamic)
    fun focus()
    fun paste(data: String)
    fun dispose()
}
