/**
 * The **wormhole pane spawn** — the [WORMHOLE_SPAWN_ENABLED] birth cinematic that a
 * newly-created pane plays instead of the plain grow-in, the counterpart to the
 * phaser-fire close ([World3DSpikePhaser]). When a lone pane is born while the world is
 * open and the camera is idle, the camera swings off to a patch of open space to the
 * side, a **blue whirlpool vortex** spirals open there, and the new pane **emerges out of
 * it** — flashing into being, tumbling, then sailing to its ring slot while the rift
 * collapses shut behind it and the camera follows it home.
 *
 * The vortex ([buildWormholeVortex]) is a flat disc of **turbulent blue gas** (SVG
 * `feTurbulence` cloud) with a **dark eye** at its centre, on a three.js `Group` that is
 * billboarded then canted hard off the view axis so the round disc reads as a tilted
 * ellipse — the classic Star-Trek / Babylon-5 rift seen at a shallow angle. Two
 * counter-rotating cloud layers churn the gas into two swirling arms; the pane is born
 * out of the dark centre. All of it lives in ONE CSS3D plane — its `mix-blend-mode`
 * layers must never become CSS3D objects of their own, or Chromium flattens the whole
 * scene's perspective while they exist (see the one-plane rule in [buildWormholeVortex]).
 *
 * Two layers cooperate, as the phaser close's do:
 *  - the world-space **vortex** ([WormholeSpawn.funnel]) — billboarded to face the
 *    camera (plus a fixed cant), scaled open/shut, its cloud layers spun each frame; and
 *  - a **transform override** on the newborn [RingPane]: while [RingPane.spawnPhase] is
 *    live, [tickWormhole] fully rewrites the pane's world position / scale / opacity /
 *    rotation each frame — reading the ring slot the render loop *just* computed as the
 *    emergence destination, so the hand-off back to normal ring placement is seamless.
 *
 * Driven from the render loop's per-frame [tickWormhole] (called just *before*
 * `css.render`, since it moves real 3D objects); armed by [armWormholeSpawn] from
 * [reconcileRing]; torn down via [clearWormholes] on [closeWorld3dSpike].
 *
 * @see WORMHOLE_SPAWN_ENABLED
 * @see armWormholeSpawn
 * @see tickWormhole
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera

/**
 * One in-flight wormhole birth: the funnel plus the clock and DOM handles [tickWormhole]
 * advances it with. Created by [armWormholeSpawn]; there is normally at most one, since
 * the effect only arms for a lone newborn while the camera is idle.
 *
 * @property paneId the pane being born through this vortex (its [RingPane.spawnPhase]
 *   is the twin of [phase]).
 * @property phase elapsed frames since arming — the master clock the whole sequence
 *   (focus → open → emerge → close) is keyed off.
 * @property wx/[wy]/[wz] the vortex's fixed world position (the spawn point off to the side).
 * @property funnel the funnel `Group` (billboarded + tilted + scaled each frame).
 * @property rings the two cloud-layer `<div>`s INSIDE the disc wrapper — spun with a flat
 *   2D `rotate()` each frame for the churn (never separate CSS3D planes; see
 *   [buildWormholeVortex] for the one-plane rule).
 * @property ringTwist each cloud layer's fixed pre-rotation (radians), the base its spin adds to.
 * @property flashEl the white pop/implosion flash disc (opacity pulsed at emergence + collapse).
 * @property elements the DOM the funnel owns, for teardown and the open/collapse fade — now
 *   just the one disc wrapper (its layers go down with it); removed explicitly since the
 *   CSS3D renderer leaves orphaned elements in the DOM otherwise.
 * @property spin accumulated cloud spin (radians), advanced each frame.
 * @property armedReturn `true` once the follow-the-pane return flight has been fired.
 */
internal class WormholeSpawn(
    val paneId: String,
    var phase: Double,
    val wx: Double,
    val wy: Double,
    val wz: Double,
    val funnel: Group,
    val rings: List<HTMLElement>,
    val ringTwist: DoubleArray,
    val flashEl: HTMLElement,
    val elements: List<HTMLElement>,
    /** The pane's emergence **start** — the vortex centre pushed toward the camera by
     *  [WORMHOLE_PANE_FRONT], so the pane never intersects the vortex disc. */
    val sx: Double,
    val sy: Double,
    val sz: Double,
    var spin: Double = 0.0,
    var armedReturn: Boolean = false,
)

/** The frame at which the camera-focus leg ends and the funnel begins to open. */
private val WORMHOLE_FOCUS_END get() = WORMHOLE_FOCUS_FRAMES

/** The frame at which the funnel has fully opened and the pane begins to emerge. */
private val WORMHOLE_OPEN_END get() = WORMHOLE_FOCUS_FRAMES + WORMHOLE_OPEN_FRAMES

/** The frame at which emergence completes and the spawn tears down. */
private val WORMHOLE_EMERGE_END get() = WORMHOLE_FOCUS_FRAMES + WORMHOLE_OPEN_FRAMES + WORMHOLE_EMERGE_FRAMES

/** Smootherstep `t³(t(6t−15)+10)` — the same soft-in/soft-out curve the camera tours use. */
private fun smoother(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/**
 * Whether a wormhole spawn may be armed for the pane(s) just built by [reconcileRing]:
 * the flag is on, the scene is up, **exactly one** pane was newly built (a burst — e.g.
 * a workspace restore — falls back to the plain grow-in so we never fire a camera
 * hijack per pane), no wormhole is already running, and the camera is idle (not flying,
 * not mid-tour). Fires during the demo movie too — its `n` beat is a genuine single
 * create and is meant to arrive through the vortex; burst rebuilds are still filtered
 * out by [newBornCount] regardless of whether a movie is playing. Called from
 * [reconcileRing].
 *
 * @param newBornCount how many panes [reconcileRing] built this pass.
 * @return `true` if [armWormholeSpawn] should be called for the sole newborn.
 * @see armWormholeSpawn
 */
internal fun wormholeSpawnEligible(newBornCount: Int): Boolean =
    WORMHOLE_SPAWN_ENABLED &&
        newBornCount == 1 &&
        spikeWormholes.isEmpty() &&
        spikeCssScene != null &&
        !spikeFlyMode &&
        !spikeCamReturning

/**
 * Arms the wormhole birth for [pane]: parks it hidden, builds the funnel at the spawn
 * point off to the side, and fires the opening camera flight to frame it. The per-frame
 * [tickWormhole] then drives the whole sequence. Idempotent per pane.
 *
 * @param pane the freshly-built [RingPane] (birth 0) to bring in through the vortex.
 * @see wormholeSpawnEligible @see tickWormhole
 */
internal fun armWormholeSpawn(pane: RingPane) {
    if (pane.spawnPhase >= 0.0) return
    val scene = spikeCssScene ?: return
    val chrome = spikeChromeColors ?: return

    val wx = WORMHOLE_POS_X
    val wy = WORMHOLE_POS_Y
    val wz = WORMHOLE_POS_Z

    val funnel = buildWormholeVortex(chrome)
    funnel.group.position.set(wx, wy, wz)
    funnel.group.asDynamic().scale.set(0.0, 0.0, 0.0) // a point; spirals open at WORMHOLE_FOCUS_END
    scene.add(funnel.group)

    // The pane emerges from a point pushed toward the camera from the vortex centre, so
    // its plane stays in front of the tilted disc (no intersecting-planes clip).
    val d = perspDistance(window.innerHeight)
    val camX = wx + WORMHOLE_CAM_SIDE
    val camY = wy + WORMHOLE_CAM_LIFT
    val camZ = wz + d * WORMHOLE_CAM_BACK
    val dl = kotlin.math.sqrt((camX - wx) * (camX - wx) + (camY - wy) * (camY - wy) + (camZ - wz) * (camZ - wz))
        .coerceAtLeast(1.0)
    val sx = wx + (camX - wx) / dl * WORMHOLE_PANE_FRONT
    val sy = wy + (camY - wy) / dl * WORMHOLE_PANE_FRONT
    val sz = wz + (camZ - wz) / dl * WORMHOLE_PANE_FRONT

    pane.spawnPhase = 0.0
    spikeWormholes.add(
        WormholeSpawn(
            pane.paneId, 0.0, wx, wy, wz,
            funnel.group, funnel.rings, funnel.twists, funnel.flash, funnel.elements,
            sx, sy, sz,
        ),
    )

    // Focus leg: swing the camera off to frame the spawn point against open sky.
    flyCamTo(
        camX, camY, camZ,
        wx, wy, wz,
        landPristine = false,
        frames = WORMHOLE_FOCUS_FRAMES,
        pullout = WORMHOLE_FOCUS_PULLOUT,
        rise = WORMHOLE_FOCUS_RISE,
    )
}

/**
 * Per-frame driver of every wormhole spawn, called from the render loop ([startSpikeLoop])
 * **just before** `css.render` — so the funnel it moves and the newborn-pane transforms it
 * overrides all take effect the same frame. For each live spawn it advances the master
 * clock, billboards / tilts / spins / spirals-open-then-collapses the funnel, and, once
 * the pane is emerging, lerps it out of the throat to the ring slot the render loop set
 * this frame (firing the follow-the-pane return flight once). A spawn whose pane vanished
 * or started dying tears down at once; one whose clock passes [WORMHOLE_EMERGE_END] clears
 * [RingPane.spawnPhase] (handing the pane back to normal ring placement) and disposes.
 *
 * @param camera the shared spike camera, used to billboard the funnel toward the viewer.
 * @see armWormholeSpawn @see startSpikeLoop
 */
internal fun tickWormhole(camera: PerspectiveCamera) {
    if (spikeWormholes.isEmpty()) return
    val q = camera.asDynamic().quaternion
    val completed = mutableListOf<WormholeSpawn>()

    for (ws in spikeWormholes) {
        ws.phase += spikeDtFrames
        val phase = ws.phase
        val pane = spikePanes.firstOrNull { it.paneId == ws.paneId }

        // Pane gone or dying: abort gracefully — drop the funnel, let the pane die normally.
        if (pane == null || pane.dying) {
            pane?.spawnPhase = -1.0
            completed.add(ws)
            continue
        }

        // --- Funnel: face the camera (with a fixed cant so we see *into* it), spin the
        //     helix, and spiral open → hold → collapse via the group scale. ---
        val g = ws.funnel.asDynamic()
        ws.funnel.position.set(ws.wx, ws.wy, ws.wz)
        g.quaternion.copy(q)
        g.rotateX(WORMHOLE_TILT_X)
        g.rotateY(WORMHOLE_TILT_Y)

        ws.spin += (WORMHOLE_SPIN_SPEED +
            (if (phase >= WORMHOLE_OPEN_END) WORMHOLE_SPIN_EMERGE else 0.0)) * spikeDtFrames
        for (i in ws.rings.indices) {
            // The cloud layers counter-rotate (and at different rates) so the gas churns
            // like turbulence rather than reading as one rigid spinning texture. Spun as
            // a flat 2D rotate INSIDE the disc wrapper — deliberately not as CSS3D object
            // rotations; the layers must stay inside the one isolated plane (see the
            // one-plane rule in [buildWormholeVortex]).
            val dir = if (i % 2 == 0) 1.0 else -1.35
            ws.rings[i].style.setProperty(
                "transform",
                "translate(-50%,-50%) rotate(${ws.ringTwist[i] + ws.spin * dir}rad)",
            )
        }

        var portalScale = 0.0
        var portalOpacity = 0.0
        var flashOpacity = 0.0
        when {
            phase < WORMHOLE_FOCUS_END -> {
                portalScale = 0.0
                portalOpacity = 0.0
            }
            phase < WORMHOLE_OPEN_END -> {
                val o = ((phase - WORMHOLE_FOCUS_END) / WORMHOLE_OPEN_FRAMES).coerceIn(0.0, 1.0)
                portalScale = smoother(o) + WORMHOLE_OPEN_OVERSHOOT * sin(PI * o)
                portalOpacity = min(1.0, o * 2.0)
            }
            else -> {
                val e = ((phase - WORMHOLE_OPEN_END) / WORMHOLE_EMERGE_FRAMES).coerceIn(0.0, 1.0)
                val closeStart = 1.0 - WORMHOLE_CLOSE_TAIL
                if (e > closeStart) {
                    val c = ((e - closeStart) / WORMHOLE_CLOSE_TAIL).coerceIn(0.0, 1.0)
                    portalScale = 1.0 - smoother(c)
                    portalOpacity = 1.0 - c
                    flashOpacity = max(flashOpacity, smoother(c) * (1.0 - c) * 2.4)
                } else {
                    portalScale = 1.0
                    portalOpacity = 1.0
                }
                flashOpacity = max(flashOpacity, (1.0 - e / 0.12).coerceIn(0.0, 1.0))
            }
        }
        g.scale.set(portalScale, portalScale, portalScale)
        // The wrapper carries the whole disc's open/collapse fade; the flash rides
        // inside it, so its own opacity only needs the pulse (the wrapper already
        // multiplies the portal fade in).
        for (el in ws.elements) el.style.opacity = portalOpacity.toString()
        ws.flashEl.style.opacity = flashOpacity.toString()

        // --- Pane: hidden inside the vortex, then flying out to the ring slot. ---
        //
        // Crucially we override ONLY the pane's POSITION. Scale, rotation, opacity and the
        // terminal's own pixel sizing are left entirely to the render loop, so the pane
        // grows in via its normal (already-correct) birth animation, at the right front
        // size and orientation — never mis-scaled (the size pop) or rotated near edge-on
        // (the CSS-3D clip that sheared it into a wedge). We just fly it from the vortex to
        // whatever slot the loop computed this frame; the lerp ends exactly on that slot,
        // so hand-off is seamless.
        if (phase >= WORMHOLE_EMERGE_END) {
            pane.spawnPhase = -1.0
            completed.add(ws)
            continue
        }
        pane.spawnPhase = phase
        if (phase < WORMHOLE_OPEN_END) {
            // Held hidden through the focus + open legs: pin birth (and thus the loop's
            // scale) to zero and park the invisible pane just in front of the vortex.
            pane.birth = 0.0
            pane.obj.scale.set(0.0, 0.0, 0.0)
            pane.obj.position.set(ws.sx, ws.sy, ws.sz)
        } else {
            // Emerging: the loop now grows the pane in (birth 0 → 1) and sizes/orients it;
            // we only fly its position from in front of the vortex to the loop slot.
            val e = ((phase - WORMHOLE_OPEN_END) / WORMHOLE_EMERGE_FRAMES).coerceIn(0.0, 1.0)
            val ee = smoother(e)
            val rx = pane.obj.position.x as Double
            val ry = pane.obj.position.y as Double
            val rz = pane.obj.position.z as Double
            pane.obj.position.set(
                ws.sx + (rx - ws.sx) * ee,
                ws.sy + (ry - ws.sy) * ee,
                ws.sz + (rz - ws.sz) * ee,
            )
            if (!ws.armedReturn) {
                ws.armedReturn = true
                // Follow the pane home: the camera keeps the emerging pane centred in view
                // the whole way (so it never drifts to the frustum edge, where a big CSS-3D
                // plane distorts/clips into a wedge), then eases its gaze to the origin as it
                // lands. This is the cinematic arc the journey is built around.
                val homeZ = RING_R + perspDistance(window.innerHeight)
                flyCamTo(
                    0.0, 0.0, homeZ,
                    0.0, 0.0, 0.0,
                    landPristine = true,
                    frames = WORMHOLE_RETURN_FRAMES,
                    pullout = WORMHOLE_RETURN_PULLOUT,
                    rise = WORMHOLE_RETURN_RISE,
                    followPaneId = ws.paneId,
                    endLook = Triple(0.0, 0.0, 0.0),
                )
            }
        }
    }

    if (completed.isNotEmpty()) {
        for (ws in completed) {
            for (el in ws.elements) runCatching { el.remove() }
            runCatching { spikeCssScene?.asDynamic()?.remove(ws.funnel) }
        }
        spikeWormholes.removeAll(completed)
    }
}

/**
 * Clears the wormhole registry on close. Called from [closeWorld3dSpike]; the funnel DOM
 * goes down with the overlay wholesale, so this just drops the registry. Any still-
 * spawning pane is disposed by the close's own pane sweep.
 *
 * @see armWormholeSpawn
 */
internal fun clearWormholes() {
    spikeWormholes.clear()
}

/**
 * Builds one **whirlpool vortex**: a single flat CSS3D disc plane layering a dark
 * structural **backdrop** (black eye at the centre, faint blue body, feathered rim), two
 * counter-rotating **cloud** layers of turbulent blue gas (SVG `feTurbulence`, masked to
 * a ringed spiral so the gas swirls in two arms around the dark eye), and a subtle
 * **flash** for the pop/implosion. [tickWormhole] spins the cloud layers (2D `rotate()`)
 * and billboards the group with a big X-cant so the round disc reads as a tilted ellipse
 * — the classic blue rift seen at a shallow angle, with the pane born out of the dark
 * centre.
 *
 * **THE ONE-PLANE RULE (hard-won):** every `mix-blend-mode` layer of the vortex must live
 * INSIDE this one wrapper `<div>` — the funnel's only CSS3D plane — never as its own
 * CSS3D object. The CSS3D renderer parents every object's element directly under its
 * `transform-style: preserve-3d` camera element, and a blending child there makes
 * Chromium force the camera element's used transform-style to *flat*: the ENTIRE scene
 * silently loses its perspective projection while the vortex is in the DOM (every pane
 * drawn orthographic-then-2D-projected, the front pane at ~half size), then snaps back
 * to the true projection the frame the vortex is torn down — which read as "the birthed
 * pane suddenly doubles in size when the camera lands" and defeated every DOM-side probe
 * (`getBoundingClientRect` is blind to the CSS3D projection, so all rect probes reported
 * the correct size throughout). Keeping the blend layers inside one wrapper — its own
 * stacking context, made explicit with `isolation:isolate` — contains the blending to
 * the disc, exactly like the panes' own screen-blended working glow, and leaves the
 * camera element's `preserve-3d` intact.
 *
 * @param chrome the active pane chrome, for the flash tint.
 * @return the vortex group plus the two cloud layers (spun), their fixed twists, the
 *   flash element, and the wrapper element (for the portal fade + teardown).
 * @see armWormholeSpawn @see tickWormhole
 */
private fun buildWormholeVortex(chrome: SpikeChrome): WormholeVortex {
    val group = Group()
    val d = WORMHOLE_DIAMETER

    // The one CSS3D plane of the funnel: hosts every layer as stacked, centred children
    // (DOM order = paint order, replacing the old per-plane z offsets). See the
    // one-plane rule above for why this wrapper must exist and isolate.
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${d}px;height:${d}px;position:relative;" +
        "pointer-events:none;isolation:isolate;"

    /** Centres [el] in the wrapper as a stacked paint layer (append order = paint order). */
    fun add(el: HTMLElement): HTMLElement {
        el.style.setProperty("position", "absolute")
        el.style.setProperty("left", "50%")
        el.style.setProperty("top", "50%")
        el.style.setProperty("transform", "translate(-50%,-50%)")
        wrapper.appendChild(el)
        return el
    }

    // Structural backdrop — the dark eye, a faint blue body, and a feathered rim, so the
    // vortex has a black hole at its heart and dissolves into space at its edge. Normal
    // blend (it darkens); the cloud layers screen over it.
    val backdrop = document.createElement("div") as HTMLElement
    backdrop.style.cssText = "width:${d * 1.04}px;height:${d * 1.04}px;border-radius:50%;" +
        "pointer-events:none;background:radial-gradient(circle,#000000 0%,#000208 15%," +
        "rgba(12,30,66,0.55) 33%,rgba(24,58,120,0.4) 52%,rgba(8,18,42,0.22) 74%,transparent 90%);"
    add(backdrop)

    // Two turbulent-gas cloud layers, counter-rotated by the tick. Different noise scale
    // and seed per layer, both masked to a two-arm spiral ring so the gas curls around
    // the eye rather than filling a flat disc. cloud2 paints beneath cloud1 (it used to
    // sit slightly behind it in z).
    val cloud2 = add(cloudLayer(d, seed = 29, baseFreq = "0.011 0.021", octaves = 4, blurPx = 5, maskFrom = 200))
    val cloud1 = add(cloudLayer(d, seed = 11, baseFreq = "0.0055 0.013", octaves = 5, blurPx = 8, maskFrom = 0))

    // Pop / implosion flash — a soft cool-white burst at the eye, hidden until pulsed.
    val flashD = d * 0.46
    val flashEl = document.createElement("div") as HTMLElement
    flashEl.style.cssText = "width:${flashD}px;height:${flashD}px;border-radius:50%;" +
        "pointer-events:none;opacity:0;mix-blend-mode:screen;filter:blur(14px);" +
        "background:radial-gradient(circle,#ffffff 0%,${WORMHOLE_SWIRL_A}cc 34%,${chrome.accent}44 58%,transparent 76%);"
    add(flashEl)

    group.add(CSS3DObject(wrapper))

    val rings = listOf(cloud1, cloud2)
    val twists = doubleArrayOf(0.0, 1.1)
    return WormholeVortex(group, rings, twists, flashEl, listOf(wrapper))
}

/**
 * One **cloud layer** — a `<div>` (wrapping an inline `<svg>` so the CSS3D renderer, which
 * needs an `HTMLElement`, has one) filled with turbulent blue gas via SVG `feTurbulence`,
 * masked to a **two-arm spiral ring**: a radial mask carves the dark eye and feathered rim
 * while a soft conic mask concentrates the gas into two opposing arms, so it curls around
 * the centre like a whirlpool. `screen`-blended and blurred into wisps; spun by the tick.
 *
 * The turbulence is coloured by an `feColorMatrix` that fixes RGB to a blue and drives
 * alpha from the noise (then an `feComponentTransfer` gamma thins it to wisps).
 *
 * @param diameter layer diameter in px (world units at scale 1).
 * @param seed the `feTurbulence` seed (distinct per layer so they don't align).
 * @param baseFreq the `feTurbulence` base frequency (anisotropic → streaky gas).
 * @param octaves fractal-noise octaves (more = finer detail).
 * @param blurPx blur radius in px.
 * @param maskFrom the conic mask's starting angle (offsets the two arms per layer).
 * @return the cloud `<div>`.
 * @see buildWormholeVortex
 */
private fun cloudLayer(diameter: Double, seed: Int, baseFreq: String, octaves: Int, blurPx: Int, maskFrom: Int): HTMLElement {
    val fid = "wh-turb-$seed"
    val svg = "<svg width=\"100%\" height=\"100%\" xmlns=\"http://www.w3.org/2000/svg\">" +
        "<defs><filter id=\"$fid\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">" +
        "<feTurbulence type=\"fractalNoise\" baseFrequency=\"$baseFreq\" numOctaves=\"$octaves\" " +
        "seed=\"$seed\" stitchTiles=\"stitch\" result=\"n\"/>" +
        // Fix RGB to a blue, drive alpha from the noise alpha channel.
        "<feColorMatrix in=\"n\" type=\"matrix\" values=\"0 0 0 0 0.28  0 0 0 0 0.56  0 0 0 0 1  0 0 0 1.3 0\"/>" +
        // Thin the alpha into wisps (gamma) and clip the low end to transparent.
        "<feComponentTransfer><feFuncA type=\"gamma\" amplitude=\"1.1\" exponent=\"1.8\" offset=\"-0.16\"/></feComponentTransfer>" +
        "</filter></defs>" +
        "<rect x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" filter=\"url(#$fid)\"/></svg>"

    // Two masks intersected: a radial ring (dark eye + feathered rim) and a soft two-arm
    // conic (spiral arms). mask-composite:intersect keeps only where both are opaque.
    val radialMask = "radial-gradient(closest-side,transparent 13%,#000 31%,#000 66%,transparent 92%)"
    val conicMask = "conic-gradient(from ${maskFrom}deg,#000,rgba(0,0,0,0.30) 78deg,#000 150deg," +
        "#000 240deg,rgba(0,0,0,0.30) 318deg,#000 360deg)"
    val el = document.createElement("div") as HTMLElement
    el.style.cssText = "width:${diameter}px;height:${diameter}px;position:relative;pointer-events:none;" +
        "mix-blend-mode:screen;filter:blur(${blurPx}px);will-change:transform;" +
        "-webkit-mask:$radialMask,$conicMask;-webkit-mask-composite:source-in;" +
        "mask:$radialMask,$conicMask;mask-composite:intersect;"
    el.innerHTML = svg
    return el
}

/** Bundle returned by [buildWormholeVortex]. */
private class WormholeVortex(
    val group: Group,
    val rings: List<HTMLElement>,
    val twists: DoubleArray,
    val flash: HTMLElement,
    val elements: List<HTMLElement>,
)
