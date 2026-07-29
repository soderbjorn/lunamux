package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

/**
 * LUNAMUX ADDITION. Covers the two additions the server's canonical grid depends on:
 * a main buffer that keeps no scrollback at all, and a hook that reports each row as it
 * leaves the screen.
 * <p>
 * Together they let history live outside the emulator as logical lines that no resize
 * may rewrite. The emulator then models only the live screen, so a reflow has nothing
 * historical to touch.
 */
public class ScreenOnlyBufferTest extends TestCase {

    /** Build a headless emulator whose main buffer holds only the screen. */
    private TerminalEmulator screenOnly(int cols, int rows) {
        return new TerminalEmulator(new NullOutput(), cols, rows, 8, 16, 0, null);
    }

    /** Build a stock emulator, to show the additions leave normal behaviour alone. */
    private TerminalEmulator withTranscript(int cols, int rows, int transcript) {
        return new TerminalEmulator(new NullOutput(), cols, rows, 8, 16, transcript, null);
    }

    private void feed(TerminalEmulator e, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.append(b, b.length);
    }

    public void testScreenOnlyBufferNeverAccumulatesTranscript() {
        TerminalEmulator e = screenOnly(20, 4);
        for (int i = 0; i < 20; i++) feed(e, "line " + i + "\r\n");
        assertEquals("a screen-only buffer keeps no scrollback", 0, e.getScreen().getActiveTranscriptRows());
    }

    public void testStockBufferStillAccumulatesTranscript() {
        TerminalEmulator e = withTranscript(20, 4, 100);
        for (int i = 0; i < 20; i++) feed(e, "line " + i + "\r\n");
        assertTrue("stock behaviour is unchanged", e.getScreen().getActiveTranscriptRows() > 0);
    }

    public void testRowsShrinkOnAScreenOnlyBufferCreatesNoTranscript() {
        // The rows-only fast path used to derive this from the caller's altScreen flag,
        // so a screen-only MAIN buffer would have been handed transcript rows it has no
        // room for — addressing past the end of the ring.
        TerminalEmulator e = screenOnly(20, 12);
        for (int i = 0; i < 30; i++) feed(e, "line " + i + "\r\n");
        e.resize(20, 5, 8, 16);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());
        e.resize(20, 12, 8, 16);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());
    }

    public void testColsChangeOnAScreenOnlyBufferCreatesNoTranscript() {
        TerminalEmulator e = screenOnly(40, 6);
        for (int i = 0; i < 20; i++) feed(e, "a fairly long line number " + i + "\r\n");
        e.resize(15, 6, 8, 16);
        assertEquals("narrowing must not resurrect a transcript", 0, e.getScreen().getActiveTranscriptRows());
        e.resize(80, 6, 8, 16);
        assertEquals("nor widening", 0, e.getScreen().getActiveTranscriptRows());
    }

    /** The visible screen, one entry per row, exactly as stored (full width, untrimmed). */
    private List<String> screenRows(TerminalEmulator e) {
        TerminalBuffer b = e.getScreen();
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < b.mScreenRows; r++) {
            TerminalRow row = b.mLines[b.externalToInternalRow(r)];
            rows.add(row == null ? "" : new String(row.mText, 0, row.getSpaceUsed()));
        }
        return rows;
    }

    /**
     * The ring is coherent: its length is its own modulus, and no row object appears twice.
     * Both held while the rows-only shrink was rotating the screen inside a ring it had
     * already shrunk the modulus of — which is why the invariant is asserted rather than
     * assumed.
     */
    private void assertRingIsCoherent(TerminalEmulator e) {
        TerminalBuffer b = e.getScreen();
        assertEquals("mTotalRows must be mLines.length", b.mLines.length, b.mTotalRows);
        assertTrue("the screen must fit the ring", b.mScreenRows + b.getActiveTranscriptRows() <= b.mTotalRows);
        List<TerminalRow> seen = new ArrayList<>();
        for (TerminalRow row : b.mLines) {
            if (row == null) continue;
            for (TerminalRow other : seen)
                assertFalse("a row object appears at two places in the ring", other == row);
            seen.add(row);
        }
    }

    /** Fill rows 0..rows-1 with "line 0".."line n", leaving the cursor on the last one. */
    private void fillOneLinePerRow(TerminalEmulator e, int rows) {
        for (int i = 0; i < rows; i++) {
            if (i > 0) feed(e, "\r\n");
            feed(e, "line " + i);
        }
    }

    public void testRowsOnlyShrinkOnScreenOnlyKeepsTheRingCoherent() {
        // The bug this pins: the fast path advanced mScreenFirstRow modulo the OLD ring size
        // and then shrank mTotalRows without reallocating mLines, so every later row lookup
        // reduced modulo a size the array did not have — an aliased, rotated screen that was
        // neither the old one nor a coherent new one, and no client resync to expose it.
        TerminalEmulator e = screenOnly(20, 10);
        final List<String> evicted = new ArrayList<>();
        e.getScreen().setRowEvictionListener((row, wrapped) -> evicted.add(new String(row.mText, 0, row.getSpaceUsed()).trim()));
        fillOneLinePerRow(e, 10);

        e.resize(20, 4, 8, 16);

        assertRingIsCoherent(e);
        assertEquals(4, e.getScreen().mTotalRows);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());
        // The bottom four rows survive, in order and once each.
        List<String> rows = screenRows(e);
        for (int i = 0; i < 4; i++)
            assertEquals("row " + i, "line " + (6 + i), rows.get(i).trim());
        // Everything above them reached history, oldest first, exactly once.
        assertEquals("[line 0, line 1, line 2, line 3, line 4, line 5]", evicted.toString());
        // The cursor rode its own content down: it was on "line 9", the last row.
        assertEquals(3, e.getCursorRow());
        assertEquals("line 9".length(), e.getCursorCol());
    }

    public void testRowsOnlyShrinkShowsExactlyWhatATranscriptFulBufferShows() {
        // The hard constraint on the fix: clients run transcript-ful buffers and get NO
        // resync on a rows-only change, so the canonical screen-only grid must land on the
        // same visible screen the client's own resize produces. Any divergence is a
        // permanent, invisible split between what the server thinks it shows and what a
        // client shows.
        for (int newRows : new int[]{2, 3, 7, 11}) {
            for (boolean trailingBlanks : new boolean[]{false, true}) {
                TerminalEmulator screen = screenOnly(24, 12);
                TerminalEmulator stock = withTranscript(24, 12, 400);
                for (TerminalEmulator e : new TerminalEmulator[]{screen, stock}) {
                    fillOneLinePerRow(e, trailingBlanks ? 6 : 12);
                    e.resize(24, newRows, 8, 16);
                }
                String why = "rows=" + newRows + " trailingBlanks=" + trailingBlanks;
                assertRingIsCoherent(screen);
                assertEquals(why, screenRows(stock).toString(), screenRows(screen).toString());
                assertEquals("cursor row, " + why, stock.getCursorRow(), screen.getCursorRow());
                assertEquals("cursor col, " + why, stock.getCursorCol(), screen.getCursorCol());
            }
        }
    }

    public void testRowsOnlyShrinkThenGrowOnScreenOnly() {
        // The phone soft-keyboard settle: rows down, rows back up, no columns change and no
        // resync in between.
        TerminalEmulator e = screenOnly(20, 8);
        final List<String> evicted = new ArrayList<>();
        e.getScreen().setRowEvictionListener((row, wrapped) -> evicted.add(new String(row.mText, 0, row.getSpaceUsed()).trim()));
        fillOneLinePerRow(e, 8);

        e.resize(20, 3, 8, 16);
        assertRingIsCoherent(e);
        e.resize(20, 8, 8, 16);
        assertRingIsCoherent(e);
        assertEquals(8, e.getScreen().mTotalRows);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());

        // Growing cannot bring back what shrinking archived — history is outside the
        // emulator now — so the survivors sit at the top and the rest is blank.
        List<String> rows = screenRows(e);
        assertEquals("line 5", rows.get(0).trim());
        assertEquals("line 6", rows.get(1).trim());
        assertEquals("line 7", rows.get(2).trim());
        for (int i = 3; i < 8; i++) assertEquals("row " + i + " must be blank", "", rows.get(i).trim());
        assertEquals("[line 0, line 1, line 2, line 3, line 4]", evicted.toString());
    }

    public void testAlternateBufferRowsShrinkKeepsItsRingCoherent() {
        // The alt buffer is allocated screen-only too, so it took the same broken path — and
        // it has no eviction listener, which the fix must tolerate.
        TerminalEmulator e = withTranscript(20, 8, 200);
        feed(e, "main buffer line");
        feed(e, "\033[?1049h");
        fillOneLinePerRow(e, 8);

        e.resize(20, 3, 8, 16);

        assertRingIsCoherent(e);
        assertEquals(3, e.getScreen().mTotalRows);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());
        List<String> rows = screenRows(e);
        assertEquals("line 5", rows.get(0).trim());
        assertEquals("line 6", rows.get(1).trim());
        assertEquals("line 7", rows.get(2).trim());

        // Leaving the alt buffer must still find the main buffer as it was left.
        feed(e, "\033[?1049l");
        assertRingIsCoherent(e);
        assertEquals("main buffer line", screenRows(e).get(0).trim());
    }

    public void testEvictionListenerSeesRowsLeavingTheScreen() {
        TerminalEmulator e = screenOnly(20, 3);
        final List<String> seen = new ArrayList<>();
        e.getScreen().setRowEvictionListener((row, wrapped) -> seen.add(row.getSpaceUsed() == 0 ? "" : new String(row.mText, 0, row.getSpaceUsed()).trim()));

        feed(e, "first\r\nsecond\r\nthird\r\nfourth\r\n");
        // A 3-row screen showing four lines plus the cursor row has pushed the earliest
        // ones off the top.
        assertTrue("expected evictions, got " + seen, seen.size() >= 2);
        assertEquals("first", seen.get(0));
        assertEquals("second", seen.get(1));
    }

    public void testEvictionListenerReportsTheWrapFlag() {
        TerminalEmulator e = screenOnly(10, 2);
        final List<Boolean> wraps = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        e.getScreen().setRowEvictionListener((row, wrapped) -> {
            wraps.add(wrapped);
            texts.add(new String(row.mText, 0, row.getSpaceUsed()));
        });

        // 15 chars at width 10 soft-wraps: the first row carries the wrap flag, so a
        // consumer knows the logical line continues.
        feed(e, "abcdefghijklmno\r\nnext\r\nmore\r\n");
        assertTrue("expected an eviction", !wraps.isEmpty());
        assertEquals("abcdefghij", texts.get(0));
        assertEquals(Boolean.TRUE, wraps.get(0));
    }

    public void testEvictionListenerIsSilentForScrollRegions() {
        // A row displaced out of a scroll region is overwritten in place; a real terminal
        // does not put it in scrollback either, so history must not see it.
        TerminalEmulator e = screenOnly(20, 6);
        feed(e, "keep me\r\n");
        final List<String> seen = new ArrayList<>();
        e.getScreen().setRowEvictionListener((row, wrapped) -> seen.add(new String(row.mText, 0, row.getSpaceUsed())));

        // Set a scroll region covering rows 3..5, park the cursor at its bottom, scroll.
        feed(e, "[3;5r[5;1H\n\n\n\n");
        assertTrue("scroll-region churn must not reach history, saw " + seen, seen.isEmpty());
    }

    public void testAlternateBufferIsUnaffected() {
        TerminalEmulator e = withTranscript(20, 4, 100);
        feed(e, "normal buffer line\r\n");
        feed(e, "[?1049h"); // enter alt screen
        for (int i = 0; i < 20; i++) feed(e, "alt " + i + "\r\n");
        assertEquals("the alternate buffer has never had scrollback", 0, e.getScreen().getActiveTranscriptRows());
        e.resize(20, 2, 8, 16);
        assertEquals(0, e.getScreen().getActiveTranscriptRows());
    }

    // ── backfill: putting rows back above the screen ──────────────────────────

    /** Lay {@code text} out as rows at {@code cols} wide, the way the owner of an external
     * history does before handing them back. */
    private TerminalRow[] rowsFor(int cols, String... text) {
        TerminalEmulator scratch = screenOnly(cols, Math.max(2, text.length));
        for (int i = 0; i < text.length; i++) {
            if (i > 0) feed(scratch, "\r\n");
            feed(scratch, text[i]);
        }
        TerminalRow[] rows = new TerminalRow[text.length];
        for (int i = 0; i < text.length; i++) rows[i] = scratch.getScreen().getRow(i);
        return rows;
    }

    private String rowText(TerminalEmulator e, int row) {
        return e.getScreen().getSelectedText(0, row, e.mColumns - 1, row).trim();
    }

    public void testBackfillPlacesRowsAboveAndCarriesTheCursorDown() {
        // The property the server's canonical grid needs: growing the screen reveals
        // history above rather than blank rows below, so the cursor keeps its distance
        // from the bottom of the screen. A screen-only ring has no transcript to reveal,
        // so its owner hands the rows back through this.
        TerminalEmulator e = screenOnly(20, 6);
        feed(e, "content one\r\ncontent two\r\nPROMPT");
        e.resize(20, 9, 8, 16);
        assertEquals("the grow left the cursor stranded mid-screen", 2, e.getCursorRow());

        assertTrue(e.backfillAboveScreen(rowsFor(20, "older one", "older two", "older three"), 0));


        assertEquals("the cursor followed its own row down", 5, e.getCursorRow());
        assertEquals("older one", rowText(e, 0));
        assertEquals("older three", rowText(e, 2));
        assertEquals("content one", rowText(e, 3));
        assertEquals("PROMPT", rowText(e, 5));
    }

    public void testBackfilledGrowShowsExactlyWhatATranscriptFulBufferShows() {
        // Why the backfill has to exist at all. A transcript-ful buffer — which is what every
        // client renders with, Termux here and xterm.js on the web — answers a rows-grow by
        // moving its screen UP into its transcript (see resize()'s "Only move screen up if
        // there is transcript to show"), so the content stays pressed against the bottom. The
        // screen-only canonical grid has no transcript to move into and padded below instead,
        // which is a *divergence*: same session, same size, two different screens, and absolute
        // cursor addressing then lands on different content on the server than on the client.
        TerminalEmulator screen = screenOnly(24, 12);
        TerminalEmulator stock = withTranscript(24, 12, 400);
        final List<String> evicted = new ArrayList<>();
        screen.getScreen().setRowEvictionListener(
            (row, wrapped) -> evicted.add(new String(row.mText, 0, row.getSpaceUsed()).trim()));
        for (TerminalEmulator e : new TerminalEmulator[]{screen, stock}) {
            for (int i = 0; i < 30; i++) {
                if (i > 0) feed(e, "\r\n");
                feed(e, "line " + i);
            }
            e.resize(24, 20, 8, 16);
        }

        // The owner of the external history hands back the rows the grow made room for: the
        // newest evicted ones, oldest first.
        int room = screen.backfillCapacityAboveScreen(20);
        String[] back = evicted.subList(evicted.size() - room, evicted.size()).toArray(new String[0]);
        assertTrue(screen.backfillAboveScreen(rowsFor(24, back), 0));

        assertEquals(screenRows(stock).toString(), screenRows(screen).toString());
        assertEquals("cursor row", stock.getCursorRow(), screen.getCursorRow());
        assertEquals("cursor col", stock.getCursorCol(), screen.getCursorCol());
    }

    public void testBackfillCanRestateTheScreensTopRows() {
        // The widening case a pure prepend cannot express: the screen's first row is the tail of
        // a line whose head is in the external history, and at the new width the two fit in no
        // more rows than the tail alone already occupies — so the head does not go above that
        // row, it rejoins it.
        TerminalEmulator e = screenOnly(20, 5);
        feed(e, "tail of a line\r\nsecond\r\nPROMPT");
        assertEquals(2, e.backfillCapacityAboveScreen(5));

        // One row restating row 0, plus one row above it.
        assertTrue(e.backfillAboveScreen(rowsFor(20, "above", "head + tail of it"), 1));

        assertEquals("above", rowText(e, 0));
        assertEquals("head + tail of it", rowText(e, 1));
        assertEquals("second", rowText(e, 2));
        assertEquals("PROMPT", rowText(e, 3));
        assertEquals("the cursor moved down by the one net row", 3, e.getCursorRow());
    }

    public void testBackfillIsRefusedRatherThanTruncated() {
        // All-or-nothing on purpose: the caller has to pop the lines out of its history to
        // hand them over, so a partial placement would drop the oldest one on the floor.
        TerminalEmulator e = screenOnly(20, 6);
        feed(e, "one\r\ntwo\r\nthree\r\nfour\r\nfive\r\nsix");   // no blank rows at all

        assertEquals(0, e.backfillCapacityAboveScreen(3));
        assertFalse(e.backfillAboveScreen(rowsFor(20, "a", "b", "c"), 0));
        assertEquals("nothing moved", "one", rowText(e, 0));
    }

    public void testBackfillRefusesABufferThatHasItsOwnTranscript() {
        // A ring with room above the screen reveals its own rows on a grow (the vendored
        // path), and rotating it backwards here would consume them.
        TerminalEmulator e = withTranscript(20, 4, 100);
        for (int i = 0; i < 20; i++) feed(e, "line " + i + "\r\n");
        e.resize(20, 8, 8, 16);
        assertEquals(0, e.backfillCapacityAboveScreen(4));
    }

    public void testBackfillRefusesTheAlternateBufferAndScrollRegions() {
        TerminalEmulator alt = screenOnly(20, 8);
        feed(alt, "\u001b[?1049h");
        assertEquals("an alt frame is absolutely addressed and has no history behind it",
            0, alt.backfillCapacityAboveScreen(4));

        TerminalEmulator region = screenOnly(20, 8);
        feed(region, "hello");
        feed(region, "\u001b[3;6r");
        assertEquals("a program addressing rows by number must not have them moved",
            0, region.backfillCapacityAboveScreen(4));
    }

    /** A sink for the emulator's device replies; a headless buffer has nowhere to send them. */
    private static class NullOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
