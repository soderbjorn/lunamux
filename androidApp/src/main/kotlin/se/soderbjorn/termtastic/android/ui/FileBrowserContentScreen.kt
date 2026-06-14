/**
 * File content viewer screen for the Termtastic file browser.
 *
 * Fetches the server-rendered HTML for a single file (text with syntax
 * highlighting, Markdown, or binary) and displays it inside an Android
 * [android.webkit.WebView]. The HTML body is wrapped in a minimal responsive
 * stylesheet that adapts to the system dark/light theme.
 *
 * Navigated to from [FileBrowserListScreen] when the user taps a file entry.
 *
 * @see FileBrowserListScreen
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.termtastic.FileContentKind
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.termtastic.client.TermtasticThemeConfig
import se.soderbjorn.termtastic.client.fetchThemeConfig
import se.soderbjorn.termtastic.android.net.ConnectionHolder

/**
 * Displays the rendered content of a single file from the server's file browser.
 *
 * Fetches the file's HTML representation via [ConnectionHolder.windowSocket] and
 * renders it in a [WebView]. Binary files show a placeholder message; text and
 * Markdown files are wrapped in a responsive stylesheet with syntax highlighting.
 *
 * @param paneId the server-side pane identifier owning the file browser session.
 * @param relPath relative path of the file within the project root.
 * @param onBack callback invoked when the user navigates back (back button or gesture).
 * @see FileBrowserListScreen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContentScreen(
    paneId: String,
    relPath: String,
    onBack: () -> Unit,
) {
    val windowSocket = ConnectionHolder.windowSocket()
    if (windowSocket == null) {
        onBack()
        return
    }

    var html by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<FileContentKind?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isDark = isSystemInDarkTheme()
    val centralSettings = LocalUiSettings.current
    var localConfig by remember { mutableStateOf<TermtasticThemeConfig?>(null) }
    LaunchedEffect(Unit) {
        if (centralSettings == null) {
            localConfig = ConnectionHolder.client()?.fetchThemeConfig()
        }
    }
    val uiSettings = centralSettings ?: localConfig?.resolve(isDark)
    val palette = remember(isDark, uiSettings) {
        val theme = uiSettings?.schemeForPane("fileBrowser")
            ?: recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }
        val appearance = uiSettings?.appearance ?: Appearance.Auto
        theme.resolve(appearance, isDark)
    }

    LaunchedEffect(paneId, relPath) {
        val reply = runCatching { windowSocket.fileBrowserOpenFile(paneId, relPath) }.getOrNull()
        when (reply) {
            is WindowEnvelope.FileBrowserContentMsg -> {
                kind = reply.kind
                html = reply.html
                errorMessage = null
            }
            is WindowEnvelope.FileBrowserError -> errorMessage = reply.message
            else -> errorMessage = "No response"
        }
    }

    BackHandler { onBack() }

    val fileName = relPath.substringAfterLast('/')

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
            val currentHtml = html
            val currentKind = kind
            when {
                currentKind == FileContentKind.Binary -> Text(
                    "Binary file — preview unavailable.",
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                currentHtml != null && currentKind != null -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            webViewClient = WebViewClient()
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = { webView ->
                        // For HTML files load the raw document directly: the
                        // file is its own complete page and we don't want the
                        // markdown/source wrapper's stylesheet competing with
                        // it. Scripts inside are allowed (mirrors the web
                        // `srcdoc` iframe's `allow-scripts` sandbox); for
                        // Markdown/Text we keep JS off and use the wrapper.
                        val isHtml = currentKind == FileContentKind.Html
                        webView.settings.javaScriptEnabled = isHtml
                        val content = if (isHtml) {
                            currentHtml
                        } else {
                            wrapFileHtml(currentHtml, currentKind, palette)
                        }
                        // `baseURL = null` keeps relative asset URLs unresolved
                        // (intentional — matches the web `srcdoc` fallback).
                        webView.loadDataWithBaseURL(
                            null,
                            content,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    },
                )
                errorMessage != null -> Text(
                    text = errorMessage!!,
                    color = Color.Gray,
                    modifier = Modifier.padding(16.dp),
                )
                else -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Wrap the server-rendered HTML body in a minimal stylesheet. For Markdown
 * files the body is flexmark output (prose). For Text files the body holds
 * pre-tokenised `<span class="hl-…">` spans from [SyntaxHighlighter] — those
 * need the same hl-\* palette the diff viewer already ships on the web.
 */
private fun wrapFileHtml(bodyHtml: String, kind: FileContentKind, palette: se.soderbjorn.darkness.core.ResolvedPalette): String {
    val c = { v: Long -> se.soderbjorn.darkness.core.argbToCss(v) }
    val vars = """
    --background: ${c(palette.surface.base)};
    --surface: ${c(palette.surface.raised)};
    --bg-elevated: ${c(palette.surface.overlay)};
    --text-primary: ${c(palette.text.primary)};
    --text-secondary: ${c(palette.text.secondary)};
    --separator: ${c(palette.border.subtle)};
    --accent: ${c(palette.accent.primary)};
    --hl-keyword: ${c(palette.syntax.keyword)};
    --hl-string: ${c(palette.syntax.string)};
    --hl-comment: ${c(palette.syntax.comment)};
    --hl-number: ${c(palette.syntax.number)};
    --hl-tag: ${c(palette.syntax.keyword)};
    --hl-attr: ${c(palette.syntax.function)};
    --hl-type: ${c(palette.syntax.type)};
    --hl-annotation: ${c(palette.syntax.constant)};
    --hl-punctuation: ${c(palette.syntax.operator)};
    """.trimIndent()

    val body = when (kind) {
        FileContentKind.Text -> "<pre class=\"file-source\"><code>$bodyHtml</code></pre>"
        else -> bodyHtml
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
    background: var(--background);
    color: var(--text-primary);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    font-size: 15px;
    line-height: 1.6;
    padding: 16px 18px 32px;
    word-wrap: break-word;
  }
  h1, h2, h3, h4, h5, h6 {
    color: var(--text-primary);
    margin-top: 1.5em;
    margin-bottom: 0.5em;
    line-height: 1.25;
  }
  h1 { font-size: 1.8em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
  h2 { font-size: 1.5em; border-bottom: 1px solid var(--separator); padding-bottom: 0.3em; }
  h3 { font-size: 1.25em; }
  p { margin: 0.75em 0; }
  a { color: var(--accent); text-decoration: none; }
  a:active { text-decoration: underline; }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.9em;
    color: var(--text-primary);
    background: var(--bg-elevated);
    border: 1px solid var(--separator);
    padding: 0.15em 0.4em;
    border-radius: 3px;
  }
  pre {
    color: var(--text-primary);
    background: var(--bg-elevated);
    border: 1px solid var(--separator);
    padding: 12px 14px;
    border-radius: 4px;
    overflow-x: auto;
    margin: 0.75em 0;
  }
  pre code { background: transparent; border: none; padding: 0; font-size: 0.9em; }
  pre.file-source {
    white-space: pre;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 13px;
    line-height: 1.45;
  }
  blockquote {
    border-left: 3px solid var(--separator);
    margin: 0.75em 0;
    padding: 0 1em;
    color: var(--text-secondary);
  }
  ul, ol { margin: 0.5em 0; padding-left: 1.5em; }
  li { margin: 0.25em 0; }
  hr { border: 0; border-top: 1px solid var(--separator); margin: 1.2em 0; }
  table { border-collapse: collapse; margin: 0.75em 0; }
  th, td { border: 1px solid var(--separator); padding: 6px 10px; }
  th { background: var(--surface); }
  img { max-width: 100%; height: auto; }

  .hl-keyword { color: var(--hl-keyword); }
  .hl-string { color: var(--hl-string); }
  .hl-comment { color: var(--hl-comment); font-style: italic; }
  .hl-number { color: var(--hl-number); }
  .hl-tag { color: var(--hl-tag); }
  .hl-attr { color: var(--hl-attr); }
  .hl-type { color: var(--hl-type); }
  .hl-annotation { color: var(--hl-annotation); }
  .hl-punctuation { color: var(--hl-punctuation); }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}
