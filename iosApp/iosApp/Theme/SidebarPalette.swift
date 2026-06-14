import SwiftUI
import UIKit
import Client

/// Adaptive colour palette derived from the Termtastic semantic theme system.
///
/// Resolves colours from the user's selected theme via ``settings``. When
/// settings have not been loaded yet (e.g. before the first server fetch),
/// falls back to the default Neon Green theme. Sidebar-specific overrides are
/// respected via `schemeForPane(pane: "sidebar")`.
///
/// ``settings`` is set at connect time (in `HostsView`) so that all views
/// pick up the user's theme from the start.
///
/// - SeeAlso: `Client.ColorScheme.resolve(isDark:)`
/// - SeeAlso: `Client.UiSettings`
enum Palette {
    /// The user's dual-slot theme config (light theme + dark theme) fetched
    /// from the server after connection. Set by `HostsViewModel` before
    /// navigating to the tree. All colour accessors resolve the active slot
    /// from this for the current system appearance.
    static var config: Client.TermtasticThemeConfig?

    /// Resolve the active `UiSettings` (correct light/dark slot) for the given
    /// system appearance, falling back to the app defaults when no config has
    /// been fetched yet.
    static func resolved(isDark: Bool) -> Client.UiSettings {
        let cfg = config ?? Client.ThemeConfigKt.defaultThemeConfig()
        return cfg.resolve(systemIsDark: isDark)
    }

    /// The active `UiSettings` resolved for the *current* system appearance,
    /// or `nil` before the first server fetch. Reactive accessors that already
    /// hold a trait should prefer `resolved(isDark:)`.
    static var settings: Client.UiSettings? {
        config == nil
            ? nil
            : resolved(isDark: UITraitCollection.current.userInterfaceStyle == .dark)
    }

    /// Resolves the sidebar palette for the given appearance, selecting the
    /// correct light/dark theme slot from the server config (falling back to
    /// the app defaults).
    private static func sidebarPalette(isDark: Bool) -> Client.ResolvedPalette {
        let ui = resolved(isDark: isDark)
        return ui.schemeForPane(pane: "sidebar").resolve(
            appearance: ui.appearance,
            systemIsDark: isDark
        )
    }

    /// Theme accent colour derived from the terminal foreground.
    ///
    /// Like the other accessors below, this is a *dynamic* `UIColor` that
    /// re-resolves against the current trait collection, so flipping the device
    /// between light and dark immediately selects the matching theme slot's
    /// accent rather than staying frozen on the slot active when it was first
    /// read.
    static var headerAccent: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.accent.primary)).withAlphaComponent(0.75)
        })
    }

    // Adaptive accessors — resolve at render time based on system appearance
    static var background: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.bg))
        })
    }
    static var surface: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.surface.raised))
        })
    }
    static var textPrimary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.text))
        })
    }
    static var textSecondary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.sidebar.textDim))
        })
    }
    /// Semantic warn colour, used by the waiting-for-input state indicator.
    static var warn: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            let pal = sidebarPalette(isDark: isDark)
            return UIColor(Color(argb: pal.semantic.warn))
        })
    }
}

extension Color {
    /// Create a Color from an ARGB UInt64 like `0xFF1C1C1E`.
    init(hex: UInt64) {
        let a = Double((hex >> 24) & 0xFF) / 255.0
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }

    /// Create a Color from a Kotlin Long ARGB value (bridged as Int64).
    init(argb: Int64) {
        self.init(hex: UInt64(bitPattern: argb))
    }
}
