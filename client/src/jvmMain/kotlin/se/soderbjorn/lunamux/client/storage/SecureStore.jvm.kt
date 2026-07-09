/**
 * JVM `actual` for [SecureStore].
 *
 * The JVM target exists for tests and tooling, not a shipping client, and has no
 * OS-managed secret store comparable to the Keychain / Android Keystore. This
 * actual therefore degrades to a plain file under `~/.termtastic/` (one file per
 * key, prefixed `secure-`) — it is **not** encrypted and is not intended to
 * protect anything in production. All ops dispatch to [Dispatchers.IO].
 */
package se.soderbjorn.lunamux.client.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM `actual` of [SecureStore]. Backed by an unencrypted `~/.termtastic/`
 * file (dev/test only).
 */
actual class SecureStore {

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".termtastic").apply { mkdirs() }
    }

    private fun resolve(key: String): File = File(dir, "secure-$key").also { it.parentFile?.mkdirs() }

    actual suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(key)
        if (!file.exists()) return@withContext null
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    actual suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        runCatching { resolve(key).writeText(value, Charsets.UTF_8) }
        Unit
    }

    actual suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        runCatching { resolve(key).delete() }
        Unit
    }
}
