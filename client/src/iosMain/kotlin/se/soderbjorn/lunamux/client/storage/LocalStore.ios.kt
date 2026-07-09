/**
 * iOS `actual` for [LocalStore], backed by `NSDocumentDirectory`.
 *
 * Files are written under the per-app Documents directory — persistent across
 * launches and included in device backups. Reads/writes dispatch to a
 * background dispatcher and bridge bytes between Kotlin and Foundation via
 * NSData / cinterop. Construction is no-arg; path resolution is lazy so the
 * cinterop lookup only fires once.
 */
package se.soderbjorn.lunamux.client.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS `actual` of [LocalStore]. Backed by `NSDocumentDirectory`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class LocalStore {

    private val documentsDirPath: String by lazy {
        (NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String)
            ?: error("Documents directory unavailable")
    }

    private fun filePath(name: String): String = "$documentsDirPath/$name"

    actual suspend fun read(name: String): String? = withContext(Dispatchers.Default) {
        val path = filePath(name)
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(path)) return@withContext null
        val data: NSData = NSData.create(contentsOfFile = path) ?: return@withContext null
        val length = data.length.toInt()
        val bytes = ByteArray(length)
        if (length > 0) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
        bytes.decodeToString()
    }

    actual suspend fun write(name: String, text: String): Unit = withContext(Dispatchers.Default) {
        val path = filePath(name)
        val bytes = text.encodeToByteArray()
        // NSData.create copies immediately, so unpinning at lambda exit is safe.
        // Use a 1-byte scratch when empty (length=0 ignores the pointer contents).
        val source = if (bytes.isEmpty()) ByteArray(1) else bytes
        source.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
            data.writeToFile(path, atomically = true)
        }
        Unit
    }

    actual suspend fun delete(name: String): Unit = withContext(Dispatchers.Default) {
        NSFileManager.defaultManager.removeItemAtPath(filePath(name), error = null)
        Unit
    }
}
