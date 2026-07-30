/**
 * Counts how many times the PTY actually resizes during take-over.
 *
 * Every effective size change is a SIGWINCH, and a SIGWINCH makes a live TUI repaint
 * its screen — which, for a program rendering into the normal buffer, appends another
 * copy to the scrollback. So duplicated output is not really a rendering bug: it is a
 * direct count of how many times the grid changed size. One resize per deliberate
 * take-over is the floor; anything above that is churn we inflicted.
 *
 * The reported symptom is ~one duplicated block per take-over on the current build
 * against ~one in ten on the pre-refactor build, so this pins the count rather than
 * reasoning about it.
 */
package se.soderbjorn.lunamux.pty

import se.soderbjorn.lunamux.SizePriority
import se.soderbjorn.lunamux.SizeVote
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeOverChurnTest {

    private fun normal(c: Int, r: Int) = SizeVote(c, r, SizePriority.NORMAL)

    private val laptop = 200 to 50
    private val phone = 60 to 40

    /**
     * A mirroring client re-votes its own natural grid (which it must, or a lone
     * client deadlocks). That vote must not move the PTY while another client is
     * actually driving, or the two clients fight and every flip is a repaint.
     */
    @Test
    fun `a mirror re-voting its natural grid does not move the PTY`() {
        val a = ClientSizeArbiter(laptop.first, laptop.second)
        a.setPosture("laptop", ClientPosture.DRIVER)
        a.setPosture("phone", ClientPosture.DRIVER)
        a.setSize("laptop", normal(laptop.first, laptop.second))

        // Phone takes over.
        a.forceSize("phone", normal(phone.first, phone.second))
        assertEquals(phone, a.effective)

        // The laptop is now a mirror. It keeps re-voting its natural grid on every
        // local relayout — font re-fit, container resize, tab activation.
        repeat(5) {
            assertEquals(
                null,
                a.setSize("laptop", normal(laptop.first, laptop.second)),
                "a mirror's vote must not resize the PTY while another client drives",
            )
        }
        assertEquals(phone, a.effective, "the phone must still govern")
    }

    /**
     * The headline count: N deliberate take-overs must cost exactly N resizes.
     */
    @Test
    fun `alternating take-over costs exactly one resize each`() {
        val a = ClientSizeArbiter(laptop.first, laptop.second)
        a.setPosture("laptop", ClientPosture.DRIVER)
        a.setPosture("phone", ClientPosture.DRIVER)
        a.setSize("laptop", normal(laptop.first, laptop.second))
        a.setSize("phone", normal(phone.first, phone.second))
        // Start from a known governed state, so the first force below is a real
        // transition rather than a no-op against whatever the fallback picked.
        a.forceSize("laptop", normal(laptop.first, laptop.second))
        assertEquals(laptop, a.effective)

        var resizes = 0
        repeat(10) {
            // Phone take-over, then the mirror votes that follow it.
            if (a.forceSize("phone", normal(phone.first, phone.second)) != null) resizes++
            if (a.setSize("laptop", normal(laptop.first, laptop.second)) != null) resizes++

            // Laptop take-over, then the phone's mirror votes.
            if (a.forceSize("laptop", normal(laptop.first, laptop.second)) != null) resizes++
            if (a.setSize("phone", normal(phone.first, phone.second)) != null) resizes++
        }

        assertEquals(20, resizes, "10 round trips must cost 20 resizes, not more")
    }
}
