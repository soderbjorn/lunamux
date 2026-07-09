/**
 * Hand-rolled MCP (Model Context Protocol) server core for Lunamux.
 *
 * This file contains the protocol layer of the `/mcp` endpoint:
 *  - [McpServer] — JSON-RPC 2.0 message dispatch (`initialize`,
 *    `tools/list`, `tools/call`, `ping`) plus the process-wide tool
 *    registry.
 *  - [McpTool] / [McpToolResult] — the registry entry and result envelope
 *    each tool handler produces.
 *  - [McpSession] / [McpSessions] — lightweight per-client sessions keyed
 *    by the `Mcp-Session-Id` header, used to scope client-owned resources
 *    (e.g. ephemeral agent consoles) and tear them down on disconnect.
 *  - JSON Schema builder helpers ([schemaObject], [schemaString], …) used
 *    by the tool registration files.
 *
 * The protocol surface is implemented by hand (rather than via the
 * official `io.modelcontextprotocol:kotlin-sdk`) because the server pins
 * its bytecode to Java 11 while the SDK targets JVM 17 — see
 * `server/build.gradle.kts`. Only `kotlinx.serialization` (an existing
 * dependency) is used.
 *
 * Transport (Ktor routes, auth, SSE framing) lives in [McpRoutes];
 * tool implementations live in `McpReadTools.kt` (and sibling files).
 *
 * @see se.soderbjorn.lunamux.mcp.mcpRoutes
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Lenient JSON used for all MCP wire encoding/decoding. */
internal val mcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * Access scope carried by an MCP token, mapped 1:1 from the persisted
 * token scope string (see `DeviceAuth.MCP_SCOPE_READ` /
 * `DeviceAuth.MCP_SCOPE_READ_WRITE`).
 */
enum class McpScope {
    /** Read/watch tools only. */
    READ,

    /** All tools, including window/tab mutation and input injection. */
    READ_WRITE;

    /** Whether this scope may invoke a tool flagged [McpTool.requiresWrite]. */
    fun allowsWrite(): Boolean = this == READ_WRITE
}

/**
 * One MCP client session, minted on `initialize` and identified to the
 * client via the `Mcp-Session-Id` response header. Sessions carry no
 * protocol state beyond liveness — their purpose is ownership: resources a
 * client creates (Phase 3's ephemeral agent consoles) register a close
 * hook here and are torn down when the session ends.
 *
 * A session ends when the client sends `DELETE /mcp` (the MCP streamable-
 * HTTP goodbye), or when [McpSessions.sweepIdle] reaps it after prolonged
 * inactivity (a crashed client never says goodbye).
 *
 * @property id the opaque session id (hex, unguessable).
 */
class McpSession(val id: String) {

    /** Epoch-ms of the last request seen on this session (any verb). */
    @Volatile
    var lastSeenEpochMs: Long = System.currentTimeMillis()

    private val closed = AtomicBoolean(false)
    private val closeHooks = CopyOnWriteArrayList<() -> Unit>()

    /** Whether [close] has run. */
    fun isClosed(): Boolean = closed.get()

    /**
     * Register [hook] to run when this session ends. Runs immediately if
     * the session is already closed (registration/close race).
     *
     * @param hook teardown callback; must not throw (failures are logged).
     */
    fun onClose(hook: () -> Unit) {
        closeHooks.add(hook)
        if (closed.get()) close()
    }

    /**
     * End the session, running (and draining) every registered close hook
     * exactly once each. Idempotent.
     */
    fun close() {
        closed.set(true)
        while (true) {
            val hook = closeHooks.removeFirstOrNull() ?: break
            runCatching { hook() }.onFailure {
                LoggerFactory.getLogger(McpSession::class.java)
                    .warn("MCP session {} close hook failed", id, it)
            }
        }
    }

    private fun CopyOnWriteArrayList<() -> Unit>.removeFirstOrNull(): (() -> Unit)? =
        try {
            removeAt(0)
        } catch (_: IndexOutOfBoundsException) {
            null
        }
}

/**
 * Process-wide registry of live [McpSession]s, keyed by session id.
 * Sessions are created by `initialize`, touched on every request, ended by
 * `DELETE /mcp`, and swept when idle for over [IDLE_TIMEOUT_MS].
 */
object McpSessions {
    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val rng = SecureRandom()

    /** Sessions idle longer than this are reaped by [sweepIdle]. */
    private const val IDLE_TIMEOUT_MS: Long = 30 * 60 * 1000

    /** Mint and register a fresh session with an unguessable id. */
    fun create(): McpSession {
        val bytes = ByteArray(16).also { rng.nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        val session = McpSession(id)
        sessions[id] = session
        return session
    }

    /**
     * Look up a live session, bumping its last-seen stamp. Also runs an
     * opportunistic idle sweep so abandoned sessions get reaped without a
     * dedicated timer thread.
     *
     * @param id the value of the client's `Mcp-Session-Id` header.
     * @return the session, or null if unknown/expired.
     */
    fun touch(id: String?): McpSession? {
        sweepIdle()
        if (id == null) return null
        val s = sessions[id] ?: return null
        s.lastSeenEpochMs = System.currentTimeMillis()
        return s
    }

    /**
     * End and remove the session with [id] (the `DELETE /mcp` path).
     *
     * @return true if a session was found and closed.
     */
    fun terminate(id: String): Boolean {
        val s = sessions.remove(id) ?: return false
        s.close()
        return true
    }

    /** Reap sessions idle beyond [IDLE_TIMEOUT_MS] (crashed clients). */
    private fun sweepIdle() {
        val cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MS
        for ((id, s) in sessions) {
            if (s.lastSeenEpochMs < cutoff) {
                sessions.remove(id)?.close()
            }
        }
    }
}

/**
 * The result envelope a tool handler returns, mapped onto the MCP
 * `tools/call` result shape (`content` blocks + `isError`).
 *
 * @property content MCP content blocks (usually a single `text` block).
 * @property isError true when the call failed in a tool-domain way (bad
 *   session id, timeout, …) — protocol-level failures use JSON-RPC errors
 *   instead.
 */
class McpToolResult(val content: JsonArray, val isError: Boolean = false) {
    companion object {
        /** A successful single-text-block result. */
        fun text(text: String): McpToolResult = McpToolResult(
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        )

        /** A successful result carrying [value] pretty-printed as JSON text. */
        fun json(value: JsonElement): McpToolResult =
            text(prettyMcpJson.encodeToString(JsonElement.serializer(), value))

        /** A failed (isError=true) single-text-block result. */
        fun error(message: String): McpToolResult = McpToolResult(
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", message)
                })
            },
            isError = true,
        )

        private val prettyMcpJson = Json { prettyPrint = true; encodeDefaults = false }
    }
}

/**
 * Per-call context handed to every tool handler.
 *
 * @property scope the calling token's scope (already enforced for
 *   [McpTool.requiresWrite] tools before the handler runs — carried for
 *   tools that vary behaviour by scope).
 * @property session the caller's [McpSession], or null when the client
 *   never initialized a session (tolerated for stateless calls).
 */
class McpCallContext(
    val scope: McpScope,
    val session: McpSession?,
)

/**
 * A registered MCP tool: name, human description, JSON Schema for its
 * arguments, write-scope requirement, and the suspend handler.
 *
 * @property name tool identifier as exposed by `tools/list`.
 * @property description one-paragraph description shown to the model.
 * @property inputSchema JSON Schema (draft-07 subset) for `arguments`.
 * @property requiresWrite true if the tool mutates state — calls from
 *   read-scope tokens are rejected before the handler runs.
 * @property handler the implementation; exceptions become isError results.
 */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiresWrite: Boolean = false,
    val handler: suspend (McpCallContext, JsonObject) -> McpToolResult,
)

// ── JSON Schema builder helpers ─────────────────────────────────────────

/**
 * Build an `object` JSON Schema from property name → schema pairs.
 *
 * @param properties each tool argument's name and schema (see
 *   [schemaString], [schemaInt], [schemaBool]).
 * @param required names of mandatory properties.
 * @return the schema object for [McpTool.inputSchema].
 */
fun schemaObject(
    vararg properties: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((name, schema) in properties) put(name, schema)
    }
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    }
    put("additionalProperties", false)
}

/**
 * A `string` property schema.
 *
 * @param description shown to the model.
 * @param enum optional closed set of accepted values.
 */
fun schemaString(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
}

/**
 * An `integer` property schema.
 *
 * @param description shown to the model.
 */
fun schemaInt(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

/**
 * A `boolean` property schema.
 *
 * @param description shown to the model.
 */
fun schemaBool(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/**
 * An `array` property schema.
 *
 * @param description shown to the model.
 * @param items the element schema.
 */
fun schemaArray(description: String, items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", items)
}

// ── Argument extraction helpers used by tool handlers ────────────────────

/** Read a required string argument or throw [McpArgumentException]. */
fun JsonObject.requireString(name: String): String =
    optString(name) ?: throw McpArgumentException("Missing required argument '$name'")

/** Read an optional string argument. */
fun JsonObject.optString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

/** Read an optional integer argument (tolerates JSON numbers and numeric strings). */
fun JsonObject.optInt(name: String): Int? = when (val el = this[name]) {
    is JsonPrimitive -> runCatching { el.double.toInt() }.getOrNull()
    else -> null
}

/** Read an optional boolean argument. */
fun JsonObject.optBool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() }

/**
 * Thrown by tool handlers when arguments are missing or malformed;
 * surfaced to the client as an `isError` tool result.
 */
class McpArgumentException(message: String) : IllegalArgumentException(message)

// ── The server ───────────────────────────────────────────────────────────

/**
 * What the route layer should do with a handled message: reply with a
 * JSON body, reply with 202 Accepted (notifications/responses), and/or
 * stamp a freshly minted session id on the response.
 *
 * @property body the serialized JSON-RPC response, or null for "202, no body".
 * @property newSessionId set when `initialize` minted a session the route
 *   must echo via the `Mcp-Session-Id` header.
 */
class McpHandled(val body: String?, val newSessionId: String? = null)

/**
 * The MCP protocol engine: holds the tool registry and turns one inbound
 * JSON-RPC message into at most one response.
 *
 * Thread-safe: registration happens once at boot (from `Application.module`
 * via [mcpRoutes]); dispatch is read-only over the registry.
 */
object McpServer {

    private val log = LoggerFactory.getLogger(McpServer::class.java)

    /** Protocol revisions this server knows; the newest is the default. */
    private val SUPPORTED_PROTOCOL_VERSIONS =
        listOf("2025-06-18", "2025-03-26", "2024-11-05")

    private val tools = LinkedHashMap<String, McpTool>()

    /**
     * Register [tool] in the process-wide registry. Later registrations
     * with the same name replace earlier ones (used by tests).
     */
    fun register(tool: McpTool) {
        synchronized(tools) { tools[tool.name] = tool }
    }

    /** Snapshot of every registered tool, in registration order. */
    fun allTools(): List<McpTool> = synchronized(tools) { tools.values.toList() }

    /**
     * Handle one inbound JSON-RPC message.
     *
     * Called by the `/mcp` POST route after auth. Requests produce a
     * response body; notifications produce `McpHandled(null)` (the route
     * answers 202). Malformed JSON or JSON-RPC batches produce a JSON-RPC
     * error response.
     *
     * @param raw the request body (one JSON-RPC message).
     * @param scope the authenticated token's scope.
     * @param session the caller's session (null before `initialize`).
     * @return what the route layer should send back.
     */
    suspend fun handleMessage(raw: String, scope: McpScope, session: McpSession?): McpHandled {
        val element = runCatching { mcpJson.parseToJsonElement(raw) }.getOrNull()
            ?: return McpHandled(errorResponse(JsonNull, PARSE_ERROR, "Parse error"))
        if (element is JsonArray) {
            // JSON-RPC batching was removed from MCP in 2025-06-18; keep the
            // surface minimal and reject batches outright.
            return McpHandled(errorResponse(JsonNull, INVALID_REQUEST, "Batch requests are not supported"))
        }
        val msg = element as? JsonObject
            ?: return McpHandled(errorResponse(JsonNull, INVALID_REQUEST, "Invalid request"))

        val id: JsonElement? = msg["id"]
        val method = (msg["method"] as? JsonPrimitive)?.contentOrNull

        // A message without a method is a client→server *response* (we never
        // issue server→client requests, so just swallow it); a message
        // without an id is a notification. Neither gets a response body.
        if (method == null) return McpHandled(null)
        if (id == null || id is JsonNull) {
            handleNotification(method)
            return McpHandled(null)
        }

        val params = msg["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> handleInitialize(id, params)
            "ping" -> McpHandled(resultResponse(id, JsonObject(emptyMap())))
            "tools/list" -> McpHandled(resultResponse(id, buildToolsList()))
            "tools/call" -> McpHandled(handleToolsCall(id, params, scope, session))
            else -> McpHandled(errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method"))
        }
    }

    /** Handle a notification (no response). Only the `notifications/…` family matters. */
    private fun handleNotification(method: String) {
        when (method) {
            "notifications/initialized" -> Unit // client handshake complete
            "notifications/cancelled" -> Unit   // best-effort; long tools have their own timeouts
            else -> log.debug("MCP: ignoring notification {}", method)
        }
    }

    /**
     * `initialize`: negotiate the protocol version, advertise the `tools`
     * capability, and mint a session the route layer echoes back via the
     * `Mcp-Session-Id` header.
     */
    private fun handleInitialize(id: JsonElement, params: JsonObject): McpHandled {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val negotiated = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            SUPPORTED_PROTOCOL_VERSIONS.first()
        }
        val session = McpSessions.create()
        val result = buildJsonObject {
            put("protocolVersion", negotiated)
            putJsonObject("capabilities") {
                putJsonObject("tools") { }
            }
            putJsonObject("serverInfo") {
                put("name", "lunamux")
                put("version", "1.0.0")
            }
            put(
                "instructions",
                "Lunamux terminal-multiplexer control surface. Content/exec tools take a " +
                    "sessionId; layout tools take a windowId (a window is a pane in the UI). " +
                    "Read tools echo both so results can be cross-referenced.",
            )
        }
        return McpHandled(resultResponse(id, result), newSessionId = session.id)
    }

    /** Build the `tools/list` result from the registry. */
    private fun buildToolsList(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            for (tool in allTools()) {
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            }
        })
    }

    /** Dispatch `tools/call` to the registered handler, enforcing scope. */
    private suspend fun handleToolsCall(
        id: JsonElement,
        params: JsonObject,
        scope: McpScope,
        session: McpSession?,
    ): String {
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return errorResponse(id, INVALID_PARAMS, "tools/call requires 'name'")
        val tool = synchronized(tools) { tools[name] }
            ?: return errorResponse(id, INVALID_PARAMS, "Unknown tool: $name")
        if (tool.requiresWrite && !scope.allowsWrite()) {
            val scopeName = if (scope == McpScope.READ) "read" else "read+write"
            return toolResultResponse(
                id,
                McpToolResult.error(
                    "Token scope '$scopeName' does not permit '$name' — " +
                        "a read+write MCP token is required.",
                ),
            )
        }
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val result = try {
            tool.handler(McpCallContext(scope, session), args)
        } catch (e: McpArgumentException) {
            McpToolResult.error(e.message ?: "Invalid arguments")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("MCP tool '{}' failed", name, t)
            McpToolResult.error("Tool '$name' failed: ${t.message ?: t::class.simpleName}")
        }
        return toolResultResponse(id, result)
    }

    /** Serialize a JSON-RPC success response for a tool result. */
    private fun toolResultResponse(id: JsonElement, result: McpToolResult): String =
        resultResponse(id, buildJsonObject {
            put("content", result.content)
            if (result.isError) put("isError", true)
        })

    /** Serialize a JSON-RPC success response. */
    private fun resultResponse(id: JsonElement, result: JsonObject): String =
        mcpJson.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )

    /** Serialize a JSON-RPC error response. */
    private fun errorResponse(id: JsonElement, code: Int, message: String): String =
        mcpJson.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            },
        )

    private const val PARSE_ERROR = -32700
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
}
