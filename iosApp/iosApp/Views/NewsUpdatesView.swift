import SwiftUI

/// "News & Updates" screen for the Lunamux iOS app.
///
/// Reached from the always-visible bell icon in the Hosts/Sessions toolbars
/// (muted when there is nothing new). Mirrors `TreeView`'s collapsing-title
/// chrome and lists two sections:
///
///  - **New update** — shown only when an update is available: the new version's
///    text and a Download button opening the manifest URL in Safari; swiping it
///    to either side dismisses the update ("seen, don't remind me") until a
///    newer version ships.
///  - **News** — each active, undismissed item as a row; swiping it to either
///    side dismisses it (persisted via the shared view-model so it never
///    reappears).
///
/// When nothing remains a "You're all caught up" placeholder is shown, and a
/// **Restore** footer button brings every dismissed news item and update back.
///
/// State comes from the app-wide `NewsUpdatesViewModel.shared`; dismissals call
/// `dismissNews` / `dismissUpdate` and Restore calls `restoreAll`. Mirrors the
/// Android `NewsUpdatesScreen`.
struct NewsUpdatesView: View {
    @State private var viewModel = NewsUpdatesViewModel.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        List {
            if viewModel.updateAvailable, let url = viewModel.infoURL {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(viewModel.latestVersionName.map { "Version \($0) is available." }
                            ?? "A new version is available.")
                            .foregroundStyle(Palette.textPrimary)
                        Button { openURL(url) } label: {
                            Text("Download")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Palette.headerAccent)
                    }
                    .cardStyle(borderColor: Palette.headerAccent.opacity(0.4))
                    // Swipe either direction to dismiss the update, matching the
                    // news rows and the macOS "close the update box" affordance.
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button { viewModel.dismissUpdate() } label: {
                            Label("Dismiss", systemImage: "checkmark")
                        }
                        .tint(Palette.headerAccent)
                    }
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button { viewModel.dismissUpdate() } label: {
                            Label("Dismiss", systemImage: "checkmark")
                        }
                        .tint(Palette.headerAccent)
                    }
                } header: {
                    SectionHeader("New update")
                }
            }

            ForEach(Array(viewModel.newsItems.enumerated()), id: \.element.id) { index, item in
                Section {
                    NewsRow(item: item, onLearnMore: { openURL($0) })
                        .cardStyle(borderColor: Palette.textSecondary.opacity(0.3))
                        // Swipe either direction to dismiss, matching the
                        // macOS "close a news card" affordance.
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button { viewModel.dismissNews(id: item.id) } label: {
                                Label("Dismiss", systemImage: "checkmark")
                            }
                            .tint(Palette.headerAccent)
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button { viewModel.dismissNews(id: item.id) } label: {
                                Label("Dismiss", systemImage: "checkmark")
                            }
                            .tint(Palette.headerAccent)
                        }
                } header: {
                    // Only the first card carries the "News" heading so the
                    // section reads as one list rather than repeating headers.
                    if index == 0 { SectionHeader("News") }
                }
            }

            // Empty-state placeholder when there is no update and no news. The
            // bell is always reachable, so an empty screen is a normal state.
            if !viewModel.updateAvailable && viewModel.newsItems.isEmpty {
                Section {
                    Text("You're all caught up")
                        .foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 24)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
            }

            // Restore footer: always available so dismissed items can be brought
            // back, including from the empty state.
            Section {
                Button { viewModel.restoreAll() } label: {
                    Text("Restore news & updates")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .center)
                }
                .buttonStyle(.bordered)
                .tint(Palette.headerAccent)
                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        // Plain (not inset-grouped) so the cards' width is governed solely by our
        // own `listRowInsets` (16pt sides, matching Android), with no extra
        // system section margins padding them in from the screen edges.
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Palette.background.ignoresSafeArea())
        .navigationTitle("News & Updates")
        .navigationBarTitleDisplayMode(.large)
        .toolbarBackground(Palette.background, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

/// Draws each update/news entry as a distinct rounded, subtly bordered card.
///
/// The card chrome is applied as the *content's* `.background`, not via
/// `listRowBackground`: an inset-grouped `List` clips the row-background view to
/// its own grouped-section shape, which sliced the hairline stroke off at the
/// card edges and corners (the "broken borders" bug). Drawing the rounded fill
/// and stroke behind the content instead — with the actual row background cleared
/// and the system row insets removed in favour of our own padding — lets the full
/// border render uninterrupted.
///
/// - Parameter borderColor: the hairline stroke colour (accent-tinted for the
///   update card, neutral for news cards).
private struct CardStyle: ViewModifier {
    let borderColor: Color

    func body(content: Content) -> some View {
        content
            .padding(16)
            // Expand every card to the full row width so a short card (e.g. the
            // update card, whose content barely fills a line) is the same width as
            // a text-heavy news card rather than shrinking to its content.
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Palette.surface)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(borderColor, lineWidth: 1)
                    )
            )
            // Outer margins + the small gap between adjacent cards. We supply the
            // insets ourselves so the bordered background, not a system-clipped
            // row background, defines the card's bounds.
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

private extension View {
    /// Applies the news/update card chrome. See `CardStyle`.
    /// - Parameter borderColor: the hairline stroke colour.
    /// - Returns: the view wrapped in the bordered-card background.
    func cardStyle(borderColor: Color) -> some View {
        modifier(CardStyle(borderColor: borderColor))
    }
}

/// One news row: optional date, title, plain-text body, and an optional "Learn
/// more" button when the item carries a URL.
private struct NewsRow: View {
    let item: NewsItemLocal
    let onLearnMore: (URL) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let date = item.date {
                Text(date)
                    .font(.caption)
                    .foregroundStyle(Palette.textSecondary)
            }
            Text(item.title)
                .font(.title3.weight(.bold))
                .foregroundStyle(Palette.textPrimary)
            Text(item.body)
                .font(.body)
                .foregroundStyle(Palette.textPrimary)
            if let url = item.url {
                Button { onLearnMore(url) } label: {
                    Text("Learn more")
                        .font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .tint(Palette.headerAccent)
                .padding(.top, 8)
            }
        }
    }
}

/// A bold section heading ("New update" / "News") — larger than the native
/// uppercase list-section caption, but smaller than the navigation title, so the
/// two sections read as distinct headlines rather than faint dividers.
private struct SectionHeader: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.title2)
            .fontWeight(.bold)
            .foregroundStyle(Palette.textPrimary)
            .textCase(nil)
            .padding(.top, 8)
            .padding(.bottom, 4)
    }
}
