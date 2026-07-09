/**
 * Kotlin/JS external declarations for the three.js 3D rendering library.
 *
 * Minimal, purpose-built bindings covering exactly the surface the 3D tab
 * overview ([Overview3D]) needs: a scene with a perspective camera and WebGL
 * renderer, textured planes (one per tab card, textured from the live
 * thumbnail canvases via [CanvasTexture]), a [Group] to spin them as one
 * carousel ring, and a [Raycaster] for click/hover picking.
 *
 * Deliberately *not* a general three.js binding: vector-valued properties
 * (`position`, `rotation`, `scale`) are typed `dynamic` and used through
 * their JS `set(x, y, z)` methods, which keeps the binding surface tiny while
 * remaining fully type-checked at the call sites that matter (constructors
 * and render calls).
 *
 * The declarations live in the `three` subpackage so names like [Scene] can't
 * collide with app types in `se.soderbjorn.lunamux`.
 *
 * @see se.soderbjorn.lunamux.toggleOverview3d
 */
@file:JsModule("three")
@file:JsNonModule

package se.soderbjorn.lunamux.three

import org.w3c.dom.HTMLCanvasElement

/**
 * three.js `Scene` — the root of the 3D object graph handed to
 * [WebGLRenderer.render].
 *
 * Created once (and cached) by the overview's pre-warm path; cards are added
 * and removed per open/close via the carousel [Group].
 */
external class Scene {
    /**
     * Adds a child object (mesh/group) to the scene graph.
     *
     * @param obj any three.js `Object3D` (typed dynamic — see file kdoc).
     */
    fun add(obj: dynamic)

    /**
     * Removes a child object previously added with [add].
     *
     * @param obj the same object reference that was added.
     */
    fun remove(obj: dynamic)
}

/**
 * three.js `Group` — an invisible transform node. The overview parents every
 * tab card to one group and animates the group's `rotation.y` so the whole
 * carousel spins as a unit.
 */
external class Group {
    /** Position vector (`Vector3`, dynamic — use `.set(x, y, z)`). */
    val position: dynamic

    /** Euler rotation (dynamic — the overview animates `.y`). */
    val rotation: dynamic

    /**
     * Adds a child object to the group.
     *
     * @param obj any three.js `Object3D`.
     */
    fun add(obj: dynamic)

    /**
     * Removes a child object from the group.
     *
     * @param obj the same object reference that was added.
     */
    fun remove(obj: dynamic)

    /** Live child list (used to clear the ring between opens). */
    val children: Array<dynamic>
}

/**
 * three.js `PerspectiveCamera`. The overview keeps a single camera cached for
 * the app's lifetime, repositioning it per open (ring radius varies with tab
 * count) and animating it during the dive-in activation transition.
 *
 * @param fov vertical field of view in degrees.
 * @param aspect viewport aspect ratio (width / height).
 * @param near near clipping plane distance.
 * @param far far clipping plane distance.
 */
external class PerspectiveCamera(fov: Double, aspect: Double, near: Double, far: Double) {
    /** Position vector (dynamic — use `.set(x, y, z)`). */
    val position: dynamic

    /**
     * The camera's up vector (`Vector3`, dynamic — use `.set(x, y, z)`).
     * Consulted by [lookAt] to decide the roll of the view. Most styles leave
     * it at the default `(0, 1, 0)`; the [se.soderbjorn.lunamux.OrbitStyle]
     * rolls it per-frame to *bank* the camera into its curved fly-throughs.
     */
    val up: dynamic

    /** Viewport aspect ratio; call [updateProjectionMatrix] after changing. */
    var aspect: Double

    /**
     * Vertical field of view in degrees; call [updateProjectionMatrix] after
     * changing. Most styles keep the fixed opening fov, but the
     * [se.soderbjorn.lunamux.VertigoStyle] animates it against the camera
     * distance for the Hitchcock *dolly-zoom*, and the
     * [se.soderbjorn.lunamux.OrbitStyle] widens it briefly as a warp punch
     * at the fastest part of a fly-through.
     */
    var fov: Double

    /**
     * Points the camera at a world-space position.
     *
     * @param x world X. @param y world Y. @param z world Z.
     */
    fun lookAt(x: Double, y: Double, z: Double)

    /** Recomputes the projection matrix after [aspect]/fov changes. */
    fun updateProjectionMatrix()
}

/**
 * three.js `WebGLRenderer`. Created once by the overview's pre-warm path
 * (paying WebGL context creation + first-shader compilation at boot rather
 * than on first hotkey press) and reused for every open.
 *
 * @param parameters constructor options object — the overview passes
 *   `{ antialias: true, alpha: true }` so the CSS gradient behind the canvas
 *   shows through.
 */
external class WebGLRenderer(parameters: dynamic = definedExternally) {
    /** The canvas the renderer draws into; appended to the overlay DOM. */
    val domElement: HTMLCanvasElement

    /**
     * Resizes the drawing buffer (and, by default, the canvas CSS size).
     *
     * @param width CSS pixel width. @param height CSS pixel height.
     * @param updateStyle pass `false` to leave CSS sizing to the stylesheet.
     */
    fun setSize(width: Int, height: Int, updateStyle: Boolean = definedExternally)

    /**
     * Sets the device-pixel-ratio multiplier for the drawing buffer.
     *
     * @param value typically `window.devicePixelRatio`.
     */
    fun setPixelRatio(value: Double)

    /**
     * Renders one frame.
     *
     * @param scene the scene graph root. @param camera the viewpoint.
     */
    fun render(scene: Scene, camera: PerspectiveCamera)

    /** Releases the GL context. Not called in practice (app-lifetime cache). */
    fun dispose()
}

/**
 * three.js `PlaneGeometry` — the flat rectangle each tab card is built from.
 * Cards use the two-arg (unsubdivided) form; pane tiles pass segment counts so
 * their vertices can be displaced into a shallow dish (`dishGeometry`) for a
 * genuinely curved surface. `attributes.position` / `computeVertexNormals` are
 * reached through `asDynamic()` at the (single) call site that bends the mesh.
 *
 * @param width world-space width. @param height world-space height.
 * @param widthSegments columns of subdivision (default 1 = flat quad).
 * @param heightSegments rows of subdivision (default 1 = flat quad).
 */
external class PlaneGeometry(
    width: Double,
    height: Double,
    widthSegments: Int = definedExternally,
    heightSegments: Int = definedExternally,
) {
    /** Frees GPU buffers. Called when the overview closes. */
    fun dispose()
}

/**
 * three.js `MeshBasicMaterial` — unlit material; exactly right for thumbnail
 * cards that should read like screens, not lit surfaces.
 *
 * @param parameters options object — the overview passes `{ map: texture }`
 *   for cards and `{ color, transparent: true, opacity, depthWrite: false }`
 *   for glow halos.
 */
external class MeshBasicMaterial(parameters: dynamic = definedExternally) {
    /** Live opacity (animated for the activity glow / selection halo). */
    var opacity: Double

    /** Frees GPU resources. Called when the overview closes. */
    fun dispose()
}

/**
 * three.js `CanvasTexture` — wraps an HTML canvas as a GPU texture and
 * re-uploads it whenever [needsUpdate] is set. This is the bridge between
 * the existing 2D thumbnail renderer ([se.soderbjorn.lunamux.renderThumbnail])
 * and the 3D scene: every thumbnail repaint just flags the texture dirty.
 *
 * @param canvas the source canvas (the overview's per-tab thumbnail canvas).
 */
external class CanvasTexture(canvas: HTMLCanvasElement) {
    /** Set to `true` after painting the canvas to re-upload the texture. */
    var needsUpdate: Boolean

    /**
     * Color space tag; set to `"srgb"` so three.js r152+ color management
     * doesn't wash out the canvas colors.
     */
    var colorSpace: String

    /** Frees the GPU texture. Called when the overview closes. */
    fun dispose()
}

/**
 * three.js `Mesh` — geometry + material; one per tab card, one per glow halo
 * (the halo is added as a *child* of its card so it inherits the card's
 * carousel transform).
 *
 * @param geometry the shape (a shared [PlaneGeometry] here).
 * @param material the surface (a [MeshBasicMaterial] here).
 */
external class Mesh(geometry: dynamic = definedExternally, material: dynamic = definedExternally) {
    /** Position vector (dynamic — use `.set(x, y, z)`). */
    val position: dynamic

    /** Euler rotation (dynamic — cards face outward via `.y`). */
    val rotation: dynamic

    /** Scale vector (dynamic — used for the hover pop). */
    val scale: dynamic

    /** App payload; the overview stores the card index for raycast picking. */
    var userData: dynamic

    /** Render/raycast visibility toggle (pane tiles hide while assembled). */
    var visible: Boolean

    /**
     * Adds a child object (the glow halo) that inherits this mesh's transform.
     *
     * @param obj any three.js `Object3D`.
     */
    fun add(obj: dynamic)
}

/**
 * three.js `Raycaster` — converts a pointer position into scene intersections;
 * the overview uses it for click-to-select/activate and hover feedback.
 */
external class Raycaster {
    /**
     * Aims the ray from the camera through a normalized-device-coordinate
     * point (x/y in -1..1).
     *
     * @param coords NDC pointer position. @param camera the scene camera.
     */
    fun setFromCamera(coords: Vector2, camera: PerspectiveCamera)

    /**
     * Intersects the ray with the given objects.
     *
     * @param objects candidate meshes (the tab cards).
     * @param recursive whether to test descendants too.
     * @return intersections sorted nearest-first; each carries `.object`.
     */
    fun intersectObjects(objects: Array<dynamic>, recursive: Boolean = definedExternally): Array<dynamic>
}

/**
 * three.js `Vector2` — used only as the NDC pointer argument to
 * [Raycaster.setFromCamera].
 *
 * @param x NDC x (-1..1). @param y NDC y (-1..1).
 */
external class Vector2(x: Double = definedExternally, y: Double = definedExternally) {
    /** NDC x component. */
    var x: Double

    /** NDC y component. */
    var y: Double
}
