/**
 * Top-level singletons for the Termtastic web frontend: the
 * [TermtasticClient], the `/window` [WindowSocket], the
 * [AppBackingViewModel], and the small set of Electron / connection
 * detection flags.
 *
 * The bigger registries (terminals, DOM refs, per-pane VMs, rendering
 * bookkeeping) and the action helpers that operate on them have been
 * moved to companion files in this package:
 *  - [TerminalRegistry] — xterm instances + visibility refit
 *  - [DomRefRegistry] — cached layout DOM elements
 *  - [PaneStateRegistry] — file-browser / git per-pane caches
 *  - [RenderingState] — active tab, modal handlers, animation flags
 *  - [WebStateActions] — command dispatch, theme application, focus, …
 *
 * Splitting was a pure file-level reorganisation: every name remains
 * top-level in `se.soderbjorn.termtastic`, so consumers continue to
 * reference the unqualified `appVm`, `terminals`, `launchCmd(...)`, etc.
 *
 * @see main
 * @see connectWindow
 * @see renderConfig
 */
package se.soderbjorn.termtastic

import se.soderbjorn.darkness.core.Persister
import se.soderbjorn.termtastic.client.TermtasticClient
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SettingsPersister

// ── Core references (initialized in start()) ────────────────────────
internal lateinit var termtasticClient: TermtasticClient
internal lateinit var windowSocket: WindowSocket
internal lateinit var appVm: AppBackingViewModel

/**
 * Server-backed REST bridge that round-trips `/api/ui-settings` writes.
 * Lifted to a top-level reference so app-level UI code (e.g.
 * [buildAppSettingsContent]) can write directly without reaching through
 * private members of the view model. Initialised in `start()` together
 * with [appVm] and [toolkitPersister].
 */
internal lateinit var webSettingsPersister: SettingsPersister

/**
 * Toolkit-shape persistence adapter. Initialized in `start()` after
 * [appVm] and the server-backed [SettingsPersister] are constructed.
 * Wraps termtastic's existing flat-KV REST bridge so future
 * toolkit-driven persistence (e.g. a `mountAppShell` migration) can
 * read/write through the canonical [Persister] interface without
 * termtastic re-implementing storage.
 */
internal lateinit var toolkitPersister: Persister

/**
 * Mirror of the most recent server-pushed UI-settings payload as a
 * canonical-nested [JsonObject], read by [toolkitPersister].
 * Repopulated each time `applyServerUiSettings` lands a payload — see
 * [updateToolkitSettingsSnapshot] in `main.kt`.
 */
internal var toolkitSettingsSnapshot: kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.JsonObject(emptyMap())

// ── Electron / connection detection ─────────────────────────────────
internal var isElectronClient = false
internal var proto = "ws"
internal var authQueryParam = ""
internal var clientTypeAtStart = "Web"

/**
 * `host:port` authority every API/WebSocket request is sent to. Normally the
 * origin the bundle was served from (`window.location.host`).
 *
 * A `?backend=host:port` URL parameter overrides this, so a UI bundle served
 * from a throwaway static server can drive a *different* live server — e.g.
 * your production instance on `127.0.0.1:8443` — giving you this branch's
 * renderer on top of your real, live sessions (see
 * `scripts/run-electron-to-prod-server.sh`). When the param is absent this stays
 * `window.location.host` and every code path behaves exactly as before.
 * Set once in `main.kt`'s `start()`; read by the raw pty/agent socket URLs.
 */
internal var backendHost = ""

// ── Default chrome / monospaced fonts ───────────────────────────────
// When the user hasn't picked a font/size we default the sidebar, tab bar and
// monospaced (terminal) fonts to JetBrains Mono, and the chrome size to 12px.
// This applies to every client (web + Electron). The default is a read-time
// FALLBACK only — it is never written back to the server, so it never
// overwrites a user's explicit choice; the moment the user picks a font/size
// that choice is persisted and takes over.

/** Font-preset key used as the default for chrome + monospaced fonts. */
internal const val DEFAULT_FONT_KEY = "jetbrainsMono"

/** Default sidebar / tab bar font size (px). */
internal const val DEFAULT_CHROME_SIZE = 12

/** Returns [persisted] if the user has chosen a font, else the default key. */
internal fun effectiveFontKey(persisted: String?): String? =
    persisted ?: DEFAULT_FONT_KEY

/** Returns [persisted] if the user has chosen a chrome size, else the default (12). */
internal fun effectiveChromeSize(persisted: Int?): Int? =
    persisted ?: DEFAULT_CHROME_SIZE

/**
 * Whether this page runs in demo mode (opened on `/demo`, `?demo`, or
 * `#demo` — see [detectDemoUrl]). When `true` the whole client is backed by
 * the in-process [se.soderbjorn.termtastic.client.demo.DemoServer]: no
 * WebSocket or REST request is ever made, terminal panes attach to the demo
 * sessions directly ([connectDemoPane]), and settings writes are dropped.
 */
internal var isDemoClient = false
