/**
 * Server-side settings UI rendered via Compose Desktop.
 *
 * This file contains [SettingsDialog], a process-wide singleton Compose
 * Desktop window that provides the Termtastic settings interface. It exposes
 * controls for:
 *  - Network settings (allow remote connections, display listening port/IPs).
 *  - Claude Code usage polling toggle.
 *  - Trusted device management (list, revoke).
 *  - Denied device management (list, unban).
 *
 * The dialog is opened by the `OpenSettings` command from the `/window`
 * WebSocket (triggered by the client's settings button). Settings take
 * effect immediately -- there is no "Apply" button; the user dismisses
 * the dialog via the window close control.
 *
 * Silently no-ops in headless JVM environments (e.g. when running without
 * a display server).
 *
 * @see DeviceAuth
 * @see SettingsRepository
 * @see ClaudeUsageMonitor
 */
package se.soderbjorn.termtastic.ui

import se.soderbjorn.darkness.core.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.ClaudeUsageMonitor
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Process-wide Compose Desktop settings window.
 *
 * Only one instance is kept: if [show] is called while a dialog is already on
 * screen, the existing instance is brought to the front instead of spawning a
 * duplicate. Settings mutate the backing [SettingsRepository] immediately --
 * there is no "Apply" button; dismiss via the window's close control.
 */
object SettingsDialog {

    private val log = LoggerFactory.getLogger(SettingsDialog::class.java)

    @Volatile
    private var listeningPort: Int? = null

    @Volatile
    var usageMonitor: ClaudeUsageMonitor? = null

    // Guarded by the Compose UI thread — mutations must happen on the AWT EDT
    // so Compose Desktop's recomposer picks up the change.
    private val showing = mutableStateOf(false)
    private var repo: SettingsRepository? = null

    /**
     * Record the Ktor server's listening port so the Network section can
     * display it. Called once from [Application.main] after the port is resolved.
     *
     * @param port the TCP port the server is listening on
     */
    fun setListeningPort(port: Int) {
        listeningPort = port
    }

    /** Tron-style green used for accents throughout the dialog. */
    private val tronGreen = Color(0xFF00FF9C)

    val tronColorScheme = darkColorScheme(
        primary = tronGreen,
        onPrimary = Color.Black,
        secondary = tronGreen,
        onSecondary = Color.Black,
    )

    private val brandImage by lazy {
        runCatching {
            SettingsDialog::class.java.getResourceAsStream("/termtastic-icon.png")
                ?.use { ImageIO.read(it) }
        }.onFailure { log.warn("Failed to load termtastic-icon.png for settings dialog", it) }
            .getOrNull()
    }

    private val iconPainter by lazy {
        brandImage?.toComposeImageBitmap()?.let { BitmapPainter(it) }
    }

    /**
     * Pop the dialog on screen. Silently no-ops in headless JVMs.
     */
    fun show(repo: SettingsRepository) {
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Ignoring settings-dialog request in headless mode")
            return
        }
        // Dispatch onto the AWT EDT so Compose Desktop's recomposer observes
        // the state change. show() is called from Ktor worker threads.
        SwingUtilities.invokeLater {
            this.repo = repo
            showing.value = true
        }
    }

    /**
     * Call from a top-level Compose application scope (or wrapped in an
     * `application {}` block) so the window lifecycle is managed by Compose.
     *
     * The Window is always part of the composition tree (so Compose keeps
     * its recomposer active) but toggled via [visible]. This avoids the
     * problem where an initially-empty application scope has no frame
     * clock to drive recomposition of state changes.
     */
    @Composable
    fun renderIfShowing() {
        val isShowing by showing
        val currentRepo = repo

        val windowState = rememberWindowState(
            size = DpSize(560.dp, 620.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        Window(
            onCloseRequest = { showing.value = false },
            title = "Termtastic \u2014 Settings",
            state = windowState,
            icon = iconPainter,
            alwaysOnTop = true,
            resizable = true,
            visible = isShowing && currentRepo != null,
        ) {
            if (currentRepo != null) {
                MaterialTheme(colorScheme = tronColorScheme) {
                    Surface {
                        SettingsContent(currentRepo, isShowing)
                    }
                }
            }
        }
    }

    /**
     * Compose the body of the settings window.
     *
     * Called from [renderIfShowing] whenever the window is part of the
     * composition tree. The [isShowing] flag is forwarded to the device
     * sections as a refresh key so their lists are re-read from the repo
     * each time the dialog is reopened — the Window is kept in the
     * composition permanently and only its `visible` flag is toggled, so
     * unkeyed `remember { }` would otherwise cache the lists for the
     * lifetime of the process.
     *
     * @param repo settings repository backing the dialog
     * @param isShowing current visibility of the dialog window; flipping
     *   to true triggers a fresh read of trusted/denied device lists
     * @see DeniedDevicesSection
     * @see TrustedDevicesSection
     */
    @Composable
    private fun SettingsContent(repo: SettingsRepository, isShowing: Boolean) {
        val scrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp)
                .verticalScroll(scrollState),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                brandImage?.toComposeImageBitmap()?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Termtastic icon",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(22f)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        "Termtastic",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Settings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            NetworkSection(repo)
            Spacer(Modifier.height(16.dp))
            ClaudeUsageSection(repo)
            Spacer(Modifier.height(16.dp))
            TrustedDevicesSection(repo, isShowing)
            Spacer(Modifier.height(16.dp))
            DeniedDevicesSection(repo, isShowing)
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 4.dp),
            style = ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = Color.White.copy(alpha = 0.25f),
                hoverColor = tronGreen.copy(alpha = 0.6f),
            ),
        )
        }
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun NetworkSection(repo: SettingsRepository) {
        SectionHeader("Network")

        val port = listeningPort
        val addresses = remember { localIpv4Addresses() }

        Text("Loopback: 127.0.0.1", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        if (addresses.isEmpty()) {
            Text(
                "No non-loopback IPv4 interfaces found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            Text(
                "LAN: ${addresses.joinToString(", ")}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        if (port != null) {
            Text("Port: $port", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        var allowRemote by remember { mutableStateOf(repo.isAllowRemoteConnections()) }
        val toggleAllowRemote = {
            allowRemote = !allowRemote
            repo.setAllowRemoteConnections(allowRemote)
            log.info("Settings: allow-remote toggled to {}", allowRemote)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = toggleAllowRemote),
        ) {
            Checkbox(
                checked = allowRemote,
                onCheckedChange = {
                    allowRemote = it
                    repo.setAllowRemoteConnections(it)
                    log.info("Settings: allow-remote toggled to {}", it)
                },
            )
            Text("Allow connections from other devices", fontSize = 14.sp)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun ClaudeUsageSection(repo: SettingsRepository) {
        SectionHeader("Claude Code")

        var pollUsage by remember { mutableStateOf(repo.isClaudeUsagePollEnabled()) }
        val togglePollUsage = {
            pollUsage = !pollUsage
            repo.setClaudeUsagePollEnabled(pollUsage)
            val monitor = usageMonitor
            if (monitor != null) {
                if (pollUsage) monitor.start() else monitor.stop()
            }
            log.info("Settings: claude-usage-poll toggled to {}", pollUsage)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = togglePollUsage),
        ) {
            Checkbox(
                checked = pollUsage,
                onCheckedChange = {
                    pollUsage = it
                    repo.setClaudeUsagePollEnabled(it)
                    val monitor = usageMonitor
                    if (monitor != null) {
                        if (it) monitor.start() else monitor.stop()
                    }
                    log.info("Settings: claude-usage-poll toggled to {}", it)
                },
            )
            Text("Poll Claude Code usage data", fontSize = 14.sp)
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /**
     * Render the "Trusted devices" section.
     *
     * Called from [SettingsContent]. Lists every device persisted by
     * [DeviceAuth.persistApprovedDevice] and offers a "Revoke" action per
     * row. The list is read from [repo] each time [refreshKey] changes —
     * callers pass the dialog's visibility flag so a fresh snapshot is
     * loaded whenever the window becomes visible (the Window stays in
     * the composition tree, so an unkeyed `remember { }` would only read
     * once on first show).
     *
     * @param repo settings repository to read trusted devices from
     * @param refreshKey changing this value discards the cached list and
     *   re-reads from the repo on the next composition
     * @see DeviceAuth.listTrustedDevices
     * @see DeviceAuth.revokeTrustedDevice
     */
    @Composable
    private fun TrustedDevicesSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("Trusted devices")

        var devices by remember(refreshKey) { mutableStateOf(DeviceAuth.listTrustedDevices(repo)) }
        val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }

        if (devices.isEmpty()) {
            Text(
                "No trusted devices yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    label = device.label?.takeIf { it.isNotBlank() } ?: "Device",
                    hashPrefix = device.tokenHash.take(10),
                    lastSeen = "last seen ${df.format(Date(device.lastSeenEpochMs))} from ${device.lastIp}",
                    connections = device.connections,
                    actionLabel = "Revoke",
                    df = df,
                    onAction = {
                        val removed = DeviceAuth.revokeTrustedDevice(repo, device.tokenHash)
                        log.info(
                            "Settings: revoke trusted device {} removed={}",
                            device.tokenHash.take(10),
                            removed,
                        )
                        devices = DeviceAuth.listTrustedDevices(repo)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /**
     * Render the "Denied devices" section.
     *
     * Called from [SettingsContent]. Lists every device the user has
     * explicitly denied via [DeviceAuth.promptOrReject] and offers an
     * "Unban" action per row. The list is read from [repo] each time
     * [refreshKey] changes — callers pass the dialog's visibility flag
     * so a fresh snapshot is loaded whenever the window becomes visible.
     * Without this, denials persisted while the dialog was closed would
     * not appear on reopen, because the Window stays in the composition
     * tree and an unkeyed `remember { }` only reads once on first show.
     *
     * @param repo settings repository to read denied devices from
     * @param refreshKey changing this value discards the cached list and
     *   re-reads from the repo on the next composition
     * @see DeviceAuth.listDeniedDevices
     * @see DeviceAuth.unbanDeniedDevice
     */
    @Composable
    private fun DeniedDevicesSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("Denied devices")

        var devices by remember(refreshKey) { mutableStateOf(DeviceAuth.listDeniedDevices(repo)) }
        val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }

        if (devices.isEmpty()) {
            Text(
                "No denied devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    label = "Denied",
                    hashPrefix = device.tokenHash.take(10),
                    lastSeen = "last attempt ${df.format(Date(device.lastSeenEpochMs))} from ${device.lastIp}",
                    connections = device.connections,
                    actionLabel = "Unban",
                    df = df,
                    onAction = {
                        val removed = DeviceAuth.unbanDeniedDevice(repo, device.tokenHash)
                        log.info(
                            "Settings: unban denied device {} removed={}",
                            device.tokenHash.take(10),
                            removed,
                        )
                        devices = DeviceAuth.listDeniedDevices(repo)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun DeviceRow(
        label: String,
        hashPrefix: String,
        lastSeen: String,
        connections: List<DeviceAuth.ClientConnectionInfo>,
        actionLabel: String,
        df: SimpleDateFormat,
        onAction: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "#$hashPrefix",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    lastSeen,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (connections.isNotEmpty()) {
                    Text(
                        "clients:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    val sorted = connections.sortedByDescending { it.lastSeenEpochMs }
                    sorted.forEach { c ->
                        val host = c.hostname?.takeIf { it.isNotBlank() }
                        val selfIp = c.selfReportedIp?.takeIf { it.isNotBlank() }
                        val hostPart = when {
                            host != null && selfIp != null -> "$host ($selfIp)"
                            host != null -> host
                            selfIp != null -> selfIp
                            else -> c.remoteAddress
                        }
                        val lastSeenClient = df.format(Date(c.lastSeenEpochMs))
                        Text(
                            "  \u2022 ${c.type} \u2014 $hostPart ($lastSeenClient)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }

    /**
     * Enumerate all non-loopback IPv4 addresses on the machine's network
     * interfaces. Used by the Network section to show available LAN addresses.
     *
     * @return distinct list of IPv4 address strings
     */
    private fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nic in nics) {
                if (!nic.isUp || nic.isLoopback || nic.isVirtual) continue
                for (addr in nic.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        result.add(addr.hostAddress)
                    }
                }
            }
        }.onFailure { log.warn("Failed to enumerate network interfaces", it) }
        return result.distinct()
    }
}
