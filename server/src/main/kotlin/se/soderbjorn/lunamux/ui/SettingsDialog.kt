/**
 * Server-side settings UI rendered via Compose Desktop.
 *
 * This file contains [SettingsDialog], a process-wide singleton Compose
 * Desktop window that provides the Lunamux settings interface. The
 * controls are split across two tabs so the MCP configuration doesn't
 * clutter the everyday network/device controls:
 *  - **General** tab:
 *    - Network settings (allow remote connections, display listening port/IPs).
 *    - Claude Code usage polling toggle.
 *    - Trusted device management (list, revoke).
 *    - Denied device management (list, unban).
 *  - **MCP** tab:
 *    - MCP server kill switch, token minting/scoping/revoking, the
 *      ready-to-paste `.mcp.json` snippet, recent agent-activity log, and
 *      the TLS-trust line for Node clients.
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
package se.soderbjorn.lunamux.ui

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.tls.CertStore
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

    /** Lunamux dark-theme blue used for accents throughout the dialog. */
    private val lunamuxBlue = Color(0xFF0A84FF)

    val lunamuxColorScheme = darkColorScheme(
        primary = lunamuxBlue,
        onPrimary = Color.White,
        secondary = lunamuxBlue,
        onSecondary = Color.White,
    )

    private val brandImage by lazy {
        runCatching {
            SettingsDialog::class.java.getResourceAsStream("/lunamux-icon.png")
                ?.use { ImageIO.read(it) }
        }.onFailure { log.warn("Failed to load lunamux-icon.png for settings dialog", it) }
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
            title = "Lunamux \u2014 Settings",
            state = windowState,
            icon = iconPainter,
            alwaysOnTop = true,
            resizable = true,
            visible = isShowing && currentRepo != null,
        ) {
            if (currentRepo != null) {
                MaterialTheme(colorScheme = lunamuxColorScheme) {
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
     * The sections are split across two tabs so the MCP configuration
     * (token minting, scopes, the `.mcp.json` snippet, agent-activity log,
     * TLS-trust line) doesn't clutter the everyday network/device controls:
     *  - **General** — Network, Claude Code, Trusted / Denied devices.
     *  - **MCP** — [McpSection] on its own.
     *
     * The header and the tab bar stay pinned at the top; only the selected
     * tab's section stack scrolls (with the scrollbar scoped to that area).
     *
     * @param repo settings repository backing the dialog
     * @param isShowing current visibility of the dialog window; flipping
     *   to true triggers a fresh read of trusted/denied device lists
     * @see DeniedDevicesSection
     * @see TrustedDevicesSection
     * @see McpSection
     */
    @Composable
    private fun SettingsContent(repo: SettingsRepository, isShowing: Boolean) {
        val scrollState = rememberScrollState()
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("General", "MCP")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp),
        ) {
            // Header (pinned above the tabs — does not scroll).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                brandImage?.toComposeImageBitmap()?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Lunamux icon",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(22f)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        "Lunamux",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Settings",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Tab bar (pinned) — selecting a tab swaps the scrollable body below.
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tabTitle ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tabTitle) },
                    )
                }
            }

            // Scrollable body for the selected tab. The scrollbar is scoped to
            // this Box so it tracks the section stack rather than the header/tabs.
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp)
                        .verticalScroll(scrollState),
                ) {
                    when (selectedTab) {
                        1 -> {
                            McpSection(repo, isShowing)
                        }
                        else -> {
                            NetworkSection(repo)
                            Spacer(Modifier.height(16.dp))
                            ClaudeUsageSection(repo)
                            Spacer(Modifier.height(16.dp))
                            TrustedDevicesSection(repo, isShowing)
                            Spacer(Modifier.height(16.dp))
                            DeniedDevicesSection(repo, isShowing)
                        }
                    }
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
                        hoverColor = lunamuxBlue.copy(alpha = 0.6f),
                    ),
                )
            }
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
     * Render the "MCP" section: the global kill switch, token minting with
     * a ready-to-paste `.mcp.json` snippet, the scope/revoke list for
     * existing MCP tokens, and the TLS-trust instructions for Node-based
     * clients (`NODE_EXTRA_CA_CERTS` pointing at the exported leaf PEM).
     *
     * Called from [SettingsContent]. Tokens are stored hashed, so the raw
     * token (and the snippet embedding it) is only shown for the token
     * generated in this dialog session — once the dialog closes it cannot
     * be recovered, only revoked and re-minted.
     *
     * @param repo settings repository backing the kill switch and token list
     * @param refreshKey changing this value re-reads the token list (the
     *   dialog window stays composed while hidden — see [TrustedDevicesSection])
     * @see DeviceAuth.addTrustedToken
     * @see DeviceAuth.listMcpTokens
     * @see CertStore.leafPemFile
     */
    @Composable
    private fun McpSection(repo: SettingsRepository, refreshKey: Any) {
        SectionHeader("MCP server")

        var enabled by remember { mutableStateOf(repo.isMcpEnabled()) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = {
                enabled = !enabled
                repo.setMcpEnabled(enabled)
                log.info("Settings: MCP enabled toggled to {}", enabled)
            }),
        ) {
            Checkbox(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    repo.setMcpEnabled(it)
                    log.info("Settings: MCP enabled toggled to {}", it)
                },
            )
            Text("Enable MCP server (/mcp, localhost only)", fontSize = 14.sp)
        }

        Spacer(Modifier.height(8.dp))

        var tokens by remember(refreshKey) { mutableStateOf(DeviceAuth.listMcpTokens(repo)) }
        // Raw token + snippet for the token minted in this dialog session.
        var freshSnippet by remember(refreshKey) { mutableStateOf<String?>(null) }

        Button(onClick = {
            val raw = generateMcpToken()
            DeviceAuth.addTrustedToken(repo, raw, DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
            tokens = DeviceAuth.listMcpTokens(repo)
            freshSnippet = mcpJsonSnippet(raw)
            log.info("Settings: generated a new MCP token (scope=read)")
        }) {
            Text("Generate MCP token")
        }

        freshSnippet?.let { snippet ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Paste into your project's .mcp.json (shown once — copy it now):",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(snippet, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Row {
                Button(onClick = { copyToClipboard(snippet) }) { Text("Copy snippet") }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (tokens.isEmpty()) {
            Text(
                "No MCP tokens yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        } else {
            val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }
            tokens.forEach { token ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "MCP token #${token.tokenHash.take(10)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            "scope: ${token.scope ?: "?"} · last used " +
                                df.format(Date(token.lastSeenEpochMs)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    val isWrite = token.scope == DeviceAuth.MCP_SCOPE_READ_WRITE
                    Button(onClick = {
                        val newScope = if (isWrite) DeviceAuth.MCP_SCOPE_READ
                        else DeviceAuth.MCP_SCOPE_READ_WRITE
                        DeviceAuth.setMcpTokenScope(repo, token.tokenHash, newScope)
                        tokens = DeviceAuth.listMcpTokens(repo)
                        log.info("Settings: MCP token {} scope set to {}", token.tokenHash.take(10), newScope)
                    }) {
                        Text(if (isWrite) "Make read-only" else "Allow writes")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        DeviceAuth.revokeTrustedDevice(repo, token.tokenHash)
                        tokens = DeviceAuth.listMcpTokens(repo)
                        log.info("Settings: revoked MCP token {}", token.tokenHash.take(10))
                    }) {
                        Text("Revoke")
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        // Compact agent-activity list: the most recent MCP write calls
        // (tool + redacted args), newest first. Re-read on each dialog show.
        val activity = remember(refreshKey) { se.soderbjorn.lunamux.mcp.McpActivityLog.recent(12) }
        if (activity.isNotEmpty()) {
            Text("Recent agent activity:", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val adf = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }
            activity.forEach { entry ->
                Text(
                    "${adf.format(Date(entry.atEpochMs))}  ${entry.tool}  ${entry.detail}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        val pemPath = remember { CertStore.leafPemFile().absolutePath }
        Text(
            "TLS trust for Node clients (Claude Code): export before launching —",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "NODE_EXTRA_CA_CERTS=$pemPath",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Row {
            Button(onClick = { copyToClipboard("NODE_EXTRA_CA_CERTS=$pemPath") }) {
                Text("Copy env line")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }

    /** Mint a fresh 256-bit hex MCP token. */
    private fun generateMcpToken(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return "mcp-" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the ready-to-paste `.mcp.json` snippet embedding [rawToken] and
     * the server's live port.
     */
    private fun mcpJsonSnippet(rawToken: String): String {
        val port = listeningPort ?: 8443
        return """
            {
              "mcpServers": {
                "lunamux": {
                  "type": "http",
                  "url": "https://localhost:$port/mcp",
                  "headers": { "X-Termtastic-Auth": "$rawToken" }
                }
              }
            }
        """.trimIndent()
    }

    /** Copy [text] to the system clipboard (best effort). */
    private fun copyToClipboard(text: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(text), null,
            )
        }.onFailure { log.warn("Failed to copy to clipboard", it) }
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
