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
    /// A standalone group label (currently only "Hidden") separating the
    /// sidebar-hidden tabs below it from the visible tabs above. Mirrors the
    /// Android `TreeRow.SectionHeader`.
    case sectionHeader(title: String)
    /// A tappable "Show hidden tabs" / "Hide hidden tabs" link that reveals or
    /// re-hides the sidebar-hidden tabs (issue #52). Emitted only when hidden
    /// tabs exist; `showing` is `true` when they are currently revealed.
    /// Mirrors the Android `TreeRow.HiddenToggle`.
    case hiddenToggle(showing: Bool)
    case tabHeader(tabId: String, title: String, aggregateState: String?)
    case leaf(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool, minimized: Bool)

    var id: String {
        switch self {
        case .sectionHeader(let title): return "section-\(title)"
        case .hiddenToggle: return "hidden-toggle"
        case .tabHeader(let tabId, _, _): return "tab-\(tabId)"
        case .leaf(let paneId, _, _, _, _, _): return "leaf-\(paneId)"
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

    /// Whether the sidebar-hidden tabs are currently revealed in the list
    /// (issue #52). Seeded from the process-wide, memory-only
    /// `SessionsViewModeStore.showHiddenTabs` so the choice survives navigating
    /// away from the Sessions screen and back, while resetting to the default
    /// (hidden) on the next app launch. Flipped via `toggleShowHiddenTabs()`.
    private var showHiddenTabs: Bool = Client.SessionsViewModeStore.shared.showHiddenTabs

    private let flowObserver = Client.FlowObserver()
    private var latestConfig: Client.WindowConfig?
    /// Pane ids minimized (docked) on the web client. Mobile has no dock,
    /// so the sessions list dims these rows. Updated live via the
    /// `WindowStateRepository.minimizedPaneIds` flow.
    private var minimizedPaneIds: Set<String> = []

    func subscribe() {
        guard let client = ConnectionHolder.shared.client else { return }

        flowObserver.observe(flow: client.windowState.config) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                self.latestConfig = value as? Client.WindowConfig
                self.rebuild()
            }
        }

        flowObserver.observe(flow: client.windowState.minimizedPaneIds) { [weak self] value in
            DispatchQueue.main.async {
                guard let self else { return }
                if let set = value as? Set<String> {
                    self.minimizedPaneIds = set
                } else if let arr = value as? [String] {
                    self.minimizedPaneIds = Set(arr)
                } else {
                    self.minimizedPaneIds = []
                }
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

    /// Whether the tab is hidden ("unlisted") from the tab strip, straight off
    /// the latest config. Labels the tab menu's hide/show-in-tab-bar item.
    func isTabHidden(tabId: String) -> Bool {
        latestConfig?.tabs.first { $0.id == tabId }?.isHidden ?? false
    }

    /// Whether the tab is hidden from the sidebar tab tree (and this list,
    /// which mirrors it). Labels the tab menu's hide/show-in-sidebar item.
    func isTabHiddenFromSidebar(tabId: String) -> Bool {
        latestConfig?.tabs.first { $0.id == tabId }?.isHiddenFromSidebar ?? false
    }

    /// Hide or reveal a tab in the tab strip ("unlist"/"list" it). A hidden
    /// tab stays reachable through the overview strip's `⋮` unlisted-tabs menu.
    func setTabHidden(tabId: String, hidden: Bool) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.setTabHidden(
                socket: socket, tabId: tabId, hidden: hidden
            )
        }
    }

    /// Hide or reveal a tab in the sidebar tab tree — and therefore this
    /// session list, where hidden tabs move under the "Hidden" reveal
    /// (issue #52). Orthogonal to the tab-strip flag.
    func setTabHiddenFromSidebar(tabId: String, hidden: Bool) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.setTabHiddenFromSidebar(
                socket: socket, tabId: tabId, hidden: hidden
            )
        }
    }

    func disconnect() {
        flowObserver.clear()
        Task { @MainActor in
            ConnectionHolder.shared.disconnect()
        }
    }

    /// Flips whether the sidebar-hidden tabs are revealed (issue #52), persists
    /// the new value in memory via `SessionsViewModeStore.showHiddenTabs`, and
    /// rebuilds the row list. Called by `TreeView` when the user taps the
    /// "Show hidden tabs" / "Hide hidden tabs" link.
    func toggleShowHiddenTabs() {
        showHiddenTabs.toggle()
        Client.SessionsViewModeStore.shared.showHiddenTabs = showHiddenTabs
        rebuild()
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
        // Tabs the user hid from the sidebar are excluded from the list by
        // default (issue #52): when any exist, a single "Show hidden tabs"
        // toggle row is appended instead, keeping the primary list decluttered
        // like the web sidebar (which omits them). When `showHiddenTabs` is set,
        // they are revealed at the bottom under a "Hidden" header and the toggle
        // reads "Hide hidden tabs". Mirrors Android's flatten().
        let visibleTabs = config.tabs.filter { !$0.isHiddenFromSidebar }
        let hiddenTabs = config.tabs.filter { $0.isHiddenFromSidebar }

        var result: [TreeRow] = []
        for tab in visibleTabs {
            appendTab(tab, into: &result)
        }
        if !hiddenTabs.isEmpty {
            result.append(.hiddenToggle(showing: showHiddenTabs))
            if showHiddenTabs {
                result.append(.sectionHeader(title: "Hidden"))
                for tab in hiddenTabs {
                    appendTab(tab, into: &result)
                }
            }
        }
        rows = result
    }

    /// Appends a tab's header row followed by its leaf rows into `result`,
    /// computing the tab's aggregate state dot along the way. Shared by both
    /// the visible and hidden passes of `rebuild()` so the two groups render
    /// identically.
    private func appendTab(_ tab: Client.TabConfig, into result: inout [TreeRow]) {
        var leaves: [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool, minimized: Bool)] = []

        for pane in tab.panes {
            addLeaf(
                leaf: pane.leaf,
                floating: false,
                minimized: minimizedPaneIds.contains(pane.leaf.id),
                out: &leaves
            )
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
                floating: leaf.floating,
                minimized: leaf.minimized
            ))
        }
    }

    private func addLeaf(
        leaf: Client.LeafNode,
        floating: Bool,
        minimized: Bool,
        out: inout [(paneId: String, sessionId: String, title: String, kind: LeafKind, floating: Bool, minimized: Bool)]
    ) {
        let kind: LeafKind
        if leaf.content is Client.GitContent {
            kind = .git
        } else if leaf.content is Client.FileBrowserContent {
            kind = .fileBrowser
        } else if leaf.content is Client.AgentContent {
            // Agent consoles route through the terminal screen bound to
            // their session id: the server mirrors both agent render modes
            // into the /pty byte stream (transcript mode with a cooked
            // input line, screen mode as the full grid via SwiftTerm).
            kind = .terminal
        } else if leaf.content is Client.TerminalContent || leaf.content == nil {
            kind = .terminal
        } else {
            kind = .empty
        }
        out.append((paneId: leaf.id, sessionId: leaf.sessionId, title: leaf.title, kind: kind, floating: floating, minimized: minimized))
    }
}
