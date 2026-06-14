import Foundation
import Observation
import Client

/// SwiftUI-facing wrapper around the shared KMP `OnboardingViewModel`.
///
/// The walkthrough's pages and copy live in the shared client module
/// (`defaultOnboardingPages()`), so Android and iOS never drift. This wrapper
/// maps each shared `OnboardingPage` into a native `Page` value (turning the
/// semantic `OnboardingIcon` into an SF Symbol and the link string into a
/// `URL`), and bridges the KMP `stateFlow` into an `@Observable` `pageIndex`
/// that a `TabView` can bind to.
///
/// Mirrors the Android `OnboardingScreen`, which consumes the same shared
/// view-model directly.
@Observable
final class OnboardingViewModel {
    /// A single walkthrough page, pre-mapped to iOS-native types.
    struct Page: Identifiable {
        let id: String
        let systemImage: String
        let title: String
        let body: String
        let linkLabel: String?
        let linkURL: URL?
    }

    /// The walkthrough pages, mapped once from the shared content.
    private(set) var pages: [Page] = []

    /// The currently shown page index. Bound to the `TabView` selection; its
    /// setter forwards into the shared view-model so paging state has a single
    /// source of truth. Guarded against the echo from `stateFlow` so the two
    /// directions converge instead of looping.
    var pageIndex: Int = 0 {
        didSet {
            guard pageIndex != oldValue else { return }
            kmp.goTo(index: Int32(pageIndex))
        }
    }

    /// True on the first page, so the "Back" button can be hidden.
    var isFirstPage: Bool { pageIndex == 0 }

    /// True on the last page, so the primary button reads "Get Started".
    var isLastPage: Bool { pageIndex >= pages.count - 1 }

    private let kmp = Client.OnboardingViewModel(
        pages: Client.OnboardingViewModelKt.defaultOnboardingPages()
    )
    private let flowObserver = Client.FlowObserver()

    init() {
        pages = Client.OnboardingViewModelKt.defaultOnboardingPages().map { Self.map($0) }
        // Keep `pageIndex` in step when navigation happens inside the shared
        // view-model (e.g. via `next()` / `previous()`).
        flowObserver.observe(flow: kmp.stateFlow) { [weak self] value in
            guard let state = value as? Client.OnboardingViewModel.State else { return }
            let next = Int(state.pageIndex)
            DispatchQueue.main.async {
                if self?.pageIndex != next { self?.pageIndex = next }
            }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Advance to the next page (no-op on the last page).
    func next() {
        kmp.next()
    }

    /// Return to the previous page (no-op on the first page).
    func previous() {
        kmp.previous()
    }

    /// Map a shared `OnboardingPage` into the native `Page` value.
    private static func map(_ page: Client.OnboardingPage) -> Page {
        Page(
            id: page.id,
            systemImage: symbol(for: page.icon),
            title: page.title,
            body: page.body,
            linkLabel: page.linkLabel,
            linkURL: page.linkUrl.flatMap { URL(string: $0) }
        )
    }

    /// Map the shared, platform-agnostic `OnboardingIcon` to an SF Symbol.
    ///
    /// KMP enum cases bridge as singleton objects rather than Swift enum cases,
    /// so this compares by identity rather than using a `switch`.
    private static func symbol(for icon: Client.OnboardingIcon) -> String {
        if icon == Client.OnboardingIcon.welcome {
            return "hand.wave"
        } else if icon == Client.OnboardingIcon.workspace {
            return "rectangle.3.group"
        } else {
            return "desktopcomputer"
        }
    }
}
