/**
 * Mutable client-side view-model for a single git pane.
 *
 * Holds the list of changed file entries (raw dynamic from the server),
 * the currently selected file, cached diff HTML, diff mode preferences,
 * and search state. Persists across re-renders via the [gitPaneStates]
 * registry in [PaneStateRegistry].
 *
 * Companion type [GitPaneView] holds references to the live DOM
 * elements; both are populated by [buildGitView].
 *
 * NOTE: Several fields are typed as `dynamic` because the server payload
 * (entries, hunks, line content) is consumed directly by the renderers
 * without a proper Kotlin model. Migrating those to typed properties
 * would touch [WindowConnection] and the renderers and is intentionally
 * out of scope for this pure-split refactor.
 *
 * @see GitPaneView
 * @see buildGitView
 */
package se.soderbjorn.lunamux

import org.w3c.dom.HTMLElement

/**
 * Per-pane git view-model state.
 */
class GitPaneState {
    var entries: dynamic = null
    var selectedFilePath: String? = null
    var diffHtml: String? = null
    var diffMode: String = "Inline"
    var graphicalDiff: Boolean = false
    var diffFontSize: Int = 12
    var searchQuery: String = ""
    var searchMatchIndex: Int = 0
}

/**
 * Holds references to the live DOM elements of a git pane's panels and
 * search controls.
 *
 * @property listBody the scrollable container for the changed files list
 * @property diffPane the container for the rendered diff content
 * @property searchCounter the element displaying "N/M" search match counter
 * @property searchNavButtons navigation buttons for stepping through search matches
 * @property onResize the current renderer's **pixel-dependent relayout**, or `null`
 *   when the active diff mode has none. Invoked whenever the pane's box changes
 *   (via the [resizeObserver], so it fires the same for a 2D splitter drag /
 *   maximize / sidebar toggle, a 3D [resizePaneBox], or a window resize). Set by
 *   [renderGitDiffGraphical] to recompute its SVG connectors at the new size, and
 *   cleared by the flowed inline/split renderers (which reflow via CSS alone).
 * @property resizeObserver the [ResizeObserver] watching the view root for box-size
 *   changes; drives [onResize]. Disconnected when the view is rebuilt or disposed.
 */
class GitPaneView(
    val listBody: HTMLElement,
    val diffPane: HTMLElement,
    var searchCounter: HTMLElement? = null,
    var searchNavButtons: List<HTMLElement> = emptyList(),
    var onResize: (() -> Unit)? = null,
    var resizeObserver: ResizeObserver? = null,
)
