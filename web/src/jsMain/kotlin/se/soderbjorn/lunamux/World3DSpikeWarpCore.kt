/**
 * The **warp-core charge + awaiting-input (HOLD)** cinematic — the [spikeStatusIndication]
 * runtime toggle (the `p` key), a layer-independent alternative to the working
 * dots/green-glow ([spikeStatusIndication]) and the red "needs input" halo ([WAITING_GLOW_COLOR]).
 * It is driven entirely off the agent status the app already knows (`working` / `waiting`
 * from `lunamuxClient.windowState.states`, respecting the manual [spikeWorkingOverride] /
 * [spikeWaitingOverride] test maps) — no activity heuristics, no terminal-output parsing.
 *
 * Two per-pane states, in the same spirit as the phaser-fire close and the wormhole spawn:
 *
 *  1. **Warp-core charge** — a pane whose agent is `working` glows like a charging reactor:
 *     a slow ~1.5s spool-up ([WARP_ATTACK], so brief blips never visibly charge), a soft
 *     blue outward halo hugging the pane that breathes and flickers a touch brighter on
 *     output ([RingPane.warpFlicker]), and on the `working → not` edge — if it charged past
 *     [WARP_MIN_DISCHARGE] — a bright **discharge** bloom plus a **thruster plume** off the
 *     bottom edge (cyan success; orange sputter if it failed, [RingPane.dischargeFail]).
 *  2. **Awaiting input (HOLD)** — a pane that is `waiting` (agent blocked on you) idles
 *     **amber**, with a slow, calm heartbeat that escalates *slightly* the longer it waits,
 *     **sonar pings** radiating out past the pane so you catch it in peripheral vision, and
 *     it stays **fully lit** (no dim veil) — all without changing the pane's size or place.
 *
 * A collective **reactor load** (summed ring charge) faintly warms the sky (gated on the
 * toggle). Two top-left **status pills** — shown *independently* of the toggle, and of each
 * other — report how many agents await your answer (amber) and how many are running (blue);
 * each pill disappears when its count is zero.
 *
 * **Layering, and why screen-space:** the pane wrapper is `overflow:hidden`, so anything
 * that must bleed *past* the pane edge cannot be a DOM child of it. The outward reactor
 * **halo** is therefore a `box-shadow` (never clipped — exactly the mechanic the working
 * green / waiting red halos use), and the **discharge bloom, thruster plume and sonar
 * pings** are painted in screen space on a full-viewport 2D canvas ([spikeWarpCanvas],
 * z-index just above the CSS3D pane layer), each burning/hailing pane projected to the
 * screen with the shared camera — the same approach [World3DSpikePhaser] uses for its
 * bolts. Only the **inner heat** veil ([RingPane.warpHeat]) rides inside the wrapper (it
 * stays within the box, like [phaserTint]).
 *
 * Wired in two places, both gated on [spikeStatusIndication]: [tickWarpCore] runs per pane
 * inside the render loop (owning that pane's charge, veils, border, halo and lean, and
 * scheduling its pings), and [tickWarpCoreOverlay] runs once after `css.render` (painting
 * the screen-space FX and updating the HUD). Torn down on close via [clearWarpCore].
 *
 * @see spikeStatusIndication
 * @see syncWorld3dRuntimeFromSettings
 * @see tickWarpCore
 * @see tickWarpCoreOverlay
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.three.PerspectiveCamera

/**
 * One in-flight **sonar ping** — an amber ring expanding out past its hailing pane, drawn
 * in screen space each frame by [tickWarpCoreOverlay] and culled once [age] passes
 * [WARP_PING_LIFE_S]. Kept as a flat list rather than DOM elements because the pane wrapper
 * is `overflow:hidden` and would clip a DOM ring the instant it grew past the edge.
 *
 * @property paneId the hailing pane this ping radiates from (its live projection is the
 *   ring centre, so the ping tracks the pane even as it bobs / leans).
 * @property age elapsed seconds since the ping was emitted; drives its radius and fade.
 * @see spawnWarpPing @see tickWarpCoreOverlay
 */
internal class WarpPing(
    val paneId: String,
    var age: Double,
)

/**
 * The full-viewport 2D canvas the warp-core effect paints its discharge blooms, thruster
 * plumes and sonar pings onto, or `null` until first armed. A child of [spikeOverlay], so
 * it is discarded wholesale on [closeWorld3dSpike]; its reference is nulled by [clearWarpCore].
 * @see ensureWarpCanvas
 */
internal var spikeWarpCanvas: HTMLCanvasElement? = null

/** Every sonar ping currently radiating, advanced/drawn/culled each frame by [tickWarpCoreOverlay]. */
internal val spikeWarpPings: MutableList<WarpPing> = mutableListOf()

/** The faint warm **sky tint** overlay (behind the CSS3D scene) whose opacity tracks reactor load; `null` until built. @see ensureWarpHud */
internal var spikeWarpSkyTint: HTMLElement? = null

/**
 * The top-left **status row** holding the two independent count pills side by side; `null`
 * until built. Built + updated **independently of [spikeStatusIndication]** — the world always
 * shows how many agents want you and how many are running. @see ensureWarpBanner @see updateWarpBanner
 */
internal var spikeWarpBannerRow: HTMLElement? = null

/** The amber **"N awaiting your answer"** pill; shown only while any agent awaits you, else hidden. @see updateWarpBanner */
internal var spikeWarpAwaitPill: HTMLElement? = null

/** The blue **"M working"** pill; shown only while any agent is running, else hidden. @see updateWarpBanner */
internal var spikeWarpWorkPill: HTMLElement? = null

/** Pre-built inner-heat gradient for the amber HOLD state — constant, so cached here to skip a per-frame re-parse. @see tickWarpCore */
private val WARP_HEAT_AMBER_BG = warpHeatGradient(WARP_AMBER_COLOR)

/** Pre-built inner-heat gradient for the blue charge state — constant, so cached here to skip a per-frame re-parse. @see tickWarpCore */
private val WARP_HEAT_BLUE_BG = warpHeatGradient(WARP_CORE_COLOR)

/**
 * Snaps a halo alpha onto the [WARP_GLOW_ALPHA_STEP] grid so [tickWarpCore] can dedupe the
 * outward-glow `box-shadow` string frame-to-frame — a blurred shadow re-rasterizes on **any**
 * change, and the glow's alpha pulses on a slow sine that is visually identical across several
 * frames. Returns [a] unchanged when the step is `0.0` (quantization disabled / revert path).
 *
 * @param a the raw halo alpha (0..1). @return the alpha snapped to the quantization grid.
 * @see setWarpShadow @see WARP_GLOW_ALPHA_STEP
 */
private fun quantizeWarpAlpha(a: Double): Double =
    if (WARP_GLOW_ALPHA_STEP > 0.0) kotlin.math.round(a / WARP_GLOW_ALPHA_STEP) * WARP_GLOW_ALPHA_STEP else a

/**
 * Writes the reactor **border colour** onto the pane wrapper only when it differs from the
 * last value ([RingPane.lastBorderCol]) — a held amber pane keeps a constant border, so most
 * frames skip the redundant style recalc; the blue charge tint still writes as it ramps.
 *
 * @param p the pane. @param col the border colour to apply. @see tickWarpCore
 */
private fun setWarpBorder(p: RingPane, col: String) {
    if (col != p.lastBorderCol) {
        p.wrapper.style.setProperty("border-color", col)
        p.lastBorderCol = col
    }
}

/**
 * Writes the outward-halo **box-shadow** onto the pane wrapper only when the (alpha-quantized)
 * string differs from the last one ([RingPane.lastShadowKey]). A blurred `box-shadow` cannot
 * composite, so every string change re-rasterizes the whole halo; skipping unchanged writes is
 * the point of the [quantizeWarpAlpha] snapping.
 *
 * @param p the pane. @param shadow the fully-built box-shadow string (alpha already quantized).
 * @see tickWarpCore @see quantizeWarpAlpha
 */
private fun setWarpShadow(p: RingPane, shadow: String) {
    if (shadow != p.lastShadowKey) {
        p.wrapper.style.setProperty("box-shadow", shadow)
        p.lastShadowKey = shadow
    }
}

/**
 * Per-pane driver of the warp-core charge / awaiting HOLD, called from the render loop's
 * per-pane pass **only when [spikeStatusIndication]** — right after this pane's live
 * `working` / `waiting` state is resolved. It advances the pane's charge, drives its inner
 * heat veil, outward halo (`box-shadow`) and border colour, schedules sonar pings while it
 * holds, and — for a `waiting` pane — forces it fully lit (no dim veil, full opacity) so a
 * hailing agent never fades into the ring. Discharge/ping/HUD *painting* happens later, in
 * [tickWarpCoreOverlay].
 *
 * @param p the pane. @param phaseIdx the pane's ring index, offsetting its breath phase so
 *   neighbours don't pulse in lock-step. @param working whether its agent is running.
 * @param waiting whether its agent is blocked on you (needs input). @param edgeCol the
 *   pane's resting/lit border colour, blended toward the reactor colour as it charges.
 * @see tickWarpCoreOverlay @see spikeStatusIndication
 */
internal fun tickWarpCore(p: RingPane, phaseIdx: Int, working: Boolean, waiting: Boolean, edgeCol: String) {
    val now = spikeWarpClock
    ensureWarpHeat(p)
    ensureWarpCore(p)
    // On a **light** theme the pane surface is bright, and a `screen`-blended ring lightens
    // toward white → invisible. Switch the ring + heat to `multiply` there (darkens toward
    // the reactor colour, so the ring reads as a bright coloured ring on the light pane).
    val ringBlend = if (warpLightSurface()) "multiply" else "screen"
    // PERF: mix-blend-mode changes only on a theme surface flip — apply it to both layers only
    // then, instead of re-setting the same value on every pane every frame. @see RingPane.lastRingBlend
    if (ringBlend != p.lastRingBlend) {
        p.warpCore?.let { it.style.setProperty("mix-blend-mode", ringBlend) }
        p.warpHeat?.let { it.style.setProperty("mix-blend-mode", ringBlend) }
        p.lastRingBlend = ringBlend
    }

    // --- charge dynamics (per-60fps-frame rates scaled by spikeDtFrames) ---------------
    if (waiting) {
        // Held at station: the reactor idles warm, no drain. Bank the entry time (for the
        // heartbeat + ping escalation) and give it enough warmth for the amber to read.
        if (p.warpAwaitStart < 0.0) {
            p.warpAwaitStart = now
            p.warpNextPing = now
            p.chargeProg = maxOf(p.chargeProg, 0.55)
        }
        val esc = ((now - p.warpAwaitStart) / WARP_HOLD_ESC_S).coerceIn(0.0, 1.0)
        // Sonar pings radiate faster the longer the agent has been kept waiting.
        if (now >= p.warpNextPing) {
            spawnWarpPing(p)
            p.warpNextPing = now + WARP_PING_MAX_S - esc * (WARP_PING_MAX_S - WARP_PING_MIN_S)
        }
    } else {
        p.warpAwaitStart = -1.0
        if (working) {
            p.chargeProg += (1.0 - p.chargeProg) * WARP_ATTACK * spikeDtFrames
        } else if (p.dischargePhase < 0.0) {
            p.chargeProg += (0.0 - p.chargeProg) * WARP_COOLDOWN * spikeDtFrames
            if (p.chargeProg < 0.003) p.chargeProg = 0.0
        }
    }
    p.warpFlicker *= WARP_FLICKER_DECAY.pow(spikeDtFrames)

    // --- discharge trigger on the working → not-working falling edge --------------------
    if (p.warpWasWorking && !working && !waiting && p.dischargePhase < 0.0 && p.chargeProg >= WARP_MIN_DISCHARGE) {
        p.dischargePhase = 0.0
        // The `working` status is agent-level and carries no exit code today, so a discharge
        // defaults to the success (cyan) look; the orange fail sputter awaits OSC 133 parsing.
        p.dischargeFail = false
    }
    p.warpWasWorking = working
    if (p.dischargePhase >= 0.0) {
        p.dischargePhase += spikeDtFrames / 60.0
        p.chargeProg *= WARP_DRAIN.pow(spikeDtFrames)
        if (p.dischargePhase > WARP_DISCHARGE_S) p.dischargePhase = -1.0
    }

    // --- per-pane veils: inner heat + outward halo + border -----------------------------
    if (waiting) {
        // AWAITING: amber "held" reactor, a slow calm heartbeat (NOT a strobe) escalating
        // slightly the longer it waits. Small swing, mostly steady.
        val esc = ((now - p.warpAwaitStart) / WARP_HOLD_ESC_S).coerceIn(0.0, 1.0)
        val pulse = 0.5 + 0.5 * sin(now * (WARP_HOLD_FREQ + esc * WARP_HOLD_ESC_FREQ))
        val inten = (WARP_HOLD_INTEN + WARP_HOLD_ESC_INTEN * esc) * (0.78 + 0.22 * pulse)
        p.warpCore?.let {
            // PERF: the ring gradient is constant — assign it only on change (a re-parse invalidates the layer).
            if (WARP_RING_AMBER_ACTIVE != p.lastRingBg) { it.style.background = WARP_RING_AMBER_ACTIVE; p.lastRingBg = WARP_RING_AMBER_ACTIVE }
            it.style.opacity = inten.toString()
            it.style.transform = "scale(${0.7 + inten * 0.42})"
        }
        p.warpHeat?.let {
            if (WARP_HEAT_AMBER_BG != p.lastHeatBg) { it.style.background = WARP_HEAT_AMBER_BG; p.lastHeatBg = WARP_HEAT_AMBER_BG }
            it.style.opacity = (inten * 0.4).toString()
        }
        setWarpBorder(p, WARP_AMBER_HEX)
        // PERF: pin the halo blur radius (charge reads through the pulsing alpha) instead of
        // growing it every frame — see [WARP_GLOW_BLUR_PINNED] for the revert switch.
        val amberBlur = if (WARP_GLOW_BLUR_PINNED) WARP_GLOW_BLUR + WARP_GLOW_BLUR_PINNED_ADD
        else WARP_GLOW_BLUR + inten * WARP_GLOW_BLUR_GROW_AMBER
        // PERF: quantize the halo alpha + skip the write when the string is unchanged (see
        // [WARP_GLOW_ALPHA_STEP]) — a blurred box-shadow re-rasterizes on any change.
        setWarpShadow(
            p,
            "$PANE_BASE_SHADOW, 0 0 ${amberBlur}px ${WARP_GLOW_SPREAD}px " +
                "rgba($WARP_AMBER_COLOR,${quantizeWarpAlpha(inten * 0.9)})",
        )
        // A hailing pane never fades or dims — stay fully lit so you can't miss it. (Full
        // opacity also keeps its preserve-3d children painting; see the implementation prompt.)
        p.wrapper.style.setProperty("opacity", "1")
        p.wrapper.style.setProperty("display", "")
        p.dim.style.opacity = "0"
    } else {
        // CHARGING / idle / discharging: blue reactor breathing at the calm hum cadence,
        // flickering a touch brighter on output activity.
        val breath = WARP_BREATH_AMP * sin(now * WARP_BREATH_SPEED + phaseIdx * BOB_STAGGER)
        val intensity = (p.chargeProg * (0.9 + breath) + p.warpFlicker * 0.25 * p.chargeProg).coerceIn(0.0, 1.0)
        p.warpCore?.let {
            // PERF: the ring gradient is constant — assign it only on change (a re-parse invalidates the layer).
            if (WARP_RING_BLUE_ACTIVE != p.lastRingBg) { it.style.background = WARP_RING_BLUE_ACTIVE; p.lastRingBg = WARP_RING_BLUE_ACTIVE }
            it.style.opacity = minOf(1.0, intensity * 0.95).toString()
            it.style.transform = "scale(${0.66 + intensity * 0.4})"
        }
        p.warpHeat?.let {
            if (WARP_HEAT_BLUE_BG != p.lastHeatBg) { it.style.background = WARP_HEAT_BLUE_BG; p.lastHeatBg = WARP_HEAT_BLUE_BG }
            it.style.opacity = (intensity * WARP_HEAT_MAX).toString()
        }
        // Border tints from the resting edge toward reactor blue as it charges; the outward
        // halo is a box-shadow (not clipped by the wrapper's overflow:hidden).
        val bc = maxOf(intensity, if (p.dischargePhase >= 0.0) 0.5 else 0.0)
        setWarpBorder(p, if (bc > 0.03) blendWarp(edgeCol, WARP_CORE_HEX, bc * 0.85) else edgeCol)
        // PERF: pin the halo blur radius (charge reads through the pulsing alpha) instead of
        // growing it every frame — see [WARP_GLOW_BLUR_PINNED] for the revert switch.
        val blueBlur = if (WARP_GLOW_BLUR_PINNED) WARP_GLOW_BLUR + WARP_GLOW_BLUR_PINNED_ADD
        else WARP_GLOW_BLUR + intensity * WARP_GLOW_BLUR_GROW_BLUE
        // PERF: quantize the halo alpha + skip the write when the string is unchanged (see
        // [WARP_GLOW_ALPHA_STEP]) — a blurred box-shadow re-rasterizes on any change.
        setWarpShadow(
            p,
            if (intensity > 0.02 || p.dischargePhase >= 0.0) {
                "$PANE_BASE_SHADOW, 0 0 ${blueBlur}px ${WARP_GLOW_SPREAD}px " +
                    "rgba($WARP_CORE_COLOR,${quantizeWarpAlpha((intensity * WARP_GLOW_ALPHA).coerceAtMost(WARP_GLOW_ALPHA))})"
            } else {
                PANE_BASE_SHADOW
            },
        )
    }
}

/**
 * Screen-space + HUD driver of the warp-core effect, called once per frame from the render
 * loop **after** `css.render` (so the camera matrices its projection uses are current),
 * mirroring [tickPhaser]. It first updates the top-center status banner (awaiting/working
 * counts) — **regardless of [spikeStatusIndication]** — then, only when the reactor FX is on,
 * clears the warp canvas, paints every discharging pane's bloom + thruster plume and every
 * live sonar ping, and updates the collective reactor-load meter + warm sky tint. When the
 * FX is off it hides the canvas + reactor HUD (but not the banner) and returns cheaply.
 *
 * @param camera the shared spike camera, used to project each pane to the screen.
 * @see tickWarpCore @see clearWarpCore
 */
internal fun tickWarpCoreOverlay(camera: PerspectiveCamera) {
    // --- top-of-world status banner: awaiting + working counts, shown REGARDLESS of the
    //     reactor-FX flag (it is agent-status info, not a warp visual). Resolved from the
    //     live session state map, honouring the manual override maps like the render loop. ---
    ensureWarpBanner()
    val states = runCatching { lunamuxClient.windowState.states.value }.getOrNull()
    var waitingN = 0
    var workingN = 0
    for (p in spikePanes) {
        val waiting = spikeWaitingOverride[p.paneId] ?: (states?.get(p.sessionId) == "waiting")
        val working = spikeWorkingOverride[p.paneId] ?: (states?.get(p.sessionId) == "working")
        if (waiting) waitingN++ else if (working) workingN++
    }
    updateWarpBanner(waitingN, workingN)

    // --- the reactor FX + sky tint are gated on the REACTOR status style. ---
    if (spikeStatusIndication != StatusIndication.REACTOR) {
        spikeWarpCanvas?.let { if (it.style.display != "none") it.style.display = "none" }
        spikeWarpSkyTint?.let { it.style.opacity = "0" }
        if (spikeWarpPings.isNotEmpty()) spikeWarpPings.clear()
        return
    }
    ensureWarpHud()
    val w = window.innerWidth
    val h = window.innerHeight
    val canvas = ensureWarpCanvas() ?: return
    if (canvas.width != w) canvas.width = w
    if (canvas.height != h) canvas.height = h
    canvas.style.display = "block"
    val ctx = canvas.getContext("2d").asDynamic() ?: return
    ctx.clearRect(0.0, 0.0, w.toDouble(), h.toDouble())
    ctx.globalCompositeOperation = "lighter"

    // --- discharge blooms + thruster plumes --------------------------------------------
    var load = 0.0
    for (p in spikePanes) {
        load += p.chargeProg
        if (p.dischargePhase >= 0.0) {
            paneScreen(p, camera, w, h)?.let { (cx, cy, halfH) ->
                drawDischarge(ctx, cx, cy, halfH, p.dischargePhase, p.dischargeFail)
            }
        }
    }

    // --- sonar pings --------------------------------------------------------------------
    val pingIt = spikeWarpPings.iterator()
    while (pingIt.hasNext()) {
        val ping = pingIt.next()
        ping.age += spikeDtFrames / 60.0
        val pane = spikePanes.firstOrNull { it.paneId == ping.paneId }
        val screen = pane?.let { paneScreen(it, camera, w, h) }
        if (screen != null) drawWarpPing(ctx, screen.first, screen.second, screen.third, ping.age)
        if (ping.age > WARP_PING_LIFE_S || screen == null) pingIt.remove()
    }
    ctx.globalCompositeOperation = "source-over"

    // --- collective reactor load → warm sky tint (a busy ring visibly runs hot) ---------
    val n = spikePanes.size.coerceAtLeast(1)
    val loadN = (load / n).coerceIn(0.0, 1.0)
    spikeWarpSkyTint?.let { it.style.opacity = (loadN * WARP_LOAD_TINT_ALPHA).toString() }
    // (the status banner is updated above, independently of this flag)
}

/**
 * Updates the two **status pills** with the live counts, called every frame by
 * [tickWarpCoreOverlay] regardless of [spikeStatusIndication]. The amber "awaiting" pill shows
 * only while any agent is blocked on you; the blue "working" pill shows only while any agent
 * is running; each disappears independently when its count drops to zero.
 *
 * @param waitingN how many panes' agents are blocked awaiting your input.
 * @param workingN how many panes' agents are currently running.
 * @see ensureWarpBanner
 */
private fun updateWarpBanner(waitingN: Int, workingN: Int) {
    spikeWarpAwaitPill?.let {
        it.style.display = if (waitingN > 0) "flex" else "none"
        if (waitingN > 0) it.textContent = "$waitingN awaiting answer"
    }
    spikeWarpWorkPill?.let {
        it.style.display = if (workingN > 0) "flex" else "none"
        if (workingN > 0) it.textContent = "$workingN working"
    }
}

/**
 * Emits one sonar ping from a hailing pane by appending a [WarpPing] the overlay pass then
 * expands and fades. Called from [tickWarpCore] on the awaiting-input ping schedule.
 *
 * @param p the hailing pane.
 * @see tickWarpCoreOverlay
 */
internal fun spawnWarpPing(p: RingPane) {
    spikeWarpPings.add(WarpPing(p.paneId, 0.0))
}

/**
 * Clears every lingering warp-core visual on the settled panes — inner heat veils reset,
 * borders/box-shadows returned to rest, pings dropped, HUD hidden — so switching the effect
 * **off** ([syncWorld3dRuntimeFromSettings]) doesn't freeze a pane mid-glow. The next non-warp render pass
 * repaints borders/shadows normally; here we only need to clear what the warp path owned.
 * Also invoked by [clearWarpCore] on close. @see syncWorld3dRuntimeFromSettings
 */
internal fun resetWarpCoreVisuals() {
    for (p in spikePanes) {
        p.warpCore?.let { it.style.opacity = "0" }
        p.warpHeat?.let { it.style.opacity = "0" }
        p.dischargePhase = -1.0
        p.warpFlicker = 0.0
        p.warpAwaitStart = -1.0
        // Drop the PERF style caches: on leaving REACTOR the glow render path becomes the
        // wrapper's border/box-shadow writer, so the reactor's cached keys are now stale and a
        // re-light must write fresh. @see setWarpShadow @see setWarpBorder
        p.lastRingBg = null
        p.lastRingBlend = null
        p.lastHeatBg = null
        p.lastBorderCol = null
        p.lastShadowKey = null
        // chargeProg is eased back to rest by its own cooldown path, so it relaxes
        // smoothly rather than snapping.
    }
    spikeWarpPings.clear()
    spikeWarpCanvas?.let { it.style.display = "none" }
    spikeWarpSkyTint?.let { it.style.opacity = "0" }
    // NB: the status banner is deliberately NOT hidden here — it shows the awaiting/working
    // counts independently of the reactor-FX toggle (updated every frame by the overlay).
}

/**
 * Drops the warp-core registry and HUD references on world close. The canvas + HUD DOM go
 * down with [spikeOverlay] wholesale; this just nulls the references and clears the ping
 * list so a re-open starts clean. Called from [closeWorld3dSpike].
 * @see resetWarpCoreVisuals
 */
internal fun clearWarpCore() {
    spikeWarpPings.clear()
    spikeWarpCanvas = null
    spikeWarpSkyTint = null
    spikeWarpBannerRow = null
    spikeWarpAwaitPill = null
    spikeWarpWorkPill = null
    spikeWarpClock = 0.0
}

/**
 * Lazily builds this pane's inner **heat veil** — a screen-blended radial tint laid over the
 * terminal (below the z-index:2 title bar, like [phaserTint]), whose colour + opacity
 * [tickWarpCore] drives each frame. Kept low-opacity so the reactor heat rides over the text
 * without washing it out. No-op once built.
 *
 * @param p the pane to give a heat veil.
 * @see tickWarpCore
 */
private fun ensureWarpHeat(p: RingPane) {
    if (p.warpHeat != null) return
    val heat = document.createElement("div") as HTMLElement
    // Below the title bar (z-index:2) so the title stays legible; a subtle wash under the ring.
    heat.style.cssText = "position:absolute;left:0;right:0;bottom:0;top:${TITLE_H}px;pointer-events:none;" +
        "z-index:1;opacity:0;mix-blend-mode:screen;"
    p.wrapper.appendChild(heat)
    p.warpHeat = heat
}

/**
 * Lazily builds this pane's **core ring** — the signature big glowing ring of light: a
 * screen-blended, heavily blurred radial-ellipse gradient ([WARP_RING_BLUE]) filling the pane
 * interior, transparent in the centre so the terminal reads through. Rides *inside* the
 * wrapper (so it tracks the pane's 3D transform), at z-index:1 — above the terminal + heat
 * veil, below the z-index:2 title bar. Its outer soft edge is clipped by the wrapper's
 * `overflow:hidden`, but the bright ring sits well inside the box and the outward bleed is
 * carried by the pane's box-shadow. [tickWarpCore] pulses its opacity, scale and colour each
 * frame. No-op once built. @see tickWarpCore
 */
private fun ensureWarpCore(p: RingPane) {
    if (p.warpCore != null) return
    val core = document.createElement("div") as HTMLElement
    // PERF: [WARP_RING_BLUR_ACTIVE] collapses to a small residual blur (with a pre-softened
    // gradient) when [WARP_RING_LOWCOST] is on — see the constant for the revert switch.
    core.style.cssText = "position:absolute;inset:0;pointer-events:none;z-index:1;opacity:0;" +
        "mix-blend-mode:screen;filter:blur(${WARP_RING_BLUR_ACTIVE}px);will-change:opacity,transform;" +
        "background:$WARP_RING_BLUE_ACTIVE;"
    p.wrapper.appendChild(core)
    p.warpCore = core
}

/** The inner-heat radial gradient for a reactor colour (bare `r,g,b`), concentrated low like the spec's `.heat`. */
private fun warpHeatGradient(rgb: String): String =
    "radial-gradient(120% 90% at 50% 62%, rgba($rgb,0.42), rgba($rgb,0.12) 55%, transparent 80%)"

/**
 * Lazily creates (and returns) the full-viewport warp canvas, appended to [spikeOverlay] at
 * z-index:2 — above the CSS3D pane layer, below the chrome — with `pointer-events:none`,
 * exactly like the phaser canvas. Shares nothing with it so the two effects never contend
 * for the same context state.
 *
 * @return the canvas, or `null` if the overlay is gone (spike closed mid-frame).
 * @see tickWarpCoreOverlay
 */
private fun ensureWarpCanvas(): HTMLCanvasElement? {
    spikeWarpCanvas?.let { return it }
    val overlay = spikeOverlay ?: return null
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.style.cssText = "position:absolute;inset:0;width:100%;height:100%;z-index:2;pointer-events:none;"
    overlay.appendChild(canvas)
    spikeWarpCanvas = canvas
    return canvas
}

/**
 * Lazily builds the warp-core **sky tint** (gated on the toggle): a fixed warm radial overlay
 * behind the CSS3D scene (z-index:0) whose opacity tracks reactor load, so a busy ring visibly
 * runs hot. The status banner is built separately by [ensureWarpBanner] since it shows
 * regardless of the toggle. `pointer-events:none`, hidden until [tickWarpCoreOverlay] drives
 * its opacity. No-op once built. @see tickWarpCoreOverlay
 */
private fun ensureWarpHud() {
    val overlay = spikeOverlay ?: return
    if (spikeWarpSkyTint == null) {
        val tint = document.createElement("div") as HTMLElement
        // Behind the scene (z-index:0): a faint warm bloom so a busy ring visibly runs hot.
        tint.style.cssText = "position:absolute;inset:0;z-index:0;pointer-events:none;opacity:0;" +
            "transition:opacity .4s ease;background:radial-gradient(1200px 800px at 50% 12%," +
            "rgba(90,150,255,0.10),transparent 60%),radial-gradient(1000px 700px at 50% 100%," +
            "rgba(255,180,90,0.08),transparent 55%);"
        overlay.insertBefore(tint, overlay.firstChild)
        spikeWarpSkyTint = tint
    }
}

/**
 * Lazily builds the top-left **status row** — shown **regardless of [spikeStatusIndication]**,
 * since it reports agent status (how many await you / how many are running), not a reactor
 * visual. A flex row of two independent pills side by side: an amber "awaiting" pill and a
 * blue "working" pill, each `pointer-events:none` and hidden until [updateWarpBanner] shows
 * it. Anchored top-left (where the old reactor meter sat), nudged down to clear the macOS
 * traffic lights. No-op once built. @see updateWarpBanner
 */
private fun ensureWarpBanner() {
    if (spikeWarpBannerRow != null) return
    val overlay = spikeOverlay ?: return
    val row = document.createElement("div") as HTMLElement
    // top:44px sits the pills clear below the macOS traffic-light cluster (which the 3D
    // overlay covers at the very top-left) rather than tucked up against it.
    row.style.cssText = "position:absolute;left:16px;top:44px;z-index:4;" +
        "pointer-events:none;display:flex;align-items:center;gap:8px;"

    /** One count pill with a fixed colour scheme; hidden until [updateWarpBanner] lights it. */
    fun pill(bg: String, borderCol: String, textCol: String, glow: String): HTMLElement {
        val el = document.createElement("div") as HTMLElement
        el.style.cssText = "display:none;align-items:center;padding:9px 16px;border-radius:10px;" +
            "background:$bg;border:1px solid $borderCol;color:$textCol;white-space:nowrap;" +
            "font:600 13px/1 ui-monospace,Menlo,monospace;letter-spacing:.02em;box-shadow:$glow;"
        row.appendChild(el)
        return el
    }
    // Blue "working" pill first (left), then the amber "awaiting" pill (right) — the two read
    // left-to-right as the natural progression running → then blocked-on-you.
    spikeWarpWorkPill = pill("rgba(10,22,36,0.9)", "#2c5c86", "#bfe0ff", "0 0 20px rgba($WARP_CORE_COLOR,0.28)")
    spikeWarpAwaitPill = pill("rgba(42,31,8,0.93)", "#6f5620", "#ffd77a", "0 0 22px rgba($WARP_AMBER_COLOR,0.33)")

    overlay.appendChild(row)
    spikeWarpBannerRow = row
}

/**
 * Draws one discharge over a pane's screen centre: a bright radial **bloom** flash plus a
 * **thruster plume** flaring off the bottom edge, both shaped by the discharge [phase]
 * (seconds) so they crest and fade over [WARP_DISCHARGE_S]. Cyan when [fail] is false, an
 * orange sputter (jittered) when true. Assumes additive compositing is set.
 *
 * @param ctx the warp canvas 2D context (composite already `"lighter"`).
 * @param cx/[cy] the pane's screen centre px. @param halfH the pane's on-screen half-height
 *   px (so bloom/plume scale with the pane's distance). @param phase discharge age in seconds.
 * @param fail `true` for the orange failure sputter, else the cyan success look.
 * @see tickWarpCoreOverlay
 */
private fun drawDischarge(ctx: dynamic, cx: Double, cy: Double, halfH: Double, phase: Double, fail: Boolean) {
    // Fast attack, exponential tail — the spec's shape(t, peak, tail).
    val attack = 1.0 - exp(-phase / 0.05)
    var bloom = exp(-phase / 0.19) * attack
    val thrust = exp(-phase / 0.32) * attack
    if (fail) bloom *= 0.7 + 0.3 * sin(phase * 55.0) // fail flickers/sputters
    val bottom = cy + halfH

    // Thruster plume: a flare narrow at the pane's bottom edge, widening downward.
    if (thrust > 0.01) {
        val jitter = if (fail) 0.6 + 0.4 * kotlin.math.abs(sin(phase * 40.0)) else 1.0
        val len = halfH * 1.7 * thrust * jitter
        val topW = halfH * 0.28 * (if (fail) 1.25 else 1.0)
        val botW = halfH * 0.62 * (if (fail) 1.25 else 1.0)
        val grad = ctx.createLinearGradient(cx, bottom, cx, bottom + len)
        if (fail) {
            grad.addColorStop(0.0, "rgba(255,230,176,${0.9 * thrust})")
            grad.addColorStop(0.24, "rgba($WARP_FAIL_COLOR,${0.8 * thrust})")
            grad.addColorStop(1.0, "rgba($WARP_FAIL_COLOR,0)")
        } else {
            grad.addColorStop(0.0, "rgba(191,230,255,${0.9 * thrust})")
            grad.addColorStop(0.22, "rgba($WARP_CORE_COLOR,${0.8 * thrust})")
            grad.addColorStop(1.0, "rgba($WARP_CORE_COLOR,0)")
        }
        ctx.fillStyle = grad
        ctx.beginPath()
        ctx.moveTo(cx - topW, bottom)
        ctx.lineTo(cx + topW, bottom)
        ctx.lineTo(cx + botW, bottom + len)
        ctx.lineTo(cx - botW, bottom + len)
        ctx.closePath()
        ctx.fill()
    }

    // Discharge bloom flash at the pane centre.
    if (bloom > 0.01) {
        val r = halfH * (0.9 + bloom * 0.5)
        val g = ctx.createRadialGradient(cx, cy, 0.0, cx, cy, r)
        if (fail) {
            g.addColorStop(0.0, "rgba(255,242,216,${0.95 * bloom})")
            g.addColorStop(0.34, "rgba($WARP_AMBER_COLOR,${0.7 * bloom})")
            g.addColorStop(0.6, "rgba($WARP_FAIL_COLOR,${0.4 * bloom})")
            g.addColorStop(1.0, "rgba($WARP_FAIL_COLOR,0)")
        } else {
            g.addColorStop(0.0, "rgba(255,255,255,${0.95 * bloom})")
            g.addColorStop(0.3, "rgba($WARP_CORE_HOT_RGB,${0.75 * bloom})")
            g.addColorStop(0.55, "rgba($WARP_CORE_COLOR,${0.45 * bloom})")
            g.addColorStop(1.0, "rgba($WARP_CORE_COLOR,0)")
        }
        ctx.fillStyle = g
        ctx.beginPath()
        ctx.arc(cx, cy, r, 0.0, PI * 2.0)
        ctx.fill()
    }
}

/**
 * Draws one sonar ping: an amber ring expanding out past its pane and fading, its radius
 * anchored to the pane's on-screen size so it reads the same near or far across the ring.
 * Assumes additive compositing.
 *
 * @param ctx the warp canvas 2D context. @param cx/[cy] the pane's screen centre px.
 * @param halfH the pane's on-screen half-height px. @param age the ping's age in seconds.
 * @see tickWarpCoreOverlay
 */
private fun drawWarpPing(ctx: dynamic, cx: Double, cy: Double, halfH: Double, age: Double) {
    val t = (age / WARP_PING_LIFE_S).coerceIn(0.0, 1.0)
    val radius = halfH * (0.9 + t * 2.4)
    val alpha = (0.7 * (1.0 - t)).coerceIn(0.0, 1.0)
    if (alpha <= 0.01) return
    ctx.lineWidth = 2.5
    ctx.strokeStyle = "rgba($WARP_AMBER_COLOR,$alpha)"
    ctx.beginPath()
    ctx.arc(cx, cy, radius, 0.0, PI * 2.0)
    ctx.stroke()
}

/**
 * Projects a pane to the screen and measures its on-screen half-height, so the screen-space
 * FX (bloom / plume / pings) scale with how big the pane currently appears. Projects the
 * pane's world centre and a point one world-half-height above it with the shared camera.
 *
 * @param p the pane. @param camera the shared camera. @param w/[h] the viewport size px.
 * @return `(centreX, centreY, halfHeight)` in screen px, or `null` if the pane centre is
 *   outside the clip volume (behind the camera / beyond the far plane).
 * @see tickWarpCoreOverlay
 */
private fun paneScreen(p: RingPane, camera: PerspectiveCamera, w: Int, h: Int): Triple<Double, Double, Double>? {
    val objScale = p.obj.scale.x as Double
    val halfHWorld = (p.baseCh + TITLE_H) * 0.5 * objScale
    val centre = projectWarpPoint(p.obj.position.clone(), camera, w, h) ?: return null
    val up = p.obj.position.clone()
    up.y = (up.y as Double) + halfHWorld
    val upScreen = projectWarpPoint(up, camera, w, h)
    val halfH = if (upScreen != null) {
        val dx = upScreen.first - centre.first
        val dy = upScreen.second - centre.second
        sqrt(dx * dx + dy * dy).coerceAtLeast(24.0)
    } else {
        halfHWorld * 0.35 // graceful fallback if the up-point clips
    }
    return Triple(centre.first, centre.second, halfH)
}

/**
 * Projects a (dynamic) three.js Vector3 clone to screen-pixel coordinates with the shared
 * camera, the twin of [World3DSpikePhaser]'s projector. Mutates the passed clone.
 *
 * @param v a *clone* of a position vector (it is projected in place). @param camera the camera.
 * @param w/[h] the viewport size px. @return the screen `(x, y)` px, or `null` if off-clip.
 */
private fun projectWarpPoint(v: dynamic, camera: PerspectiveCamera, w: Int, h: Int): Pair<Double, Double>? {
    v.project(camera)
    val nz = v.z as Double
    if (nz < -1.0 || nz > 1.0) return null
    return Pair(((v.x as Double) + 1.0) / 2.0 * w, (1.0 - (v.y as Double)) / 2.0 * h)
}

/**
 * Blends two CSS colours in sRGB by `t` (0 → `from`, 1 → `to`) using the shared
 * [parseCssColor] / [mixRgb] / [cssRgb] helpers — the same mechanic the render loop uses to
 * fade a pane's edge between its rest and accent colours, so the reactor's border tint
 * matches the world's own colour maths.
 *
 * @param from the base colour. @param to the reactor colour. @param t the blend amount.
 * @return the mixed `rgb(...)` string (or the nearer endpoint if either colour won't parse).
 */
private fun blendWarp(from: String, to: String, t: Double): String {
    val a = parseCssColor(from)
    val b = parseCssColor(to)
    return if (a != null && b != null) cssRgb(mixRgb(a, b, t.coerceIn(0.0, 1.0)))
    else if (t >= 0.5) to else from
}

/**
 * Whether the active theme's pane **surface** is light (perceived luminance over ~0.55),
 * read from [spikeChromeColors]. Used to flip the reactor ring + heat blend from `screen`
 * (glows on a dark surface) to `multiply` (a bright coloured ring on a light surface), since
 * a screen-blended ring is invisible on a light background. Defaults to `false` (dark) when
 * no theme is resolved. @see tickWarpCore
 */
private fun warpLightSurface(): Boolean {
    val c = spikeChromeColors?.surface?.let { parseCssColor(it) } ?: return false
    val lum = (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0
    return lum > 0.55
}
