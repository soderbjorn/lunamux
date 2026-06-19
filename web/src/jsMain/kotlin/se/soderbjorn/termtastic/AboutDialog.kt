/**
 * About dialog for the Termtastic web frontend.
 *
 * The dialog is split into two tabs:
 * - **About** (main): copyright, the desktop app version (when hosted in
 *   Electron), the machine's LAN address so the user knows which host to add
 *   from the Android and iOS clients, and links to the project website and the
 *   GitHub repository.
 * - **Licensing**: third-party libraries, bundled fonts, and a pointer to the
 *   NOTICE file with the full license texts.
 *
 * The dialog reuses the same modal overlay pattern as [showConfirmDialog] and
 * [openTerminalLinkPicker].
 *
 * @see showAboutDialog
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Copyright notice displayed in the about dialog. */
private const val COPYRIGHT = "Copyright \u00A9 2026 Robert S\u00F6derbjorn"

/** Project website URL. */
private const val WEBSITE_URL = "https://termtastic.soderbjorn.se/"

/** GitHub repository URL. */
private const val GITHUB_URL = "https://github.com/soderbjorn/termtastic"

/** Link to the NOTICE file on GitHub (main branch), used for the "full license texts" pointer. */
private const val NOTICE_URL = "https://github.com/soderbjorn/termtastic/blob/main/NOTICE"

/**
 * Third-party libraries used by Termtastic across all platforms
 * (Android, iOS, Web/Electron, server). Versions intentionally omitted —
 * they drift and are not useful to end users. Full attribution per
 * library (license, copyright, source URL) lives in the `NOTICE` file.
 */
private val DEPENDENCIES = listOf(
    "Kotlin",
    "kotlinx.coroutines",
    "kotlinx.serialization",
    "Ktor",
    "Jetpack Compose / Compose Multiplatform",
    "AndroidX",
    "SwiftTerm",
    "xterm.js",
    "xterm-addon-fit",
    "Electron",
    "Chromium",
    "pty4j",
    "JediTerm",
    "SQLDelight",
    "Flexmark",
    "Jsoup",
    "Logback",
    "terminal-emulator / terminal-view",
)

/**
 * Bundled font credit shown in the about dialog.
 *
 * @property name the font family display name
 * @property holder short attribution line (copyright holder / designers)
 * @property license the license short name (e.g. "OFL 1.1")
 */
private data class FontCredit(val name: String, val holder: String, val license: String)

/**
 * Monospace font families bundled with the web/Electron client under
 * `/fonts/`. Mirrored in `NOTICE` → "Web / Electron" and in
 * `web/src/jsMain/resources/fonts/<family>-LICENSE.txt`.
 */
private val BUNDLED_FONTS = listOf(
    FontCredit("JetBrains Mono", "JetBrains", "OFL 1.1"),
    FontCredit("Fira Code", "Nikita Prokopov", "OFL 1.1"),
    FontCredit("Cascadia Code", "Microsoft", "OFL 1.1"),
    FontCredit("IBM Plex Mono", "IBM Corp.", "OFL 1.1"),
    FontCredit("Geist Mono", "Vercel × basement.studio", "OFL 1.1"),
    FontCredit("Source Code Pro", "Adobe", "OFL 1.1"),
)

/**
 * Builds a single external link anchor for the About dialog, used for the
 * project website and GitHub repository links.
 *
 * Called by [showAboutDialog]. The anchor opens in a new tab/window and uses
 * `rel="noopener noreferrer"` for safety.
 *
 * @param url the URL to link to; also used verbatim as the visible link text.
 * @return the anchor element ready to append to a link row.
 */
private fun buildLink(url: String): HTMLAnchorElement {
    val link = document.createElement("a") as HTMLAnchorElement
    link.className = "about-link"
    link.href = url
    link.target = "_blank"
    link.rel = "noopener noreferrer"
    link.textContent = url
    return link
}

/**
 * Builds the "About" (main) tab panel: copyright, desktop app version, the
 * machine's LAN address, and the website/GitHub links.
 *
 * Called by [showAboutDialog] when assembling the tabbed dialog body.
 *
 * The LAN address row is populated asynchronously: it is only meaningful in the
 * Electron desktop build (where the main process can enumerate the host's
 * network interfaces over IPC), so a placeholder is appended immediately and
 * filled in once [populateLanAddress] resolves. The web build, which has no
 * such bridge, omits the row entirely.
 *
 * @return the panel element ready to append to the dialog body.
 * @see populateLanAddress
 */
private fun buildMainTab(): HTMLElement {
    val panel = document.createElement("div") as HTMLElement
    panel.className = "about-tab-panel"

    // Copyright.
    val copyright = document.createElement("p") as HTMLElement
    copyright.className = "about-copyright"
    copyright.textContent = COPYRIGHT
    panel.appendChild(copyright)

    // Desktop app version (Electron only). The web build has no native
    // version, so the row is omitted unless the Electron preload supplied one.
    val electronApi = window.asDynamic().electronApi
    val versionName = (electronApi?.appVersionName as? String) ?: ""
    val versionCode = (electronApi?.appVersionCode as? String) ?: ""
    if (versionName.isNotEmpty()) {
        val versionP = document.createElement("p") as HTMLElement
        versionP.className = "about-version"
        versionP.textContent = if (versionCode.isNotEmpty()) {
            "Version $versionName ($versionCode)"
        } else {
            "Version $versionName"
        }
        panel.appendChild(versionP)
    }

    // LAN address — the host other devices add in the Android/iOS clients.
    // Only the Electron build can resolve it, so the section is added (with a
    // loading placeholder) only when the preload exposes the bridge.
    if (electronApi != null && electronApi.getLocalIpAddresses != null) {
        val section = document.createElement("div") as HTMLElement
        section.className = "about-ip-section"

        val label = document.createElement("p") as HTMLElement
        label.className = "about-ip-label"
        label.textContent = "Add this Mac from the Android or iOS app:"
        section.appendChild(label)

        // Connection details are laid out as a labeled grid (Hostname / IP
        // address / Port), each value on its own row so the user can read the
        // host and port as distinct fields. Populated asynchronously, so it
        // starts as a single placeholder line.
        val grid = document.createElement("div") as HTMLElement
        grid.className = "about-ip-grid"
        val placeholder = document.createElement("p") as HTMLElement
        placeholder.className = "about-ip-value about-ip-empty"
        placeholder.textContent = "Looking up address…"
        grid.appendChild(placeholder)
        section.appendChild(grid)

        panel.appendChild(section)
        populateLanAddress(electronApi, grid)
    }

    // Website + GitHub links, side by side on one row separated by a dot.
    val linkRow = document.createElement("p") as HTMLElement
    linkRow.className = "about-link-row"
    linkRow.appendChild(buildLink(WEBSITE_URL))
    val sep = document.createElement("span") as HTMLElement
    sep.className = "about-link-sep"
    sep.textContent = " · "
    linkRow.appendChild(sep)
    linkRow.appendChild(buildLink(GITHUB_URL))
    panel.appendChild(linkRow)

    return panel
}

/**
 * Resolves the host's hostname, LAN IPv4 address(es), and port via the Electron
 * preload bridge and renders them as a labeled grid into [grid].
 *
 * Called by [buildMainTab] after appending a loading placeholder. The bridge
 * returns `{ addresses: string[], hostname: string, port: number }`. The
 * result is laid out as `Hostname` / `IP address` / `Port` rows so the user
 * sees that the Mac can be reached by either its name or any LAN IP, and on
 * which port — each a distinct field they type into a mobile client's host
 * list. When the machine has no LAN interface (or the lookup fails) a short
 * explanatory line is shown instead.
 *
 * @param electronApi the `window.electronApi` bridge object (must expose
 *   `getLocalIpAddresses`).
 * @param grid the container element whose contents are replaced with the
 *   resolved connection rows (or an explanatory message).
 */
private fun populateLanAddress(electronApi: dynamic, grid: HTMLElement) {
    /** Replaces the grid with a single full-width explanatory line. */
    fun showMessage(message: String) {
        grid.innerHTML = ""
        val line = document.createElement("p") as HTMLElement
        line.className = "about-ip-value about-ip-empty"
        line.textContent = message
        grid.appendChild(line)
    }

    /** Appends one `label → value` row to the grid (value rendered monospace). */
    fun addRow(label: String, value: String) {
        val labelSpan = document.createElement("span") as HTMLElement
        labelSpan.className = "about-ip-grid-label"
        labelSpan.textContent = label
        val valueSpan = document.createElement("span") as HTMLElement
        valueSpan.className = "about-ip-grid-value"
        valueSpan.textContent = value
        grid.appendChild(labelSpan)
        grid.appendChild(valueSpan)
    }

    try {
        val promise = electronApi.getLocalIpAddresses()
        promise.then({ result: dynamic ->
            val port = (result?.port as? Int) ?: 0
            val hostname = (result?.hostname as? String)?.takeIf { it.isNotEmpty() }
            val rawAddresses = result?.addresses
            val addresses = mutableListOf<String>()
            if (rawAddresses != null) {
                val len = (rawAddresses.length as? Int) ?: 0
                for (i in 0 until len) {
                    val addr = rawAddresses[i] as? String ?: continue
                    addresses.add(addr)
                }
            }
            if (addresses.isEmpty()) {
                showMessage("No LAN connection — connect this Mac to a network first.")
            } else {
                grid.innerHTML = ""
                // Hostname is only present in newer bridge builds; skip the row
                // gracefully when absent so older shells still render IP + port.
                if (hostname != null) addRow("Hostname", hostname)
                // Stack multiple IPs under a single label rather than repeating
                // it; the value cell honours newlines via white-space: pre-line.
                addRow("IP address", addresses.joinToString("\n"))
                if (port > 0) addRow("Port", port.toString())
            }
            Unit
        }, { _: dynamic ->
            showMessage("Could not determine the LAN address.")
            Unit
        })
    } catch (_: Throwable) {
        showMessage("Could not determine the LAN address.")
    }
}

/**
 * Builds the "Licensing" tab panel: third-party libraries, bundled fonts, and
 * the pointer to the NOTICE file.
 *
 * Called by [showAboutDialog] when assembling the tabbed dialog body.
 *
 * @return the panel element ready to append to the dialog body.
 */
private fun buildLicensingTab(): HTMLElement {
    val panel = document.createElement("div") as HTMLElement
    panel.className = "about-tab-panel"

    // Third-party dependencies.
    val depsTitle = document.createElement("h3") as HTMLElement
    depsTitle.className = "about-section-title"
    depsTitle.textContent = "Third-Party Libraries"
    panel.appendChild(depsTitle)

    val depsList = document.createElement("ul") as HTMLElement
    depsList.className = "about-deps"
    for (dep in DEPENDENCIES) {
        val li = document.createElement("li") as HTMLElement
        val nameSpan = document.createElement("span") as HTMLElement
        nameSpan.className = "about-dep-name"
        nameSpan.textContent = dep
        li.appendChild(nameSpan)
        depsList.appendChild(li)
    }
    panel.appendChild(depsList)

    // Bundled fonts.
    val fontsTitle = document.createElement("h3") as HTMLElement
    fontsTitle.className = "about-section-title"
    fontsTitle.textContent = "Bundled Fonts"
    panel.appendChild(fontsTitle)

    // Short blurb above the list: fonts are shipped under the SIL Open Font
    // License 1.1, so users know why the app ships with its own type without
    // phoning home.
    val fontsBlurb = document.createElement("p") as HTMLElement
    fontsBlurb.className = "about-fonts-blurb"
    fontsBlurb.textContent = "Monospace families shipped with the client, " +
        "rendered in the terminal, git diff, and markdown windows. Each font is " +
        "used under its upstream license."
    panel.appendChild(fontsBlurb)

    val fontsList = document.createElement("ul") as HTMLElement
    // Reuse the about-deps list styling — same "name on the left, tag on
    // the right" shape works for both dependencies and font credits.
    fontsList.className = "about-deps about-fonts"
    for (font in BUNDLED_FONTS) {
        val li = document.createElement("li") as HTMLElement
        val nameSpan = document.createElement("span") as HTMLElement
        nameSpan.className = "about-dep-name"
        // Render each credit line in its own typeface so users can eyeball
        // the actual shapes without having to flip through Settings.
        nameSpan.style.fontFamily = "'${font.name}', ui-monospace, monospace"
        nameSpan.textContent = "${font.name} — ${font.holder}"
        val licenseSpan = document.createElement("span") as HTMLElement
        licenseSpan.className = "about-dep-version"
        licenseSpan.textContent = font.license
        li.appendChild(nameSpan)
        li.appendChild(licenseSpan)
        fontsList.appendChild(li)
    }
    panel.appendChild(fontsList)

    // Footer pointer to the NOTICE file on GitHub — one canonical place where
    // every library and font has its full license text.
    val footer = document.createElement("p") as HTMLElement
    footer.className = "about-footer"
    footer.appendChild(document.createTextNode("Full license texts are in the "))
    val noticeLink = document.createElement("a") as HTMLAnchorElement
    noticeLink.className = "about-link"
    noticeLink.href = NOTICE_URL
    noticeLink.target = "_blank"
    noticeLink.rel = "noopener noreferrer"
    noticeLink.textContent = "NOTICE"
    footer.appendChild(noticeLink)
    footer.appendChild(document.createTextNode(" file."))
    panel.appendChild(footer)

    return panel
}

/**
 * Opens a modal "About" dialog showing application info and third-party
 * dependencies.
 *
 * The body is split into two tabs — "About" (copyright, version, LAN address,
 * links) and "Licensing" (libraries, fonts, NOTICE) — built by [buildMainTab]
 * and [buildLicensingTab]. The "About" tab is shown first.
 *
 * Only one dialog can be visible at a time (guards against duplicates via
 * element ID). The dialog can be dismissed by clicking the close button,
 * clicking the overlay backdrop, or pressing Escape.
 *
 * Called by the about button click handler in [main].
 */
fun showAboutDialog() {
    if (document.getElementById("about-dialog") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "about-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal about-dialog"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    // Close button.
    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    (closeBtn.asDynamic()).type = "button"
    closeBtn.innerHTML = "&times;"
    closeBtn.addEventListener("click", { _: Event -> overlay.remove() })
    card.appendChild(closeBtn)

    // App name.
    val title = document.createElement("h2") as HTMLElement
    title.className = "pane-modal-title"
    title.textContent = "Termtastic"
    card.appendChild(title)

    // Build both tab panels up front; switching just toggles visibility so the
    // LAN-address lookup on the main tab keeps its resolved value when the user
    // flips back and forth.
    val mainPanel = buildMainTab()
    val licensingPanel = buildLicensingTab()
    licensingPanel.style.display = "none"

    // Tab bar. Each button toggles which panel is visible and carries the
    // `active` class for styling.
    val tabBar = document.createElement("div") as HTMLElement
    tabBar.className = "about-tabs"

    val mainTabBtn = document.createElement("button") as HTMLElement
    mainTabBtn.className = "about-tab active"
    (mainTabBtn.asDynamic()).type = "button"
    mainTabBtn.textContent = "About"

    val licensingTabBtn = document.createElement("button") as HTMLElement
    licensingTabBtn.className = "about-tab"
    (licensingTabBtn.asDynamic()).type = "button"
    licensingTabBtn.textContent = "Licensing"

    val selectMain = {
        mainTabBtn.classList.add("active")
        licensingTabBtn.classList.remove("active")
        mainPanel.style.display = ""
        licensingPanel.style.display = "none"
    }
    val selectLicensing = {
        licensingTabBtn.classList.add("active")
        mainTabBtn.classList.remove("active")
        licensingPanel.style.display = ""
        mainPanel.style.display = "none"
    }
    mainTabBtn.addEventListener("click", { _: Event -> selectMain() })
    licensingTabBtn.addEventListener("click", { _: Event -> selectLicensing() })

    tabBar.appendChild(mainTabBtn)
    tabBar.appendChild(licensingTabBtn)
    card.appendChild(tabBar)

    card.appendChild(mainPanel)
    card.appendChild(licensingPanel)

    overlay.appendChild(card)

    // Dismiss on backdrop click.
    overlay.addEventListener("click", { ev: Event ->
        if (ev.target === overlay) overlay.remove()
    })

    // Dismiss on Escape.
    var escHandler: ((Event) -> Unit)? = null
    escHandler = { ev: Event ->
        if ((ev as KeyboardEvent).key == "Escape") {
            overlay.remove()
            escHandler?.let { document.removeEventListener("keydown", it) }
        }
    }
    document.addEventListener("keydown", escHandler)

    document.body?.appendChild(overlay)
}
