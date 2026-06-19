/**
 * "News & Updates" modal for the Termtastic desktop (Electron) renderer.
 *
 * Opened from the top-bar bell action (`tt-topbar-news`, built in
 * `TermtasticToolkitBootstrap`; the bell is always visible but rendered muted
 * when there is nothing new — see [refreshNewsTopbarIcon] in [NewsLabel]),
 * mirroring the mobile "News & Updates" view. It lists two sections:
 *
 *  - **New update** — shown only when an update is available: the new version's
 *    text, a Download button that opens the manifest's download URL in the user's
 *    default browser, and a close (×) button that animates the box away and
 *    dismisses the update ("seen, don't remind me") so it stays hidden until a
 *    newer version ships.
 *  - **News** — every unconfirmed news item as a card. Each card shows the
 *    item's title and plain-text body, an optional "Learn more" button (when the
 *    item has a [NewsItem.url]) that opens the link in the browser, and a close
 *    (×) button that animates the card away and dismisses the item so it never
 *    reappears.
 *
 * When nothing remains (no update and no news) the body shows a "You're all
 * caught up" placeholder rather than closing — the modal closes only via the
 * close button, backdrop, or Escape. A pinned footer carries a **Restore** button
 * that brings every dismissed news item and update back.
 *
 * Reuses the same modal overlay pattern as [showAboutDialog] (`.pane-modal-overlay`
 * + `.pane-modal`, dismiss on close button / backdrop / Escape).
 *
 * @see startNewsUpdatesChecker
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.dismissNews
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.dismissUpdate
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel.restoreAll
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.termtastic.client.news.NewsItem
import se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel

/**
 * Open the combined "News & Updates" modal for the given [state].
 *
 * Only one dialog can be visible at a time (guards against duplicates via
 * element ID). Dismissed by the close button, the overlay backdrop, or Escape.
 * Renders an optional "New update" section followed by a "News" section, an
 * empty-state placeholder when neither has content, and a Restore footer.
 *
 * @param viewModel the shared view-model; [NewsUpdatesBackingViewModel.dismissNews]
 *   / [NewsUpdatesBackingViewModel.dismissUpdate] are called when a card is
 *   dismissed (so the bell refreshes and the item stays gone after a reload), and
 *   [NewsUpdatesBackingViewModel.restoreAll] when Restore is tapped.
 * @param state the latest news/update state — supplies the update section's
 *   version text + download URL and the undismissed news items (newest first).
 */
fun showNewsDialog(viewModel: NewsUpdatesBackingViewModel, state: NewsUpdatesBackingViewModel.State) {
    if (document.getElementById("news-dialog") != null) return

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "news-dialog"
    overlay.className = "pane-modal-overlay"

    val card = document.createElement("div") as HTMLElement
    card.className = "pane-modal news-dialog"
    card.addEventListener("click", { ev: Event -> ev.stopPropagation() })

    // Close button.
    val closeBtn = document.createElement("button") as HTMLElement
    closeBtn.className = "pane-modal-close"
    (closeBtn.asDynamic()).type = "button"
    closeBtn.innerHTML = "&times;"
    closeBtn.addEventListener("click", { _: Event -> overlay.remove() })
    card.appendChild(closeBtn)

    // Title.
    val title = document.createElement("h2") as HTMLElement
    title.className = "pane-modal-title"
    title.textContent = "News & Updates"
    card.appendChild(title)

    // Scrollable body holding both sections; the title, close button, and footer
    // stay pinned while this scrolls.
    val list = document.createElement("div") as HTMLElement
    list.className = "news-list"

    // ── New update section ── kept in its own container so the header and card
    // can be removed together once the update is dismissed.
    val updateUrl = state.infoUrl
    if (state.updateAvailable && updateUrl != null) {
        val updateSection = document.createElement("div") as HTMLElement
        updateSection.className = "news-update-section"
        updateSection.appendChild(sectionHeader("New update"))
        updateSection.appendChild(buildUpdateCard(viewModel, list, updateSection, state.latestVersionName, updateUrl))
        list.appendChild(updateSection)
    }

    // ── News section ── kept in its own container so the header and cards can
    // be removed together once the last card is dismissed.
    if (state.newsItems.isNotEmpty()) {
        val newsSection = document.createElement("div") as HTMLElement
        newsSection.className = "news-section"
        newsSection.appendChild(sectionHeader("News"))
        for (item in state.newsItems) {
            newsSection.appendChild(buildNewsCard(viewModel, list, newsSection, item))
        }
        list.appendChild(newsSection)
    }

    // Append the list before refreshing the empty state so the latter can reach
    // the title (a sibling of the list) to toggle it.
    card.appendChild(list)
    // Show the placeholder when there is nothing to read.
    refreshEmptyState(list)

    // Pinned footer with the Restore action.
    val footer = document.createElement("div") as HTMLElement
    footer.className = "news-dialog-footer"
    val restoreBtn = document.createElement("button") as HTMLElement
    restoreBtn.className = "news-restore-btn"
    (restoreBtn.asDynamic()).type = "button"
    restoreBtn.textContent = "Restore dismissed news & updates"
    restoreBtn.addEventListener("click", { _: Event ->
        // restoreAll() re-applies the cached manifests synchronously, so reopening
        // the dialog from the fresh state shows the restored items at once.
        viewModel.restoreAll()
        overlay.remove()
        showNewsDialog(viewModel, viewModel.stateFlow.value)
    })
    footer.appendChild(restoreBtn)
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

/**
 * Build a section heading ("New update" / "News") for the dialog body.
 *
 * Sized between the modal title and a card title so the two sections read as
 * distinct headlines, matching the mobile screen.
 *
 * @param text the heading label.
 * @return the assembled heading element.
 */
private fun sectionHeader(text: String): HTMLElement {
    val el = document.createElement("h3") as HTMLElement
    el.className = "news-section-header"
    el.textContent = text
    return el
}

/**
 * Ensure the "You're all caught up" placeholder is present iff the body holds no
 * update card and no news card, and toggle the "News & Updates" title to match:
 * hidden while there is content (the section headers already label it), shown
 * only alongside the empty-state placeholder. Called after the dialog is built
 * and after every dismissal so the modal can stay open showing an empty state
 * (the bell is always clickable, so reaching an empty screen is expected).
 *
 * @param list the scrollable body element holding the sections; its parent is
 *   the dialog card, queried to reach the title sibling.
 */
private fun refreshEmptyState(list: HTMLElement) {
    val hasCards = list.querySelector(".news-update-card") != null ||
        list.querySelector(".news-card") != null

    // Hide the "News & Updates" title while there is content to read — the
    // section headers ("New update" / "News") already label the cards, so the
    // modal title is redundant there. It returns only for the empty state, where
    // it gives the placeholder some context. The title is a sibling of the list.
    val title = list.parentElement?.querySelector(".pane-modal-title") as? HTMLElement
    title?.style?.display = if (hasCards) "none" else ""

    val existing = list.querySelector(".news-empty")
    if (hasCards) {
        existing?.remove()
    } else if (existing == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "news-empty"
        empty.textContent = "You're all caught up"
        list.appendChild(empty)
    }
}

/**
 * Build the "New update" card: the available-version text, a Download button that
 * opens the manifest's download URL in the user's default browser, and a close
 * (×) button that animates the box away and dismisses the update.
 *
 * @param viewModel the shared view-model, for [NewsUpdatesBackingViewModel.dismissUpdate].
 * @param list the scrollable body, queried to refresh the empty state once gone.
 * @param section the update section container (header + card), removed on dismiss.
 * @param versionName the newer version's display name, or `null` to fall back to
 *   a generic "A new version is available." line.
 * @param url the download URL opened when Download is clicked.
 * @return the assembled update card element.
 */
private fun buildUpdateCard(
    viewModel: NewsUpdatesBackingViewModel,
    list: HTMLElement,
    section: HTMLElement,
    versionName: String?,
    url: String,
): HTMLElement {
    val cardEl = document.createElement("div") as HTMLElement
    cardEl.className = "news-update-card"

    // Close button → animate + dismiss the update.
    val cardClose = document.createElement("button") as HTMLElement
    cardClose.className = "news-update-close"
    (cardClose.asDynamic()).type = "button"
    cardClose.innerHTML = "&times;"
    cardClose.addEventListener("click", { _: Event ->
        dismissUpdateCard(viewModel, list, section, cardEl)
    })
    cardEl.appendChild(cardClose)

    val text = document.createElement("div") as HTMLElement
    text.className = "news-update-text"
    text.textContent = versionName?.let { "Version $it is available." } ?: "A new version is available."
    cardEl.appendChild(text)

    val downloadBtn = document.createElement("button") as HTMLElement
    downloadBtn.className = "news-update-download"
    (downloadBtn.asDynamic()).type = "button"
    downloadBtn.textContent = "Download"
    downloadBtn.addEventListener("click", { _: Event -> openExternalUrl(url) })
    cardEl.appendChild(downloadBtn)

    return cardEl
}

/**
 * Build one news card: date, title, plain-text body, and a close (×) button
 * that animates the card away and dismisses the item.
 *
 * @param viewModel the shared view-model, for [NewsUpdatesBackingViewModel.dismissNews].
 * @param list the scrollable body, queried to refresh the empty state once gone.
 * @param section the news section container, queried to detect when no cards remain.
 * @param item the news item to render.
 * @return the assembled card element.
 */
private fun buildNewsCard(
    viewModel: NewsUpdatesBackingViewModel,
    list: HTMLElement,
    section: HTMLElement,
    item: NewsItem,
): HTMLElement {
    val cardEl = document.createElement("div") as HTMLElement
    cardEl.className = "news-card"
    cardEl.setAttribute("data-news-id", item.id)

    // Per-card close button → animate + confirm.
    val cardClose = document.createElement("button") as HTMLElement
    cardClose.className = "news-card-close"
    (cardClose.asDynamic()).type = "button"
    cardClose.innerHTML = "&times;"
    cardClose.addEventListener("click", { _: Event ->
        dismissCard(viewModel, list, section, cardEl, item.id)
    })
    cardEl.appendChild(cardClose)

    // Undated (evergreen) items omit the date line entirely.
    val date = item.date
    if (date != null) {
        val dateEl = document.createElement("span") as HTMLElement
        dateEl.className = "news-card-date"
        dateEl.textContent = date
        cardEl.appendChild(dateEl)
    }

    val titleEl = document.createElement("h3") as HTMLElement
    titleEl.className = "news-card-title"
    titleEl.textContent = item.title
    cardEl.appendChild(titleEl)

    // Body: plain text, shown verbatim. `textContent` keeps any markup literal
    // and is XSS-safe — the news manifest is third-party content fetched from
    // GitHub, so it must never be injected as HTML.
    val bodyEl = document.createElement("div") as HTMLElement
    bodyEl.className = "news-card-body"
    bodyEl.textContent = item.body
    cardEl.appendChild(bodyEl)

    // Optional link: a button that opens the item's URL in the default browser
    // (via the Electron bridge) instead of navigating the renderer window.
    val url = item.url
    if (!url.isNullOrBlank()) {
        val linkBtn = document.createElement("button") as HTMLElement
        linkBtn.className = "news-card-link"
        (linkBtn.asDynamic()).type = "button"
        linkBtn.textContent = "Learn more"
        linkBtn.addEventListener("click", { _: Event -> openExternalUrl(url) })
        cardEl.appendChild(linkBtn)
    }

    return cardEl
}

/**
 * Animate a news card away, then confirm the item and tidy up.
 *
 * Adds `.news-card-dismissing` (which triggers the CSS collapse transition); on
 * `transitionend` it removes the card, calls [NewsUpdatesBackingViewModel.dismissNews]
 * (which persists the id and refreshes the bell via the StateFlow), and — once no
 * news cards remain — removes the whole News section and refreshes the empty
 * state. The modal stays open so the Restore footer is always reachable. Guards
 * so the work runs exactly once even though several properties transition.
 *
 * @param viewModel the shared view-model, for [NewsUpdatesBackingViewModel.dismissNews].
 * @param list the scrollable body, queried to refresh the empty state.
 * @param section the news section container, removed when its last card goes.
 * @param cardEl the card element being dismissed.
 * @param id the [NewsItem.id] to dismiss.
 */
private fun dismissCard(
    viewModel: NewsUpdatesBackingViewModel,
    list: HTMLElement,
    section: HTMLElement,
    cardEl: HTMLElement,
    id: String,
) {
    if (cardEl.classList.contains("news-card-dismissing")) return
    cardEl.classList.add("news-card-dismissing")
    var done = false
    var handler: ((Event) -> Unit)? = null
    handler = handler@{ ev: Event ->
        if (ev.target !== cardEl || done) return@handler
        done = true
        handler?.let { cardEl.removeEventListener("transitionend", it) }
        cardEl.remove()
        viewModel.dismissNews(id)
        if (section.querySelector(".news-card") == null) section.remove()
        refreshEmptyState(list)
    }
    cardEl.addEventListener("transitionend", handler)
}

/**
 * Animate the update box away, then dismiss the update and tidy up.
 *
 * Mirrors [dismissCard]: adds `.news-card-dismissing` to run the CSS collapse,
 * and on `transitionend` removes the update card, calls
 * [NewsUpdatesBackingViewModel.dismissUpdate] (which persists the dismissed
 * version and refreshes the bell), removes the update section, and refreshes the
 * empty state. The modal stays open. Guards so the work runs exactly once.
 *
 * @param viewModel the shared view-model, for [NewsUpdatesBackingViewModel.dismissUpdate].
 * @param list the scrollable body, queried to refresh the empty state.
 * @param section the update section container (header + card), removed on dismiss.
 * @param cardEl the update card element being dismissed.
 */
private fun dismissUpdateCard(
    viewModel: NewsUpdatesBackingViewModel,
    list: HTMLElement,
    section: HTMLElement,
    cardEl: HTMLElement,
) {
    if (cardEl.classList.contains("news-card-dismissing")) return
    cardEl.classList.add("news-card-dismissing")
    var done = false
    var handler: ((Event) -> Unit)? = null
    handler = handler@{ ev: Event ->
        if (ev.target !== cardEl || done) return@handler
        done = true
        handler?.let { cardEl.removeEventListener("transitionend", it) }
        cardEl.remove()
        viewModel.dismissUpdate()
        section.remove()
        refreshEmptyState(list)
    }
    cardEl.addEventListener("transitionend", handler)
}
