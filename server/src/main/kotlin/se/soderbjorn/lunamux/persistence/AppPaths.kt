/**
 * Application data directory resolution for Lunamux.
 *
 * This file contains [AppPaths], which resolves the on-disk locations for the
 * SQLite database and auxiliary state files (e.g. shell init bootstraps).
 * It honours override conventions (`-Dlunamux.dbPath` system property or
 * `LUNAMUX_DB_PATH` environment variable) and falls back to per-OS
 * application-data directories (macOS `~/Library/Application Support`,
 * Windows `%APPDATA%`, Linux `$XDG_DATA_HOME`).
 *
 * Called by:
 *  - [Application.main] to obtain the database file path for
 *    [SettingsRepository].
 *  - [ShellInitFiles] to locate the directory for generated shell
 *    bootstrap scripts.
 *
 * @see SettingsRepository
 * @see ShellInitFiles
 */
package se.soderbjorn.lunamux.persistence

import java.io.File
import java.util.Locale

/**
 * Resolves the on-disk location of the SQLite database that backs persisted
 * server state. Honours the same override convention as `lunamux.port`:
 * a `-Dlunamux.dbPath=...` system property or `LUNAMUX_DB_PATH` env var
 * wins, otherwise we fall back to a per-OS application-data directory.
 */
object AppPaths {

    private const val APP_DIR_NAME = "Termtastic"
    private const val DB_FILE_NAME = "termtastic.db"

    /**
     * Resolve the SQLite database file path.
     *
     * Checks for a `-Dlunamux.dbPath` system property or `LUNAMUX_DB_PATH`
     * environment variable override, then falls back to a per-OS default.
     *
     * @return the [File] pointing to the SQLite database
     */
    fun databaseFile(): File {
        val override = System.getProperty("lunamux.dbPath")
            ?: System.getenv("LUNAMUX_DB_PATH")
        if (!override.isNullOrBlank()) {
            return File(override)
        }
        return File(defaultDataDir(), DB_FILE_NAME)
    }

    /**
     * Directory for auxiliary on-disk state (shell init bootstrap files, etc.).
     * Tracks the database file's parent so a `-Dlunamux.dbPath` override
     * keeps everything together for tests and packaged installs.
     */
    fun dataDir(): File {
        val dbFile = databaseFile()
        return dbFile.parentFile ?: defaultDataDir()
    }

    /**
     * Directory holding the TLS keystore (`server.p12`), its password file
     * (`keystore.pass`), and the SHA-256 fingerprint sidecar
     * (`server.p12.fingerprint`) used by the Electron loopback trust handler.
     *
     * @return the `tls/` subdirectory of [dataDir]; not guaranteed to exist
     *   on disk yet — callers must `mkdirs()` before writing.
     * @see se.soderbjorn.lunamux.tls.CertStore
     */
    fun tlsDir(): File = File(dataDir(), "tls")

    /**
     * Determine the default application data directory based on the OS.
     *
     * - macOS: `~/Library/Application Support/Termtastic`
     * - Windows: `%APPDATA%/Lunamux`
     * - Linux: `$XDG_DATA_HOME/termtastic` (defaults to `~/.local/share/termtastic`)
     *
     * @return the platform-appropriate data directory
     */
    private fun defaultDataDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val home = System.getProperty("user.home")
        return when {
            os.contains("mac") || os.contains("darwin") ->
                File(home, "Library/Application Support/$APP_DIR_NAME")

            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) File(appData, APP_DIR_NAME)
                else File(home, "AppData/Roaming/$APP_DIR_NAME")
            }

            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                val base = if (!xdg.isNullOrBlank()) File(xdg) else File(home, ".local/share")
                File(base, "termtastic")
            }
        }
    }
}
