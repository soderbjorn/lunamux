import Foundation
import Observation
import Client

/// SwiftUI-facing wrapper around the shared KMP `OverviewBackingViewModel`,
/// which backs the graphical "overview" mode — a miniaturised, *interactive*
/// replica of the web/Electron tabs-and-panes layout (issues #44, #58).
///
/// All decisions — flattening the authoritative `WindowConfig`, applying the
/// toolkit-owned pane geometry, computing per-tab aggregate status, authoring
/// the `LAYOUT_STATE` blob for move/resize/maximize/minimize/layout, and the
/// edit-mode drag state machine — live in the shared `client` module so iOS and
/// Android render the same model and never drift. This wrapper bridges the KMP
/// `stateFlow` / `editTabId` / `drag` flows into `@Observable` properties that
/// `OverviewView` binds to, and forwards every window-management action.
///
/// Mirrors the Android `OverviewContent` composable + hoisted
/// `OverviewBackingViewModel` in `TreeScreen`.
///
/// - SeeAlso: `Client.OverviewBackingViewModel`
@Observable
final class OverviewViewModel {
    /// Every visible tab, in display order, each carrying its on-canvas panes
    /// and its dock of minimized panes. Empty before the first config push.
    private(set) var tabs: [Client.OverviewBackingViewModel.OverviewTab] = []

    /// The id of the currently-selected tab, or `nil` before the first config
    /// arrives. Drives both the tab strip highlight and the pager selection.
    private(set) var activeTabId: String?

    /// The hidden ("unlisted") tabs that are not in `tabs` (every hidden tab
    /// except the active one). Surfaced by the tab strip's trailing `⋮` menu so
    /// the user can re-activate them; selecting one shows it temporarily in the
    /// strip. Mirrors the web/Mac far-right overflow menu.
    private(set) var unlistedTabs: [Client.OverviewBackingViewModel.UnlistedTab] = []

    /// The tab currently in edit-layout mode (free move + resize on every
    /// pane), or `nil`. Drives the editing banner, the resize handles, and
    /// disabling the pager so horizontal drags move panes instead of paging.
    private(set) var editTabId: String?

    /// The pane being actively dragged (moved or resized) plus its live,
    /// uncommitted geometry, or `nil`. The canvas renders this pane at
    /// `drag.box` for lag-free feedback and commits on release via `endDrag()`.
    private(set) var drag: Client.OverviewBackingViewModel.Drag?

    /// The shared projection view-model, or `nil` if there is no live
    /// connection (the overview then renders a "Disconnected" placeholder).
    private let backing: Client.OverviewBackingViewModel?

    /// Observes `backing`'s flows. Recreated per `start()` because
    /// `FlowObserver.clear()` permanently cancels its scope.
    private var flowObserver = Client.FlowObserver()

    /// Drives `backing.run()` — the long-running collector that projects config
    /// + state + geometry pushes into the flows. Cancelled on `stop()`.
    private var runTask: Task<Void, Never>?

    init() {
        if let client = ConnectionHolder.shared.client,
           let socket = ConnectionHolder.shared.windowSocket {
            // Mirror the Android hoisted construction (TreeScreen): the backing
            // VM gets the live geometry, the raw LAYOUT_STATE blob for
            // read-modify-write geometry edits, and an HTTP persister so
            // move/resize/maximize/minimize/layout round-trip to the server and
            // sync to every client.
            backing = Client.OverviewBackingViewModel(
                windowSocket: socket,
                geometryByTab: client.windowState.geometryByTab,
                rawLayoutState: client.windowState.rawLayoutState,
                settingsPersister: Client.HttpSettingsPersister(client: client)
            )
        } else {
            backing = nil
        }
    }

    /// Whether a live connection backs this overview. `false` makes the view
    /// show its disconnected placeholder.
    var isConnected: Bool { backing != nil }

    /// The active tab's model, or `nil` when there is no active tab. Drives the
    /// toolbar's layout/pane-count actions.
    var activeTab: Client.OverviewBackingViewModel.OverviewTab? {
        tabs.first { $0.id == activeTabId }
    }

    /// The active tab's on-canvas pane count (minimized panes excluded) — the
    /// count the layout-sheet miniatures depict.
    var activePaneCount: Int { Int(activeTab?.panes.count ?? 0) }

    // MARK: - Lifecycle

    /// Start projecting config/state pushes and observing the result. Called
    /// from `OverviewView.onAppear`.
    func start() {
        guard let backing else { return }
        if runTask == nil {
            // `run()` suspends forever (it collects the config/state/geometry
            // combine); hold the Task so `stop()` can cancel the coroutine.
            runTask = Task { try? await backing.run() }
        }
        flowObserver = Client.FlowObserver()
        flowObserver.observe(flow: backing.stateFlow) { [weak self] value in
            guard let state = value as? Client.OverviewBackingViewModel.State else { return }
            let tabs = state.tabs
            let active = state.activeTabId
            let unlisted = state.unlistedTabs
            DispatchQueue.main.async {
                self?.tabs = tabs
                self?.activeTabId = active
                self?.unlistedTabs = unlisted
            }
        }
        flowObserver.observe(flow: backing.editTabId) { [weak self] value in
            let id = value as? String
            DispatchQueue.main.async { self?.editTabId = id }
        }
        flowObserver.observe(flow: backing.drag) { [weak self] value in
            let d = value as? Client.OverviewBackingViewModel.Drag
            DispatchQueue.main.async { self?.drag = d }
        }
    }

    /// Tear down the collector and observer. Called from
    /// `OverviewView.onDisappear` (toggling back to the list, or drilling into a
    /// pane).
    func stop() {
        flowObserver.clear()
        runTask?.cancel()
        runTask = nil
    }

    // MARK: - Structural actions (server WindowCommands)

    /// Activate `tabId` server-side; the change syncs to every client via the
    /// broadcast config echo. `activeTabId` updates when that echo arrives.
    func setActiveTab(_ tabId: String) {
        guard let backing else { return }
        Task { try? await backing.setActiveTab(tabId: tabId) }
    }

    /// Hide or reveal `tabId` in the tab strip ("unlist"/"list" it). A hidden
    /// tab stays reachable through the strip's trailing `⋮` unlisted-tabs menu
    /// and remains visible while it is the active tab. Invoked from the tab
    /// chip's context menu.
    func setTabHidden(tabId: String, hidden: Bool) {
        guard let backing else { return }
        Task { try? await backing.setTabHidden(tabId: tabId, hidden: hidden) }
    }

    /// Hide or reveal `tabId` in the sidebar tab tree (and the sessions list,
    /// which mirrors it). Orthogonal to the tab-strip flag. Invoked from the
    /// tab chip's context menu.
    func setTabHiddenFromSidebar(tabId: String, hidden: Bool) {
        guard let backing else { return }
        Task { try? await backing.setTabHiddenFromSidebar(tabId: tabId, hidden: hidden) }
    }

    /// Focus `paneId` in `tabId` and raise it to the front — the desktop
    /// "click a window → it activates and comes forward" behaviour.
    func focusPane(tabId: String, paneId: String) {
        guard let backing else { return }
        Task { try? await backing.focusPane(tabId: tabId, paneId: paneId) }
    }

    /// Add a new pane of `kindWire` (`"terminal"` / `"fileBrowser"` / `"git"`)
    /// to `tabId`. The server positions and sizes it.
    func addPane(tabId: String, kindWire: String) {
        guard let backing else { return }
        Task { try? await backing.addPane(tabId: tabId, kindWire: kindWire) }
    }

    /// Close `paneId`, terminating its session.
    func closePane(paneId: String) {
        guard let backing else { return }
        Task { try? await backing.closePane(paneId: paneId) }
    }

    // MARK: - Geometry actions (LAYOUT_STATE writes)

    /// Toggle `paneId`'s maximized state in `tabId`.
    func toggleMaximize(tabId: String, paneId: String) {
        guard let backing else { return }
        Task { try? await backing.toggleMaximize(tabId: tabId, paneId: paneId) }
    }

    /// Minimize `paneId` to the dock.
    func minimize(tabId: String, paneId: String) {
        guard let backing else { return }
        Task { try? await backing.minimize(tabId: tabId, paneId: paneId) }
    }

    /// Restore `paneId` from the dock back to its stored geometry, and raise it.
    func restore(tabId: String, paneId: String) {
        guard let backing else { return }
        Task { try? await backing.restore(tabId: tabId, paneId: paneId) }
    }

    /// Apply the preset identified by `presetKey` to `tabId` (string-keyed so
    /// the SwiftUI layer never holds a bridged `LayoutPreset`).
    func applyLayout(tabId: String, presetKey: String) {
        guard let backing else { return }
        Task { try? await backing.applyLayoutByKey(tabId: tabId, presetKey: presetKey) }
    }

    /// The persisted layout-preset key for `tabId`, or `nil`. Drives the layout
    /// sheet's "current layout" marker.
    func activePresetKey(tabId: String) -> String? {
        backing?.activePresetKey(tabId: tabId)
    }

    // MARK: - Edit-layout mode (unified free move + resize)

    /// Enter edit-layout mode for `tabId`: every pane becomes draggable and
    /// corner-resizable until `exitEdit()`.
    func enterEdit(tabId: String) { backing?.enterEdit(tabId: tabId) }

    /// Leave edit-layout mode, discarding any uncommitted in-flight drag.
    func exitEdit() { backing?.exitEdit() }

    /// Begin a drag (move or resize) on `paneId`, seeding the live box from its
    /// current geometry.
    func beginDrag(tabId: String, paneId: String) {
        backing?.beginDrag(tabId: tabId, paneId: paneId)
    }

    /// Translate the dragged pane by a fractional delta (tab fractions).
    func dragMoveBy(dxFrac: Double, dyFrac: Double) {
        backing?.dragMoveBy(dxFrac: dxFrac, dyFrac: dyFrac)
    }

    /// Grow/shrink the dragged pane by a fractional delta (tab fractions).
    func dragResizeBy(dwFrac: Double, dhFrac: Double) {
        backing?.dragResizeBy(dwFrac: dwFrac, dhFrac: dhFrac)
    }

    /// Commit the in-flight drag to `LAYOUT_STATE`. Stays in edit mode.
    func endDrag() {
        guard let backing else { return }
        Task { try? await backing.endDrag() }
    }

    deinit {
        flowObserver.clear()
        runTask?.cancel()
    }
}
