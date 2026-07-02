/**
 * Device authorization flow for Termtastic.
 *
 * This file contains [DeviceAuth], which implements a per-device token-based
 * auth gate. Each client generates a unique token on first launch and sends it
 * as a cookie, query parameter, or header. Unknown tokens trigger an interactive
 * Compose Desktop approval dialog on the server; approved token hashes are
 * persisted in SQLite so subsequent connections are silent.
 *
 * Key responsibilities:
 *  - [DeviceAuth.authorize] -- the main entry point, called from every HTTP
 *    route and WebSocket handler in Application.kt.
 *  - [DeviceAuth.checkFastPath] -- non-blocking pre-check for known/denied
 *    tokens (used by the `/window` WebSocket to send a "pending approval"
 *    frame before blocking).
 *  - Trusted and denied device lists with revoke/unban operations, surfaced
 *    in the [SettingsDialog].
 *  - Network-scope gate: rejects non-loopback connections unless the user
 *    has opted in via [SettingsRepository.isAllowRemoteConnections].
 *
 * @see SettingsRepository
 * @see SettingsDialog
 */
package se.soderbjorn.termtastic.auth

import se.soderbjorn.darkness.core.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import se.soderbjorn.termtastic.ui.SettingsDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Device-approval auth gate.
 *
 * A client sends a per-device token (generated once on first launch, stored
 * in its localStorage, carried as a cookie). Unknown tokens trigger an
 * approval dialog on the server's desktop; approved hashes are persisted in
 * SQLite so subsequent connections are silent. See docs/device-auth-plan.md.
 */
object DeviceAuth {

    private val log = LoggerFactory.getLogger(DeviceAuth::class.java)

    // Serialize approval prompts: two concurrent unknown connections must not
    // pop two dialogs at once — the user should see them one at a time.
    private val approvalMutex = Mutex()

    // On boot the client opens /api/ui-settings + /window + one /pty/{id} per
    // pre-existing session in parallel, all with the same unknown token. The
    // mutex alone isn't enough: it serializes the prompts, but each waiter
    // would still show its own dialog once it got the lock. This cache lets
    // a single APPROVED/REJECTED decision for a given token hash cover every
    // queued-up duplicate within a short window. Entries auto-expire so a
    // user who clicks Deny by mistake can retry by reloading shortly after.
    // All access happens inside [approvalMutex] so no extra locking is needed.
    private data class CachedDecision(val decision: Decision, val expiresAtMs: Long)
    private val recentDecisions = HashMap<String, CachedDecision>()
    private const val RECENT_DECISION_TTL_MS: Long = 10_000

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private const val TRUSTED_DEVICES_KEY = "auth.trusted_devices.v1"
    private const val DENIED_DEVICES_KEY = "auth.denied_devices.v1"

    /**
     * The SHA-256 fingerprint (lowercase hex) of the server's current TLS
     * leaf certificate, surfaced in [ApprovalDialog] as informational text.
     * Set once at server boot by `Application.main` from the [CertStore]
     * bundle; left null in tests / headless setups that don't run TLS.
     *
     * @see ApprovalDialog
     */
    @Volatile
    var serverCertFingerprintHex: String? = null

    /** Format a 64-char lowercase hex SHA-256 as `AB:CD:EF:…` for the dialog. */
    private fun prettyFingerprint(hex: String): String =
        hex.uppercase().chunked(2).joinToString(":")

    private val brandImage by lazy {
        runCatching {
            DeviceAuth::class.java.getResourceAsStream("/termtastic-icon.png")?.use { ImageIO.read(it) }
        }.onFailure { log.warn("Failed to load termtastic-icon.png for auth dialog", it) }
            .getOrNull()
    }

    private val iconPainter by lazy {
        brandImage?.toComposeImageBitmap()?.let { BitmapPainter(it) }
    }

    // Compose dialog state: when non-null, the approval dialog is rendered.
    // The CompletableDeferred is completed with the user's decision.
    private data class PendingApproval(
        val client: ClientInfo,
        val result: CompletableDeferred<Boolean>,
    )

    private var pendingApproval by mutableStateOf<PendingApproval?>(null)

    enum class Decision { APPROVED, REJECTED, HEADLESS }

    /**
     * Self-reported client metadata sent alongside the device token. All
     * fields come over the wire as optional query params / headers — callers
     * must treat them as untrusted (purely informational) strings for display.
     */
    data class ClientInfo(
        /** What the client called itself, e.g. "Web" or "Computer". Never empty. */
        val type: String,
        /** Best-effort self-reported hostname. */
        val hostname: String?,
        /** Best-effort self-reported local IP (may differ from the observed remoteAddress under NAT). */
        val selfReportedIp: String?,
        /** The TCP peer address Ktor observed for this request. */
        val remoteAddress: String,
    ) {
        fun displayLine(): String {
            val host = hostname?.takeIf { it.isNotBlank() }
            val ip = selfReportedIp?.takeIf { it.isNotBlank() }
            val hostPart = when {
                host != null && ip != null -> "$host ($ip)"
                host != null -> host
                ip != null -> ip
                else -> remoteAddress
            }
            return "$type — $hostPart"
        }
    }

    /**
     * Public-facing view of a persisted trusted device. Internal bookkeeping
     * fields live in [TrustedDevice]; this projection is what the
     * settings dialog shows in its list.
     */
    data class TrustedDeviceInfo(
        val tokenHash: String,
        val label: String?,
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
        val lastIp: String,
        val connections: List<ClientConnectionInfo>,
    )

    data class ClientConnectionInfo(
        val type: String,
        val hostname: String?,
        val selfReportedIp: String?,
        val remoteAddress: String,
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
    )

    /**
     * Public-facing view of a persisted denied device. Symmetric with
     * [TrustedDeviceInfo] so the settings dialog can render both lists with
     * the same code path.
     */
    data class DeniedDeviceInfo(
        val tokenHash: String,
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
        val lastIp: String,
        val connections: List<ClientConnectionInfo>,
    )

    /** Snapshot of all trusted devices, for the settings dialog. */
    fun listTrustedDevices(repo: SettingsRepository): List<TrustedDeviceInfo> =
        loadDevices(repo).devices.map {
            TrustedDeviceInfo(
                tokenHash = it.tokenHash,
                label = it.label,
                firstSeenEpochMs = it.firstSeenEpochMs,
                lastSeenEpochMs = it.lastSeenEpochMs,
                lastIp = it.lastIp,
                connections = it.connections.map { c ->
                    ClientConnectionInfo(
                        type = c.type,
                        hostname = c.hostname,
                        selfReportedIp = c.selfReportedIp,
                        remoteAddress = c.remoteAddress,
                        firstSeenEpochMs = c.firstSeenEpochMs,
                        lastSeenEpochMs = c.lastSeenEpochMs,
                    )
                },
            )
        }

    /**
     * Remove a trusted device so the next connection from it triggers a fresh
     * approval prompt. Returns true if a matching device was found and removed.
     */
    fun revokeTrustedDevice(repo: SettingsRepository, tokenHash: String): Boolean {
        val current = loadDevices(repo)
        val filtered = current.devices.filterNot { it.tokenHash == tokenHash }
        if (filtered.size == current.devices.size) return false
        saveDevices(repo, TrustedDevices(filtered))
        // Invalidate any cached APPROVED decision so a queued duplicate
        // connection within the short TTL window still has to re-prompt.
        recentDecisions.remove(tokenHash)
        return true
    }

    /** Snapshot of all denied devices, for the settings dialog. */
    fun listDeniedDevices(repo: SettingsRepository): List<DeniedDeviceInfo> =
        loadDeniedDevices(repo).devices.map {
            DeniedDeviceInfo(
                tokenHash = it.tokenHash,
                firstSeenEpochMs = it.firstSeenEpochMs,
                lastSeenEpochMs = it.lastSeenEpochMs,
                lastIp = it.lastIp,
                connections = it.connections.map { c ->
                    ClientConnectionInfo(
                        type = c.type,
                        hostname = c.hostname,
                        selfReportedIp = c.selfReportedIp,
                        remoteAddress = c.remoteAddress,
                        firstSeenEpochMs = c.firstSeenEpochMs,
                        lastSeenEpochMs = c.lastSeenEpochMs,
                    )
                },
            )
        }

    /**
     * Remove a denied device so the next connection from it triggers a fresh
     * approval prompt. Returns true if a matching device was found and removed.
     */
    fun unbanDeniedDevice(repo: SettingsRepository, tokenHash: String): Boolean {
        val current = loadDeniedDevices(repo)
        val filtered = current.devices.filterNot { it.tokenHash == tokenHash }
        if (filtered.size == current.devices.size) return false
        saveDeniedDevices(repo, DeniedDevices(filtered))
        recentDecisions.remove(tokenHash)
        return true
    }

    @Serializable
    private data class ClientConnection(
        val type: String,
        val hostname: String? = null,
        val selfReportedIp: String? = null,
        val remoteAddress: String,
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
    )

    @Serializable
    private data class TrustedDevice(
        val tokenHash: String,
        val label: String? = null,
        val firstSeenEpochMs: Long,
        var lastSeenEpochMs: Long,
        var lastIp: String,
        val connections: List<ClientConnection> = emptyList(),
    )

    @Serializable
    private data class TrustedDevices(val devices: List<TrustedDevice> = emptyList())

    @Serializable
    private data class DeniedDevice(
        val tokenHash: String,
        val firstSeenEpochMs: Long,
        var lastSeenEpochMs: Long,
        var lastIp: String,
        val connections: List<ClientConnection> = emptyList(),
    )

    @Serializable
    private data class DeniedDevices(val devices: List<DeniedDevice> = emptyList())

    /**
     * Heuristic: is [remoteAddress] one of the usual loopback forms Ktor's
     * `origin.remoteAddress` hands us? We can't fully trust this for policy in
     * general, but we bind to `0.0.0.0` only so the network-setting gate
     * works, and the filter below is an additional defence layered on top of
     * the per-device token check.
     */
    private fun isLoopback(remoteAddress: String): Boolean {
        val a = remoteAddress.trim()
        return a == "localhost" ||
            a == "127.0.0.1" ||
            a.startsWith("127.") ||
            a == "::1" ||
            a == "0:0:0:0:0:0:0:1" ||
            a.equals("::ffff:127.0.0.1", ignoreCase = true)
    }

    /**
     * Non-blocking pre-check that resolves known and denied tokens without
     * acquiring the approval mutex or showing a dialog. Returns [Decision]
     * for tokens with a definitive answer, or `null` when the token is
     * unknown and requires the interactive approval flow.
     *
     * Callers that can send feedback while waiting (e.g. the `/window`
     * WebSocket handler) should call this first, notify the client if the
     * result is `null`, and only then call [authorize] for the blocking
     * prompt.
     */
    fun checkFastPath(
        token: String?,
        client: ClientInfo,
        repo: SettingsRepository,
    ): Decision? {
        val remoteAddress = client.remoteAddress
        if (!isLoopback(remoteAddress) && !repo.isAllowRemoteConnections()) {
            return Decision.REJECTED
        }
        if (token.isNullOrBlank()) return null
        val hash = sha256Hex(token)
        val known = loadDevices(repo)
        val existing = known.devices.firstOrNull {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (existing != null) {
            val now = System.currentTimeMillis()
            val updated = known.devices.map {
                if (it.tokenHash == existing.tokenHash) {
                    it.copy(
                        lastSeenEpochMs = now,
                        lastIp = remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    )
                } else it
            }
            saveDevices(repo, TrustedDevices(updated))
            return Decision.APPROVED
        }
        val denied = loadDeniedDevices(repo)
        val deniedMatch = denied.devices.firstOrNull {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (deniedMatch != null) {
            val now = System.currentTimeMillis()
            val updated = denied.devices.map {
                if (it.tokenHash == deniedMatch.tokenHash) {
                    it.copy(
                        lastSeenEpochMs = now,
                        lastIp = remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    )
                } else it
            }
            saveDeniedDevices(repo, DeniedDevices(updated))
            log.info(
                "DeviceAuth: silently rejecting previously-denied device from {} hashPrefix={}",
                remoteAddress,
                hash.take(10),
            )
            return Decision.REJECTED
        }
        tryAutoApproveCleanSlateLoopback(token, client, repo, known, denied)?.let { return it }
        return null
    }

    suspend fun authorize(
        token: String?,
        client: ClientInfo,
        repo: SettingsRepository,
    ): Decision {
        val remoteAddress = client.remoteAddress
        // Network-scope gate: if the user hasn't opted into non-localhost
        // connections, reject anything that isn't clearly loopback before we
        // even look at the token. Default is localhost-only so a freshly
        // installed server never exposes its UI to the LAN by accident.
        if (!isLoopback(remoteAddress) && !repo.isAllowRemoteConnections()) {
            log.info(
                "DeviceAuth: rejecting non-loopback connection from {} because allow-remote is disabled",
                remoteAddress,
            )
            return Decision.REJECTED
        }
        if (token.isNullOrBlank()) {
            log.info("DeviceAuth: incoming request from {} has no token cookie", remoteAddress)
            return promptOrReject(tokenToPersist = null, client, repo)
        }
        val hash = sha256Hex(token)
        val known = loadDevices(repo)
        log.info(
            "DeviceAuth: authorize from {} tokenPrefix={} hashPrefix={} storedCount={} storedPrefixes={}",
            remoteAddress,
            token.take(6),
            hash.take(10),
            known.devices.size,
            known.devices.joinToString(",") { it.tokenHash.take(10) },
        )
        val existing = known.devices.firstOrNull {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (existing != null) {
            // Touch lastSeen / lastIp and merge the current client into the
            // device's history so an out-of-band inspection of the DB can see
            // which devices are actually in use and from where.
            val now = System.currentTimeMillis()
            val updated = known.devices.map {
                if (it.tokenHash == existing.tokenHash) {
                    it.copy(
                        lastSeenEpochMs = now,
                        lastIp = remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    )
                } else it
            }
            saveDevices(repo, TrustedDevices(updated))
            return Decision.APPROVED
        }
        // Persistent deny list: a token the user has explicitly denied stays
        // denied without re-prompting. The entry records the latest attempt
        // so the settings dialog can show when the banned client last tried
        // to come back.
        val denied = loadDeniedDevices(repo)
        val deniedMatch = denied.devices.firstOrNull {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (deniedMatch != null) {
            val now = System.currentTimeMillis()
            val updated = denied.devices.map {
                if (it.tokenHash == deniedMatch.tokenHash) {
                    it.copy(
                        lastSeenEpochMs = now,
                        lastIp = remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    )
                } else it
            }
            saveDeniedDevices(repo, DeniedDevices(updated))
            log.info(
                "DeviceAuth: silently rejecting previously-denied device from {} hashPrefix={}",
                remoteAddress,
                hash.take(10),
            )
            return Decision.REJECTED
        }
        tryAutoApproveCleanSlateLoopback(token, client, repo, known, denied)?.let { return it }
        return promptOrReject(tokenToPersist = token, client, repo)
    }

    /**
     * Update [existing] with a fresh observation of [client] at [now]. A
     * matching entry (same type + hostname + self-reported IP + remote
     * address) has its `lastSeen` bumped; otherwise a new entry is appended.
     * Older duplicates are preserved so the user can see every distinct place
     * the token has been used.
     */
    private fun mergeConnections(
        existing: List<ClientConnection>,
        client: ClientInfo,
        now: Long,
    ): List<ClientConnection> {
        val matchIdx = existing.indexOfFirst {
            it.type == client.type &&
                it.hostname == client.hostname &&
                it.selfReportedIp == client.selfReportedIp &&
                it.remoteAddress == client.remoteAddress
        }
        if (matchIdx >= 0) {
            val updated = existing.toMutableList()
            val prev = updated[matchIdx]
            updated[matchIdx] = prev.copy(lastSeenEpochMs = now)
            return updated
        }
        return existing + ClientConnection(
            type = client.type,
            hostname = client.hostname,
            selfReportedIp = client.selfReportedIp,
            remoteAddress = client.remoteAddress,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
        )
    }

    /**
     * Clean-slate onboarding shortcut: persist [token] as trusted without
     * prompting when the trusted and denied lists are both empty and
     * [client] is connecting from loopback.
     *
     * The approval dialog can land behind other windows on a fresh install
     * (especially on macOS where the server runs as a UIElement agent),
     * leaving a freshly-launched client stuck on "Waiting for approval…"
     * with no obvious next step. Auto-trusting the very first localhost
     * caller does not weaken the model — the user is on the same machine
     * and just launched both processes themselves. Once anything has been
     * trusted or denied this shortcut never fires again, so every later
     * connection still goes through the normal prompt path.
     *
     * Caller context: invoked from [checkFastPath] and [authorize] right
     * after both the trusted and denied lookups have failed, as a last
     * step before falling through to the interactive dialog.
     *
     * @param token the raw device token (the hash is what's persisted).
     * @param client the connecting client's metadata; only used for the
     *   loopback check, the persisted history record, and logging.
     * @param repo the settings repository to read existing trust state
     *   from and persist the new trusted device into.
     * @param known the trusted-device snapshot the caller already loaded,
     *   passed in to avoid a second SQLite read on the hot path.
     * @param denied the denied-device snapshot the caller already loaded,
     *   passed in for the same reason.
     * @return [Decision.APPROVED] if the shortcut fired (and the new
     *   trusted device has been persisted), `null` otherwise so the caller
     *   falls through to its normal handling.
     * @see checkFastPath
     * @see authorize
     */
    private fun tryAutoApproveCleanSlateLoopback(
        token: String?,
        client: ClientInfo,
        repo: SettingsRepository,
        known: TrustedDevices,
        denied: DeniedDevices,
    ): Decision? {
        if (token.isNullOrBlank()) return null
        if (!isLoopback(client.remoteAddress)) return null
        if (known.devices.isNotEmpty() || denied.devices.isNotEmpty()) return null
        val hash = sha256Hex(token)
        val now = System.currentTimeMillis()
        val initialConnection = ClientConnection(
            type = client.type,
            hostname = client.hostname,
            selfReportedIp = client.selfReportedIp,
            remoteAddress = client.remoteAddress,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
        )
        val added = TrustedDevice(
            tokenHash = hash,
            label = null,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
            lastIp = client.remoteAddress,
            connections = listOf(initialConnection),
        )
        saveDevices(repo, TrustedDevices(listOf(added)))
        log.info(
            "DeviceAuth: auto-approved first localhost device (clean slate) from {} hashPrefix={}",
            client.remoteAddress,
            hash.take(10),
        )
        return Decision.APPROVED
    }

    private suspend fun promptOrReject(
        tokenToPersist: String?,
        client: ClientInfo,
        repo: SettingsRepository,
    ): Decision = approvalMutex.withLock {
        val remoteAddress = client.remoteAddress
        val hash = tokenToPersist?.let { sha256Hex(it) }
        val now = System.currentTimeMillis()

        // Evict expired cache entries so the map doesn't grow without bound.
        recentDecisions.entries.removeAll { it.value.expiresAtMs <= now }

        // A previous waiter inside this same mutex session may have just
        // persisted this exact token as trusted (common at boot, when
        // /window + /api/ui-settings + several /pty sockets all race through
        // the gate with the same unknown cookie). Re-check the trusted set
        // before bothering the user again.
        if (hash != null) {
            val known = loadDevices(repo)
            if (known.devices.any { it.tokenHash == hash }) {
                return@withLock Decision.APPROVED
            }
            // Same token, recent decision still valid — reuse it. This is
            // what makes a single "Deny" click on boot also deny the other
            // in-flight connections from the same browser instead of popping
            // a fresh dialog for each.
            recentDecisions[hash]?.let { cached ->
                return@withLock cached.decision
            }
        }

        if (GraphicsEnvironment.isHeadless()) {
            log.warn(
                "Rejecting connection from {}: no token or unknown token, and JVM is headless so no approval dialog can be shown",
                remoteAddress,
            )
            return@withLock Decision.HEADLESS
        }
        val approved = showApprovalDialog(client)
        if (!approved) {
            log.info("User denied device from {}", remoteAddress)
            if (hash != null) {
                recentDecisions[hash] = CachedDecision(
                    Decision.REJECTED,
                    now + RECENT_DECISION_TTL_MS,
                )
                // Persist the denial so the next attempt from this token
                // doesn't re-prompt. The entry is revocable from the settings
                // dialog ("Unban") in case of a misclick.
                val current = loadDeniedDevices(repo)
                val initialConnection = ClientConnection(
                    type = client.type,
                    hostname = client.hostname,
                    selfReportedIp = client.selfReportedIp,
                    remoteAddress = remoteAddress,
                    firstSeenEpochMs = now,
                    lastSeenEpochMs = now,
                )
                val added = DeniedDevice(
                    tokenHash = hash,
                    firstSeenEpochMs = now,
                    lastSeenEpochMs = now,
                    lastIp = remoteAddress,
                    connections = listOf(initialConnection),
                )
                saveDeniedDevices(repo, DeniedDevices(current.devices + added))
                log.info("DeviceAuth: persisted denial for hashPrefix={}", hash.take(10))
            }
            return@withLock Decision.REJECTED
        }
        if (tokenToPersist == null || hash == null) {
            // Approval was granted but the client never sent a token, so we
            // have nothing to remember. This is mostly a developer scenario
            // (curl without a cookie); in practice the browser always sends
            // one. Allow the single connection through and move on.
            log.info("User approved cookie-less connection from {} (not persisted)", remoteAddress)
            return@withLock Decision.APPROVED
        }
        val devices = loadDevices(repo)
        val initialConnection = ClientConnection(
            type = client.type,
            hostname = client.hostname,
            selfReportedIp = client.selfReportedIp,
            remoteAddress = remoteAddress,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
        )
        val added = TrustedDevice(
            tokenHash = hash,
            label = null,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
            lastIp = remoteAddress,
            connections = listOf(initialConnection),
        )
        saveDevices(repo, TrustedDevices(devices.devices + added))
        recentDecisions[hash] = CachedDecision(
            Decision.APPROVED,
            now + RECENT_DECISION_TTL_MS,
        )
        // Read back immediately and log what we just persisted so we can
        // tell mid-debug if the write/read round-trip is broken.
        val roundTrip = loadDevices(repo)
        log.info(
            "User approved new device from {}; persisted hashPrefix={}; readback count={} prefixes={}",
            remoteAddress,
            hash.take(10),
            roundTrip.devices.size,
            roundTrip.devices.joinToString(",") { it.tokenHash.take(10) },
        )
        Decision.APPROVED
    }

    /**
     * Show the Compose approval dialog and suspend until the user decides.
     * Sets [pendingApproval] so the Compose application loop picks it up;
     * the returned [CompletableDeferred] is completed when the user clicks OK
     * or dismisses the window.
     */
    private suspend fun showApprovalDialog(client: ClientInfo): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        // Mutate on the AWT EDT so Compose Desktop's recomposer observes the change.
        SwingUtilities.invokeLater {
            pendingApproval = PendingApproval(client, deferred)
        }
        return try {
            deferred.await()
        } finally {
            SwingUtilities.invokeLater { pendingApproval = null }
        }
    }

    /**
     * Call from the top-level Compose application scope. Renders the
     * device-approval dialog when a pending approval is active.
     * Always present in the composition tree (via [visible]) so the
     * recomposer stays active even when no windows are shown.
     */
    @Composable
    fun renderApprovalDialogIfShowing() {
        val pending = pendingApproval
        if (pending != null) {
            ApprovalDialog(
                client = pending.client,
                onResult = { approved -> pending.result.complete(approved) },
            )
        }
    }

    @Composable
    private fun ApprovalDialog(client: ClientInfo, onResult: (Boolean) -> Unit) {
        var selectedApprove by remember { mutableStateOf<Boolean?>(null) }

        val dialogState = rememberDialogState(
            size = DpSize(560.dp, 480.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        DialogWindow(
            onCloseRequest = { onResult(false) },
            title = "Termtastic \u2014 New device",
            state = dialogState,
            icon = iconPainter,
            alwaysOnTop = true,
            resizable = false,
        ) {
            // On macOS the JVM runs as a background agent
            // (-Dapple.awt.UIElement=true when spawned by Electron), so a
            // freshly shown window stays buried behind the frontmost app.
            // requestForeground maps to activateIgnoringOtherApps:, which
            // works even for UIElement agents; the beep replaces the sound
            // of the osascript notification this approach supersedes.
            LaunchedEffect(Unit) {
                runCatching {
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        desktop.requestForeground(true)
                    }
                }
                window.toFront()
                window.requestFocus()
                runCatching { Toolkit.getDefaultToolkit().beep() }
            }
            MaterialTheme(colorScheme = SettingsDialog.tronColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "A new device is trying to connect to Termtastic.",
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(16.dp))

                        InfoLine("Client type", client.type)
                        InfoLine("Remote address", client.remoteAddress)
                        client.hostname?.takeIf { it.isNotBlank() }?.let {
                            InfoLine("Hostname", it)
                        }
                        client.selfReportedIp?.takeIf { it.isNotBlank() }?.let {
                            InfoLine("Self-reported IP", it)
                        }
                        // Transparency: show the SHA-256 of the TLS leaf the
                        // approving client is about to pin. Verification is
                        // not required for TOFU correctness — it just lets a
                        // user cross-check out-of-band if they care.
                        serverCertFingerprintHex?.let {
                            InfoLine("Server cert SHA-256", prettyFingerprint(it))
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Choose how to handle this device:", fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedApprove == true,
                                    onClick = { selectedApprove = true },
                                    role = Role.RadioButton,
                                ),
                        ) {
                            RadioButton(
                                selected = selectedApprove == true,
                                onClick = null,
                            )
                            Text("Approve this device (save as trusted)", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedApprove == false,
                                    onClick = { selectedApprove = false },
                                    role = Role.RadioButton,
                                ),
                        ) {
                            RadioButton(
                                selected = selectedApprove == false,
                                onClick = null,
                            )
                            Text("Deny this device (close the connection)", fontSize = 14.sp)
                        }

                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { onResult(selectedApprove == true) },
                                enabled = selectedApprove != null,
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoLine(label: String, value: String) {
        Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text("$label: ", fontSize = 13.sp)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }

    private fun loadDevices(repo: SettingsRepository): TrustedDevices {
        val raw = repo.getString(TRUSTED_DEVICES_KEY) ?: return TrustedDevices()
        return runCatching { json.decodeFromString(TrustedDevices.serializer(), raw) }
            .getOrElse {
                log.warn("Failed to decode trusted devices blob; treating as empty", it)
                TrustedDevices()
            }
    }

    private fun saveDevices(repo: SettingsRepository, value: TrustedDevices) {
        repo.putString(TRUSTED_DEVICES_KEY, json.encodeToString(TrustedDevices.serializer(), value))
    }

    private fun loadDeniedDevices(repo: SettingsRepository): DeniedDevices {
        val raw = repo.getString(DENIED_DEVICES_KEY) ?: return DeniedDevices()
        return runCatching { json.decodeFromString(DeniedDevices.serializer(), raw) }
            .getOrElse {
                log.warn("Failed to decode denied devices blob; treating as empty", it)
                DeniedDevices()
            }
    }

    private fun saveDeniedDevices(repo: SettingsRepository, value: DeniedDevices) {
        repo.putString(DENIED_DEVICES_KEY, json.encodeToString(DeniedDevices.serializer(), value))
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
