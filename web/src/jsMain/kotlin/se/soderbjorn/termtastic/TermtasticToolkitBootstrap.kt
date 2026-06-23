/* TermtasticToolkitBootstrap.kt (jsMain)
 * Production entry point that mounts termtastic's UI through the
 * darkness-toolkit's `mountAppShell`. Replaces the hand-rolled chrome
 * (custom `TabBar`, `Sidebar`, header DOM in `index.html`, `LayoutMenu`,
 * bespoke pane drag/resize) with toolkit-supplied primitives. The
 * toolkit becomes the source of truth for app frame, top bar, tab strip,
 * sidebar tree, layout presets, pane chrome (header/drag/resize/close),
 * and pane geometry persistence. Termtastic-specific code shrinks to:
 *   - per-pane content (terminal, file browser, git diff) via
 *     [mountPaneContent]
 *   - the `WindowConfig` ↔ [TabSource] bridge ([termtasticTabSource])
 *   - app-specific pane action buttons (worktree, reformat, copy-path)
 *     via [termtasticPaneActions]
 *   - app-specific topbar trailing actions (settings, about, debug)
 *   - the app logo in the left-sidebar header (`sidebarHeader`) and the
 *     Claude usage rows in its footer (`sidebarFooter`); news/updates
 *     live behind the pulsing top-bar bell action
 *
 * Pane geometry is toolkit-owned post-migration: drag/resize/maximize
 * gestures persist as `LAYOUT_STATE` blobs through the
 * [SettingsPersisterAdapter], which round-trips to the server's flat-KV
 * `SettingsRepository`. Cross-client sync continues via the existing
 * `WindowEnvelope.UiSettings` push path. Mobile clients still read
 * `WindowConfig` geometry as a derived view. */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.confirmClosePane
import se.soderbjorn.darkness.web.layout.PaneAction
import se.soderbjorn.darkness.web.layout.PaneActions
import se.soderbjorn.darkness.web.layout.PaneMenuItem
import se.soderbjorn.darkness.web.layout.PaneMenuSpec
import se.soderbjorn.darkness.web.layout.openPaneMenu
import se.soderbjorn.darkness.web.shell.AppShellHandle
import se.soderbjorn.darkness.web.shell.AppShellSpec
import se.soderbjorn.darkness.web.shell.ThemeBootstrap
import se.soderbjorn.darkness.web.shell.TopbarAction
import se.soderbjorn.darkness.web.shell.mountAppShell
import se.soderbjorn.darkness.web.hotkey.ToolkitHotkeysModal
import se.soderbjorn.darkness.web.hotkey.installCheatsheetHotkey

/* -------------------------------------------------------------------- */
/* SVG icon constants for top-bar trailing actions and pane-action      */
/* buttons. Centralised here (instead of inline) so the bootstrap is    */
/* self-contained and the bespoke `main.kt` SUN/MOON/AUTO/etc. can be   */
/* deleted along with the rest of the bespoke chrome wiring.            */
/* -------------------------------------------------------------------- */

/** Info "i in a circle" — opens the About dialog. */
private const val ICON_ABOUT =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>"""

/**
 * Bell — opens the combined "News & Updates" screen. Carries the `tt-news-topbar`
 * marker class so CSS can (a) pulse it in the warning colour and (b) show/hide
 * its toolbar button via `:has()` keyed off `document.body[data-tt-news]` (see
 * `styles.css` and [refreshNewsTopbarIcon]).
 */
private const val ICON_NEWS =
    """<svg class="tt-news-topbar" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>"""

/** Material Symbols "content_copy" — file-browser path-copy action. */
private const val PA_ICON_COPY =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="8" y="8" width="13" height="13" rx="1.5"/><path d="M16 8V4.5A1.5 1.5 0 0 0 14.5 3H4.5A1.5 1.5 0 0 0 3 4.5v10A1.5 1.5 0 0 0 4.5 16H8"/></svg>"""

/** Reformat (terminal action). */
private const val PA_ICON_REFORMAT =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="1.5"/><polyline points="7 10 4 12 7 14"/><polyline points="17 10 20 12 17 14"/></svg>"""

/** Branch-with-plus glyph for the create-worktree action (now a menu item glyph). */
private const val PA_ICON_WORKTREE =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 8v2c0 2.2 1.8 4 4 4h4"/><line x1="6" y1="8" x2="6" y2="16"/><line x1="21" y1="9" x2="21" y2="15"/><line x1="18" y1="12" x2="24" y2="12"/></svg>"""

/** Three vertical dots — overflow / "more" menu trigger on the pane header. */
private const val PA_ICON_MORE =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>"""

/** Stacked rows — git "inline" (unified) diff mode. */
private const val PA_ICON_DIFF_INLINE =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/></svg>"""

/** Two side-by-side panels — git "split" diff mode. */
private const val PA_ICON_DIFF_SPLIT =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="7" height="16" rx="1"/><rect x="14" y="4" width="7" height="16" rx="1"/></svg>"""

/** Two panels joined by a connector — git "split + graphical" (p4merge-style) diff mode. */
private const val PA_ICON_DIFF_GRAPHICAL =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="6" height="16" rx="1"/><rect x="15" y="4" width="6" height="16" rx="1"/><line x1="9" y1="9" x2="15" y2="13"/></svg>"""

/**
 * Live handle returned by `mountAppShell`. Captured during
 * [bootViaToolkitShell] so per-pane action handlers (the kebab menu's
 * "Rename pane" item) can call back into the toolkit-managed pane
 * header — specifically [AppShellHandle.beginPaneRename] — without
 * having to thread the handle through every callback.
 *
 * `null` until the shell mounts; the kebab menu does nothing useful
 * before that.
 */
internal var appShellHandle: AppShellHandle? = null

/* -------------------------------------------------------------------- */
/* Lookup helpers — single source of truth for "what kind of pane is X" */
/* and "what is its title", used by paneIcon / paneLabel / paneActions  */
/* factories. Walk `currentConfig` rather than the AppBackingViewModel  */
/* state (the latter lags the dynamic snapshot by one tick).            */
/* -------------------------------------------------------------------- */

/** Looks up a leaf descriptor in the current server config by pane id. */
private fun findLeafDynamic(paneId: String): dynamic {
    val cfg: dynamic = currentConfig ?: return null
    val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabsArr) {
        val panes = (tab.panes as? Array<dynamic>) ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) return p.leaf
        }
    }
    return null
}

/* -------------------------------------------------------------------- */
/* Git diff-mode toolbar action. The three reachable states (Inline,    */
/* Split, Split+graphical) are a flattened view of GitPaneState's        */
/* `diffMode`/`graphicalDiff` pair; the toolbar action cycles through    */
/* them on each click, mirroring the (now-removed) header flyout.        */
/* -------------------------------------------------------------------- */

/**
 * Picks the toolbar glyph for the current git diff mode.
 *
 * @param mode the [GitPaneState.diffMode] value ("Inline" or "Split").
 * @param graphical whether the p4merge-style graphical overlay is on
 *   (only meaningful when [mode] is "Split").
 * @return one of the `PA_ICON_DIFF_*` SVG strings.
 * @see cycleGitDiffMode
 */
private fun gitDiffModeIcon(mode: String, graphical: Boolean): String = when {
    mode != "Split" -> PA_ICON_DIFF_INLINE
    !graphical -> PA_ICON_DIFF_SPLIT
    else -> PA_ICON_DIFF_GRAPHICAL
}

/**
 * Human-readable label for the current git diff mode, used in the
 * toolbar button tooltip.
 *
 * @param mode the [GitPaneState.diffMode] value.
 * @param graphical whether the graphical overlay is on.
 * @return "Inline", "Split", or "Split + graphical".
 * @see cycleGitDiffMode
 */
private fun gitDiffModeLabel(mode: String, graphical: Boolean): String = when {
    mode != "Split" -> "Inline"
    !graphical -> "Split"
    else -> "Split + graphical"
}

/**
 * Advances a git pane's diff mode one step (Inline → Split →
 * Split+graphical → Inline), persists the change via the same
 * [WindowCommand]s the old flyout used, refreshes the currently shown
 * diff, and swaps the toolbar button's icon/tooltip in place.
 *
 * Called from the git [PaneAction.handlerWithAnchor] in
 * [termtasticPaneActions].
 *
 * @param paneId the git pane whose mode is cycling.
 * @param btn the rendered toolbar button to re-skin after the change.
 * @see gitDiffModeIcon
 */
private fun cycleGitDiffMode(paneId: String, btn: HTMLElement) {
    val st = gitPaneStates.getOrPut(paneId) { GitPaneState() }
    val cur = when {
        st.diffMode != "Split" -> 0
        !st.graphicalDiff -> 1
        else -> 2
    }
    when ((cur + 1) % 3) {
        0 -> {
            st.diffMode = "Inline"
            launchCmd(WindowCommand.SetGitDiffMode(paneId = paneId, mode = GitDiffMode.Inline))
        }
        1 -> {
            st.diffMode = "Split"; st.graphicalDiff = false
            launchCmd(WindowCommand.SetGitDiffMode(paneId = paneId, mode = GitDiffMode.Split))
            launchCmd(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = false))
        }
        else -> {
            st.diffMode = "Split"; st.graphicalDiff = true
            launchCmd(WindowCommand.SetGitDiffMode(paneId = paneId, mode = GitDiffMode.Split))
            launchCmd(WindowCommand.SetGitGraphicalDiff(paneId = paneId, enabled = true))
        }
    }
    st.diffHtml = null
    btn.innerHTML = gitDiffModeIcon(st.diffMode, st.graphicalDiff)
    btn.setAttribute("title", "Diff: ${gitDiffModeLabel(st.diffMode, st.graphicalDiff)} · click to cycle")
    val sel = st.selectedFilePath
    if (sel != null) launchCmd(WindowCommand.GitDiff(paneId = paneId, filePath = sel))
}

/* -------------------------------------------------------------------- */
/* Per-pane action buttons in the chrome header. Carries content-kind   */
/* specific actions (reformat for terminal panes, copy-path for file-   */
/* browser panes, diff-mode for git panes) and a trailing "more"        */
/* overflow icon whose menu hosts the pane-level meta-actions: rename,   */
/* create worktree. Maximize and close are toolkit-owned, not here.     */
/* -------------------------------------------------------------------- */

/**
 * Builds the per-pane action button list for the toolkit's pane header.
 *
 * Order: content-kind actions (reformat / copy-path), then the trailing
 * `⋮` overflow menu (rename pane / create worktree).
 * Maximize/restore and close are toolkit-owned and not included here.
 *
 * @param paneId stable pane identifier — used for [findLeafDynamic] lookup.
 * @return ordered list of [PaneAction]s; an empty list when the pane id
 *   is not in the live config.
 *
 * @see se.soderbjorn.darkness.web.shell.AppShellSpec.paneActions
 * @see AppShellHandle.beginPaneRename
 */
fun termtasticPaneActions(paneId: String): List<PaneAction> {
    val leaf = findLeafDynamic(paneId) ?: return emptyList()
    val sessionId = leaf.sessionId as? String
    val contentKind = (leaf.content?.kind as? String) ?: "terminal"
    val title = (leaf.title as? String) ?: paneId
    val isLink = (leaf.isLink as? Boolean) ?: false

    val actions = mutableListOf<PaneAction>()

    if (contentKind == "terminal" && sessionId != null) {
        actions += PaneAction(
            iconHtml = PA_ICON_REFORMAT,
            tooltip = "Reflow",
            handler = {
                val entry = terminals[paneId] ?: return@PaneAction
                forceReassert(entry)
            },
            extraClass = "tt-pane-action-reformat",
        )
    }

    if (contentKind == "fileBrowser") {
        actions += PaneAction(
            iconHtml = PA_ICON_COPY,
            tooltip = "Copy path",
            handler = {
                val rel = fileBrowserPaneStates[paneId]?.selectedRelPath ?: return@PaneAction
                kotlinx.browser.window.asDynamic().navigator.clipboard.writeText(rel)
            },
            extraClass = "tt-pane-action-copypath",
        )
    }

    if (contentKind == "git") {
        // Read current diff mode from live pane state if present, else fall
        // back to the persisted leaf content (matches buildGitView's seed).
        val st = gitPaneStates[paneId]
        val mode = st?.diffMode ?: (leaf.content?.diffMode as? String) ?: "Inline"
        val graphical = st?.graphicalDiff ?: (leaf.content?.graphicalDiff as? Boolean) ?: false
        actions += PaneAction(
            iconHtml = gitDiffModeIcon(mode, graphical),
            tooltip = "Diff: ${gitDiffModeLabel(mode, graphical)} · click to cycle",
            // handlerWithAnchor gives us the rendered button so we can swap
            // its icon/tooltip in place without a full header rebuild.
            handler = {},
            handlerWithAnchor = { btn -> cycleGitDiffMode(paneId, btn) },
            extraClass = "tt-pane-action-diffmode",
        )
    }

    // Maximize / close are wired by the toolkit-owned chrome via the
    // standard PaneCallbacks supplied by mountAppShell, so they are NOT
    // included here. The pre-toolkit chrome carried them as extra app
    // actions; with mountAppShell they are part of the toolkit's own
    // window-control cluster.

    // Linked-pane close confirmation: toolkit wires the close button to
    // its `onPaneClose` (which routes to TabSource.onPaneClose →
    // WindowCommand.Close). We add a *pre-confirm* override only for
    // terminal panes that share their session with siblings, by
    // shadowing the toolkit's close action with a custom one. The
    // toolkit's own close action is appended after — only the first
    // matching action with the close `extraClass` runs, so this works
    // out as a "wrap" without needing a toolkit hook.
    if (contentKind == "terminal" && sessionId != null && !isLink) {
        val linkedCount = countPanesForSession(sessionId)
        if (linkedCount >= 2) {
            actions += PaneAction(
                iconHtml = """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>""",
                tooltip = "Close",
                handler = {
                    confirmClosePane(
                        paneTitle = title,
                        linkedPaneCount = linkedCount,
                        onConfirm = {
                            launchCmd(WindowCommand.CloseSession(sessionId = sessionId))
                        },
                    )
                },
                extraClass = "tt-pane-action-close-linked",
            )
        }
    }

    // Trailing `⋮` overflow menu: holds pane-level meta-actions
    // (rename, create worktree). Mirrors the tab-bar's overflow menu in
    // visual weight so the affordance is recognisable across the chrome.
    actions += PaneAction(
        iconHtml = PA_ICON_MORE,
        tooltip = "More",
        // `handler` is unused when `handlerWithAnchor` is set; the
        // toolkit's `buildActionButton` prefers the anchor variant when
        // available so the popover sits exactly under the kebab button.
        handler = {},
        handlerWithAnchor = { btn ->
            openPaneMenu(
                anchor = btn,
                spec = PaneMenuSpec(items = listOf(
                    PaneMenuItem(
                        label = "Rename window",
                        handler = { appShellHandle?.beginPaneRename(paneId) },
                    ),
                    PaneMenuItem(
                        label = "Create worktree",
                        iconHtml = PA_ICON_WORKTREE,
                        handler = { launchCmd(WindowCommand.GetWorktreeDefaults(paneId = paneId)) },
                    ),
                )),
            )
        },
        extraClass = "tt-pane-action-more",
    )

    return actions
}

/* -------------------------------------------------------------------- */
/* Top-bar trailing actions — settings, about, debug.                   */
/* -------------------------------------------------------------------- */

/**
 * Builds the "About" trailing topbar action. Click opens the existing
 * about dialog.
 */
private fun buildAboutTopbarAction(): TopbarAction = TopbarAction(
    id = "tt-topbar-about",
    iconHtml = ICON_ABOUT,
    label = "About Termtastic",
    onActivate = { showAboutDialog() },
)

/**
 * Builds the "News & Updates" trailing topbar action — a bell that opens the
 * combined [showNewsDialog] screen. Sits immediately right of the About action.
 *
 * The button stays in the DOM at all times; CSS hides it until there is content
 * to show (an available update or unread news), driven by [refreshNewsTopbarIcon]
 * toggling `document.body[data-tt-news]`. The bell pulses in the warning colour
 * via the `tt-news-topbar` marker class on its SVG. Replaces the former
 * sidebar-footer news/update pills, matching the mobile toolbar bell.
 *
 * @return the bell topbar action; its click opens the screen using the shared
 *   [newsUpdatesViewModel]'s current state (a no-op before the checker starts).
 */
private fun buildNewsTopbarAction(): TopbarAction = TopbarAction(
    id = "tt-topbar-news",
    iconHtml = ICON_NEWS,
    label = "News & updates",
    onActivate = {
        newsUpdatesViewModel?.let { showNewsDialog(it, it.stateFlow.value) }
    },
)

/**
 * Apply a Working / Waiting / Clear pane-state override to the
 * currently-focused terminal pane. Invoked from the macOS Debug menu
 * (Electron) and from `window.__ttDebugSetPaneState` (browser console).
 *
 * @param mode one of `"working"`, `"waiting"`, `"auto"`. Other values
 *   are ignored.
 */
fun applyDebugPaneStateOverride(mode: String) {
    val focusedCell = document.querySelector(".terminal-cell.focused") as? HTMLElement
        ?: document.querySelector(".dt-pane-focused .terminal-cell") as? HTMLElement
    val paneId = focusedCell?.getAttribute("data-pane") ?: return
    val sessionId = terminals[paneId]?.sessionId ?: return
    if (sessionId.isEmpty()) return
    launchCmd(WindowCommand.SetStateOverride(sessionId, mode))
}

/* -------------------------------------------------------------------- */
/* Left-sidebar header / footer slots — the app logo rides at the top   */
/* of the sessions list, and the Claude usage rows sit pinned at the     */
/* bottom. The toolkit bottom bar is disabled                           */
/* (`showBottomBar = false`), so there is no bottom-bar slot to fill.    */
/* -------------------------------------------------------------------- */

/**
 * Cached app-logo element for the sidebar header slot. Built once and
 * re-served on every rerender so the toolkit re-parents the same element
 * (preserving the `#app-logo-dot` that [updateStateIndicators] repaints in
 * place) instead of orphaning a freshly-built one.
 */
private var sidebarLogoEl: HTMLElement? = null

/**
 * Cached sidebar-footer element (Claude usage rows).
 * Cached for the same reason as [sidebarLogoEl]: the persistent [usageBar]
 * (addressed by id from `ClaudeUsageBar`) must survive toolkit rerenders, which
 * it does when the toolkit re-parents one stable element rather than rebuilding
 * it each time.
 */
private var sidebarFooterEl: HTMLElement? = null

/**
 * Builds (once) the app logo — "Termtastic" wordmark + work-state dot — for
 * the toolkit's `sidebarHeader` slot at the top of the sessions list.
 *
 * Invoked by `mountAppShell` on every shell rerender, but returns the cached
 * element after the first build so the `#app-logo-dot` that
 * [updateStateIndicators] mutates in place is never discarded. The dot is
 * painted from the current session-state snapshot at construction time via
 * [applyLogoDotState] (the element isn't attached yet, so a `getElementById`
 * repaint would miss it).
 *
 * @return the persistent logo element for the sidebar header.
 * @see applyLogoDotState
 */
private fun buildSidebarLogo(): HTMLElement {
    sidebarLogoEl?.let { return it }
    val logo = document.createElement("div") as HTMLElement
    logo.id = "app-logo"
    logo.className = "app-logo"
    logo.setAttribute("aria-hidden", "true")
    val row = document.createElement("div") as HTMLElement
    row.className = "app-logo-row"
    val wordmark = document.createElement("span") as HTMLElement
    wordmark.className = "app-logo-wordmark"
    // Lowercase, monospaced wordmark (styled in .app-logo-wordmark).
    wordmark.textContent = "termtastic"
    val dot = document.createElement("span") as HTMLElement
    dot.id = "app-logo-dot"
    dot.className = "app-logo-dot"
    applyLogoDotState(dot, currentSessionStates())
    // Dot first, then wordmark: the status light sits to the LEFT of the
    // "termtastic" text (mirroring the landing-page brand row).
    row.appendChild(dot)
    row.appendChild(wordmark)
    logo.appendChild(row)
    sidebarLogoEl = logo
    return logo
}

/**
 * Builds (once) the sidebar footer for the toolkit's `sidebarFooter` slot: the
 * Claude usage rows. (News and updates now live behind the pulsing top-bar bell
 * — see [buildNewsTopbarAction] — not a footer pill.)
 *
 * The Claude usage bar element keeps its `claude-usage-bar` id and is cached
 * into [usageBar] so subsequent [updateClaudeUsageBadge] writes land here; the
 * last-known `claudeUsage` from [appVm] is repainted immediately so the rows
 * aren't blank until the next `/usage` poll.
 *
 * Returns the cached element after the first build so the persistent usage bar
 * survives toolkit rerenders.
 *
 * @return the persistent sidebar footer element.
 */
private fun buildSidebarFooter(): HTMLElement {
    sidebarFooterEl?.let { return it }
    val footer = document.createElement("div") as HTMLElement
    footer.className = "tt-sidebar-footer"

    val usage = document.createElement("div") as HTMLElement
    usage.id = "claude-usage-bar"
    usage.className = "claude-usage-bar claude-usage-bar-empty"
    usageBar = usage
    val last = appVm.stateFlow.value.claudeUsage
    if (last != null) {
        val json = windowJson.encodeToString(WindowEnvelope.ClaudeUsage(last))
        val dyn = js("JSON.parse(json)")
        updateClaudeUsageBadge(dyn.usage)
    }
    footer.appendChild(usage)

    sidebarFooterEl = footer
    return footer
}

/* -------------------------------------------------------------------- */
/* Per-pane / per-tab session-state dot factories. The toolkit owns the  */
/* chrome (sidebar rows, tab strip, pane headers); termtastic contributes */
/* `.tt-status-dot` `<span>` elements carrying `data-session` /           */
/* `data-tab-state` attributes. The factories paint each fresh element    */
/* from the current `WindowStateRepository` snapshot (the KMP-side runtime */
/* cache held by `TermtasticClient` — see                                 */
/* `client/.../WindowStateRepository.kt`) before returning, so a toolkit   */
/* rerender (theme toggle, sidebar toggle, drag-end rebuild) produces dots */
/* that already carry the live state — no blank frame waiting for the next */
/* `WindowEnvelope.State` push from the server (termtastic#24). Live       */
/* updates after the initial paint continue to flow through               */
/* `updateStateIndicators` in `WebStateActions.kt`, which finds the same   */
/* elements by `data-session` / `data-tab-state` and re-applies            */
/* `applyDotState` in-place. The per-pane header/tab factories return      */
/* `null` for panes whose leaf has no associated terminal session; the     */
/* sidebar factory always returns a dot, but CSS hides any idle dot         */
/* (issue #43), so stateless / idle panes show no indicator in practice.    */
/* -------------------------------------------------------------------- */

/**
 * Returns the latest per-session state snapshot from the KMP-side
 * runtime cache ([se.soderbjorn.termtastic.client.WindowStateRepository]),
 * which is updated whenever a `WindowEnvelope.State` arrives over the
 * window socket. Used by [buildStatusDot] / [buildTabStatusDot]
 * to paint fresh dot elements at construction time, so rerenders of
 * the toolkit chrome don't drop the visible state dot between the
 * rebuild and the next server push (termtastic#24).
 *
 * Safe to call from any factory invoked after [bootViaToolkitShell]:
 * `termtasticClient` is constructed in `main.kt` before `mountAppShell`,
 * so by the time the toolkit asks for a badge the runtime cache exists
 * (it may simply still be empty before the first server push, which is
 * the correct pre-connection appearance).
 */
private fun currentSessionStates(): Map<String, String?> =
    termtasticClient.windowState.states.value

/**
 * Aggregates the per-pane states of a tab into a single tab-level
 * indicator label. Mirrors the per-tab aggregation block in
 * [updateStateIndicators] (`"waiting"` wins over `"working"` wins over
 * `null`), so the chrome's tab-strip badge stays consistent whether
 * it's painted by the factory at construction time or repainted by
 * `updateStateIndicators` on a later state push.
 *
 * @param tabId stable tab id whose panes' session states are inspected.
 * @param sessionStates snapshot of the per-session state map (typically
 *   [currentSessionStates]).
 * @return `"waiting"` if any pane in the tab is waiting, otherwise
 *   `"working"` if any pane is working, otherwise `null`. Also returns
 *   `null` if [tabId] is not in the current config (e.g. tab just closed).
 */
private fun aggregateTabState(tabId: String, sessionStates: Map<String, String?>): String? {
    val cfg: dynamic = currentConfig ?: return null
    val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabsArr) {
        if ((tab.id as? String) != tabId) continue
        val panes = (tab.panes as? Array<dynamic>) ?: return null
        var tabState: String? = null
        for (p in panes) {
            val sid = p.leaf?.sessionId as? String ?: continue
            when (sessionStates[sid]) {
                "waiting" -> return "waiting"
                "working" -> if (tabState != "working") tabState = "working"
            }
        }
        return tabState
    }
    return null
}

/**
 * Builds a status indicator for a toolkit badge slot (sidebar row or pane
 * header). Uses the unified `.tt-status-dot` indicator, painted in the theme's
 * foreground colour (issue #38): idle = solid dot, working = breathing dot,
 * waiting = pulsing warning/exclamation triangle. Painted from the current
 * [currentSessionStates] snapshot so it survives toolkit rerenders without a
 * blank frame.
 *
 * Terminal panes carry `data-session=<sid>` so [updateStateIndicators] repaints
 * them on every server push; a null [sessionId] yields a static idle dot
 * (used for stateless sidebar panes — file browser, git, link).
 *
 * @param sessionId terminal session id stamped onto `data-session`, or null for
 *   a stateless dot.
 * @param header when true, adds the larger `.tt-status-dot-header` variant for
 *   the roomier pane-header chrome.
 * @return a `<span class="tt-status-dot">` painted from the current state.
 * @see applyDotState
 */
private fun buildStatusDot(sessionId: String?, header: Boolean = false): HTMLElement {
    val el = document.createElement("span") as HTMLElement
    el.className = if (header) "tt-status-dot tt-status-dot-header" else "tt-status-dot"
    if (sessionId != null) {
        el.setAttribute("data-session", sessionId)
        applyDotState(el, currentSessionStates()[sessionId])
    }
    return el
}

/**
 * Builds the per-TAB aggregated status dot for the tab-strip trailing badge,
 * replacing the former `spinner-tab`. Carries `data-tab-state=<tabId>` so the
 * per-tab aggregation block in [updateStateIndicators] repaints it; painted from
 * [aggregateTabState] over the current [currentSessionStates] snapshot so a
 * chrome rebuild (theme toggle, sidebar toggle, etc.) doesn't drop the visible
 * tab indicator between the rebuild and the next server push (termtastic#24).
 *
 * @param tabId tab id stamped onto `data-tab-state`.
 * @return a `<span class="tt-status-dot">` carrying `data-tab-state`.
 * @see applyDotState
 */
private fun buildTabStatusDot(tabId: String): HTMLElement {
    val el = document.createElement("span") as HTMLElement
    el.className = "tt-status-dot"
    el.setAttribute("data-tab-state", tabId)
    applyDotState(el, aggregateTabState(tabId, currentSessionStates()))
    return el
}

/**
 * Looks up the terminal session id for [paneId] via the live
 * `currentConfig` snapshot. Returns `null` for non-terminal leaves
 * (file browser, git, link panes).
 */
private fun sessionIdForPane(paneId: String): String? {
    val leaf = findLeafDynamic(paneId) ?: return null
    val sid = leaf.sessionId as? String ?: return null
    return sid.ifEmpty { null }
}

/* -------------------------------------------------------------------- */
/* Boot.                                                                */
/* -------------------------------------------------------------------- */

/**
 * Mounts termtastic's full UI through the darkness-toolkit's
 * `mountAppShell`. Replaces every chrome-side concern (top bar, tab
 * strip, sidebar tree, layout root, pane drag/resize, theme manager,
 * appearance toggle) with toolkit-supplied primitives.
 *
 * Pre-requisites (the same ones the previous `start()` path needed):
 * - `appVm` constructed and started.
 * - `windowSocket` open.
 * - `webSettingsPersister` constructed and `toolkitPersister` adapter
 *   wired to the snapshot mirror.
 *
 * @param root host element (typically `document.getElementById("app")`).
 */
fun bootViaToolkitShell(root: HTMLElement) {
    // Construct termtastic's cheatsheet modal and bind Cmd/Ctrl+/. The
    // modal shell, escape-dismiss, and outside-click logic all live in
    // the toolkit; termtastic only supplies the curated content list.
    val hotkeysModal = ToolkitHotkeysModal().apply { setContent(termtasticHotkeysSpec()) }
    installCheatsheetHotkey(hotkeysModal)
    appShellHandle = mountAppShell(
        AppShellSpec(
            rootContainer = root,
            title = "Termtastic",
            persister = toolkitPersister,
            paneContent = { paneId -> mountPaneContent(paneId) },
            tabSource = termtasticTabSource(
                scope = GlobalScope,
                windowState = termtasticClient.windowState,
                socket = windowSocket,
            ),
            paneLabel = { _, paneId ->
                (findLeafDynamic(paneId)?.title as? String) ?: paneId
            },
            paneIcon = { _, paneId -> termtasticPaneIcon(findLeafDynamic(paneId)) },
            paneActions = { _, paneId -> termtasticPaneActions(paneId) },
            // Pane rename commits via WindowCommand.Rename. Empty
            // `newLabel` is honoured server-side (PaneManager.renamePane
            // clears `customName` and reverts the title to the cwd-based
            // fallback). The toolkit input swap already trims the value
            // and skips no-op commits, so we only see meaningful changes.
            paneRename = { _, paneId, newLabel ->
                launchCmd(WindowCommand.Rename(paneId = paneId, title = newLabel))
            },
            // Pane HEADER status indicator: a `.tt-status-dot` (header size)
            // carrying `data-session=<sid>` so `updateStateIndicators` finds it
            // via `querySelectorAll`. Non-terminal panes (file browser, git,
            // link) return `null` and so don't get a header dot. Replaces the
            // former spinner / warning-triangle glyph.
            paneHeaderBadge = { _, paneId ->
                sessionIdForPane(paneId)?.let { buildStatusDot(it, header = true) }
            },
            // The sidebar badge slot holds the per-row status DOT (trailing bead,
            // after the label) for EVERY pane — terminal panes are state-driven
            // via their session id; non-terminal panes get an idle dot. CSS
            // `order` floats it to the trailing edge and hides it while idle
            // (issue #43), so a row only shows the dot when working/waiting.
            // See buildStatusDot.
            paneSidebarBadge = { _, paneId ->
                buildStatusDot(sessionIdForPane(paneId))
            },
            // Per-tab aggregated status DOT in the LEFT-PANE tab header, placed
            // after the tab label (issue #43). Carries `data-tab-state=<tabId>`
            // just like the tab-strip dot below, so `updateStateIndicators`
            // repaints both from one query; CSS hides it while the tab is idle,
            // so it appears only when some pane in the tab is working/waiting.
            tabSidebarHeaderBadge = { tabId -> buildTabStatusDot(tabId) },
            // Sticky pane-slot index — `①..⑨`, `Ⓐ..Ⓩ` rendered as a
            // trailing badge on both pane header and sidebar row. The
            // assigner is kept in sync with the server-pushed pane set
            // by `TermtasticTabSource`'s collector.
            paneIndex = { _, paneId -> termtasticPaneAssigner.indexOf(paneId) },
            // Per-tab aggregated status DOT on the tab itself in the strip.
            // `updateStateIndicators` aggregates pane states per tab in its
            // `for (tab in cfg.tabs)` block and repaints every
            // `.tt-status-dot[data-tab-state='<tabId>']` element it finds.
            tabTrailingBadge = { tabId -> buildTabStatusDot(tabId) },
            extraTopbarTrailing = buildList {
                add(buildNewsTopbarAction())
                add(buildAboutTopbarAction())
            },
            // App logo at the top of the left (sessions) sidebar and the
            // Claude usage rows pinned at its bottom.
            sidebarHeader = { buildSidebarLogo() },
            sidebarFooter = { buildSidebarFooter() },
            // No bottom status bar: termtastic's only footer content (the
            // Claude usage rows, and the work-state dot in the AppLogo) lives
            // in the left-sidebar header/footer now, so the toolkit's bottom
            // bar would just be an empty strip. Opt out so the app frame ends
            // at the panes.
            showBottomBar = false,
            settingsHost = termtasticThemeHost,
            // Opt-in to the toolkit's App Settings sidebar slot. Adds
            // the gear icon to the trailing topbar cluster (right of
            // the Appearance "Aa" gear) and renders [buildAppSettingsContent]
            // inside the sidebar when the user clicks it.
            appSettingsContent = { buildAppSettingsContent() },
            isElectron = isElectronClient,
            theme = ThemeBootstrap.default(),
            // Toolkit fires this after every committed pane-geometry
            // change in the active tab: split-bar/corner resize end,
            // maximize / restore, and layout-preset apply (including
            // Auto re-tile on pane add/remove). The per-terminal
            // ResizeObserver has already re-fitted local xterm grids
            // to the new container size before this fires, but the
            // remote PTYs are still at their pre-resize cols/rows —
            // so the shell's output continues to wrap at the old
            // width and the pane "looks broken" until something
            // tells the server to reassert size. The Reformat button
            // in the pane header does exactly that for one pane via
            // [forceReassert]; we now do it automatically for every
            // visible terminal whenever geometry settles, so the
            // user no longer has to click Reformat after every
            // resize gesture. Iterating `terminals.values` and
            // gating on `offsetParent != null` (the existing
            // visibility check used by [fitVisible]) restricts the
            // ping to terminals in the active tab — inactive tabs'
            // pane content elements are detached by the toolkit's
            // chrome cache. The `tabId` is ignored for the same
            // reason: visibility is already the right filter, and
            // the toolkit may pass a not-yet-current tab id for the
            // "Auto re-tile on membership change" path.
            onGeometryChanged = { _ ->
                for (entry in terminals.values) {
                    // Honour the per-pane "stop automatic reflow" setting:
                    // panes with `autoReflow == false` are frozen, so a
                    // geometry change must not reassert their PTY size.
                    if (!entry.autoReflow) continue
                    val parent = (entry.term.asDynamic().element as? HTMLElement)?.offsetParent
                    if (parent != null) {
                        try { forceReassert(entry) } catch (_: Throwable) {}
                    }
                }
            },

            // Toolkit rerenders wipe & rebuild the pane chrome slots,
            // discarding the inline per-section CSS vars that
            // [applyAppearanceClass] stamps on `.terminal-cell` /
            // `.terminal` / `.md-view` / `.git-view` for the
            // `terminal` / `fileBrowser` / `git` schemes. Without
            // this reapply, panes inherit `--t-terminal-bg` from
            // `.dt-pane`'s `windows`-scheme palette on every tab
            // switch / sidebar toggle / layout change — visible as
            // the pane interior padding flipping to the chrome
            // scheme's surface colour instead of the user's chosen
            // terminal-scheme bg. Idempotent; the toolkit makes no
            // guarantees about call frequency.
            onAfterRefresh = ::applyAppearanceClass,
        ),
        scope = GlobalScope,
    )

    // Install the reformat-button hover popup ("Automatic reformat (this
    // window)" / "(future windows)"). Uses document-level event delegation
    // so it survives the toolkit's chrome rebuilds without per-render
    // re-wiring — see [installReformatHoverPopup].
    installReformatHoverPopup()

    // Double-click a pane title to rename it (mirrors tab-label rename).
    // Document-level delegation, so it survives toolkit chrome rebuilds;
    // routes to the toolkit's beginPaneRename via appShellHandle (now set
    // above). See [installPaneTitleDoubleClickRename].
    installPaneTitleDoubleClickRename()

    // Double-click a pane's name in the sidebar to rename it. Same
    // delegation pattern; the sidebar has no toolkit rename hook, so this
    // hand-rolls the inline input. See [installSidebarPaneDoubleClickRename].
    installSidebarPaneDoubleClickRename()
}
