/**
 * Window WebSocket connection handler for the Termtastic web frontend.
 *
 * Manages the main WebSocket connection that carries configuration updates,
 * session state changes, Claude usage data, and pane content messages (file browser
 * listings/content, git file lists/diffs). Also handles the full DOM rendering
 * pipeline via [renderConfig].
 *
 * The [connectWindow] function sets up a coroutine that collects typed
 * [WindowEnvelope] messages from the [WindowSocket] and routes them to the
 * appropriate rendering functions.
 *
 * @see connectWindow
 * @see renderConfig
 * @see handlePaneContentMessage
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.showConfirmDialog

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.w3c.dom.HTMLElement

/**
 * Routes pane content messages to the appropriate rendering function.
 *
 * Handles the following message types:
 * - "fileBrowserDir": updates directory listing cache and re-renders the file tree
 * - "fileBrowserContent": updates file content and re-renders the preview panel
 * - "fileBrowserError": shows an error message in the preview panel
 * - "gitList": updates the changed files list and re-renders it
 * - "gitDiff": renders the diff in the appropriate mode (inline/split/graphical)
 * - "gitError": shows an error message in the diff panel
 * - "worktreeDefaults": opens the worktree creation dialog with server-computed defaults
 * - "worktreeCreated": no-op (config push handles the UI update)
 * - "worktreeError": shows an error dialog
 *
 * @param type the message type string, or null
 * @param parsed the dynamic parsed message object
 * @return true if the message was handled, false if the type was unrecognized
 */
fun handlePaneContentMessage(type: String?, parsed: dynamic): Boolean {
    when (type) {
        "fileBrowserDir" -> {
            val paneId = parsed.paneId as String
            val dirRelPath = parsed.dirRelPath as String
            val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
            state.dirListings[dirRelPath] = parsed.entries as Array<dynamic>
            val view = fileBrowserPaneViews[paneId]
            if (view != null) renderFileBrowserTree(paneId, view, state)
        }
        "fileBrowserContent" -> {
            val paneId = parsed.paneId as String
            val relPath = parsed.relPath as String
            val html = parsed.html as String
            val kind = parsed.kind as String
            val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
            state.selectedRelPath = relPath; state.html = html; state.kind = kind
            val view = fileBrowserPaneViews[paneId]
            if (view != null) {
                renderFileBrowserContent(paneId, view, kind, html)
                renderFileBrowserTree(paneId, view, state)
            }
        }
        "fileBrowserError" -> {
            val paneId = parsed.paneId as String
            val message = parsed.message as String
            val state = fileBrowserPaneStates[paneId]
            if (state != null) { state.selectedRelPath = null; state.html = null; state.kind = null }
            val view = fileBrowserPaneViews[paneId]
            if (view != null) {
                val err = document.createElement("div") as HTMLElement
                err.className = "md-error"; err.textContent = message
                view.rendered.innerHTML = ""; view.rendered.appendChild(err)
                renderFileBrowserTree(paneId, view, state ?: FileBrowserPaneState())
            }
        }
        "gitList" -> {
            val paneId = parsed.paneId as String
            val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
            state.entries = parsed.entries
            val view = gitPaneViews[paneId]
            if (view != null) renderGitList(paneId, view, state)
        }
        "gitDiff" -> {
            val paneId = parsed.paneId as String
            val filePath = parsed.filePath as String
            val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
            state.selectedFilePath = filePath
            val view = gitPaneViews[paneId]
            if (view != null) {
                val hasOld = parsed.oldContent as? String != null
                val hasNew = parsed.newContent as? String != null
                val oneSided = !hasOld || !hasNew
                if (!oneSided && state.diffMode == "Split" && state.graphicalDiff) {
                    renderGitDiffGraphical(view.diffPane, parsed, state)
                } else if (!oneSided && state.diffMode == "Split") {
                    renderGitDiffSplit(view.diffPane, parsed)
                } else {
                    renderGitDiffInline(view.diffPane, parsed)
                }
                if (state.searchQuery.isNotEmpty() && view.searchCounter != null) {
                    state.searchMatchIndex = 0
                    performDiffSearch(view.diffPane, state.searchQuery, state, view.searchCounter!!, view.searchNavButtons)
                }
                state.diffHtml = if (state.diffMode == "Split" && state.graphicalDiff) null
                    else view.diffPane.innerHTML
                updateGitListActiveFile(view, filePath)
            }
        }
        "gitError" -> {
            val paneId = parsed.paneId as String
            val message = parsed.message as String
            val state = gitPaneStates[paneId]
            if (state != null) { state.selectedFilePath = null; state.diffHtml = null }
            val view = gitPaneViews[paneId]
            if (view != null) {
                val err = document.createElement("div") as HTMLElement
                err.className = "git-error"; err.textContent = message
                view.diffPane.innerHTML = ""; view.diffPane.appendChild(err)
                renderGitList(paneId, view, state ?: GitPaneState())
            }
        }
        "worktreeDefaults" -> {
            showWorktreeDialog(
                paneId = parsed.paneId as String,
                repoName = parsed.repoName as String,
                siblingBase = parsed.siblingPath as String,
                dotWorktreesBase = parsed.dotWorktreesPath as String,
                hasUncommittedChanges = parsed.hasUncommittedChanges as Boolean,
            )
        }
        "worktreeCreated" -> {
            // The server already updated the pane's cwd; the config push
            // will re-render everything. Nothing extra needed here.
        }
        "worktreeError" -> {
            val message = parsed.message as String
            showConfirmDialog("Worktree Error", message, "OK") {}
        }
        else -> return false
    }
    return true
}

/**
 * Performs a full re-render of the application from a server configuration update.
 *
 * This is the main rendering entry point, called whenever a [WindowEnvelope.Config]
 * envelope is received. It:
 * 1. Removes terminals for panes that no longer exist in the config
 * 2. Rebuilds the tab bar with drag-and-drop support
 * 3. Rebuilds all tab pane content via [buildPane]
 * 4. Constructs floating pane layers
 * 5. Restores maximized panes, scroll positions, and focus state
 * 6. Applies entrance animations for new tabs/panes
 * 7. Re-renders the sidebar and state dots
 * 8. Triggers terminal refitting
 *
 * @param config the dynamic server configuration object
 * @see connectWindow
 * @see buildPane
 */
/**
 * Returns true when [prev] and [next] differ only in per-tab `focusedPaneId`
 * fields, meaning no structural DOM rebuild is needed.
 */
private fun isOnlyFocusChange(prev: dynamic, next: dynamic): Boolean {
    if (prev == null) return false
    val prevTabs = prev.tabs as? Array<dynamic> ?: return false
    val nextTabs = next.tabs as? Array<dynamic> ?: return false
    if (prevTabs.size != nextTabs.size) return false
    if ((prev.activeTabId as? String) != (next.activeTabId as? String)) return false
    val stringify = js("JSON.stringify") as (dynamic) -> String
    for (i in prevTabs.indices) {
        val pt = prevTabs[i]; val nt = nextTabs[i]
        if ((pt.id as? String) != (nt.id as? String)) return false
        if ((pt.title as? String) != (nt.title as? String)) return false
        // Hidden-state toggles change what the tab strip renders (and what
        // the overflow menu offers), so they are *not* focus-only changes.
        // The sidebar-visibility flag likewise changes what the sidebar tree
        // renders, so toggling it must trigger a full rebuild.
        if ((pt.isHidden as? Boolean ?: false) != (nt.isHidden as? Boolean ?: false)) return false
        if ((pt.isHiddenFromSidebar as? Boolean ?: false) != (nt.isHiddenFromSidebar as? Boolean ?: false)) return false
        if (stringify(pt.panes) != stringify(nt.panes)) return false
    }
    return true
}

/**
 * Re-focus the xterm textarea for the active tab's server-remembered
 * focused pane after every config push.
 *
 * Mirrors `main`'s `focusFirstPaneInActiveTab()` call at the end of
 * `renderConfig`. The toolkit-migrated chrome rebuilds pane DOM on
 * every push, which momentarily detaches the xterm `<textarea>` and
 * causes the browser to drop native focus. Without an unconditional
 * refocus here, any push whose focusedPaneId didn't change (e.g. a
 * cd-driven title update) leaves the cursor stranded.
 *
 * No gating on `lastFocusedTerminalId` — that pointer is best-effort
 * and can lag the server. The server's `focusedPaneId` is the single
 * source of truth for which pane should receive keystrokes.
 *
 * Why: cd-driven title pushes don't change focusedPaneId, so the prior
 * id-changed-only path skipped the refocus and left the cursor in
 * <body> after the toolkit detached the pane content. See
 * `plans/CD-FOCUS-LOSS-PLAN-V2.md`.
 *
 * Deferred to the next frame so toolkit reattach + focus-class flip
 * have committed before `term.focus()` runs against the new textarea.
 *
 * @param config the dynamic server configuration object
 */
private fun refocusActivePane(config: dynamic) {
    val activeTabId = config.activeTabId as? String ?: return
    val tabsArr = config.tabs as? Array<dynamic> ?: return
    val activeTab = tabsArr.firstOrNull { (it.id as? String) == activeTabId } ?: return
    if ((activeTab.focusedPaneId as? String) == null) return
    kotlinx.browser.window.requestAnimationFrame {
        // Re-resolve the target from [currentConfig] at frame time
        // rather than capturing it from the config that scheduled this
        // callback. Config pushes can arrive faster than frames; a
        // captured target would focus a pane the server has already
        // moved on from, churning DOM focus between two stale values.
        // With the fresh read, a burst of pushes converges on a single
        // focus of the latest pane.
        val cfg: dynamic = currentConfig ?: return@requestAnimationFrame
        val tabs = cfg.tabs as? Array<dynamic> ?: return@requestAnimationFrame
        val nowActiveId = cfg.activeTabId as? String ?: return@requestAnimationFrame
        val nowTab = tabs.firstOrNull { (it.id as? String) == nowActiveId } ?: return@requestAnimationFrame
        val paneId = (nowTab.focusedPaneId as? String) ?: return@requestAnimationFrame
        val entry = terminals[paneId] ?: return@requestAnimationFrame
        // Programmatic restoration: the synchronous focusin this
        // triggers must not echo a SetFocusedPane command back to the
        // server (see [suppressFocusCommands] for the ping-pong this
        // prevented).
        suppressFocusCommands = true
        try { entry.term.focus() } catch (_: Throwable) {} finally { suppressFocusCommands = false }
    }
}

/**
 * Realign the `.xterm-viewport` DOM scrollbar with the buffer's logical
 * scroll offset for every currently-visible terminal, on the next frame.
 *
 * Companion to the per-pane `ResizeObserver` hook (which only fires on a
 * size change, i.e. the hidden→visible edge of a tab switch). The toolkit
 * also reattaches the *active* tab's cached pane elements in place on every
 * config push — e.g. a window refocus that triggers a re-render — and that
 * reattach resets `scrollTop` to 0 without changing the container size, so
 * the `ResizeObserver` never fires and the desync would otherwise linger.
 * Sweeping all visible terminals here closes that gap.
 *
 * Deferred to the next frame for the same reason as [refocusActivePane]:
 * the toolkit's reattach must commit before we read/write `scrollTop`.
 * Cheap (a handful of panes, each a couple of DOM reads and at most one
 * `scrollTop` write) and idempotent — [resyncViewportScroll] no-ops when a
 * pane is already aligned.
 *
 * @see resyncViewportScroll
 * @see refocusActivePane
 */
private fun resyncVisibleTerminalsScroll() {
    kotlinx.browser.window.requestAnimationFrame {
        for (entry in terminals.values) {
            if (entry.container.offsetParent != null) {
                try { resyncViewportScroll(entry) } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Builds a signature string over exactly the config fields the toolkit chrome
 * and the per-push focus/scroll housekeeping depend on: the active tab, and per
 * tab its id / title / visibility flags / focused pane, plus the ordered pane
 * ids. Deliberately EXCLUDES pane titles (`leaf.title`) — the only field a
 * program-set OSC title tick changes.
 *
 * Mirrors the fields carried in the toolkit's `TabListSnapshot`, so two configs
 * with equal signatures are exactly the pushes for which the toolkit takes its
 * label-only fast path (no chrome rebuild, no pane-content detach). [renderConfig]
 * uses this to skip the refocus / scroll-realign / dot-repaint that only a real
 * structural render needs.
 *
 * @param config the dynamic server config object.
 * @return a stable signature that changes iff something structural changed.
 */
private fun structuralConfigSignature(config: dynamic): String {
    val sb = StringBuilder()
    sb.append(config.activeTabId as? String ?: "")
    val tabs = config.tabs as? Array<dynamic> ?: return sb.toString()
    for (tab in tabs) {
        sb.append("|t:").append(tab.id as? String ?: "")
        sb.append(",ti:").append(tab.title as? String ?: "")
        sb.append(",h:").append((tab.isHidden as? Boolean ?: false).toString())
        sb.append(",hs:").append((tab.isHiddenFromSidebar as? Boolean ?: false).toString())
        sb.append(",f:").append(tab.focusedPaneId as? String ?: "")
        val panes = tab.panes as? Array<dynamic> ?: continue
        for (p in panes) sb.append(",p:").append(p.leaf.id as? String ?: "")
    }
    return sb.toString()
}

fun renderConfig(config: dynamic) {
    // Post-toolkit-migration: the toolkit's `mountAppShell` (driven by
    // `TermtasticTabSource`) owns the entire chrome rebuild — top bar,
    // tab strip, sidebar tree, layout root, pane chrome. This function
    // only does the housekeeping that the chrome render used to do as
    // a side effect: snapshot the live config for synchronous lookups
    // (`mountPaneContent`, `savedFocusedPaneId`, `countPanesForSession`)
    // and prune any PTY entries whose pane is no longer in the config.
    val prevConfig = currentConfig
    currentConfig = config
    val tabsArr = config.tabs as? Array<dynamic> ?: return

    val livePanes = HashSet<String>()
    for (tab in tabsArr) {
        val panes = tab.panes as? Array<dynamic> ?: emptyArray()
        for (p in panes) livePanes.add(p.leaf.id as String)
    }
    // Agent badges can change without any structural change (an MCP agent
    // annotating a window), so sync them before the fast-path bail below.
    updateAgentBadges(config)

    val toRemove = terminals.keys.filter { it !in livePanes }
    for (pid in toRemove) {
        val entry = terminals.remove(pid) ?: continue
        try { entry.socket?.close() } catch (_: Throwable) {}
        try { entry.demoJob?.cancel() } catch (_: Throwable) {}
        try { (entry.resizeObserver as? ResizeObserver)?.disconnect() } catch (_: Throwable) {}
        // Tear down the xterm.js instance itself, not just our plumbing
        // around it. Without `dispose()` every closed pane leaks the
        // terminal's renderer, canvas, scrollback buffer, and its
        // registered onData/onResize callbacks (xterm offers no
        // unsubscribe through our bindings) — over a long session of
        // opening/closing panes these accumulate until the UI visibly
        // degrades, worst in the embedded demo where the page lives for
        // the whole visit.
        try { entry.term.dispose() } catch (_: Throwable) {}
        connectionState.remove(entry.sessionId)
        // The sticky "user wants to type here" pointer must not survive
        // the disappearance of its target terminal — otherwise the next
        // config push could schedule a `term.focus()` against an entry
        // that's already gone.
        if (lastFocusedTerminalId == pid) lastFocusedTerminalId = null
    }
    // Agent transcript panes are pruned the same way terminal entries are.
    pruneAgentTranscripts(livePanes)
    updateAggregateStatus()
    // Label-only fast path. When nothing structural changed (only pane titles
    // did — e.g. a program-set OSC title tick while a terminal task runs), the
    // toolkit takes its own label-only fast path: it refreshes the label text
    // in place and never detaches pane content. So there is no lost focus to
    // restore and no reset scrollbar to realign, and the status dots weren't
    // recreated — skip the per-push refocus / scroll-resync / dot repaint that
    // only a real structural render needs. This keeps a working pane's ~750 ms
    // title cadence from force-refocusing the active terminal (which would
    // otherwise steal focus from e.g. a settings field) on every tick.
    // The signature match is exactly the toolkit's fast-path condition, so the
    // two stay consistent. See [structuralConfigSignature].
    if (prevConfig != null &&
        structuralConfigSignature(prevConfig) == structuralConfigSignature(config)
    ) {
        return
    }
    refocusActivePane(config)
    // Realign every visible terminal's native scrollbar with its buffer after
    // the toolkit reattaches the active tab's cached pane elements (which
    // resets scrollTop to 0). Covers in-place reattaches — e.g. window
    // refocus — that the per-pane ResizeObserver misses because the size
    // didn't change. The tab-switch case is also covered here, with the
    // ResizeObserver edge as a faster belt-and-suspenders.
    resyncVisibleTerminalsScroll()
    // Replay cached session states onto the freshly-rendered chrome so
    // the AppLogo dot and the per-pane / per-tab status dots pick up
    // working/waiting state immediately. Without this the dot stays on
    // its previous colour until the next State envelope arrives, which
    // can be several seconds (or never, if no further state changes
    // happen). Mirrors the call site in pre-toolkit `renderConfig`.
    //
    // Read from the authoritative `windowState.states` (the same source the
    // per-pane dot builders use via `currentSessionStates`) rather than the
    // `appVm` mirror, which can still be empty at this point — e.g. in demo
    // mode the single fixture state seed is otherwise missed and the AppLogo
    // dot stays idle-green while the rows correctly show working/waiting.
    updateStateIndicators(termtasticClient.windowState.states.value)
}

/**
 * Collect typed envelopes from the WindowSocket and route them to the
 * rendering layer. The backing VMs also receive these envelopes
 * independently via their own run() loops.
 */
fun connectWindow() {
    GlobalScope.launch {
        windowSocket.envelopes.collect { envelope ->
            when (envelope) {
                is WindowEnvelope.Config -> {
                    hidePendingApprovalOverlay()
                    latestWindowConfig = envelope.config
                    val json = windowJson.encodeToString(envelope.config)
                    val dynamic = js("JSON.parse(json)")
                    renderConfig(dynamic)
                }
                is WindowEnvelope.State -> {
                    updateStateIndicators(envelope.states)
                }
                is WindowEnvelope.ClaudeUsage -> {
                    val json = windowJson.encodeToString(envelope)
                    val dynamic = js("JSON.parse(json)")
                    updateClaudeUsageBadge(dynamic.usage)
                }
                is WindowEnvelope.PendingApproval -> {
                    showPendingApprovalOverlay()
                }
                is WindowEnvelope.AgentNotify -> {
                    showAgentToast(envelope.message, envelope.level)
                }
                is WindowEnvelope.UiSettings -> {
                    // Handled reactively: the AppBackingViewModel updates its
                    // state from this envelope, and the stateFlow collector in
                    // main.kt calls applyAll().
                }
                else -> {
                    val json = windowJson.encodeToString(envelope)
                    val parsed = js("JSON.parse(json)")
                    handlePaneContentMessage(parsed.type as? String, parsed)
                }
            }
        }
    }
}
