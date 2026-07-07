/**
 * The **cosmos dressing** — decorative planets, moons, nebulae and star clusters
 * scattered through the open sky around the pane sphere, so the world reads as a
 * *place* rather than empty void. Purely atmospheric: nothing here is interactive
 * or navigable, and none of it participates in the pane fade regimes.
 *
 * Several bodies are deliberately staged along the **stash-flight corridor** (the
 * long climb from the ring at the origin up to the shelf at [STASH_SHELF_Y], swayed
 * sideways by up to [STASH_CAM_SWAY]) so that stashing a pane means sailing *past*
 * planets — the journey gets landmarks and parallax instead of featureless sky.
 * Others surround the sphere for the resting backdrop and free-fly exploration.
 *
 * The spike scene is pure CSS3D (real DOM planes, no WebGL meshes), so each body is
 * a flat DOM element — a radial-gradient disc for a planet, layered soft gradients
 * for a nebula, a field of tiny dots for a star cluster — **billboarded to the
 * camera every frame** ([tickCosmos] copies the camera quaternion onto each body).
 * A billboarded gradient disc reads as a lit sphere from *any* viewing angle, which
 * is why planets don't need the beacons' crossed-planes trick. Each body also
 * drifts vertically on a slow phase-staggered sine so the sky feels alive; the
 * drift rides [spikeBobPhase] (always ticking) but is independent of the pane-bob
 * toggle — celestial bodies never hold still.
 *
 * Paint order: the cosmos is built **before** the ring panes (see
 * [openWorld3dSpike]), so its DOM elements precede every pane and the CSS3D
 * `preserve-3d` depth sorting plus DOM order keep planets behind the screens.
 *
 * @see buildCosmos
 * @see tickCosmos
 * @see clearCosmos
 * @see buildHomeBeacon
 */
package se.soderbjorn.termtastic

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.Scene

/**
 * One placed celestial body: its CSS3D object plus the anchor position and drift
 * parameters the per-frame tick animates it with.
 *
 * @property obj the CSS3D object wrapping the body's DOM element.
 * @property baseX anchor world X (constant; drift only moves Y).
 * @property baseY anchor world Y the vertical drift oscillates around.
 * @property baseZ anchor world Z (constant).
 * @property driftAmp world-units of vertical drift amplitude (0 pins the body still).
 * @property driftPhase per-body phase offset so the sky shimmers out of sync.
 * @see tickCosmos
 */
internal class SpikeCosmosBody(
    val obj: CSS3DObject,
    val baseX: Double,
    val baseY: Double,
    val baseZ: Double,
    val driftAmp: Double,
    val driftPhase: Double,
)

/**
 * Builds the whole cosmos — the fixed catalog of planets, moons, nebulae and star
 * clusters — and adds every body to the CSS3D scene and to [spikeCosmos] for the
 * per-frame tick. Called once per open from [openWorld3dSpike], right after the
 * beacons and **before** the ring panes (see the paint-order note in the file kdoc).
 *
 * The catalog is deliberately hand-placed (not random): a big backdrop gas giant
 * behind the sphere for the resting view, a string of bodies climbing the stash
 * corridor so the shelf flight has fly-bys, a couple below/beside the sphere for
 * free-fly, and nebula washes + star clusters at depth for parallax.
 *
 * Gated on [SPIKE_COSMOS_ENABLED] (currently off — the billboarded DOM planes
 * caused severe flicker; see the flag's kdoc): when disabled this is a no-op, and
 * [tickCosmos] no-ops on the empty registry.
 *
 * @param scene the CSS3D scene to add the bodies into.
 * @see clearCosmos
 * @see tickCosmos
 */
internal fun buildCosmos(scene: Scene) {
    if (!SPIKE_COSMOS_ENABLED) return
    /** Wraps [el] in a [CSS3DObject] at (x, y, z) and registers it for the tick. */
    fun add(el: HTMLElement, x: Double, y: Double, z: Double, driftAmp: Double) {
        val obj = CSS3DObject(el)
        obj.position.set(x, y, z)
        scene.add(obj)
        spikeCosmos.add(SpikeCosmosBody(obj, x, y, z, driftAmp, spikeCosmos.size * 1.9))
    }

    // ── Deep backdrop (visible from the resting ring view) ─────────────────────
    // Nebula washes go in first: the farthest layers paint under everything else.
    add(nebulaEl(3200, 2000, "rgba(99,102,241,0.32)", "rgba(56,189,248,0.22)"), 400.0, 4800.0, -6400.0, 40.0)
    add(nebulaEl(2600, 1700, "rgba(45,212,191,0.26)", "rgba(129,140,248,0.18)"), 4600.0, -900.0, -4800.0, 30.0)
    add(starClusterEl(2200, 34, seed = 11), -2400.0, 2600.0, -5600.0, 22.0)
    add(starClusterEl(1800, 26, seed = 23), 3400.0, 6800.0, -4200.0, 26.0)
    add(starClusterEl(2000, 30, seed = 37), -3800.0, -1800.0, -3600.0, 20.0)
    add(starClusterEl(1600, 22, seed = 51), 1200.0, 9200.0, -3000.0, 24.0)
    // The big gas giant looming behind-left of the pane sphere — the anchor of the
    // resting backdrop, glimpsed between panes without ever crowding them.
    add(
        planetEl(1400, "#3d4e7a", "#1c2745", "#070b18", "rgba(99,140,255,0.30)", ringColor = null),
        -2900.0, 1100.0, -5200.0, 26.0,
    )

    // ── The stash corridor (the climb from the ring to the shelf) ──────────────
    // Staged roughly along the swayed flight path so a stash/unstash sails past
    // them in sequence — near enough for real parallax, far enough never to clip
    // the travelling pane or the camera's swing ([STASH_CAM_SWAY] ≈ 2400).
    add(
        planetEl(680, "#8a6a3a", "#4d3617", "#171006", "rgba(240,180,90,0.25)", ringColor = "rgba(226,192,134,0.55)"),
        3400.0, 2500.0, -2600.0, 34.0,
    )
    add(
        planetEl(440, "#9a5540", "#54281c", "#170a06", "rgba(235,120,80,0.22)", ringColor = null),
        -3100.0, 4300.0, 600.0, 30.0,
    )
    add(
        planetEl(360, "#9fc4de", "#4a6f8e", "#101c28", "rgba(160,210,245,0.28)", ringColor = null),
        1900.0, 6400.0, -1400.0, 28.0,
    )
    add(
        planetEl(230, "#8f7ab8", "#463a66", "#120e1e", "rgba(170,140,230,0.24)", ringColor = null),
        800.0, 5300.0, 1900.0, 24.0,
    )
    // The shelf's own neighbour — a cool teal world floating just beyond the stash
    // row, so arriving up top feels like docking somewhere, not parking in fog.
    add(
        planetEl(560, "#3f8f86", "#1d4a46", "#081413", "rgba(80,220,200,0.26)", ringColor = null),
        -1600.0, 8400.0, -2400.0, 32.0,
    )

    // ── Around and below the sphere (free-fly rewards) ──────────────────────────
    add(
        planetEl(520, "#5f8f4f", "#2c4a24", "#0b140a", "rgba(140,220,110,0.22)", ringColor = null),
        2300.0, -2700.0, -1900.0, 30.0,
    )
    add(
        planetEl(300, "#7a7f8f", "#3a3f4d", "#0e1016", "rgba(170,180,210,0.20)", ringColor = null),
        -5200.0, 1500.0, -3800.0, 26.0,
    )
}

/**
 * Per-frame cosmos animation, called from the render loop ([startSpikeLoop]):
 * **billboards** every body to the camera (copies the camera quaternion, so a
 * gradient disc always shows its face — the flat plane can never be caught
 * edge-on) and applies the slow phase-staggered vertical **drift** around each
 * body's anchor. Rides [spikeBobPhase] (ticked unconditionally every frame) at
 * [COSMOS_DRIFT_RATE], independent of the pane-bob toggle.
 *
 * @param camera the shared spike camera whose orientation the bodies copy.
 * @see buildCosmos
 */
internal fun tickCosmos(camera: PerspectiveCamera) {
    if (spikeCosmos.isEmpty()) return
    val q = camera.asDynamic().quaternion
    for (b in spikeCosmos) {
        val y = b.baseY + sin(spikeBobPhase * COSMOS_DRIFT_RATE + b.driftPhase) * b.driftAmp
        b.obj.position.set(b.baseX, y, b.baseZ)
        b.obj.asDynamic().quaternion.copy(q)
    }
}

/**
 * Clears the cosmos registry on close. Called from [closeWorld3dSpike]; like the
 * beacons, no scene/DOM teardown is needed — the whole CSS3D scene and overlay are
 * discarded wholesale there, and every cosmos body owns its DOM outright.
 *
 * @see buildCosmos
 */
internal fun clearCosmos() {
    spikeCosmos.clear()
}

/**
 * Builds one **planet** as a DOM element: a circular disc with a radial gradient
 * lit from the upper-left, an inset shadow carving the terminator on the lower-right,
 * and a soft outer glow for atmosphere — which, billboarded, reads as a lit sphere
 * from every angle. Optionally adds a Saturn-style tilted ring ellipse.
 *
 * @param size disc diameter in px (world units at scale 1).
 * @param light the lit-side colour (top-left of the gradient).
 * @param mid the body's main colour.
 * @param dark the night-side colour the gradient falls to.
 * @param glow the atmosphere glow as an `rgba(...)` string.
 * @param ringColor optional ring colour (an `rgba(...)` string), or `null` for none.
 * @return the planet wrapper `<div>`, size×size px.
 * @see buildCosmos
 */
private fun planetEl(
    size: Int,
    light: String,
    mid: String,
    dark: String,
    glow: String,
    ringColor: String?,
): HTMLElement {
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${size}px;height:${size}px;position:relative;pointer-events:none;"

    val ball = document.createElement("div") as HTMLElement
    ball.style.cssText = "position:absolute;inset:0;border-radius:50%;" +
        "background:radial-gradient(circle at 32% 28%,$light,$mid 55%,$dark 100%);" +
        "box-shadow:inset ${-size / 5}px ${-size / 8}px ${size / 3}px rgba(0,0,0,0.6)," +
        "0 0 ${size / 4}px $glow;"
    wrapper.appendChild(ball)

    if (ringColor != null) {
        // A flat elliptical outline, tilted a touch — on the billboarded plane it
        // reads as an inclined ring system without needing a second 3D plane.
        val rw = (size * 1.9).roundToInt()
        val rh = (size * 0.62).roundToInt()
        val stroke = (size * 0.045).roundToInt().coerceAtLeast(2)
        val ring = document.createElement("div") as HTMLElement
        ring.style.cssText = "position:absolute;left:50%;top:50%;width:${rw}px;height:${rh}px;" +
            "margin-left:${-rw / 2}px;margin-top:${-rh / 2}px;border-radius:50%;" +
            "border:${stroke}px solid $ringColor;transform:rotate(-16deg);" +
            "box-shadow:0 0 ${size / 8}px $ringColor;opacity:0.75;"
        wrapper.appendChild(ring)
    }
    return wrapper
}

/**
 * Builds one **nebula** as a DOM element: two offset, overlapping elliptical
 * radial gradients fading to transparent — a soft luminous wash with no hard
 * edge, so it needs no blur filter (kept cheap: `filter:blur` on a huge plane
 * would re-rasterize every frame as the transform changes).
 *
 * @param w plane width in px. @param h plane height in px.
 * @param colorA the first wash colour (an `rgba(...)` string, low alpha).
 * @param colorB the second, offset wash colour.
 * @return the nebula `<div>`, w×h px.
 * @see buildCosmos
 */
private fun nebulaEl(w: Int, h: Int, colorA: String, colorB: String): HTMLElement {
    val el = document.createElement("div") as HTMLElement
    el.style.cssText = "width:${w}px;height:${h}px;pointer-events:none;" +
        "background:radial-gradient(ellipse at 38% 45%,$colorA,transparent 62%)," +
        "radial-gradient(ellipse at 64% 55%,$colorB,transparent 60%);"
    return el
}

/**
 * Builds one **star cluster** as a DOM element: a transparent square plane
 * scattered with tiny glowing dots. Grouping stars onto a handful of billboarded
 * cluster planes (rather than one CSS3D object per star) keeps the object count —
 * and the per-frame transform writes — low while still giving inter-plane parallax.
 *
 * Placement uses a tiny seeded LCG so the sky is deterministic across opens
 * (no `Math.random` — the same constellation greets you every time).
 *
 * @param size plane side length in px.
 * @param count number of stars to scatter.
 * @param seed LCG seed; give each cluster its own so they don't repeat.
 * @return the cluster wrapper `<div>`, size×size px.
 * @see buildCosmos
 */
private fun starClusterEl(size: Int, count: Int, seed: Int): HTMLElement {
    var s = seed
    // Minimal LCG (glibc constants), returning 0..1.
    fun rnd(): Double {
        s = (s * 1103515245 + 12345) and 0x7fffffff
        return s / 2147483647.0
    }
    val sb = StringBuilder()
    repeat(count) {
        val x = (rnd() * 100).roundToInt()
        val y = (rnd() * 100).roundToInt()
        val d = 2 + (rnd() * 3).roundToInt()
        val o = 0.25 + rnd() * 0.6
        val glow = if (rnd() > 0.72) "box-shadow:0 0 ${d * 3}px rgba(190,215,255,0.8);" else ""
        sb.append(
            "<div style=\"position:absolute;left:$x%;top:$y%;width:${d}px;height:${d}px;" +
                "border-radius:50%;background:#cfe1ff;opacity:$o;$glow\"></div>",
        )
    }
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${size}px;height:${size}px;position:relative;pointer-events:none;"
    wrapper.innerHTML = sb.toString()
    return wrapper
}
