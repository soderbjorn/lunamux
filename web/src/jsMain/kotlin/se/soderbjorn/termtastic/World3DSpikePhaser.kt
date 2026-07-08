/**
 * The **phaser-fire pane close** — the [PHASER_CLOSE_ENABLED] feature-flag alternative
 * to [confirmRemove]'s instant shrink-out. Instead of vanishing at once, a removed pane
 * lingers at the ring front while the camera pours a several-second burst of Star-Trek-
 * style phaser fire at it: irregular bright bolts streak from the viewer (near the bottom
 * of the screen — the ship's "phaser banks") and converge on the pane, its background
 * heats to a deepening, flickering red, and only then does it collapse via the normal
 * despawn.
 *
 * Two layers cooperate:
 *  - a per-pane **heat veil** ([RingPane.phaserTint]) inside the CSS3D wrapper, so the
 *    reddening rides the pane's 3D transform (bob, zoom, rotation) exactly; and
 *  - a single full-viewport **2D canvas** ([spikePhaserCanvas], z-index just above the
 *    CSS3D pane layer and below the chrome) onto which every bolt and the growing impact
 *    glow are drawn in screen space each frame. The burning pane's world position is
 *    projected to screen with the shared camera so the bolts always land on it.
 *
 * Driven entirely from the render loop's per-frame [tickPhaser]; armed by
 * [startPhaserDeath] and torn down with the overlay on [closeWorld3dSpike].
 *
 * @see PHASER_CLOSE_ENABLED
 * @see startPhaserDeath
 * @see tickPhaser
 * @see confirmRemove
 */
package se.soderbjorn.termtastic

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.PerspectiveCamera

/**
 * One in-flight phaser bolt: a bright streak that flies from a fixed off-screen emitter
 * point toward its target pane's live screen position, jittered into a jagged "fire"
 * shape. Advanced and drawn each frame by [tickPhaser]; culled once [age] passes
 * [PHASER_BOLT_LIFE] or its pane is gone.
 *
 * @property paneId the burning pane this bolt is aimed at (its live projection is the
 *   target, so the beam tracks the pane even as it bobs).
 * @property ox/[oy] the emitter origin in screen px (near the viewer — the "camera").
 * @property age elapsed frames since spawn; the beam head lerps origin→target by it.
 * @property width core beam width in px (the outer glow is a multiple of it).
 * @property jitterAmp peak perpendicular wobble of the jagged beam in px.
 * @property jitters the fixed per-segment wobble fractions (−0.5..0.5), so the beam keeps
 *   a stable jagged shape as its head advances rather than reshuffling every frame.
 * @see tickPhaser
 */
internal class PhaserBolt(
    val paneId: String,
    val ox: Double,
    val oy: Double,
    var age: Double,
    val width: Double,
    val jitterAmp: Double,
    val jitters: DoubleArray,
)

/**
 * The full-viewport 2D canvas the phaser-fire close paints its bolts and impact glow
 * onto, or `null` until first armed. A child of [spikeOverlay], so it is discarded
 * wholesale on [closeWorld3dSpike]; its reference is nulled there. @see ensurePhaserCanvas
 */
internal var spikePhaserCanvas: HTMLCanvasElement? = null

/** Every phaser bolt currently in flight, advanced/drawn/culled each frame by [tickPhaser]. */
internal val spikePhaserBolts: MutableList<PhaserBolt> = mutableListOf()

/**
 * Arms the [PHASER_CLOSE_ENABLED] phaser-fire close on [p]: starts its frame clock and
 * inserts the red "heat" veil into its wrapper (below the z-index:2 title bar, so the
 * title stays legible while the content reddens). Idempotent — a second call on an
 * already-phasering pane is a no-op. Called from [confirmRemove] in place of the plain
 * `dying = true`; the pane is not marked dying until [tickPhaser] finishes the barrage.
 *
 * @param p the fronted pane being removed.
 * @see tickPhaser @see confirmRemove
 */
internal fun startPhaserDeath(p: RingPane) {
    if (p.phaserPhase >= 0.0) return
    p.phaserPhase = 0.0
    p.phaserNextBolt = 0.0
    if (p.phaserTint == null) {
        val tint = document.createElement("div") as HTMLElement
        tint.style.cssText = "position:absolute;inset:0;pointer-events:none;z-index:1;opacity:0;" +
            "background:radial-gradient(circle at 50% 42%," +
            "rgba(255,110,50,0.65),rgba(200,20,0,0.9) 68%,rgba(110,0,0,0.96));" +
            "box-shadow:inset 0 0 80px rgba(255,50,0,0.85);"
        p.wrapper.appendChild(tint)
        p.phaserTint = tint
    }
}

/**
 * Per-frame driver of the phaser-fire close, called near the end of the render loop
 * ([startSpikeLoop]) after `css.render` so the camera matrices are current. For every
 * pane whose [RingPane.phaserPhase] is live it advances the clock, flickers the red heat
 * veil toward [PHASER_TINT_MAX], and spawns irregular bolt volleys (tightening as the
 * barrage builds); then it advances, draws and culls every bolt, and paints the growing
 * impact glow over each burning pane. A pane whose clock passes [PHASER_TOTAL_FRAMES] is
 * marked [RingPane.dying] (handing off to the normal shrink-out) and its veil removed;
 * [reconcileRing] is then re-run once so navigation/selection re-clamps around the loss.
 *
 * No-ops cheaply (hiding the canvas) when nothing is burning and no bolts remain.
 *
 * @param camera the shared spike camera, used to project each burning pane to the screen.
 * @see startPhaserDeath @see startSpikeLoop
 */
internal fun tickPhaser(camera: PerspectiveCamera) {
    if (!PHASER_CLOSE_ENABLED) return // zero-cost when the flag is off (nothing can be phasering)
    val active = spikePanes.filter { it.phaserPhase >= 0.0 }
    if (active.isEmpty() && spikePhaserBolts.isEmpty()) {
        spikePhaserCanvas?.let { if (it.style.display != "none") it.style.display = "none" }
        return
    }
    val w = window.innerWidth
    val h = window.innerHeight
    val canvas = ensurePhaserCanvas() ?: return
    if (canvas.width != w) canvas.width = w
    if (canvas.height != h) canvas.height = h
    canvas.style.display = "block"
    val ctx = canvas.getContext("2d").asDynamic()
    if (ctx == null) return
    ctx.clearRect(0.0, 0.0, w.toDouble(), h.toDouble())

    // --- Advance each burning pane: heat veil + irregular bolt volleys. ---
    val completed = mutableListOf<RingPane>()
    for (p in active) {
        p.phaserPhase += spikeDtFrames
        val prog = (p.phaserPhase / PHASER_TOTAL_FRAMES).coerceIn(0.0, 1.0)
        p.phaserTint?.let { tint ->
            val flick = 1.0 - PHASER_TINT_FLICKER * Random.nextDouble()
            tint.style.opacity = (PHASER_TINT_MAX * prog * flick).toString()
        }
        if (p.phaserPhase < PHASER_TOTAL_FRAMES) {
            // Fire only during the barrage; the collapse phase runs quietly (tint + implode).
            p.phaserNextBolt -= spikeDtFrames
            if (p.phaserNextBolt <= 0) {
                // Slightly more bolts per volley, and volleys a touch closer, as it peaks.
                val volley = 1 + (Random.nextDouble() * (0.4 + prog * 1.1)).toInt()
                repeat(volley) { spawnPhaserBolt(p, w, h) }
                val span = PHASER_BOLT_INTERVAL_MAX - PHASER_BOLT_INTERVAL_MIN
                p.phaserNextBolt = (PHASER_BOLT_INTERVAL_MIN +
                    (Random.nextDouble() * span * (1.0 - prog * 0.55)).toInt()).toDouble()
            }
        } else if (p.phaserPhase >= PHASER_TOTAL_FRAMES + PHASER_COLLAPSE_FRAMES) {
            completed.add(p)
        }
    }

    // --- Draw + advance + cull the bolts, then the heat glow, additively. ---
    ctx.globalCompositeOperation = "lighter"
    val boltIt = spikePhaserBolts.iterator()
    while (boltIt.hasNext()) {
        val b = boltIt.next()
        val pane = spikePanes.firstOrNull { it.paneId == b.paneId }
        val target = pane?.let { projectToScreen(it.obj, camera, w, h) }
        if (target != null) drawPhaserBolt(ctx, b, target.first, target.second)
        b.age += spikeDtFrames
        val impacted = b.age > PHASER_BOLT_LIFE
        // A landed bolt rocks the pane: add recoil (the render loop punches bulge + scale).
        if (impacted && pane != null) {
            pane.phaserRecoil = (pane.phaserRecoil + PHASER_RECOIL_PER_HIT).coerceAtMost(1.6)
        }
        if (impacted || target == null) boltIt.remove()
    }
    for (p in active) {
        val prog = (p.phaserPhase / PHASER_TOTAL_FRAMES).coerceIn(0.0, 1.0)
        projectToScreen(p.obj, camera, w, h)?.let { (tx, ty) -> drawHeatGlow(ctx, tx, ty, prog) }
    }
    ctx.globalCompositeOperation = "source-over"

    // --- Dispose the panes whose implosion just finished, then re-reconcile once. ---
    if (completed.isNotEmpty()) {
        for (p in completed) {
            p.phaserPhase = -1.0
            p.phaserRecoil = 0.0
            p.phaserTint?.let { runCatching { it.remove() } }
            p.phaserTint = null
            // The implosion already scaled it to ~0; keep it there (birth 0, filter cleared)
            // so it stays invisible — no one-frame pop — until the death sweep disposes it.
            runCatching {
                p.wrapper.style.removeProperty("filter")
                p.bulgeMap?.setAttribute("scale", "0")
            }
            p.birth = 0.0
            p.dying = true
        }
        spikePhaserBolts.removeAll { b -> completed.any { it.paneId == b.paneId } }
        reconcileRing()
    }
}

/**
 * Lazily creates (and returns) the full-viewport phaser canvas, appended to
 * [spikeOverlay] at z-index:2 — above the CSS3D pane layer (z-index:1) so the fire reads
 * in front of the terminals, below the chrome badges (z-index:3+) so the close button and
 * banners stay on top. `pointer-events:none` keeps it from ever blocking input.
 *
 * @return the canvas, or `null` if the overlay is gone (spike closed mid-frame).
 * @see tickPhaser
 */
private fun ensurePhaserCanvas(): HTMLCanvasElement? {
    spikePhaserCanvas?.let { return it }
    val overlay = spikeOverlay ?: return null
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;z-index:2;pointer-events:none;"
    overlay.appendChild(canvas)
    spikePhaserCanvas = canvas
    return canvas
}

/**
 * Spawns one [PhaserBolt] aimed at [p], from an emitter near the viewer so it reads as
 * fired "from the camera": the lower-left or lower-right phaser bank, the bottom-centre,
 * or occasionally just off a side edge — all a touch below/beyond the frame. The jagged
 * shape and wobble amplitude are randomised per bolt for the irregular fire look.
 *
 * @param p the target pane. @param w viewport width px. @param h viewport height px.
 * @see tickPhaser
 */
private fun spawnPhaserBolt(p: RingPane, w: Int, h: Int) {
    val wd = w.toDouble()
    val hd = h.toDouble()
    val (ox, oy) = when (Random.nextInt(4)) {
        0 -> Pair(wd * 0.10, hd * 1.05)
        1 -> Pair(wd * 0.90, hd * 1.05)
        2 -> Pair(wd * 0.5 + (Random.nextDouble() - 0.5) * wd * 0.3, hd * 1.12)
        else -> Pair(if (Random.nextDouble() < 0.5) -wd * 0.05 else wd * 1.05, hd * (0.6 + Random.nextDouble() * 0.4))
    }
    val jitters = DoubleArray(PHASER_BOLT_SEGS) { Random.nextDouble() - 0.5 }
    spikePhaserBolts.add(
        PhaserBolt(
            paneId = p.paneId,
            ox = ox, oy = oy, age = 0.0,
            width = 1.5 + Random.nextDouble() * 2.0,
            jitterAmp = 40.0 + Random.nextDouble() * 60.0,
            jitters = jitters,
        ),
    )
}

/**
 * Draws one bolt this frame: the beam head lerps from the emitter toward [tx],[ty] on an
 * ease-out so it snaps to the target, and the beam is stroked as a jagged multi-segment
 * path in two passes — a wide soft orange glow under a thin white-hot core — then a bright
 * radial muzzle/impact flash at the head. Assumes the caller has set additive compositing.
 *
 * @param ctx the phaser canvas 2D context (composite already `"lighter"`).
 * @param b the bolt. @param tx/[ty] the target pane's current screen centre px.
 * @see tickPhaser
 */
private fun drawPhaserBolt(ctx: dynamic, b: PhaserBolt, tx: Double, ty: Double) {
    val t = (b.age / PHASER_BOLT_LIFE).coerceIn(0.0, 1.0)
    val ease = 1.0 - (1.0 - t) * (1.0 - t)
    val hx = b.ox + (tx - b.ox) * ease
    val hy = b.oy + (ty - b.oy) * ease
    val alpha = if (t < 0.82) 1.0 else (1.0 - (t - 0.82) / 0.18).coerceIn(0.0, 1.0)
    val dx = hx - b.ox
    val dy = hy - b.oy
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1.0)
    val nx = -dy / len
    val ny = dx / len

    ctx.lineCap = "round"
    ctx.lineJoin = "round"
    for (pass in 0..1) {
        ctx.beginPath()
        ctx.moveTo(b.ox, b.oy)
        for (s in 1..PHASER_BOLT_SEGS) {
            val f = s.toDouble() / PHASER_BOLT_SEGS
            // Wobble fades to zero at the head so bolts always strike the target cleanly.
            val wob = if (s == PHASER_BOLT_SEGS) 0.0 else b.jitters[s - 1] * b.jitterAmp * (1.0 - f)
            ctx.lineTo(b.ox + dx * f + nx * wob, b.oy + dy * f + ny * wob)
        }
        if (pass == 0) {
            ctx.strokeStyle = "rgba(255,120,20,${0.30 * alpha})"
            ctx.lineWidth = b.width * 3.0
        } else {
            ctx.strokeStyle = "rgba(255,240,190,${0.9 * alpha})"
            ctx.lineWidth = b.width
        }
        ctx.stroke()
    }

    val r = b.width * 6.0
    val flash = ctx.createRadialGradient(hx, hy, 0.0, hx, hy, r)
    flash.addColorStop(0.0, "rgba(255,255,230,${0.9 * alpha})")
    flash.addColorStop(0.4, "rgba(255,150,40,${0.55 * alpha})")
    flash.addColorStop(1.0, "rgba(255,40,0,0)")
    ctx.fillStyle = flash
    ctx.beginPath()
    ctx.arc(hx, hy, r, 0.0, PI * 2.0)
    ctx.fill()
}

/**
 * Draws the growing red "impact heat" glow bloom over a burning pane's screen centre,
 * swelling in radius and opacity with the barrage [prog]ress so the pane looks
 * increasingly scorched from the front. Assumes additive compositing.
 *
 * @param ctx the phaser canvas 2D context. @param tx/[ty] the pane's screen centre px.
 * @param prog the pane's barrage progress in `0.0..1.0`.
 * @see tickPhaser
 */
private fun drawHeatGlow(ctx: dynamic, tx: Double, ty: Double, prog: Double) {
    val r = 60.0 + prog * 150.0
    val g = ctx.createRadialGradient(tx, ty, 0.0, tx, ty, r)
    g.addColorStop(0.0, "rgba(255,90,25,${0.20 + 0.45 * prog})")
    g.addColorStop(0.5, "rgba(220,25,0,${0.12 + 0.28 * prog})")
    g.addColorStop(1.0, "rgba(120,0,0,0)")
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(tx, ty, r, 0.0, PI * 2.0)
    ctx.fill()
}

/**
 * Projects a CSS3D object's world position to screen-pixel coordinates with the shared
 * camera (three.js `Vector3.project`, called on the `dynamic` position vector since it is
 * untyped in our minimal bindings). Returns `null` when the point falls outside the
 * clip volume (behind the camera / beyond the far plane), so bolts to an off-screen
 * pane are simply skipped and culled.
 *
 * @param obj the pane's CSS3D object. @param camera the shared camera.
 * @param w viewport width px. @param h viewport height px.
 * @return the screen `(x, y)` in px, or `null` if the point is not in view.
 * @see tickPhaser
 */
private fun projectToScreen(obj: CSS3DObject, camera: PerspectiveCamera, w: Int, h: Int): Pair<Double, Double>? {
    // NB: obj.position is already `dynamic`; calling `.asDynamic()` on a dynamic receiver
    // emits a real `.asDynamic()` method call on the JS Vector3 (which has no such method)
    // → a runtime TypeError. Clone the (dynamic) Vector3 directly instead.
    val v = obj.position.clone()
    v.project(camera)
    val nz = v.z as Double
    if (nz < -1.0 || nz > 1.0) return null
    val nx = v.x as Double
    val ny = v.y as Double
    return Pair((nx + 1.0) / 2.0 * w, (1.0 - ny) / 2.0 * h)
}
