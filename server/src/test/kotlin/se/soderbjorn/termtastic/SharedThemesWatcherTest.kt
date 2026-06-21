/**
 * Verifies the publish-gate predicate used by [installSharedThemesWatcher]
 * to drop partial snapshots that would otherwise coerce connected clients
 * back to their slot defaults, and the cross-app union of shared custom
 * theme definitions under the v2 theme system.
 *
 * The race the gate guards against: `mergeUiSettings` writes
 * `themes.json` then `termtastic.json` sequentially. The shared themes
 * watcher fires on the first write, reads both files, and finds the
 * per-app file half-written (missing or empty v2 selection blob).
 * Without the gate, the partial JSON gets broadcast over the `/window`
 * socket and the renderer re-applies the slot defaults.
 *
 * Also verifies [buildRepublishSnapshot]'s in-memory-first sourcing of
 * per-app keys, which closes the related "theme flips to a previous
 * one" regression: when the shared-file watcher fires between
 * `mergeUiSettings`'s two writes, the per-app file on disk still holds
 * the prior selection. Re-reading it would broadcast the stale value;
 * trusting the in-memory snapshot does not.
 *
 * Finally, [mergeSharedThemes] is exercised directly to confirm the
 * shared custom-theme array ([PersistKeys.THEME_V2_CUSTOM]) round-trips
 * and is unioned by `name` (outgoing wins on collision, disk-only
 * entries preserved) so a custom theme authored in a peer Darkness app
 * isn't dropped.
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.mergeSharedThemes
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThemesWatcherTest {

    /** A v2 per-app selection blob as a JSON string, the shape the renderer writes. */
    private fun selection(dark: String, light: String, appearance: String = "Auto"): String =
        buildJsonObject {
            put("darkThemeName", JsonPrimitive(dark))
            put("lightThemeName", JsonPrimitive(light))
            put("appearance", JsonPrimitive(appearance))
        }.toString()

    @Test
    fun publishable_when_v2_selection_key_is_a_nonblank_string() {
        val merged = buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(selection("Phosphor", "Paper")))
        }
        assertTrue(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_v2_selection_key_is_missing() {
        val merged = buildJsonObject {
            put(PersistKeys.THEME_V2_CUSTOM, JsonPrimitive("[]"))
        }
        assertFalse(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_object_is_empty() {
        assertFalse(isPublishableUiSettings(JsonObject(emptyMap())))
    }

    @Test
    fun not_publishable_when_v2_selection_key_is_blank() {
        val merged = buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(""))
        }
        assertFalse(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_v2_selection_key_is_not_a_string() {
        val merged = buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(42))
        }
        assertFalse(isPublishableUiSettings(merged))
        val arrayValued = buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, buildJsonArray { add("Phosphor") })
        }
        assertFalse(isPublishableUiSettings(arrayValued))
    }

    /**
     * Regression for the "adding a pane flips theme to a previously
     * used one" race. Simulates the mid-write window where the per-app
     * file on disk still has the prior selection but the in-memory
     * snapshot has the new one. `buildRepublishSnapshot` must prefer the
     * in-memory value; otherwise the watcher would push the stale theme
     * over the `/window` socket and the renderer would re-apply it.
     */
    @Test
    fun republish_snapshot_prefers_in_memory_selection_over_stale_disk_per_app() {
        val tmp = Files.createTempDirectory("themes-watcher-test")
        try {
            val sharedFile = tmp.resolve("themes.json")
            // Shared file holds only the custom-theme array (the one SHARED key).
            sharedFile.writeText("""{"${PersistKeys.THEME_V2_CUSTOM}":[]}""")
            // In-memory snapshot reflects the user's NEW selection
            // ("Dracula"). The per-app file on disk would still hold the
            // previous "Phosphor" at this point in the mergeUiSettings
            // sequence — but we don't read it. If the function ever
            // regresses to disk-sourcing, this would assert against the
            // stale value.
            val inMem = buildJsonObject {
                put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(selection("Dracula", "Paper")))
                // SHARED_THEMES_KEYS entry — must be dropped from the
                // per-app side so the disk copy wins.
                put(PersistKeys.THEME_V2_CUSTOM, buildJsonArray { })
            }
            val snapshot = buildRepublishSnapshot(sharedFile.toString(), inMem)
            requireNotNull(snapshot) { "expected a publishable snapshot" }
            assertEquals(
                JsonPrimitive(selection("Dracula", "Paper")),
                snapshot[PersistKeys.THEME_V2_SELECTION],
            )
            // The custom array came from disk (empty), not the in-memory side.
            assertEquals(JsonArray(emptyList()), snapshot[PersistKeys.THEME_V2_CUSTOM])
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun republish_snapshot_returns_null_when_in_memory_has_no_selection() {
        val tmp = Files.createTempDirectory("themes-watcher-test")
        try {
            val sharedFile = tmp.resolve("themes.json")
            sharedFile.writeText("""{"${PersistKeys.THEME_V2_CUSTOM}":[]}""")
            val inMem = buildJsonObject {
                put(PersistKeys.THEME_V2_CUSTOM, buildJsonArray { })
            }
            assertNull(buildRepublishSnapshot(sharedFile.toString(), inMem))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Shared custom themes round-trip and union by name across apps: a
     * theme this app is about to write (outgoing) and a theme a peer app
     * already added on disk must both survive, with the outgoing entry
     * winning on a name collision.
     */
    @Test
    fun shared_custom_themes_union_by_name() {
        fun themeStub(name: String, accent: String): JsonObject = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("accent", JsonPrimitive(accent))
        }

        val outgoing = buildJsonObject {
            put(PersistKeys.THEME_V2_CUSTOM, buildJsonArray {
                add(themeStub("Mine", "#111111"))
                add(themeStub("Shared", "#222222")) // collides with disk; outgoing wins
            })
        }
        val onDisk = buildJsonObject {
            put(PersistKeys.THEME_V2_CUSTOM, buildJsonArray {
                add(themeStub("Shared", "#999999")) // older accent, should lose
                add(themeStub("PeerOnly", "#333333")) // disk-only, must be preserved
            })
        }

        val merged = mergeSharedThemes(outgoing, onDisk)
        val arr = merged[PersistKeys.THEME_V2_CUSTOM] as JsonArray
        val byName = arr.associateBy { (it as JsonObject)["name"].toString() }

        // Union: all three distinct names present.
        assertEquals(3, arr.size)
        assertTrue(JsonPrimitive("Mine").toString() in byName)
        assertTrue(JsonPrimitive("Shared").toString() in byName)
        assertTrue(JsonPrimitive("PeerOnly").toString() in byName)

        // Outgoing wins on the collision: accent is the outgoing #222222.
        val shared = byName[JsonPrimitive("Shared").toString()] as JsonObject
        assertEquals(JsonPrimitive("#222222"), shared["accent"])
    }
}
