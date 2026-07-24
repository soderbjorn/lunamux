/**
 * The session's scrollback, as an append-only log of *logical lines* that is never
 * reflowed.
 *
 * This file contains [HistoryLog] and its value types [StyledRun] and [LogicalLine].
 * It is one half of the split the server-authoritative screen is built on: the live
 * screen is the canonical emulator (width-adaptive, re-laid out on every resize), and
 * history is this — committed once, stored as authored, immutable thereafter.
 *
 * Why the split exists. With one reflowable transcript holding both, a resize rewrites
 * history as a side effect of re-laying out the screen, and a program that repaints
 * itself on `SIGWINCH` then stacks a second copy of its frame on top of the rows the
 * reflow had just archived. There is no seam to reconcile on, because the archival and
 * the repaint touch the same structure. With history separate, a resize touches only
 * the live screen, and what the resize *would* have archived becomes an explicit,
 * reviewable decision — see the window API below.
 *
 * Wrapping is not stored. A logical line is a sequence of styled cells with no width;
 * rendering it at a client's width is just emitting its characters and letting the
 * receiving terminal wrap them. That is why history can be served correctly to a phone
 * and a laptop at once without either reinterpreting the other's bytes.
 *
 * @see SessionGrid the owner, which feeds rows as they leave the live screen
 * @see GridSerializer which renders committed lines ahead of the live screen
 * @see docs/server-side-screen.md the design record
 */
package se.soderbjorn.lunamux.pty

/**
 * A run of characters sharing one style — the unit both the emulator's rows and the
 * serializer's SGR output are naturally expressed in.
 *
 * Wide glyphs and combining marks live inside [text] rather than being modelled as
 * cells, so a run round-trips through a terminal unchanged without the log needing its
 * own notion of display width.
 *
 * @property text the characters, in emission order.
 * @property style the packed [com.termux.terminal.TextStyle] value they carry.
 */
data class StyledRun(val text: String, val style: Long)

/**
 * One complete logical line of history: everything the program wrote between two line
 * breaks, however many screen rows it happened to occupy when it was written.
 *
 * Equality is by content, which is what makes the window reconciliation in
 * [HistoryLog] possible: a repainting program re-emits the same logical lines, and
 * matching them by value is width-invariant in a way that any count-based measure is
 * not.
 *
 * @property runs the styled runs, in order.
 */
data class LogicalLine(val runs: List<StyledRun>) {
    /** The line's characters with styles dropped — for matching, logging and tests. */
    val text: String by lazy(LazyThreadSafetyMode.NONE) { runs.joinToString("") { it.text } }

    /** True when the line holds no characters at all. */
    val isEmpty: Boolean get() = runs.all { it.text.isEmpty() }
}

/**
 * Append-only scrollback for one session.
 *
 * Rows are fed in as they leave the live screen ([appendRow]); rows joined by a soft
 * wrap accumulate into one [LogicalLine], which is committed when a row arrives that
 * does not wrap. Committed lines are never rewritten, reflowed or truncated by any
 * resize.
 *
 * ## The window
 *
 * A resize re-lays out the live screen, and content that no longer fits leaves it —
 * arriving here exactly as ordinary scroll-off does. For most programs that content is
 * real history. For a program that owns the whole screen and repaints on `SIGWINCH` it
 * is not: the program is about to redraw those very lines, so committing them mints the
 * duplicate this whole design exists to remove.
 *
 * Nothing at the moment of the resize distinguishes the two cases, so this class does
 * not try. [beginWindow] diverts subsequent commits into a pending list; the caller
 * inspects [pendingLines] against evidence only it has — what the program actually put
 * on screen afterwards — and then calls [commitWindow] or [discardWindow].
 *
 * The safety property that makes this different from the truncation approach it
 * replaces: a window decision can only affect rows that left the screen *inside* that
 * window. Already-committed history is unreachable from here, so the worst a wrong
 * verdict can cost is one resize's worth of scroll-off — never the user's established
 * scrollback.
 *
 * Not thread-safe: callers hold the grid monitor (see [SessionGrid]).
 *
 * @param maxLines how many committed logical lines to retain; the oldest are dropped
 *   past this. Counted in logical lines rather than rows because rows are a function of
 *   a width this log deliberately does not have.
 */
class HistoryLog(private val maxLines: Int = DEFAULT_MAX_LINES) {

    /** Committed history, oldest first. Never mutated except by FIFO eviction. */
    private val committed = ArrayDeque<LogicalLine>()

    /**
     * Lines committed since [beginWindow], awaiting the caller's verdict. Null when no
     * window is open, which is also what makes [beginWindow] idempotent across a burst
     * of resizes: a take-over commonly fires a cols change and then a rows-only adjust,
     * and only the first opens the window that spans the program's response.
     */
    private var pending: MutableList<LogicalLine>? = null

    /**
     * Runs of the logical line currently being assembled — the rows that have left the
     * screen so far under an unbroken soft wrap. Held across a window boundary on
     * purpose: a line half-committed when the resize hits is still one line.
     */
    private val open = mutableListOf<StyledRun>()

    /** True while a window is open and commits are being diverted. */
    val windowOpen: Boolean get() = pending != null

    /** How many logical lines are committed. */
    val size: Int get() = committed.size

    /**
     * Feed one row that has just left the live screen.
     *
     * Called by [SessionGrid] from the emulator's scroll-off hook, for both ordinary
     * scrolling and the re-layout a resize performs.
     *
     * @param runs the row's styled content, trailing default-styled blanks already
     *   trimmed by the caller (a blank row is an empty list, which is a real empty
     *   history line, not a no-op).
     * @param wrapped true when the row soft-wraps into the next one, so the logical line
     *   continues rather than ending here.
     */
    fun appendRow(runs: List<StyledRun>, wrapped: Boolean) {
        open.addAll(runs)
        if (wrapped) return
        val line = LogicalLine(mergeAdjacent(open))
        open.clear()
        val target = pending
        if (target != null) target.add(line) else commit(line)
    }

    /**
     * Open a reconciliation window: from here until [commitWindow] or [discardWindow],
     * completed lines are held in [pendingLines] instead of being committed.
     *
     * Idempotent — a second call while a window is open does nothing, so a burst of
     * resizes produces one window spanning the program's whole response rather than a
     * fresh one that would only capture the tail of it.
     */
    fun beginWindow() {
        if (pending == null) pending = mutableListOf()
    }

    /**
     * The lines that have left the screen since [beginWindow], oldest first.
     *
     * The caller matches these against what the program subsequently drew to decide
     * whether they are genuine history or a frame the program is re-rendering.
     *
     * @return the pending lines, or an empty list when no window is open.
     */
    fun pendingLines(): List<LogicalLine> = pending?.toList() ?: emptyList()

    /**
     * Close the window, keeping [pendingLines] as history. The verdict for ordinary
     * output: the program did not reclaim those lines, so they scrolled off for real.
     */
    fun commitWindow() {
        val target = pending ?: return
        pending = null
        target.forEach { commit(it) }
    }

    /**
     * Close the window, dropping [pendingLines]. The verdict for a full-screen program
     * answering a resize: it re-rendered that content, so committing it would leave the
     * frame in scrollback twice.
     *
     * Reaches only lines from inside this window; established history is untouched.
     */
    fun discardWindow() {
        pending = null
    }

    /**
     * Close the window, keeping only the lines past [keepFrom].
     *
     * The middle verdict, for a repaint that reclaimed some of what scrolled off but not
     * all of it: everything before [keepFrom] was re-rendered by the program, everything
     * from it onward is genuine.
     *
     * @param keepFrom index into [pendingLines] of the first line to keep; values ≤ 0
     *   keep everything and values past the end keep nothing.
     */
    fun closeWindow(keepFrom: Int) {
        val target = pending ?: return
        pending = null
        for (i in keepFrom.coerceAtLeast(0) until target.size) commit(target[i])
    }

    /**
     * Committed history, oldest first.
     *
     * @return an immutable snapshot; the log itself is unaffected by what callers do
     *   with it.
     */
    fun lines(): List<LogicalLine> = committed.toList()

    /**
     * The last [n] committed lines, oldest first — the tail a caller matches a repaint
     * against without copying the whole log.
     *
     * @param n how many lines to take; more than are held returns all of them.
     * @return the tail, oldest first.
     */
    fun tail(n: Int): List<LogicalLine> {
        if (n <= 0) return emptyList()
        val from = (committed.size - n).coerceAtLeast(0)
        return committed.toList().subList(from, committed.size)
    }

    /** Drop all history, committed and pending, and any half-assembled line. */
    fun clear() {
        committed.clear()
        pending = null
        open.clear()
    }

    private fun commit(line: LogicalLine) {
        committed.addLast(line)
        while (committed.size > maxLines) committed.removeFirst()
    }

    /**
     * Fuse neighbouring runs that share a style, so a line assembled from several screen
     * rows compares equal to the same line assembled in one go. Without this, whether two
     * identical lines match would depend on where they happened to wrap — precisely the
     * width dependence this log exists to eliminate.
     */
    private fun mergeAdjacent(runs: List<StyledRun>): List<StyledRun> {
        if (runs.size < 2) return runs.filter { it.text.isNotEmpty() }
        val out = mutableListOf<StyledRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.style == run.style) {
                out[out.size - 1] = StyledRun(last.text + run.text, last.style)
            } else {
                out.add(run)
            }
        }
        return out
    }

    companion object {
        /**
         * Default retention. Logical lines, not rows: at typical widths this is a deeper
         * scrollback than the 3000-row transcript it replaces, because one logical line
         * may have occupied several rows.
         */
        const val DEFAULT_MAX_LINES = 5000
    }
}
