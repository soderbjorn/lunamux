/**
 * Kotlin/JS external declarations for three.js's **CSS3DRenderer** addon.
 *
 * The core WebGL renderer ([se.soderbjorn.termtastic.three.WebGLRenderer])
 * draws textured planes — snapshots of terminals, not interactive ones. The
 * Phase-2 world-mode spike ([se.soderbjorn.termtastic.World3DSpike]) needs the
 * opposite: a *real, typeable* xterm.js terminal positioned in 3D. That is what
 * this addon provides — it renders real DOM elements as CSS-`transform`ed 3D
 * planes, sharing the same [PerspectiveCamera] as the WebGL scene, so a live
 * terminal (full input, cursor, selection, crisp text) can sit in the same
 * space as WebGL geometry.
 *
 * Resolved from the three npm package's addons folder (`examples/jsm`, aliased
 * as `three/addons`); three 0.170.x ships CSS3DRenderer there. Deliberately
 * minimal, in the spirit of [se.soderbjorn.termtastic.three] — only the surface
 * the spike calls.
 *
 * @see se.soderbjorn.termtastic.three.WebGLRenderer
 * @see se.soderbjorn.termtastic.World3DSpike
 */
@file:JsModule("three/examples/jsm/renderers/CSS3DRenderer.js")
@file:JsNonModule

package se.soderbjorn.termtastic.three

import org.w3c.dom.HTMLElement

/**
 * three.js `CSS3DRenderer` — renders a scene of [CSS3DObject]s by applying CSS
 * 3D `transform`s to their real DOM elements, using the same camera as the
 * WebGL renderer. Its [domElement] is a wrapper `<div>` layered over (or under)
 * the WebGL canvas.
 *
 * Note the compositing rule this addon lives by: the whole CSS3D layer sits in
 * one browser compositing plane, so WebGL geometry cannot be drawn *in front of*
 * an individual CSS3D element — the DOM plane and the WebGL canvas are two
 * stacked layers. CSS3D elements *do* occlude each other correctly.
 */
external class CSS3DRenderer {
    /** The wrapper element to append over the WebGL canvas; holds every transformed plane. */
    val domElement: HTMLElement

    /**
     * Sizes the renderer to the viewport.
     *
     * @param width CSS pixel width. @param height CSS pixel height.
     */
    fun setSize(width: Int, height: Int)

    /**
     * Renders one frame: re-applies each object's CSS 3D transform for the
     * current camera pose.
     *
     * @param scene the CSS3D scene (holds [CSS3DObject]s).
     * @param camera the shared perspective camera.
     */
    fun render(scene: Scene, camera: PerspectiveCamera)
}

/**
 * three.js `CSS3DObject` — wraps a real DOM element so it can be placed in a
 * three.js scene like a mesh. Its world size is the element's *pixel* size times
 * its [scale], so a terminal `<div>` is typically scaled down (~0.01) to occupy
 * a sensible number of world units.
 *
 * @param element the live DOM element (here, an xterm.js terminal container).
 */
external class CSS3DObject(element: HTMLElement) {
    /** Position vector (dynamic — use `.set(x, y, z)`). */
    val position: dynamic

    /** Euler rotation (dynamic — use `.set(x, y, z)`; the spike tilts `.y`). */
    val rotation: dynamic

    /** Scale vector (dynamic — use `.set(s, s, s)`; shrinks px → world units). */
    val scale: dynamic

    /** Render/visibility toggle. */
    var visible: Boolean
}
