import Foundation
import Client

/// iOS persistence for the version-update checker's last-check timestamp.
///
/// Conforms to the shared KMP `UpdateCheckStore` interface (whose methods are
/// synchronous, so no async/completion-handler bridging is needed) and stores a
/// single epoch-millisecond value in `UserDefaults`. Constructed once and handed
/// to the shared `UpdateCheckViewModel` via `createUpdateCheckViewModel`; the
/// checker reads it on startup to decide whether to check immediately and writes
/// it after each check.
///
/// The Android counterpart is `AndroidUpdateCheckStore` (SharedPreferences); the
/// macOS/Electron one is `JsUpdateCheckStore` (localStorage).
final class IosUpdateCheckStore: UpdateCheckStore {
    /// Versioned key, mirroring `OnboardingStore`'s convention.
    private static let key = "update_last_check_epoch_millis_v1"

    /// Load the last-check timestamp.
    ///
    /// - Returns: the stored epoch-millis time as a `KotlinLong`, or `nil` if no
    ///   check has ever run on this device.
    func loadLastCheckEpochMillis() -> KotlinLong? {
        guard let number = UserDefaults.standard.object(forKey: Self.key) as? NSNumber else {
            return nil
        }
        return KotlinLong(value: number.int64Value)
    }

    /// Persist the time of a check that just completed.
    ///
    /// - Parameter epochMillis: the check time in milliseconds since the epoch.
    func saveLastCheckEpochMillis(epochMillis: Int64) {
        UserDefaults.standard.set(NSNumber(value: epochMillis), forKey: Self.key)
    }
}
