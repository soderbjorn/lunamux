/**
 * Cross-platform secure key/value storage for a single secret string.
 *
 * `expect class SecureStore` is the OS-backed secret store every platform
 * implements with its strongest available protection: iOS uses the Keychain,
 * Android uses `EncryptedSharedPreferences` (AES-256, key in the Android
 * Keystore), the JVM falls back to a plain file under `~/.termtastic/` (dev/test
 * only — not actually encrypted), and JS falls back to `localStorage`.
 *
 * It exists so the one piece of genuinely sensitive on-device state — the
 * device-auth token — can live outside the plain-JSON [LocalRepository]
 * (`local_state.json`) while every other field stays in the unified file. The
 * contract is intentionally minimal: read, write, and delete one UTF-8 string by
 * key. Common code never instantiates a [SecureStore]; platform glue does and
 * injects it into [LocalRepository].
 *
 * Unlike [LocalStore], the iOS Keychain entry is **not** removed on app
 * uninstall and is **not** included in iCloud/iTunes backups, so the token both
 * survives reinstalls and never leaks through a backup.
 *
 * @see LocalRepository
 * @see LocalStore
 */
package se.soderbjorn.termtastic.client.storage

/**
 * The [SecureStore] key under which [LocalRepository.getOrCreateAuthToken]
 * stores the device-auth token. On iOS this is the Keychain `kSecAttrAccount`
 * value; it matches the account the pre-consolidation `KeychainAuthTokenStore`
 * used, so an existing device's token (and its server-side approval) carries
 * over rather than being re-minted.
 */
const val SECURE_AUTH_TOKEN_KEY: String = "device-token"

/**
 * A tiny OS-backed secret store for single UTF-8 string values, keyed by name.
 * Values survive app restarts; on iOS they additionally survive reinstalls.
 */
expect class SecureStore {
    /**
     * Read the secret stored under [key].
     *
     * @param key the entry key (e.g. [SECURE_AUTH_TOKEN_KEY]).
     * @return the stored value, or `null` if absent or unreadable.
     */
    suspend fun read(key: String): String?

    /**
     * Store [value] under [key], overwriting any existing entry.
     *
     * @param key the entry key.
     * @param value the UTF-8 secret to persist.
     */
    suspend fun write(key: String, value: String)

    /**
     * Delete the entry stored under [key]. Best-effort no-op if absent.
     *
     * @param key the entry key to remove.
     */
    suspend fun delete(key: String)
}
