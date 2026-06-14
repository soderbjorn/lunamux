/**
 * Kotlin/JS-specific [Platform] implementation for the Electron/web frontend.
 */
package se.soderbjorn.termtastic

/**
 * [Platform] implementation for the Kotlin/JS compilation target, used by the
 * Electron-based web frontend.
 *
 * @see Platform
 * @see getPlatform
 */
class JsPlatform: Platform {
    /** Returns the fixed string `"Web with Kotlin/JS"`. */
    override val name: String = "Web with Kotlin/JS"
}

/**
 * Returns a [JsPlatform] instance.
 *
 * @return the Kotlin/JS [Platform] implementation
 */
actual fun getPlatform(): Platform = JsPlatform()