/**
 * Dialog component for creating a new git worktree from the current pane's
 * working directory.
 *
 * Displays a form with:
 * - A branch name text input
 * - Three radio options for the worktree path (sibling folder, `.worktrees/`
 *   subfolder, or custom path)
 * - A "Move uncommitted changes" checkbox (shown when the repo is dirty)
 *
 * Default paths are computed server-side and passed via
 * [WindowEnvelope.WorktreeDefaults]. On submission, sends a
 * [WindowCommand.CreateWorktree] to the server.
 *
 * @see ConfirmDialog for the simpler yes/no dialog pattern this is modelled after
 * @see PaneHeader where the worktree button triggers this dialog
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Show the "Create Git Worktree" dialog. Only one instance can be open at a time
 * (guarded by element ID). The dialog is dismissed by Cancel, Escape, or backdrop
 * click. On "Create", validates inputs and sends [WindowCommand.CreateWorktree].
 *
 * @param paneId the pane that initiated the dialog
 * @param repoName the basename of the current git repository
 * @param siblingBase the default sibling path prefix (e.g. `../repo-`)
 * @param dotWorktreesBase the default `.worktrees/` path prefix
 * @param hasUncommittedChanges whether the working tree has uncommitted changes
 */
fun showWorktreeDialog(
    paneId: String,
    repoName: String,
    siblingBase: String,
    dotWorktreesBase: String,
    hasUncommittedChanges: Boolean,
) {
    if (document.getElementById("worktree-dialog") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "worktree-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal worktree-modal"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    // Title
    val titleEl = document.createElement("h2") as HTMLElement
    titleEl.className = "pane-modal-title"
    titleEl.textContent = "Create Git Worktree"
    card.appendChild(titleEl)

    // ---- Branch name field ----
    val branchLabel = document.createElement("label") as HTMLElement
    branchLabel.className = "worktree-label"
    branchLabel.textContent = "Branch name"
    card.appendChild(branchLabel)

    val branchInput = document.createElement("input") as HTMLInputElement
    branchInput.className = "worktree-input"
    branchInput.type = "text"
    branchInput.placeholder = "feature/my-branch"
    branchInput.setAttribute("autocomplete", "off")
    branchInput.setAttribute("spellcheck", "false")
    card.appendChild(branchInput)

    // ---- Path preview ----
    val pathPreview = document.createElement("div") as HTMLElement
    pathPreview.className = "worktree-path-preview"
    card.appendChild(pathPreview)

    // ---- Create button (declared early so validation functions can reference it) ----
    val createBtn = document.createElement("button") as HTMLElement
    createBtn.className = "confirm-btn confirm-ok"
    (createBtn.asDynamic()).type = "button"
    createBtn.textContent = "Create"
    createBtn.setAttribute("disabled", "")

    // ---- Location radios ----
    val locationLabel = document.createElement("label") as HTMLElement
    locationLabel.className = "worktree-label"
    locationLabel.textContent = "Worktree location"
    card.appendChild(locationLabel)

    val radioGroup = document.createElement("div") as HTMLElement
    radioGroup.className = "worktree-radio-group"

    var selectedMode = "sibling"
    val customInput = document.createElement("input") as HTMLInputElement
    customInput.className = "worktree-input worktree-custom-path"
    customInput.type = "text"
    customInput.placeholder = "/absolute/path/to/worktree"
    customInput.disabled = true

    fun computePath(): String {
        val branch = branchInput.value.trim()
        val safeBranch = branch.replace("/", "-").replace("\\", "-")
        return when (selectedMode) {
            "sibling" -> "$siblingBase$safeBranch"
            "dotworktrees" -> "$dotWorktreesBase$safeBranch"
            "custom" -> customInput.value.trim()
            else -> ""
        }
    }

    fun updatePreview() {
        val path = computePath()
        pathPreview.textContent = if (path.isNotBlank()) path else "(enter a branch name)"
    }

    fun updateCreateEnabled() {
        val branchFilled = branchInput.value.trim().isNotEmpty()
        val pathFilled = if (selectedMode == "custom") customInput.value.trim().isNotEmpty() else true
        val enabled = branchFilled && pathFilled
        if (enabled) {
            createBtn.removeAttribute("disabled")
        } else {
            createBtn.setAttribute("disabled", "")
        }
    }

    fun makeRadio(value: String, label: String, description: String): HTMLElement {
        val row = document.createElement("label") as HTMLElement
        row.className = "worktree-radio-row"

        val radio = document.createElement("input") as HTMLInputElement
        radio.type = "radio"
        radio.name = "worktree-location"
        radio.value = value
        radio.checked = (value == selectedMode)
        radio.addEventListener("change", { _: Event ->
            selectedMode = value
            customInput.disabled = (value != "custom")
            // Seed the custom path with the sibling suggestion the first time
            // the user switches to "custom", so they can tweak instead of
            // typing the full path. Don't clobber a value they already typed.
            if (value == "custom" && customInput.value.isEmpty()) {
                val branch = branchInput.value.trim()
                val safeBranch = branch.replace("/", "-").replace("\\", "-")
                customInput.value = "$siblingBase$safeBranch"
            }
            updatePreview()
            updateCreateEnabled()
        })
        row.appendChild(radio)

        val textWrap = document.createElement("span") as HTMLElement
        textWrap.className = "worktree-radio-text"

        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "worktree-radio-label"
        labelSpan.textContent = label
        textWrap.appendChild(labelSpan)

        val descSpan = document.createElement("span") as HTMLElement
        descSpan.className = "worktree-radio-desc"
        descSpan.textContent = description
        textWrap.appendChild(descSpan)

        row.appendChild(textWrap)
        return row
    }

    radioGroup.appendChild(makeRadio("sibling", "Sibling folder", "Next to the current repository"))
    radioGroup.appendChild(makeRadio("dotworktrees", ".worktrees/ subfolder", "Under a shared worktrees directory"))
    radioGroup.appendChild(makeRadio("custom", "Custom path", "Specify an absolute path"))
    radioGroup.appendChild(customInput)
    card.appendChild(radioGroup)

    // ---- Migrate changes checkbox ----
    val migrateCheckbox: HTMLInputElement?
    if (hasUncommittedChanges) {
        val checkRow = document.createElement("label") as HTMLElement
        checkRow.className = "worktree-checkbox-row"

        val checkbox = document.createElement("input") as HTMLInputElement
        checkbox.type = "checkbox"
        checkbox.checked = true
        checkRow.appendChild(checkbox)

        val checkLabel = document.createElement("span") as HTMLElement
        checkLabel.textContent = "Move uncommitted changes to new worktree"
        checkRow.appendChild(checkLabel)

        card.appendChild(checkRow)
        migrateCheckbox = checkbox
    } else {
        migrateCheckbox = null
    }

    branchInput.addEventListener("input", { _: Event -> updatePreview(); updateCreateEnabled() })
    customInput.addEventListener("input", { _: Event -> updatePreview(); updateCreateEnabled() })
    updatePreview()

    // ---- Error display ----
    val errorEl = document.createElement("div") as HTMLElement
    errorEl.className = "worktree-error"
    errorEl.style.display = "none"
    card.appendChild(errorEl)

    // ---- Buttons ----
    val btnRow = document.createElement("div") as HTMLElement
    btnRow.className = "confirm-buttons"

    val cancelBtn = document.createElement("button") as HTMLElement
    cancelBtn.className = "confirm-btn confirm-cancel"
    (cancelBtn.asDynamic()).type = "button"
    cancelBtn.textContent = "Cancel"
    cancelBtn.addEventListener("click", { _: Event -> overlay.remove() })
    btnRow.appendChild(cancelBtn)

    createBtn.addEventListener("click", { _: Event ->
        val branchName = branchInput.value.trim()
        if (branchName.isEmpty()) {
            errorEl.textContent = "Branch name is required"
            errorEl.style.display = ""
            return@addEventListener
        }
        val worktreePath = computePath()
        if (worktreePath.isEmpty()) {
            errorEl.textContent = "Worktree path is required"
            errorEl.style.display = ""
            return@addEventListener
        }
        val migrate = migrateCheckbox?.checked ?: false
        overlay.remove()
        launchCmd(WindowCommand.CreateWorktree(
            paneId = paneId,
            branchName = branchName,
            worktreePath = worktreePath,
            migrateChanges = migrate,
        ))
    })
    btnRow.appendChild(createBtn)

    card.appendChild(btnRow)
    overlay.appendChild(card)

    // Dismiss on backdrop click or Escape.
    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })
    var escHandler: ((Event) -> Unit)? = null
    escHandler = { ev: Event ->
        if ((ev as KeyboardEvent).key == "Escape") {
            overlay.remove()
            escHandler?.let { document.removeEventListener("keydown", it) }
        }
    }
    document.addEventListener("keydown", escHandler)

    // Enter key submits (when not in custom path input).
    branchInput.addEventListener("keydown", { ev: Event ->
        if ((ev as KeyboardEvent).key == "Enter") {
            ev.preventDefault()
            createBtn.asDynamic().click()
        }
    })

    document.body?.appendChild(overlay)
    branchInput.focus()
}
