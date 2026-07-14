/**
 * Tests for the scrollback width-persistence path in [SettingsRepository]:
 *  - cols/rows round-trip through save/load ([SettingsRepository.ScrollbackRecord]);
 *  - a legacy database whose `pane_scrollback` table predates the cols/rows
 *    columns is migrated in place by the idempotent ALTERs in the repository
 *    init, and its rows load with null size (callers fall back to the
 *    session default grid);
 *  - reopening an already-migrated database does not throw.
 */
package se.soderbjorn.lunamux.persistence

import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScrollbackPersistenceTest {

    private fun tempDbFile(): File =
        File.createTempFile("lunamux-scrollback-test", ".db").apply { deleteOnExit() }

    @Test
    fun colsAndRowsRoundTrip() = runBlocking {
        val repo = SettingsRepository(tempDbFile())
        val bytes = "hello scrollback".toByteArray()
        repo.saveScrollback("leaf-1", bytes, 187, 45)

        val record = repo.loadScrollback("leaf-1")
        assertNotNull(record)
        assertContentEquals(bytes, record.bytes)
        assertEquals(187, record.cols)
        assertEquals(45, record.rows)
    }

    @Test
    fun nullSizePersistsAsNull() = runBlocking {
        val repo = SettingsRepository(tempDbFile())
        repo.saveScrollback("leaf-1", byteArrayOf(1, 2, 3), null, null)

        val record = repo.loadScrollback("leaf-1")
        assertNotNull(record)
        assertNull(record.cols)
        assertNull(record.rows)
    }

    @Test
    fun missingLeafLoadsAsNull() {
        val repo = SettingsRepository(tempDbFile())
        assertNull(repo.loadScrollback("no-such-leaf"))
    }

    @Test
    fun legacyDatabaseIsMigratedAndLoadsWithNullSize() {
        // Build a database exactly as a pre-size-recording server left it:
        // user_version=1 (so the SQLDelight create path is skipped) and the
        // old three-column pane_scrollback shape with a row in it.
        val dbFile = tempDbFile()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)"
                )
                st.executeUpdate(
                    """
                    CREATE TABLE pane_scrollback (
                        leaf_id    TEXT NOT NULL PRIMARY KEY,
                        bytes      BLOB NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate("PRAGMA user_version = 1")
            }
            conn.prepareStatement(
                "INSERT INTO pane_scrollback(leaf_id, bytes, updated_at) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setString(1, "legacy-leaf")
                ps.setBytes(2, byteArrayOf(42))
                ps.setLong(3, 12345L)
                ps.executeUpdate()
            }
        }

        val repo = SettingsRepository(dbFile)
        val record = repo.loadScrollback("legacy-leaf")
        assertNotNull(record)
        assertContentEquals(byteArrayOf(42), record.bytes)
        assertNull(record.cols)
        assertNull(record.rows)
    }

    @Test
    fun reopeningMigratedDatabaseIsIdempotent() = runBlocking {
        val dbFile = tempDbFile()
        SettingsRepository(dbFile).saveScrollback("leaf-1", byteArrayOf(7), 100, 30)

        // Second open runs the ALTERs again; the duplicate-column error must
        // be swallowed and the data must still be there.
        val reopened = SettingsRepository(dbFile)
        val record = reopened.loadScrollback("leaf-1")
        assertNotNull(record)
        assertEquals(100, record.cols)
        assertEquals(30, record.rows)
    }
}
