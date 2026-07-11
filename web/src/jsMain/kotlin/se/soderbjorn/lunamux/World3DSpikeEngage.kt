/*
 * Split from World3DSpike.kt — engage/promote, latch flex and bulge displacement filter.
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.three.CSS3DObject
import se.soderbjorn.lunamux.three.CSS3DRenderer
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene

/**
 * Engages the settled front pane (Enter): focuses its terminal so you can type.
 *
 * A **real** pane is engaged with a plain `focus()` and *no* app command — the
 * whole point: dispatching [WindowCommand.SetActiveTab] / [WindowCommand.SetFocusedPane]
 * mid-session makes the app's `refocusActivePane` fight every ⌘Esc for focus, so
 * we defer all app activation to [closeWorld3dSpike] (which lands you on the last
 * pane you engaged). A **mirror** preview is unavoidably different: engaging it
 * activates its tab so the real terminal mounts, then [tryPromoteMirror] swaps the
 * live term onto the plane. No-op unless a front pane is settled.
 */
/**
 * Arms the one-shot latch **flex** on pane [p]: the render loop then plays a decaying
 * spring deflection ([FLEX_FRAMES] frames) on that plane, restarting cleanly if a
 * flex is already mid-play. Called by [activateFront] (engage, [FLEX_DIR_OUT]) and
 * [disengage] (latch-off, [FLEX_DIR_IN]) so the moment reads as a real event.
 *
 * @param p the pane to flex. @param dir the deflection sign — [FLEX_DIR_OUT] to lunge
 *   outward (toward the camera), [FLEX_DIR_IN] to dip inward.
 */
internal fun startFlex(p: RingPane, dir: Double) {
    p.flexPhase = 0.0
    p.flexDir = dir
}

/** SVG namespace for the displacement-filter elements built by [createBulgeFilter]. */
internal const val SVG_NS = "http://www.w3.org/2000/svg"

/** The hidden `<svg>` holding every pane's bulge `<filter>`; created in [openWorld3dSpike]. */
internal var spikeFilterDefs: Element? = null

/** Monotonic id source so each pane's bulge filter gets a unique `url(#…)` target. */
internal var spikeBulgeSeq = 0

/** Cached data-URI of the radial displacement map ([bulgeMapUri]); built once per open. */
internal var spikeBulgeMapUri: String? = null

/**
 * Builds (once, then caches) the **displacement map** that drives the latch bulge: a
 * radial vector field where each texel's red/green channels encode an outward
 * push-direction scaled by `sin(π·r)` — zero at the centre *and* the rim, peaking
 * mid-radius. Fed to an `feDisplacementMap`, a positive `scale` samples the live
 * pane through this field so its interior magnifies (a convex bulge toward the
 * centre) while the rim stays pinned; a negative `scale` dishes it inward.
 *
 * Called lazily by [createBulgeFilter]. @return a PNG `data:` URI for the map.
 */
internal fun bulgeMapUri(): String {
    spikeBulgeMapUri?.let { return it }
    val nn = 128
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = nn
    canvas.height = nn
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    val img: ImageData = ctx.createImageData(nn.toDouble(), nn.toDouble())
    val data = img.data
    for (y in 0 until nn) {
        for (x in 0 until nn) {
            val nx = (x + 0.5) / nn * 2 - 1
            val ny = (y + 0.5) / nn * 2 - 1
            val r = sqrt(nx * nx + ny * ny)
            val dirx = if (r > 1e-4) nx / r else 0.0
            val diry = if (r > 1e-4) ny / r else 0.0
            val s = sin(PI * min(r, 1.0))
            val idx = (y * nn + x) * 4
            data.asDynamic()[idx] = ((0.5 + 0.5 * dirx * s) * 255).roundToInt()
            data.asDynamic()[idx + 1] = ((0.5 + 0.5 * diry * s) * 255).roundToInt()
            data.asDynamic()[idx + 2] = 128
            data.asDynamic()[idx + 3] = 255
        }
    }
    ctx.putImageData(img, 0.0, 0.0)
    return canvas.toDataURL("image/png").also { spikeBulgeMapUri = it }
}

/**
 * Creates a fresh, uniquely-id'd `<filter>` (a `feImage` map + a `feDisplacementMap`)
 * inside the shared [spikeFilterDefs] defs and returns its id plus the live
 * `feDisplacementMap` node. Each pane owns one so the render loop can animate that
 * pane's bulge `scale` independently by writing the node's `scale` attribute.
 *
 * @return the `url(#…)` filter id and the `feDisplacementMap` element to drive.
 * @see bulgeMapUri @see startFlex
 */
internal fun createBulgeFilter(): Pair<String, Element> {
    val id = "spike-bulge-${spikeBulgeSeq++}"
    val filter = document.createElementNS(SVG_NS, "filter")
    filter.setAttribute("id", id)
    // Expand the region so the outermost displaced pixels aren't clipped by the box.
    filter.setAttribute("x", "-15%")
    filter.setAttribute("y", "-15%")
    filter.setAttribute("width", "130%")
    filter.setAttribute("height", "130%")
    filter.setAttribute("color-interpolation-filters", "sRGB")
    val feImage = document.createElementNS(SVG_NS, "feImage")
    feImage.setAttribute("preserveAspectRatio", "none")
    feImage.setAttribute("result", "bmap")
    // `href` for modern Chromium; xlink:href as a legacy fallback.
    feImage.setAttribute("href", bulgeMapUri())
    feImage.setAttributeNS("http://www.w3.org/1999/xlink", "xlink:href", bulgeMapUri())
    val feDisp = document.createElementNS(SVG_NS, "feDisplacementMap")
    feDisp.setAttribute("in", "SourceGraphic")
    feDisp.setAttribute("in2", "bmap")
    feDisp.setAttribute("scale", "0")
    feDisp.setAttribute("xChannelSelector", "R")
    feDisp.setAttribute("yChannelSelector", "G")
    filter.appendChild(feImage)
    filter.appendChild(feDisp)
    spikeFilterDefs?.appendChild(filter)
    return id to feDisp
}

/**
 * Focuses (engages) the **command center's selected** pane — the `Enter` action in
 * command center mode. No-op unless that pane is settled at the front. Delegates to
 * [activatePane]. @see activatePane
 */
internal fun activateFront() {
    val fi = frontIndex()
    if (spikeSettledIndex != fi) return
    val p = spikePanes.getOrNull(fi) ?: return
    activatePane(p)
}

/**
 * Focuses (engages) a specific pane [p] so keystrokes flow into it — the shared body of
 * both `Enter` paths: [activateFront] (command center → the selected front pane) and the
 * free-flight `Enter` (→ the nearest pane, which need not be the front one). Plays the
 * outward latch flex, records [spikeLastEngagedPane]/[spikeLastEngagedTab] (which
 * [disengage] and [tryPromoteMirror] key off, so the *engaged* pane is tracked rather
 * than assumed to be the front), and focuses/promotes by pane kind. Does not move the
 * camera — in free flight you observe the typing from wherever you are.
 *
 * @param p the pane to engage. @see activateFront @see disengage @see tryPromoteMirror
 */
internal fun activatePane(p: RingPane) {
    startFlex(p, FLEX_DIR_OUT)
    spikeEngaged = true
    // Swap the bottom-left legend to the engage / type panel — from here every key but
    // the ⌥⌘X disengage chord types into the terminal. @see updateLegendVisibility
    updateLegendVisibility()
    spikeLastEngagedTab = p.tabId.takeIf { it.isNotEmpty() }
    spikeLastEngagedPane = p.paneId
    when {
        // Git / file-browser: the DOM view is already live and clickable (the render loop
        // turns pointer-events on for the engaged front pane), so there is nothing to
        // promote — just focus a focusable child (e.g. the filter box) if one exists.
        p.kind != PaneKind.TERMINAL -> {
            runCatching { (p.container.querySelector("input, [tabindex], button") as? HTMLElement)?.focus() }
        }
        else -> {
            // Claim this pane as the app's **active tab + focused pane** before focusing it.
            // The app's [refocusActivePane] re-focuses the active tab's `focusedPaneId` after
            // every config push; in free flight the engaged pane is the *nearest* one, which is
            // usually neither the active tab nor its focused pane — so a bare `term.focus()`
            // gets stolen straight back and typing goes nowhere (you get the engaged border but
            // no live cursor). Making it the server's focused pane points that machinery *at*
            // this pane instead of fighting it. In command center this is effectively a no-op
            // (the selected pane is already active/focused), so it fixes free flight without
            // changing the command-center behaviour. Server pushes are deduped, so re-asserting
            // an already-active tab / focused pane costs nothing.
            if (p.tabId.isNotEmpty()) {
                runCatching { launchCmd(WindowCommand.SetActiveTab(p.tabId)) }
                runCatching { launchCmd(WindowCommand.SetFocusedPane(tabId = p.tabId, paneId = p.paneId)) }
            }
            if (p.interactive) runCatching { p.term?.focus() }
            else tryPromoteMirror(p, 0) // mirror preview → mount the real term, then focus
        }
    }
}

/**
 * After a mirror pane's tab is engaged, waits (briefly, polling) for its real
 * terminal to mount into the [terminals] registry, then hot-swaps the live term's
 * container onto the plane in place of the preview: the pane becomes fully
 * interactive without the ring rebuilding. No-op if the spike closes or the pane
 * is no longer engaged first.
 *
 * @param p the mirror pane to promote. @param attempt the current retry count.
 */
internal fun tryPromoteMirror(p: RingPane, attempt: Int) {
    if (!spikeOpen || p.interactive) return
    // Track the pane we actually engaged (front in command center, nearest in free
    // flight) rather than assuming it's the front one.
    if (spikeLastEngagedPane != p.paneId) return
    val entry = terminals[p.paneId]
    if (entry == null) {
        if (attempt < 15) window.setTimeout({ tryPromoteMirror(p, attempt + 1) }, 120)
        return
    }
    runCatching {
        val real = entry.container
        p.origParent = real.parentNode
        p.origNext = real.nextSibling
        real.setAttribute("data-spike-prevcss", real.style.cssText)
        entry.oobOverlayRight?.style?.display = "none"
        entry.oobOverlayBottom?.style?.display = "none"
        // Retire the preview.
        runCatching { p.mirrorSocket?.close() }
        p.mirrorSocket = null
        runCatching { p.term?.dispose() }
        runCatching { p.container.remove() }
        // Install the live terminal, below the title strip and behind the dim.
        real.style.cssText = "position:absolute;left:0;top:${TITLE_H}px;width:${spikeScreenW}px;height:${spikeScreenH}px;"
        p.wrapper.insertBefore(real, p.dim)
        p.entry = entry
        p.term = entry.term
        p.fit = entry.fit
        p.container = real
        p.interactive = true
        p.needsRefit = SPIKE_UNIFORM_SCREENS
        p.baseFont = ((entry.term.options.fontSize as? Number)?.toInt()) ?: 13
        p.previewTag?.style?.setProperty("display", "none")
        reformatAndHug(p, initial = false)
        // Only grab focus if the user still wants to be engaged (they may have
        // ⌘Esc'd out while the real terminal was still mounting).
        if (spikeEngaged && spikeLastEngagedPane == p.paneId) runCatching { p.term?.focus() }
    }
}

