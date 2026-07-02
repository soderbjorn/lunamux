/**
 * Hosts/servers list screen for the Termtastic Android app.
 *
 * This is the app's landing screen. It displays the user's saved server
 * entries from the shared [se.soderbjorn.termtastic.client.storage.LocalRepository]
 * (via [se.soderbjorn.termtastic.android.data.AppLocalRepository]) and provides
 * add/edit/delete dialogs. Tapping a host initiates a WebSocket connection via
 * [se.soderbjorn.termtastic.android.net.ConnectionHolder] and, on success,
 * navigates to the [TreeScreen] overview.
 *
 * @see se.soderbjorn.termtastic.android.data.AppLocalRepository
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 * @see TreeScreen
 */
package se.soderbjorn.termtastic.android.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.SERVER_TLS_PORT
import se.soderbjorn.termtastic.android.data.AppLocalRepository
import se.soderbjorn.termtastic.client.HostEntry
import se.soderbjorn.termtastic.android.net.ConnectionHolder
import se.soderbjorn.termtastic.android.net.NewsUpdatesController
import se.soderbjorn.termtastic.client.ServerUrl
import se.soderbjorn.termtastic.client.demo.DEMO_HOST

/**
 * Sentinel id used in the `connectingId` state for the built-in demo row —
 * it is not a persisted [HostEntry], so it needs its own marker to drive
 * the row's progress spinner and to disable the rest of the list while the
 * (instant) demo connection is being set up.
 */
private const val DEMO_ROW_ID = "builtin-demo"

/**
 * Sealed type representing the target of the host edit dialog.
 *
 * [Add] opens a blank form; [Edit] opens a pre-filled form for an existing entry.
 */
private sealed interface EditTarget {
    /** Indicates the dialog should create a new host entry. */
    data object Add : EditTarget
    /**
     * Indicates the dialog should edit an existing host entry.
     *
     * @property entry the host entry to edit.
     */
    data class Edit(val entry: HostEntry) : EditTarget
}

/**
 * Landing screen showing the user's saved server hosts.
 *
 * Provides add, edit, and delete functionality for host entries. Tapping a
 * host initiates a WebSocket connection to the Termtastic server; on success
 * the [onConnected] callback navigates to the tree overview.
 *
 * Shows a "Waiting for approval..." label when the server has a pending
 * device-approval dialog for this client.
 *
 * @param applicationContext Android application context, forwarded to the
 *   [se.soderbjorn.termtastic.android.net.NewsUpdatesController].
 * @param onConnected callback invoked after a successful connection to a host.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see se.soderbjorn.termtastic.android.data.AppLocalRepository
 * @see se.soderbjorn.termtastic.android.net.ConnectionHolder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    applicationContext: Context,
    onConnected: () -> Unit,
    onOpenNews: () -> Unit,
) {
    val repository = remember { AppLocalRepository.instance }
    val scope = rememberCoroutineScope()

    // Null while local_state.json is still hydrating (render nothing rather than
    // flashing the empty state), then the persisted host list.
    val localState by repository.state.collectAsState()
    val hosts = localState?.hosts
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<HostEntry?>(null) }
    var connectingId by remember { mutableStateOf<String?>(null) }
    // Triggered when ConnectionHolder.connect throws a pin-mismatch — the
    // server's cert no longer matches what we pinned. Re-pair clears the
    // stored pin and runs first-connect again; Forget removes the host.
    var pinMismatchEntry by remember { mutableStateOf<HostEntry?>(null) }
    val pendingApproval by (ConnectionHolder.pendingApproval
        ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Shared news/update checker — drives the toolbar bell's visibility (shown
    // only when there is news or an available update).
    val newsUpdatesVm = remember { NewsUpdatesController.ensureStarted(applicationContext) }
    val newsUpdatesState by newsUpdatesVm.stateFlow.collectAsState()

    // Connect to the built-in demo "server": the magic demo host makes the
    // shared client run against its in-process simulation, so this never
    // touches the network and completes instantly. No auth, no TLS pin, no
    // saved host entry.
    val connectDemo: () -> Unit = {
        connectingId = DEMO_ROW_ID
        scope.launch {
            runCatching {
                ConnectionHolder.connect(
                    serverUrl = ServerUrl(host = DEMO_HOST, port = 0),
                    authToken = "demo",
                )
            }.onSuccess {
                connectingId = null
                onConnected()
            }.onFailure { e ->
                connectingId = null
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = e.message ?: "Demo failed to start",
                        actionLabel = "Dismiss",
                    )
                }
            }
        }
    }

    // Collapsing large title — expands to a tall "Hosts" header at rest and
    // shrinks to an inline bar as the list scrolls, mirroring the iOS hosts
    // screen. The behaviour is wired to the Scaffold via nestedScroll below.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // This screen renders before any server connection exists, so no
    // server-driven theme has been fetched yet — but the sidebar palette falls
    // back to the default theme, so we use its black background and themed text
    // here (rather than the Material 3 surface) to match the Sessions screen.
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SidebarBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Hosts") },
                actions = {
                    NewsBellButton(
                        onClick = onOpenNews,
                        shouldPulse = newsUpdatesState.hasNews,
                        muted = !newsUpdatesState.hasContent,
                    )
                    IconButton(onClick = { editTarget = EditTarget.Add }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add host",
                            tint = SidebarAccent,
                        )
                    }
                    // Shared info menu → support forum, website, legal pages.
                    // Mirrored in the Sessions top bar so both primary screens
                    // expose the same links from the same place.
                    AboutMenu()
                },
                // Keep the bar the sidebar colour in both expanded and collapsed
                // states so the title region never flashes the default surface
                // tint as it scrolls — matching the Sessions screen's top bar.
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = SidebarBackground,
                    scrolledContainerColor = SidebarBackground,
                    titleContentColor = SidebarTextPrimary,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = hosts
                when {
                    list == null -> Unit // first composition
                    list.isEmpty() -> EmptyState(
                        onAdd = { editTarget = EditTarget.Add },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { entry ->
                            HostRow(
                                entry = entry,
                                connecting = connectingId == entry.id,
                                pendingApproval = pendingApproval && connectingId == entry.id,
                                enabled = connectingId == null,
                                onConnect = {
                                    connectingId = entry.id
                                    scope.launch {
                                        runCatching {
                                            val token = repository.getOrCreateAuthToken()
                                            val client = ConnectionHolder.connect(
                                                serverUrl = ServerUrl(
                                                    host = entry.host,
                                                    port = entry.port,
                                                ),
                                                authToken = token,
                                                pinnedFingerprintHex = entry.pinnedFingerprintHex,
                                            )
                                            // TOFU: if this was a first-connect (no pin
                                            // stored yet) and the handshake captured a
                                            // leaf fingerprint, persist it so the next
                                            // connect runs in strict-verify mode.
                                            if (entry.pinnedFingerprintHex == null) {
                                                client.observedFingerprint.value?.let { fp ->
                                                    repository.updateHost(entry.copy(pinnedFingerprintHex = fp))
                                                }
                                            }
                                        }.onSuccess {
                                            connectingId = null
                                            onConnected()
                                        }.onFailure { e ->
                                            connectingId = null
                                            if (ConnectionHolder.isPinMismatch(e)) {
                                                pinMismatchEntry = entry
                                            } else {
                                                val msg = e.message ?: "Connection failed"
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        message = msg,
                                                        actionLabel = "Dismiss",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                onEdit = { editTarget = EditTarget.Edit(entry) },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
            // Discreet, always-visible entry into the built-in demo, pinned to
            // the bottom of the screen below both the empty state and the host
            // list so it never competes with the user's own servers.
            DemoFooter(
                connecting = connectingId == DEMO_ROW_ID,
                enabled = connectingId == null,
                onConnect = connectDemo,
            )
        }
    }

    editTarget?.let { target ->
        val initial = (target as? EditTarget.Edit)?.entry
        HostEditDialog(
            initial = initial,
            onDismiss = { editTarget = null },
            onSave = { label, host, port ->
                scope.launch {
                    if (initial == null) {
                        repository.addHost(label, host, port)
                    } else {
                        repository.updateHost(initial.copy(label = label, host = host, port = port))
                    }
                    editTarget = null
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        DeleteHostDialog(
            entry = entry,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    deleteTarget = null
                }
            },
        )
    }

    pinMismatchEntry?.let { entry ->
        PinMismatchDialog(
            entry = entry,
            onDismiss = { pinMismatchEntry = null },
            onRepair = {
                scope.launch {
                    // Clear the stored pin so the next tap runs first-connect
                    // (capture mode + server ApprovalDialog) again.
                    repository.updateHost(entry.copy(pinnedFingerprintHex = null))
                    pinMismatchEntry = null
                }
            },
            onForget = {
                scope.launch {
                    repository.deleteHost(entry.id)
                    pinMismatchEntry = null
                }
            },
        )
    }
}

/**
 * Placeholder UI shown when no hosts have been saved yet.
 *
 * Displays a friendly message and a button to add the first host. The entry
 * point into the built-in demo lives in the bottom-pinned [DemoFooter], which
 * is shown in this state too, so first-time users can still explore the app
 * without owning a server.
 *
 * @param onAdd callback invoked when the "Add host" button is tapped.
 */
@Composable
private fun EmptyState(
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = SidebarTextSecondary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No hosts yet",
            style = MaterialTheme.typography.titleMedium,
            color = SidebarTextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Add a server to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = SidebarTextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onAdd,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SidebarAccent,
                contentColor = SidebarBackground,
            ),
        ) {
            Text("Add host")
        }
    }
}

/**
 * Discreet, bottom-pinned entry into the built-in demo. Tapping it connects to
 * the in-process demo simulation (see the magic [DEMO_HOST] handling in the
 * shared client) — instant, offline, and stateless, so it needs no edit/delete
 * affordances.
 *
 * Rendered as a single muted row beneath a hairline divider, staying out of the
 * way of the user's own servers. External links (support forum, website, legal
 * pages) now live in the top bar's [AboutMenu] rather than here, keeping this
 * footer to the one demo affordance.
 *
 * @param connecting true while the demo connection is being set up.
 * @param enabled false while another host is connecting.
 * @param onConnect callback invoked when the footer is tapped.
 * @see AboutMenu
 */
@Composable
private fun DemoFooter(
    connecting: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = SidebarTextSecondary.copy(alpha = 0.2f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Live demo — the footer's sole affordance.
            Row(
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onConnect)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Try the live demo, no server needed"
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = SidebarTextSecondary,
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SidebarTextSecondary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Try the live demo",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = SidebarTextSecondary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A single row in the hosts list showing the label, host:port, and an
 * overflow menu for edit/delete actions.
 *
 * When a connection attempt is in progress, the overflow menu is replaced
 * by a progress spinner and optional "Waiting for approval" text.
 *
 * @param entry the host entry to render.
 * @param connecting true while a connection attempt is in progress for this entry.
 * @param pendingApproval true when the server is showing a device-approval dialog.
 * @param enabled false to disable tap interactions (while another host is connecting).
 * @param onConnect callback invoked when the row is tapped to connect.
 * @param onEdit callback invoked from the overflow menu to edit this entry.
 * @param onDelete callback invoked from the overflow menu to delete this entry.
 */
@Composable
private fun HostRow(
    entry: HostEntry,
    connecting: Boolean,
    pendingApproval: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onConnect)
            .semantics {
                role = Role.Button
                contentDescription = "${entry.label}, ${entry.host}:${entry.port}"
            }
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalGlyph()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = SidebarTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.host}:${entry.port}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = SidebarTextSecondary,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (connecting) {
            if (pendingApproval) {
                Text(
                    "Waiting for approval\u2026",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SidebarTextSecondary,
                    ),
                )
                Spacer(Modifier.width(6.dp))
            }
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Host options",
                        tint = SidebarTextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Dialog for adding or editing a host entry.
 *
 * Presents text fields for label, host, and port. The Save button is
 * disabled until all fields contain valid input.
 *
 * @param initial the existing entry to edit, or null when adding a new one.
 * @param onDismiss callback to close the dialog without saving.
 * @param onSave callback with the validated label, host, and port values.
 */
@Composable
private fun HostEditDialog(
    initial: HostEntry?,
    onDismiss: () -> Unit,
    onSave: (label: String, host: String, port: Int) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    // New hosts default to the port every Termtastic server listens on.
    var port by remember { mutableStateOf(initial?.port?.toString() ?: SERVER_TLS_PORT.toString()) }

    val parsedPort = port.toIntOrNull()
    val canSave = label.isNotBlank() && host.isNotBlank() && parsedPort != null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text(if (initial == null) "Add host" else "Edit host") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    supportingText = { Text("A name for this server, shown in the host list") },
                    singleLine = true,
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    supportingText = { Text("Hostname or IP address of your Mac running Termtastic") },
                    singleLine = true,
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    supportingText = { Text("Servers listen on $SERVER_TLS_PORT by default") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = themedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (parsedPort != null && canSave) onSave(label.trim(), host.trim(), parsedPort) },
                enabled = canSave,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) {
                Text("Save")
            }
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
 * Dialog shown when the server's TLS leaf certificate no longer matches the
 * fingerprint pinned during the first successful connect. This is either a
 * benign event (server was reinstalled / regenerated its cert) or evidence
 * of an active MITM attempt.
 *
 * Three exits:
 *  - **Re-pair**: clears [HostEntry.pinnedFingerprintHex] so the next tap
 *    runs first-connect (TOFU capture + server `ApprovalDialog`) again.
 *  - **Forget**: deletes the host entry.
 *  - **Cancel**: dismisses without changing anything; user can retry later.
 *
 * @param entry the host entry whose pin mismatched.
 * @param onDismiss invoked when the user picks Cancel or taps outside.
 * @param onRepair invoked when the user picks Re-pair.
 * @param onForget invoked when the user picks Forget.
 */
@Composable
private fun PinMismatchDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onRepair: () -> Unit,
    onForget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Server certificate changed") },
        text = {
            Text(
                "The server at \"${entry.label}\" (${entry.host}:${entry.port}) is " +
                    "presenting a different certificate than the one you paired with. " +
                    "This could mean the server was reinstalled, or someone is trying " +
                    "to intercept your connection.\n\n" +
                    "Re-pair if you trust the new certificate; Forget to remove the host.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onRepair,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarAccent),
            ) { Text("Re-pair") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onForget,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
                ) { Text("Cancel") }
            }
        },
    )
}

/**
 * Confirmation dialog shown before deleting a host entry.
 *
 * @param entry the host entry about to be deleted.
 * @param onDismiss callback to close the dialog without deleting.
 * @param onConfirm callback to proceed with deletion.
 */
@Composable
private fun DeleteHostDialog(
    entry: HostEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SidebarSurface,
        titleContentColor = SidebarTextPrimary,
        textContentColor = SidebarTextSecondary,
        title = { Text("Delete host?") },
        text = { Text("\"${entry.label}\" will be removed from this device.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
            ) { Text("Cancel") }
        },
    )
}

/** Small 16dp terminal-pane glyph, inlined from TreeScreen's PaneIcon (non-floating variant). */
@Composable
private fun TerminalGlyph() {
    val tint = SidebarTextSecondary.copy(alpha = 0.7f)
    Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = "Terminal" }) {
        val w = size.width
        val px = w / 16f
        val stroke = Stroke(
            width = 1.3f * px,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(1f * px, 2f * px),
            size = Size(14f * px, 12f * px),
            cornerRadius = CornerRadius(1.5f * px, 1.5f * px),
            style = stroke,
        )
        val chevron = Path().apply {
            moveTo(4f * px, 7f * px)
            lineTo(6f * px, 5f * px)
            lineTo(4f * px, 3f * px)
        }
        drawPath(chevron, color = tint, style = stroke)
        drawLine(
            color = tint,
            start = Offset(7f * px, 7f * px),
            end = Offset(11f * px, 7f * px),
            strokeWidth = 1.2f * px,
            cap = StrokeCap.Round,
        )
    }
}
