/**
 * Serializable models for the cross-platform version-update manifest.
 *
 * Every Termtastic client (Android, iOS, and the macOS Electron app) fetches a
 * single `versions.json` published at the root of the GitHub repository and
 * deserializes it into a [VersionManifest]. The manifest lists, per platform,
 * the latest published build so a client can compare it against its own running
 * version and surface a discreet "new version available" notification.
 *
 * These classes intentionally mirror the on-disk JSON one-to-one (see the
 * repo-root `versions.json`) and live in the shared `client` module so all
 * three platforms parse the same shape — the comparison and notification logic
 * in [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel]
 * is written once here rather than duplicated in Kotlin and Swift.
 *
 * @see se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel
 */
package se.soderbjorn.termtastic.client.update

import kotlinx.serialization.Serializable

/**
 * Stable identifiers for the platforms that publish builds in `versions.json`.
 *
 * The strings match the JSON keys under [VersionManifest.platforms]. Each
 * client passes the constant for the platform it is running on to
 * [se.soderbjorn.termtastic.client.newsupdates.NewsUpdatesBackingViewModel] so
 * the right entry is consulted. Note there is no `web` entry — a plain browser
 * tab shows no update notification; the macOS Electron desktop app uses [MAC].
 */
object UpdatePlatform {
    /** The Android app, published to the Play Store. */
    const val ANDROID: String = "android"

    /** The iOS app, published to the App Store. */
    const val IOS: String = "ios"

    /** The macOS Electron desktop app. */
    const val MAC: String = "mac"
}

/**
 * The top-level shape of `versions.json`.
 *
 * @property schemaVersion format version of the manifest, bumped if the layout
 *   ever changes incompatibly. Clients that see a [schemaVersion] newer than the
 *   one they understand ([SUPPORTED_SCHEMA_VERSION]) treat the manifest as
 *   "no update" rather than risk misreading a future format.
 * @property platforms map keyed by an [UpdatePlatform] identifier to that
 *   platform's latest published build. A client looks up only its own key.
 */
@Serializable
data class VersionManifest(
    val schemaVersion: Int = 1,
    val platforms: Map<String, PlatformVersionInfo> = emptyMap(),
) {
    companion object {
        /** The highest [schemaVersion] this build knows how to interpret. */
        const val SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/**
 * The latest published build for one platform.
 *
 * @property latestVersionCode monotonic integer build number; compared against
 *   the running build's version code to decide whether an update exists. This
 *   (not [latestVersionName]) is the source of truth for the comparison.
 * @property latestVersionName human-readable version string (e.g. "1.4.2"),
 *   shown to the user in the notification.
 * @property url the "more info" link opened when the user taps/clicks the
 *   notification — typically a store page or the GitHub releases page.
 */
@Serializable
data class PlatformVersionInfo(
    val latestVersionCode: Long,
    val latestVersionName: String,
    val url: String,
)
