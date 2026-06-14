import Foundation
import Observation
import UIKit
import Client

/// SwiftUI-facing wrapper around the shared KMP `UpdateCheckViewModel`.
///
/// All of the update logic — periodic scheduling, fetching `versions.json`,
/// comparing the running build against the published one — lives in the shared
/// client module so iOS and Android never drift. This wrapper owns the single
/// shared instance for the app (`shared`), bridges the KMP `stateFlow` into
/// `@Observable` properties a SwiftUI banner can bind to, and opens the
/// "more info" URL when the banner is tapped.
///
/// Mirrors the Android `UpdateCheckController`, which holds the same shared
/// view-model behind a process-wide singleton.
@Observable
final class UpdateCheckViewModel {
    /// App-wide instance. One shared checker runs a single periodic loop; the
    /// banner on the hosts and terminal screens observes this.
    @MainActor static let shared = UpdateCheckViewModel()

    /// Whether a newer published build is available; drives banner visibility.
    private(set) var updateAvailable: Bool = false

    /// The newer version's display name, shown in the banner (e.g. "1.4.2").
    private(set) var latestVersionName: String?

    /// The "more info" URL opened when the banner is tapped.
    private(set) var infoURL: URL?

    private let kmp: Client.UpdateCheckViewModel
    private let flowObserver = Client.FlowObserver()

    private init() {
        let info = Bundle.main.infoDictionary
        let versionName = (info?["CFBundleShortVersionString"] as? String) ?? "0"
        // CFBundleVersion is the monotonic build number; it is the comparison
        // baseline against the manifest's `latestVersionCode`.
        let versionCode = Int64((info?["CFBundleVersion"] as? String) ?? "0") ?? 0

        // "ios" matches Client.UpdatePlatform.IOS — passed as a literal to avoid
        // relying on Kotlin `object` constant name-mangling across the bridge.
        kmp = Client.UpdateCheckViewModelKt.createUpdateCheckViewModel(
            platformId: "ios",
            currentVersionCode: versionCode,
            currentVersionName: versionName,
            store: IosUpdateCheckStore()
        )

        flowObserver.observe(flow: kmp.stateFlow) { [weak self] value in
            guard let state = value as? Client.UpdateCheckViewModel.State else { return }
            let available = state.updateAvailable
            let name = state.latestVersionName
            let url = state.infoUrl.flatMap { URL(string: $0) }
            DispatchQueue.main.async {
                self?.updateAvailable = available
                self?.latestVersionName = name
                self?.infoURL = url
            }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Start the periodic check loop. Idempotent on the shared KMP view-model,
    /// so it is safe to call on every screen appearance.
    func start() {
        kmp.start()
    }

    /// Open the manifest's "more info" URL in the system browser (Safari).
    @MainActor
    func openUpdateURL() {
        guard let url = infoURL else { return }
        UIApplication.shared.open(url)
    }
}
