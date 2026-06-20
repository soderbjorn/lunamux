import SwiftUI
import Client

/// Host list screen — add, edit, delete saved servers and connect.
/// Mirrors the Android `HostsScreen` composable.
///
/// Unlike the workspace screens (tree, terminal, …), this screen renders
/// before any server connection exists, so no server-driven theme can apply
/// yet. It therefore uses native system colors and standard iOS list styling
/// instead of the `Palette` workspace theme.
struct HostsView: View {
    @Bindable var viewModel: HostsViewModel
    var onConnected: () -> Void
    var onOpenNews: () -> Void

    @State private var editTarget: HostEntryLocal?
    @State private var showAddSheet = false
    @State private var deleteTarget: HostEntryLocal?

    var body: some View {
        baseView
            .modifier(HostsAlertsModifier(viewModel: viewModel, deleteTarget: $deleteTarget))
            .onChange(of: ConnectionHolder.shared.client != nil) { _, connected in
                if connected { onConnected() }
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
                Button { showAddSheet = true } label: {
                    Label("Add Host", systemImage: "plus")
                }
                .tint(Palette.headerAccent)
            }
        }
        .sheet(isPresented: $showAddSheet) {
            HostEditSheet(initial: nil) { label, host, port in
                viewModel.addHost(label: label, host: host, port: port)
                showAddSheet = false
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

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Hosts", systemImage: "server.rack")
        } description: {
            Text("Add a server to get started.")
        } actions: {
            Button { showAddSheet = true } label: {
                Text("Add Host")
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.headerAccent)
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
/// Rendered as a single muted, centered line beneath a hairline divider so it
/// stays out of the way of the user's own servers while remaining reachable.
/// Mirrors the Android `DemoFooter` composable.
private struct DemoFooter: View {
    let connecting: Bool
    let enabled: Bool
    let onConnect: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Divider()
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
                .frame(maxWidth: .infinity)
                .padding(.top, 10)
                .padding(.bottom, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityLabel("Try the live demo, no server needed")
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

    var body: some View {
        Button(action: { if enabled { onConnect() } }) {
            HStack(spacing: 12) {
                TerminalGlyph()
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.label)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    // verbatim: interpolating an Int32 through Text's
                    // LocalizedStringKey path runs it through a locale-aware
                    // number formatter, rendering ports like "8 443".
                    Text(verbatim: "\(entry.host):\(entry.port)")
                        .font(.subheadline)
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
        // New hosts default to the port every Termtastic server listens on.
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
                    Text("Hostname or IP address of your Mac running Termtastic.")
                        .foregroundStyle(Palette.textSecondary)
                }
                .listRowBackground(Palette.surface)
                Section {
                    TextField("Port", text: $portText)
                        .focused($focusedField, equals: .port)
                        .keyboardType(.numberPad)
                        .foregroundStyle(Palette.textPrimary)
                } footer: {
                    Text("Termtastic servers listen on port \(String(ConstantsKt.SERVER_TLS_PORT)) by default.")
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
        .presentationDetents([.medium, .large])
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
