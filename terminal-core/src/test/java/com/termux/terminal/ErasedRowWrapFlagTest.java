package com.termux.terminal;

/**
 * LUNAMUX ADDITION. A row's wrap flag is a claim about its <b>last cell</b>: "the text ran off
 * this row's right edge and continues on the row below". Erasing that cell makes the claim
 * false, so the flag has to go with it.
 * <p>
 * This emulator never cleared it, and nothing else clears it either, so a stale flag survived
 * until the row object was recycled. Harmless while the flag only guides reflow and selection —
 * all it did upstream. It is load-bearing in Lunamux: {@link TerminalBuffer#getLineWrap} decides
 * whether the server's serializer emits a row as "all columns, no CRLF" and whether the external
 * history fuses it with the next row into one logical line. So a program that erased its rows and
 * repainted something shorter — a full-screen app answering a `SIGWINCH`, i.e. every device
 * take-over and every pane resize — had its own repaint padded out to full width and spliced
 * permanently into the line below.
 *
 * @see TerminalBuffer#blockSet(int, int, int, int, int, long)
 */
public class ErasedRowWrapFlagTest extends TerminalTestCase {

    /** Text long enough to soft-wrap over three rows at 20 columns. */
    private static final String WRAPPING = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJ";

    private boolean wrapped(int row) {
        return mTerminal.getScreen().getLineWrap(row);
    }

    /** Write a paragraph that wraps across rows 0-2, leaving all three flags meaningful. */
    private void givenAWrappedParagraph() {
        withTerminalSized(20, 6).enterString(WRAPPING);
        assertTrue("precondition: row 0 wraps", wrapped(0));
        assertTrue("precondition: row 1 wraps", wrapped(1));
    }

    public void testEraseWholeLineClearsTheWrapFlag() {
        givenAWrappedParagraph();
        enterString("\033[1;1H\033[2K");
        assertFalse("an erased row continues nothing", wrapped(0));
    }

    public void testEraseToEndOfLineClearsTheWrapFlag() {
        // EL 0 from anywhere in the row reaches the last cell, so the wrap goes.
        givenAWrappedParagraph();
        enterString("\033[1;5H\033[K");
        assertFalse(wrapped(0));
    }

    public void testEraseToCursorLeavesTheWrapFlagAlone() {
        // EL 1 stops at the cursor, so the row's last cell — and its claim — survive.
        givenAWrappedParagraph();
        enterString("\033[1;5H\033[1K");
        assertTrue("the last cell was untouched, so the row still wraps", wrapped(0));
    }

    public void testEraseCharactersOnlyClearsTheFlagWhenItReachesTheEnd() {
        givenAWrappedParagraph();
        enterString("\033[1;3H\033[4X");        // ECH well short of the right edge
        assertTrue("a mid-row erase says nothing about the last cell", wrapped(0));

        enterString("\033[1;18H\033[9X");       // ECH clamped at the right edge
        assertFalse("this one blanked the last cell", wrapped(0));
    }

    public void testEraseInDisplayClearsEveryRowItBlanks() {
        givenAWrappedParagraph();
        enterString("\033[1;1H\033[J");         // ED 0: cursor to end of screen
        for (int row = 0; row < 3; row++) {
            assertFalse("row " + row + " was erased", wrapped(row));
        }
    }

    public void testRepaintingShorterTextLeavesNoStaleContinuation() {
        // The shape that caused the damage, captured from a live Claude Code SIGWINCH repaint:
        // hide cursor, home, erase each row, home, paint a shorter frame.
        givenAWrappedParagraph();

        enterString("\033[?25l\033[H");
        for (int row = 0; row < 6; row++) enterString("\033[2K\033[1B");
        enterString("\033[H" + "short\033[?25h");

        assertEquals("short", mTerminal.getScreen().getSelectedText(0, 0, 19, 0).trim());
        for (int row = 0; row < 6; row++) {
            assertFalse("row " + row + " must not claim a continuation", wrapped(row));
        }
    }

    public void testAGenuineWrapIsStillRecorded() {
        // The flag must not become write-only: the fix clears it on erase, and the next real
        // overflow has to set it again.
        givenAWrappedParagraph();
        enterString("\033[H\033[2J");
        assertFalse(wrapped(0));

        enterString(WRAPPING);

        assertTrue("a fresh overflow still wraps", wrapped(0));
        assertTrue(wrapped(1));
    }
}
