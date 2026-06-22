import Foundation
import Observation
import Client

/// SwiftUI-facing wrapper around the shared KMP `OverviewBackingViewModel`,
/// which backs the graphical "overview" mode (issue #44) — a miniaturised,
/// read-only replica of the web/Electron tabs-and-panes layout.
///
/// All projection logic — flattening the authoritative `WindowConfig`, applying
/// the toolkit-owned pane geometry, computing per-tab aggregate status, and
/// routing tab activation back to the server — lives in the shared `client`
/// module so iOS and Android render the same model and never drift. This
/// wrapper only bridges the KMP `stateFlow` into `@Observable` properties that
/// `OverviewView` binds to, and forwards tab activation.
///
/// Mirrors the Android `OverviewContent` composable, which constructs the same
/// shared view-model with `client.windowState.geometryByTab`.
///
/// - SeeAlso: `Client.OverviewBackingViewModel`
@Observable
final class OverviewViewModel {
    /// Every visible tab, in display order, each carrying its panes — bound by
    /// the tab strip and the paging canvas. Empty before the first config push.
    private(set) var tabs: [Client.OverviewBackingViewModel.OverviewTab] = []

    /// The id of the currently-selected tab, or `nil` before the first config
    /// arrives. Drives both the tab strip highlight and the pager selection.
    private(set) var activeTabId: String?

    /// The shared projection view-model, or `nil` if there is no live
    /// connection (the overview then renders a "Disconnected" placeholder).
    private let backing: Client.OverviewBackingViewModel?

    /// Observes `backing.stateFlow`. Recreated per `start()` because
    /// `FlowObserver.clear()` permanently cancels its scope.
    private var flowObserver = Client.FlowObserver()

    /// Drives `backing.run()` — the long-running collector that projects config
    /// + state pushes into `stateFlow`. Cancelled on `stop()`.
    private var runTask: Task<Void, Never>?

    init() {
        if let client = ConnectionHolder.shared.client,
           let socket = ConnectionHolder.shared.windowSocket {
            backing = Client.OverviewBackingViewModel(
                windowSocket: socket,
                geometryByTab: client.windowState.geometryByTab
            )
        } else {
            backing = nil
        }
    }

    /// Whether a live connection backs this overview. `false` makes the view
    /// show its disconnected placeholder.
    var isConnected: Bool { backing != nil }

    /// Start projecting config/state pushes and observing the result. Called
    /// from `OverviewView.onAppear`.
    func start() {
        guard let backing else { return }
        if runTask == nil {
            // `run()` suspends forever (it collects the config/state combine);
            // hold the Task so `stop()` can cancel the underlying coroutine.
            runTask = Task { try? await backing.run() }
        }
        flowObserver = Client.FlowObserver()
        flowObserver.observe(flow: backing.stateFlow) { [weak self] value in
            guard let state = value as? Client.OverviewBackingViewModel.State else { return }
            let tabs = state.tabs
            let active = state.activeTabId
            DispatchQueue.main.async {
                self?.tabs = tabs
                self?.activeTabId = active
            }
        }
    }

    /// Tear down the collector and observer. Called from
    /// `OverviewView.onDisappear` (toggling back to the list, or drilling into a
    /// pane), matching the Android registry/disposable lifecycle.
    func stop() {
        flowObserver.clear()
        runTask?.cancel()
        runTask = nil
    }

    /// Activate `tabId` server-side, exactly like the web client: the server
    /// persists the choice and broadcasts an updated config to every client, so
    /// the selection syncs across devices. `activeTabId` updates when that echo
    /// arrives, not optimistically.
    ///
    /// - Parameter tabId: the tab to activate.
    func setActiveTab(_ tabId: String) {
        guard let backing else { return }
        Task { try? await backing.setActiveTab(tabId: tabId) }
    }

    deinit {
        flowObserver.clear()
        runTask?.cancel()
    }
}
