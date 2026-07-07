/**
 * `/window` WebSocket and `/api/ui-settings` REST routes.
 *
 * Holds the [WindowConnectionContext] (per-socket reply channel and owned
 * git watchers), the [handleWindowCommand] dispatcher that translates
 * inbound [WindowCommand]s into [WindowState] mutations and unicast
 * envelopes, and the small set of envelope-building helpers used by the
 * dispatcher.
 *
 * Auth helpers — [readAuthToken] and [readClientInfo] — live here so both
 * the `/pty/{id}` and `/window` routes can share them without dragging in
 * the rest of the routing surface.
 *
 * @see WindowState
 * @see DeviceAuth
 */
package se.soderbjorn.termtastic

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.SettingsRepository
import se.soderbjorn.termtastic.ui.SettingsDialog
import java.util.concurrent.ConcurrentHashMap

/**
 * Read the device-auth token from any of three places, in order:
 *  1. `termtastic_auth` cookie
 *  2. `auth` query parameter
 *  3. `X-Termtastic-Auth` header
 */
internal fun ApplicationCall.readAuthToken(): String? {
    val cookie = request.cookies["termtastic_auth"]
    if (!cookie.isNullOrBlank()) return cookie
    val query = request.queryParameters["auth"]
    if (!query.isNullOrBlank()) return query
    val header = request.header("X-Termtastic-Auth")
    if (!header.isNullOrBlank()) return header
    return null
}

/**
 * Build a [DeviceAuth.ClientInfo] from the incoming request.
 *
 * Uses `origin.remoteAddress` (the raw socket IP), NOT `origin.remoteHost`:
 * on the Netty engine `remoteHost` reverse-DNS-resolves the client address,
 * and for LAN peers without a PTR record (e.g. a phone connecting via a
 * link-local IPv6 address) that lookup blocks ~5 s on every new connection —
 * window socket, each pty socket, each API call — which made mobile connects
 * and pane opens crawl while localhost clients stayed fast (issue #93).
 */
internal fun ApplicationCall.readClientInfo(): DeviceAuth.ClientInfo {
    fun first(headerName: String, queryName: String): String? {
        val q = request.queryParameters[queryName]
        if (!q.isNullOrBlank()) return q
        val h = request.header(headerName)
        if (!h.isNullOrBlank()) return h
        return null
    }
    val type = first("X-Termtastic-Client-Type", "clientType")?.takeIf { it.isNotBlank() } ?: "Unknown"
    val hostname = first("X-Termtastic-Client-Host", "clientHost")
    val selfIp = first("X-Termtastic-Client-Ip", "clientIp")
    val version = first("X-Termtastic-Client-Version", "clientVersion")
    return DeviceAuth.ClientInfo(
        type = type,
        hostname = hostname,
        selfReportedIp = selfIp,
        remoteAddress = request.origin.remoteAddress,
        version = version,
    )
}

/**
 * Lowest client app version whose deserializer knows the `agent` [LeafContent]
 * kind. Older clients have no such subtype registered, so decoding a config
 * that contains one throws and (via the client's whole-frame `runCatching`)
 * silently drops the entire update — freezing the client on its last layout.
 * We therefore strip agent panes from configs sent to clients below this.
 */
private val MIN_AGENT_PANE_VERSION = intArrayOf(1, 5, 0)

/**
 * Whether a client reporting [version] (a `"major.minor.patch"` string) is new
 * enough to render agent-console panes.
 *
 * Called once per `/window` connection to decide whether that socket receives
 * agent panes and agent notifications. Released clients before 1.5 never send a
 * version, so a `null`/blank/unparseable value is treated as *incapable* — the
 * absence of a version is itself the "old client" signal.
 *
 * @param version the self-reported client version, or `null`.
 * @return `true` if [version] parses to at least [MIN_AGENT_PANE_VERSION].
 * @see DeviceAuth.ClientInfo.version
 */
internal fun clientSupportsAgentPanes(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    // Split on '.', '-', '+' so suffixes like "1.5.0-beta" compare by numbers.
    val parts = version.trim().split('.', '-', '+')
    fun component(i: Int): Int =
        parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    for (i in 0 until MIN_AGENT_PANE_VERSION.size) {
        val c = component(i)
        if (c != MIN_AGENT_PANE_VERSION[i]) return c > MIN_AGENT_PANE_VERSION[i]
    }
    return true // exactly equal → supported
}

/**
 * Return a copy of this config with every agent-console pane removed, for
 * clients too old to deserialize the `agent` [LeafContent] kind.
 *
 * Panes live in a flat per-tab list (there is no split tree), so removal is a
 * clean filter — no geometry surgery. Removing the pane outright (rather than
 * substituting a placeholder) is deliberate: it keeps the old client's whole
 * config sync working, where a single unknown `"kind"` would otherwise fail the
 * entire envelope decode. Agent panes are ephemeral anyway, so an old client
 * simply never sees them.
 *
 * @return the config unchanged when it holds no agent panes; otherwise a copy
 *   with them filtered out of every tab.
 * @see clientSupportsAgentPanes
 */
private fun WindowConfig.withoutAgentPanes(): WindowConfig =
    copy(tabs = tabs.map { tab ->
        val kept = tab.panes.filterNot { it.leaf.content is AgentContent }
        if (kept.size == tab.panes.size) tab else tab.copy(panes = kept)
    })

/**
 * Per-`/window`-connection state passed into [handleWindowCommand]. Holds
 * the unicast reply channel (so e.g. a `fileBrowserDir` reply lands only
 * on the socket that asked) and the connection's owned watcher handles,
 * keyed by paneId.
 */
internal class WindowConnectionContext(
    val send: suspend (WindowEnvelope) -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    val settingsRepo: SettingsRepository,
    val usageMonitor: ClaudeUsageMonitor,
) {
    private val gitWatchers = ConcurrentHashMap<String, GitWatchHandle>()

    fun setGitWatcher(paneId: String, handle: GitWatchHandle?) {
        val previous = if (handle == null) gitWatchers.remove(paneId) else gitWatchers.put(paneId, handle)
        previous?.close()
    }

    fun cancelGitWatcher(paneId: String) {
        gitWatchers.remove(paneId)?.close()
    }

    fun closeAll() {
        for (handle in gitWatchers.values) handle.close()
        gitWatchers.clear()
    }
}

/**
 * Mount the `GET` and `POST` `/api/ui-settings` REST endpoints on this
 * [Route]. Both routes gate on [DeviceAuth.authorize].
 */
internal fun Route.uiSettingsRoutes(settingsRepo: SettingsRepository) {
    get("/api/ui-settings") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo)) {
            // Merge in the synthesized legacy compat keys so pre-revamp mobile
            // apps still render approximately right; new apps read the v2 keys.
            DeviceAuth.Decision.APPROVED -> call.respond(settingsRepo.getUiSettingsWithLegacy())
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
    post("/api/ui-settings") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo)) {
            DeviceAuth.Decision.APPROVED -> {
                val incoming = call.receive<JsonObject>()
                settingsRepo.mergeUiSettings(incoming)
                call.respond(HttpStatusCode.NoContent)
            }
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

/**
 * Mount the `/window` WebSocket on this [Route]. Pushes the live
 * [WindowConfig], per-session AI state, Claude usage data, and UI
 * settings; receives [WindowCommand]s via [handleWindowCommand].
 */
internal fun Route.windowRoutes(
    settingsRepo: SettingsRepository,
    sessionStates: MutableSharedFlow<Map<String, String?>>,
    usageMonitor: ClaudeUsageMonitor,
) {
    webSocket("/window") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        // Two-phase auth: the fast path resolves known/denied tokens
        // without blocking. Unknown tokens require the interactive
        // dialog, so we send a PendingApproval frame first — this lets
        // the client show "Waiting for approval…" instead of timing out.
        val decision = DeviceAuth.checkFastPath(token, info, settingsRepo)
            ?: run {
                val pending = windowJson.encodeToString<WindowEnvelope>(
                    WindowEnvelope.PendingApproval("Waiting for approval on the server…")
                )
                send(Frame.Text(pending))
                DeviceAuth.authorize(token, info, settingsRepo)
            }
        when (decision) {
            DeviceAuth.Decision.APPROVED -> Unit
            DeviceAuth.Decision.REJECTED -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                return@webSocket
            }
            DeviceAuth.Decision.HEADLESS -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                return@webSocket
            }
        }
        // Older clients can't deserialize the `agent` pane kind (added in 1.5)
        // and would drop the whole config frame on encountering one. Gate agent
        // panes and agent notifications on the self-reported client version.
        val supportsAgentPanes = clientSupportsAgentPanes(info.version)

        val pushJob = launch {
            WindowState.config.collect { cfg ->
                val outbound = if (supportsAgentPanes) cfg else cfg.withoutAgentPanes()
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.Config(outbound))
                send(Frame.Text(payload))
            }
        }

        val statePushJob = launch {
            sessionStates.collect { states ->
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.State(states))
                send(Frame.Text(payload))
            }
        }

        val usagePushJob = launch {
            usageMonitor.usageData.collect { data ->
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.ClaudeUsage(data))
                send(Frame.Text(payload))
            }
        }

        // Agent notifications (MCP `notify` tool) fan out to every capable
        // client. The AgentNotify envelope kind also postdates 1.5, so skip the
        // subscription entirely for older clients rather than stream frames they
        // can't decode.
        val agentNoticePushJob = if (supportsAgentPanes) launch {
            se.soderbjorn.termtastic.mcp.McpNotices.flow.collect { env ->
                send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(env)))
            }
        } else null

        val uiSettingsPushJob = launch {
            settingsRepo.uiSettings.collect { s ->
                // Publish the v2 settings MERGED with the synthesized legacy
                // compat keys so old apps connected over the socket also get
                // the legacy shape; new apps consume the v2 keys.
                val withLegacy = settingsRepo.withLegacyCompat(s)
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.UiSettings(withLegacy))
                send(Frame.Text(payload))
            }
        }

        val ctx = WindowConnectionContext(
            send = { env -> send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(env))) },
            scope = this,
            settingsRepo = settingsRepo,
            usageMonitor = usageMonitor,
        )

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) handleWindowCommand(frame.readText(), ctx)
            }
        } finally {
            ctx.closeAll()
            pushJob.cancel()
            statePushJob.cancel()
            usagePushJob.cancel()
            agentNoticePushJob?.cancel()
            uiSettingsPushJob.cancel()
        }
    }
}

/**
 * Build a `fileBrowserDir` envelope for [paneId] by listing [dirRelPath]
 * under the leaf's cwd. `""` lists the root. Empty list when the leaf has
 * no cwd.
 */
private fun buildFileBrowserDirEnvelope(
    paneId: String,
    cwd: String?,
    dirRelPath: String,
    filter: String? = null,
    sort: FileBrowserSort = FileBrowserSort.NAME,
): WindowEnvelope.FileBrowserDir {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else FileBrowserCatalog.listDir(java.nio.file.Paths.get(cwd), dirRelPath, filter, sort)
    return WindowEnvelope.FileBrowserDir(paneId = paneId, dirRelPath = dirRelPath, entries = entries)
}

/** Build a `fileBrowserError` envelope for [paneId]. */
private fun buildFileBrowserErrorEnvelope(paneId: String, message: String): WindowEnvelope.FileBrowserError =
    WindowEnvelope.FileBrowserError(paneId = paneId, message = message)

/** Build a `gitList` envelope for [paneId] from the working tree at [cwd]. */
private fun buildGitListEnvelope(paneId: String, cwd: String?): WindowEnvelope.GitList {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else GitCatalog.listChanges(java.nio.file.Paths.get(cwd)) ?: emptyList()
    return WindowEnvelope.GitList(paneId = paneId, entries = entries)
}

/**
 * Dispatch a JSON command received on the `/window` WebSocket.
 *
 * Deserialises [text] as a [WindowCommand] and routes it to the
 * appropriate [WindowState] mutation or file/git catalog operation.
 * Results are sent back to the requesting client via [ctx]'s unicast
 * reply channel.
 */
internal suspend fun handleWindowCommand(text: String, ctx: WindowConnectionContext) {
    val cmd = runCatching { windowJson.decodeFromString<WindowCommand>(text) }.getOrNull() ?: return
    when (cmd) {
        is WindowCommand.FileBrowserListDir -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            ctx.send(buildFileBrowserDirEnvelope(cmd.paneId, leaf.cwd, cmd.dirRelPath, content.fileFilter, content.sortBy))
        }
        is WindowCommand.FileBrowserOpenFile -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is FileBrowserContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "No cwd for pane"))
                return
            }
            val read = FileBrowserCatalog.readFile(java.nio.file.Paths.get(cwd), cmd.relPath)
            if (read == null) {
                WindowState.setFileBrowserSelected(cmd.paneId, null)
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Cannot read ${cmd.relPath}"))
                return
            }
            WindowState.setFileBrowserSelected(cmd.paneId, cmd.relPath)
            val envelope: WindowEnvelope = when (read) {
                is FileBrowserCatalog.FileRead.Binary -> WindowEnvelope.FileBrowserContentMsg(
                    paneId = cmd.paneId,
                    relPath = cmd.relPath,
                    kind = FileContentKind.Binary,
                    html = "",
                    language = null,
                )
                is FileBrowserCatalog.FileRead.Text -> {
                    val ext = cmd.relPath.substringAfterLast('.', "").lowercase()
                    val isHtml = ext == "html" || ext == "htm"
                    val isMarkdown = ext == "md"
                    when {
                        isHtml -> WindowEnvelope.FileBrowserContentMsg(
                            paneId = cmd.paneId,
                            relPath = cmd.relPath,
                            kind = FileContentKind.Html,
                            html = read.content,
                            language = null,
                        )
                        isMarkdown -> {
                            val html = runCatching { MarkdownRenderer.render(read.content) }.getOrElse {
                                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Render failed"))
                                return
                            }
                            WindowEnvelope.FileBrowserContentMsg(
                                paneId = cmd.paneId,
                                relPath = cmd.relPath,
                                kind = FileContentKind.Markdown,
                                html = html,
                                language = null,
                            )
                        }
                        else -> {
                            val (html, lang) = SyntaxHighlighter.highlightFile(read.content, cmd.relPath)
                            WindowEnvelope.FileBrowserContentMsg(
                                paneId = cmd.paneId,
                                relPath = cmd.relPath,
                                kind = FileContentKind.Text,
                                html = html,
                                language = lang,
                            )
                        }
                    }
                }
            }
            ctx.send(envelope)
        }
        is WindowCommand.FileBrowserSetExpanded ->
            WindowState.setFileBrowserExpanded(cmd.paneId, cmd.dirRelPath, cmd.expanded)
        is WindowCommand.SetFileBrowserLeftWidth -> WindowState.setFileBrowserLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetFileBrowserFontSize -> WindowState.setFileBrowserFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetTerminalFontSize -> WindowState.setTerminalFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetTerminalAutoReflow -> WindowState.setTerminalAutoReflow(cmd.paneId, cmd.enabled)
        is WindowCommand.SetFileBrowserAutoRefresh -> {
            WindowState.setFileBrowserAutoRefresh(cmd.paneId, cmd.enabled)
        }
        is WindowCommand.SetFileBrowserFilter -> {
            val updated = WindowState.setFileBrowserFilter(cmd.paneId, cmd.filter)
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val toRefresh = (updated?.expandedDirs ?: emptySet()) + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated?.fileFilter,
                    updated?.sortBy ?: FileBrowserSort.NAME,
                ))
            }
        }
        is WindowCommand.SetFileBrowserSort -> {
            val updated = WindowState.setFileBrowserSort(cmd.paneId, cmd.sort) ?: return
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val toRefresh = updated.expandedDirs + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated.fileFilter, updated.sortBy,
                ))
            }
        }
        is WindowCommand.FileBrowserExpandAll -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) return
            val walk = FileBrowserCatalog.listAll(java.nio.file.Paths.get(cwd), content.fileFilter, content.sortBy)
            for ((dir, entries) in walk.listings) {
                ctx.send(WindowEnvelope.FileBrowserDir(
                    paneId = cmd.paneId, dirRelPath = dir, entries = entries,
                ))
            }
            WindowState.setFileBrowserExpandedAll(cmd.paneId, walk.expandedDirs)
        }
        is WindowCommand.FileBrowserCollapseAll -> {
            WindowState.clearFileBrowserExpanded(cmd.paneId)
        }
        is WindowCommand.GitList -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            ctx.send(buildGitListEnvelope(cmd.paneId, leaf.cwd))
        }
        is WindowCommand.GitDiff -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "No cwd for pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val hunks = GitCatalog.readDiff(cwdPath, cmd.filePath)
            if (hunks == null) {
                WindowState.setGitSelected(cmd.paneId, null)
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "Cannot read diff for ${cmd.filePath}"))
                return
            }
            val language = GitCatalog.detectLanguage(cmd.filePath)
            val highlightedHunks = hunks.map { hunk ->
                hunk.copy(lines = hunk.lines.map { line ->
                    line.copy(content = SyntaxHighlighter.highlight(line.content, language))
                })
            }
            val oldContent = GitCatalog.readGitFile(cwdPath, cmd.filePath, "HEAD")
            val newContent = GitCatalog.readWorkingFile(cwdPath, cmd.filePath)
            WindowState.setGitSelected(cmd.paneId, cmd.filePath)
            ctx.send(WindowEnvelope.GitDiffResult(
                paneId = cmd.paneId,
                filePath = cmd.filePath,
                hunks = highlightedHunks,
                language = language,
                oldContent = oldContent,
                newContent = newContent,
            ))
        }
        is WindowCommand.SetGitLeftWidth -> WindowState.setGitLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetGitDiffMode -> WindowState.setGitDiffMode(cmd.paneId, cmd.mode)
        is WindowCommand.SetGitGraphicalDiff -> WindowState.setGitGraphicalDiff(cmd.paneId, cmd.enabled)
        is WindowCommand.SetGitDiffFontSize -> WindowState.setGitDiffFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetGitAutoRefresh -> {
            val newState = WindowState.setGitAutoRefresh(cmd.paneId, cmd.enabled) ?: return
            if (!cmd.enabled) {
                ctx.cancelGitWatcher(cmd.paneId)
                return
            }
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd ?: return
            val handle = GitWatcher.register(
                scope = ctx.scope,
                root = java.nio.file.Paths.get(cwd),
            ) {
                val current = WindowState.findLeaf(cmd.paneId) ?: return@register
                val gitContent = current.content as? GitContent ?: return@register
                ctx.send(buildGitListEnvelope(cmd.paneId, current.cwd))
                val selPath = gitContent.selectedFilePath
                if (selPath != null && !current.cwd.isNullOrBlank()) {
                    val cwdPath = java.nio.file.Paths.get(current.cwd)
                    val diffHunks = GitCatalog.readDiff(cwdPath, selPath)
                    if (diffHunks != null) {
                        val lang = GitCatalog.detectLanguage(selPath)
                        val oldC = GitCatalog.readGitFile(cwdPath, selPath, "HEAD")
                        val newC = GitCatalog.readWorkingFile(cwdPath, selPath)
                        ctx.send(WindowEnvelope.GitDiffResult(
                            paneId = cmd.paneId,
                            filePath = selPath,
                            hunks = diffHunks,
                            language = lang,
                            oldContent = oldC,
                            newContent = newC,
                        ))
                    }
                }
            }
            ctx.setGitWatcher(cmd.paneId, handle)
        }
        is WindowCommand.AddGitToTab -> {
            val newLeaf = WindowState.addGitToTab(cmd.tabId, initialCwd = cmd.cwd)
            if (newLeaf != null) {
                ctx.send(buildGitListEnvelope(newLeaf.id, newLeaf.cwd))
            }
        }
        is WindowCommand.Close -> {
            ctx.cancelGitWatcher(cmd.paneId)
            WindowState.closePane(cmd.paneId)
        }
        is WindowCommand.CloseSession -> {
            WindowState.closeSession(cmd.sessionId)
        }
        is WindowCommand.Rename -> WindowState.renamePane(cmd.paneId, cmd.title)
        is WindowCommand.AddTab -> WindowState.addTab()
        is WindowCommand.CloseTab -> WindowState.closeTab(cmd.tabId)
        is WindowCommand.RenameTab -> WindowState.renameTab(cmd.tabId, cmd.title)
        is WindowCommand.MoveTab -> WindowState.moveTab(cmd.tabId, cmd.targetTabId, cmd.before)
        is WindowCommand.SetTabHidden -> WindowState.setTabHidden(cmd.tabId, cmd.hidden)
        is WindowCommand.SetTabHiddenFromSidebar -> WindowState.setTabHiddenFromSidebar(cmd.tabId, cmd.hidden)
        is WindowCommand.AddPaneToTab -> {
            WindowState.addPaneToTab(cmd.tabId, initialCwd = cmd.cwd)
        }
        is WindowCommand.AddFileBrowserToTab -> {
            val newLeaf = WindowState.addFileBrowserToTab(cmd.tabId, initialCwd = cmd.cwd)
            if (newLeaf != null) {
                ctx.send(buildFileBrowserDirEnvelope(newLeaf.id, newLeaf.cwd, ""))
            }
        }
        is WindowCommand.AddLinkToTab -> WindowState.addLinkToTab(cmd.tabId, cmd.targetSessionId)
        is WindowCommand.SetPaneGeom ->
            WindowState.setPaneGeometry(cmd.paneId, cmd.x, cmd.y, cmd.width, cmd.height)
        is WindowCommand.SetPaneZoom -> WindowState.setPaneZoom(cmd.paneId, cmd.zoom)
        is WindowCommand.RaisePane -> WindowState.raisePane(cmd.paneId)
        is WindowCommand.ToggleMaximized -> WindowState.toggleMaximized(cmd.paneId)
        is WindowCommand.ApplyLayout ->
            WindowState.applyLayout(cmd.tabId, cmd.layout, cmd.primaryPaneId)
        is WindowCommand.MovePaneToTab -> WindowState.movePaneToTab(cmd.paneId, cmd.targetTabId)
        is WindowCommand.SwapPanes -> WindowState.swapPanes(cmd.paneAId, cmd.paneBId)
        is WindowCommand.MovePaneWithinTab -> WindowState.movePaneWithinTab(cmd.paneId, cmd.targetPaneId, cmd.before, cmd.retile)
        is WindowCommand.SetActiveTab -> WindowState.setActiveTab(cmd.tabId)
        is WindowCommand.SetFocusedPane -> WindowState.setFocusedPane(cmd.tabId, cmd.paneId)
        is WindowCommand.SetStateOverride -> TerminalSessions.setStateOverride(cmd.sessionId, cmd.mode)
        is WindowCommand.OpenSettings -> SettingsDialog.show(ctx.settingsRepo)
        is WindowCommand.RefreshUsage -> ctx.usageMonitor.requestRefresh()
        is WindowCommand.GetWorktreeDefaults -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            val repoName = GitCatalog.getRepoNameFromRemote(cwdPath)
                ?: repoRoot.fileName?.toString()
                ?: "repo"
            val parentDir = repoRoot.parent?.toString() ?: cwd
            val siblingPath = "$parentDir/$repoName-"
            val dotWorktreesPath = "$parentDir/.worktrees/$repoName-"
            val hasChanges = GitCatalog.hasUncommittedChanges(cwdPath)
            ctx.send(WindowEnvelope.WorktreeDefaults(
                paneId = cmd.paneId,
                repoName = repoName,
                siblingPath = siblingPath,
                dotWorktreesPath = dotWorktreesPath,
                hasUncommittedChanges = hasChanges,
            ))
        }
        is WindowCommand.CreateWorktree -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            if (!GitCatalog.isValidBranchName(cmd.branchName)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Invalid branch name: ${cmd.branchName}"))
                return
            }
            val rawWorktreePath = java.nio.file.Paths.get(cmd.worktreePath)
            val worktreePath = if (rawWorktreePath.isAbsolute) {
                rawWorktreePath.normalize()
            } else {
                repoRoot.resolve(rawWorktreePath).normalize()
            }
            if (java.nio.file.Files.exists(worktreePath)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Path already exists: $worktreePath"))
                return
            }

            var stashed = false
            if (cmd.migrateChanges && GitCatalog.hasUncommittedChanges(repoRoot)) {
                stashed = GitCatalog.stashPush(repoRoot, "termtastic-worktree-migrate")
                if (!stashed) {
                    ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Failed to stash uncommitted changes"))
                    return
                }
            }

            val createError = GitCatalog.createWorktree(repoRoot, cmd.branchName, worktreePath)
            if (createError != null) {
                if (stashed) GitCatalog.stashPop(repoRoot)
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, createError))
                return
            }

            if (stashed) {
                val popError = GitCatalog.stashPop(worktreePath)
                if (popError != null) {
                    ctx.send(WindowEnvelope.WorktreeError(
                        cmd.paneId,
                        "Worktree created but failed to migrate changes: $popError\n" +
                            "Your changes are still in the stash. Run 'git stash pop' manually."
                    ))
                    return
                }
            }

            val newCwd = worktreePath.toAbsolutePath().toString()
            val hostTabId = WindowState.tabIdOfPane(cmd.paneId)
            if (hostTabId != null) {
                WindowState.addPaneToTab(hostTabId, initialCwd = newCwd)
            }
            ctx.send(WindowEnvelope.WorktreeCreated(paneId = cmd.paneId, newCwd = newCwd))
        }
    }
}
