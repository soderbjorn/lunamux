/**
 * TEMPORARY DIAGNOSTIC. A faithful, replayable capture of one PTY session's byte stream,
 * with resizes interleaved in order.
 *
 * This file contains [PtyTrace] (the recorder) and [PtyTraceEvent] (one decoded record).
 * It exists so the take-over-duplication behaviour can be reproduced offline, byte for
 * byte, instead of against a stylised fixture. The reconciliation rule was first written
 * against a synthetic full-screen repaint and matched the wrong thing on device; a trace
 * of what a real program (Claude Code) actually emits turns "guess what it does" into a
 * unit test that replays exactly what it did.
 *
 * Format: a length-prefixed binary log, so raw output survives exactly — no escaping, no
 * charset round-trip. Each record is `[1 byte tag][4 byte big-endian length][payload]`:
 *
 *  - tag `O` (output): payload is the raw chunk as read from the PTY.
 *  - tag `R` (resize): payload is `"<cols>x<rows>"` in ASCII.
 *
 * Armed by `LUNAMUX_PTY_TRACE=<path>` (or the `lunamux.ptyTrace` system property, which is
 * the dependable one under `:server:run` — see the note on `SizeChurnLog`). A no-op when
 * unset. Remove this file together with `SizeChurnLog` before upstream.
 *
 * @see se.soderbjorn.lunamux.TerminalSession the read loop and applySize that call this
 */
package se.soderbjorn.lunamux.pty

import java.io.DataInputStream
import java.io.File

/** One decoded record from a [PtyTrace] file. */
sealed interface PtyTraceEvent {
    /** A chunk of raw PTY output, exactly as the program emitted it. */
    class Output(val bytes: ByteArray) : PtyTraceEvent

    /** An effective PTY resize to [cols]×[rows]. */
    class Resize(val cols: Int, val rows: Int) : PtyTraceEvent
}

/**
 * Records a session's PTY stream to a file for offline replay.
 *
 * One instance per session. Thread-safe: [recordOutput] runs on the PTY read coroutine and
 * [recordResize] on whatever coroutine applied the size, so both synchronize on the shared
 * output stream. Appends are best-effort — a diagnostic must never take a session down — so
 * a write failure is swallowed after the first.
 *
 * @param filePath where to write, defaulting to the process-wide [path] the env var/system
 *   property resolves to. Passed explicitly by tests so they do not depend on class-load
 *   ordering against that static (a load before the property is set would strand it at null).
 */
class PtyTrace(filePath: String? = path) {

    private val out = filePath?.let { runCatching { File(it).outputStream().buffered() }.getOrNull() }

    /**
     * Record a raw output chunk.
     *
     * @param buf the read buffer; only `[0, len)` is captured.
     * @param len valid byte count; ≤ 0 is a no-op.
     */
    fun recordOutput(buf: ByteArray, len: Int) {
        if (len <= 0) return
        write('O'.code, buf, len)
    }

    /**
     * Record an effective resize, in order with the surrounding output.
     *
     * @param cols new columns.
     * @param rows new rows.
     */
    fun recordResize(cols: Int, rows: Int) {
        val payload = "${cols}x$rows".toByteArray(Charsets.US_ASCII)
        write('R'.code, payload, payload.size)
    }

    private fun write(tag: Int, buf: ByteArray, len: Int) {
        val stream = out ?: return
        synchronized(stream) {
            runCatching {
                stream.write(tag)
                stream.write((len ushr 24) and 0xff)
                stream.write((len ushr 16) and 0xff)
                stream.write((len ushr 8) and 0xff)
                stream.write(len and 0xff)
                stream.write(buf, 0, len)
                stream.flush()
            }
        }
    }

    companion object {
        private val path: String? =
            System.getProperty("lunamux.ptyTrace") ?: System.getenv("LUNAMUX_PTY_TRACE")

        /**
         * Decode a trace file into an ordered list of events.
         *
         * Used by tests to replay a captured session through [SessionGrid]. Reads the
         * length-prefixed records until EOF.
         *
         * @param file the trace file written by a [PtyTrace].
         * @return the events in the order they were recorded.
         */
        fun read(file: File): List<PtyTraceEvent> {
            val events = mutableListOf<PtyTraceEvent>()
            DataInputStream(file.inputStream().buffered()).use { din ->
                while (true) {
                    val tag = din.read()
                    if (tag < 0) break
                    val len = din.readInt()
                    val payload = ByteArray(len)
                    din.readFully(payload)
                    events += when (tag.toChar()) {
                        'O' -> PtyTraceEvent.Output(payload)
                        'R' -> {
                            val (c, r) = String(payload, Charsets.US_ASCII).split("x")
                            PtyTraceEvent.Resize(c.toInt(), r.toInt())
                        }
                        else -> error("unknown PtyTrace tag: $tag")
                    }
                }
            }
            return events
        }
    }
}
