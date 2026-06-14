/**
 * Demo-mode [PtySocket]: streams a [DemoTerminalSession]'s canned scrollback
 * and live simulated output to the consumer, and feeds typed bytes back into
 * the session's line discipline. No network involved.
 *
 * @see PtySocket
 * @see DemoTerminalSession
 * @see se.soderbjorn.termtastic.client.TermtasticClient.openPtySocket
 */
package se.soderbjorn.termtastic.client.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.termtastic.client.PtySocket

/**
 * The PTY channel of demo mode. On creation it attaches to the simulated
 * session and mirrors its output (snapshot first, then live frames) into
 * [output] — same contract as the real socket's ring-buffer replay.
 *
 * Resize behaves like a single-client server: whatever grid the client
 * reports is echoed back as the authoritative PTY size, so no out-of-bounds
 * overlay ever shows in the demo.
 *
 * @param sessionId the demo session to attach to.
 * @param server the in-process simulation that owns the sessions.
 * @param scope the client's long-lived coroutine scope for the mirror loop.
 */
class DemoPtySocket internal constructor(
    override val sessionId: String,
    server: DemoServer,
    private val scope: CoroutineScope,
) : PtySocket {
    private val session: DemoTerminalSession = server.session(sessionId)

    private val _output = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    override val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    private val _ptySize = MutableStateFlow<Pair<Int, Int>?>(null)
    override val ptySize: StateFlow<Pair<Int, Int>?> = _ptySize.asStateFlow()

    /** Mirrors session output into [_output]; cancelled by [close]. */
    private val mirrorJob: Job = scope.launch {
        session.output().collect { _output.emit(it) }
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun send(bytes: ByteArray) {
        session.input(bytes)
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun resize(cols: Int, rows: Int) {
        _ptySize.value = Pair(cols, rows)
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun forceResize(cols: Int, rows: Int) {
        _ptySize.value = Pair(cols, rows)
    }

    @Throws(CancellationException::class, Exception::class)
    override suspend fun close() {
        mirrorJob.cancel()
    }

    override fun closeDetached() {
        scope.launch { close() }
    }
}
