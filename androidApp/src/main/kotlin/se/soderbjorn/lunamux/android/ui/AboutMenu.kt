/**
 * Top-bar "About & links" overflow menu for the Lunamux Android app.
 *
 * This file contains [AboutMenu], the single info-button-plus-dropdown control
 * shared by the Hosts and Sessions top app bars. It gathers the app's external
 * links (community support forum, marketing website, and the legal pages) into
 * one consistent place on every primary screen, rather than duplicating them
 * inline (and risking drift) per screen.
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_DISCUSSIONS_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_DOCS_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_GITHUB_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_PLAY_STORE_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_PRIVACY_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_SITE_URL
import se.soderbjorn.lunamux.client.viewmodel.LUNAMUX_TERMS_URL

/**
 * An info icon for a top app bar that reveals the app's external links.
 *
 * Placed in the `actions` slot of both the Hosts ([HostsScreen]) and Sessions
 * ([TreeScreen]) top bars, it gives users a single, always-reachable entry
 * point to the support forum, the website, and the legal pages from either
 * primary screen. Tapping the icon opens a [DropdownMenu]; selecting an item
 * opens its URL in the browser via [LocalUriHandler] and dismisses the menu.
 *
 * Keeping this a single composable (rather than inline per screen) means the
 * two bars can never drift out of sync on which links they offer, and any new
 * link is added in exactly one place.
 *
 * @see HostsScreen
 * @see TreeScreen
 * @see LUNAMUX_DISCUSSIONS_URL
 */
@Composable
fun AboutMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "About & links",
                tint = SidebarTextPrimary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AboutMenuItems(onItemSelected = { expanded = false })
        }
    }
}

/**
 * Emits the app's external-link [DropdownMenuItem]s (support forum, website,
 * GitHub, Play Store, and the legal pages) into the enclosing [DropdownMenu].
 *
 * Factored out of [AboutMenu] so the same link list can be reused inside a
 * *host* menu without duplication — the Sessions ([TreeScreen]) top bar folds
 * these links into its combined overflow ("⋮") menu rather than exposing a
 * separate info button, while [AboutMenu] still wraps them in a standalone
 * info-button-plus-dropdown for the Hosts ([HostsScreen]) bar. Keeping the
 * items in one composable means the two entry points can never drift on which
 * links they offer.
 *
 * Must be called from within a [DropdownMenu] (or another `ColumnScope` menu
 * container). Selecting an item opens its URL via [LocalUriHandler] and then
 * invokes [onItemSelected] so the caller can dismiss its own menu.
 *
 * @param onItemSelected called when any link is tapped, before/after the URL is
 *   opened, so the caller can close the surrounding menu (e.g. flip its
 *   `expanded` state to `false`).
 * @see AboutMenu
 * @see TreeScreen
 */
@Composable
fun AboutMenuItems(onItemSelected: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    DropdownMenuItem(
        text = { Text("Documentation") },
        leadingIcon = {
            Icon(Icons.Outlined.MenuBook, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_DOCS_URL)
        },
    )
    DropdownMenuItem(
        text = { Text("Support Forum") },
        leadingIcon = {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_DISCUSSIONS_URL)
        },
    )
    DropdownMenuItem(
        text = { Text("Website") },
        leadingIcon = {
            Icon(Icons.Outlined.Public, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_SITE_URL)
        },
    )
    DropdownMenuItem(
        text = { Text("Star on GitHub") },
        leadingIcon = {
            Icon(Icons.Outlined.Star, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_GITHUB_URL)
        },
    )
    DropdownMenuItem(
        text = { Text("Rate on Google Play") },
        leadingIcon = {
            Icon(Icons.Outlined.ThumbUp, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_PLAY_STORE_URL)
        },
    )
    // Separate the actionable/engagement links above from the legal
    // boilerplate below.
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Privacy Policy") },
        leadingIcon = {
            Icon(Icons.Outlined.Lock, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_PRIVACY_URL)
        },
    )
    DropdownMenuItem(
        text = { Text("Terms") },
        leadingIcon = {
            Icon(Icons.Outlined.Description, contentDescription = null)
        },
        onClick = {
            onItemSelected()
            uriHandler.openUri(LUNAMUX_TERMS_URL)
        },
    )
}
