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
