/**
 * Android `actual` for [LocalStore], backed by the app's private `filesDir`.
 *
 * Files are written directly under `context.filesDir`, which is per-app,
 * persistent across launches, and removed on uninstall. All operations run on
 * [Dispatchers.IO].
 *
 * Because the `expect class LocalStore` declares no constructor (the iOS/JS/JVM
 * actuals are no-arg), this Android actual obtains the process-wide application
 * [Context] from [LocalStoreContext], which the app sets once at startup
 * (`MainActivity.onCreate`). Constructing a [LocalStore] before that holder is
 * initialized throws on first file access.
 */
package se.soderbjorn.termtastic.client.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide holder for the application [Context] used by the Android
 * [LocalStore]. Set once at app startup before any [LocalStore] is constructed.
 */
object LocalStoreContext {
    /** The application context; assigned once from `MainActivity.onCreate`. */
    @Volatile
    lateinit var appContext: Context
}

/**
 * Android `actual` of [LocalStore]. Backed by `filesDir`; all ops dispatch to
 * [Dispatchers.IO].
 */
actual class LocalStore {

    private val dir: File by lazy { LocalStoreContext.appContext.filesDir }

    private fun resolve(name: String): File = File(dir, name).also { it.parentFile?.mkdirs() }

    actual suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val file = resolve(name)
        if (!file.exists()) return@withContext null
        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    actual suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.IO) {
        val file = resolve(name)
        runCatching { file.writeText(text, Charsets.UTF_8) }
        Unit
    }

    actual suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        runCatching { resolve(name).delete() }
        Unit
    }
}
