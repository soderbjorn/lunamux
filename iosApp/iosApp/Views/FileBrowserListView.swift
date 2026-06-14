import SwiftUI
import Client

/// Lists a single directory in the remote pane's file tree. Tapping a
/// directory pushes another `FileBrowserListView`; tapping a file pushes
/// `FileBrowserContentView`. Mirrors the Android `FileBrowserListScreen`.
struct FileBrowserListView: View {
    let paneId: String
    let dirRelPath: String
    var onOpenDir: (String) -> Void
    var onOpenFile: (String) -> Void
    var onBack: () -> Void

    @State private var entries: [Client.FileBrowserEntry]?
    @State private var errorMessage: String?

    private var title: String {
        if dirRelPath.isEmpty { return "Files" }
        return dirRelPath.components(separatedBy: "/").last ?? dirRelPath
    }

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()

            if let list = entries {
                if list.isEmpty {
                    Text(errorMessage ?? "Empty directory")
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
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Palette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .task { await loadEntries() }
    }

    private func fileList(_ list: [Client.FileBrowserEntry]) -> some View {
        List {
            ForEach(list, id: \.relPath) { entry in
                Button {
                    if entry.isDir { onOpenDir(entry.relPath) }
                    else { onOpenFile(entry.relPath) }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: entry.isDir ? "folder.fill" : "doc.text")
                            .font(.system(size: 15))
                            .foregroundStyle(entry.isDir ? Palette.headerAccent : Palette.textSecondary)
                            .frame(width: 22)
                        Text(entry.name)
                            .foregroundStyle(Palette.textPrimary)
                            .font(.body)
                            .lineLimit(1)
                        Spacer()
                        if entry.isDir {
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(Palette.textSecondary.opacity(0.5))
                        }
                    }
                }
                .buttonStyle(.plain)
                .listRowBackground(Palette.background)
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
            let list = try await socket.fileBrowserListDir(
                paneId: paneId,
                dirRelPath: dirRelPath,
                timeoutMs: 10_000
            )
            entries = list as? [Client.FileBrowserEntry] ?? []
            if !dirRelPath.isEmpty {
                // Mirror the web client's persisted expansion set as the
                // user drills deeper on mobile.
                try? await socket.fileBrowserSetExpanded(
                    paneId: paneId,
                    dirRelPath: dirRelPath,
                    expanded: true
                )
            }
        } catch {
            errorMessage = "Failed to load directory"
            entries = []
        }
    }
}
