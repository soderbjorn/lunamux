/**
 * JVM (desktop/Electron) `actual` implementation of [secureRandomBytes].
 *
 * Uses [java.security.SecureRandom] to generate cryptographically strong
 * random bytes for device-auth token creation. Functionally identical to
 * the Android implementation but lives in `jvmMain` for the CIO-based
 * desktop client and test targets.
 *
 * @see secureRandomBytes
 * @see getOrCreateToken
 */
package se.soderbjorn.termtastic.client

import java.security.SecureRandom

/** Singleton [SecureRandom] instance, seeded by the platform. */
private val rng = SecureRandom()

/**
 * Generate [n] cryptographically strong random bytes using the JVM's
 * [SecureRandom].
 *
 * @param n the number of random bytes to produce.
 * @return a [ByteArray] of length [n].
 */
actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    rng.nextBytes(out)
    return out
}
