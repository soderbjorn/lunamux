/**
 * Window, tab, and pane state management for Termtastic.
 *
 * This file contains the [WindowState] singleton, which is the authoritative
 * source of truth for the entire window layout. Every mutation flows
 * through this object so the resulting [WindowConfig] StateFlow is the
 * single stream clients subscribe to.
 *
 * `WindowState` is a thin dispatcher: it owns the [MutableStateFlow] and
 * the id counters, and delegates the actual config transformations to
 * [TabManager] and [PaneManager]. Pure formatting helpers live in
 * [PathFormatting] and the layout algorithms in [PaneLayouts].
 *
 * Mutations are synchronized; the debounced persistence writer in
 * [Application.main] picks up snapshot changes and writes them to SQLite.
 *
 * @see WindowConfig
 * @see TabManager
 * @see PaneManager
 * @see PathFormatting
 * @see PaneLayouts
 * @see TerminalSessions
 * @see SettingsRepository
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.*
import se.soderbjorn.darkness.web.layout.LayoutPreset

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.util.concurrent.atomic.AtomicLong

// The @Serializable data classes (WindowConfig, TabConfig, Pane, LeafNode,
// LeafContent + subclasses) live in the :clientServer KMP module so the web
// and android clients can deserialize the same wire types the server produces.

object WindowState {
    private val log = LoggerFactory.getLogger(WindowState::class.java)

    private val nodeIdCounter = AtomicLong(0)
    private val tabIdCounter = AtomicLong(0)

    private fun newNodeId(): String = "n${nodeIdCounter.incrementAndGet()}"
    private fun newTabId(): String = "t${tabIdCounter.incrementAndGet()}"

    private val _config: MutableStateFlow<WindowConfig> = MutableStateFlow(WindowConfig(emptyList()))
    val config: StateFlow<WindowConfig> = _config.asStateFlow()

    @Volatile
    private var initialized: Boolean = false

    /**
     * Per-tab importance order of panes; head is the active pane.
     * Mutated by focus events (bubble to head), pane creation (new pane
     * at index 0, parent at index 1), and pane removal (drop the closed
     * id). Drives the Auto layout's slot assignment so freshly-created
     * panes claim primary while their parent keeps slot 1. In-memory
     * only — rebuilt from focus/create events after a restart, no
     * backwards-compat persistence required.
     */
    private val paneOrderByTab: MutableMap<String, MutableList<String>> = mutableMapOf()

    /**
     * Pane that was active when each pane was created. Recorded by
     * the four `add…ToTab` methods. Available as a future tie-break
     * heuristic for grouping siblings; not consumed by Auto today.
     */
    private val paneParent: MutableMap<String, String> = mutableMapOf()

    /**
     * Last layout key applied to each tab. `"auto"` means the server
     * re-tiles automatically on every pane add/remove/focus event.
     * Absent entries mean no preset is driving (manual placement).
     */
    private val activeLayoutByTab: MutableMap<String, String> = mutableMapOf()

    /**
     * Bubble [paneId] to the head of [paneOrderByTab]`[tabId]`. Called
     * by [setFocusedPane] and indirectly whenever a pane becomes the
     * active one. Idempotent when already at the head.
     */
    private fun bubbleFocus(tabId: String, paneId: String) {
        val order = paneOrderByTab.getOrPut(tabId) { mutableListOf() }
        val idx = order.indexOf(paneId)
        if (idx == 0) return
        if (idx > 0) order.removeAt(idx)
        order.add(0, paneId)
    }

    /**
     * Record that pane [newPaneId] was created in [tabId] from a parent
     * pane [parentPaneId]. Inserts the new pane at index 0 of the
     * importance order; if a parent is given and present, moves it to
     * index 1 so the originating pane keeps slot 1 in Auto layout.
     */
    private fun recordPaneCreated(tabId: String, newPaneId: String, parentPaneId: String?) {
        val order = paneOrderByTab.getOrPut(tabId) { mutableListOf() }
        order.remove(newPaneId)
        order.add(0, newPaneId)
        if (parentPaneId != null && parentPaneId != newPaneId) {
            paneParent[newPaneId] = parentPaneId
            val parentIdx = order.indexOf(parentPaneId)
            if (parentIdx > 1) {
                order.removeAt(parentIdx)
                order.add(1, parentPaneId)
            }
        }
    }

    /**
     * Drop [paneId] from importance order and parent linkage. Called
     * by [closePane] after the pane is removed from its tab.
     */
    private fun recordPaneRemoved(paneId: String) {
        for (order in paneOrderByTab.values) order.remove(paneId)
        paneParent.remove(paneId)
    }

    /**
     * If [tabId] has a remembered active layout, re-apply it. Used
     * after pane add/remove/focus so Auto re-tiles on demand. Other
     * preset keys also re-apply, keeping geometry consistent.
     * No-op when no layout is remembered for the tab.
     *
     * Must be called while still holding the [WindowState] monitor —
     * uses the synchronized [applyLayout] internally, which is
     * re-entrant on the same thread.
     */
    private fun maybeReapplyLayout(tabId: String) {
        val layout = activeLayoutByTab[tabId] ?: return
        val primary = paneOrderByTab[tabId]?.firstOrNull()
        val cfg = _config.value
        val newCfg = PaneManager.applyLayout(cfg, tabId, layout, primary) ?: return
        _config.value = newCfg
    }

    /**
     * One-shot bootstrap: try to restore the persisted window config, otherwise
     * fall back to a fresh default. Must be called from `main()` exactly once,
     * before any other access to [config].
     */
    @Synchronized
    fun initialize(repo: SettingsRepository) {
        if (initialized) return
        initialized = true

        val loaded = repo.loadWindowConfig()
        val cfg = if (loaded != null && loaded.tabs.isNotEmpty()) {
            try {
                rehydrate(loaded, repo)
            } catch (t: Throwable) {
                log.warn("Failed to rehydrate persisted window config; using default", t)
                buildDefault()
            }
        } else {
            buildDefault()
        }
        _config.value = cfg

        // Re-engage persisted layout presets so subsequent pane
        // add/remove/focus events fire auto re-tile (or any other
        // preset-driven layout) without the user re-picking from the
        // dropdown. Custom is treated as "no preset is driving" and
        // skipped.
        for (tab in cfg.tabs) {
            val key = tab.layoutPreset ?: continue
            if (key == "custom") continue
            activeLayoutByTab[tab.id] = key
        }

        // Seed paneOrderByTab from the persisted focus so the head of
        // each tab's importance order matches what the client will
        // restore. Subsequent focus events bubble new entries.
        for (tab in cfg.tabs) {
            val focused = tab.focusedPaneId
            if (focused != null && tab.panes.any { it.leaf.id == focused }) {
                paneOrderByTab[tab.id] = mutableListOf(focused)
            }
        }

        runCatching {
            val live = HashSet<String>()
            for (tab in cfg.tabs) {
                tab.panes.forEach { live.add(it.leaf.id) }
            }
            for (stale in repo.allScrollbackLeafIds() - live) {
                repo.deleteScrollback(stale)
            }
        }.onFailure { log.warn("Scrollback GC failed", it) }
    }

    private fun rehydrate(loaded: WindowConfig, repo: SettingsRepository): WindowConfig {
        var maxNodeId = 0L
        var maxTabId = 0L

        fun trackNodeId(id: String) {
            id.removePrefix("n").toLongOrNull()?.let { if (it > maxNodeId) maxNodeId = it }
        }

        fun rebuildLeaf(leaf: LeafNode): LeafNode {
            trackNodeId(leaf.id)
            return when (leaf.content) {
                is FileBrowserContent, is GitContent -> leaf.copy(sessionId = "")
                is TerminalContent, null -> {
                    val priorScrollback = runCatching { repo.loadScrollback(leaf.id) }.getOrNull()
                    val freshSession = TerminalSessions.create(leaf.cwd, priorScrollback)
                    leaf.copy(sessionId = freshSession, content = TerminalContent(freshSession))
                }
            }
        }

        val rebuiltTabs = loaded.tabs.map { tab ->
            tab.id.removePrefix("t").toLongOrNull()?.let { if (it > maxTabId) maxTabId = it }

            val rebuiltPanes = tab.panes.map { p ->
                val box = PaneGeometry.normalize(p.x, p.y, p.width, p.height)
                p.copy(
                    leaf = rebuildLeaf(p.leaf),
                    x = box.x, y = box.y, width = box.width, height = box.height,
                )
            }
            tab.copy(panes = rebuiltPanes)
        }

        nodeIdCounter.set(maxNodeId)
        tabIdCounter.set(maxTabId)

        val tabIdSet = rebuiltTabs.map { it.id }.toSet()
        val validatedActive = loaded.activeTabId?.takeIf { it in tabIdSet }
        val sanitizedTabs = rebuiltTabs.map { tab ->
            val livePaneIds = tab.panes.mapTo(HashSet()) { it.leaf.id }
            val keepFocus = tab.focusedPaneId?.takeIf { it in livePaneIds }
            if (keepFocus == tab.focusedPaneId) tab else tab.copy(focusedPaneId = keepFocus)
        }
        return WindowConfig(tabs = sanitizedTabs, activeTabId = validatedActive)
    }

    private fun buildDefault(): WindowConfig {
        val s1 = TerminalSessions.create()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = s1,
            title = "Session ${s1.removePrefix("s")}",
            content = TerminalContent(s1),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val tab1 = TabConfig(
            id = newTabId(),
            title = "Tab 1",
            panes = listOf(
                Pane(
                    leaf = leaf,
                    x = ox, y = oy,
                    width = PaneGeometry.DEFAULT_SIZE,
                    height = PaneGeometry.DEFAULT_SIZE,
                    z = 1L,
                )
            ),
            // Without this the toolkit's tab snapshot has activePaneId=null
            // and the lone pane stays unrendered until the user clicks it.
            focusedPaneId = leaf.id,
            // Default the cold-start tab to auto-tiling too (issue #86), so
            // every tab in the app shares the same default layout. Re-engaged
            // into activeLayoutByTab by the persisted-preset loop in initialize().
            layoutPreset = LayoutPreset.Auto.key,
        )
        // Without an explicit activeTabId the toolkit doesn't visually
        // select any tab on cold start, so the lone tab/pane both look
        // inactive until the user clicks the tab.
        return WindowConfig(listOf(tab1), activeTabId = tab1.id)
    }

    // ── Tab dispatch ─────────────────────────────────────────────────

    /** Create a new tab with a single terminal pane, switch to it, and
     *  default it to auto-tiling. */
    fun addTab() = synchronized(this) {
        val cfg = _config.value
        val sessionId = TerminalSessions.create()
        val tabId = newTabId()
        val newCfg = TabManager.addTab(
            cfg = cfg,
            newTabId = tabId,
            newNodeId = newNodeId(),
            sessionId = sessionId,
            randomOrigin = PaneManager.randomSnappedOrigin(),
        )
        _config.value = newCfg
        // Register the auto preset in the live map so pane add/remove/focus
        // events on this tab re-tile via maybeReapplyLayout without waiting
        // for a server restart to re-engage the persisted layoutPreset.
        activeLayoutByTab[tabId] = LayoutPreset.Auto.key
    }

    /** Close [tabId], destroying any PTY sessions no longer referenced. */
    fun closeTab(tabId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.closeTab(cfg, tabId) ?: return@synchronized
        commitWithSessionGc(cfg, newCfg)
    }

    /** Mark [tabId] as the currently-selected tab. */
    fun setActiveTab(tabId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.setActiveTab(cfg, tabId) ?: return@synchronized
        _config.value = newCfg
    }

    /** Record the user's focus on [paneId] in [tabId]. */
    fun setFocusedPane(tabId: String, paneId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.setFocusedPane(cfg, tabId, paneId) ?: return@synchronized
        _config.value = newCfg
        bubbleFocus(tabId, paneId)
        maybeReapplyLayout(tabId)
    }

    /** Move [tabId] before or after [targetTabId]. */
    fun moveTab(tabId: String, targetTabId: String, before: Boolean) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.moveTab(cfg, tabId, targetTabId, before) ?: return@synchronized
        _config.value = newCfg
    }

    /**
     * Mark [tabId] as hidden or visible in the tab strip.
     *
     * @see TabConfig.isHidden
     * @see WindowCommand.SetTabHidden
     */
    fun setTabHidden(tabId: String, hidden: Boolean) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.setTabHidden(cfg, tabId, hidden) ?: return@synchronized
        _config.value = newCfg
    }

    /**
     * Mark [tabId] as hidden or visible in the left sidebar tab tree.
     *
     * @see TabConfig.isHiddenFromSidebar
     * @see WindowCommand.SetTabHiddenFromSidebar
     */
    fun setTabHiddenFromSidebar(tabId: String, hidden: Boolean) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.setTabHiddenFromSidebar(cfg, tabId, hidden) ?: return@synchronized
        _config.value = newCfg
    }

    /** Set the display title of [tabId]. */
    fun renameTab(tabId: String, title: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = TabManager.renameTab(cfg, tabId, title) ?: return@synchronized
        _config.value = newCfg
    }

    // ── Lookups ──────────────────────────────────────────────────────

    /** Find a leaf by id across all panes. */
    fun findLeaf(paneId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return@synchronized it.leaf }
        }
        null
    }

    /** Return the id of the tab that contains [paneId]. */
    fun tabIdOfPane(paneId: String): String? = synchronized(this) {
        val cfg = _config.value
        for (tab in cfg.tabs) {
            if (tab.panes.any { it.leaf.id == paneId }) return@synchronized tab.id
        }
        null
    }

    private fun findLeafBySession(cfg: WindowConfig, sessionId: String): LeafNode? {
        for (tab in cfg.tabs) {
            tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
        }
        return null
    }

    // ── File-browser content dispatch ────────────────────────────────

    private fun mutateFileBrowser(
        paneId: String,
        transform: (FileBrowserContent) -> FileBrowserContent,
    ): FileBrowserContent? = synchronized(this) {
        val cfg = _config.value
        val (newCfg, newState) = PaneManager.updateFileBrowserContent(cfg, paneId, transform)
            ?: return@synchronized null
        _config.value = newCfg
        newState
    }

    fun setFileBrowserSelected(paneId: String, relPath: String?): FileBrowserContent? =
        mutateFileBrowser(paneId) { it.copy(selectedRelPath = relPath) }

    fun setFileBrowserExpanded(paneId: String, dirRelPath: String, expanded: Boolean): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            val next = if (expanded) it.expandedDirs + dirRelPath else it.expandedDirs - dirRelPath
            if (next == it.expandedDirs) it else it.copy(expandedDirs = next)
        }

    fun setFileBrowserLeftWidth(paneId: String, px: Int): FileBrowserContent? {
        val clamped = px.coerceIn(0, 640)
        return mutateFileBrowser(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setFileBrowserAutoRefresh(paneId: String, enabled: Boolean): FileBrowserContent? =
        mutateFileBrowser(paneId) { it.copy(autoRefresh = enabled) }

    fun setFileBrowserFilter(paneId: String, filter: String): FileBrowserContent? {
        val normalized = filter.trim().ifEmpty { null }
        return mutateFileBrowser(paneId) { it.copy(fileFilter = normalized) }
    }

    fun setFileBrowserSort(paneId: String, sort: FileBrowserSort): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            if (it.sortBy == sort) it else it.copy(sortBy = sort)
        }

    fun setFileBrowserExpandedAll(paneId: String, dirs: Set<String>): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            val merged = it.expandedDirs + dirs
            if (merged == it.expandedDirs) it else it.copy(expandedDirs = merged)
        }

    fun clearFileBrowserExpanded(paneId: String): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            if (it.expandedDirs.isEmpty()) it else it.copy(expandedDirs = emptySet())
        }

    fun setFileBrowserFontSize(paneId: String, size: Int): FileBrowserContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateFileBrowser(paneId) { it.copy(fontSize = clamped) }
    }

    // ── Terminal content dispatch ────────────────────────────────────

    private fun mutateTerminal(
        paneId: String,
        transform: (TerminalContent) -> TerminalContent,
    ): TerminalContent? = synchronized(this) {
        val cfg = _config.value
        val (newCfg, newState) = PaneManager.updateTerminalContent(cfg, paneId, transform)
            ?: return@synchronized null
        _config.value = newCfg
        newState
    }

    fun setTerminalFontSize(paneId: String, size: Int): TerminalContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateTerminal(paneId) { it.copy(fontSize = clamped) }
    }

    /**
     * Set the per-pane automatic-reflow override for terminal pane [paneId].
     *
     * Called from the [WindowCommand.SetTerminalAutoReflow] dispatch in
     * `WindowRoutes` when the user toggles "Automatic reformat (this window)"
     * in the reformat button's hover popup. Persists into
     * [TerminalContent.autoReflow] so the choice survives reloads.
     *
     * @param paneId the terminal pane to update.
     * @param enabled `true` to keep auto-reflow on for this pane, `false` to
     *   freeze it until the user manually reformats.
     * @return the updated [TerminalContent], or `null` if [paneId] is not a
     *   terminal pane in the current config.
     */
    fun setTerminalAutoReflow(paneId: String, enabled: Boolean): TerminalContent? =
        mutateTerminal(paneId) { it.copy(autoReflow = enabled) }

    // ── Git content dispatch ─────────────────────────────────────────

    private fun mutateGit(
        paneId: String,
        transform: (GitContent) -> GitContent,
    ): GitContent? = synchronized(this) {
        val cfg = _config.value
        val (newCfg, newState) = PaneManager.updateGitContent(cfg, paneId, transform)
            ?: return@synchronized null
        _config.value = newCfg
        newState
    }

    fun setGitSelected(paneId: String, filePath: String?): GitContent? =
        mutateGit(paneId) { it.copy(selectedFilePath = filePath) }

    fun setGitLeftWidth(paneId: String, px: Int): GitContent? {
        val clamped = px.coerceIn(0, 640)
        return mutateGit(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setGitDiffMode(paneId: String, mode: GitDiffMode): GitContent? =
        mutateGit(paneId) { it.copy(diffMode = mode) }

    fun setGitGraphicalDiff(paneId: String, enabled: Boolean): GitContent? =
        mutateGit(paneId) { it.copy(graphicalDiff = enabled) }

    fun setGitDiffFontSize(paneId: String, size: Int): GitContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateGit(paneId) { it.copy(diffFontSize = clamped) }
    }

    fun setGitAutoRefresh(paneId: String, enabled: Boolean): GitContent? =
        mutateGit(paneId) { it.copy(autoRefresh = enabled) }

    // ── Pane CRUD dispatch ───────────────────────────────────────────

    /** Remove the pane [paneId] from its tab and destroy any orphan PTY. */
    fun closePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val tabId = cfg.tabs.firstOrNull { tab ->
            tab.panes.any { it.leaf.id == paneId }
        }?.id
        val newCfg = PaneManager.closePane(cfg, paneId) ?: return@synchronized
        commitWithSessionGc(cfg, newCfg)
        recordPaneRemoved(paneId)
        if (tabId != null) maybeReapplyLayout(tabId)
    }

    /** Close every pane that references [sessionId] and destroy the PTY. */
    fun closeSession(sessionId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.closeSession(cfg, sessionId) ?: return@synchronized
        commitWithSessionGc(cfg, newCfg)
    }

    /** Rename pane [paneId]; an empty title clears the custom name. */
    fun renamePane(paneId: String, title: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.renamePane(cfg, paneId, title) ?: return@synchronized
        _config.value = newCfg
    }

    /** Push a freshly-detected cwd for the pane backed by [sessionId]. */
    fun updatePaneCwd(sessionId: String, cwd: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.updatePaneCwd(cfg, sessionId, cwd) ?: return@synchronized
        _config.value = newCfg
    }

    /**
     * Update the position and size of [paneId]. The user has overridden
     * preset-driven geometry, so the affected tab's `layoutPreset` is
     * cleared (transitions to Custom mode) — subsequent pane add/remove
     * events stop auto re-tiling until the user re-selects a preset.
     */
    fun setPaneGeometry(
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = synchronized(this) {
        val cfg = _config.value
        val tabId = cfg.tabs.firstOrNull { tab ->
            tab.panes.any { it.leaf.id == paneId }
        }?.id
        val newCfg = PaneManager.setPaneGeometry(cfg, paneId, x, y, width, height) ?: return@synchronized
        _config.value = newCfg
        if (tabId != null) clearLayoutPresetForTab(tabId)
    }

    /** Bring [paneId] to the top of its tab's stacking order. */
    fun raisePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.raisePane(cfg, paneId) ?: return@synchronized
        _config.value = newCfg
    }

    /** Toggle the maximized flag on [paneId]. */
    fun toggleMaximized(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.toggleMaximized(cfg, paneId) ?: return@synchronized
        _config.value = newCfg
    }

    /**
     * Apply a layout algorithm to [tabId]. Records the chosen layout in
     * [activeLayoutByTab] so subsequent pane add/remove/focus events can
     * re-tile via [maybeReapplyLayout]. For `"auto"`, the
     * [primaryPaneId] argument is overridden by the head of
     * [paneOrderByTab]`[tabId]` so the most-recently-focused pane always
     * lands in slot 0.
     */
    fun applyLayout(tabId: String, layout: String, primaryPaneId: String?) = synchronized(this) {
        val cfg = _config.value
        val effectivePrimary = if (layout == "auto") {
            paneOrderByTab[tabId]?.firstOrNull() ?: primaryPaneId
        } else {
            primaryPaneId
        }
        val laidOut = PaneManager.applyLayout(cfg, tabId, layout, effectivePrimary) ?: return@synchronized
        // Stamp the chosen preset onto the persisted TabConfig so a
        // server restart re-engages auto re-tile without the user
        // having to re-pick from the dropdown.
        val tabIdx = laidOut.tabs.indexOfFirst { it.id == tabId }
        val newCfg = if (tabIdx >= 0) {
            val tab = laidOut.tabs[tabIdx]
            if (tab.layoutPreset == layout) laidOut
            else laidOut.copy(
                tabs = laidOut.tabs.toMutableList().also {
                    it[tabIdx] = tab.copy(layoutPreset = layout)
                },
            )
        } else laidOut
        _config.value = newCfg
        activeLayoutByTab[tabId] = layout
    }

    /**
     * Internal helper used after manual move/resize to forget the
     * tab's persisted preset so subsequent pane add/remove events
     * don't undo the user's hand-placement. Call from the geometry-
     * setter path. Idempotent.
     */
    private fun clearLayoutPresetForTab(tabId: String) {
        activeLayoutByTab.remove(tabId)
        val cfg = _config.value
        val tabIdx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return
        val tab = cfg.tabs[tabIdx]
        if (tab.layoutPreset == null) return
        _config.value = cfg.copy(
            tabs = cfg.tabs.toMutableList().also {
                it[tabIdx] = tab.copy(layoutPreset = null)
            },
        )
    }

    /**
     * Normalise the cwd a freshly-created pane should be created with.
     * Trims/strips blank input and returns `null` for "no cwd". The client
     * passes the directory the user can see on screen at click time
     * (the focused pane's [LeafNode.cwd] from its local [WindowConfig]
     * snapshot) so there is no server-side guessing.
     */
    private fun sanitizeIncomingCwd(explicit: String?): String? =
        explicit?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Floor for non-terminal panes when the client couldn't supply a cwd
     * (e.g. brand-new tab with no terminal in it yet). Returning the
     * server's working directory means the file-browser / git pane shows
     * *something* useful instead of an empty listing with a "FILES" title.
     */
    private fun nonTerminalCwdFallback(): String =
        System.getProperty("user.dir") ?: System.getProperty("user.home") ?: "/"

    /**
     * Spawn a fresh shell as a new pane in [tabId]. Used by the new-window
     * icon and the empty-tab placeholder.
     */
    fun addPaneToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd)
        val sessionId = TerminalSessions.create(initialCwd = effectiveCwd)
        val fallbackTitle = "Session ${sessionId.removePrefix("s")}"
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = sessionId,
            cwd = effectiveCwd,
            title = computeLeafTitle(null, effectiveCwd, fallbackTitle),
            content = TerminalContent(sessionId),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        _config.value = PaneManager.appendPane(cfg, idx, newPane)
        // Promote the new pane to the tab's focused pane so the toolkit's
        // snapshot reports it as the active pane (the renderer then lands
        // the focus ring on it).
        TabManager.setFocusedPane(_config.value, tabId, leaf.id)?.let { _config.value = it }
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a file-browser pane to [tabId]. */
    fun addFileBrowserToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd) ?: nonTerminalCwdFallback()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            cwd = effectiveCwd,
            title = computeLeafTitle(null, effectiveCwd, "Files"),
            content = FileBrowserContent(),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        _config.value = PaneManager.appendPane(cfg, idx, newPane)
        TabManager.setFocusedPane(_config.value, tabId, leaf.id)?.let { _config.value = it }
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a git overview pane to [tabId]. */
    fun addGitToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd) ?: nonTerminalCwdFallback()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            cwd = effectiveCwd,
            title = computeLeafTitle(null, effectiveCwd, "Git"),
            content = GitContent(),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        _config.value = PaneManager.appendPane(cfg, idx, newPane)
        TabManager.setFocusedPane(_config.value, tabId, leaf.id)?.let { _config.value = it }
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a linked terminal pane to [tabId] sharing [targetSessionId]. */
    fun addLinkToTab(tabId: String, targetSessionId: String): LeafNode? = synchronized(this) {
        if (TerminalSessions.get(targetSessionId) == null) return@synchronized null
        val cfg = _config.value
        val idx = cfg.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = cfg.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val sourceTitle = findLeafBySession(cfg, targetSessionId)?.title ?: "Terminal"
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = targetSessionId,
            title = sourceTitle,
            content = TerminalContent(targetSessionId),
            isLink = true,
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        _config.value = PaneManager.appendPane(cfg, idx, newPane)
        TabManager.setFocusedPane(_config.value, tabId, leaf.id)?.let { _config.value = it }
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Move [paneId] from its current tab into [targetTabId]. */
    fun movePaneToTab(paneId: String, targetTabId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.movePaneToTab(cfg, paneId, targetTabId) ?: return@synchronized
        _config.value = newCfg
    }

    /** Swap the positions and sizes of two panes that share a tab. */
    fun swapPanes(aId: String, bId: String) = synchronized(this) {
        val cfg = _config.value
        val newCfg = PaneManager.swapPanes(cfg, aId, bId) ?: return@synchronized
        _config.value = newCfg
    }

    // ── Session lifecycle helpers ────────────────────────────────────

    /** Whether [sessionId] is referenced by any leaf in the current config. */
    fun hasSession(sessionId: String): Boolean =
        collectSessionIds(_config.value).contains(sessionId)

    private fun collectSessionIds(cfg: WindowConfig): Set<String> {
        val out = HashSet<String>()
        cfg.tabs.forEach { tab ->
            tab.panes.forEach { p ->
                if (p.leaf.sessionId.isNotEmpty()) out.add(p.leaf.sessionId)
            }
        }
        return out
    }

    /**
     * Commit [newCfg] and destroy any PTY sessions that were referenced by
     * [oldCfg] but are no longer reachable from [newCfg].
     */
    private fun commitWithSessionGc(oldCfg: WindowConfig, newCfg: WindowConfig) {
        val before = collectSessionIds(oldCfg)
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }
}
