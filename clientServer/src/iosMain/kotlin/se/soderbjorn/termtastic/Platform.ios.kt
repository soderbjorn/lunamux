/**
 * iOS-specific [Platform] implementation. Reports the OS name and version
 * (e.g. `"iOS 17.4"`) obtained from UIKit's [UIDevice].
 */
package se.soderbjorn.termtastic

import platform.UIKit.UIDevice

/**
 * [Platform] implementation for iOS, using [UIDevice.currentDevice] to
 * query the system name and version at runtime.
 *
 * @see Platform
 * @see getPlatform
 */
class IOSPlatform: Platform {
    /** Returns `"<systemName> <systemVersion>"`, e.g. `"iOS 17.4"`. */
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

/**
 * Returns an [IOSPlatform] instance.
 *
 * @return the iOS [Platform] implementation
 */
actual fun getPlatform(): Platform = IOSPlatform()