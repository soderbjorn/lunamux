/**
 * Read-only git miniature for the overview screen.
 *
 * Shows a compact, non-interactive replica of the git pane's first screen (the
 * changed-files list reached from the session list): files grouped under
 * uppercase directory headers, each row carrying the **same coloured Material
 * status badge** the full-screen [GitListScreen] draws (only smaller) — so the
 * thumbnail reads as a true miniature of the screen it drills into. It reuses
 * the shared [se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel]
 * (same status data), plus [GitListScreen]'s [StatusBadge] and
 * [groupByDirectory] (same badges, same grouping).
 *
 * Like the other miniatures ([MiniTerminalPane], [MiniFileBrowserPane]) it
 * carries no in-content header: the enclosing mini-pane's title bar already
 * stands in for the full screen's top app bar, so the content area mirrors the
 * real list's content area.
 *
 * Non-interactive by design: the overview's whole-pane tap overlay drills into
 * the full [GitListScreen], so individual rows here do nothing.
 *
 * @see OverviewContent
 * @see GitListScreen
 * @see se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.client.viewmodel.GitPaneBackingViewModel

/** Max rows (headers + files) the miniature lists before it clips to the pane bounds. */
private const val MINI_GIT_ROWS = 14

/** Badge edge length for the miniature's rows — a shrunk [GitListScreen] badge. */
private val MINI_GIT_BADGE = 12.dp

/**
 * Compact git changed-files thumbnail for the pane identified by [paneId].
 *
 * @param paneId   the git leaf's pane id.
 * @param modifier layout modifier from the enclosing mini-pane.
 */
@Composable
fun MiniGitPane(
    paneId: String,
    modifier: Modifier = Modifier,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        Column(modifier.background(SidebarBackground)) {}
        return
    }

    val vm = remember(paneId, windowSocket) { GitPaneBackingViewModel(paneId, windowSocket) }
    LaunchedEffect(vm) { vm.run() }
    val state by vm.stateFlow.collectAsStateWithLifecycle()
    val entries = state.entries.orEmpty()

    Column(
        modifier = modifier
            .background(SidebarBackground)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        if (entries.isEmpty()) {
            Text(
                text = if (state.isLoading) "…" else "No changes",
                color = SidebarTextSecondary,
                fontSize = 10.sp,
            )
            return@Column
        }

        // Same directory grouping the full screen uses, capped to a row budget
        // (headers included) so the thumbnail clips cleanly to the pane bounds.
        val groups = remember(entries) { groupByDirectory(entries) }
        var budget = MINI_GIT_ROWS
        for ((directory, files) in groups) {
            if (budget <= 0) break
            MiniGitSectionHeader(directory)
            budget--
            for (entry in files) {
                if (budget <= 0) break
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Same coloured badge the full screen draws, just smaller (issue #45).
                    StatusBadge(entry.status, size = MINI_GIT_BADGE)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = entry.filePath.substringAfterLast('/'),
                        color = SidebarTextPrimary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                budget--
            }
        }
    }
}

/**
 * Compact uppercase directory header for the miniature, mirroring
 * [GitListScreen]'s section header (empty directory renders as "ROOT") but sized
 * for the thumbnail.
 *
 * @param directory the directory path to display; empty string becomes "ROOT".
 */
@Composable
private fun MiniGitSectionHeader(directory: String) {
    Text(
        text = if (directory.isEmpty()) "ROOT" else directory.uppercase(),
        color = SidebarTextSecondary,
        fontSize = 8.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp, bottom = 1.dp),
    )
}
