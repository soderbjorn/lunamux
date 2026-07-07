/**
 * 3D tab overview for the Termtastic web/Electron frontend — the shared core.
 *
 * A task-switcher-style full-screen overlay presenting every visible tab and its
 * panes in 3D. This file owns everything **style-independent**: the cached
 * renderer / scene / camera / overlay, capturing each pane's live content into a
 * textured plane (terminal grid, file listing, git status — every pane type),
 * selection state ([ovSelected] / [ovPaneSelected]), and the open / close /
 * render-loop / input-routing lifecycle. The *spatial arrangement and motion* is
 * pluggable via [Overview3DStyle]: [CarouselStyle] (the turning ring),
 * [RotundaStyle] (inside a cylinder, the default), [ExposeStyle] (real-layout →
 * grid zoom), [FlipStackStyle] (a riffled deck receding in depth),
 * [CorridorStyle] (a walkable gallery hall), [OrbitStyle] (worlds in space with
 * a curved fly-through), and [VertigoStyle] (a glass tower with a dolly-zoom),
 * chosen at open time from [experimental3dSwitcherStyle]. Both keyboard
 * and mouse/trackpad drive navigation (see [openOverview3d] and [ensureOverlay]).
 *
 * The carousel — described below — is the reference style; the same capture and
 * lifecycle machinery serves all three. Content is sourced directly from each
 * pane's **live** xterm.js instance in the [terminals] registry — the exact
 * buffer the visible pane renders — so no second `/pty` socket is opened and
 * the PTY is never resized (reading a buffer is size-neutral). A [ThumbSource]
 * subscribes to the live term's `onWriteParsed` and repaints on output, which
 * also drives a fading accent glow so active sessions "breathe" in the ring.
 * Sources are deduplicated per pane id in [ovSources]; one pane can feed both
 * a tab card and its pane tile.
 *
 * **Fidelity:** the **selected** (front) tab's terminal pane tiles render at
 * full fidelity — the pane's exact colored cell grid (ANSI/256/truecolor +
 * attributes + cursor) via [renderTerminalGrid] / [buildTermPalette] — while
 * the rest of the ring keeps the cheaper monochrome re-wrapped thumbnail
 * ([renderThumbnail]) for cost. [applyFidelity] promotes/demotes tiles as the
 * selection rotates.
 *
 * **Split-into-panes:** when a card settles at the front of the ring it
 * crossfades into its individual panes — one tile per pane, arranged exactly
 * per the tab's *real* layout geometry (read live from the toolkit via
 * [AppShellHandle.currentLayoutStateJson], falling back to the wire-model
 * [Pane] rectangle). The tiles ease apart, lift toward the camera, and bow
 * into a shallow inward curve (see [CURVE_K]); the tab's focused pane keeps a
 * persistent accent ring. Rotating away reassembles them into the flat card.
 *
 * Interaction model:
 *  - `←`/`→` or mouse wheel rotates the ring one card at a time.
 *  - `Enter` (or clicking the front card) activates that tab: the command is
 *    sent immediately and the camera *dives into* the card while the overlay
 *    fades, landing on the real tab.
 *  - Clicking a pane tile of the front card activates the tab AND focuses +
 *    raises that specific pane.
 *  - Clicking a non-front card rotates it to the front; clicking empty space
 *    or pressing `Esc` closes the overview.
 *
 * Theming: thumbnail colors come from the live xterm theme, the glow/halo
 * from the app accent, and the overlay backdrop gradient is derived from
 * the theme background ([applyThemedBackdrop]) so entering the 3D view
 * feels like the same app, not a different one.
 *
 * Performance: the WebGL renderer, scene, and camera are created once and
 * cached for the app's lifetime — [prewarmOverview3d] pays context creation
 * and first-shader compilation at boot, so opening the overview later is
 * just "build cards + unhide". Per-open resources (live-term subscriptions,
 * textures, tile geometry) are torn down on close. The
 * render loop traps per-frame exceptions so a single bad frame can never
 * kill the animation (a lesson learned: see [pickUserData]).
 *
 * Wired from [bootViaToolkitShell]: [registerOverview3dHotkey] binds the
 * configurable toggle chord (default ⌥⌘→) to [toggleOverview3d].
 *
 * @see LinkThumbnailRenderer
 * @see PaneTypeModal
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import se.soderbjorn.termtastic.three.CanvasTexture
import se.soderbjorn.termtastic.three.Mesh
import se.soderbjorn.termtastic.three.MeshBasicMaterial
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.PlaneGeometry
import se.soderbjorn.termtastic.three.Raycaster
import se.soderbjorn.termtastic.three.Scene
import se.soderbjorn.termtastic.three.Vector2
import se.soderbjorn.termtastic.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------- */
/* Module state. The renderer / scene / camera / overlay are app-lifetime  */
/* caches (see file kdoc); everything else is rebuilt per open.            */
/* ---------------------------------------------------------------------- */

/** App-lifetime cached WebGL renderer (created by [ensureRenderer]). */
internal var ovRenderer: WebGLRenderer? = null

/** App-lifetime cached scene. */
internal var ovScene: Scene? = null

/** App-lifetime cached camera. */
internal var ovCamera: PerspectiveCamera? = null

/** App-lifetime cached overlay element (hidden while closed). */
internal var ovOverlay: HTMLElement? = null

/** Selected-tab title readout at the bottom of the overlay. */
internal var ovTitleEl: HTMLElement? = null

/** Footer key/gesture hint element; its text is set per style each open. */
internal var ovHintEl: HTMLElement? = null

/**
 * The active switcher style for the current open, chosen in [openOverview3d]
 * from [experimental3dSwitcherStyle]; `null` while closed. Every lifecycle and
 * input hook routes through it.
 */
internal var ovActiveStyle: Overview3DStyle? = null

/**
 * Per-open card list, in config tab order. Populated by [buildCards]; the active
 * [Overview3DStyle] parents these into the scene and arranges them.
 */
internal val ovCards = mutableListOf<OverviewCard>()

/** Per-open live content sources, deduplicated by pane id. */
internal val ovSources = mutableMapOf<String, ThumbSource>()

/**
 * Resolved terminal color palette for the current open ([buildTermPalette]),
 * used by full-fidelity tiles. Rebuilt each open (the theme is stable during a
 * session) and cleared on close.
 */
internal var ovPalette: TermPalette? = null

/** Shared card plane geometry (per open). */
internal var ovCardGeometry: PlaneGeometry? = null

/** Shared glow plane geometry (per open). */
internal var ovGlowGeometry: PlaneGeometry? = null

/**
 * Index (into [ovCards]) of the card currently selected — the front card on the
 * carousel ring, the current floor in the rotunda, the tab in front in exposé.
 * Shared across styles so [applyFidelity] and the readout stay style-agnostic.
 */
internal var ovSelected = 0

/**
 * Index (into the front card's [OverviewCard.tiles]) of the currently
 * highlighted pane, or `-1` for "the whole tab, no specific pane". Driven by
 * ↑/↓/Tab and by hovering a pane tile; reset to `-1` whenever the front tab
 * changes. `Enter` jumps to this pane (or the whole tab when `-1`).
 */
internal var ovPaneSelected = -1

/** rAF handle of the render loop while open. */
internal var ovAnimHandle: Int? = null

/** True between [openOverview3d] and the end of [closeOverview3d]. */
internal var ovOpen = false

/**
 * `performance.now()` when the dive-in activation started; NaN = not diving.
 * Styles read this to freeze input and animate a landing camera during the
 * transition; the core closes the overview once [DIVE_MS] elapses.
 */
internal var ovDiveStart = Double.NaN

/** Capture-phase key handler while open (removed on close). */
internal var ovKeyHandler: ((Event) -> Unit)? = null

/** Window resize handler while open (removed on close). */
internal var ovResizeHandler: ((Event) -> Unit)? = null

/** Last pointer position in normalized device coordinates (for hover/picking). */
internal val ovPointerNdc = Vector2(0.0, -2.0)

/** Shared raycaster for click/hover picking (see [raycastPointer]). */
internal val ovRaycaster = Raycaster()

/**
 * Set on every pointer move, consumed once per frame. Gates hover-driven pane
 * selection on actual pointer movement, so a mouse resting over one tile
 * doesn't keep yanking the highlight back while you navigate with ↑/↓.
 */
internal var ovPointerMoved = false

/** True while a pointer button is held over the overlay (drag in progress). */
internal var ovPointerDown = false

/** Last pointer client X/Y, for computing per-move drag deltas. */
internal var ovPointerLastX = 0.0
internal var ovPointerLastY = 0.0

/**
 * Accumulated `|dx|+|dy|` (px) since pointer-down. A release under
 * [OV_CLICK_MOVE_THRESHOLD] is treated as a click (select/activate); past it the
 * gesture was a drag (look-around / orbit) and no click fires — so styles that
 * both drag *and* click never mistake one for the other.
 */
internal var ovPointerMovedPx = 0.0

/** Pointer-travel (px) under which a release counts as a click, not a drag. */
internal const val OV_CLICK_MOVE_THRESHOLD = 7.0

/**
 * Toggles the 3D overview: opens it if closed, closes it if open. The
 * target of the configurable ⌥⌘→ hotkey ([registerOverview3dHotkey] in
 * [TermtasticToolkitBootstrap]).
 *
 * This is the single gate for the experimental **3D app switcher**: every
 * entry point (hotkey, topbar app-switcher button, Electron menu) routes here, so
 * when [isExperimental3dSwitcherEnabled] is off the feature is fully inert
 * regardless of a stale binding or hidden button. The toolbar button's
 * *visibility* is handled separately by [applyOverview3dChromeVisibility].
 *
 * @see openOverview3d
 * @see closeOverview3d
 * @see isExperimental3dSwitcherEnabled
 */
internal fun toggleOverview3d() {
    if (!isExperimental3dSwitcherEnabled()) return
    if (ovOpen) closeOverview3d() else openOverview3d()
}

/**
 * Pre-warms the app-lifetime 3D machinery at boot: creates the WebGL
 * renderer, scene, and camera, and renders one empty frame so context
 * creation and the first shader compile happen *now* (hidden, at startup)
 * instead of on the first hotkey press. Chromium additionally caches
 * compiled shaders on disk, so even this cost shrinks across app launches.
 *
 * Called once from [bootViaToolkitShell] (deferred with a timeout so it
 * never competes with first paint). Idempotent.
 */
internal fun prewarmOverview3d() {
    runCatching {
        val r = ensureRenderer()
        r.setSize(2, 2, false)
        r.render(ovScene!!, ovCamera!!)
    }
}

/**
 * Creates (or returns) the cached renderer/scene/camera triple.
 *
 * @return the app-lifetime [WebGLRenderer]; [ovScene] and [ovCamera] are
 *   non-null after this returns.
 */
internal fun ensureRenderer(): WebGLRenderer {
    ovRenderer?.let { return it }
    val renderer = WebGLRenderer(json("antialias" to true, "alpha" to true))
    renderer.setPixelRatio(window.devicePixelRatio)
    ovRenderer = renderer
    ovScene = Scene()
    ovCamera = PerspectiveCamera(OVERVIEW_BASE_FOV, 1.0, 0.1, 100.0)
    return renderer
}

/**
 * Builds (or returns) the cached full-screen overlay: a fixed-position
 * backdrop hosting the renderer's canvas, the selected-tab title readout,
 * and the key-hint footer. Hidden via `display: none` while closed so
 * reopening never re-creates DOM.
 *
 * @return the overlay element, appended to `document.body`.
 */
internal fun ensureOverlay(): HTMLElement {
    ovOverlay?.let { return it }
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = OVERLAY_ID
    overlay.tabIndex = -1

    overlay.appendChild(ensureRenderer().domElement)

    val title = document.createElement("div") as HTMLElement
    title.className = "overview3d-title"
    overlay.appendChild(title)
    ovTitleEl = title

    val hint = document.createElement("div") as HTMLElement
    hint.className = "overview3d-hint"
    // Text is set per active style in openOverview3d (each style's controls
    // differ); seed with the carousel default so it's never blank pre-open.
    hint.textContent = "← → tabs · ↑ ↓ panes · ⏎ open · click a pane to jump · esc close"
    overlay.appendChild(hint)
    ovHintEl = hint

    // Wheel / trackpad: forward the dominant-axis delta to the active style,
    // which decides what it means — the carousel latches it into single tab
    // steps, the rotunda rides between floors, the exposé zooms continuously.
    // passive=false so the page never scrolls and macOS back/forward-swipe
    // navigation is suppressed.
    overlay.addEventListener("wheel", { ev: Event ->
        ev.preventDefault()
        if (!ovDiveStart.isNaN()) return@addEventListener
        val we = ev as WheelEvent
        val delta = if (abs(we.deltaX) > abs(we.deltaY)) we.deltaX else we.deltaY
        ovActiveStyle?.wheel(delta, window.performance.now())
    }, json("passive" to false))

    // Pointer move: always update the hover NDC (the render loop highlights the
    // pane under the cursor). While a button is held it's also a drag — feed the
    // per-move delta to the style's drag() (look-around / orbit) and tally the
    // travel so the release can tell a click from a drag.
    overlay.addEventListener("pointermove", { ev: Event ->
        val me = ev as MouseEvent
        ovPointerNdc.x = me.clientX / window.innerWidth.toDouble() * 2.0 - 1.0
        ovPointerNdc.y = -(me.clientY / window.innerHeight.toDouble() * 2.0 - 1.0)
        ovPointerMoved = true
        if (ovPointerDown && ovDiveStart.isNaN()) {
            val dx = me.clientX - ovPointerLastX
            val dy = me.clientY - ovPointerLastY
            ovPointerLastX = me.clientX.toDouble()
            ovPointerLastY = me.clientY.toDouble()
            ovPointerMovedPx += abs(dx) + abs(dy)
            ovActiveStyle?.drag(dx, dy)
        }
    })

    // Pointer down: arm a gesture and capture the pointer so drags that leave
    // the overlay keep flowing here.
    overlay.addEventListener("pointerdown", { ev: Event ->
        if (!ovDiveStart.isNaN()) return@addEventListener
        val me = ev as MouseEvent
        ovPointerDown = true
        ovPointerMovedPx = 0.0
        ovPointerLastX = me.clientX.toDouble()
        ovPointerLastY = me.clientY.toDouble()
        runCatching { overlay.asDynamic().setPointerCapture(me.asDynamic().pointerId) }
    })

    // Pointer up: a release that barely moved is a click — raycast the style's
    // pickables and hand the hit (or `null` for empty space) to its click(); a
    // release that dragged past the threshold was a look-around, so no click
    // fires (the style already consumed the motion via drag()).
    overlay.addEventListener("pointerup", { ev: Event ->
        if (!ovPointerDown) return@addEventListener
        ovPointerDown = false
        if (!ovDiveStart.isNaN()) return@addEventListener
        val me = ev as MouseEvent
        ovPointerNdc.x = me.clientX / window.innerWidth.toDouble() * 2.0 - 1.0
        ovPointerNdc.y = -(me.clientY / window.innerHeight.toDouble() * 2.0 - 1.0)
        if (ovPointerMovedPx < OV_CLICK_MOVE_THRESHOLD) {
            val ud = raycastPointer(ovActiveStyle?.pickables() ?: emptyArray<dynamic>())
            ovActiveStyle?.click(ud)
        }
    })

    document.body?.appendChild(overlay)
    ovOverlay = overlay
    return overlay
}

/**
 * Opens the overview: builds one card (plus pane tiles) per visible tab from
 * the current [latestWindowConfig], lays them out on the ring, attaches each
 * pane's live-term source, promotes the front tab to full fidelity, wires the
 * key handlers, and starts the render loop. No-op when already open or before
 * the first config arrives.
 *
 * @see closeOverview3d
 */
internal fun openOverview3d() {
    if (ovOpen) return
    val cfg = latestWindowConfig ?: return
    val tabs = cfg.tabs.filter { !it.isHidden }

    ovOpen = true
    ovDiveStart = Double.NaN
    ovPointerDown = false
    ovPointerMovedPx = 0.0
    val renderer = ensureRenderer()
    val scene = ovScene!!
    val camera = ovCamera!!
    val overlay = ensureOverlay()
    overlay.classList.remove("overview3d-closing")
    overlay.style.display = ""

    // Theme-integrated colors, all from the live theme so the 3D view reads
    // as the same app rather than a separate blue-tinted world:
    //  - thumbnails use the xterm foreground/background (as the link picker),
    //  - the glow, title-bar underline, and backdrop tint use the theme
    //    *accent* — `buildXtermTheme().cursor` is `ResolvedTheme.accent`, so a
    //    yellow theme yields a yellow glow, a green theme a green glow, etc.
    //    (The `--accent` CSS var is a fixed UI accent that does NOT track the
    //    active theme — reading it was the source of the stray blue.)
    val theme = buildXtermTheme()
    val fg = (theme.foreground as? String) ?: "#d4d4d4"
    val bg = (theme.background as? String) ?: "#1e1e1e"
    val accent = (theme.cursor as? String)?.takeIf { it.isNotBlank() } ?: fg
    applyThemedBackdrop(overlay, bg, accent)

    // Resolved 256-color palette for full-fidelity terminal tiles (front tab).
    ovPalette = buildTermPalette()

    // Shared plane geometries consumed by buildCardContent (the tab card + its
    // glow halo). Pane tiles get their own per-pane geometry in buildPaneTile.
    ovCardGeometry = PlaneGeometry(CARD_W, CARD_H)
    ovGlowGeometry = PlaneGeometry(CARD_W + 0.24, CARD_H + 0.24)

    // Build style-independent content for every visible tab (thumbnails, live
    // sources, pane tiles for all pane types), then seed the selection on the
    // active tab and its focused pane so the first ↑/↓/Tab steps to a neighbour.
    buildCards(tabs, fg, bg, accent)
    ovSelected = tabs.indexOfFirst { it.id == cfg.activeTabId }.takeIf { it >= 0 } ?: 0
    ovPaneSelected = focusedTileIndex(ovSelected)

    // Hand off to the chosen style: it arranges the cards/tiles in the scene
    // and poses the camera. Everything above is shared across all styles. Reset
    // the lens + roll first so a prior style that animated them (the vertigo
    // dolly-zoom's fov, the orbit bank's up vector) can't leak into this open.
    camera.fov = OVERVIEW_BASE_FOV
    camera.up.set(0.0, 1.0, 0.0)
    camera.updateProjectionMatrix()
    val style = styleFor(experimental3dSwitcherStyle())
    ovActiveStyle = style
    style.build(scene, camera)
    style.resetCamera(camera)
    ovHintEl?.textContent = style.hint()

    updateTitleReadout()
    applyFidelity()

    layoutRenderer()
    val resizeHandler: (Event) -> Unit = { layoutRenderer() }
    ovResizeHandler = resizeHandler
    window.addEventListener("resize", resizeHandler)

    // Capture-phase keys: the overlay steals focus from the terminal on open,
    // but capture also guarantees Escape/arrows never leak to other handlers
    // underneath while the overview is up. Directional keys route to the active
    // style (which maps them to its own geometry); Enter/Escape are uniform.
    val keyHandler: (Event) -> Unit = handler@{ ev ->
        if (!ovOpen || !ovDiveStart.isNaN()) return@handler
        val ke = ev as KeyboardEvent
        val st = ovActiveStyle
        when (ke.key) {
            "Escape" -> { ke.preventDefault(); ke.stopPropagation(); closeOverview3d() }
            "ArrowLeft" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.LEFT) }
            "ArrowRight" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.RIGHT) }
            "ArrowDown" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.DOWN) }
            "ArrowUp" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(OvNav.UP) }
            "Tab" -> { ke.preventDefault(); ke.stopPropagation(); st?.nav(if (ke.shiftKey) OvNav.PANE_PREV else OvNav.PANE_NEXT) }
            "Enter" -> { ke.preventDefault(); ke.stopPropagation(); beginDive(selectedPaneId()) }
        }
    }
    ovKeyHandler = keyHandler
    window.addEventListener("keydown", keyHandler, true)
    overlay.focus()

    // Fetch + paint file-browser / git tiles (terminals stream on their own).
    primeDataTiles()

    startRenderLoop(renderer, scene, camera)
}

/**
 * Starts the per-frame animation loop. Each frame delegates all layout/motion
 * to the active [Overview3DStyle.tick] (ring easing + split for the carousel,
 * cylinder spin for the rotunda, zoom for the exposé), then the core handles
 * the uniform parts: completing a dive-in ([ovDiveStart]) after [DIVE_MS] by
 * closing the overview, and rendering.
 *
 * Each frame's body runs inside a try/catch: a per-frame error is logged and
 * the loop keeps running — a single bad frame must never freeze the overview
 * (which is exactly what the [raycastPointer] TypeError used to do).
 *
 * @param renderer the cached renderer.
 * @param scene the cached scene.
 * @param camera the cached camera.
 */
internal fun startRenderLoop(renderer: WebGLRenderer, scene: Scene, camera: PerspectiveCamera) {
    fun tick(now: Double) {
        ovActiveStyle?.tick(now, camera)

        // Dive-in completion is uniform across styles: once the fade window has
        // elapsed, land on the real tab (the command already dispatched in
        // [beginDive]). Styles may animate a camera during the same window.
        if (!ovDiveStart.isNaN() && (now - ovDiveStart) >= DIVE_MS) {
            closeOverview3d()
            return
        }

        renderer.render(scene, camera)
    }

    fun frame() {
        if (!ovOpen) return
        try {
            tick(window.performance.now())
        } catch (t: Throwable) {
            console.error("[overview3d] frame error", t)
        }
        if (ovOpen) ovAnimHandle = window.requestAnimationFrame { frame() }
    }
    ovAnimHandle = window.requestAnimationFrame { frame() }
}

/**
 * Closes the overview and releases every per-open resource: stops the
 * render loop, detaches key/resize handlers, disposes the content sources
 * (unsubscribing from the live terms — which are left running — and freeing
 * only the demo hidden terms), the cards' and tiles' GPU resources, and hides
 * the overlay. The renderer/scene/camera/overlay caches stay warm so the next
 * open is instant.
 *
 * @see openOverview3d
 */
internal fun closeOverview3d() {
    if (!ovOpen) return
    ovOpen = false
    ovDiveStart = Double.NaN

    ovAnimHandle?.let { window.cancelAnimationFrame(it) }
    ovAnimHandle = null
    ovKeyHandler?.let { window.removeEventListener("keydown", it, true) }
    ovKeyHandler = null
    ovResizeHandler?.let { window.removeEventListener("resize", it) }
    ovResizeHandler = null
    onPaneContentUpdated = null

    // Let the style remove its own scene objects (groups, decorations) before
    // the shared card / tile GPU resources are disposed below.
    ovScene?.let { scene -> ovActiveStyle?.teardown(scene) }
    ovActiveStyle = null

    for (source in ovSources.values) source.dispose()
    ovSources.clear()
    ovPalette = null
    for (card in ovCards) card.dispose()
    ovCards.clear()
    ovCardGeometry?.dispose()
    ovCardGeometry = null
    ovGlowGeometry?.dispose()
    ovGlowGeometry = null

    ovOverlay?.let {
        it.classList.remove("overview3d-closing")
        it.style.display = "none"
    }

    // The overlay grabbed keyboard focus while open (see [openOverview3d]'s
    // `overlay.focus()`), and the commands a dive sends (SetActiveTab /
    // SetFocusedPane) frequently produce no config change — so nothing else
    // would hand focus back to the selected pane. Restore it explicitly, now
    // that the overlay is hidden, so the picked pane's cursor is focused
    // rather than merely active.
    focusActivePaneNow()
}

