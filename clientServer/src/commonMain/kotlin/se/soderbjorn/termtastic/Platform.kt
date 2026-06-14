/**
 * Platform abstraction layer using Kotlin Multiplatform expect/actual.
 * Each target (Android, iOS, JS, JVM, WasmJS) provides a concrete
 * implementation that reports the runtime environment name.
 */
package se.soderbjorn.termtastic

/**
 * Common interface exposing platform-specific metadata. Each compilation
 * target provides an actual implementation (e.g. [AndroidPlatform],
 * [JVMPlatform]) via [getPlatform].
 */
interface Platform {
    /** A human-readable name identifying the runtime platform, e.g. `"Java 17"` or `"Android 34"`. */
    val name: String
}

/**
 * Returns the [Platform] implementation for the current compilation target.
 * This is a Kotlin Multiplatform `expect` function; each source set
 * (androidMain, iosMain, jsMain, jvmMain, wasmJsMain) supplies the
 * corresponding `actual` factory.
 *
 * @return the platform-specific [Platform] instance
 * @see Platform
 * @see Greeting
 */
expect fun getPlatform(): Platform