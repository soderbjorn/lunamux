//
//  AboutMenu.swift
//  iosApp
//
//  Top-bar "About & links" overflow menu for the Lunamux iOS app.
//
//  Defines `AboutMenu`, the single info-button-plus-menu control shared by the
//  Hosts and Sessions toolbars. It gathers the app's external links (community
//  support forum, marketing website, and the legal pages) into one consistent
//  place on every primary screen, rather than duplicating them inline (and
//  risking drift) per screen. Mirrors the Android `AboutMenu` composable.
//

import SwiftUI
import Client

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
/// composable; the URLs are read from the shared Kotlin `LUNAMUX_*_URL`
/// constants (in `client`'s `ExternalLinks.kt`) via the generated
/// `ExternalLinksKt` accessor, so every platform points at one canonical
/// source and no URL is hardcoded here.
struct AboutMenu: View {
    /// GitHub Discussions board used as the community support forum. Read from
    /// the shared Kotlin `LUNAMUX_DISCUSSIONS_URL` constant.
    private let discussionsURL = URL(string: ExternalLinksKt.LUNAMUX_DISCUSSIONS_URL)!

    /// Canonical marketing / download site. Read from the shared Kotlin
    /// `LUNAMUX_SITE_URL` constant.
    private let siteURL = URL(string: ExternalLinksKt.LUNAMUX_SITE_URL)!

    /// Public GitHub repository, offered as a "Star on GitHub" action. Read from
    /// the shared Kotlin `LUNAMUX_GITHUB_URL` constant.
    private let gitHubURL = URL(string: ExternalLinksKt.LUNAMUX_GITHUB_URL)!

    /// Apple App Store listing, offered as a "Rate on the App Store" action.
    /// Read from the shared Kotlin `LUNAMUX_APP_STORE_URL` constant.
    private let appStoreURL = URL(string: ExternalLinksKt.LUNAMUX_APP_STORE_URL)!

    /// Published privacy policy page. Read from the shared Kotlin
    /// `LUNAMUX_PRIVACY_URL` constant.
    private let privacyURL = URL(string: ExternalLinksKt.LUNAMUX_PRIVACY_URL)!

    /// Published terms of service page. Read from the shared Kotlin
    /// `LUNAMUX_TERMS_URL` constant.
    private let termsURL = URL(string: ExternalLinksKt.LUNAMUX_TERMS_URL)!

    var body: some View {
        Menu {
            Link(destination: discussionsURL) {
                Label("Support Forum", systemImage: "questionmark.circle")
            }
            Link(destination: siteURL) {
                Label("Website", systemImage: "globe")
            }
            Link(destination: gitHubURL) {
                Label("Star on GitHub", systemImage: "star")
            }
            Link(destination: appStoreURL) {
                Label("Rate on the App Store", systemImage: "hand.thumbsup")
            }
            // Separate the actionable/engagement links above from the legal
            // boilerplate below.
            Divider()
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
