/**
 * In-memory registry of one-time QR pairing tokens.
 *
 * The desktop pairing dialog mints a token when it opens ([PairingTokens.mint])
 * and kills it when it closes ([PairingTokens.invalidate]); a scanning client
 * sends the raw token on its first connect, and [DeviceAuth] spends it via
 * [PairingTokens.consume] to trust the device without an approval dialog.
 *
 * Tokens are 256-bit SecureRandom values. Only their SHA-256 hashes are held
 * here (never the raw string), each entry is claimable by a single device, and
 * every entry expires [PairingTokens.TTL_MS] after minting — so a photographed
 * QR code goes stale in minutes and can never be redeemed by a second device.
 *
 * State is process-lifetime and deliberately not persisted: a pairing token
 * is only meaningful while the pairing dialog is on screen.
 *
 * @see DeviceAuth
 */
package se.soderbjorn.lunamux.auth

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Thread-safe (`@Synchronized`) mint/consume registry for pairing tokens.
 *
 * All entry points take an injectable `nowMs` so tests can drive expiry
 * without sleeping; production callers use the default clock.
 */
object PairingTokens {

    private val log = LoggerFactory.getLogger(PairingTokens::class.java)

    /** Lifetime of a minted token; QR codes older than this must be re-shown. */
    const val TTL_MS: Long = 5 * 60_000

    /**
     * A minted token's server-side state.
     *
     * [claimedBy] is null until the first successful [consume] and then holds
     * the sha256 of the device token that spent it, which is what makes the
     * single-use rule "one token, one device" rather than "one token, one
     * request" — see [consume].
     */
    private data class Entry(val expiresAtMs: Long, var claimedBy: String? = null)

    /** sha256-hex(raw token) → entry. Guarded by @Synchronized. */
    private val pending = HashMap<String, Entry>()

    /**
     * Mint a fresh pairing token, claimable by one device, and register its
     * hash for [TTL_MS]. Called by the pairing dialog each time it opens, so
     * every showing of the QR carries a brand-new secret.
     *
     * @param nowMs the current clock, injectable for tests.
     * @return the raw base64url token to embed in the QR payload — the only
     *   copy of it; this registry keeps just the hash.
     * @see consume
     * @see invalidate
     */
    @Synchronized
    fun mint(nowMs: Long = System.currentTimeMillis()): String {
        evictExpired(nowMs)
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pending[sha256Hex(raw)] = Entry(expiresAtMs = nowMs + TTL_MS)
        log.info("PairingTokens: minted pairing token (ttl {} s, {} outstanding)", TTL_MS / 1000, pending.size)
        return raw
    }

    /**
     * Claim [candidate] for the device identified by [deviceHash], granting it
     * trust exactly once. Comparison is constant-time on the hash and stale
     * entries are evicted first.
     *
     * Returns `true` only for the call that *first* claims a live token; every
     * later presentation returns `false`, whoever makes it. A scanning client
     * attaches its `pairToken` to every request until the connect succeeds, so
     * `/window`, `/api/ui-settings` and the `/pty` sockets routinely present
     * one token at once — but the losers of that race don't need a second
     * grant, because [DeviceAuth] has already persisted the device as trusted
     * by the time they get here and they pass on the ordinary trusted lookup.
     * Granting trust once, and only once, is what keeps a revoke from being
     * undone by a token the device is still replaying.
     *
     * Unlike a plain remove-on-spend, the entry survives its claim (until
     * [TTL_MS] or [invalidate]) so [isClaimed] can tell "already spent" apart
     * from "expired" and the panel can replace a dead QR.
     *
     * Called by [DeviceAuth]'s pairing approval path on every connect that
     * carries a `pairToken`.
     *
     * @param candidate the raw token string received from the client.
     * @param deviceHash sha256 of the connecting device's token; recorded as
     *   the claimant so logs can tie a QR to the device that redeemed it.
     * @param nowMs the current clock, injectable for tests.
     * @return `true` on the one call that claims [candidate]; `false` if it is
     *   unknown, expired, blank, or already claimed.
     * @see mint
     * @see isClaimed
     */
    @Synchronized
    fun consume(candidate: String, deviceHash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        evictExpired(nowMs)
        if (candidate.isBlank() || deviceHash.isBlank()) return false
        val hash = sha256Hex(candidate)
        val match = pending.keys.firstOrNull {
            MessageDigest.isEqual(it.toByteArray(), hash.toByteArray())
        } ?: return false
        val entry = pending.getValue(match)
        if (entry.claimedBy != null) {
            log.info(
                "PairingTokens: pairing token already claimed; refusing re-grant to {}",
                deviceHash.take(10),
            )
            return false
        }
        entry.claimedBy = deviceHash
        log.info("PairingTokens: pairing token claimed by device {}", deviceHash.take(10))
        return true
    }

    /**
     * Has [rawToken] already been claimed by a device?
     *
     * The pairing panel polls this so it can mint a replacement once the
     * on-screen QR has been spent: a displayed code that silently stops
     * working is what sends the *second* device someone scans into the
     * approval dialog instead of pairing it.
     *
     * @param rawToken the raw token returned by [mint].
     * @return `true` if a device has claimed it; `false` if it is unclaimed,
     *   expired, or unknown.
     * @see consume
     */
    @Synchronized
    fun isClaimed(rawToken: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        evictExpired(nowMs)
        return pending[sha256Hex(rawToken)]?.claimedBy != null
    }

    /**
     * Kill a specific outstanding token. Called by the pairing dialog's
     * dispose hook so a token dies the moment its QR leaves the screen.
     *
     * @param rawToken the raw token returned by [mint].
     */
    @Synchronized
    fun invalidate(rawToken: String) {
        if (pending.remove(sha256Hex(rawToken)) != null) {
            log.info("PairingTokens: pairing token invalidated ({} outstanding)", pending.size)
        }
    }

    /** Drop entries whose expiry has passed; callers hold the monitor. */
    private fun evictExpired(nowMs: Long) {
        pending.entries.removeAll { it.value.expiresAtMs <= nowMs }
    }

    /** Lowercase-hex SHA-256, matching [DeviceAuth]'s token hashing. */
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
