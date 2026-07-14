/**
 * Canonical external-link URLs surfaced across every Lunamux client.
 *
 * All of the app's outward-facing links — the marketing/download site, the user
 * manual, the published legal pages, the community support forum, the GitHub
 * repository, and the two app-store listings — live here as shared `const val`s in the common
 * client module rather than being hardcoded per platform. This is the single
 * source of truth: the Android top-bar [se.soderbjorn.lunamux.android.ui.AboutMenu]
 * imports these directly, the iOS `AboutMenu` reads them via the generated
 * `ExternalLinksKt` accessor, and the desktop (Electron) Help menu / About
 * dialog resolve them the same way — so no client can ever drift onto a stale or
 * mismatched URL.
 *
 * @see se.soderbjorn.lunamux.client.viewmodel.defaultOnboardingPages
 */
package se.soderbjorn.lunamux.client.viewmodel

/**
 * The canonical landing page for the desktop app + server download, shown on
 * the final onboarding page and opened when the user taps its link.
 *
 * Kept as a single shared constant so the Android, iOS, and desktop clients
 * (and any marketing copy that references it) can never point at different URLs.
 */
const val LUNAMUX_SITE_URL: String = "https://www.lunamux.dev"

/**
 * The published privacy policy page, linked from the bottom of the hosts
 * screen (next to the demo footer) on Android and iOS and from the desktop
 * app's menu.
 *
 * Kept as a shared constant alongside [LUNAMUX_SITE_URL] so every client
 * that surfaces the privacy policy points at the same canonical URL.
 */
const val LUNAMUX_PRIVACY_URL: String = "https://www.lunamux.dev/privacy.html"

/**
 * The published terms of service page, linked from the bottom of the hosts
 * screen (next to the privacy policy) on Android and iOS and from the desktop
 * app's menu.
 *
 * Kept as a shared constant alongside [LUNAMUX_PRIVACY_URL] so every client
 * that surfaces the terms points at the same canonical URL.
 */
const val LUNAMUX_TERMS_URL: String = "https://www.lunamux.dev/terms.html"

/**
 * The user manual (the site's Docs tab), surfaced as a "Documentation" link in
 * the top-bar info menu on Android and in the desktop Help menu / About dialog.
 *
 * Kept as a shared constant alongside [LUNAMUX_SITE_URL] so every client points
 * at the same manual. Note the hash route: the site is a single page whose
 * router reads `#/docs`, so the fragment is load-bearing rather than an anchor
 * — dropping it lands the user on the marketing home page instead.
 */
const val LUNAMUX_DOCS_URL: String = "https://lunamux.dev/#/docs"

/**
 * The community support forum, surfaced as a prominent "Support Forum" link in
 * the top-bar info menu on Android and iOS and in the desktop Help menu.
 *
 * Kept as a shared constant alongside [LUNAMUX_SITE_URL] so every client
 * that offers help points users at the same canonical destination.
 */
const val LUNAMUX_DISCUSSIONS_URL: String = "https://lunamux.dev/#/discuss"

/**
 * The public GitHub repository, surfaced as a "Star on GitHub" link in the
 * top-bar info menu on Android and iOS and in the desktop Help menu so users
 * can star the project.
 *
 * Kept as a shared constant alongside [LUNAMUX_DISCUSSIONS_URL] so every
 * client points at the same repository.
 */
const val LUNAMUX_GITHUB_URL: String = "https://github.com/soderbjorn/lunamux"

/**
 * The Google Play store listing, surfaced as a "Rate on Google Play" link in
 * the Android top-bar info menu (the iOS client uses [LUNAMUX_APP_STORE_URL]
 * instead).
 *
 * Kept as a shared constant alongside [LUNAMUX_APP_STORE_URL] so the store
 * URLs live with the other canonical links.
 */
const val LUNAMUX_PLAY_STORE_URL: String =
    "https://play.google.com/store/apps/details?id=se.soderbjorn.termtastic.android"

/**
 * The Apple App Store listing, surfaced as a "Rate on the App Store" link in
 * the iOS top-bar info menu (the Android client uses [LUNAMUX_PLAY_STORE_URL]
 * instead).
 *
 * Kept as a shared constant alongside [LUNAMUX_PLAY_STORE_URL] so the store
 * URLs live with the other canonical links.
 */
const val LUNAMUX_APP_STORE_URL: String = "https://apps.apple.com/app/id6780234087"
