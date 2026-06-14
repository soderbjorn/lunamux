/**
 * OSC 7 (current working directory) escape sequence scanner.
 *
 * This file contains [Osc7Scanner], a streaming state-machine parser that
 * watches raw PTY byte output for OSC 7 escape sequences
 * (`ESC ] 7 ; file://<host>/<path> ST`) and invokes a callback with the
 * decoded path each time one is found for the local host.
 *
 * Each [TerminalSession] creates one scanner instance, feeding it from the
 * PTY read loop. The callback updates the session's [TerminalSession.cwd]
 * StateFlow, which [WindowState.updatePaneCwd] uses to keep the pane title
 * and file-browser root directory in sync.
 *
 * @see ShellInitFiles
 * @see ProcessCwdReader
 * @see TerminalSession
 */
package se.soderbjorn.termtastic.pty

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Streaming parser that watches a PTY byte stream for OSC 7 cwd reports
 * (`ESC ] 7 ; file://<host>/<urlencoded-path> ST` where ST is `BEL` or `ESC \`)
 * and invokes [onCwd] each time it sees one for the local host.
 *
 * Designed to be fed in arbitrary chunks: the state machine carries any partial
 * sequence across [feed] calls so a sequence that straddles a buffer boundary
 * still parses correctly.
 *
 * Bytes are NOT removed from the stream — terminal emulators render OSC
 * sequences invisibly anyway, and stripping them would break other consumers
 * (e.g. iTerm2 integration scrolling, font escapes) sharing the same OSC range.
 */
internal class Osc7Scanner(private val onCwd: (String) -> Unit) {

    private enum class State { IDLE, ESC, OSC, OSC_ESC }

    private var state = State.IDLE
    private val buf = StringBuilder()

    /**
     * Feed [len] bytes from [chunk] into the state machine. Any complete
     * OSC 7 sequences found will trigger the [onCwd] callback with the
     * decoded path. Partial sequences are carried across calls.
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
                    0x5D /* ] */ -> { state = State.OSC; buf.setLength(0) }
                    0x1B -> Unit // stay in ESC
                    else -> state = State.IDLE
                }
                State.OSC -> when (b) {
                    0x07 /* BEL */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> state = State.OSC_ESC
                    else -> {
                        if (buf.length < MAX_BUF) {
                            buf.append(b.toChar())
                        } else {
                            // Runaway sequence — abort and resync.
                            buf.setLength(0)
                            state = State.IDLE
                        }
                    }
                }
                State.OSC_ESC -> when (b) {
                    0x5C /* \ */ -> { finishOsc(); state = State.IDLE }
                    0x1B -> { buf.setLength(0); state = State.ESC }
                    else -> { buf.setLength(0); state = State.IDLE }
                }
            }
            i++
        }
    }

    /**
     * Called when the state machine sees a complete OSC sequence (terminated
     * by BEL or ST). Checks if it is an OSC 7 with a `file://` URL for
     * the local host, and if so invokes [onCwd] with the decoded path.
     */
    private fun finishOsc() {
        val payload = buf.toString()
        buf.setLength(0)
        if (!payload.startsWith("7;")) return
        val (host, path) = parseFileUrl(payload.substring(2)) ?: return
        if (host.isNotEmpty() && host != "localhost" && host != localHostname) return
        if (path.isNotEmpty()) onCwd(path)
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

        private val localHostname: String by lazy {
            runCatching { InetAddress.getLocalHost().hostName }.getOrNull().orEmpty()
        }
    }
}
