/**
 * The in-process server simulation behind demo mode. Owns the mutable
 * [WindowConfig], the per-session state map, the demo PTY sessions, and a
 * [WindowEnvelope] stream — and applies incoming [WindowCommand]s the same
 * way the real server's `handleWindowCommand` does, so the demo feels live:
 * panes can be moved, resized, renamed, re-laid-out, added, and closed.
 *
 * Only the effects that genuinely require a machine behind them are canned:
 * terminal command execution ([DemoTerminalSession]), the file tree, git
 * diffs, and Claude usage all come from [DemoFixtures]. Nothing persists —
 * reloading the page resets the demo to its initial state by design.
 *
 * Layout-preset geometry is computed with the same toolkit code
 * ([LayoutPreset.computeBoxes]) the real server uses, so `ApplyLayout`
 * produces identical arrangements.
 *
 * @see DemoWindowSocket
 * @see DemoPtySocket
 * @see DemoFixtures
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import se.soderbjorn.darkness.web.layout.GridSpec
import se.soderbjorn.darkness.web.layout.LayoutPreset
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.FileBrowserEntry
import se.soderbjorn.lunamux.FileBrowserSort
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.PaneGeometry
import se.soderbjorn.lunamux.SyntaxHighlighter
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TerminalContent
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository

/**
 * Snap grid for layout presets — identical to the real server's
 * `PANE_LAYOUT_GRID` so demo layouts match server layouts exactly.
 */
private val DEMO_LAYOUT_GRID = GridSpec(cols = 20, rows = 20)

/**
 * The demo "server": a deterministic, in-memory implementation of the
 * window-command protocol.
 *
 * Created once per demo-mode
 * [se.soderbjorn.lunamux.client.LunamuxClient]; seeds
 * [WindowStateRepository] with the fixture config at construction so
 * `awaitInitialConfig` completes immediately on every platform.
 *
 * @param scope the client's long-lived coroutine scope.
 * @param windowState the client's config/state cache, updated on every
 *   simulated mutation exactly like [se.soderbjorn.lunamux.client.RealWindowSocket]
 *   does for real envelopes.
 */
class DemoServer internal constructor(
    private val scope: CoroutineScope,
    private val windowState: WindowStateRepository,
) {
    /** Serialises command handling so concurrent sends can't interleave. */
    private val mutex = Mutex()

    /** The authoritative (simulated) window layout. */
    private var config: WindowConfig = DemoFixtures.initialConfig()

    /** The authoritative (simulated) per-session state map. */
    private val states: MutableMap<String, String?> = DemoFixtures.initialStates.toMutableMap()

    private val _envelopes = MutableSharedFlow<WindowEnvelope>(extraBufferCapacity = 128)

    /** Every simulated server→client message, mirrored to all demo sockets. */
    val envelopes: SharedFlow<WindowEnvelope> = _envelopes.asSharedFlow()

    /** Live demo PTY sessions keyed by session id. */
    private val sessions = mutableMapOf<String, DemoTerminalSession>()

    /** Deterministic id counter for panes/tabs/sessions created at runtime. */
    private var nextId = 100

    /**
     * The simulated persisted `ui-settings` blob — the in-memory analogue of
     * the real server's settings store. The mobile overview authors the
     * toolkit-owned `LAYOUT_STATE` here for every geometry edit (move / resize /
     * maximize / minimize / layout); [applyUiSettings] merges into it and
     * mirrors the result into [windowState] so the layout survives a screen
     * teardown the way it does against a real server. Starts empty — geometry
     * then falls back to the fixture config's pane fields, exactly like a fresh
     * real session with no persisted `LAYOUT_STATE`.
     */
    private val uiSettings: MutableMap<String, JsonElement> = mutableMapOf()

    init {
        for (spec in demoSessionSpecs()) {
            sessions[spec.sessionId] = createSession(spec)
        }
        windowState.updateConfig(config)
        windowState.updateStates(states.toMap())
        // Push the canned Claude usage as soon as anything subscribes to the
        // envelope stream (the badge has no other way to learn about it).
        scope.launch {
            _envelopes.subscriptionCount.filter { it > 0 }.first()
            _envelopes.emit(WindowEnvelope.ClaudeUsage(DemoFixtures.claudeUsage))
        }
    }

    /**
     * Look up (or lazily create) the demo PTY session for [sessionId].
     * Sessions referenced by the fixture config are pre-seeded; anything
     * else gets a fresh interactive shell — covers panes the user creates
     * at runtime.
     *
     * @param sessionId the session to attach to.
     * @return the simulated session.
     */
    fun session(sessionId: String): DemoTerminalSession =
        sessions.getOrPut(sessionId) { createSession(newShellSessionSpec(sessionId)) }

    /**
     * Construct a [DemoTerminalSession] with its agent-activity callback
     * wired to this server's state map: a script run starting flips the
     * session to `working`, a run finishing (or being Ctrl-C'd) clears the
     * state — publishing a [WindowEnvelope.State] each time, exactly like
     * the real server's Claude state detector. This is what makes the
     * finished demo Claude pane light up as "working" when the user types
     * into it, and stop pulsing when the burst completes.
     *
     * @param spec the session's canned content and scripts.
     * @return the wired session.
     */
    private fun createSession(spec: DemoSessionSpec): DemoTerminalSession {
        val session = DemoTerminalSession(spec, scope)
        session.onAgentActivity = { active ->
            scope.launch { setAgentState(spec.sessionId, if (active) "working" else null) }
        }
        return session
    }

    /**
     * Set (or clear, with `null`) one session's AI state and broadcast the
     * new state map — no-op when the value is already current, so repeated
     * activity callbacks don't spam every status consumer.
     *
     * @param sessionId the session whose state changed.
     * @param state the new state label (`"working"`), or `null` for idle.
     */
    private suspend fun setAgentState(sessionId: String, state: String?) {
        mutex.withLock {
            if (states[sessionId] == state) return
            if (state == null) states.remove(sessionId) else states[sessionId] = state
            publishStates()
        }
    }

    /**
     * Apply one [WindowCommand] the way the real server would, updating the
     * simulated config and emitting reply envelopes.
     *
     * Called by [DemoWindowSocket.send].
     *
     * @param command the client command to simulate.
     */
    suspend fun handle(command: WindowCommand) {
        mutex.withLock { dispatch(command) }
    }

    /**
     * Persist a `ui-settings` patch the way the real server's
     * `POST /api/ui-settings` does: merge it into the stored [uiSettings],
     * mirror the merged blob into [windowState] (so `geometryByTab` /
     * `rawLayoutState` reflect it for the whole client lifetime), and broadcast
     * a [WindowEnvelope.UiSettings] so envelope subscribers re-sync too.
     *
     * Called (in demo mode) by the overview's settings persister for every
     * geometry edit. Without it, demo-mode layout/geometry changes lived only
     * in the overview view-model's optimistic overrides and were lost the
     * moment that view-model was recreated — e.g. opening a pane and navigating
     * back dropped the layout the user had just picked.
     *
     * @param patch the settings key/values to merge (verbatim JSON); the
     *   overview sends the toolkit `LAYOUT_STATE` blob under that key.
     */
    suspend fun applyUiSettings(patch: JsonObject) {
        if (patch.isEmpty()) return
        mutex.withLock {
            uiSettings.putAll(patch)
            val merged = JsonObject(uiSettings.toMap())
            windowState.updateUiSettings(merged)
            emit(WindowEnvelope.UiSettings(merged))
        }
    }

    /**
     * Reset the whole simulation to its fixture starting point — the state a
     * fresh page load boots into: the fixture [WindowConfig] and session
     * state map are restored and re-published, sessions created at runtime
     * are reaped, every fixture session restarts its canned content **in
     * place** ([DemoTerminalSession.restart], so already-attached terminals
     * rewind live), and the simulated persisted `ui-settings` blob is
     * emptied back to its boot state.
     *
     * Called by the web client's demo tour right before the choreography
     * plays: the tour is written against the fixture workspace, so whatever
     * the visitor moved, closed, typed, or created must be rolled back
     * first. The id counter is deliberately NOT reset — a recycled pane id
     * could collide with a same-id pane still animating out in a client.
     */
    suspend fun resetToFixtures() {
        mutex.withLock {
            states.clear()
            states.putAll(DemoFixtures.initialStates)
            // Force a full re-broadcast of the fixture config, bypassing
            // publish()'s "identical config" no-op guard: a visitor edit may
            // live only in a client's local view (the open 3D ring's own pane
            // list, an optimistic removal) while the server's `config` still
            // equals the fixture, in which case publish() would emit nothing
            // and the ring would keep the visitor's world. Re-asserting the
            // fixture config unconditionally makes the tour always start from
            // the fixture workspace no matter what the visitor did.
            val fresh = DemoFixtures.initialConfig()
            config = fresh
            windowState.updateConfig(fresh)
            emit(WindowEnvelope.Config(fresh))
            publishStates()
            // Runtime-created panes are gone from the fixture config now;
            // drop their sessions the same way a pane close would.
            reapOrphanSessions()
            // Fixture sessions: restart survivors in place, re-create any the
            // visitor closed along the way (their session was reaped then).
            for (spec in demoSessionSpecs()) {
                val existing = sessions[spec.sessionId]
                if (existing == null) sessions[spec.sessionId] = createSession(spec)
                else existing.restart()
            }
            if (uiSettings.isNotEmpty()) {
                uiSettings.clear()
                val empty = JsonObject(emptyMap())
                windowState.updateUiSettings(empty)
                emit(WindowEnvelope.UiSettings(empty))
            }
        }
    }

    // -- internal plumbing ---------------------------------------------------

    /** Emit [envelope] to every subscribed demo socket. */
    private suspend fun emit(envelope: WindowEnvelope) {
        _envelopes.emit(envelope)
    }

    /**
     * Replace the config, mirror it into [windowState], and broadcast it.
     *
     * No-op when nothing actually changed: many commands arrive that leave
     * the config identical (focusing the already-focused pane, raising the
     * already-top pane, identity content updates), and broadcasting them
     * anyway makes every consumer — the toolkit shell, the envelope router,
     * the theme painters — re-render for nothing. The real server's
     * mutations are similarly conditional, so this also keeps demo timing
     * closer to production.
     */
    private suspend fun publish(newConfig: WindowConfig) {
        if (newConfig == config) return
        config = newConfig
        windowState.updateConfig(newConfig)
        emit(WindowEnvelope.Config(newConfig))
    }

    /** Mirror and broadcast the state map after a mutation. */
    private suspend fun publishStates() {
        val snapshot = states.toMap()
        windowState.updateStates(snapshot)
        emit(WindowEnvelope.State(snapshot))
    }

    /** Apply [transform] to the tab with [tabId] and publish the result. */
    private suspend fun updateTab(tabId: String, transform: (TabConfig) -> TabConfig) {
        publish(config.copy(tabs = config.tabs.map { if (it.id == tabId) transform(it) else it }))
    }

    /** Apply [transform] to the pane with [paneId] (wherever it lives) and publish. */
    private suspend fun updatePane(paneId: String, transform: (Pane) -> Pane) {
        publish(
            config.copy(
                tabs = config.tabs.map { tab ->
                    tab.copy(panes = tab.panes.map { if (it.leaf.id == paneId) transform(it) else it })
                },
            ),
        )
    }

    /** Find the pane with [paneId], or `null`. */
    private fun findPane(paneId: String): Pane? =
        config.tabs.asSequence().flatMap { it.panes }.firstOrNull { it.leaf.id == paneId }

    /** Find the tab containing [paneId], or `null`. */
    private fun findTabOf(paneId: String): TabConfig? =
        config.tabs.firstOrNull { tab -> tab.panes.any { it.leaf.id == paneId } }

    /** Next deterministic id with the given prefix (`demo-p104`, …). */
    private fun allocId(prefix: String): String = "demo-$prefix${nextId++}"

    /** Highest z in [tab], or 0 when empty. */
    private fun maxZ(tab: TabConfig): Long = tab.panes.maxOfOrNull { it.z } ?: 0L

    /**
     * Re-arrange [tab]'s panes according to [layoutKey], giving the primary
     * slot to [primaryPaneId] (falling back to the focused pane, then the
     * first). Uses the exact toolkit geometry the real server uses.
     *
     * @return the re-arranged tab.
     */
    private fun applyLayoutToTab(tab: TabConfig, layoutKey: String, primaryPaneId: String?): TabConfig {
        if (tab.panes.isEmpty()) return tab.copy(layoutPreset = layoutKey)
        val preset = LayoutPreset.fromKey(layoutKey)
            ?.takeIf { it != LayoutPreset.Custom }
            ?: LayoutPreset.Grid
        val boxes = preset.computeBoxes(tab.panes.size, DEMO_LAYOUT_GRID)
        val primary = tab.panes.firstOrNull { it.leaf.id == primaryPaneId }
            ?: tab.panes.firstOrNull { it.leaf.id == tab.focusedPaneId }
            ?: tab.panes.first()
        val ordered = listOf(primary) + tab.panes.filter { it !== primary }
        val rearranged = ordered.mapIndexed { i, pane ->
            val box = boxes[i]
            pane.copy(
                x = box.x, y = box.y, width = box.width, height = box.height,
                z = (i + 1).toLong(), maximized = false,
            )
        }
        return tab.copy(panes = rearranged, layoutPreset = layoutKey)
    }

    /** Re-tile [tab] if its preset is `auto` (mirrors the server's re-tiling). */
    private fun retileIfAuto(tab: TabConfig): TabConfig =
        if (tab.layoutPreset == "auto") applyLayoutToTab(tab, "auto", tab.focusedPaneId) else tab

    /** The demo pane title for [cwd], like the server's prettifier. */
    private fun titleFor(cwd: String?): String = DemoFixtures.prettify(cwd) ?: "zsh"

    /**
     * Remove every session that no pane references any more, mirroring the
     * server killing PTYs when their last pane closes.
     *
     * Suspends because dropping a reaped session's AI state must be broadcast:
     * the tab status icons and the global activity indicator are derived from
     * the published [WindowEnvelope.State] snapshot, so a closed pane whose
     * session was `working`/`waiting` would otherwise keep pulsing forever.
     */
    private suspend fun reapOrphanSessions() {
        val referenced = config.tabs.asSequence()
            .flatMap { it.panes }
            .map { it.leaf.sessionId }
            .filter { it.isNotEmpty() }
            .toSet()
        // `close()` each orphan rather than just dropping it from the
        // map: the session's input-consumer coroutine runs on the
        // client's long-lived scope and would otherwise survive the
        // reap, leaking one parked coroutine (plus the session it pins)
        // per closed pane for the lifetime of the page.
        val orphans = sessions.keys.filter { it !in referenced }
        for (id in orphans) sessions.remove(id)?.close()
        // `retainAll` reports whether it removed anything; only republish (and
        // re-render every status consumer) when a state actually disappeared.
        if (states.keys.retainAll { it in referenced }) {
            publishStates()
        }
    }

    // -- command dispatch ----------------------------------------------------

    /** The big `when` mirroring the real server's `handleWindowCommand`. */
    private suspend fun dispatch(command: WindowCommand) {
        when (command) {
            // --- tabs ---
            is WindowCommand.AddTab -> {
                val tab = TabConfig(
                    id = allocId("t"),
                    title = "tab ${config.tabs.size + 1}",
                    // Default new tabs to auto-tiling, matching the real
                    // server (issue #86).
                    layoutPreset = LayoutPreset.Auto.key,
                )
                publish(config.copy(tabs = config.tabs + tab, activeTabId = tab.id))
            }
            is WindowCommand.CloseTab -> {
                val remaining = config.tabs.filter { it.id != command.tabId }
                val active = if (config.activeTabId == command.tabId) {
                    remaining.firstOrNull { !it.isHidden }?.id ?: remaining.firstOrNull()?.id
                } else {
                    config.activeTabId
                }
                publish(config.copy(tabs = remaining, activeTabId = active))
                reapOrphanSessions()
            }
            is WindowCommand.RenameTab -> updateTab(command.tabId) { it.copy(title = command.title) }
            is WindowCommand.MoveTab -> {
                val moving = config.tabs.firstOrNull { it.id == command.tabId } ?: return
                val without = config.tabs.filter { it.id != command.tabId }
                val targetIdx = without.indexOfFirst { it.id == command.targetTabId }
                if (targetIdx < 0) return
                val insertAt = if (command.before) targetIdx else targetIdx + 1
                val reordered = without.take(insertAt) + moving + without.drop(insertAt)
                publish(config.copy(tabs = reordered))
            }
            is WindowCommand.SetTabHidden -> {
                var newConfig = config.copy(
                    tabs = config.tabs.map {
                        if (it.id == command.tabId) it.copy(isHidden = command.hidden) else it
                    },
                )
                if (command.hidden && newConfig.activeTabId == command.tabId) {
                    val nextVisible = newConfig.tabs.firstOrNull { !it.isHidden }
                    newConfig = newConfig.copy(activeTabId = nextVisible?.id ?: newConfig.activeTabId)
                }
                publish(newConfig)
            }
            is WindowCommand.SetTabHiddenFromSidebar ->
                updateTab(command.tabId) { it.copy(isHiddenFromSidebar = command.hidden) }
            is WindowCommand.SetActiveTab -> publish(config.copy(activeTabId = command.tabId))
            is WindowCommand.SetFocusedPane ->
                updateTab(command.tabId) { it.copy(focusedPaneId = command.paneId) }

            // --- pane lifecycle ---
            is WindowCommand.AddPaneToTab -> {
                val sessionId = allocId("s")
                session(sessionId) // eagerly create so attach replays a prompt
                addLeafToTab(
                    command.tabId,
                    LeafNode(
                        id = allocId("p"),
                        sessionId = sessionId,
                        title = titleFor(command.cwd ?: DemoFixtures.CWD),
                        cwd = command.cwd ?: DemoFixtures.CWD,
                        content = TerminalContent(sessionId = sessionId),
                    ),
                )
            }
            is WindowCommand.AddFileBrowserToTab -> addLeafToTab(
                command.tabId,
                LeafNode(
                    id = allocId("p"),
                    sessionId = "",
                    title = titleFor(command.cwd ?: DemoFixtures.CWD),
                    cwd = command.cwd ?: DemoFixtures.CWD,
                    content = FileBrowserContent(),
                ),
            )
            is WindowCommand.AddGitToTab -> addLeafToTab(
                command.tabId,
                LeafNode(
                    id = allocId("p"),
                    sessionId = "",
                    title = titleFor(command.cwd ?: DemoFixtures.CWD),
                    cwd = command.cwd ?: DemoFixtures.CWD,
                    content = GitContent(),
                ),
            )
            is WindowCommand.AddLinkToTab -> {
                val original = config.tabs.asSequence().flatMap { it.panes }
                    .firstOrNull { it.leaf.sessionId == command.targetSessionId && !it.leaf.isLink }
                    ?: return
                addLeafToTab(
                    command.tabId,
                    original.leaf.copy(id = allocId("p"), isLink = true),
                )
            }
            is WindowCommand.Close -> {
                publish(
                    config.copy(
                        tabs = config.tabs.map { tab ->
                            if (tab.panes.none { it.leaf.id == command.paneId }) {
                                tab
                            } else {
                                retileIfAuto(tab.copy(panes = tab.panes.filter { it.leaf.id != command.paneId }))
                            }
                        },
                    ),
                )
                reapOrphanSessions()
            }
            is WindowCommand.CloseSession -> {
                publish(
                    config.copy(
                        tabs = config.tabs.map { tab ->
                            val filtered = tab.panes.filter { it.leaf.sessionId != command.sessionId }
                            if (filtered.size == tab.panes.size) tab else retileIfAuto(tab.copy(panes = filtered))
                        },
                    ),
                )
                reapOrphanSessions()
            }
            is WindowCommand.Rename -> updatePane(command.paneId) { pane ->
                val custom = command.title.ifBlank { null }
                pane.copy(
                    leaf = pane.leaf.copy(
                        customName = custom,
                        title = custom ?: titleFor(pane.leaf.cwd),
                    ),
                )
            }

            // --- geometry ---
            is WindowCommand.SetPaneGeom -> {
                val box = PaneGeometry.normalize(command.x, command.y, command.width, command.height)
                updatePane(command.paneId) {
                    it.copy(x = box.x, y = box.y, width = box.width, height = box.height)
                }
            }
            is WindowCommand.SetPaneZoom -> {
                // Mirror `PaneManager.setPaneZoom`: drop non-finite values and
                // clamp to the same range before storing the 3D-world zoom.
                if (command.zoom.isFinite()) {
                    updatePane(command.paneId) { it.copy(zoom = command.zoom.coerceIn(0.01, 100.0)) }
                }
            }
            is WindowCommand.RaisePane -> {
                val tab = findTabOf(command.paneId) ?: return
                val pane = tab.panes.firstOrNull { it.leaf.id == command.paneId } ?: return
                // Already on top: keep the z stable so this very common
                // command (sent on every pane click) doesn't fabricate a
                // new config and force a full re-render.
                if (pane.z == maxZ(tab)) return
                val top = maxZ(tab) + 1
                updatePane(command.paneId) { it.copy(z = top) }
            }
            is WindowCommand.ToggleMaximized -> {
                val tab = findTabOf(command.paneId) ?: return
                val target = tab.panes.firstOrNull { it.leaf.id == command.paneId } ?: return
                val top = maxZ(tab) + 1
                updateTab(tab.id) { t ->
                    t.copy(
                        panes = t.panes.map { pane ->
                            when {
                                pane.leaf.id == command.paneId ->
                                    pane.copy(maximized = !target.maximized, z = top)
                                else -> pane.copy(maximized = false)
                            }
                        },
                    )
                }
            }
            is WindowCommand.ApplyLayout -> updateTab(command.tabId) {
                applyLayoutToTab(it, command.layout, command.primaryPaneId)
            }
            is WindowCommand.MovePaneToTab -> {
                val sourceTab = findTabOf(command.paneId) ?: return
                val pane = sourceTab.panes.first { it.leaf.id == command.paneId }
                publish(
                    config.copy(
                        tabs = config.tabs.map { tab ->
                            when (tab.id) {
                                sourceTab.id ->
                                    retileIfAuto(tab.copy(panes = tab.panes.filter { it.leaf.id != command.paneId }))
                                command.targetTabId ->
                                    retileIfAuto(tab.copy(panes = tab.panes + pane.copy(z = maxZ(tab) + 1)))
                                else -> tab
                            }
                        },
                    ),
                )
            }
            is WindowCommand.SwapPanes -> {
                val tab = findTabOf(command.paneAId) ?: return
                val a = tab.panes.firstOrNull { it.leaf.id == command.paneAId } ?: return
                val b = tab.panes.firstOrNull { it.leaf.id == command.paneBId } ?: return
                val top = maxZ(tab) + 1
                updateTab(tab.id) { t ->
                    t.copy(
                        panes = t.panes.map { pane ->
                            when (pane.leaf.id) {
                                a.leaf.id -> pane.copy(x = b.x, y = b.y, width = b.width, height = b.height, z = top)
                                b.leaf.id -> pane.copy(x = a.x, y = a.y, width = a.width, height = a.height)
                                else -> pane
                            }
                        },
                    )
                }
            }
            is WindowCommand.MovePaneWithinTab -> {
                val tab = findTabOf(command.paneId) ?: return
                val srcIdx = tab.panes.indexOfFirst { it.leaf.id == command.paneId }
                val moving = tab.panes.getOrNull(srcIdx) ?: return
                if (tab.panes.none { it.leaf.id == command.targetPaneId }) return
                val without = tab.panes.filter { it.leaf.id != command.paneId }
                val tgtIdx = without.indexOfFirst { it.leaf.id == command.targetPaneId }
                val insertAt = if (command.before) tgtIdx else tgtIdx + 1
                if (insertAt == srcIdx) return
                val reordered = without.take(insertAt) + moving + without.drop(insertAt)
                updateTab(tab.id) { retileIfAuto(it.copy(panes = reordered)) }
            }

            // --- per-pane content settings ---
            is WindowCommand.SetTerminalFontSize -> updateContent<TerminalContent>(command.paneId) {
                it.copy(fontSize = command.size)
            }
            is WindowCommand.SetTerminalAutoReflow -> updateContent<TerminalContent>(command.paneId) {
                it.copy(autoReflow = command.enabled)
            }
            is WindowCommand.SetFileBrowserLeftWidth -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(leftColumnWidthPx = command.px)
            }
            is WindowCommand.SetFileBrowserAutoRefresh -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(autoRefresh = command.enabled)
            }
            is WindowCommand.SetFileBrowserFilter -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(fileFilter = command.filter.ifBlank { null })
            }
            is WindowCommand.SetFileBrowserSort -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(sortBy = command.sort)
            }
            is WindowCommand.SetFileBrowserFontSize -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(fontSize = command.size)
            }
            is WindowCommand.SetGitLeftWidth -> updateContent<GitContent>(command.paneId) {
                it.copy(leftColumnWidthPx = command.px)
            }
            is WindowCommand.SetGitDiffMode -> updateContent<GitContent>(command.paneId) {
                it.copy(diffMode = command.mode)
            }
            is WindowCommand.SetGitGraphicalDiff -> updateContent<GitContent>(command.paneId) {
                it.copy(graphicalDiff = command.enabled)
            }
            is WindowCommand.SetGitDiffFontSize -> updateContent<GitContent>(command.paneId) {
                it.copy(diffFontSize = command.size)
            }
            is WindowCommand.SetGitAutoRefresh -> updateContent<GitContent>(command.paneId) {
                it.copy(autoRefresh = command.enabled)
            }

            // --- file browser RPCs ---
            is WindowCommand.FileBrowserListDir -> replyListDir(command.paneId, command.dirRelPath)
            is WindowCommand.FileBrowserOpenFile -> {
                val content = DemoFixtures.fileContents[command.relPath]
                if (content == null) {
                    emit(WindowEnvelope.FileBrowserError(command.paneId, "No demo content for ${command.relPath}"))
                } else {
                    emit(
                        WindowEnvelope.FileBrowserContentMsg(
                            paneId = command.paneId,
                            relPath = command.relPath,
                            kind = content.kind,
                            html = content.html,
                            language = content.language,
                        ),
                    )
                    updateContent<FileBrowserContent>(command.paneId) {
                        it.copy(selectedRelPath = command.relPath)
                    }
                }
            }
            is WindowCommand.FileBrowserSetExpanded -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(
                    expandedDirs = if (command.expanded) {
                        it.expandedDirs + command.dirRelPath
                    } else {
                        it.expandedDirs - command.dirRelPath
                    },
                )
            }
            is WindowCommand.FileBrowserExpandAll -> {
                for (dir in DemoFixtures.dirListings.keys) replyListDir(command.paneId, dir)
                updateContent<FileBrowserContent>(command.paneId) {
                    it.copy(expandedDirs = DemoFixtures.dirListings.keys)
                }
            }
            is WindowCommand.FileBrowserCollapseAll -> updateContent<FileBrowserContent>(command.paneId) {
                it.copy(expandedDirs = emptySet())
            }

            // --- git RPCs ---
            is WindowCommand.GitList -> emit(WindowEnvelope.GitList(command.paneId, DemoFixtures.gitEntries))
            is WindowCommand.GitDiff -> {
                val diff = DemoFixtures.gitDiffs[command.filePath]
                if (diff == null) {
                    emit(WindowEnvelope.GitError(command.paneId, "No demo diff for ${command.filePath}"))
                } else {
                    // Highlight each diff line's content the same way the real
                    // server does (WindowRoutes' GitDiff path) so demo diffs are
                    // coloured identically. Fixtures store raw text; the web
                    // client renders `DiffLine.content` via innerHTML, so the
                    // highlighter's escaping also keeps `<`/`&` safe.
                    val highlightedHunks = diff.hunks.map { hunk ->
                        hunk.copy(
                            lines = hunk.lines.map { line ->
                                line.copy(content = SyntaxHighlighter.highlight(line.content, diff.language))
                            },
                        )
                    }
                    emit(
                        WindowEnvelope.GitDiffResult(
                            paneId = command.paneId,
                            filePath = command.filePath,
                            hunks = highlightedHunks,
                            language = diff.language,
                            oldContent = diff.oldContent,
                            newContent = diff.newContent,
                        ),
                    )
                    updateContent<GitContent>(command.paneId) {
                        it.copy(selectedFilePath = command.filePath)
                    }
                }
            }

            // --- misc ---
            is WindowCommand.SetStateOverride -> {
                states[command.sessionId] = command.mode.ifBlank { null }
                publishStates()
            }
            is WindowCommand.OpenSettings -> emit(WindowEnvelope.UiSettings(buildJsonObject { }))
            is WindowCommand.RefreshUsage -> emit(WindowEnvelope.ClaudeUsage(DemoFixtures.claudeUsage))
            is WindowCommand.GetWorktreeDefaults -> emit(
                WindowEnvelope.WorktreeDefaults(
                    paneId = command.paneId,
                    repoName = DemoFixtures.WORKTREE_REPO_NAME,
                    siblingPath = DemoFixtures.WORKTREE_SIBLING,
                    dotWorktreesPath = DemoFixtures.WORKTREE_DOTDIR,
                    hasUncommittedChanges = true,
                ),
            )
            is WindowCommand.CreateWorktree -> emit(
                WindowEnvelope.WorktreeError(
                    paneId = command.paneId,
                    message = "Creating worktrees isn't available in the demo.",
                ),
            )
        }
    }

    /**
     * Append [leaf] as a new pane to [tabId]: under an `auto` preset the tab
     * re-tiles (the new pane gets slotted in), otherwise the pane lands as a
     * default-sized box on top of the stack.
     */
    private suspend fun addLeafToTab(tabId: String, leaf: LeafNode) {
        updateTab(tabId) { tab ->
            val size = PaneGeometry.DEFAULT_SIZE
            val pane = Pane(
                leaf = leaf,
                x = 0.3, y = 0.3, width = size, height = size,
                z = maxZ(tab) + 1,
            )
            retileIfAuto(tab.copy(panes = tab.panes + pane, focusedPaneId = leaf.id))
        }
    }

    /**
     * Update the typed [LeafContent] of [paneId] when it matches [T]; no-op
     * otherwise (mirrors the server ignoring mismatched pane kinds).
     */
    private suspend inline fun <reified T : LeafContent> updateContent(
        paneId: String,
        crossinline transform: (T) -> T,
    ) {
        updatePane(paneId) { pane ->
            val content = pane.leaf.content
            if (content is T) pane.copy(leaf = pane.leaf.copy(content = transform(content))) else pane
        }
    }

    /**
     * Emit the fixture listing for [dirRelPath] (with the pane's filter and
     * sort applied) or a demo error for unknown directories.
     */
    private suspend fun replyListDir(paneId: String, dirRelPath: String) {
        val raw = DemoFixtures.dirListings[dirRelPath]
        if (raw == null) {
            emit(WindowEnvelope.FileBrowserError(paneId, "No demo directory at \"$dirRelPath\""))
            return
        }
        val fb = findPane(paneId)?.leaf?.content as? FileBrowserContent
        var entries: List<FileBrowserEntry> = raw
        fb?.fileFilter?.takeIf { it.isNotBlank() }?.let { pattern ->
            val regex = globToRegex(pattern)
            entries = entries.filter { it.isDir || regex.matches(it.name) }
        }
        entries = when (fb?.sortBy ?: FileBrowserSort.NAME) {
            FileBrowserSort.NAME -> entries.sortedWith(
                compareByDescending<FileBrowserEntry> { it.isDir }.thenBy { it.name.lowercase() },
            )
            FileBrowserSort.MTIME -> entries.sortedWith(
                compareByDescending<FileBrowserEntry> { it.isDir }.thenByDescending { it.mtimeEpochMs },
            )
        }
        emit(WindowEnvelope.FileBrowserDir(paneId, dirRelPath, entries))
    }

    /** Translate a `*`/`?` glob into a [Regex] for the file-browser filter. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> {
                    if (!c.isLetterOrDigit()) sb.append('\\')
                    sb.append(c)
                }
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
