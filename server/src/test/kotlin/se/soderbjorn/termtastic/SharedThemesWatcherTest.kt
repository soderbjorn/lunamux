/**
 * Verifies the publish-gate predicate used by [installSharedThemesWatcher]
 * to drop partial snapshots that would otherwise coerce connected clients
 * to `DEFAULT_THEME_NAME` (the source of the random "Neon Green" flips).
 *
 * The race the gate guards against: `mergeUiSettings` writes
 * `themes.json` then `termtastic.json` sequentially. The shared themes
 * watcher fires on the first write, reads both files, and finds the
 * per-app file half-written (missing or empty `"theme"` key). Without
 * the gate, the partial JSON gets broadcast over the `/window` socket
 * and the client's resolver silently substitutes "Neon Green".
 *
 * Also verifies [buildRepublishSnapshot]'s in-memory-first sourcing of
 * per-app keys, which closes the related "theme flips to a previous
 * one" regression: when the shared-file watcher fires between
 * `mergeUiSettings`'s two writes, the per-app file on disk still holds
 * the prior `"theme"` value. Re-reading it would broadcast the stale
 * name; trusting the in-memory snapshot does not.
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedThemesWatcherTest {

    @Test
    fun publishable_when_theme_key_is_a_nonblank_string() {
        val merged = buildJsonObject {
            put("theme", JsonPrimitive("Neon Cyan"))
            put("appearance", JsonPrimitive("Auto"))
        }
        assertTrue(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_theme_key_is_missing() {
        val merged = buildJsonObject {
            put("appearance", JsonPrimitive("Auto"))
        }
        assertFalse(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_object_is_empty() {
        assertFalse(isPublishableUiSettings(JsonObject(emptyMap())))
    }

    @Test
    fun not_publishable_when_theme_key_is_blank() {
        val merged = buildJsonObject {
            put("theme", JsonPrimitive(""))
        }
        assertFalse(isPublishableUiSettings(merged))
    }

    @Test
    fun not_publishable_when_theme_key_is_not_a_string() {
        val merged = buildJsonObject {
            put("theme", JsonPrimitive(42))
        }
        assertFalse(isPublishableUiSettings(merged))
        val arrayValued = buildJsonObject {
            put("theme", buildJsonArray { add("Neon Cyan") })
        }
        assertFalse(isPublishableUiSettings(arrayValued))
    }

    /**
     * Regression for the "adding a pane flips theme to a previously
     * used one" race. Simulates the mid-write window where the per-app
     * file on disk still has the prior `"theme"` value but the
     * in-memory snapshot has the new one. `buildRepublishSnapshot`
     * must prefer the in-memory value; otherwise the watcher would
     * push the stale theme over the `/window` socket and the renderer
     * would re-apply it.
     */
    @Test
    fun republish_snapshot_prefers_in_memory_theme_over_stale_disk_per_app() {
        val tmp = Files.createTempDirectory("themes-watcher-test")
        try {
            val sharedFile = tmp.resolve("themes.json")
            sharedFile.writeText(
                """{"themeConfigs":{"Neon Cyan":{}}}"""
            )
            // In-memory snapshot reflects the user's NEW selection
            // ("Neon Magenta"). The per-app file on disk would still
            // hold the previous "Neon Cyan" at this point in the
            // mergeUiSettings sequence — but we don't read it. If the
            // function ever regresses to disk-sourcing, this would
            // assert against "Neon Cyan".
            val inMem = buildJsonObject {
                put("theme", JsonPrimitive("Neon Magenta"))
                put("appearance", JsonPrimitive("Auto"))
                // SHARED_THEMES_KEYS entry — must be dropped from the
                // per-app side so the disk copy wins.
                put("themeConfigs", buildJsonObject {
                    put("Neon Magenta", buildJsonObject {})
                })
            }
            val snapshot = buildRepublishSnapshot(sharedFile.toString(), inMem)
            requireNotNull(snapshot) { "expected a publishable snapshot" }
            assertEquals(JsonPrimitive("Neon Magenta"), snapshot["theme"])
            assertEquals(JsonPrimitive("Auto"), snapshot["appearance"])
            // themeConfigs came from disk (Neon Cyan), not from the
            // in-memory override (which would have been Neon Magenta).
            val configs = snapshot["themeConfigs"] as JsonObject
            assertTrue("Neon Cyan" in configs)
            assertFalse("Neon Magenta" in configs)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun republish_snapshot_returns_null_when_in_memory_has_no_theme() {
        val tmp = Files.createTempDirectory("themes-watcher-test")
        try {
            val sharedFile = tmp.resolve("themes.json")
            sharedFile.writeText("""{"themeConfigs":{}}""")
            val inMem = buildJsonObject {
                put("appearance", JsonPrimitive("Auto"))
            }
            assertNull(buildRepublishSnapshot(sharedFile.toString(), inMem))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
