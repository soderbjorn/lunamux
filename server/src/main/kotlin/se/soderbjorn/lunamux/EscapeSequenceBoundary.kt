/**
 * Finds where a run of terminal output stops being complete.
 *
 * A PTY read can end anywhere, including in the middle of an escape sequence
 * (`ESC [ 2` now, `G` on the next read). JediTerm, which [ScreenEmulator]
 * wraps, cannot resume a sequence it ran out of input for: its emulator loop
 * swallows the end-of-input signal, the characters of the sequence read so far
 * are lost, and the remainder is printed as text. A Claude Code dialog line
 * then snapshots as `[2GNo, exit` with its `❯` selection marker gone, so
 * anything reading the screen — [StateDetector] above all — sees text that was
 * never on the real terminal.
 *
 * This file holds [EscapeSequenceBoundary], a small scanner that reports where
 * an unterminated sequence begins so [ScreenEmulator.feed] can hold it back
 * until the rest arrives.
 *
 * @see ScreenEmulator.feed
 */
package se.soderbjorn.lunamux

/**
 * Locates an escape sequence left unterminated at the end of a char run.
 *
 * Recognises the sequence shapes terminal programs emit (ECMA-48 / xterm):
 *  - CSI `ESC [` … parameter and intermediate bytes … one final byte `0x40–0x7E`;
 *  - string sequences OSC `ESC ]`, DCS `ESC P`, SOS `ESC X`, PM `ESC ^` and
 *    APC `ESC _`, each ended by BEL or ST (`ESC \`);
 *  - character-set and similar designators (`ESC (`, `ESC )`, `ESC #`, …),
 *    which take exactly one more character;
 *  - every other `ESC x` pair, which is complete as soon as `x` arrives.
 *
 * Used by [ScreenEmulator.feed] only.
 */
internal object EscapeSequenceBoundary {

    private const val ESC = '\u001b'
    private const val BEL = '\u0007'

    /** Second characters that open a string sequence ended by BEL or ST. */
    private const val STRING_INTRODUCERS = "]PX^_"

    /** Second characters whose sequence takes exactly one more character. */
    private const val ONE_ARGUMENT_INTRODUCERS = "()*+-./#% "

    /**
     * Returns the index at which an unterminated escape sequence starts in
     * [chars], or `-1` when everything in [chars] is complete.
     *
     * Scans the whole run once from the start, so a sequence that opens early
     * and is still open at the end (an OSC title with a long payload, say) is
     * reported from its `ESC`, not from some later byte inside it.
     *
     * @param chars decoded terminal output, possibly ending mid-sequence.
     * @return the start index of the trailing incomplete sequence, or `-1`.
     */
    fun incompleteTailStart(chars: CharSequence): Int {
        var i = 0
        val n = chars.length
        while (i < n) {
            if (chars[i] != ESC) { i++; continue }
            val start = i
            val end = sequenceEnd(chars, start)
            if (end < 0) return start
            i = end
        }
        return -1
    }

    /**
     * Returns the index just past the escape sequence that starts at [start],
     * or `-1` when [chars] ends before the sequence does.
     *
     * @param chars the run being scanned.
     * @param start index of an `ESC`.
     * @return index after the sequence's last character, or `-1` if it is unterminated.
     */
    private fun sequenceEnd(chars: CharSequence, start: Int): Int {
        val n = chars.length
        if (start + 1 >= n) return -1
        val kind = chars[start + 1]
        return when {
            kind == '[' -> {
                var j = start + 2
                while (j < n) {
                    val c = chars[j]
                    if (c.code in 0x40..0x7E) return j + 1
                    // A control character or a new ESC aborts the sequence;
                    // terminals treat it as ended, so it is not "incomplete".
                    if (c.code < 0x20 || c == ESC) return j
                    j++
                }
                -1
            }
            kind in STRING_INTRODUCERS -> {
                var j = start + 2
                while (j < n) {
                    val c = chars[j]
                    if (c == BEL) return j + 1
                    if (c == ESC) {
                        // ESC at the very end may be the first half of ST.
                        if (j + 1 >= n) return -1
                        // ESC \ is ST. Any other ESC ends the string and
                        // starts a new sequence, which the caller scans next.
                        return if (chars[j + 1] == '\\') j + 2 else j
                    }
                    j++
                }
                -1
            }
            kind in ONE_ARGUMENT_INTRODUCERS -> if (start + 2 < n) start + 3 else -1
            else -> start + 2
        }
    }
}
