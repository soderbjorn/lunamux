/*
 * Tests for the persisted 3D-world pane zoom: PaneManager.setPaneZoom's
 * clamping / no-op / missing-pane behaviour, and the WindowConfig wire
 * compatibility of the Pane.zoom field (legacy blobs persisted before the
 * field existed must decode to the 1.0 default).
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaneZoomTest {

    /** A one-tab, one-pane config to mutate. */
    private fun config(zoom: Double = 1.0): WindowConfig = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "tab1",
                title = "Tab 1",
                panes = listOf(
                    Pane(
                        leaf = LeafNode(id = "pane1", sessionId = "s1", title = "Pane 1"),
                        x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1,
                        zoom = zoom,
                    ),
                ),
                focusedPaneId = "pane1",
            ),
        ),
        activeTabId = "tab1",
    )

    private fun zoomOf(cfg: WindowConfig): Double = cfg.tabs.single().panes.single().zoom

    @Test
    fun set_pane_zoom_updates_the_pane() {
        val updated = PaneManager.setPaneZoom(config(), "pane1", 2.25)
        assertEquals(2.25, updated?.let(::zoomOf))
    }

    @Test
    fun unchanged_zoom_is_a_no_op() {
        assertNull(PaneManager.setPaneZoom(config(zoom = 1.5), "pane1", 1.5))
    }

    @Test
    fun missing_pane_is_a_no_op() {
        assertNull(PaneManager.setPaneZoom(config(), "nope", 2.0))
    }

    @Test
    fun non_finite_zoom_is_rejected() {
        assertNull(PaneManager.setPaneZoom(config(), "pane1", Double.NaN))
        assertNull(PaneManager.setPaneZoom(config(), "pane1", Double.POSITIVE_INFINITY))
    }

    @Test
    fun out_of_range_zoom_is_clamped() {
        assertEquals(100.0, PaneManager.setPaneZoom(config(), "pane1", 1e9)?.let(::zoomOf))
        assertEquals(0.01, PaneManager.setPaneZoom(config(), "pane1", 0.0)?.let(::zoomOf))
    }

    @Test
    fun legacy_blob_without_zoom_decodes_to_default() {
        // A persisted pane from before the zoom field existed: no "zoom" key.
        val legacy = """
            {"tabs":[{"id":"t","title":"T","panes":[
                {"leaf":{"id":"p","sessionId":"s","title":"P"},
                 "x":0.0,"y":0.0,"width":1.0,"height":1.0,"z":1}
            ]}]}
        """.trimIndent()
        val cfg = windowJson.decodeFromString(WindowConfig.serializer(), legacy)
        assertEquals(1.0, zoomOf(cfg))
    }

    @Test
    fun zoom_survives_a_persistence_round_trip() {
        val cfg = PaneManager.setPaneZoom(config(), "pane1", 3.375)!!
        val json = windowJson.encodeToString(WindowConfig.serializer(), cfg)
        val back = windowJson.decodeFromString(WindowConfig.serializer(), json)
        assertEquals(3.375, zoomOf(back))
    }
}
