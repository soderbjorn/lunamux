/**
 * Sanitizer for replayed scrollback: strips terminal *query* escape
 * sequences from recorded PTY output before it is replayed to a client.
 *
 * Programs emit queries (DSR/CPR, DA, XTVERSION, DECRQM, OSC color/clipboard
 * reads, XTGETTCAP, DECRQSS) as ordinary output on the tty, so they end up in
 * the session's replay ring buffer verbatim. Replaying them later makes the
 * client terminal (xterm.js or the native view) *answer* them again — and the
 * answer is delivered to the shell's stdin as if the user typed it, which is
 * how fragments like `2cfe2cfe` (pieces of an OSC `rgb:…` color reply) end up
 * on the prompt after a reconnect or restore.
 *
 * [ReplaySanitizer.stripQueries] is called on the replay paths only
 * ([TerminalSession.snapshot] and the restored-scrollback ingestion in
 * [TerminalSession]'s init); the live output stream is never filtered, so a
 * running program's query still reaches the attached client and gets its
 * answer while the program is actually waiting for one.
 *
 * @see se.soderbjorn.lunamux.TerminalSession
 */
package se.soderbjorn.lunamux.pty

import java.io.ByteArrayOutputStream

/**
 * One-shot, pure byte-level filter that removes *complete* terminal query
 * sequences from a buffer of recorded PTY output.
 *
 * Called by [se.soderbjorn.lunamux.TerminalSession] on the two replay choke
 * points: [se.soderbjorn.lunamux.TermSession.snapshot] (every reconnect /
 * attach replay, and — because the scrollback saver persists `snapshot()` —
 * every newly persisted blob) and the constructor path that re-ingests a
 * blob persisted by an older server version.
 *
 * Deliberately *not* a streaming scanner (contrast [OscScanner]): it runs on
 * a fully materialized ring copy, so it can classify sequences with simple
 * bounded lookahead and needs no cross-call state.
 */
internal object ReplaySanitizer {

    /**
     * Terminator scan cap for OSC / DCS payloads. A well-formed query payload
     * is tiny; anything longer (e.g. an inline-image DCS) is not a query, so
     * past this bound the sequence introducer is copied through verbatim and
     * scanning resumes inside the payload.
     */
    private const val STRING_SEQUENCE_SCAN_CAP = 4096

    /**
     * Return a copy of [bytes] with every *complete* query sequence removed.
     *
     * Two boundary rules keep the filter safe on ring-buffer data:
     *  - A buffer that *starts* mid-sequence (the ring overwrote the head) is
     *    passed through untouched — without its `ESC` introducer the tail can
     *    never be recognized as a query by a client parser, so it is inert.
     *  - An *incomplete trailing* sequence is preserved verbatim: the live
     *    stream delivers the remaining bytes right after the replay, and the
     *    (still running) program that emitted the query is legitimately
     *    waiting for the answer.
     *
     * The operation is idempotent: output never contains a strippable
     * sequence.
     *
     * @param bytes recorded PTY output (raw ring contents or a persisted blob).
     * @return the input minus complete query sequences; the same content
     *   byte-for-byte where no queries are present.
     */
    fun stripQueries(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b != ESC) {
                out.write(b.toInt())
                i++
                continue
            }
            if (i + 1 >= bytes.size) {
                // Lone trailing ESC — incomplete, preserve.
                out.write(b.toInt())
                i++
                continue
            }
            when (bytes[i + 1]) {
                CSI_INTRODUCER -> i = consumeCsi(bytes, i, out)
                OSC_INTRODUCER -> i = consumeOsc(bytes, i, out)
                DCS_INTRODUCER -> i = consumeDcs(bytes, i, out)
                else -> {
                    // Any other escape (ESC c, ESC 7, ESC >, ESC \ …) is never
                    // a query: copy the ESC and let the main loop handle the
                    // rest as ordinary bytes.
                    out.write(b.toInt())
                    i++
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Consume a CSI sequence starting at [start] (`ESC [`), writing it to
     * [out] unless it classifies as a query. CSI structure per ECMA-48:
     * parameter bytes `0x30..0x3F`, intermediate bytes `0x20..0x2F`, one
     * final byte `0x40..0x7E`.
     *
     * @return the index of the first byte after the consumed input.
     */
    private fun consumeCsi(bytes: ByteArray, start: Int, out: ByteArrayOutputStream): Int {
        var i = start + 2
        val paramsStart = i
        while (i < bytes.size && bytes[i].toInt() in 0x30..0x3F) i++
        val paramsEnd = i
        val intermediatesStart = i
        while (i < bytes.size && bytes[i].toInt() in 0x20..0x2F) i++
        val intermediatesEnd = i
        if (i >= bytes.size) return copyThrough(bytes, start, bytes.size, out)
        val final = bytes[i].toInt()
        if (final !in 0x40..0x7E) {
            // Malformed CSI — copy what we scanned and resume after it.
            return copyThrough(bytes, start, i, out)
        }
        val end = i + 1
        val hasIntermediates = intermediatesEnd > intermediatesStart
        val singleIntermediate =
            if (intermediatesEnd - intermediatesStart == 1) bytes[intermediatesStart].toInt() else -1
        val hasGtPrefix = paramsEnd > paramsStart && bytes[paramsStart] == '>'.code.toByte()
        val isBareQuestionMark = paramsEnd - paramsStart == 1 &&
            bytes[paramsStart] == '?'.code.toByte()
        val isQuery = when (final) {
            // DSR / CPR / DECXCPR requests (ESC[5n, ESC[6n, ESC[?6n, ESC[?15n …).
            'n'.code -> !hasIntermediates
            // DA1/DA2/DA3 (ESC[c, ESC[0c, ESC[>c, ESC[=c). Device-attribute
            // *responses* travel terminal→host, so a `c` final in PTY output
            // is always a request.
            'c'.code -> !hasIntermediates
            // XTVERSION (ESC[>q / ESC[>0q). DECSCUSR (ESC[4 q) has a space
            // intermediate and must survive.
            'q'.code -> !hasIntermediates && hasGtPrefix
            // DECRQM (ESC[?2004$p / ESC[4$p). DECSCL (ESC[62"p) has a `"`
            // intermediate and must survive.
            'p'.code -> singleIntermediate == '$'.code
            // Kitty keyboard-protocol query is exactly ESC[?u; ESC[u (SCORC)
            // and the push/pop/set forms must survive.
            'u'.code -> !hasIntermediates && isBareQuestionMark
            else -> false
        }
        if (!isQuery) copyThrough(bytes, start, end, out)
        return end
    }

    /**
     * Consume an OSC sequence starting at [start] (`ESC ]`), writing it to
     * [out] unless any `;`-separated payload segment is exactly `?` — the
     * xterm "report instead of set" convention used by the color queries
     * (OSC 4/5/10..19) and the clipboard read (OSC 52 with a `?` data field).
     *
     * @return the index of the first byte after the consumed input.
     */
    private fun consumeOsc(bytes: ByteArray, start: Int, out: ByteArrayOutputStream): Int {
        val payloadStart = start + 2
        val terminator = findStringTerminator(bytes, payloadStart)
            ?: return copyThrough(bytes, start, minOf(start + 2, bytes.size), out)
        if (terminator.payloadEnd < 0) {
            // Incomplete at buffer end — preserve verbatim.
            return copyThrough(bytes, start, bytes.size, out)
        }
        val isQuery = hasBareQuestionMarkSegment(bytes, payloadStart, terminator.payloadEnd)
        if (!isQuery) copyThrough(bytes, start, terminator.end, out)
        return terminator.end
    }

    /**
     * Consume a DCS sequence starting at [start] (`ESC P`). Only the two
     * query forms are stripped — XTGETTCAP (`ESC P + q … ST`) and DECRQSS
     * (`ESC P $ q … ST`), where the intermediate immediately follows the
     * introducer. Everything else (sixel `ESC P Ps ; … q`, etc.) is copied.
     *
     * @return the index of the first byte after the consumed input.
     */
    private fun consumeDcs(bytes: ByteArray, start: Int, out: ByteArrayOutputStream): Int {
        if (start + 3 >= bytes.size) {
            // Too short to classify — incomplete, preserve.
            return copyThrough(bytes, start, bytes.size, out)
        }
        val marker = bytes[start + 2].toInt()
        val isQueryForm = (marker == '+'.code || marker == '$'.code) &&
            bytes[start + 3] == 'q'.code.toByte()
        if (!isQueryForm) {
            // Not a query DCS: copy the introducer and resume inside the
            // payload (which is then copied as ordinary bytes).
            return copyThrough(bytes, start, start + 2, out)
        }
        val terminator = findStringTerminator(bytes, start + 4)
            ?: return copyThrough(bytes, start, start + 2, out)
        if (terminator.payloadEnd < 0) {
            // Incomplete at buffer end — preserve verbatim.
            return copyThrough(bytes, start, bytes.size, out)
        }
        return terminator.end
    }

    /**
     * Result of scanning for an OSC/DCS string terminator: [payloadEnd] is the
     * index of the terminator's first byte (or -1 when the buffer ended before
     * one was found), [end] the index just past the full terminator.
     */
    private class Terminator(val payloadEnd: Int, val end: Int)

    /**
     * Scan forward from [from] for a BEL or ST (`ESC \`) terminator, bounded
     * by [STRING_SEQUENCE_SCAN_CAP].
     *
     * @return the terminator location; a [Terminator] with `payloadEnd = -1`
     *   when the buffer ends first; or null when the cap is exceeded (caller
     *   should treat the sequence as opaque non-query content).
     */
    private fun findStringTerminator(bytes: ByteArray, from: Int): Terminator? {
        val cap = minOf(bytes.size, from + STRING_SEQUENCE_SCAN_CAP)
        var i = from
        while (i < cap) {
            when {
                bytes[i] == BEL -> return Terminator(i, i + 1)
                bytes[i] == ESC && i + 1 < bytes.size && bytes[i + 1] == '\\'.code.toByte() ->
                    return Terminator(i, i + 2)
                // A lone ESC at the very end could be the first half of ST.
                bytes[i] == ESC && i + 1 >= bytes.size -> return Terminator(-1, bytes.size)
                else -> i++
            }
        }
        return if (i >= bytes.size) Terminator(-1, bytes.size) else null
    }

    /**
     * True when any `;`-separated segment of `bytes[from, until)` is exactly
     * the single character `?`.
     */
    private fun hasBareQuestionMarkSegment(bytes: ByteArray, from: Int, until: Int): Boolean {
        var segStart = from
        var i = from
        while (i <= until) {
            if (i == until || bytes[i] == ';'.code.toByte()) {
                if (i - segStart == 1 && bytes[segStart] == '?'.code.toByte()) return true
                segStart = i + 1
            }
            i++
        }
        return false
    }

    /** Copy `bytes[from, until)` into [out] and return [until]. */
    private fun copyThrough(bytes: ByteArray, from: Int, until: Int, out: ByteArrayOutputStream): Int {
        out.write(bytes, from, until - from)
        return until
    }

    private const val ESC = 0x1B.toByte()
    private const val BEL = 0x07.toByte()
    private val CSI_INTRODUCER = '['.code.toByte()
    private val OSC_INTRODUCER = ']'.code.toByte()
    private val DCS_INTRODUCER = 'P'.code.toByte()
}
