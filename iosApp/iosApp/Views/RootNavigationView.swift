import SwiftUI

/// Navigation destinations for the app, matching the Android `TermtasticApp`
/// NavHost routes.
enum Destination: Hashable {
    case tree
    case terminal(sessionId: String)
    case fileBrowserList(paneId: String, dirRelPath: String)
    case fileBrowserContent(paneId: String, relPath: String)
    case gitList(paneId: String)
    case gitDiff(paneId: String, filePath: String)
}

/// Root navigation view with a `NavigationStack`. The hosts screen is the
/// start destination; connecting to a server navigates to the tree.
struct RootNavigationView: View {
    @State private var path = NavigationPath()
    @State private var hostsViewModel = HostsViewModel()
    @State private var treeViewModel = TreeViewModel()
    /// Drives the first-launch onboarding cover. Seeded once from the persisted
    /// flag, so a fresh install shows the walkthrough and returning users skip
    /// straight to the host list.
    @State private var showOnboarding = !OnboardingStore.hasSeen

    var body: some View {
        NavigationStack(path: $path) {
            HostsView(viewModel: hostsViewModel, onConnected: {
                path.append(Destination.tree)
            })
            .navigationDestination(for: Destination.self) { dest in
                switch dest {
                case .tree:
                    TreeView(
                        viewModel: treeViewModel,
                        onOpenTerminal: { sessionId in
                            path.append(Destination.terminal(sessionId: sessionId))
                        },
                        onOpenFileBrowser: { paneId in
                            path.append(Destination.fileBrowserList(paneId: paneId, dirRelPath: ""))
                        },
                        onOpenGit: { paneId in
                            path.append(Destination.gitList(paneId: paneId))
                        },
                        onDisconnect: {
                            treeViewModel = TreeViewModel()
                            path = NavigationPath()
                        }
                    )

                case .terminal(let sessionId):
                    TerminalScreen(sessionId: sessionId, onBack: {
                        path.removeLast()
                    })

                case .fileBrowserList(let paneId, let dirRelPath):
                    FileBrowserListView(
                        paneId: paneId,
                        dirRelPath: dirRelPath,
                        onOpenDir: { child in
                            path.append(Destination.fileBrowserList(paneId: paneId, dirRelPath: child))
                        },
                        onOpenFile: { relPath in
                            path.append(Destination.fileBrowserContent(paneId: paneId, relPath: relPath))
                        },
                        onBack: { path.removeLast() }
                    )

                case .fileBrowserContent(let paneId, let relPath):
                    FileBrowserContentView(
                        paneId: paneId,
                        relPath: relPath,
                        onBack: { path.removeLast() }
                    )

                case .gitList(let paneId):
                    GitListView(
                        paneId: paneId,
                        onOpenFile: { filePath in
                            path.append(Destination.gitDiff(paneId: paneId, filePath: filePath))
                        },
                        onBack: { path.removeLast() }
                    )

                case .gitDiff(let paneId, let filePath):
                    GitDiffView(
                        paneId: paneId,
                        filePath: filePath,
                        onBack: { path.removeLast() }
                    )
                }
            }
        }
        .fullScreenCover(isPresented: $showOnboarding) {
            OnboardingView(onFinish: {
                OnboardingStore.markSeen()
                showOnboarding = false
            })
        }
        .onAppear {
            // Kick off the shared version-update checker once. Idempotent, so a
            // re-appearance (e.g. returning from a cover) won't start a second
            // loop. The banner observes `UpdateCheckViewModel.shared`.
            UpdateCheckViewModel.shared.start()
        }
    }
}
