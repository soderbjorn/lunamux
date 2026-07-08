/**
 * The **home beacon** — a large neon double-chevron arrow hovering just above the
 * spike's default camera position, pointing at the pane sphere. It is the free-fly
 * landmark: once you fly off with `F` it is the unmistakable "home is here, screens
 * are that way" marker, and because it sits directly *above* the home pose it is
 * outside the camera frustum at rest, so the normal ring view is untouched.
 *
 * The spike scene is pure CSS3D (real DOM planes, no WebGL meshes), so the beacon is
 * built from two SVG chevron planes **crossed at 90°** and slowly **spun** about the
 * arrow's own pointing axis — the cross + spin is what keeps a flat-plane arrow
 * readable as a volumetric object from every flight angle instead of vanishing when
 * seen edge-on. The glow pulse is a pure-CSS keyframe animation (no per-frame DOM
 * writes); only the spin is JS-driven, because the CSS3D renderer owns each plane's
 * `transform` and would overwrite a CSS transform animation.
 *
 * Scene-graph shape:
 * ```
 * scene
 * └─ aim Group   — position (0, BEACON_Y, homeZ), rotation.x aims local −Y at the origin
 *    └─ spin Group — rotation.y ticked each frame (the spin)
 *       ├─ CSS3DObject(chevron)  rotation.y = 0
 *       └─ CSS3DObject(chevron)  rotation.y = π/2
 * ```
 *
 * @see buildHomeBeacon
 * @see clearHomeBeacon
 * @see BEACON_Y
 */
package se.soderbjorn.termtastic

import kotlin.math.PI
import kotlin.math.atan2
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.Group
import se.soderbjorn.termtastic.three.Scene

/**
 * Builds the home beacon and adds it to the CSS3D scene. Called once per open from
 * [openWorld3dSpike], right after the theme chrome is resolved (the chevrons glow in
 * the theme accent so the beacon matches the pane chrome).
 *
 * Placement: `(0, BEACON_Y, homeZ)` where `homeZ` is the home camera distance for the
 * *current* window height. The arrow aims its local −Y (the chevrons' pointing
 * direction) at the origin with a single fixed X-tilt: rotating (0,−1,0) by `Rx(θ)`
 * gives (0,−cosθ,−sinθ), which is parallel to the beacon→origin direction
 * (0,−y,−z)/|P| exactly when **θ = atan2(z, y)**. The aim is not recomputed on window
 * resize — a few degrees of drift on a landmark is invisible.
 *
 * @param scene the CSS3D scene to add the beacon into.
 * @param chrome the resolved theme colours; the chevron stroke/glow uses [SpikeChrome.accent].
 * @see clearHomeBeacon
 * @see startSpikeLoop
 */
internal fun buildHomeBeacon(scene: Scene, chrome: SpikeChrome) {
    val homeZ = RING_R + perspDistance(window.innerHeight)

    val aim = Group()
    aim.position.set(0.0, BEACON_Y, homeZ)
    aim.rotation.set(atan2(homeZ, BEACON_Y), 0.0, 0.0)

    val spin = Group()
    aim.add(spin)

    val planeA = CSS3DObject(buildBeaconChevron(chrome, withPulseKeyframes = true))
    val planeB = CSS3DObject(buildBeaconChevron(chrome, withPulseKeyframes = false))
    planeB.rotation.set(0.0, PI / 2.0, 0.0)
    spin.add(planeA)
    spin.add(planeB)

    scene.add(aim)
    spikeBeaconSpin = spin
    spikeBeaconPhase = 0.0

    // The "COMMAND CENTER" banner floats [BEACON_LABEL_RISE] world-units above the
    // beacon anchor (further from the origin than the chevron, so it reads as a sign
    // *over* the arrow). Unlike the crossed chevrons it does NOT spin or billboard —
    // it is a real sign fixed in world space, facing +Z (away from the origin, toward
    // the home camera spot); flying around it, you see it swing through 3D like any
    // other object.
    val label = CSS3DObject(buildBeaconBanner(chrome, BEACON_LABEL_TEXT, BEACON_LABEL_FONT_PX))
    label.position.set(0.0, BEACON_Y + BEACON_LABEL_RISE, homeZ)
    scene.add(label)
    spikeBeaconLabel = label
}

/**
 * Builds one glowing text-banner plane for a beacon: [text] split on its single space
 * into two stacked lines, drawn in the theme accent with the same neon bloom (layered
 * text-shadows) and breathing pulse as the chevrons, so the sign reads as part of the
 * beacon rather than a bolted-on caption. Shared by the home ([buildHomeBeacon]) and
 * stash ([buildStashBeacon]) beacons.
 *
 * @param chrome the theme colours ([SpikeChrome.accent] fills and glows the text).
 * @param text the banner words; a single embedded space becomes the line break.
 * @param fontPx the per-line font size in px (world units at scale 1).
 * @return the banner wrapper `<div>`, carrying the `spike-beacon-label` class so
 *   [restyleWorldChrome] can recolour it on a live theme change.
 * @see buildHomeBeacon
 */
internal fun buildBeaconBanner(chrome: SpikeChrome, text: String, fontPx: Int): HTMLElement {
    val wrapper = document.createElement("div") as HTMLElement
    // Class lets [restyleWorldChrome] find and recolour the banner on a live theme
    // change, alongside the accent-stroked chevron glyphs.
    wrapper.className = "spike-beacon-label"
    // Auto-sized to the text; the CSS3D renderer centres each plane on its object
    // position (translate(-50%,-50%)), so the banner hangs symmetrically above the
    // beacon regardless of the word lengths — no manual offset needed.
    wrapper.style.cssText =
        "pointer-events:none;white-space:nowrap;text-align:center;" +
            "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
            "font-weight:800;letter-spacing:0.08em;line-height:1.05;" +
            "font-size:${fontPx}px;color:${chrome.accent};" +
            "text-shadow:0 0 24px ${chrome.accent},0 0 72px ${chrome.accent};" +
            "animation:spike-beacon-pulse ${BEACON_PULSE_S}s ease-in-out infinite;"
    // Two stacked lines read as a squarer, sign-like block above the beacon; fall back
    // to the whole phrase on one line if it has no space to split on.
    val parts = text.split(' ', limit = 2)
    wrapper.innerHTML = parts.joinToString("<br>") { it }
    return wrapper
}

/**
 * Builds one glowing double-chevron plane (`▼▼` pointing down, i.e. along local −Y)
 * as a DOM element for a [CSS3DObject]. The chevron is horizontally symmetric, so
 * its mirrored backface reads identically and is deliberately left visible — the
 * plane looks right from both sides.
 *
 * @param chrome the theme colours ([SpikeChrome.accent] strokes and glows the chevrons).
 * @param withPulseKeyframes whether to embed the shared `@keyframes` rule — the
 *   `<style>` is document-scoped once attached, so only the first plane carries it.
 * @return the chevron wrapper `<div>`, [BEACON_W]×[BEACON_H] px.
 * @see buildHomeBeacon
 */
private fun buildBeaconChevron(chrome: SpikeChrome, withPulseKeyframes: Boolean): HTMLElement {
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${BEACON_W}px;height:${BEACON_H}px;pointer-events:none;"

    if (withPulseKeyframes) {
        val style = document.createElement("style")
        style.textContent =
            "@keyframes spike-beacon-pulse{0%,100%{opacity:0.45;}50%{opacity:1;}}"
        wrapper.appendChild(style)
    }

    // Two nested chevrons, thick round strokes, doubled drop-shadow for the neon
    // bloom; opacity breathes via the shared keyframes at the halo's calm cadence.
    wrapper.innerHTML += """
        <svg class="spike-beacon-glyph" viewBox="0 0 640 900" width="$BEACON_W" height="$BEACON_H"
             fill="none" stroke="${chrome.accent}" stroke-width="64"
             stroke-linecap="round" stroke-linejoin="round"
             style="filter:drop-shadow(0 0 26px ${chrome.accent}) drop-shadow(0 0 90px ${chrome.accent});
                    animation:spike-beacon-pulse ${BEACON_PULSE_S}s ease-in-out infinite;">
            <polyline points="80,140 320,440 560,140"/>
            <polyline points="80,500 320,800 560,500"/>
        </svg>
    """.trimIndent()
    return wrapper
}

/**
 * Clears the beacon globals on close. Called from [closeWorld3dSpike]; no scene/DOM
 * teardown is needed because the whole CSS3D scene is discarded and the overlay
 * removed wholesale there — unlike the panes, the beacon owns its DOM outright, so
 * nothing has to be reparented back.
 *
 * @see buildHomeBeacon
 */
internal fun clearHomeBeacon() {
    spikeBeaconSpin = null
    spikeBeaconPhase = 0.0
    spikeBeaconLabel = null
}
