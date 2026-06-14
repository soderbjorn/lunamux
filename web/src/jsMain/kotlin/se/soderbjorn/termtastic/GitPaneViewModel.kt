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
package se.soderbjorn.termtastic

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
 */
class GitPaneView(
    val listBody: HTMLElement,
    val diffPane: HTMLElement,
    var searchCounter: HTMLElement? = null,
    var searchNavButtons: List<HTMLElement> = emptyList(),
)
