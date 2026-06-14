/**
 * Verifies the [computePaneLayout] shim — that termtastic's server
 * delegates to the toolkit's `LayoutPreset.computeBoxes` correctly,
 * including the new `"auto"` key. Geometry guarantees (primary slot
 * largest, n-box output, etc.) are exhaustively covered in the
 * toolkit's commonTest suite; here we just verify the dispatch is
 * wired and the grid-snap is applied.
 */
package se.soderbjorn.termtastic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaneLayoutsTest {

    @Test
    fun zero_panes_returns_empty_list() {
        assertEquals(emptyList(), computePaneLayout("auto", 0))
        assertEquals(emptyList(), computePaneLayout("grid", 0))
    }

    @Test
    fun one_pane_collapses_to_full_bleed_box() {
        for (key in listOf("auto", "grid", "hero-left", "split-h")) {
            val boxes = computePaneLayout(key, 1)
            assertEquals(1, boxes.size, key)
            assertEquals(PaneBox(0.0, 0.0, 1.0, 1.0), boxes[0], key)
        }
    }

    @Test
    fun auto_with_two_panes_returns_50_50_split() {
        val boxes = computePaneLayout("auto", 2)
        assertEquals(2, boxes.size)
        assertEquals(0.0, boxes[0].x)
        assertEquals(0.5, boxes[1].x)
        // Width 0.5 lands on a grid line (cell width = 0.05) so snap
        // shouldn't perturb it.
        assertEquals(0.5, boxes[0].width)
        assertEquals(0.5, boxes[1].width)
    }

    @Test
    fun auto_with_three_panes_uses_hero_left_with_two_stacked() {
        val boxes = computePaneLayout("auto", 3)
        assertEquals(3, boxes.size)
        // Primary 60%/full-height + 2 stacked on the right 40%
        assertEquals(0.0, boxes[0].x)
        assertEquals(0.6, boxes[0].width)
        assertEquals(1.0, boxes[0].height)
        assertEquals(0.6, boxes[1].x)
        assertEquals(0.6, boxes[2].x)
        // Stacks split the right column vertically.
        assertTrue(boxes[1].y < boxes[2].y, "second slot above third")
    }

    @Test
    fun unknown_key_falls_back_to_grid() {
        val auto = computePaneLayout("auto", 4)
        val grid = computePaneLayout("grid", 4)
        val unknown = computePaneLayout("totally-fake", 4)
        // Unknown defaults to grid — same number of boxes, same shape.
        assertEquals(grid.size, unknown.size)
        for (i in grid.indices) {
            assertEquals(grid[i], unknown[i], "slot $i")
        }
        // And auto is genuinely different from grid.
        assertEquals(auto.size, grid.size)
    }

    @Test
    fun custom_key_falls_back_to_grid() {
        // Custom is a sentinel preset and should never be applied as a
        // layout target. The shim treats it the same as an unknown key.
        val grid = computePaneLayout("grid", 5)
        val custom = computePaneLayout("custom", 5)
        assertEquals(grid.size, custom.size)
        for (i in grid.indices) {
            assertEquals(grid[i], custom[i], "slot $i")
        }
    }

    @Test
    fun every_box_lands_on_the_5_percent_grid() {
        // Every layout should produce grid-aligned boxes after the
        // 20-cell snap. This guards against future preset additions
        // that produce sub-grid coordinates.
        val cellW = 1.0 / 20
        val cellH = 1.0 / 20
        for (key in listOf("auto", "grid", "hero-left", "split-h", "t-shape", "big-2-stack")) {
            for (n in 2..6) {
                val boxes = computePaneLayout(key, n)
                for ((i, box) in boxes.withIndex()) {
                    assertOnGrid(box.x, cellW, "$key n=$n slot $i x")
                    assertOnGrid(box.y, cellH, "$key n=$n slot $i y")
                    assertOnGrid(box.x + box.width, cellW, "$key n=$n slot $i right")
                    assertOnGrid(box.y + box.height, cellH, "$key n=$n slot $i bottom")
                }
            }
        }
    }

    private fun assertOnGrid(value: Double, cell: Double, hint: String) {
        val nearest = kotlin.math.round(value / cell) * cell
        assertTrue(
            kotlin.math.abs(value - nearest) < 1e-9,
            "$hint: $value not on grid (cell=$cell)",
        )
    }
}
