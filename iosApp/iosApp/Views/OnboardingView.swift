import SwiftUI

/// First-launch onboarding walkthrough.
///
/// Presents the shared `OnboardingViewModel` pages in a swipeable, paged
/// `TabView` with native page dots, Back / Next buttons, and a "Get Started"
/// button on the last page. All copy, page order, and the Mac-app download URL
/// come from the shared client module; this view only lays them out and turns
/// the final page's link into a tappable `Link`.
///
/// Presented once by `RootNavigationView` — via `fullScreenCover`, before the
/// host list — when the shared `LocalRepository`'s `onboardingSeen` flag (mirrored
/// by `OnboardingGate`) is still false. Mirrors the Android `OnboardingScreen`.
///
/// Colours come from `Palette`, which — with no server connection yet — falls
/// back to the app's default Neon Green theme, so the walkthrough matches the
/// rest of the (pre-connection) UI.
struct OnboardingView: View {
    @State private var viewModel = OnboardingViewModel()

    /// Invoked when the user completes or skips the walkthrough; the caller
    /// persists the "seen" flag and dismisses the cover.
    var onFinish: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button("Skip", action: onFinish)
                    .foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal)
                    .padding(.top, 8)
            }

            TabView(selection: $viewModel.pageIndex) {
                ForEach(Array(viewModel.pages.enumerated()), id: \.element.id) { index, page in
                    pageContent(page).tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            HStack {
                if !viewModel.isFirstPage {
                    Button("Back") {
                        withAnimation { viewModel.pageIndex -= 1 }
                    }
                    .foregroundStyle(Palette.textSecondary)
                }
                Spacer()
                Button(viewModel.isLastPage ? "Get Started" : "Next") {
                    if viewModel.isLastPage {
                        onFinish()
                    } else {
                        withAnimation { viewModel.pageIndex += 1 }
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Palette.headerAccent)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 24)
            .animation(.default, value: viewModel.isFirstPage)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background.ignoresSafeArea())
        .tint(Palette.headerAccent)
    }

    /// Renders a single page: its SF Symbol, title, body, and — on the download
    /// page — a tappable link that opens the Mac-app site in the browser.
    @ViewBuilder
    private func pageContent(_ page: OnboardingViewModel.Page) -> some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: page.systemImage)
                .font(.system(size: 84))
                .foregroundStyle(Palette.headerAccent)
            Text(page.title)
                .font(.title.bold())
                .foregroundStyle(Palette.textPrimary)
                .multilineTextAlignment(.center)
            Text(page.body)
                .font(.body)
                .foregroundStyle(Palette.textSecondary)
                .multilineTextAlignment(.center)
            if let url = page.linkURL, let label = page.linkLabel {
                Link(label, destination: url)
                    .font(.headline)
                    .underline()
                    .foregroundStyle(Palette.headerAccent)
            }
            Spacer()
            Spacer()
        }
        .padding(.horizontal, 32)
    }
}
