/**
 * Top-bar "About & links" overflow menu for the Termtastic Android app.
 *
 * This file contains [AboutMenu], the single info-button-plus-dropdown control
 * shared by the Hosts and Sessions top app bars. It gathers the app's external
 * links (community support forum, marketing website, and the legal pages) into
 * one consistent place on every primary screen, rather than duplicating them
 * inline (and risking drift) per screen.
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import se.soderbjorn.termtastic.client.viewmodel.TERMTASTIC_DISCUSSIONS_URL
import se.soderbjorn.termtastic.client.viewmodel.TERMTASTIC_PRIVACY_URL
import se.soderbjorn.termtastic.client.viewmodel.TERMTASTIC_SITE_URL
import se.soderbjorn.termtastic.client.viewmodel.TERMTASTIC_TERMS_URL

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
 * @see TERMTASTIC_DISCUSSIONS_URL
 */
@Composable
fun AboutMenu() {
    val uriHandler = LocalUriHandler.current
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
            DropdownMenuItem(
                text = { Text("Support Forum") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    uriHandler.openUri(TERMTASTIC_DISCUSSIONS_URL)
                },
            )
            DropdownMenuItem(
                text = { Text("Website") },
                leadingIcon = {
                    Icon(Icons.Outlined.Public, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    uriHandler.openUri(TERMTASTIC_SITE_URL)
                },
            )
            DropdownMenuItem(
                text = { Text("Privacy Policy") },
                leadingIcon = {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    uriHandler.openUri(TERMTASTIC_PRIVACY_URL)
                },
            )
            DropdownMenuItem(
                text = { Text("Terms") },
                leadingIcon = {
                    Icon(Icons.Outlined.Description, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    uriHandler.openUri(TERMTASTIC_TERMS_URL)
                },
            )
        }
    }
}
