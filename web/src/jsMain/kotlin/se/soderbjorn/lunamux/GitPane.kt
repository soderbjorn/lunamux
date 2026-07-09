/**
 * Git diff/status pane composer for the Lunamux web frontend.
 *
 * `buildGitView` wires together the file-list component
 * ([renderGitList], [updateGitListActiveFile]), the diff viewer trio
 * ([renderGitDiffInline], [renderGitDiffSplit], [renderGitDiffGraphical]),
 * and the search bar helpers ([performDiffSearch], [navigateDiffSearch],
 * [clearDiffSearch]) into a single pane DOM subtree. The header toolbar
 * controls (diff mode, search, auto-refresh, refresh) are injected into
 * the supplied `headerEl`.
 *
 * Per-pane state lives in [GitPaneState] and per-pane DOM handles in
 * [GitPaneView]; both are stored in the [gitPaneStates] / [gitPaneViews]
 * registries from [PaneStateRegistry] so they survive re-renders.
 *
 * @see GitPaneState
 * @see GitPaneView
 * @see WindowConnection.handlePaneContentMessage
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent

/**
 * Constructs the complete DOM subtree for a git pane.
 *
 * Restores persisted state (selected file, diff mode, graphical toggle,
 * column width) from the leaf's content payload and triggers initial
 * file list and diff requests.
 *
 * @param paneId   the unique pane identifier
 * @param leaf     the dynamic leaf node from the server config
 * @param headerEl optional pane header element to inject toolbar buttons into
 * @return the root HTMLElement for the git pane view
 */
fun buildGitView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
    val content = leaf.content
    val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 280
    val initialSelected = content?.selectedFilePath as? String
    val initialAutoRefresh = content?.autoRefresh as? Boolean ?: false
    val initialDiffMode = (content?.diffMode as? String) ?: "Inline"
    val initialGraphical = content?.graphicalDiff as? Boolean ?: false
    val state = gitPaneStates.getOrPut(paneId) { GitPaneState() }
    if (initialSelected != null) state.selectedFilePath = initialSelected
    state.diffMode = initialDiffMode
    state.graphicalDiff = initialGraphical
    state.diffFontSize = (appVm.stateFlow.value.paneFontSize ?: 14)

    val outer = document.createElement("div") as HTMLElement
    outer.className = "git-view"
    val left = document.createElement("div") as HTMLElement
    left.className = "git-list"
    left.style.width = "${initialWidth.coerceIn(0, 640)}px"
    left.style.asDynamic().flexShrink = "0"

    val header = document.createElement("div") as HTMLElement
    header.className = "git-list-header"
    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "git-list-title"; titleEl.textContent = "Git Changes"
    header.appendChild(titleEl)
    left.appendChild(header)

    val listBody = document.createElement("div") as HTMLElement
    listBody.className = "git-list-body"
    left.appendChild(listBody)

    val divider = document.createElement("div") as HTMLElement
    divider.className = "git-divider"
    val right = document.createElement("div") as HTMLElement
    right.className = "git-diff-pane"
    val diffContent = document.createElement("div") as HTMLElement
    diffContent.className = "git-diff-content"

    // Header flyout controls. NOTE: the diff-mode control now lives in the
    // toolkit pane header via `lunamuxPaneActions` (the cycle-on-click
    // "Diff:" button); it is no longer a flyout here.
    val iconSearch = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="16" y1="16" x2="21" y2="21"/></svg>"""
    val iconAutoRefresh = """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12,7 12,12 15,14"/></svg>"""

    fun makeFlyoutBtn(titleText: String, svg: String, extraClass: String = ""): Pair<HTMLElement, HTMLElement> {
        val wrap = document.createElement("div") as HTMLElement
        wrap.className = "pane-flyout-wrap"
        wrap.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
        val btn = document.createElement("button") as HTMLElement
        btn.className = "pane-action-btn" + (if (extraClass.isNotEmpty()) " $extraClass" else "")
        btn.setAttribute("type", "button"); btn.setAttribute("title", titleText); btn.innerHTML = svg
        btn.addEventListener("click", { ev ->
            ev.stopPropagation()
            val open = document.querySelectorAll(".pane-flyout-wrap.open")
            for (i in 0 until open.length) { val other = open.item(i) as HTMLElement; if (other !== wrap) other.classList.remove("open") }
            wrap.classList.toggle("open")
        })
        wrap.appendChild(btn)
        val flyout = document.createElement("div") as HTMLElement
        flyout.className = "pane-flyout"
        flyout.addEventListener("click", { ev -> ev.stopPropagation() })
        wrap.appendChild(flyout)
        return Pair(wrap, flyout)
    }

    // Search flyout
    val (searchWrap, searchFlyout) = makeFlyoutBtn("Search diff", iconSearch)
    val searchInput = document.createElement("input") as HTMLInputElement
    searchInput.type = "text"; searchInput.placeholder = "Search…"
    searchInput.className = "git-search-input"; searchInput.value = state.searchQuery
    searchFlyout.appendChild(searchInput)

    val searchNavRow = document.createElement("div") as HTMLElement
    searchNavRow.className = "pane-flyout-row"
    val searchCounter = document.createElement("span") as HTMLElement
    searchCounter.className = "git-search-counter"
    searchNavRow.appendChild(searchCounter)

    fun makeNavBtn(text: String, titleText: String): HTMLElement {
        val b = document.createElement("button") as HTMLElement
        (b.asDynamic()).type = "button"; b.className = "git-font-btn git-search-nav"
        b.textContent = text; b.title = titleText; b.asDynamic().disabled = true; return b
    }
    val firstBtn = makeNavBtn("⏮", "First match")
    val prevBtn = makeNavBtn("◀", "Previous match")
    val nextBtn = makeNavBtn("▶", "Next match")
    val lastBtn = makeNavBtn("⏭", "Last match")
    val searchNavButtons = listOf(firstBtn, prevBtn, nextBtn, lastBtn)

    var searchTimer: Int? = null
    fun runSearch() {
        state.searchQuery = searchInput.value; state.searchMatchIndex = 0
        performDiffSearch(diffContent, state.searchQuery, state, searchCounter, searchNavButtons)
    }
    searchInput.addEventListener("input", { _ ->
        searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
        searchTimer = kotlinx.browser.window.setTimeout({ runSearch() }, 200)
    })
    firstBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MIN_VALUE) })
    prevBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, -1) })
    nextBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, 1) })
    lastBtn.addEventListener("click", { _ -> navigateDiffSearch(diffContent, state, searchCounter, Int.MAX_VALUE) })
    for (b in searchNavButtons) searchNavRow.appendChild(b)
    searchFlyout.appendChild(searchNavRow)

    // Auto-refresh toggle
    val autoRefreshBtn = document.createElement("button") as HTMLElement
    autoRefreshBtn.className = "pane-action-btn" + if (initialAutoRefresh) " active" else ""
    autoRefreshBtn.setAttribute("type", "button"); autoRefreshBtn.setAttribute("title", "Auto-refresh")
    autoRefreshBtn.innerHTML = iconAutoRefresh
    autoRefreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    autoRefreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        val nowOn = !autoRefreshBtn.classList.contains("active")
        autoRefreshBtn.classList.toggle("active", nowOn)
        launchCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = nowOn))
    })

    // Refresh button
    val refreshBtn = document.createElement("button") as HTMLElement
    refreshBtn.className = "pane-action-btn"
    refreshBtn.setAttribute("type", "button"); refreshBtn.setAttribute("title", "Refresh")
    refreshBtn.innerHTML = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
    refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    refreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        launchCmd(WindowCommand.GitList(paneId = paneId))
        val sel = state.selectedFilePath
        if (sel != null) { state.diffHtml = null; launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel)) }
    })

    if (headerEl != null) {
        val actions = headerEl.querySelector(".dt-pane-actions") as? HTMLElement
        if (actions != null) {
            actions.parentNode?.insertBefore(searchWrap, actions)
            actions.parentNode?.insertBefore(autoRefreshBtn, actions)
            actions.parentNode?.insertBefore(refreshBtn, actions)
            val spacer = document.createElement("div") as HTMLElement
            spacer.className = "pane-header-spacer"
            actions.parentNode?.insertBefore(spacer, actions)
        }
    }

    fun applyFontSize() {
        diffContent.style.fontSize = "${state.diffFontSize}px"
        diffContent.style.lineHeight = "${(state.diffFontSize * 1.54).toInt()}px"
    }

    if (state.selectedFilePath == null && state.diffHtml == null) {
        val emptyState = document.createElement("div") as HTMLElement
        emptyState.className = "git-diff-empty"
        emptyState.innerHTML = """
            <div class="git-diff-empty-icon">&#128221;</div>
            <div class="git-diff-empty-title">No file selected</div>
            <div class="git-diff-empty-subtitle">Choose a file from the list to view its diff</div>
        """.trimIndent()
        diffContent.appendChild(emptyState)
    } else if (state.diffHtml != null) {
        diffContent.innerHTML = state.diffHtml!!
    }
    right.appendChild(diffContent)
    applyFontSize()

    outer.appendChild(left); outer.appendChild(divider); outer.appendChild(right)

    val view = GitPaneView(listBody, diffContent, searchCounter, searchNavButtons)
    gitPaneViews[paneId] = view
    renderGitList(paneId, view, state)
    if (state.entries == null) {
        launchCmd(WindowCommand.GitList(paneId = paneId))
    }
    if (state.selectedFilePath != null && state.diffHtml == null) {
        launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = state.selectedFilePath!!))
    }
    if (initialAutoRefresh) launchCmd(WindowCommand.SetGitAutoRefresh(paneId = paneId, enabled = true))

    divider.addEventListener("mousedown", { ev ->
        val mev = ev as MouseEvent; mev.preventDefault()
        divider.classList.add("dragging")
        val startX = mev.clientX; val startWidth = left.offsetWidth
        val previousBodyCursor = document.body?.style?.cursor ?: ""
        document.body?.style?.cursor = "col-resize"
        var latestPx = startWidth
        val moveListener: (org.w3c.dom.events.Event) -> Unit = { evMove ->
            val m = evMove as MouseEvent
            val w = (startWidth + (m.clientX - startX)).coerceIn(0, 640)
            latestPx = w; left.style.width = "${w}px"
        }
        lateinit var upListener: (org.w3c.dom.events.Event) -> Unit
        upListener = { _ ->
            document.removeEventListener("mousemove", moveListener)
            document.removeEventListener("mouseup", upListener)
            divider.classList.remove("dragging")
            document.body?.style?.cursor = previousBodyCursor
            launchCmd(WindowCommand.SetGitLeftWidth(paneId = paneId, px = latestPx))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })

    return outer
}
