import SwiftUI
import Client

/// Host list screen — add, edit, delete saved servers and connect.
/// Mirrors the Android `HostsScreen` composable.
///
/// The screen is also the QR pairing entry point: the toolbar scanner (and the
/// `lunamux://pair` deep link relayed through `PendingPairingUri`) parses a
/// `PairingPayload`, saves or updates the host entry, and connects immediately
/// — scan → connected, with no approval dialog.
///
/// Unlike the workspace screens (tree, terminal, …), this screen renders
/// before any server connection exists, so no server-driven theme can apply
/// yet. It therefore uses native system colors and standard iOS list styling
/// instead of the `Palette` workspace theme.
struct HostsView: View {
    @Bindable var viewModel: HostsViewModel
    var onConnected: () -> Void
    var onOpenNews: () -> Void

    /// Roomier (iPad) vs compact (iPhone) sizing.
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var editTarget: HostEntryLocal?
    @State private var showAddSheet = false
    @State private var showScanSheet = false
    @State private var deleteTarget: HostEntryLocal?

    /// Deep-linked pairing URIs parked by `LunamuxApp.onOpenURL`.
    private let pendingPairing = PendingPairingUri.shared

    var body: some View {
        baseView
            .modifier(HostsAlertsModifier(viewModel: viewModel, deleteTarget: $deleteTarget))
            .onChange(of: ConnectionHolder.shared.client != nil) { _, connected in
                if connected { onConnected() }
            }
            // Pairing deep link (system camera / browser → lunamux://pair).
            // `onAppear` catches a cold-launch link posted before this screen
            // existed; `onChange` catches one that arrives while it is up.
            // `consume()` clears the slot either way, so a re-render cannot
            // pair twice.
            .onAppear { consumePendingPairing() }
            .onChange(of: pendingPairing.uri) { _, uri in
                if uri != nil { consumePendingPairing() }
            }
    }

    /// Drain the pairing mailbox into the view model, if anything is waiting.
    private func consumePendingPairing() {
        if let uri = pendingPairing.consume() {
            viewModel.handlePairingUri(uri)
        }
    }

    /// Base list/empty-state view plus toolbar and sheets. Split out so the
    /// alerts (which SwiftUI's type checker struggles to resolve when chained
    /// on one body) live in a separate `ViewModifier`.
    @ViewBuilder
    private var baseView: some View {
        Group {
            if viewModel.hosts.isEmpty {
                emptyState
            } else {
                hostsList
            }
        }
        // Keep the list/empty state in a readable column on iPad instead of
        // stretching edge-to-edge, left-aligned so it lines up under the large
        // "Hosts" title rather than floating centred. A no-op on iPhone (< 700 pt).
        .frame(maxWidth: 700)
        .frame(maxWidth: .infinity, alignment: .leading)
        .safeAreaInset(edge: .bottom) {
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting: viewModel.connectingId == HostsViewModel.demoConnectingId,
                enabled: viewModel.connectingId == nil,
                onConnect: { viewModel.connectDemo() }
            )
        }
        .navigationTitle("Hosts")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NewsBellButton(action: onOpenNews)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showScanSheet = true } label: {
                    Label("Scan pairing code", systemImage: "qrcode.viewfinder")
                }
                .tint(Palette.headerAccent)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddSheet = true } label: {
                    Label("Add Host", systemImage: "plus")
                }
                .tint(Palette.headerAccent)
            }
            // Shared info menu → support forum, website, legal pages. Mirrored
            // in the Sessions toolbar so both primary screens expose the same
            // links from the same place.
            ToolbarItem(placement: .topBarTrailing) {
                AboutMenu()
            }
        }
        .sheet(isPresented: $showAddSheet) {
            HostEditSheet(initial: nil) { label, host, port in
                viewModel.addHost(label: label, host: host, port: port)
                showAddSheet = false
            }
        }
        .sheet(isPresented: $showScanSheet) {
            // Dismiss first, then pair: the connect drives this screen's
            // spinner and, on success, the push to the tree — none of which
            // is visible under a presented sheet.
            QRScannerSheet { uri in
                showScanSheet = false
                viewModel.handlePairingUri(uri)
            }
        }
        .sheet(item: $editTarget) { entry in
            HostEditSheet(initial: entry) { label, host, port in
                var updated = entry
                updated.label = label
                updated.host = host
                updated.port = port
                viewModel.updateHost(updated)
                editTarget = nil
            }
        }
    }

    /// Empty state. Pairing by QR is the primary path (scan → connected, no
    /// typing, no approval dialog); manual entry is demoted to a secondary
    /// action. Mirrors the Android `EmptyState` composable.
    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Hosts", systemImage: "qrcode.viewfinder")
        } description: {
            Text("On your Mac: Go to \"Lunamux > Settings > Server & Security… > Devices\" "
                 + "and tick \"Allow connections from other devices\" and then press "
                 + "\"Pair via QR Code\" - and then scan the code here.")
        } actions: {
            Button { showScanSheet = true } label: {
                Text("Scan pairing code")
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.headerAccent)

            Button { showAddSheet = true } label: {
                Text("Add manually")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }

    private var hostsList: some View {
        List {
            ForEach(viewModel.hosts) { entry in
                HostRow(
                    entry: entry,
                    connecting: viewModel.connectingId == entry.id,
                    waitingForApproval: viewModel.waitingForApproval,
                    enabled: viewModel.connectingId == nil,
                    onConnect: { viewModel.connect(entry: entry) },
                    onEdit: { editTarget = entry },
                    onDelete: { deleteTarget = entry }
                )
            }
        }
        .listStyle(.insetGrouped)
    }
}

// MARK: - Demo Footer

/// Discreet, bottom-pinned entry into the built-in demo. Tapping it connects
/// to the shared client's in-process demo simulation — instant, offline, and
/// stateless, so it carries no edit/delete affordances.
///
/// Rendered as a single muted row beneath a hairline divider, staying out of the
/// way of the user's own servers. External links (support forum, website, legal
/// pages) now live in the top bar's `AboutMenu` rather than here, keeping this
/// footer to the one demo affordance. Mirrors the Android `DemoFooter`
/// composable.
private struct DemoFooter: View {
    let connecting: Bool
    let enabled: Bool
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                // Live demo — the footer's sole affordance.
                Button(action: { if enabled { onConnect() } }) {
                    HStack(spacing: 6) {
                        if connecting {
                            ProgressView()
                                .scaleEffect(0.7)
                        } else {
                            Image(systemName: "play.circle")
                                .font(.system(size: 13))
                        }
                        Text("Try the live demo")
                            .font(.footnote)
                    }
                    .foregroundStyle(.secondary)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityLabel("Try the live demo, no server needed")

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 6)
        }
        // Opaque background that matches the list base (black in dark mode)
        // rather than the lighter `.bar` material. `.bar` filled the bottom
        // safe area with a visibly lighter band, reading as a big chunk of
        // padding beneath the text; a matching colour makes that region blend
        // into the screen. (A *transparent* background instead caused SwiftUI
        // to ghost the footer up under the status bar, so an opaque fill is
        // required.)
        .background(Color(.systemGroupedBackground))
    }
}

// MARK: - Host Row

private struct HostRow: View {
    let entry: HostEntryLocal
    let connecting: Bool
    let waitingForApproval: Bool
    let enabled: Bool
    let onConnect: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        Button(action: { if enabled { onConnect() } }) {
            HStack(spacing: hSize.scaled(12)) {
                TerminalGlyph()
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.label)
                        .font(hSize.pick(.headline, .title3))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    // verbatim: interpolating an Int32 through Text's
                    // LocalizedStringKey path runs it through a locale-aware
                    // number formatter, rendering ports like "8 443".
                    Text(verbatim: "\(entry.host):\(entry.port)")
                        .font(hSize.pick(.subheadline, .body))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if connecting {
                    if waitingForApproval {
                        Text("Waiting for approval…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    ProgressView()
                        .scaleEffect(0.8)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { onDelete() } label: {
                Label("Delete", systemImage: "trash")
            }
            Button { onEdit() } label: {
                Label("Edit", systemImage: "pencil")
            }
            .tint(.blue)
        }
        .contextMenu {
            Button { onEdit() } label: {
                Label("Edit", systemImage: "pencil")
            }
            Button(role: .destructive) { onDelete() } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

// MARK: - Terminal Glyph (matches Android's TerminalGlyph Canvas)

private struct TerminalGlyph: View {
    var body: some View {
        Canvas { context, size in
            let w = size.width
            let px = w / 16.0
            let stroke = StrokeStyle(lineWidth: 1.3 * px, lineCap: .round, lineJoin: .round)
            let tint = Color.secondary.opacity(0.7)

            // Rounded rect
            let rect = CGRect(x: 1*px, y: 2*px, width: 14*px, height: 12*px)
            context.stroke(RoundedRectangle(cornerRadius: 1.5*px).path(in: rect), with: .color(tint), style: stroke)

            // Chevron
            var chevron = Path()
            chevron.move(to: CGPoint(x: 4*px, y: 7*px))
            chevron.addLine(to: CGPoint(x: 6*px, y: 5*px))
            chevron.addLine(to: CGPoint(x: 4*px, y: 3*px))
            context.stroke(chevron, with: .color(tint), style: stroke)

            // Cursor line
            var line = Path()
            line.move(to: CGPoint(x: 7*px, y: 7*px))
            line.addLine(to: CGPoint(x: 11*px, y: 7*px))
            context.stroke(line, with: .color(tint), style: StrokeStyle(lineWidth: 1.2*px, lineCap: .round))
        }
        .frame(width: 16, height: 16)
        .accessibilityLabel("Terminal")
    }
}

// MARK: - Host Edit Sheet

private struct HostEditSheet: View {
    let initial: HostEntryLocal?
    let onSave: (String, String, Int32) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize
    @State private var label: String
    @State private var host: String
    @State private var portText: String
    @FocusState private var focusedField: Field?

    private enum Field { case label, host, port }

    init(initial: HostEntryLocal?, onSave: @escaping (String, String, Int32) -> Void) {
        self.initial = initial
        self.onSave = onSave
        _label = State(initialValue: initial?.label ?? "")
        _host = State(initialValue: initial?.host ?? "")
        // New hosts default to the port every Lunamux server listens on.
        _portText = State(initialValue: initial.map { String($0.port) }
            ?? String(ConstantsKt.SERVER_TLS_PORT))
    }

    private var canSave: Bool {
        !label.trimmingCharacters(in: .whitespaces).isEmpty
            && !host.trimmingCharacters(in: .whitespaces).isEmpty
            && Int32(portText) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $label)
                        .focused($focusedField, equals: .label)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .host }
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("A name for this server, shown in the host list.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                Section {
                    TextField("Host", text: $host)
                        .focused($focusedField, equals: .host)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .port }
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("Hostname or IP address of your Mac running Lunamux.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                Section {
                    TextField("Port", text: $portText)
                        .focused($focusedField, equals: .port)
                        .keyboardType(.numberPad)
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("Lunamux servers listen on port \(String(ConstantsKt.SERVER_TLS_PORT)) by default.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
            }
            // Theme the sheet to match the rest of the app: hide the system
            // grouped-list backdrop, paint the palette background behind it, and
            // tint the cursor / Cancel & Save actions with the theme accent.
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .tint(Palette.headerAccent)
            .navigationTitle(initial == nil ? "Add Host" : "Edit Host")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .tint(Palette.headerAccent)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let port = Int32(portText), canSave {
                            onSave(label.trimmingCharacters(in: .whitespaces),
                                   host.trimmingCharacters(in: .whitespaces),
                                   port)
                        }
                    }
                    .disabled(!canSave)
                    .tint(Palette.headerAccent)
                }
            }
            .onAppear {
                if initial == nil { focusedField = .label }
            }
        }
        // On iPad a `.medium` detent renders as a small floating card; present
        // the full form sheet there instead. iPhone keeps the half-sheet.
        .presentationDetents(hSize.pick([.medium, .large], [.large]))
    }
}

// MARK: - Alerts

/// All three alert modifiers (delete confirmation, generic connection error,
/// cert-changed re-pair prompt) extracted out of `HostsView.body`. SwiftUI's
/// type checker times out when this many `.alert` modifiers chain off the
/// same body, so they live here as a single `ViewModifier`.
private struct HostsAlertsModifier: ViewModifier {
    @Bindable var viewModel: HostsViewModel
    @Binding var deleteTarget: HostEntryLocal?

    func body(content: Content) -> some View {
        content
            .alert("Delete host?", isPresented: .init(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            )) {
                Button("Cancel", role: .cancel) { deleteTarget = nil }
                Button("Delete", role: .destructive) {
                    if let entry = deleteTarget {
                        viewModel.deleteHost(id: entry.id)
                        deleteTarget = nil
                    }
                }
            } message: {
                if let entry = deleteTarget {
                    Text("\"\(entry.label)\" will be removed from this device.")
                }
            }
            .alert("Connection failed", isPresented: .init(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { viewModel.errorMessage = nil }
            } message: {
                if let msg = viewModel.errorMessage {
                    Text(msg)
                }
            }
            .alert("Server certificate changed", isPresented: .init(
                get: { viewModel.pinMismatchEntry != nil },
                set: { if !$0 { viewModel.pinMismatchEntry = nil } }
            )) {
                // Mirrors Android's PinMismatchDialog (HostsScreen.kt:511).
                Button("Re-pair") {
                    if let e = viewModel.pinMismatchEntry { viewModel.repairPin(e) }
                }
                Button("Forget", role: .destructive) {
                    if let e = viewModel.pinMismatchEntry { viewModel.forgetHost(e) }
                }
                Button("Cancel", role: .cancel) { viewModel.pinMismatchEntry = nil }
            } message: {
                if let e = viewModel.pinMismatchEntry {
                    Text(pinMismatchMessage(for: e))
                }
            }
    }

    private func pinMismatchMessage(for entry: HostEntryLocal) -> String {
        "The server at \"\(entry.label)\" (\(entry.host):\(entry.port)) is presenting a different certificate than the one you paired with. "
            + "This could mean the server was reinstalled, or someone is trying to intercept your connection.\n\n"
            + "Re-pair if you trust the new certificate; Forget to remove the host."
    }
}
