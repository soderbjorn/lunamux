/**
 * First-launch onboarding walkthrough for the Termtastic Android app.
 *
 * Renders the shared [OnboardingViewModel]'s pages in a swipeable
 * [HorizontalPager] with page-dot indicators and Back / Next buttons, finishing
 * with a "Get Started" button on the last page. All copy, page order, and the
 * Mac-app download URL come from the shared client module so Android and iOS
 * stay in lock-step; this file only maps the semantic [OnboardingIcon] to a
 * Material glyph and turns [OnboardingPage.linkUrl] into a tappable link.
 *
 * Colours come from the [SidebarAccent] / [SidebarBackground] palette, which —
 * with no server connection yet — falls back to the app's default Neon Green
 * theme, so the walkthrough matches the rest of the (pre-connection) UI rather
 * than the bare Material accent.
 *
 * Shown by [TermtasticApp] exactly once — before the host list — when the shared
 * [se.soderbjorn.termtastic.client.storage.LocalRepository]'s `onboardingSeen`
 * flag is still false.
 *
 * @see TermtasticApp
 * @see OnboardingViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.client.viewmodel.OnboardingIcon
import se.soderbjorn.termtastic.client.viewmodel.OnboardingPage
import se.soderbjorn.termtastic.client.viewmodel.OnboardingViewModel

/**
 * Duration, in milliseconds, of the Back / Next button page transition.
 *
 * The pager's default `animateScrollToPage` spring settles almost instantly,
 * which read as the page "snapping" past the user; a fixed [tween] of this
 * length gives the tap-driven slide a deliberate, swipe-like feel.
 */
private const val ONBOARDING_PAGE_ANIM_MILLIS = 450

/**
 * Full-screen, first-launch onboarding walkthrough.
 *
 * Hosts a [HorizontalPager] over the shared [OnboardingViewModel] pages. Tapping
 * Back / Next advances the view-model and animates the pager to match (so the
 * transition slides rather than jumps), while a swipe settles the view-model's
 * page via [OnboardingViewModel.goTo]. The primary button reads "Get Started"
 * on the last page and invokes [onFinish]; a "Skip" action in the top corner
 * finishes immediately.
 *
 * @param onFinish invoked when the user completes or skips the walkthrough;
 *   [TermtasticApp] uses this to persist the "seen" flag and reveal the host
 *   list.
 * @see TermtasticApp
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val viewModel = remember { OnboardingViewModel() }
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { state.pageCount })
    val scope = rememberCoroutineScope()

    // Swipe → view-model: when the pager settles on a page, mirror it into the
    // shared view-model so `state.isFirstPage` / `isLastPage` (which drive the
    // button labels) follow the gesture.
    LaunchedEffect(pagerState.currentPage) {
        viewModel.goTo(pagerState.currentPage)
    }

    Column(modifier = Modifier.fillMaxSize().background(SidebarBackground)) {
        // Top "Skip" affordance — first-launch only, so a quick way out.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(
                onClick = onFinish,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text("Skip")
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            OnboardingPageContent(state.pages[page])
        }

        PageIndicator(
            pageCount = state.pageCount,
            currentPage = state.pageIndex,
            modifier = Modifier.padding(vertical = 16.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Back" only matters past the first page; keep the slot otherwise
            // empty so "Next"/"Get Started" stays pinned to the trailing edge.
            if (!state.isFirstPage) {
                TextButton(
                    onClick = {
                        viewModel.previous()
                        scope.launch {
                            pagerState.animateScrollToPage(
                                viewModel.stateFlow.value.pageIndex,
                                animationSpec = tween(durationMillis = ONBOARDING_PAGE_ANIM_MILLIS),
                            )
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
                ) {
                    Text("Back")
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }
            Button(
                onClick = {
                    if (state.isLastPage) {
                        onFinish()
                    } else {
                        viewModel.next()
                        scope.launch {
                            pagerState.animateScrollToPage(
                                viewModel.stateFlow.value.pageIndex,
                                animationSpec = tween(durationMillis = ONBOARDING_PAGE_ANIM_MILLIS),
                            )
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SidebarAccent,
                    contentColor = SidebarBackground,
                ),
            ) {
                Text(if (state.isLastPage) "Get Started" else "Next")
            }
        }
    }
}

/**
 * Renders a single onboarding page: its glyph, title, body paragraph, and — on
 * the download page — a tappable link that opens [OnboardingPage.linkUrl] in
 * the system browser. Colours follow the Neon Green palette accessors.
 *
 * @param page the page to render.
 */
@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = iconFor(page.icon),
            contentDescription = null,
            tint = SidebarAccent,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SidebarTextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = SidebarTextSecondary,
            textAlign = TextAlign.Center,
        )
        if (page.linkUrl != null && page.linkLabel != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { uriHandler.openUri(page.linkUrl!!) }) {
                Text(
                    text = page.linkLabel!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = SidebarAccent,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

/**
 * Row of page-dot indicators; the active dot uses the Neon Green accent and the
 * rest a muted variant of the secondary text colour.
 *
 * @param pageCount total number of pages.
 * @param currentPage the active page's zero-based index.
 * @param modifier layout modifier applied to the indicator row.
 */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val accent = SidebarAccent
    val inactive = SidebarTextSecondary.copy(alpha = 0.3f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (active) accent else inactive),
            )
        }
    }
}

/**
 * Maps a shared, platform-agnostic [OnboardingIcon] to a Material glyph.
 *
 * @param icon the semantic icon named by the shared page.
 * @return the Material [ImageVector] to render.
 */
private fun iconFor(icon: OnboardingIcon): ImageVector = when (icon) {
    OnboardingIcon.WELCOME -> Icons.Outlined.WavingHand
    OnboardingIcon.WORKSPACE -> Icons.Outlined.Dashboard
    OnboardingIcon.DESKTOP -> Icons.Outlined.DesktopMac
}
