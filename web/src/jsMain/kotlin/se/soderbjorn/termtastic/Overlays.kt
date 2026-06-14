/**
 * Overlay and modal UI components for the Termtastic web frontend.
 *
 * Provides full-screen overlay dialogs for authentication state, connection
 * errors, and device approval. Also contains authentication token management
 * and HTML utility functions.
 *
 * @see showPendingApprovalOverlay
 * @see showDisconnectedModal
 * @see showDeviceRejectedOverlay
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.client.LocalStorageAuthTokenStore
import se.soderbjorn.termtastic.client.getOrCreateToken

/**
 * Ensures a persistent authentication token exists in both localStorage and
 * as a cookie. Creates a new token if none is stored.
 *
 * The token is used for authenticating WebSocket connections and REST API calls
 * to the Termtastic server. Logs a warning if the browser silently rejects
 * the cookie write (e.g. due to third-party cookie policies).
 *
 * Called once during [start] initialization.
 *
 * @see authTokenForSending
 */
fun ensureAuthToken() {
    val store = LocalStorageAuthTokenStore()
    val hadStored = !store.load().isNullOrEmpty()
    val token = getOrCreateToken(store)
    document.cookie = "termtastic_auth=$token; Path=/; SameSite=Strict; Max-Age=31536000"

    val tokenPrefix = token.take(6)
    val cookieNow = document.cookie
    val cookieHasToken = cookieNow.contains("termtastic_auth=")
    console.log(
        "[termtastic auth] hadStored=$hadStored tokenPrefix=$tokenPrefix " +
            "cookieVisible=$cookieHasToken cookieString=$cookieNow"
    )
    if (!cookieHasToken) {
        console.warn(
            "[termtastic auth] Cookie write silently rejected by the browser. " +
                "The server will see every connection as an unknown device and " +
                "prompt for approval forever. Check devtools > Application > " +
                "Cookies to see if anything was stored at all."
        )
    }
}

/**
 * Shows a full-screen overlay indicating that the client is waiting for
 * server-side device approval. Includes a spinning indicator.
 *
 * Displayed when a [WindowEnvelope.PendingApproval] message is received.
 * Guards against duplicate overlays.
 *
 * @see hidePendingApprovalOverlay
 */
fun showPendingApprovalOverlay() {
    if (document.getElementById("pending-approval-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "pending-approval-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);
                    text-align:center;">
          <h2 style="margin:0 0 12px 0;font-size:18px;">Waiting for server approval</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            Look for the approval dialog on the host machine.
          </p>
          <div style="margin:0 auto;width:24px;height:24px;border:3px solid #555;
                      border-top-color:#0a84ff;border-radius:50%;animation:spin 1s linear infinite;">
          </div>
          <style>@keyframes spin { to { transform: rotate(360deg); } }</style>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
}

/**
 * Removes the pending approval overlay if it is currently shown.
 *
 * @see showPendingApprovalOverlay
 */
fun hidePendingApprovalOverlay() {
    document.getElementById("pending-approval-overlay")?.remove()
}

/**
 * Shows a full-screen overlay indicating that the WebSocket connection to the
 * server has been lost. Includes a "Retry" button that reloads the page.
 *
 * Called by [updateAggregateStatus] when any PTY connection enters the "disconnected" state.
 *
 * @see hideDisconnectedModal
 * @see updateAggregateStatus
 */
fun showDisconnectedModal() {
    if (document.getElementById("disconnected-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "disconnected-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:100000;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);
                    text-align:center;">
          <h2 style="margin:0 0 12px 0;font-size:18px;">Connection lost</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            The connection to the server was lost. Check that the server is
            running and try again.
          </p>
          <button id="disconnected-retry"
                  style="padding:8px 18px;background:#0a84ff;color:#fff;border:none;
                         border-radius:6px;cursor:pointer;font-size:14px;">
            Retry
          </button>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
    document.getElementById("disconnected-retry")?.addEventListener("click", {
        window.location.reload()
    })
}

/**
 * Removes the disconnected modal overlay if it is currently shown.
 *
 * @see showDisconnectedModal
 */
fun hideDisconnectedModal() {
    document.getElementById("disconnected-overlay")?.remove()
}

/**
 * Shows a full-screen overlay when the server rejects this device's authentication.
 *
 * Displays different messages depending on whether the server is running in headless
 * mode (cannot show approval dialog) or the device was explicitly rejected.
 * The "Try again" button clears the auth token and reloads.
 *
 * Called by [connectPane] when a WebSocket is closed with code 1008 (Policy Violation).
 *
 * @param closeCode the WebSocket close code
 * @param closeReason the WebSocket close reason string
 */
fun showDeviceRejectedOverlay(closeCode: Int, closeReason: String) {
    if (document.getElementById("device-rejected-overlay") != null) return
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "device-rejected-overlay"
    overlay.setAttribute(
        "style",
        "position:fixed;inset:0;z-index:100000;background:rgba(0,0,0,0.85);" +
            "display:flex;align-items:center;justify-content:center;" +
            "font-family:-apple-system,system-ui,sans-serif;color:#eee;"
    )
    val headline = if (closeReason.contains("headless", ignoreCase = true))
        "Server can't show the approval dialog"
    else
        "This device isn't approved"
    val body = if (closeReason.contains("headless", ignoreCase = true))
        "The Termtastic server is running in headless mode, so it can't pop " +
            "up the approval prompt on the host desktop. Approve this device " +
            "out-of-band (or run the server with a display attached) and try again."
    else
        "The Termtastic server rejected this browser. Ask the user at the " +
            "host machine to approve this device, then try again."
    overlay.innerHTML = """
        <div style="max-width:460px;padding:28px 32px;background:#1e1e1e;
                    border:1px solid #444;border-radius:10px;box-shadow:0 8px 32px rgba(0,0,0,0.6);">
          <h2 style="margin:0 0 12px 0;font-size:18px;">${escapeHtmlForOverlay(headline)}</h2>
          <p style="margin:0 0 18px 0;font-size:14px;line-height:1.5;color:#c8c8c8;">
            ${escapeHtmlForOverlay(body)}
          </p>
          <div style="font-size:12px;color:#888;margin-bottom:18px;">
            WebSocket close $closeCode · ${escapeHtmlForOverlay(closeReason)}
          </div>
          <button id="device-rejected-retry"
                  style="padding:8px 18px;background:#0a84ff;color:#fff;border:none;
                         border-radius:6px;cursor:pointer;font-size:14px;">
            Try again
          </button>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
    val btn = document.getElementById("device-rejected-retry") as? HTMLElement
    btn?.addEventListener("click", {
        window.localStorage.removeItem("termtastic.authToken")
        document.cookie = "termtastic_auth=; Path=/; Max-Age=0"
        window.location.reload()
    })
}

/**
 * Escapes HTML special characters for safe insertion into overlay innerHTML.
 *
 * @param s the raw string to escape
 * @return the HTML-safe string with &, <, >, and " escaped
 */
fun escapeHtmlForOverlay(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

/**
 * Retrieves the authentication token from localStorage for use in WebSocket
 * URLs and REST API headers.
 *
 * @return the stored auth token, or empty string if none exists
 */
fun authTokenForSending(): String =
    window.localStorage.getItem("termtastic.authToken") ?: ""

/**
 * Delegates to JavaScript's `encodeURIComponent` for URL-encoding a string value.
 *
 * @param value the string to encode
 * @return the URL-encoded string
 */
fun encodeUriComponent(value: String): String =
    js("encodeURIComponent(value)") as String
