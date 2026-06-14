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
 *    [applyAll], [buildXtermTheme], [renderThemeSwatch])
 *  - sidebar / header / usage-bar collapsed-state appliers
 *  - global font-size sync ([applyGlobalFontSize])
 *  - markdown anchor wiring ([wireMarkdownAnchorLinks])
 *  - desktop notifications and per-pane spinner / waiting-icon updates
 *    ([checkStateNotifications], [applySpinnerState], [updateStateIndicators])
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.web.toCssVarMap

import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.client.viewmodel.findLeafBySessionId

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
 * Builds a dynamic xterm.js theme options object from the current app
 * theme and appearance.
 */
internal fun buildXtermTheme(): dynamic {
    val palette = sectionPalette("terminal")
    val bg = argbToHex(palette.terminal.bg)
    val fg = argbToHex(palette.terminal.fg)
    val bgLight = isColorLight(palette.terminal.bg)
    val base = kotlin.js.json(
        "background" to bg,
        "foreground" to fg,
        "cursor" to argbToHex(palette.terminal.cursor),
        "cursorAccent" to argbToHex(palette.accent.onPrimary),
        "selectionBackground" to argbToCss(palette.terminal.selection),
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
 * theme/appearance state and emits all semantic `--t-*` CSS custom
 * properties on `:root`.
 */
internal fun applyAppearanceClass() {
    val state = appVm.stateFlow.value
    kotlinx.browser.window.asDynamic().console.log(
        "[applyAppearanceClass] state.appearance=${state.appearance} theme=${state.theme.name}"
    )
    kotlinx.browser.document.body?.classList?.remove("appearance-light", "appearance-dark", "dark-spiced")
    val light = isLightActive(state.appearance)
    kotlinx.browser.document.body?.classList?.add(if (light) "appearance-light" else "appearance-dark")
    kotlinx.browser.window.asDynamic().console.log(
        "[applyAppearanceClass] body.class=" + (kotlinx.browser.document.body?.className ?: "<no body>")
    )

    val palette = currentResolvedPalette()
    val root = kotlinx.browser.document.documentElement as? HTMLElement
    for ((prop, value) in palette.toCssVarMap()) {
        root?.style?.setProperty(prop, value)
    }

    val electronApi = kotlinx.browser.window.asDynamic().electronApi
    if (electronApi?.setWindowBackgroundColor != null) {
        electronApi.setWindowBackgroundColor(argbToHex(palette.chrome.titlebar))
    }

    if (root != null) {
        val activeVars = activeAccentCssVars()
        if (activeVars.isNotEmpty()) {
            for ((prop, value) in activeVars) root.style.setProperty(prop, value)
        } else {
            clearActiveAccentVars(root)
        }
    }

    fun queryElements(selector: String): List<HTMLElement> {
        val nodes = kotlinx.browser.document.querySelectorAll(selector)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? HTMLElement }
    }
    val sectionContainers: Map<String, List<HTMLElement>> = buildMap {
        val sidebarEls = buildList {
            (kotlinx.browser.document.getElementById("sidebar") as? HTMLElement)?.let { add(it) }
            (kotlinx.browser.document.querySelector(".settings-sidebar") as? HTMLElement)?.let { add(it) }
            (kotlinx.browser.document.querySelector(".theme-manager-sidebar") as? HTMLElement)?.let { add(it) }
        }
        if (sidebarEls.isNotEmpty()) put("sidebar", sidebarEls)
        val tabsEls = listOfNotNull(
            kotlinx.browser.document.querySelector(".app-header") as? HTMLElement,
            kotlinx.browser.document.getElementById("tab-bar") as? HTMLElement,
        )
        if (tabsEls.isNotEmpty()) put("tabs", tabsEls)
        val bottomBarEls = listOfNotNull(
            kotlinx.browser.document.getElementById("claude-usage-bar") as? HTMLElement,
        )
        if (bottomBarEls.isNotEmpty()) put("bottomBar", bottomBarEls)
        queryElements(".terminal-cell").takeIf { it.isNotEmpty() }?.let { put("windows", it) }
        queryElements(".terminal-cell[data-content-kind='terminal'] > .terminal").takeIf { it.isNotEmpty() }?.let { put("terminal", it) }
        queryElements(".terminal-cell[data-content-kind='fileBrowser'] > .md-view").takeIf { it.isNotEmpty() }?.let { put("fileBrowser", it) }
        // Key "git" (not "diff"): `termtasticPanes` registers the concrete
        // pane under "git" → Auxiliary, so paneSchemes is keyed by "git".
        // Using "diff" here would silently fall back to the main scheme
        // (no Auxiliary override applied), which is what produced a
        // white-bg git pane on themes whose Auxiliary scheme differs from
        // Main and from the windows override.
        queryElements(".terminal-cell[data-content-kind='git'] > .git-view").takeIf { it.isNotEmpty() }?.let { put("git", it) }
    }
    val allProps = palette.toCssVarMap().keys + palette.toCssAliasMap().keys
    for ((_, elements) in sectionContainers) {
        for (el in elements) {
            for (prop in allProps) el.style.removeProperty(prop)
        }
    }
    val windowsPalette = sectionPalette("windows")
    val windowsOverrideActive = windowsPalette != palette
    val paneContentSections = setOf("terminal", "fileBrowser", "git")
    for ((section, elements) in sectionContainers) {
        val sp = sectionPalette(section)
        val differs = sp != palette
        val needsApply = differs || (windowsOverrideActive && section in paneContentSections)
        console.log("[theme] section=$section elements=${elements.size} differs=$differs needsApply=$needsApply")
        if (needsApply) {
            val cssVars = sp.toCssVarMap() + sp.toCssAliasMap()
            for (el in elements) {
                for ((prop, value) in cssVars) {
                    el.style.setProperty(prop, value)
                }
            }
        }
    }

    // Toolkit chrome paint. The block above paints termtastic-legacy
    // selectors (#sidebar, .terminal-cell, .app-header, ...) but the live
    // chrome that mountAppShell renders uses .dt-pane, .dt-sidebar,
    // .dt-topbar, .dt-tabbar, .dt-bottombar — none of which we touch here.
    // Without this, switching themes through TermtasticThemeManagerHost
    // (appVm-only path that bypasses the toolkit's onThemeManagerChanged)
    // leaves the toolkit chrome stuck on whatever section paint
    // mountAppShell installed at boot. Calling the toolkit's painter here
    // makes its Pass 3 repaint .dt-pane / .dt-sidebar / .dt-topbar etc.
    // with the freshly resolved per-section schemes — same paint the
    // toolkit's own appearance-toggle button uses.
    val curState = appVm.stateFlow.value
    val ui = se.soderbjorn.darkness.core.UiSettings(
        theme = curState.theme,
        appearance = curState.appearance,
        paneSchemes = curState.paneSchemes,
    )
    val docEl = kotlinx.browser.document.documentElement as? HTMLElement
    if (docEl != null) {
        se.soderbjorn.darkness.web.applyUiSettings(docEl, ui, isDark = !light)
    }

    // Keep the toolkit assembler's stored UiSettings in sync. Without
    // this, the toolkit re-paints chrome from its mount-time snapshot
    // on every rerender (tab switch, pane switch, sidebar toggle, layout
    // change) — clobbering the paint above with the theme that was
    // persisted at page load. The user-visible symptom: pick a theme in
    // the editor, switch active pane, chrome reverts to the boot-time
    // theme even though appVm still holds the freshly selected one.
    appShellHandle?.setUiSettings(ui)
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
 * Renders an HTML snippet for a theme swatch preview, showing a mini
 * terminal rectangle with accent prompt and a row of syntax colour dots.
 */
internal fun renderThemeSwatch(t: ColorScheme): String {
    val isDark = !isLightActive(appVm.stateFlow.value.appearance)
    val p = t.resolve(isDark)
    val bg = argbToHex(p.terminal.bg)
    val fg = argbToHex(p.terminal.fg)
    val accent = argbToHex(p.accent.primary)
    val syntaxDots = listOf(
        p.syntax.keyword, p.syntax.string, p.syntax.number, p.syntax.comment,
        p.syntax.function, p.syntax.type, p.syntax.operator, p.syntax.constant,
    ).joinToString("") { color ->
        """<span class="syntax-dot" style="background:${argbToHex(color)}"></span>"""
    }
    return """<span class="theme-swatch">
        <span class="swatch-terminal" style="background:$bg;color:$fg">
            <span class="swatch-prompt" style="color:$accent">❯</span> ls
        </span>
        <span class="swatch-syntax-row">$syntaxDots</span>
    </span>"""
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

/** Small warning-triangle SVG for the spinner slot when a pane is waiting. */
private const val WAITING_WARNING_SVG = """<svg viewBox="0 0 16 16" fill="currentColor" width="12" height="12"><path d="M8 1.5 L14.5 13.5 H1.5 Z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><rect x="7.25" y="6" width="1.5" height="4" rx="0.5" fill="currentColor"/><circle cx="8" cy="12" r="0.85" fill="currentColor"/></svg>"""

/** 14px variant of the warning triangle for pane headers. */
private const val WAITING_WARNING_SVG_HEADER = """<svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14"><path d="M8 1.5 L14.5 13.5 H1.5 Z" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><rect x="7.25" y="6" width="1.5" height="4" rx="0.5" fill="currentColor"/><circle cx="8" cy="12" r="0.85" fill="currentColor"/></svg>"""

/**
 * Applies the correct class and content to a spinner element based on
 * the given pane state.
 */
internal fun applySpinnerState(el: HTMLElement, baseClass: String, state: String?) {
    when (state) {
        "working" -> { el.innerHTML = ""; el.className = "$baseClass state-working" }
        "waiting" -> {
            val svg = if ("spinner-header" in baseClass) WAITING_WARNING_SVG_HEADER else WAITING_WARNING_SVG
            el.innerHTML = svg; el.className = "$baseClass state-waiting"
        }
        else -> { el.innerHTML = ""; el.className = baseClass }
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
        val spinners = document.querySelectorAll(".pane-status-spinner[data-session='$sessionId']")
        for (i in 0 until spinners.length) {
            val el = spinners.item(i) as? HTMLElement ?: continue
            val baseClass = if (el.classList.contains("spinner-sidebar")) "pane-status-spinner spinner-sidebar"
                else if (el.classList.contains("spinner-tab")) "pane-status-spinner spinner-tab"
                else "pane-status-spinner spinner-header"
            applySpinnerState(el, baseClass, state)
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
        val tabSpinners = document.querySelectorAll("[data-tab-state='$tabId']")
        for (j in 0 until tabSpinners.length) {
            val el = tabSpinners.item(j) as? HTMLElement ?: continue
            applySpinnerState(el, "pane-status-spinner spinner-tab", tabState)
        }
    }
    val indicator = document.querySelector(".tab-active-indicator") as? HTMLElement
    val activeBtn = document.querySelector(".tab-button.active") as? HTMLElement
    if (indicator != null && activeBtn != null) {
        indicator.style.width = "${(activeBtn.asDynamic().offsetWidth as Number).toDouble()}px"
        indicator.style.transform = "translate3d(${(activeBtn.asDynamic().offsetLeft as Number).toDouble()}px, 0, 0)"
    }
}
