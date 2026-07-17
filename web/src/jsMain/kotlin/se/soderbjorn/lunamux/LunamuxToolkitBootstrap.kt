/* LunamuxToolkitBootstrap.kt (jsMain)
 * Production entry point that mounts Lunamux's UI through the
 * darkness-toolkit's `mountAppShell`. Replaces the hand-rolled chrome
 * (custom `TabBar`, `Sidebar`, header DOM in `index.html`, `LayoutMenu`,
 * bespoke pane drag/resize) with toolkit-supplied primitives. The
 * toolkit becomes the source of truth for app frame, top bar, tab strip,
 * sidebar tree, layout presets, pane chrome (header/drag/resize/close),
 * and pane geometry persistence. Lunamux-specific code shrinks to:
 *   - per-pane content (terminal, file browser, git diff) via
 *     [mountPaneContent]
 *   - the `WindowConfig` ↔ [TabSource] bridge ([lunamuxTabSource])
 *   - app-specific pane action buttons (worktree, reformat, copy-path)
 *     via [lunamuxPaneActions]
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
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.darkness.web.confirmClosePane
import se.soderbjorn.darkness.web.hotkey.Hotkey
import se.soderbjorn.darkness.web.hotkey.HotkeyActionSpec
import se.soderbjorn.darkness.web.hotkey.HotkeyBindings
import se.soderbjorn.darkness.web.layout.PaneAction
import se.soderbjorn.darkness.web.layout.PaneActions
import se.soderbjorn.darkness.web.layout.PaneMenuItem
import se.soderbjorn.darkness.web.layout.PaneMenuSpec
import se.soderbjorn.darkness.web.layout.openPaneMenu
import se.soderbjorn.darkness.web.shell.AppShellHandle
import se.soderbjorn.darkness.web.shell.AppShellSpec
import se.soderbjorn.darkness.web.shell.ThemeBootstrap
import se.soderbjorn.darkness.web.shell.TopbarAction
import se.soderbjorn.darkness.web.shell.buildTopbarIconButton
import se.soderbjorn.darkness.web.shell.mountAppShell

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

/**
 * Triptych — opens the 3D tab/pane overview (carousel ring). Three cards in
 * a row with the center one forward (app-switcher silhouette), drawn in the
 * same 24×24 stroke style as [ICON_ABOUT] / [ICON_NEWS] so it reads as part
 * of the same topbar icon set. Sits immediately left of the trailing "+"
 * New button. Replaced the earlier wireframe globe, which read as
 * "language/region" rather than "switch apps".
 *
 * @see buildOverview3dTopbarAction
 */
private const val ICON_TRIPTYCH =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2.5" y="7" width="5" height="10" rx="1"/><rect x="9.5" y="4" width="5" height="16" rx="1"/><rect x="16.5" y="7" width="5" height="10" rx="1"/></svg>"""

/** Arrow-out-of-box glyph — web pane "Open in browser" action. */
private const val PA_ICON_OPEN_EXTERNAL =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><line x1="20" y1="4" x2="11" y2="13"/><path d="M18 13v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 19V8a1.5 1.5 0 0 1 1.5-1.5H11"/></svg>"""

/** Material Symbols "content_copy" — file-browser path-copy action. */
private const val PA_ICON_COPY =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="8" y="8" width="13" height="13" rx="1.5"/><path d="M16 8V4.5A1.5 1.5 0 0 0 14.5 3H4.5A1.5 1.5 0 0 0 3 4.5v10A1.5 1.5 0 0 0 4.5 16H8"/></svg>"""

/**
 * Stable, namespaced id for the user-configurable "Reformat terminal"
 * hotkey action. This is the persistence key for the user's custom chord
 * (see [HotkeyBindings]) — renaming it would orphan saved rebindings — and
 * the id the Keyboard-shortcuts sidebar row references
 * ([HotkeysSidebarContent]'s Windows group).
 *
 * @see registerReformatHotkey
 */
internal const val REFORMAT_HOTKEY_ACTION_ID: String = "Lunamux.terminal.reformat"

/**
 * Stable, namespaced id for the user-configurable "3D tab overview" toggle
 * hotkey. Persistence key for custom chords (see [HotkeyBindings]) — renaming
 * it would orphan saved rebindings — and the id referenced by the
 * Keyboard-shortcuts sidebar row ([HotkeysSidebarContent]'s Tabs group).
 *
 * @see registerOverview3dHotkey
 */
internal const val OVERVIEW3D_HOTKEY_ACTION_ID: String = "Lunamux.overview3d.toggle"

/**
 * Stable action id for the **3D world spike** toggle hotkey (default ⌥⌘← /
 * Alt+Meta+ArrowLeft), the mirror of [OVERVIEW3D_HOTKEY_ACTION_ID]'s ⌥⌘→: one
 * opens the app switcher, the other opens the world spike.
 *
 * @see registerWorld3dSpikeHotkey
 */
internal const val WORLD3D_SPIKE_HOTKEY_ACTION_ID: String = "Lunamux.world3dspike.toggle"

/**
 * Stable action id for the **switch world** hotkey (default ⌥⌘O /
 * Alt+Meta+KeyO) — cycles the active world on to the next one in flat 2D mode,
 * the 2D counterpart of the 3D world's ⌥⌘O fly-through-the-wormhole switch. No-
 * op while the 3D world is open, where that chord runs the cinematic transit
 * instead ([se.soderbjorn.lunamux.buildKeyHandler]).
 *
 * @see registerWorldSwitchHotkey
 */
internal const val WORLD_SWITCH_HOTKEY_ACTION_ID: String = "Lunamux.world.switch"

/** Reformat (terminal action). */
private const val PA_ICON_REFORMAT =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="1.5"/><polyline points="7 10 4 12 7 14"/><polyline points="17 10 20 12 17 14"/></svg>"""

/** Branch-with-plus glyph for the create-worktree action (now a menu item glyph). */
private const val PA_ICON_WORKTREE =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><path d="M6 8v2c0 2.2 1.8 4 4 4h4"/><line x1="6" y1="8" x2="6" y2="16"/><line x1="21" y1="9" x2="21" y2="15"/><line x1="18" y1="12" x2="24" y2="12"/></svg>"""

/** Arrow-into-frame glyph for the "Move to tab" pane-menu row (issue #89). */
private const val PA_ICON_MOVE_TO_TAB =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 4h4a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 19 20h-4"/><line x1="3" y1="12" x2="13" y2="12"/><polyline points="9 8 13 12 9 16"/></svg>"""

/** Circular arrow — "Reset terminal" pane-menu row (issue #91). */
private const val PA_ICON_RESET_TERMINAL =
    """<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 1 0 3-6.7"/><polyline points="3 4 3 9 8 9"/></svg>"""

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

/** Returns the tabs array of the world that owns [paneId] in the dynamic
 *  [cfg], or `null` if none (or the config has no worlds). */
private fun worldTabsOwningPane(cfg: dynamic, paneId: String): Array<dynamic>? {
    val worlds = cfg.worlds as? Array<dynamic> ?: return null
    for (world in worlds) {
        val tabs = world.tabs as? Array<dynamic> ?: continue
        for (tab in tabs) {
            val panes = (tab.panes as? Array<dynamic>) ?: continue
            for (p in panes) {
                if ((p.leaf?.id as? String) == paneId) return tabs
            }
        }
    }
    return null
}

/** Looks up a leaf descriptor in the current server config by pane id.
 *  Searches every world's tabs (worlds hold the source-of-truth panes for
 *  ≥1.9 clients) and falls back to the legacy top-level `tabs` mirror.
 *
 *  `internal` (not `private`) because [mountPaneContent] and [rawLeafFor]
 *  need the same world-aware scan: the legacy `cfg.tabs` mirror only carries
 *  the DEFAULT world's panes, so a `cfg.tabs`-only scan returns `null` for
 *  any pane created in a non-default world — leaving its pane stuck on the
 *  `[window … — booting]` placeholder forever. */
internal fun findLeafDynamic(paneId: String): dynamic {
    val cfg: dynamic = currentConfig ?: return null
    fun scan(tabsArr: Array<dynamic>?): dynamic {
        if (tabsArr == null) return null
        for (tab in tabsArr) {
            val panes = (tab.panes as? Array<dynamic>) ?: continue
            for (p in panes) {
                if ((p.leaf?.id as? String) == paneId) return p.leaf
            }
        }
        return null
    }
    val worlds = cfg.worlds as? Array<dynamic>
    if (worlds != null) {
        for (world in worlds) {
            val found = scan(world.tabs as? Array<dynamic>)
            if (found != null) return found
        }
    }
    return scan(cfg.tabs as? Array<dynamic>)
}

/**
 * Builds the "Move to tab" submenu rows for [paneId]'s pane kebab menu
 * (issue #89): one [PaneMenuItem] per tab in the current server config,
 * excluding the tab the pane already lives in. Choosing a row dispatches
 * [WindowCommand.MovePaneToTab]; the server relocates the pane and
 * re-applies both tabs' layout presets (e.g. Auto re-tiles both sides),
 * then broadcasts the updated [WindowConfig] to every client.
 *
 * Hidden tabs (both strip-hidden and sidebar-hidden) are deliberately
 * INCLUDED as targets — they keep all their panes and PTY sessions alive
 * and remain valid destinations; strip-hidden ones get a "(hidden)"
 * suffix so the user isn't surprised when the pane seems to vanish.
 *
 * Called at menu-open time (inside the kebab's `handlerWithAnchor`) so
 * the tab list is always the freshest snapshot.
 *
 * @param paneId the pane the kebab menu belongs to.
 * @return submenu rows in tab-strip order; empty when there is no other
 *   tab to move to (the caller renders the parent row disabled then).
 */
private fun buildMoveToTabItems(paneId: String): List<PaneMenuItem> {
    val cfg: dynamic = currentConfig ?: return emptyList()
    // Panes only move between tabs of the SAME world, so scope the target
    // list to the world that owns this pane (fall back to the legacy flat
    // tabs when the config carries no worlds).
    val tabsArr = worldTabsOwningPane(cfg, paneId)
        ?: (cfg.tabs as? Array<dynamic>)
        ?: return emptyList()
    // Resolve the pane's own tab first so it can be excluded from targets.
    var ownTabId: String? = null
    outer@ for (tab in tabsArr) {
        val panes = (tab.panes as? Array<dynamic>) ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) {
                ownTabId = tab.id as? String
                break@outer
            }
        }
    }
    val items = mutableListOf<PaneMenuItem>()
    for (tab in tabsArr) {
        val tabId = tab.id as? String ?: continue
        if (tabId == ownTabId) continue
        val title = (tab.title as? String) ?: tabId
        val hidden = (tab.isHidden as? Boolean) ?: false
        items += PaneMenuItem(
            label = if (hidden) "$title (hidden)" else title,
            handler = {
                launchCmd(WindowCommand.MovePaneToTab(paneId = paneId, targetTabId = tabId))
            },
        )
    }
    return items
}

/**
 * Builds the "Reset terminal" row for [paneId]'s pane kebab menu, or an
 * empty list when the pane isn't a terminal (file browser / git panes
 * have no PTY session to reset).
 *
 * The row sends [PtyControl.ResetModes] over the pane's PTY WebSocket via
 * [sendModeReset]; the server broadcasts DECRST sequences cancelling
 * sticky modes (mouse tracking, focus reporting, bracketed paste,
 * application cursor keys, alt screen) to every attached client. Escape
 * hatch for terminals wedged in mouse-reporting mode — e.g. after a
 * killed-server restore replayed a dead full-screen app's DECSET
 * sequences (issue #91).
 *
 * @param paneId the pane the kebab menu belongs to.
 * @param contentKind the pane's content kind as passed to the pane-action
 *   builder ("terminal", "filebrowser", "git", …).
 * @return a single-row list for terminal panes, else empty.
 */
private fun buildResetTerminalItems(paneId: String, contentKind: String): List<PaneMenuItem> {
    if (contentKind != "terminal") return emptyList()
    return listOf(
        PaneMenuItem(
            label = "Reset terminal",
            iconHtml = PA_ICON_RESET_TERMINAL,
            handler = { terminals[paneId]?.let { sendModeReset(it) } },
        ),
    )
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
 * [lunamuxPaneActions].
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

/**
 * Reformat the terminal in the currently active (most-recently focused)
 * pane, if any. The keyboard-shortcut counterpart of clicking a pane's
 * Reformat button: both funnel into [forceReassert], which re-asserts the
 * remote PTY size to match the pane.
 *
 * "Active" is resolved from [lastFocusedTerminalId] (set on terminal
 * `focusin`), so the shortcut targets whichever terminal the user last
 * worked in. No-ops silently when no terminal has been focused yet or the
 * focused pane is not a live terminal (e.g. it was closed) — [forceReassert]
 * itself is also a safe no-op on a stale socket.
 *
 * Called by the [REFORMAT_HOTKEY_ACTION_ID] binding registered in
 * [registerReformatHotkey].
 */
private fun reformatActiveTerminal() {
    val paneId = lastFocusedTerminalId ?: return
    val entry = terminals[paneId] ?: return
    try { forceReassert(entry) } catch (_: Throwable) {}
}

/**
 * Register the configurable **Reformat terminal** hotkey (default
 * ⌃⌥R / Ctrl+Alt+R) with the toolkit's [HotkeyBindings].
 *
 * Going through [HotkeyBindings.registerAction] (rather than a raw
 * [se.soderbjorn.darkness.web.hotkey.HotkeyRegistry.register]) is what makes
 * the shortcut **user-changeable**: it surfaces as a clickable, rebindable
 * row in the Keyboard-shortcuts sidebar ([HotkeysSidebarContent]) and its
 * custom chord persists and syncs across clients through the same
 * server-managed `HOTKEY_BINDINGS` blob as the toolkit's own hotkeys (see
 * `onServerUiSettingsApplied` in `main.kt`). The registry dispatches from a
 * capture-phase `window` listener, so the chord fires even while a terminal
 * has key focus and is not forwarded to the PTY.
 *
 * The default chord uses `key = "r"`. Depending on macOS version/layout,
 * ⌥-composition can make `KeyboardEvent.key` report the Option glyph ("®")
 * instead of the base letter even with Control held; the toolkit's
 * `Hotkey.matches` handles this by falling back to the physical
 * `KeyboardEvent.code` (`"KeyR"`) when Alt is held, so ⌃⌥R matches on all
 * layouts. Idempotent — safe to call once at boot from [bootViaToolkitShell].
 *
 * @see reformatActiveTerminal
 */
private fun registerReformatHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = REFORMAT_HOTKEY_ACTION_ID,
            label = "Reformat terminal",
            defaults = listOf(Hotkey(key = "r", ctrl = true, alt = true)),
        ),
    ) { reformatActiveTerminal() }
}

/**
 * Register the configurable **3D tab overview** toggle hotkey (default
 * ⌥⌘→ / Alt+Meta+ArrowRight) with the toolkit's [HotkeyBindings].
 *
 * Same registration mechanics as [registerReformatHotkey]: going through
 * [HotkeyBindings.registerAction] makes the chord user-changeable via the
 * Keyboard-shortcuts sidebar, persists custom bindings through the
 * server-managed `HOTKEY_BINDINGS` blob, and dispatches from a capture-phase
 * `window` listener so the chord fires even while a terminal has key focus.
 * Idempotent — called once at boot from [bootViaToolkitShell].
 *
 * @see toggleOverview3d
 * @see OVERVIEW3D_HOTKEY_ACTION_ID
 */
private fun registerOverview3dHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = OVERVIEW3D_HOTKEY_ACTION_ID,
            label = "3D tab overview",
            defaults = listOf(Hotkey(key = "ArrowRight", alt = true, meta = true)),
        ),
    ) { toggleOverview3d() }
}

/**
 * Register the **3D world spike** toggle hotkey (default ⌥⌘← /
 * Alt+Meta+ArrowLeft) — the left-hand mirror of [registerOverview3dHotkey]'s ⌥⌘→.
 * Same user-rebindable registration mechanics; opens the panes-rotunda world
 * ([toggleWorld3dSpike]) rather than the app switcher. Idempotent — called once
 * at boot from [bootViaToolkitShell].
 *
 * @see toggleWorld3dSpike
 * @see WORLD3D_SPIKE_HOTKEY_ACTION_ID
 */
private fun registerWorld3dSpikeHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = WORLD3D_SPIKE_HOTKEY_ACTION_ID,
            label = "3D mode",
            defaults = listOf(Hotkey(key = "ArrowLeft", alt = true, meta = true)),
        ),
    ) { toggleWorld3dSpike() }
}

/**
 * Register the **switch world** hotkey (default ⌥⌘O / Alt+Meta+KeyO): cycle the
 * active world on to the next one in flat 2D mode. Mirrors the 3D world's ⌥⌘O
 * fly-through-the-wormhole switch so the same chord means "next world" in both
 * modes. Guarded on [spikeOpen] so it stays inert while the 3D world is up —
 * there the world's own key handler owns ⌥⌘O and plays the cinematic transit,
 * and firing this too would double-cycle. Same user-rebindable registration
 * mechanics as the others; idempotent — called once at boot.
 *
 * @see switchToNextWorld
 * @see WORLD_SWITCH_HOTKEY_ACTION_ID
 */
private fun registerWorldSwitchHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = WORLD_SWITCH_HOTKEY_ACTION_ID,
            label = "Switch workspace",
            defaults = listOf(Hotkey(key = "o", alt = true, meta = true)),
        ),
    ) {
        // The 3D world binds ⌥⌘O itself (the wormhole transit); don't also cycle here.
        if (!spikeOpen) switchToNextWorld()
    }
}

/**
 * Advance the active world to the next one in [WindowConfig.worlds] (wrapping
 * past the end) by sending [WindowCommand.SetActiveWorld]; the server re-
 * broadcasts and the 2D tab strip repaints to the new world (see
 * [lunamuxTabSource]). No-op with fewer than two worlds or before the server
 * has reported any. The 2D counterpart of the globe switcher's click and the
 * 3D ⌥⌘O transit.
 *
 * @see registerWorldSwitchHotkey
 */
internal fun switchToNextWorld() {
    val config = lunamuxClient.windowState.config.value ?: return
    val worlds = config.worlds
    if (worlds.size < 2) return
    val idx = worlds.indexOfFirst { it.id == config.activeWorldId }
    val next = worlds[if (idx < 0) 0 else (idx + 1) % worlds.size]
    if (next.id != config.activeWorldId) launchCmd(WindowCommand.SetActiveWorld(next.id))
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
fun lunamuxPaneActions(paneId: String): List<PaneAction> {
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

    if (contentKind == "webBrowser") {
        actions += PaneAction(
            iconHtml = PA_ICON_OPEN_EXTERNAL,
            tooltip = "Open in browser",
            handler = {
                // Prefer the live guest's current URL (it reflects in-page
                // navigation the persisted content may lag behind); fall back
                // to the stored content URL.
                val liveUrl = webBrowserPaneViews[paneId]?.lastCommittedUrl
                val url = liveUrl ?: (leaf.content?.url as? String)
                url?.takeIf { it.isNotBlank() }?.let { openExternalUrl(it) }
            },
            extraClass = "tt-pane-action-openext",
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
            // Built at open time so the tab list reflects the live config.
            val moveTargets = buildMoveToTabItems(paneId)
            openPaneMenu(
                anchor = btn,
                spec = PaneMenuSpec(items = listOf(
                    PaneMenuItem(
                        label = "Rename window",
                        handler = { appShellHandle?.beginPaneRename(paneId) },
                    ),
                    // "Move to tab ▸" flyout listing every other tab
                    // (issue #89). Disabled when this is the only tab.
                    PaneMenuItem(
                        label = "Move to tab",
                        iconHtml = PA_ICON_MOVE_TO_TAB,
                        submenu = moveTargets,
                        isEnabled = moveTargets.isNotEmpty(),
                    ),
                    PaneMenuItem(
                        label = "Create worktree",
                        iconHtml = PA_ICON_WORKTREE,
                        handler = { launchCmd(WindowCommand.GetWorktreeDefaults(paneId = paneId)) },
                    ),
                ) + buildResetTerminalItems(paneId, contentKind)),
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
    label = "About Lunamux",
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
 *   (Desktop app updates are surfaced separately in the sidebar-footer update
 *   banner — see AutoUpdaterPanel.kt — so this bell is news-only.)
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
 * Builds the "3D overview" leading topbar action — a triptych that toggles the
 * carousel-ring tab/pane overview ([toggleOverview3d]). Placed in the
 * [AppShellSpec.extraTopbarBeforeStandard] slot so it renders immediately to
 * the LEFT of the trailing "+" New split-button, giving mouse users a click
 * target for what was previously only reachable via the ⌥⌘→ hotkey
 * (see [registerOverview3dHotkey]).
 *
 * @return the triptych topbar action; its click toggles the overview open/closed.
 * @see toggleOverview3d
 */
private fun buildOverview3dTopbarAction(): TopbarAction {
    // Pre-build the button through the toolkit's canonical
    // [buildTopbarIconButton] so it carries the `.dt-topbar-icon-button`
    // class — same sizing, vertical centering, currentColor tint, and hover
    // affordance as the adjacent standard cluster ("+" / Layout / Appearance).
    // The plain [TopbarAction] fallback renders a bare button with none of
    // that styling, which made the icon read as off-colour and mis-centred
    // with no hover. `element` takes precedence over `iconHtml` in the shell.
    val button = buildTopbarIconButton(ICON_TRIPTYCH, "3D tab overview") { toggleOverview3d() }
    // Stash the element so [applyOverview3dChromeVisibility] can show/hide it
    // live when the experimental "3D app switcher" flag toggles.
    overview3dTopbarButton = button
    return TopbarAction(
        id = "tt-topbar-overview3d",
        label = "3D tab overview",
        onActivate = { toggleOverview3d() },
        element = button,
    )
}

/**
 * The pre-built topbar app-switcher button (from [buildOverview3dTopbarAction]),
 * captured so [applyOverview3dChromeVisibility] can toggle its visibility
 * without re-querying the DOM. `null` until the shell spec is built at boot.
 */
private var overview3dTopbarButton: HTMLElement? = null

/** Cube glyph for the temporary Phase-2 world-mode spike topbar button. */
private const val ICON_CUBE =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>"""

/**
 * Builds the temporary **Phase-2 world-mode spike** topbar action — a cube that
 * toggles [toggleWorld3dSpike], the throwaway proof that a real xterm.js
 * terminal can live and be typed into on a CSS3D 3D plane. Placed alongside the
 * app-switcher triptych in the leading topbar slot. Remove with the spike itself.
 *
 * In the **web demo** ([isDemoClient] without [isElectronClient]) the bare
 * cube gets the [decorateWorld3dDemoTopbarButton] dressing — a "3D Mode"
 * label, a "NEW!" tag, and a temporary glow — because the website's visitors
 * have no reason to hover an unlabelled icon, and this button is the door to
 * the demo's showpiece.
 *
 * @return the cube topbar action; its click toggles the spike overlay.
 * @see toggleWorld3dSpike
 */
private fun buildWorld3dSpikeTopbarAction(): TopbarAction {
    val button = buildTopbarIconButton(ICON_CUBE, "3D mode") { toggleWorld3dSpike() }
    if (isDemoClient && !isElectronClient) decorateWorld3dDemoTopbarButton(button)
    // Stash the element so [applyWorld3dSpikeChromeVisibility] can show/hide it
    // live when the experimental "3D world" flag toggles.
    world3dSpikeTopbarButton = button
    return TopbarAction(
        id = "tt-topbar-world3d-spike",
        label = "3D mode",
        onActivate = { toggleWorld3dSpike() },
        element = button,
    )
}

/**
 * The pre-built topbar cube button (from [buildWorld3dSpikeTopbarAction]),
 * captured so [applyWorld3dSpikeChromeVisibility] can toggle its visibility
 * without re-querying the DOM. `null` until the shell spec is built at boot.
 */
private var world3dSpikeTopbarButton: HTMLElement? = null

/**
 * Web-demo dressing for the topbar 3D-world cube: a visible **"3D Mode"**
 * label next to the icon, a tiny top-aligned **"NEW!"** tag, and the same
 * ~15 s slow attention glow the in-world tour button uses. Applied in place
 * on the pre-built icon button — the `.dt-topbar-icon-button` class stays,
 * so hover/tint still match the rest of the topbar.
 *
 * Called only by [buildWorld3dSpikeTopbarAction], and only in the web demo
 * ([isDemoClient] and not [isElectronClient]) — a real install's topbar
 * keeps the quiet bare-icon look.
 *
 * @param button the pre-built cube icon button to decorate.
 */
private fun decorateWorld3dDemoTopbarButton(button: HTMLElement) {
    // Widen the toolkit's fixed 30×30 icon square into a labelled pill.
    button.style.width = "auto"
    button.style.padding = "0 8px"
    button.style.setProperty("gap", "6px")

    val label = document.createElement("span") as HTMLElement
    label.textContent = "3D Mode"
    // The icon-button class zeroes line-height (SVG centring); text needs it back.
    label.style.cssText = "font-size:12px;font-weight:600;line-height:normal;white-space:nowrap;"
    button.appendChild(label)

    val tag = document.createElement("span") as HTMLElement
    tag.textContent = "NEW!"
    tag.style.cssText = "align-self:flex-start;margin-top:3px;font-size:8px;font-weight:700;" +
        "line-height:normal;letter-spacing:0.5px;color:var(--t-accent, #4dc8f5);"
    button.appendChild(tag)

    // Same attention treatment as the in-world tour button: a slow glow
    // swell that stops by itself after ~15 s. Keyframes ride in a
    // page-lifetime <style> (inline `style=` cannot declare @keyframes).
    // Tinted with the active theme accent (the lunamux dark theme's blue by
    // default) via `--t-accent`, matching the "NEW!" tag above — `color-mix`
    // fades it to transparent for the swell, the same pattern styles.css uses.
    val glow = document.createElement("style") as HTMLElement
    glow.textContent = "@keyframes tt-world3d-demo-glow{" +
        "0%,100%{box-shadow:0 0 0 transparent;}" +
        "50%{box-shadow:0 0 12px color-mix(in srgb, var(--t-accent, #4dc8f5) 55%, transparent);}}"
    document.head?.appendChild(glow)
    button.style.setProperty("animation", "tt-world3d-demo-glow 3s ease-in-out infinite")
    window.setTimeout({ button.style.removeProperty("animation") }, 15_000)
}

/**
 * Show or hide the topbar 3D-world cube button to match the current
 * `experimentalWorld3d` flag ([isExperimentalWorld3dEnabled]).
 *
 * Called once after the shell mounts to seed the initial state, and again from
 * the App Settings toggle's `onChange` so flipping the flag adds/removes the
 * button live — no reload needed. The ⌥⌘← hotkey is gated separately at the
 * [toggleWorld3dSpike] chokepoint, so it needs no DOM work here.
 *
 * @see isExperimentalWorld3dEnabled
 */
internal fun applyWorld3dSpikeChromeVisibility() {
    world3dSpikeTopbarButton?.style?.display =
        if (isExperimentalWorld3dEnabled()) "" else "none"
}

/**
 * Show or hide the topbar 3D-overview app-switcher button to match the current
 * `experimental3dSwitcher` flag ([isExperimental3dSwitcherEnabled]).
 *
 * Called once after the shell mounts to seed the initial state, and again from
 * the App Settings toggle's `onChange` so flipping the flag adds/removes the
 * button live — no reload needed. The ⌥⌘→ hotkey is gated separately at the
 * [toggleOverview3d] chokepoint, so it needs no DOM work here.
 *
 * @see isExperimental3dSwitcherEnabled
 */
internal fun applyOverview3dChromeVisibility() {
    overview3dTopbarButton?.style?.display =
        if (isExperimental3dSwitcherEnabled()) "" else "none"
}

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
 * Cached world-status element (the per-world working/waiting rows) that sits at
 * the very bottom of the sidebar footer, below the Claude usage bar. Cached like
 * [sidebarFooterEl] so [updateWorldStatusFooter] can reconcile the same element
 * across toolkit rerenders instead of losing its handle. Held here (module level)
 * rather than looked up by id so a rerender can't briefly detach it.
 */
private var worldStatusEl: HTMLElement? = null

/**
 * Builds (once) the app logo — "Lunamux" wordmark + work-state dot — for
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
    wordmark.textContent = "lunamux"
    val dot = document.createElement("span") as HTMLElement
    dot.id = "app-logo-dot"
    dot.className = "app-logo-dot"
    applyLogoDotState(dot, currentSessionStates())
    // Dot first, then wordmark: the status light sits to the LEFT of the
    // "Lunamux" text (mirroring the landing-page brand row).
    row.appendChild(dot)
    row.appendChild(wordmark)
    logo.appendChild(row)
    sidebarLogoEl = logo
    return logo
}

/**
 * Builds (once) the sidebar footer for the toolkit's `sidebarFooter` slot: on
 * Electron, the auto-update banner pinned at the top (see
 * AutoUpdaterPanel.buildUpdateBanner), then the Claude usage rows and the
 * world-status block. (News still lives behind the pulsing top-bar bell — see
 * [buildNewsTopbarAction].)
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

    // Desktop auto-update banner, pinned at the TOP of the footer (above the
    // Claude usage bar) — the lunamux analog of acolite's side-menu updater:
    // hidden until an update is available / in progress, then an accent title +
    // status/progress + Download/Restart. Electron only — a plain browser has no
    // updater to drive. See AutoUpdaterPanel.buildUpdateBanner.
    if (isElectronClient) footer.appendChild(buildUpdateBanner())

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

    // World-aware agent status, pinned BELOW the usage bar (last child of the
    // column-flex footer → renders at the very bottom of the sidebar). Ported
    // from the 3D world's warp-core status boxes, but restyled to the pane's
    // idiom (a titled block of accent-dot rows) — see `.world-status-bar`.
    val worlds = document.createElement("div") as HTMLElement
    worlds.className = "world-status-bar world-status-empty"
    worldStatusEl = worlds
    footer.appendChild(worlds)
    updateWorldStatusFooter()

    sidebarFooterEl = footer
    return footer
}

/**
 * A single active world's agent tally, for the sidebar's world-status block.
 *
 * @property name    the world's display name (the row's label).
 * @property accent  the world's resolved theme accent as a CSS colour string
 *   (the row's status dot), from [resolvedThemeForWorld] via [argbToCss].
 * @property working how many of the world's panes have a running agent.
 * @property waiting how many of the world's panes have an agent awaiting input.
 * @see computeWorldStatusRows
 */
private class WorldStatusRow(
    val name: String,
    val accent: String,
    val working: Int,
    val waiting: Int,
)

/**
 * Tallies every world's working/waiting panes from the resident
 * [latestWindowConfig] and the authoritative session-state map
 * ([lunamuxClient]`.windowState.states`, keyed by `sessionId` across all
 * worlds). Returns one [WorldStatusRow] per world that has any activity, in the
 * config's world order; quiet worlds are omitted so the block stays empty when
 * nothing is running.
 *
 * This is the 2D counterpart of the 3D warp-core's `computeWorldStatuses`: it
 * reads the same shared session-state model AND honours the same manual
 * [spikeWaitingOverride] / [spikeWorkingOverride] test maps (keyed by pane id,
 * toggled by the 3D `w` key), so the sidebar footer and the 3D warp-core boxes
 * agree on the numbers even while an override is in effect.
 *
 * @return the active worlds' tallies, in world order.
 * @see updateWorldStatusFooter
 */
private fun computeWorldStatusRows(): List<WorldStatusRow> {
    val cfg = latestWindowConfig ?: return emptyList()
    val states = runCatching { lunamuxClient.windowState.states.value }.getOrNull()
    val out = mutableListOf<WorldStatusRow>()
    for (world in cfg.worlds) {
        var working = 0
        var waiting = 0
        for (tab in world.tabs) {
            for (pane in tab.panes) {
                // A manual override (3D `w` key) wins over the live session state,
                // exactly as the 3D warp-core's computeWorldStatuses does; absent an
                // override the pane follows its real state.
                val paneId = pane.leaf.id
                val sessionId = pane.leaf.sessionId
                val isWaiting = spikeWaitingOverride[paneId] ?: (states?.get(sessionId) == "waiting")
                val isWorking = spikeWorkingOverride[paneId] ?: (states?.get(sessionId) == "working")
                if (isWaiting) waiting++ else if (isWorking) working++
            }
        }
        if (working > 0 || waiting > 0) {
            val accent = runCatching { argbToCss(resolvedThemeForWorld(world.themeSelection).accent) }
                .getOrNull() ?: "#4f8cf7"
            out.add(WorldStatusRow(world.name, accent, working, waiting))
        }
    }
    return out
}

/**
 * Fills a row's count element with colour-coded, gently-pulsing segments — "N
 * working" in reactor blue and "M waiting" in attention amber (the 3D warp
 * core's semantic working/waiting colours), joined by a muted separator, and
 * omitting a half that is zero (so a purely-waiting world reads just "M
 * waiting"). The colour + slow breathe draw the eye to live / blocked agents
 * without the heavier chrome of the sidebar's per-pane dots.
 *
 * The breathe opacity is driven by the shared global pulse loop
 * ([startPulse] / `pulseTick` in `WebStateActions`), NOT a CSS `@keyframes`
 * animation: [updateWorldStatusFooter] rebuilds this block with `innerHTML = ""`
 * on every state push, and a CSS animation on the fresh span would restart from
 * `opacity: 1` each time, making the breathe look erratic. Seeding each segment
 * off the shared clock keeps every world's breathe steady and in sync — the same
 * fix the per-pane `.tt-status-dot` beads use.
 *
 * @param host    the row's count container to populate (assumed empty).
 * @param working the world's working-pane count.
 * @param waiting its waiting-pane count.
 * @see startPulse
 */
private fun appendWorldStatusCounts(host: HTMLElement, working: Int, waiting: Int) {
    var needSep = false
    if (working > 0) {
        val w = document.createElement("span") as HTMLElement
        w.className = "world-status-working"
        w.textContent = "$working working"
        host.appendChild(w)
        startPulse(w, WORLD_WORKING_PULSE_PERIOD_MS, WORLD_WORKING_PULSE_FLOOR)
        needSep = true
    }
    if (waiting > 0) {
        if (needSep) {
            val sep = document.createElement("span") as HTMLElement
            sep.className = "world-status-sep"
            sep.textContent = " • "
            host.appendChild(sep)
        }
        val a = document.createElement("span") as HTMLElement
        a.className = "world-status-waiting"
        a.textContent = "$waiting waiting"
        host.appendChild(a)
        startPulse(a, WORLD_WAITING_PULSE_PERIOD_MS, WORLD_WAITING_PULSE_FLOOR)
    }
}

/**
 * Repaints the sidebar's world-status block from the current worlds + session
 * states. Called from [updateStateIndicators] — the shared hub every config /
 * state push already routes through — so the rows track agent activity live,
 * exactly like the per-pane sidebar dots beside them.
 *
 * When no world has any working/waiting pane, the block collapses to nothing
 * (via `world-status-empty`, which zeroes its padding) so an idle footer shows
 * only the usage bar. Otherwise it renders a "Worlds" title over one accent-dot
 * row per active world. The whole block is rebuilt each call (a handful of
 * worlds, pushed at most every few seconds) rather than diffed.
 *
 * @see computeWorldStatusRows
 */
internal fun updateWorldStatusFooter() {
    val host = worldStatusEl ?: return
    val rows = computeWorldStatusRows()
    if (rows.isEmpty()) {
        host.classList.add("world-status-empty")
        host.innerHTML = ""
        return
    }
    host.classList.remove("world-status-empty")
    host.innerHTML = ""

    val title = document.createElement("div") as HTMLElement
    title.className = "world-status-title"
    title.textContent = "Workspace agent status"
    host.appendChild(title)

    for (row in rows) {
        val rowEl = document.createElement("div") as HTMLElement
        rowEl.className = "world-status-row"

        val dot = document.createElement("span") as HTMLElement
        dot.className = "world-status-dot"
        dot.style.background = row.accent

        val name = document.createElement("span") as HTMLElement
        name.className = "world-status-name"
        name.textContent = row.name

        val count = document.createElement("span") as HTMLElement
        count.className = "world-status-count"
        appendWorldStatusCounts(count, row.working, row.waiting)

        rowEl.appendChild(dot)
        rowEl.appendChild(name)
        rowEl.appendChild(count)
        host.appendChild(rowEl)
    }
}

/* -------------------------------------------------------------------- */
/* Per-pane / per-tab session-state dot factories. The toolkit owns the  */
/* chrome (sidebar rows, tab strip, pane headers); Lunamux contributes */
/* `.tt-status-dot` `<span>` elements carrying `data-session` /           */
/* `data-tab-state` attributes. The factories paint each fresh element    */
/* from the current `WindowStateRepository` snapshot (the KMP-side runtime */
/* cache held by `LunamuxClient` — see                                 */
/* `client/.../WindowStateRepository.kt`) before returning, so a toolkit   */
/* rerender (theme toggle, sidebar toggle, drag-end rebuild) produces dots */
/* that already carry the live state — no blank frame waiting for the next */
/* `WindowEnvelope.State` push from the server (Lunamux#24). Live       */
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
 * runtime cache ([se.soderbjorn.lunamux.client.WindowStateRepository]),
 * which is updated whenever a `WindowEnvelope.State` arrives over the
 * window socket. Used by [buildStatusDot] / [buildTabStatusDot]
 * to paint fresh dot elements at construction time, so rerenders of
 * the toolkit chrome don't drop the visible state dot between the
 * rebuild and the next server push (Lunamux#24).
 *
 * The snapshot is passed through [applySignalOverrides] so the manual 3D signal
 * overrides (the `w` key) are reflected in construction-time dot seeds too, not
 * just the live [updateStateIndicators] repaint — keeping every session indicator
 * consistent with the 3D warp-core boxes and the 2D world-status footer even
 * across chrome rebuilds that carry no state push.
 *
 * Safe to call from any factory invoked after [bootViaToolkitShell]:
 * `lunamuxClient` is constructed in `main.kt` before `mountAppShell`,
 * so by the time the toolkit asks for a badge the runtime cache exists
 * (it may simply still be empty before the first server push, which is
 * the correct pre-connection appearance).
 */
private fun currentSessionStates(): Map<String, String?> =
    applySignalOverrides(lunamuxClient.windowState.states.value)

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
 * tab indicator between the rebuild and the next server push (Lunamux#24).
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
 * Mounts Lunamux's full UI through the darkness-toolkit's
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
    appShellHandle = mountAppShell(
        AppShellSpec(
            rootContainer = root,
            title = "Lunamux",
            persister = toolkitPersister,
            paneContent = { paneId -> mountPaneContent(paneId) },
            tabSource = lunamuxTabSource(
                scope = GlobalScope,
                windowState = lunamuxClient.windowState,
                socket = windowSocket,
            ),
            // World switcher (globe, left of the tabs) fed from the server's
            // worlds model. Switching worlds swaps the tab strip (the tab
            // source above renders the active world) and repaints to that
            // world's theme (see applyActiveWorldTheme on config push).
            worldSource = lunamuxWorldSource(
                scope = GlobalScope,
                windowState = lunamuxClient.windowState,
                socket = windowSocket,
            ),
            // Per-world pane layout: hand the toolkit each world's saved
            // layout on demand (from the cached server UI-settings snapshot)
            // so a world switch swaps a saved slice instead of pruning the
            // previous world's geometry. Default world reads/writes the flat
            // LAYOUT_STATE key (old-client + saved-data compat); see
            // [worldLayoutBlob] / [WorldLayoutKeys].
            worldLayoutProvider = { worldId -> worldLayoutBlob(worldId) },
            paneLabel = { _, paneId ->
                (findLeafDynamic(paneId)?.title as? String) ?: paneId
            },
            paneIcon = { _, paneId -> lunamuxPaneIcon(findLeafDynamic(paneId)) },
            paneActions = { _, paneId -> lunamuxPaneActions(paneId) },
            // Pane rename commits via WindowCommand.Rename. An empty
            // `newLabel` is meaningful here: PaneManager.renamePane clears
            // `customName` server-side and reverts the title to the
            // program-title / cwd fallback. `allowEmptyPaneRename = true`
            // below tells the toolkit's inline-rename input to forward an
            // empty commit instead of discarding it as a cancel, so users
            // can clear a name by emptying the field. The toolkit still
            // trims the value and skips a commit that just repeats the
            // current title.
            paneRename = { _, paneId, newLabel ->
                launchCmd(WindowCommand.Rename(paneId = paneId, title = newLabel))
            },
            allowEmptyPaneRename = true,
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
            // No per-tab aggregated status dot in the LEFT-PANE tab header
            // (issue #108): the status is already surfaced on each session
            // row in this sidebar (`paneSidebarBadge`), on the tab in the
            // strip (`tabTrailingBadge`), and in the pane title bar
            // (`paneHeaderBadge`), so an extra dot on the enclosing tab row
            // was redundant. Leaving `tabSidebarHeaderBadge` unset falls back
            // to the toolkit default (no badge). The tab-strip dot below is
            // deliberately kept.
            // Per-tab aggregated status DOT on the tab itself in the strip.
            // `updateStateIndicators` aggregates pane states per tab in its
            // `for (tab in cfg.tabs)` block and repaints every
            // `.tt-status-dot[data-tab-state='<tabId>']` element it finds.
            tabTrailingBadge = { tabId -> buildTabStatusDot(tabId) },
            // App-switcher button in the LEADING trailing-cluster slot — the toolkit
            // renders `extraTopbarBeforeStandard` immediately to the left of the
            // standard "+" New split-button (with a small divider between), so
            // this places the 3D-overview toggle right where the user asked:
            // just left of "+". Click routes to the same [toggleOverview3d] as
            // the ⌥⌘→ hotkey.
            extraTopbarBeforeStandard = buildList {
                add(buildOverview3dTopbarAction())
                add(buildWorld3dSpikeTopbarAction())
            },
            extraTopbarTrailing = buildList {
                add(buildNewsTopbarAction())
                add(buildAboutTopbarAction())
            },
            // App logo at the top of the left (sessions) sidebar and the
            // Claude usage rows pinned at its bottom.
            sidebarHeader = { buildSidebarLogo() },
            sidebarFooter = { buildSidebarFooter() },
            // No bottom status bar: Lunamux's only footer content (the
            // Claude usage rows, and the work-state dot in the AppLogo) lives
            // in the left-sidebar header/footer now, so the toolkit's bottom
            // bar would just be an empty strip. Opt out so the app frame ends
            // at the panes.
            showBottomBar = false,
            settingsHost = lunamuxThemeHost,
            // Opt-in to the toolkit's App Settings sidebar slot. Adds
            // the gear icon to the trailing topbar cluster (right of
            // the Appearance "Aa" gear) and renders [buildAppSettingsContent]
            // inside the sidebar when the user clicks it.
            appSettingsContent = { buildAppSettingsContent() },
            // Body of the dedicated "Keyboard shortcuts" sidebar. No topbar
            // button — opened via [openHotkeysSidebar] (the App Settings
            // "Hotkeys" button and the Electron "Keyboard Shortcuts" menu
            // item both route here through the toolkit handle).
            hotkeysContent = { buildHotkeysSidebarContent() },
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

    // Live cross-client layout sync (issue #58): when another client (e.g. a
    // phone on the same server) moves / resizes / maximizes / minimizes a pane
    // or applies a layout, the server broadcasts the updated LAYOUT_STATE blob
    // over /window. Feed it into the already-mounted shell so this view
    // reflects the change immediately instead of only on reload. The toolkit
    // no-ops when the blob matches its current state (so our own writes, echoed
    // back, don't churn) and never re-persists what it adopts.
    GlobalScope.launch {
        lunamuxClient.windowState.rawLayoutState.collect { el ->
            val json = when {
                el is JsonPrimitive && el.isString -> el.content
                el is JsonObject -> el.toString()
                else -> null
            } ?: return@collect
            // `rawLayoutState` tracks the flat LAYOUT_STATE key, which now
            // holds only the DEFAULT world's layout. Feeding it into the
            // toolkit while a NON-default world is active would overwrite that
            // world's geometry with the default world's — so only live-sync it
            // when the default world is the active one. Non-default worlds pick
            // up cross-client changes on the next switch via worldLayoutProvider
            // (live non-default sync is a deliberate follow-up).
            val cfg = latestWindowConfig
            val activeIsDefault = cfg == null ||
                cfg.worlds.isEmpty() ||
                cfg.activeWorldId == null ||
                cfg.activeWorldId == cfg.worlds.firstOrNull()?.id
            if (activeIsDefault) appShellHandle?.applyExternalLayoutState(json)
        }
    }

    // Install the reformat-button hover popup ("Automatic reformat (this
    // window)" / "(future windows)"). Uses document-level event delegation
    // so it survives the toolkit's chrome rebuilds without per-render
    // re-wiring — see [installReformatHoverPopup].
    installReformatHoverPopup()

    // Register Ctrl+Alt+R as a *configurable* hotkey that reformats the
    // active terminal — the keyboard equivalent of the pane-header Reformat
    // button. See [registerReformatHotkey].
    registerReformatHotkey()

    // Register the *configurable* hotkey that toggles the 3D tab overview
    // (carousel ring of live tab thumbnails — Overview3D.kt). The 3D switcher
    // is an experimental feature gated behind the `experimental3dSwitcher`
    // flag: the chord is always registered (so it appears in the Keyboard
    // Shortcuts sidebar and works the instant the flag is enabled), but stays
    // inert while the flag is off via the [toggleOverview3d] chokepoint.
    // Seed the topbar app-switcher button's visibility from the flag, and only pay
    // the WebGL context-creation / shader-compile prewarm cost when the
    // feature is actually enabled. See [registerOverview3dHotkey],
    // [applyOverview3dChromeVisibility], and [prewarmOverview3d].
    registerOverview3dHotkey()
    registerWorld3dSpikeHotkey() // ⌥⌘← opens the world spike (mirror of ⌥⌘→)
    registerWorldSwitchHotkey() // ⌥⌘O cycles to the next world in flat 2D mode
    applyOverview3dChromeVisibility()
    // Seed the topbar cube button's visibility from the experimental "3D world"
    // flag; the ⌥⌘← hotkey stays inert while off via the [toggleWorld3dSpike]
    // chokepoint. See [applyWorld3dSpikeChromeVisibility].
    applyWorld3dSpikeChromeVisibility()
    if (isExperimental3dSwitcherEnabled()) {
        kotlinx.browser.window.setTimeout({ prewarmOverview3d() }, 1500)
    }

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
