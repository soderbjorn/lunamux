/**
 * Flip-stack ("Deck") style for the 3D tab overview — the "riffle a pile of
 * cards" [Overview3DStyle].
 *
 * Every visible tab is a card in a single depth-sorted **pile** receding into the
 * screen along Z, in the spirit of the old Aero Flip 3D task switcher. The
 * selected tab is the front card — full size, turned face-on; the rest fan back
 * behind it, each one deeper, smaller, sheared up-and-to-the-right and yawed a
 * touch so you see their edges like a real stack. This is the only style whose
 * primary axis is *depth toward the camera* rather than a lateral rotation
 * (carousel), a surrounding cylinder (rotunda), or a flat plane (exposé).
 *
 * Two motions, one metaphor — and, as the user requires, one for tabs and one
 * for panes:
 *  - **Riffle the pile (tabs)** — `←`/`→` (and the wheel / a trackpad swipe)
 *    peel the front card off and bring the next tab forward; the pile flows by
 *    one card. The card dead-front is the selection.
 *  - **Fan the hand (panes)** — once the front card settles it dissolves into
 *    its panes, fanned out like a hand of cards held just in front of the pile;
 *    `↑`/`↓` (and `Tab` / `Shift+Tab`) walk the highlight through the fan (via
 *    the shared [cyclePane], which also visits the "whole tab" slot so `Enter`
 *    can dive the whole tab), lifting the chosen pane toward the camera.
 *
 * As with every style, this file owns *only* the deck's spatial arrangement and
 * gesture handling; all content capture (live terminal grids, file/git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]). The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style places the card meshes in its pile
 * group, parents each card's pane tiles onto its card (so they inherit the
 * card's transform), and animates them.
 *
 * True aspect ratio is preserved: cards keep their native [CARD_W]×[CARD_H]
 * plane and are only ever scaled *uniformly*, and each fanned pane tile is
 * letterboxed into its fan slot by a uniform scale, never stretched.
 *
 * @see Overview3DStyle
 * @see CarouselStyle
 * @see CorridorStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Camera distance back from the front card (which sits at z ≈ 0). */
private const val CAMERA_DISTANCE = 4.2

/** Camera height above the pile centre — a gentle downward look onto the deck. */
private const val CAMERA_Y = 0.28

/** Z gap between successive cards in the pile (deeper = further from camera). */
private const val DEPTH_STEP = 0.7

/** Uniform scale multiplier applied per rank, so deeper cards read smaller. */
private const val RANK_SHRINK = 0.86

/** Rightward shear per rank — the pile leans as it recedes (the Flip-3D look). */
private const val X_SHEAR = 0.55

/** Upward shear per rank, so each deeper card's top edge peeks above the one in front. */
private const val Y_SHEAR = 0.26

/**
 * Constant yaw (radians ≈ 20°) applied to *stacked* (non-front) cards so we see
 * their faces at an angle rather than edge-on — the signature Flip-3D rake. The
 * front card eases its yaw back to 0 so its fanned panes present face-on.
 */
private const val PILE_YAW = 0.35

/** Rank beyond which a card has receded far enough to fade fully out. */
private const val VISIBLE_DEPTH = 6.0

/** How long fresh PTY output keeps a stacked card's activity glow lit (ms). */
private const val GLOW_FADE_MS = 1600.0

/** Horizontal spacing between adjacent panes in the fanned hand (local units). */
private const val FAN_DX = 0.62

/** How far the whole fan lifts toward the camera off the card face (local +Z). */
private const val FAN_LIFT = 0.9

/** Extra depth drop per step out from the fan's centre, so the middle card leads. */
private const val FAN_DZ = 0.12

/** Per-tile yaw across the fan (radians per step from centre) — the cards splay. */
private const val FAN_YAW = 0.14

/** Extra forward lift given to the highlighted pane so it stands proud of the fan. */
private const val SELECTED_LIFT = 0.4

/** Uniform scale bump applied to the highlighted pane. */
private const val SELECTED_POP = 1.06

/** World-space width the fanned pane tiles are letterboxed into. */
private const val FAN_SLOT_W = 1.7

/** World-space height the fanned pane tiles are letterboxed into. */
private const val FAN_SLOT_H = 1.15

/** Per-frame lerp factor each card glides toward its target pile transform. */
private const val CARD_EASE = 0.18

/** Idle gap (ms) between wheel events that ends one gesture and re-arms the next. */
private const val WHEEL_GESTURE_GAP_MS = 220.0

/** A wheel delta above this floor that also climbs sharply counts as a fresh push. */
private const val WHEEL_RISE_FLOOR = 22.0

/** Factor a delta must exceed the prior one by to count as a new push. */
private const val WHEEL_RISE_FACTOR = 2.2

/** Accumulated wheel/swipe delta needed to riffle one tab. */
private const val WHEEL_STEP = 90.0

/**
 * The flip-stack [Overview3DStyle]. A stateless singleton apart from the
 * per-open scene state it resets in [build] (its pile group, per-card fan ease,
 * the drag parallax yaw, and the wheel-gesture latch).
 */
internal object FlipStackStyle : Overview3DStyle {

    /** Per-open group holding every card mesh; carries only the drag parallax yaw. */
    private var pile: Group? = null

    /** Drag-driven parallax yaw of the whole pile (radians); eases back to 0. */
    private var parallaxYaw = 0.0

    /** Wheel delta accumulator toward the next single-tab riffle. */
    private var wheelAcc = 0.0

    /** True once the current wheel gesture has already stepped (latch). */
    private var wheelLatched = false

    /** `performance.now()` of the last observed wheel event. */
    private var wheelLastTs = 0.0

    /** `|delta|` of the previous wheel event, for rising-edge detection. */
    private var wheelPrevMag = 0.0

    /**
     * Builds the pile: parents each card mesh into one group and each card's pane
     * tiles onto its card mesh (so the fan inherits the card's transform),
     * letterboxing every tile into the fan slot with a uniform scale. Snaps every
     * card to its rank-0..n transform so the opening frame is already a tidy pile,
     * and resets the parallax + wheel state.
     *
     * @param scene the cached scene to add the pile group to.
     * @param camera the cached camera (posed separately in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        pile = g
        scene.add(g)

        ovCards.forEachIndexed { i, card ->
            for (tile in card.tiles) {
                val s = minOf(FAN_SLOT_W / tile.worldW, FAN_SLOT_H / tile.worldH)
                tile.mesh.scale.set(s, s, 1.0)
                tile.mesh.visible = false
                card.mesh.add(tile.mesh)
            }
            // Snap to the target so the first frame opens as a formed pile, not a
            // heap collapsing at the origin.
            val r = rankOf(i)
            card.mesh.position.set(r * X_SHEAR, r * Y_SHEAR, -r * DEPTH_STEP)
            val sc = RANK_SHRINK.pow(r.toDouble())
            card.mesh.scale.set(sc, sc, 1.0)
            card.mesh.rotation.set(0.0, if (i == ovSelected) 0.0 else PILE_YAW, 0.0)
            g.add(card.mesh)
        }

        parallaxYaw = 0.0
        wheelAcc = 0.0
        wheelLatched = false
        wheelLastTs = 0.0
        wheelPrevMag = 0.0
    }

    /** Poses the camera back from the front card at [CAMERA_Y], looking at the pile front. */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.position.set(0.0, CAMERA_Y, CAMERA_DISTANCE)
        camera.lookAt(0.0, 0.0, 0.0)
    }

    /**
     * Per-frame update: eases each card toward its rank transform (so a riffle
     * flows the pile), fades stacked cards by depth and their activity glow,
     * drives hover-to-select on the fanned front card, dissolves the settled
     * front card into its fanned hand of panes, and advances the dive-in camera.
     *
     * @param now `window.performance.now()` for this frame.
     * @param camera the cached camera (moved only during the dive).
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        pile?.let { it.rotation.y = parallaxYaw }
        parallaxYaw *= 0.9

        // Hover pick (skipped mid-dive): hovering a fanned front-card tile drives
        // the same highlight the keyboard uses, so mouse and ↑/↓ agree.
        val hoverUd = if (ovDiveStart.isNaN()) raycastPointer(pickables()) else null
        if (ovPointerMoved && hoverUd != null && (hoverUd.index as? Int) == ovSelected) {
            val hpid = hoverUd.paneId as? String
            if (hpid != null) {
                val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == hpid }
                if (ti >= 0) setPaneSelection(ti)
            }
        }
        ovPointerMoved = false

        ovCards.forEachIndexed { i, card ->
            val isFront = i == ovSelected
            val r = rankOf(i)

            // Target pile transform for this card's rank, eased toward each frame.
            val tx = r * X_SHEAR
            val ty = r * Y_SHEAR + sin(now * 0.0011 + i * 1.7) * 0.015
            val tz = -r * DEPTH_STEP
            val m = card.mesh
            val cx = m.position.x as Double
            val cy = m.position.y as Double
            val cz = m.position.z as Double
            m.position.set(cx + (tx - cx) * CARD_EASE, cy + (ty - cy) * CARD_EASE, cz + (tz - cz) * CARD_EASE)

            val scTarget = RANK_SHRINK.pow(r.toDouble())
            val cs = m.scale.x as Double
            val ns = cs + (scTarget - cs) * CARD_EASE
            m.scale.set(ns, ns, 1.0)

            val yawTarget = if (isFront) 0.0 else PILE_YAW
            val cyaw = m.rotation.y as Double
            m.rotation.set(0.0, cyaw + (yawTarget - cyaw) * CARD_EASE, 0.0)

            // Depth fade: cards far back in the pile dissolve so it never reads as
            // an infinite wall. The front card is always solid.
            val depthFade = (1.0 - r / VISIBLE_DEPTH).coerceIn(0.0, 1.0)

            // Glow = decaying PTY-activity breathing on stacked cards only.
            val activity = maxOf(0.0, 1.0 - (now - card.latestActivity()) / GLOW_FADE_MS)
            val glowTarget = if (isFront) 0.0 else activity * 0.45 * depthFade
            card.glowMaterial.opacity += (glowTarget - card.glowMaterial.opacity) * 0.12

            // Fan: the settled front card dissolves its flat face into a fanned
            // hand of pane tiles. Reuse [OverviewCard.split] as the 0→1 fan factor.
            val fanTarget = if (isFront) 1.0 else 0.0
            card.split += (fanTarget - card.split) * 0.16
            val f = ease(card.split.coerceIn(0.0, 1.0))
            card.cardMaterial.opacity = if (isFront) (1.0 - f) else depthFade

            layoutFan(card, isFront, f)
        }

        // Dive-in: fly the camera into the front card while the CSS fade runs; the
        // core closes the overview when the fade window completes.
        if (!ovDiveStart.isNaN()) {
            val p = ((now - ovDiveStart) / DIVE_MS).coerceIn(0.0, 1.0)
            val ez = ease(p)
            camera.position.set(0.0, CAMERA_Y * (1.0 - ez), CAMERA_DISTANCE - (CAMERA_DISTANCE - 0.9) * ez)
        }
    }

    /**
     * Positions one card's pane tiles as a fanned hand in the card's local space,
     * opened by the fan factor [f]. Non-front cards keep their tiles hidden
     * (their flat face shows instead). The highlighted pane lifts proud of the
     * fan and pops a touch.
     *
     * @param card the card whose tiles to lay out.
     * @param isFront whether this is the selected (fanning) card.
     * @param f eased fan factor (0 = folded onto the face, 1 = fully fanned).
     */
    private fun layoutFan(card: OverviewCard, isFront: Boolean, f: Double) {
        val n = card.tiles.size
        card.tiles.forEachIndexed { ti, tile ->
            val show = isFront && f > 0.02
            tile.material.opacity = if (isFront) f else 0.0
            tile.mesh.visible = show
            if (!show) return@forEachIndexed
            val off = ti - (n - 1) / 2.0
            val selected = ti == ovPaneSelected
            val lx = off * FAN_DX * f
            val lz = (FAN_LIFT - abs(off) * FAN_DZ + (if (selected) SELECTED_LIFT else 0.0)) * f
            tile.mesh.position.set(lx, 0.0, lz)
            tile.mesh.rotation.set(0.0, -off * FAN_YAW * f, 0.0)
            val base = minOf(FAN_SLOT_W / tile.worldW, FAN_SLOT_H / tile.worldH)
            val target = base * (if (selected) SELECTED_POP else 1.0)
            val cur = tile.mesh.scale.x as Double
            val ns = cur + (target - cur) * 0.2
            tile.mesh.scale.set(ns, ns, 1.0)
        }
    }

    /**
     * The card's rank in the pile: 0 for the selected (front) card, increasing by
     * one for each tab *after* it (wrapping), so the whole pile is ordered behind
     * the front. A riffle changes [ovSelected], which re-ranks every card and
     * flows the pile in [tick].
     *
     * @param cardIndex index into [ovCards].
     * @return the card's depth rank (0 = front).
     */
    private fun rankOf(cardIndex: Int): Int {
        val n = ovCards.size
        if (n == 0) return 0
        return (cardIndex - ovSelected + n) % n
    }

    /** `←`/`→` riffle tabs; `↑`/`↓` and `Tab` / `Shift+Tab` walk the fanned hand of panes. */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT -> stepSelection(-1)
            OvNav.RIGHT -> stepSelection(1)
            OvNav.UP, OvNav.PANE_PREV -> cyclePane(-1)
            OvNav.DOWN, OvNav.PANE_NEXT -> cyclePane(1)
        }
    }

    /**
     * A single wheel/trackpad swipe riffles exactly one tab: once the ±[WHEEL_STEP]
     * threshold is crossed we latch and ignore the gesture's momentum tail, re-arming
     * after [WHEEL_GESTURE_GAP_MS] of silence or the moment a fresh finger push
     * makes the delta climb back out of the decaying tail. Mirrors the carousel's feel.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (for gesture timing).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        val mag = abs(delta)
        val gap = nowMs - wheelLastTs > WHEEL_GESTURE_GAP_MS
        val risingEdge = wheelLatched && mag > WHEEL_RISE_FLOOR && mag > wheelPrevMag * WHEEL_RISE_FACTOR
        if (gap || risingEdge) { wheelLatched = false; wheelAcc = 0.0 }
        wheelLastTs = nowMs
        wheelPrevMag = mag
        if (wheelLatched) return
        wheelAcc += delta
        if (wheelAcc > WHEEL_STEP) { stepSelection(1); wheelAcc = 0.0; wheelLatched = true }
        if (wheelAcc < -WHEEL_STEP) { stepSelection(-1); wheelAcc = 0.0; wheelLatched = true }
    }

    /**
     * Pointer drag — a gentle parallax that yaws the whole pile so you can peek at
     * the stacked cards' faces; it eases back to centre in [tick] on release
     * (riffle, not drag, is the primary mouse gesture here).
     *
     * @param dx horizontal pointer movement (px) since the last event.
     * @param dy vertical pointer movement (px) since the last event (unused).
     */
    override fun drag(dx: Double, dy: Double) {
        parallaxYaw = (parallaxYaw + dx * 0.0015).coerceIn(-0.3, 0.3)
    }

    /** Every card, plus the fanned pane tiles of the front card once it has opened. */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        for (card in ovCards) meshes.add(card.mesh.asDynamic())
        val sel = ovCards.getOrNull(ovSelected)
        if (sel != null && sel.split > 0.5) for (tile in sel.tiles) meshes.add(tile.mesh.asDynamic())
        return meshes.toTypedArray()
    }

    /**
     * Front card (or one of its fanned tiles) → dive in / focus that pane; a
     * stacked card → riffle it to the front; empty space → close.
     *
     * @param userData the nearest raycast hit's payload (`index` = card, `paneId`
     *   on tiles), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx == ovSelected) beginDive(userData.paneId as? String) else selectCard(idx)
    }

    /** Removes the pile group from the scene (cards/tiles disposed by the core). */
    override fun teardown(scene: Scene) {
        pile?.let { scene.remove(it) }
        pile = null
    }

    /**
     * Footer hint for the deck's controls: the pile riffles left/right through
     * tabs, the arrows walk the front tab's fanned panes, and the wheel riffles too.
     */
    override fun hint(): String =
        "← → riffle tabs · ↑ ↓ panes · scroll to riffle · ⏎ open · click a card · esc close"
}
