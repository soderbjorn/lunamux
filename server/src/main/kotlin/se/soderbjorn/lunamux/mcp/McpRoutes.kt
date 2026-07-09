/**
 * Ktor transport for the Lunamux MCP server: the `/mcp` streamable-HTTP
 * endpoint (POST for JSON-RPC messages, GET for a server→client SSE
 * stream, DELETE for session goodbye) plus its authorization gate.
 *
 * Policy (settled in the implementation plan):
 *  - **Localhost-only, unconditionally** — non-loopback peers are rejected
 *    regardless of the "allow remote connections" UI toggle.
 *  - **Kill switch** — every request checks
 *    [SettingsRepository.isMcpEnabled], so flipping the switch in the
 *    settings dialog takes effect immediately.
 *  - **MCP trust class** — only tokens minted with the
 *    [DeviceAuth.MCP_LABEL] label (and a scope) authorize; regular device
 *    tokens are rejected, and there is never an interactive prompt.
 *
 * Long-running tool calls (Group B watch tools) are answered as an SSE
 * stream with periodic keepalive comments when the client's `Accept`
 * header permits `text/event-stream`, so the response connection stays
 * demonstrably alive while the tool blocks.
 *
 * Mounted from [se.soderbjorn.lunamux.module] at the end of the
 * routing block.
 *
 * @see McpServer
 * @see registerMcpReadTools
 */
package se.soderbjorn.lunamux.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.readAuthToken
import se.soderbjorn.lunamux.readClientInfo

private val log = LoggerFactory.getLogger("McpRoutes")

/** Header carrying the MCP session id (per the streamable-HTTP spec). */
private const val SESSION_HEADER = "Mcp-Session-Id"

/** Keepalive cadence for SSE responses while a tool call blocks. */
private const val SSE_KEEPALIVE_MS = 15_000L

/**
 * Authorize an `/mcp` request. On failure the response has already been
 * sent (403/401) and `null` is returned.
 *
 * @receiver the incoming call.
 * @param repo the settings store (kill switch + trusted tokens).
 * @return the token's [McpScope], or null when rejected.
 */
private suspend fun ApplicationCall.mcpAuthorize(repo: SettingsRepository): McpScope? {
    val remote = request.origin.remoteAddress
    if (!DeviceAuth.isLoopback(remote)) {
        log.info("MCP: rejecting non-loopback connection from {}", remote)
        respond(HttpStatusCode.Forbidden, "MCP is localhost-only")
        return null
    }
    if (!repo.isMcpEnabled()) {
        respond(HttpStatusCode.Forbidden, "MCP is disabled in Lunamux settings")
        return null
    }
    val token = readAuthToken()
    val scope = DeviceAuth.authorizeMcp(token, readClientInfo(), repo)
    if (scope == null) {
        log.info("MCP: rejecting request with missing/unknown/non-MCP token from {}", remote)
        respond(HttpStatusCode.Unauthorized, "A valid MCP token is required (X-Termtastic-Auth)")
        return null
    }
    return if (scope == DeviceAuth.MCP_SCOPE_READ_WRITE) McpScope.READ_WRITE else McpScope.READ
}

/**
 * Mount the `/mcp` endpoint on this [Route] and register all MCP tools.
 * Called by `Application.module`; public so in-repo examples (see
 * `examples/snake-mcp`) can mount the identical MCP surface on a test
 * listener.
 *
 * @param settingsRepo the SQLite-backed settings store (auth, kill switch).
 * @param usageMonitor the Claude usage monitor backing `get_claude_usage`.
 */
fun Route.mcpRoutes(
    settingsRepo: SettingsRepository,
    usageMonitor: ClaudeUsageMonitor,
) {
    registerMcpReadTools(settingsRepo, usageMonitor)
    registerMcpWriteTools(settingsRepo)
    registerMcpConsoleTools()

    post("/mcp") {
        val scope = call.mcpAuthorize(settingsRepo) ?: return@post
        val session = McpSessions.touch(call.request.header(SESSION_HEADER))
        val body = call.receiveText()
        val acceptsSse = call.request.header(HttpHeaders.Accept)
            ?.contains("text/event-stream", ignoreCase = true) == true

        // Only tool calls can block for long; everything else (initialize,
        // tools/list, ping, notifications) answers fast as plain JSON —
        // which also lets `initialize` set the session header before any
        // body bytes are streamed.
        val isToolCall = body.contains("\"tools/call\"")
        if (acceptsSse && isToolCall) {
            call.respondTextWriter(ContentType.Text.EventStream) {
                coroutineScope {
                    val pending = async { McpServer.handleMessage(body, scope, session) }
                    while (true) {
                        val finished = withTimeoutOrNull(SSE_KEEPALIVE_MS) { pending.join() }
                        if (finished != null) break
                        // Comment line per the SSE spec — ignored by clients,
                        // keeps intermediaries from timing the response out.
                        write(": keepalive\n\n")
                        flush()
                    }
                    val handled = pending.await()
                    if (handled.body != null) {
                        write("event: message\ndata: ${handled.body}\n\n")
                        flush()
                    }
                }
            }
        } else {
            val handled = McpServer.handleMessage(body, scope, session)
            handled.newSessionId?.let { call.response.header(SESSION_HEADER, it) }
            if (handled.body == null) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respondText(handled.body, ContentType.Application.Json)
            }
        }
    }

    // Optional server→client stream from the streamable-HTTP spec. This
    // server never pushes unsolicited messages, so the stream only carries
    // keepalives — it exists so clients that open it don't get a 405.
    get("/mcp") {
        call.mcpAuthorize(settingsRepo) ?: return@get
        McpSessions.touch(call.request.header(SESSION_HEADER))
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Runs until the client closes the connection; the write then
            // throws and Ktor tears the handler down.
            while (true) {
                write(": keepalive\n\n")
                flush()
                delay(SSE_KEEPALIVE_MS)
            }
        }
    }

    // Session goodbye: the client is done. Ends the session, which runs
    // its close hooks (e.g. tearing down ephemeral agent consoles).
    delete("/mcp") {
        call.mcpAuthorize(settingsRepo) ?: return@delete
        val id = call.request.header(SESSION_HEADER)
        if (id != null && McpSessions.terminate(id)) {
            call.respond(HttpStatusCode.OK)
        } else {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}
