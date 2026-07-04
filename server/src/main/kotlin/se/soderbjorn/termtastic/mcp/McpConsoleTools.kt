/**
 * MCP agent-console tools (plan Groups F and G) for Termtastic.
 *
 * Group F — console lifecycle & transcript: `open_console`,
 * `console_write`, `console_read`, `console_ask`.
 * Group G — paintable screen & discrete input: `console_write_raw`,
 * `screen_draw`, `screen_present`, `screen_config`, `console_poll_input`,
 * `console_read_key`.
 *
 * A console is a PTY-less [AgentSession] registered in
 * [TerminalSessions] and shown as an [se.soderbjorn.termtastic.AgentContent]
 * pane. Both render modes share one output path: raw bytes and
 * translated `screen_draw` ops feed the same [ScreenEmulator]-backed
 * stream that broadcasts to clients over `/pty/{sessionId}` (plus the
 * structured transcript over `/agent/{sessionId}` for transcript mode).
 *
 * **Ephemeral**: `open_console` ties the pane to the calling MCP session
 * — when that session ends (`DELETE /mcp` or the idle sweep), the pane
 * closes and the session is destroyed. Closing the pane from any client
 * also destroys the console.
 *
 * All tools require a `read+write` token and record into the activity
 * log via the same wrapper the other write tools use.
 *
 * @see AgentSession
 * @see registerMcpWriteTools
 */
package se.soderbjorn.termtastic.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.termtastic.AgentSession
import se.soderbjorn.termtastic.TerminalSessions
import se.soderbjorn.termtastic.WindowState

/** Grid bounds accepted by open_console / screen_config. */
private const val MIN_COLS = 4
private const val MAX_COLS = 400
private const val MIN_ROWS = 2
private const val MAX_ROWS = 200

/** Default input-wait timeout for console_read / console_ask / read_key. */
private const val DEFAULT_INPUT_TIMEOUT_MS = 60_000
private const val MAX_INPUT_TIMEOUT_MS = 600_000

/** Resolve a live agent console or throw the standard argument error. */
private fun requireConsole(sessionId: String): AgentSession =
    (TerminalSessions.get(sessionId) as? AgentSession)
        ?: throw McpArgumentException("Not an agent console session: $sessionId")

/** Extract + clamp the shared `timeoutMs` argument for input waits. */
private fun inputTimeout(args: JsonObject): Long =
    ((args.optInt("timeoutMs") ?: DEFAULT_INPUT_TIMEOUT_MS)
        .coerceIn(100, MAX_INPUT_TIMEOUT_MS)).toLong()

// ── screen_draw op translation ───────────────────────────────────────────

/** Named ANSI colors accepted in screen_draw style fields. */
private val COLOR_CODES = mapOf(
    "black" to 0, "red" to 1, "green" to 2, "yellow" to 3,
    "blue" to 4, "magenta" to 5, "cyan" to 6, "white" to 7,
)

/**
 * Resolve a screen_draw color value (a named color or a 0–255 palette
 * index) to its SGR parameter for foreground ([fg]=true) or background.
 */
private fun sgrColor(value: JsonPrimitive, fg: Boolean): String {
    val name = value.contentOrNull?.lowercase() ?: return ""
    val bright = name.startsWith("bright")
    val base = name.removePrefix("bright").removePrefix("-").removePrefix("_")
    COLOR_CODES[base]?.let { code ->
        val offset = when {
            fg && !bright -> 30
            fg && bright -> 90
            !fg && !bright -> 40
            else -> 100
        }
        return "${offset + code}"
    }
    name.toIntOrNull()?.let { idx ->
        if (idx in 0..255) return if (fg) "38;5;$idx" else "48;5;$idx"
    }
    throw McpArgumentException("Unknown color '$name' — use black/red/green/yellow/blue/magenta/cyan/white, bright<color>, or a 0-255 index")
}

/**
 * Translate one `screen_draw` op object into an ANSI/VT string.
 *
 * Supported ops:
 *  - `{"op":"clear"}` — clear the screen, cursor home.
 *  - `{"op":"text","x":..,"y":..,"text":..,"fg"?,"bg"?,"bold"?}` — draw
 *    text at 0-based (x, y) with optional style.
 *  - `{"op":"fill","x","y","width","height","ch"?,"fg"?,"bg"?}` — fill a
 *    rectangle with a character (default space).
 *  - `{"op":"cursor","visible":bool}` — show/hide the cursor.
 *
 * @param op the op object from the tool arguments.
 * @return the ANSI translation to stage.
 */
internal fun translateDrawOp(op: JsonObject): String {
    fun sgrPrefix(): String {
        val parts = mutableListOf<String>()
        if (op.optBool("bold") == true) parts.add("1")
        (op["fg"] as? JsonPrimitive)?.let { parts.add(sgrColor(it, fg = true)) }
        (op["bg"] as? JsonPrimitive)?.let { parts.add(sgrColor(it, fg = false)) }
        return if (parts.isEmpty()) "" else "\u001B[${parts.joinToString(";")}m"
    }

    fun moveTo(x: Int, y: Int) = "\u001B[${y + 1};${x + 1}H"

    return when (val kind = op.optString("op")) {
        "clear" -> "\u001B[0m\u001B[2J\u001B[H"
        "text" -> {
            val x = op.optInt("x") ?: throw McpArgumentException("text op requires x")
            val y = op.optInt("y") ?: throw McpArgumentException("text op requires y")
            val text = (op["text"] as? JsonPrimitive)?.contentOrNull
                ?: throw McpArgumentException("text op requires text")
            // Strip control chars so a draw op can't smuggle arbitrary
            // escape sequences — console_write_raw is the escape hatch.
            val safe = text.filter { it.code >= 0x20 }
            moveTo(x, y) + sgrPrefix() + safe + "\u001B[0m"
        }
        "fill" -> {
            val x = op.optInt("x") ?: throw McpArgumentException("fill op requires x")
            val y = op.optInt("y") ?: throw McpArgumentException("fill op requires y")
            val w = op.optInt("width") ?: throw McpArgumentException("fill op requires width")
            val h = op.optInt("height") ?: throw McpArgumentException("fill op requires height")
            val ch = op.optString("ch")?.firstOrNull()?.takeIf { it.code >= 0x20 } ?: ' '
            if (w <= 0 || h <= 0) return ""
            val row = ch.toString().repeat(w)
            buildString {
                append(sgrPrefix())
                for (dy in 0 until h) {
                    append(moveTo(x, y + dy))
                    append(row)
                }
                append("\u001B[0m")
            }
        }
        "cursor" -> if (op.optBool("visible") == true) "\u001B[?25h" else "\u001B[?25l"
        else -> throw McpArgumentException("Unknown draw op '$kind' — use clear/text/fill/cursor")
    }
}

// ── Registration ─────────────────────────────────────────────────────────

/**
 * Register every Group F and G tool with [McpServer]. Called once at boot
 * from [mcpRoutes], after the read/write tool registrations.
 */
fun registerMcpConsoleTools() {

    McpServer.register(McpTool(
        name = "open_console",
        description = "Open an agent console window: a virtual session you (the agent) drive. " +
            "renderMode 'transcript' gives a conversation log + user input line " +
            "(console_write/console_ask); 'screen' gives an addressable character grid " +
            "(screen_draw/screen_present) with discrete key input (console_poll_input). " +
            "The console is ephemeral: it closes when your MCP session ends.",
        inputSchema = schemaObject(
            "renderMode" to schemaString("Console mode.", enum = listOf("transcript", "screen")),
            "title" to schemaString("Window title (default 'Agent console')."),
            "cols" to schemaInt("Screen-mode grid width (default 80)."),
            "rows" to schemaInt("Screen-mode grid height (default 24)."),
            "tabId" to schemaString("Tab to open the console in (default: active tab)."),
        ),
        requiresWrite = true,
    ) { ctx, args ->
        val renderMode = args.optString("renderMode") ?: "transcript"
        if (renderMode !in listOf("transcript", "screen")) {
            throw McpArgumentException("renderMode must be transcript or screen")
        }
        val cols = (args.optInt("cols") ?: 80).coerceIn(MIN_COLS, MAX_COLS)
        val rows = (args.optInt("rows") ?: 24).coerceIn(MIN_ROWS, MAX_ROWS)
        val title = args.optString("title") ?: "Agent console"
        val cfg = WindowState.config.value
        val tabId = args.optString("tabId")
            ?: cfg.activeTabId ?: cfg.tabs.firstOrNull()?.id
            ?: throw McpArgumentException("No tabs exist")

        val session = AgentSession(
            renderMode = renderMode,
            initialCols = cols,
            initialRows = rows,
            fixedGrid = renderMode == "screen",
        )
        val sessionId = TerminalSessions.registerAgent(session)
        val leaf = WindowState.addAgentToTab(
            tabId = tabId,
            sessionId = sessionId,
            title = title,
            renderMode = renderMode,
            cols = if (renderMode == "screen") cols else null,
            rows = if (renderMode == "screen") rows else null,
        ) ?: run {
            TerminalSessions.destroy(sessionId)
            throw McpArgumentException("Unknown tabId: $tabId")
        }

        if (renderMode == "screen") {
            // Start from a clean hidden-cursor grid so the first present
            // paints onto black instead of stale content.
            session.emitOutput("\u001B[?25l\u001B[2J\u001B[H".toByteArray(Charsets.US_ASCII))
        }

        // Ephemerality: closing the owning MCP session closes the pane,
        // and closing the pane (from any client) GC-destroys the session.
        val windowId = leaf.id
        ctx.session?.onClose {
            WindowState.closePane(windowId)
        }

        McpActivityLog.record("open_console", "mode=$renderMode tab=$tabId window=$windowId")
        McpToolResult.json(buildJsonObject {
            put("sessionId", sessionId)
            put("windowId", windowId)
            put("tabId", tabId)
            put("renderMode", renderMode)
            put("cols", cols)
            put("rows", rows)
            if (ctx.session == null) {
                put("note", "No MCP session established — the console will only be torn down when its pane is closed.")
            }
        })
    })

    McpServer.register(McpTool(
        name = "console_write",
        description = "Append text to an agent console's transcript (visible on every device).",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id (from open_console)."),
            "text" to schemaString("The text to append (may be multi-line)."),
            required = listOf("sessionId", "text"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpArgumentException("Missing required argument 'text'")
        session.postTranscript("output", text)
        McpToolResult.text("Appended ${text.length} chars")
    })

    McpServer.register(McpTool(
        name = "console_read",
        description = "Wait for the user to submit a line in the console (no prompt is shown — " +
            "use console_ask to show one).",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "timeoutMs" to schemaInt("Max wait (default $DEFAULT_INPUT_TIMEOUT_MS, max $MAX_INPUT_TIMEOUT_MS)."),
            required = listOf("sessionId"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val line = session.readLine(inputTimeout(args))
        McpToolResult.json(buildJsonObject {
            put("answered", line != null)
            put("text", line)
        })
    })

    McpServer.register(McpTool(
        name = "console_ask",
        description = "Show a prompt in the console and wait for the user's typed reply.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "prompt" to schemaString("The question to show."),
            "timeoutMs" to schemaInt("Max wait (default $DEFAULT_INPUT_TIMEOUT_MS, max $MAX_INPUT_TIMEOUT_MS)."),
            required = listOf("sessionId", "prompt"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        session.postTranscript("prompt", args.requireString("prompt"))
        val line = session.readLine(inputTimeout(args))
        McpToolResult.json(buildJsonObject {
            put("answered", line != null)
            put("text", line)
        })
    })

    McpServer.register(McpTool(
        name = "console_write_raw",
        description = "Feed raw bytes (including ANSI/VT escape sequences) straight into the " +
            "console's terminal stream. The low-level escape hatch behind screen_draw.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "data" to schemaString("The raw data (UTF-8; JSON escapes like \\u001b work)."),
            required = listOf("sessionId", "data"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val data = (args["data"] as? JsonPrimitive)?.contentOrNull
            ?: throw McpArgumentException("Missing required argument 'data'")
        session.emitOutput(data.toByteArray(Charsets.UTF_8))
        McpToolResult.text("Wrote ${data.length} chars")
    })

    McpServer.register(McpTool(
        name = "screen_draw",
        description = "Stage drawing operations on a screen-mode console's grid (double-buffered — " +
            "nothing is visible until screen_present). Ops: {op:'clear'}, " +
            "{op:'text',x,y,text,fg?,bg?,bold?}, {op:'fill',x,y,width,height,ch?,fg?,bg?}, " +
            "{op:'cursor',visible}. Coordinates are 0-based; colors are named " +
            "(red, brightgreen, …) or 0-255 palette indices.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "ops" to schemaArray("Drawing operations, applied in order.", buildJsonObject {
                put("type", "object")
            }),
            required = listOf("sessionId", "ops"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val ops = (args["ops"] as? JsonArray)
            ?: throw McpArgumentException("Missing required argument 'ops' (array)")
        val translated = StringBuilder()
        for (op in ops) {
            translated.append(translateDrawOp(op.jsonObject))
        }
        session.stage(translated.toString())
        McpToolResult.text("Staged ${ops.size} op(s)")
    })

    McpServer.register(McpTool(
        name = "screen_present",
        description = "Flush everything staged by screen_draw to the console as one atomic frame.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            required = listOf("sessionId"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val bytes = session.present()
        McpToolResult.json(buildJsonObject { put("flushedBytes", bytes) })
    })

    McpServer.register(McpTool(
        name = "screen_config",
        description = "Reconfigure a screen-mode console: grid size and/or cursor visibility.",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "cols" to schemaInt("New grid width."),
            "rows" to schemaInt("New grid height."),
            "cursorVisible" to schemaBool("Show or hide the cursor."),
            required = listOf("sessionId"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val cols = args.optInt("cols")
        val rows = args.optInt("rows")
        if ((cols == null) != (rows == null)) {
            throw McpArgumentException("Provide cols and rows together")
        }
        if (cols != null && rows != null) {
            session.configureGrid(cols.coerceIn(MIN_COLS, MAX_COLS), rows.coerceIn(MIN_ROWS, MAX_ROWS))
        }
        args.optBool("cursorVisible")?.let { visible ->
            session.emitOutput((if (visible) "\u001B[?25h" else "\u001B[?25l").toByteArray(Charsets.US_ASCII))
        }
        val (c, r) = session.sizeEvents.value
        McpToolResult.json(buildJsonObject {
            put("cols", c)
            put("rows", r)
        })
    })

    McpServer.register(McpTool(
        name = "console_poll_input",
        description = "Drain pending input events from a console without blocking: key events " +
            "({type:'key',key:'up'|'a'|'ctrl+c'|…}) and resize events ({type:'resize',cols,rows}).",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "max" to schemaInt("Max events to return (default 64)."),
            required = listOf("sessionId"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val events = session.pollEvents((args.optInt("max") ?: 64).coerceIn(1, 512))
        McpToolResult.json(buildJsonObject {
            put("events", buildJsonArray {
                for (e in events) {
                    add(buildJsonObject {
                        put("type", e.type)
                        if (e.key != null) put("key", e.key)
                        if (e.cols != null) put("cols", e.cols)
                        if (e.rows != null) put("rows", e.rows)
                    })
                }
            })
        })
    })

    McpServer.register(McpTool(
        name = "console_read_key",
        description = "Block until one input event arrives from the console (or the timeout).",
        inputSchema = schemaObject(
            "sessionId" to schemaString("The console's session id."),
            "timeoutMs" to schemaInt("Max wait (default $DEFAULT_INPUT_TIMEOUT_MS, max $MAX_INPUT_TIMEOUT_MS)."),
            required = listOf("sessionId"),
        ),
        requiresWrite = true,
    ) { _, args ->
        val session = requireConsole(args.requireString("sessionId"))
        val event = session.awaitEvent(inputTimeout(args))
        McpToolResult.json(buildJsonObject {
            put("received", event != null)
            if (event != null) {
                put("type", event.type)
                if (event.key != null) put("key", event.key)
                if (event.cols != null) put("cols", event.cols)
                if (event.rows != null) put("rows", event.rows)
            }
        })
    })
}
