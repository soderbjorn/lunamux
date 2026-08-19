/**
 * Read-only terminal miniature for the overview screen.
 *
 * Renders the session's screen as a truthful scaled-down "screenshot": the
 * server's real cols×rows grid, its real wrapping, ANSI colors and the cursor,
 * letterboxed to the pane (see [TerminalThumbnail]). Text at this scale may be
 * tiny — the miniature is meant to look like the OS app switcher's live cards,
 * where layout and color carry the recognition, not legibility.
 *
 * This composable is a thin renderer: all socket/emulator lifecycle lives in
 * the overview-scoped [MiniTerminalRegistry] (provided via
 * [LocalMiniTerminalRegistry]), which keeps one live emulator per session alive
 * across tab switches and recompositions and publishes resolved
 * [TerminalFrame] snapshots. The miniature just collects the registry's
 * per-session frame flow, so re-entering a tab renders instantly with no
 * reconnect.
 *
 * Composed by [OverviewContent]'s pane dispatch for terminal leaves; the
 * whole-pane tap that drills into the full-screen terminal is owned by the
 * caller's overlay, so this composable stays render-only.
 *
 * CONSOLIDATION: this is the Android "paint" end of the terminal-thumbnail
 * pipeline. Android now paints the exact colored grid (the fidelity web's
 * `Overview3DThumb.kt` `ThumbView.paintGrid` reaches on its 3D cards), while
 * iOS (`MiniTerminalPane` in `OverviewView.swift`) and web's link picker
 * (`LinkThumbnailRenderer.kt`) still re-wrap transcript *text* to the card
 * width — the platforms have diverged; reconcile toward the grid-truthful
 * approach when those thumbnails are next touched.
 *
 * @see MiniTerminalRegistry
 * @see TerminalThumbnail
 * @see OverviewContent
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.soderbjorn.lunamux.android.net.ConnectionHolder

/**
 * Live, read-only terminal miniature for [sessionId]: the session's screen as
 * a colored grid, uniformly scaled to fit the pane.
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
    val frameFlow = remember(registry, sessionId) { registry.frameFor(sessionId) }
    val frame by frameFlow.collectAsStateWithLifecycle()
    // Until the first frame lands the box shows the theme background, so the
    // frame that replaces it changes content, not color.
    TerminalThumbnail(
        frame = frame,
        fallbackBackground = Color(palette.bg),
        modifier = modifier,
    )
}
