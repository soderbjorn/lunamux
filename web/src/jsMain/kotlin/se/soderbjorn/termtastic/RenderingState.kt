/**
 * Rendering-only client-local state: which tab is active, the most recent
 * (synchronously set) `WindowConfig` snapshot, collapsed-tab and
 * collapsed-git-section bookkeeping, and DOM bookkeeping for the modal
 * overlays (settings panel, theme manager, pane-type modal).
 *
 * Lives next to [WebState] in the same package so unqualified call sites
 * still resolve.
 */
package se.soderbjorn.termtastic

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** Currently-selected tab id, or null before the first config arrives. */
internal var activeTabId: String? = null

/**
 * The most recent config received over the `/window` socket, set
 * synchronously by `renderConfig` before the AppBackingViewModel coroutine
 * gets a chance to process the StateFlow update. Read by code that needs
 * the freshest tabs/focus state without lag.
 */
internal var currentConfig: dynamic = null

/**
 * Typed mirror of [currentConfig], kept in lockstep by the same writers.
 * Use this for new code that needs to read pane/leaf state in a type-safe
 * way — call sites that walk the dynamic snapshot pre-date the typed
 * `WindowConfig` being available on the web side and should migrate to
 * this when touched.
 */
internal var latestWindowConfig: se.soderbjorn.termtastic.WindowConfig? = null

/**
 * True while a *programmatic* focus restoration is dispatching — i.e. a
 * `term.focus()` call whose purpose is to mirror already-known server
 * state back onto the DOM (`refocusActivePane` after a config push, the
 * `focusout` safety net, `focusFirstPaneInActiveTab`). The resulting
 * `focusin` event runs synchronously inside the `focus()` call, so
 * setting this around the call is race-free.
 *
 * [markPaneFocused] consults it to decide whether to send
 * [WindowCommand.SetFocusedPane]: only genuine user gestures (clicks,
 * typing into a pane) may emit the command. Programmatic restorations
 * must never emit it — an equality check against [currentConfig] is NOT
 * sufficient because `refocusActivePane` defers to the next animation
 * frame, and under back-to-back config pushes the snapshot has already
 * advanced to the *other* in-flight value by the time `focusin` fires.
 * That race produced a self-sustaining SetFocusedPane ping-pong: two
 * panes' configs alternating ~12×/s, each push re-rendering the whole
 * chrome, freezing the UI for seconds (worst in the embedded web demo).
 */
internal var suppressFocusCommands = false

/** Tab ids the user has collapsed in the sidebar tree. */
internal val collapsedTabs = HashSet<String>()

/** Tab ids that existed before the latest re-render. */
internal val previousTabIds = HashSet<String>()

/** Pending tab-flip animation map (tab id → start time), or null when idle. */
internal var pendingTabFlip: Map<String, Double>? = null

/** Whether this is the very first render after page load. */
internal var firstRender = true

/** Last seen per-session state map; used to detect notification transitions. */
internal val previousSessionStates = HashMap<String, String?>()

/**
 * Theme manager (right sidebar) root element. The settings sidebar is
 * now owned by the toolkit; mutual exclusion is enforced by the gear /
 * palette buttons in `mountAppShell`, not by termtastic.
 */
internal var themeManagerPanel: HTMLElement? = null

/** ESC key handler installed when the theme manager is open. */
internal var themeManagerEscHandler: ((Event) -> Unit)? = null

/** Pane-type modal preview entries. */
internal val previewEntries = mutableListOf<dynamic>()

/** ESC key handler installed when the pane-type modal is open. */
internal var modalEscHandler: ((Event) -> Unit)? = null

/** Git pane sections collapsed by the user (e.g. unstaged, staged, …). */
internal val collapsedGitSections = HashSet<String>()
