/**
 * Tests for the agent-console stack (plan Groups F + G): transcript
 * consoles (`open_console` → pane in the live config, `console_ask`
 * returning the user's typed reply through the cooked line discipline),
 * screen consoles (`screen_draw` + `screen_present` painting the
 * addressable grid, key bytes arriving as `console_poll_input` events),
 * ephemerality (closing the owning MCP session tears the pane down), and
 * the `/agent/{id}` transcript socket round-trip.
 *
 * @see registerMcpConsoleTools
 * @see se.soderbjorn.lunamux.AgentSession
 */
package se.soderbjorn.lunamux.mcp

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunamux.AgentSession
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.TerminalSessions
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.agentRoutes
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpConsoleToolsTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("lunamux-mcp-console", "").apply {
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
        registerMcpConsoleTools()
        return repo
    }

    private fun call(
        tool: String,
        argsJson: String = "{}",
        session: McpSession? = null,
    ): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",
               "params":{"name":"$tool","arguments":$argsJson}}""",
            McpScope.READ_WRITE,
            session,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(result: JsonObject): String =
        result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun payloadOf(result: JsonObject): JsonObject =
        mcpJson.parseToJsonElement(textOf(result)).jsonObject

    private fun assertOk(result: JsonObject, context: String) {
        assertNull(result["isError"], "$context failed: ${textOf(result)}")
    }

    /** Create a tab to host consoles; returns its id. */
    private fun hostTab(): String =
        payloadOf(call("create_tab", """{"title":"Console host"}""")).let {
            it["tabId"]!!.jsonPrimitive.content
        }

    @Test
    fun `transcript console round-trips console_ask through typed input`() {
        registerAll()
        val tabId = hostTab()
        try {
            val opened = payloadOf(call(
                "open_console",
                """{"renderMode":"transcript","title":"Ask test","tabId":"$tabId"}""",
            ))
            val sessionId = opened["sessionId"]!!.jsonPrimitive.content
            val windowId = opened["windowId"]!!.jsonPrimitive.content
            // The pane exists in the live config with the agent kind.
            val leaf = WindowState.findLeaf(windowId)
            assertNotNull(leaf, "console pane must be in the config")
            assertEquals(sessionId, leaf.sessionId)

            val agent = TerminalSessions.get(sessionId) as AgentSession

            runBlocking {
                val ask = async {
                    call("console_ask",
                        """{"sessionId":"$sessionId","prompt":"Name?","timeoutMs":10000}""")
                }
                // Give console_ask a moment to start awaiting, then type the
                // reply through the cooked line discipline (as a terminal
                // client would over /pty): chars + backspace + Enter.
                delay(300)
                agent.write("Bobb".toByteArray())
                agent.write(byteArrayOf(0x7F)) // backspace: Bobb -> Bob
                agent.write("\r".toByteArray())
                val result = withTimeout(10_000) { ask.await() }
                assertOk(result, "console_ask")
                val payload = payloadOf(result)
                assertEquals("true", payload["answered"]!!.jsonPrimitive.content)
                assertEquals("Bob", payload["text"]!!.jsonPrimitive.content)
            }

            // console_write appends to the transcript and the byte mirror.
            assertOk(call("console_write",
                """{"sessionId":"$sessionId","text":"hello transcript"}"""), "console_write")
            val snapshot = agent.transcriptSnapshot()
            assertTrue(snapshot.items.any { it.role == "prompt" && it.text == "Name?" })
            assertTrue(snapshot.items.any { it.role == "input" && it.text == "Bob" })
            assertTrue(snapshot.items.any { it.role == "output" && it.text == "hello transcript" })
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    @Test
    fun `screen console paints the grid and reports key events`() {
        registerAll()
        val tabId = hostTab()
        try {
            val opened = payloadOf(call(
                "open_console",
                """{"renderMode":"screen","cols":30,"rows":10,"tabId":"$tabId"}""",
            ))
            val sessionId = opened["sessionId"]!!.jsonPrimitive.content
            assertEquals(30, opened["cols"]!!.jsonPrimitive.content.toInt())

            // Draw + present: text lands on the emulator grid at (x, y).
            assertOk(call("screen_draw", """{"sessionId":"$sessionId","ops":[
                {"op":"clear"},
                {"op":"text","x":2,"y":1,"text":"SCORE 7","fg":"brightgreen","bold":true},
                {"op":"fill","x":0,"y":3,"width":5,"height":2,"ch":"#"}
            ]}"""), "screen_draw")
            // Nothing visible before present (fresh console cleared at open).
            val agent = TerminalSessions.get(sessionId) as AgentSession
            assertTrue(!agent.screenText().contains("SCORE 7"), "draw must be double-buffered")
            val presented = payloadOf(call("screen_present", """{"sessionId":"$sessionId"}"""))
            assertTrue(presented["flushedBytes"]!!.jsonPrimitive.content.toInt() > 0)
            val screen = agent.screenText()
            assertTrue(screen.lines()[1].contains("SCORE 7"), "row 1 must contain the text: $screen")
            assertTrue(screen.lines()[3].startsWith("#####"), "fill must paint row 3: $screen")

            // Cursor keys typed by the player arrive as poll_input events.
            agent.write(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())) // up
            agent.write("q".toByteArray())
            agent.write(byteArrayOf(0x03)) // ctrl+c
            val events = payloadOf(call("console_poll_input", """{"sessionId":"$sessionId"}"""))
                .let { it["events"]!!.jsonArray }
            val keys = events.map { it.jsonObject["key"]?.jsonPrimitive?.content }
            assertEquals(listOf("up", "q", "ctrl+c"), keys)

            // console_read_key blocks until the next event.
            runBlocking {
                val pending = async {
                    call("console_read_key", """{"sessionId":"$sessionId","timeoutMs":10000}""")
                }
                delay(200)
                agent.write(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())) // left
                val received = payloadOf(withTimeout(10_000) { pending.await() })
                assertEquals("left", received["key"]!!.jsonPrimitive.content)
            }
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    @Test
    fun `console is torn down when the owning mcp session closes`() {
        registerAll()
        val tabId = hostTab()
        try {
            val mcpSession = McpSessions.create()
            val opened = payloadOf(call(
                "open_console",
                """{"renderMode":"transcript","tabId":"$tabId"}""",
                session = mcpSession,
            ))
            val sessionId = opened["sessionId"]!!.jsonPrimitive.content
            val windowId = opened["windowId"]!!.jsonPrimitive.content
            assertNotNull(WindowState.findLeaf(windowId))
            assertNotNull(TerminalSessions.get(sessionId))

            // The MCP client says goodbye — pane closes, session destroyed.
            assertTrue(McpSessions.terminate(mcpSession.id))
            assertNull(WindowState.findLeaf(windowId), "pane must be closed on MCP disconnect")
            assertNull(TerminalSessions.get(sessionId), "agent session must be destroyed")
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    @Test
    fun `agent socket streams snapshot and accepts submits`() {
        val repo = registerAll()
        val tabId = hostTab()
        try {
            val opened = payloadOf(call(
                "open_console",
                """{"renderMode":"transcript","tabId":"$tabId"}""",
            ))
            val sessionId = opened["sessionId"]!!.jsonPrimitive.content
            call("console_write", """{"sessionId":"$sessionId","text":"pre-existing"}""")

            testApplication {
                application {
                    install(io.ktor.server.websocket.WebSockets)
                    routing { agentRoutes(repo) }
                }
                val wsClient = createClient { install(WebSockets) }
                wsClient.webSocket("/agent/$sessionId", request = {
                    // Clean-slate temp repo: the first loopback token is
                    // auto-approved by DeviceAuth's onboarding shortcut.
                    header("X-Termtastic-Auth", "test-device-token")
                }) {
                    // First frame is the snapshot with the pre-existing item.
                    val snapshot = (incoming.receive() as Frame.Text).readText()
                    assertTrue(snapshot.contains("\"snapshot\""), snapshot)
                    assertTrue(snapshot.contains("pre-existing"), snapshot)

                    // A submit lands in the console's line channel and echoes
                    // back as an appended input transcript item.
                    send(Frame.Text("""{"type":"submit","text":"from the socket"}"""))
                    val read = payloadOf(call("console_read",
                        """{"sessionId":"$sessionId","timeoutMs":10000}"""))
                    assertEquals("from the socket", read["text"]!!.jsonPrimitive.content)
                }
            }
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }
}
