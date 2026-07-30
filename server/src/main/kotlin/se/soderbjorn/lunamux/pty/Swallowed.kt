/**
 * A counter for exceptions the PTY path deliberately swallows.
 *
 * This file contains [Swallowed], a tiny process-wide tally of the `catch` blocks that
 * exist so a single malformed control sequence, a resize race, or a dying process cannot
 * take the PTY read loop down with it. Those catches are correct — the read loop must
 * outlive any one chunk — but a silent one hides the next class of bug indefinitely
 * (a Keystore failure swallowed on Android cost a full re-pairing cycle per launch before
 * anyone noticed it was happening at all).
 *
 * So: still swallow, never rethrow, but log the first occurrence per site with its stack
 * and keep a count. The count is cheap enough for the hot path and turns "the grid looks
 * wrong sometimes" into a number anyone can read.
 *
 * @see SessionGrid.feed the hot path this protects
 */
package se.soderbjorn.lunamux.pty

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Swallowed {

    private val log = LoggerFactory.getLogger(Swallowed::class.java)

    /** Per-site tallies. Keyed by the short site name passed to [note]. */
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Record that [site] swallowed [t].
     *
     * Called from every `catch` in the PTY/grid path that intentionally continues. The
     * first throwable seen at a site is logged at WARN with its stack trace; later ones
     * only increment, so a repeating fault cannot flood the log or slow the read loop.
     *
     * @param site short, stable name of the swallow site, e.g. `"feed"` or `"resize"`.
     * @param t the exception being swallowed.
     */
    fun note(site: String, t: Throwable) {
        val n = counts.computeIfAbsent(site) { AtomicLong() }.incrementAndGet()
        if (n == 1L) {
            log.warn("Swallowed exception in PTY path (site={}); further occurrences counted only", site, t)
        }
    }

    /**
     * How many exceptions [site] has swallowed since process start.
     *
     * @param site the site name used with [note].
     * @return the tally, or 0 when that site has never thrown.
     */
    fun count(site: String): Long = counts[site]?.get() ?: 0L

    /**
     * Every non-zero tally, for surfacing in diagnostics or an admin endpoint.
     *
     * @return a snapshot map of site name to swallow count.
     */
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}
