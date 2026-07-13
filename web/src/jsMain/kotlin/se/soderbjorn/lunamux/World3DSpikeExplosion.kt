/**
 * The **space explosion** that caps the phaser-fire pane close — the payoff to the
 * barrage. The instant [tickPhaser] finishes a killed pane's implosion (its clock passing
 * [PHASER_TOTAL_FRAMES] + [PHASER_COLLAPSE_FRAMES]), it calls [spawnPaneExplosion] at the
 * pane's last on-screen position, and for a beat a proper Star-Trek fireball blooms where
 * the pane was — before the surviving neighbour slides in to fill the gap (that slide is
 * held back by [EXPLOSION_PRE_SLIDE_MS] so the blast reads first).
 *
 * The whole effect is drawn additively in **screen space** onto a single full-viewport 2D
 * canvas ([spikeExplosionCanvas], the exact sibling of the phaser's [spikePhaserCanvas],
 * one z-layer of the overlay), so it needs no CSS3D objects and costs nothing once the last
 * blast has faded. Each [PaneExplosion] is frozen at spawn to a fixed screen point (the
 * pane is being disposed and will be gone next frame), and layers:
 *   - a brief blue-white **flash**;
 *   - an expanding, cooling **fireball** (white → yellow → orange → red);
 *   - a thin bright **shockwave ring** racing outward; and
 *   - a spray of glowing **debris sparks** flung radially out, slowed only by drag (this is
 *     space — no gravity pulls them down).
 *
 * Driven per-frame from the render loop by [tickExplosion], right after [tickPhaser];
 * armed by [spawnPaneExplosion] and torn down with the overlay on [closeWorld3dSpike].
 *
 * @see EXPLOSION_ON_KILL
 * @see spawnPaneExplosion
 * @see tickExplosion
 * @see tickPhaser
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.PerspectiveCamera

/**
 * One flung ember/chunk of a [PaneExplosion]: a glowing dot with a short motion-blur tail,
 * hurled radially from the blast centre and slowed each frame by [EXPLOSION_DEBRIS_DRAG]
 * (no gravity — space). Advanced, drawn and culled by [tickExplosion] once [age] passes
 * [life].
 *
 * @property x/[y] the spark's current screen position in px.
 * @property vx/[vy] its velocity in px/frame (decayed by drag each frame).
 * @property age elapsed frames since spawn; brightness fades as it nears [life].
 * @property life the spark's total lifetime in frames.
 * @property size the head radius in px.
 * @property warm 0..1 colour bias — 1 is white-hot, 0 is a deep-orange ember.
 * @property streak `true` for the fast white-hot shards ([EXPLOSION_STREAK_FRACTION] of the
 *   spray) that outrun the embers and draw a long motion-blur tail for extra violence.
 * @see tickExplosion
 */
internal class ExplosionSpark(
    var x: Double,
    var y: Double,
    var vx: Double,
    var vy: Double,
    var age: Double,
    val life: Double,
    val size: Double,
    val warm: Double,
    val streak: Boolean,
)

/**
 * One in-flight blast: a fixed screen centre, an elapsed-frame clock and its own spray of
 * [ExplosionSpark]s. The flash / fireball / shockwave are derived from [age] each frame
 * rather than stored, so the object only carries what actually persists. Culled by
 * [tickExplosion] once both the core has outlived [EXPLOSION_LIFE_FRAMES] and every spark
 * is dead.
 *
 * @property cx/[cy] the blast centre in screen px, frozen at spawn.
 * @property radius the blast's base radius in px (fireball/shockwave peaks scale off it),
 *   sized to the viewport so the effect is resolution-independent.
 * @property age elapsed frames since the blast was armed.
 * @property sparks this blast's debris, each advanced independently.
 * @see spawnPaneExplosion @see tickExplosion
 */
internal class PaneExplosion(
    val cx: Double,
    val cy: Double,
    val radius: Double,
    var age: Double,
    val sparks: MutableList<ExplosionSpark>,
)

/**
 * The full-viewport 2D canvas the space explosions paint onto, or `null` until first
 * armed. A child of [spikeOverlay] at the same z-layer as the phaser canvas (above the
 * CSS3D pane layer, below the chrome), so it is discarded wholesale on [closeWorld3dSpike];
 * its reference is nulled there. @see ensureExplosionCanvas
 */
internal var spikeExplosionCanvas: HTMLCanvasElement? = null

/** Every blast currently blooming, advanced/drawn/culled each frame by [tickExplosion]. */
internal val spikeExplosions: MutableList<PaneExplosion> = mutableListOf()

/**
 * Arms a [PaneExplosion] at the dead pane's current screen position — called from
 * [tickPhaser] the moment a phaser-fire close finishes imploding, in place of letting the
 * survivors slide in at once. Projects the (about-to-be-disposed) pane to the screen with
 * the shared camera and freezes that point as the blast centre, then seeds
 * [EXPLOSION_DEBRIS_COUNT] sparks flung radially outward at randomised speeds/lifetimes.
 * A no-op when [EXPLOSION_ON_KILL] is off or the pane projects off-screen.
 *
 * @param p the just-imploded pane (its [RingPane.obj] still in the scene this frame).
 * @param camera the shared spike camera, used to project the pane centre to screen px.
 * @see tickExplosion @see tickPhaser
 */
internal fun spawnPaneExplosion(p: RingPane, camera: PerspectiveCamera) {
    if (!EXPLOSION_ON_KILL) return
    val w = window.innerWidth
    val h = window.innerHeight
    val (cx, cy) = projectExplosionCentre(p.obj, camera, w, h) ?: return
    val radius = min(w, h).toDouble()
    val sparks = ArrayList<ExplosionSpark>(EXPLOSION_DEBRIS_COUNT)
    repeat(EXPLOSION_DEBRIS_COUNT) {
        // Even angular spread with jitter, so the debris fans out all round rather than
        // clumping; speed and life vary per spark for a ragged, organic blast. A slice are
        // fast white-hot streaks that outrun the embers — the violent, shrapnel edge.
        val ang = (it + Random.nextDouble()) / EXPLOSION_DEBRIS_COUNT * PI * 2.0
        val streak = Random.nextDouble() < EXPLOSION_STREAK_FRACTION
        val speed = if (streak) {
            EXPLOSION_DEBRIS_SPEED * (0.85 + Random.nextDouble() * 0.6) // fast shards
        } else {
            EXPLOSION_DEBRIS_SPEED * (0.28 + Random.nextDouble() * 0.6) // ragged embers
        }
        sparks.add(
            ExplosionSpark(
                x = cx, y = cy,
                vx = cos(ang) * speed,
                vy = sin(ang) * speed,
                age = 0.0,
                life = EXPLOSION_DEBRIS_LIFE * (0.5 + Random.nextDouble() * 0.5),
                size = if (streak) 1.2 + Random.nextDouble() * 1.6 else 1.6 + Random.nextDouble() * 3.0,
                warm = if (streak) 0.8 + Random.nextDouble() * 0.2 else Random.nextDouble(),
                streak = streak,
            ),
        )
    }
    spikeExplosions.add(PaneExplosion(cx = cx, cy = cy, radius = radius, age = 0.0, sparks = sparks))
}

/**
 * Per-frame driver of the space explosions, called from the render loop right after
 * [tickPhaser] (so the camera matrices its spawn projection used are current). Advances
 * every live blast's clock and its sparks, paints the flash / fireball / shockwave /
 * debris additively, and culls blasts whose core has faded and whose sparks are all dead.
 * Hides the canvas cheaply when nothing is blooming.
 *
 * @see spawnPaneExplosion @see startSpikeLoop
 */
internal fun tickExplosion() {
    if (!EXPLOSION_ON_KILL) return
    if (spikeExplosions.isEmpty()) {
        spikeExplosionCanvas?.let { if (it.style.display != "none") it.style.display = "none" }
        return
    }
    val w = window.innerWidth
    val h = window.innerHeight
    val canvas = ensureExplosionCanvas() ?: return
    if (canvas.width != w) canvas.width = w
    if (canvas.height != h) canvas.height = h
    canvas.style.display = "block"
    val ctx = canvas.getContext("2d").asDynamic()
    if (ctx == null) return
    ctx.clearRect(0.0, 0.0, w.toDouble(), h.toDouble())
    ctx.globalCompositeOperation = "lighter"
    ctx.lineCap = "round"

    val it = spikeExplosions.iterator()
    while (it.hasNext()) {
        val ex = it.next()
        ex.age += spikeDtFrames
        drawFireball(ctx, ex)
        drawShockwave(ctx, ex)
        drawSparks(ctx, ex)
        val coreDead = ex.age > EXPLOSION_LIFE_FRAMES
        if (coreDead && ex.sparks.isEmpty()) it.remove()
    }

    ctx.globalCompositeOperation = "source-over"
}

/**
 * Lazily creates (and returns) the full-viewport explosion canvas, appended to
 * [spikeOverlay] at z-index:2 — the same layer as the phaser canvas (in front of the CSS3D
 * panes, behind the chrome). `pointer-events:none` keeps it from ever blocking input; the
 * two additive canvases simply composite over one another.
 *
 * @return the canvas, or `null` if the overlay is gone (spike closed mid-frame).
 * @see tickExplosion
 */
private fun ensureExplosionCanvas(): HTMLCanvasElement? {
    spikeExplosionCanvas?.let { return it }
    val overlay = spikeOverlay ?: return null
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;z-index:2;pointer-events:none;"
    overlay.appendChild(canvas)
    spikeExplosionCanvas = canvas
    return canvas
}

/**
 * Draws the blast's opening flash and expanding, cooling fireball for the current frame:
 * the fireball radius eases outward to [EXPLOSION_FIREBALL_MAX_FRAC] × the base radius and
 * its palette cools white→yellow→orange→red as [PaneExplosion.age] climbs toward
 * [EXPLOSION_LIFE_FRAMES], while an initial blue-white core flares for the first
 * [EXPLOSION_FLASH_FRAMES]. Assumes additive compositing.
 *
 * @param ctx the explosion canvas 2D context. @param ex the blast.
 * @see tickExplosion
 */
private fun drawFireball(ctx: dynamic, ex: PaneExplosion) {
    val life = (ex.age / EXPLOSION_LIFE_FRAMES).coerceIn(0.0, 1.0)
    if (life >= 1.0) return
    val ease = 1.0 - (1.0 - life) * (1.0 - life) // ease-out expansion
    val rMax = ex.radius * EXPLOSION_FIREBALL_MAX_FRAC
    val r = (rMax * (0.18 + 0.82 * ease)).coerceAtLeast(1.0)
    val fade = 1.0 - life
    // Fireball: hot white core fading to orange then transparent, cooling as it ages.
    val fb = ctx.createRadialGradient(ex.cx, ex.cy, 0.0, ex.cx, ex.cy, r)
    val innerWhite = (0.95 * fade * (1.0 - life * 0.5)).coerceIn(0.0, 1.0)
    fb.addColorStop(0.0, "rgba(255,250,225,$innerWhite)")
    fb.addColorStop(0.35, "rgba(255,190,80,${(0.75 * fade)})")
    fb.addColorStop(0.7, "rgba(230,70,20,${(0.45 * fade)})")
    fb.addColorStop(1.0, "rgba(120,10,0,0)")
    ctx.fillStyle = fb
    ctx.beginPath()
    ctx.arc(ex.cx, ex.cy, r, 0.0, PI * 2.0)
    ctx.fill()

    // Opening detonation: a full-viewport whiteout, a big blue-white bloom that outshines
    // the fireball, and a four-point star-flare — all gone within the first few frames.
    if (ex.age < EXPLOSION_FLASH_FRAMES) {
        val f = 1.0 - ex.age / EXPLOSION_FLASH_FRAMES
        // Full-viewport whiteout: a violent frame of overexposure at the instant of the blast.
        ctx.fillStyle = "rgba(255,255,255,${EXPLOSION_SCREEN_FLASH_MAX * f * f})"
        ctx.fillRect(ex.cx - ex.radius * 2.0, ex.cy - ex.radius * 2.0, ex.radius * 4.0, ex.radius * 4.0)

        val fr = rMax * (0.7 + 1.1 * (1.0 - f))
        val fl = ctx.createRadialGradient(ex.cx, ex.cy, 0.0, ex.cx, ex.cy, fr)
        fl.addColorStop(0.0, "rgba(235,245,255,${0.95 * f})")
        fl.addColorStop(0.5, "rgba(150,200,255,${0.45 * f})")
        fl.addColorStop(1.0, "rgba(80,140,255,0)")
        ctx.fillStyle = fl
        ctx.beginPath()
        ctx.arc(ex.cx, ex.cy, fr, 0.0, PI * 2.0)
        ctx.fill()

        // Star-flare: two long, thin lens spikes crossing the core (horizontal + vertical),
        // the classic bright-flash sparkle that sells the concussion.
        val spike = rMax * (2.4 * f + 1.2)
        ctx.strokeStyle = "rgba(255,255,255,${0.8 * f})"
        for (pass in 0..1) {
            ctx.lineWidth = if (pass == 0) 3.0 * f + 0.6 else 1.4 * f + 0.4
            ctx.beginPath()
            ctx.moveTo(ex.cx - spike, ex.cy); ctx.lineTo(ex.cx + spike, ex.cy)
            ctx.moveTo(ex.cx, ex.cy - spike); ctx.lineTo(ex.cx, ex.cy + spike)
            ctx.stroke()
        }
    }
}

/**
 * Draws the thin bright shockwave ring racing outward for the current frame: the radius
 * grows on an ease-out to [EXPLOSION_SHOCK_MAX_FRAC] × the base radius over
 * [EXPLOSION_SHOCK_FRAMES], its stroke thinning and fading as it expands. Assumes additive
 * compositing.
 *
 * @param ctx the explosion canvas 2D context. @param ex the blast.
 * @see tickExplosion
 */
private fun drawShockwave(ctx: dynamic, ex: PaneExplosion) {
    val maxR = ex.radius * EXPLOSION_SHOCK_MAX_FRAC
    // A fast leading concussion ring and a slower, wider trailing one — a double blast wave.
    drawShockRing(ctx, ex, phase = ex.age / EXPLOSION_SHOCK_FRAMES, maxR = maxR, width = 9.0, alpha = 0.8)
    drawShockRing(ctx, ex, phase = ex.age / (EXPLOSION_SHOCK_FRAMES * 1.7), maxR = maxR * 1.15, width = 5.0, alpha = 0.45)
}

/**
 * Draws one expanding shockwave ring for the current frame: radius grows on an ease-out to
 * [maxR] as [phase] runs 0→1, the stroke thinning and fading as it goes. Assumes additive
 * compositing. A `phase >= 1` ring is spent and skipped.
 *
 * @param ctx the explosion canvas 2D context. @param ex the blast (for its centre).
 * @param phase the ring's own 0→1 progress. @param maxR its peak radius in px.
 * @param width its stroke width at birth in px. @param alpha its opacity at birth.
 * @see drawShockwave
 */
private fun drawShockRing(ctx: dynamic, ex: PaneExplosion, phase: Double, maxR: Double, width: Double, alpha: Double) {
    val t = phase.coerceIn(0.0, 1.0)
    if (t >= 1.0) return
    val ease = 1.0 - (1.0 - t) * (1.0 - t)
    val r = (maxR * ease).coerceAtLeast(1.0)
    ctx.strokeStyle = "rgba(255,225,180,${(1.0 - t) * alpha})"
    ctx.lineWidth = (width * (1.0 - t)).coerceAtLeast(0.6)
    ctx.beginPath()
    ctx.arc(ex.cx, ex.cy, r, 0.0, PI * 2.0)
    ctx.stroke()
}

/**
 * Advances, draws and culls the blast's debris sparks for the current frame: each moves by
 * its velocity, is slowed by [EXPLOSION_DEBRIS_DRAG] (no gravity — space), and is stroked
 * as a short motion-blur tail behind a glowing head whose warmth ([ExplosionSpark.warm])
 * runs white-hot → ember; brightness fades as the spark nears its [ExplosionSpark.life].
 * Dead sparks are removed from the blast. Assumes additive compositing.
 *
 * @param ctx the explosion canvas 2D context. @param ex the blast whose sparks to advance.
 * @see tickExplosion
 */
private fun drawSparks(ctx: dynamic, ex: PaneExplosion) {
    val sit = ex.sparks.iterator()
    while (sit.hasNext()) {
        val s = sit.next()
        s.age += spikeDtFrames
        if (s.age >= s.life) { sit.remove(); continue }
        s.x += s.vx * spikeDtFrames
        s.y += s.vy * spikeDtFrames
        val drag = EXPLOSION_DEBRIS_DRAG.pow(spikeDtFrames)
        s.vx *= drag
        s.vy *= drag
        val fade = 1.0 - s.age / s.life
        // Warm sparks stay white-hot; cool ones settle to deep orange. Fade with age.
        val red = 255
        val grn = (110 + (145 * s.warm)).toInt()
        val blu = (30 + (120 * s.warm)).toInt()
        // Motion-blur tail — long and bright for the fast streaks, short for the embers.
        val tail = if (s.streak) 4.5 else 2.0
        ctx.strokeStyle = "rgba($red,$grn,$blu,${(if (s.streak) 0.8 else 0.55) * fade})"
        ctx.lineWidth = s.size
        ctx.beginPath()
        ctx.moveTo(s.x - s.vx * tail, s.y - s.vy * tail)
        ctx.lineTo(s.x, s.y)
        ctx.stroke()
        // Glowing head.
        val hr = s.size * 2.2
        val g = ctx.createRadialGradient(s.x, s.y, 0.0, s.x, s.y, hr)
        g.addColorStop(0.0, "rgba(255,240,210,${0.9 * fade})")
        g.addColorStop(1.0, "rgba($red,$grn,$blu,0)")
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(s.x, s.y, hr, 0.0, PI * 2.0)
        ctx.fill()
    }
}

/**
 * Projects a CSS3D object's world position to screen-pixel coordinates with the shared
 * camera — the twin of the phaser pass's projector — so the blast is placed exactly where
 * the pane last was. Returns `null` when the point is outside the clip volume (behind the
 * camera / beyond the far plane).
 *
 * @param obj the dead pane's CSS3D object. @param camera the shared camera.
 * @param w viewport width px. @param h viewport height px.
 * @return the screen `(x, y)` in px, or `null` if the point is not in view.
 * @see spawnPaneExplosion
 */
private fun projectExplosionCentre(obj: CSS3DObject, camera: PerspectiveCamera, w: Int, h: Int): Pair<Double, Double>? {
    // obj.position is already `dynamic`; clone the Vector3 directly (calling .asDynamic()
    // on it would emit a real method call the JS Vector3 lacks). Mirrors the phaser pass.
    val v = obj.position.clone()
    v.project(camera)
    val nz = v.z as Double
    if (nz < -1.0 || nz > 1.0) return null
    val nx = v.x as Double
    val ny = v.y as Double
    return Pair((nx + 1.0) / 2.0 * w, (1.0 - ny) / 2.0 * h)
}
