import SwiftUI
import Client

/// Name of the stable canvas coordinate space edit-mode move/resize drags are
/// measured in (see the note on `ExposeCanvas`'s `.coordinateSpace`).
private let overviewCanvasSpace = "overviewCanvas"

/// Graphical "overview" mode for the Sessions screen (issues #44, #58): a
/// miniaturised, *interactive* replica of the web/Electron tabs-and-panes
/// experience, ported from the Android `OverviewContent` composable.
///
/// A swipeable strip of tab chips sits at the top; below it a paging canvas
/// shows the active tab's panes laid out by their toolkit-owned fractional
/// geometry, each hosting a live miniature (terminal / file-browser / git).
/// Window management (issue #58) is layered on the canvas:
///  - Tapping a pane focuses it and drills into that pane's full-screen route.
///  - Long-pressing a pane opens a **context menu** (Open / Maximize / Restore /
///    Minimize / Move or resize / Rename / Close).
///  - "Move or resize" enters a single **edit-layout mode** where every pane can
///    be freely dragged to move and resized by its bottom-right handle; a banner
///    offers Done.
///  - Minimized panes leave the canvas and appear in a bottom **dock** strip;
///    tapping a dock chip restores the pane.
///
/// All decisions (geometry maths, `LAYOUT_STATE` authoring, the edit/drag state)
/// live in the shared `OverviewBackingViewModel` (via `OverviewViewModel`) so
/// iOS and Android render the same model; this file is the SwiftUI front-end.
/// The view-model is **hoisted** from `TreeView` so the toolbar's New window /
/// Layout actions drive the same instance.
///
/// - SeeAlso: `OverviewViewModel`
/// - SeeAlso: `MiniTerminalRegistry`
struct OverviewView: View {
    /// Shared overview model, hoisted from `TreeView` so the toolbar shares it.
    @Bindable var viewModel: OverviewViewModel
    var onOpenTerminal: (String) -> Void
    var onOpenFileBrowser: (String) -> Void
    var onOpenGit: (String) -> Void

    /// One registry for the whole overview: it owns the terminal miniatures'
    /// sockets/emulators so they survive tab swipes. Created on appear once a
    /// live client is available, torn down on disappear.
    @State private var registry: MiniTerminalRegistry?
    /// The paged `TabView`'s selection, kept in sync with the server's active
    /// tab in both directions (chip tap / external change ⇄ swipe).
    @State private var selection: String = ""

    /// Rename / close targets raised from a pane's (or dock chip's) context
    /// menu. Drive the rename alert and the close confirmation dialog.
    @State private var renameTarget: PaneTarget?
    @State private var renameText: String = ""
    @State private var closeTarget: PaneTarget?

    /// Rename / close targets raised from a tab chip's context menu. Drive the
    /// tab rename alert and the tab close confirmation dialog.
    @State private var renameTabTarget: PaneTarget?
    @State private var renameTabText: String = ""
    @State private var closeTabTarget: PaneTarget?

    var body: some View {
        GeometryReader { rootGeo in
            Group {
                if !viewModel.isConnected {
                    placeholder("Disconnected")
                } else if viewModel.tabs.isEmpty {
                    placeholder("No tabs")
                } else {
                    VStack(spacing: 0) {
                        // Hide the tab strip while editing layout: the move/resize
                        // gestures own the screen, and the strip's chips would
                        // compete for taps.
                        if viewModel.editTabId == nil {
                            OverviewTabStrip(
                                tabs: viewModel.tabs,
                                unlistedTabs: viewModel.unlistedTabs,
                                activeTabId: viewModel.activeTabId,
                                closeEnabled: viewModel.tabs.count > 1,
                                onSelect: { viewModel.setActiveTab($0) },
                                onRename: { tab in
                                    renameTabText = tab.title
                                    renameTabTarget = PaneTarget(id: tab.id, title: tab.title)
                                },
                                onToggleHidden: { tab in
                                    viewModel.setTabHidden(tabId: tab.id, hidden: !tab.isHidden)
                                },
                                onToggleSidebarHidden: { tab in
                                    viewModel.setTabHiddenFromSidebar(
                                        tabId: tab.id, hidden: !tab.isHiddenFromSidebar
                                    )
                                },
                                onClose: { tab in
                                    closeTabTarget = PaneTarget(id: tab.id, title: tab.title)
                                }
                            )
                        } else {
                            EditBanner { viewModel.exitEdit() }
                        }
                        pager(bottomInset: rootGeo.safeAreaInsets.bottom)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.background)
        .onAppear {
            if registry == nil, let client = ConnectionHolder.shared.client {
                registry = MiniTerminalRegistry(client: client)
            }
            viewModel.start()
        }
        .onDisappear {
            viewModel.stop()
            registry?.close()
            registry = nil
        }
        .onChange(of: viewModel.activeTabId) { _, newValue in
            if let newValue, newValue != selection { selection = newValue }
        }
        .onChange(of: selection) { _, newValue in
            if !newValue.isEmpty, newValue != viewModel.activeTabId {
                viewModel.setActiveTab(newValue)
            }
        }
        .modifier(OverviewDialogs(
            renameTarget: $renameTarget,
            renameText: $renameText,
            closeTarget: $closeTarget,
            onClose: { viewModel.closePane(paneId: $0) },
            renameTabTarget: $renameTabTarget,
            renameTabText: $renameTabText,
            closeTabTarget: $closeTabTarget
        ))
    }

    /// The paging canvas. When a tab is in edit mode its page must not swipe
    /// (so horizontal drags move panes instead of flipping tabs); a `.page`
    /// TabView can't disable paging per-page, so edit mode swaps to a single
    /// non-paging canvas for the active tab.
    @ViewBuilder
    private func pager(bottomInset: CGFloat) -> some View {
        if viewModel.editTabId != nil,
           let tab = viewModel.tabs.first(where: { $0.id == viewModel.editTabId }) {
            canvas(for: tab, bottomInset: bottomInset)
        } else {
            TabView(selection: $selection) {
                ForEach(viewModel.tabs, id: \.id) { tab in
                    canvas(for: tab, bottomInset: bottomInset).tag(tab.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
    }

    /// The exposé canvas + dock for one tab, wired to this view's callbacks.
    private func canvas(for tab: Client.OverviewBackingViewModel.OverviewTab, bottomInset: CGFloat) -> some View {
        ExposeCanvas(
            tab: tab,
            editing: viewModel.editTabId == tab.id,
            drag: viewModel.drag?.tabId == tab.id ? viewModel.drag : nil,
            bottomInset: bottomInset,
            registry: registry,
            viewModel: viewModel,
            onOpen: { pane in open(pane, in: tab.id) },
            onRename: { leaf in beginRename(leaf) },
            onClose: { leaf in closeTarget = PaneTarget(id: leaf.id, title: leaf.title) }
        )
    }

    /// Focus the pane, then drill into its full-screen route.
    private func open(_ pane: Client.OverviewBackingViewModel.OverviewPane, in tabId: String) {
        viewModel.focusPane(tabId: tabId, paneId: pane.leaf.id)
        switch leafKind(pane.leaf) {
        case .fileBrowser: onOpenFileBrowser(pane.leaf.id)
        case .git: onOpenGit(pane.leaf.id)
        case .terminal, .empty: onOpenTerminal(pane.leaf.sessionId)
        }
    }

    private func beginRename(_ leaf: Client.LeafNode) {
        renameText = leaf.title
        renameTarget = PaneTarget(id: leaf.id, title: leaf.title)
    }

    /// A centered dim caption for the disconnected / empty states.
    private func placeholder(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(Palette.textSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// A pane targeted by the rename alert or close confirmation.
struct PaneTarget: Identifiable {
    let id: String
    let title: String
}

// MARK: - Dialogs

/// The overview's rename alert + close confirmation, extracted into a modifier
/// to keep `OverviewView.body` small enough for SwiftUI's type checker (the
/// same pattern as `TreeDialogsModifier`).
private struct OverviewDialogs: ViewModifier {
    @Binding var renameTarget: PaneTarget?
    @Binding var renameText: String
    @Binding var closeTarget: PaneTarget?
    let onClose: (String) -> Void
    @Binding var renameTabTarget: PaneTarget?
    @Binding var renameTabText: String
    @Binding var closeTabTarget: PaneTarget?

    func body(content: Content) -> some View {
        content
            .alert(
                "Rename Window",
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
            } message: { _ in
                Text("Leave empty to use the window's working directory as its name.")
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
                Button("Close Window", role: .destructive) {
                    onClose(target.id)
                    closeTarget = nil
                }
                Button("Cancel", role: .cancel) { closeTarget = nil }
            } message: { _ in
                Text("The window's session will be ended.")
            }
            .alert(
                "Rename Tab",
                isPresented: .init(
                    get: { renameTabTarget != nil },
                    set: { if !$0 { renameTabTarget = nil } }
                ),
                presenting: renameTabTarget
            ) { target in
                TextField("Name", text: $renameTabText)
                Button("Rename") {
                    commitTabRename(target)
                    renameTabTarget = nil
                }
                Button("Cancel", role: .cancel) { renameTabTarget = nil }
            }
            .confirmationDialog(
                closeTabTarget.map { "Close \u{201C}\($0.title)\u{201D}?" } ?? "",
                isPresented: .init(
                    get: { closeTabTarget != nil },
                    set: { if !$0 { closeTabTarget = nil } }
                ),
                titleVisibility: .visible,
                presenting: closeTabTarget
            ) { target in
                Button("Close Tab", role: .destructive) {
                    closeTab(target)
                    closeTabTarget = nil
                }
                Button("Cancel", role: .cancel) { closeTabTarget = nil }
            } message: { _ in
                Text("All windows in this tab will be closed and their sessions ended.")
            }
    }

    /// Commit the rename; a blank title clears the custom name so the pane falls
    /// back to its cwd-derived title.
    private func commitRename(_ target: PaneTarget) {
        let trimmed = renameText.trimmingCharacters(in: .whitespaces)
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.renamePane(
                socket: socket, paneId: target.id, title: trimmed
            )
        }
    }

    /// Commit a tab rename. A tab always carries a name, so a blank entry is
    /// ignored rather than clearing it (unlike a pane).
    private func commitTabRename(_ target: PaneTarget) {
        let trimmed = renameTabText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.renameTab(
                socket: socket, tabId: target.id, title: trimmed
            )
        }
    }

    /// Close the whole tab (and end all its windows' sessions).
    private func closeTab(_ target: PaneTarget) {
        guard let socket = ConnectionHolder.shared.windowSocket else { return }
        Task {
            try? await Client.PaneActionsKt.closeTab(socket: socket, tabId: target.id)
        }
    }
}

// MARK: - Tab strip

/// The top tab strip: a single horizontally-scrollable row of chips, the active
/// tab outlined in the accent colour (web parity) with its aggregate status dot
/// leading. Auto-scrolls to keep the active tab in view. Mirrors the Android
/// `OverviewTabStrip`.
private struct OverviewTabStrip: View {
    let tabs: [Client.OverviewBackingViewModel.OverviewTab]
    let unlistedTabs: [Client.OverviewBackingViewModel.UnlistedTab]
    let activeTabId: String?
    let closeEnabled: Bool
    let onSelect: (String) -> Void
    let onRename: (Client.OverviewBackingViewModel.OverviewTab) -> Void
    /// Flip the tab's hidden-from-tab-strip ("unlisted") flag.
    let onToggleHidden: (Client.OverviewBackingViewModel.OverviewTab) -> Void
    /// Flip the tab's hidden-from-sidebar flag (also hides it from the
    /// sessions list, which mirrors the sidebar).
    let onToggleSidebarHidden: (Client.OverviewBackingViewModel.OverviewTab) -> Void
    let onClose: (Client.OverviewBackingViewModel.OverviewTab) -> Void

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tabs, id: \.id) { tab in
                        OverviewTabChip(
                            title: tab.title,
                            isActive: tab.id == activeTabId,
                            aggregateState: tab.aggregateState
                        )
                        .id(tab.id)
                        .onTapGesture { onSelect(tab.id) }
                        .contextMenu {
                            Button {
                                onRename(tab)
                            } label: {
                                Label("Rename…", systemImage: "pencil")
                            }
                            // Listing toggles (issue #52 family): the strip flag
                            // and the sidebar flag are orthogonal, so each gets
                            // its own item labelled by its current state. The
                            // wording mirrors the web/Electron overflow menu.
                            Button {
                                onToggleHidden(tab)
                            } label: {
                                Label(
                                    tab.isHidden ? "Show in tab bar" : "Hide in tab bar",
                                    systemImage: tab.isHidden ? "eye" : "eye.slash"
                                )
                            }
                            Button {
                                onToggleSidebarHidden(tab)
                            } label: {
                                Label(
                                    tab.isHiddenFromSidebar
                                        ? "Show in side bar" : "Hide in side bar",
                                    systemImage: tab.isHiddenFromSidebar ? "eye" : "eye.slash"
                                )
                            }
                            if closeEnabled {
                                Button(role: .destructive) {
                                    onClose(tab)
                                } label: {
                                    Label("Close Tab", systemImage: "xmark")
                                }
                            }
                        }
                    }

                    // Trailing `⋮` menu of unlisted (hidden) tabs. Selecting one
                    // activates it — it then surfaces temporarily in the strip
                    // (see OverviewBackingViewModel.project). Mirrors the
                    // web/Mac far-right overflow menu. Shown only when some tabs
                    // are unlisted.
                    if !unlistedTabs.isEmpty {
                        Menu {
                            ForEach(unlistedTabs, id: \.id) { tab in
                                Button {
                                    onSelect(tab.id)
                                } label: {
                                    Label(
                                        tab.title.isEmpty ? "(untitled)" : tab.title,
                                        systemImage: "rectangle.stack"
                                    )
                                }
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .rotationEffect(.degrees(90))
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(Palette.textSecondary)
                                .frame(width: 32, height: 28)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("Unlisted tabs")
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
            .background(Palette.background)
            .onChange(of: activeTabId) { _, id in
                guard let id else { return }
                withAnimation(.easeInOut(duration: 0.2)) {
                    proxy.scrollTo(id, anchor: .center)
                }
            }
        }
    }
}

/// One pill in the tab strip: an aggregate status dot (when working/waiting) and
/// the tab title, accent-outlined and tinted while active.
private struct OverviewTabChip: View {
    let title: String
    let isActive: Bool
    let aggregateState: String?
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        HStack(spacing: hSize.scaled(4)) {
            if aggregateState != nil {
                StatusDot(state: aggregateState, box: hSize.scaled(12))
            }
            Text(title)
                .font(hSize.pick(.subheadline, .title3))
                .lineLimit(1)
                .foregroundStyle(isActive ? Palette.headerAccent : Palette.textSecondary)
        }
        .padding(.horizontal, hSize.scaled(12))
        .padding(.vertical, hSize.scaled(6))
        .background(
            Capsule().fill(isActive ? Palette.headerAccent.opacity(0.18) : Color.clear)
        )
        .overlay(
            Capsule().stroke(
                isActive ? Palette.headerAccent : Palette.textSecondary.opacity(0.4),
                lineWidth: isActive ? 2 : 1
            )
        )
        .contentShape(Capsule())
    }
}

// MARK: - Edit banner

/// The edit-layout banner shown above the canvas while a tab is being arranged.
/// Mirrors the Android `EditBanner`.
private struct EditBanner: View {
    let onDone: () -> Void

    var body: some View {
        HStack {
            Text("Drag to move \u{00B7} drag a corner to resize")
                .font(.caption)
                .foregroundStyle(Palette.headerAccent)
            Spacer()
            Button(action: onDone) {
                HStack(spacing: 2) {
                    Image(systemName: "checkmark")
                    Text("Done")
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(Palette.headerAccent)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .background(Palette.headerAccent.opacity(0.14))
    }
}

// MARK: - Exposé canvas

/// Lays out a tab's on-canvas panes by their fractional geometry mapped onto the
/// available content area, hosts each pane's interaction (tap / context menu, or
/// the edit-mode move/resize gestures), and shows the dock strip beneath when
/// the tab has minimized panes. Mirrors the Android `ExposeCanvas`.
private struct ExposeCanvas: View {
    let tab: Client.OverviewBackingViewModel.OverviewTab
    let editing: Bool
    /// The live drag iff it targets a pane in this tab, else `nil`.
    let drag: Client.OverviewBackingViewModel.Drag?
    let bottomInset: CGFloat
    let registry: MiniTerminalRegistry?
    let viewModel: OverviewViewModel
    let onOpen: (Client.OverviewBackingViewModel.OverviewPane) -> Void
    let onRename: (Client.LeafNode) -> Void
    let onClose: (Client.LeafNode) -> Void

    var body: some View {
        VStack(spacing: 0) {
            canvas
            if !tab.dock.isEmpty {
                DockStrip(
                    dock: tab.dock,
                    onRestore: { docked in viewModel.restore(tabId: tab.id, paneId: docked.leaf.id) },
                    onRename: onRename,
                    onClose: onClose
                )
            }
        }
    }

    @ViewBuilder
    private var canvas: some View {
        if tab.panes.isEmpty && tab.dock.isEmpty {
            Text("No windows in this tab")
                .font(.system(size: 13))
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if tab.panes.isEmpty {
            // Everything is docked — keep the dock visible with a hint above it.
            Text("All windows minimized")
                .font(.system(size: 13))
                .foregroundStyle(Palette.textSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            let margin: CGFloat = 8
            GeometryReader { geo in
                let canvasW = geo.size.width - margin * 2
                let canvasH = geo.size.height - margin * 2 - bottomInset
                ZStack(alignment: .topLeading) {
                    ForEach(tab.panes, id: \.leaf.id) { pane in
                        let dragging = drag?.paneId == pane.leaf.id
                        let box = boxFor(pane, dragging: dragging)
                        PaneCell(
                            pane: pane,
                            tabId: tab.id,
                            editing: editing,
                            dragging: dragging,
                            canvasW: canvasW,
                            canvasH: canvasH,
                            registry: registry,
                            viewModel: viewModel,
                            onOpen: { onOpen(pane) },
                            onRename: { onRename(pane.leaf) },
                            onClose: { onClose(pane.leaf) }
                        )
                        .frame(width: canvasW * box.w, height: canvasH * box.h)
                        .offset(x: margin + canvasW * box.x, y: margin + canvasH * box.y)
                        .zIndex(dragging ? 1 : 0)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
                // Measure edit-mode drags against this stable space, NOT each
                // pane's own (moving) frame. The dragged pane slides under the
                // finger via `.offset`, so a `.local` translation collapses back
                // toward zero as the view chases the touch — the two fight and
                // the pane jumps back and forth. A fixed canvas space reports the
                // true finger movement regardless of the pane's offset.
                .coordinateSpace(.named(overviewCanvasSpace))
            }
        }
    }

    /// The rectangle a pane occupies: the live drag box while dragging, full
    /// bleed while maximized, otherwise its stored geometry.
    private func boxFor(_ pane: Client.OverviewBackingViewModel.OverviewPane, dragging: Bool) -> Box {
        if dragging, let d = drag {
            return Box(x: d.box.x, y: d.box.y, w: d.box.width, h: d.box.height)
        }
        if pane.maximized { return Box(x: 0, y: 0, w: 1, h: 1) }
        return Box(x: pane.x, y: pane.y, w: pane.width, h: pane.height)
    }

    /// A lightweight geometry box in tab fractions.
    private struct Box { let x, y, w, h: Double }
}

// MARK: - Pane cell (visual + interaction)

/// One pane in the canvas: the visual `MiniPane` card plus its interaction
/// layer. In the idle state a tap focuses + drills in and a long-press opens the
/// native context menu; in edit mode the card is draggable (move) with a
/// bottom-right resize handle. Mirrors the Android `ExposeCanvas` per-pane Box.
private struct PaneCell: View {
    let pane: Client.OverviewBackingViewModel.OverviewPane
    let tabId: String
    let editing: Bool
    let dragging: Bool
    let canvasW: CGFloat
    let canvasH: CGFloat
    let registry: MiniTerminalRegistry?
    let viewModel: OverviewViewModel
    let onOpen: () -> Void
    let onRename: () -> Void
    let onClose: () -> Void

    /// Cumulative translation of the in-flight gesture, so each `onChanged`
    /// forwards an *incremental* delta to the backing VM (which adds it to the
    /// live box). Reset on gesture end. Only the touched pane runs its gesture,
    /// so per-cell state is sufficient.
    @State private var lastMove: CGSize = .zero
    @State private var lastResize: CGSize = .zero

    var body: some View {
        MiniPane(pane: pane, registry: registry, raised: dragging)
            .padding(3)
            .contentShape(Rectangle())
            .modifier(PaneInteraction(
                editing: editing,
                idle: { AnyView(idleLayer) },
                edit: { AnyView(editLayer) }
            ))
    }

    /// Idle: whole-card tap (focus + open) and a long-press context menu.
    private var idleLayer: some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture { onOpen() }
            .contextMenu { contextMenuItems }
    }

    /// Edit: a move drag over the whole card plus a corner resize handle.
    private var editLayer: some View {
        ZStack(alignment: .bottomTrailing) {
            Color.clear
                .contentShape(Rectangle())
                .gesture(moveGesture)
            ResizeHandle(onChanged: applyResize, onEnded: endDrag)
                .padding(5)
        }
    }

    @ViewBuilder
    private var contextMenuItems: some View {
        Button { onOpen() } label: { Label("Open", systemImage: "arrow.up.forward.app") }
        if pane.maximized {
            Button { viewModel.toggleMaximize(tabId: tabId, paneId: pane.leaf.id) } label: {
                Label("Restore", systemImage: "arrow.down.right.and.arrow.up.left")
            }
        } else {
            Button { viewModel.toggleMaximize(tabId: tabId, paneId: pane.leaf.id) } label: {
                Label("Maximize", systemImage: "arrow.up.left.and.arrow.down.right")
            }
        }
        Button { viewModel.minimize(tabId: tabId, paneId: pane.leaf.id) } label: {
            Label("Minimize", systemImage: "minus")
        }
        Button { viewModel.enterEdit(tabId: tabId) } label: {
            Label("Move or resize", systemImage: "arrow.up.and.down.and.arrow.left.and.right")
        }
        Divider()
        Button { onRename() } label: { Label("Rename\u{2026}", systemImage: "pencil") }
        Button(role: .destructive) { onClose() } label: { Label("Close", systemImage: "xmark") }
    }

    // MARK: Gestures

    private var moveGesture: some Gesture {
        DragGesture(minimumDistance: 3, coordinateSpace: .named(overviewCanvasSpace))
            .onChanged { value in
                if lastMove == .zero { viewModel.beginDrag(tabId: tabId, paneId: pane.leaf.id) }
                let d = CGSize(
                    width: value.translation.width - lastMove.width,
                    height: value.translation.height - lastMove.height
                )
                lastMove = value.translation
                viewModel.dragMoveBy(
                    dxFrac: Double(d.width / canvasW),
                    dyFrac: Double(d.height / canvasH)
                )
            }
            .onEnded { _ in endDrag() }
    }

    /// Forward an incremental resize delta from the corner handle's gesture.
    private func applyResize(_ translation: CGSize) {
        if lastResize == .zero { viewModel.beginDrag(tabId: tabId, paneId: pane.leaf.id) }
        let d = CGSize(
            width: translation.width - lastResize.width,
            height: translation.height - lastResize.height
        )
        lastResize = translation
        viewModel.dragResizeBy(
            dwFrac: Double(d.width / canvasW),
            dhFrac: Double(d.height / canvasH)
        )
    }

    private func endDrag() {
        lastMove = .zero
        lastResize = .zero
        viewModel.endDrag()
    }
}

/// Applies either the idle or the edit interaction overlay to a pane card,
/// rebuilt when the mode flips. Kept as a tiny modifier so `PaneCell.body`
/// stays trivial for the type checker.
private struct PaneInteraction: ViewModifier {
    let editing: Bool
    let idle: () -> AnyView
    let edit: () -> AnyView

    func body(content: Content) -> some View {
        content.overlay { editing ? edit() : idle() }
    }
}

/// The bottom-right resize handle shown on every pane in edit mode: an accent
/// square with a diagonal grip, sized generously for touch. Mirrors the Android
/// `ResizeHandle`. Reports cumulative gesture translation; `PaneCell` diffs it
/// into incremental deltas.
private struct ResizeHandle: View {
    let onChanged: (CGSize) -> Void
    let onEnded: () -> Void
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6)
                .fill(Palette.headerAccent)
            Image(systemName: "arrow.down.right")
                .font(.system(size: hSize.scaled(9), weight: .bold))
                .foregroundStyle(Palette.background)
        }
        .frame(width: hSize.scaled(26), height: hSize.scaled(26))
        .contentShape(Rectangle())
        .gesture(
            // Same stable canvas space as the move gesture: the handle rides on
            // the pane's bottom-right corner, which moves as the pane resizes,
            // so a `.local` translation would feed back and jitter.
            DragGesture(minimumDistance: 1, coordinateSpace: .named(overviewCanvasSpace))
                .onChanged { onChanged($0.translation) }
                .onEnded { _ in onEnded() }
        )
    }
}

// MARK: - Dock strip

/// The dock strip: a horizontally-scrollable row of chips for the tab's
/// minimized panes. Tapping a chip restores it; long-pressing opens a context
/// menu (so a parked pane can be renamed/closed without restoring). Mirrors the
/// Android `DockStrip`.
private struct DockStrip: View {
    let dock: [Client.OverviewBackingViewModel.DockedPane]
    let onRestore: (Client.OverviewBackingViewModel.DockedPane) -> Void
    let onRename: (Client.LeafNode) -> Void
    let onClose: (Client.LeafNode) -> Void
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(dock, id: \.leaf.id) { docked in
                    chip(docked)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
        .background(Palette.background)
    }

    private func chip(_ docked: Client.OverviewBackingViewModel.DockedPane) -> some View {
        HStack(spacing: hSize.scaled(4)) {
            StatusDot(state: docked.sessionState, box: hSize.scaled(12))
            PaneIcon(kind: leafKind(docked.leaf), floating: false, size: hSize.scaled(12))
            Text(docked.leaf.title)
                .font(.system(size: hSize.scaled(11)))
                .lineLimit(1)
                .foregroundStyle(Palette.textSecondary)
        }
        .padding(.horizontal, hSize.scaled(8))
        .padding(.vertical, hSize.scaled(4))
        .background(
            RoundedRectangle(cornerRadius: 6).fill(Palette.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Palette.textSecondary.opacity(0.3), lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onTapGesture { onRestore(docked) }
        .contextMenu {
            Button { onRestore(docked) } label: { Label("Restore", systemImage: "arrow.up.left.and.arrow.down.right") }
            Divider()
            Button { onRename(docked.leaf) } label: { Label("Rename\u{2026}", systemImage: "pencil") }
            Button(role: .destructive) { onClose(docked.leaf) } label: { Label("Close", systemImage: "xmark") }
        }
    }
}

// MARK: - Mini pane (visual)

/// A single miniature pane: a themed, rounded card with a tiny title bar and the
/// type-specific live miniature. Purely visual — tap / context menu / drag are
/// layered on by `PaneCell`. The focused (or dragged) pane gets the accent
/// outline, matching the web's focused-pane treatment. Mirrors the Android
/// `MiniPane`.
private struct MiniPane: View {
    let pane: Client.OverviewBackingViewModel.OverviewPane
    let registry: MiniTerminalRegistry?
    /// Whether to lift the card (used for the pane being dragged in edit mode).
    var raised: Bool = false
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        let highlighted = pane.isFocused || raised
        VStack(spacing: 0) {
            titleBar
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Palette.surface)
        .compositingGroup()
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6).stroke(
                highlighted ? Palette.headerAccent : Palette.textSecondary.opacity(0.35),
                lineWidth: highlighted ? 2 : 1
            )
        )
        .shadow(color: raised ? Color.black.opacity(0.35) : .clear, radius: raised ? 8 : 0, y: raised ? 3 : 0)
    }

    /// The compact title bar: status dot (when active), pane-type icon, title.
    ///
    /// The title colour is the brightest theme token regardless of focus —
    /// matching the web/Mac pane headers, which never dim inactive panes (only
    /// the card's accent outline marks focus). The strip carries an elevated
    /// `surfaceAlt` background and a hairline `border` divider so it reads as a
    /// distinct title bar above the pane content, and the title sits a little
    /// larger (web parity, 11 pt).
    private var titleBar: some View {
        HStack(spacing: hSize.scaled(4)) {
            if pane.sessionState != nil {
                StatusDot(state: pane.sessionState, box: hSize.scaled(12))
            }
            PaneIcon(kind: leafKind(pane.leaf), floating: false, size: hSize.scaled(13))
            Text(pane.leaf.title)
                .font(.system(size: hSize.scaled(11), weight: .medium))
                .lineLimit(1)
                .foregroundStyle(Palette.textBright)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, hSize.scaled(6))
        .padding(.vertical, hSize.scaled(3))
        .background(Palette.surfaceAlt)
        .overlay(alignment: .bottom) { Palette.border.frame(height: 1) }
    }

    /// The type-specific live miniature.
    @ViewBuilder
    private var content: some View {
        switch leafKind(pane.leaf) {
        case .fileBrowser:
            MiniFileBrowserPane(paneId: pane.leaf.id)
        case .git:
            MiniGitPane(paneId: pane.leaf.id)
        case .terminal, .empty:
            MiniTerminalPane(registry: registry, sessionId: pane.leaf.sessionId)
        }
    }
}

/// The pane's content type, dispatched on the leaf's content (terminal is the
/// default for `TerminalContent` and legacy null-content leaves). Shared by the
/// canvas panes, the dock chips, and the mini-pane title bar.
private func leafKind(_ leaf: Client.LeafNode) -> LeafKind {
    if leaf.content is Client.GitContent { return .git }
    if leaf.content is Client.FileBrowserContent { return .fileBrowser }
    return .terminal
}

// MARK: - Terminal miniature

/// Read-only terminal miniature: fills the pane with the most recent output
/// lines at a legible monospace size, anchored to the bottom and growing upward
/// (older lines clip off the top). A thin renderer — all socket/emulator
/// lifecycle lives in the overview-scoped `MiniTerminalRegistry`. Mirrors the
/// Android `MiniTerminalPane`.
private struct MiniTerminalPane: View {
    let registry: MiniTerminalRegistry?
    let sessionId: String
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        let theme = Palette.settings
        let bg = theme.map { Color(argb: $0.bg) } ?? Palette.background
        let fg = theme.map { Color(argb: $0.text) } ?? Palette.textPrimary
        let lines = registry?.box(for: sessionId).lines ?? []

        bg
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: hSize.scaled(9), design: .monospaced))
                            .foregroundStyle(fg)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, hSize.scaled(4))
                .padding(.vertical, hSize.scaled(2))
            }
            .clipped()
    }
}

// MARK: - File-browser miniature

/// Read-only file-browser miniature: a compact replica of the file browser's
/// first screen (the root directory listing). Mirrors the Android
/// `MiniFileBrowserPane`.
private struct MiniFileBrowserPane: View {
    let paneId: String
    @State private var model = MiniFileBrowserModel()
    @Environment(\.horizontalSizeClass) private var hSize

    /// Max rows the miniature lists before it clips to the pane bounds.
    private let maxRows = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.entries.isEmpty {
                Text("\u{2026}")
                    .font(.system(size: hSize.scaled(10)))
                    .foregroundStyle(Palette.textSecondary)
            } else {
                ForEach(Array(model.entries.prefix(maxRows)), id: \.relPath) { entry in
                    HStack(spacing: hSize.scaled(5)) {
                        Image(systemName: entry.isDir ? "folder.fill" : "doc.text")
                            .font(.system(size: hSize.scaled(9)))
                            .foregroundStyle(entry.isDir ? Palette.headerAccent : Palette.textSecondary)
                            .frame(width: hSize.scaled(12))
                        Text(entry.name)
                            .font(.system(size: hSize.scaled(10)))
                            .foregroundStyle(Palette.textPrimary)
                            .lineLimit(1)
                    }
                    .padding(.vertical, 1)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, hSize.scaled(6))
        .padding(.vertical, hSize.scaled(5))
        .background(Palette.background)
        .onAppear { model.start(paneId: paneId) }
        .onDisappear { model.stop() }
    }
}

// MARK: - Git miniature

/// Read-only git miniature: a compact replica of the git pane's first screen
/// (the changed-files list). Mirrors the Android `MiniGitPane`.
private struct MiniGitPane: View {
    let paneId: String
    @State private var model = MiniGitModel()
    @Environment(\.horizontalSizeClass) private var hSize

    /// Max rows (headers + files) before the miniature clips to the pane bounds.
    private let maxRows = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.entries.isEmpty {
                Text(model.isLoading ? "\u{2026}" : "No changes")
                    .font(.system(size: hSize.scaled(10)))
                    .foregroundStyle(Palette.textSecondary)
            } else {
                ForEach(miniGitRows(model.entries, budget: maxRows)) { row in
                    switch row {
                    case .header(let directory):
                        Text(directory.isEmpty ? "ROOT" : directory.uppercased())
                            .font(.system(size: hSize.scaled(8), weight: .semibold))
                            .tracking(0.5)
                            .foregroundStyle(Palette.textSecondary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 3)
                            .padding(.bottom, 1)
                    case .file(let entry):
                        HStack(spacing: hSize.scaled(5)) {
                            GitStatusBadge(status: entry.status, size: hSize.scaled(12))
                            Text(basename(entry.filePath))
                                .font(.system(size: hSize.scaled(10)))
                                .foregroundStyle(Palette.textPrimary)
                                .lineLimit(1)
                        }
                        .padding(.vertical, 1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(.horizontal, hSize.scaled(6))
        .padding(.vertical, hSize.scaled(5))
        .background(Palette.background)
        .onAppear { model.start(paneId: paneId) }
        .onDisappear { model.stop() }
    }

    private func basename(_ path: String) -> String {
        path.components(separatedBy: "/").last ?? path
    }
}

/// One row in the git miniature — a directory header or a changed file.
private enum MiniGitRow: Identifiable {
    case header(String)
    case file(Client.GitFileEntry)

    var id: String {
        switch self {
        case .header(let directory): return "h:\(directory)"
        case .file(let entry): return "f:\(entry.filePath)"
        }
    }
}

/// Flatten changed `entries` into directory-grouped rows (preserving the
/// server's order), capped at `budget` rows total. Mirrors the Android
/// `MiniGitPane`'s row-budget loop.
private func miniGitRows(_ entries: [Client.GitFileEntry], budget: Int) -> [MiniGitRow] {
    var order: [String] = []
    var grouped: [String: [Client.GitFileEntry]] = [:]
    for entry in entries {
        let dir = entry.directory
        if grouped[dir] == nil { order.append(dir) }
        grouped[dir, default: []].append(entry)
    }

    var rows: [MiniGitRow] = []
    var remaining = budget
    for dir in order {
        if remaining <= 0 { break }
        rows.append(.header(dir))
        remaining -= 1
        for entry in grouped[dir] ?? [] {
            if remaining <= 0 { break }
            rows.append(.file(entry))
            remaining -= 1
        }
    }
    return rows
}
