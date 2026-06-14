/**
 * Kotlin/JS external declaration for importing the xterm.js CSS stylesheet.
 *
 * This file triggers the webpack CSS loader to bundle xterm's styles into the
 * application. The [xtermCss] value is referenced in [main] to ensure the import
 * is not tree-shaken away by the bundler.
 *
 * @see main
 */
@file:JsModule("xterm/css/xterm.css")
@file:JsNonModule

package se.soderbjorn.termtastic

/**
 * External reference to the xterm.js CSS module.
 *
 * The value itself is unused; importing it causes the webpack CSS loader to
 * inject the xterm stylesheet into the page. Referenced as `val css = xtermCss`
 * in [main] to prevent dead-code elimination.
 */
external val xtermCss: dynamic
