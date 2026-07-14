/**
 * Hosts/servers list screen for the Lunamux Android app.
 *
 * This is the app's landing screen. It displays the user's saved server
 * entries from the shared [se.soderbjorn.lunamux.client.storage.LocalRepository]
 * (via [se.soderbjorn.lunamux.android.data.AppLocalRepository]) and provides
 * add/edit/delete dialogs. Tapping a host initiates a WebSocket connection via
 * [se.soderbjorn.lunamux.android.net.ConnectionHolder] and, on success,
 * navigates to the [TreeScreen] overview.
 *
 * The screen is also the QR pairing entry point: the top-bar scanner (and the
 * `lunamux://pair` deep link relayed through
 * [se.soderbjorn.lunamux.android.PendingPairingUri]) parses a
 * [se.soderbjorn.lunamux.PairingPayload], saves or updates the host entry,
 * and connects immediately — scan → connected, with no approval dialog.
 *
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
 * @see se.soderbjorn.lunamux.PairingPayload
 * @see TreeScreen
 */
package se.soderbjorn.lunamux.android.ui

import android.content.Context
import android.util.Log
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
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.HostPort
import se.soderbjorn.lunamux.PairingPayload
import se.soderbjorn.lunamux.SERVER_TLS_PORT
import se.soderbjorn.lunamux.android.PendingPairingUri
import se.soderbjorn.lunamux.android.data.AppLocalRepository
import se.soderbjorn.lunamux.android.net.NetworkStatus
import se.soderbjorn.lunamux.client.HostEntry
import se.soderbjorn.lunamux.android.net.ConnectionHolder
import se.soderbjorn.lunamux.android.net.ServerUnreachableException
import se.soderbjorn.lunamux.android.net.NewsUpdatesController
import se.soderbjorn.lunamux.client.ServerUrl
import se.soderbjorn.lunamux.client.demo.DEMO_HOST

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
 * host initiates a WebSocket connection to the Lunamux server; on success
 * the [onConnected] callback navigates to the tree overview.
 *
 * Shows a "Waiting for approval..." label when the server has a pending
 * device-approval dialog for this client.
 *
 * @param applicationContext Android application context, forwarded to the
 *   [se.soderbjorn.lunamux.android.net.NewsUpdatesController].
 * @param onConnected callback invoked after a successful connection to a host.
 * @param onOpenNews callback invoked when the toolbar bell is tapped; opens the
 *   "News & Updates" screen.
 * @see se.soderbjorn.lunamux.android.data.AppLocalRepository
 * @see se.soderbjorn.lunamux.android.net.ConnectionHolder
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

    // Connect to a saved host entry: walks its candidate endpoints in order
    // (a paired entry carries every address the server advertised) and, on
    // success, persists the winner in one write — preferred host/port
    // promoted, TOFU pin captured if this was a pinless first connect, spent
    // pairing token cleared. Failures produce connectivity-aware messages
    // instead of a generic timeout.
    val connectToEntry: (HostEntry) -> Unit = { entry ->
        connectingId = entry.id
        scope.launch {
            runCatching {
                val token = repository.getOrCreateAuthToken()
                val preferred = HostPort(entry.host, entry.port).toCandidateString()
                val connection = ConnectionHolder.connectMulti(
                    candidates = listOf(preferred) + (entry.candidates - preferred),
                    defaultPort = entry.port,
                    authToken = token,
                    pinnedFingerprintHex = entry.pinnedFingerprintHex,
                    pairingToken = entry.pairingToken,
                )
                val pin = entry.pinnedFingerprintHex
                    ?: connection.client.observedFingerprint.value
                val updated = entry.copy(
                    host = connection.endpoint.host,
                    port = connection.endpoint.port,
                    pinnedFingerprintHex = pin,
                    pairingToken = null,
                )
                if (updated != entry) repository.updateHost(updated)
            }.onSuccess {
                connectingId = null
                onConnected()
            }.onFailure { e ->
                connectingId = null
                if (ConnectionHolder.isPinMismatch(e)) {
                    pinMismatchEntry = entry
                } else {
                    val msg = connectFailureMessage(applicationContext, e)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = msg,
                            actionLabel = "Dismiss",
                        )
                    }
                }
            }
        }
    }

    // Handle a scanned QR / deep-linked pairing URI: parse, dedupe against
    // existing entries (same server = same cert fingerprint or overlapping
    // endpoints — the re-pair path also refreshes a rotated cert's pin), save,
    // and connect straight away.
    val handlePairingUri: (String) -> Unit = { uri ->
        val payload = PairingPayload.parse(uri)
        if (payload == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "That doesn't look like a Lunamux pairing code",
                    actionLabel = "Dismiss",
                )
            }
        } else {
            scope.launch {
                val candidateStrings = payload.candidates.map { it.toCandidateString() }
                val preferred = payload.candidates.first()
                val existing = repository.ensureLoaded().hosts.firstOrNull { host ->
                    host.pinnedFingerprintHex == payload.fingerprintHex ||
                        HostPort(host.host, host.port).toCandidateString() in candidateStrings ||
                        host.candidates.any { it in candidateStrings }
                }
                val entry = if (existing != null) {
                    existing.copy(
                        host = preferred.host,
                        port = preferred.port,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        candidates = candidateStrings,
                        pairingToken = payload.token,
                    ).also { repository.updateHost(it) }
                } else {
                    repository.addPairedHost(
                        label = payload.serverName ?: "Paired Mac",
                        host = preferred.host,
                        port = preferred.port,
                        pinnedFingerprintHex = payload.fingerprintHex,
                        candidates = candidateStrings,
                        pairingToken = payload.token,
                    )
                }
                connectToEntry(entry)
            }
        }
    }

    // QR scanner: the Google code scanner supplies its own UI and runs the
    // camera inside a Play Services process, so there is no CAMERA permission
    // and no runtime prompt to handle here — startScan() goes straight to the
    // viewfinder. It needs Play Services, this APK's only such dependency;
    // where that is missing or the module can't be fetched, the failure
    // listener explains it and manual add-host still works.
    val scanner = remember {
        GmsBarcodeScanning.getClient(
            applicationContext,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val startScan: () -> Unit = {
        scanner.startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(handlePairingUri) }
            // Cancellation is just the user backing out; say nothing.
            .addOnCanceledListener { }
            .addOnFailureListener { e ->
                Log.w("HostsScreen", "code scanner unavailable", e)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Couldn't open the scanner. Add the Mac's address " +
                            "manually with +, or check Google Play services.",
                        actionLabel = "Dismiss",
                    )
                }
            }
    }

    // Pairing deep link (system camera / browser → lunamux://pair): the
    // activity posts it into PendingPairingUri; consume() clears the slot so
    // recompositions and config changes can't pair twice.
    val pendingPairing by PendingPairingUri.uri.collectAsState()
    LaunchedEffect(pendingPairing) {
        if (pendingPairing != null) {
            PendingPairingUri.consume()?.let(handlePairingUri)
        }
    }

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
                    IconButton(onClick = startScan) {
                        Icon(
                            Icons.Filled.QrCodeScanner,
                            contentDescription = "Scan pairing code",
                            tint = SidebarAccent,
                        )
                    }
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
                        onScan = startScan,
                        onAdd = { editTarget = EditTarget.Add },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(list, key = { it.id }) { entry ->
                            HostRow(
                                entry = entry,
                                connecting = connectingId == entry.id,
                                pendingApproval = pendingApproval && connectingId == entry.id,
                                enabled = connectingId == null,
                                onConnect = { connectToEntry(entry) },
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
 * Pairing by QR is the primary path (scan → connected, no typing, no
 * approval dialog); manual entry is demoted to a secondary action. The entry
 * point into the built-in demo lives in the bottom-pinned [DemoFooter], which
 * is shown in this state too, so first-time users can still explore the app
 * without owning a server.
 *
 * @param onScan callback invoked when the "Scan pairing code" button is tapped.
 * @param onAdd callback invoked when the secondary "Add manually" action is tapped.
 */
@Composable
private fun EmptyState(
    onScan: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.QrCodeScanner,
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
            "On your Mac: Go to \"Lunamux > Settings > Server & Security… > Devices\" " +
                "and tick \"Allow connections from other devices\" and then press " +
                "\"Pair via QR Code\" - and then scan the code here.",
            style = MaterialTheme.typography.bodyMedium,
            color = SidebarTextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = onScan,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = SidebarAccent,
                contentColor = SidebarBackground,
            ),
        ) {
            Text("Scan pairing code")
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onAdd,
            colors = ButtonDefaults.textButtonColors(contentColor = SidebarTextSecondary),
        ) {
            Text("Add manually")
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
    // New hosts default to the port every Lunamux server listens on.
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
                    supportingText = {
                        Text(
                            "Hostname or IP address of your Mac running Lunamux. " +
                                "Your phone must be on the same Wi-Fi network.",
                        )
                    },
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

/**
 * Build a connection-failure message that explains the likely cause instead
 * of echoing a transport error. The server is only reachable on its own
 * network, so the phone's current transport is the single most useful hint:
 * a phone on mobile data can't reach a LAN host at all, and a phone on the
 * wrong Wi-Fi looks identical to a dead server.
 *
 * Called from the hosts screen's connect failure path (non-pin-mismatch).
 *
 * @param context used to query [NetworkStatus]; any context works.
 * @param e the connect failure, used verbatim when the transport is unknown.
 * @return a user-facing message for the snackbar.
 */
private fun connectFailureMessage(context: Context, e: Throwable): String = when {
    // A device-auth rejection reaches the server but is turned away before the
    // first config (expired/foreign pairing token, allow-remote off, or a
    // revoked device). Its raw exception text is developer-facing ("…before
    // sending a config… check the server's log for…"), so translate the known
    // case into something the user can act on.
    isDeviceAuthRejection(e) ->
        "The Mac declined this connection. Re-pair or approve this device on your " +
            "Mac: Lunamux > Settings > Server & Security… > Devices > Pair a device."
    // Network-blame only when we genuinely couldn't reach the server. A
    // phase-2 failure (reached, but the server rejected the device / never
    // sent a config) carries a descriptive message that we must not mask
    // with "check your Wi-Fi" advice.
    e !is ServerUnreachableException -> e.message ?: "Connection failed"
    NetworkStatus.isOnCellular(context) ->
        "You're on mobile data. This Mac is reachable on its own Wi-Fi network — " +
            "join that network and try again."
    NetworkStatus.isOnWifi(context) ->
        "Couldn't reach the Mac. Make sure this phone is on the same Wi-Fi " +
            "network as your computer."
    else -> e.message ?: "Connection failed"
}

/**
 * Whether [e] is the post-handshake device-auth rejection thrown by
 * [se.soderbjorn.termtastic.client.WindowSocket.awaitInitialConfig] when the
 * server accepts the socket but closes it before the first config. Matched on
 * the exception's distinctive phrase rather than its type so [connectMulti]'s
 * phase-2 failure keeps a friendly, actionable message.
 *
 * @param e the connect failure surfaced to [connectFailureMessage].
 * @return true when the failure is a device-auth rejection.
 */
private fun isDeviceAuthRejection(e: Throwable): Boolean =
    e.message?.contains("before sending a config") == true

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
