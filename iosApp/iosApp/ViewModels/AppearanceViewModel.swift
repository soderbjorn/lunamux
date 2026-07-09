import Foundation
import Observation
import Client

/// Observable holder for the app's *active* resolved theme config, plus a
/// monotonic `generation` that views key off to force a repaint when the theme
/// changes without the system trait changing.
///
/// `Palette` reads its config from here (see `Palette.config`), so updating
/// ``apply(_:)`` re-points every colour accessor at the new selection. Because
/// the dynamic `UIColor` closures behind `Palette` only re-resolve on a trait
/// change, an explicit theme/appearance switch additionally bumps ``generation``;
/// `TreeView` observes it and `.id()`s its content so the visible chrome rebuilds
/// against the new palette immediately.
///
/// - SeeAlso: `Palette`
/// - SeeAlso: `AppearanceViewModel`
@Observable
final class ThemeStore {
    /// The process-wide store. `Palette` and the appearance picker share it.
    static let shared = ThemeStore()

    /// The active dual-slot theme config (or `nil` before the first fetch).
    /// Stored, not computed, so `Palette` reads a plain value off the main store.
    var config: Client.LunamuxThemeConfig?

    /// Bumped on every theme/appearance change. Views read it inside their body
    /// (e.g. via `.id`) so an explicit switch rebuilds them even though the
    /// system light/dark trait did not change.
    private(set) var generation: Int = 0

    private init() {}

    /// Replace the active config and bump ``generation`` to trigger a repaint.
    ///
    /// - Parameter config: the new selection (from a server fetch or a local edit).
    func apply(_ config: Client.LunamuxThemeConfig?) {
        self.config = config
        generation &+= 1
    }
}

/// SwiftUI-facing wrapper around the shared KMP `ThemeBackingViewModel`, backing
/// the appearance + theme picker sheet.
///
/// Bridges the backing VM's `snapshot` flow into `@Observable` properties the
/// sheet binds to (the appearance preference and the grouped theme catalog), and
/// forwards the two mutating actions (set appearance / pick a theme). Every edit
/// writes the *same* canonical server selection the Mac/Electron app writes, so
/// the choice persists and syncs to every connected client; it also feeds the
/// new snapshot into ``ThemeStore`` so the whole app repaints live.
///
/// Mirrors the Android `AppearanceSheet`, which collects the same backing VM.
///
/// - SeeAlso: `Client.ThemeBackingViewModel`
@Observable
final class AppearanceViewModel {
    /// The user's current Auto / Light / Dark preference.
    private(set) var appearance: Client.Appearance = .auto_
    /// The full pickable catalog as a single list, ordered starred dark →
    /// starred light → unstarred dark → unstarred light (issue #107).
    private(set) var orderedThemes: [Client.Theme] = []
    /// Names of the user's starred / favorite themes.
    private(set) var favorites: Set<String> = []
    /// The dark slot's current theme name (for the assigned-card highlight).
    private(set) var darkThemeName: String = ""
    /// The light slot's current theme name (for the assigned-card highlight).
    private(set) var lightThemeName: String = ""

    /// The shared theme model, or `nil` with no live connection.
    private let backing: Client.ThemeBackingViewModel?

    /// Observes `backing.snapshot`. Recreated per `start()` because
    /// `FlowObserver.clear()` permanently cancels its scope.
    private var flowObserver = Client.FlowObserver()

    init() {
        if let client = ConnectionHolder.shared.client {
            backing = Client.ThemeBackingViewModel(
                client: client,
                persister: Client.HttpSettingsPersister(client: client)
            )
        } else {
            backing = nil
        }
    }

    /// Whether a live connection backs the picker.
    var isConnected: Bool { backing != nil }

    /// Begin observing the snapshot and seed it from the server. Idempotent
    /// enough to call on each sheet presentation.
    func start() {
        guard let backing else { return }
        flowObserver = Client.FlowObserver()
        flowObserver.observe(flow: backing.snapshot) { [weak self] value in
            guard let self, let snap = value as? Client.ThemeSnapshotV2 else { return }
            let ordered = backing.themesOrdered()
            DispatchQueue.main.async {
                self.appearance = snap.appearance
                self.orderedThemes = ordered
                self.favorites = Set(snap.favorites)
                self.darkThemeName = snap.darkThemeName
                self.lightThemeName = snap.lightThemeName
                // Re-point Palette at the live selection and force a repaint.
                ThemeStore.shared.apply(Client.LunamuxThemeConfig(studio: snap))
            }
        }
        Task { try? await backing.load() }
    }

    /// Stop observing. Called when the sheet disappears.
    func stop() {
        flowObserver.clear()
    }

    /// Set the appearance preference; applies + persists.
    func setAppearance(_ appearance: Client.Appearance) {
        guard let backing else { return }
        Task { try? await backing.setAppearance(appearance: appearance) }
    }

    /// Whether the dark slot is the one currently painted, given the system
    /// dark-mode flag — the appearance preference, or `systemIsDark` when Auto.
    /// The sheet uses this to decide which assigned theme card to highlight,
    /// mirroring the Mac/Electron theme manager.
    ///
    /// - Parameter systemIsDark: the current system "prefers dark" flag.
    /// - Returns: `true` when the dark slot is active, `false` for the light slot.
    func activeSlotIsDark(systemIsDark: Bool) -> Bool {
        switch appearance {
        case .dark: return true
        case .light: return false
        default: return systemIsDark
        }
    }

    /// Assign `name` to whichever slot is currently active (the appearance
    /// preference, or the OS when Auto) — exactly like clicking a card in the
    /// Mac/Electron theme manager, so every pick takes visible effect
    /// immediately (issue #97). Applies + persists.
    ///
    /// - Parameters:
    ///   - name:         the theme to assign to the active slot.
    ///   - systemIsDark: the current system "prefers dark" flag (only consulted
    ///     when the appearance is Auto).
    func setActiveTheme(name: String, systemIsDark: Bool) {
        guard let backing else { return }
        Task { try? await backing.setActiveTheme(name: name, systemIsDark: systemIsDark) }
    }

    /// Whether `name` is currently starred (drives the context-menu label and the
    /// card's star badge).
    ///
    /// - Parameter name: the theme name to test.
    /// - Returns: `true` if the theme is favorited.
    func isFavorite(name: String) -> Bool {
        favorites.contains(name)
    }

    /// Toggle whether `name` is starred / favorited; applies + persists and syncs
    /// to every connected client (issue #107). Invoked from the theme card's
    /// long-press context menu.
    ///
    /// - Parameter name: the theme to star or unstar.
    func toggleFavorite(name: String) {
        guard let backing else { return }
        Task { try? await backing.toggleFavorite(name: name) }
    }

    deinit {
        flowObserver.clear()
    }
}
