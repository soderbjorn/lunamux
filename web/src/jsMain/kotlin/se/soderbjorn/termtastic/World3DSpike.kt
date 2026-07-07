/**
 * Phase-2 "world mode" spike, v4 — a **rotunda of the real termtastic panes**,
 * navigated in two axes like the 3D app switcher, with the pane you engage fully
 * **interactive**.
 *
 * It takes the **actual live terminals** and *reparents* each one onto its own
 * CSS3D plane. Because they are real xterm.js instances (still wired to their
 * PTYs), every pane keeps streaming live — and once you engage the front pane it
 * is a genuine, typeable terminal (real shell editing + pixel-accurate mouse
 * selection), because the front plane is **billboarded flat to the camera at a
 * 1:1 pixel mapping** and CSS3D never touches the terminal's data path.
 *
 * **Modal, modifier-free navigation (v4):** the spike has two modes, derived
 * purely from whether a pane's terminal currently holds focus, so no per-mode
 * flag can drift:
 *  - **Navigate** (nothing focused — the state on open): bare **←/→** cycle the
 *    panes *within* the current tab (a horizontal fan), bare **↑/↓** cycle *tabs*
 *    (vertical "floors" sliding in from above / below), **+/−** zoom, **s**
 *    toggles selection mode, **Esc** closes. No modifiers needed.
 *  - **Engaged** (a pane is focused): every keystroke goes straight to the shell
 *    (bare Esc included); only **⌥⌘X** is intercepted, to disengage (blur) back to
 *    navigate mode.
 * Navigate-mode shortcuts `stopImmediatePropagation` so they never leak an escape
 * sequence into a terminal.
 *
 * **Engage on Enter (v4):** navigation never steals focus or changes the app.
 * Press **Enter** on the front pane to *engage* it — a plain terminal `focus()`,
 * so you can type straight away. Crucially it dispatches **no** app command
 * mid-session: doing so would make the app's `refocusActivePane` fight every ⌘Esc
 * for focus, so all app activation ([WindowCommand.SetActiveTab] /
 * [WindowCommand.SetFocusedPane] / [WindowCommand.RaisePane]) is **deferred to
 * close**, landing you on the last pane you engaged. A **mirror preview** is the
 * exception: engaging it activates its tab so the real terminal mounts, and
 * [tryPromoteMirror] hot-swaps the live term onto the plane. Any navigation (keys
 * or the on-screen arrows) first disengages, so you never navigate while a hidden
 * pane is focused.
 *
 * **All tabs / mirrors:** the ring shows *every* visible tab's terminal panes.
 * Mounted panes reparent their real xterm; unmounted tabs get a hidden **mirror**
 * term fed by a read-only preview `/pty` socket (or the demo preview) — a live,
 * streaming, passive preview until engaged. Mirror terms/sockets are torn down on
 * close; real panes are restored to the 2D layout untouched.
 *
 * **Titlebars:** each plane carries a title strip (pane title + tab name, accent
 * underline) styled after the switcher's tiles.
 *
 * **Uniform screens:** [SPIKE_UNIFORM_SCREENS] normalizes every pane to one
 * screen-like size and **reformats** (safeFit) it so its content reflows to fill
 * that box — the ring reads as a wall of matching monitors.
 *
 * @see three.CSS3DRenderer
 * @see toggleWorld3dSpike
 */
package se.soderbjorn.termtastic

import kotlin.js.json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Element
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.ImageData
import org.w3c.dom.Node
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.termtastic.three.CSS3DObject
import se.soderbjorn.termtastic.three.CSS3DRenderer
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.Scene

/**
 * Toggles the panes-rotunda spike. Wired to the topbar cube button and the
 * ⌥⌘← hotkey.
 *
 * Gated behind the experimental "3D world" flag: when
 * [isExperimentalWorld3dEnabled] is off the feature is fully inert — the hotkey
 * chord and any stale menu entry no-op here, matching the hidden topbar button
 * (whose *visibility* is handled separately by [applyWorld3dSpikeChromeVisibility]).
 *
 * @see isExperimentalWorld3dEnabled
 * @see buildWorld3dSpikeTopbarAction
 */
fun toggleWorld3dSpike() {
    if (!isExperimentalWorld3dEnabled()) return
    if (spikeOpen) closeWorld3dSpike() else openWorld3dSpike()
}

/**
 * Opens the spike: reparents every tab's terminal pane onto a CSS3D ring plane
 * (mounted → the real term, unmounted → a live read-only mirror), poses the
 * camera so the front pane is 1:1, seeds the selection on the active tab's
 * focused pane, and starts the render loop.
 */
internal fun openWorld3dSpike() {
    if (spikeOpen) return
    spikeOpen = true

    val w = window.innerWidth
    val h = window.innerHeight
    spikeScreenW = (w * 0.46).roundToInt().coerceIn(560, 1200)
    spikeScreenH = (spikeScreenW * h.toDouble() / w.toDouble()).roundToInt().coerceIn(360, 900)

    val overlay = document.createElement("div") as HTMLElement
    overlay.tabIndex = -1 // focusable so navigate-mode keys have a home after ⌘Esc
    overlay.style.cssText = "position:fixed;inset:0;z-index:99999;overflow:hidden;outline:none;" +
        "background:radial-gradient(circle at 50% 42%,#141b27,#04060a);"
    spikeOverlay = overlay

    // Hidden defs host for the per-pane latch-bulge displacement filters. Kept in the
    // overlay (so `url(#…)` references resolve) but zero-sized and out of the flow.
    spikeBulgeSeq = 0
    spikeBulgeMapUri = null
    // NB: an `<svg>` is an SVGSVGElement, not an HTMLElement — don't cast (it throws
    // ClassCastException in Kotlin/JS). Keep it as Element and style via attribute.
    val filterDefs = document.createElementNS(SVG_NS, "svg")
    filterDefs.setAttribute("width", "0")
    filterDefs.setAttribute("height", "0")
    filterDefs.setAttribute("style", "position:absolute;width:0;height:0;overflow:hidden;pointer-events:none;")
    overlay.appendChild(filterDefs)
    spikeFilterDefs = filterDefs

    val camera = PerspectiveCamera(SPIKE_FOV, w.toDouble() / h.toDouble(), 1.0, 12000.0)
    camera.position.set(0.0, 0.0, RING_R + perspDistance(h))
    camera.lookAt(0.0, 0.0, 0.0)
    spikeCamera = camera

    val css = CSS3DRenderer()
    css.setSize(w, h)
    css.domElement.style.cssText = "position:absolute;inset:0;z-index:1;pointer-events:none;"
    overlay.appendChild(css.domElement)
    spikeCss3d = css

    val scene = Scene()
    spikeCssScene = scene

    val chrome = spikeChrome()
    spikeChromeColors = chrome
    // The free-fly landmark above the home camera spot — built unconditionally (it
    // is useful even in an all-empty world) and out of frame at the resting pose.
    buildHomeBeacon(scene, chrome)
    // Its sibling: the storage-crystal landmark crowning the stash shelf, so the
    // shelf reads as a *place* from anywhere in the sky (even while still empty).
    buildStashBeacon(scene, chrome)
    // Dress the sky: planets, nebulae and star clusters — built before the panes so
    // they paint beneath every screen, with several staged along the stash corridor
    // so the shelf flight sails past them. @see buildCosmos
    buildCosmos(scene)
    val specs = collectPaneSpecs()
    val tabs = collectTabs()
    val n = specs.size
    val tabCount = tabs.size.coerceAtLeast(1)
    // Tabs that have no panes get a placeholder card at their latitude (id, title, ord).
    val emptyTabs = tabs.mapIndexedNotNull { ord, (id, title) ->
        if (id.isNotEmpty() && specs.none { it.tabId == id }) Triple(id, title, ord) else null
    }

    spikePanes = mutableListOf()
    spikeEmptyTabs = mutableListOf()
    spikePendingFocusTab = null
    spikePendingFocusNewTab = false
    spikeTabIndex = 0
    spikeTabScroll = 0.0
    spikePaneScroll = 0.0
    spikeTabSel = MutableList(tabCount.coerceAtLeast(1)) { 0 }
    spikeSettledIndex = -1
    spikeEngaged = false
    spikeLastEngagedTab = null
    spikeLastEngagedPane = null
    spikeZoomTarget = 1.0
    // Restore persisted per-pane zooms (Pane.zoom) on a fresh app run — local
    // session memory always wins over the server copy (see seedZoomFromConfig).
    latestWindowConfig?.let { seedZoomFromConfig(it) }
    spikeSelectionMode = false
    // Seed the shelf from the panes minimized (docked) in the 2D layout: stash and
    // minimize are the same persisted state now (see persistPaneMinimized), so a pane
    // docked in 2D — or stashed in a previous world session — starts up on the shelf
    // instead of the ring. Restricted to the panes this open is about to build so a
    // stale LAYOUT_STATE entry can't occupy a shelf slot. Pre-stashed panes are not
    // stranded: the shelf is reachable without stashing via toggleStashView.
    spikeStashed.clear()
    val shownIds = specs.mapTo(mutableSetOf()) { it.paneId }
    minimizedPaneIds().filterTo(spikeStashed) { it in shownIds }
    spikeBobEnabled = true
    spikeFlyMode = false
    spikeTilted = false
    spikeFlyKeys.clear()
    spikeCamFlown = false
    spikeFlyReveal = 0.0
    spikeCamReturning = false
    spikeCamX = 0.0
    spikeCamY = 0.0
    spikeCamZ = 0.0
    resetFlyBasis()
    clearFlyVelocity()
    spikeNeedsInitialLayout = false

    if (n == 0 && emptyTabs.isEmpty()) {
        val notice = document.createElement("div") as HTMLElement
        notice.textContent = "No terminal windows to show. Open a terminal first."
        notice.style.cssText = "position:absolute;inset:0;display:flex;align-items:center;" +
            "justify-content:center;color:#8ea3c2;font:15px ui-monospace,Menlo,monospace;"
        overlay.appendChild(notice)
    } else {
        specs.forEach { buildRingPane(it, n, scene, chrome) }
        emptyTabs.forEach { (id, title, ord) -> buildEmptyTabCard(id, title, ord, scene, chrome) }
        buildRingChrome(overlay)
        // Land on the active tab's focused pane so you start where you left off; if
        // the world is all empty tabs, land on the first tab's placeholder card.
        // Panes seeded onto the shelf are skipped — the fronted slot must never be
        // a shelved pane (same invariant stashFront maintains) — with a last-resort
        // fallback to a stashed pane when *everything* is stashed.
        val cfg = latestWindowConfig
        val focusId = cfg?.tabs?.firstOrNull { it.id == cfg.activeTabId }?.focusedPaneId
        val start = spikePanes.firstOrNull { it.paneId == focusId && !isStashed(it) }
            ?: spikePanes.firstOrNull { it.interactive && !isStashed(it) }
            ?: spikePanes.firstOrNull { !isStashed(it) }
            ?: spikePanes.firstOrNull()
        if (start != null) {
            spikeTabIndex = start.tabOrd
            spikeTabSel[start.tabOrd] = start.paneOrdInTab
            spikeTabScroll = start.tabOrd.toDouble()
            spikePaneScroll = start.paneOrdInTab.toDouble()
        } else {
            spikeTabIndex = emptyTabs.first().third
            spikeTabScroll = spikeTabIndex.toDouble()
        }
        loadFrontZoom() // restore the starting pane's remembered zoom
        showNavLabel() // flash where we landed
        spikeNeedsInitialLayout = true
    }

    document.body?.appendChild(overlay)

    // Live-reconcile the ring whenever the window config changes (create/close of
    // panes or tabs — by this spike's own keys or the 2D app) so the change animates
    // in/out instead of only showing on reopen.
    spikeConfigJob = GlobalScope.launch {
        termtasticClient.windowState.config.collect { cfg ->
            if (cfg != null && spikeOpen) {
                latestWindowConfig = cfg
                seedZoomFromConfig(cfg) // a pane created while open may carry a persisted zoom
                reconcileRing()
            }
        }
    }

    // Live-mirror the 2D dock while the world is open: minimize/restore from any
    // client updates the toolkit LAYOUT_STATE blob, which the server broadcasts —
    // reconcile the shelf so a pane docked in 2D flies up and a restored one sails
    // home. Echoes of our own stash writes are no-ops (the sets already match).
    spikeLayoutJob = GlobalScope.launch {
        termtasticClient.windowState.rawLayoutState.collect {
            if (spikeOpen) syncStashFromMinimized()
        }
    }

    val onResize: (Event) -> Unit = {
        val nw = window.innerWidth
        val nh = window.innerHeight
        css.setSize(nw, nh)
        camera.aspect = nw.toDouble() / nh.toDouble()
        camera.updateProjectionMatrix() // position/lookAt are set every frame in the loop
    }
    spikeResize = onResize
    window.addEventListener("resize", onResize)

    // Match the ⇧ zoom presets to the live keyboard layout (where `+`/`-`/`0`
    // physically sit) before the first keystroke can arrive.
    resolveZoomPresetCodes()
    spikeKeys = buildKeyHandler()
    window.addEventListener("keydown", spikeKeys!!, true)

    // Release tracking for the free-fly held keys.
    val onKeyUp: (Event) -> Unit = { ev ->
        val code = (ev as KeyboardEvent).code
        if (spikeFlyKeys.remove(code)) ev.preventDefault()
    }
    spikeKeysUp = onKeyUp
    window.addEventListener("keyup", onKeyUp, true)

    startSpikeLoop()
}

/**
 * Re-resolves [spikeZoomPlusCodes]/[spikeZoomMinusCodes]/[spikeZoomZeroCodes] from
 * the user's **live keyboard layout** via `navigator.keyboard.getLayoutMap()`, so the
 * ⇧ zoom presets in [buildKeyHandler] match the keys actually labelled `+`/`-`/`0`
 * rather than their US-layout physical positions. On a Swedish layout, for example,
 * `+` sits on code `Minus` and `-` on code `Slash` — with the US defaults, ⇧+ would
 * hit the zoom-*floor* preset (pane shrinks to minimum) instead of zoom-to-fit.
 *
 * A key whose **unshifted** character is `=` also counts as a plus key: on every
 * layout that has one (US, UK, FR, …) its shifted character is `+`, which is exactly
 * the chord the preset wants. Called from [openWorld3dSpike] each time the world
 * opens (the layout can change between opens); resolution is async but near-instant,
 * and the US defaults stay in place until it lands — and permanently in engines
 * without the Keyboard Layout Map API (the packaged app's Chromium has it).
 *
 * @see buildKeyHandler @see spikeZoomPlusCodes
 */
internal fun resolveZoomPresetCodes() {
    runCatching {
        val keyboard = window.navigator.asDynamic().keyboard ?: return
        keyboard.getLayoutMap().then { map: dynamic ->
            val plus = mutableSetOf("NumpadAdd")
            val minus = mutableSetOf("NumpadSubtract")
            val zero = mutableSetOf("Numpad0")
            map.forEach { value: String, code: String ->
                when (value) {
                    "+", "=" -> plus.add(code)
                    "-" -> minus.add(code)
                    "0" -> zero.add(code)
                }
            }
            // A layout map that surfaced no matching key falls back to the US
            // position rather than leaving the preset unreachable.
            if (plus.size == 1) plus.add("Equal")
            if (minus.size == 1) minus.add("Minus")
            if (zero.size == 1) zero.add("Digit0")
            spikeZoomPlusCodes = plus
            spikeZoomMinusCodes = minus
            spikeZoomZeroCodes = zero
        }
    }
}

/**
 * The capture-phase key handler, modal on [spikeEngaged]:
 *  - **Engaged**: pass every key to the focused terminal — including a **bare Esc**
 *    (so vim/less get their Escape). Only **⌥⌘X** is intercepted, to disengage back
 *    to navigate mode — a three-key chord no shell binds, matched on physical `code`.
 *  - **Navigate**: arrows panes/tabs, **⇧+arrows** reorder the fronted pane (⇧←/⇧→)
 *    or tab (⇧↑/⇧↓) one slot, Enter engages, `t` new tab, `n` new pane, **⌥X**
 *    remove pane / empty tab (a first ⌥X arms it, a second ⌥X confirms; any other
 *    key cancels — the ⌥ modifier keeps a stray keystroke from destroying a pane),
 *    `+`/`−` zoom, `0` reset zoom,
 *    `,`/`.` grid width, `<`/`>` grid height (animated cell steps), ⌥⌘S selection, `w` cycle
 *    signal (off/working/needs-input), `g` working style (dots↔green pulse), `b` toggle bob,
 *    **Space** stash the front pane / unstash the nearest (a camera-proximity toggle), `v`
 *    fly up to the stash shelf and back with no stash — and while *up at the shelf*,
 *    ←/→ browse along the row of shelved panes ([shelfBrowse]) so Space unstashes the
 *    one you're parked at, `F` free-fly
 *    camera, `j` slight camera tilt (toggle — angles the front pane),
 *    `c` fly camera home, `k` hide/show
 *    the shortcut legends (one shared flag for navigate *and* fly mode), Esc closes.
 *    Every handled key also flashes its row in the open legend
 *    ([flashShortcut]) as visual acknowledgement. Every non-modifier key is consumed
 *    (`stopImmediatePropagation`) — even ones we ignore — so nothing leaks into a
 *    terminal the app may hold focused underneath. `⌘`/`Ctrl` combos pass through so
 *    system/browser shortcuts work.
 *  - **Free-fly** ([spikeFlyMode], entered with `F`): `W`/`S` throttle the engine
 *    fwd/reverse along the nose, `A`/`D` strafe left/right, `Space`/`Shift` strafe
 *    up/down, `↑`/`↓` pitch, `←`/`→` yaw, `Q`/`E` roll; `B`/`N`/`O`/`U` play a slow
 *    cinematic fly-by of the nearest pane — behind it, to its flank at a three-quarter
 *    angle, over it, under it ([flyBehindPane]/[flyBesidePane]/[flyAbovePane]/
 *    [flyBelowPane]; any movement key cancels); `F` lands back to Navigate, `Esc`
 *    closes the whole overlay. Every other key is swallowed (nothing leaks).
 *
 * One gate runs **before** all three modes: while the guided tour plays, *real*
 * (`isTrusted`) keys are locked out — Esc (or ⌥⌘M) stops the tour but never the
 * world; only the ✕ button (or Esc once the tour has stopped) closes the world —
 * and everything else is swallowed so a stray press can't disturb the
 * choreography. The tour's own synthetic presses ([moviePress], `isTrusted`
 * false) fall through and drive the modes as usual. When no tour is playing,
 * the demo-only **⌥⌘M** chord starts one ([toggleDemoMovie]).
 *
 * @return the listener to attach on `window` (capture phase).
 */
internal fun buildKeyHandler(): (Event) -> Unit = handler@{ ev ->
    val ke = ev as KeyboardEvent
    val consume = { ke.preventDefault(); ke.stopPropagation(); ke.stopImmediatePropagation() }

    // Tour lockout: while the demo movie plays, every *real* key is swallowed so
    // stray input can't disturb the choreography — except Esc (or the ⌥⌘M toggle),
    // which stops the tour mid-beat wherever it currently is, deliberately WITHOUT
    // closing the 3D world (that takes the ✕ button, or Esc after the tour stops).
    // The tour's own synthetic presses (moviePress, isTrusted == false) skip this
    // gate and drive the modal branches below like human input.
    if (spikeMovieJob != null && ke.isTrusted) {
        consume()
        if (ke.key == "Escape" || (ke.altKey && ke.metaKey && ke.code == "KeyM")) {
            spikeMovieJob?.cancel()
        }
        return@handler
    }

    // Secret demo-only chord **⌥⌘M**: start the guided world tour (stopping one is
    // handled by the tour lockout above). Checked in every mode (before the modal
    // branches below). Deliberately absent from the legend, and inert outside demo
    // mode — without the simulated demo sessions there is nothing for the tour to
    // drive. @see toggleDemoMovie
    if (isDemoClient && ke.altKey && ke.metaKey && ke.code == "KeyM") {
        consume(); toggleDemoMovie(); return@handler
    }

    if (spikeEngaged) {
        // Disengage with **⌥⌘X** — a three-key chord no shell or TUI binds, so every
        // other keystroke (bare Esc included) reaches the terminal untouched. Matched
        // on the physical `code` because ⌥ rewrites `ke.key` ("x" → "≈").
        if (ke.altKey && ke.metaKey && ke.code == "KeyX") {
            consume(); flashShortcut("disengage"); disengage()
        }
        return@handler
    }

    // **Free-fly** mode: keys drive the camera, not pane navigation. `F` (or Esc)
    // fixates and returns to navigate mode; movement/rotation keys are tracked as
    // *held* (released on keyup) so the render loop can glide the camera smoothly.
    if (spikeFlyMode) {
        // Fly mode is fully modal: **every** key is consumed so nothing leaks to the
        // focused terminal (or any other handler). `F` lands back to Navigate, `Esc`
        // closes the whole 3D overlay, `B`/`N` play a cinematic fly-by of the nearest
        // pane (behind / beside), `k` hides/shows the fly shortcuts legend (same
        // flag as navigate mode), and the held movement/rotation keys drive the
        // camera. All other keys are swallowed.
        consume()
        when {
            ke.code == "KeyF" -> { flashShortcut("fly-land"); toggleFlyMode() }
            ke.key == "Escape" -> closeWorld3dSpike()
            // `k` hides/shows the shortcut legends here too — the hidden flag is
            // shared with navigate mode.
            ke.code == "KeyK" -> toggleLegend()
            // Cinematic pane fly-bys: `B` glides slowly behind the nearest pane, `N`
            // to its flank at a three-quarter angle, `O`/`U` perch over/under it.
            // Any movement key cancels (below).
            ke.code == "KeyB" -> { flashShortcut("fly-behind"); flyBehindPane() }
            ke.code == "KeyN" -> { flashShortcut("fly-beside"); flyBesidePane() }
            ke.code == "KeyO" -> { flashShortcut("fly-over"); flyAbovePane() }
            ke.code == "KeyU" -> { flashShortcut("fly-under"); flyBelowPane() }
            ke.code in FLY_KEY_CODES -> {
                spikeCamReturning = false
                spikeFlyKeys.add(ke.code)
                // Auto-repeat keydowns restart the flash, so held keys stay lit.
                flyShortcutIdForCode(ke.code)?.let { flashShortcut(it) }
            }
        }
        return@handler
    }

    // Selection mode is **⌥⌘S** (bare `s` is free): checked before the ⌘ early-return.
    // Matched on physical `code` because ⌥ rewrites `ke.key`.
    if (ke.altKey && ke.metaKey && ke.code == "KeyS") {
        consume(); flashShortcut("selection"); toggleSelectionMode(); return@handler
    }

    // Navigate mode. Leave ⌘/Ctrl chords for the system; consume everything else.
    if (ke.metaKey || ke.ctrlKey) return@handler
    consume()
    // With a removal armed, only a second **⌥X** confirms it — every other key (Esc
    // included) just cancels the arm and does nothing else, so a stray keystroke can't
    // both dismiss the prompt and, say, rotate the ring.
    if (spikeRemoveArmed && !isRemoveKey(ke)) { cancelRemoveArm(); return@handler }
    // Each branch flashes its legend row (flashShortcut) so an open shortcuts
    // panel visibly acknowledges the keypress.
    when {
        // ⇧ + arrow "grabs" the fronted item and moves it one slot along the same axis
        // the bare arrow navigates: ⇧←/⇧→ reorder the pane in its tab, ⇧↑/⇧↓ the tab.
        ke.shiftKey && ke.key == "ArrowLeft" -> { flashShortcut("move-pane"); movePaneSlot(-1) }
        ke.shiftKey && ke.key == "ArrowRight" -> { flashShortcut("move-pane"); movePaneSlot(1) }
        ke.shiftKey && ke.key == "ArrowUp" -> { flashShortcut("move-tab"); moveTabSlot(-1) }
        ke.shiftKey && ke.key == "ArrowDown" -> { flashShortcut("move-tab"); moveTabSlot(1) }
        // Up at the stash shelf, ←/→ browse along the row of shelved panes instead of
        // rotating the ring far below — same keys, place-appropriate motion.
        ke.key == "ArrowLeft" ->
            if (cameraAtShelf()) { flashShortcut("shelf-browse"); shelfBrowse(-1) }
            else { flashShortcut("pane"); rotatePane(-1) }
        ke.key == "ArrowRight" ->
            if (cameraAtShelf()) { flashShortcut("shelf-browse"); shelfBrowse(1) }
            else { flashShortcut("pane"); rotatePane(1) }
        ke.key == "ArrowUp" -> { flashShortcut("tabs"); switchTab(-1) }
        ke.key == "ArrowDown" -> { flashShortcut("tabs"); switchTab(1) }
        ke.key == "Enter" -> { flashShortcut("engage"); activateFront() }
        ke.key == " " || ke.code == "Space" -> { flashShortcut("stash"); toggleStash() }
        ke.key == "v" || ke.key == "V" -> { flashShortcut("stash-view"); toggleStashView() }
        ke.key == "Escape" -> closeWorld3dSpike()
        // ⇧ zoom presets, matched on the physical `code` because ⇧ rewrites `ke.key`
        // (⇧= is "+", ⇧- is "_", ⇧0 is ")" on a US layout): ⇧+ jumps to the largest
        // zoom that still fits the whole pane on screen, ⇧− to the zoom floor, ⇧0
        // back to 1:1. The code sets are resolved from the *live keyboard layout*
        // ([resolveZoomPresetCodes]) — the physical position of `+`/`-` moves per
        // layout (Swedish `+` sits on code `Minus`), so hard-coded US codes would
        // fire the wrong preset. Checked before the bare keys so the step-zoom
        // branches below (which match "+" / "_" as layout aliases) can't swallow
        // the chords.
        ke.shiftKey && ke.code in spikeZoomPlusCodes ->
            { flashShortcut("zoom-preset"); zoomFrontFit() }
        ke.shiftKey && ke.code in spikeZoomMinusCodes ->
            { flashShortcut("zoom-preset"); zoomFrontTo(ZOOM_MIN, glide = true) }
        ke.shiftKey && ke.code in spikeZoomZeroCodes ->
            { flashShortcut("zoom-preset"); resetFrontZoom() }
        ke.key == "+" || ke.key == "=" -> { flashShortcut("zoom"); zoomFront(1) }
        ke.key == "-" || ke.key == "_" -> { flashShortcut("zoom"); zoomFront(-1) }
        ke.key == "0" || ke.key == ")" -> { flashShortcut("zoom-reset"); resetFrontZoom() }
        // Grid resize: modifier-free keys — every modifier+arrow chord is taken on
        // macOS (⌃-arrows: Mission Control/Spaces, swallowed by the system;
        // ⌘←/→: line start/end; ⌥-arrows: intercepted before this handler in the
        // real app) — tried and reverted, so these stay on plain punctuation.
        ke.key == "." -> { flashShortcut("grid-w"); growGridW(1) }  // width (columns)
        ke.key == "," -> { flashShortcut("grid-w"); growGridW(-1) }
        ke.key == ">" -> { flashShortcut("grid-h"); growGridH(1) }  // ⇧ → height (rows)
        ke.key == "<" -> { flashShortcut("grid-h"); growGridH(-1) }
        ke.code == "KeyF" -> { flashShortcut("fly"); toggleFlyMode() }
        ke.key == "r" || ke.key == "R" -> { flashShortcut("reformat"); reformatFront() }
        ke.key == "j" || ke.key == "J" -> { flashShortcut("tilt"); toggleCameraTilt() }
        ke.key == "c" || ke.key == "C" -> { flashShortcut("cam-home"); resetCamera() }
        ke.key == "w" || ke.key == "W" -> { flashShortcut("signal"); cycleSignalOverride() }
        ke.key == "b" || ke.key == "B" -> { flashShortcut("bob"); toggleBob() }
        ke.key == "g" || ke.key == "G" -> { flashShortcut("working-style"); toggleWorkingStyle() }
        ke.key == "k" || ke.key == "K" -> {
            toggleLegend()
            // Flash the `k` row when the panel just reappeared, as feedback for
            // the un-hide; on hide there is nothing left to flash.
            if (!spikeLegendHidden) flashShortcut("legend")
        }
        ke.key == "t" || ke.key == "T" -> { flashShortcut("new-tab"); createTab() }
        ke.key == "n" || ke.key == "N" -> { flashShortcut("new-pane"); createPane() }
        isRemoveKey(ke) -> { flashShortcut("remove"); requestRemoveFocused() }
    }
}

/**
 * True for the chord that arms/confirms a removal: **⌥X** (Option/Alt+X).
 *
 * Closing a pane/tab is destructive, so it deliberately requires the ⌥ modifier —
 * bare `x` (and Backspace/Delete, which used to work) no longer remove anything, so
 * a stray keystroke can't destroy a pane. Matched on the physical `code` because ⌥
 * rewrites `ke.key` on macOS ("x" → "≈"). @see requestRemoveFocused
 */
internal fun isRemoveKey(ke: KeyboardEvent): Boolean =
    ke.altKey && ke.code == "KeyX"

/** Physical `code`s the free-fly camera consumes as held movement/rotation keys. */
internal val FLY_KEY_CODES = setOf(
    "ArrowUp", "ArrowDown",     // pitch
    "ArrowLeft", "ArrowRight",  // yaw
    "KeyW", "KeyS",             // throttle fwd / reverse
    "KeyQ", "KeyE",             // roll
    "KeyA", "KeyD",             // strafe left / right
    "Space",                    // strafe up
    "ShiftLeft", "ShiftRight",  // strafe down
)

/**
 * Closes the spike: restores every reparented **real** terminal to its original
 * place in the 2D layout, tears down every **mirror** preview, then removes the
 * overlay and listeners.
 */
internal fun closeWorld3dSpike() {
    if (!spikeOpen) return
    spikeOpen = false
    // Stop a playing demo movie first — its coroutine reads the spike state the
    // rest of this teardown is about to null out.
    spikeMovieJob?.cancel()
    spikeRaf?.let { window.cancelAnimationFrame(it) }
    spikeRaf = null
    spikeResize?.let { window.removeEventListener("resize", it) }
    spikeResize = null
    spikeKeys?.let { window.removeEventListener("keydown", it, true) }
    spikeKeys = null
    spikeKeysUp?.let { window.removeEventListener("keyup", it, true) }
    spikeKeysUp = null
    spikeConfigJob?.cancel()
    spikeConfigJob = null
    spikeLayoutJob?.cancel()
    spikeLayoutJob = null
    spikePendingFocusTab = null
    spikePendingFocusNewTab = false
    spikeFlyMode = false
    spikeFlyKeys.clear()

    for (p in spikePanes) disposeRingPane(p)
    spikePanes = mutableListOf()
    for (c in spikeEmptyTabs) disposeEmptyCard(c)
    spikeEmptyTabs = mutableListOf()

    // Land the app on the last pane you engaged (deferred here so the app's
    // focus management never fights ⌘Esc during the session).
    val tab = spikeLastEngagedTab
    val pane = spikeLastEngagedPane
    if (tab != null && pane != null) {
        runCatching { launchCmd(WindowCommand.SetActiveTab(tab)) }
        runCatching { launchCmd(WindowCommand.SetFocusedPane(tabId = tab, paneId = pane)) }
        runCatching { launchCmd(WindowCommand.RaisePane(paneId = pane)) }
    }
    spikeLastEngagedTab = null
    spikeLastEngagedPane = null
    spikeEngaged = false

    spikeRemoveArmed = false
    spikeRemoveArmTimer?.let { window.clearTimeout(it) }
    spikeRemoveArmTimer = null
    spikeConfirmBadge = null

    spikeSelectionMode = false
    spikeModeBadge = null
    spikeLegendPanel = null
    spikeFlyLegendPanel = null
    spikeDemoTourPulseTimer?.let { window.clearTimeout(it) }
    spikeDemoTourPulseTimer = null
    spikeDemoTourButton = null
    spikeDemoTourHint = null
    spikeLegendRows.clear()
    spikeFlyLegendRows.clear()
    spikeNavLabelTimer?.let { window.clearTimeout(it) }
    spikeNavLabelTimer = null
    spikeNavLabel = null
    clearHomeBeacon()
    clearStashBeacon()
    clearCosmos()
    spikeChromeColors = null
    spikeFilterDefs = null
    spikeBulgeMapUri = null
    spikeCss3d = null
    spikeCssScene = null
    spikeCamera = null
    spikeOverlay?.remove()
    spikeOverlay = null
}
