/**
 * HTTP-level integration tests for the `/mcp` endpoint: token gating,
 * the kill switch, session-header round-trip, and a full
 * initialize → tools/list → tools/call exchange through the real Ktor
 * routing (via the test host, which reports a loopback peer address).
 *
 * The non-loopback rejection path is covered structurally (the route
 * calls [DeviceAuth.isLoopback] on the raw peer address, tested in
 * [McpServerTest]) since the Ktor test engine always reports localhost.
 *
 * @see mcpRoutes
 * @see McpServer
 */
package se.soderbjorn.termtastic.mcp

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.termtastic.ClaudeUsageMonitor
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpRoutesTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("termtastic-mcp-routes", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    private fun app(repo: SettingsRepository, body: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                routing { mcpRoutes(repo, ClaudeUsageMonitor()) }
            }
            body(client)
        }

    @Test
    fun `requests without a valid mcp token are rejected`() {
        val repo = tempRepo()
        app(repo) { client ->
            // No token at all.
            val none = client.post("/mcp") { setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }
            assertEquals(HttpStatusCode.Unauthorized, none.status)
            // A trusted *interactive* token is not an MCP token.
            DeviceAuth.addTrustedToken(repo, "interactive-token", label = "Browser")
            val interactive = client.post("/mcp") {
                header("X-Termtastic-Auth", "interactive-token")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, interactive.status)
        }
    }

    @Test
    fun `kill switch disables the endpoint for valid tokens`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "tok", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
        repo.setMcpEnabled(false)
        app(repo) { client ->
            val resp = client.post("/mcp") {
                header("X-Termtastic-Auth", "tok")
                setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, resp.status)
        }
    }

    @Test
    fun `initialize then tools list and call over http`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "tok", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
        app(repo) { client ->
            val init = client.post("/mcp") {
                header("X-Termtastic-Auth", "tok")
                setBody(
                    """{"jsonrpc":"2.0","id":1,"method":"initialize",
                       "params":{"protocolVersion":"2025-06-18"}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, init.status)
            val sessionId = init.headers["Mcp-Session-Id"]
            assertNotNull(sessionId, "initialize must return a session header")

            val list = client.post("/mcp") {
                header("X-Termtastic-Auth", "tok")
                header("Mcp-Session-Id", sessionId)
                setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            }
            assertEquals(HttpStatusCode.OK, list.status)
            val tools = mcpJson.parseToJsonElement(list.bodyAsText())
                .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "list_sessions" })

            val call = client.post("/mcp") {
                header("X-Termtastic-Auth", "tok")
                header("Mcp-Session-Id", sessionId)
                setBody(
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                       "params":{"name":"list_sessions","arguments":{}}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, call.status)
            assertTrue(call.bodyAsText().contains("sessions"))

            // Goodbye ends the session; a second DELETE finds nothing.
            val bye = client.delete("/mcp") {
                header("X-Termtastic-Auth", "tok")
                header("Mcp-Session-Id", sessionId)
            }
            assertEquals(HttpStatusCode.OK, bye.status)
            val byeAgain = client.delete("/mcp") {
                header("X-Termtastic-Auth", "tok")
                header("Mcp-Session-Id", sessionId)
            }
            assertEquals(HttpStatusCode.NotFound, byeAgain.status)
        }
    }

    @Test
    fun `sse-accepting tool calls stream the result as an event`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "tok", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
        app(repo) { client ->
            val resp = client.post("/mcp") {
                header("X-Termtastic-Auth", "tok")
                header("Accept", "application/json, text/event-stream")
                setBody(
                    """{"jsonrpc":"2.0","id":9,"method":"tools/call",
                       "params":{"name":"list_sessions","arguments":{}}}""",
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("event: message"), "expected an SSE message event, got: $body")
            assertTrue(body.contains("\"jsonrpc\""))
        }
    }
}
