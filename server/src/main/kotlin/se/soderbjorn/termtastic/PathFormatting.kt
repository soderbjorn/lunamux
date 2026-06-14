/**
 * Pure path / leaf-title formatting helpers used by [WindowState] when
 * updating leaf titles in response to cwd changes, custom-name changes, and
 * persisted-config rehydrations.
 *
 * Lives at file scope so the helpers can be reused from any module that
 * needs them without dragging in [WindowState]'s mutation surface.
 */
package se.soderbjorn.termtastic

/**
 * Collapse `$HOME` to `~` in [path] for display. Anything else is left intact —
 * shortening to basename is intentionally avoided so users can tell similarly
 * named directories apart at a glance.
 */
internal fun prettifyPath(path: String): String {
    val home = System.getProperty("user.home")
    if (home.isNullOrEmpty()) return path
    return when {
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.substring(home.length)
        else -> path
    }
}

/**
 * Resolve the display title for a pane:
 *  1. user-set [customName] wins;
 *  2. else the prettified [cwd];
 *  3. else [fallback] (typically the auto-generated "Session N" label).
 */
internal fun computeLeafTitle(customName: String?, cwd: String?, fallback: String): String =
    customName?.takeIf { it.isNotBlank() }
        ?: cwd?.takeIf { it.isNotBlank() }?.let(::prettifyPath)
        ?: fallback

/**
 * Returns a deep copy of this config with every [LeafNode.sessionId] blanked.
 * Persisted blobs use this so we never write live PTY ids to disk — they
 * become stale the moment the process exits.
 */
internal fun WindowConfig.withBlankSessionIds(): WindowConfig {
    fun blankContent(c: LeafContent?): LeafContent? = when (c) {
        is TerminalContent -> if (c.sessionId.isEmpty()) c else c.copy(sessionId = "")
        is FileBrowserContent, is GitContent, null -> c
    }
    fun stripLeaf(leaf: LeafNode): LeafNode {
        val newContent = blankContent(leaf.content)
        return if (leaf.sessionId.isEmpty() && newContent === leaf.content) leaf
        else leaf.copy(sessionId = "", content = newContent)
    }
    return copy(
        tabs = tabs.map { tab ->
            tab.copy(
                panes = tab.panes.map { p -> p.copy(leaf = stripLeaf(p.leaf)) },
            )
        }
    )
}
