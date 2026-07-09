/**
 * Directory listing screen for the Lunamux file browser.
 *
 * Displays the contents of a single directory as a scrollable list with
 * directories shown before files. Tapping a directory pushes another
 * [FileBrowserListScreen]; tapping a file opens [FileBrowserContentScreen].
 * Each directory navigation is also reported to the server so the web
 * client's tree expansion stays in sync.
 *
 * @see FileBrowserContentScreen
 * @see se.soderbjorn.lunamux.android.ui.TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.soderbjorn.lunamux.FileBrowserEntry
import se.soderbjorn.lunamux.android.net.ConnectionHolder

/**
 * One screen per directory: lists entries with directories first, then files.
 * Tapping a directory pushes another [FileBrowserListScreen]; tapping a file
 * pushes [FileBrowserContentScreen]. The server is told about every
 * navigated-into folder so the web client's tree reflects the same expansion.
 *
 * @param paneId the server-side pane identifier owning the file browser session.
 * @param dirRelPath relative path of the directory to list (empty string for root).
 * @param onOpenDir callback invoked with a child directory's relative path.
 * @param onOpenFile callback invoked with a file's relative path.
 * @param onBack callback invoked when the user navigates back.
 * @see FileBrowserContentScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserListScreen(
    paneId: String,
    dirRelPath: String,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var entries by remember { mutableStateOf<List<FileBrowserEntry>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paneId, dirRelPath) {
        val list = runCatching { windowSocket.fileBrowserListDir(paneId, dirRelPath) }.getOrNull()
        entries = list ?: emptyList()
        if (list == null) errorMessage = "Failed to load directory"
        if (dirRelPath.isNotEmpty()) {
            // Keep the web client's persisted expansion set in sync with the
            // directories the mobile user has actually walked into.
            runCatching {
                windowSocket.fileBrowserSetExpanded(paneId, dirRelPath, true)
            }
        }
    }

    BackHandler { onBack() }

    val title = if (dirRelPath.isEmpty()) "Files"
        else dirRelPath.substringAfterLast('/').ifEmpty { dirRelPath }

    Scaffold(
        containerColor = SidebarBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Leading pane-type icon (issue #48) — the same glyph the
                        // session list draws before each pane title, keeping the
                        // full-screen header consistent with the list. This screen
                        // only ever hosts file-browser panes (never floating).
                        PaneIcon(kind = LeafKind.FILE_BROWSER, floating = false)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            title,
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
                    errorMessage ?: "Empty directory",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = SidebarTextSecondary,
                    ),
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.relPath }) { entry ->
                        FileBrowserRow(entry) {
                            if (entry.isDir) onOpenDir(entry.relPath)
                            else onOpenFile(entry.relPath)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single row in the file browser directory listing.
 *
 * Displays a folder or file icon followed by the entry name. Tapping the row
 * invokes [onClick], which the caller uses to navigate into a directory or
 * open a file.
 *
 * @param entry the directory or file entry to render.
 * @param onClick callback invoked when the row is tapped.
 */
@Composable
private fun FileBrowserRow(entry: FileBrowserEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = entry.name
            }
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDir) FolderGlyph() else FileGlyph()
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = SidebarTextPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Stroked folder glyph tinted with the theme accent, matching the hand-drawn
 * icon language of [TreeScreen]'s pane icons (replaces the emoji previously used
 * here, which clashed with the themed UI).
 *
 * `internal` and size-parameterised so the overview's [MiniFileBrowserPane] can
 * render the exact same glyph at a smaller size, keeping the thumbnail a true
 * miniature of [FileBrowserListScreen].
 *
 * @param edge the glyph's square edge length; defaults to the list-screen 18dp.
 * @see MiniFileBrowserPane
 */
@Composable
internal fun FolderGlyph(edge: Dp = 18.dp) {
    val tint = SidebarAccent.copy(alpha = 0.8f)
    Canvas(modifier = Modifier.size(edge).semantics { contentDescription = "Folder" }) {
        val px = size.width / 16f
        val stroke = Stroke(width = 1.3f * px, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Folder body with a tab on the upper-left edge
        val body = Path().apply {
            moveTo(1.5f * px, 4.5f * px)
            lineTo(1.5f * px, 13f * px)
            lineTo(14.5f * px, 13f * px)
            lineTo(14.5f * px, 5.5f * px)
            lineTo(7.5f * px, 5.5f * px)
            lineTo(6f * px, 3.5f * px)
            lineTo(2.5f * px, 3.5f * px)
            close()
        }
        drawPath(body, color = tint, style = stroke)
    }
}

/**
 * Stroked document glyph (folded corner, two text lines), matching the
 * file-browser pane icon in [TreeScreen].
 *
 * `internal` and size-parameterised so the overview's [MiniFileBrowserPane] can
 * render the exact same glyph at a smaller size.
 *
 * @param edge the glyph's square edge length; defaults to the list-screen 18dp.
 * @see MiniFileBrowserPane
 */
@Composable
internal fun FileGlyph(edge: Dp = 18.dp) {
    val tint = SidebarTextSecondary.copy(alpha = 0.7f)
    Canvas(modifier = Modifier.size(edge).semantics { contentDescription = "File" }) {
        val px = size.width / 16f
        val stroke = Stroke(width = 1.3f * px, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val doc = Path().apply {
            moveTo(4f * px, 1.75f * px)
            lineTo(9.5f * px, 1.75f * px)
            lineTo(12.5f * px, 4.75f * px)
            lineTo(12.5f * px, 14.25f * px)
            lineTo(4f * px, 14.25f * px)
            close()
        }
        drawPath(doc, color = tint, style = stroke)
        val fold = Path().apply {
            moveTo(9.5f * px, 1.75f * px)
            lineTo(9.5f * px, 4.75f * px)
            lineTo(12.5f * px, 4.75f * px)
        }
        drawPath(fold, color = tint, style = stroke)
        drawLine(tint, Offset(6f * px, 8f * px), Offset(10.5f * px, 8f * px), 1.2f * px, StrokeCap.Round)
        drawLine(tint, Offset(6f * px, 10.5f * px), Offset(10.5f * px, 10.5f * px), 1.2f * px, StrokeCap.Round)
    }
}
