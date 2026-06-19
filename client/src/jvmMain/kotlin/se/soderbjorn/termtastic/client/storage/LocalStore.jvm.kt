/**
 * JVM `actual` for [LocalStore], backed by `~/.termtastic/`.
 *
 * Used by JVM test runs and any JVM-hosted client build so persisted state has
 * a home that survives restarts without colliding with other tools. The
 * directory is created on first use and never cleaned up automatically. All ops
 * dispatch to [Dispatchers.IO].
 */
package se.soderbjorn.termtastic.client.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM `actual` of [LocalStore]. Backed by `~/.termtastic/`.
 */
actual class LocalStore {

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".termtastic").apply { mkdirs() }
    }

    private fun resolve(name: String): File = File(dir, name).also { it.parentFile?.mkdirs() }

    actual suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(name)
        if (!file.exists()) return@withContext null
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    actual suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        runCatching { resolve(name).writeText(text, Charsets.UTF_8) }
        Unit
    }

    actual suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        runCatching { resolve(name).delete() }
        Unit
    }
}
