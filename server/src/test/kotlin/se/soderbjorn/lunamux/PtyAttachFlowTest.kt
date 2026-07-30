/**
 * Unit tests for [streamAttach] — the `/pty` attach ordering and seq gating —
 * driven against a controllable fake [TermSession] so the frame order is asserted
 * without a real WebSocket or PTY:
 *  - a declared grid (?cols/?rows) is registered via setClientSize before attach;
 *  - the very first frames are this connection's governance verdict, the attach
 *    Size, then the redraw binary — governance BEFORE the Size because clients
 *    decide mirror-vs-driving inside their Size handler, and both before the
 *    redraw so a client never paints a frame under the wrong presentation;
 *  - live events already folded into the attach payload (seq ≤ attach.seq) are
 *    dropped, and only later events (Output, Size and Governance) are forwarded;
 *  - a broadcast governance id is rendered as a per-connection boolean, so a
 *    client learns whether *it* drives and never another client's identity.
 */
package se.soderbjorn.lunamux

import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.pty.ClientPosture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PtyAttachFlowTest {

    private val esc = "\u001b"

    /** A [TermSession] whose event stream and attach payload the test drives directly. */
    private class FakeSession(private var attach: AttachPayload) : TermSession {
        private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 64)
        override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
        override val output: SharedFlow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override val cwd: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val programTitle: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val sizeEvents: StateFlow<Pair<Int, Int>> = MutableStateFlow(80 to 24).asStateFlow()

        val setClientSizeCalls = mutableListOf<Triple<String, Int, Int>>()

        /** Everything that reached the PTY, and everything counted as client activity. */
        val written = mutableListOf<ByteArray>()
        val activityFrom = mutableListOf<String>()

        suspend fun emit(ev: SessionEvent) = _events.emit(ev)

        override fun attachPayload(): AttachPayload = attach
        override fun bytesWritten(): Long = 0
        override fun write(bytes: ByteArray) { written.add(bytes) }
        override fun noteClientInput(clientId: String) { activityFrom.add(clientId) }
        override fun resetTerminalModes() {}
        override fun shutdown() {}
        override fun setClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {
            setClientSizeCalls.add(Triple(clientId, cols, rows))
        }
        override fun forceClientSize(clientId: String, cols: Int, rows: Int, priority: SizePriority) {}
        override fun removeClient(clientId: String) {}
        override fun detectState(): SessionState? = null
        override fun transcriptText(): String = ""
        override fun persistSnapshot(): ByteArray = ByteArray(0)
        override fun screenText(): String = ""
        override fun isProcessAlive(): Boolean = true
    }

    private fun sizeOf(frame: Frame): Pair<Int, Int> {
        val msg = windowJson.decodeFromString<PtyServerMessage>((frame as Frame.Text).readText())
        val size = msg as PtyServerMessage.Size
        return size.cols to size.rows
    }

    private fun governanceOf(frame: Frame): PtyServerMessage.Governance =
        windowJson.decodeFromString<PtyServerMessage>((frame as Frame.Text).readText())
            as PtyServerMessage.Governance

    @Test
    fun `attach sends Governance, Size, redraw, then gates live events`() = runBlocking(Dispatchers.Default) {
        val fake = FakeSession(AttachPayload(seq = 5, cols = 80, rows = 24, bytes = "REDRAW".toByteArray()))
        val frames = mutableListOf<Frame>()

        val job = launch { fake.streamAttach("c1", qCols = 80, qRows = 24) { frames.add(it) } }
        delay(120) // let the subscription register and onSubscription send the attach frames

        // The declared grid was registered before attach.
        assertEquals(listOf(Triple("c1", 80, 24)), fake.setClientSizeCalls)

        // First frames: governance verdict, attach Size, then the redraw binary. The
        // verdict precedes the Size because clients decide mirror-vs-driving in their
        // Size handler; both precede the redraw because they decide how it is presented.
        assertTrue(frames.size >= 3, "expected governance + attach Size + redraw, got ${frames.size}")
        assertEquals(PtyServerMessage.Governance(driving = false, governed = false), governanceOf(frames[0]))
        assertEquals(80 to 24, sizeOf(frames[1]))
        assertEquals("REDRAW", (frames[2] as Frame.Binary).readBytes().toString(Charsets.UTF_8))

        // Live events: one already covered by the attach (skipped), then newer ones.
        fake.emit(SessionEvent.Output(seq = 4, bytes = "stale".toByteArray()))   // ≤ 5 → dropped
        fake.emit(SessionEvent.Output(seq = 6, bytes = "fresh".toByteArray()))   // > 5 → sent
        fake.emit(SessionEvent.Size(seq = 7, cols = 100, rows = 30))             // > 5 → sent
        delay(120)

        val live = frames.drop(3)
        assertEquals(2, live.size, "stale event must be gated out")
        assertEquals("fresh", (live[0] as Frame.Binary).readBytes().toString(Charsets.UTF_8))
        assertEquals(100 to 30, sizeOf(live[1]))

        job.cancel()
    }

    @Test
    fun `no declared grid means no pre-attach vote`() = runBlocking(Dispatchers.Default) {
        val fake = FakeSession(AttachPayload(seq = 0, cols = 120, rows = 32, bytes = ByteArray(0)))
        val frames = mutableListOf<Frame>()

        val job = launch { fake.streamAttach("c2", qCols = null, qRows = null) { frames.add(it) } }
        delay(120)

        assertTrue(fake.setClientSizeCalls.isEmpty(), "absent ?cols/?rows must not vote")
        // Empty redraw → only the governance + Size frames are sent.
        assertEquals(2, frames.size)
        assertEquals(120 to 32, sizeOf(frames[1]))

        job.cancel()
    }

    @Test
    fun `attach reports governance relative to this connection`() = runBlocking(Dispatchers.Default) {
        // The governing client is "c1", so c1's own socket is told it drives...
        val driver = FakeSession(
            AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
        )
        val driverFrames = mutableListOf<Frame>()
        val j1 = launch { driver.streamAttach("c1", null, null) { driverFrames.add(it) } }
        delay(120)
        assertEquals(
            PtyServerMessage.Governance(driving = true, governed = true),
            governanceOf(driverFrames[0]),
        )
        j1.cancel()

        // ...while a second client attaching to the same session is told it does not.
        val mirror = FakeSession(
            AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
        )
        val mirrorFrames = mutableListOf<Frame>()
        val j2 = launch { mirror.streamAttach("c2", null, null) { mirrorFrames.add(it) } }
        delay(120)
        assertEquals(
            PtyServerMessage.Governance(driving = false, governed = true),
            governanceOf(mirrorFrames[0]),
        )
        j2.cancel()
    }

    @Test
    fun `an ungoverned session reports governed=false so the client falls back`() =
        runBlocking(Dispatchers.Default) {
            // Nobody has acted yet (a restored session nobody has touched). Reporting
            // driving=false here would wrongly freeze a lone client into a mirror — the
            // exact bug the width fallback exists to avoid — so say "ungoverned".
            val fake = FakeSession(
                AttachPayload(seq = 0, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = null)
            )
            val frames = mutableListOf<Frame>()
            val job = launch { fake.streamAttach("c1", null, null) { frames.add(it) } }
            delay(120)

            assertEquals(
                PtyServerMessage.Governance(driving = false, governed = false),
                governanceOf(frames[0]),
            )
            job.cancel()
        }

    @Test
    fun `a governance change is forwarded to each connection as its own verdict`() =
        runBlocking(Dispatchers.Default) {
            val fake = FakeSession(
                AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
            )
            val frames = mutableListOf<Frame>()
            val job = launch { fake.streamAttach("c2", null, null) { frames.add(it) } }
            delay(120)
            val before = frames.size

            // Governance moves to c2 — note this carries no Size at all, which is the
            // case the old width-comparison inference could not observe.
            fake.emit(SessionEvent.Governance(seq = 2, governorClientId = "c2"))
            delay(120)

            val live = frames.drop(before)
            assertEquals(1, live.size, "expected exactly one governance frame")
            assertEquals(
                PtyServerMessage.Governance(driving = true, governed = true),
                governanceOf(live[0]),
            )
            job.cancel()
        }

    @Test
    fun `a governance event already covered by the attach is gated out`() =
        runBlocking(Dispatchers.Default) {
            val fake = FakeSession(
                AttachPayload(seq = 5, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
            )
            val frames = mutableListOf<Frame>()
            val job = launch { fake.streamAttach("c1", null, null) { frames.add(it) } }
            delay(120)
            val before = frames.size

            fake.emit(SessionEvent.Governance(seq = 4, governorClientId = "c9")) // ≤ 5 → dropped
            delay(120)

            assertEquals(before, frames.size, "stale governance must be gated like any event")
            job.cancel()
        }

    // ── inbound: the server is the single answerer ─────────────────────────────

    @Test
    fun `real typing is written and counts as activity`() {
        val fake = FakeSession(AttachPayload(seq = 0, cols = 80, rows = 24, bytes = ByteArray(0)))

        acceptClientBytes(fake, "c1", "ls -la\r".toByteArray())

        assertEquals(1, fake.written.size)
        assertEquals("ls -la\r", fake.written[0].decodeToString())
        assertEquals(listOf("c1"), fake.activityFrom)
    }

    @Test
    fun `a client's device reply is dropped, never written to the PTY`() {
        // The canonical grid answers for the terminal. A client's own answer is a duplicate
        // of a reply that has exactly one correct value; forwarded, ZLE reads the surplus as
        // typed input and echoes it into canonical state.
        val fake = FakeSession(AttachPayload(seq = 0, cols = 80, rows = 24, bytes = ByteArray(0)))

        acceptClientBytes(fake, "c1", "$esc[24;80R".toByteArray())        // cursor position
        acceptClientBytes(fake, "c1", "$esc[0n".toByteArray())            // DSR-5
        acceptClientBytes(fake, "c1", "$esc[8;24;80t".toByteArray())      // XTWINOPS size
        acceptClientBytes(fake, "c1", "$esc[?62;c".toByteArray())         // device attributes
        acceptClientBytes(fake, "c1", "$esc]11;rgb:0/0/0$esc\\".toByteArray()) // OSC colour reply

        assertTrue(fake.written.isEmpty(), "device replies must not reach the PTY, got ${fake.written.size}")
        assertTrue(fake.activityFrom.isEmpty(), "nor count as the user acting on this client")
    }

    @Test
    fun `a reply with typing appended is not dropped`() {
        // The classifier is conservative on purpose: one burst carrying a reply AND real
        // keystrokes is real input, because dropping it would swallow what the user typed.
        val fake = FakeSession(AttachPayload(seq = 0, cols = 80, rows = 24, bytes = ByteArray(0)))

        acceptClientBytes(fake, "c1", "$esc[24;80Rls".toByteArray())

        assertEquals(1, fake.written.size, "a mixed burst is delivered whole")
        assertEquals(listOf("c1"), fake.activityFrom)
    }
}
