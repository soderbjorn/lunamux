/**
 * Tests for [ClientSizeArbiter] — the latest-active-client PTY size policy
 * with viewer/driver posture:
 *  - upstream parity when no activity is recorded (tiered min over the drivers'
 *    votes, hold-on-empty),
 *  - governance transfer via input and forced take-overs,
 *  - viewer posture: a phone attaching never shrinks the drivers' grid, its
 *    keystrokes do not govern, and it takes over only by forcing,
 *  - the no-eviction reclaim: a laptop reclaims the grid by typing after a
 *    phone take-over (the bug the old eviction created),
 *  - tier interactions (THREE_D overrides a typing 2D viewer; an explicit force
 *    beats a standing THREE_D override and the authority is sticky until
 *    another client takes over),
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

    // ── no-activity fallback: upstream parity (every client a driver) ────────

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

    // ── viewer/driver posture ────────────────────────────────────────────

    @Test
    fun a_viewer_attaching_does_not_shrink_the_drivers_grid() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))         // driver by default
        a.setPosture("phone", ClientPosture.VIEWER)
        // The phone mirrors the desktop; its ambient vote must not shrink it,
        // even with no driver activity recorded yet (the no-activity fallback
        // considers only driver votes).
        assertNull(a.setSize("phone", normal(40, 20)))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun a_viewer_keystroke_does_not_govern() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        a.setPosture("phone", ClientPosture.VIEWER)
        a.setSize("phone", normal(40, 20))
        // A stray keystroke from a viewer that has not taken over is ignored.
        assertNull(a.noteInput("phone"))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun a_viewer_governs_only_after_taking_over() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        a.setPosture("phone", ClientPosture.VIEWER)
        assertNull(a.setSize("phone", normal(40, 20)))    // ambient, no take-over
        assertEquals(200 to 60, a.effective)
        // Explicit take-over (force) promotes the phone to a driver and governs.
        assertEquals(40 to 20, a.forceSize("phone", normal(40, 20)))
    }

    @Test
    fun laptop_reclaims_by_typing_after_a_phone_takes_over() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        a.setPosture("phone", ClientPosture.VIEWER)
        assertEquals(40 to 20, a.forceSize("phone", normal(40, 20)))  // phone takes over
        // The desktop's vote was NOT evicted, so one keystroke reclaims the grid
        // (the exact case the old force-evicts-everything broke).
        assertEquals(200 to 60, a.noteInput("desktop"))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun a_phone_revote_after_taking_over_applies() {
        val a = arbiter()
        a.setSize("desktop", normal(200, 60))
        a.noteInput("desktop")
        a.setPosture("phone", ClientPosture.VIEWER)
        a.forceSize("phone", normal(40, 20))              // phone drives at 40x20
        assertEquals(20 to 40, a.setSize("phone", normal(20, 40)))  // rotate → applies
    }

    @Test
    fun a_phone_only_session_sizes_to_the_phone() {
        val a = arbiter()
        a.setPosture("phone", ClientPosture.VIEWER)
        // Only a viewer is attached — the fallback still sizes to it so a
        // phone-only session is usable without a take-over.
        assertEquals(40 to 20, a.setSize("phone", normal(40, 20)))
        assertEquals(40 to 20, a.effective)
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

    // ── forced resizes (Reformat / take-over) ────────────────────────────

    @Test
    fun force_governs_without_evicting_and_ambient_revotes_dont_steal_back() {
        val a = arbiter()
        a.setSize("small", normal(80, 24))
        a.noteInput("small")
        assertEquals(200 to 60, a.forceSize("desktop", normal(200, 60)))
        // The other client re-votes ambiently — its vote is kept, but it does
        // not govern, so it must not steal the size back.
        assertNull(a.setSize("small", normal(80, 24)))
        assertEquals(200 to 60, a.effective)
    }

    @Test
    fun force_beats_a_standing_three_d_override() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        // An explicit force outranks the 3D tier (no eviction needed).
        assertEquals(100 to 30, a.forceSize("desktop", normal(100, 30)))
    }

    @Test
    fun force_over_3d_stays_while_the_forcer_types_on() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        a.forceSize("desktop", normal(100, 30))   // 100x30 — force beats 3D
        // The forcer keeps typing; its take-over authority is sticky.
        assertNull(a.noteInput("desktop"))
        assertEquals(100 to 30, a.effective)
    }

    @Test
    fun a_stale_force_over_3d_is_dropped_when_another_driver_types() {
        val a = arbiter()
        a.setSize("rider", threeD(200, 60))
        a.setSize("laptop", normal(150, 50))       // ambient; 3D still wins
        a.forceSize("phone", normal(100, 30))      // force beats 3D → 100x30
        // An ordinary keystroke on the laptop supersedes the phone's force, so
        // the force authority is dropped and the 3D override reasserts (a plain
        // typist does not beat 3D).
        assertEquals(200 to 60, a.noteInput("laptop"))
    }

    // ── removal ──────────────────────────────────────────────────────────

    @Test
    fun removing_the_governor_falls_back_to_most_recently_active() {
        val a = arbiter()
        a.setSize("first", normal(100, 30))
        a.noteInput("first")
        a.setSize("second", normal(150, 45))
        a.noteInput("second")
        a.setSize("third", normal(88, 22))
        a.noteInput("third")                       // third governs (latest)
        assertEquals(150 to 45, a.remove("third"))
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

    // ── governor identity ────────────────────────────────────────────────

    @Test
    fun no_client_governs_until_one_acts() {
        val a = arbiter()
        assertNull(a.governor())
        // An ambient vote is not activity, so it does not make anyone the governor —
        // the size still moves via the no-activity fallback.
        a.setSize("desktop", normal(120, 40))
        assertNull(a.governor())
        a.noteInput("desktop")
        assertEquals("desktop", a.governor())
    }

    @Test
    fun governance_moves_between_same_width_clients_without_a_size_change() {
        // The case that made the client-side width comparison unfixable: two clients
        // rendering at the SAME width. Governance moves, the effective size does not,
        // so a client watching only the size stream sees nothing at all — and both
        // clients conclude they are driving because both widths match the server's.
        val a = arbiter()
        a.setSize("laptop", normal(120, 40))
        a.noteInput("laptop")
        a.setSize("phone", normal(120, 40))
        assertEquals("laptop", a.governor())

        assertNull(a.forceSize("phone", normal(120, 40)), "same width: no size change")
        assertEquals("phone", a.governor(), "governance must move even though the grid did not")
    }

    @Test
    fun a_viewers_input_moves_neither_the_size_nor_governance() {
        val a = arbiter()
        a.setSize("laptop", normal(120, 40))
        a.noteInput("laptop")
        a.setPosture("phone", ClientPosture.VIEWER)
        a.setSize("phone", normal(50, 20))

        assertNull(a.noteInput("phone"), "a viewer's keystroke must not seize the grid")
        assertEquals("laptop", a.governor(), "nor governance")
    }

    @Test
    fun losing_the_governor_leaves_the_session_ungoverned() {
        // With the governor gone and only ambient votes left, nobody governs. The
        // route reports governed=false so clients resume their own width fallback
        // rather than being frozen as mirrors of a device that is no longer attached.
        val a = arbiter()
        a.setSize("laptop", normal(120, 40))
        a.noteInput("laptop")
        a.setSize("phone", normal(50, 20))
        assertEquals("laptop", a.governor())

        a.remove("laptop")
        assertNull(a.governor())
    }
}
