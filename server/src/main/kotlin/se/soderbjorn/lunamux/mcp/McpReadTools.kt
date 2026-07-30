/**
 * MCP read/watch tools (plan Groups A and B) for Lunamux.
 *
 * Group A — inventory & inspection: `list_sessions`, `get_session`,
 * `list_layout`, `list_worlds`, `get_state`, `get_claude_usage`,
 * `read_scrollback`.
 * Group B — watching: `watch_output`, `wait_for_idle`, `wait_for_exit`.
 *
 * All tools are read-only ([McpTool.requiresWrite] = false) and available
 * to both `read` and `read+write` scoped tokens. Registered once at boot
 * by [registerMcpReadTools], called from [mcpRoutes].
 *
 * Addressing note (settled in the plan): content/exec tools take a
 * `sessionId` (the PTY session, `s<n>-<nonce>`); layout tools take a
 * `windowId` (the pane / LeafNode id, `n<n>-<nonce>`). Read results echo
 * both so agents can cross-reference.
 *
 * @see McpServer
 * @see se.soderbjorn.lunamux.TerminalSessions
 * @see se.soderbjorn.lunamux.WindowState
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.TerminalContent
import se.soderbjorn.lunamux.WebBrowserContent
import se.soderbjorn.lunamux.TermSession
import se.soderbjorn.lunamux.TerminalSessions
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository

/** Default timeout for Group B watch tools when the caller omits one. */
private const val DEFAULT_WATCH_TIMEOUT_MS = 30_000

/** Hard ceiling for Group B timeouts so a stray call can't pin a worker forever. */
private const val MAX_WATCH_TIMEOUT_MS = 600_000

/** Default / maximum line counts for `read_scrollback`. */
private const val DEFAULT_SCROLLBACK_LINES = 100
private const val MAX_SCROLLBACK_LINES = 2_000

// ── ANSI stripping ───────────────────────────────────────────────────────

/** CSI sequences: `ESC [ ... final-byte` (colors, cursor movement, modes). */
private val CSI_SEQ = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

/** OSC sequences: `ESC ] ... (BEL | ESC backslash)` (titles, cwd reports). */
private val OSC_SEQ = Regex("\\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)?")

/** Remaining two/three-byte ESC sequences (charset selection, keypad, ...). */
private val OTHER_ESC = Regex("\\u001B[()#%][0-9A-Za-z@]|\\u001B[@-Z\\\\^_=><]")

/**
 * Strip ANSI escape sequences and normalize carriage returns from raw PTY
 * text, approximating what the user visually saw:
 *  - CSI / OSC / other ESC sequences are removed,
 *  - `\r\n` normalizes to `\n`,
 *  - a bare `\r` inside a line keeps only the text after the *last* one
 *    (approximating in-place overwrites, e.g. progress bars),
 *  - remaining C0 control characters (except `\t`) are dropped.
 *
 * Used by `read_scrollback` and the Group B output matchers. Shared with
 * the write-tool layer (`run_command` output capture).
 *
 * @param raw decoded PTY output.
 * @return plain readable text.
 */
internal fun stripAnsi(raw: String): String {
    var s = raw
    s = OSC_SEQ.replace(s, "")
    s = CSI_SEQ.replace(s, "")
    s = OTHER_ESC.replace(s, "")
    s = s.replace("\r\n", "\n")
    val sb = StringBuilder(s.length)
    for (line in s.split('\n')) {
        val kept = line.substringAfterLast('\r')
        for (ch in kept) {
            if (ch == '\t' || ch.code >= 0x20) sb.append(ch)
        }
        sb.append('\n')
    }
    // split('\n') adds one trailing element; drop the extra newline.
    if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
    return sb.toString()
}

// ── Session ↔ layout cross-referencing ───────────────────────────────────

/**
 * The layout placement of a session: which pane (windowId) and tab
 * reference it. A session can back several panes when linked views exist;
 * [primary] is the first non-link pane.
 */
internal class SessionPlacement(
    val primaryWindowId: String?,
    val primaryTabId: String?,
    val title: String?,
    val allWindowIds: List<String>,
)

/**
 * Walk the live [WindowState.config] and resolve where [sessionId] is
 * shown. Called by the Group A tools to echo `windowId` / `tabId`.
 *
 * @param sessionId the PTY session id to locate.
 * @return placement info; ids are null when no pane references the session.
 */
internal fun placementOf(sessionId: String): SessionPlacement {
    val cfg = WindowState.config.value
    var primaryWindow: String? = null
    var primaryTab: String? = null
    var title: String? = null
    val all = mutableListOf<String>()
    // Span every world's tabs, not just the legacy default-world mirror
    // (cfg.tabs), so sessions living in non-default worlds still resolve
    // their windowId/tabId. Fall back to the mirror when worlds is empty.
    val tabs = cfg.worlds.flatMap { it.tabs }.ifEmpty { cfg.tabs }
    for (tab in tabs) {
        for (pane in tab.panes) {
            if (pane.leaf.sessionId == sessionId) {
                all.add(pane.leaf.id)
                if (primaryWindow == null || (!pane.leaf.isLink && title == null)) {
                    if (primaryWindow == null || !pane.leaf.isLink) {
                        primaryWindow = pane.leaf.id
                        primaryTab = tab.id
                        title = pane.leaf.title
                    }
                }
            }
        }
    }
    return SessionPlacement(primaryWindow, primaryTab, title, all)
}

/** Map a resolved state (`working`/`waiting`/null) onto the MCP vocabulary. */
private fun stateLabel(state: String?): String = state ?: "idle"

/** Build the JSON summary object for one session (list/get share it). */
private fun sessionSummary(
    sessionId: String,
    session: TermSession,
    states: Map<String, String?>,
): kotlinx.serialization.json.JsonObject {
    val placement = placementOf(sessionId)
    val (cols, rows) = session.sizeEvents.value
    return buildJsonObject {
        put("sessionId", sessionId)
        put("windowId", placement.primaryWindowId)
        put("tabId", placement.primaryTabId)
        put("title", placement.title)
        put("cwd", session.cwd.value)
        put("programTitle", session.programTitle.value)
        put("state", stateLabel(states[sessionId]))
        put("processAlive", session.isProcessAlive())
        put("bytesWritten", session.bytesWritten())
        put("cols", cols)
        put("rows", rows)
        if (placement.allWindowIds.size > 1) {
            put("linkedWindowIds", buildJsonArray {
                placement.allWindowIds.forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}

/** Resolve a live session or throw the standard argument error. */
internal fun requireSession(sessionId: String): TermSession =
    TerminalSessions.get(sessionId)
        ?: throw McpArgumentException("Unknown sessionId: $sessionId")

// ── Registration ─────────────────────────────────────────────────────────

/**
 * Register every Group A and Group B tool with [McpServer]. Called once at
 * boot from [mcpRoutes].
 *
 * @param settingsRepo used for feature-state messages (e.g. explaining a
 *   disabled Claude usage monitor).
 * @param usageMonitor the process-wide Claude usage monitor whose latest
 *   snapshot backs `get_claude_usage`.
 */
fun registerMcpReadTools(settingsRepo: SettingsRepository, usageMonitor: ClaudeUsageMonitor) {

    // ── Group A ──────────────────────────────────────────────────────────

    McpServer.register(McpTool(
        name = "list_sessions",
        description = "List every live terminal session with its window/tab placement, title, " +
            "cwd, detected AI-assistant state (working/waiting/idle), and PTY liveness.",
        inputSchema = schemaObject(),
    ) { _, _ ->
        val states = TerminalSessions.resolveStates()
        McpToolResult.json(buildJsonObject {
            put("sessions", buildJsonArray {
                for ((id, session) in TerminalSessions.list()) {
                    add(sessionSummary(id, session, states))
                }
            })
        })
    })

    McpServer.register(McpTool(
        name = "get_session",
        description = "Get full details for one terminal session: window/tab placement, title, " +
            "cwd, program title, state, PTY size and liveness.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The session id (from list_sessions)."),
            required = listOf("sessionId"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        val session = requireSession(sessionId)
        McpToolResult.json(sessionSummary(sessionId, session, TerminalSessions.resolveStates()))
    })

    McpServer.register(McpTool(
        name = "list_layout",
        description = "The full window layout across every world: each tab (annotated with its " +
            "worldId/worldName) and its windows (panes), each window's kind " +
            "(terminal/fileBrowser/git/agent), geometry (fractions of the tab area), stacking, " +
            "focus, and backing sessionId. Use list_worlds for a worlds-only summary.",
        inputSchema = schemaObject(),
    ) { _, _ ->
        val cfg = WindowState.config.value
        // Iterate every world so tabs in non-default worlds are included; the
        // legacy cfg.tabs mirror only holds the default world's tabs. Fall
        // back to the mirror as a synthetic default world when worlds is empty.
        val worlds = cfg.worlds.ifEmpty {
            listOf(se.soderbjorn.lunamux.WorldConfig(id = "", name = "", tabs = cfg.tabs, activeTabId = cfg.activeTabId))
        }
        McpToolResult.json(buildJsonObject {
            put("activeTabId", cfg.activeTabId)
            put("activeWorldId", WindowState.activeWorldId())
            put("tabs", buildJsonArray {
                for (world in worlds) for (tab in world.tabs) {
                    add(buildJsonObject {
                        put("tabId", tab.id)
                        put("worldId", world.id)
                        put("worldName", world.name)
                        put("title", tab.title)
                        put("hidden", tab.isHidden)
                        put("layoutPreset", tab.layoutPreset)
                        put("focusedWindowId", tab.focusedPaneId)
                        put("windows", buildJsonArray {
                            for (pane in tab.panes.sortedBy { it.z }) {
                                add(buildJsonObject {
                                    put("windowId", pane.leaf.id)
                                    put("title", pane.leaf.title)
                                    // `else` covers AgentContent (added in the
                                    // agent-console phase) and any future kind.
                                    put("kind", when (pane.leaf.content) {
                                        is TerminalContent, null -> "terminal"
                                        is FileBrowserContent -> "fileBrowser"
                                        is GitContent -> "git"
                                        is WebBrowserContent -> "webBrowser"
                                        else -> "agent"
                                    })
                                    put("sessionId", pane.leaf.sessionId.takeIf { it.isNotEmpty() })
                                    put("cwd", pane.leaf.cwd)
                                    put("isLink", pane.leaf.isLink)
                                    put("x", pane.x)
                                    put("y", pane.y)
                                    put("width", pane.width)
                                    put("height", pane.height)
                                    put("z", pane.z)
                                    put("maximized", pane.maximized)
                                })
                            }
                        })
                    })
                }
            })
        })
    })

    McpServer.register(McpTool(
        name = "list_worlds",
        description = "List every world (named workspace above tabs): its id, name, whether it is " +
            "the active world, its tab count, and its per-world theme pair (null when the world " +
            "follows the global theme). Use list_layout to see the tabs/windows within each world.",
        inputSchema = schemaObject(),
    ) { _, _ ->
        val cfg = WindowState.config.value
        val activeWorldId = WindowState.activeWorldId()
        McpToolResult.json(buildJsonObject {
            put("activeWorldId", activeWorldId)
            put("worlds", buildJsonArray {
                for (world in cfg.worlds) {
                    add(buildJsonObject {
                        put("worldId", world.id)
                        put("name", world.name)
                        put("active", world.id == activeWorldId)
                        put("activeTabId", world.activeTabId)
                        put("tabCount", world.tabs.size)
                        val theme = world.themeSelection
                        if (theme != null) {
                            put("theme", buildJsonObject {
                                put("darkThemeName", theme.darkThemeName)
                                put("lightThemeName", theme.lightThemeName)
                            })
                        } else {
                            put("theme", JsonPrimitive(null as String?))
                        }
                    })
                }
            })
        })
    })

    McpServer.register(McpTool(
        name = "get_state",
        description = "Detected AI-assistant state per session: 'working' (generating/executing), " +
            "'waiting' (blocked on user confirmation) or 'idle'. Pass sessionId for a single " +
            "session, omit it for all.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("Optional session id; omit for every session."),
        ),
    ) { _, args ->
        val states = TerminalSessions.resolveStates()
        val one = args.optString("sessionId")
        if (one != null) {
            requireSession(one)
            McpToolResult.json(buildJsonObject {
                put("sessionId", one)
                put("state", stateLabel(states[one]))
            })
        } else {
            McpToolResult.json(buildJsonObject {
                put("states", buildJsonObject {
                    for ((id, st) in states) put(id, stateLabel(st))
                })
            })
        }
    })

    McpServer.register(McpTool(
        name = "get_claude_usage",
        description = "Latest Claude Code subscription usage snapshot (session %, weekly %, " +
            "per-model rows, reset times) scraped by the built-in usage monitor.",
        inputSchema = schemaObject(),
    ) { _, _ ->
        val data = usageMonitor.current()
        when {
            data != null -> McpToolResult.json(
                mcpJson.encodeToJsonElement(
                    se.soderbjorn.lunamux.ClaudeUsageData.serializer(), data,
                ),
            )
            !settingsRepo.isClaudeUsagePollEnabled() -> McpToolResult.error(
                "Claude usage polling is disabled — enable it in Lunamux's settings dialog.",
            )
            else -> McpToolResult.error(
                "No usage data yet — the monitor is running but hasn't parsed a snapshot.",
            )
        }
    })

    McpServer.register(McpTool(
        name = "read_scrollback",
        description = "Read a session's recent output as plain text (ANSI stripped). " +
            "source='scrollback' (default) tails the recent output history; source='screen' " +
            "returns the currently rendered viewport (useful for full-screen TUIs).",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The session id (from list_sessions)."),
            "lines" to schemaInt("Tail this many lines (default $DEFAULT_SCROLLBACK_LINES, max $MAX_SCROLLBACK_LINES)."),
            "source" to schemaString("Where to read from.", enum = listOf("scrollback", "screen")),
            required = listOf("sessionId"),
        ),
    ) { _, args ->
        val session = requireSession(args.requireString("sessionId"))
        val lines = (args.optInt("lines") ?: DEFAULT_SCROLLBACK_LINES)
            .coerceIn(1, MAX_SCROLLBACK_LINES)
        val source = args.optString("source") ?: "scrollback"
        val text = when (source) {
            "screen" -> session.screenText().trimEnd()
            else -> stripAnsi(session.transcriptText())
        }
        val tail = text.lines().takeLast(lines).joinToString("\n")
        McpToolResult.text(tail)
    })

    // ── Group B ──────────────────────────────────────────────────────────

    McpServer.register(McpTool(
        name = "watch_output",
        description = "Block until a session prints output matching a regex (matched against " +
            "ANSI-stripped text), or the timeout elapses. Returns the matching text.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The session id to watch."),
            "pattern" to schemaString("Regular expression to match against new output."),
            "timeoutMs" to schemaInt("Give up after this long (default $DEFAULT_WATCH_TIMEOUT_MS, max $MAX_WATCH_TIMEOUT_MS)."),
            required = listOf("sessionId", "pattern"),
        ),
    ) { _, args ->
        val session = requireSession(args.requireString("sessionId"))
        val pattern = args.requireString("pattern")
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            throw McpArgumentException("Invalid regex: ${e.message}")
        }
        val timeoutMs = watchTimeout(args)
        // Rolling window of recent output so matches can span chunk
        // boundaries without unbounded memory on chatty sessions.
        val window = StringBuilder()
        var matchedValue: String? = null
        var matchedLine: String? = null
        try {
            // A SharedFlow collect never completes on its own; either the
            // timeout cancels it or the WatchDone sentinel breaks us out.
            withTimeoutOrNull(timeoutMs) {
                session.output.collect { chunk ->
                    window.append(chunk.toString(Charsets.UTF_8))
                    if (window.length > 64 * 1024) window.delete(0, window.length - 32 * 1024)
                    val stripped = stripAnsi(window.toString())
                    val m = regex.find(stripped)
                    if (m != null) {
                        matchedValue = m.value
                        val s = stripped.lastIndexOf('\n', m.range.first)
                            .let { if (it < 0) 0 else it + 1 }
                        val e = stripped.indexOf('\n', m.range.last)
                            .let { if (it < 0) stripped.length else it }
                        matchedLine = stripped.substring(s, e)
                        throw WatchDone
                    }
                }
            }
        } catch (_: WatchDone) {
            // Match found — fall through to the result below.
        }
        if (matchedValue != null) {
            McpToolResult.json(buildJsonObject {
                put("matched", true)
                put("match", matchedValue)
                put("line", matchedLine)
            })
        } else {
            McpToolResult.json(buildJsonObject {
                put("matched", false)
                put("timedOutAfterMs", timeoutMs)
            })
        }
    })

    McpServer.register(McpTool(
        name = "wait_for_idle",
        description = "Block until a session goes quiet: no output for quietMs AND its detected " +
            "state is not 'working'. Returns whether idleness was reached before the timeout.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The session id to watch."),
            "quietMs" to schemaInt("Required quiet window in ms (default 2000)."),
            "timeoutMs" to schemaInt("Give up after this long (default $DEFAULT_WATCH_TIMEOUT_MS, max $MAX_WATCH_TIMEOUT_MS)."),
            required = listOf("sessionId"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        val session = requireSession(sessionId)
        val quietMs = (args.optInt("quietMs") ?: 2_000).coerceIn(100, 60_000).toLong()
        val timeoutMs = watchTimeout(args)
        val deadline = System.currentTimeMillis() + timeoutMs
        var idle = false
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val burst = withTimeoutOrNull(minOf(quietMs, remaining)) { session.output.first() }
            if (burst != null) continue // output arrived — restart the quiet window
            val working = TerminalSessions.resolveStates()[sessionId] == "working"
            if (!working) {
                idle = true
                break
            }
        }
        McpToolResult.json(buildJsonObject {
            put("sessionId", sessionId)
            put("idle", idle)
            if (!idle) put("timedOutAfterMs", timeoutMs)
        })
    })

    McpServer.register(McpTool(
        name = "wait_for_exit",
        description = "Block until the session's shell process exits (PTY death), or the timeout " +
            "elapses. Note this waits for the whole shell, not a single command — use " +
            "run_command with waitForExit for per-command completion.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The session id to watch."),
            "timeoutMs" to schemaInt("Give up after this long (default $DEFAULT_WATCH_TIMEOUT_MS, max $MAX_WATCH_TIMEOUT_MS)."),
            required = listOf("sessionId"),
        ),
    ) { _, args ->
        val sessionId = args.requireString("sessionId")
        val session = requireSession(sessionId)
        val timeoutMs = watchTimeout(args)
        val deadline = System.currentTimeMillis() + timeoutMs
        var exited = !session.isProcessAlive()
        while (!exited && System.currentTimeMillis() < deadline) {
            delay(250)
            exited = !session.isProcessAlive()
        }
        McpToolResult.json(buildJsonObject {
            put("sessionId", sessionId)
            put("exited", exited)
            if (!exited) put("timedOutAfterMs", timeoutMs)
        })
    })
}

/** Sentinel used to break out of a `SharedFlow.collect` in `watch_output`. */
private object WatchDone : Exception() {
    private fun readResolve(): Any = WatchDone
}

/** Extract + clamp the shared `timeoutMs` argument for Group B tools. */
private fun watchTimeout(args: kotlinx.serialization.json.JsonObject): Long =
    (args.optInt("timeoutMs") ?: DEFAULT_WATCH_TIMEOUT_MS)
        .coerceIn(100, MAX_WATCH_TIMEOUT_MS).toLong()
