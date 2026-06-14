/**
 * JS/browser `actual` implementation of [secureRandomBytes].
 *
 * Uses the Web Crypto API (`window.crypto.getRandomValues`) to generate
 * cryptographically strong random bytes for device-auth token creation.
 *
 * @see secureRandomBytes
 * @see getOrCreateToken
 */
package se.soderbjorn.termtastic.client

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlinx.browser.window

/**
 * Generate [n] cryptographically strong random bytes using the browser's
 * `window.crypto.getRandomValues` API.
 *
 * @param n the number of random bytes to produce.
 * @return a [ByteArray] of length [n].
 */
actual fun secureRandomBytes(n: Int): ByteArray {
    val u8 = Uint8Array(n)
    window.asDynamic().crypto.getRandomValues(u8)
    val out = ByteArray(n)
    for (i in 0 until n) {
        out[i] = u8[i]
    }
    return out
}
