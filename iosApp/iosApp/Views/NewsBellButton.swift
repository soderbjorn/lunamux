import SwiftUI

/// Pulsing "News & Updates" toolbar bell for the Lunamux iOS app.
///
/// Shared by the Servers and Sessions toolbars (see `HostsView` / `TreeView`).
/// The bell is *always* shown so the "News & Updates" screen — and its Restore
/// button — is reachable at any time, and it always carries the theme accent so
/// it reads as one of the toolbar's action icons rather than a separate state
/// light (issue #136). Motion, not colour, is the signal: it pulses
/// continuously when there is actual news so it reads as a call-to-action,
/// mirroring the desktop and Android bells, and sits still otherwise.
///
/// The bell observes the app-wide `NewsUpdatesViewModel.shared` directly and
/// derives its pulse from that `@Observable` inside its own `body`. This subscribes the bell as a leaf observer, so it re-renders the
/// moment the shared state changes — even though it lives inside a `.toolbar`,
/// whose content SwiftUI does not reliably re-evaluate from a parent view's
/// observed state. Earlier the appearance was passed in as params and the pulse
/// was latched once in `.onAppear`, so the asynchronously-fetched news never lit
/// the bell until the view was destroyed and recreated (i.e. after visiting the
/// News screen and navigating back).
struct NewsBellButton: View {
    /// Invoked when the bell is tapped; navigates to the News & Updates screen.
    let action: () -> Void

    /// Shared news/update checker. Reading its `hasNews` in `body` subscribes
    /// this view, so the bell updates reactively as news loads.
    private var news = NewsUpdatesViewModel.shared

    /// Construct the bell.
    ///
    /// - Parameter action: tap handler navigating to the News & Updates screen.
    init(action: @escaping () -> Void) {
        self.action = action
    }

    /// Drives the repeating tint animation. A single flip to `true` is enough for
    /// the `repeatForever(autoreverses:)` animation to oscillate the tint until it
    /// flips back; it is kept in sync with `news.hasNews` via `.onAppear` and
    /// `.onChange` so it tracks news that arrives after the bell is on screen.
    @State private var pulsing = false

    var body: some View {
        // Read the observed state once per body so this view is subscribed and
        // re-rendered when the shared view-model changes.
        let hasNews = news.hasNews
        return Button(action: action) {
            bellImage()
        }
        .accessibilityLabel("News and updates")
        .onAppear { pulsing = hasNews }
        .onChange(of: hasNews) { _, value in pulsing = value }
    }

    /// The bell glyph: always the theme accent, matching every other toolbar
    /// action, and pulsing only when there is news (issue #136).
    ///
    /// The resting tint is the *un-faded* accent and `pulsing` animates away
    /// from it, which is why the ternary reads "inverted". Flipping `pulsing`
    /// to `true` starts a `repeatForever(autoreverses:)` run toward the faded
    /// accent, so the glyph oscillates between the two; flipping it back settles
    /// on the resting tint under a one-shot animation, because leaving the
    /// repeating curve installed would pulse on forever after the news is read.
    ///
    /// - Returns: the tinted bell image.
    private func bellImage() -> some View {
        Image(systemName: "bell.badge")
            .foregroundStyle(pulsing ? Palette.headerAccent.opacity(0.35) : Palette.headerAccent)
            .animation(
                pulsing
                    ? .easeInOut(duration: 1.3).repeatForever(autoreverses: true)
                    : .easeInOut(duration: 0.2),
                value: pulsing
            )
    }
}
