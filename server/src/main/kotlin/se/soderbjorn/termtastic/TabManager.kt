/**
 * Stateless tab-CRUD operations on a [WindowConfig] snapshot. Each function
 * either returns the new config (for mutations) or `null` when the input
 * leaves the config unchanged. [WindowState] sequences these calls inside
 * its `synchronized(this)` block so the on-disk debouncer sees one new
 * snapshot per logical command.
 *
 * The service is stateless to keep the locking model simple: callers own
 * the [MutableStateFlow] and the id counters.
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.layout.LayoutPreset

/**
 * Tab-only mutations on a [WindowConfig]. Pane mutations live in
 * [PaneManager]. This class is internal and only used by [WindowState].
 */
internal object TabManager {

    /**
     * Create a new tab with a single terminal pane, append it to the tab
     * list, and return the updated config + the freshly minted session id
     * so the caller can register cleanup on it.
     *
     * The new tab is stamped with the [LayoutPreset.Auto] preset and made
     * the active tab: creating a tab both defaults it to auto-tiling (so
     * subsequent panes re-tile automatically — issue #86) and switches the
     * user to it, matching the behaviour every client already expects from
     * the [WindowConfig.activeTabId] echo.
     */
    fun addTab(
        cfg: WindowConfig,
        newTabId: String,
        newNodeId: String,
        sessionId: String,
        randomOrigin: Pair<Double, Double>,
    ): WindowConfig {
        val nextNumber = cfg.tabs.size + 1
        val leaf = LeafNode(
            id = newNodeId,
            sessionId = sessionId,
            title = "Session ${sessionId.removePrefix("s")}",
            content = TerminalContent(sessionId),
        )
        val (ox, oy) = randomOrigin
        val newTab = TabConfig(
            id = newTabId,
            title = "Tab $nextNumber",
            panes = listOf(
                Pane(
                    leaf = leaf,
                    x = ox, y = oy,
                    width = PaneGeometry.DEFAULT_SIZE,
                    height = PaneGeometry.DEFAULT_SIZE,
                    z = 1L,
                )
            ),
            // Focus the lone pane so it renders immediately in the freshly
            // activated tab instead of waiting for a user click (mirrors
            // WindowState.buildDefault's note for the cold-start tab).
            focusedPaneId = newNodeId,
            // Default every new tab to auto-tiling (issue #86).
            layoutPreset = LayoutPreset.Auto.key,
        )
        return cfg.copy(tabs = cfg.tabs + newTab, activeTabId = newTabId)
    }

    /**
     * Close [tabId]. Returns `null` when the tab is unknown or it would
     * leave the user with zero tabs.
     */
    fun closeTab(cfg: WindowConfig, tabId: String): WindowConfig? {
        if (cfg.tabs.size <= 1) return null
        if (cfg.tabs.none { it.id == tabId }) return null
        val newTabs = cfg.tabs.filterNot { it.id == tabId }
        val newActive = if (cfg.activeTabId == tabId) {
            val oldIdx = cfg.tabs.indexOfFirst { it.id == tabId }
            newTabs.getOrNull(oldIdx.coerceAtMost(newTabs.size - 1))?.id
        } else {
            cfg.activeTabId
        }
        return cfg.copy(tabs = newTabs, activeTabId = newActive)
    }

    /** Mark [tabId] as the currently-selected tab. */
    fun setActiveTab(cfg: WindowConfig, tabId: String): WindowConfig? {
        if (cfg.activeTabId == tabId) return null
        if (cfg.tabs.none { it.id == tabId }) return null
        return cfg.copy(activeTabId = tabId)
    }

    /**
     * Record that the user just focused [paneId] in [tabId]. If a *different*
     * pane in that tab is currently maximized (and would therefore visually
     * cover the newly-focused one), clear that maximize flag so the focused
     * pane becomes visible. Focusing the maximized pane itself leaves the
     * maximize state alone.
     */
    fun setFocusedPane(cfg: WindowConfig, tabId: String, paneId: String): WindowConfig? {
        val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return null
        val tab = cfg.tabs[tabIdx]
        val livePanes = HashSet<String>()
        tab.panes.forEach { livePanes.add(it.leaf.id) }
        if (paneId !in livePanes) return null
        val maximizedOther = tab.panes.firstOrNull { it.maximized && it.leaf.id != paneId }
        val needsUnmaximize = maximizedOther != null
        if (tab.focusedPaneId == paneId && !needsUnmaximize) return null
        val newPanes = if (needsUnmaximize) {
            tab.panes.map { if (it.maximized && it.leaf.id != paneId) it.copy(maximized = false) else it }
        } else {
            tab.panes
        }
        val newTabs = cfg.tabs.toMutableList()
        newTabs[tabIdx] = tab.copy(focusedPaneId = paneId, panes = newPanes)
        return cfg.copy(tabs = newTabs)
    }

    /**
     * Move [tabId] so it sits immediately before or after [targetTabId]
     * (depending on [before]).
     */
    fun moveTab(cfg: WindowConfig, tabId: String, targetTabId: String, before: Boolean): WindowConfig? {
        if (tabId == targetTabId) return null
        val srcIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        val tgtIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
        if (srcIdx < 0 || tgtIdx < 0) return null

        val moving = cfg.tabs[srcIdx]
        val without = cfg.tabs.toMutableList().also { it.removeAt(srcIdx) }
        val newTargetIdx = without.indexOfFirst { it.id == targetTabId }
        val insertAt = if (before) newTargetIdx else newTargetIdx + 1
        if (insertAt == srcIdx) return null
        without.add(insertAt, moving)
        return cfg.copy(tabs = without)
    }

    /** Mark [tabId] as hidden or visible in the tab strip. */
    fun setTabHidden(cfg: WindowConfig, tabId: String, hidden: Boolean): WindowConfig? {
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return null
        val tab = cfg.tabs[idx]
        if (tab.isHidden == hidden) return null
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(isHidden = hidden)
        return cfg.copy(tabs = newTabs)
    }

    /** Mark [tabId] as hidden or visible in the left sidebar's tab tree. */
    fun setTabHiddenFromSidebar(cfg: WindowConfig, tabId: String, hidden: Boolean): WindowConfig? {
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return null
        val tab = cfg.tabs[idx]
        if (tab.isHiddenFromSidebar == hidden) return null
        val newTabs = cfg.tabs.toMutableList()
        newTabs[idx] = tab.copy(isHiddenFromSidebar = hidden)
        return cfg.copy(tabs = newTabs)
    }

    /** Set the display title of [tabId]. Returns `null` when unchanged. */
    fun renameTab(cfg: WindowConfig, tabId: String, title: String): WindowConfig? {
        val sanitized = title.trim().take(80)
        if (sanitized.isEmpty()) return null
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            if (tab.id == tabId && tab.title != sanitized) {
                changed = true
                tab.copy(title = sanitized)
            } else tab
        }
        return if (changed) cfg.copy(tabs = newTabs) else null
    }
}
