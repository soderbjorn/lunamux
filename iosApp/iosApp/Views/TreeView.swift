import SwiftUI
import Client

/// Which layout the Sessions screen is showing: the flat list of tabs/panes,
/// or the graphical miniature overview (issue #44). Mirrors the Android
/// `SessionsViewMode` enum toggled from the `TreeScreen` top bar.
enum SessionsViewMode {
    case list
    case overview
}

/// Target of an in-flight rename initiated from a row's context menu or
/// swipe action. Drives the rename alert's text field and commit call.
private struct RenameTarget: Identifiable {
    enum Kind { case tab, pane }
    let kind: Kind
    let id: String
    let currentTitle: String
}

/// Target of an in-flight close confirmation (pane or tab). Closing kills
/// live sessions, so both get a destructive confirmation dialog first.
private struct CloseTarget: Identifiable {
    enum Kind { case tab, pane }
    let kind: Kind
    let id: String
    let title: String
}

/// Displays the window layout tree — tabs and their panes with state dots —
/// under a large, collapsing "Sessions" title that mirrors the Hosts screen.
/// Tabs the user hid from the sidebar (`TabConfig.isHiddenFromSidebar`) are
/// excluded from the list by default (issue #52); a "Show hidden tabs" link
/// reveals them at the bottom under a "Hidden" headline, and "Hide hidden tabs"
/// re-hides them. The reveal state is held in memory by the view model (seeded
/// from `SessionsViewModeStore.showHiddenTabs`) so it survives navigation but
/// resets on app restart. Mirrors the Android `TreeScreen` composable.
struct TreeView: View {
    @Bindable var viewModel: TreeViewModel
    var onOpenTerminal: (String) -> Void
    var onOpenFileBrowser: (String) -> Void
    var onOpenGit: (String) -> Void
    var onDisconnect: () -> Void
    var onOpenNews: () -> Void

    /// Roomier (iPad) vs compact (iPhone) sizing for the list rows.
    @Environment(\.horizontalSizeClass) private var hSize

    /// The system dark-mode flag; picks the navigation bar's colour scheme from
    /// the active theme's surface luminance (issue #95).
    @Environment(\.colorScheme) private var colorScheme

    // Creation flow state
    @State private var showTabNameAlert = false
    @State private var tabName = ""

    // Rename / close flow state
    @State private var renameTarget: RenameTarget?
    @State private var renameText = ""
    @State private var closeTarget: CloseTarget?

    /// List vs. graphical overview, toggled from the toolbar (issue #44).
    ///
    /// Seeded from (and written back to) the process-wide shared
    /// `SessionsViewModeStore` so the choice survives navigating away from the
    /// Sessions screen and back — `@State` alone resets whenever this view is
    /// recreated — while still resetting to the default on the next app launch
    /// (issue #54). The store is memory-only (never persisted to disk).
    @State private var viewMode: SessionsViewMode =
        Client.SessionsViewModeStore.shared.overviewMode ? .overview : .list

    /// Shared overview model — hoisted here (not created inside `OverviewView`)
    /// so the overview content *and* the toolbar's "New window" / "Layout"
    /// actions drive the same instance (issue #58). Owns all the geometry logic
    /// (move/resize/maximize/minimize/layout) in the shared `client` module.
    @State private var overviewViewModel = OverviewViewModel()

    /// Whether the layout-preset sheet is presented (overview mode only).
    @State private var showLayoutSheet = false

    /// The appearance + theme picker model, hoisted so the toolbar button and
    /// the sheet share one instance across presentations.
    @State private var appearanceViewModel = AppearanceViewModel()

    /// Whether the appearance + theme picker sheet is presented.
    @State private var showAppearanceSheet = false

    /// The shared theme store; its `generation` is read below to repaint the
    /// content when an in-app theme/appearance change lands.
    @State private var themeStore = ThemeStore.shared

    var body: some View {
        screenContent
            .navigationTitle("Sessions")
            // Large, collapsing title — mirrors the Hosts screen so the
            // session list reads as a peer landing screen. It collapses to an
            // inline title as the pane list scrolls under the bar.
            .navigationBarTitleDisplayMode(.large)
            // The bar's content scheme must follow the *theme's* surface, not a
            // hard-coded `.dark` — on light themes a forced-dark bar renders its
            // title white-on-light (issue #95). NOTE: do *not* add
            // `.toolbarBackground(.visible, …)` here — forcing the bar
            // background visible suppresses the large title entirely (it
            // reserves the title's space but never draws the text). Verified on
            // the simulator: with `.visible` the "Sessions" headline is blank;
            // without it the large title renders and still collapses on scroll.
            .toolbarBackground(Palette.background, for: .navigationBar)
            .toolbarColorScheme(
                Palette.backgroundIsDark(systemIsDark: colorScheme == .dark) ? .dark : .light,
                for: .navigationBar
            )
            .toolbar { toolbarItems }
            .navigationBarBackButtonHidden(true)
            .onAppear { viewModel.subscribe() }
            .modifier(TreeDialogsModifier(
                viewModel: viewModel,
                showTabNameAlert: $showTabNameAlert,
                tabName: $tabName,
                renameTarget: $renameTarget,
                renameText: $renameText,
                closeTarget: $closeTarget
            ))
            .sheet(isPresented: $showLayoutSheet) {
                LayoutSheet(
                    paneCount: overviewViewModel.activePaneCount,
                    activePresetKey: overviewViewModel.activeTabId
                        .flatMap { overviewViewModel.activePresetKey(tabId: $0) },
                    onSelect: { key in
                        guard let tabId = overviewViewModel.activeTabId else { return }
                        overviewViewModel.applyLayout(tabId: tabId, presetKey: key)
                    }
                )
            }
            .sheet(isPresented: $showAppearanceSheet) {
                AppearanceSheet(viewModel: appearanceViewModel)
            }
    }

    /// Add a pane of `kindWire` to the overview's active tab, from the toolbar's
    /// "New window" menu. No-op until the first config arrives (no active tab).
    private func addOverviewPane(_ kindWire: String) {
        guard let tabId = overviewViewModel.activeTabId else { return }
        overviewViewModel.addPane(tabId: tabId, kindWire: kindWire)
    }

    /// Navigation bar items: disconnect on the left, "new tab" on the right.
    /// Panes are created from each tab's own menu (tap the tab header), so
    /// the toolbar's "+" only creates tabs.
    @ToolbarContentBuilder
    private var toolbarItems: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button {
                viewModel.disconnect()
                onDisconnect()
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.backward")
                    Text("Hosts")
                }
                // Same tint as every other bar action — mixed per-icon tints
                // read as a bug on light themes (issue #96).
                .foregroundStyle(Palette.textPrimary)
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            // Combined "+" menu: always offers "New Tab"; in overview mode it
            // also offers adding a pane to the current tab (issue #58).
            // Replaces the former separate "New window" button so the toolbar
            // carries a single add affordance. Mirrors the Android toolbar.
            // Placed first so it sits leftmost in the trailing action cluster.
            Menu {
                Button {
                    tabName = ""
                    showTabNameAlert = true
                } label: {
                    Label("New Tab", systemImage: "plus.rectangle.on.rectangle.angled")
                }
                if viewMode == .overview {
                    Button {
                        addOverviewPane("terminal")
                    } label: {
                        Label("New Terminal", systemImage: "apple.terminal")
                    }
                    Button {
                        addOverviewPane("fileBrowser")
                    } label: {
                        Label("New File Browser", systemImage: "folder")
                    }
                    Button {
                        addOverviewPane("git")
                    } label: {
                        Label("New Git", systemImage: "arrow.triangle.branch")
                    }
                }
            } label: {
                PlusIcon()
                    .foregroundStyle(Palette.textPrimary)
            }
            .accessibilityLabel("Add")
        }
        // Overview-only layout preset picker (issue #58). Hidden in list mode
        // where it has no spatial meaning. Adding panes is folded into the
        // combined "+" menu above. Mirrors the Android `TreeScreen` toolbar.
        if viewMode == .overview {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showLayoutSheet = true
                } label: {
                    LayoutGridIcon()
                        .foregroundStyle(Palette.textPrimary)
                }
                .accessibilityLabel("Layout")
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            // List ⇄ overview toggle (issue #44). The icon shows the layout the
            // tap switches *to* — a grid while listing, a list while in the
            // overview. The glyph swap alone signals the mode; no accent tint
            // on the active state, so the bar keeps a single icon colour
            // (issue #96). Mirrors the Android `TreeScreen` view-mode
            // IconButton.
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    viewMode = (viewMode == .list) ? .overview : .list
                }
                // Persist the new mode in memory so it survives leaving and
                // returning to this screen (issue #54).
                Client.SessionsViewModeStore.shared.overviewMode = (viewMode == .overview)
            } label: {
                Image(systemName: viewMode == .list ? "square.grid.2x2" : "list.bullet")
                    .foregroundStyle(Palette.textPrimary)
            }
            .accessibilityLabel(viewMode == .list ? "Switch to overview" : "Switch to list")
        }
        ToolbarItem(placement: .topBarTrailing) {
            // Appearance + theme picker (parity with the Mac/Electron app's
            // appearance toggle + theme manager). Available in both list and
            // overview modes; writes the same canonical server selection the
            // desktop does, so the choice syncs across every client. Mirrors the
            // Android `TreeScreen` appearance button.
            Button {
                showAppearanceSheet = true
            } label: {
                Image(systemName: "paintpalette")
                    .foregroundStyle(Palette.textPrimary)
            }
            .accessibilityLabel("Appearance & theme")
        }
        ToolbarItem(placement: .topBarTrailing) {
            NewsBellButton(action: onOpenNews)
        }
    }

    /// The body's content area: the graphical overview when the toolbar toggle
    /// selects it (issue #44), otherwise the flat session list. Both render
    /// under the same "Sessions" navigation bar, so toggling swaps only the
    /// content below the title. The overview is handed the same drill-in
    /// callbacks as the list rows, so tapping a pane miniature opens that pane's
    /// existing full-screen route.
    @ViewBuilder
    private var screenContent: some View {
        Group {
            switch viewMode {
            case .overview:
                OverviewView(
                    viewModel: overviewViewModel,
                    onOpenTerminal: onOpenTerminal,
                    onOpenFileBrowser: onOpenFileBrowser,
                    onOpenGit: onOpenGit
                )
            case .list:
                content
            }
        }
        // Rebuild the content subtree when an in-app theme/appearance change
        // lands so every `Palette` read re-resolves against the new palette —
        // the system trait does not change, so the dynamic colours would
        // otherwise stay frozen. The navigation chrome and presented sheets sit
        // above this Group, so they are unaffected.
        .id(themeStore.generation)
    }

    /// The tree list (or the connecting placeholder) on the themed background.
    /// Split out of `body` — together with the row builders below — to keep
    /// each expression small enough for SwiftUI's type checker.
    private var content: some View {
        // The List is *always* the top-level content so the scroll view keeps a
        // stable identity from the very first frame. The window config streams
        // in asynchronously (rows start empty), and an earlier version swapped
        // between a non-scrollable "Connecting…" placeholder and this List —
        // that structural swap reset the navigation bar and made the large
        // "Sessions" title flash in and immediately collapse. Showing the
        // placeholder as an overlay instead keeps the title attached to one
        // unchanging scroll view, so it stays expanded until the user scrolls.
        List {
            ForEach(viewModel.rows) { row in
                rowView(row)
            }
        }
        .listStyle(.plain)
        // Taller minimum rows give the iPad's bigger pointer/touch targets room.
        .environment(\.defaultMinListRowHeight, hSize.pick(44, 56))
        .scrollContentBackground(.hidden)
        // Only bleed the themed background into the *bottom* safe area. Letting
        // it ignore the top safe area too painted opaque black over the large
        // title's region (which sits at the top of the scroll content), hiding
        // the "Sessions" headline entirely. The top is covered by the themed
        // toolbar background instead, so the bar and list still read as one.
        .background { Palette.background.ignoresSafeArea(edges: .bottom) }
        .overlay {
            if viewModel.rows.isEmpty {
                Text("Connecting\u{2026}")
                    .foregroundStyle(Palette.textSecondary)
            }
        }
    }

    /// Renders a single tree row (tab header or leaf pane).
    @ViewBuilder
    private func rowView(_ row: TreeRow) -> some View {
        switch row {
        case .sectionHeader(let title):
            sectionHeaderView(title: title)
        case .hiddenToggle(let showing):
            hiddenToggleView(showing: showing)
        case .tabHeader(let tabId, let title, let aggState):
            tabHeaderView(tabId: tabId, title: title, aggState: aggState)
        case .leaf(let paneId, let sessionId, let title, let kind, let floating, let minimized):
            leafView(paneId: paneId, sessionId: sessionId, title: title, kind: kind, floating: floating, minimized: minimized)
        }
    }

    /// A standalone, non-interactive group label (currently only "Hidden")
    /// separating the sidebar-hidden tabs from the visible ones above. Carries
    /// the same themed list-row chrome as the other rows.
    private func sectionHeaderView(title: String) -> some View {
        SectionHeaderRow(title: title)
            .listRowBackground(Palette.background)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
    }

    /// The tappable "Show hidden tabs" / "Hide hidden tabs" link (issue #52).
    /// Tapping it flips the in-memory reveal state on the view model, which
    /// rebuilds the rows. Carries the same themed list-row chrome as the
    /// section header.
    private func hiddenToggleView(showing: Bool) -> some View {
        HiddenToggleRow(showing: showing) {
            viewModel.toggleShowHiddenTabs()
        }
        .listRowBackground(Palette.background)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
    }

    /// A tab header row. Long-pressing it opens the tab's menu (rename, create
    /// panes inside it, close) — the tab is the creation point for panes,
    /// so this works for empty tabs too. New panes are placed by the server
    /// exactly like the web client's "+" button. Uses `.contextMenu` to match
    /// the pane rows below; the previous `Menu` blanked the label while open.
    private func tabHeaderView(tabId: String, title: String, aggState: String?) -> some View {
        TabHeaderRow(title: title, aggregateState: aggState)
            .contentShape(Rectangle())
            .accessibilityLabel("Tab \(title), opens tab menu")
            .accessibilityAddTraits(.isButton)
            .listRowBackground(Palette.background)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 0, trailing: 8))
            .contextMenu { tabMenu(tabId: tabId, title: title) }
    }

    /// A leaf pane row with tap-to-open, context menu, and swipe actions.
    private func leafView(
        paneId: String,
        sessionId: String,
        title: String,
        kind: LeafKind,
        floating: Bool,
        minimized: Bool
    ) -> some View {
        Button {
            switch kind {
            case .terminal: onOpenTerminal(sessionId)
            case .fileBrowser: onOpenFileBrowser(paneId)
            case .git: onOpenGit(paneId)
            case .empty: break // undecided pane — no action
            }
        } label: {
            LeafRow(
                title: title,
                kind: kind,
                state: viewModel.states[sessionId],
                floating: floating
            )
            // Dim minimized (docked-on-web) panes so the row reads as
            // "parked"; it stays fully tappable. Mirrors the web sidebar.
            .opacity(minimized ? 0.45 : 1)
        }
        .buttonStyle(.plain)
        .listRowBackground(Palette.background)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
        .contextMenu { paneMenu(paneId: paneId, title: title) }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                closeTarget = CloseTarget(kind: .pane, id: paneId, title: title)
            } label: {
                Label("Close", systemImage: "xmark")
            }
            Button {
                beginRename(RenameTarget(kind: .pane, id: paneId, currentTitle: title))
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(.blue)
        }
    }

    /// Context menu for a tab header: rename, listing toggles, add panes to
    /// this tab, close.
    @ViewBuilder
    private func tabMenu(tabId: String, title: String) -> some View {
        Button {
            beginRename(RenameTarget(kind: .tab, id: tabId, currentTitle: title))
        } label: {
            Label("Rename…", systemImage: "pencil")
        }
        // Listing toggles: the tab-strip flag and the sidebar flag are
        // orthogonal, so each gets its own item labelled by its current state.
        // The wording mirrors the web/Electron overflow menu and the overview
        // tab chip's context menu.
        Button {
            viewModel.setTabHidden(
                tabId: tabId, hidden: !viewModel.isTabHidden(tabId: tabId)
            )
        } label: {
            Label(
                viewModel.isTabHidden(tabId: tabId)
                    ? "Show in tab bar" : "Hide in tab bar",
                systemImage: viewModel.isTabHidden(tabId: tabId) ? "eye" : "eye.slash"
            )
        }
        Button {
            viewModel.setTabHiddenFromSidebar(
                tabId: tabId, hidden: !viewModel.isTabHiddenFromSidebar(tabId: tabId)
            )
        } label: {
            Label(
                viewModel.isTabHiddenFromSidebar(tabId: tabId)
                    ? "Show in side bar" : "Hide in side bar",
                systemImage: viewModel.isTabHiddenFromSidebar(tabId: tabId) ? "eye" : "eye.slash"
            )
        }
        Divider()
        Button {
            viewModel.addPaneToTab(tabId: tabId, kindWire: "terminal")
        } label: {
            Label("New Terminal", systemImage: "apple.terminal")
        }
        Button {
            viewModel.addPaneToTab(tabId: tabId, kindWire: "fileBrowser")
        } label: {
            Label("New File Browser", systemImage: "folder")
        }
        Button {
            viewModel.addPaneToTab(tabId: tabId, kindWire: "git")
        } label: {
            Label("New Git", systemImage: "arrow.triangle.branch")
        }
        Divider()
        Button(role: .destructive) {
            closeTarget = CloseTarget(kind: .tab, id: tabId, title: title)
        } label: {
            Label("Close Tab…", systemImage: "xmark")
        }
        .disabled(viewModel.tabCount <= 1)
    }

    /// Context menu for a pane row: rename and close. Pane creation lives on
    /// the tab's own menu instead, so empty tabs can be filled too.
    @ViewBuilder
    private func paneMenu(paneId: String, title: String) -> some View {
        Button {
            beginRename(RenameTarget(kind: .pane, id: paneId, currentTitle: title))
        } label: {
            Label("Rename…", systemImage: "pencil")
        }
        Button(role: .destructive) {
            closeTarget = CloseTarget(kind: .pane, id: paneId, title: title)
        } label: {
            Label("Close…", systemImage: "xmark")
        }
    }

    /// Pre-fills the rename alert's text field and presents it.
    private func beginRename(_ target: RenameTarget) {
        renameText = target.currentTitle
        renameTarget = target
    }
}

// MARK: - Dialogs Modifier

/// The tree screen's three presentation flows (new-tab alert, rename alert,
/// close confirmation) extracted out of `TreeView.body`. SwiftUI's type
/// checker times out when this many presentation modifiers chain off the
/// same body (see `HostsAlertsModifier` for the same pattern).
private struct TreeDialogsModifier: ViewModifier {
    @Bindable var viewModel: TreeViewModel
    @Binding var showTabNameAlert: Bool
    @Binding var tabName: String
    @Binding var renameTarget: RenameTarget?
    @Binding var renameText: String
    @Binding var closeTarget: CloseTarget?

    func body(content: Content) -> some View {
        content
            .alert("New Tab", isPresented: $showTabNameAlert) {
                TextField("Tab name", text: $tabName)
                Button("Create") {
                    guard !tabName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                    viewModel.addTab(name: tabName)
                }
                Button("Cancel", role: .cancel) {}
            }
            .alert(
                renameTarget?.kind == .tab ? "Rename Tab" : "Rename Window",
                isPresented: .init(
                    get: { renameTarget != nil },
                    set: { if !$0 { renameTarget = nil } }
                ),
                presenting: renameTarget
            ) { target in
                TextField("Name", text: $renameText)
                Button("Rename") {
                    commitRename(target)
                    renameTarget = nil
                }
                Button("Cancel", role: .cancel) { renameTarget = nil }
            } message: { target in
                if target.kind == .pane {
                    Text("Leave empty to use the window's working directory as its name.")
                }
            }
            .confirmationDialog(
                closeTarget.map { "Close \u{201C}\($0.title)\u{201D}?" } ?? "",
                isPresented: .init(
                    get: { closeTarget != nil },
                    set: { if !$0 { closeTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: closeTarget
            ) { target in
                Button(target.kind == .tab ? "Close Tab" : "Close Window", role: .destructive) {
                    switch target.kind {
                    case .tab: viewModel.closeTab(tabId: target.id)
                    case .pane: viewModel.closePane(paneId: target.id)
                    }
                    closeTarget = nil
                }
                Button("Cancel", role: .cancel) { closeTarget = nil }
            } message: { target in
                Text(target.kind == .tab
                     ? "All windows in this tab will be closed and their sessions ended."
                     : "The window's session will be ended.")
            }
    }

    /// Commits the rename alert. Tab titles must be non-blank (the server
    /// rejects empty ones); a blank pane title clears the custom name so the
    /// pane falls back to its cwd-derived title.
    private func commitRename(_ target: RenameTarget) {
        let trimmed = renameText.trimmingCharacters(in: .whitespaces)
        switch target.kind {
        case .tab:
            guard !trimmed.isEmpty else { return }
            viewModel.renameTab(tabId: target.id, title: trimmed)
        case .pane:
            viewModel.renamePane(paneId: target.id, title: trimmed)
        }
    }
}

// MARK: - Section Header Row

/// A dim uppercase caption preceded by a hairline rule, used to label the
/// "Hidden" group of sidebar-hidden tabs. Purely decorative — it has no tap
/// target. Mirrors the Android `SectionHeaderRow` composable.
private struct SectionHeaderRow: View {
    let title: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider()
                .overlay(Palette.textSecondary.opacity(0.18))
            Text(title.uppercased())
                .font(.caption2)
                .fontWeight(.semibold)
                .foregroundStyle(Palette.textSecondary.opacity(0.7))
                .tracking(1)
                .padding(.top, 16)
                .padding(.bottom, 4)
        }
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Hidden Toggle Row

/// A tappable accent-coloured link that reveals or re-hides the sidebar-hidden
/// tabs (issue #52), preceded by a hairline rule. Reads "Show hidden tabs" when
/// the hidden tabs are omitted and "Hide hidden tabs" when they are revealed.
/// Styled as an action (accent colour) to distinguish it from the dim,
/// non-interactive `SectionHeaderRow`. Mirrors the Android `HiddenToggleRow`.
private struct HiddenToggleRow: View {
    let showing: Bool
    let action: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Divider()
                .overlay(Palette.textSecondary.opacity(0.18))
            Button(action: action) {
                Text(showing ? "Hide hidden tabs" : "Show hidden tabs")
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.headerAccent)
                    .tracking(0.5)
                    .padding(.top, 16)
                    .padding(.bottom, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityLabel(showing ? "Hide hidden tabs" : "Show hidden tabs")
    }
}

// MARK: - Tab Header Row

private struct TabHeaderRow: View {
    let title: String
    let aggregateState: String?
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        HStack {
            // Dim section header — pane rows below carry the bright primary
            // color, matching the web sidebar's emphasis hierarchy.
            Text(title.uppercased())
                .font(hSize.pick(.caption, .subheadline))
                .fontWeight(.semibold)
                .foregroundStyle(Palette.textSecondary)
                .tracking(0.5)
            // Tab-aggregated status indicator (issue #38), painted in the theme
            // foreground colour: idle = solid dot, working = breathing dot,
            // waiting = pulsing warning triangle.
            StatusDot(state: aggregateState, box: hSize.scaled(16))
            Spacer()
        }
        .padding(.horizontal, hSize.scaled(4))
        .padding(.vertical, hSize.scaled(6))
    }
}

// MARK: - Leaf Row

private struct LeafRow: View {
    let title: String
    let kind: LeafKind
    let state: String?
    let floating: Bool
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        HStack(spacing: hSize.scaled(6)) {
            // Leading: pane-type icon. The status dot and the pane-type icon
            // swapped places (issue #43), so each row reads
            // [pane icon] [title] [status] [chevron].
            PaneIcon(kind: kind, floating: floating, size: hSize.scaled(16))
            Text(title)
                .font(hSize.pick(.body, .title3))
                .foregroundStyle(Palette.textPrimary)
                .lineLimit(1)
            Spacer()
            // Trailing: per-row status dot — working = breathing dot, waiting =
            // pulsing warning triangle; idle renders nothing (issue #43). The
            // navigation chevron follows it.
            StatusDot(state: state, box: hSize.scaled(16))
            Image(systemName: "chevron.right")
                .font(hSize.pick(.caption2, .footnote))
                .foregroundStyle(Palette.textSecondary.opacity(0.5))
        }
        .padding(.leading, hSize.scaled(16))
        .padding(.trailing, hSize.scaled(8))
        .padding(.vertical, hSize.scaled(8))
        .accessibilityElement(children: .combine)
        .accessibilityHint(
            kind == .terminal ? "Opens terminal session" :
            kind == .fileBrowser ? "Opens file browser" :
            kind == .git ? "Opens git changes" :
            "Undecided window"
        )
    }
}

// MARK: - Pane Icon (matches web SVGs: terminal, fileBrowser, empty, floating)

/// The web pane-type SVG icon (terminal / file browser / git / empty), drawn
/// with `Canvas`. Shared between the `TreeView` leaf rows and the overview's
/// `MiniPane` title bar (issue #44), which is why it is not `private` and
/// accepts a `size`: the Canvas scales every path off its own footprint, so a
/// 12-pt frame renders the same glyph shrunk for a thumbnail title bar.
struct PaneIcon: View {
    let kind: LeafKind
    let floating: Bool
    /// Square footprint in points; the Canvas scales all paths off this so the
    /// same artwork serves both the full-size row (16) and the mini title bar (12).
    var size: CGFloat = 16

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let px = w / 16.0
            let alpha = floating ? 0.9 : 0.6
            let tint = Palette.textSecondary.opacity(alpha)
            let stroke = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)

            switch kind {
            case .git:
                // Git branch icon
                let s = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)
                // Main vertical line
                var trunk = Path()
                trunk.move(to: CGPoint(x: 8*px, y: 2*px))
                trunk.addLine(to: CGPoint(x: 8*px, y: 14*px))
                context.stroke(trunk, with: .color(tint), style: s)
                // Branch line
                var branch = Path()
                branch.move(to: CGPoint(x: 8*px, y: 6*px))
                branch.addLine(to: CGPoint(x: 12*px, y: 4*px))
                context.stroke(branch, with: .color(tint), style: s)
                // Dots
                let dotSize = 2.5 * px
                context.fill(Circle().path(in: CGRect(x: 8*px - dotSize, y: 2*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))
                context.fill(Circle().path(in: CGRect(x: 12*px - dotSize, y: 4*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))
                context.fill(Circle().path(in: CGRect(x: 8*px - dotSize, y: 14*px - dotSize, width: dotSize*2, height: dotSize*2)), with: .color(tint))

            case .fileBrowser:
                // Document with folded corner and two text lines
                var doc = Path()
                doc.move(to: CGPoint(x: 3*px, y: 1.75*px))
                doc.addLine(to: CGPoint(x: 9.5*px, y: 1.75*px))
                doc.addLine(to: CGPoint(x: 13*px, y: 5.25*px))
                doc.addLine(to: CGPoint(x: 13*px, y: 14.25*px))
                doc.addCurve(to: CGPoint(x: 12.5*px, y: 14.75*px),
                             control1: CGPoint(x: 13*px, y: 14.53*px),
                             control2: CGPoint(x: 12.78*px, y: 14.75*px))
                doc.addLine(to: CGPoint(x: 3*px, y: 14.75*px))
                doc.addCurve(to: CGPoint(x: 2.5*px, y: 14.25*px),
                             control1: CGPoint(x: 2.72*px, y: 14.75*px),
                             control2: CGPoint(x: 2.5*px, y: 14.53*px))
                doc.addLine(to: CGPoint(x: 2.5*px, y: 2.25*px))
                doc.addCurve(to: CGPoint(x: 3*px, y: 1.75*px),
                             control1: CGPoint(x: 2.5*px, y: 1.97*px),
                             control2: CGPoint(x: 2.72*px, y: 1.75*px))
                doc.closeSubpath()
                context.stroke(doc, with: .color(tint), style: stroke)
                // Fold
                var fold = Path()
                fold.move(to: CGPoint(x: 9.25*px, y: 1.75*px))
                fold.addLine(to: CGPoint(x: 9.25*px, y: 5.25*px))
                fold.addLine(to: CGPoint(x: 13*px, y: 5.25*px))
                context.stroke(fold, with: .color(tint), style: stroke)
                // Text lines
                var line1 = Path()
                line1.move(to: CGPoint(x: 5*px, y: 8.5*px))
                line1.addLine(to: CGPoint(x: 10.5*px, y: 8.5*px))
                context.stroke(line1, with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                var line2 = Path()
                line2.move(to: CGPoint(x: 5*px, y: 11*px))
                line2.addLine(to: CGPoint(x: 10.5*px, y: 11*px))
                context.stroke(line2, with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))

            case .empty:
                // Dashed rounded rect placeholder
                let rect = CGRect(x: 2*px, y: 2*px, width: 12*px, height: 12*px)
                context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                               with: .color(tint),
                               style: StrokeStyle(lineWidth: 1.3*px, dash: [2.5*px, 2*px]))

            case .terminal:
                if floating {
                    let rect = CGRect(x: 2*px, y: 1*px, width: 12*px, height: 10*px)
                    context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                                   with: .color(tint), style: stroke)
                    var line = Path()
                    line.move(to: CGPoint(x: 4*px, y: 8*px))
                    line.addLine(to: CGPoint(x: 10*px, y: 8*px))
                    context.stroke(line, with: .color(tint),
                                   style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                    let taskbar = CGRect(x: 4*px, y: 13*px, width: 8*px, height: 1.2*px)
                    context.fill(RoundedRectangle(cornerRadius: 0.6*px).path(in: taskbar),
                                 with: .color(tint.opacity(0.5)))
                } else {
                    let rect = CGRect(x: 1*px, y: 2*px, width: 14*px, height: 12*px)
                    context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect),
                                   with: .color(tint), style: stroke)
                    var chevron = Path()
                    chevron.move(to: CGPoint(x: 4*px, y: 7*px))
                    chevron.addLine(to: CGPoint(x: 6*px, y: 5*px))
                    chevron.addLine(to: CGPoint(x: 4*px, y: 3*px))
                    context.stroke(chevron, with: .color(tint), style: stroke)
                    var line = Path()
                    line.move(to: CGPoint(x: 7*px, y: 7*px))
                    line.addLine(to: CGPoint(x: 11*px, y: 7*px))
                    context.stroke(line, with: .color(tint),
                                   style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
                }
            }
        }
        .frame(width: size, height: size)
        .accessibilityLabel(
            kind == .fileBrowser ? "File browser window" :
            kind == .git ? "Git window" :
            kind == .empty ? "Undecided window" :
            floating ? "Floating window" : "Window"
        )
    }
}
