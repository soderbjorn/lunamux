/**
 * Read-only terminal miniature for the overview screen.
 *
 * Fills the pane with the **most recent output lines** at a legible font,
 * anchored to the bottom and growing upward — so you get an actual sense of
 * what the pane is doing. Long lines are word-wrapped to the (narrow) pane
 * width; older lines scroll off the top (clipped) like a real terminal tail.
 *
 * This composable is a thin renderer: all socket/emulator lifecycle lives in
 * the overview-scoped [MiniTerminalRegistry] (provided via
 * [LocalMiniTerminalRegistry]), which keeps one live emulator per session alive
 * across tab switches and recompositions. The miniature just collects the
 * registry's per-session lines flow, so re-entering a tab renders instantly
 * with no reconnect.
 *
 * Rendering uses a `reverseLayout` [LazyColumn] so the newest line is always
 * pinned to the bottom and surplus lines clip off the top (a plain
 * bottom-aligned Column instead gets height-constrained and shows its *oldest*
 * rows, which looked like content "from a page up" or an empty pane).
 *
 * Composed by [OverviewContent]'s pane dispatch for terminal leaves; the
 * whole-pane tap that drills into the full-screen terminal is owned by the
 * caller's overlay, so this composable stays render-only.
 *
 * CONSOLIDATION: this is the Android "paint" end of the terminal-thumbnail
 * pipeline (the iOS analogue is `MiniTerminalPane` in `OverviewView.swift`; the
 * web analogue is `renderThumbnail` in `LinkThumbnailRenderer.kt`). The paint is
 * irreducibly platform-specific (Compose `LazyColumn` here, SwiftUI overlay on
 * iOS, HTML5 canvas on web), but it leans on Compose `Text` to re-wrap each
 * logical line to the pane width and on `reverseLayout` to bottom-anchor — two
 * steps the web has to do by hand and which could become shared pure helpers.
 * See the design note in `web/.../LinkThumbnailRenderer.kt`.
 *
 * @see MiniTerminalRegistry
 * @see OverviewContent
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.lunamux.android.net.ConnectionHolder

/** Legible monospace font size (sp) for the miniature's content lines. */
private const val MINI_TERMINAL_FONT_SP = 9

/**
 * Live, read-only terminal miniature for [sessionId] that fills the pane with
 * the most recent (word-wrapped) output lines, bottom-anchored.
 *
 * @param sessionId the PTY session to mirror.
 * @param modifier  layout modifier from the enclosing mini-pane.
 */
@Composable
fun MiniTerminalPane(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val client = ConnectionHolder.client()
    val registry = LocalMiniTerminalRegistry.current
    if (client == null || registry == null) {
        Box(modifier)
        return
    }

    val palette = rememberTerminalPalette(client, sessionId)
    val context = LocalContext.current
    val terminalFontFamily = remember(context) { FontFamily(TerminalFont.typeface(context)) }
    val linesFlow = remember(registry, sessionId) { registry.linesFor(sessionId) }
    val lines by linesFlow.collectAsStateWithLifecycle()

    // Newest line first, rendered with reverseLayout so item 0 sits at the
    // bottom and older lines fill upward, clipping off the top.
    val display = remember(lines) { lines.asReversed() }
    LazyColumn(
        modifier = modifier.background(Color(palette.bg)),
        reverseLayout = true,
        userScrollEnabled = false,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
    ) {
        items(display) { line ->
            Text(
                text = line,
                color = Color(palette.text),
                // The shared terminal face, not FontFamily.Monospace: the system mono
                // font carries no Iosevka fallback, so the terminal symbols JetBrains Mono
                // lacks (⏺, ⎿, …) fall through to Noto Color Emoji and the miniature fills
                // with emoji buttons (#141).
                fontFamily = terminalFontFamily,
                fontSize = MINI_TERMINAL_FONT_SP.sp,
                lineHeight = (MINI_TERMINAL_FONT_SP + 2).sp,
            )
        }
    }
}
