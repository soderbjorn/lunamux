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
