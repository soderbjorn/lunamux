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
 * dissolves the width-bound byte-replay bug class instead of managing it. It is also
 * the terminal's single *answerer* — device queries are answered here and every
 * attached client's own answer is dropped at the server, so a question with one
 * correct answer gets exactly one (see [SessionGrid.armAnswerSink]).
 *
 * The emulator holds only the live screen; scrollback is [HistoryLog], outside it,
 * as logical lines no reflow can reach. A resize therefore re-lays out the screen and
 * nothing else; whatever it pushes off the top is committed as scrolled-off content,
 * exactly as a real terminal's scrollback would record it. See
 * `docs/server-side-screen.md` for the measured behaviour behind this and for the
 * approaches it replaced.
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
 * @param initialAnswerSink where the emulator's device-query replies go, or `null` (the
 *   default) to discard them. Production arms the sink after construction instead — see
 *   [armAnswerSink] for why, and for what the owner must guarantee about the callback.
 */
class SessionGrid(cols: Int, rows: Int, initialAnswerSink: ((ByteArray) -> Unit)? = null) {

    /**
     * The wired answer sink, or `null` while replies are discarded. `@Volatile` because
     * [armAnswerSink] runs on the constructing thread while feeds arrive on the PTY reader.
     *
     * Note the constructor parameter is deliberately named differently: a same-named parameter
     * stays in scope throughout the class body and would shadow this property inside
     * [gridOutput]'s object expression, silently pinning the sink to its construction-time
     * value and making [armAnswerSink] a no-op.
     *
     * @see armAnswerSink
     */
    @Volatile
    private var answerSink: ((ByteArray) -> Unit)? = initialAnswerSink

    /**
     * Where the emulator writes its device-query replies (DSR, DA, OSC colour reads,
     * XTWINOPS reports, mouse reports, …).
     *
     * **The canonical grid is the terminal's single answerer.** A device query has exactly
     * one correct answer, and this emulator is the one emulator that speaks for the session
     * — so when [answerSink] is wired, replies are handed to the owner to write to the PTY,
     * and every attached client's own reply is dropped at the server (see `PtyRoutes`). The
     * arrangement this replaces had N answerers: each interactive client answered every
     * query and the server wrote them all, so two clients meant two answers and ZLE consumed
     * the surplus as typed input and echoed it into canonical state.
     *
     * With no [answerSink] the replies are discarded, which is what tests, the round-trip
     * harness, and the restore feed want: a *replayed* query must never be answered into a
     * live shell's stdin, and a synthesized redraw never contains queries at all.
     *
     * The callback must not block and must not write to the PTY inline — see the contract
     * documented on the constructor parameter's use site in `TerminalSessionManager`: this
     * runs on the PTY reader thread inside `emulator.append`, holding both the caller's
     * outbound lock and this grid's monitor.
     *
     * [discardedOutputBytes] counts what was sunk while no sink was wired, purely so tests
     * can assert the emulator really did answer-and-drop rather than leak.
     */
    private val gridOutput = object : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) {
            if (count <= 0 || data == null) return
            val sink = answerSink
            if (sink == null) {
                discardedOutputBytes += count.toLong()
                return
            }
            sink(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    /**
     * Bytes the emulator wrote back to [gridOutput] (query replies, mouse reports, …) that
     * this grid discarded because no answer sink was wired. Observability-only; see the field
     * doc on [gridOutput]. `@Volatile` because feeds may arrive from the PTY read coroutine
     * while a test thread reads the counter.
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
        gridOutput,
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

    init {
        // Rows leaving the screen become history. Converted here, synchronously: the
        // emulator recycles the row the moment this returns.
        emulator.mainBuffer.setRowEvictionListener { row, wrapped ->
            history.appendRow(GridSerializer.rowRuns(row, emulator.mColumns, wrapped), wrapped)
        }
        // ED3 (`ESC[3J`, what `clear` emits to wipe scrollback) must drop the external
        // history too. Without this the split regressed `clear`: the live screen blanks but
        // history survives and the next redraw re-emits it — the "my earlier prompts came
        // back after clearing" report.
        emulator.mainBuffer.setTranscriptClearedListener {
            history.clear()
        }
    }

    /**
     * Start routing the emulator's device-query replies to [sink], making this grid the
     * session's single answerer.
     *
     * Separate from the constructor on purpose: the owner feeds the persisted scrollback blob
     * into a fresh grid before the session goes live, and a legacy raw blob can contain device
     * queries. Answering those would push a reply for a query from a *dead* session into the
     * new shell's stdin, where ZLE reads it as typed input. Arming after the restore feed
     * makes that impossible by construction rather than by filtering.
     *
     * @param sink receives each reply as its own byte array, in the order the emulator
     *   produced it. **It must not block and must not write to the PTY inline**: it is called
     *   from inside `emulator.append` on the PTY reader thread while the caller's outbound
     *   lock and this grid's monitor are both held, and a blocking `write(2)` there is the
     *   both-ends-blocked deadlock shape. Hand the bytes to a queue and let one writer drain
     *   it (see `TerminalSessionManager`).
     */
    fun armAnswerSink(sink: (ByteArray) -> Unit) {
        synchronized(emulator) { answerSink = sink }
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
        synchronized(emulator) {
            try {
                emulator.append(buf, len)
            } catch (t: Throwable) {
                // A malformed control sequence must never take the PTY read loop
                // down. The grid may be left mid-sequence; the next feed recovers.
                Swallowed.note("feed", t)
            }
        }
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
            try {
                emulator.resize(cols, rows, NOMINAL_CELL_WIDTH_PX, NOMINAL_CELL_HEIGHT_PX)
            } catch (t: Throwable) {
                // Resize races are benign; the next feed settles the layout.
                Swallowed.note("resize", t)
            }
            // A re-layout is a logical-line boundary in a way ordinary scrolling is not: what
            // it evicted was wrapped at the OLD width, and the continuation of that line was
            // rewrapped and is still on the live screen. Closing the line here keeps a
            // wrapped last eviction from sitting invisible in the log — absent from every
            // paint — until an unrelated later eviction fuses onto it. Inside the same monitor
            // hold as the resize, so no reader can observe the gap.
            history.closeOpenLine()
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
     * The session's committed scrollback — the half of the session that lives outside
     * the emulator. Matches exactly what [synthesizeRedraw] emits ahead of the screen.
     *
     * @return the logical lines, oldest first.
     */
    fun historyLines(): List<LogicalLine> = synchronized(emulator) { history.lines() }

    /**
     * The full normal-buffer transcript (scrollback + visible screen) as plain
     * text, one logical line per row. Backed by the canonical grid so it stays
     * correct across resizes; used by the MCP `read_scrollback` tool.
     *
     * @return the main buffer's transcript text.
     */
    fun transcriptText(): String = synchronized(emulator) {
        val sb = StringBuilder()
        for (line in history.lines()) sb.append(line.text).append('\n')
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
        GridSerializer.serialize(emulator, history.lines())
    }

    /**
     * The redraw bytes and the grid dims they were authored at, taken under one
     * hold of the grid monitor so the two can never disagree.
     *
     * @property bytes the full redraw, exactly what [synthesizeRedraw] produces.
     * @property cols the columns the redraw was authored at.
     * @property rows the rows the redraw was authored at.
     */
    class AttachSnapshot(val bytes: ByteArray, val cols: Int, val rows: Int)

    /**
     * Snapshot the grid for a fresh attach: the same history + screen redraw a
     * live resync carries, plus the dims it was authored at.
     *
     * Called by `TerminalSession.attachPayload()` when a client connects. It must
     * serve the same paint as [synthesizeRedraw] — serializing the emulator alone
     * would paint only the visible screen, leaving an attaching client with no
     * scrollback until the next cols change happens to fire a resync.
     *
     * @return the redraw and its dims, consistent under one monitor hold.
     */
    fun attachSnapshot(): AttachSnapshot = synchronized(emulator) {
        AttachSnapshot(
            GridSerializer.serialize(emulator, history.lines()),
            emulator.mColumns,
            emulator.mRows,
        )
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
        GridSerializer.serializeForPersist(emulator, history.lines())
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
    }
}
