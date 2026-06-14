/**
 * Inline rename handler for tab labels in the Termtastic web frontend.
 *
 * Pane title rename is handled by the toolkit: termtastic supplies
 * `AppShellSpec.paneRename` (which forwards to the lower-level
 * `PaneHeaderSpec.onRename`) and triggers the input swap on demand via
 * `AppShellHandle.beginPaneRename(paneId)`. The kebab "more" overflow
 * menu in `termtasticPaneActions` exposes a "Rename pane" item that
 * calls `beginPaneRename`. The toolkit's hover-arm gesture is *not*
 * enabled for pane headers (`PaneHeaderSpec.armRenameOnHover` defaults
 * `false`); the menu item is the sole entry point.
 *
 * This file now only carries the tab-label variant, which still owns
 * its own DOM swap because the tab strip isn't yet on the toolkit
 * primitive.
 *
 * @see startTabRename
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Starts an inline rename interaction for a tab label.
 *
 * Similar to [startRename] but operates on tab labels in the tab bar. Closes
 * any open tab menus, adds a "renaming" CSS class during editing, and sends
 * [WindowCommand.RenameTab] to the server on commit.
 *
 * @param labelEl the DOM element displaying the current tab label
 * @param tabId the unique tab identifier for the rename command
 * @see renderConfig
 */
fun startTabRename(labelEl: HTMLElement, tabId: String) {
    val current = labelEl.textContent ?: ""
    val parent = labelEl.parentElement ?: return
    parent.classList.add("renaming")
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "dt-tab-label-input"
    input.value = current
    parent.replaceChild(input, labelEl)
    input.focus()
    input.select()

    var settled = false
    fun cancel() {
        if (settled) return
        settled = true
        if (input.parentElement === parent) parent.replaceChild(labelEl, input)
        parent.classList.remove("renaming")
    }
    fun commit() {
        if (settled) return
        settled = true
        val newTitle = input.value.trim()
        if (newTitle.isEmpty() || newTitle == current) {
            if (input.parentElement === parent) parent.replaceChild(labelEl, input)
            parent.classList.remove("renaming")
            return
        }
        parent.classList.remove("renaming")
        launchCmd(WindowCommand.RenameTab(tabId = tabId, title = newTitle))
    }

    input.addEventListener("blur", { commit() })
    input.addEventListener("keydown", { ev ->
        val key = (ev as KeyboardEvent).key
        when (key) {
            "Enter" -> { ev.preventDefault(); commit() }
            "Escape" -> { ev.preventDefault(); cancel() }
        }
    })
    input.addEventListener("click", { ev -> ev.stopPropagation() })
    input.addEventListener("dblclick", { ev -> ev.stopPropagation() })
}
