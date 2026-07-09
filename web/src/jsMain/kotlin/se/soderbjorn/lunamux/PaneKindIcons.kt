/**
 * Centralised SVG glyphs for the four Lunamux pane kinds (terminal,
 * file browser, git, link). Single source of truth so every consumer
 * — `mountPaneContent`, the toolkit's `paneIcon` factory feeding the
 * pane chrome and sidebar tree, the legacy `Sidebar.kt` rows — picks
 * the same drawing.
 *
 * Sized for 14×14 chrome contexts to match the toolkit's stroke-width
 * conventions for sidebar rows and pane headers.
 *
 * @see lunamuxPaneIcon
 */
package se.soderbjorn.lunamux

/** Folder glyph for `fileBrowser` panes. */
const val PANE_ICON_FILE_BROWSER: String =
    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M2 4.5 a0.5 0.5 0 0 1 0.5 -0.5 h3.5 l1.25 1.75 h6.25 a0.5 0.5 0 0 1 0.5 0.5 v7.25 a0.5 0.5 0 0 1 -0.5 0.5 H2.5 a0.5 0.5 0 0 1 -0.5 -0.5 Z"/></svg>"""

/** Branch-with-commits glyph for `git` panes. */
const val PANE_ICON_GIT: String =
    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><circle cx="5" cy="4" r="1.5"/><circle cx="5" cy="12" r="1.5"/><circle cx="11" cy="8" r="1.5"/><line x1="5" y1="5.5" x2="5" y2="10.5"/><path d="M5 5.5 C5 8 8 8 9.5 8"/></svg>"""

/** Chain-link glyph for terminal panes that mirror another session. */
const val PANE_ICON_LINK: String =
    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M6.5 9.5a3 3 0 0 0 4.24 0l2.5-2.5a3 3 0 0 0-4.24-4.24L7.5 3.76"/><path d="M9.5 6.5a3 3 0 0 0-4.24 0l-2.5 2.5a3 3 0 0 0 4.24 4.24l1-1"/></svg>"""

/** Window-with-prompt-arrow glyph for ordinary terminal panes. */
const val PANE_ICON_TERMINAL: String =
    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="1" y="2" width="14" height="12" rx="1.5"/><polyline points="4,7 6,5 4,3"/><line x1="7" y1="7" x2="11" y2="7" stroke-linecap="round"/></svg>"""

/** Robot-head glyph for `agent` console panes (MCP-driven). */
const val PANE_ICON_AGENT: String =
    """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><rect x="3" y="5" width="10" height="8" rx="1.5"/><line x1="8" y1="5" x2="8" y2="2.5"/><circle cx="8" cy="2" r="0.6"/><circle cx="6" cy="8.5" r="0.8"/><circle cx="10" cy="8.5" r="0.8"/><line x1="6.5" y1="11" x2="9.5" y2="11"/></svg>"""

/**
 * Looks up the icon SVG for a given Lunamux leaf, by its dynamic
 * descriptor (the same shape `LayoutBuilder` consumes from the server's
 * `WindowConfig` JSON tree). Returns `null` when [leaf] is null so the
 * toolkit's default-glyph fallback can fire.
 *
 * @param leaf dynamic leaf descriptor with `content.kind` and `isLink`
 *   fields, or `null` when the pane id is no longer in the live config.
 * @return inline-SVG string suitable for [se.soderbjorn.darkness.web.shell.AppShellSpec.paneIcon].
 */
fun lunamuxPaneIcon(leaf: dynamic): String? {
    if (leaf == null) return null
    val isLink = (leaf.isLink as? Boolean) ?: false
    if (isLink) return PANE_ICON_LINK
    return when ((leaf.content?.kind as? String) ?: "terminal") {
        "fileBrowser" -> PANE_ICON_FILE_BROWSER
        "git" -> PANE_ICON_GIT
        "agent" -> PANE_ICON_AGENT
        else -> PANE_ICON_TERMINAL
    }
}
