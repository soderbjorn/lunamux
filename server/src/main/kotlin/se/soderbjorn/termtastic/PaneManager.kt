/**
 * Stateless pane-CRUD operations on a [WindowConfig] snapshot. Each function
 * either returns the new config (for mutations) or `null` when the input
 * leaves the config unchanged. Per-pane content updates (file-browser,
 * terminal, git) reuse [updatePaneContent] which scans every tab once.
 *
 * Used internally by [WindowState] within its `synchronized(this)` block.
 */
package se.soderbjorn.termtastic

import kotlin.random.Random

/**
 * Pane-only mutations on a [WindowConfig]. Tab mutations live in
 * [TabManager]. This object is internal and only used by [WindowState].
 */
internal object PaneManager {

    /** Pick a random `(x, y)` origin on the snap grid for a new pane. */
    fun randomSnappedOrigin(size: Double = PaneGeometry.DEFAULT_SIZE): Pair<Double, Double> {
        val maxSteps = ((1.0 - size) / PaneGeometry.SNAP).toInt().coerceAtLeast(0)
        val sx = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        val sy = Random.nextInt(0, maxSteps + 1) * PaneGeometry.SNAP
        return sx to sy
    }

    /** Next available z in [tab]'s stacking order. */
    fun nextZ(tab: TabConfig): Long =
        (tab.panes.maxOfOrNull { it.z } ?: 0L) + 1L

    /**
     * Return [tab] with every pane's `maximized` flag cleared. Adding a new
     * pane to a tab that already has one maximized would otherwise leave
     * the new pane hidden behind the full-screen sibling.
     */
    fun demoteMaximized(tab: TabConfig): TabConfig {
        if (tab.panes.none { it.maximized }) return tab
        return tab.copy(panes = tab.panes.map { if (it.maximized) it.copy(maximized = false) else it })
    }

    /**
     * Append [newPane] to the tab at index [tabIdx], demoting any maximized
     * sibling first.
     */
    fun appendPane(cfg: WindowConfig, tabIdx: Int, newPane: Pane): WindowConfig {
        val newTabs = cfg.tabs.toMutableList()
        val tab = cfg.tabs[tabIdx]
        val demoted = demoteMaximized(tab)
        newTabs[tabIdx] = demoted.copy(panes = demoted.panes + newPane)
        return cfg.copy(tabs = newTabs)
    }

    /**
     * Remove pane [paneId] from its tab, clearing the tab's saved focus if
     * it pointed at the killed pane. Returns `null` when nothing changed.
     */
    fun closePane(cfg: WindowConfig, paneId: String): WindowConfig? {
        val newTabs = cfg.tabs.map { tab ->
            val newPanes = tab.panes.filterNot { it.leaf.id == paneId }
            val newFocus = if (tab.focusedPaneId == paneId) null else tab.focusedPaneId
            if (newPanes.size == tab.panes.size &&
                newFocus == tab.focusedPaneId
            ) tab
            else tab.copy(panes = newPanes, focusedPaneId = newFocus)
        }
        val newCfg = cfg.copy(tabs = newTabs)
        return if (newCfg == cfg) null else newCfg
    }

    /**
     * Remove every pane that references [sessionId]. Returns `null` when
     * nothing changed.
     */
    fun closeSession(cfg: WindowConfig, sessionId: String): WindowConfig? {
        if (sessionId.isEmpty()) return null
        val newTabs = cfg.tabs.map { tab ->
            val newPanes = tab.panes.filterNot { it.leaf.sessionId == sessionId }
            val liveIds = HashSet<String>()
            newPanes.forEach { liveIds.add(it.leaf.id) }
            val newFocus = tab.focusedPaneId?.takeIf { it in liveIds }
            tab.copy(panes = newPanes, focusedPaneId = newFocus)
        }
        val newCfg = cfg.copy(tabs = newTabs)
        return if (newCfg == cfg) null else newCfg
    }

    /**
     * Rename pane [paneId]. An empty [title] clears the custom name and
     * lets the cwd-based title take over.
     */
    fun renamePane(cfg: WindowConfig, paneId: String, title: String): WindowConfig? {
        val sanitized = title.trim().take(80)
        val newCustomName: String? = sanitized.ifEmpty { null }
        var changed = false
        fun renameLeaf(leaf: LeafNode): LeafNode {
            if (leaf.id != paneId) return leaf
            val newTitle = computeLeafTitle(newCustomName, leaf.cwd, leaf.title)
            if (leaf.customName == newCustomName && leaf.title == newTitle) return leaf
            changed = true
            return leaf.copy(customName = newCustomName, title = newTitle)
        }
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                tab.copy(
                    panes = tab.panes.map { p -> p.copy(leaf = renameLeaf(p.leaf)) },
                )
            }
        )
        return if (changed) newCfg else null
    }

    /**
     * Push a freshly-detected working directory for the pane backed by
     * [sessionId]. Returns `null` when no leaf matched or the cwd was
     * unchanged.
     */
    fun updatePaneCwd(cfg: WindowConfig, sessionId: String, cwd: String): WindowConfig? {
        if (cwd.isBlank()) return null
        var changed = false
        fun maybeUpdate(leaf: LeafNode): LeafNode {
            if (leaf.sessionId != sessionId || leaf.cwd == cwd) return leaf
            changed = true
            val newTitle = computeLeafTitle(leaf.customName, cwd, leaf.title)
            return leaf.copy(cwd = cwd, title = newTitle)
        }
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                tab.copy(
                    panes = tab.panes.map { p -> p.copy(leaf = maybeUpdate(p.leaf)) },
                )
            }
        )
        return if (changed) newCfg else null
    }

    /**
     * Update the position and size of [paneId] using normalised geometry.
     */
    fun setPaneGeometry(
        cfg: WindowConfig,
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ): WindowConfig? {
        val box = PaneGeometry.normalize(x, y, width, height)
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) return@map tab
            val current = tab.panes[idx]
            if (current.x == box.x && current.y == box.y &&
                current.width == box.width && current.height == box.height
            ) return@map tab
            changed = true
            val newPanes = tab.panes.toMutableList()
            newPanes[idx] = current.copy(x = box.x, y = box.y, width = box.width, height = box.height)
            tab.copy(panes = newPanes)
        }
        return if (changed) cfg.copy(tabs = newTabs) else null
    }

    /** Bring [paneId] to the top of its tab's stacking order. */
    fun raisePane(cfg: WindowConfig, paneId: String): WindowConfig? {
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) return@map tab
            val current = tab.panes[idx]
            val maxZ = tab.panes.maxOf { it.z }
            if (current.z == maxZ && tab.panes.count { it.z == maxZ } == 1) return@map tab
            changed = true
            val newPanes = tab.panes.toMutableList()
            newPanes[idx] = current.copy(z = maxZ + 1)
            tab.copy(panes = newPanes)
        }
        return if (changed) cfg.copy(tabs = newTabs) else null
    }

    /** Toggle the maximized flag on [paneId]. */
    fun toggleMaximized(cfg: WindowConfig, paneId: String): WindowConfig? {
        var changed = false
        val newTabs = cfg.tabs.map { tab ->
            val idx = tab.panes.indexOfFirst { it.leaf.id == paneId }
            if (idx < 0) return@map tab
            val current = tab.panes[idx]
            val nowMax = !current.maximized
            val topZ = tab.panes.maxOf { it.z }
            changed = true
            val newPanes = tab.panes.mapIndexed { i, p ->
                when {
                    i == idx -> p.copy(
                        maximized = nowMax,
                        z = if (nowMax) topZ + 1 else p.z,
                    )
                    nowMax && p.maximized -> p.copy(maximized = false)
                    else -> p
                }
            }
            tab.copy(panes = newPanes)
        }
        return if (changed) cfg.copy(tabs = newTabs) else null
    }

    /**
     * Apply the named [layout] algorithm to [tabId]. The pane with
     * [primaryPaneId] (or the first pane if null/invalid) wins slot 0;
     * remaining panes are area-ranked into the remaining slots.
     */
    fun applyLayout(
        cfg: WindowConfig,
        tabId: String,
        layout: String,
        primaryPaneId: String?,
    ): WindowConfig? {
        val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return null
        val tab = cfg.tabs[tabIdx]
        if (tab.panes.isEmpty()) return null

        val primary = tab.panes.firstOrNull { it.leaf.id == primaryPaneId } ?: tab.panes.first()
        val rest = tab.panes
            .filter { it.leaf.id != primary.leaf.id }
            .sortedWith(
                compareByDescending<Pane> { it.width * it.height }
                    .thenBy { tab.panes.indexOf(it) }
            )
        val ordered = listOf(primary) + rest
        val boxes = computePaneLayout(layout, ordered.size)

        val boxById = ordered.withIndex().associate { (i, p) -> p.leaf.id to boxes[i] }
        val newPanes = tab.panes.map { p ->
            val b = boxById[p.leaf.id] ?: return@map p
            p.copy(x = b.x, y = b.y, width = b.width, height = b.height, maximized = false)
        }
        val newTabs = cfg.tabs.toMutableList()
        newTabs[tabIdx] = tab.copy(panes = newPanes)
        return cfg.copy(tabs = newTabs)
    }

    /**
     * Move [paneId] from its current tab to [targetTabId], placing it at
     * a fresh random origin in the target. Carries focus across when the
     * pane was the source tab's focused one.
     */
    fun movePaneToTab(cfg: WindowConfig, paneId: String, targetTabId: String): WindowConfig? {
        if (paneId.isEmpty() || targetTabId.isEmpty()) return null
        val targetIdx = cfg.tabs.indexOfFirst { it.id == targetTabId }
        if (targetIdx < 0) return null

        var sourceIdx = -1
        var movedLeaf: LeafNode? = null
        var newSourcePanes: List<Pane>? = null

        for ((idx, tab) in cfg.tabs.withIndex()) {
            val paneIdx = tab.panes.indexOfFirst { it.leaf.id == paneId }
            if (paneIdx >= 0) {
                sourceIdx = idx
                movedLeaf = tab.panes[paneIdx].leaf
                newSourcePanes = tab.panes.toMutableList().also { it.removeAt(paneIdx) }
                break
            }
        }
        if (sourceIdx < 0 || movedLeaf == null || newSourcePanes == null) return null
        if (sourceIdx == targetIdx) return null

        val newTabs = cfg.tabs.toMutableList()
        val sourceFocus = cfg.tabs[sourceIdx].focusedPaneId
        val wasFocusedInSource = sourceFocus == paneId
        val newSourceFocus = if (wasFocusedInSource) null else sourceFocus
        newTabs[sourceIdx] = cfg.tabs[sourceIdx].copy(
            panes = newSourcePanes,
            focusedPaneId = newSourceFocus,
        )
        val targetTab = newTabs[targetIdx]
        val (ox, oy) = randomSnappedOrigin()
        val newPane = Pane(
            leaf = movedLeaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = nextZ(targetTab),
        )
        val demotedTarget = demoteMaximized(targetTab)
        val newTargetFocus = if (wasFocusedInSource) paneId else demotedTarget.focusedPaneId
        newTabs[targetIdx] = demotedTarget.copy(
            panes = demotedTarget.panes + newPane,
            focusedPaneId = newTargetFocus,
        )
        return cfg.copy(tabs = newTabs)
    }

    /**
     * Swap the positions and sizes of two panes that share a tab. Pane A
     * (the drag source) inherits B's geometry and is raised to the top;
     * pane B takes A's former geometry and keeps its existing z.
     */
    fun swapPanes(cfg: WindowConfig, aId: String, bId: String): WindowConfig? {
        if (aId.isEmpty() || bId.isEmpty() || aId == bId) return null
        for ((tabIdx, tab) in cfg.tabs.withIndex()) {
            val aIdx = tab.panes.indexOfFirst { it.leaf.id == aId }
            val bIdx = tab.panes.indexOfFirst { it.leaf.id == bId }
            if (aIdx < 0 || bIdx < 0) continue
            val a = tab.panes[aIdx]
            val b = tab.panes[bIdx]
            val topZ = tab.panes.maxOf { it.z }
            val newPanes = tab.panes.toMutableList()
            newPanes[aIdx] = a.copy(
                x = b.x, y = b.y, width = b.width, height = b.height,
                z = topZ + 1,
            )
            newPanes[bIdx] = b.copy(x = a.x, y = a.y, width = a.width, height = a.height)
            val newTabs = cfg.tabs.toMutableList()
            newTabs[tabIdx] = tab.copy(panes = newPanes)
            return cfg.copy(tabs = newTabs)
        }
        return null
    }

    /**
     * Apply [transform] to the [FileBrowserContent] of pane [paneId] if it
     * exists and is currently a file-browser pane. Returns the new content
     * and the new config when the transform produced a change.
     */
    fun updateFileBrowserContent(
        cfg: WindowConfig,
        paneId: String,
        transform: (FileBrowserContent) -> FileBrowserContent,
    ): Pair<WindowConfig, FileBrowserContent>? {
        var newState: FileBrowserContent? = null
        fun mutate(leaf: LeafNode): LeafNode {
            if (leaf.id != paneId) return leaf
            val current = leaf.content as? FileBrowserContent ?: return leaf
            val next = transform(current)
            if (next == current) return leaf
            newState = next
            return leaf.copy(content = next)
        }
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                tab.copy(
                    panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                )
            }
        )
        val ns = newState ?: return null
        return newCfg to ns
    }

    /** Apply [transform] to the [TerminalContent] of pane [paneId]. */
    fun updateTerminalContent(
        cfg: WindowConfig,
        paneId: String,
        transform: (TerminalContent) -> TerminalContent,
    ): Pair<WindowConfig, TerminalContent>? {
        var newState: TerminalContent? = null
        fun mutate(leaf: LeafNode): LeafNode {
            if (leaf.id != paneId) return leaf
            val current = leaf.content as? TerminalContent ?: return leaf
            val next = transform(current)
            if (next == current) return leaf
            newState = next
            return leaf.copy(content = next)
        }
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                tab.copy(
                    panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                )
            }
        )
        val ns = newState ?: return null
        return newCfg to ns
    }

    /** Apply [transform] to the [GitContent] of pane [paneId]. */
    fun updateGitContent(
        cfg: WindowConfig,
        paneId: String,
        transform: (GitContent) -> GitContent,
    ): Pair<WindowConfig, GitContent>? {
        var newState: GitContent? = null
        fun mutate(leaf: LeafNode): LeafNode {
            if (leaf.id != paneId) return leaf
            val current = leaf.content as? GitContent ?: return leaf
            val next = transform(current)
            if (next == current) return leaf
            newState = next
            return leaf.copy(content = next)
        }
        val newCfg = cfg.copy(
            tabs = cfg.tabs.map { tab ->
                tab.copy(
                    panes = tab.panes.map { p -> p.copy(leaf = mutate(p.leaf)) },
                )
            }
        )
        val ns = newState ?: return null
        return newCfg to ns
    }
}
