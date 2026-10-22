/**
 * Web-browser pane UI component for the Lunamux web frontend.
 *
 * Renders an embedded, interactive web page. In the **Electron** host this is a
 * live `<webview>` guest with a chrome bar (back / forward / reload-stop, an
 * editable address input, and a loading spinner). Everywhere else (the plain
 * web client and the mobile apps) a webview can't be embedded, so the pane
 * shows an **"Open in browser"** button that hands the URL to the OS default
 * browser instead.
 *
 * The live cell is cached per pane in the [webBrowserPaneViews] registry.
 * [mountPaneContent] returns the cached root on re-render so the page is not
 * rebuilt (and thus not reloaded) on every structural render, and the 3D world
 * reuses the very same DOM when it promotes a ring pane on engage (Phase 7).
 *
 * Navigation is persisted back to the server with [WindowCommand.WebBrowserSetUrl]
 * so the pane restores at its last URL after a restart. Because a URL/title
 * change is not part of the structural config signature, the resulting config
 * broadcast takes the toolkit's label-only fast path and never reparents (and
 * so never reloads) the webview — see [renderConfig].
 *
 * @see WebBrowserContent
 * @see WebBrowserPaneView
 * @see buildWebBrowserView
 * @see buildWebBrowserLinkButton
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * `true` when the renderer is running inside the Lunamux Electron host, where a
 * `<webview>` guest can embed a live page. Detected by sniffing the user-agent
 * for `"Electron"`, mirroring the gate in [FileBrowserPane]. Used by
 * [mountPaneContent] to choose between [buildWebBrowserView] (live page) and
 * [buildWebBrowserLinkButton] (link-only fallback).
 */
internal val isElectronWebHost: Boolean by lazy {
    window.navigator.userAgent.contains("Electron", ignoreCase = true)
}

/**
 * The local blank start page shown when a web pane opens with no URL (see the
 * "New Web Browser" menu item, which dispatches `url = null`). Kept local — not
 * a remote homepage — so an offline app still opens the pane cleanly and lands
 * focus in the address bar (decision D3 in the implementation plan).
 */
private const val WEB_START_PAGE = "about:blank"

/**
 * Live DOM handles for one Electron web pane, cached in [webBrowserPaneViews].
 *
 * Held so that (a) [mountPaneContent] can return the same root across
 * re-renders instead of rebuilding the `<webview>` (which would reload the
 * page), and (b) the 3D world can reparent the exact same cell onto the front
 * plane when the pane is engaged (Phase 7 promote-on-engage).
 *
 * @property root the pane's outermost element (chrome bar + webview column).
 * @property webview the `<webview>` guest element (typed `dynamic` — its
 *   navigation API, `loadURL`/`goBack`/`reload`/…, is Electron-only and not in
 *   the Kotlin/JS DOM externs).
 * @property addressInput the editable address bar, kept so guest navigation
 *   events can write the committed URL back into it.
 * @see buildWebBrowserView
 */
internal class WebBrowserPaneView(
    val root: HTMLElement,
    val webview: dynamic,
    val addressInput: HTMLInputElement,
) {
    /** Last URL the guest committed, mirrored to avoid redundant server pushes. */
    var lastCommittedUrl: String? = null
}

/**
 * Normalise raw address-bar text into a loadable URL. A value that already
 * carries a scheme is used verbatim; a bare host/path that looks like a domain
 * (contains a dot and no spaces) is prefixed with `https://`; anything else is
 * treated as a web search.
 *
 * @param raw the trimmed text the user typed into the address bar.
 * @return an absolute URL suitable for `webview.loadURL`.
 */
internal fun normalizeWebAddress(raw: String): String {
    val v = raw.trim()
    if (v.isEmpty()) return WEB_START_PAGE
    val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(v) ||
        v.startsWith("about:")
    if (hasScheme) return v
    val looksLikeHost = !v.contains(' ') && v.contains('.')
    if (looksLikeHost) return "https://$v"
    val encoded = js("encodeURIComponent")(v) as String
    return "https://www.google.com/search?q=$encoded"
}

/**
 * Build (or return the cached) live web-browser view for [paneId] in the
 * Electron host. Mirrors [buildFileBrowserView]/[buildGitView] but embeds a
 * `<webview>` guest instead of DOM-rendered content, and self-registers in
 * [webBrowserPaneViews] so the same cell can be reused across re-renders and
 * reparented into the 3D world on engage.
 *
 * Called by [mountPaneContent] for the `"webBrowser"` content kind when the
 * host is Electron.
 *
 * @param paneId stable pane identifier matching the toolkit snapshot.
 * @param leaf the pane's dynamic leaf; `leaf.content.url` seeds the initial page.
 * @return the pane's root element, ready to append into the toolkit's
 *   `.dt-pane-content` (or to reparent onto a 3D plane).
 * @see webBrowserPaneViews
 * @see normalizeWebAddress
 */
fun buildWebBrowserView(paneId: String, leaf: dynamic): HTMLElement {
    // Reuse the cached live cell if we have one: rebuilding the <webview>
    // reloads the page, so a structural re-render (tab switch, sidebar toggle)
    // must hand back the exact element that is already showing.
    webBrowserPaneViews[paneId]?.let { return it.root }

    val initialUrl = (leaf?.content?.url as? String)?.takeIf { it.isNotBlank() }

    val root = document.createElement("div") as HTMLElement
    root.className = "web-pane"
    root.style.display = "flex"
    root.style.flexDirection = "column"
    root.style.height = "100%"
    root.style.minHeight = "0"
    // `--dt-window-bg`: the window colour under Lunula's Tone setting, unset otherwise.
    root.style.background = "var(--dt-window-bg, var(--t-surface, #14181f))"

    // ── Chrome bar ────────────────────────────────────────────────────
    val chrome = document.createElement("div") as HTMLElement
    chrome.className = "web-pane-chrome"
    chrome.style.display = "flex"
    chrome.style.alignItems = "center"
    chrome.style.asDynamic().gap = "6px"
    chrome.style.padding = "5px 7px"
    chrome.style.borderBottom = "1px solid var(--t-border, #26374e)"
    chrome.style.flex = "0 0 auto"

    fun navButton(glyph: String, title: String): HTMLElement {
        val b = document.createElement("button") as HTMLElement
        b.className = "web-pane-navbtn"
        b.setAttribute("type", "button")
        b.setAttribute("title", title)
        b.textContent = glyph
        b.style.flex = "0 0 auto"
        b.style.width = "26px"
        b.style.height = "26px"
        b.style.border = "none"
        b.style.borderRadius = "5px"
        b.style.background = "transparent"
        b.style.color = "var(--t-text-secondary, #8ea3c2)"
        b.style.cursor = "pointer"
        b.style.fontSize = "14px"
        b.style.lineHeight = "1"
        return b
    }

    val backBtn = navButton("←", "Back")
    val fwdBtn = navButton("→", "Forward")
    val reloadBtn = navButton("↻", "Reload")

    val addressInput = document.createElement("input") as HTMLInputElement
    addressInput.className = "web-pane-address"
    addressInput.type = "text"
    addressInput.placeholder = "Enter a URL or search…"
    addressInput.value = initialUrl ?: ""
    addressInput.spellcheck = false
    addressInput.style.flex = "1 1 auto"
    addressInput.style.minWidth = "0"
    addressInput.style.height = "26px"
    addressInput.style.padding = "0 9px"
    addressInput.style.border = "1px solid var(--t-border, #26374e)"
    addressInput.style.borderRadius = "6px"
    addressInput.style.background = "var(--t-surface-raised, #0f151d)"
    addressInput.style.color = "var(--t-text-primary, #d7e2f4)"
    addressInput.style.fontSize = "12px"
    addressInput.style.asDynamic().fontFamily = "ui-monospace, SFMono-Regular, Menlo, monospace"

    val spinner = document.createElement("div") as HTMLElement
    spinner.className = "web-pane-spinner"
    spinner.style.flex = "0 0 auto"
    spinner.style.width = "14px"
    spinner.style.height = "14px"
    spinner.style.marginLeft = "2px"
    spinner.style.visibility = "hidden"
    spinner.style.border = "2px solid var(--t-border, #26374e)"
    spinner.style.borderTopColor = "var(--t-accent, #4f8cf7)"
    spinner.style.borderRadius = "50%"
    spinner.style.asDynamic().animation = "web-pane-spin 0.7s linear infinite"

    chrome.appendChild(backBtn)
    chrome.appendChild(fwdBtn)
    chrome.appendChild(reloadBtn)
    chrome.appendChild(addressInput)
    chrome.appendChild(spinner)
    root.appendChild(chrome)

    // Inject the spinner keyframes once per document.
    if (document.getElementById("web-pane-spin-kf") == null) {
        val styleEl = document.createElement("style") as HTMLElement
        styleEl.id = "web-pane-spin-kf"
        styleEl.textContent =
            "@keyframes web-pane-spin{to{transform:rotate(360deg)}}"
        document.head?.appendChild(styleEl)
    }

    // ── Guest <webview> ───────────────────────────────────────────────
    val webview = document.createElement("webview")
    webview.asDynamic().style.flex = "1 1 auto"
    webview.asDynamic().style.width = "100%"
    webview.asDynamic().style.minHeight = "0"
    webview.asDynamic().style.border = "0"
    // Shared persistent session (decision D1): logins carry across web panes
    // and survive restart, like a normal browser profile.
    webview.setAttribute("partition", "persist:webpane")
    // Allow popups so OAuth/SSO "Sign in with…" windows can open; the main
    // process's guest-scoped window-open handler (Phase 6) decides their fate.
    webview.setAttribute("allowpopups", "")
    webview.setAttribute("src", initialUrl ?: WEB_START_PAGE)
    root.appendChild(webview)

    val view = WebBrowserPaneView(root, webview, addressInput)
    view.lastCommittedUrl = initialUrl
    webBrowserPaneViews[paneId] = view

    // ── Wiring ────────────────────────────────────────────────────────
    fun navigateTo(raw: String) {
        val url = normalizeWebAddress(raw)
        try { webview.asDynamic().loadURL(url) } catch (_: Throwable) {}
    }

    addressInput.addEventListener("keydown", { ev ->
        val key = ev.asDynamic().key as? String
        if (key == "Enter") {
            ev.preventDefault()
            navigateTo(addressInput.value)
            try { webview.asDynamic().focus() } catch (_: Throwable) {}
        }
    })

    backBtn.addEventListener("click", {
        try { if (webview.asDynamic().canGoBack() as Boolean) webview.asDynamic().goBack() } catch (_: Throwable) {}
    })
    fwdBtn.addEventListener("click", {
        try { if (webview.asDynamic().canGoForward() as Boolean) webview.asDynamic().goForward() } catch (_: Throwable) {}
    })
    reloadBtn.addEventListener("click", {
        try { webview.asDynamic().reload() } catch (_: Throwable) {}
    })

    // Persist committed navigation + keep the address bar in sync. Guarded so
    // an unchanged URL doesn't spam the server on in-page fragment updates.
    fun onNavigated(e: dynamic) {
        val url = e?.url as? String ?: return
        addressInput.value = url
        if (url != view.lastCommittedUrl && url != "about:blank") {
            view.lastCommittedUrl = url
            val title = try { webview.asDynamic().getTitle() as? String } catch (_: Throwable) { null }
            launchCmd(WindowCommand.WebBrowserSetUrl(paneId = paneId, url = url, title = title))
        }
    }
    webview.addEventListener("did-navigate", { e -> onNavigated(e.asDynamic()) })
    webview.addEventListener("did-navigate-in-page", { e ->
        // Only same-document navigations that change the top-level URL matter.
        val d = e.asDynamic()
        if (d.isMainFrame == null || d.isMainFrame as Boolean) onNavigated(d)
    })
    webview.addEventListener("page-title-updated", { e ->
        val title = e.asDynamic().title as? String ?: return@addEventListener
        val url = view.lastCommittedUrl ?: return@addEventListener
        launchCmd(WindowCommand.WebBrowserSetUrl(paneId = paneId, url = url, title = title))
    })
    webview.addEventListener("did-start-loading", { spinner.style.visibility = "visible" })
    webview.addEventListener("did-stop-loading", { spinner.style.visibility = "hidden" })
    webview.addEventListener("did-fail-load", { e ->
        // -3 == ERR_ABORTED, fired for ordinary navigations the user cancels
        // or in-page anchor jumps; not a real failure, so ignore it.
        val d = e.asDynamic()
        val code = (d.errorCode as? Number)?.toInt() ?: 0
        val mainFrame = d.isMainFrame == null || d.isMainFrame as Boolean
        if (code != -3 && mainFrame) {
            spinner.style.visibility = "hidden"
        }
    })

    if (initialUrl == null) {
        // Blank start page: land the caret in the address bar so the user can
        // type a URL straight away.
        window.requestAnimationFrame { addressInput.focus() }
    }

    return root
}

/**
 * Build the link-button fallback shown for a web pane outside Electron (the
 * plain web client and the mobile apps, which can't embed a live page). Clicking
 * the button opens the pane's URL in the OS default browser via [openExternalUrl].
 *
 * Called by [mountPaneContent] for the `"webBrowser"` content kind when the host
 * is not Electron.
 *
 * @param paneId stable pane identifier (used only for labelling/diagnostics).
 * @param url the pane's stored URL, or `null` when it has never navigated.
 * @return a centred card with an "Open in browser" button.
 * @see isElectronWebHost
 */
fun buildWebBrowserLinkButton(paneId: String, url: String?): HTMLElement {
    val wrap = document.createElement("div") as HTMLElement
    wrap.className = "web-pane-linkfallback"
    wrap.style.display = "flex"
    wrap.style.flexDirection = "column"
    wrap.style.alignItems = "center"
    wrap.style.justifyContent = "center"
    wrap.style.asDynamic().gap = "12px"
    wrap.style.height = "100%"
    wrap.style.padding = "20px"
    wrap.style.textAlign = "center"

    val caption = document.createElement("div") as HTMLElement
    caption.style.color = "var(--t-text-secondary, #8ea3c2)"
    caption.style.fontSize = "13px"
    caption.textContent = if (url.isNullOrBlank()) {
        "This pane opens a web page. Live browsing is available in the desktop app."
    } else {
        url
    }

    val btn = document.createElement("button") as HTMLElement
    btn.className = "web-pane-openbtn"
    btn.setAttribute("type", "button")
    btn.textContent = "Open in browser"
    btn.style.padding = "8px 16px"
    btn.style.border = "1px solid var(--t-accent, #4f8cf7)"
    btn.style.borderRadius = "7px"
    btn.style.background = "var(--t-accent, #4f8cf7)"
    btn.style.color = "#fff"
    btn.style.fontSize = "13px"
    btn.style.cursor = "pointer"
    val target = url?.takeIf { it.isNotBlank() }
    btn.style.opacity = if (target == null) "0.5" else "1"
    if (target == null) btn.setAttribute("disabled", "")
    btn.addEventListener("click", {
        target?.let { openExternalUrl(it) }
    })

    wrap.appendChild(caption)
    wrap.appendChild(btn)
    return wrap
}
