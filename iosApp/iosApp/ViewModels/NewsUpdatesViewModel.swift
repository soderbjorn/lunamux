import Foundation
import Observation
import UIKit
import Client

/// A single news item, flattened from the KMP `NewsItem` into a SwiftUI-friendly
/// `Identifiable` value (so `ForEach` / `.swipeActions` work directly).
struct NewsItemLocal: Identifiable {
    let id: String
    let date: String?
    let title: String
    let body: String
    let url: URL?

    init(from item: Client.NewsItem) {
        id = item.id
        date = item.date
        title = item.title
        body = item.body
        url = item.url.flatMap { URL(string: $0) }
    }
}

/// SwiftUI-facing wrapper around the shared KMP `NewsUpdatesBackingViewModel`.
///
/// All of the logic — periodic scheduling, fetching `versions.json` + `news.json`,
/// comparing the running build, filtering dismissed news, and JSON-file
/// persistence — lives in the shared `client` module so iOS and Android never
/// drift. This wrapper owns the single app-wide instance (`shared`), bridges the
/// KMP `stateFlow` into `@Observable` properties the toolbar bell and the
/// "News & Updates" screen bind to, dismisses news items and the update box,
/// restores dismissed items, and opens the download URL.
///
/// Mirrors the Android `NewsUpdatesController`, which holds the same shared
/// view-model behind a process-wide singleton.
@Observable
final class NewsUpdatesViewModel {
    /// App-wide instance. One shared checker runs a single periodic loop; the
    /// bell icon on the hosts/sessions screens and the News & Updates screen
    /// observe this.
    @MainActor static let shared = NewsUpdatesViewModel()

    /// Whether a newer published build is available.
    private(set) var updateAvailable: Bool = false

    /// The newer version's display name, shown in the "New update" section.
    private(set) var latestVersionName: String?

    /// The download URL opened from the "New update" section.
    private(set) var infoURL: URL?

    /// The active, not-yet-dismissed news items, newest first as published.
    private(set) var newsItems: [NewsItemLocal] = []

    /// Whether the toolbar bell should show: there is news or an update.
    var hasContent: Bool { updateAvailable || !newsItems.isEmpty }

    /// Whether the toolbar bell should pulse: there is actual news to read. A
    /// version update on its own shows the bell but does not pulse it.
    var hasNews: Bool { !newsItems.isEmpty }

    private let kmp: Client.NewsUpdatesBackingViewModel
    private let flowObserver = Client.FlowObserver()

    private init() {
        let info = Bundle.main.infoDictionary
        let versionName = (info?["CFBundleShortVersionString"] as? String) ?? "0"
        // CFBundleVersion is the monotonic build number — the comparison baseline
        // against the manifest's `latestVersionCode`.
        let versionCode = Int64((info?["CFBundleVersion"] as? String) ?? "0") ?? 0

        // "ios" matches Client.UpdatePlatform.IOS — passed as a literal to avoid
        // relying on Kotlin `object` constant name-mangling across the bridge.
        kmp = Client.NewsUpdatesBackingViewModelKt.createNewsUpdatesBackingViewModel(
            repository: AppRepository.shared,
            platformId: "ios",
            currentVersionCode: versionCode,
            currentVersionName: versionName
        )

        flowObserver.observe(flow: kmp.stateFlow) { [weak self] value in
            guard let state = value as? Client.NewsUpdatesBackingViewModel.State else { return }
            let available = state.updateAvailable
            let name = state.latestVersionName
            let url = state.infoUrl.flatMap { URL(string: $0) }
            let items = state.newsItems.map { NewsItemLocal(from: $0) }
            DispatchQueue.main.async {
                self?.updateAvailable = available
                self?.latestVersionName = name
                self?.infoURL = url
                self?.newsItems = items
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

    /// Dismiss a news item: removes it from `newsItems` (reactively, via the
    /// StateFlow) and persists the dismissal so it never reappears.
    ///
    /// - Parameter id: the `NewsItem.id` to dismiss.
    func dismissNews(id: String) {
        kmp.dismissNews(id: id)
    }

    /// Dismiss the currently-advertised update ("seen, don't remind me"): hides
    /// the "New update" box (reactively, via the StateFlow) and persists the
    /// dismissed version so it stays hidden until a newer build ships.
    func dismissUpdate() {
        kmp.dismissUpdate()
    }

    /// Restore everything the user has dismissed — all news items and any
    /// dismissed update — so they reappear (reactively, via the StateFlow).
    func restoreAll() {
        kmp.restoreAll()
    }

    /// Open the manifest's download URL in the system browser (Safari).
    @MainActor
    func openUpdateURL() {
        guard let url = infoURL else { return }
        UIApplication.shared.open(url)
    }
}
