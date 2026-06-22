import SwiftUI
import Client

/// Lists changed files from a remote git pane, grouped by directory.
/// Mirrors the pattern of `MarkdownListView`.
struct GitListView: View {
    let paneId: String
    var onOpenFile: (String) -> Void
    var onBack: () -> Void

    @State private var entries: [Client.GitFileEntry]?
    @State private var errorMessage: String?

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()

            if let list = entries {
                if list.isEmpty {
                    Text(errorMessage ?? "No changed files")
                        .foregroundStyle(Palette.textSecondary)
                        .font(.subheadline)
                        .padding()
                } else {
                    fileList(list)
                }
            } else {
                ProgressView()
                    .tint(Palette.textSecondary)
            }
        }
        .navigationTitle("Git")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .task { await loadEntries() }
    }

    private func fileList(_ list: [Client.GitFileEntry]) -> some View {
        let groups = groupByDirectory(list)
        return List {
            ForEach(groups, id: \.0) { directory, entries in
                Section {
                    ForEach(entries, id: \.filePath) { entry in
                        Button { onOpenFile(entry.filePath) } label: {
                            HStack(spacing: 8) {
                                GitStatusBadge(status: entry.status)
                                Text(basename(entry.filePath))
                                    .foregroundStyle(Palette.textPrimary)
                                    .font(.body)
                                    .lineLimit(1)
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Palette.background)
                    }
                } header: {
                    Text(directory.isEmpty ? "Root" : directory)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .foregroundStyle(Palette.textSecondary)
                        .tracking(0.5)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func loadEntries() async {
        guard let socket = ConnectionHolder.shared.windowSocket else {
            errorMessage = "Not connected"
            entries = []
            return
        }
        do {
            let list = try await socket.gitList(paneId: paneId, timeoutMs: 10_000)
            entries = list as? [Client.GitFileEntry] ?? []
        } catch {
            errorMessage = "Failed to load file list"
            entries = []
        }
    }

    private func groupByDirectory(_ list: [Client.GitFileEntry]) -> [(String, [Client.GitFileEntry])] {
        var order: [String] = []
        var map: [String: [Client.GitFileEntry]] = [:]
        for entry in list {
            let dir = entry.directory
            if map[dir] == nil { order.append(dir) }
            map[dir, default: []].append(entry)
        }
        return order.map { ($0, map[$0]!) }
    }

    private func basename(_ path: String) -> String {
        path.components(separatedBy: "/").last ?? path
    }
}

// MARK: - Git Status Badge

struct GitStatusBadge: View {
    let status: Client.GitFileStatus
    /// Square footprint in points; the glyph scales with it so the same badge
    /// serves both the full git list (18) and the overview's mini git pane (12,
    /// issue #44).
    var size: CGFloat = 18

    var body: some View {
        Image(systemName: statusIcon)
            .font(.system(size: size * 0.66, weight: .bold))
            .foregroundStyle(statusColor)
            .frame(width: size, height: size)
    }

    private var statusIcon: String {
        switch status {
        case .modified:  return "pencil"
        case .added:     return "plus.circle"
        case .deleted:   return "minus.circle"
        case .renamed:   return "arrow.right"
        case .untracked: return "plus.circle"
        default:         return "questionmark"
        }
    }

    private var statusColor: Color {
        switch status {
        case .modified:  return .yellow
        case .added:     return .green
        case .deleted:   return .red
        case .renamed:   return .blue
        case .untracked: return .gray
        default:         return .gray
        }
    }
}
