/**
 * Client-side abstraction over a single terminal session's byte stream: the
 * live bidirectional channel between the client's terminal renderer and a
 * (real or simulated) PTY.
 *
 * [PtySocket] is an interface so the transport can be swapped:
 *   - [RealPtySocket] talks WebSocket to `/pty/{sessionId}` on a live server
 *     (created via [LunamuxClient.openPtySocket]);
 *   - [se.soderbjorn.lunamux.client.demo.DemoPtySocket] streams canned
 *     scrollback and simulated command responses from the in-process
 *     [se.soderbjorn.lunamux.client.demo.DemoServer] (demo mode).
 *
 * Consumers subscribe to [PtySocket.output] for terminal output bytes and
 * call [PtySocket.send] to forward user keystrokes. Resize notifications are
 * sent via [PtySocket.resize] (or [PtySocket.forceResize] to override
 * multi-client min-aggregation).
 *
 * @see LunamuxClient.openPtySocket
 * @see se.soderbjorn.lunamux.client.viewmodel.TerminalBackingViewModel
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live connection to a terminal session. Consumers:
 *   - subscribe to [output] for binary frames from the remote PTY (the first
 *     frames are the 64 KB ring-buffer replay, then live output — the caller
 *     doesn't need to special-case them, it's just a stream of bytes to feed
 *     into whatever terminal renderer is in use);
 *   - call [send] to write raw user-input bytes back to the PTY;
 *   - call [resize] whenever the renderer's cell grid changes.
 *
 * Implementations: [RealPtySocket] (WebSocket transport) and
 * [se.soderbjorn.lunamux.client.demo.DemoPtySocket] (in-process
 * simulation).
 */
interface PtySocket {
    /** The PTY session this socket is attached to. */
    val sessionId: String

    /** Terminal output bytes: ring-buffer replay first, then live frames. */
    val output: SharedFlow<ByteArray>

    /**
     * Latest authoritative PTY size as reported by the server. Null until
     * the first size frame arrives. StateFlow so late subscribers (e.g. a
     * Compose `collectAsState` that binds after the socket's first frame)
     * see the current value.
     */
    val ptySize: StateFlow<Pair<Int, Int>?>

    /**
     * Write raw user-input bytes to the PTY. The server forwards these
     * directly to the PTY master file descriptor; the demo transport feeds
     * them to its command simulator.
     *
     * @param bytes the keystrokes or paste data to send.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun send(bytes: ByteArray)

    /**
     * Notify the server that the terminal renderer's grid size has changed.
     * The server aggregates sizes from all attached clients and applies the
     * minimum to the PTY via `TIOCSWINSZ`.
     *
     * @param cols new column count.
     * @param rows new row count.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun resize(cols: Int, rows: Int)

    /**
     * "Reformat" from the client's UI: ask the server to evict every
     * other attached client's size entry and pin the PTY to our
     * cols/rows. The next auto-resize those clients send re-enters them
     * into the min() aggregation, so this is a momentary override.
     *
     * @param cols new column count.
     * @param rows new row count.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun forceResize(cols: Int, rows: Int)

    /**
     * Gracefully close the connection and cancel any reader coroutine.
     * Safe to call multiple times.
     */
    @Throws(CancellationException::class, Exception::class)
    suspend fun close()

    /**
     * Mobile resume hook: if no output has been received for at least
     * [maxQuietMillis], drop and re-establish the connection. The server
     * replays the session's ring buffer on attach, and the real transport
     * prefixes the replay of a *re*-connect with a full terminal reset
     * (`ESC c`), so the renderer ends up showing exactly the current
     * server-side content — this is what makes a terminal screen refresh
     * after the phone slept or the app was backgrounded.
     *
     * Unlike `/window`, a PTY stream can be legitimately quiet (idle
     * shell), so a small threshold means "refresh unless output is
     * actively streaming". Default is a no-op (demo transport).
     *
     * @param maxQuietMillis quiet period after which the stream is
     *   re-established. Pass `0` to force a reconnect unconditionally.
     */
    fun reconnectIfStale(maxQuietMillis: Long) {}

    /**
     * Fire-and-forget variant of [close] that runs on the long-lived client
     * scope instead of whatever scope the caller happens to be in. Callers
     * in Android composables use `rememberCoroutineScope` which is cancelled
     * as the screen leaves composition — a `scope.launch { close() }` there
     * can be cancelled before the suspending close() ever reaches the
     * server, leaving the server-side socket handler blocked in its
     * `incoming` loop and the attached client's per-session dims pinned on
     * the server until the TCP connection finally times out.
     */
    fun closeDetached()
}
