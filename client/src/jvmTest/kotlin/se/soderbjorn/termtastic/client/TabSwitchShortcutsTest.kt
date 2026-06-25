/**
 * Tests for [resolveTabSwitchIndex] — the pure mapping from a
 * `Cmd+<digit>` keyboard shortcut to the tab index it should activate.
 *
 * @see resolveTabSwitchIndex
 */
package se.soderbjorn.termtastic.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TabSwitchShortcutsTest {

    @Test
    fun digitsOneThroughEightMapToZeroBasedIndex() {
        assertEquals(0, resolveTabSwitchIndex(digit = 1, tabCount = 8))
        assertEquals(2, resolveTabSwitchIndex(digit = 3, tabCount = 8))
        assertEquals(7, resolveTabSwitchIndex(digit = 8, tabCount = 8))
    }

    @Test
    fun digitNineAlwaysSelectsLastTab() {
        assertEquals(2, resolveTabSwitchIndex(digit = 9, tabCount = 3))
        assertEquals(11, resolveTabSwitchIndex(digit = 9, tabCount = 12))
        assertEquals(0, resolveTabSwitchIndex(digit = 9, tabCount = 1))
    }

    @Test
    fun outOfRangePositionalDigitReturnsNull() {
        assertNull(resolveTabSwitchIndex(digit = 5, tabCount = 3))
        assertNull(resolveTabSwitchIndex(digit = 8, tabCount = 7))
    }

    @Test
    fun noTabsReturnsNull() {
        assertNull(resolveTabSwitchIndex(digit = 1, tabCount = 0))
        assertNull(resolveTabSwitchIndex(digit = 9, tabCount = 0))
    }

    @Test
    fun digitsOutsideOneThroughNineReturnNull() {
        assertNull(resolveTabSwitchIndex(digit = 0, tabCount = 5))
        assertNull(resolveTabSwitchIndex(digit = 10, tabCount = 5))
        assertNull(resolveTabSwitchIndex(digit = -1, tabCount = 5))
    }
}
