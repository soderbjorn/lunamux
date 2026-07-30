/**
 * The phone's ack-clocked PTY size-vote pipeline.
 *
 * This file contains [SizeVoteClock], which paces this client's size requests against the
 * server's answers instead of against a timer.
 *
 * What it replaces. The phone used to vote straight from the view's grid-size listener
 * behind a 200 ms trailing debounce. The listener fires on every layout pass, and a
 * take-over walked the font from the shrunken mirror size back to the driving size one
 * pixel at a time — measured on device as 19 row-only votes inside 250 ms, each one a
 * SIGWINCH, each SIGWINCH answered by a live TUI repainting another copy of its output into
 * the scrollback. The debounce was a guess at how long a settling layout takes: too short
 * and the storm gets through, too long and a finished rotation feels laggy.
 *
 * The storm's cause is gone structurally (font changes no longer touch the emulator or the
 * natural grid, so they generate no votes at all), and what remains is paced by the
 * protocol: **at most one vote in flight**, and while one is in flight only the latest
 * desired grid is remembered. A fast server means fast votes; a slow one means fewer. No
 * number to tune.
 *
 * @see TerminalScreen the owner, which clocks it from the `Size` frames it collects
 * @see se.soderbjorn.lunamux.client.PtySocket the transport the callbacks write to
 */
package se.soderbjorn.lunamux.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serialises one session's size requests against the server's `Size` answers.
 *
 * Thread confinement: every method is `synchronized`, and the sends are dispatched onto
 * [scope] rather than performed inline, so a caller may drive this from the UI thread (the
 * view's layout listener) and from the event collector without ordering hazards.
 *
 * @param scope the coroutine scope the sends are launched on; cancelled with the screen.
 * @param sendVote sends a soft size vote — the arbiter decides, nobody is evicted.
 * @param sendForce sends a take-over: seizes governance at the given grid.
 * @param timeoutMs safety valve. A vote that *loses* — to a mobile floor, or to a governing
 *   client — produces no `Size` broadcast at all, so without an upper bound the one-in-flight
 *   latch would stay shut forever. It is not a pacing mechanism: the ordinary clock is
 *   [onServerGrid].
 */
internal class SizeVoteClock(
    private val scope: CoroutineScope,
    private val sendVote: suspend (Int, Int) -> Unit,
    private val sendForce: suspend (Int, Int) -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /** True between sending a request and resolving it. The one-in-flight latch. */
    private var inFlight = false

    /** The latest desired grid while [inFlight], or null when nothing is outstanding. */
    private var desired: Pair<Int, Int>? = null

    /** The safety-valve timer for the in-flight request. */
    private var timeoutJob: Job? = null

    /** The server's last known grid — one of the two things that resolves a request. */
    private var serverGrid: Pair<Int, Int>? = null

    /**
     * Ask for [cols]×[rows].
     *
     * Sent immediately when nothing is outstanding. While a *vote* is in flight this only
     * remembers the desire, replacing any earlier one: intermediate sizes from a settling
     * layout are of no interest once superseded. A take-over is not deferred at all.
     *
     * A request for the grid the server already has is not sent — there would be nothing to
     * wait for — and resolves the pipeline instead.
     *
     * @param cols desired columns. @param rows desired rows.
     * @param force true for a take-over (seizes governance), false for a soft vote. Called
     *   with true by real input, tap-to-focus, the take-over badge and Reformat. A force
     *   preempts the one-in-flight rule rather than queueing behind an ambient vote.
     */
    @Synchronized
    fun request(cols: Int, rows: Int, force: Boolean) {
        if (cols < 2 || rows < 2) return
        if (!force && cols to rows == serverGrid) {
            desired = null
            unlatch()
            return
        }
        // A force PREEMPTS the one-in-flight rule and goes out at once. That rule exists to
        // keep ambient measurement from becoming a vote storm; making a take-over wait up to
        // the safety-valve timeout behind an ambient vote it is about to overrule would be a
        // second of dead UI for no benefit.
        if (inFlight && !force) {
            desired = cols to rows
            return
        }
        desired = null
        inFlight = true
        scope.launch { runCatching { if (force) sendForce(cols, rows) else sendVote(cols, rows) } }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            resolve()
        }
    }

    /**
     * Record the server's grid and resolve the in-flight request — the ordinary clock tick.
     *
     * Called for every `Size` frame the screen collects, including ones this client did not
     * ask for: any answer from the server is proof the previous request has been dealt with,
     * one way or another.
     *
     * @param cols the server's column count. @param rows the server's row count.
     */
    @Synchronized
    fun onServerGrid(cols: Int, rows: Int) {
        serverGrid = cols to rows
        resolve()
    }

    /** Drop any outstanding request without sending it — used when the screen goes away. */
    @Synchronized
    fun cancel() {
        desired = null
        unlatch()
    }

    /**
     * Unlatch and send the remembered desire if it still differs from the server's grid.
     *
     * Not `synchronized` itself: only ever called from a `synchronized` method, and it
     * re-enters [request], which is `synchronized` on the same (reentrant) monitor.
     */
    private fun resolve() {
        unlatch()
        val next = desired ?: return
        desired = null
        // Always a vote: a take-over never queues here (see [request]). request() itself drops
        // it when it turns out to match the grid the server now has.
        request(next.first, next.second, force = false)
    }

    private fun unlatch() {
        inFlight = false
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private companion object {
        /**
         * Safety-valve default (ms). Long enough that a healthy round trip always answers
         * first, short enough that a lost vote does not wedge the pipeline noticeably.
         */
        const val DEFAULT_TIMEOUT_MS = 1_000L
    }
}
