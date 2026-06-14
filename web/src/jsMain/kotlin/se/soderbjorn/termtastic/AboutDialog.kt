/**
 * About dialog for the Termtastic web frontend.
 *
 * Displays application information including copyright, the desktop app
 * version (when hosted in Electron), links to the project website and the
 * GitHub repository, and a list of third-party dependencies. The dialog
 * reuses the same modal overlay pattern as [showConfirmDialog] and
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
 * Opens a modal "About" dialog showing application info and third-party
 * dependencies.
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

    // Copyright.
    val copyright = document.createElement("p") as HTMLElement
    copyright.className = "about-copyright"
    copyright.textContent = COPYRIGHT
    card.appendChild(copyright)

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
        card.appendChild(versionP)
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
    card.appendChild(linkRow)

    // Third-party dependencies.
    val depsTitle = document.createElement("h3") as HTMLElement
    depsTitle.className = "about-section-title"
    depsTitle.textContent = "Third-Party Libraries"
    card.appendChild(depsTitle)

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
    card.appendChild(depsList)

    // Bundled fonts.
    val fontsTitle = document.createElement("h3") as HTMLElement
    fontsTitle.className = "about-section-title"
    fontsTitle.textContent = "Bundled Fonts"
    card.appendChild(fontsTitle)

    // Short blurb above the list: fonts are shipped under the SIL Open Font
    // License 1.1, so users know why the app ships with its own type without
    // phoning home.
    val fontsBlurb = document.createElement("p") as HTMLElement
    fontsBlurb.className = "about-fonts-blurb"
    fontsBlurb.textContent = "Monospace families shipped with the client, " +
        "rendered in the terminal, git diff, and markdown windows. Each font is " +
        "used under its upstream license."
    card.appendChild(fontsBlurb)

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
    card.appendChild(fontsList)

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
    card.appendChild(footer)

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
