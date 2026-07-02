/**
 * Shared backing view-model and content for the first-launch onboarding flow.
 *
 * The Android ([se.soderbjorn.termtastic.android.ui.OnboardingScreen]) and iOS
 * (`OnboardingView`) clients both render an introductory, paged walkthrough the
 * very first time the app is launched. So the two platforms never drift in
 * wording, page order, or the download URL, all of that *content* lives here in
 * the shared client module rather than being duplicated in Kotlin and Swift.
 *
 * [OnboardingViewModel] owns the paging state (which page is showing, whether
 * we are on the first/last page) behind a [StateFlow], mirroring the
 * [se.soderbjorn.termtastic.client.viewmodel.TerminalBackingViewModel] pattern
 * used elsewhere in this package. The pages themselves are plain immutable
 * [OnboardingPage] data, produced by [defaultOnboardingPages]; each platform
 * maps the semantic [OnboardingIcon] to its own native glyph (SF Symbols on
 * iOS, Material icons on Android) and turns [OnboardingPage.linkUrl] into a
 * tappable link.
 *
 * Whether onboarding has already been seen is *not* tracked here — that is a
 * per-device flag persisted by each platform (DataStore on Android,
 * UserDefaults on iOS), since the shared client holds no on-device storage of
 * its own.
 *
 * @see defaultOnboardingPages
 * @see OnboardingPage
 */
package se.soderbjorn.termtastic.client.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The canonical landing page for the desktop app + server download, shown on
 * the final onboarding page and opened when the user taps its link.
 *
 * Kept as a single shared constant so the Android and iOS clients (and any
 * marketing copy that references it) can never point at different URLs.
 */
const val TERMTASTIC_SITE_URL: String = "https://termtastic.soderbjorn.se"

/**
 * The published privacy policy page, linked from the bottom of the hosts
 * screen (next to the demo footer) on Android and iOS and from the desktop
 * app's menu.
 *
 * Kept as a shared constant alongside [TERMTASTIC_SITE_URL] so every client
 * that surfaces the privacy policy points at the same canonical URL.
 */
const val TERMTASTIC_PRIVACY_URL: String = "https://termtastic.soderbjorn.se/privacy.html"

/**
 * The published terms of service page, linked from the bottom of the hosts
 * screen (next to the privacy policy) on Android and iOS and from the desktop
 * app's menu.
 *
 * Kept as a shared constant alongside [TERMTASTIC_PRIVACY_URL] so every client
 * that surfaces the terms points at the same canonical URL.
 */
const val TERMTASTIC_TERMS_URL: String = "https://termtastic.soderbjorn.se/terms.html"

/**
 * The GitHub Discussions board used as the app's community support forum,
 * surfaced as a prominent "Support Forum" link at the bottom of the hosts
 * screen on Android and iOS.
 *
 * Kept as a shared constant alongside [TERMTASTIC_SITE_URL] so every client
 * that offers help points users at the same canonical destination.
 */
const val TERMTASTIC_DISCUSSIONS_URL: String = "https://github.com/soderbjorn/termtastic/discussions"

/**
 * Semantic icon for an [OnboardingPage], decoupled from any platform's icon
 * set.
 *
 * The shared module cannot reference SF Symbols or Material icons, so each page
 * names the *kind* of illustration it wants and the platform onboarding screen
 * maps it to a concrete glyph. Keeping this an enum (rather than a raw symbol
 * name) means a missing mapping is a compile-time `when`/`switch` gap on each
 * platform rather than a silently blank icon.
 *
 * @see OnboardingPage.icon
 */
enum class OnboardingIcon {
    /** A friendly greeting glyph for the introductory page. */
    WELCOME,

    /** A workspace / multi-pane glyph for the capabilities page. */
    WORKSPACE,

    /** A desktop-computer glyph for the "download the Mac app" page. */
    DESKTOP,
}

/**
 * One screen of the onboarding walkthrough.
 *
 * @property id        stable identifier, used by the platforms as a list/pager
 *   key so page state survives recomposition.
 * @property icon      the semantic illustration to show; mapped to a native
 *   glyph by each platform.
 * @property title     short headline for the page.
 * @property body      one- or two-sentence explanatory paragraph.
 * @property linkLabel optional user-visible label for a tappable link (e.g. the
 *   bare domain). Non-null only on pages that carry a [linkUrl].
 * @property linkUrl   optional URL opened when the link is tapped; `null` on
 *   pages with no link.
 */
data class OnboardingPage(
    val id: String,
    val icon: OnboardingIcon,
    val title: String,
    val body: String,
    val linkLabel: String? = null,
    val linkUrl: String? = null,
)

/**
 * The default three-page onboarding walkthrough shown on first launch.
 *
 * Page order is significant — it goes from *what the app is* → *what it can do*
 * → *the one thing the user must do next* (install the Mac app that hosts the
 * server). The final page is the only one with a link, pointing at
 * [TERMTASTIC_SITE_URL].
 *
 * Exposed as a top-level function so Swift call sites can reach it via the
 * `OnboardingViewModelKt` file facade and so tests can assert on the copy
 * without constructing a view-model.
 *
 * @return the ordered list of onboarding pages.
 */
fun defaultOnboardingPages(): List<OnboardingPage> = listOf(
    OnboardingPage(
        id = "welcome",
        icon = OnboardingIcon.WELCOME,
        title = "Welcome to Termtastic",
        body = "Termtastic puts your computer's terminal in your pocket. Securely " +
            "connect to your desktop and pick up your work right where you left " +
            "off — from anywhere.",
    ),
    OnboardingPage(
        id = "workspace",
        icon = OnboardingIcon.WORKSPACE,
        title = "Your whole workspace",
        body = "Drive full terminal sessions, browse files, and review Git diffs " +
            "— all kept in sync with your desktop in real time.",
    ),
    OnboardingPage(
        id = "desktop",
        icon = OnboardingIcon.DESKTOP,
        title = "Get the Mac app",
        body = "Termtastic connects to a lightweight server that runs inside the " +
            "Termtastic Mac app. Install it on your Mac to start hosting sessions " +
            "this app can join.",
        linkLabel = TERMTASTIC_SITE_URL,
        linkUrl = TERMTASTIC_SITE_URL,
    ),
)

/**
 * Backing view-model that holds the onboarding pages and the current page
 * position, exposing both through a single immutable [State] over a
 * [StateFlow].
 *
 * Constructed by each platform's onboarding screen (Android's
 * `OnboardingScreen`, iOS's `OnboardingViewModel` wrapper) at the moment the
 * walkthrough is shown. Navigation actions ([next], [previous], [goTo]) mutate
 * the position and clamp to valid bounds, so the platform UI can wire "Back" /
 * "Next" buttons and a swipeable pager to the same source of truth without
 * risking an out-of-range page.
 *
 * @param pages the walkthrough pages to present; defaults to
 *   [defaultOnboardingPages]. Injectable mainly for tests.
 * @see defaultOnboardingPages
 */
class OnboardingViewModel(
    pages: List<OnboardingPage> = defaultOnboardingPages(),
) {
    private val _stateFlow = MutableStateFlow(State(pages = pages))

    /** The current onboarding state, observed by both platform UIs. */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * Immutable snapshot of the onboarding flow.
     *
     * @property pages     the full, ordered list of pages.
     * @property pageIndex the zero-based index of the page currently shown.
     */
    data class State(
        val pages: List<OnboardingPage>,
        val pageIndex: Int = 0,
    ) {
        /** Total number of pages, convenient for a page-dot indicator. */
        val pageCount: Int get() = pages.size

        /** The page currently being shown. */
        val currentPage: OnboardingPage get() = pages[pageIndex]

        /** True when the first page is showing (so "Back" can be hidden). */
        val isFirstPage: Boolean get() = pageIndex == 0

        /**
         * True when the last page is showing, so the primary button can read
         * "Get Started" (and finish onboarding) instead of "Next".
         */
        val isLastPage: Boolean get() = pageIndex >= pages.lastIndex
    }

    /**
     * Advance to the next page, or do nothing if already on the last page.
     *
     * Called by the platform UI's "Next" button and by a forward swipe.
     */
    fun next() {
        goTo(_stateFlow.value.pageIndex + 1)
    }

    /**
     * Return to the previous page, or do nothing if already on the first page.
     *
     * Called by the platform UI's "Back" button and by a backward swipe.
     */
    fun previous() {
        goTo(_stateFlow.value.pageIndex - 1)
    }

    /**
     * Jump directly to [index], clamped into the valid page range.
     *
     * Called when a swipeable pager settles on a page so the view-model's
     * position follows the gesture, and indirectly by [next] / [previous].
     *
     * @param index the requested zero-based page index; values outside
     *   `0..lastIndex` are clamped.
     */
    fun goTo(index: Int) {
        val current = _stateFlow.value
        val clamped = index.coerceIn(0, current.pages.lastIndex)
        if (clamped != current.pageIndex) {
            _stateFlow.value = current.copy(pageIndex = clamped)
        }
    }
}
