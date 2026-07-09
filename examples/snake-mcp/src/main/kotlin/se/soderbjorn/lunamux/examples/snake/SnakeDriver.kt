/**
 * The Snake "game driver": renders [SnakeGame] into a Lunamux
 * agent-console pane and reads the player's cursor keys back — entirely
 * through public MCP tools ([McpClient]):
 *
 *  - `open_console` (screen mode, fixed grid) creates the pane,
 *  - each frame: `console_poll_input` drains the player's keys →
 *    [SnakeGame.turn] → [SnakeGame.step] → `screen_draw` ops →
 *    `screen_present`,
 *  - on death: a GAME OVER banner + `console_read_key` (any key restarts,
 *    `q` quits).
 *
 * The human plays with the arrow keys inside any Lunamux client
 * (web/Android/iOS/Electron); this process never touches server
 * internals — proving the tool surface is sufficient for a real-time
 * (~8–12 fps) game.
 *
 * @see Main.kt for the runnable entry point
 * @see SnakeGame for the pure game rules
 */
package se.soderbjorn.lunamux.examples.snake

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drives one Snake session over an established [McpClient] connection.
 *
 * @param client an initialized MCP client (call [McpClient.initialize]
 *   before [open]).
 * @param cols console grid width (default 30).
 * @param rows console grid height (default 16).
 */
class SnakeDriver(
    private val client: McpClient,
    private val cols: Int = 30,
    private val rows: Int = 16,
) {
    /** The live game state (exposed for the headless test's assertions). */
    var game = SnakeGame(cols, rows)
        private set

    /** The agent-console session id, set by [open]. */
    lateinit var sessionId: String
        private set

    /** The console's window (pane) id, set by [open]. */
    lateinit var windowId: String
        private set

    /** Set when the player pressed `q`. */
    var quitRequested = false
        private set

    /**
     * Open the screen-mode console pane and paint the first frame.
     *
     * @param tabId optional tab to open in (defaults to the active tab).
     */
    fun open(tabId: String? = null) {
        val opened = client.callTool("open_console", buildJsonObject {
            put("renderMode", "screen")
            put("title", "Snake — arrows to steer, q to quit")
            put("cols", cols)
            put("rows", rows)
            if (tabId != null) put("tabId", tabId)
        })
        sessionId = opened["sessionId"]!!.jsonPrimitive.content
        windowId = opened["windowId"]!!.jsonPrimitive.content
        drawFrame()
    }

    /**
     * Drain pending player input and apply it to the game.
     *
     * @return true if the player asked to quit (`q`).
     */
    fun pollAndApplyInput(): Boolean {
        val events = client.callTool("console_poll_input", buildJsonObject {
            put("sessionId", sessionId)
        })["events"]!!.jsonArray
        for (event in events) {
            val obj = event.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "key") continue
            when (obj["key"]?.jsonPrimitive?.content) {
                "up" -> game.turn(Direction.UP)
                "down" -> game.turn(Direction.DOWN)
                "left" -> game.turn(Direction.LEFT)
                "right" -> game.turn(Direction.RIGHT)
                "q", "ctrl+c" -> quitRequested = true
            }
        }
        return quitRequested
    }

    /**
     * Advance one frame: input → step → draw. The playable loop in
     * [runLoop] calls this on a timer; the headless test calls it
     * directly for deterministic stepping.
     */
    fun tick() {
        pollAndApplyInput()
        if (!game.over && !quitRequested) game.step()
        drawFrame()
    }

    /**
     * Paint the full board (border, snake, food, score line) as one
     * staged frame and present it. Full redraw per frame — the grid is
     * tiny and `screen_draw`/`screen_present` are localhost round-trips.
     */
    fun drawFrame() {
        val ops = buildJsonArray {
            add(buildJsonObject { put("op", "clear") })
            // Border box.
            add(buildJsonObject {
                put("op", "text"); put("x", 0); put("y", 0)
                put("text", "┌" + "─".repeat(cols - 2) + "┐")
            })
            for (y in 1 until rows - 1) {
                add(buildJsonObject { put("op", "text"); put("x", 0); put("y", y); put("text", "│") })
                add(buildJsonObject { put("op", "text"); put("x", cols - 1); put("y", y); put("text", "│") })
            }
            add(buildJsonObject {
                put("op", "text"); put("x", 0); put("y", rows - 1)
                put("text", "└" + "─".repeat(cols - 2) + "┘")
            })
            // Score, inlaid in the top border.
            add(buildJsonObject {
                put("op", "text"); put("x", 2); put("y", 0)
                put("text", " score: %02d ".format(game.score))
                put("fg", "brightyellow"); put("bold", true)
            })
            // Food.
            add(buildJsonObject {
                put("op", "text"); put("x", game.food.x); put("y", game.food.y)
                put("text", "◆"); put("fg", "brightred")
            })
            // Snake, head highlighted.
            game.body.forEachIndexed { i, cell ->
                add(buildJsonObject {
                    put("op", "text"); put("x", cell.x); put("y", cell.y)
                    put("text", "█")
                    put("fg", if (i == 0) "brightgreen" else "green")
                })
            }
        }
        client.callTool("screen_draw", buildJsonObject {
            put("sessionId", sessionId)
            put("ops", ops)
        })
        client.callTool("screen_present", buildJsonObject { put("sessionId", sessionId) })
    }

    /**
     * Paint the GAME OVER banner over the board and present it.
     */
    fun drawGameOver() {
        val message = " GAME OVER — score ${game.score} "
        val hint = " press any key — q quits "
        val ops = buildJsonArray {
            add(buildJsonObject {
                put("op", "text")
                put("x", ((cols - message.length) / 2).coerceAtLeast(0))
                put("y", rows / 2 - 1)
                put("text", message)
                put("fg", "brightwhite"); put("bg", "red"); put("bold", true)
            })
            add(buildJsonObject {
                put("op", "text")
                put("x", ((cols - hint.length) / 2).coerceAtLeast(0))
                put("y", rows / 2 + 1)
                put("text", hint)
                put("fg", "brightwhite")
            })
        }
        client.callTool("screen_draw", buildJsonObject {
            put("sessionId", sessionId)
            put("ops", ops)
        })
        client.callTool("screen_present", buildJsonObject { put("sessionId", sessionId) })
    }

    /**
     * Block on `console_read_key` for the game-over choice.
     *
     * @return true to restart, false to quit.
     */
    fun awaitRestartChoice(): Boolean {
        val event = client.callTool("console_read_key", buildJsonObject {
            put("sessionId", sessionId)
            put("timeoutMs", 300_000)
        })
        val key = event["key"]?.jsonPrimitive?.content
        return event["received"]?.jsonPrimitive?.content == "true" && key != "q"
    }

    /**
     * The playable loop: tick at [fps] until the player quits.
     *
     * @param fps frames per second (8–12 is comfortable on localhost).
     */
    fun runLoop(fps: Int = 10) {
        val frameMs = (1000L / fps).coerceAtLeast(50L)
        while (!quitRequested) {
            tick()
            if (game.over) {
                drawGameOver()
                if (awaitRestartChoice()) {
                    game = SnakeGame(cols, rows)
                    drawFrame()
                } else {
                    quitRequested = true
                }
                continue
            }
            Thread.sleep(frameMs)
        }
    }
}
