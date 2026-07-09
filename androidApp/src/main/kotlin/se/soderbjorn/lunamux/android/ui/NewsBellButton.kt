/**
 * Pulsing "News & Updates" toolbar bell for the Lunamux Android app.
 *
 * Shared by the Hosts and Sessions collapsing top bars (see [HostsScreen] /
 * [TreeScreen]). The bell is *always* shown so the "News & Updates" screen — and
 * its Restore button — is reachable at any time; when there is nothing new it is
 * rendered muted/grayed (`muted = true`) rather than hidden. With content it uses
 * the warning colour, and its tint pulses continuously when there is actual news
 * so it reads as a call-to-action, mirroring the desktop and iOS bells. When only
 * a version update is available (no news) the bell shows coloured but static.
 *
 * @see HostsScreen
 * @see TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * Render the toolbar bell that opens the "News & Updates" screen, pulsing its
 * tint between the full warning colour and a dimmed variant when there is news.
 *
 * Called from the Hosts/Sessions top-bar `actions` slots — always shown, so the
 * screen is reachable at any time. When [muted] is true (nothing new) it renders
 * in the secondary/grayed colour and never pulses.
 *
 * @param onClick invoked when the bell is tapped; navigates to the News &
 *   Updates screen.
 * @param shouldPulse whether to pulse the tint; `true` only when there is news
 *   to read. A version update on its own keeps the bell static. Ignored when
 *   [muted].
 * @param muted when `true`, render the bell grayed (nothing new) and never pulse.
 */
@Composable
fun NewsBellButton(onClick: () -> Unit, shouldPulse: Boolean = true, muted: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "news-bell")
    val pulsedTint by transition.animateColor(
        initialValue = SidebarWarn,
        targetValue = SidebarWarn.copy(alpha = 0.3f),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "news-bell-tint",
    )
    val tint = when {
        muted -> SidebarTextSecondary
        shouldPulse -> pulsedTint
        else -> SidebarWarn
    }
    IconButton(onClick = onClick) {
        Icon(
            Icons.Outlined.Notifications,
            contentDescription = "News & updates",
            tint = tint,
        )
    }
}
