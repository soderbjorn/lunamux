/**
 * Freeze-to-canvas for stashing panes — turning a pane in *flight* to / from the stash
 * shelf into a single cached raster instead of live, re-rasterizing DOM.
 *
 * ## Why
 * The spike world is pure CSS3D: every pane's body is a live xterm.js DOM subtree
 * (rows of `<span>`, no canvas), transformed in 3D. While a pane flies to or from the
 * shelf — individually ([RingPane.stashProg] easing between 0 and 1) or as a member of a
 * [TabBundle] (the whole tab flying as one merged stack) — the render loop rewrites its
 * `matrix3d` **every frame**, forcing the browser to re-rasterize that live DOM (and its
 * blurred chrome) each frame. Several large panes doing this at once is a direct
 * contributor to Chromium's *"tile memory limits exceeded"* warning (the compositor tile
 * budget is exceeded and planes flash transparent).
 *
 * ## What
 * The moment a pane enters stash motion, [freezePaneSnapshot] does two things so the moving
 * sheet becomes a stable layer the compositor can re-sample rather than re-paint:
 *  1. Paints the terminal grid once into a static `<canvas>` (via [renderTerminalGrid])
 *     sized to the terminal box, laid over the (now `visibility:hidden`) live
 *     [RingPane.container] — the per-frame *text* raster is gone.
 *  2. Promotes the wrapper with `will-change: transform`, so Chromium keeps each flying
 *     sheet on its own composited layer — rasterized **once** (blurred drop-shadow and all)
 *     and then merely re-composited under the changing matrix, instead of re-painted every
 *     frame. This is the crucial one for **bundle** flights: a [TabBundle] spins and pitches
 *     its sheets every frame (a lone pane does not), and without a stable layer that rotation
 *     forces a full repaint of each sheet — including its blurred shadow — every frame, worst
 *     when the N stacked sheets are largest just off the command center. Promotion is why the
 *     spin becomes cheap; it is also **visually inert** — every glow, reactor ring, halo and
 *     border is preserved exactly (see the note in [freezePaneSnapshot] on why we deliberately
 *     do NOT strip the `box-shadow`: it is the reactor's outward halo, owned by [tickWarpCore],
 *     and stripping it would pop the glow off at take-off and back on at landing).
 *
 * On arrival at rest (parked on the shelf, or re-seated on the ring) [thawPaneSnapshot]
 * removes the canvas and the layer hint and reveals the live body again, which kept updating
 * underneath, so nothing is stale.
 *
 * The single entry point [tickPaneFreeze] is called once per frame from the render loop
 * *after* [tickBundles]/[tickWormhole] have settled every pane's state, so one idempotent
 * pass — driven by the pure predicate [paneInStashFlight] — decides freeze vs. thaw for
 * every pane. Single-sourcing the decision this way is essential: during a bundle
 * bring-back both the per-pane render loop and [tickBundles] touch the same panes, and two
 * independent freeze/thaw decisions would churn the snapshot (repaint every frame),
 * defeating the purpose.
 *
 * Only [PaneKind.TERMINAL] panes are frozen — git / file-browser panes carry arbitrary
 * live DOM with no terminal grid to repaint, and are a minority, so they simply keep flying
 * live. The title bar stays live too (it is cheap static text); only the terminal body is
 * swapped.
 *
 * ## Feature flags
 *  - [SPIKE_FLIGHT_FREEZE_ENABLED] — master switch. When `false` (default) nothing is ever
 *    frozen and any lingering snapshot is thawed, so the whole feature is off.
 *  - [SPIKE_FREEZE_PARKED_ENABLED] — when `true` (and the master is on) also freezes panes
 *    *sitting* at the stash site (a parked bundle's whole stack, a rested shelf pane). Off by
 *    default, so parked panes paint live; the flight is frozen either way.
 *
 * @see tickPaneFreeze
 * @see World3DSpikeConfig for the tile-memory background this mitigates.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

/**
 * Process-wide terminal palette used to paint every freeze snapshot. Resolved lazily from
 * the live theme on first freeze and cached — the theme is stable for the session, so
 * rebuilding it per snapshot would be wasted work. @see buildTermPalette
 */
private var spikeFreezePalette: TermPalette? = null

/**
 * Whether [p] is currently *flying* to or from the stash shelf — the sole condition under
 * which its body is frozen. Pure (no side effects) so the same answer can be read anywhere.
 *
 * A bundled pane is in flight whenever its [TabBundle] is not [BundleState.PARKED]
 * (MERGING / FLYING_UP / FLYING_DOWN / SEPARATING all move the stack). A lone pane is in
 * flight while its [RingPane.stashProg] has not yet reached its target — `1.0` when it is a
 * member of [spikeStashed] (flying up / resting on the shelf), else `0.0` (flying home /
 * resting on the ring); equality means *at rest*.
 *
 * This covers only the *journey*. Panes *sitting* at the stash site (a parked bundle, or a
 * lone pane rested on the shelf) return `false` here — [tickPaneFreeze] freezes those only when
 * [SPIKE_FREEZE_PARKED_ENABLED] opts in (via [paneParkedAtStash]); by default they paint live.
 *
 * @param p the pane to test.
 * @return `true` while the pane is mid-flight (freeze), `false` at rest — on the ring OR parked
 *   at the stash site.
 * @see tickPaneFreeze @see paneParkedAtStash
 */
private fun paneInStashFlight(p: RingPane): Boolean {
    val b = bundleById(p.bundleId)
    if (b != null) return b.state != BundleState.PARKED
    val target = if (p.paneId in spikeStashed) 1.0 else 0.0
    return p.stashProg != target
}

/**
 * Whether [p] is *sitting at rest at the stash site* — a member of a parked tab bundle
 * ([BundleState.PARKED]), or a lone pane that has finished flying up and rests on the shelf
 * (in [spikeStashed] with [RingPane.stashProg] at its clamped `1.0`). Distinct from at-rest
 * *on the ring* (home), which is never a freeze candidate. Pure; mutually exclusive with
 * [paneInStashFlight].
 *
 * Only consulted by [tickPaneFreeze] when [SPIKE_FREEZE_PARKED_ENABLED] is on — that is the
 * single switch deciding whether the whole stacked pile of a stashed tab group (every sheet,
 * including the occluded ones behind the front) is held as a static snapshot or painted live.
 *
 * @param p the pane to test.
 * @return `true` when parked at the stash site, `false` otherwise (in flight, or home on the ring).
 * @see tickPaneFreeze @see paneInStashFlight
 */
private fun paneParkedAtStash(p: RingPane): Boolean {
    val b = bundleById(p.bundleId)
    if (b != null) return b.state == BundleState.PARKED
    return p.paneId in spikeStashed && p.stashProg >= 1.0
}

/**
 * One idempotent freeze/thaw pass over every pane, called once per frame from the render
 * loop (after [tickBundles] and [tickWormhole], so all pane state is final for the frame).
 * Freezes each pane that [paneInStashFlight] reports mid-flight and thaws every other, so a
 * pane holds exactly one snapshot for the whole journey and reverts to live at rest.
 *
 * Both [freezePaneSnapshot] and [thawPaneSnapshot] no-op when already in the requested
 * state, so calling this for every pane every frame is cheap.
 *
 * @see paneInStashFlight
 */
internal fun tickPaneFreeze() {
    // Feature-flagged off: never freeze, and thaw anything still frozen so flipping the flag
    // off mid-run cleanly restores every pane to its live body. @see SPIKE_FLIGHT_FREEZE_ENABLED
    if (!SPIKE_FLIGHT_FREEZE_ENABLED) {
        for (p in spikePanes) thawPaneSnapshot(p)
        return
    }
    for (p in spikePanes) {
        // Always freeze the journey; freeze the destination (parked stack / shelf) only when
        // the parked flag opts in — off by default, so every parked pane paints live.
        val freeze = paneInStashFlight(p) ||
            (SPIKE_FREEZE_PARKED_ENABLED && paneParkedAtStash(p))
        if (freeze) freezePaneSnapshot(p) else thawPaneSnapshot(p)
    }
}

/**
 * Paints [p]'s terminal grid once into a static `<canvas>` over the terminal body, hides the
 * live [RingPane.container], and promotes the wrapper to its own composited layer for the
 * flight (see the file header for the rationale). No-op if the pane is already frozen
 * ([RingPane.freezeCanvas] set), is not a [PaneKind.TERMINAL] pane, or has no terminal /
 * zero size yet.
 *
 * The snapshot canvas is sized to the terminal box ([RingPane.baseCw] × [RingPane.baseCh])
 * at a 1:1 (DPR-1) backing store — a flying, tilted pane needs no Retina crispness, and a
 * single low-resolution raster is exactly the tile saving we want. It is positioned to sit
 * under the title bar ([TITLE_H]) at the same place the live container occupies.
 *
 * Deliberately non-destructive to the pane's chrome: the wrapper's `box-shadow` is left
 * untouched because it doubles as the reactor's outward halo (composed each frame by
 * [tickWarpCore] for live panes), and the `dim` / `glow` / `border` overlays are left
 * untouched too — stripping any of them would pop a glow/halo off at take-off and back on at
 * landing. `will-change: transform` gets the sheet a stable layer that rasterizes all of that
 * chrome once, so the spin stays cheap **without** removing anything.
 *
 * @param p the pane to freeze; mutated ([RingPane.freezeCanvas], [container] visibility, and
 *   the wrapper's `will-change` hint).
 * @see tickPaneFreeze @see thawPaneSnapshot @see renderTerminalGrid
 */
private fun freezePaneSnapshot(p: RingPane) {
    if (p.freezeCanvas != null) return
    if (p.kind != PaneKind.TERMINAL) return
    val term = p.term ?: return
    val w = p.baseCw
    val h = p.baseCh
    if (w <= 0 || h <= 0) return

    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = w
    canvas.height = h
    val palette = spikeFreezePalette ?: buildTermPalette().also { spikeFreezePalette = it }
    // A disposing / not-yet-open term can throw while its buffer is read — never let a
    // snapshot failure break the frame; fall back to leaving the live body shown.
    val painted = runCatching {
        renderTerminalGrid(canvas, term, palette, showCursor = false, topInsetPx = 0.0, padPx = 0.0)
    }.isSuccess
    if (!painted) return

    canvas.style.cssText =
        "position:absolute;left:0;top:${TITLE_H}px;width:${w}px;height:${h}px;pointer-events:none;"
    p.container.style.visibility = "hidden"
    p.wrapper.appendChild(canvas)
    p.freezeCanvas = canvas

    // Promote to a stable composited layer so the reveal-spin re-composites a cached raster
    // instead of repainting the sheet (blurred shadow / reactor halo included) every frame.
    // NB: NOT backface-visibility:hidden — the bundle reveal-spin turns a full 360° so each
    // sheet's (mirrored) back is meant to show mid-flight; hiding it would blink the pane out.
    p.wrapper.style.setProperty("will-change", "transform")
}

/**
 * Removes [p]'s freeze snapshot (if any), reveals the live terminal body, and drops the
 * `will-change` layer hint. The live [RingPane.container] kept updating while hidden, so it
 * shows current content on thaw. No-op when the pane is not frozen.
 *
 * @param p the pane to thaw; mutated ([RingPane.freezeCanvas], [container] visibility, and
 *   the wrapper's `will-change` hint).
 * @see tickPaneFreeze @see freezePaneSnapshot
 */
private fun thawPaneSnapshot(p: RingPane) {
    val canvas = p.freezeCanvas ?: return
    runCatching { p.wrapper.removeChild(canvas) }
    p.container.style.visibility = ""
    p.freezeCanvas = null
    p.wrapper.style.removeProperty("will-change")
}
