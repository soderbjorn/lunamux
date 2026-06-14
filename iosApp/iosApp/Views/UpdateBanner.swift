import SwiftUI

/// "New version available" banner for the Termtastic iOS app.
///
/// A discreet, full-width tappable bar shown at the top of the hosts list
/// (`HostsView`) and each terminal session (`TerminalScreen`) whenever the
/// shared update checker reports a newer published build than the one running.
/// Tapping it opens the platform-specific "more info" URL (the App Store page)
/// from the manifest in Safari.
///
/// Observes the app-wide `UpdateCheckViewModel.shared` and renders nothing until
/// an update is actually available, so callers can place it unconditionally
/// (e.g. via `.safeAreaInset(edge: .top)`). Mirrors the Android `UpdateBanner`.
struct UpdateBanner: View {
    @State private var viewModel = UpdateCheckViewModel.shared

    var body: some View {
        if viewModel.updateAvailable {
            HStack(spacing: 8) {
                Image(systemName: "arrow.down.circle")
                    .font(.footnote)
                Text(label)
                    .font(.footnote.weight(.medium))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption2)
                    .opacity(0.7)
            }
            // Discrete, theme-aware styling: a faint wash of the workspace
            // accent with accent-coloured text, rather than a loud solid bar.
            .foregroundStyle(Palette.headerAccent)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
            .background(Palette.headerAccent.opacity(0.12))
            .contentShape(Rectangle())
            .onTapGesture { viewModel.openUpdateURL() }
        }
    }

    /// Banner text, including the new version name when known.
    private var label: String {
        if let name = viewModel.latestVersionName {
            return "New version available — \(name)"
        }
        return "New version available"
    }
}
