/* TermtasticTabSource.kt (jsMain)
 * Adapter that exposes termtastic's server-driven [WindowConfig] flow
 * as a darkness-toolkit [TabSource]. The toolkit's `mountAppShell`
 * subscribes to the push channel and renders whatever tabs / panes
 * the server reports; user gestures (select, close, rename, reorder)
 * are forwarded as [WindowCommand]s through the existing [WindowSocket].
 *
 * Pane *geometry* (position, size, z-order, maximized state) is
 * toolkit-owned post-refactor — persisted under
 * `PersistKeys.LAYOUT_STATE` through termtastic's
 * `SettingsPersisterAdapter`, which round-trips to the server's flat-KV
 * so cross-device layouts continue to sync. The snapshot we push only
 * carries pane *identity*; the toolkit takes it from there. */
package se.soderbjorn.termtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import se.soderbjorn.darkness.web.shell.PaneAddMenuItem
import se.soderbjorn.darkness.web.shell.PaneSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabListSnapshot
import se.soderbjorn.darkness.web.shell.TabSnapshotEntry
import se.soderbjorn.darkness.web.shell.TabSource
import se.soderbjorn.darkness.web.util.PaneSlotAssigner
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository
import se.soderbjorn.termtastic.WindowCommand

/**
 * Inline SVGs used by the "New pane" hover dropdown. Same glyphs that
 * the legacy [showPaneTypeModal] card grid used — kept here (where
 * the dropdown is wired) now that the modal card grid is gone.
 *
 * The stroke width / viewBox match termtastic's existing 32-unit pane
 * icons so the dropdown rows read consistently with the rest of the
 * chrome.
 */
private const val NEW_PANE_TERMINAL_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="26" height="22" rx="2"/><polyline points="9,13 13,16 9,19"/><line x1="15" y1="20" x2="22" y2="20"/></svg>"""
private const val NEW_PANE_LINK_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13 19a5 5 0 0 0 7.07 0l4-4a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M19 13a5 5 0 0 0-7.07 0l-4 4a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>"""
private const val NEW_PANE_FILE_BROWSER_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9 a1 1 0 0 1 1-1 h7 l2 3 h13 a1 1 0 0 1 1 1 v13 a1 1 0 0 1 -1 1 H5 a1 1 0 0 1 -1 -1 Z"/><line x1="9" y1="17" x2="23" y2="17"/><line x1="9" y1="21" x2="19" y2="21"/></svg>"""
private const val NEW_PANE_GIT_SVG =
    """<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="10" cy="8" r="2.5"/><circle cx="10" cy="24" r="2.5"/><circle cx="22" cy="16" r="2.5"/><line x1="10" y1="10.5" x2="10" y2="21.5"/><path d="M10 10.5 C10 16 16 16 19.5 16"/></svg>"""

/**
 * Process-global pane-slot assigner. Sticky 1-based indices used to render
 * the encircled-digit/letter badge in pane headers and sidebar rows. The
 * snapshot collector below keeps this in sync with the server-pushed pane
 * set on every config emission; the toolkit reads it via the
 * `paneIndex` callback wired in [bootViaToolkitShell].
 */
internal val termtasticPaneAssigner: PaneSlotAssigner = PaneSlotAssigner()

/**
 * Builds a [TabSource] backed by termtastic's server-pushed
 * [WindowStateRepository.config] flow.
 *
 * On `subscribe`, this collects the config flow on [scope]; every
 * non-null emission is mapped to a [TabListSnapshot] and pushed into
 * the toolkit. User gestures fire [WindowCommand]s through [socket].
 *
 * @param scope coroutine scope for the collector (typically the same
 *   one termtastic runs the rest of its WebSocket loop on).
 * @param windowState the live server-state repository.
 * @param socket the open [WindowSocket] for sending commands back.
 * @return a [TabSource] suitable for [se.soderbjorn.darkness.web.shell.AppShellSpec.tabSource].
 */
fun termtasticTabSource(
    scope: CoroutineScope,
    windowState: WindowStateRepository,
    socket: WindowSocket,
): TabSource = TabSource(
    subscribe = { push ->
        scope.launch {
            windowState.config.collect { config ->
                if (config == null) return@collect
                // Refresh the synchronous dynamic snapshot before the toolkit
                // re-renders. mountPaneContent / findLeafDynamic / paneIcon /
                // paneActions all read `currentConfig` synchronously while the
                // toolkit's pane factory runs. The WindowEnvelope.Config path
                // in WindowConnection.renderConfig also sets `currentConfig`,
                // but it runs on a different coroutine and frequently loses
                // the race against this collector — leaving the very first
                // paneContent call to return its `[pane … — booting]`
                // placeholder, which AppShellMount.paneContentCache then
                // pins forever.
                val json = windowJson.encodeToString(WindowConfig.serializer(), config)
                currentConfig = js("JSON.parse(json)")
                latestWindowConfig = config
                // Refocus after the toolkit's `push` (below) is handled
                // unconditionally by `WindowConnection.refocusActivePane`,
                // which runs on every config render via `renderConfig`.
                // See plans/CD-FOCUS-LOSS-PLAN-V2.md for the rationale —
                // the sticky-id gated path that used to live here was the
                // root cause of cd-driven focus loss.
                // Pass hidden tabs through to the toolkit instead of
                // filtering them here — the toolkit's tab strip skips
                // `isHidden` entries on its own, but its overflow menu
                // needs them to populate the "Unlisted tabs" section
                // (the only re-activate path with no inline strip
                // entry). Hidden-from-sidebar tabs likewise need to
                // reach the toolkit so it can decide whether to render
                // the row. The two server flags map 1:1 onto the
                // toolkit snapshot fields.
                // Reconcile the sticky pane-slot assigner with the live
                // pane set BEFORE pushing the snapshot, so the toolkit's
                // `paneIndex` lookups during the resulting render see
                // up-to-date slots. Order: across all tabs (including
                // hidden ones) in tab order, panes in their tab order —
                // matches the canonical global enumeration the user sees
                // when scanning the sidebar top-to-bottom.
                val livePaneIds = config.tabs.flatMap { tab ->
                    tab.panes.map { it.leaf.id }
                }
                termtasticPaneAssigner.syncTo(livePaneIds)
                push(
                    TabListSnapshot(
                        tabs = config.tabs.map { tab ->
                            TabSnapshotEntry(
                                id = tab.id,
                                label = tab.title,
                                panes = tab.panes.map { pane ->
                                    PaneSnapshotEntry(id = pane.leaf.id)
                                },
                                activePaneId = tab.focusedPaneId,
                                isHidden = tab.isHidden,
                                isHiddenFromSidebar = tab.isHiddenFromSidebar,
                            )
                        },
                        activeTabId = config.activeTabId,
                    )
                )
                // Paint freshly-mounted status badges with the most
                // recent session-states map. The toolkit's `push` is
                // synchronous — by the time it returns, sidebar rows,
                // tab-strip badges, and pane-header badges with the
                // expected `data-session` / `data-tab-state` attributes
                // are in the DOM. Without this call, brand-new badges
                // (after a tab/pane add or app first paint) sit empty
                // until the next 3-second server poll.
                //
                // Read from the authoritative `windowState.states` (the same
                // source the per-pane dot builders use via
                // `currentSessionStates`) rather than the `appVm` mirror, which
                // can still be empty here — e.g. in demo mode the single fixture
                // state seed is otherwise missed, so this call would feed
                // `updateAppLogoState` an empty map and reset the aggregate
                // AppLogo dot to idle while the rows correctly show
                // working/waiting. Mirrors the fix in `renderConfig`.
                updateStateIndicators(termtasticClient.windowState.states.value)
            }
        }
    },
    onSelect = { id -> scope.launch { socket.send(WindowCommand.SetActiveTab(id)) } },
    onAdd = { scope.launch { socket.send(WindowCommand.AddTab) } },
    onClose = { id -> scope.launch { socket.send(WindowCommand.CloseTab(id)) } },
    onRename = { id, label -> scope.launch { socket.send(WindowCommand.RenameTab(id, label)) } },
    onReorder = { sourceId, targetId, before ->
        scope.launch {
            socket.send(WindowCommand.MoveTab(tabId = sourceId, targetTabId = targetId, before = before))
        }
    },
    // Sidebar pane click → activate the tab if needed, focus the pane,
    // and raise it to the top of the tab's z-order so the click brings
    // the pane to the front (same expectation users have when clicking
    // a window in any tiling/floating UI). `SetFocusedPane` alone keeps
    // the pane buried under whatever was on top before the click.
    onPaneSelect = { tabId, paneId ->
        scope.launch {
            socket.send(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
            socket.send(WindowCommand.RaisePane(paneId = paneId))
        }
    },
    // Toolkit pane × → close the pane on the server. Linked-session
    // confirmation already ran in the toolkit's confirm dialog before
    // this fires; routing through `WindowCommand.Close` matches the
    // legacy chrome's behaviour for the lone-pane case.
    onPaneClose = { _, paneId ->
        scope.launch { socket.send(WindowCommand.Close(paneId = paneId)) }
    },
    // Toolkit "+ pane" — primary (click) action: add a plain terminal
    // immediately. The secondary types (terminal link / file browser /
    // git) live on the hover dropdown wired via [paneAddMenuItems]
    // below, so the common case (open a terminal) stays a single click
    // and the modal card grid is no longer the default path.
    onPaneAdd = { tabId ->
        launchCmd(WindowCommand.AddPaneToTab(
            tabId = tabId,
            cwd = cwdForNewPaneIn(tabId),
        ))
    },
    // Hover dropdown attached to the "+ pane" icon. Lists the four
    // pane flavours termtastic supports (same icons the legacy modal
    // used) so users can land on any flavour without going through a
    // popup. The default click already covers Terminal, but we keep
    // it in the dropdown for discoverability; selecting it from the
    // dropdown commits the same command. "Terminal link" still needs
    // its existing live-preview submodal — we open it directly on
    // click, bypassing the type-card view.
    paneAddMenuItems = { tabId ->
        // Resolve the new pane's cwd at *click* time (inside the
        // per-item closures), not when the menu was built. Hover
        // dropdowns can sit open for a while; the live config can
        // shift focus / track a `cd` underneath them. The user
        // expects the cwd they see in the title bar at the moment
        // they click, not at the moment they hovered.
        // File Browser and Git are gated behind two experimental flags
        // ([isExperimentalFileBrowserEnabled] / [isExperimentalGitViewEnabled]).
        // The flags are persisted via the App Settings sidebar and read
        // from the in-memory settings snapshot on every menu open — so
        // toggling a flag is visible the very next hover without any
        // shell rerender.
        buildList {
            add(PaneAddMenuItem(
                id = "terminal",
                label = "New Terminal",
                iconHtml = NEW_PANE_TERMINAL_SVG,
            ) {
                launchCmd(WindowCommand.AddPaneToTab(tabId = tabId, cwd = cwdForNewPaneIn(tabId)))
            })
            add(PaneAddMenuItem(
                id = "terminal-link",
                label = "New Terminal link",
                iconHtml = NEW_PANE_LINK_SVG,
            ) {
                openTerminalLinkPicker(emptyTabId = tabId, anchorPaneId = savedFocusedPaneId(tabId))
            })
            if (isExperimentalFileBrowserEnabled()) {
                add(PaneAddMenuItem(
                    id = "file-browser",
                    label = "New File Browser",
                    iconHtml = NEW_PANE_FILE_BROWSER_SVG,
                ) {
                    launchCmd(WindowCommand.AddFileBrowserToTab(tabId = tabId, cwd = cwdForNewPaneIn(tabId)))
                })
            }
            if (isExperimentalGitViewEnabled()) {
                add(PaneAddMenuItem(
                    id = "git",
                    label = "New Git",
                    iconHtml = NEW_PANE_GIT_SVG,
                ) {
                    launchCmd(WindowCommand.AddGitToTab(tabId = tabId, cwd = cwdForNewPaneIn(tabId)))
                })
            }
        }
    },
    // Tab-visibility toggles surfaced from the toolkit's overflow menu
    // ("Hide / Show in tab bar", "Hide / Show in side bar"). Both
    // flags live on the server's TabConfig and are flipped by their
    // own WindowCommands; the server pushes a refreshed WindowConfig
    // back through the windowState.config flow above, which the
    // collector turns into a fresh snapshot for the toolkit.
    onSetHidden = { id, hidden ->
        scope.launch { socket.send(WindowCommand.SetTabHidden(tabId = id, hidden = hidden)) }
    },
    onSetHiddenFromSidebar = { id, hidden ->
        scope.launch {
            socket.send(WindowCommand.SetTabHiddenFromSidebar(tabId = id, hidden = hidden))
        }
    },
    // Pane geometry callbacks live in the toolkit now — see file kdoc.
    // The server's per-pane WindowCommands (ToggleMaximized,
    // SetPanePosition, SetPaneSize) are no longer the route for these
    // gestures; the toolkit persists geometry through the supplied
    // [Persister] which round-trips to the server's flat-KV.
)
