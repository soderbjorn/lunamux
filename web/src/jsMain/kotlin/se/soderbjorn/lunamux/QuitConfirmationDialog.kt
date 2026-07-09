/**
 * Quit-confirmation dialog for the Lunamux Electron host.
 *
 * Shown by the Electron main process before any quit intent (Cmd-Q,
 * menu Quit, window close button) actually quits the app. The user
 * confirms or cancels the quit, and can opt in to also stopping the
 * background server — needed only when installing a new Lunamux
 * version, since terminal sessions are intentionally long-lived and
 * survive normal restarts.
 *
 * The modal reuses the toolkit's `.dt-modal*` classes for consistent
 * appearance with other Lunamux dialogs (close-pane confirmation,
 * etc.) and adds a single checkbox row above the buttons.
 *
 * @see showQuitConfirmationDialog
 * @see se.soderbjorn.darkness.web.showConfirmDialog
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLLabelElement
import org.w3c.dom.events.KeyboardEvent

/**
 * Result of the quit-confirmation dialog.
 *
 * @property confirmed  true if the user clicked Quit; false if they
 *                      cancelled (button, Escape, or backdrop)
 * @property killServer true if the user also ticked the "stop the
 *                      background server" checkbox. Only meaningful
 *                      when [confirmed] is true.
 */
data class QuitConfirmationResult(val confirmed: Boolean, val killServer: Boolean)

/**
 * Open the modal quit-confirmation dialog and resolve [onResult] when
 * the user makes a choice.
 *
 * Only one dialog can be visible at a time (guards against duplicates
 * via element ID). The dialog can be cancelled with the Cancel button,
 * Escape key, or backdrop click; in all three cases [onResult] is
 * invoked with `confirmed = false`.
 *
 * Called by the renderer's IPC subscriber for the
 * `show-quit-confirmation` channel; the resolved choice is shipped
 * back to the Electron main process via
 * `electronApi.respondQuitConfirmation`.
 *
 * @param onResult callback invoked exactly once with the user's choice
 *
 * @see QuitConfirmationResult
 */
fun showQuitConfirmationDialog(onResult: (QuitConfirmationResult) -> Unit) {
    if (document.getElementById("quit-confirmation-dialog") != null) return

    val backdrop = document.createElement("div") as HTMLElement
    backdrop.id = "quit-confirmation-dialog"
    backdrop.className = "dt-modal-backdrop"

    val card = document.createElement("div") as HTMLElement
    card.className = "dt-modal"

    val title = document.createElement("h2") as HTMLElement
    title.className = "dt-modal-title"
    title.textContent = "Quit Lunamux?"
    card.appendChild(title)

    val message = document.createElement("p") as HTMLElement
    message.className = "dt-modal-message"
    message.textContent = "Your terminal sessions will continue running in the " +
        "background and will be available the next time you open Lunamux."
    card.appendChild(message)

    // The "also stop the background server" opt-in is only meaningful when a
    // real Lunamux server backs the client. In demo mode the whole client
    // runs in-process with no server to stop, so the checkbox is omitted and
    // [killServer] stays false.
    val killCheckbox: HTMLInputElement? = if (isDemoClient) {
        null
    } else {
        val killRow = document.createElement("div") as HTMLDivElement
        // Inline-styled here rather than added to the shared toolkit CSS
        // because this is the only place a checkbox-in-modal is used.
        killRow.style.display = "flex"
        killRow.style.alignItems = "flex-start"
        killRow.style.asDynamic().gap = "8px"
        killRow.style.margin = "0 0 16px 0"
        killRow.style.fontSize = "13px"
        killRow.style.lineHeight = "1.45"

        val checkbox = document.createElement("input") as HTMLInputElement
        checkbox.type = "checkbox"
        checkbox.id = "quit-confirmation-kill-server"
        checkbox.style.marginTop = "3px"

        val killLabel = document.createElement("label") as HTMLLabelElement
        killLabel.htmlFor = "quit-confirmation-kill-server"
        killLabel.style.cursor = "pointer"
        val labelStrong = document.createElement("strong") as HTMLElement
        labelStrong.textContent = "Also stop the background server"
        killLabel.appendChild(labelStrong)
        val labelHint = document.createElement("div") as HTMLElement
        labelHint.style.opacity = "0.75"
        labelHint.style.marginTop = "2px"
        labelHint.textContent = "Only check this if you want to install a new version " +
            "of Lunamux. All running terminal sessions will be terminated."
        killLabel.appendChild(labelHint)

        killRow.appendChild(checkbox)
        killRow.appendChild(killLabel)
        card.appendChild(killRow)
        checkbox
    }

    val buttons = document.createElement("div") as HTMLElement
    buttons.className = "dt-modal-buttons"

    val cancelBtn = document.createElement("button") as HTMLButtonElement
    cancelBtn.type = "button"
    cancelBtn.textContent = "Cancel"
    cancelBtn.className = "dt-modal-btn dt-modal-btn-cancel"

    val quitBtn = document.createElement("button") as HTMLButtonElement
    quitBtn.type = "button"
    quitBtn.textContent = "Quit"
    quitBtn.className = "dt-modal-btn dt-modal-btn-confirm"

    buttons.appendChild(cancelBtn)
    buttons.appendChild(quitBtn)
    card.appendChild(buttons)
    backdrop.appendChild(card)
    document.body?.appendChild(backdrop)
    quitBtn.focus()

    var done = false
    fun close(result: QuitConfirmationResult) {
        if (done) return
        done = true
        backdrop.parentNode?.removeChild(backdrop)
        onResult(result)
    }

    cancelBtn.addEventListener("click", { close(QuitConfirmationResult(confirmed = false, killServer = false)) })
    quitBtn.addEventListener("click", {
        close(QuitConfirmationResult(confirmed = true, killServer = killCheckbox?.checked == true))
    })
    backdrop.addEventListener("click", { ev ->
        if (ev.target === backdrop) close(QuitConfirmationResult(confirmed = false, killServer = false))
    })
    val keyHandler: (org.w3c.dom.events.Event) -> Unit = { ev ->
        val k = ev as KeyboardEvent
        if (k.key == "Escape") close(QuitConfirmationResult(confirmed = false, killServer = false))
        else if (k.key == "Enter" && document.activeElement === quitBtn) {
            close(QuitConfirmationResult(confirmed = true, killServer = killCheckbox?.checked == true))
        }
    }
    document.addEventListener("keydown", keyHandler)
    // The handler is no-op once `done` is set, so leaving it attached is
    // harmless — same approach as the toolkit's showConfirmDialog.
}
