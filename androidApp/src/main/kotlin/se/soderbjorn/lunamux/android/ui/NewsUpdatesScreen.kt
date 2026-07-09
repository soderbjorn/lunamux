/**
 * "News & Updates" screen for the Lunamux Android app.
 *
 * Reached from the always-visible bell icon in the Hosts/Sessions collapsing top
 * bars (muted when there is nothing new). Mirrors those screens' collapsing
 * [LargeTopAppBar] styling and lists two sections:
 *
 *  - **New update** — shown only when an update is available: the new version's
 *    text and a Download button opening the manifest URL in the browser; swiping
 *    it to either side dismisses the update ("seen, don't remind me") until a
 *    newer version ships.
 *  - **News** — each active, undismissed news item as a card; swiping a card to
 *    either side dismisses it (persisted via the shared view-model so it never
 *    reappears).
 *
 * When nothing remains, a "You're all caught up" placeholder is shown. A
 * **Restore** button (in both the populated list and the empty state) brings every
 * dismissed news item and update back.
 *
 * All state comes from the app-wide
 * [se.soderbjorn.lunamux.android.net.NewsUpdatesController]; dismissals call
 * [se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel.dismissNews]
 * / [se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel.dismissUpdate]
 * and Restore calls
 * [se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel.restoreAll].
 *
 * @see se.soderbjorn.lunamux.android.net.NewsUpdatesController
 * @see HostsScreen
 * @see TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.lunamux.android.net.NewsUpdatesController
import se.soderbjorn.lunamux.client.news.NewsItem

/**
 * Render the "News & Updates" screen.
 *
 * @param onBack invoked when the toolbar back arrow is tapped (pops the nav
 *   stack back to the originating Hosts/Sessions screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsUpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val viewModel = remember { NewsUpdatesController.ensureStarted(context) }
    val state by viewModel.stateFlow.collectAsState()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SidebarBackground,
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SidebarTextSecondary,
                        )
                    }
                },
                // Brightest token so the screen heading reads as crisp white on
                // dark themes, matching the web "News & Updates" modal title.
                title = { Text("News & Updates", color = SidebarTextBright) },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SidebarBackground,
                    scrolledContainerColor = SidebarBackground,
                    titleContentColor = SidebarTextBright,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val updateUrl = state.infoUrl
        val hasUpdate = state.updateAvailable && updateUrl != null
        val news = state.newsItems

        if (!hasUpdate && news.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("You're all caught up", color = SidebarTextSecondary)
                Spacer(Modifier.height(8.dp))
                RestoreButton(onClick = { viewModel.restoreAll() })
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasUpdate) {
                item(key = "update-header") { SectionHeader("New update") }
                item(key = "update-body") {
                    // Swipe either direction to dismiss the update, mirroring the
                    // news cards; persists the dismissed version via the view-model.
                    val updateDismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.StartToEnd ||
                                value == SwipeToDismissBoxValue.EndToStart
                            ) {
                                viewModel.dismissUpdate()
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = updateDismissState,
                        backgroundContent = { DismissBackground(updateDismissState) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SidebarSurface)
                                .border(1.dp, SidebarAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(16.dp),
                        ) {
                            Text(
                                text = state.latestVersionName?.let { "Version $it is available." }
                                    ?: "A new version is available.",
                                color = SidebarTextPrimary,
                                fontSize = 15.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { uriHandler.openUri(updateUrl) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SidebarAccent,
                                    contentColor = SidebarBackground,
                                ),
                            ) {
                                Text("Download")
                            }
                        }
                    }
                }
            }

            if (news.isNotEmpty()) {
                item(key = "news-header") { SectionHeader("News") }
                items(news, key = { it.id }) { item ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.StartToEnd ||
                                value == SwipeToDismissBoxValue.EndToStart
                            ) {
                                viewModel.dismissNews(item.id)
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { DismissBackground(dismissState) },
                    ) {
                        NewsCard(item = item, onLearnMore = { url -> uriHandler.openUri(url) })
                    }
                }
            }

            // Restore footer: always available so dismissed items can be brought
            // back even while some content is still shown.
            item(key = "restore") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RestoreButton(onClick = { viewModel.restoreAll() })
                }
            }
        }
    }
}

/**
 * "Restore news & updates" text button that brings every dismissed news item and
 * update back. Shown both in the populated list and the empty state.
 *
 * @param onClick invoked when tapped; calls
 *   [se.soderbjorn.lunamux.client.newsupdates.NewsUpdatesBackingViewModel.restoreAll].
 */
@Composable
private fun RestoreButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text("Restore news & updates", color = SidebarAccent)
    }
}

/** A bold, padded section heading ("New update" / "News"). */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = SidebarTextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * Reveal shown behind a news/update card while it is being swiped away.
 *
 * Mirrors the iOS news screen's swipe affordance: as the user drags the card to
 * either edge, an accent-tinted wash with a checkmark and a "Dismiss" label is
 * revealed underneath, hugging the edge the card is moving toward. This is the
 * standard Material swipe-to-dismiss background pattern (a labelled icon on a
 * coloured surface), so it stays consistent with platform guidance.
 *
 * Called by the [SwipeToDismissBox]es wrapping the update card and each news
 * card; passed that box's [SwipeToDismissBoxState] so it can align and light up
 * according to the active swipe direction.
 *
 * @param state the swipe state of the card this background sits behind; its
 *   [SwipeToDismissBoxState.dismissDirection] drives the alignment and whether
 *   the affordance is shown.
 * @see NewsUpdatesScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissBackground(state: SwipeToDismissBoxState) {
    val direction = state.dismissDirection
    val active = direction != SwipeToDismissBoxValue.Settled
    // Hug the edge the card is sliding toward, so the affordance appears on the
    // side being uncovered (start when swiping right, end when swiping left).
    val alignment = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) SidebarAccent.copy(alpha = 0.16f) else SidebarBackground)
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        if (active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = SidebarAccent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Dismiss",
                    color = SidebarAccent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * One news card: optional date, title, plain-text body, and an optional "Learn
 * more" button when the item carries a URL.
 *
 * @param item the news item to render.
 * @param onLearnMore invoked with the item's URL when "Learn more" is tapped.
 */
@Composable
private fun NewsCard(item: NewsItem, onLearnMore: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SidebarSurface)
            .border(1.dp, SidebarTextSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        val date = item.date
        if (date != null) {
            Text(text = date, color = SidebarTextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
        }
        // Headline: larger and heavier than the body, with tight line spacing and
        // a touch of negative letter-spacing, so each item reads as its own
        // distinct headline rather than running into the body — mirroring the
        // desktop news cards.
        Text(
            text = item.title,
            color = SidebarTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 23.sp,
            letterSpacing = (-0.2).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(text = item.body, color = SidebarTextPrimary, fontSize = 14.sp)
        val url = item.url
        if (!url.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onLearnMore(url) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SidebarAccent),
                border = BorderStroke(1.dp, SidebarAccent),
            ) {
                Text("Learn more")
            }
        }
    }
}
