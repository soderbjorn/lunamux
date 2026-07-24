/**
 * Latest-active-client PTY size arbitration with viewer/driver posture.
 *
 * [ClientSizeArbiter] decides the effective grid of a shared PTY from the
 * per-client size votes plus a record of which client the user actually
 * used last — the analogue of tmux's `window-size latest`. It replaces the
 * bare tiered-min() aggregation as [se.soderbjorn.lunamux.TerminalSession]'s
 * size policy while keeping that aggregation as the no-signal fallback, so
 * sessions where no activity has been observed behave (for drivers) exactly
 * as before.
 *
 * Each attached client has a [ClientPosture]. A **driver** (desktop/web by
 * default) may seize governance by typing or forcing; a **viewer** (a phone
 * mirroring the desktop by default) never governs merely by attaching or
 * scrolling — it takes over only by an explicit force gesture, which promotes
 * it to a driver. This is what keeps a phone opening a session from hijacking
 * the grid the laptop owns (symptom 1 in the sizing plan): the phone mirrors
 * until the user deliberately takes over.
 *
 * @see se.soderbjorn.lunamux.pickEffectiveSize
 * @see se.soderbjorn.lunamux.TermSession.noteClientInput
 * @see se.soderbjorn.lunamux.TermSession.setClientPosture
 */
package se.soderbjorn.lunamux.pty

import se.soderbjorn.lunamux.SizePriority
import se.soderbjorn.lunamux.SizeVote
import se.soderbjorn.lunamux.pickEffectiveSize

/**
 * How a client participates in size governance.
 *
 * Declared by the client on connect (a `posture=` query param on `/pty`) and
 * sniffed from the client type as a fallback (see
 * [se.soderbjorn.lunamux.isMobileClientType]). A client is treated as a
 * [DRIVER] until told otherwise, which preserves the pre-posture behaviour for
 * every desktop/web client and for callers (tests) that never declare one.
 *
 * @see ClientSizeArbiter.setPosture
 */
enum class ClientPosture {
    /** May seize governance by typing ([ClientSizeArbiter.noteInput]) or forcing. */
    DRIVER,

    /** Mirrors the driver; governs only after an explicit force promotes it. */
    VIEWER,
}

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
 * The **governor** is the *driver* with the most recent *activity*. Activity
 * is deliberately narrower than "sent any frame":
 *
 *  - input bytes ([noteInput]) — the user is typing on that client — but only
 *    when the client is a [ClientPosture.DRIVER]; a viewer's keystrokes never
 *    seize the grid (in practice a viewer force-promotes itself first, see
 *    [forceSize]);
 *  - a forced resize ([forceSize]) — the user pressed Reformat / took over on
 *    that client; this also promotes a viewer to a driver.
 *
 * Plain [setSize] votes are *ambient* (ResizeObserver refits, window resizes,
 * font loads, world transitions) and update the client's vote without stealing
 * governance — so a second desktop window, a reconnect storm, or a phone
 * peeking can no longer shrink a session the user is actively typing in, and
 * one keystroke on the desktop reclaims the grid from a phone.
 *
 * The effective size is then resolved as:
 *
 *  1. the governor's vote when the governor's latest activity was a **force**
 *     — an explicit reformat / take-over wins even over a [SizePriority.THREE_D]
 *     override, and stays winning until another client takes over (resolves the
 *     "explicit force wins over the 3D tier" open decision);
 *  2. otherwise the tiered min over [SizePriority.THREE_D] votes when any
 *     exist — the 3D world's enlarged grid is a mode assertion that a 2D
 *     viewer's typing must not clobber;
 *  3. otherwise the governor's vote;
 *  4. otherwise (no activity recorded) the classic tiered aggregation
 *     [pickEffectiveSize] over the **drivers'** votes — a viewer attaching
 *     never shrinks the grid below what the drivers want; only a viewer-only
 *     session (no driver votes at all) falls back to the viewer votes so a
 *     phone-only session still sizes to the phone;
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

    /**
     * Clients declared [ClientPosture.VIEWER]. A client is a driver iff it is
     * *absent* here, so the default (and the behaviour for callers that never
     * declare a posture) is driver — preserving the pre-posture semantics.
     */
    private val viewers = HashSet<String>()

    /**
     * Clients whose most recent governing action was a [forceSize] (and which
     * have not since been superseded by another client's take-over). The
     * governor being in this set is what lets an explicit force outrank a
     * standing [SizePriority.THREE_D] override (policy step 1).
     */
    private val forced = HashSet<String>()
    private var clock = 0L

    /** The currently arbitrated `(cols, rows)`. */
    var effective: Pair<Int, Int> = Pair(initialCols, initialRows)
        private set

    /**
     * The client currently governing the grid, or null when none does — no
     * client has acted yet, or the governor disconnected and only ambient votes
     * remain (policy step 4, the tiered fallback).
     *
     * Recomputed by [recompute] on every mutation, including the ones that leave
     * the effective size unchanged. That case is the whole point: when two
     * clients happen to render at the same width, a take-over moves governance
     * without moving a single column, and nothing in the size stream would show
     * it. `TermSession` broadcasts changes to this so clients are *told* who
     * drives instead of inferring it from a width coincidence.
     *
     * @see governor
     */
    private var governorId: String? = null

    /**
     * The governing client's id, read under the arbiter's monitor.
     *
     * Called by `TermSession` after every mutation to decide whether to
     * broadcast a governance change.
     *
     * @return the governor's client id, or null when no client governs.
     */
    @Synchronized
    fun governor(): String? = governorId

    /**
     * Declare [clientId]'s governance [posture]. Called once when a client
     * attaches (from the `/pty` route). Idempotent and side-effect free on the
     * effective size — posture only changes *who can* govern, not the grid, so
     * this never returns a resize.
     *
     * @param clientId the attaching client.
     * @param posture driver (may govern) or viewer (mirrors until it forces).
     */
    @Synchronized
    fun setPosture(clientId: String, posture: ClientPosture) {
        if (posture == ClientPosture.VIEWER) viewers.add(clientId) else viewers.remove(clientId)
    }

    /**
     * Record that [clientId] delivered user input (keystrokes). For a driver
     * this makes it the governor; a viewer's input is ignored (it must force to
     * take over first — the Android client sends that force before the first
     * keystroke, so this only guards a stray byte).
     *
     * Called by the `/pty` route on every inbound binary frame — hot path,
     * so the common case (viewer, or already-governing driver with unchanged
     * size) returns null after one or two map reads.
     *
     * @param clientId the per-connection client id that sent input.
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun noteInput(clientId: String): Pair<Int, Int>? {
        if (clientId in viewers) return null
        // A plain keystroke by a client that did not itself force means the
        // previous forcer has been superseded — drop its sticky force authority
        // so a stale force can never resurrect over a 3D override. Typing by the
        // forcer itself keeps it (a phone takes over, then types on).
        if (clientId !in forced) forced.clear()
        activity[clientId] = ++clock
        return recompute()
    }

    /**
     * Register or update [clientId]'s size vote. A plain vote is *ambient*: it
     * never counts as activity, so it applies immediately only if [clientId] is
     * already the governor, and otherwise is stored and takes effect if the
     * client later becomes one (or feeds the no-activity fallback).
     *
     * @param clientId the voting client.
     * @param vote the client's grid and tier (already clamped by the caller).
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun setSize(clientId: String, vote: SizeVote): Pair<Int, Int>? {
        votes[clientId] = vote
        return recompute()
    }

    /**
     * "Reformat" / take-over: pin the PTY to [clientId]'s size and make it the
     * governor — an explicit user action always wins. This **promotes a viewer
     * to a driver** (a phone taking over the session) and marks the client
     * [forced] so it outranks even a standing [SizePriority.THREE_D] override.
     *
     * Unlike the previous implementation it does **not** evict other clients'
     * votes: governance is decided by recency of activity, not by which votes
     * survive, so those facts are kept and a laptop can reclaim the grid by
     * simply typing (its vote was never thrown away).
     *
     * @param clientId the forcing client.
     * @param vote the forced grid and tier (already floored by the caller).
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun forceSize(clientId: String, vote: SizeVote): Pair<Int, Int>? {
        viewers.remove(clientId)
        votes[clientId] = vote
        activity[clientId] = ++clock
        // Only the newest forcer holds force authority (a fresh take-over
        // supersedes any earlier one), so the set carries at most one client.
        forced.clear()
        forced.add(clientId)
        return recompute()
    }

    /**
     * Drop [clientId]'s vote, activity and posture when its socket disconnects.
     * If it was the governor, the most recently active remaining driver takes
     * over; with no votes left the current size is held.
     *
     * @param clientId the departing client.
     * @return the new effective size to apply, or null if unchanged.
     */
    @Synchronized
    fun remove(clientId: String): Pair<Int, Int>? {
        val hadVote = votes.remove(clientId) != null
        val hadActivity = activity.remove(clientId) != null
        viewers.remove(clientId)
        forced.remove(clientId)
        if (!hadVote && !hadActivity) return null
        return recompute()
    }

    /**
     * Resolve the policy (see class kdoc) against the current votes, activity
     * and posture, update [effective], and report the change.
     *
     * @return the new effective size, or null when it is unchanged.
     */
    private fun recompute(): Pair<Int, Int>? {
        // Allocation-free governor scan — this runs on every input frame. Only
        // drivers accumulate activity (noteInput gates on posture, forceSize
        // promotes), so this naturally picks the most-recently-active driver.
        var governor: String? = null
        var bestStamp = Long.MIN_VALUE
        for ((id, stamp) in activity) {
            if (stamp > bestStamp && id in votes) {
                bestStamp = stamp
                governor = id
            }
        }
        governorId = governor
        val governorVote = governor?.let { votes[it] }
        val threeD = votes.values.filter { it.priority == SizePriority.THREE_D }
        val next = when {
            governorVote != null && governor in forced ->
                Pair(governorVote.cols, governorVote.rows)
            threeD.isNotEmpty() -> pickEffectiveSize(threeD)!!
            governorVote != null -> Pair(governorVote.cols, governorVote.rows)
            else -> driverFallback() ?: effective
        }
        if (next == effective) return null
        effective = next
        return next
    }

    /**
     * The no-activity fallback size (policy step 4): the classic tiered
     * aggregation over the **drivers'** votes, so a viewer merely attaching
     * cannot shrink the grid the drivers want. Only when no driver has voted
     * (a viewer-only session, e.g. a phone opened on its own) does it fall back
     * to all votes, so such a session still sizes to the phone.
     *
     * @return the aggregated size, or null when there are no votes at all.
     */
    private fun driverFallback(): Pair<Int, Int>? {
        val driverVotes = votes.entries.filter { it.key !in viewers }.map { it.value }
        return pickEffectiveSize(driverVotes) ?: pickEffectiveSize(votes.values)
    }
}
