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
 * Deliberately *not* here: any reconciliation of a program's post-SIGWINCH
 * repaint against scrollback. Today a resize reflows one transcript that tangles
 * live screen and history together, which leaves no seam to reconcile on — see
 * `docs/server-side-screen.md` ("Take-over duplication") for the measured
 * behaviour and the history model that is meant to replace it.
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
class SessionGrid(cols: Int, rows: Int) {

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
     * The canonical emulator. `null` client → the cursor style defaults and
     * Logger falls back to stderr (this grid is never bound to a Session client).
     * Cell pixel sizes are nominal (headless). Transcript depth matches the
     * ~3000-row scrollback promise of the normal-buffer replay ring.
     */
    private val emulator = TerminalEmulator(
        discardOutput,
        cols.coerceAtLeast(MIN_DIM),
        rows.coerceAtLeast(MIN_DIM),
        NOMINAL_CELL_WIDTH_PX,
        NOMINAL_CELL_HEIGHT_PX,
        TRANSCRIPT_ROWS,
        null,
    )

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
     * The full normal-buffer transcript (scrollback + visible screen) as plain
     * text, one logical line per row. Backed by the canonical grid so it stays
     * correct across resizes; used by the MCP `read_scrollback` tool.
     *
     * @return the main buffer's transcript text.
     */
    fun transcriptText(): String = synchronized(emulator) { emulator.mainBuffer.transcriptText }

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
    fun synthesizeRedraw(): ByteArray = synchronized(emulator) { GridSerializer.serialize(emulator) }

    /**
     * Synthesize the persistence form: scrollback (and, for a live TUI, an inert
     * frozen frame) with no mode/cursor epilogue, safe to store and replay into a
     * fresh grid on restore without resurrecting a dead session's modes.
     *
     * @return UTF-8 bytes for persistence.
     * @see GridSerializer.serializeForPersist
     */
    fun synthesizeForPersist(): ByteArray = synchronized(emulator) { GridSerializer.serializeForPersist(emulator) }

    private companion object {
        /** Emulator's hard minimum per side; [TerminalEmulator.resize] throws below 2. */
        const val MIN_DIM = 2

        /** Headless cell pixel sizes — used only for DECSLPP-style pixel reports, never rendered. */
        const val NOMINAL_CELL_WIDTH_PX = 8
        const val NOMINAL_CELL_HEIGHT_PX = 16

        /**
         * Scrollback depth of the canonical grid. Matches the ~3000-row promise of
         * the normal-buffer replay ring. Worst-case memory is a few MB per active
         * session (full-width styled rows) versus the ring's ~512 KB — acceptable
         * for the fidelity it buys, and flagged here as the one real cost.
         */
        const val TRANSCRIPT_ROWS = 3000
    }
}
