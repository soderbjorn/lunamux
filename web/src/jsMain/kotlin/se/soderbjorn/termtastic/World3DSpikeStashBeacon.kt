/**
 * The **stash beacon** — the home beacon's sibling landmark, hovering above the stash
 * shelf far up in the sky. Where the home beacon is a neon double-chevron *arrow*
 * ("home is here, screens are that way"), the stash beacon is a pair of nested neon
 * *diamond* outlines — a "storage crystal" marking where shelved panes live. It gives
 * the stash journey a destination you can see growing nearer through the whole flight,
 * and makes the shelf findable in free-fly even when nothing is stashed (an empty shelf
 * is otherwise featureless sky at [STASH_SHELF_Y]).
 *
 * Construction mirrors [buildHomeBeacon]: the scene is pure CSS3D, so the crystal is
 * two SVG planes **crossed at 90°** and slowly spun about the vertical axis — the
 * cross + spin keeps flat planes reading as a volumetric object from every flight
 * angle. It spins the **opposite direction** at a different cadence to the home beacon
 * ([STASH_BEACON_SPIN_SPEED] < 0), and its glow breathes slower
 * ([STASH_BEACON_PULSE_S]), so the two landmarks read as siblings rather than copies.
 * Only the spin is JS-driven (the CSS3D renderer owns each plane's `transform`); the
 * pulse is a pure-CSS keyframe animation.
 *
 * Scene-graph shape:
 * ```
 * scene
 * └─ spin Group — position (0, STASH_SHELF_Y + STASH_BEACON_RISE, STASH_SHELF_Z),
 *    │            rotation.y ticked each frame (the counter-spin)
 *    ├─ CSS3DObject(diamond)  rotation.y = 0
 *    └─ CSS3DObject(diamond)  rotation.y = π/2
 * ```
 * (No aim group: a diamond has no pointing direction, so the crystal stands upright.)
 *
 * @see buildStashBeacon
 * @see clearStashBeacon
 * @see buildHomeBeacon
 */
package se.soderbjorn.termtastic

import kotlin.math.PI
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.Group
import se.soderbjorn.termtastic.three.Scene

/**
 * Builds the stash beacon and adds it to the CSS3D scene. Called once per open from
 * [openWorld3dSpike], right after [buildHomeBeacon] (the diamonds glow in the same
 * theme accent so both landmarks match the pane chrome).
 *
 * Placement: centred over the shelf row's x-origin neighbourhood at
 * `(0, STASH_SHELF_Y + STASH_BEACON_RISE, STASH_SHELF_Z)` — high enough above the row
 * that shelved panes never overlap it, and dead ahead of the stash-view camera pose so
 * it crowns the shelf when you arrive.
 *
 * @param scene the CSS3D scene to add the beacon into.
 * @param chrome the resolved theme colours; the diamond stroke/glow uses [SpikeChrome.accent].
 * @see clearStashBeacon
 * @see startSpikeLoop
 */
internal fun buildStashBeacon(scene: Scene, chrome: SpikeChrome) {
    val spin = Group()
    spin.position.set(0.0, STASH_SHELF_Y + STASH_BEACON_RISE, STASH_SHELF_Z)

    val planeA = CSS3DObject(buildStashDiamond(chrome, withPulseKeyframes = true))
    val planeB = CSS3DObject(buildStashDiamond(chrome, withPulseKeyframes = false))
    planeB.rotation.set(0.0, PI / 2.0, 0.0)
    spin.add(planeA)
    spin.add(planeB)

    scene.add(spin)
    spikeStashBeaconSpin = spin
    spikeStashBeaconPhase = 0.0
}

/**
 * Builds one glowing nested-diamond plane (`◇` inside `◇`) as a DOM element for a
 * [CSS3DObject]. The diamond is symmetric about both axes, so its mirrored backface
 * reads identically and is deliberately left visible — the plane looks right from
 * both sides.
 *
 * @param chrome the theme colours ([SpikeChrome.accent] strokes and glows the diamonds).
 * @param withPulseKeyframes whether to embed the shared `@keyframes` rule — the
 *   `<style>` is document-scoped once attached, so only the first plane carries it.
 * @return the diamond wrapper `<div>`, [STASH_BEACON_S]×[STASH_BEACON_S] px.
 * @see buildStashBeacon
 */
private fun buildStashDiamond(chrome: SpikeChrome, withPulseKeyframes: Boolean): HTMLElement {
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${STASH_BEACON_S}px;height:${STASH_BEACON_S}px;pointer-events:none;"

    if (withPulseKeyframes) {
        val style = document.createElement("style")
        style.textContent =
            "@keyframes spike-stash-beacon-pulse{0%,100%{opacity:0.4;}50%{opacity:1;}}"
        wrapper.appendChild(style)
    }

    // Two nested diamond outlines, thick round strokes, doubled drop-shadow for the
    // neon bloom; opacity breathes via the shared keyframes at its own calm cadence.
    wrapper.innerHTML += """
        <svg class="spike-beacon-glyph" viewBox="0 0 640 640" width="$STASH_BEACON_S" height="$STASH_BEACON_S"
             fill="none" stroke="${chrome.accent}" stroke-width="56"
             stroke-linecap="round" stroke-linejoin="round"
             style="filter:drop-shadow(0 0 26px ${chrome.accent}) drop-shadow(0 0 90px ${chrome.accent});
                    animation:spike-stash-beacon-pulse ${STASH_BEACON_PULSE_S}s ease-in-out infinite;">
            <polygon points="320,48 592,320 320,592 48,320"/>
            <polygon points="320,208 432,320 320,432 208,320"/>
        </svg>
    """.trimIndent()
    return wrapper
}

/**
 * Clears the stash beacon globals on close. Called from [closeWorld3dSpike] alongside
 * [clearHomeBeacon]; no scene/DOM teardown is needed because the whole CSS3D scene is
 * discarded and the overlay removed wholesale there — the beacon owns its DOM outright,
 * so nothing has to be reparented back.
 *
 * @see buildStashBeacon
 */
internal fun clearStashBeacon() {
    spikeStashBeaconSpin = null
    spikeStashBeaconPhase = 0.0
}
