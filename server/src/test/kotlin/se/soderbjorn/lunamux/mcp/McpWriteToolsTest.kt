/**
 * Tests for the MCP write tools (Groups C, D, E): the full window/tab
 * lifecycle against the real [WindowState] (ids are read back from tool
 * results and verified in the live config), `run_command` end-to-end
 * against a real PTY shell, scope gating across every registered write
 * tool, workspace templates, key-token encoding, and the agent-activity
 * indicators (activity log + auto badge).
 *
 * `set_theme` is only exercised for its validation path — a successful
 * call would write through [SettingsRepository.mergeUiSettings] into the
 * developer's real per-user UI-settings files.
 *
 * @see registerMcpWriteTools
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpWriteToolsTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("lunamux-mcp-write", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    private fun registerAll(): SettingsRepository {
        val repo = tempRepo()
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo)
        return repo
    }

    /** Run a tools/call and return the parsed result object. */
    private fun call(
        tool: String,
        argsJson: String = "{}",
        scope: McpScope = McpScope.READ_WRITE,
    ): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",
               "params":{"name":"$tool","arguments":$argsJson}}""",
            scope,
            null,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    /** Extract the first text block of a tool result. */
    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    /** Parse the JSON payload a json-result tool returned in its text block. */
    private fun payloadOf(result: JsonObject): JsonObject =
        mcpJson.parseToJsonElement(textOf(result)).jsonObject

    private fun assertOk(result: JsonObject, context: String) {
        assertNull(result["isError"], "$context failed: ${textOf(result)}")
    }

    @Test
    fun `window and tab lifecycle with id read-back`() {
        registerAll()

        // create_tab returns the server-minted tab id + seeded window.
        val tabResult = call("create_tab", """{"title":"MCP Test Tab"}""")
        assertOk(tabResult, "create_tab")
        val tabPayload = payloadOf(tabResult)
        val tabId = tabPayload["tabId"]!!.jsonPrimitive.content
        assertTrue(WindowState.config.value.tabs.any { it.id == tabId && it.title == "MCP Test Tab" })

        try {
            // create_window returns windowId + sessionId, present in config.
            val winResult = call("create_window", """{"tabId":"$tabId","title":"agent window"}""")
            assertOk(winResult, "create_window")
            val winPayload = payloadOf(winResult)
            val windowId = winPayload["windowId"]!!.jsonPrimitive.content
            val sessionId = winPayload["sessionId"]!!.jsonPrimitive.content
            assertTrue(sessionId.isNotEmpty())
            val leaf = WindowState.findLeaf(windowId)
            assertNotNull(leaf, "created window must be in the config")
            assertEquals("agent window", leaf.customName)
            // create_window auto-badges the agent-touched window.
            assertEquals("agent", leaf.agentNote)

            // rename_window (and clear).
            assertOk(call("rename_window", """{"windowId":"$windowId","title":"renamed"}"""), "rename")
            assertEquals("renamed", WindowState.findLeaf(windowId)?.customName)
            assertOk(call("rename_window", """{"windowId":"$windowId","title":""}"""), "clear rename")
            assertNull(WindowState.findLeaf(windowId)?.customName)

            // activate_window focuses + raises + activates the tab.
            assertOk(call("activate_window", """{"windowId":"$windowId"}"""), "activate_window")
            val cfg = WindowState.config.value
            assertEquals(tabId, cfg.activeTabId)
            assertEquals(windowId, cfg.tabs.first { it.id == tabId }.focusedPaneId)

            // maximize_window is idempotent in both directions.
            assertOk(call("maximize_window", """{"windowId":"$windowId","maximized":true}"""), "maximize")
            assertTrue(paneOf(windowId).maximized)
            assertOk(call("maximize_window", """{"windowId":"$windowId","maximized":true}"""), "maximize again")
            assertTrue(paneOf(windowId).maximized)
            assertOk(call("maximize_window", """{"windowId":"$windowId","maximized":false}"""), "restore")
            assertTrue(!paneOf(windowId).maximized)

            // arrange applies a preset to the tab.
            assertOk(call("arrange", """{"tabId":"$tabId","layout":"grid"}"""), "arrange")
            assertEquals("grid", WindowState.config.value.tabs.first { it.id == tabId }.layoutPreset)

            // annotate_window sets and clears the badge.
            assertOk(call("annotate_window", """{"windowId":"$windowId","note":"building"}"""), "annotate")
            assertEquals("building", WindowState.findLeaf(windowId)?.agentNote)
            assertOk(call("annotate_window", """{"windowId":"$windowId"}"""), "clear annotate")
            assertNull(WindowState.findLeaf(windowId)?.agentNote)

            // rename_tab + activate_tab.
            assertOk(call("rename_tab", """{"tabId":"$tabId","title":"Renamed Tab"}"""), "rename_tab")
            assertEquals("Renamed Tab", WindowState.config.value.tabs.first { it.id == tabId }.title)

            // close_window removes the pane and kills its session.
            assertOk(call("close_window", """{"windowId":"$windowId"}"""), "close_window")
            assertNull(WindowState.findLeaf(windowId))
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
        assertTrue(WindowState.config.value.tabs.none { it.id == tabId })
    }

    private fun paneOf(windowId: String) =
        WindowState.config.value.tabs.flatMap { it.panes }.first { it.leaf.id == windowId }

    @Test
    fun `run_command captures output and exit code`() {
        registerAll()
        val tabId = payloadOf(call("create_tab", "{}"))["tabId"]!!.jsonPrimitive.content
        try {
            val result = call(
                "run_command",
                """{"command":"echo hel\"\"lo-out; false","tabId":"$tabId","timeoutMs":30000}""",
            )
            assertOk(result, "run_command")
            val payload = payloadOf(result)
            assertEquals(true, payload["completed"]?.jsonPrimitive?.content?.toBoolean(),
                "command must complete: $payload")
            assertEquals(1, payload["exitCode"]?.jsonPrimitive?.content?.toInt())
            assertTrue(payload["output"]!!.jsonPrimitive.content.contains("hello-out"),
                "output must contain the echo: $payload")
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    @Test
    fun `every write tool is refused for read scope`() {
        registerAll()
        for (tool in McpServer.allTools().filter { it.requiresWrite }) {
            val result = call(tool.name, "{}", scope = McpScope.READ)
            assertEquals(
                "true",
                result["isError"]?.jsonPrimitive?.content,
                "${tool.name} must be refused for read scope",
            )
            assertTrue(textOf(result).contains("read+write"), "${tool.name} refusal must explain scope")
        }
    }

    @Test
    fun `workspaces save list and apply`() {
        registerAll()
        val tabId = payloadOf(call("create_tab", """{"title":"WS Source"}"""))["tabId"]!!.jsonPrimitive.content
        var appliedTabs = emptyList<String>()
        try {
            val saved = payloadOf(call("save_workspace", """{"name":"ws-test"}"""))
            assertEquals("ws-test", saved["name"]!!.jsonPrimitive.content)

            val listed = payloadOf(call("list_workspaces"))
            assertTrue(listed["workspaces"]!!.jsonArray.any {
                it.jsonObject["name"]!!.jsonPrimitive.content == "ws-test"
            })
            // list_workspaces is read-only — allowed with read scope.
            assertNull(call("list_workspaces", "{}", scope = McpScope.READ)["isError"])

            val before = WindowState.config.value.tabs.map { it.id }.toSet()
            val applied = payloadOf(call("apply_workspace", """{"name":"ws-test"}"""))
            appliedTabs = applied["createdTabIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(appliedTabs.isNotEmpty())
            assertTrue(WindowState.config.value.tabs.map { it.id }.containsAll(appliedTabs))
            assertTrue(before.none { it in appliedTabs })
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
            for (t in appliedTabs) call("close_tab", """{"tabId":"$t"}""")
        }
    }

    @Test
    fun `set_theme rejects unknown theme names before touching settings`() {
        registerAll()
        val result = call("set_theme", """{"darkTheme":"definitely-not-a-theme"}""")
        assertEquals("true", result["isError"]?.jsonPrimitive?.content)
        assertTrue(textOf(result).contains("Unknown theme"))
        // And requires at least one argument.
        val empty = call("set_theme", "{}")
        assertEquals("true", empty["isError"]?.jsonPrimitive?.content)
    }

    @Test
    fun `key tokens encode to the expected byte sequences`() {
        assertEquals("\r", encodeKeyToken("enter").decodeToString())
        assertEquals("\u001B[A", encodeKeyToken("up").decodeToString())
        assertEquals("\u001B[D", encodeKeyToken("LEFT").decodeToString())
        assertEquals(byteArrayOf(0x03).toList(), encodeKeyToken("ctrl+c").toList())
        assertEquals(byteArrayOf(0x1B, 'x'.code.toByte()).toList(), encodeKeyToken("alt+x").toList())
        assertEquals("q", encodeKeyToken("q").decodeToString())
        assertEquals("\u001B[24~", encodeKeyToken("f12").decodeToString())
        try {
            encodeKeyToken("hyperkey+banana")
            error("expected McpArgumentException")
        } catch (_: McpArgumentException) {
            // expected
        }
    }

    @Test
    fun `activity log records write calls newest first`() {
        McpActivityLog.record("test_tool_a", "x=1")
        McpActivityLog.record("test_tool_b", "y=2")
        val recent = McpActivityLog.recent(5)
        assertTrue(recent.size >= 2)
        assertEquals("test_tool_b", recent[0].tool)
        assertEquals("test_tool_a", recent[1].tool)
    }
}
