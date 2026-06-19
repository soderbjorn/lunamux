/**
 * iOS `actual` for [SecureStore], backed by Keychain Services.
 *
 * Each value is a `kSecClassGenericPassword` item under a fixed service
 * (`se.soderbjorn.termtastic.auth`) with the [SecureStore] key as its
 * `kSecAttrAccount`. These match the service/account the pre-consolidation
 * Swift `KeychainAuthTokenStore` used, so a device that already minted a token
 * before this refactor keeps it (and its server-side approval) rather than being
 * issued a fresh one. Keychain items survive app reinstalls and are excluded
 * from unencrypted backups, which is exactly why the auth token lives here
 * instead of in the plain `local_state.json`.
 *
 * The query dictionaries are built with Core Foundation; Kotlin strings are
 * bridged to `NSString`/`NSData` and retained into the dictionaries via
 * `CFBridgingRetain`, then released once the Keychain call returns. All
 * operations run on a background dispatcher.
 *
 * @see SecureStore
 * @see LocalRepository.getOrCreateAuthToken
 */
package se.soderbjorn.termtastic.client.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * iOS `actual` of [SecureStore]. Backed by Keychain Services.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SecureStore {

    /** Keychain `kSecAttrService` shared by every entry this store manages. */
    private val service = "se.soderbjorn.termtastic.auth"

    /**
     * Bridge a Kotlin string into a retained `NSString` reference suitable as a
     * Core Foundation dictionary value. Passing the Kotlin [String] through
     * `CFBridgingRetain` (whose parameter is `Any?`) lets the runtime do the
     * String→NSString bridging without a static cast. The caller must
     * [CFRelease] the result.
     */
    private fun retainedString(s: String): CFTypeRef? = CFBridgingRetain(s)

    /**
     * Build the base lookup dictionary identifying one entry: generic-password
     * class, the shared [service], and the per-entry [key] as the account. The
     * caller owns the returned dictionary and the two bridged strings and must
     * [CFRelease] all three.
     */
    private fun baseQuery(key: String): Triple<CFMutableDictionaryRef?, CFTypeRef?, CFTypeRef?> {
        val query = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        val cfService = retainedString(service)
        val cfAccount = retainedString(key)
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, cfService)
        CFDictionaryAddValue(query, kSecAttrAccount, cfAccount)
        return Triple(query, cfService, cfAccount)
    }

    actual suspend fun read(key: String): String? = withContext(Dispatchers.Default) {
        val (query, cfService, cfAccount) = baseQuery(key)
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val token: String? = memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecSuccess) {
                // CFBridgingRelease takes ownership of the copied data ref.
                (CFBridgingRelease(result.value) as? NSData)?.let { nsDataToString(it) }
            } else {
                null
            }
        }
        CFRelease(query)
        cfService?.let { CFRelease(it) }
        cfAccount?.let { CFRelease(it) }
        token
    }

    actual suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.Default) {
        val (query, cfService, cfAccount) = baseQuery(key)
        // Build NSData from the UTF-8 bytes (NSData.create copies immediately, so
        // unpinning at lambda exit is safe), then retain it as a CFDataRef value.
        val bytes = value.encodeToByteArray()
        val source = if (bytes.isEmpty()) ByteArray(1) else bytes
        val cfData = source.usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
            CFBridgingRetain(data)
        }

        val attributes = CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionaryAddValue(attributes, kSecValueData, cfData)

        // Update an existing item if present; otherwise insert a new one,
        // mirroring the prior Swift KeychainAuthTokenStore.save behaviour.
        val updateStatus = SecItemUpdate(query, attributes)
        if (updateStatus == errSecItemNotFound) {
            CFDictionaryAddValue(query, kSecValueData, cfData)
            SecItemAdd(query, null)
        }

        CFRelease(query)
        CFRelease(attributes)
        cfService?.let { CFRelease(it) }
        cfAccount?.let { CFRelease(it) }
        cfData?.let { CFRelease(it) }
        Unit
    }

    actual suspend fun delete(key: String): Unit = withContext(Dispatchers.Default) {
        val (query, cfService, cfAccount) = baseQuery(key)
        SecItemDelete(query)
        CFRelease(query)
        cfService?.let { CFRelease(it) }
        cfAccount?.let { CFRelease(it) }
        Unit
    }

    /**
     * Copy an [NSData]'s bytes into a UTF-8 Kotlin string, reusing the same
     * pinned-`memcpy` bridge the iOS [LocalStore] uses for file reads.
     */
    private fun nsDataToString(data: NSData): String {
        val length = data.length.toInt()
        val bytes = ByteArray(length)
        if (length > 0) {
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }
        }
        return bytes.decodeToString()
    }
}
