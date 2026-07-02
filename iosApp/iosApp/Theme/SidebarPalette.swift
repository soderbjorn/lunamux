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
    /// navigating to the tree, and updated live by the appearance picker. All
    /// colour accessors resolve the active slot from this for the current system
    /// appearance.
    ///
    /// Backed by ``ThemeStore`` so an in-app theme/appearance change re-points
    /// every accessor *and* bumps the store's repaint generation. The setter is
    /// kept for `HostsViewModel`'s connect-time fetch.
    static var config: Client.TermtasticThemeConfig? {
        get { ThemeStore.shared.config }
        set { ThemeStore.shared.apply(newValue) }
    }

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

    /// Whether the active slot's `surface` token — the colour behind the
    /// navigation bars (``background``) — is visually dark.
    ///
    /// Used by `TreeView` and `AppearanceSheet` to pick the navigation bar's
    /// `toolbarColorScheme` from the *theme* instead of hard-coding `.dark`,
    /// so default bar content (title text, button chrome) contrasts with the
    /// themed bar background on light themes too (issue #95).
    ///
    /// - Parameter systemIsDark: the current system "prefers dark" flag.
    /// - Returns: `true` when the surface's luminance reads as dark.
    static func backgroundIsDark(systemIsDark: Bool) -> Bool {
        let argb = resolved(isDark: systemIsDark).surface
        let r = Double((argb >> 16) & 0xFF) / 255.0
        let g = Double((argb >> 8) & 0xFF) / 255.0
        let b = Double(argb & 0xFF) / 255.0
        return (0.299 * r + 0.587 * g + 0.114 * b) < 0.5
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
    /// Brightest text token (`textBright`). Used for the overview mini-pane
    /// title bars, which — matching the web/Mac pane headers — paint every
    /// pane's title in the same bright colour regardless of focus.
    static var textBright: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).textBright))
        })
    }
    /// Elevated surface token (`surfaceAlt`). Backs the mini-pane title bar so
    /// it reads as a distinct strip above the pane content (web parity).
    static var surfaceAlt: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).surfaceAlt))
        })
    }
    /// Divider/border token (`border`), e.g. the hairline under a title bar.
    static var border: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).border))
        })
    }
    /// Semantic warn colour, used by the waiting-for-input state indicator.
    static var warn: Color {
        Color(UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).warn))
        })
    }

    /// Dynamic `UIColor` for the workspace background (the `surface` token),
    /// suitable for UIKit APIs such as ``UINavigationBarAppearance``.
    ///
    /// Used by ``View/themedNavigationBar()`` to paint the navigation bar /
    /// large-title header so it blends with the themed list content beneath it
    /// (issue #49). Re-resolves against the current trait collection so flipping
    /// light/dark picks the matching theme slot.
    ///
    /// - SeeAlso: ``background``
    static var backgroundUIColor: UIColor {
        UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).surface))
        }
    }

    /// Dynamic `UIColor` for primary text (the `text` token), suitable for the
    /// navigation bar's title / large-title text attributes.
    ///
    /// Used by ``View/themedNavigationBar()`` so the header title stays legible
    /// against ``backgroundUIColor`` (issue #49).
    ///
    /// - SeeAlso: ``textPrimary``
    static var textPrimaryUIColor: UIColor {
        UIColor { traitCollection in
            let isDark = traitCollection.userInterfaceStyle == .dark
            return UIColor(Color(argb: resolved(isDark: isDark).text))
        }
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
