/**
 * Ktor server entry point and module wiring for Termtastic.
 *
 * This file is now a thin orchestrator:
 *  - [main] brings up the SQLite persistence layer, restores the window
 *    layout, kicks off the persistence/scrollback/state-poller coroutines
 *    via [ServerInitializer] helpers, launches the Claude usage monitor,
 *    and starts the Netty HTTP/WebSocket server.
 *  - [Application.module] installs Ktor plugins and mounts route
 *    extensions defined in [PtyRoutes], [WindowRoutes], plus static
 *    files.
 *
 * Route, dispatch, and PTY-session logic lives in their own files.
 *
 * @see ServerInitializer
 * @see PtyRoutes
 * @see WindowRoutes
 * @see TerminalSessionManager
 * @see WindowState
 */
package se.soderbjorn.termtastic

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.mcp.mcpRoutes
import se.soderbjorn.termtastic.persistence.AppPaths
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.tls.CertStore
import se.soderbjorn.termtastic.ui.SettingsDialog
import java.io.File

/**
 * WebSocket keepalive cadence: a ping every [PING_PERIOD_MS]; a peer that
 * hasn't answered within [PING_TIMEOUT_MS] is considered dead and its
 * connection is closed (which also evicts its terminal-size entry from the
 * per-session min() aggregation).
 */
private const val PING_PERIOD_MS = 20_000L
private const val PING_TIMEOUT_MS = 40_000L

/**
 * Read the "use program-set terminal titles" opt-in flag
 * ([TERMINAL_PROGRAM_TITLE_KEY]) out of a UI-settings snapshot, tolerating
 * both JSON-boolean and stringified-"true" shapes (writes can land either
 * way — see `snapshotBoolean` in the web client). Called by [main] when
 * mapping [SettingsRepository.uiSettings] into the live
 * [TerminalSessions.programTitlesEnabled] flag flow.
 *
 * @receiver a UI-settings snapshot from [SettingsRepository.uiSettings].
 * @return `true` when the user has enabled program-set titles.
 */
internal fun JsonObject.terminalProgramTitleEnabled(): Boolean {
    val el = this[TERMINAL_PROGRAM_TITLE_KEY] as? JsonPrimitive ?: return false
    el.booleanOrNull?.let { return it }
    return el.isString && el.content == "true"
}

/**
 * Application entry point. Initialises the persistence layer, restores the
 * window layout, starts background coroutines, launches the Claude usage
 * monitor, and starts the Netty HTTP/WebSocket server.
 */
fun main() {
    // Persistence first so the loaded window config (if any) is the very
    // first value seen by the rest of the app — that way no throwaway PTYs
    // are created for a default layout we'd immediately discard.
    val repo = SettingsRepository(AppPaths.databaseFile())
    WindowState.initialize(repo)

    val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Opt-in program-set terminal titles (OSC 0/2). Mirror the toggle into
    // TerminalSessions' stable flag flow: per-session watchers re-collect on
    // enable (so the current title applies immediately, no restart), and an
    // off value sweeps every stored program title so panes revert to
    // cwd-based names (including scrubbing titles persisted from an earlier
    // enabled run when the app starts disabled).
    persistenceScope.launch {
        repo.uiSettings
            .map { it.terminalProgramTitleEnabled() }
            .distinctUntilChanged()
            .collect { enabled ->
                TerminalSessions.programTitlesEnabled.value = enabled
                if (!enabled) WindowState.clearProgramTitles()
            }
    }

    installWindowConfigPersister(persistenceScope, repo)
    val scrollbackSaver = installScrollbackSaver(persistenceScope, repo)
    val sessionStates = installSessionStatePoller(persistenceScope)
    val sharedThemesWatch = installSharedThemesWatcher(repo)

    val usageMonitor = ClaudeUsageMonitor()
    SettingsDialog.usageMonitor = usageMonitor
    if (repo.isClaudeUsagePollEnabled()) usageMonitor.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        // Best-effort final flush so a clean Ctrl-C captures any unsaved
        // changes that landed inside the debounce window.
        runCatching {
            runBlocking {
                repo.saveWindowConfig(WindowState.config.value.withBlankSessionIds())
                scrollbackSaver.saveAll(force = true)
            }
        }
        usageMonitor.stop()
        runCatching { sharedThemesWatch.close() }
        persistenceScope.cancel()
        repo.close()
    })

    val port = System.getProperty("termtastic.port")?.toIntOrNull()
        ?: SERVER_TLS_PORT
    SettingsDialog.setListeningPort(port)

    // Generate-or-load the self-signed TLS keystore before binding the
    // listener. Native clients pin the leaf cert by SHA-256 (TOFU on first
    // connect, verify thereafter); browsers fall back to the standard
    // self-signed-warning interstitial; the bundled Electron app silently
    // accepts the loopback cert via the fingerprint sidecar handler.
    val tlsBundle = CertStore.ensureKeystore()
    // Surface the fingerprint in the device-approval dialog so the user can
    // cross-check what their newly-approved client is about to pin.
    se.soderbjorn.termtastic.auth.DeviceAuth.serverCertFingerprintHex = tlsBundle.sha256Fingerprint

    // Bind HTTPS only on all interfaces. The default-off remote policy is
    // enforced inside DeviceAuth.authorize, which rejects non-loopback
    // requests until the user opts in via the settings dialog. There is no
    // plain-HTTP fallback — every connection is encrypted.
    val server = embeddedServer(
        factory = Netty,
        environment = applicationEnvironment {},
        configure = {
            sslConnector(
                keyStore = tlsBundle.keystore,
                keyAlias = tlsBundle.alias,
                keyStorePassword = { tlsBundle.password.copyOf() },
                privateKeyPassword = { tlsBundle.password.copyOf() },
            ) {
                this.host = "0.0.0.0"
                this.port = port
            }
        },
        module = { module(repo, sessionStates, usageMonitor, scrollbackSaver) }
    )

    if (java.awt.GraphicsEnvironment.isHeadless()) {
        server.start(wait = true)
    } else {
        // Non-headless: start the Ktor server in the background and let
        // Compose Desktop own the main thread (required on macOS for the
        // AppKit run loop).
        server.start(wait = false)
        try {
            androidx.compose.ui.window.application(exitProcessOnExit = false) {
                SettingsDialog.renderIfShowing()
                se.soderbjorn.termtastic.auth.DeviceAuth.renderApprovalDialogIfShowing()
            }
        } catch (t: Throwable) {
            LoggerFactory.getLogger("Application")
                .error("Compose application loop crashed; server will stay up headless", t)
        }
        Thread.currentThread().join()
    }
}

/**
 * Ktor application module: installs plugins and mounts the route
 * extensions defined in [PtyRoutes], [WindowRoutes], and [AdminRoutes].
 *
 * @param settingsRepo    the SQLite-backed settings store, shared across all routes
 * @param sessionStates   flow of per-session AI assistant states, polled every 3 s
 * @param usageMonitor    the Claude CLI usage monitor whose data is pushed to clients
 * @param scrollbackSaver the periodic scrollback flusher; reused by `/admin/shutdown`
 *                        so a graceful stop produces the same on-disk result as the
 *                        JVM shutdown hook
 */
fun Application.module(
    settingsRepo: SettingsRepository,
    sessionStates: MutableSharedFlow<Map<String, String?>>,
    usageMonitor: ClaudeUsageMonitor,
    scrollbackSaver: ScrollbackSaver,
) {
    install(ContentNegotiation) { json() }
    install(WebSockets) {
        maxFrameSize = Long.MAX_VALUE
        masking = false
        // Keepalive: without pings, a mobile client whose TCP connection
        // died while the phone slept stays "attached" until the kernel
        // times the connection out (~15 min) — keeping its last terminal
        // size pinned in the per-session min() aggregation and its socket
        // handler blocked. Pinging every 20 s detects dead peers within
        // ~40 s and tears their sessions down server-side.
        pingPeriodMillis = PING_PERIOD_MS
        timeoutMillis = PING_TIMEOUT_MS
    }

    val webDistPath = System.getProperty("termtastic.webDist")

    routing {
        if (webDistPath != null) {
            // Dev flow: serve from the on-disk web dist so edits hot-reload without re-jarring.
            staticFiles("/", File(webDistPath)) {
                default("index.html")
            }
        } else {
            // Packaged flow: the web bundle is embedded in the server jar under /web.
            staticResources("/", "web") {
                default("index.html")
            }
        }
        uiSettingsRoutes(settingsRepo)
        ptyRoutes(settingsRepo)
        agentRoutes(settingsRepo)
        windowRoutes(settingsRepo, sessionStates, usageMonitor)
        adminRoutes(settingsRepo, scrollbackSaver)
        // MCP endpoint (localhost-only, MCP-token gated) — see mcp/McpRoutes.kt.
        mcpRoutes(settingsRepo, usageMonitor)
    }
}
