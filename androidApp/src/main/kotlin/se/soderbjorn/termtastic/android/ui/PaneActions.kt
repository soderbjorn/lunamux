/**
 * Tab/pane mutation dialogs for the Termtastic Android app.
 *
 * Provides the UI flows for mutating the server's window layout:
 * - [TabNameDialog] -- name a new tab, launched from the "+" action.
 * - [RenameDialog] -- rename a tab or pane from a row's menu.
 * - [ConfirmCloseDialog] -- destructive confirmation before closing a tab
 *   or pane (both end live sessions).
 *
 * Pane creation lives on each tab header's menu in [TreeScreen]; the server
 * places new panes exactly like the web client's "+" button does.
 *
 * @see TreeScreen
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Prompts the user to enter a name for a new tab, launched from the tree
 * screen's "+" action.
 *
 * @param onDismiss callback to close the dialog without creating.
 * @param onConfirm callback invoked with the entered tab name.
 */
@Composable
fun TabNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("New tab") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Tab name") },
                singleLine = true,
                colors = themedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Dialog for renaming a tab or pane from a row's overflow menu.
 *
 * Pre-fills the text field with the current title. For panes ([allowBlank]
 * = true) a blank value is allowed and clears the custom name so the title
 * falls back to the pane's working directory; tab titles must be non-blank
 * (the server rejects empty ones).
 *
 * @param title dialog title (e.g. "Rename tab").
 * @param initialValue the current name, pre-filled in the text field.
 * @param allowBlank whether an empty value may be confirmed (panes only).
 * @param supportingText optional helper text shown under the field.
 * @param onDismiss callback to close the dialog without renaming.
 * @param onConfirm callback invoked with the trimmed new name.
 */
@Composable
fun RenameDialog(
    title: String,
    initialValue: String,
    allowBlank: Boolean,
    supportingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                supportingText = supportingText?.let { { Text(it) } },
                singleLine = true,
                colors = themedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = allowBlank || name.isNotBlank(),
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/**
 * Destructive confirmation dialog shown before closing a tab or pane. Both
 * actions end live sessions, so they always confirm first.
 *
 * @param title dialog title (e.g. "Close “dev server”?").
 * @param text explanation of what closing will do.
 * @param confirmLabel label for the destructive confirm button.
 * @param onDismiss callback to close the dialog without closing anything.
 * @param onConfirm callback invoked when the user confirms.
 */
@Composable
fun ConfirmCloseDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}
