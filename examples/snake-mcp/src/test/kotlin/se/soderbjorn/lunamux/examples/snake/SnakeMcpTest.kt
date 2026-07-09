/**
 * Headless end-to-end test for the Snake capstone: boots the real
 * Lunamux MCP routes on a local Netty listener, mints an MCP token,
 * connects the [SnakeDriver] through the public tool surface, and feeds
 * *synthetic* arrow-key events into the agent session's input channel —
 * asserting the snake turns, grows on food, and dies on wall/self
 * collision, and that the frames actually land on the console's screen
 * grid. This is what makes the capstone verifiable without a human at
 * the keys; the driver itself uses only public MCP tools.
 *
 * Pure-rules cases (self collision, reversal-ignore) run against
 * [SnakeGame] directly.
 *
 * @see SnakeDriver
 * @see SnakeGame
 */
package se.soderbjorn.lunamux.examples.snake

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.TerminalSessions
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.mcp.mcpRoutes
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnakeMcpTest {

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: McpClient
    private lateinit var tabId: String

    @BeforeTest
    fun bootServer() {
        val dir = File.createTempFile("snake-mcp-test", "").apply {
            delete(); mkdirs(); deleteOnExit()
        }
        val repo = SettingsRepository(File(dir, "test.db"))
        DeviceAuth.addTrustedToken(
            repo, "snake-test-token", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ_WRITE,
        )
        server = embeddedServer(Netty, port = 0) {
            routing { mcpRoutes(repo, ClaudeUsageMonitor()) }
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        client = McpClient("http://127.0.0.1:$port/mcp", "snake-test-token")
        client.initialize()
        // A tab must exist to host the console pane.
        tabId = client.callTool("create_tab", buildJsonObject { put("title", "Snake test") })
            .let { it["tabId"]!!.jsonPrimitive.content }
    }

    @AfterTest
    fun stopServer() {
        runCatching { client.callTool("close_tab", buildJsonObject { put("tabId", tabId) }) }
        server.stop(200, 500)
    }

    /** Read the console's rendered screen text through the MCP surface. */
    private fun screenText(sessionId: String): String =
        client.callTool("read_scrollback", buildJsonObject {
            put("sessionId", sessionId)
            put("source", "screen")
            put("lines", 100)
        })["text"]!!.jsonPrimitive.content

    /** Inject player key bytes into the console's input channel. */
    private fun pressKey(sessionId: String, bytes: ByteArray) {
        val session = TerminalSessions.get(sessionId)
            ?: error("agent session $sessionId not found")
        session.write(bytes)
    }

    private val ARROW_UP = byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
    private val ARROW_DOWN = byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())

    @Test
    fun `snake turns on synthetic arrows, grows on food, dies on the wall — all over mcp`() {
        val driver = SnakeDriver(client, cols = 30, rows = 16)
        driver.open(tabId)

        // The first frame is on the console's grid: border + score.
        val firstFrame = screenText(driver.sessionId)
        assertTrue(firstFrame.contains("score: 00"), "first frame must show the score:\n$firstFrame")
        assertTrue(firstFrame.contains("┌"), "first frame must draw the border:\n$firstFrame")
        assertTrue(firstFrame.contains("█"), "first frame must draw the snake:\n$firstFrame")

        // Turn: a synthetic ↑ arrives via the input channel; the next tick
        // polls it over MCP and the snake turns.
        assertEquals(Direction.RIGHT, driver.game.direction)
        pressKey(driver.sessionId, ARROW_UP)
        driver.tick()
        assertEquals(Direction.UP, driver.game.direction, "snake must turn up on ↑")

        // Grow: stage the apple directly in the snake's path.
        val head = driver.game.body.first()
        driver.game.food = Cell(head.x, head.y - 1)
        val lengthBefore = driver.game.body.size
        driver.tick()
        assertEquals(lengthBefore + 1, driver.game.body.size, "snake must grow on food")
        assertEquals(1, driver.game.score)
        assertTrue(screenText(driver.sessionId).contains("score: 01"),
            "the score frame must be repainted")

        // Death: keep going up until the wall ends the game.
        var guard = 0
        while (!driver.game.over && guard++ < 32) driver.tick()
        assertTrue(driver.game.over, "snake must die on the wall")

        // The driver paints the game-over banner; it lands on the grid.
        driver.drawGameOver()
        val finalFrame = screenText(driver.sessionId)
        assertTrue(finalFrame.contains("GAME OVER"), "game-over banner must render:\n$finalFrame")

        // Quit path: a synthetic 'q' ends the loop on the next poll.
        pressKey(driver.sessionId, "q".toByteArray())
        assertTrue(driver.pollAndApplyInput(), "q must request quit")
    }

    @Test
    fun `snake dies on self collision`() {
        val game = SnakeGame(30, 16, seed = 7)
        // Grow to length 5 by staging food ahead twice.
        repeat(2) {
            val head = game.body.first()
            game.food = Cell(head.x + 1, head.y)
            game.step()
        }
        assertEquals(5, game.body.size)
        // Tight loop: up, left, down → the head lands on its own body.
        game.turn(Direction.UP); game.step()
        game.turn(Direction.LEFT); game.step()
        game.turn(Direction.DOWN); game.step()
        assertTrue(game.over, "snake must die when biting itself")
    }

    @Test
    fun `reversals are ignored`() {
        val game = SnakeGame(30, 16, seed = 7)
        assertEquals(Direction.RIGHT, game.direction)
        game.turn(Direction.LEFT) // illegal reversal
        game.step()
        assertEquals(Direction.RIGHT, game.direction)
        assertTrue(!game.over)
    }
}
