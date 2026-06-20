import SwiftUI

/// Pulsing "News & Updates" toolbar bell for the Termtastic iOS app.
///
/// Shared by the Hosts and Sessions toolbars (see `HostsView` / `TreeView`). The
/// bell is *always* shown so the "News & Updates" screen — and its Restore
/// button — is reachable at any time; when there is nothing new it renders
/// muted/grayed rather than hidden. With content the warning colour shows, and it
/// pulses continuously when there is actual news so it reads as a call-to-action,
/// mirroring the desktop and Android bells. When only a version update is
/// available (no news) the bell shows coloured but static.
///
/// The bell observes the app-wide `NewsUpdatesViewModel.shared` directly and
/// derives its appearance (`muted` / pulsing) from that `@Observable` inside its
/// own `body`. This subscribes the bell as a leaf observer, so it re-renders the
/// moment the shared state changes — even though it lives inside a `.toolbar`,
/// whose content SwiftUI does not reliably re-evaluate from a parent view's
/// observed state. Earlier the appearance was passed in as params and the pulse
/// was latched once in `.onAppear`, so the asynchronously-fetched news never lit
/// the bell until the view was destroyed and recreated (i.e. after visiting the
/// News screen and navigating back).
struct NewsBellButton: View {
    /// Invoked when the bell is tapped; navigates to the News & Updates screen.
    let action: () -> Void

    /// Shared news/update checker. Reading its `hasContent` / `hasNews` in `body`
    /// subscribes this view, so the bell updates reactively as news loads.
    private var news = NewsUpdatesViewModel.shared

    /// Construct the bell.
    ///
    /// - Parameter action: tap handler navigating to the News & Updates screen.
    init(action: @escaping () -> Void) {
        self.action = action
    }

    /// Drives the repeating tint animation. A single flip to `true` is enough for
    /// the `repeatForever(autoreverses:)` animation to oscillate the tint forever;
    /// it is kept in sync with `news.hasNews` via `.onAppear` and `.onChange` so
    /// it tracks news that arrives after the bell is already on screen.
    @State private var pulsing = false

    var body: some View {
        // Read the observed state once per body so this view is subscribed and
        // re-rendered when the shared view-model changes.
        let muted = !news.hasContent
        let hasNews = news.hasNews
        return Button(action: action) {
            bellImage(muted: muted)
        }
        .accessibilityLabel("News and updates")
        .onAppear { pulsing = hasNews }
        .onChange(of: hasNews) { _, value in pulsing = value }
    }

    /// The bell glyph, grayed when `muted`, otherwise warning-coloured (pulsing
    /// between full and faded when there is news).
    ///
    /// - Parameter muted: when `true`, render grayed (nothing new) and never pulse.
    /// - Returns: the tinted bell image.
    @ViewBuilder private func bellImage(muted: Bool) -> some View {
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
