/**
 * "New version available" banner for the Termtastic Android app.
 *
 * A discreet, full-width tappable bar shown at the very top of the hosts list
 * ([HostsScreen]) and each terminal session ([TerminalScreen]) whenever the
 * shared update checker reports a newer published build than the one running.
 * Tapping it opens the platform-specific "more info" URL (the Play Store page)
 * from the manifest in the browser.
 *
 * The composable is self-contained: it lazily starts the app-wide
 * [se.soderbjorn.termtastic.android.net.UpdateCheckController], observes its
 * state, and renders nothing until an update is actually available — so callers
 * simply place it at the top of their layout.
 *
 * @see se.soderbjorn.termtastic.android.net.UpdateCheckController
 * @see se.soderbjorn.termtastic.client.update.UpdateCheckViewModel
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.termtastic.android.net.UpdateCheckController

/**
 * Render the update bar at the top of a screen, or nothing when no update is
 * available.
 *
 * Lazily starts the app-wide update checker on first composition and observes
 * its state. When [se.soderbjorn.termtastic.client.update.UpdateCheckViewModel.State.updateAvailable]
 * is true, shows a tappable amber bar; tapping opens the manifest's info URL.
 *
 * @param modifier optional layout modifier applied to the bar row.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val viewModel = remember(context) { UpdateCheckController.ensureStarted(context) }
    val state by viewModel.stateFlow.collectAsState()

    if (!state.updateAvailable) return
    val url = state.infoUrl ?: return
    // Discrete, theme-aware styling: a faint wash of the workspace accent with
    // accent-coloured text, rather than a loud solid bar.
    val accent = SidebarAccent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f))
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowCircleDown,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = state.latestVersionName?.let { "New version available — $it" }
                ?: "New version available",
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = accent.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}
