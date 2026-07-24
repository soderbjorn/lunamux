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
 * Consumers subscribe to [PtySocket.events] for the ordered stream of output
 * bytes + size changes and call [PtySocket.send] to forward user keystrokes.
 * Resize notifications are sent via [PtySocket.resize] (or
 * [PtySocket.forceResize] to override multi-client min-aggregation).
 *
 * @see LunamuxClient.openPtySocket
 * @see se.soderbjorn.lunamux.client.viewmodel.TerminalBackingViewModel
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A single transport event from the remote PTY, delivered in the exact order
 * the server produced it. Collapsing output bytes and size changes into one
 * ordered stream is what keeps a resize and the redraw bytes it triggers from
 * racing: a [Size] followed by the program's repaint [Bytes] must reach the
 * renderer in that order, or the emulator repaints at the wrong width and the
 * grid mangles.
 *
 * @see PtySocket.events
 */
sealed interface PtyEvent {
    /**
     * A frame of raw PTY output bytes — the ring-buffer replay first, then live
     * output. Not a `data class`: [ByteArray] has identity equality, so a data
     * class would advertise a misleading `equals`, and events are never compared.
     */
    class Bytes(val data: ByteArray) : PtyEvent

    /**
     * The server's authoritative PTY grid, plus [maxReplayCols] — the widest
     * width represented in the server's replay history. A renderer that feeds
     * an emulator ratchets its grid width up to at least [maxReplayCols] so wide
     * replayed content is never reinterpreted narrower (and rewrapped lossily);
     * 0 means "no hint" (older server / empty history).
     */
    data class Size(val cols: Int, val rows: Int, val maxReplayCols: Int = 0) : PtyEvent

    /**
     * A reconnect boundary: the server is about to replay the whole ring buffer,
     * so the renderer should reset (feed RIS `ESC c` to its emulator and
     * re-apply its theme) before the replay bytes that follow, instead of
     * appending a duplicate transcript. A genuine server-side `reset` is NOT
     * this — it still arrives as `ESC c` inside a [Bytes] frame.
     */
    data object Reset : PtyEvent

    /**
     * Whether this client is the session's *driving* client, as decided by the
     * server. The driver's size vote governs the PTY and it renders 1:1; every
     * other attached client presents a passive, scaled mirror.
     *
     * Ordered with [Bytes] and [Size] on purpose: a client that learned it had
     * gone passive only after applying a redraw authored for the new driver's
     * width would render one frame at the wrong presentation and visibly correct
     * itself.
     *
     * @property driving true when this client governs the session.
     * @property governed false when *no* client governs yet (a freshly restored
     *   session nobody has touched, or the governor just disconnected). Consumers
     *   fall back to comparing their natural width against the server's while
     *   this is false — see [PtyPresentation.isPassive].
     */
    data class Governance(val driving: Boolean, val governed: Boolean) : PtyEvent
}

/**
 * Live connection to a terminal session. Consumers:
 *   - subscribe to [events] for the ordered stream from the remote PTY: output
 *     [PtyEvent.Bytes] (the first frames are the 64 KB ring-buffer replay, then
 *     live output), authoritative [PtyEvent.Size] changes, and a
 *     [PtyEvent.Reset] marker before a reconnect's replay — all interleaved in
 *     the order the server produced them;
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

    /**
     * The ordered stream of transport events (output bytes, size changes, and
     * reconnect resets). This is the single source of truth for a renderer that
     * feeds an emulator: apply each event in arrival order so a size change and
     * the repaint it provokes never race. See [PtyEvent].
     */
    val events: SharedFlow<PtyEvent>

    /**
     * Latest authoritative PTY size as reported by the server. Null until the
     * first size frame arrives. A conflated [StateFlow] purely for consumers
     * that only want the *current* size (pane headers, sidebar entries) and do
     * not feed an emulator — those get the value immediately on a late
     * subscribe. Renderers that feed an emulator must instead read
     * [PtyEvent.Size] off [events] so the resize stays ordered with the bytes.
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
