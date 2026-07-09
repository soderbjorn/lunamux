/**
 * OSC (Operating System Command) escape sequence scanner.
 *
 * This file contains [OscScanner], a streaming state-machine parser that
 * watches raw PTY byte output for the OSC sequences Lunamux consumes:
 *  - **OSC 7** (`ESC ] 7 ; file://<host>/<path> ST`) — current-working-
 *    directory reports; the decoded path is delivered via a callback each
 *    time one is found for the local host.
 *  - **OSC 0 / OSC 2** (`ESC ] 0;<title> ST` / `ESC ] 2;<title> ST`) — the
 *    standard window-title sequence any program can emit (e.g. Claude Code's
 *    live task summary); the raw title is delivered via an optional second
 *    callback. OSC 1 (icon name only) is deliberately ignored.
 *
 * Each [TerminalSession] creates one scanner instance, feeding it from the
 * PTY read loop. The cwd callback updates the session's [TerminalSession.cwd]
 * StateFlow, which [WindowState.updatePaneCwd] uses to keep the pane title
 * and file-browser root directory in sync; the title callback feeds
 * [TerminalSession.programTitle], consumed (opt-in) by
 * [WindowState.applyProgramTitle] the same way.
 *
 * @see ShellInitFiles
 * @see ProcessCwdReader
 * @see TerminalSession
 */
package se.soderbjorn.lunamux.pty

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Streaming parser that watches a PTY byte stream for OSC 7 cwd reports
 * (`ESC ] 7 ; file://<host>/<urlencoded-path> ST` where ST is `BEL` or `ESC \`),
 * invoking [onCwd] each time it sees one for the local host, and for OSC 0/2
 * window-title sequences, invoking [onTitle] with the raw UTF-8 title text
 * (which may be empty — the standard way for a program to clear its title).
 *
 * Designed to be fed in arbitrary chunks: the state machine carries any partial
 * sequence across [feed] calls so a sequence that straddles a buffer boundary
 * still parses correctly.
 *
 * Bytes are NOT removed from the stream — terminal emulators render OSC
 * sequences invisibly anyway, and stripping them would break other consumers
 * (e.g. iTerm2 integration scrolling, font escapes) sharing the same OSC range.
 *
 * @property onCwd invoked with the decoded local path of each OSC 7 report.
 * @property onTitle invoked with the raw title of each OSC 0/2 sequence;
 *   `null` (the default) skips title reporting.
 */
internal class OscScanner(
    private val onCwd: (String) -> Unit,
    private val onTitle: ((String) -> Unit)? = null,
) {

    private enum class State { IDLE, ESC, OSC, OSC_ESC }

    private var state = State.IDLE

    // Plain array + length index (not a ByteArrayOutputStream) — this runs on
    // the PTY read loop for every byte of an in-flight OSC sequence, and BAOS
    // methods are synchronized.
    private val buf = ByteArray(MAX_BUF)
    private var bufLen = 0

    /**
     * Feed [len] bytes from [chunk] into the state machine. Any complete
     * OSC 7 sequence found triggers the [onCwd] callback with the decoded
     * path; any complete OSC 0/2 sequence triggers [onTitle] with the title.
     * Partial sequences are carried across calls.
     *
     * @param chunk raw PTY output bytes
     * @param len number of valid bytes in [chunk] (defaults to `chunk.size`)
     */
    fun feed(chunk: ByteArray, len: Int = chunk.size) {
        var i = 0
        while (i < len) {
            val b = chunk[i].toInt() and 0xFF
            when (state) {
                State.IDLE -> if (b == 0x1B) state = State.ESC
                State.ESC -> when (b) {
                    0x5D /* ] */ -> { state = State.OSC; bufLen = 0 }
                    0x1B -> Unit // stay in ESC
                    else -> state = State.IDLE
                }
                State.OSC -> when (b) {
                    0x07 /* BEL */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> state = State.OSC_ESC
                    else -> {
                        if (bufLen < MAX_BUF) {
                            buf[bufLen++] = b.toByte()
                        } else {
                            // Runaway sequence — abort and resync.
                            bufLen = 0
                            state = State.IDLE
                        }
                    }
                }
                State.OSC_ESC -> when (b) {
                    0x5C /* \ */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> { bufLen = 0; state = State.ESC }
                    else -> { bufLen = 0; state = State.IDLE }
                }
            }
            i++
        }
    }

    /**
     * Called when the state machine sees a complete OSC sequence (terminated
     * by BEL or ST) and dispatches it: OSC 7 with a `file://` URL for the
     * local host → [onCwd] with the decoded path; OSC 0/2 → [onTitle] with
     * the UTF-8 title text (possibly empty = "clear the title"). The command
     * prefix is checked on the raw bytes so uninteresting sequences (fonts,
     * clipboard, iTerm2 integration, …) cost no decode or allocation.
     */
    private fun finishOsc() {
        val len = bufLen
        bufLen = 0
        if (len < 2 || buf[1] != SEMICOLON) return
        when (buf[0].toInt()) {
            // The file URL is percent-encoded ASCII, so the byte-faithful
            // ISO-8859-1 view is exact; percentDecode reassembles UTF-8 from
            // the raw bytes.
            '7'.code -> {
                val (host, path) = parseFileUrl(String(buf, 2, len - 2, Charsets.ISO_8859_1)) ?: return
                if (host.isNotEmpty() && host != "localhost" && host != localHostname) return
                if (path.isNotEmpty()) onCwd(path)
            }
            // OSC 0 (icon + window title) and OSC 2 (window title); title
            // text is real UTF-8. OSC 1 (icon only) falls through, ignored.
            '0'.code, '2'.code -> onTitle?.invoke(String(buf, 2, len - 2, Charsets.UTF_8))
        }
    }

    /**
     * Parse a `file://host/path` URL into its host and path components.
     *
     * @param url the URL string (expected to start with `file://`)
     * @return a pair of (host, decoded-path), or null if the format is invalid
     */
    private fun parseFileUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("file://")) return null
        val rest = url.substring(7)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val host = rest.substring(0, slash)
        val path = percentDecode(rest.substring(slash))
        return host to path
    }

    /**
     * Decode percent-encoded characters (`%20` etc.) in [s].
     *
     * @param s the percent-encoded string
     * @return the decoded UTF-8 string
     */
    private fun percentDecode(s: String): String {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            // Non-ASCII shouldn't appear in a valid file URL, but be lenient.
            out.write(c.code and 0xFF)
            i++
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_BUF = 4096
        private const val SEMICOLON = ';'.code.toByte()

        private val localHostname: String by lazy {
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
        }
    }
}
