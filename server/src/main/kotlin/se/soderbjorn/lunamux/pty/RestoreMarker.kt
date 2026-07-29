/**
 * The terminal-mode reset stamped into a session's replay ring after a
 * cross-restart restore (and by the pane menu's "Reset terminal" action).
 *
 * This file contains [RestoreMarker], which owns those bytes and the
 * one-time rewrite that repairs blobs persisted by older server versions.
 *
 * The marker exists because a full-screen program that died with the previous
 * server may have left sticky client-side modes enabled (mouse tracking, focus
 * reporting, bracketed paste, application cursor keys). Nothing will ever send
 * the matching DECRST, so replayed scrollback has to cancel them itself
 * (issue #91).
 *
 * The catch: the marker is *persisted with the ring*, so it comes back through
 * [AltScreenTracker] on the next restore. A marker carrying an alternate-screen
 * exit reads there as an exit with no matching enter — an orphan — and the
 * ingest path responds by dropping everything recorded before it. One restore
 * therefore erased the history of the one before it (LMX-2: "shut down, restart,
 * quit, start again → all sessions completely empty"). [MODE_RESET] consequently
 * carries no buffer switch at all, and [neutralizeLegacy] rewrites the older
 * form out of blobs already on disk before they reach the tracker.
 *
 * @see se.soderbjorn.lunamux.TerminalSession
 * @see AltScreenTracker
 */
package se.soderbjorn.lunamux.pty

/**
 * Byte constants and repair for the post-restore terminal-mode reset.
 *
 * Used by [se.soderbjorn.lunamux.TerminalSession] in two places: appended to
 * the replay ring after a restored blob is ingested, and broadcast by
 * `resetTerminalModes()` when a client asks to un-wedge its terminal.
 */
internal object RestoreMarker {

    /**
     * Escape sequences appended to restored scrollback to cancel terminal
     * modes a dead full-screen app may have left enabled.
     *
     * In order: DECRST of X10/normal/highlight/button-event/any-event mouse
     * tracking plus the UTF-8, SGR and urxvt mouse encodings (9, 1000-1003,
     * 1005, 1006, 1015), focus-event reporting (1004), bracketed paste (2004)
     * and application cursor keys (DECCKM, 1), then DECKPNM (`ESC >`) to
     * restore the normal keypad.
     *
     * Deliberately carries **no** alternate-screen exit. The older form ended
     * in a DECRST 1047 ([LEGACY_MODE_RESET]) as belt-and-braces against a blob
     * that left the client in the alternate buffer, but since the replay ring
     * was split per screen buffer no such blob can exist: an alternate-buffer
     * *enter* is routed to the alternate ring and never persisted, and a frozen
     * TUI frame is inert styled text. The 1047 bought nothing and cost the
     * whole transcript on the next restore — see [neutralizeLegacy].
     *
     * @see neutralizeLegacy
     */
    val MODE_RESET: ByteArray =
        "[?9;1000;1001;1002;1003;1005;1006;1015l[?1004l[?2004l[?1l>"
            .toByteArray(Charsets.US_ASCII)

    /**
     * The pre-fix marker, ending in `ESC[?1047l`.
     *
     * Still present in every `pane_scrollback` blob written by a server that
     * restored a session before the fix, which is why [neutralizeLegacy]
     * exists. Matched as a whole sequence rather than by its 1047 alone so a
     * *genuine* orphaned alternate-screen exit — a pre-span-tracking blob whose
     * `?1049h` was evicted by the ring — is still recognized and still cleaned.
     */
    private val LEGACY_MODE_RESET: ByteArray =
        ("[?9;1000;1001;1002;1003;1005;1006;1015l[?1004l[?2004l[?1l" +
            "[?1047l>").toByteArray(Charsets.US_ASCII)

    /**
     * Rewrite every [LEGACY_MODE_RESET] in [blob] to [MODE_RESET].
     *
     * Called on the restored-blob ingest path before the bytes reach
     * [AltScreenTracker], so a blob written by a pre-fix server keeps the
     * history recorded before its marker instead of having it dropped as
     * orphaned alternate-buffer paint. Content is otherwise untouched: the
     * replacement differs from the original only by the eight bytes of the
     * `ESC[?1047l` it removes.
     *
     * @param blob persisted scrollback, straight from `pane_scrollback`.
     * @return [blob] itself when no legacy marker is present (the common case
     *   from here on), otherwise a rewritten copy.
     * @see se.soderbjorn.lunamux.TerminalSession
     */
    fun neutralizeLegacy(blob: ByteArray): ByteArray {
        var from = indexOf(blob, 0)
        if (from < 0) return blob
        val out = java.io.ByteArrayOutputStream(blob.size)
        var copied = 0
        while (from >= 0) {
            out.write(blob, copied, from - copied)
            out.write(MODE_RESET)
            copied = from + LEGACY_MODE_RESET.size
            from = indexOf(blob, copied)
        }
        out.write(blob, copied, blob.size - copied)
        return out.toByteArray()
    }

    /**
     * Index of the next [LEGACY_MODE_RESET] occurrence in [blob] at or after
     * [start], or -1 when there is none.
     */
    private fun indexOf(blob: ByteArray, start: Int): Int {
        val needle = LEGACY_MODE_RESET
        if (needle.isEmpty() || blob.size < needle.size) return -1
        outer@ for (i in start..blob.size - needle.size) {
            for (j in needle.indices) {
                if (blob[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
