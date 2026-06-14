/**
 * Renders the changed-files list in the left panel of a git pane.
 *
 * Files are grouped by parent directory; each entry shows a status
 * badge (A/M/D/R/U) and basename. Sections are collapsible and
 * collapsed-state is tracked in the package-level [collapsedGitSections]
 * set so it survives re-renders.
 *
 * @see GitPaneView
 * @see GitPaneState
 * @see buildGitView
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Updates the "active" CSS class on file list items to highlight the
 * currently selected file.
 */
internal fun updateGitListActiveFile(view: GitPaneView, filePath: String?) {
    val items = view.listBody.querySelectorAll(".git-list-item")
    for (i in 0 until items.length) {
        val el = items.item(i) as HTMLElement
        val path = el.querySelector(".git-list-item-name")?.getAttribute("title")
        if (path == filePath) el.classList.add("active") else el.classList.remove("active")
    }
}

/**
 * Re-renders the changed files list in the left panel, grouped by parent
 * directory. Clicking a file dispatches [WindowCommand.GitDiff] for it.
 */
internal fun renderGitList(paneId: String, view: GitPaneView, state: GitPaneState) {
    val savedScroll = view.listBody.scrollTop
    view.listBody.style.setProperty("--git-section-transition", "none")
    view.listBody.innerHTML = ""
    val entries = state.entries
    val arr = entries as? Array<dynamic>
    if (arr == null || arr.isEmpty()) {
        if (arr != null) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "git-list-empty"
            empty.textContent = "No uncommitted changes"
            view.listBody.appendChild(empty)
        }
        view.listBody.scrollTop = savedScroll
        val el2 = view.listBody
        kotlinx.browser.window.requestAnimationFrame { el2.style.removeProperty("--git-section-transition") }
        return
    }

    val groups = LinkedHashMap<String, MutableList<dynamic>>()
    for (entry in arr) {
        val dir = entry.directory as String
        groups.getOrPut(dir) { mutableListOf() }.add(entry)
    }

    for ((parent, items) in groups) {
        val sectionKey = "$paneId|$parent"
        val isCollapsed = sectionKey in collapsedGitSections

        val section = document.createElement("div") as HTMLElement
        section.className = "git-list-section"

        val headline = document.createElement("div") as HTMLElement
        headline.className = "git-list-section-header"
        if (isCollapsed) headline.classList.add("collapsed-tree")

        val chevron = document.createElement("span") as HTMLElement
        chevron.className = "git-list-section-chevron"
        chevron.textContent = "▾"
        headline.appendChild(chevron)

        val titleSpan = document.createElement("span") as HTMLElement
        titleSpan.className = "git-list-section-title"
        titleSpan.textContent = if (parent.isEmpty()) "(root)" else parent
        titleSpan.title = titleSpan.textContent ?: ""
        headline.appendChild(titleSpan)
        section.appendChild(headline)

        val childrenEl = document.createElement("div") as HTMLElement
        childrenEl.className = "git-list-section-children"
        if (isCollapsed) childrenEl.classList.add("collapsed-tree")

        headline.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
        headline.addEventListener("click", { _ ->
            if (sectionKey in collapsedGitSections) {
                collapsedGitSections.remove(sectionKey)
                headline.classList.remove("collapsed-tree")
                childrenEl.classList.remove("collapsed-tree")
                childrenEl.style.maxHeight = "${childrenEl.scrollHeight}px"
            } else {
                collapsedGitSections.add(sectionKey)
                headline.classList.add("collapsed-tree")
                childrenEl.classList.add("collapsed-tree")
            }
        })

        for (entry in items) {
            val filePath = entry.filePath as String
            val status = entry.status as String
            val basename = filePath.substringAfterLast('/')
            val statusKey = when (status) {
                "Added" -> "A"; "Modified" -> "M"; "Deleted" -> "D"
                "Renamed" -> "R"; "Untracked" -> "U"; else -> "?"
            }
            val statusSvg = when (status) {
                "Modified" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M11.013 1.427a1.75 1.75 0 0 1 2.474 0l1.086 1.086a1.75 1.75 0 0 1 0 2.474l-8.61 8.61c-.21.21-.47.364-.756.445l-3.251.93a.75.75 0 0 1-.927-.928l.929-3.25c.081-.286.235-.547.445-.758l8.61-8.61ZM11.524 2.2l-8.61 8.61a.25.25 0 0 0-.064.108l-.59 2.065 2.066-.59a.25.25 0 0 0 .108-.064l8.61-8.61a.25.25 0 0 0 0-.354l-1.086-1.086a.25.25 0 0 0-.434.079Z"/></svg>"""
                "Added" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0Zm.75 4.75a.75.75 0 0 0-1.5 0v2.5h-2.5a.75.75 0 0 0 0 1.5h2.5v2.5a.75.75 0 0 0 1.5 0v-2.5h2.5a.75.75 0 0 0 0-1.5h-2.5Z"/></svg>"""
                "Deleted" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0ZM4.5 7.25a.75.75 0 0 0 0 1.5h7a.75.75 0 0 0 0-1.5Z"/></svg>"""
                "Renamed" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M1 8a.75.75 0 0 1 .75-.75h10.69L9.22 4.03a.75.75 0 0 1 1.06-1.06l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 1 1-1.06-1.06l3.22-3.22H1.75A.75.75 0 0 1 1 8Z"/></svg>"""
                "Untracked" -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M8 0a8 8 0 1 1 0 16A8 8 0 0 1 8 0Zm.75 4.75a.75.75 0 0 0-1.5 0v2.5h-2.5a.75.75 0 0 0 0 1.5h2.5v2.5a.75.75 0 0 0 1.5 0v-2.5h2.5a.75.75 0 0 0 0-1.5h-2.5Z"/></svg>"""
                else -> """<svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><circle cx="8" cy="8" r="6"/></svg>"""
            }
            val item = document.createElement("div") as HTMLElement
            item.className = "git-list-item"
            if (filePath == state.selectedFilePath) item.classList.add("active")

            val badge = document.createElement("span") as HTMLElement
            badge.className = "git-status-badge git-status-$statusKey"
            badge.innerHTML = statusSvg
            val name = document.createElement("span") as HTMLElement
            name.className = "git-list-item-name"
            name.textContent = basename
            name.title = filePath
            item.appendChild(badge)
            item.appendChild(name)

            item.addEventListener("mousedown", { ev -> ev.stopPropagation(); (ev.currentTarget as? HTMLElement)?.asDynamic()?.closest(".terminal-cell")?.let { cell -> markPaneFocused(cell as HTMLElement) } })
            item.addEventListener("click", { _ -> launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = filePath)) })
            childrenEl.appendChild(item)
        }
        section.appendChild(childrenEl)
        if (!isCollapsed) childrenEl.style.maxHeight = "9999px"
        view.listBody.appendChild(section)
    }
    view.listBody.scrollTop = savedScroll
    val el = view.listBody
    kotlinx.browser.window.requestAnimationFrame { el.style.removeProperty("--git-section-transition") }
}
