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
    fun classifierTreatsMouseAndFocusReportsAsAmbient() {
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;10;5M".encodeToByteArray()))   // SGR wheel
        assertTrue(PtyPresentation.isAmbientReport("$esc[<0;3;4m".encodeToByteArray()))      // SGR release
        assertTrue(PtyPresentation.isAmbientReport("$esc[I".encodeToByteArray()))            // focus in
        assertTrue(PtyPresentation.isAmbientReport("$esc[O".encodeToByteArray()))            // focus out
        assertTrue(PtyPresentation.isAmbientReport("$esc[M   ".encodeToByteArray()))         // X10 mouse + 3 bytes
        // A burst of several ambient reports is still ambient.
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;1;1M$esc[<64;1;2M".encodeToByteArray()))
    }

    @Test
    fun classifierRecognisesDeviceReplies() {
        // Replies the emulator generates by itself when the remote program probes it.
        assertTrue(PtyPresentation.isDeviceReply("$esc[24;80R".encodeToByteArray()))          // cursor position
        assertTrue(PtyPresentation.isDeviceReply("$esc[?62;c".encodeToByteArray()))           // device attributes
        assertTrue(PtyPresentation.isDeviceReply("$esc[>0;10;1c".encodeToByteArray()))        // secondary DA
        assertTrue(PtyPresentation.isDeviceReply("$esc[?2004;1\$y".encodeToByteArray()))      // mode report
        assertTrue(PtyPresentation.isDeviceReply("$esc]10;rgb:abab/cdcd/0000\u0007".encodeToByteArray())) // OSC reply (BEL)
        assertTrue(PtyPresentation.isDeviceReply("$esc]11;rgb:0/0/0$esc\\".encodeToByteArray()))          // OSC reply (ST)
        assertTrue(PtyPresentation.isDeviceReply("$esc[24;80R$esc[?62;c".encodeToByteArray()))// a burst of them
        // DSR-5 and the XTWINOPS window reports. Missing these was what made an idle mirror's
        // answers read as real typing: the mirror was promoted to size governor and the
        // resulting take-over produced a SIGWINCH repaint storm, caused by nothing but the
        // terminal answering a question it was asked.
        assertTrue(PtyPresentation.isDeviceReply("$esc[0n".encodeToByteArray()))               // DSR-5 "ok"
        assertTrue(PtyPresentation.isDeviceReply("$esc[3n".encodeToByteArray()))               // DSR-5 "not ok"
        assertTrue(PtyPresentation.isDeviceReply("$esc[8;24;80t".encodeToByteArray()))         // XTWINOPS text-area size
        assertTrue(PtyPresentation.isDeviceReply("$esc[6;16;8t".encodeToByteArray()))          // XTWINOPS cell size
        assertTrue(PtyPresentation.isDeviceReply("$esc[4;384;640t".encodeToByteArray()))       // XTWINOPS pixel size
        assertTrue(PtyPresentation.isDeviceReply("$esc[0n$esc[8;24;80t".encodeToByteArray()))  // a mixed burst
    }

    @Test
    fun classifierDoesNotMistakeInputForADeviceReply() {
        assertFalse(PtyPresentation.isDeviceReply("a".encodeToByteArray()))            // printable
        assertFalse(PtyPresentation.isDeviceReply("\r".encodeToByteArray()))           // Enter
        assertFalse(PtyPresentation.isDeviceReply("$esc[A".encodeToByteArray()))       // up arrow
        assertFalse(PtyPresentation.isDeviceReply("$esc[<64;10;5M".encodeToByteArray()))// mouse report
        assertFalse(PtyPresentation.isDeviceReply("$esc]10;rgb:0/0/0".encodeToByteArray())) // unterminated OSC
        assertFalse(PtyPresentation.isDeviceReply(ByteArray(0)))
        // A reply followed by real typing is not purely a reply.
        assertFalse(PtyPresentation.isDeviceReply("$esc[24;80Rls".encodeToByteArray()))
    }

    @Test
    fun classifierTreatsRealInputAsNonAmbient() {
        assertFalse(PtyPresentation.isAmbientReport("a".encodeToByteArray()))                // printable
        assertFalse(PtyPresentation.isAmbientReport("$esc[A".encodeToByteArray()))           // up arrow (CSI)
        assertFalse(PtyPresentation.isAmbientReport("${esc}OA".encodeToByteArray()))         // up arrow (app-cursor SS3)
        assertFalse(PtyPresentation.isAmbientReport("\r".encodeToByteArray()))               // Enter
        assertFalse(PtyPresentation.isAmbientReport("\u0003".encodeToByteArray()))           // Ctrl-C
        assertFalse(PtyPresentation.isAmbientReport(ByteArray(0)))                     // nothing
        // Mixed: an ambient report followed by a real keystroke is a take-over.
        assertFalse(PtyPresentation.isAmbientReport("$esc[<64;1;1Ma".encodeToByteArray()))
    }
}
