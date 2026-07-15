/**
 * Tests for [ClientSizeArbiter] — the latest-active-client PTY size policy:
 *  - upstream parity when no activity is recorded (tiered min over votes,
 *    hold-on-empty),
 *  - governance transfer via input, mobile votes, and forced resizes,
 *  - ambient (NORMAL/THREE_D) votes never stealing governance,
 *  - tier interactions (THREE_D overrides a typing 2D viewer; an actively
 *    used MOBILE client overrides everything),
 *  - removal fallback to the most recently active remaining client,
 *  - the null no-op guard on unchanged sizes.
 */
package se.soderbjorn.lunamux.pty

import se.soderbjorn.lunamux.SizePriority
import se.soderbjorn.lunamux.SizeVote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientSizeArbiterTest {

    private fun arbiter() = ClientSizeArbiter(120, 32)

    private fun normal(c: Int, r: Int) = SizeVote(c, r, SizePriority.NORMAL)
    private fun threeD(c: Int, r: Int) = SizeVote(c, r, SizePriority.THREE_D)
    private fun mobile(c: Int, r: Int) = SizeVote(c, r, SizePriority.MOBILE)

    // ── no-activity fallback: upstream parity ────────────────────────────

    @Test
    fun single_vote_applies() {
        val a = arbiter()
        assertEquals(150 to 45, a.setSize("desktop", normal(150, 45)))
    }

    @Test
    fun without_activity_two_normal_votes_reduce_to_min() {
        val a = arbiter()
        a.setSize("big", normal(200, 60))
        assertEquals(80 to 24, a.setSize("small", normal(80, 24)))
        assertEquals(80 to 24, a.effective)
    }

    @Test
    fun without_activity_three_d_beats_normal() {
        val a = arbiter()
        a.setSize("viewer", normal(80, 24))
        assertEquals(200 to 60, a.setSize("rider", threeD(200, 60)))
    }

    @Test
    fun initial_size_holds_until_someone_votes() {
        val a = arbiter()
        assertEquals(120 to 32, a.effective)
        assertNull(a.noteInput("ghost"))
        assertEquals(120 to 32, a.effective)
    }

    // ── governance via input ─────────────────────────────────────────────

    @Test
    fun input_makes_a_client_the_governor_and_applies_its_vote() {
        val a = arbiter()
        a.setSize("big", normal(200, 60))
        a.setSize("small", normal(80, 24))          // min() shrinks to 80x24
        assertEquals(200 to 60, a.noteInput("big")) // typing reclaims
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun ambient_normal_vote_does_not_steal_governance() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        // A second window attaches / jitters — the governed size must hold.
        assertNull(a.setSize("viewer", normal(80, 24)))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun governing_clients_own_resize_applies_immediately() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        a.setSize("viewer", normal(80, 24))
        assertEquals(190 to 55, a.setSize("desktop", normal(190, 55)))
    }

    @Test
    fun repeated_input_from_governor_is_a_no_op() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        assertNull(a.noteInput("desktop"))
    }

    // ── mobile semantics ─────────────────────────────────────────────────

    @Test
    fun mobile_vote_claims_the_size_immediately() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        assertEquals(40 to 20, a.setSize("phone", mobile(40, 20)))
    }

    @Test
    fun desktop_keystroke_reclaims_from_an_idle_phone() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.setSize("phone", mobile(40, 20))
        assertEquals(200 to 60, a.noteInput("desktop"))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun phone_rotation_revote_reclaims_for_the_phone() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.setSize("phone", mobile(40, 20))
        a.noteInput("desktop")
        assertEquals(20 to 40, a.setSize("phone", mobile(20, 40)))
    }

    @Test
    fun active_mobile_governor_beats_a_three_d_override() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        assertEquals(40 to 20, a.setSize("phone", mobile(40, 20)))
    }

    // ── 3D-tier interactions ─────────────────────────────────────────────

    @Test
    fun three_d_override_survives_a_typing_2d_viewer() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        a.setSize("viewer", normal(80, 24))
        // The co-attached viewer types; the enlarged 3D grid must hold.
        assertNull(a.noteInput("viewer"))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun min_within_three_d_tier_is_kept() {
        val a = arbiter()
        a.setSize("r1", threeD(200, 60))
        assertEquals(150 to 60, a.setSize("r2", threeD(150, 80)))
    }

    // ── forced resizes (Reformat) ────────────────────────────────────────

    @Test
    fun force_evicts_other_votes_and_governs() {
        val a = arbiter()
        a.setSize("small", normal(80, 24))
        a.noteInput("small")
        assertEquals(200 to 60, a.forceSize("desktop", normal(200, 60)))
        // The evicted client re-votes ambiently — must not steal back.
        assertNull(a.setSize("small", normal(80, 24)))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun force_beats_a_standing_three_d_vote_by_evicting_it() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        assertEquals(100 to 30, a.forceSize("desktop", normal(100, 30)))
    }

    // ── removal ──────────────────────────────────────────────────────────

    @Test
    fun removing_the_governor_falls_back_to_most_recently_active() {
        val a = arbiter()
        a.setSize("first", normal(100, 30))
        a.noteInput("first")
        a.setSize("second", normal(150, 45))
        a.noteInput("second")
        a.setSize("phone", mobile(40, 20))     // phone governs (latest)
        assertEquals(150 to 45, a.remove("phone"))
        assertEquals(100 to 30, a.remove("second"))
    }

    @Test
    fun removing_the_last_client_holds_the_effective_size() {
        val a = arbiter()
        a.setSize("desktop", normal(87, 41))
        assertNull(a.remove("desktop"))
        assertEquals(87 to 41, a.effective)
    }

    @Test
    fun removing_an_unknown_client_is_a_no_op() {
        val a = arbiter()
        a.setSize("desktop", normal(87, 41))
        assertNull(a.remove("nope"))
    }
}
