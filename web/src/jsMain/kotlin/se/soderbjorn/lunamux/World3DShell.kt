/**
 * **Full-shell 3D mode** — the entire 2D Lunamux interface, rendered as a single
 * live CSS3D plane you can rotate on any axis.
 *
 * Where [World3DSpike] explodes the app into a rotunda of *individual* pane
 * planes, this mode does the opposite and far simpler thing: it takes the app
 * shell's one root element (`div.dt-frame` — topbar, sidebars, tab strip, pane
 * layout and all; see the toolkit's `renderAppFrame`) and reparents that whole
 * subtree onto a *single* [CSS3DObject]. Nothing is re-implemented, textured, or
 * mirrored: it is the same DOM the 2D app was already running, just with a
 * `matrix3d` on it.
 *
 * The consequence is the point of the exercise — **everything keeps working**.
 * Sidebars, settings, the theme editor, tab drag, xterm.js input and streaming
 * all behave exactly as in 2D, because none of their code paths can tell the
 * difference: CSS3D is a paint-time transform, and the browser's own hit-testing
 * walks it. There is no raycasting here and no event remapping (contrast
 * [Overview3D], which needs both because its panes are `CanvasTexture`
 * snapshots).
 *
 * At the resting pose the plane is billboarded at an exact **1:1 pixel mapping**
 * (camera distance derived from the FOV — see [shellCameraDistance]), so the
 * shell is pixel-identical to flat 2D and every interaction is pixel-accurate.
 * The rotation shortcuts ([SHELL_ROT_STEP_DEG]) exist to *prove* it is genuinely
 * a 3D-projected plane rather than a flat div.
 *
 * **Known limits of this spike** — see the notes on [openWorld3dShell] and
 * [rotateShell]:
 *  - Body-mounted popovers (menus, dialogs, toasts) do not ride the transform.
 *  - xterm's mouse→cell math is wrong while rotated away from rest.
 *
 * **Not currently reachable.** This mode has no entry point: it had a topbar
 * button and an ⌥⌘U hotkey, both removed once it had served its purpose. It is
 * kept as the executable proof behind the [World3DIntro] cinematic — the intro
 * clones the shell rather than lifting the live one, and its header explains
 * why that is the right trade for *that* job. Re-wire [toggleWorld3dShell] from
 * [LunamuxToolkitBootstrap] to bring it back.
 *
 * @see toggleWorld3dShell
 * @see World3DSpike
 * @see World3DIntro
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.CSS3DRenderer
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * Vertical field of view, in degrees, of the full-shell camera.
 *
 * Only mildly consequential: it cancels out of the resting pose (which is 1:1 by
 * construction — [shellCameraDistance] solves for whatever FOV is set here), so
 * it purely governs how strong the perspective foreshortening looks *while*
 * rotated. 45° reads as a natural "screen in a room" rather than a fisheye.
 */
private const val SHELL_FOV: Double = 45.0

/** Degrees added per rotation keypress. Coarse enough that one tap is unmistakable. */
private const val SHELL_ROT_STEP_DEG: Double = 12.0

/** Radians/frame the plane yaws while auto-tumble (⌃⌥Space) is armed. */
private const val SHELL_TUMBLE_SPEED: Double = 0.006

/**
 * Per-frame easing factor for the current→target rotation lerp. Low enough to
 * read as a glide rather than a snap; high enough to settle within ~½ second.
 */
private const val SHELL_EASE: Double = 0.12

/** True while full-shell 3D mode is open. @see toggleWorld3dShell */
internal var shellOpen: Boolean = false
    private set

/** The full-viewport host layered over the (now-empty) `#app`. Null while closed. */
private var shellOverlay: HTMLElement? = null

/** The CSS3D renderer driving the single shell plane. Null while closed. */
private var shellCss3d: CSS3DRenderer? = null

/** Scene holding exactly one object: the shell plane. Null while closed. */
private var shellScene: Scene? = null

/** The shared perspective camera. Null while closed. */
private var shellCamera: PerspectiveCamera? = null

/** The shell plane itself — wraps the reparented `div.dt-frame`. Null while closed. */
private var shellObject: CSS3DObject? = null

/**
 * The reparented app frame, held so [closeWorld3dShell] can put it back.
 *
 * @see shellOrigParent
 */
private var shellFrame: HTMLElement? = null

/** The frame's original parent (`#app`), captured on open for exact restoration. */
private var shellOrigParent: Node? = null

/** The frame's original next sibling, so restoration preserves DOM order. */
private var shellOrigNext: Node? = null

/** The frame's original inline `cssText`, restored verbatim on close. */
private var shellOrigCss: String = ""

/** Handle from `requestAnimationFrame`, used to cancel the loop on close. */
private var shellRaf: Int = 0

/** Live rotation, in radians, eased toward [shellTargetRot] each frame. */
private var shellRotX: Double = 0.0
private var shellRotY: Double = 0.0
private var shellRotZ: Double = 0.0

/** Rotation the keys command; [shellRotX]/[shellRotY]/[shellRotZ] chase these. */
private var shellTargetRot: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)

/** True while ⌃⌥Space auto-yaw is armed. @see SHELL_TUMBLE_SPEED */
private var shellTumble: Boolean = false

/** The capture-phase key listener, retained so close can detach exactly it. */
private var shellKeyHandler: ((Event) -> Unit)? = null

/** The window resize listener, retained for the same reason. */
private var shellResizeHandler: ((Event) -> Unit)? = null

/**
 * Camera distance, in world units (= CSS px, since the plane is unscaled), that
 * makes the plane render at an exact 1:1 pixel mapping.
 *
 * CSS3DRenderer sets its wrapper's CSS `perspective` to
 * `(height/2) / tan(fov/2)` px, and an element sitting exactly that far from the
 * camera projects at native size. Solving for the same quantity here is what
 * lets the resting pose be pixel-identical to flat 2D — which in turn is what
 * makes xterm's own mouse→cell arithmetic exact at rest.
 *
 * @param height viewport height in CSS px.
 * @return the camera's resting `z`.
 */
private fun shellCameraDistance(height: Int): Double =
    (height / 2.0) / tan(SHELL_FOV * PI / 360.0)

/**
 * Toggle full-shell 3D mode. The mode's only entry point — currently **unwired**:
 * the topbar button and ⌥⌘U hotkey that called this were removed, so nothing
 * reaches it. Call it from [LunamuxToolkitBootstrap] to restore the mode.
 *
 * Guarded against re-entry while the sibling world spike ([spikeOpen]) is up:
 * that mode has already reparented the individual terminals out of the frame, so
 * lifting the frame too would fight it over the same DOM.
 *
 * @see openWorld3dShell
 * @see closeWorld3dShell
 */
internal fun toggleWorld3dShell() {
    if (spikeOpen) return
    if (shellOpen) closeWorld3dShell() else openWorld3dShell()
}

/**
 * Enter full-shell 3D mode: build the overlay, lift `div.dt-frame` onto a single
 * CSS3D plane, and start the render loop.
 *
 * **On the overlay's z-index:** it is deliberately layered *below* the ~29
 * popovers the app and toolkit mount straight onto `document.body` (menus at
 * `z-index: 999`+, modals at `99998`). Those cannot ride the transform — they are
 * not in the frame's subtree — so they float flat above the plane. At the resting
 * 1:1 pose that is invisible (their `getBoundingClientRect()`-derived viewport
 * coordinates still land exactly on the widgets that opened them); while rotated
 * they visibly detach. Keeping them clickable rather than hiding them is the
 * right trade for a spike: the mode stays fully usable.
 *
 * @see closeWorld3dShell
 * @see shellCameraDistance
 */
private fun openWorld3dShell() {
    if (shellOpen) return
    val app = document.getElementById("app") as? HTMLElement ?: return
    val frame = app.firstElementChild as? HTMLElement ?: return

    shellOpen = true
    val w = window.innerWidth
    val h = window.innerHeight

    // Sits under the body-mounted popovers (see the KDoc note) and never eats a
    // pointer event itself — only the frame and the exit button opt back in.
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "world3d-shell-overlay"
    overlay.style.cssText =
        "position:fixed;inset:0;z-index:0;pointer-events:none;overflow:hidden;background:var(--dt-bg, #0b0d12);"
    document.body?.appendChild(overlay)
    shellOverlay = overlay

    val camera = PerspectiveCamera(SHELL_FOV, w.toDouble() / h.toDouble(), 1.0, 20000.0)
    camera.position.set(0.0, 0.0, shellCameraDistance(h))
    camera.lookAt(0.0, 0.0, 0.0)
    shellCamera = camera

    val css = CSS3DRenderer()
    css.setSize(w, h)
    css.domElement.style.cssText = "position:absolute;inset:0;pointer-events:none;"
    overlay.appendChild(css.domElement)
    shellCss3d = css

    val scene = Scene()
    shellScene = scene

    // Remember exactly where the frame came from, so close is a true restoration
    // rather than a re-append that would silently reorder or restyle the shell.
    shellFrame = frame
    shellOrigParent = frame.parentNode
    shellOrigNext = frame.nextSibling
    shellOrigCss = frame.style.cssText

    // A CSS3D plane's world size is its element's *pixel* size, so the frame needs
    // explicit px dimensions — it previously filled `#app` via percentage height.
    // Pinning it to the viewport it already occupied means no reflow, and hence no
    // FitAddon resize and no PTY SIGWINCH on entering the mode.
    frame.style.width = "${w}px"
    frame.style.height = "${h}px"

    val obj = CSS3DObject(frame)
    obj.position.set(0.0, 0.0, 0.0)
    // three.js's CSS3DObject constructor force-sets `user-select: none` on its
    // element (it assumes decorative planes). Left alone that would silently kill
    // terminal text selection and every input in the shell, so undo it right after
    // construction — the frame is the real, fully interactive app.
    frame.style.removeProperty("user-select")
    frame.style.setProperty("pointer-events", "auto")
    scene.add(obj)
    shellObject = obj

    overlay.appendChild(buildShellExitButton())

    shellRotX = 0.0; shellRotY = 0.0; shellRotZ = 0.0
    shellTargetRot = doubleArrayOf(0.0, 0.0, 0.0)
    shellTumble = false

    val keys = buildShellKeyHandler()
    shellKeyHandler = keys
    // Capture phase: the shell underneath is live and focused terminals would
    // otherwise swallow these chords before they ever reach us.
    window.addEventListener("keydown", keys, true)

    val onResize: (Event) -> Unit = { _ -> resizeWorld3dShell() }
    shellResizeHandler = onResize
    window.addEventListener("resize", onResize)

    shellTick()
}

/**
 * Leave full-shell 3D mode: stop the loop, put `div.dt-frame` back exactly where
 * it was, and tear the overlay down.
 *
 * Restoration order matters — the frame is reparented *before* the overlay is
 * removed, so the shell is never un-parented from the document even for a frame
 * (an orphaned xterm would measure zero and thrash its FitAddon).
 *
 * @see openWorld3dShell
 */
private fun closeWorld3dShell() {
    if (!shellOpen) return
    shellOpen = false

    if (shellRaf != 0) {
        window.cancelAnimationFrame(shellRaf)
        shellRaf = 0
    }
    shellKeyHandler?.let { window.removeEventListener("keydown", it, true) }
    shellKeyHandler = null
    shellResizeHandler?.let { window.removeEventListener("resize", it) }
    shellResizeHandler = null

    val frame = shellFrame
    if (frame != null) {
        shellObject?.let { shellScene?.remove(it) }
        // Drop every inline style CSS3D put on the element (transform, position,
        // user-select, the px sizing) by restoring the original cssText wholesale —
        // clearing them individually is what leaves a stray `transform` behind and
        // makes the restored 2D shell look subtly off.
        frame.style.cssText = shellOrigCss
        val parent = shellOrigParent
        if (parent != null) parent.insertBefore(frame, shellOrigNext)
    }

    shellOverlay?.remove()
    shellOverlay = null
    shellCss3d = null
    shellScene = null
    shellCamera = null
    shellObject = null
    shellFrame = null
    shellOrigParent = null
    shellOrigNext = null
    shellOrigCss = ""
}

/**
 * Re-fit the plane, renderer and camera to a resized window.
 *
 * Re-solving [shellCameraDistance] for the new height is what keeps the resting
 * pose at 1:1 across a resize; the frame is re-pinned to the viewport so the
 * shell reflows and re-fits its terminals exactly as the 2D app would.
 *
 * Called from the `resize` listener installed by [openWorld3dShell].
 */
private fun resizeWorld3dShell() {
    if (!shellOpen) return
    val w = window.innerWidth
    val h = window.innerHeight
    shellCss3d?.setSize(w, h)
    shellCamera?.let { cam ->
        cam.aspect = w.toDouble() / h.toDouble()
        cam.position.set(0.0, 0.0, shellCameraDistance(h))
        cam.updateProjectionMatrix()
    }
    shellFrame?.let { f ->
        f.style.width = "${w}px"
        f.style.height = "${h}px"
    }
}

/**
 * The render loop: ease the live rotation toward its target, apply it, draw.
 *
 * Deliberately cheap — one CSS `matrix3d` write per frame on a single element.
 * It parks itself (stops scheduling) once the rotation has settled and no tumble
 * is armed, so a stationary shell costs nothing; [rotateShell] and
 * [toggleShellTumble] restart it.
 */
private fun shellTick() {
    if (!shellOpen) return
    val obj = shellObject
    val css = shellCss3d
    val scene = shellScene
    val cam = shellCamera
    if (obj == null || css == null || scene == null || cam == null) return

    if (shellTumble) shellTargetRot[1] += SHELL_TUMBLE_SPEED

    shellRotX += (shellTargetRot[0] - shellRotX) * SHELL_EASE
    shellRotY += (shellTargetRot[1] - shellRotY) * SHELL_EASE
    shellRotZ += (shellTargetRot[2] - shellRotZ) * SHELL_EASE
    obj.rotation.set(shellRotX, shellRotY, shellRotZ)
    css.render(scene, cam)

    val settled = !shellTumble &&
        abs(shellTargetRot[0] - shellRotX) < 1e-4 &&
        abs(shellTargetRot[1] - shellRotY) < 1e-4 &&
        abs(shellTargetRot[2] - shellRotZ) < 1e-4
    shellRaf = if (settled) 0 else window.requestAnimationFrame { shellTick() }
}

/**
 * Nudge the plane's target rotation on one axis and wake the render loop.
 *
 * **Why rotation degrades xterm's mouse mapping:** xterm derives the cell under
 * the pointer from `getBoundingClientRect()`, which reports the *axis-aligned
 * bounding box* of a transformed element, not the transform itself. At rest the
 * box is the element, so the arithmetic is exact; rotated, the box grows and every
 * click resolves to the wrong cell. Plain buttons, menus and the sidebars are
 * unaffected — they rely on the browser's hit-testing, which does walk the 3D
 * transform. This is the same constraint that makes [World3DSpike] snap its
 * engaged pane to a 1:1 billboard, and the reason ⌃⌥0 exists.
 *
 * @param axis 0 = pitch (X), 1 = yaw (Y), 2 = roll (Z).
 * @param dirDeg signed degrees to add.
 */
private fun rotateShell(axis: Int, dirDeg: Double) {
    shellTargetRot[axis] += dirDeg * PI / 180.0
    if (shellRaf == 0) shellTick()
}

/**
 * Snap the plane back to the resting 1:1 pose (⌃⌥0), disarming auto-tumble.
 *
 * The escape hatch from the mouse-mapping drift described on [rotateShell]: once
 * settled here the shell is pixel-identical to flat 2D again.
 */
private fun resetShellRotation() {
    shellTumble = false
    shellTargetRot = doubleArrayOf(0.0, 0.0, 0.0)
    if (shellRaf == 0) shellTick()
}

/**
 * Arm/disarm the continuous yaw (⌃⌥Space).
 *
 * @see SHELL_TUMBLE_SPEED
 */
private fun toggleShellTumble() {
    shellTumble = !shellTumble
    if (shellRaf == 0) shellTick()
}

/**
 * Build the capture-phase key handler owning this mode's chords.
 *
 * Every binding is **⌃⌥-prefixed and matched on physical `code`**, for two
 * reasons: the shell underneath is live, so bare keys must keep flowing to
 * focused terminals untouched; and macOS's ⌥ rewrites `KeyboardEvent.key` to a
 * symbol (⌥X → "≈"), so `key` matching would simply not fire. Matched events are
 * consumed with `stopImmediatePropagation` so no rotation chord can also leak an
 * escape sequence into a shell.
 *
 * @return the listener, retained by [shellKeyHandler] for exact removal on close.
 */
private fun buildShellKeyHandler(): (Event) -> Unit = handler@{ ev ->
    val ke = ev as? KeyboardEvent ?: return@handler
    if (!shellOpen) return@handler
    if (!ke.ctrlKey || !ke.altKey || ke.metaKey) return@handler

    val shift = ke.shiftKey
    val dir = if (shift) -SHELL_ROT_STEP_DEG else SHELL_ROT_STEP_DEG
    val handled = when (ke.code) {
        "KeyX" -> { rotateShell(0, dir); true } // pitch
        "KeyY" -> { rotateShell(1, dir); true } // yaw
        "KeyZ" -> { rotateShell(2, dir); true } // roll
        "Digit0" -> { resetShellRotation(); true }
        "Space" -> { toggleShellTumble(); true }
        "Escape" -> { closeWorld3dShell(); true }
        else -> false
    }
    if (handled) {
        ke.preventDefault()
        ke.stopImmediatePropagation()
    }
}

/**
 * Build the small corner exit affordance — a bare ✕ pill, no shortcut list.
 *
 * A legend was tried here and removed: this mode's whole point is that the real
 * shell stays usable, so any persistent overlay is covering the thing you came to
 * look at. The chords live in the topbar button's tooltip and the
 * Keyboard-shortcuts sidebar instead; only the mouse escape hatch earns screen
 * space, since a user who has forgotten ⌃⌥Esc has no other way out.
 *
 * Opts back into pointer events (the overlay itself is inert) so the ✕ is
 * clickable, and is `user-select: none` so a stray drag over it never competes
 * with terminal selection underneath.
 *
 * @return the exit button, appended to the overlay by [openWorld3dShell].
 */
private fun buildShellExitButton(): HTMLElement {
    val close = document.createElement("button") as HTMLElement
    close.setAttribute("type", "button")
    close.title = "Exit full-shell 3D mode (⌃⌥Esc)"
    close.textContent = "✕"
    close.style.cssText =
        "position:absolute;right:10px;bottom:10px;z-index:5;pointer-events:auto;user-select:none;" +
            "cursor:pointer;font:11px ui-monospace,monospace;line-height:1;width:24px;height:24px;" +
            "display:flex;align-items:center;justify-content:center;padding:0;border-radius:6px;opacity:.45;" +
            "color:var(--dt-fg, #c9d1d9);background:rgba(12,14,20,.7);border:1px solid rgba(255,255,255,.16);"
    // Faded until pointed at, so it reads as an affordance rather than chrome.
    close.addEventListener("mouseenter", { _: Event -> close.style.opacity = "1" })
    close.addEventListener("mouseleave", { _: Event -> close.style.opacity = "0.45" })
    close.addEventListener("click", { _: Event -> closeWorld3dShell() })
    return close
}
