/**
 * Utility functions for navigating and querying the [WindowConfig] pane list.
 *
 * These helpers are used by the per-pane ViewModels ([FileBrowserBackingViewModel],
 * [GitPaneBackingViewModel], [TerminalBackingViewModel]) to locate their own
 * [LeafNode] by pane ID or session ID, and to determine whether a
 * [WindowEnvelope] is addressed to a specific pane.
 *
 * @see se.soderbjorn.termtastic.WindowConfig
 */
package se.soderbjorn.termtastic.client.viewmodel

import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope

/**
 * Find a [LeafNode] by its pane ID across all tabs.
 *
 * @param config the current window layout.
 * @param paneId the unique pane identifier to search for.
 * @return the matching [LeafNode], or `null` if not found.
 */
fun findLeafById(config: WindowConfig, paneId: String): LeafNode? {
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return it.leaf }
    }
    return null
}

/**
 * Find a [LeafNode] whose PTY session ID matches [sessionId] across all tabs.
 *
 * @param config    the current window layout.
 * @param sessionId the PTY session identifier to search for.
 * @return the matching [LeafNode], or `null` if not found.
 */
fun findLeafBySessionId(config: WindowConfig, sessionId: String): LeafNode? {
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
    }
    return null
}

/**
 * Check whether this [WindowEnvelope] is addressed to the given [paneId].
 * Only pane-scoped envelope types (file-browser and git replies/errors) can
 * match; all other envelope types return `false`.
 *
 * @param paneId the pane identifier to test against.
 * @return `true` if this envelope belongs to [paneId].
 */
fun WindowEnvelope.belongsToPane(paneId: String): Boolean = when (this) {
    is WindowEnvelope.FileBrowserDir -> this.paneId == paneId
    is WindowEnvelope.FileBrowserContentMsg -> this.paneId == paneId
    is WindowEnvelope.FileBrowserError -> this.paneId == paneId
    is WindowEnvelope.GitList -> this.paneId == paneId
    is WindowEnvelope.GitDiffResult -> this.paneId == paneId
    is WindowEnvelope.GitError -> this.paneId == paneId
    else -> false
}
