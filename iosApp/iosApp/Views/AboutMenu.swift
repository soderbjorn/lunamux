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
    var body: some View {
        Menu {
            AboutMenuItems()
        } label: {
            Image(systemName: "info.circle")
                // Same tint as every other bar action — mixed per-icon tints
                // read as a bug on light themes (issue #96).
                .foregroundStyle(Palette.textPrimary)
        }
        .accessibilityLabel("About & links")
    }
}

/// The app's external-link rows (support forum, website, GitHub, App Store, and
/// the legal pages) as bare menu content.
///
/// Factored out of `AboutMenu` so the same links can be embedded inside a *host*
/// menu without duplication — the Sessions (`TreeView`) toolbar folds these
/// links into its combined overflow ("ellipsis") menu rather than exposing a
/// separate info button, while `AboutMenu` still wraps them in a standalone
/// info-button-plus-menu for the Hosts (`HostsView`) toolbar. Keeping the rows
/// in one view means the two entry points can never drift on which links they
/// offer. Must be placed inside a `Menu` (or another menu content builder).
///
/// The URLs are read from the shared Kotlin `LUNAMUX_*_URL` constants (in
/// `client`'s `ExternalLinks.kt`) via the generated `ExternalLinksKt` accessor,
/// so every platform points at one canonical source and no URL is hardcoded.
///
/// - SeeAlso: `AboutMenu`
/// - SeeAlso: `TreeView`
struct AboutMenuItems: View {
    /// The user manual (the site's Docs tab). Read from the shared Kotlin
    /// `LUNAMUX_DOCS_URL` constant.
    private let docsURL = URL(string: ExternalLinksKt.LUNAMUX_DOCS_URL)!

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
        Link(destination: docsURL) {
            Label("Documentation", systemImage: "book")
        }
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
    }
}
