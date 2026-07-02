/**
 * Shared helper functions for pane-level layout mutations (adding panes/tabs,
 * finding leaves) that are used by both platform UI code and ViewModels.
 *
 * These functions operate on [WindowConfig] snapshots and send [WindowCommand]s
 * through a [WindowSocket] to let the server apply the canonical layout change.
 *
 * @see WindowSocket
 * @see se.soderbjorn.termtastic.WindowCommand
 */
package se.soderbjorn.termtastic.client

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig

/**
 * Walk [config] and return every leaf ID across all tabs. Used by the sidebar
 * to determine which PTY sockets need to be open.
 *
 * @param config the current window layout snapshot, or `null`.
 * @return a set of all leaf pane IDs, or an empty set if [config] is `null`.
 */
fun collectLeafIds(config: WindowConfig?): Set<String> {
    if (config == null) return emptySet()
    val out = HashSet<String>()
    for (tab in config.tabs) {
        tab.panes.forEach { out.add(it.leaf.id) }
    }
    return out
}

/**
 * Return the pane ID for the leaf whose session ID matches [sessionId], or
 * `null` if no such leaf exists.
 *
 * @param config    the current window layout, or `null` (returns `null`).
 * @param sessionId the PTY session identifier to look up.
 * @return the matching pane ID, or `null`.
 */
fun findPaneIdBySession(config: WindowConfig?, sessionId: String): String? {
    if (config == null || sessionId.isEmpty()) return null
    for (tab in config.tabs) {
        tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf.id }
    }
    return null
}

/**
 * Return the tab ID that currently holds [paneId], or `null` if the pane
 * isn't found.
 *
 * @param config the current window layout, or `null`.
 * @param paneId the pane ID to locate.
 * @return the owning tab ID, or `null`.
 */
fun findTabIdOfPane(config: WindowConfig?, paneId: String): String? {
    if (config == null || paneId.isEmpty()) return null
    for (tab in config.tabs) {
        if (tab.panes.any { it.leaf.id == paneId }) return tab.id
    }
    return null
}

/**
 * Resolve the directory a newly-created pane in [config]'s [tabId] should
 * inherit. Reads `LeafNode.cwd` directly from the typed snapshot — the
 * same value that paints the title bars the user sees on screen — so
 * the new pane lands where the user expects without the server having
 * to re-derive it from focus state.
 *
 * Preference order:
 *  1. The pane the user clicked next to ([anchorPaneId]).
 *  2. The tab's currently-focused pane.
 *  3. Any pane in the tab.
 *  4. Any pane in any tab.
 *
 * @return the resolved cwd, or `null` if no pane has a known cwd yet
 *   (the server falls back to its own working directory for
 *   non-terminal panes, and to `$HOME` for terminals).
 */
fun resolveCwdForNewPane(
    config: WindowConfig?,
    tabId: String,
    anchorPaneId: String? = null,
): String? {
    if (config == null) return null
    val tab = config.tabs.firstOrNull { it.id == tabId }
    if (tab != null) {
        anchorPaneId
            ?.let { id -> tab.panes.firstOrNull { it.leaf.id == id }?.leaf?.cwd }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        tab.focusedPaneId
            ?.let { id -> tab.panes.firstOrNull { it.leaf.id == id }?.leaf?.cwd }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        tab.panes
            .firstNotNullOfOrNull { it.leaf.cwd?.takeIf { c -> c.isNotBlank() } }
            ?.let { return it }
    }
    return config.tabs.firstNotNullOfOrNull { t ->
        t.panes.firstNotNullOfOrNull { it.leaf.cwd?.takeIf { c -> c.isNotBlank() } }
    }
}

/**
 * Add a new terminal pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddPaneToTab] to the server via [socket]. No-op if
 * the anchor pane can't be located.
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot used to resolve
 *                       the anchor pane's tab id.
 */
suspend fun addSiblingTerminal(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    val cwd = resolveCwdForNewPane(config, tabId, anchorPaneId)
    socket.send(WindowCommand.AddPaneToTab(tabId = tabId, cwd = cwd))
}

/**
 * Add a new file-browser pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddFileBrowserToTab] to the server via [socket].
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot.
 */
suspend fun addSiblingFileBrowser(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    val cwd = resolveCwdForNewPane(config, tabId, anchorPaneId)
    socket.send(WindowCommand.AddFileBrowserToTab(tabId = tabId, cwd = cwd))
}

/**
 * Add a new git pane to the tab that currently holds [anchorPaneId].
 * Sends a [WindowCommand.AddGitToTab] to the server via [socket].
 *
 * @param socket         the live window WebSocket connection.
 * @param anchorPaneId   the pane ID whose tab should receive the new pane.
 * @param config         the current window layout snapshot.
 */
suspend fun addSiblingGit(
    socket: WindowSocket,
    anchorPaneId: String,
    config: WindowConfig?,
) {
    val tabId = findTabIdOfPane(config, anchorPaneId) ?: return
    val cwd = resolveCwdForNewPane(config, tabId, anchorPaneId)
    socket.send(WindowCommand.AddGitToTab(tabId = tabId, cwd = cwd))
}

/**
 * Rename the pane [paneId] to [title], setting its user-visible custom name.
 * Sends a [WindowCommand.Rename]; an empty [title] clears the custom name so
 * the pane falls back to its cwd-derived title. Used by the mobile tree
 * screens and the web pane-header rename flow.
 *
 * @param socket the live window WebSocket connection.
 * @param paneId the pane to rename.
 * @param title  the new custom name; blank clears the override.
 */
suspend fun renamePane(socket: WindowSocket, paneId: String, title: String) {
    socket.send(WindowCommand.Rename(paneId = paneId, title = title))
}

/**
 * Close the pane [paneId], terminating its session. Sends a
 * [WindowCommand.Close]; the server no-ops if the pane no longer exists.
 *
 * @param socket the live window WebSocket connection.
 * @param paneId the pane to close.
 */
suspend fun closePane(socket: WindowSocket, paneId: String) {
    socket.send(WindowCommand.Close(paneId = paneId))
}

/**
 * Rename the tab [tabId] to [title]. Sends a [WindowCommand.RenameTab]; the
 * server rejects blank titles and trims to 80 characters.
 *
 * @param socket the live window WebSocket connection.
 * @param tabId  the tab to rename.
 * @param title  the new tab title; must not be blank.
 */
suspend fun renameTab(socket: WindowSocket, tabId: String, title: String) {
    socket.send(WindowCommand.RenameTab(tabId = tabId, title = title))
}

/**
 * Close the tab [tabId] and all panes inside it. Sends a
 * [WindowCommand.CloseTab]; the server refuses to close the last remaining
 * tab, so callers should disable the affordance when only one tab exists.
 *
 * @param socket the live window WebSocket connection.
 * @param tabId  the tab to close.
 */
suspend fun closeTab(socket: WindowSocket, tabId: String) {
    socket.send(WindowCommand.CloseTab(tabId = tabId))
}

/**
 * Hide or reveal the tab [tabId] in the tab strip. Sends a
 * [WindowCommand.SetTabHidden]; the server no-ops when the flag is unchanged.
 * A strip-hidden ("unlisted") tab stays reachable through the overview tab
 * strip's trailing "…" menu. Used by the mobile tab context menus'
 * "Hide in tab bar" / "Show in tab bar" items.
 *
 * @param socket the live window WebSocket connection.
 * @param tabId  the tab to hide or reveal.
 * @param hidden `true` to hide the tab from the strip, `false` to reveal it.
 * @see setTabHiddenFromSidebar
 */
suspend fun setTabHidden(socket: WindowSocket, tabId: String, hidden: Boolean) {
    socket.send(WindowCommand.SetTabHidden(tabId = tabId, hidden = hidden))
}

/**
 * Hide or reveal the tab [tabId] in the sidebar tab tree (and the mobile
 * session list, which mirrors it). Sends a
 * [WindowCommand.SetTabHiddenFromSidebar]; the server no-ops when the flag is
 * unchanged. Orthogonal to the tab-strip flag ([setTabHidden]). Used by the
 * mobile tab context menus' "Hide in side bar" / "Show in side bar" items.
 *
 * @param socket the live window WebSocket connection.
 * @param tabId  the tab to hide or reveal.
 * @param hidden `true` to hide the tab from the sidebar, `false` to reveal it.
 * @see setTabHidden
 */
suspend fun setTabHiddenFromSidebar(socket: WindowSocket, tabId: String, hidden: Boolean) {
    socket.send(WindowCommand.SetTabHiddenFromSidebar(tabId = tabId, hidden = hidden))
}

/**
 * Add a new pane of [kindWire] directly to the tab [tabId] (rather than next
 * to a specific anchor pane). Resolves the new pane's starting directory from
 * the tab's focused pane via [resolveCwdForNewPane]. Used by the mobile tab
 * header menus ("New Terminal" / "New File Browser" / "New Git" in this tab).
 *
 * @param socket   the live window WebSocket connection.
 * @param tabId    the tab that should receive the new pane.
 * @param kindWire pane kind on the wire: "terminal", "fileBrowser", or "git";
 *   anything else falls back to "terminal".
 * @param config   the current window layout snapshot used to resolve the cwd.
 */
suspend fun addPaneToTab(
    socket: WindowSocket,
    tabId: String,
    kindWire: String,
    config: WindowConfig?,
) {
    val cwd = resolveCwdForNewPane(config, tabId)
    val command = when (kindWire) {
        "fileBrowser" -> WindowCommand.AddFileBrowserToTab(tabId = tabId, cwd = cwd)
        "git" -> WindowCommand.AddGitToTab(tabId = tabId, cwd = cwd)
        else -> WindowCommand.AddPaneToTab(tabId = tabId, cwd = cwd)
    }
    socket.send(command)
}

/**
 * Create a new tab, optionally renaming it to [name]. The server always
 * creates a fresh terminal pane inside the new tab. Returns `true` on
 * success, `false` if the server did not acknowledge within [timeoutMs].
 *
 * Waits for a [WindowConfig] update that contains a tab ID not present before
 * the command was sent, then optionally renames it.
 *
 * @param client    the [TermtasticClient] whose [WindowStateRepository] is
 *   observed for the new tab.
 * @param socket    the live window WebSocket used to send the command.
 * @param name      desired tab title; blank means keep the server default.
 * @param timeoutMs maximum time in milliseconds to wait for the server to
 *   push the updated config.
 * @return `true` if the tab was created (and renamed if requested), `false`
 *   on timeout.
 */
suspend fun addTab(
    client: TermtasticClient,
    socket: WindowSocket,
    name: String,
    timeoutMs: Long = 5_000,
): Boolean {
    val beforeIds = client.windowState.config.value?.tabs?.map { it.id }?.toSet() ?: emptySet()
    socket.send(WindowCommand.AddTab)
    val newTabId = withTimeoutOrNull(timeoutMs) {
        client.windowState.config
            .filterNotNull()
            .first { cfg -> (cfg.tabs.map { it.id }.toSet() - beforeIds).isNotEmpty() }
            .let { cfg -> (cfg.tabs.map { it.id }.toSet() - beforeIds).first() }
    } ?: return false
    if (name.isNotBlank()) {
        socket.send(WindowCommand.RenameTab(newTabId, name))
    }
    return true
}
