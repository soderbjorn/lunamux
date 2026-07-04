/**
 * Unit tests for the MCP protocol core ([McpServer]), the read-tool
 * registration ([registerMcpReadTools]), the MCP token trust class in
 * [DeviceAuth], and the ANSI-stripping helper.
 *
 * These cover the Phase 1 acceptance checks that don't need a live
 * network listener: initialize/tools-list/tools-call framing, schema
 * presence for every Group A+B tool, read-scope gating of write tools,
 * and the MCP-token/interactive-token isolation.
 *
 * @see McpServer
 * @see registerMcpReadTools
 */
package se.soderbjorn.termtastic.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.termtastic.ClaudeUsageMonitor
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("termtastic-mcp-test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    private fun registerAll(repo: SettingsRepository) {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
    }

    private fun handle(raw: String, scope: McpScope = McpScope.READ): JsonObject? = runBlocking {
        val handled = McpServer.handleMessage(raw, scope, null)
        handled.body?.let { mcpJson.parseToJsonElement(it).jsonObject }
    }

    @Test
    fun `initialize negotiates protocol and mints a session`() = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"initialize",
               "params":{"protocolVersion":"2025-03-26","capabilities":{}}}""",
            McpScope.READ,
            null,
        )
        assertNotNull(handled.newSessionId, "initialize must mint a session id")
        val body = mcpJson.parseToJsonElement(handled.body!!).jsonObject
        val result = body["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
        assertEquals("termtastic", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools list exposes every group A and B tool with a schema`() {
        registerAll(tempRepo())
        val body = handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
        val tools = body["result"]!!.jsonObject["tools"]!!.jsonArray
            .associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val expected = listOf(
            "list_sessions", "get_session", "list_layout", "get_state",
            "get_claude_usage", "read_scrollback",
            "watch_output", "wait_for_idle", "wait_for_exit",
        )
        for (name in expected) {
            val tool = tools[name] ?: error("tools/list is missing '$name'")
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `list_sessions and list_layout answer without error`() {
        registerAll(tempRepo())
        for (tool in listOf("list_sessions", "list_layout", "get_state")) {
            val body = handle(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                   "params":{"name":"$tool","arguments":{}}}""",
            )!!
            val result = body["result"]!!.jsonObject
            assertNull(result["isError"], "$tool must not be an error result")
        }
    }

    @Test
    fun `unknown tool and unknown method produce protocol errors`() {
        registerAll(tempRepo())
        val badTool = handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope"}}""",
        )!!
        assertEquals(-32602, badTool["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val badMethod = handle("""{"jsonrpc":"2.0","id":5,"method":"nope/nope"}""")!!
        assertEquals(-32601, badMethod["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `write tools are refused for read scope and allowed for write scope`() {
        McpServer.register(McpTool(
            name = "test_write_tool",
            description = "test",
            inputSchema = schemaObject(),
            requiresWrite = true,
        ) { _, _ -> McpToolResult.text("wrote") })

        val refused = handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call",
               "params":{"name":"test_write_tool"}}""",
            McpScope.READ,
        )!!
        assertEquals(
            true,
            refused["result"]!!.jsonObject["isError"]?.jsonPrimitive?.content?.toBoolean(),
            "read scope must be refused",
        )

        val allowed = handle(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call",
               "params":{"name":"test_write_tool"}}""",
            McpScope.READ_WRITE,
        )!!
        assertNull(allowed["result"]!!.jsonObject["isError"])
    }

    @Test
    fun `notifications get no response body`() = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            McpScope.READ,
            null,
        )
        assertNull(handled.body)
    }

    @Test
    fun `mcp tokens authorize mcp and never the interactive surface`() {
        val repo = tempRepo()
        val token = "mcp-test-token-abc"
        DeviceAuth.addTrustedToken(repo, token, DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)

        val client = DeviceAuth.ClientInfo(
            type = "Test", hostname = null, selfReportedIp = null, remoteAddress = "127.0.0.1",
        )
        // MCP endpoint: accepted with the persisted scope.
        assertEquals(DeviceAuth.MCP_SCOPE_READ, DeviceAuth.authorizeMcp(token, client, repo))
        // Unknown / regular tokens: rejected on the MCP surface.
        assertNull(DeviceAuth.authorizeMcp("other-token", client, repo))
        // Interactive fast path must NOT recognize the MCP token as a
        // trusted device (it would need a fresh approval instead) — and the
        // clean-slate loopback shortcut must not fire for a known MCP hash.
        assertNull(DeviceAuth.checkFastPath(token, client, repo))

        // Scope flip round-trips.
        val hash = DeviceAuth.listMcpTokens(repo).single().tokenHash
        assertTrue(DeviceAuth.setMcpTokenScope(repo, hash, DeviceAuth.MCP_SCOPE_READ_WRITE))
        assertEquals(DeviceAuth.MCP_SCOPE_READ_WRITE, DeviceAuth.authorizeMcp(token, client, repo))

        // Revocation kills MCP access.
        assertTrue(DeviceAuth.revokeTrustedDevice(repo, hash))
        assertNull(DeviceAuth.authorizeMcp(token, client, repo))
    }

    @Test
    fun `regular device tokens do not authorize mcp`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "regular-device-token", label = "Browser")
        val client = DeviceAuth.ClientInfo(
            type = "Test", hostname = null, selfReportedIp = null, remoteAddress = "127.0.0.1",
        )
        assertNull(DeviceAuth.authorizeMcp("regular-device-token", client, repo))
    }

    @Test
    fun `stripAnsi removes escapes and applies carriage-return overwrites`() {
        val raw = "\u001B[1;32mgreen\u001B[0m plain\r\n" +
            "\u001B]0;title\u0007progress 10%\rprogress 99%\n" +
            "tab\tkept"
        val stripped = stripAnsi(raw)
        assertEquals("green plain\nprogress 99%\ntab\tkept", stripped)
        assertFalse(stripped.contains('\u001B'))
    }

    @Test
    fun `argument helpers validate and coerce`() {
        val args = buildJsonObject {
            put("s", JsonPrimitive("x"))
            put("i", JsonPrimitive(42))
            put("b", JsonPrimitive(true))
        }
        assertEquals("x", args.requireString("s"))
        assertEquals(42, args.optInt("i"))
        assertEquals(true, args.optBool("b"))
        assertNull(args.optString("missing"))
        try {
            args.requireString("missing")
            error("expected McpArgumentException")
        } catch (_: McpArgumentException) {
            // expected
        }
    }
}
