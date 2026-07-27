/**
 * Unit tests for [streamAttach] — the `/pty` attach ordering and seq gating —
 * driven against a controllable fake [TermSession] so the frame order is asserted
 * without a real WebSocket or PTY:
 *  - a declared grid (?cols/?rows) is registered via setClientSize before attach;
 *  - the very first frames are the attach Size, this connection's governance
 *    verdict, then the redraw binary — governance BEFORE the redraw so a client
 *    never paints a frame under the wrong presentation;
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

    /** A [TermSession] whose event stream and attach payload the test drives directly. */
    private class FakeSession(private var attach: AttachPayload) : TermSession {
        private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 64)
        override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()
        override val output: SharedFlow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override val cwd: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val programTitle: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
        override val sizeEvents: StateFlow<Pair<Int, Int>> = MutableStateFlow(80 to 24).asStateFlow()

        val setClientSizeCalls = mutableListOf<Triple<String, Int, Int>>()

        suspend fun emit(ev: SessionEvent) = _events.emit(ev)

        override fun attachPayload(): AttachPayload = attach
        override fun bytesWritten(): Long = 0
        override fun write(bytes: ByteArray) {}
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
    fun `attach sends Size then redraw, then gates live events`() = runBlocking(Dispatchers.Default) {
        val fake = FakeSession(AttachPayload(seq = 5, cols = 80, rows = 24, bytes = "REDRAW".toByteArray()))
        val frames = mutableListOf<Frame>()

        val job = launch { fake.streamAttach("c1", qCols = 80, qRows = 24) { frames.add(it) } }
        delay(120) // let the subscription register and onSubscription send the attach frames

        // The declared grid was registered before attach.
        assertEquals(listOf(Triple("c1", 80, 24)), fake.setClientSizeCalls)

        // First frames: attach Size, governance verdict, then the redraw binary. The
        // verdict precedes the redraw because it decides how that paint is presented.
        assertTrue(frames.size >= 3, "expected attach Size + governance + redraw, got ${frames.size}")
        assertEquals(80 to 24, sizeOf(frames[0]))
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
        // Empty redraw → only the Size + governance frames are sent.
        assertEquals(2, frames.size)
        assertEquals(120 to 32, sizeOf(frames[0]))

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
            governanceOf(driverFrames[1]),
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
            governanceOf(mirrorFrames[1]),
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
                governanceOf(frames[1]),
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

    private fun bytesOf(frame: Frame): String = (frame as Frame.Binary).readBytes().toString(Charsets.UTF_8)

    @Test
    fun `the driving client is not sent a resync but a mirror is`() = runBlocking(Dispatchers.Default) {
        // The driver is at the PTY's width, so the program's native output is already correct
        // for it; a synthesized resync would only overwrite that clean render with the
        // server's reconstruction. A mirror, at a different width, needs it.
        val driver = FakeSession(
            AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
        )
        val driverFrames = mutableListOf<Frame>()
        val j1 = launch { driver.streamAttach("c1", null, null) { driverFrames.add(it) } }
        delay(120)
        var base = driverFrames.size
        driver.emit(SessionEvent.Output(seq = 2, bytes = "live".toByteArray(), resync = false))
        driver.emit(SessionEvent.Output(seq = 3, bytes = "RESYNC".toByteArray(), resync = true))
        delay(120)
        val driverLive = driverFrames.drop(base)
        assertEquals(1, driverLive.size, "driver must receive live output but not the resync")
        assertEquals("live", bytesOf(driverLive[0]))
        j1.cancel()

        val mirror = FakeSession(
            AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
        )
        val mirrorFrames = mutableListOf<Frame>()
        val j2 = launch { mirror.streamAttach("c2", null, null) { mirrorFrames.add(it) } }
        delay(120)
        base = mirrorFrames.size
        mirror.emit(SessionEvent.Output(seq = 2, bytes = "live".toByteArray(), resync = false))
        mirror.emit(SessionEvent.Output(seq = 3, bytes = "RESYNC".toByteArray(), resync = true))
        delay(120)
        val mirrorLive = mirrorFrames.drop(base)
        assertEquals(2, mirrorLive.size, "mirror must receive both live output and the resync")
        assertEquals("RESYNC", bytesOf(mirrorLive[1]))
        j2.cancel()
    }

    @Test
    fun `a resync is withheld only while this client governs, and resumes when it does not`() =
        runBlocking(Dispatchers.Default) {
            // c2 starts as a mirror (c1 governs), then takes over, then loses it again.
            val fake = FakeSession(
                AttachPayload(seq = 1, cols = 80, rows = 24, bytes = ByteArray(0), governorClientId = "c1")
            )
            val frames = mutableListOf<Frame>()
            val job = launch { fake.streamAttach("c2", null, null) { frames.add(it) } }
            delay(120)

            // Mirror now: resync delivered.
            var base = frames.size
            fake.emit(SessionEvent.Output(seq = 2, bytes = "R1".toByteArray(), resync = true))
            delay(120)
            assertEquals(1, frames.size - base, "a mirror gets the resync")

            // c2 takes over: resync withheld.
            fake.emit(SessionEvent.Governance(seq = 3, governorClientId = "c2"))
            base = frames.size
            fake.emit(SessionEvent.Output(seq = 4, bytes = "R2".toByteArray(), resync = true))
            delay(120)
            // Only the governance frame from seq 3 should have arrived, not the resync.
            assertEquals(1, frames.size - base, "the take-over frame only; the resync is withheld")
            assertEquals(
                PtyServerMessage.Governance(driving = true, governed = true),
                governanceOf(frames.last()),
            )

            // c1 reclaims: c2 is a mirror again, resync resumes.
            fake.emit(SessionEvent.Governance(seq = 5, governorClientId = "c1"))
            base = frames.size
            fake.emit(SessionEvent.Output(seq = 6, bytes = "R3".toByteArray(), resync = true))
            delay(120)
            assertEquals(2, frames.size - base, "governance frame + the now-delivered resync")
            assertEquals("R3", bytesOf(frames.last()))
            job.cancel()
        }
}
