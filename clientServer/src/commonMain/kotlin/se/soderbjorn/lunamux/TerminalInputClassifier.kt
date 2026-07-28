/**
 * Classification of bytes travelling *from* a terminal client *to* the PTY.
 *
 * Not everything a terminal writes is a person typing. An emulator answers the
 * running program's queries by itself — cursor position, device attributes, mode
 * and colour reports — and those answers ride the same channel as keystrokes.
 * Anything that treats "bytes arrived from this client" as "the user is active on
 * this client" therefore misreads a terminal talking to itself as human intent.
 *
 * That distinction decides PTY size governance, so the rule lives here, in the
 * module both the clients and the server share, rather than being restated (and
 * drifting) on either side.
 *
 * @see se.soderbjorn.lunamux.client.PtyPresentation the client-side presentation
 *   wrapper that also drops ambient mouse/focus reports while mirroring
 */
package se.soderbjorn.lunamux

/**
 * Recogniser for terminal-generated replies, shared by every client and the server.
 */
object TerminalInputClassifier {

    private const val ESC: Byte = 0x1b
    private const val BEL: Byte = 0x07

    /**
     * Whether [bytes] consists *entirely* of device replies the emulator generated
     * itself in answer to a query from the running program — cursor position
     * (`CSI … R`), device attributes (`CSI ? … c` / `CSI > … c`), mode reports
     * (`CSI ? … $ y`), device status (`CSI 0 n`, the DSR-5 "terminal ok" answer),
     * window reports (`CSI … t`, the XTWINOPS size/position answers) and OSC/DCS
     * replies (e.g. a colour query answered with `OSC 10 ; rgb:… ST`).
     *
     * These are never user intent. Callers use this to keep them from counting as
     * activity: on the client that means not seizing the grid, on the server that
     * means not recording the sender as the most recently active client for size
     * arbitration. The server additionally *drops* them, because the canonical grid
     * is the session's single answerer — a client's copy of the answer would be a
     * duplicate the shell reads as typed input.
     *
     * The `n` and `t` finals were originally missing, which is what made an idle
     * mirror's DSR-5 and XTWINOPS reports read as real typing: that promoted the
     * mirror to size governor and triggered a spurious take-over, i.e. a SIGWINCH
     * repaint storm caused by the terminal answering a question.
     *
     * Conservative by construction: any byte that is not part of a complete,
     * properly terminated reply makes the whole burst false, so real typing (which
     * may be appended to a reply in one frame) is never misclassified as ambient.
     *
     * @param bytes one outbound burst from a terminal.
     * @return true only if every byte belongs to a device reply; false for an empty
     *   burst, real input, or an unterminated sequence.
     */
    fun isDeviceReply(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val n = bytes.size
        var i = 0
        while (i < n) {
            if (bytes[i] != ESC || i + 1 >= n) return false
            when (bytes[i + 1]) {
                '['.code.toByte() -> {                       // CSI … final
                    var j = i + 2
                    while (j < n && (bytes[j] in '0'.code.toByte()..'9'.code.toByte() ||
                            bytes[j] == ';'.code.toByte() || bytes[j] == '?'.code.toByte() ||
                            bytes[j] == '>'.code.toByte() || bytes[j] == '$'.code.toByte())
                    ) j++
                    if (j >= n) return false
                    val f = bytes[j]
                    // R = cursor position report, c = device attributes, y = mode report,
                    // n = device status report (DSR), t = window report (XTWINOPS).
                    if (f != 'R'.code.toByte() && f != 'c'.code.toByte() && f != 'y'.code.toByte() &&
                        f != 'n'.code.toByte() && f != 't'.code.toByte()
                    ) return false
                    i = j + 1
                }
                ']'.code.toByte(), 'P'.code.toByte() -> {     // OSC / DCS … BEL or ST
                    var j = i + 2
                    var terminated = false
                    while (j < n) {
                        if (bytes[j] == BEL) { j++; terminated = true; break }
                        if (bytes[j] == ESC && j + 1 < n && bytes[j + 1] == '\\'.code.toByte()) {
                            j += 2; terminated = true; break
                        }
                        j++
                    }
                    if (!terminated) return false
                    i = j
                }
                else -> return false
            }
        }
        return true
    }
}
