/**
 * MCP write tools (plan Groups C, D and E) for Lunamux.
 *
 * Group C — input & execution: `send_text`, `send_keys`, `interrupt`,
 * `run_command`.
 * Group D — windows & tabs: `create_window`, `close_window`,
 * `rename_window`, `activate_window`, `move_window`, `maximize_window`,
 * `arrange`, `create_tab`, `rename_tab`, `close_tab`, `activate_tab`.
 * Group E — workspaces & signalling: `save_workspace`, `apply_workspace`,
 * `list_workspaces`, `set_theme`, `annotate_window`, `notify`.
 *
 * Every tool here (except the read-only `list_workspaces`) requires a
 * `read+write` scoped token. All calls are recorded in [McpActivityLog],
 * and windows an agent touches get an automatic `agentNote` badge (see
 * [markAgentTouched]) so agent activity is visible on every device.
 *
 * The tools call the same [WindowState] mutators the `/window`
 * WebSocket's `handleWindowCommand` dispatcher uses; the mutated
 * [se.soderbjorn.lunamux.WindowConfig] auto-broadcasts to all clients
 * through the existing StateFlow, so no extra push plumbing is needed.
 *
 * @see registerMcpWriteTools
 * @see McpServer
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TerminalContent
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository

/** SQLite key holding the persisted workspace-template store. */
private const val WORKSPACES_KEY = "mcp.workspaces.v1"

/** Default / maximum run_command timeouts (ms). */
private const val DEFAULT_RUN_TIMEOUT_MS = 60_000
private const val MAX_RUN_TIMEOUT_MS = 600_000

// ── Workspace template model (persisted as JSON via SettingsRepository) ──

/**
 * One window in a saved workspace template: enough to recreate the pane
 * (kind + cwd + name) and its geometry.
 */
@Serializable
internal data class WorkspaceWindow(
    val kind: String,
    val cwd: String? = null,
    val customName: String? = null,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** One tab in a saved workspace template. */
@Serializable
internal data class WorkspaceTab(
    val title: String,
    val layoutPreset: String? = null,
    val windows: List<WorkspaceWindow> = emptyList(),
)

/** A named workspace template (what `save_workspace` captures). */
@Serializable
internal data class WorkspaceTemplate(
    val name: String,
    val savedAtEpochMs: Long,
    val tabs: List<WorkspaceTab> = emptyList(),
)

/** The persisted map of all templates, keyed by name. */
@Serializable
internal data class WorkspaceStore(
    val workspaces: Map<String, WorkspaceTemplate> = emptyMap(),
)

private fun loadWorkspaces(repo: SettingsRepository): WorkspaceStore =
    repo.getString(WORKSPACES_KEY)?.let {
        runCatching { mcpJson.decodeFromString(WorkspaceStore.serializer(), it) }.getOrNull()
    } ?: WorkspaceStore()

private fun saveWorkspaces(repo: SettingsRepository, store: WorkspaceStore) {
    repo.putString(WORKSPACES_KEY, mcpJson.encodeToString(WorkspaceStore.serializer(), store))
}

// ── Shared helpers ───────────────────────────────────────────────────────

/** Resolve a live window (pane) leaf or throw the standard argument error. */
private fun requireWindow(windowId: String): LeafNode =
    WindowState.findLeaf(windowId)
        ?: throw McpArgumentException("Unknown windowId: $windowId")

/** Resolve an existing tab id or throw the standard argument error. */
private fun requireTab(tabId: String): String {
    if (WindowState.config.value.tabs.none { it.id == tabId }) {
        throw McpArgumentException("Unknown tabId: $tabId")
    }
    return tabId
}

/** The target tab: explicit arg, else the active tab, else the first tab. */
private fun resolveTargetTab(args: JsonObject): String {
    args.optString("tabId")?.let { return requireTab(it) }
    val cfg = WindowState.config.value
    return cfg.activeTabId ?: cfg.tabs.firstOrNull()?.id
        ?: throw McpArgumentException("No tabs exist")
}

/**
 * Stamp the automatic agent-activity badge on [windowId] unless the
 * window already carries a (possibly custom) note. Called by every write
 * tool that touches a specific window or session.
 */
private fun markAgentTouched(windowId: String?) {
    if (windowId == null) return
    val leaf = WindowState.findLeaf(windowId) ?: return
    if (leaf.agentNote == null) WindowState.setAgentNote(windowId, "agent")
}

/** [markAgentTouched] via a session id (badges the primary window). */
private fun markAgentTouchedBySession(sessionId: String) {
    markAgentTouched(placementOf(sessionId).primaryWindowId)
}

/**
 * Build the one-line argument summary recorded in [McpActivityLog].
 * Free-text payloads (`text`, `command`) are redacted to their length so
 * the activity list never leaks typed content.
 */
private fun summarizeArgs(args: JsonObject): String =
    args.entries.joinToString(" ") { (k, v) ->
        when {
            k == "text" || k == "command" ->
                "$k=<${(v as? JsonPrimitive)?.contentOrNull?.length ?: 0} chars>"
            v is JsonPrimitive -> "$k=${v.contentOrNull?.take(48)}"
            else -> "$k=${v.toString().take(48)}"
        }
    }

/**
 * Register a write tool: wraps the handler so every successful call is
 * recorded in [McpActivityLog] with redacted arguments.
 */
private fun writeTool(
    name: String,
    description: String,
    inputSchema: JsonObject,
    handler: suspend (McpCallContext, JsonObject) -> McpToolResult,
) {
    McpServer.register(McpTool(name, description, inputSchema, requiresWrite = true) { ctx, args ->
        val result = handler(ctx, args)
        if (!result.isError) McpActivityLog.record(name, summarizeArgs(args))
        result
    })
}

// ── Key-name → VT byte-sequence encoding for send_keys ──────────────────

/** Named keys → their VT/xterm byte sequences (normal keypad mode). */
private val NAMED_KEYS: Map<String, String> = buildMap {
    put("enter", "\r"); put("return", "\r")
    put("tab", "\t")
    put("escape", "\u001B"); put("esc", "\u001B")
    put("space", " ")
    put("backspace", "\u007F")
    put("up", "\u001B[A"); put("down", "\u001B[B")
    put("right", "\u001B[C"); put("left", "\u001B[D")
    put("home", "\u001B[H"); put("end", "\u001B[F")
    put("pageup", "\u001B[5~"); put("pagedown", "\u001B[6~")
    put("insert", "\u001B[2~"); put("delete", "\u001B[3~")
    put("f1", "\u001BOP"); put("f2", "\u001BOQ"); put("f3", "\u001BOR"); put("f4", "\u001BOS")
    put("f5", "\u001B[15~"); put("f6", "\u001B[17~"); put("f7", "\u001B[18~"); put("f8", "\u001B[19~")
    put("f9", "\u001B[20~"); put("f10", "\u001B[21~"); put("f11", "\u001B[23~"); put("f12", "\u001B[24~")
}

/**
 * Encode one `send_keys` key token into the bytes to write to the PTY.
 * Accepts named keys (see [NAMED_KEYS]), `ctrl+<letter>` chords,
 * `alt+<char>` chords, and single literal characters.
 *
 * @param token the key name from the tool arguments.
 * @return the VT byte sequence.
 * @throws McpArgumentException for an unrecognized token.
 */
internal fun encodeKeyToken(token: String): ByteArray {
    val k = token.trim()
    val lower = k.lowercase()
    NAMED_KEYS[lower]?.let { return it.toByteArray(Charsets.UTF_8) }
    if (lower.startsWith("ctrl+") && lower.length == 6) {
        val c = lower[5]
        if (c in 'a'..'z') return byteArrayOf(((c - 'a') + 1).toByte())
        when (c) {
            '[' -> return byteArrayOf(0x1B)
            '\\' -> return byteArrayOf(0x1C)
            ']' -> return byteArrayOf(0x1D)
            '^' -> return byteArrayOf(0x1E)
            '_' -> return byteArrayOf(0x1F)
        }
    }
    if (lower.startsWith("alt+") && k.length == 5) {
        return byteArrayOf(0x1B) + k.substring(4).toByteArray(Charsets.UTF_8)
    }
    if (k.length == 1) return k.toByteArray(Charsets.UTF_8)
    throw McpArgumentException(
        "Unrecognized key '$token' — use a character, a named key " +
            "(enter, tab, escape, up, …, f12), ctrl+<letter>, or alt+<char>",
    )
}

/** Sentinel used to break out of the run_command output collect. */
private object RunDone : Exception() {
    private fun readResolve(): Any = RunDone
}

// ── Registration ─────────────────────────────────────────────────────────

/**
 * Register every Group C, D and E tool with [McpServer]. Called once at
 * boot from [mcpRoutes], after [registerMcpReadTools].
 *
 * @param settingsRepo backing store for workspace templates and the
 *   theme-selection blob used by `set_theme`.
 */
fun registerMcpWriteTools(settingsRepo: SettingsRepository) {

    // ── Group C — input & execution ──────────────────────────────────────

    writeTool(
        name = "send_text",
        description = "Type text into a terminal session. Set submit=true to press Enter after it.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("Target session id."),
            "text" to schemaString("The text to type (sent verbatim to the PTY)."),
            "submit" to schemaBool("Append Enter (CR) after the text. Default false."),
            required = listOf("sessionId", "text"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        val session = requireSession(sessionId)
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpArgumentException("Missing required argument 'text'")
        val submit = args.optBool("submit") ?: false
        session.write((text + if (submit) "\r" else "").toByteArray(Charsets.UTF_8))
        markAgentTouchedBySession(sessionId)
        McpToolResult.text("Sent ${text.length} chars${if (submit) " + Enter" else ""} to $sessionId")
    }

    writeTool(
        name = "send_keys",
        description = "Send special keys / chords to a terminal session, in order. Accepts named " +
            "keys (enter, tab, escape, up, down, left, right, home, end, pageup, pagedown, " +
            "insert, delete, f1–f12, space, backspace), ctrl+<letter>, alt+<char>, or single " +
            "literal characters.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("Target session id."),
            "keys" to schemaArray("The key tokens to send, in order.", schemaString("A key token.")),
            required = listOf("sessionId", "keys"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        val session = requireSession(sessionId)
        val keys = (args["keys"] as? kotlinx.serialization.json.JsonArray)
            ?: throw McpArgumentException("Missing required argument 'keys' (array)")
        var bytes = ByteArray(0)
        for (el in keys) {
            val token = (el as? JsonPrimitive)?.contentOrNull
                ?: throw McpArgumentException("keys must be strings")
            bytes += encodeKeyToken(token)
        }
        session.write(bytes)
        markAgentTouchedBySession(sessionId)
        McpToolResult.text("Sent ${keys.size} key(s) to $sessionId")
    }

    writeTool(
        name = "interrupt",
        description = "Send Ctrl-C (SIGINT) to a terminal session.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("Target session id."),
            required = listOf("sessionId"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        requireSession(sessionId).write(byteArrayOf(0x03))
        markAgentTouchedBySession(sessionId)
        McpToolResult.text("Sent interrupt to $sessionId")
    }

    writeTool(
        name = "run_command",
        description = "Run a shell command in a session (or a freshly created window) and, by " +
            "default, wait for it to finish — returning its exit code and captured output. Uses " +
            "a shell marker to detect completion, so it requires a POSIX-ish shell (bash/zsh) at " +
            "an interactive prompt. Set waitForExit=false to fire-and-forget.",
        inputSchema = schemaObject(
            "command" to schemaString("The command line to run."),
            "sessionId" to schemaString("Run in this existing session. Omit to create a new window."),
            "tabId" to schemaString("When creating a window: the tab to create it in (default: active tab)."),
            "cwd" to schemaString("When creating a window: the starting directory for its shell."),
            "waitForExit" to schemaBool("Wait for completion and capture output. Default true."),
            "timeoutMs" to schemaInt("Max wait in ms (default $DEFAULT_RUN_TIMEOUT_MS, max $MAX_RUN_TIMEOUT_MS)."),
            required = listOf("command"),
        ),
    ) { _, args ->
        val command = args.requireString("command")
        var createdWindowId: String? = null
        val sessionId = args.optString("sessionId") ?: run {
            val tabId = resolveTargetTab(args)
            val leaf = WindowState.addPaneToTab(tabId, initialCwd = args.optString("cwd"))
                ?: throw McpArgumentException("Unknown tabId: $tabId")
            createdWindowId = leaf.id
            leaf.sessionId
        }
        val session = requireSession(sessionId)
        markAgentTouchedBySession(sessionId)

        val waitForExit = args.optBool("waitForExit") ?: true
        if (!waitForExit) {
            session.write((command + "\r").toByteArray(Charsets.UTF_8))
            return@writeTool McpToolResult.json(buildJsonObject {
                put("submitted", true)
                put("sessionId", sessionId)
                put("windowId", createdWindowId ?: placementOf(sessionId).primaryWindowId)
            })
        }

        val timeoutMs = ((args.optInt("timeoutMs") ?: DEFAULT_RUN_TIMEOUT_MS)
            .coerceIn(1_000, MAX_RUN_TIMEOUT_MS)).toLong()
        val nonce = (1..8).map { "0123456789abcdef".random() }.joinToString("")
        val marker = "__TT_DONE_${nonce}_"
        // The echoed command line contains the literal `%d`, so the digit
        // regex below can never match the echo — only the printf output.
        val full = "$command; printf '\\n$marker%d\\n' $?\r"
        val markerRegex = Regex(Regex.escape(marker) + "(\\d+)")

        val buffer = StringBuilder()
        var exitCode: Int? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                // onSubscription guarantees the collector is attached before
                // the command is written, so no output burst is missed.
                session.output
                    .onSubscription { session.write(full.toByteArray(Charsets.UTF_8)) }
                    .collect { chunk ->
                        buffer.append(chunk.toString(Charsets.UTF_8))
                        val m = markerRegex.find(stripAnsi(buffer.toString()))
                        if (m != null) {
                            exitCode = m.groupValues[1].toIntOrNull()
                            throw RunDone
                        }
                    }
            }
        } catch (_: RunDone) {
            // Completion marker seen.
        }

        val stripped = stripAnsi(buffer.toString())
        val output = run {
            // Cut at the marker line, drop the echoed command line, trim.
            val cut = markerRegex.find(stripped)?.let { stripped.substring(0, it.range.first) } ?: stripped
            cut.lines()
                .dropWhile { it.isBlank() || it.contains(marker) || it.contains(command.take(40)) }
                .joinToString("\n")
                .trim('\n')
        }
        McpToolResult.json(buildJsonObject {
            put("completed", exitCode != null)
            put("exitCode", exitCode)
            put("output", output)
            put("sessionId", sessionId)
            put("windowId", createdWindowId ?: placementOf(sessionId).primaryWindowId)
            if (exitCode == null) put("timedOutAfterMs", timeoutMs)
        })
    }

    // ── Group D — windows & tabs ─────────────────────────────────────────

    writeTool(
        name = "create_window",
        description = "Create a new terminal window (pane). Returns the new windowId and sessionId.",
        inputSchema = schemaObject(
            "tabId" to schemaString("Tab to create the window in (default: active tab)."),
            "cwd" to schemaString("Starting directory for the new shell."),
            "title" to schemaString("Optional custom window name."),
        ),
    ) { _, args ->
        val tabId = resolveTargetTab(args)
        val leaf = WindowState.addPaneToTab(tabId, initialCwd = args.optString("cwd"))
            ?: throw McpArgumentException("Unknown tabId: $tabId")
        args.optString("title")?.let { WindowState.renamePane(leaf.id, it) }
        markAgentTouched(leaf.id)
        McpToolResult.json(buildJsonObject {
            put("windowId", leaf.id)
            put("sessionId", leaf.sessionId)
            put("tabId", tabId)
        })
    }

    writeTool(
        name = "close_window",
        description = "Close a window (pane). A terminal window's PTY is killed unless other " +
            "linked windows still reference it.",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to close."),
            required = listOf("windowId"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        WindowState.closePane(windowId)
        McpToolResult.text("Closed window $windowId")
    }

    writeTool(
        name = "rename_window",
        description = "Set a window's custom name. An empty title clears the custom name (falls " +
            "back to the cwd-based title).",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to rename."),
            "title" to schemaString("New custom name; empty string clears it."),
            required = listOf("windowId", "title"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        val title = (args["title"] as? JsonPrimitive)?.contentOrNull ?: ""
        WindowState.renamePane(windowId, title)
        markAgentTouched(windowId)
        McpToolResult.text("Renamed window $windowId")
    }

    writeTool(
        name = "activate_window",
        description = "Bring a window to the front and focus it (switching to its tab first if " +
            "needed).",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to activate."),
            required = listOf("windowId"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        val tabId = WindowState.tabIdOfPane(windowId)
            ?: throw McpArgumentException("Window $windowId is not in any tab")
        if (WindowState.config.value.activeTabId != tabId) WindowState.setActiveTab(tabId)
        WindowState.raisePane(windowId)
        WindowState.setFocusedPane(tabId, windowId)
        McpToolResult.text("Activated window $windowId (tab $tabId)")
    }

    writeTool(
        name = "move_window",
        description = "Move a window to a different tab.",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to move."),
            "targetTabId" to schemaString("Destination tab id."),
            required = listOf("windowId", "targetTabId"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        val target = requireTab(args.requireString("targetTabId"))
        WindowState.movePaneToTab(windowId, target)
        markAgentTouched(windowId)
        McpToolResult.text("Moved window $windowId to tab $target")
    }

    writeTool(
        name = "maximize_window",
        description = "Maximize or restore a window (idempotent — sets the exact state).",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to (un)maximize."),
            "maximized" to schemaBool("Desired state: true = maximized, false = restored."),
            required = listOf("windowId", "maximized"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        val maximized = args.optBool("maximized")
            ?: throw McpArgumentException("Missing required argument 'maximized'")
        WindowState.setMaximized(windowId, maximized)
        McpToolResult.text("Window $windowId maximized=$maximized")
    }

    writeTool(
        name = "arrange",
        description = "Apply a layout preset to a tab's windows. Layouts: auto, grid, columns, " +
            "rows, hero-left, hero-right, hero-top, hero-bottom, split-h, split-v, sidebar-left, " +
            "sidebar-right, sidebar-top, sidebar-bottom, t-shape, t-shape-inv, l-shape, " +
            "l-shape-tr, l-shape-bl, l-shape-br, big-2-stack, big-2-stack-right, big-2-stack-bottom.",
        inputSchema = schemaObject(
            "layout" to schemaString("The layout preset key."),
            "tabId" to schemaString("The tab to arrange (default: active tab)."),
            "primaryWindowId" to schemaString("Window that gets the largest slot (layout-dependent)."),
            required = listOf("layout"),
        ),
    ) { _, args ->
        val tabId = resolveTargetTab(args)
        WindowState.applyLayout(tabId, args.requireString("layout"), args.optString("primaryWindowId"))
        McpToolResult.text("Applied layout to tab $tabId")
    }

    writeTool(
        name = "create_tab",
        description = "Create a new tab (seeded with one terminal window) and switch to it.",
        inputSchema = schemaObject(
            "title" to schemaString("Optional tab title."),
        ),
    ) { _, args ->
        val tabId = WindowState.addTab()
        args.optString("title")?.let { WindowState.renameTab(tabId, it) }
        val tab = WindowState.config.value.tabs.first { it.id == tabId }
        val seeded = tab.panes.firstOrNull()?.leaf
        McpToolResult.json(buildJsonObject {
            put("tabId", tabId)
            put("windowId", seeded?.id)
            put("sessionId", seeded?.sessionId)
        })
    }

    writeTool(
        name = "rename_tab",
        description = "Set a tab's display title.",
        inputSchema = schemaObject(
            "tabId" to schemaString("The tab to rename."),
            "title" to schemaString("The new title."),
            required = listOf("tabId", "title"),
        ),
    ) { _, args ->
        val tabId = requireTab(args.requireString("tabId"))
        WindowState.renameTab(tabId, args.requireString("title"))
        McpToolResult.text("Renamed tab $tabId")
    }

    writeTool(
        name = "close_tab",
        description = "Close a tab and every window in it (their PTY sessions are killed).",
        inputSchema = schemaObject(
            "tabId" to schemaString("The tab to close."),
            required = listOf("tabId"),
        ),
    ) { _, args ->
        val tabId = requireTab(args.requireString("tabId"))
        WindowState.closeTab(tabId)
        McpToolResult.text("Closed tab $tabId")
    }

    writeTool(
        name = "activate_tab",
        description = "Switch the active (visible) tab.",
        inputSchema = schemaObject(
            "tabId" to schemaString("The tab to activate."),
            required = listOf("tabId"),
        ),
    ) { _, args ->
        val tabId = requireTab(args.requireString("tabId"))
        WindowState.setActiveTab(tabId)
        McpToolResult.text("Activated tab $tabId")
    }

    // ── Group E — workspaces & signalling ────────────────────────────────

    writeTool(
        name = "set_theme",
        description = "Change the UI appearance and/or the theme bound to the dark/light slot. " +
            "Theme names must match an existing built-in or custom theme; the change broadcasts " +
            "to every connected client.",
        inputSchema = schemaObject(
            "appearance" to schemaString("Appearance mode.", enum = listOf("auto", "dark", "light")),
            "darkTheme" to schemaString("Theme name to bind to the dark slot."),
            "lightTheme" to schemaString("Theme name to bind to the light slot."),
        ),
    ) { _, args ->
        val appearanceArg = args.optString("appearance")
        val darkArg = args.optString("darkTheme")
        val lightArg = args.optString("lightTheme")
        if (appearanceArg == null && darkArg == null && lightArg == null) {
            throw McpArgumentException("Provide at least one of appearance/darkTheme/lightTheme")
        }
        val stored = settingsRepo.getUiSettings()
        fun raw(key: String): String? =
            (stored[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val snapshot = ThemeSnapshotV2.fromStrings(
            selectionJson = raw(PersistKeys.THEME_V2_SELECTION),
            customThemesJson = raw(PersistKeys.THEME_V2_CUSTOM),
        )
        val known = allThemes(snapshot.customThemes).map { it.name }
        for (candidate in listOfNotNull(darkArg, lightArg)) {
            if (candidate !in known) {
                throw McpArgumentException(
                    "Unknown theme '$candidate'. Available: ${known.joinToString(", ")}",
                )
            }
        }
        val appearance = when (appearanceArg?.lowercase()) {
            null -> snapshot.appearance
            "auto" -> Appearance.Auto
            "dark" -> Appearance.Dark
            "light" -> Appearance.Light
            else -> throw McpArgumentException("appearance must be auto/dark/light")
        }
        val updated = snapshot.copy(
            appearance = appearance,
            darkThemeName = darkArg ?: snapshot.darkThemeName,
            lightThemeName = lightArg ?: snapshot.lightThemeName,
        )
        settingsRepo.mergeUiSettings(buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(updated.selectionJson()))
        })
        McpToolResult.json(buildJsonObject {
            put("appearance", updated.appearance.name)
            put("darkTheme", updated.darkThemeName)
            put("lightTheme", updated.lightThemeName)
        })
    }

    writeTool(
        name = "annotate_window",
        description = "Set (or clear) the agent badge on a window — a small note visible on every " +
            "device, e.g. 'building' or 'needs input'. Omit note to clear the badge.",
        inputSchema = schemaObject(
            "windowId" to schemaString("The window to annotate."),
            "note" to schemaString("Badge text (max 60 chars); omit or empty to clear."),
            required = listOf("windowId"),
        ),
    ) { _, args ->
        val windowId = args.requireString("windowId")
        requireWindow(windowId)
        val note = args.optString("note")
        WindowState.setAgentNote(windowId, note)
        McpToolResult.text(if (note == null) "Cleared badge on $windowId" else "Annotated $windowId")
    }

    writeTool(
        name = "notify",
        description = "Show a transient notification (toast) on every connected Lunamux client.",
        inputSchema = schemaObject(
            "message" to schemaString("The notification text."),
            "level" to schemaString("Severity.", enum = listOf("info", "warn", "error")),
            required = listOf("message"),
        ),
    ) { _, args ->
        val message = args.requireString("message").take(500)
        val level = args.optString("level")?.takeIf { it in listOf("info", "warn", "error") } ?: "info"
        McpNotices.emit(WindowEnvelope.AgentNotify(message = message, level = level))
        McpToolResult.text("Notified all clients")
    }

    writeTool(
        name = "save_workspace",
        description = "Save the current layout (tabs, window kinds, cwds, names, geometry) as a " +
            "named workspace template.",
        inputSchema = schemaObject(
            "name" to schemaString("Template name (overwrites an existing template of the same name)."),
            required = listOf("name"),
        ),
    ) { _, args ->
        val name = args.requireString("name").take(60)
        val cfg = WindowState.config.value
        val template = WorkspaceTemplate(
            name = name,
            savedAtEpochMs = System.currentTimeMillis(),
            tabs = cfg.tabs.map { tab ->
                WorkspaceTab(
                    title = tab.title,
                    layoutPreset = tab.layoutPreset,
                    windows = tab.panes.mapNotNull { p ->
                        val kind = when (p.leaf.content) {
                            is FileBrowserContent -> "fileBrowser"
                            is GitContent -> "git"
                            is TerminalContent, null -> "terminal"
                            // Agent consoles are ephemeral (their driving MCP
                            // client won't exist when the template is applied)
                            // — they are not captured.
                            else -> return@mapNotNull null
                        }
                        WorkspaceWindow(
                            kind = kind,
                            cwd = p.leaf.cwd,
                            customName = p.leaf.customName,
                            x = p.x, y = p.y, width = p.width, height = p.height,
                        )
                    },
                )
            },
        )
        val store = loadWorkspaces(settingsRepo)
        saveWorkspaces(settingsRepo, WorkspaceStore(store.workspaces + (name to template)))
        McpToolResult.json(buildJsonObject {
            put("name", name)
            put("tabs", template.tabs.size)
            put("windows", template.tabs.sumOf { it.windows.size })
        })
    }

    writeTool(
        name = "apply_workspace",
        description = "Recreate a saved workspace template: appends its tabs (with their windows, " +
            "cwds, names and geometry) to the current layout.",
        inputSchema = schemaObject(
            "name" to schemaString("The template to apply (see list_workspaces)."),
            required = listOf("name"),
        ),
    ) { _, args ->
        val name = args.requireString("name")
        val template = loadWorkspaces(settingsRepo).workspaces[name]
            ?: throw McpArgumentException("Unknown workspace: $name")
        val createdTabs = mutableListOf<String>()
        for (tabTemplate in template.tabs) {
            val tabId = WindowState.addTab()
            createdTabs.add(tabId)
            WindowState.renameTab(tabId, tabTemplate.title)
            // addTab seeds one terminal window we don't want — remember it,
            // add the template's windows, then close the seed.
            val seededIds = WindowState.config.value.tabs
                .first { it.id == tabId }.panes.map { it.leaf.id }
            val placed = mutableListOf<Pair<String, WorkspaceWindow>>()
            for (w in tabTemplate.windows) {
                val leaf = when (w.kind) {
                    "fileBrowser" -> WindowState.addFileBrowserToTab(tabId, initialCwd = w.cwd)
                    "git" -> WindowState.addGitToTab(tabId, initialCwd = w.cwd)
                    else -> WindowState.addPaneToTab(tabId, initialCwd = w.cwd)
                } ?: continue
                w.customName?.let { WindowState.renamePane(leaf.id, it) }
                placed.add(leaf.id to w)
            }
            for (seed in seededIds) WindowState.closePane(seed)
            val preset = tabTemplate.layoutPreset
            if (preset != null && preset != "custom") {
                WindowState.applyLayout(tabId, preset, placed.firstOrNull()?.first)
            } else {
                for ((leafId, w) in placed) {
                    WindowState.setPaneGeometry(leafId, w.x, w.y, w.width, w.height)
                }
            }
        }
        McpToolResult.json(buildJsonObject {
            put("applied", name)
            put("createdTabIds", buildJsonArray { createdTabs.forEach { add(JsonPrimitive(it)) } })
        })
    }

    // Read-only: available to read-scope tokens too.
    McpServer.register(McpTool(
        name = "list_workspaces",
        description = "List saved workspace templates.",
        inputSchema = schemaObject(),
    ) { _, _ ->
        val store = loadWorkspaces(settingsRepo)
        McpToolResult.json(buildJsonObject {
            put("workspaces", buildJsonArray {
                for (t in store.workspaces.values.sortedBy { it.name }) {
                    add(buildJsonObject {
                        put("name", t.name)
                        put("savedAtEpochMs", t.savedAtEpochMs)
                        put("tabs", t.tabs.size)
                        put("windows", t.tabs.sumOf { it.windows.size })
                    })
                }
            })
        })
    })
}
