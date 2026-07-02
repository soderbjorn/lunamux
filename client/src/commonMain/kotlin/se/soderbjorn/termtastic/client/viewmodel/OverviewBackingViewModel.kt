/**
 * Backing ViewModel for the Android/iOS "Overview" mode — a miniaturised,
 * *interactive* replica of the web/Electron tabs-and-panes experience.
 *
 * The overview shows a single scrollable row of tabs and, for the active tab,
 * a faithful layout of its panes (terminal / file-browser / git miniatures)
 * positioned by their server-owned geometry. Beyond rendering, this ViewModel
 * now owns every window-management action mobile exposes — focus, add, close,
 * maximize/restore, minimize-to-dock/restore, layout presets, and the
 * move/resize interaction state machine — so the Compose (Android) and SwiftUI
 * (iOS, planned) front-ends stay thin renderers over a shared model.
 *
 * **Geometry is toolkit-owned.** Pane position/size/z/maximize/minimize are
 * *not* server-command driven on the real client: they live in the toolkit's
 * `LAYOUT_STATE` blob, persisted via `PATCH /api/ui-settings` and broadcast
 * back over `/window`. So geometry actions here author that same blob through
 * [se.soderbjorn.termtastic.client.WindowLayoutState] + a [SettingsPersister].
 * Structural actions (add/close pane, focus) remain server [WindowCommand]s.
 *
 * @see AppBackingViewModel
 * @see se.soderbjorn.termtastic.client.WindowLayoutState
 * @see se.soderbjorn.termtastic.client.WindowSocket
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.web.layout.DEFAULT_LAYOUT_GRID
import se.soderbjorn.darkness.web.layout.LayoutPreset
import se.soderbjorn.termtastic.LeafNode
import se.soderbjorn.termtastic.PaneBox
import se.soderbjorn.termtastic.PaneGeometry
import se.soderbjorn.termtastic.TabConfig
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.client.LayoutGeom
import se.soderbjorn.termtastic.client.ToolkitPaneGeometry
import se.soderbjorn.termtastic.client.WindowLayoutState
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.addPaneToTab

/**
 * ViewModel backing the overview screen.
 *
 * Combines the server-pushed [WindowConfig] with the per-session state map,
 * the toolkit-owned pane geometry, and the raw `LAYOUT_STATE` blob, then
 * exposes a single [State] snapshot both platforms render directly plus an
 * [interaction] flow that drives the move/resize modes.
 *
 * @param windowSocket the live `/window` WebSocket — source of config + state
 *   pushes and the channel for structural [WindowCommand]s.
 * @param geometryByTab authoritative `tabId -> (paneId -> geometry)` from the
 *   toolkit's `LAYOUT_STATE` blob; the overview renders this (not
 *   [se.soderbjorn.termtastic.Pane]'s placeholder fields).
 * @param rawLayoutState the raw `LAYOUT_STATE` element, used for read-modify-
 *   write of geometry edits without dropping toolkit fields. Defaults to an
 *   empty flow for callers/tests that don't persist geometry.
 * @param settingsPersister sink for geometry writes (`PATCH /api/ui-settings`).
 *   `null` disables geometry actions (they no-op) — used by tests and demo.
 */
class OverviewBackingViewModel(
    private val windowSocket: WindowSocket,
    private val geometryByTab: StateFlow<Map<String, Map<String, ToolkitPaneGeometry>>> =
        MutableStateFlow(emptyMap()),
    private val rawLayoutState: StateFlow<JsonElement?> = MutableStateFlow(null),
    private val settingsPersister: SettingsPersister? = null,
) {
    private val _stateFlow = MutableStateFlow(State())

    /** Render-ready overview state; emits on every config or session-state push. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _editTabId = MutableStateFlow<String?>(null)

    /**
     * The tab currently in **edit-layout mode**, or `null`. In edit mode every
     * pane in the tab can be freely dragged to move and resized by its corner
     * handle. Drives the editing banner, the resize handles, and disabling the
     * pager so horizontal drags move panes instead of flipping tabs.
     */
    val editTabId: StateFlow<String?> = _editTabId.asStateFlow()

    private val _drag = MutableStateFlow<Drag?>(null)

    /**
     * The pane currently under the finger in edit mode plus its live,
     * uncommitted geometry, or `null`. The UI renders this pane at
     * [Drag.box] for lag-free feedback and commits on release via [endDrag].
     */
    val drag: StateFlow<Drag?> = _drag.asStateFlow()

    // Optimistic geometry overrides: tabId -> (paneId -> geometry). Applied on
    // top of the server's geometry so a move / maximize / minimize / layout
    // shows **immediately** rather than only after the server round-trip — and
    // at all in demo mode, where there is no server to echo it back. Each tab's
    // override is pruned once the server echoes matching geometry (see
    // [pruneLocalGeom]), so a later change from another client (e.g. the Mac
    // app) is no longer masked by a stale local copy.
    private val _localGeom = MutableStateFlow<Map<String, Map<String, LayoutGeom>>>(emptyMap())

    // Latest inputs captured on each projection so the suspend actions can
    // compute a full, consistent geometry map without re-reading flows.
    private var latestConfig: WindowConfig? = null
    private var latestGeometry: Map<String, Map<String, ToolkitPaneGeometry>> = emptyMap()
    private var latestRawLayout: JsonElement? = null

    /**
     * Immutable, render-ready snapshot of the overview.
     *
     * @property tabs        every visible tab, in display order. Includes the
     *   active tab even when it is "unlisted" (hidden from the strip), so the
     *   user can always see the tab they are looking at.
     * @property activeTabId the currently-selected tab id, or `null`.
     * @property unlistedTabs the hidden ("unlisted") tabs that are NOT in
     *   [tabs] (i.e. every hidden tab except the active one). Surfaced by the
     *   overview's "unlisted tabs" affordance so the user can re-activate
     *   them; tapping one fires the normal select path, after which it shows
     *   temporarily in the strip. Mirrors the web/Mac far-right `⋮` menu.
     */
    data class State(
        val tabs: List<OverviewTab> = emptyList(),
        val activeTabId: String? = null,
        val unlistedTabs: List<UnlistedTab> = emptyList(),
    )

    /**
     * A hidden ("unlisted") tab reference, shown in the overview's unlisted
     * tabs menu. Carries just enough to render a row and re-activate the tab.
     *
     * @property id    the tab id (passed to the select callback).
     * @property title the display label (may be blank — UIs substitute a
     *   placeholder).
     */
    data class UnlistedTab(
        val id: String,
        val title: String,
    )

    /**
     * One tab in the strip / one page in the pager.
     *
     * @property id             the tab id.
     * @property title          display label.
     * @property isActive       whether this is the selected tab.
     * @property aggregateState worst-case status across the tab's panes.
     * @property panes          the tab's **on-canvas** panes (minimized panes
     *   excluded), sorted bottom-to-top by z.
     * @property dock           the tab's minimized panes, for the dock strip.
     * @property isHidden       whether the tab is hidden ("unlisted") from the
     *   tab strip ([TabConfig.isHidden]) — `true` only for the active tab,
     *   since other hidden tabs are projected into [State.unlistedTabs]
     *   instead. Lets the tab context menu label its hide/show item correctly.
     * @property isHiddenFromSidebar whether the tab is hidden from the sidebar
     *   tab tree ([TabConfig.isHiddenFromSidebar]); orthogonal to [isHidden].
     *   Also drives the tab context menu's hide/show-in-sidebar item.
     */
    data class OverviewTab(
        val id: String,
        val title: String,
        val isActive: Boolean,
        val aggregateState: String?,
        val panes: List<OverviewPane>,
        val dock: List<DockedPane>,
        val isHidden: Boolean,
        val isHiddenFromSidebar: Boolean,
    )

    /**
     * One on-canvas pane in a tab's miniature layout. Geometry fields are
     * fractions (0.0–1.0) of the content area.
     *
     * @property leaf         the pane's content descriptor.
     * @property x            top-left x fraction.
     * @property y            top-left y fraction.
     * @property width        width fraction.
     * @property height       height fraction.
     * @property z            stacking key (list pre-sorted ascending).
     * @property maximized    whether the pane should fill the content area.
     * @property isFocused    whether this is the tab's focused pane.
     * @property sessionState the pane's PTY session state, or `null`.
     */
    data class OverviewPane(
        val leaf: LeafNode,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val z: Long,
        val maximized: Boolean,
        val isFocused: Boolean,
        val sessionState: String?,
    )

    /**
     * One minimized (docked) pane, shown as a chip in the dock strip.
     *
     * @property leaf         the pane's content descriptor (icon + title).
     * @property sessionState the pane's PTY session state, or `null`.
     */
    data class DockedPane(
        val leaf: LeafNode,
        val sessionState: String?,
    )

    /**
     * The pane being actively dragged (moved or resized) in edit mode.
     *
     * @property tabId  the pane's tab.
     * @property paneId the pane under the finger.
     * @property box    the live, uncommitted geometry (clamped to bounds).
     */
    data class Drag(
        val tabId: String,
        val paneId: String,
        val box: PaneBox,
    )

    /**
     * Start projecting config + state pushes into [stateFlow]. Long-running —
     * cancels when the enclosing scope is cancelled.
     */
    suspend fun run() {
        coroutineScope {
            launch {
                combine(
                    windowSocket.config.filterNotNull(),
                    windowSocket.states,
                    geometryByTab,
                    rawLayoutState,
                    _localGeom,
                ) { config, states, geometry, rawLayout, localGeom ->
                    latestConfig = config
                    latestGeometry = geometry
                    latestRawLayout = rawLayout
                    project(config, states, geometry, localGeom)
                }.collect { _stateFlow.value = it }
            }
            // Drop optimistic overrides once the server confirms them, so
            // genuine changes from other clients flow through again.
            launch {
                geometryByTab.collect { serverGeo -> pruneLocalGeom(serverGeo) }
            }
        }
    }

    /**
     * Remove optimistic overrides for any tab whose every overridden pane now
     * matches the server's geometry — i.e. our write has been echoed back.
     *
     * @param serverGeo the latest authoritative per-tab geometry.
     */
    private fun pruneLocalGeom(serverGeo: Map<String, Map<String, ToolkitPaneGeometry>>) {
        val cur = _localGeom.value
        if (cur.isEmpty()) return
        val pruned = cur.filterNot { (tabId, localTab) ->
            val serverTab = serverGeo[tabId] ?: return@filterNot false
            localTab.all { (paneId, lg) -> serverTab[paneId]?.toLayoutGeom() == lg }
        }
        if (pruned.size != cur.size) _localGeom.value = pruned
    }

    // ── Structural actions (server WindowCommands) ──────────────────

    /**
     * Make [tabId] the active tab (server-authoritative; the change syncs to
     * every client via the broadcast config echo).
     *
     * @param tabId the tab to activate.
     */
    suspend fun setActiveTab(tabId: String) {
        windowSocket.send(WindowCommand.SetActiveTab(tabId))
    }

    /**
     * Hide or reveal [tabId] in the tab strip ("unlist"/"list" it). Server
     * -authoritative; a hidden tab stays reachable through the strip's
     * trailing "…" unlisted-tabs menu, and remains visible while it is the
     * active tab. Invoked from the tab context menus' "Hide in tab bar" /
     * "Show in tab bar" items.
     *
     * @param tabId  the tab to hide or reveal.
     * @param hidden `true` to hide the tab from the strip, `false` to reveal it.
     * @see setTabHiddenFromSidebar
     */
    suspend fun setTabHidden(tabId: String, hidden: Boolean) {
        windowSocket.send(WindowCommand.SetTabHidden(tabId = tabId, hidden = hidden))
    }

    /**
     * Hide or reveal [tabId] in the sidebar tab tree (and the mobile session
     * list, which mirrors it). Orthogonal to the tab-strip flag
     * ([setTabHidden]). Invoked from the tab context menus' "Hide in side
     * bar" / "Show in side bar" items.
     *
     * @param tabId  the tab to hide or reveal.
     * @param hidden `true` to hide the tab from the sidebar, `false` to reveal it.
     * @see setTabHidden
     */
    suspend fun setTabHiddenFromSidebar(tabId: String, hidden: Boolean) {
        windowSocket.send(WindowCommand.SetTabHiddenFromSidebar(tabId = tabId, hidden = hidden))
    }

    /**
     * Focus [paneId] in [tabId] and raise it to the front — the desktop
     * "click a window → it activates and comes forward" behaviour. Focus is a
     * server command (drives the accent outline); the raise bumps z-order in
     * the toolkit `LAYOUT_STATE` blob so the pane also renders on top.
     *
     * @param tabId  the pane's tab.
     * @param paneId the pane to focus and raise.
     */
    suspend fun focusPane(tabId: String, paneId: String) {
        windowSocket.send(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
        raiseZ(tabId, paneId)
    }

    /**
     * Add a new pane of [kindWire] to [tabId]. Server positions and sizes it
     * (random snapped origin, 40% default). Mirrors the desktop "+pane".
     *
     * @param tabId    the tab to receive the pane.
     * @param kindWire `"terminal"`, `"fileBrowser"`, or `"git"`.
     */
    suspend fun addPane(tabId: String, kindWire: String) {
        addPaneToTab(windowSocket, tabId, kindWire, latestConfig)
    }

    /**
     * Close [paneId], terminating its session. Structural — routes through the
     * server, which no-ops if the pane is already gone.
     *
     * @param paneId the pane to close.
     */
    suspend fun closePane(paneId: String) {
        windowSocket.send(WindowCommand.Close(paneId = paneId))
    }

    // ── Geometry actions (LAYOUT_STATE writes) ──────────────────────

    /**
     * Toggle [paneId]'s maximized state. Maximizing clears the flag on every
     * sibling and raises the pane; restoring leaves the stored x/y/w/h intact
     * so the pane returns to its prior geometry.
     *
     * @param tabId  the pane's tab.
     * @param paneId the pane to maximize/restore.
     */
    suspend fun toggleMaximize(tabId: String, paneId: String) {
        val geom = fullGeomForTab(tabId)
        val cur = geom[paneId] ?: return
        val makeMax = !cur.isMaximized
        for ((id, g) in geom) geom[id] = g.copy(isMaximized = makeMax && id == paneId)
        if (makeMax) geom[paneId] = geom[paneId]!!.copy(zIndex = maxZ(geom) + 1, isMinimized = false)
        persistTabGeom(tabId, geom)
    }

    /**
     * Minimize [paneId] to the dock (leaves the canvas; its geometry is kept
     * for restore).
     *
     * @param tabId  the pane's tab.
     * @param paneId the pane to minimize.
     */
    suspend fun minimize(tabId: String, paneId: String) = setMinimized(tabId, paneId, true)

    /**
     * Restore [paneId] from the dock back to its stored geometry, and raise it.
     *
     * @param tabId  the pane's tab.
     * @param paneId the pane to restore.
     */
    suspend fun restore(tabId: String, paneId: String) {
        val geom = fullGeomForTab(tabId)
        val cur = geom[paneId] ?: return
        geom[paneId] = cur.copy(isMinimized = false, zIndex = maxZ(geom) + 1)
        persistTabGeom(tabId, geom)
    }

    /**
     * Apply [preset] to [tabId], placing the focused pane in slot 0 and the
     * remaining (non-minimized) panes in the preset's other slots. Records the
     * preset key and pane order so the geometry reads as preset-driven.
     *
     * @param tabId  the tab to rearrange.
     * @param preset the layout preset to apply.
     */
    suspend fun applyLayout(tabId: String, preset: LayoutPreset) {
        val tab = latestConfig?.tabs?.firstOrNull { it.id == tabId } ?: return
        val geom = fullGeomForTab(tabId)
        val visible = tab.panes.map { it.leaf.id }.filter { geom[it]?.isMinimized != true }
        if (visible.isEmpty()) return
        val focused = tab.focusedPaneId?.takeIf { it in visible }
        val ordered = listOfNotNull(focused) + visible.filter { it != focused }
        val boxes = preset.computeBoxes(ordered.size, DEFAULT_LAYOUT_GRID)
        val newGeom = geom.toMutableMap()
        ordered.forEachIndexed { i, id ->
            val b = boxes.getOrNull(i) ?: return@forEachIndexed
            val c = geom[id] ?: return@forEachIndexed
            newGeom[id] = c.copy(
                xPct = b.x, yPct = b.y, widthPct = b.width, heightPct = b.height,
                zIndex = i + 1, isMaximized = false, isMinimized = false,
            )
        }
        persistTabGeom(tabId, newGeom, preset = preset.key, order = ordered)
    }

    /**
     * Apply the preset identified by [presetKey] to [tabId]. A
     * string-keyed convenience over [applyLayout] for front-ends (notably the
     * SwiftUI iOS layer) that drive the layout sheet by [LayoutPreset.key]
     * rather than holding a bridged [LayoutPreset] enum value.
     *
     * @param tabId     the tab to rearrange.
     * @param presetKey a [LayoutPreset.key]; unknown keys (including `"custom"`)
     *   no-op.
     */
    suspend fun applyLayoutByKey(tabId: String, presetKey: String) {
        val preset = LayoutPreset.fromKey(presetKey) ?: return
        applyLayout(tabId, preset)
    }

    /**
     * The persisted layout-preset key for [tabId] from the toolkit
     * `LAYOUT_STATE` blob, or `null` when none is recorded. Drives the layout
     * sheet's "current layout" marker. Reads the latest blob captured during
     * projection, so it reflects the live server state.
     *
     * @param tabId the tab to look up.
     * @return the preset key (e.g. `"auto"`, `"grid"`, `"custom"`), or `null`.
     */
    fun activePresetKey(tabId: String): String? =
        WindowLayoutState.parse(latestRawLayout).presetByTab[tabId]

    // ── Edit-layout mode (unified free move + resize) ───────────────

    /**
     * Enter edit-layout mode for [tabId]. Every pane in the tab becomes freely
     * draggable (move) and resizable (corner handle) until [exitEdit].
     *
     * @param tabId the tab to edit.
     */
    fun enterEdit(tabId: String) {
        _editTabId.value = tabId
    }

    /** Leave edit-layout mode, discarding any uncommitted in-flight drag. */
    fun exitEdit() {
        _editTabId.value = null
        _drag.value = null
    }

    /**
     * Begin a drag (move or resize) on [paneId], seeding the live box from its
     * current geometry. Called on gesture start; the move/resize distinction is
     * decided by the caller (body drag vs. corner-handle drag).
     *
     * @param tabId  the pane's tab.
     * @param paneId the pane being grabbed.
     */
    fun beginDrag(tabId: String, paneId: String) {
        val cur = fullGeomForTab(tabId)[paneId] ?: return
        _drag.value = Drag(tabId, paneId, PaneBox(cur.xPct, cur.yPct, cur.widthPct, cur.heightPct))
    }

    /**
     * Translate the dragged pane by a fractional delta. Reads the live box
     * fresh so gesture callbacks never apply a stale offset.
     *
     * @param dxFrac horizontal delta as a fraction of the tab width.
     * @param dyFrac vertical delta as a fraction of the tab height.
     */
    fun dragMoveBy(dxFrac: Double, dyFrac: Double) {
        val d = _drag.value ?: return
        _drag.value = d.copy(box = clampBox(d.box.copy(x = d.box.x + dxFrac, y = d.box.y + dyFrac)))
    }

    /**
     * Grow/shrink the dragged pane by a fractional delta (corner-handle drag).
     *
     * @param dwFrac width delta as a fraction of the tab width.
     * @param dhFrac height delta as a fraction of the tab height.
     */
    fun dragResizeBy(dwFrac: Double, dhFrac: Double) {
        val d = _drag.value ?: return
        _drag.value = d.copy(box = clampBox(d.box.copy(width = d.box.width + dwFrac, height = d.box.height + dhFrac)))
    }

    /**
     * Commit the in-flight drag to `LAYOUT_STATE` (snapped to the 5% grid) and
     * raise the pane to the front. No-op if no drag is in flight. Stays in edit
     * mode so the user can keep arranging.
     */
    suspend fun endDrag() {
        val d = _drag.value ?: return
        _drag.value = null
        val norm = PaneGeometry.normalize(d.box.x, d.box.y, d.box.width, d.box.height)
        val geom = fullGeomForTab(d.tabId)
        val cur = geom[d.paneId] ?: return
        geom[d.paneId] = cur.copy(
            xPct = norm.x, yPct = norm.y, widthPct = norm.width, heightPct = norm.height,
            zIndex = maxZ(geom) + 1, isMaximized = false,
        )
        // Manual placement detaches the tab from any active preset.
        persistTabGeom(d.tabId, geom, preset = LayoutPreset.Custom.key)
    }

    // ── Geometry helpers ────────────────────────────────────────────

    /**
     * Build the full `paneId -> geometry` map for [tabId] from the latest
     * config + toolkit geometry, falling back to the config's placeholder
     * fields where the blob has no entry (so a never-touched layout still
     * seeds consistent geometry on first edit).
     */
    private fun fullGeomForTab(tabId: String): MutableMap<String, LayoutGeom> {
        val tab = latestConfig?.tabs?.firstOrNull { it.id == tabId } ?: return mutableMapOf()
        val serverTab = latestGeometry[tabId].orEmpty()
        // Prefer any not-yet-echoed optimistic override so successive actions
        // build on the latest local state, then server geometry, then the
        // config's placeholder fields.
        val localTab = _localGeom.value[tabId].orEmpty()
        val out = LinkedHashMap<String, LayoutGeom>()
        for (p in tab.panes) {
            val local = localTab[p.leaf.id]
            val g = serverTab[p.leaf.id]
            out[p.leaf.id] = local ?: LayoutGeom(
                xPct = g?.xPct ?: p.x,
                yPct = g?.yPct ?: p.y,
                widthPct = g?.widthPct ?: p.width,
                heightPct = g?.heightPct ?: p.height,
                zIndex = g?.zIndex ?: p.z.toInt(),
                isMaximized = g?.isMaximized ?: p.maximized,
                isMinimized = g?.isMinimized ?: false,
            )
        }
        return out
    }

    private suspend fun raiseZ(tabId: String, paneId: String) {
        val geom = fullGeomForTab(tabId)
        val cur = geom[paneId] ?: return
        geom[paneId] = cur.copy(zIndex = maxZ(geom) + 1)
        persistTabGeom(tabId, geom)
    }

    private suspend fun setMinimized(tabId: String, paneId: String, minimized: Boolean) {
        val geom = fullGeomForTab(tabId)
        val cur = geom[paneId] ?: return
        geom[paneId] = cur.copy(
            isMinimized = minimized,
            isMaximized = if (minimized) false else cur.isMaximized,
        )
        persistTabGeom(tabId, geom)
    }

    private suspend fun persistTabGeom(
        tabId: String,
        geom: Map<String, LayoutGeom>,
        preset: String? = null,
        order: List<String>? = null,
    ) {
        // Optimistically apply locally first so the change is reflected
        // immediately — and at all in demo mode, where the persist below is a
        // no-op and no server echo ever arrives.
        _localGeom.value = _localGeom.value + (tabId to geom)
        val persister = settingsPersister ?: return
        val blob = WindowLayoutState.parse(latestRawLayout)
            .withTabGeometry(tabId, geom, preset = preset, order = order)
        persister.putSetting(PersistKeys.LAYOUT_STATE, blob.encode())
    }

    private fun maxZ(geom: Map<String, LayoutGeom>): Int =
        geom.values.maxOfOrNull { it.zIndex } ?: 0

    private fun clampBox(box: PaneBox): PaneBox {
        val w = box.width.coerceIn(PaneGeometry.MIN_SIZE, 1.0)
        val h = box.height.coerceIn(PaneGeometry.MIN_SIZE, 1.0)
        val x = box.x.coerceIn(0.0, 1.0 - w)
        val y = box.y.coerceIn(0.0, 1.0 - h)
        return PaneBox(x, y, w, h)
    }

    // ── Projection ──────────────────────────────────────────────────

    private fun project(
        config: WindowConfig,
        states: Map<String, String?>,
        geometry: Map<String, Map<String, ToolkitPaneGeometry>>,
        localGeom: Map<String, Map<String, LayoutGeom>>,
    ): State {
        // A hidden ("unlisted") tab is surfaced in the strip only while it is
        // the active tab — so the user can always see and act on the tab they
        // are actually looking at. It drops back out of the strip once another
        // tab is activated. Mirrors the web/Mac tab strip behaviour.
        val activeTabId = config.activeTabId
            ?.takeIf { id -> config.tabs.any { it.id == id } }
            ?: config.tabs.firstOrNull { !it.isHidden }?.id
        val visibleTabs = config.tabs.filter { !it.isHidden || it.id == activeTabId }

        val tabs = visibleTabs.map { tab ->
            // Server geometry overlaid by any optimistic local override.
            val effective = effectiveGeom(geometry[tab.id].orEmpty(), localGeom[tab.id])
            OverviewTab(
                id = tab.id,
                title = tab.title,
                isActive = tab.id == activeTabId,
                aggregateState = aggregateState(tab, states),
                panes = projectPanes(tab, states, effective),
                dock = projectDock(tab, states, effective),
                isHidden = tab.isHidden,
                isHiddenFromSidebar = tab.isHiddenFromSidebar,
            )
        }
        // Hidden tabs other than the active one — offered via the overview's
        // "unlisted tabs" affordance so they stay reachable.
        val unlistedTabs = config.tabs
            .filter { it.isHidden && it.id != activeTabId }
            .map { UnlistedTab(id = it.id, title = it.title) }
        return State(tabs = tabs, activeTabId = activeTabId, unlistedTabs = unlistedTabs)
    }

    /** Server geometry (as [LayoutGeom]) with the optimistic local override
     *  (if any) layered on top, per pane. */
    private fun effectiveGeom(
        serverTab: Map<String, ToolkitPaneGeometry>,
        localTab: Map<String, LayoutGeom>?,
    ): Map<String, LayoutGeom> {
        val merged = serverTab.mapValues { it.value.toLayoutGeom() }.toMutableMap()
        if (localTab != null) merged.putAll(localTab)
        return merged
    }

    private fun projectPanes(
        tab: TabConfig,
        states: Map<String, String?>,
        tabGeometry: Map<String, LayoutGeom>,
    ): List<OverviewPane> {
        // On-canvas panes only — docked (minimized) panes go to the dock strip.
        val canvasPanes = tab.panes.filterNot { tabGeometry[it.leaf.id]?.isMinimized == true }

        val focusedPaneId = tab.focusedPaneId
            ?.takeIf { id -> canvasPanes.any { it.leaf.id == id } }
            ?: canvasPanes.firstOrNull()?.leaf?.id

        return canvasPanes
            .map { pane ->
                val geom = tabGeometry[pane.leaf.id]
                OverviewPane(
                    leaf = pane.leaf,
                    x = geom?.xPct ?: pane.x,
                    y = geom?.yPct ?: pane.y,
                    width = geom?.widthPct ?: pane.width,
                    height = geom?.heightPct ?: pane.height,
                    z = geom?.zIndex?.toLong() ?: pane.z,
                    maximized = geom?.isMaximized ?: pane.maximized,
                    isFocused = pane.leaf.id == focusedPaneId,
                    sessionState = states[pane.leaf.sessionId],
                )
            }
            .sortedBy { it.z }
    }

    private fun projectDock(
        tab: TabConfig,
        states: Map<String, String?>,
        tabGeometry: Map<String, LayoutGeom>,
    ): List<DockedPane> =
        tab.panes
            .filter { tabGeometry[it.leaf.id]?.isMinimized == true }
            .map { DockedPane(leaf = it.leaf, sessionState = states[it.leaf.sessionId]) }

    /** Convert a server [ToolkitPaneGeometry] into the local [LayoutGeom] shape. */
    private fun ToolkitPaneGeometry.toLayoutGeom(): LayoutGeom = LayoutGeom(
        xPct = xPct, yPct = yPct, widthPct = widthPct, heightPct = heightPct,
        zIndex = zIndex, isMaximized = isMaximized, isMinimized = isMinimized,
    )

    private fun aggregateState(tab: TabConfig, states: Map<String, String?>): String? {
        var result: String? = null
        for (pane in tab.panes) {
            when (states[pane.leaf.sessionId]) {
                "waiting" -> return "waiting"
                "working" -> if (result != "working") result = "working"
            }
        }
        return result
    }
}
