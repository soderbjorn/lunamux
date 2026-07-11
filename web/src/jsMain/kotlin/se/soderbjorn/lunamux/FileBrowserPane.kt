/**
 * File browser pane UI component for the Lunamux web frontend.
 *
 * Renders a split-panel file browser with a navigable directory tree on the left
 * and a rendered file preview (Markdown, source code, or binary placeholder) on the
 * right. Supports filtering by glob pattern, sorting by name or modification time,
 * expand/collapse all, and a draggable column divider for resizing.
 *
 * Directory listings and file contents are fetched from the server via
 * [WindowCommand.FileBrowserListDir] and [WindowCommand.FileBrowserOpenFile].
 * Incoming data is routed through [handlePaneContentMessage] in [WindowConnection].
 *
 * @see FileBrowserPaneState
 * @see FileBrowserPaneView
 * @see buildFileBrowserView
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.MouseEvent

/**
 * Mutable client-side state for a single file browser pane.
 *
 * Holds cached directory listings, the set of expanded directories, the currently
 * selected file, and filter/sort preferences. This state persists across re-renders
 * within the same session via the [fileBrowserPaneStates] registry in [WebState].
 *
 * @see FileBrowserPaneView
 * @see renderFileBrowserTree
 */
class FileBrowserPaneState {
    val dirListings = HashMap<String, Array<dynamic>>()
    val expandedDirs = HashSet<String>()
    var html: String? = null
    var kind: String? = null
    var selectedRelPath: String? = null
    var fileFilter: String = ""
    var sortBy: String = "name"
    /**
     * The "Open in default browser" pane-action button, when the pane has
     * been mounted. Held here so [renderFileBrowserContent] can toggle its
     * visibility when the current file's [kind] changes. Visible only when
     * the host is the Electron app and the selected file is HTML.
     */
    var openInBrowserBtn: HTMLElement? = null
}

/**
 * `true` when the renderer is running inside the Lunamux Electron host
 * (as opposed to a plain web browser). Detected by sniffing the user-agent
 * for the literal substring `"Electron"`, mirroring the check in
 * `main.kt`. Used to gate Electron-only affordances (the
 * `tt-file://` iframe protocol, the "Open in default browser" button).
 */
private val isElectronHost: Boolean by lazy {
    window.navigator.userAgent.contains("Electron", ignoreCase = true)
}

/**
 * Resolves a file-browser pane's absolute filesystem path for [relPath] by
 * joining the pane's leaf [cwd] with the relative path. Returns `null` when
 * the pane is unknown, has no [cwd] set, or [relPath] is `null`.
 *
 * Used by:
 *  - the Electron `tt-file://` iframe `src` builder in [renderFileBrowserContent]
 *  - the "Open in default browser" click handler in [buildFileBrowserView],
 *    which forwards the path to `window.electronApi.openPath`.
 *
 * @param paneId stable pane identifier for the file browser pane
 * @param relPath relative-to-cwd path of the selected file (e.g. `docs/foo.html`)
 * @return absolute filesystem path, or `null` if it cannot be resolved
 */
private fun fileBrowserAbsPath(paneId: String, relPath: String?): String? {
    if (relPath.isNullOrBlank()) return null
    val cfg: dynamic = currentConfig ?: return null
    val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
    var cwd: String? = null
    for (tab in tabsArr) {
        val panes = (tab.panes as? Array<dynamic>) ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) {
                cwd = p.leaf?.cwd as? String
                break
            }
        }
        if (cwd != null) break
    }
    val base = cwd?.trimEnd('/') ?: return null
    val tail = relPath.trimStart('/')
    return "$base/$tail"
}

/**
 * Holds references to the live DOM elements of a file browser pane's two panels.
 *
 * @property listBody the scrollable container for the directory tree on the left
 * @property rendered the container for the rendered file content on the right
 * @property onResize the current view's **pixel-dependent relayout**, or `null` when
 *   it has none. Invoked whenever the pane's box changes (via [resizeObserver]) —
 *   the same general hook [GitPaneView] carries. The file browser's content is
 *   CSS-flowed (flexbox, a full-size iframe preview), so it reflows on its own and
 *   this stays `null`; the hook exists so any future pixel-measured view (a
 *   canvas/SVG preview) can opt in without new plumbing. @see resizeObserver
 * @property resizeObserver the [ResizeObserver] watching the view root for box-size
 *   changes; drives [onResize]. Disconnected when the view is rebuilt or disposed.
 */
class FileBrowserPaneView(
    val listBody: HTMLElement,
    val rendered: HTMLElement,
    var onResize: (() -> Unit)? = null,
    var resizeObserver: ResizeObserver? = null,
)

/**
 * Re-renders the directory tree in the left panel of a file browser pane.
 *
 * Builds a hierarchical list of file/directory rows from [state]'s cached
 * directory listings, respecting expanded/collapsed state. Clicking a directory
 * toggles its expansion and requests its listing from the server if not yet cached.
 * Clicking a file sends [WindowCommand.FileBrowserOpenFile] to load its content.
 *
 * @param paneId the unique identifier of this file browser pane
 * @param view the live DOM handles for the pane's list body and rendered area
 * @param state the current client-side state (directory cache, selection, etc.)
 * @see renderFileBrowserContent
 * @see handlePaneContentMessage
 */
fun renderFileBrowserTree(paneId: String, view: FileBrowserPaneView, state: FileBrowserPaneState) {
    val savedScroll = view.listBody.scrollTop
    view.listBody.innerHTML = ""
    val rootEntries = state.dirListings[""]
    if (rootEntries == null) {
        val loading = document.createElement("div") as HTMLElement
        loading.className = "md-list-empty"
        loading.textContent = "Loading\u2026"
        view.listBody.appendChild(loading)
        return
    }
    if (rootEntries.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "md-list-empty"
        empty.textContent = "No files"
        view.listBody.appendChild(empty)
        return
    }

    fun toggleDir(relPath: String) {
        val nowExpanded = relPath !in state.expandedDirs
        if (nowExpanded) {
            state.expandedDirs.add(relPath)
            if (state.dirListings[relPath] == null) {
                launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = relPath))
            }
        } else {
            state.expandedDirs.remove(relPath)
        }
        launchCmd(WindowCommand.FileBrowserSetExpanded(paneId = paneId, dirRelPath = relPath, expanded = nowExpanded))
        renderFileBrowserTree(paneId, view, state)
    }

    fun renderDir(container: HTMLElement, entries: Array<dynamic>, depth: Int) {
        for (entry in entries) {
            val relPath = entry.relPath as String
            val name = entry.name as String
            val isDir = entry.isDir as Boolean
            val row = document.createElement("div") as HTMLElement
            row.className = "md-list-item"
            if (!isDir && relPath == state.selectedRelPath) row.classList.add("active")
            row.style.asDynamic().paddingLeft = "${4 + depth * 14}px"

            val chevron = document.createElement("span") as HTMLElement
            chevron.className = if (isDir) "md-list-chevron" else "md-list-chevron empty"
            if (isDir) {
                val expanded = relPath in state.expandedDirs
                chevron.textContent = if (expanded) "\u25BE" else "\u25B8"
                chevron.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
                chevron.addEventListener("click", { ev -> ev.stopPropagation(); toggleDir(relPath) })
            }
            row.appendChild(chevron)

            val icon = document.createElement("span") as HTMLElement
            icon.className = "md-list-item-icon"
            if (isDir) {
                icon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M1.75 4.25a0.5 0.5 0 0 1 0.5 -0.5h3.5L7.25 5.25H13.75a0.5 0.5 0 0 1 0.5 0.5v7.5a0.5 0.5 0 0 1 -0.5 0.5H2.25a0.5 0.5 0 0 1 -0.5 -0.5z"/></svg>"""
            } else {
                icon.innerHTML = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" width="14" height="14"><path d="M3 1.75h6.5L13 5.25V14.25a0.5 0.5 0 0 1 -0.5 0.5H3a0.5 0.5 0 0 1 -0.5 -0.5V2.25a0.5 0.5 0 0 1 0.5 -0.5Z"/><path d="M9.25 1.75V5.25H13"/></svg>"""
            }
            val label = document.createElement("span") as HTMLElement
            label.className = "md-list-item-name"
            label.textContent = name
            label.title = relPath
            row.appendChild(icon)
            row.appendChild(label)
            row.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
            row.addEventListener("click", { _ ->
                if (isDir) toggleDir(relPath)
                else launchCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = relPath))
            })
            container.appendChild(row)
            if (isDir && relPath in state.expandedDirs) {
                val children = state.dirListings[relPath]
                if (children == null) {
                    val loading = document.createElement("div") as HTMLElement
                    loading.className = "md-list-empty"
                    loading.style.asDynamic().paddingLeft = "${4 + (depth + 1) * 14}px"
                    loading.textContent = "Loading\u2026"
                    container.appendChild(loading)
                } else {
                    renderDir(container, children, depth + 1)
                }
            }
        }
    }
    renderDir(view.listBody, rootEntries, 0)
    view.listBody.scrollTop = savedScroll
}

/**
 * Renders file content in the right panel of a file browser pane.
 *
 * Displays different presentations depending on the file kind:
 * - "Markdown": renders server-provided HTML directly
 * - "Text": wraps syntax-highlighted HTML in a pre/code block
 * - "Html": renders the document inline in an iframe (sandboxed `srcdoc`
 *   in the plain web client, or via the Electron `tt-file://` protocol
 *   when the host is the Electron app so relative assets resolve)
 * - "Binary": shows a "Preview unavailable" placeholder
 * - null/other: shows a "No document selected" empty state
 *
 * Also toggles the per-pane "Open in default browser" button: visible only
 * when running inside Electron and the current document is HTML.
 *
 * @param paneId the pane identifier — used for Electron `tt-file://` URL
 *   construction and for resolving the per-pane state (selected path,
 *   open-in-browser button)
 * @param view the live DOM handles for the pane
 * @param kind the content type returned by the server ("Markdown", "Text",
 *   "Html", "Binary", or null)
 * @param html the server-rendered HTML content (for Markdown / Html /
 *   syntax-highlighted Text), or null
 * @see renderFileBrowserTree
 * @see fileBrowserAbsPath
 */
fun renderFileBrowserContent(paneId: String, view: FileBrowserPaneView, kind: String?, html: String?) {
    view.rendered.innerHTML = ""
    val isHtmlOrMarkdown = kind == "Markdown" || kind == "Html"
    if (isHtmlOrMarkdown) view.rendered.classList.remove("is-source")
    else view.rendered.classList.add("is-source")

    val state = fileBrowserPaneStates[paneId]
    val wantOpenInBrowser = isElectronHost && kind == "Html"
    // Toggle the per-pane "Open in default browser" button. We look it up
    // via the data-attr tag rather than relying on `state.openInBrowserBtn`
    // alone: the toolkit's pane chrome can rebuild independently of the
    // file-browser body (the toolkit caches the body element keyed by
    // pane id but re-renders the chrome around it on every shell refresh),
    // and any rebuild detaches the inserted button strip. Re-querying by
    // attribute finds the *current* button instance, which the most recent
    // `buildFileBrowserView` call inserted into the live chrome.
    val openBtn = document.querySelector("button[data-fb-open-browser-pane=\"$paneId\"]") as? HTMLElement
        ?: state?.openInBrowserBtn
    openBtn?.style?.display = if (wantOpenInBrowser) "" else "none"

    when (kind) {
        "Markdown" -> { view.rendered.innerHTML = html ?: "" }
        "Html" -> {
            val iframe = document.createElement("iframe") as HTMLElement
            iframe.className = "file-html-preview"
            // Sizing + border live in styles.css (`.file-html-preview`). We
            // intentionally don't set `border = "0"` here so the themed 1px
            // separator border applies — it visually frames the iframe's
            // self-painted background against the themed pane surface.
            val absPath = fileBrowserAbsPath(paneId, state?.selectedRelPath)
            if (isElectronHost && absPath != null) {
                // Electron-only: load via the `tt-file://` custom protocol
                // registered by the main process. Relative assets (images,
                // CSS, JS) referenced by the page resolve against this
                // protocol's directory and load correctly.
                val encoded = js("encodeURI")(absPath.trimStart('/')) as String
                iframe.setAttribute("src", "tt-file://localhost/$encoded")
            } else {
                // Plain web fallback: render via sandboxed `srcdoc`. Scripts
                // run in a unique origin (no `allow-same-origin`) so they
                // can't reach the host page. Relative assets won't load
                // because `srcdoc` documents have no base URL.
                iframe.setAttribute("sandbox", "allow-scripts allow-popups")
                iframe.setAttribute("srcdoc", html ?: "")
            }
            view.rendered.appendChild(iframe)
        }
        "Text" -> {
            val pre = document.createElement("pre") as HTMLElement
            pre.className = "file-source"
            val code = document.createElement("code") as HTMLElement
            code.innerHTML = html ?: ""
            pre.appendChild(code)
            view.rendered.appendChild(pre)
        }
        "Binary" -> {
            val placeholder = document.createElement("div") as HTMLElement
            placeholder.className = "md-rendered-empty"
            placeholder.innerHTML = """
                <div class="md-rendered-empty-icon">&#128452;</div>
                <div class="md-rendered-empty-title">Binary file</div>
                <div class="md-rendered-empty-subtitle">Preview unavailable</div>
            """.trimIndent()
            view.rendered.appendChild(placeholder)
        }
        else -> {
            val emptyState = document.createElement("div") as HTMLElement
            emptyState.className = "md-rendered-empty"
            emptyState.innerHTML = """
                <div class="md-rendered-empty-icon">&#128196;</div>
                <div class="md-rendered-empty-title">No document selected</div>
                <div class="md-rendered-empty-subtitle">Choose a file from the tree</div>
            """.trimIndent()
            view.rendered.appendChild(emptyState)
        }
    }
}

/**
 * Constructs the complete DOM subtree for a file browser pane, including
 * the directory tree, content panel, column divider, and header toolbar controls
 * (filter, sort, expand/collapse all, refresh).
 *
 * Called by [buildLeafCell] when a pane's content kind is "fileBrowser".
 * Restores persisted state (expanded dirs, selected file, filter, sort, column width)
 * from the leaf's content payload and triggers initial directory listing requests.
 *
 * @param paneId the unique pane identifier
 * @param leaf the dynamic leaf node from the server config containing content state
 * @param headerEl optional pane header element to inject toolbar buttons into
 * @return the root HTMLElement for the file browser view
 * @see buildLeafCell
 * @see FileBrowserPaneState
 */
fun buildFileBrowserView(paneId: String, leaf: dynamic, headerEl: HTMLElement? = null): HTMLElement {
    val content = leaf.content
    val initialWidth = (content?.leftColumnWidthPx as? Number)?.toInt() ?: 240
    val initialSelected = content?.selectedRelPath as? String
    val persistedExpanded = content?.expandedDirs as? Array<String>
    val persistedFilter = content?.fileFilter as? String
    val persistedSort = content?.sortBy as? String

    val state = fileBrowserPaneStates.getOrPut(paneId) { FileBrowserPaneState() }
    if (initialSelected != null) state.selectedRelPath = initialSelected
    if (persistedExpanded != null) for (p in persistedExpanded) state.expandedDirs.add(p)
    if (persistedFilter != null) state.fileFilter = persistedFilter
    if (persistedSort != null) state.sortBy = persistedSort

    val outer = document.createElement("div") as HTMLElement
    outer.className = "md-view"

    val left = document.createElement("div") as HTMLElement
    left.className = "md-list"
    left.style.width = "${initialWidth.coerceIn(0, 640)}px"
    left.style.asDynamic().flexShrink = "0"

    val header = document.createElement("div") as HTMLElement
    header.className = "md-list-header"
    val titleEl = document.createElement("span") as HTMLElement
    titleEl.className = "md-list-title"
    titleEl.textContent = "Files"
    header.appendChild(titleEl)
    left.appendChild(header)

    // Header flyout controls
    val iconFilter = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="2,3 14,3 10,8.5 10,13 6,11 6,8.5"/></svg>"""
    val iconSort = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="4" x2="11" y2="4"/><line x1="3" y1="8" x2="9" y2="8"/><line x1="3" y1="12" x2="7" y2="12"/><polyline points="11,10 13,12 15,10"/><line x1="13" y1="5" x2="13" y2="12"/></svg>"""
    val iconExpand = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,6 8,11 13,6"/></svg>"""
    val iconCollapse = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3,10 8,5 13,10"/></svg>"""

    // Filter flyout
    val filterWrap = document.createElement("div") as HTMLElement
    filterWrap.className = "pane-flyout-wrap"
    filterWrap.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    val filterTrigger = document.createElement("button") as HTMLElement
    filterTrigger.className = "pane-action-btn"
    filterTrigger.setAttribute("type", "button")
    filterTrigger.setAttribute("title", "Filter")
    filterTrigger.innerHTML = iconFilter
    filterTrigger.addEventListener("click", { ev ->
        ev.stopPropagation()
        val open = document.querySelectorAll(".pane-flyout-wrap.open")
        for (i in 0 until open.length) {
            val other = open.item(i) as HTMLElement
            if (other !== filterWrap) other.classList.remove("open")
        }
        filterWrap.classList.toggle("open")
    })
    filterWrap.appendChild(filterTrigger)
    val filterFlyout = document.createElement("div") as HTMLElement
    filterFlyout.className = "pane-flyout"
    filterFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

    val filterInput = document.createElement("input") as HTMLInputElement
    filterInput.className = "git-search-input"
    filterInput.type = "text"
    filterInput.placeholder = "Filter (e.g. *.md)"
    filterInput.value = state.fileFilter
    filterInput.title = "Filter files by name (glob or substring)"
    filterInput.style.width = "100%"
    filterInput.style.marginBottom = "4px"
    filterFlyout.appendChild(filterInput)

    var filterDebounce: Int? = null
    fun dispatchFilter(value: String) {
        state.fileFilter = value
        filterDebounce?.let { window.clearTimeout(it) }
        filterDebounce = window.setTimeout({
            launchCmd(WindowCommand.SetFileBrowserFilter(paneId = paneId, filter = value))
        }, 250)
    }
    filterInput.addEventListener("input", { _ -> dispatchFilter(filterInput.value) })

    val presets = listOf(
        "" to "All files", "*.md" to "Markdown", "*.{kt,kts}" to "Kotlin",
        "*.{js,ts,jsx,tsx}" to "JS / TS", "*.{py}" to "Python", "*.{java,scala,groovy}" to "JVM",
        "*.{c,h,cpp,hpp,cc}" to "C / C++", "*.{rs}" to "Rust", "*.{go}" to "Go",
        "*.{json,yaml,yml,toml,xml}" to "Config", "*.{png,jpg,jpeg,gif,svg,webp}" to "Images",
    )
    for ((value, label) in presets) {
        val item = document.createElement("div") as HTMLElement
        item.className = "pane-split-item"
        item.textContent = label
        item.addEventListener("click", { ev ->
            ev.stopPropagation()
            filterInput.value = value
            dispatchFilter(value)
            filterWrap.classList.remove("open")
        })
        filterFlyout.appendChild(item)
    }
    filterWrap.appendChild(filterFlyout)

    // Sort flyout
    val sortWrap = document.createElement("div") as HTMLElement
    sortWrap.className = "pane-flyout-wrap"
    sortWrap.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    val sortTrigger = document.createElement("button") as HTMLElement
    sortTrigger.className = "pane-action-btn"
    sortTrigger.setAttribute("type", "button")
    sortTrigger.setAttribute("title", "Sort order")
    sortTrigger.innerHTML = iconSort
    sortTrigger.addEventListener("click", { ev ->
        ev.stopPropagation()
        val open = document.querySelectorAll(".pane-flyout-wrap.open")
        for (i in 0 until open.length) {
            val other = open.item(i) as HTMLElement
            if (other !== sortWrap) other.classList.remove("open")
        }
        sortWrap.classList.toggle("open")
    })
    sortWrap.appendChild(sortTrigger)
    val sortFlyout = document.createElement("div") as HTMLElement
    sortFlyout.className = "pane-flyout"
    sortFlyout.addEventListener("click", { ev -> ev.stopPropagation() })

    val sortOptions = listOf("name" to "By name", "mtime" to "By last change")
    val sortItems = HashMap<String, HTMLElement>()
    for ((value, label) in sortOptions) {
        val item = document.createElement("div") as HTMLElement
        item.className = "pane-split-item"
        if (value == state.sortBy) item.classList.add("active")
        item.innerHTML = """<span class="pane-split-item-label">$label</span><span class="check">${if (value == state.sortBy) "\u2713" else ""}</span>"""
        item.addEventListener("click", { ev ->
            ev.stopPropagation()
            state.sortBy = value
            for ((k, el) in sortItems) {
                val check = el.querySelector(".check") as? HTMLElement
                if (k == value) { el.classList.add("active"); check?.textContent = "\u2713" }
                else { el.classList.remove("active"); check?.textContent = "" }
            }
            val sortEnum = if (value == "mtime") FileBrowserSort.MTIME else FileBrowserSort.NAME
            launchCmd(WindowCommand.SetFileBrowserSort(paneId = paneId, sort = sortEnum))
            sortWrap.classList.remove("open")
        })
        sortFlyout.appendChild(item)
        sortItems[value] = item
    }
    sortWrap.appendChild(sortFlyout)

    // Expand/Collapse all
    val expandBtn = document.createElement("button") as HTMLElement
    expandBtn.className = "pane-action-btn"
    expandBtn.setAttribute("type", "button"); expandBtn.setAttribute("title", "Expand all")
    expandBtn.innerHTML = iconExpand
    expandBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    expandBtn.addEventListener("click", { ev -> ev.stopPropagation(); launchCmd(WindowCommand.FileBrowserExpandAll(paneId = paneId)) })

    val collapseBtn = document.createElement("button") as HTMLElement
    collapseBtn.className = "pane-action-btn"
    collapseBtn.setAttribute("type", "button"); collapseBtn.setAttribute("title", "Collapse all")
    collapseBtn.innerHTML = iconCollapse
    collapseBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    collapseBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        state.expandedDirs.clear()
        launchCmd(WindowCommand.FileBrowserCollapseAll(paneId = paneId))
        val v = fileBrowserPaneViews[paneId]
        if (v != null) renderFileBrowserTree(paneId, v, state)
    })

    document.addEventListener("click", { _ ->
        filterWrap.classList.remove("open")
        sortWrap.classList.remove("open")
    })

    // Refresh button
    val refreshBtn = document.createElement("button") as HTMLElement
    refreshBtn.className = "pane-action-btn"
    refreshBtn.setAttribute("type", "button"); refreshBtn.setAttribute("title", "Refresh")
    refreshBtn.innerHTML = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 8a6 6 0 0 1 10.5-3.9"/><polyline points="13,2 13,5 10,5"/><path d="M14 8a6 6 0 0 1 -10.5 3.9"/><polyline points="3,14 3,11 6,11"/></svg>"""
    refreshBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    refreshBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
        for (dir in state.expandedDirs) launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
    })

    // "Open in default browser" button — Electron-only. Hidden by default;
    // shown by [renderFileBrowserContent] only when the host is the Electron
    // app and the currently selected file is HTML. Click forwards the file's
    // absolute path to `window.electronApi.openPath`, which IPC-invokes
    // `shell.openPath` in the Electron main process.
    val openInBrowserBtn = document.createElement("button") as HTMLElement
    openInBrowserBtn.className = "pane-action-btn"
    openInBrowserBtn.setAttribute("type", "button")
    openInBrowserBtn.setAttribute("title", "Open in default browser")
    // Tag the button with the pane id so [renderFileBrowserContent] can
    // find it via DOM query rather than relying on the per-pane state
    // reference (which may point at a stale, chrome-rebuilt-out button).
    openInBrowserBtn.setAttribute("data-fb-open-browser-pane", paneId)
    openInBrowserBtn.innerHTML = """<svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10 3h3v3"/><path d="M13 3l-6 6"/><path d="M11.5 9.5V12a1.5 1.5 0 0 1 -1.5 1.5H4A1.5 1.5 0 0 1 2.5 12V6A1.5 1.5 0 0 1 4 4.5h2.5"/></svg>"""
    openInBrowserBtn.style.display = "none"
    openInBrowserBtn.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
    openInBrowserBtn.addEventListener("click", { ev ->
        ev.stopPropagation()
        val rel = state.selectedRelPath
        val absPath = fileBrowserAbsPath(paneId, rel) ?: return@addEventListener
        val electronApi = window.asDynamic().electronApi
        if (electronApi != null && electronApi.openPath != null) {
            electronApi.openPath(absPath)
        }
    })
    state.openInBrowserBtn = openInBrowserBtn

    if (headerEl != null) {
        val actions = headerEl.querySelector(".dt-pane-actions") as? HTMLElement
        if (actions != null) {
            actions.parentNode?.insertBefore(filterWrap, actions)
            actions.parentNode?.insertBefore(sortWrap, actions)
            actions.parentNode?.insertBefore(expandBtn, actions)
            actions.parentNode?.insertBefore(collapseBtn, actions)
            actions.parentNode?.insertBefore(refreshBtn, actions)
            actions.parentNode?.insertBefore(openInBrowserBtn, actions)
            val spacer = document.createElement("div") as HTMLElement
            spacer.className = "pane-header-spacer"
            actions.parentNode?.insertBefore(spacer, actions)
        }
    }

    val listBody = document.createElement("div") as HTMLElement
    listBody.className = "md-list-body"
    left.appendChild(listBody)

    val divider = document.createElement("div") as HTMLElement
    divider.className = "md-divider"
    val right = document.createElement("div") as HTMLElement
    right.className = "md-rendered"

    outer.appendChild(left); outer.appendChild(divider); outer.appendChild(right)
    wireMarkdownAnchorLinks(right)

    // See [buildGitView] for the rationale: the same world-agnostic resize hook is
    // installed here for symmetry and future-proofing. The file browser's content is
    // CSS-flowed, so [FileBrowserPaneView.onResize] stays `null` and the observer is
    // a no-op today — but a later pixel-measured preview can opt in with no plumbing.
    fileBrowserPaneViews[paneId]?.resizeObserver?.disconnect()
    val view = FileBrowserPaneView(listBody, right)
    fileBrowserPaneViews[paneId] = view
    val resizeObs = ResizeObserver { _, _ -> view.onResize?.invoke() }
    resizeObs.observe(outer)
    view.resizeObserver = resizeObs
    renderFileBrowserTree(paneId, view, state)
    renderFileBrowserContent(paneId, view, state.kind, state.html)

    launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = ""))
    for (dir in state.expandedDirs) {
        if (state.dirListings[dir] == null) launchCmd(WindowCommand.FileBrowserListDir(paneId = paneId, dirRelPath = dir))
    }
    if (state.selectedRelPath != null && state.html == null) {
        launchCmd(WindowCommand.FileBrowserOpenFile(paneId = paneId, relPath = state.selectedRelPath!!))
    }

    divider.addEventListener("mousedown", { ev ->
        val mev = ev as MouseEvent
        mev.preventDefault()
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
            launchCmd(WindowCommand.SetFileBrowserLeftWidth(paneId = paneId, px = latestPx))
        }
        document.addEventListener("mousemove", moveListener)
        document.addEventListener("mouseup", upListener)
    })

    return outer
}
