/**
 * Session state indicators for the Lunamux Android app.
 *
 * Renders the per-session status shown next to titles in [TreeScreen] rows and
 * the [TerminalScreen] top bar, mirroring the web client's `.tt-status-dot`
 * indicator (`applyDotState()` in `web/.../WebStateActions.kt`). Per issue #38
 * the indicator is painted in the theme's foreground text colour (not a fixed
 * green/red) so it meshes with any theme:
 *  - idle (`null`) → no indicator at all (hidden); the bead appears only when
 *    working or waiting (issue #43).
 *  - `"working"`   → the same dot, breathing (pulsing) between full and ~30%.
 *  - `"waiting"`   → the dot is swapped for a pulsing warning/exclamation
 *    triangle, so working and waiting stay distinguishable now that they share
 *    one colour.
 *
 * @see TreeScreen
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** CSS `ease-in-out` curve, matching the web `fade-warning` animation timing. */
private val EaseInOutCss = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * Per-row status indicator mirroring the web `.tt-status-dot` (issue #38): a
 * small bead whose motion/shape — but no longer its colour — encodes the
 * session state. It is always painted in the theme's foreground text colour
 * ([SidebarTextPrimary]) so it meshes with any theme instead of a fixed
 * green/red.
 *  - idle (`null`) → no indicator at all (hidden); the bead appears only when
 *    working or waiting (issue #43).
 *  - `"working"`   → the same dot, breathing between full and ~30% opacity.
 *  - `"waiting"`   → a pulsing warning/exclamation triangle (see
 *    [WaitingWarningIcon]) in place of the dot.
 *
 * Rendered at the LEADING edge of each [TreeScreen] leaf row and aggregated on
 * tab headers; also shown next to the [TerminalScreen] title. A soft
 * radial-gradient glow surrounds the dot, echoing the web bead's box-shadow
 * halo.
 *
 * @param state the session state (`"working"`, `"waiting"`, or null/idle).
 * @param boxDp the square canvas size in dp; the core bead is ~44% of it, the
 *   rest is glow headroom.
 * @see WaitingWarningIcon
 */
@Composable
internal fun StatusDot(state: String?, boxDp: Int = 16) {
    // Idle (null, or any value other than working/waiting) shows NO indicator
    // (issue #43): the bead appears only while a session is working or waiting
    // for input. This single guard hides the indicator everywhere StatusDot is
    // used — sidebar leaf rows, tab headers, the overview tab strip and pane
    // thumbnails, and the terminal-screen title bar.
    if (state != "working" && state != "waiting") return
    val color = SidebarTextPrimary
    // Waiting for input → swap the dot for the warning triangle, still pulsing.
    if (state == "waiting") {
        WaitingWarningIcon(boxDp = boxDp, color = color)
        return
    }
    // Working → a breathing (pulsing) dot.
    val transition = rememberInfiniteTransition(label = "statusDot")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            // The web `.tt-status-dot.state-working` breathes over a 2.5s
            // ease-in-out cycle; the reversing tween runs the 1250ms
            // half-cycle each way so it matches.
            animation = tween(durationMillis = 1250, easing = EaseInOutCss),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusDotAlpha",
    ).value
    Canvas(
        modifier = Modifier
            .size(boxDp.dp)
            .semantics { stateDescription = "working" },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val core = size.minDimension * 0.22f
        val glow = size.minDimension * 0.5f
        // Soft outer glow — a radial gradient fading to transparent at the
        // canvas edge, echoing the web dot's layered box-shadow halo.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.5f * alpha), Color.Transparent),
                center = center,
                radius = glow,
            ),
            radius = glow,
            center = center,
        )
        // Core bead.
        drawCircle(color = color.copy(alpha = alpha), radius = core, center = center)
    }
}

/**
 * Warning triangle with an exclamation mark, fading between full and 30%
 * opacity to flag a session waiting for user input.
 *
 * Geometry mirrors the web client's `.tt-status-dot.state-waiting` mask (a
 * 16-unit viewBox: a filled triangle with the exclamation bar and dot punched
 * out as transparent holes via an even-odd fill), and the fade mirrors the
 * `fade-warning` keyframes (opacity 1 → 0.3 → 1 over 2.5s ease-in-out). Painted
 * in the supplied [color] (the theme foreground) so it meshes with any theme.
 *
 * @param boxDp the square canvas size in dp (matches the dot it replaces).
 * @param color the fill colour (the theme's foreground text colour).
 * @see StatusDot
 */
@Composable
private fun WaitingWarningIcon(boxDp: Int, color: Color) {
    val transition = rememberInfiniteTransition(label = "fadeWarning")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = EaseInOutCss),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fadeWarningAlpha",
    ).value
    Canvas(
        modifier = Modifier
            .size(boxDp.dp)
            .semantics { stateDescription = "waiting for input" },
    ) {
        // Scale the 16-unit viewBox to fill the canvas; the triangle has its
        // own padding (apex at y=1.7, base at y=13.7) so it never touches the
        // edges. A single even-odd path fills the triangle and cuts the
        // exclamation bar + dot as holes (transparent, reading on any theme).
        val s = size.minDimension / 16f
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            // Triangle silhouette.
            moveTo(8f * s, 1.7f * s)
            lineTo(14.5f * s, 13.7f * s)
            lineTo(1.5f * s, 13.7f * s)
            close()
            // Exclamation bar (cut-out).
            addRect(Rect(7.25f * s, 5.6f * s, 8.75f * s, 9.8f * s))
            // Exclamation dot (cut-out).
            addOval(Rect(7.1f * s, 10.9f * s, 8.9f * s, 12.7f * s))
        }
        drawPath(path, color = color.copy(alpha = color.alpha * alpha))
    }
}
