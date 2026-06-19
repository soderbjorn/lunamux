import SwiftUI

/// Pulsing "News & Updates" toolbar bell for the Termtastic iOS app.
///
/// Shared by the Hosts and Sessions toolbars (see `HostsView` / `TreeView`). The
/// bell is *always* shown so the "News & Updates" screen — and its Restore
/// button — is reachable at any time; when there is nothing new it renders
/// muted/grayed (`muted = true`) rather than hidden. With content the warning
/// colour pulses continuously when there is actual news so it reads as a
/// call-to-action, mirroring the desktop and Android bells. When only a version
/// update is available (no news) the bell shows coloured but static.
struct NewsBellButton: View {
    /// Invoked when the bell is tapped; navigates to the News & Updates screen.
    let action: () -> Void

    /// Whether to pulse the tint. `true` only when there is news to read; a
    /// version update on its own keeps the bell static. Defaults to `true` so
    /// existing call sites that always pulse keep working. Ignored when `muted`.
    var shouldPulse: Bool = true

    /// When `true`, render the bell grayed (nothing new) and never pulse.
    var muted: Bool = false

    /// Drives the repeating tint animation. Flipped once `.onAppear` so the
    /// `repeatForever` animation has two values to oscillate between.
    @State private var pulsing = false

    var body: some View {
        Button(action: action) {
            bellImage
        }
        .accessibilityLabel("News and updates")
        .onAppear { pulsing = shouldPulse && !muted }
    }

    /// The bell glyph, grayed when `muted`, otherwise warning-coloured (pulsing
    /// between full and faded when `shouldPulse`).
    @ViewBuilder private var bellImage: some View {
        if muted {
            Image(systemName: "bell.badge")
                .foregroundStyle(Palette.textSecondary)
        } else {
            Image(systemName: "bell.badge")
                .foregroundStyle(pulsing ? Palette.warn : Palette.warn.opacity(0.35))
                .animation(
                    .easeInOut(duration: 1.3).repeatForever(autoreverses: true),
                    value: pulsing
                )
        }
    }
}
