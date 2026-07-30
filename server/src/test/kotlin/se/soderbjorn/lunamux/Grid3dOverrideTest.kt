/*
 * Tests for the 3D-world per-pane grid override feature:
 *  - PaneManager.setPaneGrid3d's set / clear / clamp / no-op / missing-pane
 *    behaviour and the WindowConfig wire compatibility of the Pane.grid3d field
 *    (legacy blobs persisted before the field existed must decode to null),
 *  - pickEffectiveSize's tiered "highest tier wins, min() within it" aggregation,
 *  - isMobileClientType's classification of reported client types.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Grid3dOverrideTest {

    // ── PaneManager.setPaneGrid3d ────────────────────────────────────────

    /** A one-tab, one-pane config to mutate. */
    private fun config(grid3d: PaneGrid? = null): WindowConfig = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "tab1",
                title = "Tab 1",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(id = "pane1", sessionId = "s1", title = "Pane 1"),
                        x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1,
                        grid3d = grid3d,
                    ),
                ),
                focusedPaneId = "pane1",
            ),
        ),
        activeTabId = "tab1",
    )

    private fun gridOf(cfg: WindowConfig): PaneGrid? = cfg.tabs.single().panes.single().grid3d

    @Test
    fun set_pane_grid3d_updates_the_pane() {
        val updated = PaneManager.setPaneGrid3d(config(), "pane1", 100, 40)
        assertEquals(PaneGrid(100, 40), updated?.let(::gridOf))
    }

    @Test
    fun clearing_grid3d_sets_it_back_to_null() {
        val updated = PaneManager.setPaneGrid3d(config(grid3d = PaneGrid(100, 40)), "pane1", null, null)
        assertNull(updated?.let(::gridOf))
    }

    @Test
    fun partial_null_clears_the_override() {
        // Either dimension null means "clear" — a half-specified grid is meaningless.
        assertNull(PaneManager.setPaneGrid3d(config(grid3d = PaneGrid(100, 40)), "pane1", 80, null)?.let(::gridOf))
        assertNull(PaneManager.setPaneGrid3d(config(grid3d = PaneGrid(100, 40)), "pane1", null, 20)?.let(::gridOf))
    }

    @Test
    fun unchanged_grid3d_is_a_no_op() {
        assertNull(PaneManager.setPaneGrid3d(config(grid3d = PaneGrid(100, 40)), "pane1", 100, 40))
    }

    @Test
    fun clearing_an_already_absent_override_is_a_no_op() {
        assertNull(PaneManager.setPaneGrid3d(config(), "pane1", null, null))
    }

    @Test
    fun missing_pane_is_a_no_op() {
        assertNull(PaneManager.setPaneGrid3d(config(), "nope", 100, 40))
    }

    @Test
    fun out_of_range_grid3d_is_clamped() {
        assertEquals(PaneGrid(1000, 1), PaneManager.setPaneGrid3d(config(), "pane1", 999_999, 0)?.let(::gridOf))
        assertEquals(PaneGrid(1, 1000), PaneManager.setPaneGrid3d(config(), "pane1", -5, 5000)?.let(::gridOf))
    }

    @Test
    fun legacy_blob_without_grid3d_decodes_to_null() {
        // A persisted pane from before the grid3d field existed: no "grid3d" key.
        val legacy = """
            {"tabs":[{"id":"t","title":"T","panes":[
                {"leaf":{"id":"p","sessionId":"s","title":"P"},
                 "x":0.0,"y":0.0,"width":1.0,"height":1.0,"z":1}
            ]}]}
        """.trimIndent()
        val cfg = windowJson.decodeFromString(WindowConfig.serializer(), legacy)
        assertNull(gridOf(cfg))
    }

    @Test
    fun grid3d_survives_a_persistence_round_trip() {
        val cfg = PaneManager.setPaneGrid3d(config(), "pane1", 132, 43)!!
        val json = windowJson.encodeToString(WindowConfig.serializer(), cfg)
        val back = windowJson.decodeFromString(WindowConfig.serializer(), json)
        assertEquals(PaneGrid(132, 43), gridOf(back))
    }

    // ── pickEffectiveSize (tiered aggregation) ───────────────────────────

    @Test
    fun no_votes_yields_null() {
        assertNull(pickEffectiveSize(emptyList()))
    }

    @Test
    fun all_normal_votes_reduce_to_smallest_viewport() {
        val votes = listOf(
            SizeVote(120, 40, SizePriority.NORMAL),
            SizeVote(80, 24, SizePriority.NORMAL),
            SizeVote(100, 50, SizePriority.NORMAL),
        )
        assertEquals(80 to 24, pickEffectiveSize(votes))
    }

    @Test
    fun three_d_override_beats_a_smaller_normal_vote() {
        // The whole point: a 2D client's small viewport must NOT clamp a
        // deliberately-enlarged 3D pane.
        val votes = listOf(
            SizeVote(80, 24, SizePriority.NORMAL),   // a 2D client
            SizeVote(200, 60, SizePriority.THREE_D), // the 3D world's override
        )
        assertEquals(200 to 60, pickEffectiveSize(votes))
    }

    @Test
    fun min_is_taken_within_the_winning_tier() {
        val votes = listOf(
            SizeVote(200, 60, SizePriority.THREE_D),
            SizeVote(150, 80, SizePriority.THREE_D),
            SizeVote(500, 500, SizePriority.NORMAL),
        )
        assertEquals(150 to 60, pickEffectiveSize(votes))
    }

    // ── isMobileClientType ───────────────────────────────────────────────

    @Test
    fun mobile_client_types_are_detected() {
        for (t in listOf("Android", "iOS", "iPhone", "iPad", "phone", "Mobile", "tablet")) {
            assertTrue(isMobileClientType(t), "expected $t to be mobile")
        }
    }

    @Test
    fun desktop_and_web_client_types_are_not_mobile() {
        for (t in listOf("Web", "Computer", "Unknown", "")) {
            assertFalse(isMobileClientType(t), "expected $t to be non-mobile")
        }
    }
}
