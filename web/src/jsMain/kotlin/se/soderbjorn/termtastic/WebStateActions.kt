/**
 * Action helpers that operate over the registries in [WebState],
 * [TerminalRegistry], [DomRefRegistry], [PaneStateRegistry], and
 * [RenderingState]. Includes:
 *  - command dispatch ([launchCmd])
 *  - aggregate connection status ([updateAggregateStatus])
 *  - DOM walk helpers ([findTabPane], [shellQuote])
 *  - focus management ([markPaneFocused], [focusFirstPaneInActiveTab],
 *    [savedFocusedPaneId])
 *  - theme application ([applyAppearanceClass], [applyThemeToTerminals],
 *    [applyAll], [buildXtermTheme])
 *  - sidebar / header / usage-bar collapsed-state appliers
 *  - global font-size sync ([applyGlobalFontSize])
 *  - markdown anchor wiring ([wireMarkdownAnchorLinks])
 *  - desktop notifications and per-pane / per-tab status-dot updates
 *    ([checkStateNotifications], [applyDotState], [updateStateIndicators])
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.applyTheme

import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.client.viewmodel.findLeafBySessionId
import se.soderbjorn.termtastic.client.viewmodel.resolvedTheme

/**
 * Sends a [WindowCommand] to the server via the [WindowSocket] in a
 * fire-and-forget coroutine. Primary mechanism for client → server
 * communication in the web frontend.
 */
internal fun launchCmd(cmd: WindowCommand) {
    GlobalScope.launch { windowSocket.send(cmd) }
}

/**
 * Checks all PTY connection states and shows/hides the disconnected
 * modal accordingly.
 */
internal fun updateAggregateStatus() {
    val ptyDisconnected = connectionState.values.any { it == "disconnected" }
    if (!windowSocketConnected || ptyDisconnected) showDisconnectedModal()
    else hideDisconnectedModal()
}

/**
 * Walks up the DOM tree from the given element to find the nearest
 * ancestor with the "tab-pane" CSS class.
 */
internal fun findTabPane(el: HTMLElement): HTMLElement? {
    var cur: HTMLElement? = el
    while (cur != null) {
        if (cur.classList.contains("tab-pane")) return cur
        cur = cur.parentElement as? HTMLElement
    }
    return null
}

/**
 * Counts how many panes (including linked views) share the given session
 * ID. Used by the close-confirmation dialog.
 */
internal fun countPanesForSession(sessionId: String?): Int {
    if (sessionId.isNullOrEmpty()) return 0
    val cfg = appVm.stateFlow.value.config ?: return 0
    var count = 0
    for (tab in cfg.tabs) {
        for (p in tab.panes) if (p.leaf.sessionId == sessionId) count++
    }
    return count
}

/** Shell-quotes a file path for safe pasting into a terminal. */
internal fun shellQuote(p: String): String = "'" + p.replace("'", "'\\''") + "'"

/**
 * Looks up the server-persisted focused pane ID for a given tab.
 *
 * Reads from [currentConfig] (set synchronously by `renderConfig`) rather
 * than the AppBackingViewModel state, which may lag.
 */
internal fun savedFocusedPaneId(tabId: String): String? {
    val cfg = currentConfig ?: return null
    val tabs = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabs) {
        if ((tab.id as? String) == tabId) return tab.focusedPaneId as? String
    }
    return null
}

/**
 * Resolve the directory a freshly-created pane in [tabId] should be
 * rooted at. Reads from the typed [latestWindowConfig] snapshot so the
 * value is exactly what the user can see on screen at click time —
 * the same `LeafNode.cwd` that paints the title bars and sidebar
 * entries the user is staring at.
 *
 * Preference order:
 *  1. The focused pane's cwd in the target tab (the obvious answer when
 *     the user clicks "+" while a terminal is active).
 *  2. Any pane's cwd in the target tab (handles the case where focus
 *     tracking is null because a tab was just created or the previous
 *     focused pane was closed without a replacement).
 *  3. Any pane's cwd in any tab (last-resort heuristic; better than
 *     making the server guess from `user.dir`).
 *
 * Returns null only when the client truly has no directory context —
 * e.g. immediately after page load before the first config has arrived.
 * The server applies its own [System.getProperty] floor for non-terminal
 * panes so the new pane is never created with a blank cwd.
 */
internal fun cwdForNewPaneIn(tabId: String): String? {
    val cfg = latestWindowConfig ?: return null
    val tab = cfg.tabs.firstOrNull { it.id == tabId }
    if (tab != null) {
        tab.focusedPaneId
            ?.let { id -> tab.panes.firstOrNull { it.leaf.id == id }?.leaf?.cwd }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        tab.panes
            .firstNotNullOfOrNull { it.leaf.cwd?.takeIf { c -> c.isNotBlank() } }
            ?.let { return it }
    }
    return cfg.tabs.firstNotNullOfOrNull { t ->
        t.panes.firstNotNullOfOrNull { it.leaf.cwd?.takeIf { c -> c.isNotBlank() } }
    }
}

/**
 * Finds the server-side tab id that owns [paneId] by scanning the live
 * [currentConfig]. Used by [markPaneFocused] to identify the tab when the
 * legacy `.tab-pane` ancestor class is not present in the DOM (the
 * toolkit-driven chrome uses `.dt-pane` and exposes `data-pane-id` only
 * on the pane wrapper — there is no `data-tab-id` upstream of it).
 *
 * @param paneId pane identifier set as `data-pane` on the terminal-cell.
 * @return owning tab id, or null if the pane is not in the live config.
 */
internal fun tabIdForPane(paneId: String): String? {
    val cfg = currentConfig ?: return null
    val tabs = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabs) {
        val panes = tab.panes as? Array<dynamic> ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) return tab.id as? String
        }
    }
    return null
}

/**
 * Marks a pane cell as focused, removing the "focused" class from all
 * other cells; updates the sidebar active-pane highlight; sends
 * [WindowCommand.SetFocusedPane] to persist.
 */
internal fun markPaneFocused(cell: HTMLElement) {
    val all = kotlinx.browser.document.querySelectorAll(".terminal-cell.focused")
    for (i in 0 until all.length) {
        val el = all.item(i) as HTMLElement
        if (el !== cell) el.classList.remove("focused")
    }
    cell.classList.add("focused")
    // The visible focus ring lives on the toolkit's
    // `.dt-pane.dt-pane-focused` outline. Optimistically swap the
    // focused-class on the matching pane wrapper for instant feedback
    // on cell-internal clicks (file browser rows, git toolbar buttons,
    // xterm canvas) so the user doesn't see a server-roundtrip lag
    // before the toolkit re-renders the snapshot. The toolkit will
    // re-assert the same class on its next render — idempotent.
    val paneWrapper = cell.asDynamic().closest(".dt-pane") as? HTMLElement
    if (paneWrapper != null) {
        val allPanes = kotlinx.browser.document.querySelectorAll(".dt-pane.dt-pane-focused")
        for (i in 0 until allPanes.length) {
            val el = allPanes.item(i) as HTMLElement
            if (el !== paneWrapper) el.classList.remove("dt-pane-focused")
        }
        paneWrapper.classList.add("dt-pane-focused")
    }
    // Resolve the owning tab id. The legacy chrome wrapped each tab's
    // panes in `.tab-pane` with the tab id on the wrapper element; the
    // toolkit-migrated chrome does neither, so the DOM lookup here
    // always returned null and `SetFocusedPane` was never sent for
    // direct xterm clicks. That left the server's `focusedPaneId`
    // stuck on whatever pane the user last clicked in the *sidebar*,
    // and `WindowConnection.refocusActivePane` then snapped DOM focus
    // to that stale pane on every config push (e.g. after a `cd`).
    // Fall back to looking the tab id up from the live config so the
    // server's view of focus tracks direct pane clicks again.
    val paneId = cell.getAttribute("data-pane")
    val domTabId = (cell.asDynamic().closest(".tab-pane") as? HTMLElement)?.id
    val tabId = if (!domTabId.isNullOrEmpty()) domTabId
                else paneId?.let { tabIdForPane(it) }
    // Send SetFocusedPane only for genuine user gestures, and only when
    // it would actually change the server's focusedPaneId. This runs
    // from the container `focusin` listener, which fires for
    // programmatic focus too — notably `refocusActivePane`'s
    // `term.focus()` after every config push. Echoing SetFocusedPane
    // back for those reflected focuses sustained a focus ping-pong
    // loop: with two conflicting commands in flight, each alternating
    // config push re-focused the now-stale pane, whose focusin re-sent
    // the stale id, flickering the selection between two panes
    // indefinitely.
    //
    // Two guards, both required:
    //  - [suppressFocusCommands] is the authoritative one. It is set
    //    synchronously around every programmatic `term.focus()` call,
    //    so a reflected focusin can NEVER emit a command. The equality
    //    check alone is racy: `refocusActivePane` runs a frame after
    //    its config push, and under back-to-back pushes
    //    [currentConfig] has already advanced to the *other* in-flight
    //    value when focusin fires — so each side kept "correcting" the
    //    other at ~12 round-trips/s, every push re-rendering the full
    //    chrome (the multi-second UI freezes in the embedded demo).
    //  - The savedFocusedPaneId equality check additionally drops
    //    no-op sends from real clicks on the already-focused pane.
    if (!suppressFocusCommands &&
        !tabId.isNullOrEmpty() && !paneId.isNullOrEmpty() &&
        savedFocusedPaneId(tabId) != paneId
    ) {
        launchCmd(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }
    if (!paneId.isNullOrEmpty()) {
        val sidebar = kotlinx.browser.document.getElementById("sidebar")
        val items = sidebar?.querySelectorAll(".sidebar-pane-item")
        if (items != null) {
            for (i in 0 until items.length) {
                val item = items.item(i) as HTMLElement
                val itemPane = item.getAttribute("data-pane")
                if (itemPane == paneId) item.classList.add("active-pane")
                else item.classList.remove("active-pane")
            }
        }
    }
}

/**
 * Focuses the first terminal pane in the active tab, preferring the
 * server-remembered focused pane if available.
 */
internal fun focusFirstPaneInActiveTab(): Boolean {
    val wrap = terminalWrapEl ?: return false
    val activePane = wrap.querySelector(".tab-pane.active") as? HTMLElement ?: return false
    val activeId = (currentConfig?.activeTabId as? String) ?: appVm.stateFlow.value.config?.activeTabId
    val rememberedPaneId = activeId?.let { savedFocusedPaneId(it) }
    if (rememberedPaneId != null) {
        val entry = terminals[rememberedPaneId]
        if (entry != null) {
            // Programmatic restoration — must not echo SetFocusedPane
            // (see [suppressFocusCommands]).
            suppressFocusCommands = true
            try { entry.term.focus() } finally { suppressFocusCommands = false }
            return true
        }
        // Scope to `.terminal-cell` — the outer `.floating-pane` also
        // carries `data-pane`, and an unscoped selector would match it
        // first, feeding `markPaneFocused` the wrong element.
        val cell = activePane.querySelector(".terminal-cell[data-pane=\"$rememberedPaneId\"]") as? HTMLElement
        if (cell != null) { markPaneFocused(cell); return true }
    }
    val paneCells = activePane.querySelectorAll("[data-pane]")
    for (i in 0 until paneCells.length) {
        val cell = paneCells.item(i) as HTMLElement
        val pid = cell.getAttribute("data-pane") ?: continue
        val entry = terminals[pid] ?: continue
        entry.term.focus()
        return true
    }
    return false
}

/**
 * Builds a dynamic xterm.js `ITheme` options object from the current
 * resolved theme and appearance.
 *
 * The terminal background is forced opaque (`argbToCss(theme.bg)`) so the
 * xterm canvas never bleeds the underlying pane chrome through; foreground,
 * cursor and selection are read directly from the flat [ResolvedTheme]
 * tokens.
 */
internal fun buildXtermTheme(): dynamic {
    val theme = currentResolvedTheme()
    // The terminal canvas sits on the pane surface (white on Termtastic Light),
    // one level above the `--t-bg` canvas — matching the pane chrome around it.
    val bg = argbToCss(theme.surface)
    val fg = argbToCss(theme.text)
    val bgLight = isColorLight(theme.surface)
    val base = kotlin.js.json(
        "background" to bg,
        "foreground" to fg,
        "cursor" to argbToCss(theme.accent),
        "cursorAccent" to argbToCss(theme.surface),
        "selectionBackground" to argbToCss(theme.accentSoft),
    )
    if (bgLight) {
        base["white"] = "#3b3b3b"; base["brightWhite"] = "#5a5a5a"
        base["yellow"] = "#866a00"; base["brightYellow"] = "#9d7e00"
        base["green"] = "#116329"; base["brightGreen"] = "#1a7f37"
        base["cyan"] = "#0b6e6e"; base["brightCyan"] = "#0f8585"
        base["blue"] = "#0550ae"; base["brightBlue"] = "#0969da"
        base["magenta"] = "#6e3996"; base["brightMagenta"] = "#8250df"
        base["red"] = "#b3261e"; base["brightRed"] = "#cf222e"
    } else {
        base["black"] = "#5a5a5a"; base["brightBlack"] = "#8a8a8a"
    }
    return base
}

/**
 * Updates the CSS appearance classes on `<body>` based on the current
 * theme/appearance state and emits all 19 semantic `--t-*` CSS custom
 * properties on `:root` via the toolkit's [applyTheme].
 *
 * Post theme-system rewrite there is a single flat [ResolvedTheme] for the
 * whole app — no per-pane/section overrides — so a single `:root` stamp
 * suffices and every legacy section / pane container inherits it.
 */
internal fun applyAppearanceClass() {
    val state = appVm.stateFlow.value
    val light = isLightActive(state.appearance)
    val isDark = !light
    kotlinx.browser.document.body?.classList?.remove("appearance-light", "appearance-dark", "dark-spiced")
    kotlinx.browser.document.body?.classList?.add(if (light) "appearance-light" else "appearance-dark")

    val theme = state.resolvedTheme(isSystemDark())
    val root = kotlinx.browser.document.documentElement as? HTMLElement
    if (root != null) {
        applyTheme(root, theme, isDark)
    }

    val electronApi = kotlinx.browser.window.asDynamic().electronApi
    if (electronApi?.setWindowBackgroundColor != null) {
        electronApi.setWindowBackgroundColor(argbToCss(theme.titlebar))
    }
}

/**
 * Applies the current xterm theme to all registered terminal instances.
 */
internal fun applyThemeToTerminals() {
    val globalTheme = buildXtermTheme()
    for ((_, entry) in terminals) {
        entry.term.options.theme = globalTheme
    }
}

/** Applies all visual changes: appearance CSS classes and xterm themes. */
internal fun applyAll() {
    applyAppearanceClass()
    applyThemeToTerminals()
}

/**
 * Applies the persisted sidebar width and collapsed state to the DOM.
 */
internal fun applySidebarState() {
    val state = appVm.stateFlow.value
    val sb = sidebarEl ?: return
    if (state.sidebarCollapsed) {
        sb.style.width = "0px"
    } else {
        val w = (state.sidebarWidth ?: 0).takeIf { it > 10 } ?: 260
        sb.style.width = "${w}px"
    }
}

/**
 * Applies the persisted collapsed state of the app header to the DOM by
 * toggling the `.collapsed` class.
 */
internal fun applyHeaderCollapsedState() {
    val hdr = appHeaderEl ?: return
    val collapsed = appVm.stateFlow.value.headerCollapsed
    if (collapsed) hdr.classList.add("collapsed") else hdr.classList.remove("collapsed")
}

/**
 * Applies the persisted collapsed state of the Claude usage bar.
 */
internal fun applyUsageBarCollapsedState() {
    val bar = usageBar ?: return
    val collapsed = appVm.stateFlow.value.usageBarCollapsed
    if (collapsed) bar.classList.add("collapsed") else bar.classList.remove("collapsed")
}

/**
 * Applies a new font size to all terminals, file browser rendered areas,
 * and git diff panes.
 */
internal fun applyGlobalFontSize(size: Int) {
    for ((_, entry) in terminals) {
        entry.term.options.fontSize = size
        try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    }
    val mdRoots = kotlinx.browser.document.querySelectorAll(".md-rendered")
    for (i in 0 until mdRoots.length) {
        (mdRoots.item(i) as? HTMLElement)?.style?.fontSize = "${size}px"
    }
    for ((paneId, view) in gitPaneViews) {
        val state = gitPaneStates[paneId] ?: continue
        state.diffFontSize = size
        state.diffHtml = null
        view.diffPane.style.fontSize = "${size}px"
        view.diffPane.style.lineHeight = "${(size * 1.54).toInt()}px"
        val sel = state.selectedFilePath
        if (sel != null) launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
    }
}

/**
 * Wires up click handlers for anchor links within rendered Markdown
 * content. Intercepts clicks on `#`-prefixed href links and smoothly
 * scrolls to the corresponding heading element.
 */
internal fun wireMarkdownAnchorLinks(container: HTMLElement) {
    container.addEventListener("click", { ev ->
        var node = ev.target as? HTMLElement
        while (node != null && node != container && node !is org.w3c.dom.HTMLAnchorElement) {
            node = node.parentElement as? HTMLElement
        }
        if (node is org.w3c.dom.HTMLAnchorElement) {
            val href = node.getAttribute("href") ?: return@addEventListener
            if (href.startsWith("#")) {
                ev.preventDefault()
                val id = href.removePrefix("#")
                val heading = container.querySelector("[id='${id.replace("'", "\\'")}']")
                heading?.scrollIntoView(js("({behavior:'smooth',block:'start'})"))
            }
        }
    })
}

/**
 * Checks for session state transitions that warrant desktop notifications.
 */
private fun checkStateNotifications(sessionStates: Map<String, String?>) {
    if (!appVm.stateFlow.value.desktopNotifications) return
    if (js("typeof Notification === 'undefined'") as Boolean) return
    if ((js("Notification.permission") as String) != "granted") return

    val effective = HashMap(sessionStates)

    for ((sessionId, newState) in effective) {
        val oldState = previousSessionStates[sessionId]
        val kind: String? = when {
            newState == "waiting" && oldState != "waiting" -> "waiting"
            newState == null && oldState == "working" -> "done"
            else -> null
        }
        if (kind != null) {
            val config = appVm.stateFlow.value.config
            val title = if (config != null) findLeafBySessionId(config, sessionId)?.title else null
            val paneLabel = title ?: sessionId
            val body = if (kind == "waiting") "$paneLabel needs input" else "$paneLabel finished"
            val opts: dynamic = js("({})")
            opts.body = body
            opts.silent = false
            val notification = js("new Notification('Termtastic', opts)")
            notification.onclick = {
                kotlinx.browser.window.focus()
                notification.close()
            }
        }
    }
    previousSessionStates.clear()
    previousSessionStates.putAll(effective)
}

/**
 * Applies the working/waiting state to a status indicator (`.tt-status-dot`) —
 * the unified indicator used on sidebar rows, pane headers, and the tab strip.
 * Its look is driven purely by the `state-working` / `state-waiting` modifier
 * classes (see `.tt-status-dot` in styles.css), all painted in the theme's
 * foreground colour (issue #38): idle clears both so the base rule paints a
 * solid dot; `state-working` makes that dot breathe; `state-waiting` swaps the
 * dot for a pulsing warning/exclamation triangle.
 *
 * Called by [updateStateIndicators] on each server push and by the dot builders
 * at construction time.
 *
 * @param el the `.tt-status-dot` element to repaint.
 * @param state the session state (`"working"`, `"waiting"`, or null/idle).
 */
internal fun applyDotState(el: HTMLElement, state: String?) {
    el.classList.remove("state-working")
    el.classList.remove("state-waiting")
    when (state) {
        "working" -> el.classList.add("state-working")
        "waiting" -> el.classList.add("state-waiting")
    }
}

/**
 * Updates all session state indicators across the sidebar, tab bar,
 * pane headers, and the lower-right app logo dot.
 */
internal fun updateStateIndicators(sessionStates: Map<String, String?>) {
    val document = kotlinx.browser.document
    checkStateNotifications(sessionStates)
    updateAppLogoState(sessionStates)
    val effective = HashMap(sessionStates)
    for ((sessionId, state) in effective) {
        // Per-pane status dots — the leading bead on each sidebar row and the
        // pane-header dot. Both carry `.tt-status-dot[data-session]`.
        val dots = document.querySelectorAll(".tt-status-dot[data-session='$sessionId']")
        for (k in 0 until dots.length) {
            val el = dots.item(k) as? HTMLElement ?: continue
            applyDotState(el, state)
        }
    }
    val cfg = appVm.stateFlow.value.config ?: return
    for (tab in cfg.tabs) {
        val tabId = tab.id
        val sids = mutableListOf<String>()
        for (p in tab.panes) sids.add(p.leaf.sessionId)
        var tabState: String? = null
        for (sid in sids) {
            when (effective[sid]) {
                "waiting" -> { tabState = "waiting"; break }
                "working" -> if (tabState != "working") tabState = "working"
            }
        }
        // Per-tab aggregated status dot on the tab in the strip.
        val tabDots = document.querySelectorAll(".tt-status-dot[data-tab-state='$tabId']")
        for (j in 0 until tabDots.length) {
            val el = tabDots.item(j) as? HTMLElement ?: continue
            applyDotState(el, tabState)
        }
    }
    val indicator = document.querySelector(".tab-active-indicator") as? HTMLElement
    val activeBtn = document.querySelector(".tab-button.active") as? HTMLElement
    if (indicator != null && activeBtn != null) {
        indicator.style.width = "${(activeBtn.asDynamic().offsetWidth as Number).toDouble()}px"
        indicator.style.transform = "translate3d(${(activeBtn.asDynamic().offsetLeft as Number).toDouble()}px, 0, 0)"
    }
}
