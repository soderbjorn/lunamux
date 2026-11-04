/**
 * Tests for [EscapeSequenceBoundary.incompleteTailStart] — finding an escape
 * sequence left unterminated at the end of a PTY read — and for
 * [ScreenEmulator] rendering output identically however the reads split it.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals

class EscapeSequenceBoundaryTest {

    private fun tail(s: String) = EscapeSequenceBoundary.incompleteTailStart(s)

    @Test
    fun completeRunHasNoTail() {
        assertEquals(-1, tail("plain text"))
        assertEquals(-1, tail("a\u001b[2Gb\u001b[38;2;1;2;3mc\u001b[0m"))
        assertEquals(-1, tail("\u001b]0;title\u0007after"))
        assertEquals(-1, tail("\u001b]8;;https://x\u001b\\link"))
        assertEquals(-1, tail("\u001b(B\u001b7\u001b8"))
    }

    @Test
    fun loneEscapeAtEndIsIncomplete() {
        assertEquals(3, tail("abc\u001b"))
    }

    @Test
    fun unfinishedCsiIsIncompleteFromItsEscape() {
        assertEquals(1, tail("a\u001b["))
        assertEquals(1, tail("a\u001b[38;2;177"))
    }

    @Test
    fun unfinishedStringSequenceIsIncompleteFromItsStart() {
        assertEquals(0, tail("\u001b]0;a long title"))
        // ESC at the very end may be the first half of the ST terminator.
        assertEquals(0, tail("\u001b]0;title\u001b"))
    }

    @Test
    fun designatorNeedsItsArgument() {
        assertEquals(0, tail("\u001b("))
        assertEquals(-1, tail("\u001b(0"))
    }

    @Test
    fun controlCharacterEndsACsi() {
        assertEquals(-1, tail("\u001b[12\r\n"))
    }

    @Test
    fun screenEmulatorRendersTheSameHoweverReadsSplitTheOutput() {
        // Claude Code's folder-trust options, as it draws them.
        val bytes = ("\r\n\u001b[2G\u001b[38;2;177;185;249m❯\u001b[4GNo,\u001b[8Gexit\u001b[39m\r\n" +
            "\u001b[4GYes,\u001b[9GI\u001b[11Gtrust\u001b[17Gthis\u001b[22Gfolder\r\n").toByteArray()
        fun render(feedPieces: (ScreenEmulator) -> Unit): String =
            ScreenEmulator(40, 4).also(feedPieces).snapshotVisibleText().lines().map { it.trimEnd() }.joinToString("\n")

        val whole = render { it.feed(bytes, bytes.size) }
        assertEquals(" ❯ No, exit\n   Yes, I trust this folder", whole.trim('\n').lines().filter { it.isNotBlank() }.joinToString("\n"))
        val bytewise = render { e -> for (b in bytes) e.feed(byteArrayOf(b), 1) }
        assertEquals(whole, bytewise)
        for (cut in 1 until bytes.size) {
            val split = render { e ->
                e.feed(bytes.copyOfRange(0, cut), cut)
                e.feed(bytes.copyOfRange(cut, bytes.size), bytes.size - cut)
            }
            assertEquals(whole, split, "split at byte $cut")
        }
    }
}
