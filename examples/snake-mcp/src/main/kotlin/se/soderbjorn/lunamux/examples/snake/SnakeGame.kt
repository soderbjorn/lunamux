/**
 * Pure Snake game logic for the MCP example driver — no I/O, fully
 * deterministic with a seeded RNG, so the headless test can assert
 * turning, growth, and death without a renderer.
 *
 * Coordinates are grid cells inside the border box: the playfield spans
 * `1..cols-2` horizontally and `1..rows-2` vertically (row 0 and the
 * outermost columns/rows belong to the border drawn by [SnakeDriver]).
 *
 * @see SnakeDriver
 */
package se.soderbjorn.lunamux.examples.snake

import kotlin.random.Random

/** A grid cell position. */
data class Cell(val x: Int, val y: Int)

/** Movement directions with their per-step deltas. */
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    /** The opposing direction (a snake cannot reverse onto itself). */
    fun opposite(): Direction = when (this) {
        UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT
    }
}

/**
 * Game state + rules.
 *
 * @param cols total grid width including the border.
 * @param rows total grid height including the border.
 * @param seed RNG seed for food placement (deterministic in tests).
 */
class SnakeGame(val cols: Int, val rows: Int, seed: Long = System.nanoTime()) {

    private val rng = Random(seed)

    /** Playfield bounds (inclusive), inside the border ring. */
    val minX = 1
    val maxX = cols - 2
    val minY = 1
    val maxY = rows - 2

    /** Snake body, head first. */
    val body = ArrayDeque<Cell>()

    /** Current movement direction; changed via [turn]. */
    var direction: Direction = Direction.RIGHT
        private set

    /** The pending direction applied on the next [step] (prevents double-turn-into-self within one tick). */
    private var pendingDirection: Direction = Direction.RIGHT

    /** Current food cell. Assignable so tests can stage growth deterministically. */
    var food: Cell = Cell(0, 0)

    /** Apples eaten. */
    var score = 0
        private set

    /** True once the snake hit a wall or itself. */
    var over = false
        private set

    init {
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        body.addLast(Cell(cx, cy))
        body.addLast(Cell(cx - 1, cy))
        body.addLast(Cell(cx - 2, cy))
        food = spawnFood()
    }

    /** Pick a free cell for the next apple. */
    private fun spawnFood(): Cell {
        while (true) {
            val c = Cell(rng.nextInt(minX, maxX + 1), rng.nextInt(minY, maxY + 1))
            if (c !in body) return c
        }
    }

    /**
     * Request a direction change (from a player's arrow key). Reversals
     * onto the snake's own neck are ignored, matching classic rules.
     *
     * @param dir the requested direction.
     */
    fun turn(dir: Direction) {
        if (dir == direction.opposite()) return
        pendingDirection = dir
    }

    /**
     * Advance the game one tick: move the head, handle food (grow +
     * score) and collisions (wall or self ends the game).
     */
    fun step() {
        if (over) return
        direction = pendingDirection
        val head = body.first()
        val next = Cell(head.x + direction.dx, head.y + direction.dy)
        if (next.x < minX || next.x > maxX || next.y < minY || next.y > maxY) {
            over = true
            return
        }
        // Moving into the current tail cell is legal (it vacates this tick)
        // unless the snake grows; keep it simple and strict: growing onto
        // the tail is the only excluded case handled below.
        val eats = next == food
        if (next in body && !(next == body.last() && !eats)) {
            over = true
            return
        }
        body.addFirst(next)
        if (eats) {
            score++
            food = spawnFood()
        } else {
            body.removeLast()
        }
    }

    /**
     * Reset to the initial state (used by the "play again" flow).
     */
    fun reset() {
        body.clear()
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        body.addLast(Cell(cx, cy))
        body.addLast(Cell(cx - 1, cy))
        body.addLast(Cell(cx - 2, cy))
        direction = Direction.RIGHT
        pendingDirection = Direction.RIGHT
        score = 0
        over = false
        food = spawnFood()
    }
}
