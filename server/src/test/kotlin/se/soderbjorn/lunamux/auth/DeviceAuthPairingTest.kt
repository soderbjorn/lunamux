/**
 * Tests for [DeviceAuth]'s QR-pairing approval path and the related
 * hardening: pairing-token trust (gated behind allow-remote for non-loopback
 * peers), fall-through on spent/foreign pairing tokens, silent rejection of
 * token-less remote peers, the append semantics of trust persistence
 * (a pairing or clean-slate approval must never wipe existing entries), and
 * the `trustedVia` provenance stamp — including that entries persisted
 * before that field existed still load and still authorize.
 *
 * Everything here goes through [DeviceAuth.checkFastPath], which never
 * shows a dialog — the interactive prompt path is exercised manually.
 */
package se.soderbjorn.lunamux.auth

import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAuthPairingTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("lunamux-pairing-auth", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    private fun remoteClient(address: String = "192.168.1.50") = DeviceAuth.ClientInfo(
        type = "Android",
        hostname = "phone",
        selfReportedIp = address,
        remoteAddress = address,
    )

    private fun loopbackClient() = DeviceAuth.ClientInfo(
        type = "Web",
        hostname = null,
        selfReportedIp = null,
        remoteAddress = "127.0.0.1",
    )

    @Test
    fun `pairing token trusts a remote device when allow-remote is on`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val pairToken = PairingTokens.mint()

        val decision = DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken)

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size)

        // Reconnect re-sending the consumed pairing token: falls through to
        // the trusted-device lookup and passes.
        val again = DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken)
        assertEquals(DeviceAuth.Decision.APPROVED, again)
    }

    @Test
    fun `pairing token from a remote device is rejected while allow-remote is off`() {
        val repo = tempRepo()
        assertFalse(repo.isAllowRemoteConnections())
        val pairToken = PairingTokens.mint()

        // The network gate runs first, so pairing can never widen the scope:
        // the UI disables "Pair a device" until the toggle is on, and a token
        // minted before it was switched back off must not sneak past.
        val decision = DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken)

        assertEquals(DeviceAuth.Decision.REJECTED, decision)
        assertFalse(repo.isAllowRemoteConnections(), "pairing must never enable allow-remote")
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())

        // The gate rejected before the spend, so the token is still live for a
        // retry once the user turns the setting on.
        repo.setAllowRemoteConnections(true)
        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-token-1", remoteClient(), repo, pairToken),
        )
    }

    /**
     * Seed a denial for [token]. The production deny path runs off the
     * approval dialog, which a headless test can't click, so this writes the
     * same persisted blob directly. Callers assert [DeviceAuth.listDeniedDevices]
     * to confirm the seed deserialized before relying on it.
     */
    private fun seedDeniedDevice(repo: SettingsRepository, token: String, ip: String = "192.168.1.50") {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        repo.putString(
            "auth.denied_devices.v1",
            """{"devices":[{"tokenHash":"$hash","firstSeenEpochMs":1,""" +
                """"lastSeenEpochMs":1,"lastIp":"$ip","connections":[]}]}""",
        )
    }

    @Test
    fun `a denied device cannot pair with a QR code`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        seedDeniedDevice(repo, "device-token-denied")
        assertEquals(1, DeviceAuth.listDeniedDevices(repo).size, "denial seed failed to deserialize")
        val pairToken = PairingTokens.mint()

        val decision = DeviceAuth.checkFastPath("device-token-denied", remoteClient(), repo, pairToken)

        // Scanning must not launder a denial into trust: "Unban" is the only undo.
        assertEquals(DeviceAuth.Decision.REJECTED, decision)
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty(), "denied device must not become trusted")
        assertEquals(1, DeviceAuth.listDeniedDevices(repo).size, "the denial must survive the scan")

        // The QR was ignored before the spend, so the same code still works
        // once the user unbans the device from settings.
        val hash = DeviceAuth.listDeniedDevices(repo).first().tokenHash
        assertTrue(DeviceAuth.unbanDeniedDevice(repo, hash))
        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-token-denied", remoteClient(), repo, pairToken),
        )
    }

    @Test
    fun `foreign pairing token falls through to the normal flow`() {
        val repo = tempRepo()
        // Allow-remote off: the network gate rejects the unknown remote device.
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath("device-token-2", remoteClient(), repo, "not-a-minted-token"),
        )
        assertFalse(repo.isAllowRemoteConnections())
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())

        // Allow-remote on: the unknown token needs the interactive prompt (null).
        repo.setAllowRemoteConnections(true)
        assertNull(DeviceAuth.checkFastPath("device-token-2", remoteClient(), repo, "not-a-minted-token"))
    }

    @Test
    fun `token-less remote peers are rejected without prompting`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath(null, remoteClient(), repo),
        )
        assertEquals(
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.checkFastPath("", remoteClient(), repo),
        )
    }

    @Test
    fun `token-less loopback keeps the prompt path`() {
        val repo = tempRepo()
        // null = "needs the interactive flow" — the loopback curl/dev
        // experience is unchanged by the remote hardening.
        assertNull(DeviceAuth.checkFastPath(null, loopbackClient(), repo))
    }

    @Test
    fun `pairing appends to the trusted list instead of replacing it`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.addTrustedToken(repo, "pre-existing", label = "Browser")
        val pairToken = PairingTokens.mint()

        val decision = DeviceAuth.checkFastPath("device-token-3", remoteClient("192.168.1.51"), repo, pairToken)

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertEquals(2, DeviceAuth.listTrustedDevices(repo).size)
    }

    @Test
    fun `concurrent pairings all persist without losing devices`() {
        val repo = tempRepo()
        val n = 24
        // Loopback so the network gate is a no-op and every repo access
        // funnels through the locked trusted-list writer — isolating the
        // load-modify-save race this guards against.
        val tokens = (0 until n).map { PairingTokens.mint() }
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        val threads = (0 until n).map { i ->
            thread {
                ready.countDown()
                go.await()
                DeviceAuth.checkFastPath(
                    token = "device-token-concurrent-$i",
                    client = loopbackClient(),
                    repo = repo,
                    pairToken = tokens[i],
                )
            }
        }
        ready.await()
        go.countDown() // release all threads at once for maximum contention
        threads.forEach { it.join() }

        // Every pairing must have survived; a lost-update race would drop some.
        assertEquals(n, DeviceAuth.listTrustedDevices(repo).size)
    }

    /**
     * One device, one pairing token, many simultaneous requests — the shape a
     * real scan produces, since the client attaches its pairToken to /window,
     * /api/ui-settings and every /pty socket until the connect succeeds.
     *
     * A remove-on-first-spend token let exactly one of these win and dropped
     * the rest through to the approval dialog, so a pairing that had already
     * succeeded still popped a prompt. Every request must resolve to APPROVED
     * off the token's own claim.
     */
    @Test
    fun `one device's parallel requests all pair off a single token`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val pairToken = PairingTokens.mint()
        val n = 8
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        val decisions = java.util.Collections.synchronizedList(mutableListOf<DeviceAuth.Decision?>())
        val threads = (0 until n).map {
            thread {
                ready.countDown()
                go.await()
                decisions.add(
                    DeviceAuth.checkFastPath("device-token-parallel", remoteClient(), repo, pairToken),
                )
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }

        // null would mean "fall through to the interactive prompt" — the bug.
        assertEquals(n, decisions.size)
        assertTrue(
            decisions.all { it == DeviceAuth.Decision.APPROVED },
            "every parallel request must pair, got $decisions",
        )
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size, "the device must be trusted exactly once")
    }

    /**
     * A paired client keeps sending its pairToken for the rest of the token's
     * TTL. Those requests must land on the trusted lookup — the path that
     * refreshes the device's history — rather than being short-circuited by
     * the pairing shortcut, which would freeze last-seen at the pairing
     * instant for as long as the QR stayed live.
     */
    @Test
    fun `later requests still re-sending the pairing token refresh device history`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val pairToken = PairingTokens.mint()

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-history", remoteClient("192.168.1.70"), repo, pairToken),
        )
        val afterPairing = DeviceAuth.listTrustedDevices(repo).single()

        // Same live pairToken, new address — as a phone moving networks would.
        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-history", remoteClient("192.168.1.71"), repo, pairToken),
        )

        val afterReconnect = DeviceAuth.listTrustedDevices(repo).single()
        assertEquals("192.168.1.71", afterReconnect.lastIp, "last-seen IP must track the live connection")
        assertTrue(
            afterReconnect.connections.size > afterPairing.connections.size,
            "the second connection must be recorded, not swallowed by the pairing shortcut",
        )
    }

    @Test
    fun `a second device cannot claim an already-claimed pairing token`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val pairToken = PairingTokens.mint()

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-first", remoteClient("192.168.1.60"), repo, pairToken),
        )

        // Single-use still holds where it matters: someone who photographed the
        // QR must not be able to redeem it behind the real device's back.
        assertNull(
            DeviceAuth.checkFastPath("device-second", remoteClient("192.168.1.61"), repo, pairToken),
            "a foreign device must fall through to the prompt, not pair",
        )
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size)
    }

    /**
     * Seed a trusted entry in the pre-provenance on-disk format: no
     * `trustedVia` key at all, exactly as [DeviceAuth] wrote it before the
     * field existed. Hand-rolled rather than round-tripped through the
     * current writer, which would always emit the new key and so could never
     * reproduce what is actually sitting in users' databases.
     */
    private fun seedLegacyTrustedDevice(
        repo: SettingsRepository,
        token: String,
        firstSeen: Long,
        ip: String = "127.0.0.1",
    ) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        repo.putString(
            "auth.trusted_devices.v1",
            """{"devices":[{"tokenHash":"$hash","firstSeenEpochMs":$firstSeen,""" +
                """"lastSeenEpochMs":$firstSeen,"lastIp":"$ip","connections":[""" +
                """{"type":"Computer","hostname":"mac","selfReportedIp":"$ip",""" +
                """"remoteAddress":"$ip","firstSeenEpochMs":$firstSeen,""" +
                """"lastSeenEpochMs":$firstSeen}]}]}""",
        )
    }

    @Test
    fun `a legacy trusted entry deserializes with no provenance and still authorizes`() {
        val repo = tempRepo()
        val approvedAt = 1_700_000_000_000L
        seedLegacyTrustedDevice(repo, "legacy-token", approvedAt)

        val device = DeviceAuth.listTrustedDevices(repo).single()

        // The whole point of defaulting the field: a blob written before it
        // existed must load, not throw, and must not invent an answer.
        assertNull(device.trustedVia, "legacy entries carry no provenance and must not fabricate one")
        assertEquals(approvedAt, device.firstSeenEpochMs, "the approval date must survive the upgrade")
        assertEquals(1, device.connections.size)

        // A device trusted before the upgrade must still get in afterwards.
        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("legacy-token", loopbackClient(), repo),
        )
    }

    @Test
    fun `reconnecting leaves the approval date and provenance untouched`() {
        val repo = tempRepo()
        val approvedAt = 1_700_000_000_000L
        seedLegacyTrustedDevice(repo, "legacy-token", approvedAt)

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("legacy-token", loopbackClient(), repo),
        )

        // The settings dialog shows firstSeen as "Approved <date>", which only
        // holds if a reconnect bumps lastSeen without dragging firstSeen along.
        val device = DeviceAuth.listTrustedDevices(repo).single()
        assertEquals(approvedAt, device.firstSeenEpochMs, "first-seen must stay pinned to the approval")
        assertTrue(device.lastSeenEpochMs > approvedAt, "the reconnect should have bumped last-seen")
        assertNull(device.trustedVia, "a touch must not invent provenance for a legacy entry")
    }

    @Test
    fun `the clean-slate loopback shortcut stamps its provenance`() {
        val repo = tempRepo()

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-token-auto", loopbackClient(), repo),
        )

        // This is the one path that grants trust with no human ceremony, so
        // the list has to be able to call it out.
        val device = DeviceAuth.listTrustedDevices(repo).single()
        assertEquals(DeviceAuth.TRUSTED_VIA_AUTO, device.trustedVia)
    }

    @Test
    fun `QR pairing stamps its provenance`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        val pairToken = PairingTokens.mint()

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("device-token-qr", remoteClient(), repo, pairToken),
        )

        val device = DeviceAuth.listTrustedDevices(repo).single()
        assertEquals(DeviceAuth.TRUSTED_VIA_QR, device.trustedVia)
        // Approval date and provenance describe the same instant.
        assertTrue(device.firstSeenEpochMs > 0)
    }

    @Test
    fun `clean-slate loopback auto-approve preserves MCP tokens`() {
        val repo = tempRepo()
        DeviceAuth.addTrustedToken(repo, "mcp-abc", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)

        // Only MCP tokens exist → the clean-slate loopback shortcut fires for
        // the first interactive localhost device…
        val decision = DeviceAuth.checkFastPath("device-token-4", loopbackClient(), repo)
        assertEquals(DeviceAuth.Decision.APPROVED, decision)

        // …and must not wipe the MCP token while persisting the new device.
        assertEquals(1, DeviceAuth.listMcpTokens(repo).size)
        assertEquals(2, DeviceAuth.listTrustedDevices(repo).size)
    }

    @Test
    fun `revoking every device does not re-arm the auto-approve shortcut`() {
        val repo = tempRepo()
        // Fresh install: the first localhost device rides the shortcut.
        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("first-device", loopbackClient(), repo),
        )

        // Wipe the install back to an empty trusted list. Before the
        // first-decision latch this re-armed the shortcut, so the next
        // localhost caller was trusted with no prompt — long past onboarding.
        val hash = DeviceAuth.listTrustedDevices(repo).single().tokenHash
        assertTrue(DeviceAuth.revokeTrustedDevice(repo, hash))
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())

        // null = falls through to the interactive dialog, not auto-approved.
        assertNull(DeviceAuth.checkFastPath("second-device", loopbackClient(), repo))
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())
    }

    @Test
    fun `revoking a device trusted by an older version does not re-arm the shortcut`() {
        val repo = tempRepo()
        // An install that predates the first-decision latch: it has a trusted
        // interactive device but no latch key. addTrustedToken models that
        // exactly — it persists trust without latching, as old versions did.
        DeviceAuth.addTrustedToken(repo, "device-from-old-version", label = "Browser")

        // Revoking is the moment the evidence would vanish. Before the
        // backfill, the list went empty with the key still unset, and the next
        // localhost caller was silently auto-approved.
        val hash = DeviceAuth.listTrustedDevices(repo).single().tokenHash
        assertTrue(DeviceAuth.revokeTrustedDevice(repo, hash))
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())

        assertNull(DeviceAuth.checkFastPath("new-device", loopbackClient(), repo))
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())
    }

    @Test
    fun `revoking an MCP token alone does not spend the shortcut`() {
        val repo = tempRepo()
        // MCP tokens never counted against the clean slate, so minting and
        // revoking one must leave a genuinely fresh install still fresh.
        DeviceAuth.addTrustedToken(repo, "mcp-tok", DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ)
        val hash = DeviceAuth.listMcpTokens(repo).single().tokenHash
        assertTrue(DeviceAuth.revokeTrustedDevice(repo, hash))

        assertEquals(
            DeviceAuth.Decision.APPROVED,
            DeviceAuth.checkFastPath("first-real-device", loopbackClient(), repo),
        )
    }

    @Test
    fun `a denial spends the auto-approve shortcut even after an unban`() {
        val repo = tempRepo()
        seedDeniedDevice(repo, "denied-device", ip = "127.0.0.1")
        // Seeding writes the list directly, as the real deny path's dialog
        // can't be clicked here; latch by hand to stand in for that branch.
        DeviceAuth.checkFastPath("paired-device", loopbackClient(), repo, PairingTokens.mint())
        DeviceAuth.revokeTrustedDevice(
            repo,
            DeviceAuth.listTrustedDevices(repo).single().tokenHash,
        )
        DeviceAuth.unbanDeniedDevice(repo, DeviceAuth.listDeniedDevices(repo).single().tokenHash)

        // Both lists are empty again, but the install is not fresh.
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())
        assertTrue(DeviceAuth.listDeniedDevices(repo).isEmpty())
        assertNull(DeviceAuth.checkFastPath("later-device", loopbackClient(), repo))
    }
}
