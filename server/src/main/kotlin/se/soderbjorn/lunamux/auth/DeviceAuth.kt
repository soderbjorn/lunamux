/**
 * Device authorization flow for Lunamux.
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
package se.soderbjorn.lunamux.auth

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import se.soderbjorn.lunamux.ui.SettingsDialog
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
import se.soderbjorn.lunamux.persistence.SettingsRepository
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

    // Makes claiming a pairing token and persisting the resulting trust one
    // atomic step. Without it a sibling request can look up the trusted list
    // in the gap between the two and decide it needs a dialog, prompting in
    // the middle of a pairing that has already succeeded.
    private val pairingLock = Any()

    // Guards [recentDecisions] and [recentIpDenials]. A plain monitor rather
    // than [approvalMutex] because these are also touched from outside any
    // coroutine — revokeTrustedDevice runs on the settings dialog's EDT — and
    // because approvalMutex is held for the entire lifetime of an open dialog,
    // which is far too long to make anything else wait on.
    private val suppressionLock = Any()

    // On boot the client opens /api/ui-settings + /window + one /pty/{id} per
    // pre-existing session in parallel, all with the same unknown token. The
    // mutex alone isn't enough: it serializes the prompts, but each waiter
    // would still show its own dialog once it got the lock. This cache lets
    // a single APPROVED/REJECTED decision for a given token hash cover every
    // queued-up duplicate within a short window. Entries auto-expire so a
    // user who clicks Deny by mistake can retry by reloading shortly after.
    // All access happens inside [suppressionLock].
    private data class CachedDecision(val decision: Decision, val expiresAtMs: Long)
    private val recentDecisions = HashMap<String, CachedDecision>()
    private const val RECENT_DECISION_TTL_MS: Long = 10_000

    // How long "Not now" quiets a device for. Longer than
    // [RECENT_DECISION_TTL_MS] because a dismissal has no persisted record to
    // fall back on: an auto-reconnecting client would otherwise re-pop the
    // dialog every ten seconds. Long enough not to nag, short enough that
    // "not now" still plainly means "ask me again".
    private const val DISMISS_SUPPRESSION_MS: Long = 60_000

    // Prompt cooldown per remote IP: after the user denies a non-loopback
    // device, further unknown tokens from the same address are rejected
    // without a dialog for a short window, so a port scanner cycling random
    // tokens can't turn into desktop dialog spam. Keyed by observed remote
    // address → denial epoch-millis; all access happens inside
    // [suppressionLock] and entries are evicted alongside [recentDecisions].
    private val recentIpDenials = HashMap<String, Long>()
    private const val IP_DENIAL_COOLDOWN_MS: Long = 30_000

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private const val TRUSTED_DEVICES_KEY = "auth.trusted_devices.v1"
    private const val DENIED_DEVICES_KEY = "auth.denied_devices.v1"

    /**
     * One-way latch: set the first time this install ever trusts or denies an
     * interactive device, and never cleared. Gates the clean-slate loopback
     * shortcut (see [tryAutoApproveCleanSlateLoopback]).
     *
     * Empty trusted/denied lists are not sufficient evidence of a fresh
     * install — revoking every device and unbanning every denial empties them
     * again, which would re-arm a shortcut that grants trust with no prompt to
     * anything on localhost. The lists describe current state; this records
     * that the state was ever non-empty.
     *
     * @see markFirstDecisionMade
     * @see hasMadeFirstDecision
     */
    private const val FIRST_DECISION_KEY = "auth.first_decision.v1"

    /**
     * Trust-class label stamped on tokens minted for MCP clients. Tokens
     * carrying this label are a distinct trust class from interactive
     * device tokens: they are *excluded* from the regular [authorize] /
     * [checkFastPath] device lookup (an MCP token must never grant full
     * `/window` UI access) and are the *only* tokens [authorizeMcp]
     * accepts on the `/mcp` endpoint.
     *
     * @see authorizeMcp
     * @see addTrustedToken
     */
    const val MCP_LABEL = "MCP"

    /** Token scope granting read-only MCP tool access. */
    const val MCP_SCOPE_READ = "read"

    /** Token scope granting both read and write MCP tool access. */
    const val MCP_SCOPE_READ_WRITE = "read+write"

    /**
     * How a device came to be trusted, stamped on [TrustedDevice.trustedVia]
     * at approval time and surfaced as a tag in the settings dialog's
     * trusted-device list.
     *
     * The clean-slate loopback shortcut is the only path that grants trust
     * with no human ceremony at all — no dialog click, no QR scan — so it is
     * the one worth being able to spot in the list. The other two values
     * exist so that a *missing* value is unambiguous: entries persisted
     * before this field was introduced deserialize to `null` and are
     * rendered untagged rather than guessed at.
     *
     * @see persistTrustedDevice
     * @see tryAutoApproveCleanSlateLoopback
     */
    const val TRUSTED_VIA_AUTO = "auto"

    /** Trusted by spending a one-time QR pairing token. @see tryPairingTokenApproval */
    const val TRUSTED_VIA_QR = "qr"

    /** Trusted by the user clicking Approve in [ApprovalDialog]. @see promptOrReject */
    const val TRUSTED_VIA_DIALOG = "dialog"

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
            DeviceAuth::class.java.getResourceAsStream("/lunamux-icon.png")?.use { ImageIO.read(it) }
        }.onFailure { log.warn("Failed to load lunamux-icon.png for auth dialog", it) }
            .getOrNull()
    }

    private val iconPainter by lazy {
        brandImage?.toComposeImageBitmap()?.let { BitmapPainter(it) }
    }

    // Compose dialog state: when non-null, the approval dialog is rendered.
    // The CompletableDeferred is completed with the user's decision.
    private data class PendingApproval(
        val client: ClientInfo,
        val result: CompletableDeferred<ApprovalChoice>,
    )

    private var pendingApproval by mutableStateOf<PendingApproval?>(null)

    enum class Decision { APPROVED, REJECTED, HEADLESS }

    /**
     * What the user did with the approval dialog.
     *
     * [DISMISS] exists so closing the window is not silently equivalent to
     * [DENY]. It rejects the connection in hand and records nothing *about the
     * device* — no trust, no denial — so a later attempt prompts again rather
     * than being banned by an accident. It is still a decision by the user
     * though, and spends the first-decision latch like the other two: someone
     * was asked, which is what the clean-slate shortcut assumes hasn't
     * happened.
     *
     * @see promptOrReject
     * @see markFirstDecisionMade
     */
    internal enum class ApprovalChoice { APPROVE, DENY, DISMISS }

    /** The real prompt: shows the Compose dialog and waits for the user. */
    internal val defaultApprovalPrompt: suspend (ClientInfo) -> ApprovalChoice = { showApprovalDialog(it) }

    /**
     * Seam for the approval prompt. Production points at the Compose dialog;
     * tests swap it to drive the deny/dismiss branches, which are otherwise
     * unreachable headlessly because they run off a real window, and restore
     * [defaultApprovalPrompt] afterwards.
     *
     * @see showApprovalDialog
     */
    internal var approvalPrompt: suspend (ClientInfo) -> ApprovalChoice = defaultApprovalPrompt

    /**
     * Seam for the headless check that guards [approvalPrompt]. Production
     * asks the JVM; tests force it false so they can drive [approvalPrompt]
     * (the test JVM is headless, which would otherwise short-circuit to
     * [Decision.HEADLESS] before the prompt is ever reached).
     */
    internal var headlessCheck: () -> Boolean = { GraphicsEnvironment.isHeadless() }

    /**
     * Seam for the wall clock used by the prompt-suppression caches. Tests
     * substitute it to span [RECENT_DECISION_TTL_MS] without sleeping, the way
     * a dialog left open on a real desktop does.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * Drop the short-lived prompt suppressions ([recentDecisions],
     * [recentIpDenials]). Both normally lapse on their own within seconds; this
     * exists so tests can stand for "…and later, once they had", and so that
     * this object's process-wide state can't leak between them.
     */
    internal fun clearTransientSuppressions() = synchronized(suppressionLock) {
        recentDecisions.clear()
        recentIpDenials.clear()
    }

    /**
     * Self-reported client metadata sent alongside the device token. All
     * fields come over the wire as optional query params / headers — callers
     * must treat them as untrusted (purely informational) strings for display.
     */
    /**
     * Stand-in [ClientInfo.type] for a client that sent no type at all.
     * Applied by `readClientInfo` so the field can stay non-null, and
     * recognised by the settings dialog, which falls back to a generic title
     * rather than showing this placeholder to the user.
     *
     * @see ClientInfo.type
     */
    const val UNKNOWN_CLIENT_TYPE = "Unknown"

    data class ClientInfo(
        /**
         * What the client called itself, e.g. "Web", "iOS", or "Android".
         * Never empty; [UNKNOWN_CLIENT_TYPE] when the client sent nothing.
         */
        val type: String,
        /** Best-effort self-reported hostname. */
        val hostname: String?,
        /** Best-effort self-reported local IP (may differ from the observed remoteAddress under NAT). */
        val selfReportedIp: String?,
        /** The TCP peer address Ktor observed for this request. */
        val remoteAddress: String,
        /**
         * Self-reported client app version (e.g. `"1.5.0"`), or `null` for
         * clients that don't send one. Used for capability gating — see
         * [clientSupportsAgentPanes]. Released clients before 1.5 never send
         * a version, so `null` means "too old for 1.5+ pane kinds".
         */
        val version: String? = null,
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
        /**
         * When the device was approved. A trusted record is only ever
         * constructed at the moment of approval and this field is never
         * rewritten afterwards, so first-seen *is* the approval instant.
         */
        val firstSeenEpochMs: Long,
        val lastSeenEpochMs: Long,
        val lastIp: String,
        val connections: List<ClientConnectionInfo>,
        /**
         * MCP token scope ([MCP_SCOPE_READ] or [MCP_SCOPE_READ_WRITE]).
         * `null` for regular interactive device tokens, which carry no scope.
         */
        val scope: String? = null,
        /**
         * How this device was trusted ([TRUSTED_VIA_AUTO], [TRUSTED_VIA_QR]
         * or [TRUSTED_VIA_DIALOG]); `null` on entries persisted before the
         * field existed, and on MCP tokens.
         */
        val trustedVia: String? = null,
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
        loadDevices(repo).devices.map { it.toInfo() }

    /** Projection helper shared by [listTrustedDevices] and [listMcpTokens]. */
    private fun TrustedDevice.toInfo(): TrustedDeviceInfo =
        TrustedDeviceInfo(
            tokenHash = tokenHash,
            label = label,
            firstSeenEpochMs = firstSeenEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            lastIp = lastIp,
            connections = connections.map { c ->
                ClientConnectionInfo(
                    type = c.type,
                    hostname = c.hostname,
                    selfReportedIp = c.selfReportedIp,
                    remoteAddress = c.remoteAddress,
                    firstSeenEpochMs = c.firstSeenEpochMs,
                    lastSeenEpochMs = c.lastSeenEpochMs,
                )
            },
            scope = scope,
            trustedVia = trustedVia,
        )

    /**
     * Snapshot of all MCP tokens (trusted devices carrying the [MCP_LABEL]
     * trust class), for the settings dialog's MCP section.
     *
     * @param repo the settings repository holding the trusted-device list.
     * @return the MCP-labelled subset of the trusted devices.
     * @see addTrustedToken
     */
    fun listMcpTokens(repo: SettingsRepository): List<TrustedDeviceInfo> =
        loadDevices(repo).devices.filter { it.label == MCP_LABEL }.map { it.toInfo() }

    /**
     * Persist a pre-minted token as trusted, without any interactive
     * approval. Used by the settings dialog's "Generate MCP token" button
     * (and by tests) — the raw token is shown to the user exactly once;
     * only its SHA-256 hash is stored.
     *
     * @param repo  the settings repository to persist into.
     * @param token the raw token string (the hash is what's persisted).
     * @param label trust-class label; [MCP_LABEL] for MCP tokens.
     * @param scope MCP scope, [MCP_SCOPE_READ] or [MCP_SCOPE_READ_WRITE].
     * @return the SHA-256 hash of [token] (the settings-dialog list key).
     * @see listMcpTokens
     * @see authorizeMcp
     */
    fun addTrustedToken(
        repo: SettingsRepository,
        token: String,
        label: String,
        scope: String? = null,
    ): String {
        val hash = sha256Hex(token)
        val now = System.currentTimeMillis()
        val added = TrustedDevice(
            tokenHash = hash,
            label = label,
            firstSeenEpochMs = now,
            lastSeenEpochMs = now,
            lastIp = "-",
            connections = emptyList(),
            scope = scope,
        )
        updateTrusted(repo) { current ->
            TrustedDevices(current.devices.filterNot { it.tokenHash == hash } + added)
        }
        log.info("DeviceAuth: minted trusted token label={} scope={} hashPrefix={}", label, scope, hash.take(10))
        return hash
    }

    /**
     * Change the scope of an existing MCP token.
     *
     * @param repo      the settings repository holding the trusted-device list.
     * @param tokenHash the stored SHA-256 hash identifying the token.
     * @param scope     the new scope ([MCP_SCOPE_READ] or [MCP_SCOPE_READ_WRITE]).
     * @return true if a matching MCP token was found and updated.
     */
    fun setMcpTokenScope(repo: SettingsRepository, tokenHash: String, scope: String): Boolean {
        var changed = false
        updateTrusted(repo) { current ->
            if (current.devices.none { it.tokenHash == tokenHash && it.label == MCP_LABEL }) {
                return@updateTrusted current
            }
            changed = true
            TrustedDevices(
                current.devices.map {
                    if (it.tokenHash == tokenHash && it.label == MCP_LABEL) it.copy(scope = scope) else it
                },
            )
        }
        return changed
    }

    /**
     * Authorization gate for the `/mcp` endpoint. Non-interactive by design:
     * unknown tokens are rejected outright (never prompted), and only tokens
     * carrying the [MCP_LABEL] trust class and a non-null scope are accepted.
     * Loopback-only enforcement is the caller's job (the route rejects
     * non-loopback peers *regardless* of the allow-remote UI toggle).
     *
     * Called by `McpRoutes` on every `/mcp` request.
     *
     * @param token  the raw token from the `X-Termtastic-Auth` header (or
     *   cookie / query param — see `readAuthToken`).
     * @param client the connecting client's metadata, recorded into the
     *   token's connection history.
     * @param repo   the settings repository holding the trusted-device list.
     * @return the token's scope ([MCP_SCOPE_READ] or [MCP_SCOPE_READ_WRITE]),
     *   or `null` when the token is missing, unknown, revoked, or not an MCP
     *   token.
     * @see addTrustedToken
     */
    fun authorizeMcp(token: String?, client: ClientInfo, repo: SettingsRepository): String? {
        if (token.isNullOrBlank()) return null
        val hash = sha256Hex(token)
        val known = loadDevices(repo)
        val existing = known.devices.firstOrNull {
            it.label == MCP_LABEL &&
                MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        } ?: return null
        val scope = existing.scope ?: return null
        touchTrustedDevice(repo, existing.tokenHash, client)
        return scope
    }

    /**
     * Remove a trusted device so the next connection from it triggers a fresh
     * approval prompt. Returns true if a matching device was found and removed.
     *
     * Latches [FIRST_DECISION_KEY] when the removed device was interactive.
     * That is the backfill for installs predating the key: the device's mere
     * existence proves a decision was once made, and this is the last moment
     * that evidence exists — without it, revoking a device trusted by an older
     * version would empty the list and re-arm the onboarding shortcut. MCP
     * tokens are excluded, as they never counted against the clean slate.
     */
    fun revokeTrustedDevice(repo: SettingsRepository, tokenHash: String): Boolean {
        var removed = false
        var wasInteractive = false
        updateTrusted(repo) { current ->
            val target = current.devices.firstOrNull { it.tokenHash == tokenHash }
            val filtered = current.devices.filterNot { it.tokenHash == tokenHash }
            removed = filtered.size != current.devices.size
            wasInteractive = target != null && target.label != MCP_LABEL
            TrustedDevices(filtered)
        }
        if (wasInteractive) markFirstDecisionMade(repo)
        // Invalidate any cached APPROVED decision so a queued duplicate
        // connection within the short TTL window still has to re-prompt.
        if (removed) synchronized(suppressionLock) { recentDecisions.remove(tokenHash) }
        return removed
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
     *
     * Latches [FIRST_DECISION_KEY] for the same reason as
     * [revokeTrustedDevice]: a denial is evidence a decision was made, and
     * unbanning is the moment that evidence disappears from the lists.
     */
    fun unbanDeniedDevice(repo: SettingsRepository, tokenHash: String): Boolean {
        var removed = false
        updateDenied(repo) { current ->
            val filtered = current.devices.filterNot { it.tokenHash == tokenHash }
            removed = filtered.size != current.devices.size
            DeniedDevices(filtered)
        }
        if (removed) {
            markFirstDecisionMade(repo)
            synchronized(suppressionLock) { recentDecisions.remove(tokenHash) }
        }
        return removed
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
        /**
         * MCP token scope ([MCP_SCOPE_READ] / [MCP_SCOPE_READ_WRITE]).
         * `null` on regular interactive device tokens; defaulted so
         * pre-existing persisted blobs deserialize unchanged.
         */
        val scope: String? = null,
        /**
         * How this device was trusted ([TRUSTED_VIA_AUTO] / [TRUSTED_VIA_QR]
         * / [TRUSTED_VIA_DIALOG]). Defaulted for the same reason as [scope]:
         * blobs written before the field existed must still deserialize, and
         * they legitimately carry no answer — the settings dialog renders
         * them untagged rather than inferring one.
         */
        val trustedVia: String? = null,
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
     * the per-device token check. Public so `McpRoutes` can enforce its
     * unconditional localhost-only policy with the same address forms.
     */
    fun isLoopback(remoteAddress: String): Boolean {
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
     *
     * @param pairToken optional one-time QR pairing token; once past the
     *   network gate, a token that spends successfully trusts the device
     *   immediately (see [tryPairingTokenApproval]).
     */
    fun checkFastPath(
        token: String?,
        client: ClientInfo,
        repo: SettingsRepository,
        pairToken: String? = null,
    ): Decision? {
        val remoteAddress = client.remoteAddress
        if (!isLoopback(remoteAddress) && !repo.isAllowRemoteConnections()) {
            return Decision.REJECTED
        }
        // Pairing runs after the network gate: allow-remote is the master
        // switch, so a pairing token can never widen the scope past it.
        tryPairingTokenApproval(token, pairToken, client, repo)?.let { return it }
        if (token.isNullOrBlank()) {
            // A remote peer without a device token has nothing an approval
            // could persist. Reject silently instead of prompting so port
            // scanners and stray browsers can't pop dialogs on the desktop.
            // (Loopback keeps the prompt path — the token-less curl/dev flow.)
            if (!isLoopback(remoteAddress)) {
                log.info(
                    "DeviceAuth: rejecting token-less non-loopback connection from {} without prompting",
                    remoteAddress,
                )
                return Decision.REJECTED
            }
            return null
        }
        val hash = sha256Hex(token)
        val known = loadDevices(repo)
        // MCP tokens are a distinct trust class: they never authorize the
        // interactive `/window`/`/pty` surface, only `/mcp` (authorizeMcp).
        val existing = known.devices.firstOrNull {
            it.label != MCP_LABEL &&
                MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (existing != null) {
            touchTrustedDevice(repo, existing.tokenHash, client)
            return Decision.APPROVED
        }
        val denied = loadDeniedDevices(repo)
        val deniedMatch = denied.devices.firstOrNull {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (deniedMatch != null) {
            touchDeniedDevice(repo, deniedMatch.tokenHash, client)
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

    /**
     * The blocking authorization gate every HTTP route and WebSocket handler
     * runs through. Resolves trusted/denied tokens silently and prompts the
     * interactive approval dialog for unknown ones.
     *
     * @param pairToken optional one-time QR pairing token; once past the
     *   network gate, a token that spends successfully trusts the device
     *   immediately — see [tryPairingTokenApproval].
     */
    suspend fun authorize(
        token: String?,
        client: ClientInfo,
        repo: SettingsRepository,
        pairToken: String? = null,
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
        // Pairing runs after the network gate: allow-remote is the master
        // switch, so a pairing token can never widen the scope past it.
        tryPairingTokenApproval(token, pairToken, client, repo)?.let { return it }
        if (token.isNullOrBlank()) {
            // See checkFastPath: token-less remote peers are rejected without
            // a prompt; the loopback curl/dev flow keeps its dialog.
            if (!isLoopback(remoteAddress)) {
                log.info(
                    "DeviceAuth: rejecting token-less non-loopback connection from {} without prompting",
                    remoteAddress,
                )
                return Decision.REJECTED
            }
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
        // MCP tokens are a distinct trust class: they never authorize the
        // interactive `/window`/`/pty` surface, only `/mcp` (authorizeMcp).
        val existing = known.devices.firstOrNull {
            it.label != MCP_LABEL &&
                MessageDigest.isEqual(it.tokenHash.toByteArray(), hash.toByteArray())
        }
        if (existing != null) {
            // Touch lastSeen / lastIp and merge the current client into the
            // device's history so an out-of-band inspection of the DB can see
            // which devices are actually in use and from where.
            touchTrustedDevice(repo, existing.tokenHash, client)
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
            touchDeniedDevice(repo, deniedMatch.tokenHash, client)
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
     * prompting when this install has never trusted or denied a device and
     * [client] is connecting from loopback.
     *
     * The approval dialog can land behind other windows on a fresh install
     * (especially on macOS where the server runs as a UIElement agent),
     * leaving a freshly-launched client stuck on "Waiting for approval…"
     * with no obvious next step. Auto-trusting the very first localhost
     * caller does not weaken the model — the user is on the same machine
     * and just launched both processes themselves.
     *
     * Fires at most once per install, enforced by [FIRST_DECISION_KEY] rather
     * than by the lists being empty: revoking every device and unbanning every
     * denial empties the lists, and without the latch that would re-arm a
     * no-prompt grant for anything on localhost long after onboarding. The
     * empty-list checks are kept as a cheap second line of defence.
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
        // Once spent, spent for good. The list checks below describe current
        // state, which revoking everything resets; this doesn't.
        if (hasMadeFirstDecision(repo)) return null
        // MCP tokens don't count against the clean slate — minting an MCP
        // token in Settings before the first browser connect must not break
        // the first-localhost-device onboarding shortcut.
        if (known.devices.any { it.label != MCP_LABEL } || denied.devices.isNotEmpty()) return null
        // …but an MCP token itself must never ride the shortcut into the
        // interactive trust class: a known hash (of any label) is excluded.
        if (known.devices.any { it.tokenHash == sha256Hex(token) }) return null
        val hash = persistTrustedDevice(token, client, repo, TRUSTED_VIA_AUTO)
        log.info(
            "DeviceAuth: auto-approved first localhost device (clean slate) from {} hashPrefix={}",
            client.remoteAddress,
            hash.take(10),
        )
        return Decision.APPROVED
    }

    /**
     * QR pairing shortcut: when the connect carries both a device token and
     * a live one-time pairing token, spend the pairing token and trust the
     * device on the spot — no approval dialog. Possession of an un-expired
     * pairing token means the user was just looking at this server's pairing
     * QR, which is a stronger trust ceremony than clicking a dialog button.
     *
     * Pairing never overrides an explicit "no". It cannot widen the network
     * scope — allow-remote is the master switch, and "Pair via QR code" is
     * hidden in settings until the user turns it on — and it cannot
     * re-admit a denied device: a token on the denied list is ignored here
     * and rejected by the caller's denied lookup. "Unban" in settings is the
     * only way back.
     *
     * Caller context: [checkFastPath] and [authorize], immediately *after*
     * the network-scope gate — a remote pairing attempted while allow-remote
     * is off is rejected there and never reaches this. Only the connect that
     * *claims* the pairing token is approved here; anything else (expired,
     * foreign, or already-claimed token) returns `null` and falls through to
     * the caller's normal flow.
     *
     * That fall-through is load-bearing, not a leftover. A scanning client
     * re-sends its `pairToken` on every request until the connect succeeds, so
     * the requests racing the claim — and every request for the rest of the
     * token's TTL — land here again. Returning `null` puts them back on the
     * ordinary trusted-device lookup, which is the path that refreshes
     * last-seen/IP history and honours a revoke. Approving them here instead
     * would freeze the device's history for as long as the QR stays live.
     *
     * The claim and the trust write happen together under [pairingLock] so a
     * racing sibling can't read the trusted list in the window between them
     * and wrongly conclude it needs a dialog.
     *
     * @param token the device-auth token to persist as trusted.
     * @param pairToken the raw pairing token from the QR scan, or `null`.
     * @param client the connecting client's metadata for history/logging.
     * @param repo the settings repository to persist trust state into.
     * @return [Decision.APPROVED] for the connect that claims [pairToken],
     *   else `null` — including for later requests from the device that
     *   already claimed it.
     * @see PairingTokens.consume
     */
    private fun tryPairingTokenApproval(
        token: String?,
        pairToken: String?,
        client: ClientInfo,
        repo: SettingsRepository,
    ): Decision? = synchronized(pairingLock) {
        if (token.isNullOrBlank() || pairToken.isNullOrBlank()) return null
        // A denial outranks the QR: scanning must not re-admit a device the
        // user explicitly denied — "Unban" in settings is the only undo.
        // Checked before the spend so the code survives for a retry after an
        // unban, and returning null lets the caller's denied lookup do the
        // rejecting (keeping trusted/denied disjoint: we never persist trust).
        val deviceHash = sha256Hex(token)
        val deniedMatch = loadDeniedDevices(repo).devices.any {
            MessageDigest.isEqual(it.tokenHash.toByteArray(), deviceHash.toByteArray())
        }
        if (deniedMatch) {
            log.info(
                "DeviceAuth: ignoring pairing token from denied device {} hashPrefix={}",
                client.remoteAddress,
                deviceHash.take(10),
            )
            return null
        }
        // Not the claiming connect: hand it back to the trusted lookup, which
        // is where a device that already paired belongs.
        if (!PairingTokens.consume(pairToken, deviceHash)) return null
        val hash = persistTrustedDevice(token, client, repo, TRUSTED_VIA_QR)
        log.info(
            "DeviceAuth: pairing token claimed; trusted device from {} hashPrefix={}",
            client.remoteAddress,
            hash.take(10),
        )
        return Decision.APPROVED
    }

    /**
     * Persist [token] as a trusted interactive device, appending to the
     * existing list (a no-op when the hash is already present, so racing
     * callers can't duplicate an entry). Shared by every non-dialog trust
     * path: the clean-slate loopback shortcut, QR pairing approval, and the
     * dialog's own approve branch.
     *
     * @param token the raw device token; only its SHA-256 is stored.
     * @param client the connecting client, recorded as the first connection.
     * @param repo the settings repository to persist into.
     * @param via which trust path is calling: [TRUSTED_VIA_AUTO],
     *   [TRUSTED_VIA_QR] or [TRUSTED_VIA_DIALOG]. Recorded verbatim and
     *   shown as a tag in the settings dialog.
     * @return the token's SHA-256 hash (the settings-dialog list key).
     */
    private fun persistTrustedDevice(
        token: String,
        client: ClientInfo,
        repo: SettingsRepository,
        via: String,
    ): String {
        val hash = sha256Hex(token)
        // Every interactive trust path funnels through here, so this is the
        // one place that has to latch. MCP tokens go through addTrustedToken
        // instead and deliberately don't count — minting one in settings must
        // not consume the first-localhost-device onboarding shortcut.
        markFirstDecisionMade(repo)
        updateTrusted(repo) { current ->
            // Re-check under the lock so racing callers can't double-add.
            if (current.devices.any { it.tokenHash == hash }) return@updateTrusted current
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
                trustedVia = via,
            )
            TrustedDevices(current.devices + added)
        }
        return hash
    }

    private suspend fun promptOrReject(
        tokenToPersist: String?,
        client: ClientInfo,
        repo: SettingsRepository,
    ): Decision = approvalMutex.withLock {
        val remoteAddress = client.remoteAddress
        val hash = tokenToPersist?.let { sha256Hex(it) }
        val now = clock()

        // Evict expired cache entries so the maps don't grow without bound.
        synchronized(suppressionLock) {
            recentDecisions.entries.removeAll { it.value.expiresAtMs <= now }
            recentIpDenials.entries.removeAll { it.value + IP_DENIAL_COOLDOWN_MS <= now }
        }

        // A previous waiter inside this same mutex session may have just
        // persisted this exact token as trusted (common at boot, when
        // /window + /api/ui-settings + several /pty sockets all race through
        // the gate with the same unknown cookie). Re-check the trusted set
        // before bothering the user again.
        if (hash != null) {
            val known = loadDevices(repo)
            if (known.devices.any { it.tokenHash == hash && it.label != MCP_LABEL }) {
                return@withLock Decision.APPROVED
            }
            // Same token, recent decision still valid — reuse it. This is
            // what makes a single "Deny" click on boot also deny the other
            // in-flight connections from the same browser instead of popping
            // a fresh dialog for each.
            synchronized(suppressionLock) { recentDecisions[hash] }?.let { cached ->
                return@withLock cached.decision
            }
        }

        // Per-IP prompt cooldown: a non-loopback address the user denied
        // moments ago is rejected outright instead of re-prompting.
        if (!isLoopback(remoteAddress) &&
            synchronized(suppressionLock) { recentIpDenials.containsKey(remoteAddress) }
        ) {
            log.info(
                "DeviceAuth: rejecting {} without prompting (address denied within the last {} s)",
                remoteAddress,
                IP_DENIAL_COOLDOWN_MS / 1000,
            )
            return@withLock Decision.REJECTED
        }

        if (headlessCheck()) {
            log.warn(
                "Rejecting connection from {}: no token or unknown token, and JVM is headless so no approval dialog can be shown",
                remoteAddress,
            )
            return@withLock Decision.HEADLESS
        }
        val choice = approvalPrompt(client)
        // Re-read the clock: [now] was taken before the dialog went up, and a
        // dialog sits on a human's desk for as long as it takes them to read
        // it. Stamping the suppressions and the persisted entry with [now]
        // would date them from when the prompt *opened* — so a dialog left up
        // longer than RECENT_DECISION_TTL_MS wrote an already-expired cache
        // entry, and every connection queued behind it re-prompted and
        // appended its own duplicate denial.
        val decidedAt = clock()

        if (choice != ApprovalChoice.APPROVE) {
            // Both refusals quiet the sibling requests racing this one
            // (/window + /api/ui-settings + several /pty sockets on a single
            // boot) so they don't each pop their own dialog. What separates
            // them is what outlives that: a denial is written down, a
            // dismissal is not.
            val dismissed = choice == ApprovalChoice.DISMISS
            if (dismissed) {
                log.info("User dismissed the approval dialog for {}; no denial persisted", remoteAddress)
            } else {
                log.info("User denied device from {}", remoteAddress)
            }

            // Refusing to choose is still a choice, and the latch records the
            // human rather than the device: someone was sitting here, saw a
            // prompt, and declined to bless it. That is exactly the evidence
            // the clean-slate loopback shortcut needs to *not* have — it grants
            // trust with no prompt at all on the theory that nobody has been
            // asked anything yet, which stops being true the moment this
            // dialog goes up. Unlike the denial below, this doesn't depend on
            // there being a token worth writing down.
            markFirstDecisionMade(repo)

            // The per-IP cooldown is a denial's blast radius: it silences every
            // token from that address, which is what stops a scanner cycling
            // random tokens from becoming dialog spam. A dismissal hasn't
            // accused anyone of anything, so it only quiets the device it was
            // about — otherwise "Not now" would lock out the very phone the
            // user is about to retry from, and any other device sharing its IP.
            if (!dismissed && !isLoopback(remoteAddress)) {
                synchronized(suppressionLock) { recentIpDenials[remoteAddress] = decidedAt }
            }
            if (hash != null) {
                val quietFor = if (dismissed) DISMISS_SUPPRESSION_MS else RECENT_DECISION_TTL_MS
                synchronized(suppressionLock) {
                    recentDecisions[hash] = CachedDecision(Decision.REJECTED, decidedAt + quietFor)
                }
            }
            if (dismissed) return@withLock Decision.REJECTED

            if (hash != null) {
                // Persist the denial so the next attempt from this token
                // doesn't re-prompt. The entry is revocable from the settings
                // dialog ("Unban") in case of a misclick.
                val initialConnection = ClientConnection(
                    type = client.type,
                    hostname = client.hostname,
                    selfReportedIp = client.selfReportedIp,
                    remoteAddress = remoteAddress,
                    firstSeenEpochMs = decidedAt,
                    lastSeenEpochMs = decidedAt,
                )
                val added = DeniedDevice(
                    tokenHash = hash,
                    firstSeenEpochMs = decidedAt,
                    lastSeenEpochMs = decidedAt,
                    lastIp = remoteAddress,
                    connections = listOf(initialConnection),
                )
                updateDenied(repo) { current ->
                    // Fold into the existing row rather than appending a twin:
                    // one device denied twice is still one denied device, and
                    // duplicates read as separate phones in the settings list.
                    // Mirrors the re-check persistTrustedDevice does under the
                    // same lock.
                    if (current.devices.any { it.tokenHash == hash }) {
                        DeniedDevices(
                            current.devices.map {
                                if (it.tokenHash == hash) it.copy(
                                    lastSeenEpochMs = decidedAt,
                                    lastIp = remoteAddress,
                                    connections = mergeConnections(it.connections, client, decidedAt),
                                ) else it
                            },
                        )
                    } else {
                        DeniedDevices(current.devices + added)
                    }
                }
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
        persistTrustedDevice(tokenToPersist, client, repo, TRUSTED_VIA_DIALOG)
        synchronized(suppressionLock) {
            recentDecisions[hash] = CachedDecision(
                Decision.APPROVED,
                decidedAt + RECENT_DECISION_TTL_MS,
            )
        }
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
    private suspend fun showApprovalDialog(client: ClientInfo): ApprovalChoice {
        val deferred = CompletableDeferred<ApprovalChoice>()
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
                onResult = { choice -> pending.result.complete(choice) },
            )
        }
    }

    @Composable
    private fun ApprovalDialog(client: ClientInfo, onResult: (ApprovalChoice) -> Unit) {
        var selectedApprove by remember { mutableStateOf<Boolean?>(null) }

        val dialogState = rememberDialogState(
            size = DpSize(560.dp, 480.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        DialogWindow(
            // Closing the window decides nothing \u2014 see ApprovalChoice.DISMISS.
            onCloseRequest = { onResult(ApprovalChoice.DISMISS) },
            title = "Lunamux \u2014 New device",
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
            MaterialTheme(colorScheme = SettingsDialog.lunamuxColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "A new device is trying to connect to Lunamux.",
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
                            // Gives dismissal a visible home. Without it the
                            // only way out without deciding is the window's
                            // close box, which reads as an accident.
                            TextButton(onClick = { onResult(ApprovalChoice.DISMISS) }) {
                                Text("Not now")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onResult(
                                        if (selectedApprove == true) {
                                            ApprovalChoice.APPROVE
                                        } else {
                                            ApprovalChoice.DENY
                                        },
                                    )
                                },
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

    // Every trusted/denied mutation is a load-modify-save on one of two SQLite
    // keys. Serialize them through a single JVM monitor so concurrent connects
    // — most sharply, a QR pairing landing while the desktop approval dialog is
    // being answered — can't both read the same snapshot, append different
    // devices, and have the second save clobber the first (a silently-dropped
    // trusted device). The critical section is one SQLite read + write with no
    // suspension, so a plain lock is safe on both the coroutine and event-loop
    // threads that reach here, and it is always the innermost lock (callers may
    // hold approvalMutex around it, never the reverse), so there is no cycle.
    private val deviceStoreLock = Any()

    /** Atomically load the trusted-device list, apply [transform], persist it. */
    private fun updateTrusted(repo: SettingsRepository, transform: (TrustedDevices) -> TrustedDevices) {
        synchronized(deviceStoreLock) { saveDevices(repo, transform(loadDevices(repo))) }
    }

    /**
     * Whether this install has ever trusted or denied an interactive device.
     *
     * Called from [tryAutoApproveCleanSlateLoopback], the only consumer: a
     * `true` here means the no-prompt onboarding shortcut is spent for good.
     *
     * @param repo the settings repository holding [FIRST_DECISION_KEY].
     * @return true once any interactive trust or denial has been persisted.
     * @see markFirstDecisionMade
     */
    private fun hasMadeFirstDecision(repo: SettingsRepository): Boolean =
        repo.getString(FIRST_DECISION_KEY) != null

    /**
     * Latch [FIRST_DECISION_KEY]. Idempotent, never unset.
     *
     * Called from [persistTrustedDevice] (covering the clean-slate, QR and
     * dialog trust paths) and from the dialog's deny branch.
     *
     * Note for upgrades: installs predating this key have it unset. One that
     * already holds devices is still protected by the empty-list checks in
     * [tryAutoApproveCleanSlateLoopback]; one that had revoked everything
     * keeps the old behaviour exactly once more, then latches.
     *
     * @param repo the settings repository to persist into.
     * @see hasMadeFirstDecision
     */
    private fun markFirstDecisionMade(repo: SettingsRepository) {
        if (repo.getString(FIRST_DECISION_KEY) == null) {
            repo.putString(FIRST_DECISION_KEY, "true")
            log.info("DeviceAuth: first trust decision recorded; clean-slate auto-approve is now spent")
        }
    }

    /** Atomically load the denied-device list, apply [transform], persist it. */
    private fun updateDenied(repo: SettingsRepository, transform: (DeniedDevices) -> DeniedDevices) {
        synchronized(deviceStoreLock) { saveDeniedDevices(repo, transform(loadDeniedDevices(repo))) }
    }

    /**
     * Atomically bump lastSeen/lastIp and merge [client] into the trusted
     * device with [tokenHash]. No-op if the device was concurrently revoked.
     */
    private fun touchTrustedDevice(repo: SettingsRepository, tokenHash: String, client: ClientInfo) {
        val now = clock()
        updateTrusted(repo) { current ->
            TrustedDevices(
                current.devices.map {
                    if (it.tokenHash == tokenHash) it.copy(
                        lastSeenEpochMs = now,
                        lastIp = client.remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    ) else it
                },
            )
        }
    }

    /** Denied-list counterpart of [touchTrustedDevice]. */
    private fun touchDeniedDevice(repo: SettingsRepository, tokenHash: String, client: ClientInfo) {
        val now = clock()
        updateDenied(repo) { current ->
            DeniedDevices(
                current.devices.map {
                    if (it.tokenHash == tokenHash) it.copy(
                        lastSeenEpochMs = now,
                        lastIp = client.remoteAddress,
                        connections = mergeConnections(it.connections, client, now),
                    ) else it
                },
            )
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
