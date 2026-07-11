/*
 * Split from World3DSpike.kt — front-pane zoom, grid resize, selection mode, measurement helpers.
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
 * Multiplies the front pane's *visual* zoom target by [ZOOM_STEP] (`+`) or its
 * reciprocal (`−`), clamped to [ZOOM_MIN]..[ZOOM_MAX]. Pure GPU scale (no reflow).
 * The result is remembered per pane in [spikeZoomByPane] so it survives navigating
 * away and back, and written through to the server ([WindowCommand.SetPaneZoom] →
 * `Pane.zoom`) so it also survives an app restart — the animation still runs off
 * the local map; the server copy only reseeds it on the next fresh run. No-op
 * unless a front pane is settled.
 *
 * @param step +1 = larger, -1 = smaller.
 */
internal fun zoomFront(step: Int) {
    val factor = if (step > 0) ZOOM_STEP else 1.0 / ZOOM_STEP
    zoomFrontTo(spikeZoomTarget * factor)
}

/**
 * Sets the front pane's visual zoom target to an **absolute** level, clamped to
 * [ZOOM_MIN]..[ZOOM_MAX] — the shared tail of every zoom shortcut: [zoomFront]
 * multiplies into it, the ⇧ presets ([zoomFrontFit], ⇧− min) jump straight to a
 * level. Same settle guard, per-pane memory ([spikeZoomByPane]) and server
 * write-through ([WindowCommand.SetPaneZoom]) as [zoomFront] has always had.
 * No-op unless a front pane is settled.
 *
 * @param target absolute zoom multiplier (1.0 = native), clamped before use.
 * @param glide true when the change should ease at the slow [ZOOM_PRESET_EASE]
 *   instead of the snappy [SCALE_EASE] — passed by the ⇧ presets, whose one-jump
 *   targets are far enough away that the normal ease reads as a snap
 *   ([spikeZoomGlide]).
 */
internal fun zoomFrontTo(target: Double, glide: Boolean = false) {
    val fi = frontIndex()
    if (spikeSettledIndex != fi || fi < 0) {
        // Same settle guard as growGridAxis — logged so a dead zoom key and a
        // dead grid key can be correlated to the shared cause in the console.
        console.warn(
            "[world3d-spike] zoom ignored: front pane not settled " +
                "(frontIndex=$fi settledIndex=$spikeSettledIndex)"
        )
        return
    }
    if (spikeSelectionMode) exitSelectionMode()
    spikeZoomTarget = target.coerceIn(ZOOM_MIN, ZOOM_MAX)
    spikeZoomGlide = glide
    spikePanes.getOrNull(fi)?.let {
        spikeZoomByPane[it.paneId] = spikeZoomTarget
        // Persist: rides the config broadcast + debounced DB write on the server.
        runCatching { launchCmd(WindowCommand.SetPaneZoom(it.paneId, spikeZoomTarget)) }
    }
}

/**
 * Zooms the front pane to the **largest level at which it still fits entirely
 * on screen** (`⇧+`), scaled by [ZOOM_FIT_MARGIN] so the border/glow stays a hair
 * inside the viewport rather than spilling off the edges. Glides at the slow
 * [ZOOM_PRESET_EASE] rather than snapping ([spikeZoomGlide]). No-op unless a front
 * pane is settled (guard lives in [zoomFrontTo]).
 *
 * **Why measure instead of model.** An earlier version fitted the pane's *layout*
 * box (`offsetWidth/Height`) against the viewport, trusting a theoretical
 * "1 plane px == 1 screen px × zoom" identity and assuming the pane sits dead
 * centre. In practice both drift: the CSS3D perspective projection isn't exactly
 * 1:1 (title strip, glow, camera-distance rounding), and — more importantly — the
 * pane's projected centre is usually a touch *above* screen centre, so a symmetric
 * fit grows the pane until its **bottom rows clip off** the lower edge before the
 * top ever reaches the ceiling. So instead we read the pane's *actual* rendered
 * on-screen box ([getBoundingClientRect], which already bakes in the real CSS3D
 * transform) and fit *that*, about the box's true centre.
 *
 * A pane magnifies about its own projected centre, so the span that has to fit on
 * each axis is twice the distance from that centre to the *nearer* viewport edge —
 * `2·min(c, size−c)`. Dividing that room by the box's current projected size gives
 * the scale headroom, and multiplying the scale the box was measured at
 * ([obj].scale, the value that produced this frame's rect) by that headroom yields
 * the zoom that just fits. Self-correcting for both off-centre placement and any
 * projection drift, so the bottom rows stay on screen. Clamped to [ZOOM_MAX] by
 * [zoomFrontTo] like every zoom.
 *
 * @see zoomFrontTo @see zoomFront
 */
internal fun zoomFrontFit() {
    val p = spikePanes.getOrNull(frontIndex()) ?: return
    // The pane's true on-screen box under the live CSS3D perspective transform — not
    // its flat layout box — so the fit reflects what the eye actually sees.
    val rect = p.wrapper.getBoundingClientRect()
    val projW = rect.width
    val projH = rect.height
    if (projW <= 0.0 || projH <= 0.0) return
    // The scale that produced this rect (eased object scale ≈ visual zoom for a
    // settled front pane). Guard a degenerate frame rather than dividing by zero.
    val s0 = p.obj.scale.x as Double
    if (s0 <= 0.0) return
    // The pane grows about its projected centre, so what must fit is the larger
    // half-span from that centre to the viewport edge on each axis. When the centre
    // sits off-screen-centre (typically a hair high), the nearer edge binds first —
    // this is what keeps the bottom rows from clipping.
    val cx = rect.left + projW / 2.0
    val cy = rect.top + projH / 2.0
    val availW = 2.0 * min(cx, window.innerWidth - cx)
    val availH = 2.0 * min(cy, window.innerHeight - cy)
    if (availW <= 0.0 || availH <= 0.0) return
    // Headroom on the tighter axis × [ZOOM_FIT_MARGIN] slack, applied to the scale
    // the rect was measured at → the zoom that just fits inside the viewport.
    val headroom = min(availW / projW, availH / projH) * ZOOM_FIT_MARGIN
    zoomFrontTo(s0 * headroom, glide = true)
}

/**
 * Grows/shrinks the front pane's grid **width** (`,` / `.`) by [GRID_COLS_STEP]
 * columns. Unlike [zoomFront] (a pure GPU magnify), a grid step genuinely
 * adds/removes columns: the terminal, the pane's plane, and the shared PTY all
 * change together ([setPaneGrid]) — the pane visibly grows or shrinks by exactly
 * that many cells, gliding to the new box over [GRID_ANIM_MS]. No per-pane memory
 * is needed: the PTY itself remembers its size. No-op unless a front pane is
 * settled.
 *
 * @param step +1 = wider, -1 = narrower.
 */
internal fun growGridW(step: Int) = growGridAxis(step * GRID_COLS_STEP, 0)

/**
 * Grows/shrinks the front pane's grid **height** (`<` / `>`) by [GRID_ROWS_STEP]
 * rows — the row-axis twin of [growGridW].
 *
 * @param step +1 = taller, -1 = shorter.
 */
internal fun growGridH(step: Int) = growGridAxis(0, step * GRID_ROWS_STEP)

/**
 * Maps a signed **cell** delta ([dCols]/[dRows], as the grid keys emit) to a signed
 * **pixel** box delta of magnitude [step] on the same axis — the translation the
 * non-terminal resize path applies so `,`/`.`/`<`/`>` resize a git/file-browser
 * plane by geometry (see [resizePaneBox]) rather than by cells it doesn't have. A
 * zero cell delta stays zero (that axis is untouched); only the sign of [cell] is
 * used, not its magnitude, so one key press is one box step.
 *
 * @param cell the signed cell delta on this axis (`+`/`0`/`−`).
 * @param step the box step magnitude in px for this axis ([PANE_BOX_W_STEP] / [PANE_BOX_H_STEP]).
 * @return `+step`, `0`, or `−step`. @see resizePaneBox @see growGridAxis
 */
private fun boxStep(cell: Int, step: Int): Int = when {
    cell > 0 -> step
    cell < 0 -> -step
    else -> 0
}

/**
 * Shared body for [growGridW]/[growGridH]: applies a signed **cell delta** to the
 * front pane's current grid, clamped to the sane bounds, through [setPaneGrid]
 * (which resizes the terminal, re-presents the plane, and force-resizes the PTY).
 * Leaves selection mode first (a resize would otherwise fight the 1:1 snap).
 *
 * @param dCols signed column delta. @param dRows signed row delta.
 * @see setPaneGrid
 */
internal fun growGridAxis(dCols: Int, dRows: Int) {
    val fi = frontIndex()
    // Every silent no-op below logs its reason: a grid key that flashes the
    // legend but visibly does nothing is otherwise undiagnosable (the legend
    // flash happens in the key handler, before any of these guards run).
    if (spikeSettledIndex != fi || fi < 0) {
        console.warn(
            "[world3d-spike] grid resize ignored: front pane not settled " +
                "(frontIndex=$fi settledIndex=$spikeSettledIndex)"
        )
        return
    }
    val p = spikePanes.getOrNull(fi)
    if (p == null) {
        console.warn("[world3d-spike] grid resize ignored: no pane at front index $fi (${spikePanes.size} panes)")
        return
    }
    val term = p.term
    if (term == null) {
        // Non-terminal (git / file-browser) pane: no cell grid to resize, so nudge the
        // plane box directly in pixels — the same keys resize *any* pane, just like the
        // 2D world. The signed cell delta becomes a signed box delta on the same axis.
        if (spikeSelectionMode) exitSelectionMode()
        val dW = boxStep(dCols, PANE_BOX_W_STEP)
        val dH = boxStep(dRows, PANE_BOX_H_STEP)
        if (!resizePaneBox(p, dW, dH)) {
            console.warn(
                "[world3d-spike] box resize ignored: pane ${p.paneId} already clamped at bounds " +
                    "(${p.baseCw}x${p.baseCh}, delta $dW,$dH)"
            )
        } else {
            console.log("[world3d-spike] box key: pane ${p.paneId} -> ${p.baseCw}x${p.baseCh}")
        }
        return
    }
    if (spikeSelectionMode) exitSelectionMode()
    val cols = (term.cols + dCols).coerceIn(GRID_MIN_COLS, GRID_MAX_COLS)
    val rows = (term.rows + dRows).coerceIn(GRID_MIN_ROWS, GRID_MAX_ROWS)
    if (cols == term.cols && rows == term.rows) {
        console.warn(
            "[world3d-spike] grid resize ignored: already clamped at bounds " +
                "(${term.cols}x${term.rows}, delta $dCols,$dRows)"
        )
        return
    }
    console.log("[world3d-spike] grid key: pane ${p.paneId} ${term.cols}x${term.rows} -> ${cols}x$rows")
    setPaneGrid(p, cols, rows, reassert = true)
}

/**
 * **Free-flight zoom** — the `+`/`−` step-zoom applied to the pane **nearest the camera**
 * (the free-flight counterpart of [zoomFront], which acts on the command center's front
 * pane). Same per-pane memory ([spikeZoomByPane]) and server write-through as the front
 * zoom; the magnification is still an in-place GPU scale about the pane's own centre (it
 * does not zoom "toward" the camera). @see zoomNearestTo @see nearestPaneToCamera
 *
 * @param step +1 = larger, -1 = smaller.
 */
internal fun zoomNearest(step: Int) {
    val p = actionTargetPane() ?: return
    val factor = if (step > 0) ZOOM_STEP else 1.0 / ZOOM_STEP
    zoomNearestTo(p, (spikeZoomByPane[p.paneId] ?: 1.0) * factor)
}

/** **Free-flight `0`** — resets the centre pane's zoom to 1×. @see zoomNearest */
internal fun resetNearestZoom() {
    actionTargetPane()?.let { zoomNearestTo(it, 1.0, glide = true) }
}

/**
 * Sets pane [p]'s visual zoom to an absolute [target] (clamped), remembering it in
 * [spikeZoomByPane] and writing it through to the server — the shared tail of the
 * free-flight zoom shortcuts. When [p] is also the command center's front pane, the
 * front-pane accumulator/ease ([spikeZoomTarget]/[spikeZoomGlide]) is kept in sync,
 * since the render loop reads those for `i == fi`.
 *
 * @param p the pane to zoom. @param target absolute multiplier (1.0 = native).
 * @param glide ease at the slow preset rate rather than snapping.
 */
private fun zoomNearestTo(p: RingPane, target: Double, glide: Boolean = false) {
    if (spikeSelectionMode) exitSelectionMode()
    val lvl = target.coerceIn(ZOOM_MIN, ZOOM_MAX)
    spikeZoomByPane[p.paneId] = lvl
    if (spikePanes.indexOf(p) == frontIndex()) { spikeZoomTarget = lvl; spikeZoomGlide = glide }
    runCatching { launchCmd(WindowCommand.SetPaneZoom(p.paneId, lvl)) }
}

/** **Free-flight `,`/`.`** — grow/shrink the nearest pane's grid width. @see growGridW */
internal fun gridNearestW(step: Int) = gridNearestAxis(step * GRID_COLS_STEP, 0)

/** **Free-flight `<`/`>`** — grow/shrink the nearest pane's grid height. @see growGridH */
internal fun gridNearestH(step: Int) = gridNearestAxis(0, step * GRID_ROWS_STEP)

/**
 * Applies a signed cell delta to the pane **nearest the camera** (free-flight grid
 * resize), the counterpart of [growGridAxis] for the front pane. Clamped to the same
 * bounds; no settle guard (the ring is static in flight). No-op if the nearest pane has
 * no terminal or the resize would be a clamped no-change.
 *
 * @param dCols signed column delta. @param dRows signed row delta.
 */
private fun gridNearestAxis(dCols: Int, dRows: Int) {
    val p = actionTargetPane() ?: return
    val term = p.term
    if (term == null) {
        // Non-terminal pane in free-flight: resize its plane box directly (see the
        // front-pane branch in [growGridAxis]) so the resize keys work for any pane.
        if (spikeSelectionMode) exitSelectionMode()
        resizePaneBox(p, boxStep(dCols, PANE_BOX_W_STEP), boxStep(dRows, PANE_BOX_H_STEP))
        return
    }
    if (spikeSelectionMode) exitSelectionMode()
    val cols = (term.cols + dCols).coerceIn(GRID_MIN_COLS, GRID_MAX_COLS)
    val rows = (term.rows + dRows).coerceIn(GRID_MIN_ROWS, GRID_MAX_ROWS)
    if (cols == term.cols && rows == term.rows) return
    setPaneGrid(p, cols, rows, reassert = true)
}

/** Toggles selection mode on the front pane (⌥⌘S). */
internal fun toggleSelectionMode() {
    if (spikeSelectionMode) exitSelectionMode() else enterSelectionMode()
}

/**
 * Enters selection mode. This is now a **pure render-scale toggle**: the render loop
 * snaps the front pane to CSS3D scale 1.0 (true 1:1, so xterm's mouse→cell math is
 * pixel-accurate for drag-select) and leaves the terminal's font, grid, and box
 * completely untouched. Nothing to bake, so [exitSelectionMode] restores everything
 * exactly by simply resuming the pane's zoom — the earlier font-rebake that shifted
 * the grid and didn't cleanly restore is gone. No-op unless a front pane is settled.
 */
internal fun enterSelectionMode() {
    if (spikeSelectionMode) return
    if (spikeSettledIndex != frontIndex()) return
    spikeSelectionMode = true
    updateModeBadge()
}

/** Leaves selection mode: the render loop simply resumes the pane's zoom. */
internal fun exitSelectionMode() {
    if (!spikeSelectionMode) return
    spikeSelectionMode = false
    updateModeBadge()
}

/** Fades the "SELECT MODE" badge in/out to match [spikeSelectionMode]. */
internal fun updateModeBadge() {
    spikeModeBadge?.style?.setProperty("opacity", if (spikeSelectionMode) "1" else "0")
}

/**
 * Runs [block] with pane [p]'s CSS3D transform momentarily neutralised, so any DOM
 * measurement inside reads **layout px** (not screen px scaled by the pane's zoom) —
 * without this, `getBoundingClientRect`-based reads are multiplied by the CSS3D
 * scale. The render loop re-applies the real transform on the very next frame
 * (before any paint), so this is invisible. Under the PTY-truth sizing model
 * (see [presentPaneToGrid]) the grid paths no longer measure the DOM, so this is
 * only needed by callers that still read rendered geometry.
 *
 * @param p the pane. @param block the measure/fit work to run unscaled.
 * @return whatever [block] returns.
 */
internal fun <T> measureUnscaled(p: RingPane, block: () -> T): T {
    val prev = p.wrapper.style.transform
    p.wrapper.style.transform = "none"
    return try { block() } finally { p.wrapper.style.transform = prev }
}
