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
 * history as a side effect of re-laying out the screen — which is what mangled restored
 * and mirrored scrollback across widths. With history separate, a resize touches only
 * the live screen; whatever the re-layout pushes off the top is committed here exactly
 * as ordinary scroll-off is, and is immutable thereafter.
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
 * Equality is by content and therefore width-invariant: a line assembled from several
 * soft-wrapped rows compares equal to the same line written in one go (see
 * [HistoryLog.appendRow]), so nothing downstream depends on the width the content
 * happened to be authored at.
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
 * The log records what actually scrolled off, verbatim. A program that answers a
 * resize by re-emitting its whole frame into the normal buffer therefore leaves the
 * same duplicate here that it leaves in any real terminal's scrollback — deliberately.
 * A reconciliation window that held resize scroll-off for a content-matched verdict
 * was built and removed: against real traces it either never fired (the re-emit
 * duplicates *history*, not the screen) or mis-fired, and any heuristic that can drop
 * lines can drop the user's genuine output. See `docs/server-side-screen.md`.
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
     * Runs of the logical line currently being assembled — the rows that have left the
     * screen so far under an unbroken soft wrap.
     */
    private val open = mutableListOf<StyledRun>()

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
     * @param wrapped true when the row soft-wraps into the next one, so the logical
     *   line continues rather than ending here.
     */
    fun appendRow(runs: List<StyledRun>, wrapped: Boolean) {
        open.addAll(runs)
        if (wrapped) return
        val line = LogicalLine(mergeAdjacent(open))
        open.clear()
        commit(line)
    }

    /**
     * Close the logical line currently being assembled, committing it if it holds anything.
     *
     * Called by [SessionGrid.resize] right after the emulator's re-layout, under the same
     * hold of the grid monitor. A re-layout is a boundary in a way ordinary scrolling is
     * not: the rows it pushes off the top are the tail of the OLD width's wrapping, and the
     * screen it leaves behind has been rewrapped at the new width. When the last row it
     * evicted was `wrapped == true`, the runs collected from it are stranded in [open] —
     * absent from [lines] and therefore from every paint — until some unrelated later
     * eviction happens to fuse onto them, at which point two unrelated fragments appear as
     * one line.
     *
     * The semantic this chooses: the archived head becomes its own logical line, i.e. a hard
     * break at the point the old width happened to wrap. That is the honest representation —
     * the continuation of that line was not archived, it was rewrapped and is still on the
     * live screen, so the archived part genuinely ends there. Fusing it to whatever scrolls
     * off next would invent a line the session never wrote.
     *
     * Idempotent: a no-op when nothing is half-assembled, so a resize that evicts nothing
     * (or ends on an unwrapped row) commits nothing.
     */
    fun closeOpenLine() {
        if (open.isEmpty()) return
        val line = LogicalLine(mergeAdjacent(open))
        open.clear()
        if (!line.isEmpty) commit(line)
    }

    /**
     * Committed history, oldest first.
     *
     * @return an immutable snapshot; the log itself is unaffected by what callers do
     *   with it.
     */
    fun lines(): List<LogicalLine> = committed.toList()

    /** Drop all history and any half-assembled line. */
    fun clear() {
        committed.clear()
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
