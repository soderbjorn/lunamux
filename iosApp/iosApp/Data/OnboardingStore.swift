import Foundation

/// First-launch flag for the onboarding walkthrough, persisted in
/// `UserDefaults`.
///
/// Read once by `RootNavigationView` at startup to decide whether to present
/// `OnboardingView`, and written when the walkthrough is finished so it never
/// appears again. The Android counterpart is `OnboardingPreferences`, which
/// stores the same boolean in Jetpack DataStore.
enum OnboardingStore {
    /// Versioned key so a future onboarding revision can be re-shown by bumping
    /// the suffix without colliding with the old flag.
    private static let key = "onboarding_seen_v1"

    /// Whether the walkthrough has already been completed on this device.
    static var hasSeen: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    /// Mark the walkthrough as completed so it is never shown again.
    static func markSeen() {
        UserDefaults.standard.set(true, forKey: key)
    }
}
