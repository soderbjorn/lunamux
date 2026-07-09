/**
 * Git changed-files list screen for the Lunamux Android app.
 *
 * Queries the server for the current `git status` of the connected project
 * and displays changed files grouped by directory, each with a coloured
 * icon badge indicating file status (modified/added/deleted/renamed/untracked). Tapping a file navigates to
 * [GitDiffScreen] to show the unified diff.
 *
 * @see GitDiffScreen
 * @see se.soderbjorn.lunamux.android.ui.TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.lunamux.GitFileEntry
import se.soderbjorn.lunamux.GitFileStatus
import se.soderbjorn.lunamux.android.net.ConnectionHolder

// Palette tokens live in SidebarPalette.kt — shared with TreeScreen.

/**
 * Displays the list of changed files from `git status`, grouped by directory.
 *
 * Each file row shows a coloured status badge (M/A/D/R/U) and the file name.
 * Tapping a file navigates to [GitDiffScreen] to view its unified diff.
 *
 * @param paneId the server-side pane identifier owning the git session.
 * @param onOpenFile callback invoked with the file path when the user taps a file.
 * @param onBack callback invoked when the user navigates back.
 * @see GitDiffScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitListScreen(
    paneId: String,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var entries by remember { mutableStateOf<List<GitFileEntry>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paneId) {
        val list = runCatching { windowSocket.gitList(paneId) }.getOrNull()
        entries = list ?: emptyList()
        if (list == null) errorMessage = "Failed to load git status"
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Leading pane-type icon (issue #48) — the same glyph the
                        // session list draws before each pane title, keeping the
                        // full-screen header consistent with the list. This screen
                        // only ever hosts git panes (never floating windows).
                        PaneIcon(kind = LeafKind.GIT, floating = false)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Git",
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = SidebarTextPrimary,
                            ),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SidebarTextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SidebarSurface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SidebarBackground),
        ) {
            val list = entries
            when {
                list == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = SidebarTextSecondary)
                }
                list.isEmpty() -> Text(
                    errorMessage ?: "No changed files",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = SidebarTextSecondary,
                    ),
                    modifier = Modifier.padding(16.dp),
                )
                else -> {
                    val groups = remember(list) { groupByDirectory(list) }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groups.forEach { (directory, items) ->
                            item(key = "section:$directory") {
                                GitSectionHeader(directory)
                            }
                            items(items, key = { "file:${it.filePath}" }) { entry ->
                                GitFileRow(entry) { onOpenFile(entry.filePath) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Section header row that displays a directory name in uppercase.
 *
 * Used to group changed files by their parent directory in [GitListScreen].
 *
 * @param directory the directory path to display; empty string becomes "(ROOT)".
 */
@Composable
private fun GitSectionHeader(directory: String) {
    Text(
        text = if (directory.isEmpty()) "ROOT" else directory.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            color = SidebarTextSecondary,
            letterSpacing = 0.5.sp,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * A single row in the git changed-files list showing the file name, status badge, and path.
 *
 * @param entry the git file entry to render.
 * @param onClick callback invoked when the row is tapped to view the diff.
 */
@Composable
private fun GitFileRow(entry: GitFileEntry, onClick: () -> Unit) {
    val fileName = entry.filePath.substringAfterLast('/')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "$fileName (${entry.status.name})"
            }
            .padding(start = 32.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadge(entry.status)
        Spacer(Modifier.width(8.dp))
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SidebarTextPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = entry.filePath,
            style = MaterialTheme.typography.labelSmall.copy(
                color = SidebarTextSecondary.copy(alpha = 0.6f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Colored icon badge indicating git status.
 *
 * Uses Material icons to convey file status at a glance:
 * pencil = modified, plus = added, trash = deleted,
 * swap = renamed, help = untracked.
 *
 * `internal` and size-parameterised so the overview's [MiniGitPane] can render
 * the exact same badge at a smaller size, keeping the thumbnail a true
 * miniature of this screen.
 *
 * @param status the file's git status.
 * @param size the badge icon's square edge length; defaults to the list-screen 16dp.
 * @see MiniGitPane
 */
@Composable
internal fun StatusBadge(status: GitFileStatus, size: Dp = 16.dp) {
    val pair: Pair<ImageVector, Color> = when (status) {
        GitFileStatus.Modified  -> Icons.Filled.Edit to Color(0xFFFFD60A)
        GitFileStatus.Added     -> Icons.Filled.Add to Color(0xFF32D74B)
        GitFileStatus.Deleted   -> Icons.Filled.Delete to Color(0xFFFF453A)
        GitFileStatus.Renamed   -> Icons.Filled.Refresh to Color(0xFF64D2FF)
        GitFileStatus.Untracked -> Icons.Filled.Add to Color(0xFF8E8E93)
    }
    val (icon, color) = pair
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = color,
        modifier = Modifier.size(size),
    )
}

/**
 * Group entries by their [GitFileEntry.directory] field, preserving order.
 *
 * `internal` so the overview's [MiniGitPane] groups changed files identically
 * to this screen.
 *
 * @see MiniGitPane
 */
internal fun groupByDirectory(entries: List<GitFileEntry>): LinkedHashMap<String, MutableList<GitFileEntry>> {
    val groups = LinkedHashMap<String, MutableList<GitFileEntry>>()
    for (entry in entries) {
        groups.getOrPut(entry.directory) { mutableListOf() }.add(entry)
    }
    return groups
}
