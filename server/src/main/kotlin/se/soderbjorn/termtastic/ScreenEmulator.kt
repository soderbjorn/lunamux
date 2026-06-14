/**
 * Headless terminal emulator wrapper for server-side screen state.
 *
 * This file contains [ScreenEmulator], which wraps the JediTerm terminal
 * emulation library to maintain a virtual screen grid that mirrors what
 * xterm.js renders in the browser. It processes raw PTY byte output into
 * rendered screen lines so that [StateDetector] and [ClaudeUsageMonitor] can
 * scan the actual visible text rather than the raw byte history.
 *
 * Each [TerminalSession] owns one instance (fed from the PTY read loop),
 * and [ClaudeUsageMonitor] creates a separate instance for its hidden
 * Claude CLI session.
 *
 * @see TerminalSession
 * @see StateDetector
 * @see ClaudeUsageMonitor
 */
package se.soderbjorn.termtastic

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDataStream
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Headless VT/xterm emulator that processes PTY output into a screen grid so
 * [snapshotVisibleText] returns what's currently visible rather than the raw
 * byte history.
 *
 * This is the server-side equivalent of what xterm.js does in the browser,
 * and it exists because AI CLI state markers (e.g. Claude Code's
 * "esc to interrupt" footer) are typically emitted once per working phase and
 * then cursor-positioned around. Ink-style diff-based reconciliation means the
 * footer is never re-written until a resize or re-render forces a full redraw.
 * Regexing the raw byte stream loses the footer as soon as streaming tokens
 * push it out of the ring buffer — the rendered grid keeps it for as long as
 * it is visually on screen.
 *
 * Thread safety: [feed], [resize] and [snapshotVisibleText] all acquire the
 * underlying [TerminalTextBuffer]'s lock, which JediTerm also uses internally
 * for its own mutations. That guarantees a consistent view regardless of which
 * thread calls which method.
 */
class ScreenEmulator(initialCols: Int, initialRows: Int) {
    private val display = NoopDisplay()
    private val styleState = StyleState()
    private val textBuffer = TerminalTextBuffer(
        initialCols.coerceAtLeast(1),
        initialRows.coerceAtLeast(1),
        styleState
    )
    private val terminal = JediTerminal(display, textBuffer, styleState)
    private val stream = QueueDataStream()
    private val emulator = JediEmulator(stream, terminal)

    // Persistent UTF-8 decoder + carry-over byte buffer so multi-byte
    // codepoints split across PTY reads don't produce mojibake. The decoder
    // keeps internal state for partial sequences; the byte buffer handles the
    // tail-bytes case via compact().
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pendingBytes: ByteBuffer = ByteBuffer.allocate(16 * 1024)
    private val charBuf: CharBuffer = CharBuffer.allocate(16 * 1024)

    /**
     * Feed [length] bytes of raw PTY output into the emulator. Safe to call
     * from the PTY read coroutine. Returns after all parseable chars have been
     * applied to the screen buffer; any trailing partial UTF-8 codepoint is
     * kept for the next call.
     */
    fun feed(bytes: ByteArray, length: Int) {
        if (length <= 0) return
        textBuffer.lock()
        try {
            var offset = 0
            while (offset < length) {
                val room = pendingBytes.remaining()
                if (room == 0) {
                    // Pending is full without making progress — shouldn't
                    // happen in practice because compact() always frees up
                    // room unless 16 KB of pure partial codepoint bytes
                    // arrive, which is impossible for UTF-8 (max 4 bytes per
                    // codepoint). Drop the buffer defensively.
                    pendingBytes.clear()
                }
                val take = minOf(pendingBytes.remaining(), length - offset)
                pendingBytes.put(bytes, offset, take)
                offset += take

                pendingBytes.flip()
                charBuf.clear()
                decoder.decode(pendingBytes, charBuf, false)
                charBuf.flip()
                if (charBuf.hasRemaining()) {
                    stream.append(charBuf)
                }
                // Compact keeps any bytes that the decoder left unconsumed
                // (i.e. a partial multi-byte codepoint at the tail) at the
                // start of the buffer for the next feed.
                pendingBytes.compact()

                drain()
            }
        } finally {
            textBuffer.unlock()
        }
    }

    /** Run the emulator loop until the internal char stream is exhausted. */
    private fun drain() {
        emulator.resetEof()
        try {
            while (emulator.hasNext()) {
                emulator.next()
            }
        } catch (_: Throwable) {
            // A malformed control sequence must not kill the PTY read loop.
            // Reset EOF so subsequent feeds can resume.
            emulator.resetEof()
        }
    }

    /**
     * Resize the emulator's screen grid. Should be called whenever the real
     * PTY is resized so cursor-addressing in subsequent input matches.
     */
    fun resize(cols: Int, rows: Int) {
        if (cols < 1 || rows < 1) return
        textBuffer.lock()
        try {
            terminal.resize(TermSize(cols, rows), RequestOrigin.User)
        } catch (_: Throwable) {
            // Resize races are benign; the next feed will settle the layout.
        } finally {
            textBuffer.unlock()
        }
    }

    /**
     * Return the current rendered screen as a plain string, one row per line.
     * This is the moral equivalent of xterm.js's per-line `translateToString`
     * scan that the original client-side state detector used.
     */
    fun snapshotVisibleText(): String {
        textBuffer.lock()
        try {
            return textBuffer.getScreenLines()
        } finally {
            textBuffer.unlock()
        }
    }

    /**
     * A no-op [TerminalDisplay]. JediTerm calls these methods from the
     * emulator thread (our feed call) to notify a UI. We don't have a UI —
     * the [TerminalTextBuffer] is our only interesting state and it is
     * updated independently by [JediTerminal] itself.
     */
    private class NoopDisplay : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) {}
        override fun setCursorShape(shape: CursorShape?) {}
        override fun beep() {}
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {}
        override fun setCursorVisible(visible: Boolean) {}
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {}
        override fun getWindowTitle(): String = ""
        override fun setWindowTitle(name: String) {}
        override fun getSelection(): TerminalSelection? = null
        override fun terminalMouseModeSet(mode: MouseMode) {}
        override fun setMouseFormat(format: MouseFormat) {}
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }

    /**
     * A [TerminalDataStream] backed by a FIFO of char chunks. [append] pushes
     * chars at the tail (called from [feed] after UTF-8 decoding); [getChar]
     * pulls one char at a time from the head. When the queue drains we throw
     * [TerminalDataStream.EOF], which [DataStreamIteratingEmulator] catches
     * and uses to stop iterating. [drain] then [JediEmulator.resetEof]s so the
     * next feed can resume.
     */
    private class QueueDataStream : TerminalDataStream {
        private val chunks = ArrayDeque<CharArray>()
        private var headIndex = 0

        fun append(buf: CharBuffer) {
            if (!buf.hasRemaining()) return
            val arr = CharArray(buf.remaining())
            buf.get(arr)
            chunks.addLast(arr)
        }

        override fun getChar(): Char {
            while (chunks.isNotEmpty()) {
                val head = chunks.first()
                if (headIndex < head.size) {
                    return head[headIndex++]
                }
                chunks.removeFirst()
                headIndex = 0
            }
            throw TerminalDataStream.EOF()
        }

        override fun pushChar(c: Char) {
            // Back up inside the current head chunk if possible, else prepend
            // a 1-char chunk. JediEmulator uses this for 1-char lookahead.
            if (chunks.isNotEmpty() && headIndex > 0) {
                headIndex--
                chunks.first()[headIndex] = c
                return
            }
            chunks.addFirst(charArrayOf(c))
            headIndex = 0
        }

        // headIndex is a single cursor shared across chunks. Before prepending
        // new data we must "commit" that cursor into the current head chunk by
        // slicing off its already-consumed prefix, otherwise once the prepended
        // chunk drains the next removeFirst()+headIndex=0 will re-read the old
        // head from the start. JediEmulator calls pushBackBuffer when it
        // encounters unrecognised CSI sequences (e.g. ESC[>4m, ESC[<u from
        // Claude Code's startup), so this bug showed up as an infinite
        // re-parse loop spamming "Unhandled Control Sequence" until the PTY
        // read coroutine was wedged.
        private fun compactHead() {
            if (chunks.isEmpty() || headIndex == 0) return
            val head = chunks.first()
            if (headIndex >= head.size) {
                chunks.removeFirst()
            } else {
                chunks[0] = head.copyOfRange(headIndex, head.size)
            }
            headIndex = 0
        }

        override fun readNonControlCharacters(maxChars: Int): String {
            // Fast path JediEmulator uses for runs of printable text. Stop at
            // the first control character and push it back.
            val sb = StringBuilder()
            while (sb.length < maxChars) {
                val c = try {
                    getChar()
                } catch (_: TerminalDataStream.EOF) {
                    break
                }
                if (c.code < 0x20 || c.code == 0x7F) {
                    pushChar(c)
                    break
                }
                sb.append(c)
            }
            return sb.toString()
        }

        override fun pushBackBuffer(bytes: CharArray, len: Int) {
            if (len <= 0) return
            compactHead()
            chunks.addFirst(bytes.copyOf(len))
            headIndex = 0
        }

        override fun isEmpty(): Boolean {
            if (chunks.isEmpty()) return true
            val head = chunks.first()
            return chunks.size == 1 && headIndex >= head.size
        }
    }
}
