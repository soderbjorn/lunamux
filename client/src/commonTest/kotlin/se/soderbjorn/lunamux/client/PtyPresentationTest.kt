/**
 * Tests for [PtyPresentation] and [ptyConnectQuery] — the shared passive-mirror
 * mode machine, scale/font math, ambient-report classifier, and connect-URL
 * builder that Android/web/iOS all depend on.
 */
package se.soderbjorn.lunamux.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtyPresentationTest {

    private val esc = "\u001b"

    @Test
    fun connectQueryIncludesGridWhenPresent() {
        assertEquals("&posture=viewer&cols=80&rows=24", ptyConnectQuery("viewer", 80 to 24))
        assertEquals("&posture=driver", ptyConnectQuery("driver", null))
        // Degenerate dims are dropped so the server falls back to PTY dims.
        assertEquals("&posture=viewer", ptyConnectQuery("viewer", 0 to 24))
    }

    @Test
    fun isPassiveFallsBackToColsMismatchWhenUngoverned() {
        // No server verdict (nobody has driven yet, or an older server): the width
        // comparison is all there is.
        assertFalse(PtyPresentation.isPassive(naturalCols = 80, serverCols = 80))
        assertTrue(PtyPresentation.isPassive(naturalCols = 80, serverCols = 200))
        assertTrue(PtyPresentation.isPassive(naturalCols = 40, serverCols = 80))
        // Unknown widths never force passive.
        assertFalse(PtyPresentation.isPassive(naturalCols = 0, serverCols = 80))
        assertFalse(PtyPresentation.isPassive(naturalCols = 80, serverCols = 0))
    }

    @Test
    fun serverVerdictOverridesTheWidthComparison() {
        // The case the fallback cannot express: two clients rendering at the same
        // width, one of them driving. Widths are equal, so the comparison says
        // "driving" for both — the server says otherwise for the mirror.
        assertTrue(PtyPresentation.isPassive(naturalCols = 80, serverCols = 80, driving = false))
        assertFalse(PtyPresentation.isPassive(naturalCols = 80, serverCols = 80, driving = true))

        // And the converse: a driver whose width has not been applied yet (its
        // vote is still in flight) must not flip itself to passive in the gap.
        assertFalse(PtyPresentation.isPassive(naturalCols = 40, serverCols = 200, driving = true))
        assertTrue(PtyPresentation.isPassive(naturalCols = 40, serverCols = 200, driving = false))
    }

    @Test
    fun fitScaleClampsBetweenFloorAndOne() {
        // Server grid narrower than ours → letterbox at 1.
        assertEquals(1f, PtyPresentation.fitScale(naturalCols = 120, serverCols = 80))
        // Twice as wide → half scale.
        assertEquals(0.5f, PtyPresentation.fitScale(naturalCols = 40, serverCols = 80))
        // Far wider → clamped at the floor.
        assertEquals(PtyPresentation.MIN_SCALE, PtyPresentation.fitScale(naturalCols = 10, serverCols = 400))
    }

    @Test
    fun passiveFontSizeScalesAndFloors() {
        assertEquals(8f, PtyPresentation.passiveFontSize(16f, naturalCols = 100, serverCols = 200, floorPx = 6f))
        // Below the floor → clamped.
        assertEquals(6f, PtyPresentation.passiveFontSize(16f, naturalCols = 20, serverCols = 400, floorPx = 6f))
        // Narrower server grid → no upscale beyond user size is fine (ratio > 1).
        assertTrue(PtyPresentation.passiveFontSize(16f, naturalCols = 200, serverCols = 100, floorPx = 6f) >= 16f)
    }

    @Test
    fun classifierTreatsMouseAndFocusReportsAsAmbient() {
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;10;5M".toByteArray()))   // SGR wheel
        assertTrue(PtyPresentation.isAmbientReport("$esc[<0;3;4m".toByteArray()))      // SGR release
        assertTrue(PtyPresentation.isAmbientReport("$esc[I".toByteArray()))            // focus in
        assertTrue(PtyPresentation.isAmbientReport("$esc[O".toByteArray()))            // focus out
        assertTrue(PtyPresentation.isAmbientReport("$esc[M   ".toByteArray()))         // X10 mouse + 3 bytes
        // A burst of several ambient reports is still ambient.
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;1;1M$esc[<64;1;2M".toByteArray()))
    }

    @Test
    fun classifierRecognisesDeviceReplies() {
        // Replies the emulator generates by itself when the remote program probes it.
        assertTrue(PtyPresentation.isDeviceReply("$esc[24;80R".toByteArray()))          // cursor position
        assertTrue(PtyPresentation.isDeviceReply("$esc[?62;c".toByteArray()))           // device attributes
        assertTrue(PtyPresentation.isDeviceReply("$esc[>0;10;1c".toByteArray()))        // secondary DA
        assertTrue(PtyPresentation.isDeviceReply("$esc[?2004;1\$y".toByteArray()))      // mode report
        assertTrue(PtyPresentation.isDeviceReply("$esc]10;rgb:abab/cdcd/0000\u0007".toByteArray())) // OSC reply (BEL)
        assertTrue(PtyPresentation.isDeviceReply("$esc]11;rgb:0/0/0$esc\\".toByteArray()))          // OSC reply (ST)
        assertTrue(PtyPresentation.isDeviceReply("$esc[24;80R$esc[?62;c".toByteArray()))// a burst of them
    }

    @Test
    fun classifierDoesNotMistakeInputForADeviceReply() {
        assertFalse(PtyPresentation.isDeviceReply("a".toByteArray()))            // printable
        assertFalse(PtyPresentation.isDeviceReply("\r".toByteArray()))           // Enter
        assertFalse(PtyPresentation.isDeviceReply("$esc[A".toByteArray()))       // up arrow
        assertFalse(PtyPresentation.isDeviceReply("$esc[<64;10;5M".toByteArray()))// mouse report
        assertFalse(PtyPresentation.isDeviceReply("$esc]10;rgb:0/0/0".toByteArray())) // unterminated OSC
        assertFalse(PtyPresentation.isDeviceReply(ByteArray(0)))
        // A reply followed by real typing is not purely a reply.
        assertFalse(PtyPresentation.isDeviceReply("$esc[24;80Rls".toByteArray()))
    }

    @Test
    fun classifierTreatsRealInputAsNonAmbient() {
        assertFalse(PtyPresentation.isAmbientReport("a".toByteArray()))                // printable
        assertFalse(PtyPresentation.isAmbientReport("$esc[A".toByteArray()))           // up arrow (CSI)
        assertFalse(PtyPresentation.isAmbientReport("${esc}OA".toByteArray()))         // up arrow (app-cursor SS3)
        assertFalse(PtyPresentation.isAmbientReport("\r".toByteArray()))               // Enter
        assertFalse(PtyPresentation.isAmbientReport("\u0003".toByteArray()))           // Ctrl-C
        assertFalse(PtyPresentation.isAmbientReport(ByteArray(0)))                     // nothing
        // Mixed: an ambient report followed by a real keystroke is a take-over.
        assertFalse(PtyPresentation.isAmbientReport("$esc[<64;1;1Ma".toByteArray()))
    }
}
