import Foundation
import Observation
import Client

/// SwiftUI-facing wrapper around the shared `FileBrowserBackingViewModel` for
/// the overview's file-browser miniature (issue #44). Subscribes to the same
/// live listing the full-screen file browser uses and exposes the root
/// directory's entries (folders first, then files, alphabetical) for the
/// thumbnail. Mirrors the Android `MiniFileBrowserPane`, which instantiates the
/// same shared view-model per pane.
///
/// - SeeAlso: `Client.FileBrowserBackingViewModel`
@Observable
final class MiniFileBrowserModel {
    /// Root listing, directories first then files, both alphabetical.
    private(set) var entries: [Client.FileBrowserEntry] = []

    private var backing: Client.FileBrowserBackingViewModel?
    private var flowObserver = Client.FlowObserver()
    private var runTask: Task<Void, Never>?

    /// Start listing the pane's root directory and observing live updates.
    ///
    /// - Parameter paneId: the file-browser leaf's pane id.
    func start(paneId: String) {
        guard runTask == nil, let socket = ConnectionHolder.shared.windowSocket else { return }
        let vm = Client.FileBrowserBackingViewModel(paneId: paneId, windowSocket: socket)
        backing = vm
        runTask = Task { try? await vm.run() }
        flowObserver = Client.FlowObserver()
        flowObserver.observe(flow: vm.stateFlow) { [weak self] value in
            guard let state = value as? Client.FileBrowserBackingViewModel.State else { return }
            let root = (state.dirListings[""] as? [Client.FileBrowserEntry]) ?? []
            let sorted = root.sorted { a, b in
                if a.isDir != b.isDir { return a.isDir && !b.isDir }
                return a.name.lowercased() < b.name.lowercased()
            }
            DispatchQueue.main.async { self?.entries = sorted }
        }
    }

    /// Cancel the live collector and observer (the thumbnail left the screen).
    func stop() {
        flowObserver.clear()
        runTask?.cancel()
        runTask = nil
    }

    deinit {
        flowObserver.clear()
        runTask?.cancel()
    }
}

/// SwiftUI-facing wrapper around the shared `GitPaneBackingViewModel` for the
/// overview's git miniature (issue #44). Subscribes to the same live
/// changed-files list the full-screen git pane uses. Mirrors the Android
/// `MiniGitPane`.
///
/// - SeeAlso: `Client.GitPaneBackingViewModel`
@Observable
final class MiniGitModel {
    /// The changed files, or empty before the first load.
    private(set) var entries: [Client.GitFileEntry] = []
    /// Whether the first file-list fetch is still in flight (drives the "…"
    /// placeholder vs. "No changes").
    private(set) var isLoading: Bool = true

    private var backing: Client.GitPaneBackingViewModel?
    private var flowObserver = Client.FlowObserver()
    private var runTask: Task<Void, Never>?

    /// Start fetching the pane's changed files and observing live updates.
    ///
    /// - Parameter paneId: the git leaf's pane id.
    func start(paneId: String) {
        guard runTask == nil, let socket = ConnectionHolder.shared.windowSocket else { return }
        let vm = Client.GitPaneBackingViewModel(paneId: paneId, windowSocket: socket)
        backing = vm
        runTask = Task { try? await vm.run() }
        flowObserver = Client.FlowObserver()
        flowObserver.observe(flow: vm.stateFlow) { [weak self] value in
            guard let state = value as? Client.GitPaneBackingViewModel.State else { return }
            let entries = (state.entries as? [Client.GitFileEntry]) ?? []
            let loading = state.isLoading
            DispatchQueue.main.async {
                self?.entries = entries
                self?.isLoading = loading
            }
        }
    }

    /// Cancel the live collector and observer (the thumbnail left the screen).
    func stop() {
        flowObserver.clear()
        runTask?.cancel()
        runTask = nil
    }

    deinit {
        flowObserver.clear()
        runTask?.cancel()
    }
}
