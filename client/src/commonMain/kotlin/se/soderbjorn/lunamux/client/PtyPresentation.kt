/**
 * Shared, platform-independent presentation logic for the server-authoritative
 * screen model. This file contains [PtyPresentation] (the passive-mirror mode
 * machine, scale/font math, and the ambient-report byte classifier) and
 * [ptyConnectQuery] (the `/pty` connect-URL query builder).
 *
 * With the server synthesizing a width-correct redraw for each client, a client
 * is in one of two presentation modes:
 *  - DRIVING — the server's grid matches the width this client would naturally
 *    render at, so it renders 1:1 and its input governs the PTY;
 *  - PASSIVE — the server's grid is a different width (another client is
 *    driving), so this client renders a read-only, scaled *mirror* of the
 *    server grid and drops the ambient escape reports its own view generates
 *    (mouse/focus) rather than letting them steal the grid.
 *
 * All of it is pure and unit-tested (PtyPresentationTest) so every platform
 * (Android/web/iOS) shares one definition of "passive", one scale formula, and
 * one classifier for "is this real input or an ambient report".
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.TerminalInputClassifier

/**
 * Build the query suffix appended to the authenticated `/pty/{id}` URL (which
 * already carries `?token=…`).
 *
 * `cols`/`rows` let the server synthesize the attach redraw at the grid this
 * client actually renders — the inversion the server-authoritative model needs
 * (mirrors how `posture` already rides the URL). Omitted when [grid] is null
 * (the server then synthesizes at the current PTY dims).
 *
 * @param posture `"viewer"` or `"driver"` (see server `readClientPosture`).
 * @param grid the client's current (cols, rows), or null to let the server choose.
 * @return the query suffix, e.g. `"&posture=viewer&cols=80&rows=24"`.
 */
internal fun ptyConnectQuery(posture: String, grid: Pair<Int, Int>?): String {
    val base = "&posture=$posture"
    return if (grid != null && grid.first > 0 && grid.second > 0) {
        "$base&cols=${grid.first}&rows=${grid.second}"
    } else {
        base
    }
}

/**
 * Pure presentation helpers shared by all client renderers. No platform types —
 * callers translate the results into font sizes, CSS transforms or Compose
 * scale.
 */
object PtyPresentation {

    /** Minimum mirror scale; below this glyphs are unreadable, so we clip instead. */
    const val MIN_SCALE: Float = 0.45f

    private const val ESC: Byte = 0x1b
    private const val BEL: Byte = 0x07

    /**
     * Whether this client should present a passive mirror rather than drive.
     *
     * Governance is a *server* decision — the arbiter picks the most recently
     * active driver — so when the server has told us ([driving] non-null) that
     * answer is used verbatim. The width comparison remains only as the fallback
     * for the ungoverned case: a freshly restored session nobody has touched yet,
     * a governor that just disconnected, or a server too old to send the signal.
     *
     * The fallback is a proxy, not a definition, and it is wrong in two ways the
     * server signal fixes: two clients that happen to render at the same width
     * both conclude they are driving, and governance moving *between* two
     * same-width clients changes nothing about the widths, so it is invisible.
     *
     * @param naturalCols the width this client would render at using the user's
     *   own font and viewport (never the passive-fitted width).
     * @param serverCols the server's current authoritative grid width.
     * @param driving the server's verdict for this client, or null when no client
     *   governs (or the server never said).
     * @return true when this client should mirror rather than drive. In the
     *   fallback path, rows are ignored: only a cols change rewraps, so a
     *   rows-only difference does not make us passive.
     */
    fun isPassive(naturalCols: Int, serverCols: Int, driving: Boolean? = null): Boolean {
        if (driving != null) return !driving
        return serverCols > 0 && naturalCols > 0 && serverCols != naturalCols
    }

    /**
     * The scale to render the server grid at so its [serverCols] columns fit in
     * the width this client would natively use for [naturalCols] columns.
     * Clamped to [MIN_SCALE]..1: a server grid narrower than ours letterboxes at
     * scale 1; a much wider one bottoms out at [MIN_SCALE] and the renderer clips.
     *
     * @param naturalCols this client's own-font/viewport width.
     * @param serverCols the server grid width to fit.
     * @return the scale factor in [MIN_SCALE, 1].
     */
    fun fitScale(naturalCols: Int, serverCols: Int): Float {
        if (serverCols <= 0 || naturalCols <= 0) return 1f
        val raw = naturalCols.toFloat() / serverCols.toFloat()
        return raw.coerceIn(MIN_SCALE, 1f)
    }

    /**
     * Passive mirror font size: the user's font scaled so the server grid fits
     * the space this client's own grid occupied at [userFontSize], floored at
     * [floorPx] (below the floor the renderer clips the right/bottom edges
     * rather than shrinking further).
     *
     * Fits **both axes** when rows are supplied: a mirror renders the server
     * grid pinned cell-for-cell (cols *and* rows — the stream is absolutely
     * cursor-addressed for exactly that screen), so a server screen taller than
     * this client's natural one must shrink the font vertically too, or its
     * bottom rows — the prompt, the newest output — render outside the view
     * with no way to scroll to them. Rows default to 0 (ignored) for callers
     * that only fit width.
     *
     * @param userFontSize the user's chosen (driving) font size in px/pt.
     * @param naturalCols this client's own-font/viewport width.
     * @param serverCols the server grid width being mirrored.
     * @param floorPx smallest legible font size.
     * @param naturalRows this client's own-font/viewport height, or 0 to fit
     *   width only.
     * @param serverRows the server grid height being mirrored, or 0 to fit
     *   width only.
     * @return the mirror font size, ≥ [floorPx].
     */
    fun passiveFontSize(
        userFontSize: Float,
        naturalCols: Int,
        serverCols: Int,
        floorPx: Float,
        naturalRows: Int = 0,
        serverRows: Int = 0,
    ): Float {
        if (serverCols <= 0 || naturalCols <= 0) return userFontSize
        var ratio = naturalCols.toFloat() / serverCols.toFloat()
        if (serverRows > 0 && naturalRows > 0) {
            ratio = minOf(ratio, naturalRows.toFloat() / serverRows.toFloat())
        }
        return maxOf(userFontSize * ratio, floorPx)
    }

    /**
     * Whether [bytes] is composed *entirely* of ambient terminal reports — SGR
     * (`ESC [ < … M/m`) or X10 (`ESC [ M …`) mouse reports and focus in/out
     * (`ESC [ I` / `ESC [ O`). A passive mirror emits these from scrolling and
     * focus changes; they must be dropped, not sent, and must NOT count as the
     * user "taking over".
     *
     * Any real input — printable chars, arrows (`ESC [ A`), app-cursor keys
     * (`ESC O …`), Enter, control chars — makes this false, so it is treated as
     * a take-over gesture.
     *
     * @param bytes one input burst from the terminal view.
     * @return true only if every byte belongs to an ambient mouse/focus report.
     */
    /**
     * Whether [bytes] is composed entirely of *device replies* the local emulator
     * generated by itself in answer to a query the remote program sent — cursor
     * position (`CSI … R`), device attributes (`CSI ? … c` / `CSI > … c`), mode
     * reports (`CSI ? … $ y`), and OSC/DCS replies (e.g. a colour query answered
     * with `OSC 10 ; rgb:… ST`).
     *
     * These travel out through the same write path as keystrokes but are **not
     * user intent**: treating them as such made a passive phone silently seize the
     * PTY whenever the running program happened to probe the terminal. They must
     * still be *sent* (the program is blocked waiting for the answer) — unlike the
     * ambient mouse/focus reports of [isAmbientReport], which are dropped.
     *
     * @param bytes one outbound burst from the local emulator.
     * @return true only if every byte belongs to a device reply.
     */
    fun isDeviceReply(bytes: ByteArray): Boolean =
        TerminalInputClassifier.isDeviceReply(bytes)

    fun isAmbientReport(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val n = bytes.size
        var i = 0
        while (i < n) {
            if (bytes[i] != ESC) return false
            if (i + 1 >= n || bytes[i + 1] != '['.code.toByte()) return false
            i += 2
            if (i >= n) return false
            when (bytes[i]) {
                'I'.code.toByte(), 'O'.code.toByte() -> i += 1        // focus in / out
                'M'.code.toByte() -> {                                 // X10 mouse: ESC[M + 3 bytes
                    i += 1
                    if (i + 3 > n) return false
                    i += 3
                }
                '<'.code.toByte() -> {                                 // SGR mouse: ESC[<d;d;d(M|m)
                    i += 1
                    while (i < n && (bytes[i] in '0'.code.toByte()..'9'.code.toByte() || bytes[i] == ';'.code.toByte())) i++
                    if (i >= n || (bytes[i] != 'M'.code.toByte() && bytes[i] != 'm'.code.toByte())) return false
                    i += 1
                }
                else -> return false
            }
        }
        return true
    }
}
