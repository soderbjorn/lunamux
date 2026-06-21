import SwiftUI
import UIKit
import Client

/// Adaptive colour palette derived from the Termtastic semantic theme system.
///
/// Resolves colours from the user's selected theme via ``config``. When the
/// config has not been loaded yet (e.g. before the first server fetch), falls
/// back to the default theme. Under the new theme system there is a single flat
/// ``ResolvedTheme`` per appearance — no per-pane scheme map — so every accessor
/// reads its colour straight off the resolved theme's 19 semantic tokens.
///
/// ``config`` is set at connect time (in `HostsViewModel`) so that all views
/// pick up the user's theme from the start.
///
/// - SeeAlso: `Client.TermtasticThemeConfig.resolve(systemIsDark:)`
/// - SeeAlso: `Client.ResolvedTheme`
enum Palette {
    /// The user's dual-slot theme config (light theme + dark theme) fetched
    /// from the server after connection. Set by `HostsViewModel` before
    /// navigating to the tree. All colour accessors resolve the active slot
    /// from this for the current system appearance.
    static var config: Client.TermtasticThemeConfig?

    /// Resolve the active flat ``ResolvedTheme`` (correct light/dark slot) for
    /// the given system appearance, falling back to the app defaults when no
    /// config has been fetched yet.
    ///
    /// - Parameter isDark: the current system "prefers dark" flag.
    /// - Returns: the resolved 19-token palette for the active slot.
    static func resolved(isDark: Bool) -> Client.ResolvedTheme {
        let cfg = config ?? Client.ThemeConfigKt.defaultThemeConfig()
        return cfg.resolve(systemIsDark: isDark)
    }

    /// The active ``ResolvedTheme`` resolved for the *current* system
    /// appearance, or `nil` before the first server fetch. Reactive accessors
    /// that already hold a trait should prefer ``resolved(isDark:)``.
    static var settings: Client.ResolvedTheme? {
        config == nil
            ? nil
            : resolved(isDark: UITraitCollection.current.userInterfaceStyle == .dark)
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
            let theme = resolved(isDark: isDark)
            return UIColor(Color(argb: theme.accent)).withAlphaComponent(0.75)
        })
    }

    // Adaptive accessors — resolve at render time based on system appearance.
    // The app background uses the `surface` token (matching the web sidebar
    // mapping); the sidebar text tokens map to `text` / `textDim`.
    static var background: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).surface))
        })
    }
    static var surface: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).surface))
        })
    }
    static var textPrimary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).text))
        })
    }
    static var textSecondary: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).textDim))
        })
    }
    /// Semantic warn colour, used by the waiting-for-input state indicator.
    static var warn: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).warn))
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
