/**
 * Git diff viewer screen for the Termtastic Android app.
 *
 * Fetches the unified diff for a single changed file from the server and
 * renders it inside a [android.webkit.WebView] as a styled HTML table with
 * line numbers, addition/deletion colour coding, and syntax-highlight CSS
 * classes. Navigated to from [GitListScreen] when the user taps a file entry.
 *
 * @see GitListScreen
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
package se.soderbjorn.termtastic.android.ui

import se.soderbjorn.darkness.core.*

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.soderbjorn.termtastic.DiffHunk
import se.soderbjorn.termtastic.DiffLine
import se.soderbjorn.termtastic.DiffLineType
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.termtastic.client.TermtasticThemeConfig
import se.soderbjorn.termtastic.client.fetchThemeConfig
import se.soderbjorn.termtastic.android.net.ConnectionHolder

/**
 * Displays a unified diff for a single changed file.
 *
 * Fetches diff hunks from the server via [ConnectionHolder.windowSocket] and
 * renders them as colour-coded HTML inside a [WebView]. Additions, deletions,
 * and context lines each get distinct background colours and line number columns.
 *
 * @param paneId the server-side pane identifier owning the git session.
 * @param filePath path of the changed file relative to the repository root.
 * @param onBack callback invoked when the user navigates back.
 * @see GitListScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffScreen(
    paneId: String,
    filePath: String,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var hunks by remember { mutableStateOf<List<DiffHunk>?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isDark = isSystemInDarkTheme()
    val centralTheme = LocalUiSettings.current
    var localConfig by remember { mutableStateOf<TermtasticThemeConfig?>(null) }
    LaunchedEffect(Unit) {
        if (centralTheme == null) {
            localConfig = ConnectionHolder.client()?.fetchThemeConfig()
        }
    }
    val defaultConfig = remember { TermtasticThemeConfig.defaults() }
    val palette = remember(isDark, centralTheme, localConfig) {
        centralTheme ?: (localConfig ?: defaultConfig).resolve(isDark)
    }

    LaunchedEffect(paneId, filePath) {
        val reply = runCatching { windowSocket.gitDiff(paneId, filePath) }.getOrNull()
        when (reply) {
            is WindowEnvelope.GitDiffResult -> {
                hunks = reply.hunks
                errorMessage = null
            }
            is WindowEnvelope.GitError -> {
                errorMessage = reply.message
            }
            else -> {
                errorMessage = "No response"
            }
        }
    }

    BackHandler { onBack() }

    val fileName = filePath.substringAfterLast('/')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            val currentHunks = hunks
            when {
                currentHunks != null -> {
                    val diffHtml = remember(currentHunks, isDark, palette) {
                        buildDiffHtml(currentHunks, palette)
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(
                                null,
                                diffHtml,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        },
                    )
                }
                errorMessage != null -> Text(
                    text = errorMessage!!,
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Build a complete HTML page rendering the diff hunks in inline (unified)
 * format, styled for mobile with line numbers, coloured backgrounds for
 * additions/deletions, and syntax-highlight CSS classes.
 */
private fun buildDiffHtml(hunks: List<DiffHunk>, theme: ResolvedTheme): String {
    val vars = themeCssVars(theme)

    val body = buildString {
        for (hunk in hunks) {
            append("<table>")
            for (line in hunk.lines) {
                val cls = when (line.type) {
                    DiffLineType.Addition -> "add"
                    DiffLineType.Deletion -> "del"
                    DiffLineType.Context  -> "ctx"
                }
                val oldNo = line.oldLineNo?.toString() ?: ""
                val newNo = line.newLineNo?.toString() ?: ""
                val prefix = when (line.type) {
                    DiffLineType.Addition -> "+"
                    DiffLineType.Deletion -> "-"
                    DiffLineType.Context  -> " "
                }
                // line.content already arrives as syntax-highlighted, HTML-escaped
                // markup from the server (see SyntaxHighlighter on clientServer). It
                // must be emitted verbatim — re-escaping here would double-escape the
                // markup and surface raw <span>/&quot; text in the WebView (matches the
                // iOS GitDiffView, which also inserts line.content as-is).
                append("<tr class=\"$cls\">")
                append("<td class=\"ln\">$oldNo</td>")
                append("<td class=\"ln\">$newNo</td>")
                append("<td class=\"pfx\">$prefix</td>")
                append("<td class=\"code\">${line.content}</td>")
                append("</tr>")
            }
            append("</table>")
        }
    }

    return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>
  :root {
    $vars
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    background: var(--t-bg);
    color: var(--t-text);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 13px;
    line-height: 1.5;
    padding: 0;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    table-layout: fixed;
  }
  tr.add { background: var(--t-add-bg); }
  tr.del { background: var(--t-surface-alt); }
  tr.ctx { background: transparent; }
  td { vertical-align: top; white-space: pre-wrap; word-break: break-all; }
  td.ln {
    width: 36px;
    min-width: 36px;
    max-width: 36px;
    text-align: right;
    padding: 0 4px;
    color: var(--t-text-dim);
    font-size: 11px;
    user-select: none;
    -webkit-user-select: none;
    border-right: 1px solid var(--t-border);
  }
  td.pfx {
    width: 16px;
    min-width: 16px;
    max-width: 16px;
    text-align: center;
    color: var(--t-text-dim);
    user-select: none;
    -webkit-user-select: none;
  }
  td.code {
    padding: 0 8px;
  }
  /* Syntax highlighting classes matching server output */
  .hl-keyword  { color: var(--t-syn-keyword); }
  .hl-string   { color: var(--t-syn-string); }
  .hl-number   { color: var(--t-syn-number); }
  .hl-comment  { color: var(--t-syn-comment); font-style: italic; }
  .hl-function { color: var(--t-syn-function); }
  .hl-type     { color: var(--t-syn-type); }
  .hl-operator { color: var(--t-syn-operator); }
  .hl-constant { color: var(--t-syn-constant); }
  .hl-tag      { color: var(--t-syn-keyword); }
  .hl-attr     { color: var(--t-syn-function); }
  .hl-annotation { color: var(--t-syn-function); }
  .hl-punctuation { color: var(--t-syn-operator); }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}
