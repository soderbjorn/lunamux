//
//  AboutMenu.swift
//  iosApp
//
//  Top-bar "About & links" overflow menu for the Termtastic iOS app.
//
//  Defines `AboutMenu`, the single info-button-plus-menu control shared by the
//  Hosts and Sessions toolbars. It gathers the app's external links (community
//  support forum, marketing website, and the legal pages) into one consistent
//  place on every primary screen, rather than duplicating them inline (and
//  risking drift) per screen. Mirrors the Android `AboutMenu` composable.
//

import SwiftUI

/// An info-icon toolbar button that reveals the app's external links.
///
/// Placed in the trailing toolbar of both the Hosts (`HostsView`) and Sessions
/// (`TreeView`) screens, it gives users a single, always-reachable entry point
/// to the support forum, the website, and the legal pages from either primary
/// screen. Tapping the icon opens a `Menu`; each row opens its URL in the
/// browser.
///
/// Keeping this a single view (rather than inline per screen) means the two
/// toolbars can never drift out of sync on which links they offer, and any new
/// link is added in exactly one place. Mirrors the Android `AboutMenu`
/// composable; the URLs mirror the shared Kotlin `TERMTASTIC_*_URL` constants.
struct AboutMenu: View {
    /// GitHub Discussions board used as the community support forum. Mirrors the
    /// shared Kotlin `TERMTASTIC_DISCUSSIONS_URL` constant.
    private let discussionsURL = URL(string: "https://github.com/soderbjorn/termtastic/discussions")!

    /// Canonical marketing / download site. Mirrors the shared Kotlin
    /// `TERMTASTIC_SITE_URL` constant.
    private let siteURL = URL(string: "https://termtastic.soderbjorn.se")!

    /// Published privacy policy page. Mirrors the shared Kotlin
    /// `TERMTASTIC_PRIVACY_URL` constant.
    private let privacyURL = URL(string: "https://termtastic.soderbjorn.se/privacy.html")!

    /// Published terms of service page. Mirrors the shared Kotlin
    /// `TERMTASTIC_TERMS_URL` constant.
    private let termsURL = URL(string: "https://termtastic.soderbjorn.se/terms.html")!

    var body: some View {
        Menu {
            Link(destination: discussionsURL) {
                Label("Support Forum", systemImage: "questionmark.circle")
            }
            Link(destination: siteURL) {
                Label("Website", systemImage: "globe")
            }
            Link(destination: privacyURL) {
                Label("Privacy Policy", systemImage: "lock")
            }
            Link(destination: termsURL) {
                Label("Terms", systemImage: "doc.text")
            }
        } label: {
            Image(systemName: "info.circle")
                // Same tint as every other bar action — mixed per-icon tints
                // read as a bug on light themes (issue #96).
                .foregroundStyle(Palette.textPrimary)
        }
        .accessibilityLabel("About & links")
    }
}
