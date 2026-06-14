/**
 * Kotlin/JS external declarations for the xterm.js fit addon.
 *
 * The fit addon automatically calculates the optimal terminal dimensions
 * (cols x rows) to fill its container element. Used by [safeFit] and
 * [fitPreservingScroll] to resize terminals when their containers change size.
 *
 * @see Terminal
 * @see safeFit
 */
@file:JsModule("xterm-addon-fit")
@file:JsNonModule

package se.soderbjorn.termtastic

/**
 * Kotlin external declaration for xterm.js's FitAddon class.
 *
 * Loaded into a [Terminal] via `term.loadAddon(fit)`. Provides `fit()` to
 * resize the terminal to fill its container, and `proposeDimensions()` (accessed
 * via dynamic) to calculate target dimensions without applying them.
 */
external class FitAddon {
    fun fit()
}
