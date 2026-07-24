package se.soderbjorn.lunamux.pty

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TEMPORARY. Confirms the [PtyTrace] binary format round-trips exactly — raw bytes, a large
 * multi-KB chunk, and resizes interleaved in order — so a device capture is trustworthy
 * before a run is spent producing one. Remove with [PtyTrace].
 */
class PtyTraceSelfTest {

    @Test
    fun roundTrip() {
        val f = File.createTempFile("selftrace", ".bin")
        try {
            val trace = PtyTrace(f.absolutePath)
            // Raw ESC + control bytes must survive with no escaping or charset round-trip.
            val chunk = byteArrayOf(0x1b, '['.code.toByte(), '3'.code.toByte(), '1'.code.toByte(),
                'm'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 0x0d, 0x0a)
            trace.recordResize(80, 24)
            trace.recordOutput(chunk, chunk.size)
            trace.recordResize(67, 40)
            val bigger = ByteArray(9000) { (it % 256).toByte() } // spans the 4 KB read buffer
            trace.recordOutput(bigger, bigger.size)

            val events = PtyTrace.read(f)
            assertEquals(4, events.size)
            assertEquals(80 to 24, (events[0] as PtyTraceEvent.Resize).let { it.cols to it.rows })
            assertTrue((events[1] as PtyTraceEvent.Output).bytes.contentEquals(chunk))
            assertEquals(67 to 40, (events[2] as PtyTraceEvent.Resize).let { it.cols to it.rows })
            assertTrue((events[3] as PtyTraceEvent.Output).bytes.contentEquals(bigger))
        } finally {
            f.delete()
        }
    }
}
