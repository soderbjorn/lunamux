import SwiftUI
import Client

/// Appearance + theme picker sheet for the iOS sessions view.
///
/// Brings the Mac/Electron app's appearance toggle + theme manager to mobile in
/// a deliberately simple, read-only form: pick the appearance (Auto / Light /
/// Dark) and tap a theme thumbnail to assign it to the currently-active slot.
/// There is no semantic-colour editing and no clone/delete — browse and pick
/// only.
///
/// Every choice routes through `AppearanceViewModel` → the shared
/// `ThemeBackingViewModel`, which writes the same canonical server selection the
/// desktop writes, so the change persists and syncs to every connected client.
/// The sheet stays open after a change so the user can preview the live repaint.
///
/// Mirrors the Android `AppearanceSheet` and the structure of the iOS
/// `LayoutSheet` (a sheet of native thumbnails). `ThemeThumbnail` ports the
/// token → region mapping of the web `buildThemeThumb` silhouette.
///
/// - SeeAlso: `AppearanceViewModel`
/// - SeeAlso: `LayoutSheet`
struct AppearanceSheet: View {
    @Bindable var viewModel: AppearanceViewModel

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var hSize

    /// The system dark-mode flag; decides which slot is active while the
    /// appearance is Auto, and which colour scheme the nav bar needs.
    @Environment(\.colorScheme) private var colorScheme

    private let columns = [GridItem(.flexible(), spacing: 12),
                           GridItem(.flexible(), spacing: 12)]

    /// The appearance options shown in the segmented control, in display order.
    private let appearanceOptions: [(label: String, value: Client.Appearance)] = [
        ("Auto", .auto_), ("Light", .light), ("Dark", .dark),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        // Subheading, styled identically to the "Dark themes" /
                        // "Light themes" section headers.
                        Text("Dark mode")
                            .font(.headline)
                            .fontWeight(.semibold)
                            .foregroundStyle(Palette.textBright)
                        appearancePicker
                    }
                    themeSection(title: "Dark themes", themes: viewModel.darkThemes)
                    themeSection(title: "Light themes", themes: viewModel.lightThemes)
                }
                .padding(16)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Palette.background)
            .navigationTitle("Appearance")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Palette.background, for: .navigationBar)
            // The bar's content scheme must follow the *theme's* surface, not a
            // hard-coded `.dark` — otherwise the title renders white-on-light on
            // light themes (issue #95).
            .toolbarColorScheme(
                Palette.backgroundIsDark(systemIsDark: colorScheme == .dark) ? .dark : .light,
                for: .navigationBar
            )
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(Palette.headerAccent)
                }
            }
        }
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
        .presentationDetents(hSize.pick([.medium, .large], [.large]))
        // Scroll the theme grid in place instead of letting a scroll gesture
        // expand the sheet to `.large` — so the sheet stays at the medium detent
        // and the live theme preview behind it remains visible while browsing.
        // The grabber still resizes the sheet manually.
        .presentationContentInteraction(.scrolls)
    }

    /// The Auto / Light / Dark segmented control.
    private var appearancePicker: some View {
        HStack(spacing: 8) {
            ForEach(appearanceOptions, id: \.label) { option in
                let selected = viewModel.appearance == option.value
                Button {
                    viewModel.setAppearance(option.value)
                } label: {
                    Text(option.label)
                        .font(.subheadline)
                        .fontWeight(selected ? .semibold : .regular)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(selected ? Palette.headerAccent : Palette.surface)
                        )
                        .foregroundStyle(selected ? Palette.background : Palette.textSecondary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// The theme name assigned to the *active* slot (the one currently painted,
    /// per the appearance preference / system dark flag). Only this card gets
    /// the assigned-theme highlight, matching the Mac/Electron theme manager.
    private var activeThemeName: String {
        viewModel.activeSlotIsDark(systemIsDark: colorScheme == .dark)
            ? viewModel.darkThemeName
            : viewModel.lightThemeName
    }

    /// A titled grid of theme cards. The dark/light sections are just a catalog
    /// grouping: tapping any card assigns that theme to the currently-active
    /// slot (issue #97), like the Mac/Electron theme manager. Skipped when
    /// `themes` is empty.
    @ViewBuilder
    private func themeSection(
        title: String,
        themes: [Client.Theme]
    ) -> some View {
        if !themes.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                // Same header treatment as the sheet's "Dark mode" title.
                Text(title)
                    .font(.headline)
                    .fontWeight(.semibold)
                    .foregroundStyle(Palette.textBright)
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(themes, id: \.name) { theme in
                        ThemeCardView(
                            theme: theme,
                            selected: theme.name == activeThemeName,
                            onTap: {
                                viewModel.setActiveTheme(
                                    name: theme.name,
                                    systemIsDark: colorScheme == .dark
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/// One theme card: the theme name above its ``ThemeThumbnail``. When `selected`
/// the thumbnail is encircled with an accent ring (the assigned-theme highlight).
private struct ThemeCardView: View {
    let theme: Client.Theme
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 4) {
                Text(theme.name)
                    .font(.system(size: 11))
                    .lineLimit(1)
                    .foregroundStyle(selected ? Palette.headerAccent : Palette.textPrimary)
                ThemeThumbnail(resolved: theme.resolve())
                    .aspectRatio(1.5, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(selected ? Palette.headerAccent : Color.clear, lineWidth: 1.5)
                    )
            }
        }
        .buttonStyle(.plain)
    }
}

/// Draws a miniature app silhouette coloured entirely from `resolved` — a tab
/// strip (with one active tab), a sidebar column, a focused pane with a titlebar
/// and a few syntax-coloured code lines, and a bottom accent strip. The token →
/// region mapping mirrors the web `buildThemeThumb` and the Android
/// `ThemeThumbnail`, so a card previews the real app chrome at a glance.
struct ThemeThumbnail: View {
    let resolved: Client.ResolvedTheme

    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height

            func c(_ v: Int64) -> Color { Color(argb: v) }
            func fillRect(_ rect: CGRect, _ color: Color, radius: CGFloat = 0) {
                context.fill(RoundedRectangle(cornerRadius: radius).path(in: rect), with: .color(color))
            }
            func strokeRect(_ rect: CGRect, _ color: Color, radius: CGFloat, width: CGFloat) {
                context.stroke(RoundedRectangle(cornerRadius: radius).path(in: rect), with: .color(color), lineWidth: width)
            }

            // Canvas background.
            fillRect(CGRect(x: 0, y: 0, width: w, height: h), c(resolved.bg))

            // Tab strip.
            let tabH = h * 0.18
            fillRect(CGRect(x: 0, y: 0, width: w, height: tabH), c(resolved.surfaceAlt))
            let tabW = w * 0.22
            let tabPad = w * 0.02
            let tabInsetY = tabH * 0.22
            for i in 0..<2 {
                let x = tabPad + CGFloat(i) * (tabW + tabPad)
                fillRect(CGRect(x: x, y: tabInsetY, width: tabW, height: tabH - tabInsetY * 2),
                         c(resolved.textDim).opacity(0.5), radius: 2)
            }
            let activeX = tabPad + 2 * (tabW + tabPad)
            let activeTab = CGRect(x: activeX, y: tabInsetY, width: tabW, height: tabH - tabInsetY * 2)
            fillRect(activeTab, c(resolved.surface), radius: 2)
            strokeRect(activeTab, c(resolved.accent), radius: 2, width: 1.5)

            // Body region.
            let bodyTop = tabH
            let bodyH = h - tabH

            // Sidebar.
            let sidebarW = w * 0.24
            fillRect(CGRect(x: 0, y: bodyTop, width: sidebarW, height: bodyH), c(resolved.surface))
            let lineH = bodyH * 0.07
            for i in 0..<4 {
                let y = bodyTop + bodyH * 0.12 + CGFloat(i) * (lineH * 2)
                fillRect(CGRect(x: sidebarW * 0.18, y: y, width: sidebarW * 0.64, height: lineH),
                         c(resolved.textDim).opacity(0.7), radius: 1.5)
            }

            // Focused pane.
            let paneX = sidebarW + w * 0.05
            let paneY = bodyTop + bodyH * 0.12
            let paneW = w - paneX - w * 0.06
            let paneH = bodyH * 0.7
            let pane = CGRect(x: paneX, y: paneY, width: paneW, height: paneH)
            fillRect(pane, c(resolved.surface), radius: 3)
            // Titlebar.
            let titleH = paneH * 0.22
            fillRect(CGRect(x: paneX, y: paneY, width: paneW, height: titleH), c(resolved.surfaceAlt), radius: 3)
            fillRect(CGRect(x: paneX + paneW * 0.08, y: paneY + titleH * 0.32, width: paneW * 0.4, height: titleH * 0.36),
                     c(resolved.textBright), radius: 1.5)
            // Syntax-coloured code lines.
            let codeColors = [c(resolved.synKeyword), c(resolved.synString), c(resolved.text), c(resolved.synFunction)]
            let codeLineH = paneH * 0.09
            for i in 0..<4 {
                let y = paneY + titleH + paneH * 0.12 + CGFloat(i) * (codeLineH * 1.7)
                let lineW = paneW * (0.7 - CGFloat(i) * 0.1)
                fillRect(CGRect(x: paneX + paneW * 0.08, y: y, width: lineW, height: codeLineH),
                         codeColors[i], radius: 1.5)
            }
            // Accent focus ring.
            strokeRect(pane, c(resolved.accent), radius: 3, width: 1.5)

            // Bottom accent strip.
            fillRect(CGRect(x: 0, y: h - h * 0.05, width: w, height: h * 0.05), c(resolved.accent))
        }
    }
}
