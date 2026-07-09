/**
 * Carousel-ring style for the 3D tab overview — the original (and default)
 * [Overview3DStyle].
 *
 * Presents every visible tab as a card on a slowly turning 3D ring, camera at
 * the rim. The front card is the selection; as it settles at dead-centre it
 * dissolves into its individual pane tiles (one per pane, at the tab's real
 * layout geometry) which then ease apart, lift toward the camera, and bow into
 * a shallow inward curve. Rotating away reassembles them into the flat card.
 * Clicking the front card (or pressing Enter) dives the camera into it while the
 * overlay fades, landing on the real tab.
 *
 * This file owns only the carousel's *spatial* behaviour and gesture handling;
 * all content capture (live terminal grids, file/git thumbnails), selection
 * state, and the open/close/render lifecycle live in the shared core
 * ([Overview3D.kt]). The core builds [ovCards] and seeds [ovSelected] /
 * [ovPaneSelected]; this style parents the meshes onto its ring and animates
 * them.
 *
 * Interaction:
 *  - `←`/`→` (and a wheel/trackpad swipe along the dominant axis) rotate the
 *    ring one card at a time.
 *  - `↑`/`↓` / `Tab` walk the front tab's panes.
 *  - `Enter` or clicking the front card dives in; clicking a non-front card
 *    rotates it to front; clicking empty space (or `Esc`) closes.
 *
 * @see Overview3DStyle
 * @see RotundaStyle
 * @see ExposeStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Camera distance from the *front* card (world units). Closer = the front tab
 * fills more of the screen. Kept a touch back from the legibility floor so the
 * split front card's bottom row of panes always clears the viewport edges. */
private const val CAMERA_DISTANCE = 3.35

/**
 * Camera height above the ring center (world units). A gentle downward tilt that
 * keeps the ring grounded; shallow so the framing stays roughly symmetric
 * top-to-bottom. Shared by the open pose and the dive-in so they never disagree.
 */
private const val CAMERA_Y = 0.32

/** Ring radius floor so 2–3 tabs still form a visible ring. */
private const val MIN_RING_RADIUS = 2.2

/** How long fresh PTY output keeps a card's activity glow lit (ms). */
private const val GLOW_FADE_MS = 1600.0

/**
 * Ring-rotation arc (radians from dead-centre) over which the selected card
 * dissolves from its flat whole-tab thumbnail into its pane tiles. Driving the
 * reveal off the *incoming rotation* (not the split) fades the pane borders +
 * accent header in **with** the panes, as one unit, while the card is still
 * turning to the front.
 */
private const val REVEAL_ARC = 0.9

/**
 * Inward-curve strength for split panes: radians of tilt per world-unit of a
 * tile's offset from the card centre, so the exploded panes bend back toward the
 * centre like a shallow bowl facing the camera. Scaled by the split factor and
 * capped by [CURVE_MAX_RAD].
 */
private const val CURVE_K = 0.18

/** Cap on the per-tile inward-curve tilt (radians, ≈17°). */
private const val CURVE_MAX_RAD = 0.3

/**
 * Idle gap (ms) between wheel events that ends one gesture and arms the next.
 * Covers cleanly separated swipes where the momentum tail has fully stopped
 * before the next physical swipe.
 */
private const val OV_WHEEL_GESTURE_GAP_MS = 220.0

/**
 * A wheel event whose magnitude exceeds this floor *and* climbs meaningfully
 * above the previous one (see [OV_WHEEL_RISE_FACTOR]) is treated as a fresh
 * finger push cutting through the decaying momentum tail of the prior swipe, so
 * quick back-to-back swipes each register even before momentum stops.
 */
private const val OV_WHEEL_RISE_FLOOR = 22.0

/** Factor by which a delta must exceed the prior one to count as a new push. */
private const val OV_WHEEL_RISE_FACTOR = 2.2

/**
 * Accumulated wheel/swipe delta (either axis) needed to step one tab. Higher = a
 * more deliberate swipe is required, so a light or bumpy trackpad flick no longer
 * skips several tabs at once.
 */
private const val OV_WHEEL_STEP = 90.0

/**
 * The carousel-ring [Overview3DStyle]. A stateless singleton apart from the
 * per-open scene state it resets in [build] (its ring group, eased rotation, and
 * wheel-gesture latch).
 */
internal object CarouselStyle : Overview3DStyle {

    /** Per-open group holding the card meshes; spun as one via `rotation.y`. */
    private var ring: Group? = null

    /** Ring radius for the current open (depends on card count). */
    private var radius = 0.0

    /** Current ring rotation (radians); eased toward the selected card. */
    private var ringAngle = 0.0

    /** Wheel delta accumulator toward the next single-tab step. */
    private var wheelAcc = 0.0

    /** True once the current wheel gesture has already stepped (latch). */
    private var wheelLatched = false

    /** `performance.now()` of the last observed wheel event. */
    private var wheelLastTs = 0.0

    /** `|delta|` of the previous wheel event, for rising-edge detection. */
    private var wheelPrevMag = 0.0

    /**
     * Builds the ring: sizes the radius for a comfortable chord gap, places each
     * card at its ring angle (facing outward), parents each card's pane tiles to
     * the card mesh so they inherit the spin, and settles the rotation on the
     * selected card. Resets the wheel-gesture latch.
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val n = ovCards.size
        radius = if (n >= 2) max(MIN_RING_RADIUS, (CARD_W * 1.35) / (2.0 * sin(PI / n))) else 0.0
        val ring = Group()
        this.ring = ring
        scene.add(ring)

        val step = if (n > 0) 2.0 * PI / n else 0.0
        ovCards.forEachIndexed { i, card ->
            val angle = i * step
            card.mesh.position.set(radius * sin(angle), 0.0, radius * cos(angle))
            card.mesh.rotation.y = angle
            for (tile in card.tiles) card.mesh.add(tile.mesh)
            ring.add(card.mesh)
        }

        ringAngle = -ovSelected * step
        ring.rotation.y = ringAngle
        wheelAcc = 0.0
        wheelLatched = false
        wheelLastTs = 0.0
        wheelPrevMag = 0.0
    }

    /** Poses the camera back from the ring at [CAMERA_Y], looking at its centre. */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.position.set(0.0, CAMERA_Y, radius + CAMERA_DISTANCE)
        camera.lookAt(0.0, 0.0, 0.0)
    }

    /**
     * Eases the ring toward the selected card (shortest arc), bobs the cards,
     * drives hover selection, animates each front card's reveal + split-into-panes,
     * decays the background activity glows, and advances the dive-in camera.
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        val n = ovCards.size
        val step = if (n > 0) 2.0 * PI / n else 0.0

        // Ease the ring toward the selected card along the shortest arc.
        val target = -ovSelected * step
        val delta = atan2(sin(target - ringAngle), cos(target - ringAngle))
        ringAngle += delta * 0.16
        ring?.let { it.rotation.y = ringAngle }
        val settled = abs(delta) < 0.03

        // Hover pick (skipped mid-dive). Hovering a front-card pane tile drives
        // the same pane highlight the keyboard uses, so mouse and ↑/↓ agree.
        val hoverUd = if (ovDiveStart.isNaN()) raycastPointer(pickables()) else null
        val hoverIndex = if (hoverUd == null) null else (hoverUd.index as? Int)
        if (ovPointerMoved && hoverUd != null && hoverIndex == ovSelected) {
            val hpid = hoverUd.paneId as? String
            if (hpid != null) {
                val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == hpid }
                if (ti >= 0) setPaneSelection(ti)
            }
        }
        ovPointerMoved = false

        ovCards.forEachIndexed { i, card ->
            // Gentle idle float so the scene never feels frozen. Biased upward
            // (and shallower) so even at the bottom of the bob the front card's
            // lowest split panes stay clear of the lower viewport edge.
            card.mesh.position.y = 0.05 + sin(now * 0.0012 + i * 1.3) * 0.03

            // Glow = decaying PTY-activity breathing on *background* tabs only.
            val activity = max(0.0, 1.0 - (now - card.latestActivity()) / GLOW_FADE_MS)
            val glowTarget = if (i == ovSelected) 0.0 else activity * 0.45
            card.glowMaterial.opacity += (glowTarget - card.glowMaterial.opacity) * 0.12

            val scaleTarget = if (i == hoverIndex) 1.05 else 1.0
            val s = (card.mesh.scale.x as Double) + (scaleTarget - (card.mesh.scale.x as Double)) * 0.2
            card.mesh.scale.set(s, s, 1.0)

            val isFront = i == ovSelected

            // Reveal: dissolve the flat whole-tab thumbnail into its (bordered,
            // accent-headed) pane tiles as it rotates to dead-centre — driven by
            // the ring delta, NOT the split (see [REVEAL_ARC]).
            val revealTarget = if (isFront) (1.0 - abs(delta) / REVEAL_ARC).coerceIn(0.0, 1.0) else 0.0
            card.reveal += (revealTarget - card.reveal) * 0.2
            val r = ease(card.reveal.coerceIn(0.0, 1.0))
            card.cardMaterial.opacity = 1.0 - r

            // Split-into-panes: once the front card is fully revealed and settled,
            // its pane tiles ease apart from their assembled layout, lifting
            // toward the camera. Opacity is the reveal; the split drives position
            // and curvature only.
            val splitTarget = if (isFront && settled) 1.0 else 0.0
            card.split += (splitTarget - card.split) * 0.14
            val e = ease(card.split.coerceIn(0.0, 1.0))
            card.tiles.forEachIndexed { ti, tile ->
                tile.material.opacity = r
                tile.mesh.visible = r > 0.02
                tile.mesh.position.set(
                    tile.homeX + (tile.splitX - tile.homeX) * e,
                    tile.homeY + (tile.splitY - tile.homeY) * e,
                    tile.homeZ + (tile.splitZ - tile.homeZ) * e,
                )
                // Outward curve: each tile tilts so its outer edges bow away from
                // the camera, complementing the per-pane dish geometry.
                val yaw = (CURVE_K * tile.splitX).coerceIn(-CURVE_MAX_RAD, CURVE_MAX_RAD) * e
                val pitch = (-CURVE_K * tile.splitY).coerceIn(-CURVE_MAX_RAD, CURVE_MAX_RAD) * e
                tile.mesh.rotation.set(pitch, yaw, 0.0)
                val selected = isFront && ti == ovPaneSelected
                val tScaleTarget = if (selected) 1.05 else 1.0
                val ts = (tile.mesh.scale.x as Double) + (tScaleTarget - (tile.mesh.scale.x as Double)) * 0.2
                tile.mesh.scale.set(ts, ts, 1.0)
            }
        }

        // Dive-in: fly the camera into the front card while the CSS fade runs.
        // The core closes the overview when the fade window completes.
        if (!ovDiveStart.isNaN()) {
            val p = ((now - ovDiveStart) / DIVE_MS).coerceIn(0.0, 1.0)
            val ez = ease(p)
            val baseZ = radius + CAMERA_DISTANCE
            val targetZ = radius + 0.9
            camera.position.set(0.0, CAMERA_Y * (1.0 - ez), baseZ - (baseZ - targetZ) * ez)
        }
    }

    /** ←/→ rotate between tabs; ↑/↓ and Tab / Shift+Tab walk the front tab's panes. */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT -> stepSelection(-1)
            OvNav.RIGHT -> stepSelection(1)
            OvNav.UP -> cyclePane(-1)
            OvNav.DOWN -> cyclePane(1)
            OvNav.PANE_NEXT -> cyclePane(1)
            OvNav.PANE_PREV -> cyclePane(-1)
        }
    }

    /**
     * A single wheel/trackpad swipe steps exactly one tab: once the
     * ±[OV_WHEEL_STEP] threshold is crossed we latch and ignore the rest of the
     * gesture's momentum tail. The latch re-arms after [OV_WHEEL_GESTURE_GAP_MS]
     * of silence, or the moment a fresh finger push makes the delta climb back up
     * out of the decaying tail.
     */
    override fun wheel(delta: Double, nowMs: Double) {
        val mag = abs(delta)
        val gap = nowMs - wheelLastTs > OV_WHEEL_GESTURE_GAP_MS
        val risingEdge = wheelLatched &&
            mag > OV_WHEEL_RISE_FLOOR && mag > wheelPrevMag * OV_WHEEL_RISE_FACTOR
        if (gap || risingEdge) {
            wheelLatched = false
            wheelAcc = 0.0
        }
        wheelLastTs = nowMs
        wheelPrevMag = mag
        if (wheelLatched) return
        wheelAcc += delta
        if (wheelAcc > OV_WHEEL_STEP) { stepSelection(1); wheelAcc = 0.0; wheelLatched = true }
        if (wheelAcc < -OV_WHEEL_STEP) { stepSelection(-1); wheelAcc = 0.0; wheelLatched = true }
    }

    /** Every card, plus the pane tiles of the selected card once it has split. */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        for (card in ovCards) meshes.add(card.mesh.asDynamic())
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null && sel.split > 0.5) {
            for (tile in sel.tiles) meshes.add(tile.mesh.asDynamic())
        }
        return meshes.toTypedArray()
    }

    /**
     * Front card (or one of its pane tiles) → dive in / focus that pane; a
     * non-front card → rotate it to front; empty space → close.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx == ovSelected) beginDive(userData.paneId as? String) else selectCard(idx)
    }

    /** Removes the ring group from the scene (cards/tiles disposed by the core). */
    override fun teardown(scene: Scene) {
        ring?.let { scene.remove(it) }
        ring = null
    }
}
