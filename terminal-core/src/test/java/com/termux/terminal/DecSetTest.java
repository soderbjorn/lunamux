package com.termux.terminal;

/**
 * <pre>
 * "CSI ? Pm h", DEC Private Mode Set (DECSET)
 * </pre>
 * <p/>
 * and
 * <p/>
 * <pre>
 * "CSI ? Pm l", DEC Private Mode Reset (DECRST)
 * </pre>
 * <p/>
 * controls various aspects of the terminal
 */
public class DecSetTest extends TerminalTestCase {

	/** DECSET 25, DECTCEM, controls visibility of the cursor. */
	public void testEnableDisableCursor() {
		withTerminalSized(3, 3);
		assertTrue("Initially the cursor should be enabled", mTerminal.isCursorEnabled());
		enterString("\033[?25l"); // Disable Cursor (DECTCEM).
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033[?25h"); // Enable Cursor (DECTCEM).
		assertTrue(mTerminal.isCursorEnabled());

		enterString("\033[?25l"); // Disable Cursor (DECTCEM), again.
		assertFalse(mTerminal.isCursorEnabled());
		mTerminal.reset();
		assertTrue("Resetting the terminal should enable the cursor", mTerminal.isCursorEnabled());

		enterString("\033[?25l");
		assertFalse(mTerminal.isCursorEnabled());
		enterString("\033c"); // RIS resetting should enabled cursor.
		assertTrue(mTerminal.isCursorEnabled());
	}

	/** DECSET 2004, controls bracketed paste mode. */
	public void testBracketedPasteMode() {
		withTerminalSized(3, 3);

		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		enterString("\033[?2004l"); // Disable bracketed paste mode.
		mTerminal.paste("a");
		assertEquals("Pasting 'a' should output 'a' when bracketed paste mode is disabled", "a", mOutput.getOutputAndClear());

		enterString("\033[?2004h"); // Enable bracketed paste mode, again.
		mTerminal.paste("a");
		assertEquals("Pasting when in bracketed paste mode again should be bracketed", "\033[200~a\033[201~", mOutput.getOutputAndClear());

		mTerminal.paste("\033ab\033cd\033");
		assertEquals("Pasting an escape character should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());
		mTerminal.paste("\u0081ab\u0081cd\u009F");
		assertEquals("Pasting C1 control codes should not input it", "\033[200~abcd\033[201~", mOutput.getOutputAndClear());

		mTerminal.reset();
		mTerminal.paste("a");
		assertEquals("Terminal reset() should disable bracketed paste mode", "a", mOutput.getOutputAndClear());
	}

	/** DECSET 7, DECAWM, controls wraparound mode. */
	public void testWrapAroundMode() {
		// Default with wraparound:
		withTerminalSized(3, 3).enterString("abcd").assertLinesAre("abc", "d  ", "   ");
		// With wraparound disabled:
		withTerminalSized(3, 3).enterString("\033[?7labcd").assertLinesAre("abd", "   ", "   ");
		enterString("efg").assertLinesAre("abg", "   ", "   ");
		// Re-enabling wraparound:
		enterString("\033[?7hhij").assertLinesAre("abh", "ij ", "   ");
	}

	/**
	 * Covers the server-side serialization accessors added to {@link TerminalEmulator}
	 * (consumed by :server's GridSerializer) — each driven through the escape sequence that
	 * toggles the underlying state, so the getters are verified against real emulator behavior.
	 */
	public void testSerializationAccessors() {
		withTerminalSized(10, 10);

		// DECSET mode flags.
		assertTrue("autowrap on by default", mTerminal.isAutoWrapEnabled());
		enterString("\033[?7l");
		assertFalse(mTerminal.isAutoWrapEnabled());
		enterString("\033[?7h");
		assertTrue(mTerminal.isAutoWrapEnabled());

		assertFalse("origin mode off by default", mTerminal.isOriginMode());
		enterString("\033[?6h");
		assertTrue(mTerminal.isOriginMode());
		enterString("\033[?6l");
		assertFalse(mTerminal.isOriginMode());

		enterString("\033[?2004h");
		assertTrue(mTerminal.isBracketedPasteMode());
		enterString("\033[?1004h");
		assertTrue(mTerminal.isFocusEventsEnabled());
		enterString("\033[?1006h");
		assertTrue(mTerminal.isMouseProtocolSgr());

		// 1000 and 1002 are mutually exclusive.
		enterString("\033[?1000h");
		assertTrue(mTerminal.isMouseTrackingPressRelease());
		assertFalse(mTerminal.isMouseTrackingButtonEvent());
		enterString("\033[?1002h");
		assertTrue(mTerminal.isMouseTrackingButtonEvent());
		assertFalse(mTerminal.isMouseTrackingPressRelease());

		// SGR fore/back/effect.
		enterString("\033[0m");
		assertEquals(0, mTerminal.getCurrentEffect());
		enterString("\033[31m");
		assertEquals("SGR 31 → foreground color index 1", 1, mTerminal.getCurrentForeColor());
		enterString("\033[42m");
		assertEquals("SGR 42 → background color index 2", 2, mTerminal.getCurrentBackColor());
		enterString("\033[1m");
		assertTrue("SGR 1 sets a (bold) effect bit", mTerminal.getCurrentEffect() != 0);
		enterString("\033[0m");
		assertEquals("SGR 0 clears all effects", 0, mTerminal.getCurrentEffect());

		// Tab stops: default every 8th column; CSI 3 g clears all.
		boolean[] tabs = mTerminal.getTabStops();
		assertEquals(10, tabs.length);
		assertTrue("default tab stop at column 8", tabs[8]);
		assertFalse(tabs[0]);
		assertFalse(tabs[7]);
		enterString("\033[3g");
		for (boolean stop : mTerminal.getTabStops()) assertFalse("all tab stops cleared", stop);

		// Vertical scroll region via DECSTBM (1-based args → 0-based top / exclusive bottom).
		enterString("\033[2;5r");
		assertEquals(1, mTerminal.getTopMargin());
		assertEquals(5, mTerminal.getBottomMargin());

		// Left/right margin mode (DECLRMM / ?69).
		assertFalse(mTerminal.isLeftRightMarginModeEnabled());
		enterString("\033[?69h");
		assertTrue(mTerminal.isLeftRightMarginModeEnabled());

		// Buffer accessors + alternate-screen switch.
		assertSame(mTerminal.getMainBuffer(), mTerminal.getScreen());
		enterString("\033[?1049h");
		assertTrue(mTerminal.isAlternateBufferActive());
		assertSame(mTerminal.getAltBuffer(), mTerminal.getScreen());
		enterString("\033[?1049l");
		assertSame(mTerminal.getMainBuffer(), mTerminal.getScreen());

		// Pending-wrap (deferred autowrap) after filling the last column.
		withTerminalSized(3, 3).enterString("abc");
		assertTrue("cursor parked in last column arms pending wrap", mTerminal.isAboutToAutoWrap());
		enterString("d");
		assertFalse("emitting the wrapped char clears pending wrap", mTerminal.isAboutToAutoWrap());
	}

}
