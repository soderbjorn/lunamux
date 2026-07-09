/**
 * JVM unit tests for [WindowLayoutState] / [LayoutBlob] — the pure
 * read/modify/write helpers for the toolkit-owned `LAYOUT_STATE` blob.
 *
 * @see WindowLayoutState
 */
package se.soderbjorn.lunamux.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for [WindowLayoutState] parsing, mutation, and round-trip. */
class WindowLayoutStateTest {

    /** A malformed/absent blob degrades to an empty parse, never throws. */
    @Test
    fun parsesNullAndGarbageToEmpty() {
        assertTrue(WindowLayoutState.parse(null).geometryByTab.isEmpty())
        assertTrue(WindowLayoutState.parse(JsonPrimitive("not json")).geometryByTab.isEmpty())
    }

    /** A stringified blob round-trips its geometry, preset, and order. */
    @Test
    fun parsesStringifiedBlob() {
        val raw = JsonPrimitive(
            """
            {"presetByTab":{"t1":"grid"},
             "paneOrderByTab":{"t1":["p1","p2"]},
             "geometryByTab":{"t1":{"p1":{"xPct":0.0,"yPct":0.0,"widthPct":0.5,"heightPct":1.0,
               "zIndex":3,"isMaximized":false,"isMinimized":true}}}}
            """.trimIndent(),
        )
        val blob = WindowLayoutState.parse(raw)
        assertEquals("grid", blob.presetByTab["t1"])
        assertEquals(listOf("p1", "p2"), blob.paneOrderByTab["t1"])
        val g = blob.geom("t1", "p1")!!
        assertEquals(0.5, g.widthPct)
        assertEquals(3, g.zIndex)
        assertTrue(g.isMinimized)
    }

    /** Unknown top-level keys survive a parse → encode round-trip. */
    @Test
    fun preservesUnknownTopLevelKeys() {
        val raw = JsonPrimitive("""{"futureToolkitKey":{"a":1},"presetByTab":{"t1":"custom"}}""")
        val encoded = WindowLayoutState.parse(raw).encode()
        val reparsed = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject
        assertTrue(reparsed.containsKey("futureToolkitKey"))
        assertEquals("custom", reparsed["presetByTab"]!!.jsonObject["t1"]!!.jsonPrimitive.content)
    }

    /** withTabGeometry replaces a tab's geometry and stamps the preset. */
    @Test
    fun withTabGeometryReplacesAndStamps() {
        val blob = WindowLayoutState.empty().withTabGeometry(
            tabId = "t1",
            geom = mapOf("p1" to LayoutGeom(0.1, 0.2, 0.3, 0.4, zIndex = 5, isMaximized = true)),
            preset = "custom",
            order = listOf("p1"),
        )
        assertEquals("custom", blob.presetByTab["t1"])
        assertEquals(listOf("p1"), blob.paneOrderByTab["t1"])
        val g = blob.geom("t1", "p1")!!
        assertEquals(0.3, g.widthPct)
        assertTrue(g.isMaximized)

        // And the encoded form is parseable back to the same geometry.
        val reparsed = WindowLayoutState.parse(JsonPrimitive(blob.encode()))
        assertEquals(g, reparsed.geom("t1", "p1"))
        assertNull(reparsed.geom("t1", "missing"))
    }

    /** Encoded geometry objects carry every toolkit field with correct types. */
    @Test
    fun encodesAllGeometryFields() {
        val blob = WindowLayoutState.empty().withTabGeometry(
            "t1",
            mapOf("p1" to LayoutGeom(0.0, 0.0, 1.0, 1.0, zIndex = 2, isMinimized = true)),
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(blob.encode()).jsonObject
        val geom = obj["geometryByTab"]!!.jsonObject["t1"]!!.jsonObject["p1"]!!.jsonObject
        assertEquals(true, (geom["isMinimized"] as JsonPrimitive).booleanOrNull)
        assertEquals(false, (geom["isMaximized"] as JsonPrimitive).booleanOrNull)
        assertEquals(2, (geom["zIndex"] as JsonPrimitive).content.toInt())
        assertTrue(obj["geometryByTab"] is JsonObject)
    }
}
