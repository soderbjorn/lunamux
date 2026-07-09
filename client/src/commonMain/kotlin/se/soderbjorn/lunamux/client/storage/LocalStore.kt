/**
 * Cross-platform JSON-file storage abstraction for the Lunamux client.
 *
 * `expect class LocalStore` is the single persistent-file store every platform
 * implements: Android backs onto `filesDir`, iOS onto `NSDocumentDirectory`,
 * the macOS Electron renderer onto a `userData` file (via an IPC bridge to the
 * main process), and the JVM onto `~/.termtastic/`. The contract is
 * intentionally minimal — read, write, and delete a single UTF-8 text file by
 * name; callers serialize/deserialize JSON with kotlinx.serialization on top.
 *
 * Used for small, non-server local state that must survive app restarts. The
 * primary consumer is [LocalRepository], which persists most on-device state
 * (saved hosts, the onboarding flag, and the dismissed-news-id set + last-check
 * timestamp) as a single `local_state.json` file. The device-auth token is the
 * exception: the repository keeps it in [SecureStore] (Keychain /
 * `EncryptedSharedPreferences`), not this plain-text store.
 *
 * The `expect` declares no constructor, so each platform's `actual` is free to
 * construct itself however it needs (the Android actual reads a process-wide
 * application [android.content.Context]; the others are no-arg). Common code
 * never instantiates a [LocalStore] — platform glue does and injects it.
 *
 * @see LocalRepository
 */
package se.soderbjorn.lunamux.client.storage

/**
 * A tiny platform-backed file store. Values are plain UTF-8 text; serialize via
 * kotlinx.serialization before [write] and deserialize after [read]. Backed by
 * a persistent per-app directory that survives restarts and is not subject to
 * OS purge.
 */
expect class LocalStore {
    /**
     * Read the file named [name] and return its UTF-8 contents.
     *
     * @param name the file name (no directory component needed).
     * @return the file contents, or `null` if the file does not exist or could
     *   not be read.
     */
    suspend fun read(name: String): String?

    /**
     * Write [text] to the file named [name] as UTF-8, overwriting any existing
     * content. Creates the backing directory on demand.
     *
     * @param name the file name (no directory component needed).
     * @param text the UTF-8 text to persist.
     */
    suspend fun write(name: String, text: String)

    /**
     * Delete the file named [name]. A no-op (best-effort) if it does not exist.
     *
     * @param name the file name to remove.
     */
    suspend fun delete(name: String)
}
