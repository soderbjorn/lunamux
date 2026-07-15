/**
 * Tests for [PairingTokens]: per-device claim semantics, TTL expiry (driven via
 * the injectable clock, no sleeping), and explicit invalidation.
 */
package se.soderbjorn.lunamux.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTokensTest {

    private val deviceA = "a".repeat(64)
    private val deviceB = "b".repeat(64)

    /**
     * The pairing panel polls this to know when to replace the code on screen:
     * a claimed QR is dead for everyone but its claimant, so a second phone
     * scanning it would land in the approval dialog instead of pairing.
     */
    @Test
    fun isClaimedReportsWhetherTheCodeOnScreenIsStillUsable() {
        val now = 1_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.isClaimed(token, nowMs = now + 1))

        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 2))
        assertTrue(PairingTokens.isClaimed(token, nowMs = now + 3))

        // An expired token is not "claimed" — it is simply gone.
        assertFalse(PairingTokens.isClaimed(token, nowMs = now + PairingTokens.TTL_MS + 1))
        assertFalse(PairingTokens.isClaimed("never-minted", nowMs = now))
    }

    @Test
    fun mintedTokenIsClaimedByFirstDevice() {
        val now = 1_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(token.length >= 43) // 32 bytes base64url, no padding
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
    }

    /**
     * A token grants trust once and never again — not even to the device that
     * claimed it. Those later presentations are expected (the client re-sends
     * its pairToken until the connect succeeds); they simply aren't a second
     * grant, so they fall through to DeviceAuth's trusted lookup, which is
     * what refreshes the device's history and honours a revoke.
     */
    @Test
    fun claimingDeviceGetsNoSecondGrant() {
        val now = 1_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 2))
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 3))
    }

    /** Single-use where it matters: a photographed QR can't pair a 2nd device. */
    @Test
    fun claimedTokenIsRefusedForAnotherDevice() {
        val now = 1_200_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume(token, deviceB, nowMs = now + 2))
    }

    @Test
    fun tokenExpiresAfterTtl() {
        val now = 2_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + PairingTokens.TTL_MS + 1))
    }

    @Test
    fun tokenValidJustBeforeTtl() {
        val now = 3_000_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + PairingTokens.TTL_MS - 1))
    }

    @Test
    fun invalidateKillsOutstandingToken() {
        val now = 4_000_000L
        val token = PairingTokens.mint(nowMs = now)
        PairingTokens.invalidate(token)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 1))
    }

    /** Closing the pairing panel must revoke even an already-claimed token. */
    @Test
    fun invalidateKillsClaimedToken() {
        val now = 4_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(token, deviceA, nowMs = now + 1))
        PairingTokens.invalidate(token)
        assertFalse(PairingTokens.consume(token, deviceA, nowMs = now + 2))
        assertFalse(PairingTokens.isClaimed(token, nowMs = now + 3), "an invalidated token is gone, not claimed")
    }

    /**
     * Concurrent presentations of one fresh token must produce exactly one
     * grant — the claim is what authorizes the pairing, so two winners would
     * mean two devices trusted off a single QR.
     */
    @Test
    fun onlyOneOfManyRacingCallersClaimsTheToken() {
        val token = PairingTokens.mint()
        val n = 16
        val ready = java.util.concurrent.CountDownLatch(n)
        val go = java.util.concurrent.CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until n).map { i ->
            kotlin.concurrent.thread {
                ready.countDown()
                go.await()
                if (PairingTokens.consume(token, "device-$i".repeat(4))) wins.incrementAndGet()
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }

        assertEquals(1, wins.get(), "exactly one caller may claim a token")
    }

    @Test
    fun unknownAndBlankCandidatesAreRejected() {
        val now = 5_000_000L
        PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume("not-a-real-token", deviceA, nowMs = now + 1))
        assertFalse(PairingTokens.consume("", deviceA, nowMs = now + 1))
    }

    @Test
    fun blankDeviceHashIsRejected() {
        val now = 5_100_000L
        val token = PairingTokens.mint(nowMs = now)
        assertFalse(PairingTokens.consume(token, "", nowMs = now + 1))
    }

    @Test
    fun tokensAreIndependent() {
        val now = 6_000_000L
        val a = PairingTokens.mint(nowMs = now)
        val b = PairingTokens.mint(nowMs = now)
        assertTrue(PairingTokens.consume(b, deviceA, nowMs = now + 1))
        assertTrue(PairingTokens.consume(a, deviceB, nowMs = now + 2))
    }
}
