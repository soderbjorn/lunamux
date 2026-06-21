/**
 * Session state indicators for the Termtastic Android app.
 *
 * Renders the per-session status shown next to titles in [TreeScreen] rows
 * and the [TerminalScreen] top bar, mirroring the web client's
 * `.pane-status-spinner` element (`applySpinnerState()` in
 * `web/.../WebStateActions.kt`):
 *  - `"working"` — a small circular spinner.
 *  - `"waiting"` — a warning triangle with an exclamation mark, drawn in the
 *    theme's semantic warn colour and fading between full and 30% opacity
 *    (the web `fade-warning` keyframes: 2.5s ease-in-out cycle).
 *
 * @see TreeScreen
 * @see TerminalScreen
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** CSS `ease-in-out` curve, matching the web `fade-warning` animation timing. */
private val EaseInOutCss = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * Fixed status-dot colours, matching the web client's per-row sidebar dot
 * (`.tt-sidebar-dot` in `styles.css`) and the landing page's brand dot. Kept as
 * fixed values (not theme tokens) so the idle/working green and the waiting red
 * stay distinguishable in any appearance mode.
 */
private val StatusDotGreen = Color(0xFF7CFC9E)
private val StatusDotRed = Color(0xFFFF5F57)

/**
 * Per-row status dot mirroring the web sidebar dot (issue #35 follow-up): a
 * small glowing bead whose colour/motion encodes the session state.
 *  - idle (`null`) → solid green, no pulse.
 *  - `"working"`   → green, pulsing (the bead "breathes" between full and ~35%).
 *  - `"waiting"`   → red, pulsing.
 *
 * Rendered at the LEADING edge of each [TreeScreen] leaf row (replacing the old
 * leading pane-type icon, which now sits trailing). A soft radial-gradient glow
 * surrounds the core bead, echoing the web dot's festive box-shadow halo.
 *
 * @param state the session state (`"working"`, `"waiting"`, or null/idle).
 * @param boxDp the square canvas size in dp; the core bead is ~44% of it, the
 *   rest is glow headroom.
 * @see StateIndicator
 */
@Composable
internal fun StatusDot(state: String?, boxDp: Int = 16) {
    val pulsing = state == "working" || state == "waiting"
    val color = if (state == "waiting") StatusDotRed else StatusDotGreen
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                // Web pulse cycle is 2.8s for both colours; the reversing tween
                // runs the 1400ms half-cycle each way so red and green breathe
                // at the same speed.
                animation = tween(durationMillis = 1400, easing = EaseInOutCss),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        ).value
    } else {
        1f
    }
    val desc = when (state) {
        "working" -> "working"
        "waiting" -> "waiting for input"
        else -> "idle"
    }
    Canvas(
        modifier = Modifier
            .size(boxDp.dp)
            .semantics { stateDescription = desc },
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
 * Status indicator for a session state, shown next to the session title.
 *
 * Renders a spinner when [state] is `"working"`, a fading warning triangle
 * when it is `"waiting"`, and nothing otherwise. Called by [TreeScreen]'s
 * tab header and leaf rows and by [TerminalScreen]'s top bar title.
 *
 * @param state the session state (`"working"`, `"waiting"`, or null)
 * @param sizeDp icon size in dp (12 in list rows, 14 in the pane header,
 *   matching the web client's two spinner variants)
 * @param leadingSpacer optional spacer width in dp before the indicator
 * @param trailingSpacer optional spacer width in dp after the indicator
 * @param spinnerColor stroke colour for the working spinner
 * @param spinnerTrackColor track colour for the working spinner
 */
@Composable
internal fun StateIndicator(
    state: String?,
    sizeDp: Int,
    leadingSpacer: Int = 0,
    trailingSpacer: Int = 0,
    spinnerColor: Color = SidebarTextPrimary,
    spinnerTrackColor: Color = SidebarTextSecondary.copy(alpha = 0.3f),
) {
    if (state != "working" && state != "waiting") return
    if (leadingSpacer > 0) Spacer(Modifier.width(leadingSpacer.dp))
    if (state == "working") {
        CircularProgressIndicator(
            modifier = Modifier
                .size(sizeDp.dp)
                .semantics { stateDescription = "working" },
            strokeWidth = 2.dp,
            color = spinnerColor,
            trackColor = spinnerTrackColor,
        )
    } else {
        WaitingWarningIcon(sizeDp = sizeDp)
    }
    if (trailingSpacer > 0) Spacer(Modifier.width(trailingSpacer.dp))
}

/**
 * Warning triangle with an exclamation mark, fading between full and 30%
 * opacity to flag a session waiting for user input.
 *
 * Geometry mirrors the web client's `WAITING_WARNING_SVG` (16x16 viewBox:
 * stroked triangle, exclamation bar, and dot), and the fade mirrors the
 * `fade-warning` CSS keyframes (opacity 1 → 0.3 → 1 over 2.5s ease-in-out).
 *
 * @param sizeDp icon size in dp
 */
@Composable
private fun WaitingWarningIcon(sizeDp: Int) {
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
    val warn = SidebarWarn
    Canvas(
        modifier = Modifier
            .size(sizeDp.dp)
            .semantics { stateDescription = "waiting for input" },
    ) {
        val s = size.minDimension / 16f
        val color = warn.copy(alpha = warn.alpha * alpha)
        val triangle = Path().apply {
            moveTo(8f * s, 1.5f * s)
            lineTo(14.5f * s, 13.5f * s)
            lineTo(1.5f * s, 13.5f * s)
            close()
        }
        drawPath(triangle, color, style = Stroke(width = 1.3f * s, join = StrokeJoin.Round))
        drawRoundRect(
            color = color,
            topLeft = Offset(7.25f * s, 6f * s),
            size = Size(1.5f * s, 4f * s),
            cornerRadius = CornerRadius(0.5f * s),
        )
        drawCircle(color = color, radius = 0.85f * s, center = Offset(8f * s, 12f * s))
    }
}
