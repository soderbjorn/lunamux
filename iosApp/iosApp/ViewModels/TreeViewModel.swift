import Foundation
import Observation
import Client

// MARK: - Tree row model

enum LeafKind {
    case terminal
    case fileBrowser
    case git
    case empty
}

enum TreeRow: Identifiable {
    case tabHeader(tabId: String, title: String, aggregateState: String?)
    case leaf(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)

    var id: String {
        switch self {
        case .tabHeader(let tabId, _, _): return "tab-\(tabId)"
        case .leaf(let paneId, _, _, _, _): return "leaf-\(paneId)"
        }
    }
}

// MARK: - ViewModel

/// Observes the KMP `WindowSocket`'s config and states flows via
/// `FlowObserver` and flattens the window tree into a list of `TreeRow`s
/// for the SwiftUI `TreeView`.
@Observable
final class TreeViewModel {
    var rows: [TreeRow] = []
    var states: [String: String] = [:]

    private let flowObserver = Client.FlowObserver()
    private var latestConfig: Client.WindowConfig?

    func subscribe() {
        guard let client = ConnectionHolder.shared.client else { return }

        flowObserver.observe(flow: client.windowState.config) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                self.latestConfig = value as? Client.WindowConfig
                self.rebuild()
            }
        }

        flowObserver.observe(flow: client.windowState.states) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                if let map = value as? [String: String?] {
                    var clean: [String: String] = [:]
                    for (k, v) in map {
                        if let v { clean[k] = v }
                    }
                    self.states = clean
                }
                self.rebuild()
            }
        }
    }

    /// Number of tabs in the current layout. Used to disable "Close Tab"
    /// on the last remaining tab (the server refuses to close it anyway).
    var tabCount: Int {
        latestConfig?.tabs.count ?? 0
    }

    func addTab(name: String) {
        guard let client = ConnectionHolder.shared.client,
              let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.addTab(
                client: client, socket: socket, name: name, timeoutMs: 5_000
            )
        }
    }

    /// Add a new pane of `kindWire` ("terminal" / "fileBrowser" / "git")
    /// directly to the given tab, inheriting the tab's working directory.
    /// Backs the tab header context menu's "New …" actions.
    func addPaneToTab(tabId: String, kindWire: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        let config = latestConfig
        Task {
            try? await Client.PaneActionsKt.addPaneToTab(
                socket: socket, tabId: tabId, kindWire: kindWire, config: config
            )
        }
    }

    /// Set a custom name on a pane; an empty string clears the override so
    /// the title falls back to the pane's working directory.
    func renamePane(paneId: String, title: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.renamePane(
                socket: socket, paneId: paneId, title: title
            )
        }
    }

    /// Close a pane, terminating its session.
    func closePane(paneId: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.closePane(socket: socket, paneId: paneId)
        }
    }

    /// Rename a tab. The server rejects blank titles, so callers validate first.
    func renameTab(tabId: String, title: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.renameTab(
                socket: socket, tabId: tabId, title: title
            )
        }
    }

    /// Close a tab and all panes inside it. The server refuses to close the
    /// last remaining tab; the UI disables the action in that case.
    func closeTab(tabId: String) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.closeTab(socket: socket, tabId: tabId)
        }
    }

    func disconnect() {
        flowObserver.clear()
        Task { @MainActor in
            ConnectionHolder.shared.disconnect()
        }
    }

    deinit {
        flowObserver.clear()
    }

    // MARK: - Flatten logic (mirrors Android's flatten())

    private func rebuild() {
        guard let config = latestConfig else {
            rows = []
            return
        }
        var result: [TreeRow] = []
        for tab in config.tabs {
            var leaves: [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)] = []

            for pane in tab.panes {
                addLeaf(leaf: pane.leaf, floating: false, out: &leaves)
            }

            // Aggregate state: "waiting" wins over "working"
            var tabState: String? = nil
            for leaf in leaves {
                switch states[leaf.sessionId] {
                case "waiting":
                    tabState = "waiting"
                case "working":
                    if tabState != "waiting" { tabState = "working" }
                default:
                    break
                }
                if tabState == "waiting" { break }
            }

            result.append(.tabHeader(tabId: tab.id, title: tab.title, aggregateState: tabState))
            for leaf in leaves {
                result.append(.leaf(
                    paneId: leaf.paneId,
                    sessionId: leaf.sessionId,
                    title: leaf.title,
                    kind: leaf.kind,
                    floating: leaf.floating
                ))
            }
        }
        rows = result
    }

    private func addLeaf(
        leaf: Client.LeafNode,
        floating: Bool,
        out: inout [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool)]
    ) {
        let kind: LeafKind
        if leaf.content is Client.GitContent {
            kind = .git
        } else if leaf.content is Client.FileBrowserContent {
            kind = .fileBrowser
        } else if leaf.content is Client.TerminalContent || leaf.content == nil {
            kind = .terminal
        } else {
            kind = .empty
        }
        out.append((paneId: leaf.id, sessionId: leaf.sessionId, title: leaf.title, kind: kind, floating: floating))
    }
}
