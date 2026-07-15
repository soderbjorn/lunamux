/**
 * Tests for what [DeviceAuth]'s approval dialog *decides*: the three-way
 * split between approving, denying, and dismissing.
 *
 * Dismissal used to be indistinguishable from a denial — the dialog returned a
 * Boolean and its `onCloseRequest` passed `false` — so closing the window
 * permanently banned the device. These tests pin the distinction: only an
 * explicit deny writes the device down, while a dismissal leaves its status
 * open so a later attempt can still prompt. A dismissal is still the user
 * deciding, though, so it spends the first-decision latch like the other two.
 *
 * The interactive path is driven through [DeviceAuth.approvalPrompt] and
 * [DeviceAuth.headlessCheck]; the real dialog can't be clicked headlessly.
 */
package se.soderbjorn.lunamux.auth

import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAuthApprovalChoiceTest {

    private fun tempRepo(): SettingsRepository {
        val dir = File.createTempFile("lunamux-approval-choice", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        return SettingsRepository(File(dir, "test.db"))
    }

    // DeviceAuth is an object, so its in-memory suppression maps are shared
    // across tests in this JVM. Every test below uses a distinct device token
    // and a distinct remote IP so no test can be silenced by another's
    // recentDecisions / recentIpDenials entry.
    private fun clientAt(address: String) = DeviceAuth.ClientInfo(
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

    @BeforeTest
    fun forceInteractive() {
        DeviceAuth.headlessCheck = { false }
        DeviceAuth.clearTransientSuppressions()
    }

    @AfterTest
    fun restoreSeams() {
        DeviceAuth.headlessCheck = { java.awt.GraphicsEnvironment.isHeadless() }
        DeviceAuth.approvalPrompt = DeviceAuth.defaultApprovalPrompt
        DeviceAuth.clearTransientSuppressions()
    }

    @Test
    fun `dismissing the dialog rejects without persisting a denial`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.DISMISS }

        val decision = runBlocking {
            DeviceAuth.authorize("device-dismiss-1", clientAt("192.168.44.10"), repo)
        }

        // The connection in hand still fails closed...
        assertEquals(DeviceAuth.Decision.REJECTED, decision)
        // ...but nothing about the device was decided.
        assertTrue(DeviceAuth.listDeniedDevices(repo).isEmpty(), "dismissal must not ban the device")
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty(), "dismissal must not trust the device")
    }

    @Test
    fun `a dismissed device can still be approved on a later attempt`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)

        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.DISMISS }
        runBlocking { DeviceAuth.authorize("device-dismiss-2", clientAt("192.168.44.11"), repo) }

        // A dismissal still suppresses re-prompting for a few seconds so the
        // sibling requests racing it don't each pop a dialog. Lapse that (in
        // production it expires on its own) to reach the reconnect that counts.
        DeviceAuth.clearTransientSuppressions()

        // The whole point of the fix: a dismissal is "not now", not "never".
        // A persisted denial here would make this unreachable without an Unban.
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.APPROVE }
        val decision = runBlocking {
            DeviceAuth.authorize("device-dismiss-2", clientAt("192.168.44.12"), repo)
        }

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size)
    }

    /**
     * A dismissal decides nothing about the *device*, but it is still the user
     * making a call, so it spends the clean-slate loopback shortcut.
     *
     * This pins a rule, not a live hole. In practice the shortcut is almost
     * always gone before any dialog can appear: the desktop app's own loopback
     * client is auto-trusted on first connect, which latches, and allow-remote
     * — without which no LAN device reaches the prompt — can only be turned on
     * from the UI that client is serving. The narrow real case is a token-less
     * loopback request (a curl before first launch), which skips the
     * token-requiring auto-approve and prompts with the latch still armed.
     */
    @Test
    fun `dismissing the dialog spends the clean-slate shortcut`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.DISMISS }
        runBlocking { DeviceAuth.authorize("device-dismiss-latch", clientAt("192.168.44.50"), repo) }
        DeviceAuth.clearTransientSuppressions()

        // Both device lists are still empty — the only thing standing between
        // a fresh loopback token and silent trust is the latch.
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())
        assertTrue(DeviceAuth.listDeniedDevices(repo).isEmpty())

        // null = "must prompt". APPROVED here would mean auto-trusted, no dialog.
        assertNull(
            DeviceAuth.checkFastPath("device-loopback-after-dismiss", loopbackClient(), repo),
            "a dismissed prompt must leave the clean-slate auto-trust disarmed",
        )
    }

    @Test
    fun `denying the dialog persists the denial`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.DENY }

        val decision = runBlocking {
            DeviceAuth.authorize("device-deny-1", clientAt("192.168.44.20"), repo)
        }

        assertEquals(DeviceAuth.Decision.REJECTED, decision)
        assertEquals(1, DeviceAuth.listDeniedDevices(repo).size, "an explicit deny must persist")
        assertTrue(DeviceAuth.listTrustedDevices(repo).isEmpty())
    }

    @Test
    fun `approving the dialog persists trust`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.APPROVE }

        val decision = runBlocking {
            DeviceAuth.authorize("device-approve-1", clientAt("192.168.44.30"), repo)
        }

        assertEquals(DeviceAuth.Decision.APPROVED, decision)
        assertEquals(1, DeviceAuth.listTrustedDevices(repo).size)
        assertTrue(DeviceAuth.listDeniedDevices(repo).isEmpty())
    }

    @Test
    fun `a denied device stays denied without re-prompting`() {
        val repo = tempRepo()
        repo.setAllowRemoteConnections(true)
        DeviceAuth.approvalPrompt = { DeviceAuth.ApprovalChoice.DENY }
        runBlocking { DeviceAuth.authorize("device-deny-2", clientAt("192.168.44.40"), repo) }

        // Drop the short-lived caches and use a fresh IP, so neither the recent
        // -decision cache nor the per-IP cooldown can be what rejects: this has
        // to be the *persisted* denial doing the work, with no prompt at all.
        DeviceAuth.clearTransientSuppressions()
        DeviceAuth.approvalPrompt = { error("a denied device must never re-prompt") }
        val again = runBlocking {
            DeviceAuth.authorize("device-deny-2", clientAt("192.168.44.41"), repo)
        }

        assertEquals(DeviceAuth.Decision.REJECTED, again)
    }
}
