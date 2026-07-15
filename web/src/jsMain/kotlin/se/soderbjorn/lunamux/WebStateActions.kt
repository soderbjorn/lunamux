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
package se.soderbjorn.lunamux

import se.soderbjorn.darkness.web.applyTheme

import se.soderbjorn.darkness.core.*

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.viewmodel.findLeafBySessionId
import se.soderbjorn.lunamux.client.viewmodel.resolvedTheme

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
    fun scan(tabs: Array<dynamic>?): String? {
        if (tabs == null) return null
        for (tab in tabs) {
            if ((tab.id as? String) == tabId) return tab.focusedPaneId as? String
        }
        return null
    }
    // Search every world's tabs (the active world may not be the default
    // one mirrored at top level), then the legacy flat tabs.
    (cfg.worlds as? Array<dynamic>)?.let { worlds ->
        for (world in worlds) {
            scan(world.tabs as? Array<dynamic>)?.let { return it }
        }
    }
    return scan(cfg.tabs as? Array<dynamic>)
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
    // Search across every world's tabs — the target tab may live in a
    // non-default active world, not just the legacy flat mirror.
    val allTabs = if (cfg.worlds.isNotEmpty()) cfg.worlds.flatMap { it.tabs } else cfg.tabs
    val tab = allTabs.firstOrNull { it.id == tabId }
    if (tab != null) {
        tab.focusedPaneId
            ?.let { id -> tab.panes.firstOrNull { it.leaf.id == id }?.leaf?.cwd }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        tab.panes
            .firstNotNullOfOrNull { it.leaf.cwd?.takeIf { c -> c.isNotBlank() } }
            ?.let { return it }
    }
    return allTabs.firstNotNullOfOrNull { t ->
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
    fun scan(tabs: Array<dynamic>?): String? {
        if (tabs == null) return null
        for (tab in tabs) {
            val panes = tab.panes as? Array<dynamic> ?: continue
            for (p in panes) {
                if ((p.leaf?.id as? String) == paneId) return tab.id as? String
            }
        }
        return null
    }
    // Search every world's tabs, then the legacy flat mirror.
    (cfg.worlds as? Array<dynamic>)?.let { worlds ->
        for (world in worlds) {
            scan(world.tabs as? Array<dynamic>)?.let { return it }
        }
    }
    return scan(cfg.tabs as? Array<dynamic>)
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
    // The world currently on screen: the active theme in 2D / the home 3D world, the other-world
    // (destination) theme while a transit is previewing it — so the ring's terminal bodies
    // re-theme to match the world's panes instead of staying the home theme. Identical to
    // [currentResolvedTheme] whenever no world preview is active, so 2D is unaffected.
    // @see currentWorldTheme
    val theme = currentWorldTheme()
    // The terminal canvas sits on the pane surface (white on Lunamux Light),
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

    // Resolve through the per-world override so switching worlds repaints to
    // that world's theme pair (falls back to the global selection).
    val theme = currentResolvedTheme()
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
    if (!isDesktopNotificationsEnabled()) return
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
            val notification = js("new Notification('Lunamux', opts)")
            notification.onclick = {
                kotlinx.browser.window.focus()
                notification.close()
            }
        }
    }
    previousSessionStates.clear()
    previousSessionStates.putAll(effective)
}

/** Breathing period (ms) and midpoint floor for the per-pane / per-tab / logo dots. */
private const val STATUS_PULSE_PERIOD_MS = 2500.0
private const val STATUS_PULSE_FLOOR = 0.3

/**
 * Breathing period (ms) and midpoint floor for the sidebar world-status
 * "N working" segment — a calm, shallow breathe (mirrors the former
 * `world-status-breathe` keyframe). Exposed so `appendWorldStatusCounts` in
 * `LunamuxToolkitBootstrap` seeds each fresh segment at the same curve.
 */
internal const val WORLD_WORKING_PULSE_PERIOD_MS = 2600.0
internal const val WORLD_WORKING_PULSE_FLOOR = 0.55

/**
 * Breathing period (ms) and midpoint floor for the world-status "M waiting"
 * segment — a slightly deeper, slower breathe (mirrors the former
 * `world-status-breathe-strong` keyframe).
 */
internal const val WORLD_WAITING_PULSE_PERIOD_MS = 3000.0
internal const val WORLD_WAITING_PULSE_FLOOR = 0.48

/**
 * One family of indicators that share a single breathing curve, all driven off
 * the same global wall clock so recreating any member never restarts its cycle.
 *
 * @property selector CSS selector matching this family's elements.
 * @property periodMs the breath period in milliseconds.
 * @property floor    the dimmest opacity, reached at the cycle midpoint (the
 *   cycle boundaries are always fully opaque at 1.0).
 */
private class PulseGroup(val selector: String, val periodMs: Double, val floor: Double)

/**
 * Every breathing indicator family, each with its own curve but all sampled from
 * the one shared clock in [pulseTick]. Adding the world-status segments here (not
 * a CSS `@keyframes` animation) is what keeps them steady across the frequent
 * `innerHTML` rebuilds of the world-status block — see [pulseTick].
 */
private val PULSE_GROUPS: List<PulseGroup> = listOf(
    // Per-pane / per-tab dots and the aggregate sidebar logo dot — a firm heartbeat.
    PulseGroup(
        ".tt-status-dot.state-working, .tt-status-dot.state-waiting, " +
            ".app-logo-dot.state-working, .app-logo-dot.state-waiting",
        STATUS_PULSE_PERIOD_MS, STATUS_PULSE_FLOOR,
    ),
    // World-status "N working" — shallow, so it reads as a soft heartbeat.
    PulseGroup(
        ".world-status-bar .world-status-working",
        WORLD_WORKING_PULSE_PERIOD_MS, WORLD_WORKING_PULSE_FLOOR,
    ),
    // World-status "M waiting" — a touch deeper and slower, drawing more of the eye.
    PulseGroup(
        ".world-status-bar .world-status-waiting",
        WORLD_WAITING_PULSE_PERIOD_MS, WORLD_WAITING_PULSE_FLOOR,
    ),
)

/** Live `requestAnimationFrame` handle for the pulse driver, or 0 when stopped. */
private var pulseRafHandle: Int = 0

/**
 * Minimum wall-clock gap (ms) between two pulse repaints — i.e. the pulse
 * driver's effective cap of ~8 repaints/second regardless of the display's
 * refresh rate.
 *
 * The status "breathe" is a slow 2.5–3 s curve (see [STATUS_PULSE_PERIOD_MS]
 * and the world-status periods), so a handful of samples per second is visually
 * indistinguishable from a per-frame (60 fps+) repaint while doing a fraction of
 * the work. Before this cap [pulseTick] ran three whole-document
 * `querySelectorAll` scans plus inline-style writes on *every* animation frame
 * for as long as any agent was working/waiting — i.e. exactly while the user is
 * typing at a working pane — contending with xterm's input/render on the main
 * thread and making typing feel sluggish (issue #125). rAF still drives the
 * schedule (so the loop pauses when the tab is backgrounded); this only gates
 * the expensive repaint body.
 */
private const val PULSE_MIN_INTERVAL_MS: Double = 120.0

/**
 * [kotlinx.browser.Window.performance] timestamp of the last pulse repaint, so
 * [pulseTick] can cheaply skip its DOM work on animation frames that arrive
 * sooner than [PULSE_MIN_INTERVAL_MS]. Seeded to −∞ so the very first tick
 * always paints.
 */
private var lastPulsePaintMs: Double = Double.NEGATIVE_INFINITY

/**
 * True when the user has asked for reduced motion; the indicators then hold a
 * static full-opacity look instead of breathing.
 */
private fun prefersReducedMotion(): Boolean =
    kotlinx.browser.window.matchMedia("(prefers-reduced-motion: reduce)").matches

/**
 * The breathing opacity for a given curve at the current instant, derived from
 * one global clock: 1.0 at the cycle boundaries, easing down to [floor] at the
 * midpoint and back (a smoothstep approximation of the old `fade-warning`
 * ease-in-out curve). Because it is a pure function of the wall clock, every
 * indicator painted from it shows the SAME opacity regardless of when it was
 * created.
 *
 * @param periodMs the breath period in milliseconds.
 * @param floor    the dimmest opacity, reached at the cycle midpoint.
 * @return an opacity in `[floor, 1.0]`.
 */
private fun currentPulseOpacity(periodMs: Double, floor: Double): Double {
    val phase = (kotlinx.browser.window.performance.now() % periodMs) / periodMs
    val dip = kotlin.math.abs(2.0 * phase - 1.0)   // 1 at boundaries, 0 at midpoint
    val eased = dip * dip * (3.0 - 2.0 * dip)       // smoothstep ≈ ease-in-out
    return floor + (1.0 - floor) * eased
}

/**
 * One frame of the global pulse driver: for each [PulseGroup], paints that
 * group's [currentPulseOpacity] onto its live members, then reschedules itself.
 * Stops (clearing [pulseRafHandle]) once no group has any member, so an idle app
 * burns no frames — [ensurePulseRunning] restarts it the next time an indicator
 * enters working/waiting.
 *
 * Why a JS rAF loop rather than a CSS `@keyframes` animation: the chrome that
 * hosts these indicators is rebuilt on config pushes — the toolkit recreates the
 * sidebar / tab-strip / pane-header badges, and [updateWorldStatusFooter] blows
 * the whole world-status block away with `innerHTML = ""` on every state push. A
 * CSS animation on a freshly-created element restarts from its first keyframe
 * (`opacity: 1`), so every rebuild snapped the indicator to full brightness — and
 * because rebuilds fire on the program-title feature's ~750 ms config cadence
 * while a task runs (Claude Code rewrites its terminal title with a live task
 * summary), the breathe looked erratic. Driving opacity from one shared clock is
 * immune to that: a recreated element simply adopts the current global opacity on
 * the next frame — no snap, all in sync.
 *
 * Throttled to [PULSE_MIN_INTERVAL_MS]: rAF still fires ~60×/s, but frames that
 * arrive within the interval of the last repaint just reschedule and return, so
 * the expensive `querySelectorAll` + style-write body runs only ~8×/s. The
 * `anyLive` stop-check therefore only runs on repaint frames — between them the
 * loop is assumed still live, which at worst keeps it spinning one extra
 * interval (~120 ms of no-op rAF ticks) after the final indicator clears.
 */
private fun pulseTick() {
    val now = kotlinx.browser.window.performance.now()
    if (now - lastPulsePaintMs < PULSE_MIN_INTERVAL_MS) {
        // Too soon since the last repaint — skip the DOM work this frame and
        // just keep the schedule alive.
        pulseRafHandle = kotlinx.browser.window.requestAnimationFrame { pulseTick() }
        return
    }
    lastPulsePaintMs = now
    var anyLive = false
    for (group in PULSE_GROUPS) {
        val els = kotlinx.browser.document.querySelectorAll(group.selector)
        if (els.length == 0) continue
        anyLive = true
        val opacity = currentPulseOpacity(group.periodMs, group.floor).toString()
        for (i in 0 until els.length) {
            (els.item(i) as? HTMLElement)?.style?.opacity = opacity
        }
    }
    if (!anyLive) {
        pulseRafHandle = 0
        return
    }
    pulseRafHandle = kotlinx.browser.window.requestAnimationFrame { pulseTick() }
}

/**
 * Kicks the global pulse driver if it isn't already running. Under reduced
 * motion the loop is skipped entirely (indicators keep the static full-opacity
 * look that [startPulse] sets). Called whenever an indicator enters the
 * working/waiting state.
 */
internal fun ensurePulseRunning() {
    if (prefersReducedMotion()) return
    if (pulseRafHandle == 0) {
        pulseRafHandle = kotlinx.browser.window.requestAnimationFrame { pulseTick() }
    }
}

/**
 * Seeds a freshly-working/waiting indicator with the current breathing opacity
 * for its curve and ensures the driver is running, so the element is at the
 * correct global phase from its very first frame (no one-frame flash at full
 * opacity) and then tracks the shared clock. Under reduced motion, holds static
 * full opacity.
 *
 * The default curve is the dots' [STATUS_PULSE_PERIOD_MS]/[STATUS_PULSE_FLOOR];
 * the world-status segment builders pass their own
 * `WORLD_WORKING_*` / `WORLD_WAITING_*` curve so a fresh segment seeds to the
 * matching phase, not the dots'.
 *
 * @param el       the indicator that just entered the working/waiting state.
 * @param periodMs the breath period in milliseconds for this indicator's curve.
 * @param floor    the dimmest opacity for this indicator's curve.
 */
internal fun startPulse(
    el: HTMLElement,
    periodMs: Double = STATUS_PULSE_PERIOD_MS,
    floor: Double = STATUS_PULSE_FLOOR,
) {
    if (prefersReducedMotion()) {
        el.style.opacity = "1"
        return
    }
    el.style.opacity = currentPulseOpacity(periodMs, floor).toString()
    ensurePulseRunning()
}

/**
 * Applies the working/waiting state to a status indicator (`.tt-status-dot`) —
 * the unified indicator used on sidebar rows, pane headers, and the tab strip.
 * Its look is driven by the `state-working` / `state-waiting` modifier classes
 * (see `.tt-status-dot` in styles.css) plus a JS-driven breathing opacity, all
 * painted in the theme's foreground colour (issue #38): idle clears both classes
 * so the base rule paints a solid dot; `state-working` makes that dot breathe;
 * `state-waiting` swaps the dot for a pulsing warning/exclamation triangle.
 *
 * The breathe is driven by the shared rAF loop ([pulseTick]) rather than a CSS
 * animation, so recreating the element (the toolkit rebuilds these on chrome
 * renders) never restarts a keyframe and snaps the dot to full opacity.
 *
 * Called by [updateStateIndicators] on each server push and by the dot builders
 * at construction time.
 *
 * @param el the `.tt-status-dot` element to repaint.
 * @param state the session state (`"working"`, `"waiting"`, or null/idle).
 * @see startPulse
 */
internal fun applyDotState(el: HTMLElement, state: String?) {
    el.classList.remove("state-working")
    el.classList.remove("state-waiting")
    when (state) {
        "working" -> { el.classList.add("state-working"); startPulse(el) }
        "waiting" -> { el.classList.add("state-waiting"); startPulse(el) }
        // Idle: drop the JS-driven opacity so the element reverts to its CSS
        // default (fully opaque). Harmless for `.tt-status-dot` (hidden while
        // idle) and correct for the always-visible `.app-logo-dot` bead.
        else -> { el.style.opacity = "" }
    }
}

/**
 * Folds the manual 3D signal overrides ([spikeWorkingOverride] /
 * [spikeWaitingOverride], keyed by pane id and toggled by the 3D `w` key) into a
 * session-keyed state map, so every session-level indicator — the global logo
 * dot, the per-pane dots, and the per-tab aggregates — reflects the same preview
 * the 3D warp-core boxes and the 2D world-status footer already show.
 *
 * The overrides are per-pane but these indicators are per-session, so a pane's
 * override REPLACES its session's live state: for any session with at least one
 * overriding pane the live base is dropped and only its overriding panes decide
 * the state, aggregated waiting > working > cleared (matching the aggregate rule
 * used everywhere else). Panes left on "auto" (no map entry) keep the live state.
 * Sessions with no overriding pane are untouched.
 *
 * Returns [base] unchanged (no allocation) when no override is set — the common
 * case — so the normal state path pays nothing.
 *
 * Also reused by `currentSessionStates` in `LunamuxToolkitBootstrap` so the
 * construction-time dot seeds (on chrome rebuilds that carry no state push —
 * theme / sidebar toggles, drag-end) reflect the same overrides as the live
 * repaint path.
 *
 * @param base the raw server session-id → state map.
 * @return the effective session-id → state map with overrides applied.
 * @see updateStateIndicators
 */
internal fun applySignalOverrides(base: Map<String, String?>): Map<String, String?> {
    if (spikeWorkingOverride.isEmpty() && spikeWaitingOverride.isEmpty()) return base
    val cfg = latestWindowConfig ?: return base
    // Per session: the forced state aggregated across its overriding panes. A key
    // is present iff the session has at least one overriding pane (so its live
    // base must be dropped); the value is the forced state (null = cleared/idle).
    val forcedBySession = HashMap<String, String?>()
    for (world in cfg.worlds) {
        for (tab in world.tabs) {
            for (pane in tab.panes) {
                val paneId = pane.leaf.id
                val hasWorking = spikeWorkingOverride.containsKey(paneId)
                val hasWaiting = spikeWaitingOverride.containsKey(paneId)
                if (!hasWorking && !hasWaiting) continue   // auto — keep live state
                val sid = pane.leaf.sessionId
                val forced = when {
                    spikeWaitingOverride[paneId] == true -> "waiting"
                    spikeWorkingOverride[paneId] == true -> "working"
                    else -> null                            // clear — forced idle
                }
                val prev = forcedBySession[sid]
                forcedBySession[sid] = when {
                    forced == "waiting" || prev == "waiting" -> "waiting"
                    forced == "working" || prev == "working" -> "working"
                    else -> null
                }
            }
        }
    }
    if (forcedBySession.isEmpty()) return base
    val out = HashMap(base)
    for ((sid, state) in forcedBySession) out[sid] = state
    return out
}

/**
 * Updates all session state indicators across the sidebar, tab bar,
 * pane headers, and the lower-right app logo dot.
 */
internal fun updateStateIndicators(sessionStates: Map<String, String?>) {
    val document = kotlinx.browser.document
    // Fold the manual 3D signal overrides into the state map so every
    // session-level indicator agrees with the 3D warp-core boxes and 2D footer.
    // Desktop notifications intentionally stay on the RAW states below — an
    // override is a visual preview, not a real agent event to notify about.
    val effectiveStates = applySignalOverrides(sessionStates)
    checkStateNotifications(sessionStates)
    updateAppLogoState(effectiveStates)
    // Repaint the sidebar-footer world-status block from the same push. It reads
    // the authoritative `windowState.states` itself (not `sessionStates`) and
    // applies the overrides per-pane, so a config-only push — worlds added /
    // renamed / re-themed — refreshes it too.
    updateWorldStatusFooter()
    val effective = HashMap(effectiveStates)
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
    // Aggregate per-tab dots over the ACTIVE world's tabs (those are the ones
    // in the strip with `data-tab-state`), not the legacy default-world mirror
    // — otherwise a non-default active world's tab dots never update. Per-pane
    // dots above are keyed by sessionId across all worlds and need no scoping.
    val aggTabs = cfg.activeWorldOrNull()?.tabs ?: cfg.tabs
    for (tab in aggTabs) {
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
