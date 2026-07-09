import SwiftUI
import Observation
import Client

/// First-launch onboarding gate, sourced from the shared `LocalRepository`
/// instead of `UserDefaults`. Observes `LocalState.onboardingSeen` and publishes
/// whether the walkthrough should be shown.
///
/// `shouldShow` is nil until `local_state.json` has hydrated; it then resolves to
/// `true` on a fresh install or `false` once the walkthrough has been completed.
/// It is latched on first load so a later state change (e.g. the persisted write
/// from `markSeen`) does not re-present the cover.
@Observable
final class OnboardingGate {
    /// nil while loading, then whether the walkthrough should be presented.
    var shouldShow: Bool?

    private let flowObserver = Client.FlowObserver()

    init() {
        flowObserver.observe(flow: AppRepository.shared.state) { [weak self] value in
            guard let state = value as? Client.LocalState else { return }
            let show = !state.onboardingSeen
            DispatchQueue.main.async {
                // Latch on first hydration only, so persisting `seen` later
                // (which flips the flag) cannot re-trigger the cover.
                if self?.shouldShow == nil { self?.shouldShow = show }
            }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Dismiss the cover immediately and persist the completed flag.
    func markSeen() {
        shouldShow = false
        Task { try? await AppRepository.shared.setOnboardingSeen(seen: true) }
    }
}

/// Navigation destinations for the app, matching the Android `LunamuxApp`
/// NavHost routes.
enum Destination: Hashable {
    case tree
    case newsUpdates
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
    /// Drives the first-launch onboarding cover, sourced from the shared
    /// `LocalRepository` so a fresh install shows the walkthrough and returning
    /// users skip straight to the host list.
    @State private var onboardingGate = OnboardingGate()

    var body: some View {
        NavigationStack(path: $path) {
            HostsView(viewModel: hostsViewModel, onConnected: {
                path.append(Destination.tree)
            }, onOpenNews: {
                path.append(Destination.newsUpdates)
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
                        },
                        onOpenNews: {
                            path.append(Destination.newsUpdates)
                        }
                    )

                case .newsUpdates:
                    NewsUpdatesView()

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
        .fullScreenCover(isPresented: Binding(
            get: { onboardingGate.shouldShow == true },
            set: { if !$0 { onboardingGate.shouldShow = false } }
        )) {
            OnboardingView(onFinish: {
                onboardingGate.markSeen()
            })
        }
        .onAppear {
            // Kick off the shared news/update checker once. Idempotent, so a
            // re-appearance (e.g. returning from a cover) won't start a second
            // loop. The toolbar bell and News & Updates screen observe
            // `NewsUpdatesViewModel.shared`.
            NewsUpdatesViewModel.shared.start()
        }
    }
}
