/**
 * Android `actual` for [SecureStore], backed by `EncryptedSharedPreferences`.
 *
 * Values are stored in a dedicated `termtastic_secure` preferences file whose
 * keys and values are encrypted with AES-256 (SIV for keys, GCM for values); the
 * master key lives in the hardware-backed Android Keystore. Like the rest of the
 * app's private storage this is removed on uninstall, but — unlike the plain
 * `local_state.json` — its contents are not readable from a device file dump or
 * an unencrypted backup.
 *
 * The application [android.content.Context] is read from [LocalStoreContext]
 * (set once in `MainActivity.onCreate`, before any store is constructed), the
 * same holder the Android [LocalStore] uses. All operations run on
 * [Dispatchers.IO]; the underlying `SharedPreferences` is created lazily on
 * first access.
 */
package se.soderbjorn.termtastic.client.storage

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android `actual` of [SecureStore]. Backed by `EncryptedSharedPreferences`.
 */
actual class SecureStore {

    private val prefs: SharedPreferences by lazy {
        val context = LocalStoreContext.appContext
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(key, null) }.getOrNull()
    }

    actual suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putString(key, value).commit() }
        Unit
    }

    actual suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().remove(key).commit() }
        Unit
    }

    private companion object {
        /** Encrypted preferences file backing the secure store. */
        const val PREFS_FILE_NAME = "termtastic_secure"
    }
}
