/**
 * Pure, platform-free read/modify/write helpers for the toolkit-owned
 * `LAYOUT_STATE` blob — the authoritative pane geometry store that the
 * web/Electron client and the mobile overview both render from.
 *
 * Geometry on the real client is *not* server-command driven: pane
 * position, size, z-order, maximize, and minimize live in this blob,
 * which the toolkit persists through `PATCH /api/ui-settings` and the
 * server broadcasts back over `/window`. To move/resize/maximize/
 * minimize/lay-out panes from mobile we must author the very same blob,
 * so this file mirrors the toolkit's `encodeLayoutStateJson` /
 * `decodeLayoutStateJson` shape exactly (see darkness-toolkit
 * `AppShellMount.kt`).
 *
 * The blob's top-level shape is:
 * ```
 * {
 *   "presetByTab":    { tabId: "grid" | "custom" | … },
 *   "paneOrderByTab": { tabId: [paneId, …] },
 *   "geometryByTab":  { tabId: { paneId: {
 *       xPct, yPct, widthPct, heightPct, zIndex, isMaximized, isMinimized
 *   } } }
 * }
 * ```
 * stored under [se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE] as a
 * JSON **string** value. Unknown top-level keys are preserved verbatim on
 * write so a future toolkit field is never clobbered by a mobile edit.
 *
 * All functions are pure (no IO, no coroutines) so they unit-test trivially
 * and are reused unchanged by Android and iOS through
 * [se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel].
 *
 * @see se.soderbjorn.lunamux.client.WindowStateRepository
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The geometry of a single pane within the `LAYOUT_STATE` blob. Mirrors the
 * toolkit's `PersistedPaneGeometry` field-for-field.
 *
 * @property xPct        top-left x as a fraction (0.0–1.0) of the tab width.
 * @property yPct        top-left y as a fraction (0.0–1.0) of the tab height.
 * @property widthPct    width as a fraction of the tab width.
 * @property heightPct   height as a fraction of the tab height.
 * @property zIndex      stacking order; higher renders on top.
 * @property isMaximized whether the pane fills the tab content area.
 * @property isMinimized whether the pane is docked off the canvas.
 */
data class LayoutGeom(
    val xPct: Double,
    val yPct: Double,
    val widthPct: Double,
    val heightPct: Double,
    val zIndex: Int = 1,
    val isMaximized: Boolean = false,
    val isMinimized: Boolean = false,
)

/**
 * An immutable, parsed view of the `LAYOUT_STATE` blob with copy-style
 * mutators. Construct via [WindowLayoutState.parse]; serialize back to the
 * string the persister expects via [encode].
 *
 * Unknown top-level keys captured at parse time are re-emitted on [encode]
 * so mobile edits never drop fields the toolkit may add later.
 *
 * @property presetByTab    per-tab active layout-preset key (or `"custom"`).
 * @property paneOrderByTab per-tab pane importance order (head = primary slot).
 * @property geometryByTab  per-tab `paneId -> geometry`.
 */
class LayoutBlob internal constructor(
    val presetByTab: Map<String, String>,
    val paneOrderByTab: Map<String, List<String>>,
    val geometryByTab: Map<String, Map<String, LayoutGeom>>,
    private val extras: Map<String, JsonElement>,
) {
    /**
     * Return the geometry for [paneId] in [tabId], or `null` if the blob has
     * no entry for it (e.g. a pane the toolkit never positioned).
     */
    fun geom(tabId: String, paneId: String): LayoutGeom? = geometryByTab[tabId]?.get(paneId)

    /**
     * Replace [tabId]'s entire geometry map with [geom], optionally stamping a
     * new [preset] key and pane [order]. Replacing the whole tab map (rather
     * than a single pane) keeps every pane's stored geometry internally
     * consistent — the caller computes the new full map from the live
     * projection so no pane is accidentally dropped or left stale.
     *
     * @param tabId  the tab whose geometry to overwrite.
     * @param geom   the new `paneId -> geometry` for the tab.
     * @param preset new preset key to record, or `null` to leave it unchanged.
     *   Pass `"custom"` after a manual move/resize to detach the tab from any
     *   active preset (matches the web `LayoutController`'s Custom transition).
     * @param order  new pane importance order, or `null` to leave it unchanged.
     * @return a new [LayoutBlob] with the tab updated.
     */
    fun withTabGeometry(
        tabId: String,
        geom: Map<String, LayoutGeom>,
        preset: String? = null,
        order: List<String>? = null,
    ): LayoutBlob {
        val newGeom = geometryByTab.toMutableMap().apply { this[tabId] = geom }
        val newPreset = if (preset == null) presetByTab
        else presetByTab.toMutableMap().apply { this[tabId] = preset }
        val newOrder = if (order == null) paneOrderByTab
        else paneOrderByTab.toMutableMap().apply { this[tabId] = order }
        return LayoutBlob(newPreset, newOrder, newGeom, extras)
    }

    /**
     * Serialize back to the JSON **string** stored under
     * [se.soderbjorn.darkness.core.PersistKeys.LAYOUT_STATE]. Preserves any
     * unknown top-level keys captured at parse time.
     *
     * @return the stringified blob, suitable for `SettingsPersister.putSetting`.
     */
    fun encode(): String {
        val obj = buildJsonObject {
            // Re-emit unknown keys first so our canonical keys win on collision.
            for ((k, v) in extras) put(k, v)
            put(
                "presetByTab",
                buildJsonObject { for ((k, v) in presetByTab) put(k, v) },
            )
            put(
                "paneOrderByTab",
                buildJsonObject {
                    for ((k, list) in paneOrderByTab) {
                        put(k, buildJsonArray { for (id in list) add(id) })
                    }
                },
            )
            put(
                "geometryByTab",
                buildJsonObject {
                    for ((tabId, panes) in geometryByTab) {
                        put(
                            tabId,
                            buildJsonObject {
                                for ((paneId, g) in panes) put(paneId, g.toJson())
                            },
                        )
                    }
                },
            )
        }
        return obj.toString()
    }

    private fun LayoutGeom.toJson(): JsonObject = buildJsonObject {
        put("xPct", xPct)
        put("yPct", yPct)
        put("widthPct", widthPct)
        put("heightPct", heightPct)
        put("zIndex", zIndex)
        put("isMaximized", isMaximized)
        put("isMinimized", isMinimized)
    }
}

/**
 * Parser/factory for [LayoutBlob]. Tolerant of the two on-the-wire shapes the
 * `LAYOUT_STATE` value can take (a JSON-encoded string — the normal case — or
 * an inlined object) and of missing/malformed sub-trees, which degrade to
 * empty maps rather than throwing.
 */
object WindowLayoutState {
    private val json = Json { ignoreUnknownKeys = true }

    /** Keys this module owns and rebuilds on [LayoutBlob.encode]; everything
     *  else found at the top level is preserved as an "extra". */
    private val OWNED_KEYS = setOf("presetByTab", "paneOrderByTab", "geometryByTab")

    /**
     * Parse the raw `LAYOUT_STATE` element (as carried in the UI-settings blob)
     * into a [LayoutBlob]. Returns an empty blob for `null`/malformed input.
     *
     * @param raw the `settings[LAYOUT_STATE]` element — typically a string
     *   primitive holding stringified JSON, sometimes an object.
     * @return the parsed blob, never `null`.
     */
    fun parse(raw: JsonElement?): LayoutBlob {
        val obj: JsonObject = when {
            raw is JsonObject -> raw
            raw is JsonPrimitive && raw.isString ->
                runCatching { json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
                    ?: return empty()
            else -> return empty()
        }

        val presetByTab = (obj["presetByTab"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
        }.toMap()

        val paneOrderByTab = (obj["paneOrderByTab"] as? JsonObject).orEmpty().mapValues { (_, v) ->
            (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            } ?: emptyList()
        }

        val geometryByTab = (obj["geometryByTab"] as? JsonObject).orEmpty().mapValues { (_, panesEl) ->
            (panesEl as? JsonObject).orEmpty().mapNotNull { (paneId, geomEl) ->
                val g = geomEl as? JsonObject ?: return@mapNotNull null
                paneId to LayoutGeom(
                    xPct = g.num("xPct"),
                    yPct = g.num("yPct"),
                    widthPct = g.num("widthPct", default = 0.5),
                    heightPct = g.num("heightPct", default = 0.5),
                    zIndex = (g["zIndex"] as? JsonPrimitive)?.intOrNull ?: 1,
                    isMaximized = (g["isMaximized"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    isMinimized = (g["isMinimized"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }.toMap()
        }

        val extras = obj.filterKeys { it !in OWNED_KEYS }
        return LayoutBlob(presetByTab, paneOrderByTab, geometryByTab, extras)
    }

    /** An empty blob with no tabs and no preserved extras. */
    fun empty(): LayoutBlob = LayoutBlob(emptyMap(), emptyMap(), emptyMap(), emptyMap())

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonObject.num(key: String, default: Double = 0.0): Double =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: default
}
