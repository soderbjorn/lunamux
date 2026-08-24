/**
 * Regression test for the SSE keepalive cadence on the `/mcp` endpoint.
 *
 * A long-running `tools/call` is answered as an SSE stream (see [mcpRoutes]).
 * Ktor's Netty engine applies a `responseWriteTimeoutSeconds` of 10s by
 * default, so if the first keepalive is written later than that — or if two
 * consecutive writes are more than 10s apart — Netty tears the connection
 * down mid-call. HTTP/2 clients (curl's ALPN default) are unaffected, which
 * is why this hid for so long: the primary consumer, Claude Code, speaks
 * HTTP/1.1 only (Node/undici) and saw every watch tool die at exactly 10s.
 *
 * This boots a *real* Netty server on a real port — the Ktor test engine
 * (used by [McpRoutesTest]) has no Netty pipeline and cannot reproduce it —
 * and drives it with an explicitly HTTP/1.1 client.
 *
 * @see mcpRoutes
 */
package se.soderbjorn.lunamux.mcp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertTrue

class McpSseKeepaliveTest {

    /** Longer than Netty's 10s `responseWriteTimeoutSeconds` default. */
    private val blockMs = 13_000L

    @Test
    fun `a blocking tool call survives past netty's write timeout over HTTP1_1`() = runBlocking {
        val dir = File.createTempFile("lunamux-mcp-sse", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val repo = SettingsRepository(File(dir, "test.db"))
        repo.setMcpEnabled(true)
        DeviceAuth.addTrustedToken(repo, "sse-tok", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)

        val server = embeddedServer(Netty, port = 0) {
            routing { mcpRoutes(repo, ClaudeUsageMonitor()) }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port

            // A tool that only blocks — isolates the transport from session
            // plumbing. Re-registration by name is supported for tests.
            McpServer.register(
                McpTool("__test_block", "Block for the test duration.", schemaObject()) { _, _ ->
                    delay(blockMs)
                    McpToolResult.text("unblocked")
                }
            )

            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
            val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
                .header("Content-Type", "application/json")
                // Claude Code sends exactly this, which selects the SSE branch.
                .header("Accept", "application/json, text/event-stream")
                .header("X-Termtastic-Auth", "sse-tok")
                .timeout(Duration.ofSeconds(60))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
                            """"params":{"name":"__test_block","arguments":{}}}"""
                    )
                )
                .build()

            val started = System.currentTimeMillis()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val elapsed = System.currentTimeMillis() - started

            assertTrue(
                resp.body().contains("unblocked"),
                "SSE stream did not deliver the tool result (elapsed ${elapsed}ms, " +
                    "body=${resp.body().take(200)})",
            )
            assertTrue(
                elapsed >= blockMs,
                "Returned too early (${elapsed}ms) — the call did not actually block",
            )
        } finally {
            server.stop(1000, 2000)
        }
    }
}
