/**
 * Pure path / leaf-title formatting helpers used by [WindowState] when
 * updating leaf titles in response to cwd changes, custom-name changes, and
 * persisted-config rehydrations.
 *
 * Lives at file scope so the helpers can be reused from any module that
 * needs them without dragging in [WindowState]'s mutation surface.
 */
package se.soderbjorn.lunamux

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
 *  1. user-set [customName] wins (a manual rename is never overridden);
 *  2. else the program-set [programTitle] (OSC 0/2, see `applyProgramTitle`);
 *  3. else the prettified [cwd];
 *  4. else [fallback] (typically the auto-generated "Session N" label).
 *
 * Called by [PaneManager]'s title-affecting mutations and [WindowState] pane
 * creation.
 *
 * @param customName the user's manual name, or `null`.
 * @param programTitle the sanitized program-set title, or `null`.
 * @param cwd the last known working directory, or `null`.
 * @param fallback the last-resort label when nothing else is set.
 * @return the resolved display title.
 */
internal fun computeLeafTitle(
    customName: String?,
    programTitle: String?,
    cwd: String?,
    fallback: String,
): String =
    customName?.takeIf { it.isNotBlank() }
        ?: programTitle?.takeIf { it.isNotBlank() }
        ?: cwd?.takeIf { it.isNotBlank() }?.let(::prettifyPath)
        ?: fallback

/**
 * Recompute the display title for an existing [leaf] from its own naming
 * fields. Convenience overload used by [PaneManager]'s mutation helpers: after
 * changing one of `customName` / `programTitle` / `cwd` on a copied leaf, call
 * this to derive the matching [LeafNode.title] without threading every title
 * input through by hand.
 *
 * @param leaf the (already-updated) leaf whose title to compute.
 * @param fallback the last-resort label; defaults to the leaf's current title,
 *   which preserves the previously-resolved name when nothing else is set.
 * @return the resolved display title.
 */
internal fun computeLeafTitle(leaf: LeafNode, fallback: String = leaf.title): String =
    computeLeafTitle(leaf.customName, leaf.programTitle, leaf.cwd, fallback)

/**
 * Normalize a raw program-set terminal title (OSC 0/2 payload) into a clean,
 * bounded pane title. Called by [PaneManager.applyProgramTitle] before the
 * title is stored on [LeafNode.programTitle].
 *
 * The input is untrusted (any program in the PTY — including a remote host
 * over ssh — can emit it) and the result is persisted and broadcast to every
 * client, so this strips control characters (C0, C1, and invisible Unicode
 * format characters such as bidi overrides), box-drawing characters, and
 * HTML-significant characters (defense-in-depth; the toolkit renders titles
 * via `textContent`), collapses whitespace, and caps the length without
 * splitting a surrogate pair. Everything else is kept verbatim, matching how
 * other terminals display program titles.
 *
 * @param raw the untrusted OSC title payload.
 * @return a cleaned title, or `null` when nothing usable remains (an empty
 *   title is the standard way for a program to reset the title).
 */
internal fun sanitizeProgramTitle(raw: String): String? {
    val firstLine = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
    var s = firstLine
        .replace(BOX_AND_CONTROL, " ")
        .replace(HTML_UNSAFE, "")
        .trim()
    s = s.split(WHITESPACE).filter { it.isNotBlank() }.joinToString(" ")
    if (s.length > MAX_TITLE_CHARS) {
        var cut = MAX_TITLE_CHARS
        // Don't split a surrogate pair — a dangling high surrogate would
        // serialize as U+FFFD on the wire and in the persisted blob.
        if (s[cut - 1].isHighSurrogate()) cut--
        s = s.take(cut).trim()
    }
    return s.ifEmpty { null }
}

/** Length cap for program-set titles — long enough for a task summary, short enough for a tab. */
private const val MAX_TITLE_CHARS = 80
private val WHITESPACE = Regex("""\s+""")
/**
 * Characters replaced with a space: C0 controls (`\p{Cntrl}`), C1 controls
 * (not covered by the POSIX class), invisible Unicode format characters
 * (`\p{Cf}` — zero-widths, bidi overrides that could visually spoof a title),
 * and box-drawing glyphs.
 */
private val BOX_AND_CONTROL = Regex("""[\p{Cntrl}\p{Cf}\u0080-\u009F─-╿]+""")
/** HTML-significant chars stripped so a program-controlled title can't inject markup. */
private val HTML_UNSAFE = Regex("""[<>&"'`\\]""")

/**
 * Returns a deep copy of this config with every [LeafNode.sessionId] blanked.
 * Persisted blobs use this so we never write live PTY ids to disk — they
 * become stale the moment the process exits.
 */
internal fun WindowConfig.withBlankSessionIds(): WindowConfig {
    fun blankContent(c: LeafContent?): LeafContent? = when (c) {
        is TerminalContent -> if (c.sessionId.isEmpty()) c else c.copy(sessionId = "")
        // Agent panes are ephemeral and dropped on rehydrate anyway; their
        // content carries no session id to blank.
        is FileBrowserContent, is GitContent, is AgentContent, null -> c
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
