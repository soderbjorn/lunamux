/**
 * The server's canonical, server-authoritative terminal screen for a single PTY
 * session.
 *
 * This file contains [SessionGrid], a thin thread-safe wrapper around the exact
 * vendored Termux [TerminalEmulator] the Android client renders with (extracted
 * to the pure-JVM `:terminal-core` module). It is fed the raw PTY byte stream
 * alongside the JediTerm-based [se.soderbjorn.lunamux.ScreenEmulator], so the
 * server always holds a fully-interpreted grid — cells, styles, cursor, modes,
 * scrollback — rather than only a byte ring.
 *
 * The grid is the authority: [GridSerializer] reads it back to synthesize an
 * attach/resync redraw at each client's width (the tmux/mosh model), which
 * dissolves the width-bound byte-replay bug class instead of managing it.
 *
 * The emulator holds only the live screen; scrollback is [HistoryLog], outside it,
 * as logical lines no reflow can reach. That split is what gives a resize a seam to
 * reconcile on: it re-lays out the screen and nothing else, and whatever it pushes
 * off the top is held until the program's response shows whether the program meant
 * to redraw it. See `docs/server-side-screen.md` for the measured behaviour behind
 * this and for the approach it replaced.
 *
 * @see se.soderbjorn.lunamux.TerminalSession the PTY session that owns one grid
 * @see se.soderbjorn.lunamux.ScreenEmulator the JediTerm screen kept for AI state detection
 */
package se.soderbjorn.lunamux.pty

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput

/**
 * A headless [TerminalEmulator] fed the raw PTY output stream, maintaining the
 * canonical interpreted screen for one session.
 *
 * Thread safety: every operation synchronizes on the wrapped emulator (the same
 * monitor the read path and any future serializer take), so feeds, resizes and
 * reads never observe a torn grid. [feed] and [resize] also swallow any
 * exception a malformed control sequence might raise — the PTY read loop must
 * never die because of grid interpretation.
 *
 * @param cols initial grid columns.
 * @param rows initial grid rows.
 */
class SessionGrid(
    cols: Int,
    rows: Int,
    private val onHistoryRevised: () -> Unit = {},
) {

    /**
     * Where the emulator writes its device-query replies (DSR, DA, OSC color
     * reads, …). On the server that reply has nowhere legitimate to go: the
     * running program's queries are already answered by every attached client's
     * real terminal, so a server answer would be a duplicate, and a *replayed*
     * query would be answered again as phantom shell input. Sinking them here is
     * why the grid can ingest arbitrary (even legacy, query-laden) bytes safely,
     * and why a synthesized redraw — which never contains queries — has no
     * re-answer hazard at all.
     *
     * [discardedOutputBytes] counts what was sunk, purely so tests can assert the
     * emulator really did answer-and-drop rather than leak to a PTY (it never has
     * a PTY handle to leak to).
     */
    private val discardOutput = object : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) {
            if (count > 0) discardedOutputBytes += count.toLong()
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    /**
     * Bytes the emulator wrote back to [discardOutput] (query replies, mouse
     * reports, …) and this grid discarded. Observability-only; see the field doc
     * on [discardOutput]. `@Volatile` because feeds may arrive from the PTY read
     * coroutine while a test thread reads the counter.
     */
    @Volatile
    var discardedOutputBytes: Long = 0L
        private set

    /**
     * The canonical emulator — the *live screen* only. `null` client → the cursor
     * style defaults and Logger falls back to stderr (this grid is never bound to a
     * Session client). Cell pixel sizes are nominal (headless).
     */
    private val emulator = TerminalEmulator(
        discardOutput,
        cols.coerceAtLeast(MIN_DIM),
        rows.coerceAtLeast(MIN_DIM),
        NOMINAL_CELL_WIDTH_PX,
        NOMINAL_CELL_HEIGHT_PX,
        // Screen-only: this emulator models the live screen and nothing else. History is
        // [history], outside the emulator, where no reflow can reach it.
        NO_EMULATOR_TRANSCRIPT,
        null,
    )

    /**
     * The session's scrollback: logical lines, committed as they leave the screen, never
     * reflowed. The other half of the split described in the file header.
     */
    private val history = HistoryLog()

    /**
     * Chunks fed since the current reconciliation window opened, or -1 when none is open.
     * Bounds how long a verdict may be deferred; it does not decide the verdict, which is
     * always taken by comparing the pending lines against what the program actually drew.
     */
    private var chunksSinceWindow: Int = -1

    /**
     * How much of the open window's pending content the program had visibly reclaimed as of
     * the last chunk. Only used to notice the moment a repaint lands, so clients can be
     * resynced then rather than at a fixed delay after the resize.
     */
    private var reclaimedAtLastFeed: Int = 0

    init {
        // Rows leaving the screen become history. Converted here, synchronously: the
        // emulator recycles the row the moment this returns.
        emulator.mainBuffer.setRowEvictionListener { row, wrapped ->
            history.appendRow(GridSerializer.rowRuns(row, emulator.mColumns, wrapped), wrapped)
        }
    }

    /**
     * Feed [len] bytes of raw PTY output into the canonical grid.
     *
     * Called from the PTY read loop for every chunk the session emits (alongside
     * the ring append and the JediTerm feed), and on the restore path for the
     * persisted scrollback blob plus the mode-reset epilogue.
     *
     * @param buf the byte buffer; only indices `[0, len)` are read.
     * @param len number of valid bytes in [buf]; ≤ 0 is a no-op.
     */
    fun feed(buf: ByteArray, len: Int) {
        if (len <= 0) return
        var revise = false
        synchronized(emulator) {
            try {
                emulator.append(buf, len)
            } catch (t: Throwable) {
                // A malformed control sequence must never take the PTY read loop
                // down. The grid may be left mid-sequence; the next feed recovers.
                Swallowed.note("feed", t)
            }
            if (chunksSinceWindow < 0) return
            if (++chunksSinceWindow >= WINDOW_MAX_CHUNKS) {
                resolveWindow()
                return
            }
            // The repaint may have just landed. If more of what is pending is now visible
            // on screen than was a chunk ago, the view a client should see has changed, so
            // ask for a fresh resync — otherwise a client keeps the pre-repaint copy until
            // something else happens to trigger one. Cheap: only while a window is open.
            val reclaimed = reclaimedPrefixLength(history.pendingLines(), screenLines())
            if (reclaimed > reclaimedAtLastFeed) {
                reclaimedAtLastFeed = reclaimed
                revise = true
            }
        }
        // Outside the monitor: the callback re-enters the session, which takes its own
        // outbound lock, and nothing may hold this grid's monitor while that happens.
        if (revise) onHistoryRevised()
    }

    /**
     * Decide what to do with the lines that left the screen since the last resize.
     *
     * A narrowing re-lays out the live screen and pushes what no longer fits off the top.
     * For a shell that is real history. For a program that owns the screen and repaints on
     * `SIGWINCH` it is a frame the program is in the middle of redrawing, and committing it
     * is what put a second copy in scrollback.
     *
     * The verdict is taken from content, not from the bytes that arrived: whatever the
     * program has now drawn is on the screen, so a pending line that reappears there was
     * reclaimed by the repaint and is redundant. Matched as a contiguous run so an
     * incidental single-line coincidence in a shell's output cannot trigger it, and only
     * as a *prefix* of the pending lines — the reflow pushes the frame's top off first, so
     * that is where a repaint's overlap must begin.
     *
     * Everything past the overlap is committed. This can only ever reach lines from inside
     * the window; established history is not addressable from here.
     */
    private fun resolveWindow() {
        chunksSinceWindow = -1
        reclaimedAtLastFeed = 0
        if (!history.windowOpen) return
        val pending = history.pendingLines()
        if (pending.isEmpty()) {
            history.commitWindow()
            return
        }
        history.closeWindow(keepFrom = reclaimedPrefixLength(pending, screenLines()))
    }

    /**
     * History as it should be served *right now*: committed lines, plus whatever is pending
     * minus the part the program has visibly reclaimed.
     *
     * Reads deliberately do not resolve the window. Forcing a verdict on read means the
     * verdict is taken whenever a client happens to attach or the debounced resync fires —
     * 100 ms after a resize — which is a race against the program's repaint, and losing it
     * commits a duplicate permanently. Answering from the current screen instead is correct
     * at every instant: before the repaint arrives those rows genuinely are scrolled-off
     * content, and after it arrives they are visibly redundant and drop out of the answer.
     * The commit is left to a real close (the next resize, or the chunk backstop).
     *
     * @return the logical lines a client should be shown, oldest first.
     */
    private fun servedHistory(): List<LogicalLine> {
        val pending = history.pendingLines()
        if (pending.isEmpty()) return history.lines()
        val keepFrom = reclaimedPrefixLength(pending, screenLines())
        return history.lines() + pending.subList(keepFrom.coerceIn(0, pending.size), pending.size)
    }

    /**
     * How many leading [pending] lines the program has re-drawn on screen.
     *
     * @param pending the lines that left the screen inside the window, oldest first.
     * @param screen the live screen's lines, top-down.
     * @return the length of the longest prefix of [pending] appearing as a contiguous run
     *   in [screen]; 0 when there is no such run, which keeps every pending line.
     */
    private fun reclaimedPrefixLength(pending: List<LogicalLine>, screen: List<LogicalLine>): Int {
        // A blank line matches anywhere and would let a run start on nothing, so an
        // overlap has to begin on real content.
        val first = pending.firstOrNull() ?: return 0
        if (first.isEmpty) return 0
        var best = 0
        for (start in screen.indices) {
            if (screen[start] != first) continue
            var n = 0
            while (n < pending.size && start + n < screen.size && pending[n] == screen[start + n]) n++
            if (n > best) best = n
        }
        return best
    }

    /**
     * The live screen as logical lines, top-down, with soft-wrapped rows fused — the same
     * shape [HistoryLog] stores, so the two can be compared directly.
     *
     * @return the screen's logical lines.
     */
    private fun screenLines(): List<LogicalLine> {
        val out = mutableListOf<LogicalLine>()
        val open = mutableListOf<StyledRun>()
        val buffer = emulator.mainBuffer
        for (y in 0 until emulator.mRows) {
            val row = buffer.getLineOrNull(y) ?: continue
            val wrapped = buffer.getLineWrap(y)
            open.addAll(GridSerializer.rowRuns(row, emulator.mColumns, wrapped))
            if (!wrapped) {
                out.add(LogicalLine(open.toList()))
                open.clear()
            }
        }
        if (open.isNotEmpty()) out.add(LogicalLine(open.toList()))
        return out
    }

    /**
     * Resize the canonical grid, running the emulator's own (wrap-flag-faithful)
     * reflow. Called from the session's size-apply path whenever the arbitrated
     * PTY grid changes.
     *
     * @param cols new column count; values below the emulator's 2-column floor are ignored.
     * @param rows new row count; values below the 2-row floor are ignored.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols < MIN_DIM || rows < MIN_DIM) return
        synchronized(emulator) {
            // Resolve the previous window first, but only once the program has actually had
            // a chance to answer it: a take-over's burst (a cols change, then a rows-only
            // keyboard adjust) arrives with no output in between and must stay ONE window,
            // while a genuinely new resize after the program has spoken must not pour its
            // overflow into the previous verdict.
            if (chunksSinceWindow > 0) resolveWindow()
            // Divert what the re-layout pushes off the screen until we can see whether the
            // program reclaims it.
            history.beginWindow()
            if (chunksSinceWindow < 0) chunksSinceWindow = 0
            try {
                emulator.resize(cols, rows, NOMINAL_CELL_WIDTH_PX, NOMINAL_CELL_HEIGHT_PX)
            } catch (t: Throwable) {
                // Resize races are benign; the next feed settles the layout.
                Swallowed.note("resize", t)
            }
        }
    }

    /**
     * Run [block] against the live emulator while holding the grid monitor, and
     * return its result. The lock is held for the duration of [block], so callers
     * must not escape a reference to the emulator or block for long.
     *
     * @param block reader given exclusive, consistent access to the emulator.
     * @return whatever [block] returns.
     */
    fun <T> read(block: (TerminalEmulator) -> T): T = synchronized(emulator) { block(emulator) }

    /**
     * History as a client would be served it — the half of the session that lives outside
     * the emulator. Matches exactly what [synthesizeRedraw] emits, including how an open
     * reconciliation window is currently answered.
     *
     * @return the logical lines, oldest first.
     */
    fun historyLines(): List<LogicalLine> = synchronized(emulator) { servedHistory() }

    /**
     * The full normal-buffer transcript (scrollback + visible screen) as plain
     * text, one logical line per row. Backed by the canonical grid so it stays
     * correct across resizes; used by the MCP `read_scrollback` tool.
     *
     * @return the main buffer's transcript text.
     */
    fun transcriptText(): String = synchronized(emulator) {
        val sb = StringBuilder()
        for (line in servedHistory()) sb.append(line.text).append('\n')
        sb.append(emulator.mainBuffer.transcriptText)
        sb.toString()
    }

    /**
     * Synthesize an attach/resync redraw of the current grid at its current width:
     * a self-contained byte stream (RIS + styled row flow + state epilogue) that
     * reconstructs this exact screen — cells, cursor, modes, palette, title — when
     * fed to a fresh emulator or a client terminal. The width-correct replacement for
     * raw byte-ring replay.
     *
     * @return UTF-8 redraw bytes for the current grid.
     * @see GridSerializer.serialize
     */
    fun synthesizeRedraw(): ByteArray = synchronized(emulator) {
        GridSerializer.serialize(emulator, servedHistory())
    }

    /**
     * Synthesize the persistence form: scrollback (and, for a live TUI, an inert
     * frozen frame) with no mode/cursor epilogue, safe to store and replay into a
     * fresh grid on restore without resurrecting a dead session's modes.
     *
     * @return UTF-8 bytes for persistence.
     * @see GridSerializer.serializeForPersist
     */
    fun synthesizeForPersist(): ByteArray = synchronized(emulator) {
        GridSerializer.serializeForPersist(emulator, servedHistory())
    }

    private companion object {
        /** Emulator's hard minimum per side; [TerminalEmulator.resize] throws below 2. */
        const val MIN_DIM = 2

        /** Headless cell pixel sizes — used only for DECSLPP-style pixel reports, never rendered. */
        const val NOMINAL_CELL_WIDTH_PX = 8
        const val NOMINAL_CELL_HEIGHT_PX = 16

        /**
         * Asks [TerminalEmulator] for a screen-only main buffer. Scrollback depth is
         * [HistoryLog]'s concern now, counted in logical lines rather than rows.
         */
        const val NO_EMULATOR_TRANSCRIPT = 0

        /**
         * How many chunks a reconciliation window may stay open before it is resolved
         * anyway. A program's `SIGWINCH` response does not always land in the first chunk —
         * stale in-flight output can precede it — but the window cannot stay open forever,
         * or a later scroll-off would be judged against a long-past resize. Reads resolve
         * the window too, so this is only the backstop for a session nobody is looking at.
         */
        const val WINDOW_MAX_CHUNKS = 24
    }
}
