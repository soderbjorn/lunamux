/**
 * iOS/Darwin `actual` implementation of [secureRandomBytes].
 *
 * Uses Apple's Security framework (`SecRandomCopyBytes`) to generate
 * cryptographically strong random bytes for device-auth token creation.
 *
 * @see secureRandomBytes
 * @see getOrCreateToken
 */
package se.soderbjorn.termtastic.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * Generate [n] cryptographically strong random bytes using Apple's
 * `SecRandomCopyBytes`.
 *
 * @param n the number of random bytes to produce.
 * @return a [ByteArray] of length [n].
 */
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, n.toULong(), pinned.addressOf(0))
    }
    return out
}
