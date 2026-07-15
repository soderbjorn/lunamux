/**
 * The **World3D entry cinematic** — the 2D shell tilting away as its panes fly to the ring.
 *
 * Opening the 3D world is otherwise a hard cut: the 2D app vanishes and a rotunda of panes is
 * simply *there*. This turns it into one continuous move:
 *
 *  1. **Hold** — a frozen replica of the whole 2D screen sits at an exact **1:1 pixel mapping**.
 *     The first frame of 3D mode is indistinguishable from the 2D app the user was just looking
 *     at. Nothing moves.
 *  2. **Tilt** — the replica sinks and rotates down like a panel laid onto a floor, revealing the
 *     command center behind it, small with distance.
 *  3. **Fly** — every tab's panes sail out to their ring slots, one by one, tab by tab, with the
 *     tab you were looking at last. The current tab's panes lift off their own cells; a background
 *     tab's panes were never rendered, so they erupt from that tab's **button** in the strip — the
 *     only thing on the 2D screen that represents them. Each cell blanks as its pane leaves and
 *     each tab's button is retired once it has emptied, so the 2D screen visibly hollows out.
 *  4. **Fade** — the spent panel dissolves.
 *  5. **Approach** — the camera flies in to the command center, and normal 3D mode resumes.
 *
 * **Why 1:1 is free.** `perspDistance` is the same formula three.js's `CSS3DRenderer` uses for its
 * CSS `perspective`: a plane at camera-space depth equal to that value renders at CSS scale exactly
 * 1. That is why the world's *front ring pane* is already pixel-accurate for mouse selection, and
 * it means a replica parked `perspDistance` in front of the camera is pixel-identical to the flat
 * 2D shell for free — at any camera distance. @see perspDistance
 *
 * **Why the camera starts pulled back.** The world's resting camera sits at exactly
 * `RING_R + perspDistance(h)`, which puts the *ring itself* at 1:1 — so a replica parked at
 * `RING_R` would tilt away to reveal a command center already filling the screen. There would be
 * no distance to see, and nowhere for the panes to fly *to*. Starting the camera
 * [INTRO_PULLBACK] further out and parking the replica `perspDistance` in front of **it** keeps
 * the replica pixel-identical while pushing the ring into the distance; the camera then flies the
 * pullback back in at the end. @see INTRO_PULLBACK
 *
 * **Why a clone, not the live shell.** [World3DShell] proves the *real* shell can be lifted onto a
 * CSS3D plane and stay fully interactive, and it would work here too — but it would be the wrong
 * tool. The world needs the real terminals reparented onto its own ring planes ([buildRingPane]),
 * and a node cannot be in two places. Cloning sidesteps the conflict entirely:
 *  - **Throwaway.** No restore path and no risk. The clone is deleted when the cinematic ends; the
 *    real shell is only ever hidden, and is restored by the existing, tested [closeWorld3dSpike]
 *    path. Teardown is completely unchanged.
 *  - **Inert.** We never mutate the real frame's layout, so no reflow and no PTY `SIGWINCH`.
 *  - **Ours to mutilate.** Blanking a pane's cell as it flies is just DOM surgery on a decoration.
 *    No "pretend" mode has to be added to the toolkit's real `TabBar` / `LayoutRenderer`, and no
 *    [WindowCommand.SetActiveTab] is ever dispatched mid-session — which the world deliberately
 *    never does, since it would make the app's `refocusActivePane` fight it for focus every frame.
 *
 * A clone is a dead snapshot, but nothing about that is perceptible: the terminals it shows are
 * frozen for about a second while the panel tilts, and the live ones are already on their way to
 * the ring. It renders correctly at all only because the 2D terminal is xterm's **DOM renderer** —
 * real text nodes, which `cloneNode` copies. A canvas renderer would clone blank.
 *
 * **Sequencing** follows the house pattern set by `tickWorldTransit`: one master clock stepped by
 * [cineDt], cumulative frame thresholds, and a `when` chain with each leg renormalising its own
 * 0..1 progress. Registered with [cinematicInFlight] so Enter/Esc fast-forwards it, and gated on
 * [spikeCinematicAnimations] so the "Cinematic" setting turns it off entirely.
 *
 * @see armWorld3dIntro
 * @see tickWorld3dIntro
 * @see WORLD3D-TRANSITION-PLAN.html
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Frames the replica holds at a dead-on 1:1 before anything moves (~0.4 s).
 *
 * The whole point of the cinematic: the user clicks and, for a beat, *nothing happens* — what they
 * see is the screen they were already looking at. Long enough to register as stillness, short
 * enough not to read as a hang.
 */
private const val INTRO_HOLD_FRAMES: Double = 24.0

/** Frames spent tilting the replica down and away (~1.5 s). The reveal. */
private const val INTRO_TILT_FRAMES: Double = 90.0

/** Frames one pane spends in flight, from lifting off the panel to landing in its slot (~1.1 s). */
private const val INTRO_FLY_FRAMES: Double = 66.0

/**
 * Frames between successive panes launching.
 *
 * Much shorter than [INTRO_FLY_FRAMES], so panes are in the air together and read as a stream
 * rather than a queue. This is the knob that keeps a tab with many panes from dragging: total
 * flight is `INTRO_FLY_FRAMES + (n-1) * INTRO_FLY_STAGGER`, so ten panes cost ~3.5 s rather than
 * the ~11 s a strictly one-at-a-time reading would.
 */
private const val INTRO_FLY_STAGGER: Double = 9.0

/**
 * Hard ceiling, in frames, on the whole fly leg however many panes there are (~4 s).
 *
 * The owner's constraint: *"it can't be too slow in case the user has lots of tabs and panes."*
 * Left to itself the leg is `INTRO_FLY_FRAMES + slots × INTRO_FLY_STAGGER`, which grows without
 * bound — eighty panes would be thirteen seconds of cinematic every time you press the button.
 * [beginIntroFlights] compresses the stagger to fit this instead, so a handful of panes get the
 * full unhurried spacing and a busy session simply flies tighter.
 */
private const val INTRO_FLY_BUDGET_FRAMES: Double = 240.0

/**
 * Extra stagger slots spent at each tab boundary, so each tab reads as its own volley rather than
 * one undifferentiated stream. Costed against [INTRO_FLY_BUDGET_FRAMES] like any other slot, so
 * many tabs tighten the gap rather than blowing the budget.
 */
private const val INTRO_TAB_GAP_SLOTS: Int = 2

/**
 * Floor on a pane's launch scale, so a missing or zero-size launch pad can't collapse the plane to
 * nothing and make the pane appear from a single invisible point.
 */
private const val INTRO_MIN_START_SCALE: Double = 0.04

/** Frames spent dissolving the spent replica once its panes have gone (~0.6 s). */
private const val INTRO_FADE_FRAMES: Double = 36.0

/** Frames the camera takes to fly the [INTRO_PULLBACK] back in to the resting pose (~1.7 s). */
private const val INTRO_APPROACH_FRAMES: Double = 100.0

/**
 * How far the replica tips, in degrees, about the camera's X axis.
 *
 * Negative tips the panel's top edge *away* from the camera, so it settles like a screen laid
 * face-up on a floor rather than falling toward the viewer. 70° (not 90°) keeps a sliver of the
 * content legible on the way down — at a dead 90° it would vanish edge-on and read as a glitch.
 */
private const val INTRO_TILT_DEG: Double = -70.0

/**
 * How far the replica sinks as it tilts, as a fraction of viewport height.
 *
 * Load-bearing, not decoration. The camera sits at `y = 0`; a panel that tilts about its own centre
 * *without* sinking would end up edge-on to a level camera and simply disappear. Dropping it puts
 * the camera above the panel, so the tilt reads as "laid down on the floor" and the ring is
 * revealed above it. Tuned by eye.
 */
private const val INTRO_DROP_FRAC: Double = 0.55

/**
 * Extra camera distance, in world units, beyond the resting pose while the cinematic plays.
 *
 * Sets how far away the command center looks at the moment of reveal, and therefore how far the
 * panes appear to fly. The replica is parked to track it, so the 2D screen stays pixel-identical
 * regardless of what this is set to — it only moves the *ring*. Roughly `RING_R * 2`, which puts
 * the rotunda at about a third of its resting apparent size. Flown back in over
 * [INTRO_APPROACH_FRAMES] at the end.
 */
private const val INTRO_PULLBACK: Double = 2300.0

/**
 * A pane's recorded position on the 2D screen, in CSS px relative to the shell frame's top-left.
 *
 * Measured off the **real** shell at arm time — while it is still laid out normally and before the
 * overlay covers it — rather than off the clone, whose CSS3D transform would make
 * `getBoundingClientRect` meaningless. The clone is an exact copy, so the rects apply to it too.
 *
 * @see armWorld3dIntro
 */
internal class IntroRect(val x: Double, val y: Double, val w: Double, val h: Double)

/**
 * One pane's flight from the panel to its ring slot.
 *
 * The launch pad is stored in the panel's **local** plane coordinates, not as a world pose, so it
 * can be re-derived against the panel's *current* tilt every frame. That is what lets a pane sit on
 * the panel and ride it down through the tilt before peeling off, and it means a pane that has not
 * launched yet is parked somewhere truthful rather than hidden at a destination it hasn't reached.
 *
 * The target is whatever the render loop's per-pane loop computed for the slot *this* frame, read
 * straight off the object — so the flight tracks a moving target as the ring eases and scrolls
 * underneath, exactly as `tickWormhole` does for a newborn pane.
 *
 * @property localX x of the launch pad's centre in panel-local coords (origin at panel centre).
 * @property localY y of the launch pad's centre in panel-local coords, +y up.
 * @property startScale the plane's scale at liftoff, matching the launch pad's on-screen size.
 * @property startFrame master-clock reading at which this pane launches.
 * @see beginIntroFlights
 */
internal class IntroFlight(
    val localX: Double,
    val localY: Double,
    val startScale: Double,
    val startFrame: Double,
)

/**
 * Live state of the entry cinematic. Non-null only while it is playing.
 *
 * @property stage the scrubbed clone of the 2D shell being flown.
 * @property obj the CSS3D plane wrapping [stage].
 * @property dropY how far, in world units, the panel sinks over the tilt (viewport-derived).
 * @property homeZ the resting camera depth to fly back in to.
 * @property reverse true when this is the **exit** cinematic — panes flying home to the panel and
 *   the panel tilting back up to 1:1, rather than the other way about. One object serves both so
 *   that every guard keyed on `spikeIntro != null` (the pane veil, [cinematicInFlight], the camera
 *   bob, the warp core's hailing rule) covers the exit for free, without a second flag for each of
 *   them to forget about.
 * @property phase master clock, in 60fps-equivalent frames, stepped by [cineDt].
 * @see tickWorld3dIntro
 */
internal class IntroTransit(
    val stage: HTMLElement,
    val obj: CSS3DObject,
    val dropY: Double,
    val homeZ: Double,
    val reverse: Boolean = false,
) {
    var phase: Double = 0.0

    /** Pane cell rects on the 2D screen, by pane id. Populated at arm; read when flights begin. */
    val paneRects: MutableMap<String, IntroRect> = mutableMapOf()

    /**
     * Tab-button rects in the 2D tab strip, by tab id.
     *
     * The launch pad for every pane of a *background* tab: those panes were never on the 2D
     * screen (their tab wasn't showing), so they have no cell to lift off from — but their tab
     * does. They erupt from it. @see beginIntroFlights
     */
    val tabRects: MutableMap<String, IntroRect> = mutableMapOf()

    /**
     * Master-clock reading at which each tab's button should vanish from the replica — the frame
     * its last pane launches, so the tab visibly empties and then goes. @see tickIntroFlights
     */
    val tabBlankAt: MutableMap<String, Double> = mutableMapOf()

    /** In-flight panes by pane id. Empty until the fly leg starts. @see beginIntroFlights */
    val flights: MutableMap<String, IntroFlight> = mutableMapOf()

    /** One-shot latch: have the flights been planned? @see beginIntroFlights */
    var flightsPlanned: Boolean = false

    /** Total frames the fly leg lasts, once planned. */
    var flyDuration: Double = 0.0

    /** One-shot latch: has the closing camera approach been armed? */
    var approachArmed: Boolean = false
}

/** The entry cinematic in flight, or null. Read by [cinematicInFlight] and [tickWorld3dIntro]. */
internal var spikeIntro: IntroTransit? = null

/**
 * True from the moment the entry hands the camera its closing approach until that approach lands.
 *
 * The cinematic disposes itself the instant it arms the approach — the replica is spent and the
 * panes are home, so there is nothing left for it to drive — which means [spikeIntro] goes null
 * while the camera is still flying in. That is the right moment for *most* things to resume, but not
 * for the shortcuts legend: the arrival is still in progress, and popping a wall of key hints over
 * it lands the reveal on a chore. This flag carries "still arriving" across that handoff, cleared by
 * the tour's own `then` continuation so it is exact — and so a fast-forward clears it too, since a
 * skipped tour still lands and still fires its continuation.
 *
 * @see updateLegendVisibility
 */
internal var spikeIntroSettling: Boolean = false

/**
 * Ring-pane opacity multiplier, 0..1 — the veil that keeps the rotunda's panes off screen until
 * they fly in.
 *
 * Held at **0 for the whole hold and tilt**, then released to 1 for the fly leg, after which
 * [tickIntroFlights] owns each pane's opacity individually (a pane that has not launched yet stays
 * hidden; the replica is drawing it). It must not fade up during the tilt: every pane is about to
 * fly in from the panel, so revealing them at their slots first would show the destination before
 * the journey, then snap them back to the launch pad.
 *
 * Deliberately scoped to **panes only**. The beacon, the station and the cosmos are separate
 * objects this never touches, so what the tilt reveals is the command center standing empty — which
 * is the shot the whole cinematic is built around.
 *
 * It also settles the z-fight for free. The replica starts coplanar with the ring's front pane, and
 * coplanar CSS3D elements z-fight; nudging the replica forward is not an option, since its depth is
 * exactly what makes it 1:1 and 1:1 is the whole trick. Since the panes stay invisible until the
 * replica has tilted well clear, there is never anything to fight over.
 *
 * 1.0 (fully visible) whenever no cinematic is running, so the normal world is unaffected. Applied
 * in the render loop's per-pane opacity write.
 *
 * @see armWorld3dIntro
 */
internal var spikeIntroPaneVeil: Double = 1.0

/**
 * The house easing curve — smootherstep, `t³(t(6t−15)+10)`.
 *
 * Hand-inlined at half a dozen sites across the world (the only other named copy is
 * `worldSmoother` in World3DSpikeOtherWorld.kt, which is file-private). Duplicated here rather than
 * refactored so this spike stays a self-contained diff; hoisting all of them into one shared helper
 * is a legitimate low-risk cleanup for whoever touches this next.
 *
 * @param t raw progress, clamped internally to 0..1.
 * @return eased progress, flat at both ends.
 */
private fun introSmoother(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/**
 * Rename identity attributes in a cloned subtree so the clone is unaddressable.
 *
 * A verbatim `cloneNode(true)` duplicates every `id`, `data-pane` and `data-session` in the
 * document. That is not cosmetic: [closeWorld3dSpike]'s re-home sweep and [reconcileRing] both
 * locate real panes with `document.querySelector("[data-pane=…]")`, and would cheerfully find the
 * replica's copy instead of the live cell — reparenting a live terminal into a decoration that is
 * about to be deleted.
 *
 * `data-pane` is **renamed** rather than dropped, because the fly leg still needs to find a pane's
 * cell in the replica to blank it as the pane departs. Under a private attribute name it is
 * invisible to every real query.
 *
 * @param root the cloned subtree to scrub, in place.
 * @see INTRO_CLONE_PANE_ATTR
 */
private fun scrubIntroClone(root: HTMLElement) {
    root.removeAttribute("id")
    val marked = root.querySelectorAll("[id],[data-pane],[data-session],[data-tab-id]")
    for (i in 0 until marked.length) {
        val el = marked.item(i) as? HTMLElement ?: continue
        el.removeAttribute("id")
        el.removeAttribute("data-session")
        val pane = el.getAttribute("data-pane")
        if (pane != null) {
            el.removeAttribute("data-pane")
            el.setAttribute(INTRO_CLONE_PANE_ATTR, pane)
        }
        val tab = el.getAttribute("data-tab-id")
        if (tab != null) {
            el.removeAttribute("data-tab-id")
            el.setAttribute(INTRO_CLONE_TAB_ATTR, tab)
        }
    }
}

/**
 * Clone-private stand-in for `data-pane`. @see scrubIntroClone
 */
private const val INTRO_CLONE_PANE_ATTR: String = "data-intro-pane"

/**
 * Clone-private stand-in for the toolkit tab bar's `data-tab-id`. @see scrubIntroClone
 */
private const val INTRO_CLONE_TAB_ATTR: String = "data-intro-tab"

/**
 * Arm the entry cinematic: clone the 2D shell, park it at 1:1, and pull the camera back.
 *
 * Called from [openWorld3dSpike] **before** [buildRingPane] reparents the live terminals out of the
 * 2D shell — the clone has to be taken while the shell is still whole, or it would be a snapshot of
 * a screen full of holes — and **after** `syncWorld3dRuntimeFromSettings`, which seeds
 * [spikeCinematicAnimations].
 *
 * No-ops (leaving the veil at 1.0, so the world opens exactly as it does today) when the
 * "Cinematic" setting is off, or when there is no shell to clone.
 *
 * @param scene the world's CSS3D scene, to add the replica plane to.
 * @return true if the cinematic was armed; false if the world should open with a hard cut.
 * @see tickWorld3dIntro
 * @see disposeWorld3dIntro
 */
internal fun armWorld3dIntro(scene: Scene): Boolean {
    spikeIntro = null
    spikeIntroSettling = false
    spikeIntroPaneVeil = 1.0
    if (!spikeCinematicAnimations) return false

    val app = document.getElementById("app") as? HTMLElement ?: return false
    val frame = app.firstElementChild as? HTMLElement ?: return false

    val w = window.innerWidth
    val h = window.innerHeight
    val homeZ = RING_R + perspDistance(h)

    val paneRects = mutableMapOf<String, IntroRect>()
    val tabRects = mutableMapOf<String, IntroRect>()
    measureIntroRects(frame, paneRects, tabRects)

    val stage = frame.cloneNode(true) as? HTMLElement ?: return false
    scrubIntroClone(stage)
    // A CSS3D plane's world size is its element's pixel size, so the replica needs explicit
    // dimensions — the original filled `#app` by percentage. Pinned to the viewport it already
    // occupied, which is what makes the plane exactly cover the screen at 1:1.
    stage.style.setProperty("width", "${w}px")
    stage.style.setProperty("height", "${h}px")
    // Inert scenery: it must never eat a click meant for the world beneath.
    stage.style.setProperty("pointer-events", "none")

    val obj = CSS3DObject(stage)
    // The 1:1 pose: exactly perspDistance in front of the pulled-back camera, so it renders at CSS
    // scale 1 — pixel-identical to the 2D shell it replaces. @see INTRO_PULLBACK
    obj.position.set(0.0, 0.0, RING_R + INTRO_PULLBACK)
    obj.rotation.set(0.0, 0.0, 0.0)
    scene.add(obj)

    // Hold the camera out at the pullback for the whole cinematic. Written as the loop's own stored
    // pose (with spikeCamFlown, which is what makes it read that state at all) rather than poked
    // straight onto the three.js camera — so when the cinematic hands off, flyCamTo captures a pose
    // that is actually where the camera is, instead of snapping home from a stale one.
    spikeCamFlown = true
    spikeCamX = 0.0
    spikeCamY = 0.0
    spikeCamZ = homeZ + INTRO_PULLBACK
    spikeCamFx = 0.0
    spikeCamFy = 0.0
    spikeCamFz = -1.0
    spikeCamUx = 0.0
    spikeCamUy = 1.0
    spikeCamUz = 0.0

    // Hide the ring until the replica has tilted clear of it. @see spikeIntroPaneVeil
    spikeIntroPaneVeil = 0.0
    val intro = IntroTransit(stage, obj, h * INTRO_DROP_FRAC, homeZ)
    intro.paneRects.putAll(paneRects)
    intro.tabRects.putAll(tabRects)
    spikeIntro = intro
    return true
}

/**
 * Measure every pane cell and tab button on the live 2D shell.
 *
 * Taken off the **real** frame, never the clone: the clone is about to be wrapped in a CSS3D
 * transform, which makes `getBoundingClientRect` on it meaningless. It works at exit as well as at
 * entry because the world hides the 2D shell with `visibility: hidden` rather than
 * `display: none` — deliberately, so its layout stays measurable — so the rects are still live and
 * true even though nothing is on screen.
 *
 * @param frame the real shell frame.
 * @param intoPanes map to fill with pane-cell rects, by pane id.
 * @param intoTabs map to fill with tab-button rects, by tab id.
 */
private fun measureIntroRects(
    frame: HTMLElement,
    intoPanes: MutableMap<String, IntroRect>,
    intoTabs: MutableMap<String, IntroRect>,
) {
    val frameRect = frame.getBoundingClientRect()
    val cells = frame.querySelectorAll("[data-pane]")
    for (i in 0 until cells.length) {
        val cell = cells.item(i) as? HTMLElement ?: continue
        val id = cell.getAttribute("data-pane") ?: continue
        val r = cell.getBoundingClientRect()
        if (r.width <= 0.0 || r.height <= 0.0) continue // an unmounted / collapsed cell
        intoPanes[id] = IntroRect(r.left - frameRect.left, r.top - frameRect.top, r.width, r.height)
    }
    // Tab buttons: the launch pad for background tabs, whose panes have no cell of their own.
    // A tab pushed into the overflow menu has no rendered button and so no usable rect — those
    // fall back to the panel centre when the flights are planned.
    val tabEls = frame.querySelectorAll("[data-tab-id]")
    for (i in 0 until tabEls.length) {
        val el = tabEls.item(i) as? HTMLElement ?: continue
        val id = el.getAttribute("data-tab-id") ?: continue
        val r = el.getBoundingClientRect()
        if (r.width <= 0.0 || r.height <= 0.0) continue
        intoTabs[id] = IntroRect(r.left - frameRect.left, r.top - frameRect.top, r.width, r.height)
    }
}

/**
 * Arm the **exit** cinematic: the entry played backwards.
 *
 * A replica of the 2D shell reappears lying flat where the entry left it, the camera retreats to
 * the pullback, every pane flies home from its ring slot onto its own patch of the panel, and the
 * panel tilts back up to 1:1. At 1:1 it is pixel-identical to the real shell, so
 * [finishCloseWorld3dSpike] can swap them without anyone seeing the join — the same trick the entry
 * uses, in the other direction.
 *
 * **Why the replica has holes, and why that's fine.** Cloning the shell now captures it exactly as
 * the world left it: cells present but *empty*, because their terminals were reparented onto ring
 * planes at open. Those very terminals are the things flying home — and each parks on its own cell,
 * filling its own hole. The panel is only ever whole once the panes have landed in it, which is
 * precisely the shot.
 *
 * Called from [closeWorld3dSpike]. Declines (so the world closes immediately, as it always did)
 * when the "Cinematic" setting is off, when there is no shell, or when there is nothing to fly.
 *
 * @return true if the outro was armed and the caller should NOT close yet.
 * @see tickWorld3dIntro
 */
internal fun armWorld3dOutro(): Boolean {
    if (!spikeCinematicAnimations) return false
    if (spikePanes.isEmpty()) return false
    val scene = spikeCssScene ?: return false
    val app = document.getElementById("app") as? HTMLElement ?: return false
    val frame = app.firstElementChild as? HTMLElement ?: return false
    // Mid-entry exit: drop the entry's replica before building the exit's, or two panels end up
    // in the scene fighting over the same depth.
    disposeWorld3dIntro()

    val w = window.innerWidth
    val h = window.innerHeight
    val homeZ = RING_R + perspDistance(h)

    val paneRects = mutableMapOf<String, IntroRect>()
    val tabRects = mutableMapOf<String, IntroRect>()
    measureIntroRects(frame, paneRects, tabRects)

    val stage = frame.cloneNode(true) as? HTMLElement ?: return false
    scrubIntroClone(stage)
    stage.style.setProperty("width", "${w}px")
    stage.style.setProperty("height", "${h}px")
    stage.style.setProperty("pointer-events", "none")

    val obj = CSS3DObject(stage)
    val dropY = h * INTRO_DROP_FRAC
    // Start where the entry finished: lying flat, sunk, at the pullback depth.
    obj.position.set(0.0, -dropY, RING_R + INTRO_PULLBACK)
    obj.rotation.set(INTRO_TILT_DEG * PI / 180.0, 0.0, 0.0)
    scene.add(obj)

    // Retreat to the pullback so the panel reads at 1:1 by the time it stands up. The fly leg is
    // held to at least this long ([tickWorld3dIntro]) so the camera is always in place first.
    flyCamTo(
        0.0, 0.0, homeZ + INTRO_PULLBACK,
        0.0, 0.0, 0.0,
        landPristine = false,
        frames = INTRO_APPROACH_FRAMES,
        pullout = 0.0,
        rise = 0.0,
    )

    spikeIntroPaneVeil = 1.0
    val intro = IntroTransit(stage, obj, dropY, homeZ, reverse = true)
    intro.paneRects.putAll(paneRects)
    intro.tabRects.putAll(tabRects)
    spikeIntro = intro
    // Drop the key hints the moment the exit is armed — you have already decided to leave, and they
    // must not ride the 2D shell back down with it.
    updateLegendVisibility()
    return true
}

/**
 * Plan the fly leg: work out which panes launch, from where, and when.
 *
 * Deferred to the moment the leg starts rather than done at arm time, because [spikePanes] does not
 * exist yet when [armWorld3dIntro] runs (it must clone *before* [buildRingPane] builds them), and
 * `spikeTabIndex` is not seeded until after. By the fly leg both are settled.
 *
 * **Every** tab flies, in tab-strip order, with the tab you were looking at **last** — so the
 * sequence ends on the panes you recognise, in front of where the camera settles.
 *
 * The catch a background tab poses: its panes were never rendered on the 2D screen (their tab
 * wasn't showing), so they have no cell to lift off *from*. But their **tab button** was on screen,
 * and it is the only thing there that represents them — so they erupt from it, tiny, and its button
 * is retired once they have all gone. That is also what lets this dispense with tab switching
 * entirely: no [WindowCommand.SetActiveTab] is dispatched, no terminal is mounted, nothing about
 * the real app changes. It is all theater on a clone.
 *
 * @param intro the live cinematic.
 * @param tiltRad the panel's settled tilt, to launch the panes from its plane.
 */
private fun beginIntroFlights(intro: IntroTransit) {
    intro.flightsPlanned = true
    val w = window.innerWidth.toDouble()
    val h = window.innerHeight.toDouble()
    val cur = spikeTabIndex
    // Absolute readings on the master clock. Entering, the volley waits for the tilt to land;
    // leaving, the panel is already flat and the panes set off at once.
    val flyStart = if (intro.reverse) 0.0 else INTRO_HOLD_FRAMES + INTRO_TILT_FRAMES

    // Tab order: every other tab first, in tab-strip order, and the tab you were actually looking
    // at LAST — so the sequence finishes on the panes the user recognises, landing on the one the
    // camera settles in front of.
    val tabOrds = spikePanes.map { it.tabOrd }.distinct().sorted()
    val order = tabOrds.filter { it != cur } + tabOrds.filter { it == cur }

    // Lay every pane out on a slot timeline first, so the stagger can be costed against a budget
    // before anything is committed. A tab boundary spends extra slots, which is what makes each
    // tab read as its own little volley rather than one undifferentiated stream.
    class Slot(val pane: RingPane, val index: Int)
    val slots = mutableListOf<Slot>()
    var cursor = 0
    for ((t, ord) in order.withIndex()) {
        if (t > 0) cursor += INTRO_TAB_GAP_SLOTS
        val panes = spikePanes.filter { it.tabOrd == ord }.sortedBy { it.paneOrdInTab }
        for (p in panes) {
            slots.add(Slot(p, cursor))
            cursor += 1
        }
    }
    if (slots.isEmpty()) {
        intro.flyDuration = 0.0
        return
    }

    val lastSlot = slots.last().index
    // Compress the stagger so the whole volley fits the budget however many panes there are. Ten
    // panes ride the full [INTRO_FLY_STAGGER]; eighty tighten up until they fit. Without this the
    // leg grows without bound and a busy session would sit through a minute of cinematic.
    val stagger =
        if (lastSlot <= 0) 0.0
        else minOf(INTRO_FLY_STAGGER, (INTRO_FLY_BUDGET_FRAMES - INTRO_FLY_FRAMES) / lastSlot)

    for (slot in slots) {
        val p = slot.pane
        // Where this pane lifts off from. Its own cell if it was on screen; otherwise its tab's
        // button in the strip — a background tab's panes were never rendered, so the tab is the
        // only thing on the 2D screen that represents them. Falls back to the panel centre for a
        // tab that had no rendered button at all (pushed into the overflow menu).
        val r = if (p.tabOrd == cur) {
            intro.paneRects[p.paneId] ?: intro.tabRects[p.tabId]
        } else {
            intro.tabRects[p.tabId]
        } ?: IntroRect(w * 0.5, h * 0.5, 0.0, 0.0)

        // Launch pad centre, in the panel's local plane coords (origin at the panel's centre,
        // +y up). Kept local, not baked to a world pose: [introFlightPose] re-derives it against
        // whatever tilt the panel currently has, so a parked pane rides the panel down.
        val lx = (r.x + r.w * 0.5) - w * 0.5
        val ly = -((r.y + r.h * 0.5) - h * 0.5)
        // The ring's wrapper is a fixed screen box; scale it to the size the launch pad actually
        // had on the 2D screen, so a pane leaves at the size it was sitting at — and a tab-button
        // eruption starts tiny, since a tab is a fraction of a pane's width. Clamped off zero so a
        // fallback rect can't collapse the plane to nothing.
        val startScale =
            if (p.baseCw > 0.0) (r.w / p.baseCw).coerceAtLeast(INTRO_MIN_START_SCALE)
            else INTRO_MIN_START_SCALE
        val startFrame = flyStart + slot.index * stagger
        intro.flights[p.paneId] = IntroFlight(
            localX = lx,
            localY = ly,
            startScale = startScale,
            startFrame = startFrame,
        )
        // The tab's button goes when its last pane leaves it.
        val prev = intro.tabBlankAt[p.tabId]
        if (prev == null || startFrame > prev) intro.tabBlankAt[p.tabId] = startFrame
    }
    intro.flyDuration = INTRO_FLY_FRAMES + lastSlot * stagger
}

/**
 * Where a pane's launch pad is in world space, given the panel's pose right now.
 *
 * Re-derived every frame rather than baked once, so a pane parked on the panel tilts and sinks
 * *with* it and only leaves when its turn comes.
 *
 * @param f the flight, holding the pad in panel-local coords.
 * @param tiltRad the panel's current tilt about X.
 * @param dropY the panel's current y offset.
 * @return world (x, y, z) of the pad's centre.
 */
private fun introFlightPose(f: IntroFlight, tiltRad: Double, dropY: Double): Triple<Double, Double, Double> {
    val c = cos(tiltRad)
    val s = sin(tiltRad)
    return Triple(
        f.localX,
        dropY + f.localY * c,
        RING_R + INTRO_PULLBACK + f.localY * s,
    )
}

/**
 * Drive the panes for this frame: park, launch, lerp, land.
 *
 * Runs *after* the render loop's per-pane loop has already written each pane's ring-slot transform,
 * so `p.obj` currently holds the flight's **target** — read it, blend from the launch pad, and write
 * back. Same override idiom as `tickWormhole` / `tickBundles`, and it means the flight tracks a live
 * target: the ring keeps easing underneath and the pane still lands true.
 *
 * A pane that has not launched yet is **parked on the panel**, at its pad, invisible. Parking it
 * (rather than merely hiding it where the loop left it, at its ring slot) is what makes this robust:
 * the loop is not the only thing that writes pane opacity, and anything that forces a pane visible
 * mid-cinematic — the warp core's hailing rule was exactly this — would otherwise reveal it sitting
 * at its *destination* before it had travelled, and then it would appear to fly in on top of a copy
 * of itself. Parked, the worst such a bug can do is show the pane where it honestly is.
 *
 * On launch its cell is blanked from the replica, so the 2D screen empties out as its panes leave.
 *
 * @param intro the live cinematic.
 * @param tiltRad the panel's current tilt, for deriving launch pads.
 * @param dropY the panel's current y offset.
 */
private fun tickIntroFlights(intro: IntroTransit, tiltRad: Double, dropY: Double) {
    // Retire each tab's button from the replica as its last pane leaves it, so the strip empties
    // out alongside the panes and there is almost nothing left of the 2D screen to dissolve.
    val goneTabs = intro.tabBlankAt.filterValues { intro.phase >= it }.keys
    for (tabId in goneTabs) {
        blankIntroTab(intro, tabId)
        intro.tabBlankAt.remove(tabId)
    }
    for (p in spikePanes) {
        val f = intro.flights[p.paneId] ?: continue
        val local = (intro.phase - f.startFrame) / INTRO_FLY_FRAMES
        val (sx, sy, sz) = introFlightPose(f, tiltRad, dropY)
        if (local < 0.0) {
            // Entering, this pane has yet to launch: park it on the panel, where the replica is
            // drawing its likeness. Leaving, it has yet to set off: leave it alone at its slot,
            // which is where it honestly still is.
            if (!intro.reverse) {
                p.obj.position.set(sx, sy, sz)
                p.obj.rotation.set(tiltRad, 0.0, 0.0)
                p.obj.scale.set(f.startScale, f.startScale, f.startScale)
                p.wrapper.style.setProperty("opacity", "0")
            }
            continue
        }
        if (local >= 1.0 && !intro.reverse) {
            // Landed in the ring. Leave the loop's own transform and opacity alone from here on.
            intro.flights.remove(p.paneId)
            blankIntroCell(intro, p.paneId)
            continue
        }
        if (!intro.reverse) blankIntroCell(intro, p.paneId)
        // Entering, the pane travels pad → slot; leaving, slot → pad. Reading the target live off
        // p.obj (where the per-pane loop just wrote the slot) makes both directions the same lerp
        // with its ends swapped — and a pane that has arrived home is simply pinned at its pad, so
        // it rides the panel up through the tilt as part of it. That parking is the whole reason
        // the exit lands on a whole 2D screen rather than a panel full of holes.
        val s = introSmoother(local.coerceAtMost(1.0))
        val tx = p.obj.position.x as Double
        val ty = p.obj.position.y as Double
        val tz = p.obj.position.z as Double
        val trx = p.obj.rotation.x as Double
        val try_ = p.obj.rotation.y as Double
        val trz = p.obj.rotation.z as Double
        val ts = p.obj.scale.x as Double
        val blend = if (intro.reverse) 1.0 - s else s
        p.obj.position.set(
            sx + (tx - sx) * blend,
            sy + (ty - sy) * blend,
            sz + (tz - sz) * blend,
        )
        p.obj.rotation.set(
            tiltRad + (trx - tiltRad) * blend,
            try_ * blend,
            trz * blend,
        )
        val sc = f.startScale + (ts - f.startScale) * blend
        p.obj.scale.set(sc, sc, sc)
        p.wrapper.style.setProperty("opacity", "1")
        p.wrapper.style.setProperty("display", "")
    }
}

/**
 * Blank a departed pane's cell in the replica, so the 2D screen visibly empties out.
 *
 * `visibility` rather than `display`, so the panel's layout is untouched and the surrounding chrome
 * stays exactly where it was — the hole should read as the pane having *left*, not as the screen
 * reflowing around it. Idempotent.
 *
 * @param intro the live cinematic.
 * @param paneId the pane whose cell should go.
 */
private fun blankIntroCell(intro: IntroTransit, paneId: String) {
    val cell = intro.stage.querySelector("[$INTRO_CLONE_PANE_ATTR=\"$paneId\"]") as? HTMLElement
        ?: return
    cell.style.setProperty("visibility", "hidden")
}

/**
 * Retire an emptied tab's button from the replica's tab strip.
 *
 * `visibility` rather than `display` for the same reason as [blankIntroCell]: the surviving tabs
 * must not slide along to close the gap. The strip should look like it is being *emptied*, not
 * like the app is re-laying-out behind the user's back.
 *
 * @param intro the live cinematic.
 * @param tabId the tab whose button should go.
 */
private fun blankIntroTab(intro: IntroTransit, tabId: String) {
    val el = intro.stage.querySelector("[$INTRO_CLONE_TAB_ATTR=\"$tabId\"]") as? HTMLElement
        ?: return
    el.style.setProperty("visibility", "hidden")
}

/**
 * Advance the entry cinematic one frame.
 *
 * Called from the render loop before `css.render`, alongside `tickWormhole` / `tickWorldTransit`,
 * so its 3D writes land in the same frame — and after the per-pane loop, so the flights can
 * override the ring-slot transforms it just computed. A no-op once finished or never armed.
 *
 * Five legs on one master clock ([IntroTransit.phase]) stepped by [cineDt], so the Enter/Esc skip
 * and high-refresh displays both behave. The last leg hands the camera to [flyCamTo] and the
 * cinematic disposes itself, letting the world take over mid-approach — the tour finishes on its
 * own and lands pristine.
 *
 * @see armWorld3dIntro
 */
internal fun tickWorld3dIntro() {
    val intro = spikeIntro ?: return

    intro.phase += cineDt()
    val phase = intro.phase
    val tiltFullRad = INTRO_TILT_DEG * PI / 180.0
    val tHold = INTRO_HOLD_FRAMES
    val tTilt = tHold + INTRO_TILT_FRAMES

    // Plan on the very first tick, not when the fly leg starts. [spikePanes] and `spikeTabIndex`
    // are both settled by now (the open path builds and seeds them before the loop's first frame),
    // and planning early means every pane is *parked on the panel* from frame one instead of
    // loitering invisibly at its ring slot waiting for a turn. @see tickIntroFlights
    if (!intro.flightsPlanned) beginIntroFlights(intro)
    val tFly = tTilt + intro.flyDuration
    val tFade = tFly + INTRO_FADE_FRAMES

    // Declared before the branch and applied once after, so each leg only states what it changes.
    val tiltRad: Double
    val dropY: Double
    val veil: Double
    val opacity: Double

    if (intro.reverse) {
        // The exit: panes home, panel stands up, hand back to the 2D app.
        //
        // The fly leg is held to at least [INTRO_APPROACH_FRAMES] so the camera's retreat to the
        // pullback (armed in [armWorld3dOutro]) has always landed before the panel starts standing
        // up — the panel is only 1:1 at that distance, and 1:1 at the end is the entire point,
        // since it is what makes the swap to the real shell invisible.
        val rFly = maxOf(intro.flyDuration, INTRO_APPROACH_FRAMES)
        val rTilt = rFly + INTRO_TILT_FRAMES
        val rHold = rTilt + INTRO_HOLD_FRAMES
        val rTiltRad: Double
        val rDropY: Double
        when {
            phase < rFly -> {
                rTiltRad = tiltFullRad
                rDropY = -intro.dropY
            }
            phase < rTilt -> {
                // Stand back up: the inverse of the entry's tilt, panes riding it as they go.
                val s = 1.0 - introSmoother((phase - rFly) / INTRO_TILT_FRAMES)
                rTiltRad = tiltFullRad * s
                rDropY = -intro.dropY * s
            }
            phase < rHold -> {
                rTiltRad = 0.0
                rDropY = 0.0
            }
            else -> {
                // Flat, 1:1, and indistinguishable from the real thing — swap it back for real.
                // finishCloseWorld3dSpike disposes this cinematic on its way through.
                finishCloseWorld3dSpike()
                return
            }
        }
        intro.obj.rotation.set(rTiltRad, 0.0, 0.0)
        intro.obj.position.set(0.0, rDropY, RING_R + INTRO_PULLBACK)
        intro.stage.style.setProperty("opacity", "1")
        spikeIntroPaneVeil = 1.0
        tickIntroFlights(intro, rTiltRad, rDropY)
        return
    }

    when {
        phase < tHold -> {
            tiltRad = 0.0
            dropY = 0.0
            veil = 0.0
            opacity = 1.0
        }
        phase < tTilt -> {
            val s = introSmoother((phase - tHold) / INTRO_TILT_FRAMES)
            tiltRad = tiltFullRad * s
            dropY = -intro.dropY * s
            // Ring stays fully veiled through the tilt. It must NOT fade up here: every pane is
            // about to fly in from the panel, and revealing them at their slots first would show
            // the destination before the journey — then snap them back to the launch pad the
            // moment the fly leg starts. What the tilt reveals is the *empty* command center: the
            // beacon, the station and the cosmos are separate objects and this veil never touched
            // them, which is exactly the intended shot. @see spikeIntroPaneVeil
            veil = 0.0
            opacity = 1.0
        }
        phase < tFly -> {
            tiltRad = tiltFullRad
            dropY = -intro.dropY
            veil = 1.0
            opacity = 1.0
        }
        phase < tFade -> {
            val s = introSmoother((phase - tFly) / INTRO_FADE_FRAMES)
            tiltRad = tiltFullRad
            dropY = -intro.dropY
            veil = 1.0
            opacity = 1.0 - s
        }
        else -> {
            // Hand the camera the pullback to fly back in, then get out of the way. flyCamTo brings
            // the setting gate, Enter/Esc skip and the pristine landing with it, so the world is in
            // its normal resting state by the time the tour ends.
            if (!intro.approachArmed) {
                intro.approachArmed = true
                spikeIntroSettling = true
                flyCamTo(
                    0.0, 0.0, intro.homeZ,
                    0.0, 0.0, 0.0,
                    landPristine = true,
                    frames = INTRO_APPROACH_FRAMES,
                    pullout = 0.0,
                    rise = 0.0,
                    then = {
                        // Arrived. Only now is the entry really over, so let the shortcuts in.
                        spikeIntroSettling = false
                        updateLegendVisibility()
                    },
                )
            }
            disposeWorld3dIntro()
            return
        }
    }

    intro.obj.rotation.set(tiltRad, 0.0, 0.0)
    intro.obj.position.set(0.0, dropY, RING_R + INTRO_PULLBACK)
    intro.stage.style.setProperty("opacity", opacity.toString())
    spikeIntroPaneVeil = veil
    tickIntroFlights(intro, tiltRad, dropY)
}

/**
 * Tear the entry cinematic down: drop the replica and un-veil the ring.
 *
 * Called on natural completion from [tickWorld3dIntro], and unconditionally from
 * [closeWorld3dSpike] so closing the world mid-cinematic can't strand the replica in the scene or
 * leave the ring veiled for the next open. Idempotent.
 *
 * Note it does **not** restore the camera: on the natural path [tickWorld3dIntro] has just armed
 * the approach tour, which owns the camera from here; on the close path the whole world is going
 * away and `spikeCamFlown` is reset by the open path anyway.
 */
internal fun disposeWorld3dIntro() {
    val intro = spikeIntro ?: run {
        spikeIntroPaneVeil = 1.0
        return
    }
    spikeCssScene?.remove(intro.obj)
    intro.stage.remove()
    spikeIntro = null
    spikeIntroPaneVeil = 1.0
}
