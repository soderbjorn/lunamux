/**
 * In-diff text search and match navigation for a git pane.
 *
 * [performDiffSearch] wraps every case-insensitive match in a
 * `<mark class="git-search-hit">` span and scrolls the current match
 * into view; [navigateDiffSearch] steps between matches; [clearDiffSearch]
 * unwraps marks and restores the original text.
 *
 * @see GitPaneState.searchQuery
 * @see GitPaneState.searchMatchIndex
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Removes all search highlight marks from the diff content, restoring
 * the original text.
 */
internal fun clearDiffSearch(diffContent: HTMLElement) {
    val marks = diffContent.querySelectorAll("mark.git-search-hit")
    for (i in 0 until marks.length) {
        val mark = marks.item(i) as HTMLElement
        val parent = mark.parentNode ?: continue
        val text = document.createTextNode(mark.textContent ?: "")
        parent.replaceChild(text, mark)
        (parent as HTMLElement).normalize()
    }
}

/**
 * Performs a case-insensitive text search within the diff content,
 * wrapping matches in `<mark>` elements and scrolling the current
 * match into view.
 */
internal fun performDiffSearch(
    diffContent: HTMLElement, query: String, state: GitPaneState,
    counterLabel: HTMLElement, navButtons: List<HTMLElement>,
) {
    clearDiffSearch(diffContent)
    if (query.isEmpty()) {
        state.searchMatchIndex = 0; counterLabel.textContent = ""
        navButtons.forEach { it.asDynamic().disabled = true }; return
    }
    val lowerQuery = query.lowercase()
    val matches = mutableListOf<HTMLElement>()
    val walker = document.asDynamic().createTreeWalker(diffContent, 4, null)
    val textNodes = mutableListOf<org.w3c.dom.Text>()
    while (true) { val node = walker.nextNode() ?: break; textNodes.add(node as org.w3c.dom.Text) }
    for (textNode in textNodes) {
        val text = textNode.textContent ?: continue
        val lowerText = text.lowercase()
        val offsets = mutableListOf<Int>()
        var searchFrom = 0
        while (searchFrom < text.length) {
            val idx = lowerText.indexOf(lowerQuery, searchFrom)
            if (idx < 0) break; offsets.add(idx); searchFrom = idx + 1
        }
        if (offsets.isEmpty()) continue
        val parent = textNode.parentNode ?: continue
        val frag = document.createDocumentFragment()
        var pos = 0
        for (off in offsets) {
            if (off > pos) frag.appendChild(document.createTextNode(text.substring(pos, off)))
            val mark = document.createElement("mark") as HTMLElement
            mark.className = "git-search-hit"
            mark.textContent = text.substring(off, off + query.length)
            frag.appendChild(mark); matches.add(mark); pos = off + query.length
        }
        if (pos < text.length) frag.appendChild(document.createTextNode(text.substring(pos)))
        parent.replaceChild(frag, textNode)
    }
    val total = matches.size
    if (total == 0) {
        state.searchMatchIndex = 0; counterLabel.textContent = "0/0"
        navButtons.forEach { it.asDynamic().disabled = true }; return
    }
    state.searchMatchIndex = state.searchMatchIndex.coerceIn(0, total - 1)
    navButtons.forEach { it.asDynamic().disabled = false }
    matches.getOrNull(state.searchMatchIndex)?.classList?.add("git-search-current")
    matches.getOrNull(state.searchMatchIndex)?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
    counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
}

/**
 * Navigates to the next, previous, first, or last search match within
 * the diff.
 *
 * @param delta 1 for next, -1 for previous, [Int.MIN_VALUE] for first,
 *              [Int.MAX_VALUE] for last
 */
internal fun navigateDiffSearch(diffContent: HTMLElement, state: GitPaneState, counterLabel: HTMLElement, delta: Int) {
    val marks = diffContent.querySelectorAll("mark.git-search-hit")
    val total = marks.length
    if (total == 0) return
    (marks.item(state.searchMatchIndex) as? HTMLElement)?.classList?.remove("git-search-current")
    state.searchMatchIndex = when (delta) {
        Int.MIN_VALUE -> 0
        Int.MAX_VALUE -> total - 1
        else -> {
            var n = state.searchMatchIndex + delta
            if (n < 0) n = total - 1; if (n >= total) n = 0; n
        }
    }
    val current = marks.item(state.searchMatchIndex) as? HTMLElement
    current?.classList?.add("git-search-current")
    current?.scrollIntoView(js("{block:'nearest',behavior:'smooth'}"))
    counterLabel.textContent = "${state.searchMatchIndex + 1}/$total"
}
