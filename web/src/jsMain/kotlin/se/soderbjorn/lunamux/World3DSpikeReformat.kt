/*
 * Split from World3DSpike.kt — pane grid sizing under the PTY-truth model.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 *
 * ## The PTY-truth sizing model
 *
 * A ring pane is a **truthful window onto its PTY**: the pane's pixel size is always
 * derived arithmetically from the terminal's grid (`cols × cellWidth`,
 * `rows × cellHeight` — [presentPaneToGrid]), never from DOM measurement. The PTY
 * grid is the *single size authority*; it only changes on an explicit user command:
 *
 *  - **grid keys** (`,`/`.` cols, `<`/`>` rows) add/remove cells directly
 *    ([growGridAxis] → [setPaneGrid] with `reassert = true`);
 *  - **`r` reformat** keeps the current grid — the pane's box *is* its grid, so a
 *    "fit to my screen" is identity — and **jiggles** the PTY one row out and back
 *    so the program gets real SIGWINCHes and repaints (a same-size pin is deduped
 *    end-to-end and reaches nobody); the pane's size ends where it started
 *    ([reformatPane]);
 *  - an **external client's resize** arrives as a `PtyServerMessage.Size` broadcast
 *    and flows through the same function with `reassert = false`
 *    ([applyMirrorSize] for mirrors, [spikeOnServerSize] for mounted panes).
 *
 * Opening the world is **PTY-neutral**: no fitting, no resize votes, no reformat —
 * panes appear at their true size. This replaced the old chain
 * (factor map → pixel box → FitAddon → DOM-measured "hug"), whose stale DOM reads
 * made panes fail to resize, jump size on reformat, and open blank.
 */
package se.soderbjorn.lunamux

import kotlin.js.json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
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
import se.soderbjorn.lunula.core.argbToCss
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.CSS3DRenderer
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene

/**
 * True when [entry]'s container is currently mounted on a 3D-world ring plane.
 *
 * There the container's pixel box is *derived from* the terminal grid
 * ([presentPaneToGrid]), so any 2D machinery that fits the grid to the
 * container must stand down while this is true: the fit addon proposes
 * container-minus-padding, i.e. a slightly smaller grid, whose Size broadcast
 * re-presents a slightly smaller container — a feedback ratchet that shrinks
 * the pane (and, via the shared PTY, every other client's pane) step by step
 * to the minimum. Checked by the per-pane `ResizeObserver` refit and the
 * socket-open fit in `connectPane` (LayoutBuilder.kt).
 *
 * Matched by **paneId**, not entry identity: a 2D layout rebuild while the
 * world is open (any config push that defeats the header-only fast path) can
 * mint a fresh [TerminalEntry] for a pane whose ring plane still holds the old
 * one — an identity check then stopped recognizing the pane, and every
 * automatic 2D reassert (autoReflow, geometry change, fonts-loaded) fought the
 * world's PTY-truth sizing from that moment on (grid keys visibly "did
 * nothing": each resize was immediately counter-voted back).
 *
 * @param entry the mounted terminal to test.
 * @return `true` while the 3D world is open and this entry's pane rides a ring plane.
 * @see presentPaneToGrid
 */
internal fun isRidingSpikePlane(entry: TerminalEntry): Boolean =
    spikeOpen && spikePanes.any { it.paneId == entry.paneId }

/**
 * Runs once on the first render-loop frame (after the CSS3D renderer has attached
 * the plane elements): presents every pane at its terminal's true grid size and
 * forces a repaint — the fix for reparented panes coming up blank. Under the
 * PTY-truth model this touches **no** terminal grid and sends **nothing** to any
 * PTY; it is pure presentation.
 */
internal fun postOpenLayout() {
    for (p in spikePanes) reformatAndHug(p, initial = true)
    // Two settle passes. 120 ms: re-present now that the CSS3D subtree is
    // attached and styled — the first pass above can run before
    // getComputedStyle sees the theme's padding strip, under-budgeting the box
    // by ~22px (the clipped bottom line that only healed on the first resize).
    // 700 ms: pin to the tail again — a mirror's ring replay streams in *after*
    // the first pass, and can leave the viewport a line above the cursor (the
    // "no cursor on launch" look, with geometry actually correct).
    for (delay in intArrayOf(120, 700)) {
        window.setTimeout({
            for (p in spikePanes) {
                val t = p.term ?: continue
                runCatching {
                    // Re-assert any persisted 3D grid override now the term exists and
                    // still holds its native size (once per open — see ensureGrid3dApplied).
                    // Runs before present so the plane lays out at the override grid.
                    ensureGrid3dApplied(p)
                    presentPaneToGrid(p)
                    t.asDynamic().scrollToBottom()
                    t.asDynamic().refresh(0, t.rows - 1)
                }
            }
        }, delay)
    }
}

/**
 * The terminal's rendered **cell size** (px): width × height of one character cell,
 * read from xterm's render service (the same private API [safeFit] already relies
 * on). Falls back to Menlo-ish estimates from the font size when the renderer hasn't
 * produced dimensions yet (pre-first-paint), so arithmetic sizing always has a
 * usable answer.
 *
 * @param term the terminal to read.
 * @return `Pair(cellWidth, cellHeight)` in px.
 * @see presentPaneToGrid
 */
internal fun cellMetrics(term: Terminal): Pair<Double, Double> {
    val cell = term.asDynamic()._core?._renderService?.dimensions?.css?.cell
    val w = (cell?.width as? Number)?.toDouble()?.takeIf { it > 1.0 }
    val h = (cell?.height as? Number)?.toDouble()?.takeIf { it > 1.0 }
    val font = ((term.options.fontSize as? Number)?.toDouble()) ?: 13.0
    return (w ?: font * 0.602) to (h ?: font * 1.35)
}

/**
 * Structural **chrome** around the cell grid inside the xterm element: the element's
 * CSS padding plus the viewport scrollbar gutter — space the container must provide
 * *beyond* `cols × cellW / rows × cellH` for the full grid to be visible. This is
 * the exact inverse of the fit addon's `proposeDimensions()` math (it subtracts
 * these from the container before dividing by the cell size); [presentPaneToGrid]
 * must add them back or the pane box is one row/col too tight and the bottom line
 * renders clipped mid-row. The padding is a static theme constant read via computed
 * style (not a layout measurement — safe against the async-DOM staleness that
 * plagued the old "hug").
 *
 * @param term the terminal whose chrome to read.
 * @return `Pair(horizontalChrome, verticalChrome)` in px; `(0, 0)` pre-open.
 * @see presentPaneToGrid @see safeFit
 */
internal fun xtermChrome(term: Terminal): Pair<Double, Double> = runCatching {
    val core = term.asDynamic()._core
    val el = (core?.element ?: term.asDynamic().element) as? HTMLElement
        ?: return@runCatching 0.0 to 0.0
    val (padW, padH) = elementPadding(el)
    val sbw = (core?.viewport?.scrollBarWidth as? Number)?.toDouble() ?: 0.0
    (padW + sbw) to padH
}.getOrDefault(0.0 to 0.0)

/**
 * [el]'s computed CSS padding, summed per axis. Class-applied padding survives any
 * inline `cssText` rewrite (inline styles can't clear stylesheet rules), so sizing
 * an element that carries themed padding must budget for it explicitly.
 *
 * @param el the element to read.
 * @return `Pair(paddingLeft+Right, paddingTop+Bottom)` in px.
 * @see presentPaneToGrid @see xtermChrome
 */
internal fun elementPadding(el: HTMLElement): Pair<Double, Double> = runCatching {
    val cs = window.getComputedStyle(el)
    fun px(v: String) = v.removeSuffix("px").toDoubleOrNull() ?: 0.0
    (px(cs.paddingLeft) + px(cs.paddingRight)) to (px(cs.paddingTop) + px(cs.paddingBottom))
}.getOrDefault(0.0 to 0.0)

/**
 * Sizes pane [p]'s plane to its terminal's **current grid** — the presentation half
 * of the PTY-truth model. Pure arithmetic (`cols × cellWidth + chrome`,
 * `rows × cellHeight + chrome` via [cellMetrics] + [xtermChrome]): no rect
 * measuring, no FitAddon, no async settling — so it is exact and synchronous,
 * unlike the old rect-measuring "hug" it replaces. Updates the wrapper, the
 * terminal container, [RingPane.baseCw]/[RingPane.baseCh], and resizes the
 * working-border SVG so its travelling dots track the real perimeter (a mismatched
 * border showed as a stray accent-coloured dot on the pane edge).
 *
 * Called whenever the grid changes ([setPaneGrid]) and at world open / mirror
 * promotion ([reformatAndHug]).
 *
 * With [animate] `true` the box **glides** to the new size over [GRID_ANIM_MS]
 * (a CSS width/height transition on the wrapper and container) instead of
 * snapping — used for every live grid change so a resize reads as fluid growth.
 * The terminal itself is already at the new grid (text can't tween); the moving
 * box clips/reveals it, and the border SVG stretches along (`width:100%` +
 * `preserveAspectRatio:none`), so everything tracks the edge mid-glide.
 * `false` (world open, mirror promotion) presents instantly — a pane must
 * *appear at* its true size, not morph toward it (that read as the old shrink
 * bug). [RingPane.baseCw]/[RingPane.baseCh] always hold the *target* box; the
 * render loop and settle logic never read the animating DOM.
 *
 * @param p the pane to present.
 * @param animate `true` to ease the plane to the new box, `false` to snap.
 * @see setPaneGrid @see cellMetrics @see xtermChrome @see resizeWorkingBorder
 */
internal fun presentPaneToGrid(p: RingPane, animate: Boolean = false) {
    val term = p.term ?: return
    val (cw, ch) = cellMetrics(term)
    val (chromeW, chromeH) = xtermChrome(term)
    // The reparented 2D pane brings **class-applied padding** with it on top of
    // the xterm element's own chrome — `.terminal-inner`'s 10/4/12/12 "breathing
    // room" strip (styles.css) — and stylesheet rules survive the inline cssText
    // below. With border-box sizing that padding is carved out of the box we
    // set, so it must be budgeted or the screen sits 10px low and the last line
    // (the "peeking cursor") clips, immune to `r`. The padding may live on ANY
    // level between the xterm element and the container (it's on the inner div,
    // not the container — a container-only read missed it), so walk the chain
    // and sum every level's padding.
    var contPadW = 0.0
    var contPadH = 0.0
    run {
        var node = (term.asDynamic().element as? HTMLElement)?.parentElement as? HTMLElement
        var hops = 0
        while (node != null && hops < 6) {
            val (pw, ph) = elementPadding(node)
            contPadW += pw
            contPadH += ph
            if (node === p.container) return@run
            node = node.parentElement as? HTMLElement
            hops++
        }
        // Chain never reached the container (detached mid-promotion / pre-open):
        // discard the partial sum and budget at least the container's own level.
        val (pw, ph) = elementPadding(p.container)
        contPadW = pw
        contPadH = ph
    }
    val gw = ceil(term.cols * cw + chromeW + contPadW).toInt()
    val gh = ceil(term.rows * ch + chromeH + contPadH).toInt()
    p.baseCw = gw
    p.baseCh = gh
    // Side-pane normalization. Under PTY truth a plane is exactly as big as its
    // real grid — a fullscreen-sized PTY yields a plane several times the ring's
    // slot spacing. Such a giant, yawed neighbour geometrically *intersects* the
    // other panes' planes, and the browser renders the true 3D slice: windows
    // visibly peek through each other. So renormalize the presentation: as a
    // *side* pane this plane is scaled down (never up) to fit the common screen
    // box, restoring the "wall of matching monitors" read; at the front it still
    // eases to 1:1 (see the render loop's targetScale), so PTY truth is preserved
    // exactly where you read and type. @see RingPane.normScale
    p.normScale = minOf(1.0, spikeScreenW.toDouble() / gw, spikeScreenH.toDouble() / gh)
    val ease = "width ${GRID_ANIM_MS}ms ease, height ${GRID_ANIM_MS}ms ease"
    p.wrapper.style.setProperty("transition", if (animate) ease else "none")
    p.wrapper.style.setProperty("width", "${gw}px")
    p.wrapper.style.setProperty("height", "${gh + TITLE_H}px")
    // box-sizing pinned explicitly: the padding budget above assumes the set
    // width/height are outer (border-box) sizes on every theme.
    p.container.style.cssText =
        "position:absolute;left:0;top:${TITLE_H}px;width:${gw}px;height:${gh}px;" +
            "box-sizing:border-box;" +
            (if (animate) "transition:$ease;" else "")
    resizeWorkingBorder(p.border, gw, gh + TITLE_H)
    p.entry?.oobOverlayRight?.style?.display = "none"
    p.entry?.oobOverlayBottom?.style?.display = "none"
}

/**
 * Resizes a **non-terminal** pane's (git / file-browser) plane box by a signed pixel
 * delta — the box-geometry counterpart of [presentPaneToGrid], which sizes a
 * *terminal* plane from its cell grid. Such a plane has no xterm/PTY to drive, so the
 * resize keys ([growGridAxis]/[gridNearestAxis]) route here for `p.term == null`
 * panes: the box grows/shrinks directly and the DOM view inside (registered at 100%
 * width/height in [buildRingPane]) reflows to fill it — exactly how the 2D world
 * resizes any pane by geometry rather than by cells.
 *
 * Mirrors the DOM-update tail of [presentPaneToGrid]: updates
 * [RingPane.baseCw]/[RingPane.baseCh] (which the render loop and camera math read as
 * the plane's native box), the wrapper and container boxes, the side-pane
 * normalization ([RingPane.normScale]), and the working-border SVG — gliding over
 * [GRID_ANIM_MS] when [animate] so the resize reads as fluid growth like a grid step.
 * Clamped to [PANE_BOX_MIN_W]..[PANE_BOX_MAX_W] / [PANE_BOX_MIN_H]..[PANE_BOX_MAX_H];
 * returns `false` (a clamped no-change) so the caller can log an inert key press just
 * like the grid path does.
 *
 * @param p the non-terminal pane to resize (a terminal pane would be resized via its
 *   grid instead; this path never touches [RingPane.term]).
 * @param dW signed width delta in px (`.`/`,`). @param dH signed height delta in px (`>`/`<`).
 * @param animate `true` to ease the plane to the new box, `false` to snap.
 * @return `true` if the box actually changed, `false` if already clamped at a bound.
 * @see presentPaneToGrid @see growGridAxis @see gridNearestAxis
 */
internal fun resizePaneBox(p: RingPane, dW: Int, dH: Int, animate: Boolean = true): Boolean {
    val gw = (p.baseCw + dW).coerceIn(PANE_BOX_MIN_W, PANE_BOX_MAX_W)
    val gh = (p.baseCh + dH).coerceIn(PANE_BOX_MIN_H, PANE_BOX_MAX_H)
    if (gw == p.baseCw && gh == p.baseCh) return false
    p.baseCw = gw
    p.baseCh = gh
    // Same side-pane renormalization as [presentPaneToGrid]: a large plane is scaled
    // down (never up) as a side pane so it can't intersect its ring neighbours; the
    // front pane still eases to 1:1 in the render loop. @see RingPane.normScale
    p.normScale = minOf(1.0, spikeScreenW.toDouble() / gw, spikeScreenH.toDouble() / gh)
    val ease = "width ${GRID_ANIM_MS}ms ease, height ${GRID_ANIM_MS}ms ease"
    p.wrapper.style.setProperty("transition", if (animate) ease else "none")
    p.wrapper.style.setProperty("width", "${gw}px")
    p.wrapper.style.setProperty("height", "${gh + TITLE_H}px")
    // Preserve the container's positioning (position/left/top set at build time) and
    // override only its box, so the git/file-browser view fills the new plane.
    p.container.style.setProperty("transition", if (animate) ease else "none")
    p.container.style.setProperty("width", "${gw}px")
    p.container.style.setProperty("height", "${gh}px")
    resizeWorkingBorder(p.border, gw, gh + TITLE_H)
    return true
}

/**
 * Sets pane [p]'s grid to `cols × rows`: re-presents the plane at the grid
 * ([presentPaneToGrid]), keeps a tail-following viewport pinned to the bottom, repaints,
 * and — depending on [reassert] — either asks the PTY for that grid or follows a grid it
 * has already moved to. Every 3D grid change (user command or server broadcast) funnels
 * through here.
 *
 * With [reassert] `true` (user commands: grid keys, `r`) a size **vote** at [votePriority]
 * goes out over the pane's own socket — mounted ([TerminalEntry.socket]) or preview
 * ([RingPane.mirrorSocket]) — deliberately bypassing the per-pane `autoReflow` gate (an
 * explicit resize is a reformat request, exactly like the 2D Reformat button). The local
 * terminal is **not** resized: the answering `Size` frame does that, through
 * [applyServerSize] for a mounted pane or [applyMirrorSize] for a preview. That is what
 * keeps the world from being a second authority able to move a grid the server has not
 * agreed to — a vote that loses to a mobile floor or a governing client used to leave the
 * plane showing a grid the PTY never had, until the next broadcast snapped it back.
 *
 * On the alt screen ([isOnAltScreen]) the request goes out all the same — the user must
 * always be able to resize, even under a full-screen TUI — but a warning is logged, since
 * the TUI will repaint from scratch at the new grid (a momentary blank is expected).
 *
 * With [reassert] `false` (a server `Size` broadcast) the local resize DOES apply: the PTY
 * has already changed and the pane must follow to stay truthful.
 *
 * @param p the pane to resize.
 * @param cols target column count (already validated by the caller).
 * @param rows target row count (already validated by the caller).
 * @param reassert `true` to ask the PTY for this grid (user command), `false` to follow a
 *   grid it already has (server broadcast).
 * @param votePriority the tier a [reassert] request competes in.
 * @see growGridAxis @see reformatPane @see applyMirrorSize @see spikeOnServerSize
 */
internal fun setPaneGrid(
    p: RingPane,
    cols: Int,
    rows: Int,
    reassert: Boolean,
    votePriority: SizePriority = SizePriority.THREE_D,
) {
    val term = p.term ?: return
    if (cols < 1 || rows < 1) return
    if (reassert && isOnAltScreen(term)) {
        console.warn(
            "[world3d-spike] setPaneGrid on alt screen: pane ${p.paneId} is showing a " +
                "full-screen TUI; resizing anyway (the app will repaint at the new grid)"
        )
    }
    runCatching {
        val buf = term.asDynamic().buffer.active
        val atBottom =
            ((buf.baseY as? Number)?.toInt() ?: 0) == ((buf.viewportY as? Number)?.toInt() ?: 0)
        // Only a **follow** resizes the grid here. A user grid command ([reassert] = true)
        // asks the server and lets the answering Size frame do the resize, like every other
        // client path — so the world stops being a second place that can move a grid the
        // server has not agreed to, and a vote that loses (a mobile floor, a governing
        // client) no longer leaves the plane showing a grid the PTY never had.
        //
        // xterm fires onResize listeners *synchronously inside* resize(); isolate it so a
        // throwing listener elsewhere in the app can never abort the presentation below
        // (that exact failure hid every grid/reformat change).
        if (!reassert && (cols != term.cols || rows != term.rows)) {
            runCatching { term.asDynamic().resize(cols, rows) }.onFailure {
                console.error(
                    "[world3d-spike] xterm resize(${cols}x$rows) threw for pane ${p.paneId} " +
                        "(a 2D onResize listener?); grid now ${term.cols}x${term.rows}",
                    it,
                )
            }
        }
        presentPaneToGrid(p, animate = true)
        if (reassert) {
            // Log which socket carries the ForceResize (mounted vs mirror) and
            // whether it can: a missing/non-OPEN socket means the pane resizes
            // locally but the PTY keeps its old size — and the next server Size
            // broadcast snaps the pane straight back (looks like "key did nothing").
            val sock = p.entry?.socket ?: p.mirrorSocket
            val kind = if (p.entry?.socket != null) "mounted" else "mirror"
            when {
                sock == null -> console.warn(
                    "[world3d-spike] no PTY socket for pane ${p.paneId}: resize vote " +
                        "${cols}x$rows NOT sent; local grid will diverge from the PTY"
                )
                sock.readyState.toInt() != org.w3c.dom.WebSocket.OPEN.toInt() -> console.warn(
                    "[world3d-spike] $kind socket for pane ${p.paneId} not OPEN " +
                        "(readyState=${sock.readyState}): resize vote ${cols}x$rows NOT sent"
                )
                else -> {
                    // Soft resize *vote* at [votePriority] (THREE_D for a grid override,
                    // NORMAL when reverting to native), not a ForceResize: the tiered
                    // aggregation lets a THREE_D vote outrank the 2D clients without
                    // evicting anyone, and a connected mobile client's MOBILE floor still
                    // wins. @see sendResizeVote @see SizePriority
                    console.log("[world3d-spike] resize vote ${cols}x$rows ($votePriority) sent for pane ${p.paneId} ($kind socket)")
                    sendResizeVote(sock, cols, rows, votePriority)
                }
            }
        }
        // A **user** grid command ([reassert] = true: the grid keys, `r`) is a
        // deliberate "reshape this window" gesture — always land it on the live
        // tail, exactly like [reformatPane] does. Without this, a reflow across a
        // multi-step resize (e.g. the demo tour's ./,/>/< run) can leave the
        // terminal parked a line above its baseline (`viewportY < baseY`): xterm
        // then reads as "scrolled up", every following `atBottom` snapshot in the
        // sequence is false so it never re-pins, and the next PTY output falsely
        // raises the "New output" pill on a pane the user never scrolled. A
        // passive **server-size follow** ([reassert] = false: another client
        // resized) instead preserves the reader's position, only re-pinning if
        // they were already tailing. @see reformatPane @see writeHoldingScroll
        if (reassert || atBottom) {
            term.asDynamic().scrollToBottom()
            // Clear a possibly-stale "New output" pill now that we are tailing
            // again (no-op for mirror panes, which carry no scroll button).
            p.entry?.let { updateScrollButton(it) }
        }
        term.asDynamic().refresh(0, term.rows - 1)
        console.log(
            "[world3d-spike] setPaneGrid done: pane ${p.paneId} at ${term.cols}x${term.rows} " +
                "(box ${p.baseCw}x${p.baseCh}, reassert=$reassert)"
        )
    }.onFailure {
        console.error("[world3d-spike] setPaneGrid(${cols}x$rows) FAILED for pane ${p.paneId}", it)
    }
}

/**
 * The [SizePriority] tier a pane's PTY-size votes should carry right now:
 * [SizePriority.THREE_D] while it holds a 3D grid override ([spikeGrid3dByPane]),
 * else [SizePriority.NORMAL]. Lets a reformat/redraw re-assert a pane's size at
 * the right tier without changing whether it is overridden.
 *
 * @param p the pane. @return its current vote tier. @see setPaneGrid
 */
internal fun paneVotePriority(p: RingPane): SizePriority =
    if (p.paneId in spikeGrid3dByPane) SizePriority.THREE_D else SizePriority.NORMAL

/**
 * Record a pane's **native** (2D) grid the first time it is seen this world
 * session, so a later revert has a target. No-op once captured (a pane already
 * carrying an override would otherwise overwrite native with the override size).
 *
 * @param paneId the pane. @param term its terminal (its current cols/rows are the
 *   native grid, since capture happens before any override is applied).
 * @see spikeNativeGridByPane @see restoreFrontNativeGrid
 */
internal fun captureNativeGrid(paneId: String, term: Terminal) {
    if (paneId !in spikeNativeGridByPane) {
        spikeNativeGridByPane[paneId] = term.cols to term.rows
    }
}

/**
 * Remember a pane's 3D grid override locally ([spikeGrid3dByPane]) and write it
 * through to the server ([WindowCommand.SetPaneGrid3d] → [Pane.grid3d]) so it
 * persists across app restarts and re-entering the world. The single place a
 * user grid resize records its override; the actual PTY reflow is driven by the
 * THREE_D vote in [setPaneGrid].
 *
 * @param paneId the pane. @param cols/[rows] the chosen 3D grid.
 * @see rememberGrid3dOverride @see restoreFrontNativeGrid
 */
internal fun rememberGrid3dOverride(paneId: String, cols: Int, rows: Int) {
    spikeGrid3dByPane[paneId] = cols to rows
    runCatching { launchCmd(WindowCommand.SetPaneGrid3d(paneId, cols, rows)) }
}

/**
 * Apply a pane's persisted 3D grid override exactly once per world open.
 *
 * On the first call for [p] this open: captures the pane's native grid (the term
 * is still at its 2D size here), then — if the pane carries an override differing
 * from native — resizes it and votes the override at [SizePriority.THREE_D].
 * Idempotent across the two [postOpenLayout] settle passes via [spikeGrid3dApplied]
 * (cleared on open), so a pane already resized on the 120 ms pass is left alone at
 * 700 ms rather than re-capturing the (now overridden) size as "native".
 *
 * @param p the pane to reconcile. No-op until its terminal exists.
 * @see spikeGrid3dByPane @see seedGrid3dFromConfig
 */
internal fun ensureGrid3dApplied(p: RingPane) {
    val term = p.term ?: return
    if (p.paneId in spikeGrid3dApplied) return
    captureNativeGrid(p.paneId, term)
    spikeGrid3dApplied.add(p.paneId)
    val ov = spikeGrid3dByPane[p.paneId] ?: return
    if (ov.first != term.cols || ov.second != term.rows) {
        console.log("[world3d-spike] applying persisted grid override ${ov.first}x${ov.second} to pane ${p.paneId}")
        setPaneGrid(p, ov.first, ov.second, reassert = true, votePriority = SizePriority.THREE_D)
    }
}

/**
 * **Restore native grid** hotkey — clears the front pane's 3D grid override and
 * reflows it back to its native (2D) size. Drops the override both locally
 * ([spikeGrid3dByPane]) and on the server ([WindowCommand.SetPaneGrid3d] with
 * null cols/rows → [Pane.grid3d] = null), then votes the native size at
 * [SizePriority.NORMAL] so the PTY leaves the THREE_D tier and rejoins the 2D
 * clients' aggregation. No-op unless a pane is settled at the front.
 *
 * Bound to `/` in [buildKeyHandler] (adjacent to the `,`/`.` grid keys). If the
 * pane's native grid was never captured this session it falls back to the term's
 * current size (nothing to restore), still clearing the override.
 *
 * @see rememberGrid3dOverride @see setPaneGrid
 */
internal fun restoreFrontNativeGrid() {
    val fi = frontIndex()
    if (spikeSettledIndex != fi || fi < 0) {
        console.warn("[world3d-spike] restore-native ignored: front pane not settled (front=$fi settled=$spikeSettledIndex)")
        return
    }
    restorePaneNativeGrid(spikePanes.getOrNull(fi) ?: return)
}

/**
 * Free-flight counterpart of [restoreFrontNativeGrid]: clears the 3D grid
 * override on the pane **nearest the camera** and reflows it to native. @see
 * restorePaneNativeGrid
 */
internal fun restoreNearestNativeGrid() {
    restorePaneNativeGrid(actionTargetPane() ?: return)
}

/**
 * Shared body of the "restore native grid" hotkey: clear [p]'s 3D grid override
 * locally and on the server, then reflow it back to its native (2D) size with a
 * [SizePriority.NORMAL] vote so the PTY leaves the THREE_D tier. No-op for a
 * non-terminal pane. Falls back to the term's current size if the native grid was
 * never captured this session (still clearing the override).
 *
 * @param p the pane to restore. @see restoreFrontNativeGrid @see restoreNearestNativeGrid
 */
internal fun restorePaneNativeGrid(p: RingPane) {
    val term = p.term ?: return
    val hadOverride = p.paneId in spikeGrid3dByPane
    spikeGrid3dByPane.remove(p.paneId)
    runCatching { launchCmd(WindowCommand.SetPaneGrid3d(p.paneId, null, null)) }
    val native = spikeNativeGridByPane[p.paneId] ?: (term.cols to term.rows)
    console.log(
        "[world3d-spike] restore-native: pane ${p.paneId} ${term.cols}x${term.rows} -> " +
            "${native.first}x${native.second} (hadOverride=$hadOverride)"
    )
    setPaneGrid(p, native.first, native.second, reassert = true, votePriority = SizePriority.NORMAL)
}

/**
 * Revert every overridden pane to its native grid as the world closes: for each
 * pane still carrying a 3D override with a live PTY socket, vote its native size
 * at [SizePriority.NORMAL] so the PTY drops the THREE_D tier and 2D shows the
 * native size again. The override itself is left in [spikeGrid3dByPane] /
 * [Pane.grid3d] so re-entering the world restores it. Best-effort — a crashed
 * app that never runs this still reverts, because the closing sockets drop their
 * THREE_D votes server-side.
 *
 * Called from [closeWorld3dSpike] before the ring panes (and their sockets) are
 * disposed.
 *
 * @see closeWorld3dSpike @see ensureGrid3dApplied
 */
internal fun revertGrid3dOverridesOnClose() {
    for (p in spikePanes) {
        if (p.paneId !in spikeGrid3dByPane) continue
        val native = spikeNativeGridByPane[p.paneId] ?: continue
        val sock = p.entry?.socket ?: p.mirrorSocket ?: continue
        console.log("[world3d-spike] world close: reverting pane ${p.paneId} to native ${native.first}x${native.second}")
        sendResizeVote(sock, native.first, native.second, SizePriority.NORMAL)
    }
}

/**
 * Presents one pane at its true grid and forces a repaint. Shared by
 * [postOpenLayout] (all panes, `initial = true`) and [tryPromoteMirror] (the pane
 * whose mirror term was just hot-swapped for the real mounted one). The name is
 * historical — under the PTY-truth model nothing is reformatted or DOM-hugged
 * here; the grid is left exactly as the PTY has it.
 *
 * @param p the pane. @param initial whether this is the world-open pass (kept for
 *   the callers' intent; the normalization scale is owned by [presentPaneToGrid],
 *   which recomputes it on every present so an oversized grid is always
 *   renormalized as a side pane).
 * @see presentPaneToGrid
 */
internal fun reformatAndHug(p: RingPane, initial: Boolean) {
    // Non-terminal (git / file-browser) planes have no xterm grid: their DOM view
    // fills the fixed screen box set at build time, at scale 1:1.
    val term = p.term ?: run {
        if (initial) p.normScale = 1.0
        return
    }
    runCatching {
        presentPaneToGrid(p)
        term.asDynamic().refresh(0, term.rows - 1)
    }
}

/**
 * True if [term] is showing its **alternate screen buffer** — i.e. a full-screen TUI
 * (vim, less, htop, a menu) currently owns the grid. xterm.js exposes the active
 * buffer's `type` as `"normal"` or `"alternate"`.
 *
 * A resize on the alt screen makes the app repaint from scratch, which reads as the
 * pane momentarily **blanking**; [setPaneGrid] uses this only to log a warning —
 * the resize itself always goes through (grid keys must never be inert).
 * @see setPaneGrid
 */
internal fun isOnAltScreen(term: Terminal): Boolean =
    (term.asDynamic().buffer?.active?.type as? String) == "alternate"

/**
 * **Reformats** one pane — the modal equivalent of clicking the pane's **Reformat**
 * button in the 2D UX: makes the program redraw itself to exactly fill the pane's
 * grid, without changing the pane's physical size. Under the PTY-truth model the
 * pane's pixel box already *is* its grid ([presentPaneToGrid]), so there is nothing
 * to re-fit — an earlier design snapped to the global [spikeScreenW]×[spikeScreenH]
 * box instead, which yanked the pane to a wildly different size (explicitly
 * rejected as disorienting), and its replacement (re-pin the identical grid) turned
 * out to be **end-to-end invisible**: the kernel only raises SIGWINCH when the PTY
 * size actually changes, and the server's size StateFlow dedups equal values, so a
 * same-size ForceResize reaches nobody and `r` "did nothing" — while any *real*
 * grid-key change visibly auto-reformatted, the clue that unmasked this.
 *
 * So `r` is a **jiggle** (the classic tmux redraw trick): force the PTY one row off
 * and, [REFORMAT_JIGGLE_MS] later, back — two *real* size changes, two SIGWINCHes,
 * and the program repaints its full screen at the current grid. Each half also
 * evicts other clients' size votes and pins the PTY here ([PtyControl.ForceResize]
 * semantics), rescuing a session another client had shrunk. The pane visibly
 * breathes one row out and back (animated, [GRID_ANIM_MS]) as feedback. Invoked on
 * demand via the `r` hotkey (see [reformatFront]). Works identically for mounted
 * and preview panes — [setPaneGrid] picks the right socket. On alt-screen TUIs
 * ([isOnAltScreen] inside [setPaneGrid]) the jiggle applies too — the TUI just
 * repaints twice, which is exactly the redraw the user asked for.
 *
 * @param p the pane to reformat.
 * @see setPaneGrid @see reformatFront
 */
internal fun reformatPane(p: RingPane) {
    if (p.kind != PaneKind.TERMINAL) return
    val term = p.term ?: return // git / file-browser plane: no grid to reflow
    val cols = term.cols
    val rows = term.rows
    // Grow first when there is headroom (gentler: no transient scroll-up), else
    // shrink; either way the return trip lands exactly on the original grid.
    val step = if (rows + 1 <= GRID_MAX_ROWS) 1 else -1
    // A reformat is a *redraw at the current size*, not a resize: re-assert at the
    // pane's existing tier (THREE_D if it carries a 3D override, else NORMAL) rather
    // than persisting a new override.
    val prio = paneVotePriority(p)
    setPaneGrid(p, cols, rows + step, reassert = true, votePriority = prio)
    window.setTimeout({
        setPaneGrid(p, cols, rows, reassert = true, votePriority = prio)
        // Reformat means "show me this pane properly": land on the live tail
        // unconditionally. The jiggle's resizes (and any interleaved server
        // Size follows) can leave the viewport a line above the cursor — the
        // grid then looks truncated even though nothing is clipped.
        runCatching {
            term.asDynamic().scrollToBottom()
            term.asDynamic().refresh(0, term.rows - 1)
        }
    }, REFORMAT_JIGGLE_MS)
}

/**
 * Follows a server-mandated PTY resize on a **mounted** pane while the world is
 * open: [applyServerSize] has already resized the local terminal (2D shared path);
 * this re-presents the pane's plane at the new grid so it stays truthful. The
 * mirror-pane counterpart is [applyMirrorSize]. No-op when the world is closed or
 * the entry has no ring pane.
 *
 * Called from [applyServerSize].
 *
 * @param entry the mounted terminal whose PTY size just changed.
 * @see applyServerSize @see applyMirrorSize @see presentPaneToGrid
 */
internal fun spikeOnServerSize(entry: TerminalEntry) {
    if (!spikeOpen) return
    val p = spikePanes.find { it.entry === entry } ?: return
    runCatching {
        presentPaneToGrid(p, animate = true)
        entry.term.asDynamic().refresh(0, entry.term.rows - 1)
    }.onFailure {
        console.error("[world3d-spike] spikeOnServerSize failed to re-present pane ${p.paneId}", it)
    }
}

/**
 * Reformats the pane currently settled at the front — the world-mode `r` hotkey,
 * bound in [buildKeyHandler]. This is the manual replacement for the former
 * auto-reformat-on-activate (which could blank some panes on arrival): the user now
 * chooses when to reflow. No-op unless a pane is actually settled at the front.
 *
 * Deferred one animation frame so it runs at the pane's fully settled transform.
 *
 * @see reformatPane
 */
internal fun reformatFront() {
    val fi = frontIndex()
    if (spikeSettledIndex != fi || fi < 0) return
    val front = spikePanes.getOrNull(fi) ?: return
    window.requestAnimationFrame { reformatPane(front) }
}

/**
 * Reformats the pane **nearest the camera** — the free-flight `r`, the counterpart of
 * [reformatFront] for the command center's front pane. Deferred one frame like the
 * front reformat so it runs at the pane's settled transform. No-op with no panes.
 *
 * @see reformatFront @see reformatPane @see nearestPaneToCamera
 */
internal fun reformatNearest() {
    val p = actionTargetPane() ?: return
    window.requestAnimationFrame { reformatPane(p) }
}
