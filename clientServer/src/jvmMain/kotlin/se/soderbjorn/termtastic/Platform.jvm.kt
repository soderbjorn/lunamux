/**
 * JVM-specific [Platform] implementation for the Ktor server backend.
 * Reports the Java runtime version.
 */
package se.soderbjorn.termtastic

/**
 * [Platform] implementation for the JVM target, used by the Ktor backend server.
 * Reads the `java.version` system property at construction time.
 *
 * @see Platform
 * @see getPlatform
 */
class JVMPlatform: Platform {
    /** Returns `"Java <version>"`, e.g. `"Java 21.0.1"`. */
    override val name: String = "Java ${System.getProperty("java.version")}"
}

/**
 * Returns a [JVMPlatform] instance.
 *
 * @return the JVM [Platform] implementation
 */
actual fun getPlatform(): Platform = JVMPlatform()