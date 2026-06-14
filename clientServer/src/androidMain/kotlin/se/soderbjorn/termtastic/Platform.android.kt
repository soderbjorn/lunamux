/**
 * Android-specific [Platform] implementation. Reports the Android API level
 * (e.g. `"Android 34"`) as the platform name.
 */
package se.soderbjorn.termtastic

import android.os.Build

/**
 * [Platform] implementation for Android, using [Build.VERSION.SDK_INT] to
 * identify the runtime API level.
 *
 * @see Platform
 * @see getPlatform
 */
class AndroidPlatform : Platform {
    /** Returns `"Android <SDK_INT>"`, e.g. `"Android 34"`. */
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

/**
 * Returns an [AndroidPlatform] instance.
 *
 * @return the Android [Platform] implementation
 */
actual fun getPlatform(): Platform = AndroidPlatform()