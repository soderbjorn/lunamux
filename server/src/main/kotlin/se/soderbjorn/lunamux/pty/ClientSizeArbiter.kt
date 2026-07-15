/**
 * Latest-active-client PTY size arbitration.
 *
 * [ClientSizeArbiter] decides the effective grid of a shared PTY from the
 * per-client size votes plus a record of which client the user actually
 * used last — the analogue of tmux's `window-size latest`. It replaces the
 * bare tiered-min() aggregation as [se.soderbjorn.lunamux.TerminalSession]'s
 * size policy while keeping that aggregation as the no-signal fallback, so
 * sessions where no activity has been observed behave exactly as before.
 *
 * @see se.soderbjorn.lunamux.pickEffectiveSize
 * @see se.soderbjorn.lunamux.TermSession.noteClientInput
 */
package se.soderbjorn.lunamux.pty

import se.soderbjorn.lunamux.SizePriority
import se.soderbjorn.lunamux.SizeVote
import se.soderbjorn.lunamux.pickEffectiveSize

/**
 * Arbitrates a shared PTY's effective grid across attached clients.
 *
 * One instance is owned by each `TerminalSession`; every mutation returns
 * the new effective `(cols, rows)` when it changed, or `null` when the
 * caller has nothing to apply (this is the no-op guard that keeps
 * per-keystroke [noteInput] calls from re-issuing resize syscalls).
 *
 * ## Policy
 *
 * The **governor** is the voting client with the most recent *activity*.
 * Activity is deliberately narrower than "sent any frame":
 *
 *  - input bytes ([noteInput]) — the user is typing on that client;
 *  - a forced resize ([forceSize]) — the user pressed Reformat there;
 *  - a [SizePriority.MOBILE] vote ([setSize]) — a phone/tablet viewport
 *    declaring itself; the terminal must become readable there the moment
 *    the user opens it (see `isMobileClientType`).
 *
 * Plain [SizePriority.NORMAL]/[SizePriority.THREE_D] votes are *ambient*
 * (ResizeObserver refits, window resizes, font loads, world transitions)
 * and update the client's vote without stealing governance — so a second
 * desktop window or a reconnect storm can no longer shrink a session the
 * user is actively typing in, and one keystroke on the desktop reclaims
 * the grid from a phone that was merely peeking.
 *
 * The effective size is then resolved as:
 *
 *  1. governor's vote, when it is [SizePriority.MOBILE] — an actively used
 *     phone always wins (upstream mobile-readability semantics, made
 *     activity-scoped instead of unconditional);
 *  2. otherwise the tiered min over [SizePriority.THREE_D] votes when any
 *     exist — the 3D world's enlarged grid is a mode assertion that a 2D
 *     viewer's typing must not clobber;
 *  3. otherwise the governor's vote;
 *  4. otherwise (no activity recorded) the classic tiered aggregation
 *     [pickEffectiveSize] — bit-for-bit the previous behaviour;
 *  5. with no votes at all the last effective size is held, so an idle
 *     restored session keeps its persisted grid instead of snapping back
 *     to the default.
 *
 * Thread-safe: all mutators are synchronized on the instance. Recency uses
 * an internal monotonic counter, not wall-clock time, so ordering is exact
 * and tests are deterministic.
 *
 * @param initialCols the grid the session starts at (persisted restore size
 *   or the session default) — held until a vote changes it.
 * @param initialRows see [initialCols].
 */
internal class ClientSizeArbiter(initialCols: Int, initialRows: Int) {

    private val votes = LinkedHashMap<String, SizeVote>()
    private val activity = HashMap<String, Long>()
    private var clock = 0L

    /** The currently arbitrated `(cols, rows)`. */
    var effective: Pair<Int, Int> = Pair(initialCols, initialRows)
        private set

    /**
     * Record that [clientId] delivered user input (keystrokes). Makes it the
     * governor; its registered vote (if any) becomes the effective size.
     *
     * Called by the `/pty` route on every inbound binary frame — hot path,
     * so the common case (already-governing client, unchanged size) returns
     * null after two map reads.
     *
     * @param clientId the per-connection client id that sent input.
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun noteInput(clientId: String): Pair<Int, Int>? {
        activity[clientId] = ++clock
        return recompute()
    }

    /**
     * Register or update [clientId]'s size vote.
     *
     * A [SizePriority.MOBILE] vote also counts as activity (the user just
     * opened/rotated the session on that device); other tiers only update
     * the stored vote — if the client is already the governor its new size
     * applies immediately, otherwise nothing changes until it becomes one.
     *
     * @param clientId the voting client.
     * @param vote the client's grid and tier (already clamped by the caller).
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun setSize(clientId: String, vote: SizeVote): Pair<Int, Int>? {
        votes[clientId] = vote
        if (vote.priority == SizePriority.MOBILE) activity[clientId] = ++clock
        return recompute()
    }

    /**
     * "Reformat": pin the PTY to [clientId]'s size, evicting every other
     * client's vote (they re-vote on their next automatic refit) and making
     * [clientId] the governor — an explicit user action always wins.
     *
     * @param clientId the forcing client.
     * @param vote the forced grid and tier (already floored by the caller).
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun forceSize(clientId: String, vote: SizeVote): Pair<Int, Int>? {
        votes.keys.retainAll(setOf(clientId))
        votes[clientId] = vote
        activity[clientId] = ++clock
        return recompute()
    }

    /**
     * Drop [clientId]'s vote and activity when its socket disconnects. If it
     * was the governor, the most recently active remaining client takes
     * over; with no votes left the current size is held.
     *
     * @param clientId the departing client.
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun remove(clientId: String): Pair<Int, Int>? {
        val hadVote = votes.remove(clientId) != null
        val hadActivity = activity.remove(clientId) != null
        if (!hadVote && !hadActivity) return null
        return recompute()
    }

    /**
     * Resolve the policy (see class kdoc) against the current votes and
     * activity, update [effective], and report the change.
     *
     * @return the new effective size, or null when it is unchanged.
     */
    private fun recompute(): Pair<Int, Int>? {
        // Allocation-free governor scan — this runs on every input frame.
        var governorId: String? = null
        var bestStamp = Long.MIN_VALUE
        for ((id, stamp) in activity) {
            if (stamp > bestStamp && id in votes) {
                bestStamp = stamp
                governorId = id
            }
        }
        val governorVote = governorId?.let { votes[it] }
        val threeD = votes.values.filter { it.priority == SizePriority.THREE_D }
        val next = when {
            governorVote?.priority == SizePriority.MOBILE ->
                Pair(governorVote.cols, governorVote.rows)
            threeD.isNotEmpty() -> pickEffectiveSize(threeD)!!
            governorVote != null -> Pair(governorVote.cols, governorVote.rows)
            else -> pickEffectiveSize(votes.values) ?: effective
        }
        if (next == effective) return null
        effective = next
        return next
    }
}
