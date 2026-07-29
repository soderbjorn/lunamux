/**
 * Regression test for LMX-137 — "sometimes clicking a window reverts the
 * dark/light change".
 *
 * `POST /api/ui-settings` merges a handful of keys into one shared blob, and
 * Ktor serves those POSTs from a multi-threaded dispatcher. Before the fix,
 * [SettingsRepository.mergeUiSettings] read `_uiSettings.value`, spent a couple
 * of milliseconds writing two files, and only then wrote the merged snapshot
 * back — so two requests overlapping in that window both started from the same
 * `existing` and whichever finished last silently discarded the other's key.
 *
 * That is exactly the shape of the reported bug. A single appearance toggle in
 * the web client fans out into three simultaneous POSTs
 * (`darkness.theme.v2.selection` carrying the new `appearance`, plus
 * `darkness.theme.v2.custom` and `darkness.appearance.shape`), and clicking in a
 * pane adds a fourth (`darkness.layoutState`, written on the z-order bump). When
 * the selection POST is the one that loses, the server keeps the *previous*
 * dark/light choice while the client still shows the new one — and the next
 * broadcast of the server's blob replays the stale value back over the client,
 * which is the visible "it reverted".
 *
 * The tests below drive [SettingsRepository.mergeUiSettings] from several
 * threads at once, in lockstep rounds, and assert that no round loses a key.
 * Both fail reliably against the unsynchronized version.
 *
 * Isolation note: the repository's two backing files are passed explicitly so
 * this test writes to a temp directory rather than to the machine's real,
 * user-visible Lunula settings.
 *
 * @see SettingsRepository.mergeUiSettings
 */
package se.soderbjorn.lunamux.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.ThemeSnapshotV2
import se.soderbjorn.lunula.core.Appearance
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiSettingsMergeConcurrencyTest {

    /** Writers per round: one appearance writer plus this many noise writers. */
    private val noiseWriters = 5

    /** Lockstep rounds. Ample: the unsynchronized version loses a key within a few. */
    private val rounds = 20

    /**
     * A repository whose SQLite database *and* both UI-settings files live in a
     * fresh temp directory, so nothing here touches the developer's real
     * `themes.json` / `termtastic.json`.
     */
    private fun tempRepo(): SettingsRepository {
        val dir = Files.createTempDirectory("lunamux-ui-settings-merge").toFile()
        dir.deleteOnExit()
        return SettingsRepository(
            dbFile = File(dir, "test.db"),
            sharedThemesPath = File(dir, "themes.json").absolutePath,
            appSettingsPath = File(dir, "termtastic.json").absolutePath,
        )
    }

    /** The `appearance` currently stored in the v2 selection blob, or null. */
    private fun storedAppearance(settings: JsonObject): Appearance? {
        val raw = (settings[PersistKeys.THEME_V2_SELECTION] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: return null
        return ThemeSnapshotV2.fromStrings(selectionJson = raw, customThemesJson = null).appearance
    }

    /** A v2 selection blob pinning [appearance], as the web client writes it. */
    private fun selectionBlob(appearance: Appearance): String =
        ThemeSnapshotV2(appearance = appearance).selectionJson()

    /**
     * Runs [body] on [parties] threads for [rounds] lockstep rounds, calling
     * [afterRound] on the coordinating thread once every writer in a round has
     * returned. Rethrows the first worker failure.
     */
    private fun inLockstepRounds(
        parties: Int,
        rounds: Int,
        body: (round: Int, worker: Int) -> Unit,
        afterRound: (round: Int) -> Unit,
    ) {
        // +1 party: the coordinating thread joins both barriers, so it observes
        // the settled state between rounds instead of racing the workers.
        val start = CyclicBarrier(parties + 1)
        val done = CyclicBarrier(parties + 1)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(parties)
        try {
            repeat(parties) { worker ->
                pool.execute {
                    repeat(rounds) { round ->
                        start.await()
                        try {
                            body(round, worker)
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        }
                        done.await()
                    }
                }
            }
            repeat(rounds) { round ->
                start.await()
                done.await()
                failure.get()?.let { throw it }
                afterRound(round)
            }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    /**
     * The ticket's symptom, reduced to its cause: an appearance write and the
     * writes that accompany it (the toggle's sibling keys, the pane click's
     * layout blob) hit the merge at the same moment, and the appearance must
     * still be the one stored afterwards rather than the value it replaced.
     */
    @Test
    fun a_concurrent_write_does_not_revert_the_appearance() {
        val repo = tempRepo()
        // Alternating so a lost write is always visible as the *previous*
        // appearance — the reverted-toggle symptom — rather than coinciding
        // with what was already stored.
        val wanted = { round: Int -> if (round % 2 == 0) Appearance.Dark else Appearance.Light }

        inLockstepRounds(
            parties = 1 + noiseWriters,
            rounds = rounds,
            body = { round, worker ->
                if (worker == 0) {
                    // The appearance toggle's own POST.
                    repo.mergeUiSettings(
                        buildJsonObject {
                            put(
                                PersistKeys.THEME_V2_SELECTION,
                                JsonPrimitive(selectionBlob(wanted(round))),
                            )
                        },
                    )
                } else {
                    // Everything else a client writes in the same breath: the
                    // pane click's layout blob, the toggle's sibling keys. Each
                    // value changes every round so none of these merges can
                    // short-circuit on the no-op guard.
                    repo.mergeUiSettings(
                        buildJsonObject {
                            put("test.noise.$worker", JsonPrimitive("round-$round"))
                        },
                    )
                }
            },
            afterRound = { round ->
                assertEquals(
                    wanted(round),
                    storedAppearance(repo.getUiSettings()),
                    "round $round: a concurrent settings write reverted the appearance",
                )
            },
        )
    }

    /**
     * The general invariant behind the appearance case: the merge is a
     * read-modify-write over one shared blob, so *no* concurrent writer may lose
     * its key. Appearance is only the most visible casualty — a dropped
     * `layoutState` or font size is the same bug wearing different clothes.
     */
    @Test
    fun concurrent_writes_all_survive_the_merge() {
        val repo = tempRepo()
        inLockstepRounds(
            parties = 1 + noiseWriters,
            rounds = rounds,
            body = { round, worker ->
                repo.mergeUiSettings(
                    buildJsonObject {
                        put("test.writer.$worker", JsonPrimitive("round-$round"))
                    },
                )
            },
            afterRound = { round ->
                val stored = repo.getUiSettings()
                for (worker in 0..noiseWriters) {
                    assertEquals(
                        JsonPrimitive("round-$round"),
                        stored["test.writer.$worker"],
                        "round $round: writer $worker's key was lost by a concurrent merge",
                    )
                }
            },
        )
    }

    /**
     * Guards the write-storm defence the lock is wrapped around: a merge whose
     * every key already holds the incoming value must still short-circuit,
     * returning the current snapshot without touching disk or the flow.
     */
    @Test
    fun an_unchanged_merge_is_still_a_no_op() {
        val repo = tempRepo()
        val blob = selectionBlob(Appearance.Dark)
        val incoming = buildJsonObject {
            put(PersistKeys.THEME_V2_SELECTION, JsonPrimitive(blob))
        }
        repo.mergeUiSettings(incoming)
        val first = repo.getUiSettings()
        // Same keys, same values: the no-op guard must still return the current
        // snapshot untouched (issue #93's write-storm defence) now that the
        // merge runs inside a lock.
        val second = repo.mergeUiSettings(incoming)
        assertEquals(first, second)
        assertEquals(Appearance.Dark, storedAppearance(second))
        assertNull(second["test.writer.0"])
    }
}
