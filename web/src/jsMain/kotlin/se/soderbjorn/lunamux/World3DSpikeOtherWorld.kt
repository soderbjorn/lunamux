/**
 * The **other-world command center** — the destination you reach by flying the camera *through*
 * a wormhole like a spaceship. It is deliberately **not** a separate scene: the destination is
 * the *very same* rotunda of tabs/panes, re-skinned to the next world's theme, so it reads as
 * "the same command center, elsewhere." What changes on arrival is the **sky** (the destination
 * world's deep-space colour), the pane **chrome/accent** ([spikeChrome] resolves the destination
 * theme while a transit previews it), and a small arrival **banner** naming the world.
 *
 * Unlike the original prototype — which toggled between the home world and a single hardcoded
 * "Amber CRT" duplicate — this ports the look & feel onto the real multi-world support: ⌥⌘O
 * **cycles the active world** on to the next [WorldConfig] in [WindowConfig.worlds] (wrapping
 * after the last), by sending [WindowCommand.SetActiveWorld] at the tunnel midpoint. The server
 * re-broadcasts the config and the client auto-repaints to the destination world's theme pair
 * via [applyActiveWorldTheme]; to make the mid-tunnel swap crisp (hidden behind the opaque
 * canvas) we *also* preview the destination theme client-side ([spikeWorldThemePreview]) so the
 * re-skin lands before the round-trip completes.
 *
 * The journey ([enterOrExitOtherWorld] → [tickWorldTransit]) is a four-leg cinematic —
 * **open** (rift blooms ahead, camera winds back), **approach** (charge into the rift),
 * **tunnel** (a few seconds riding a high-tech light tunnel — [drawTransitTunnel] — with the
 * palette swapping at its midpoint), **arrive** (fly forward across the new world to the
 * command center). The rift reuses the spawn wormhole's [buildWormholeVortex]; the tunnel is
 * a full-screen 2D canvas in the same screen-space idiom as the phaser / warp-core passes.
 *
 * @see OTHER_WORLD_ENABLED @see tickWorldTransit @see drawTransitTunnel
 */
package se.soderbjorn.lunamux

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.argbToCss
import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera

/**
 * One in-flight world transit: the reused wormhole vortex (leg 1–2), the full-screen tunnel
 * canvas + its particle fields (leg 3), and the screen flash for the threshold punches, plus
 * the clock [tickWorldTransit] advances the whole fly-through with. At most one runs at a
 * time. Created by [enterOrExitOtherWorld]; disposed by [clearWorldTransit].
 *
 * @property group the vortex disc `Group` (billboarded + tilted + scaled while it's shown).
 * @property rings the two vortex cloud-layer `<div>`s, spun for the churn.
 * @property twists each vortex cloud layer's fixed pre-rotation (radians).
 * @property flash the vortex's own eye-flash element (unused here; kept off).
 * @property elements the vortex disc wrapper element(s) — for the open fade + teardown.
 * @property screenFlash the full-screen white `<div>` pulsed at the two thresholds (enter/exit).
 * @property canvas the full-screen tunnel canvas (opacity carries the tunnel fade in/out).
 * @property ctx the tunnel canvas 2D context.
 * @property streakAng/[streakRad]/[streakSpd] the warp-streak field (fixed angle, growing
 *   radius, per-streak speed) — the light lines rushing outward past the ship.
 * @property ringDepth/[ringRot] the tunnel-ring field (0..1 depth rushing toward the viewer,
 *   fixed rotation) — the structural hoops of the tube.
 * @property destinationWorldId the id of the world we're cycling to — sent as
 *   [WindowCommand.SetActiveWorld] at the tunnel midpoint (`null` when there is no distinct next
 *   world, e.g. fewer than two worlds; the transit then just replays returning to the same world).
 * @property destinationSelection the destination world's theme pair, previewed client-side at the
 *   midpoint ([spikeWorldThemePreview]) so the re-skin lands before the server round-trip.
 * @property destName the destination world's display name, shown in the arrival banner.
 * @property fromColor the tube-glow RGB of the sky we **left** (the departure world's sky).
 * @property toColor the tube-glow RGB of the sky we're **arriving** in (the destination world's
 *   sky). The tunnel fades [fromColor] → [toColor] across the ride, so it visibly connects the
 *   two skies. Both captured once at arming (from the world themes) via [skyGlowRgb].
 * @property resumeFlight whether we were in **free flight** ([spikeFlyMode]) when the warp began,
 *   so the arrival can hand the camera back in the same mode: free flight resumes parked at the
 *   new command center (fly on from there), command center lands pristine. The transit is a camera
 *   move between worlds, never a mode change. @see tickWorldTransit
 * @property startX/[startY]/[startZ] the camera **position** at the moment the warp was armed (the
 *   live free-flight pose, or the command-center home pose if not flown). The open leg eases from
 *   here to the wind-up point, so warping from anywhere in free flight glides off smoothly instead
 *   of snapping to the command center first.
 * @property lookStartX/[lookStartY]/[lookStartZ] the point the camera was **facing** at arm time
 *   (a point out along its nose, or the origin in the command center), eased toward the rift over
 *   the open leg so the turn-to-face-the-rift is smooth too.
 * @property phase elapsed 60fps-frames since arming — the master clock.
 * @property spin accumulated vortex cloud spin (radians).
 * @property tunnelRoll accumulated tunnel bank (radians), for the inside-the-tube sway.
 * @property tunnelTravel accumulated distance travelled down the tube (advanced by the ride
 *   `speed`), the clock the wandering *spine* is sampled against so the pipe's soft turns flow
 *   toward the viewer as you fly. @see drawTransitTunnel @see WORLD_TRANSIT_TUNNEL_BEND_RATE
 * @property reskinned `true` once [applyWorldPalette] has fired (at the tunnel midpoint).
 */
internal class WorldTransit(
    val group: Group,
    val rings: List<HTMLElement>,
    val twists: DoubleArray,
    val flash: HTMLElement,
    val elements: List<HTMLElement>,
    val screenFlash: HTMLElement,
    val canvas: HTMLCanvasElement,
    val ctx: CanvasRenderingContext2D,
    val streakAng: DoubleArray,
    val streakRad: DoubleArray,
    val streakSpd: DoubleArray,
    val ringDepth: DoubleArray,
    val ringRot: DoubleArray,
    val destinationWorldId: String?,
    val destinationSelection: WorldThemeSelection?,
    val destName: String,
    val fromColor: DoubleArray,
    val toColor: DoubleArray,
    val resumeFlight: Boolean,
    val startX: Double,
    val startY: Double,
    val startZ: Double,
    val lookStartX: Double,
    val lookStartY: Double,
    val lookStartZ: Double,
    var phase: Double = 0.0,
    var spin: Double = 0.0,
    var tunnelRoll: Double = 0.0,
    var tunnelTravel: Double = 0.0,
    var reskinned: Boolean = false,
    /** `true` once the tunnel hum/whoosh has fired (only when the tunnel leg begins). */
    var playedTravel: Boolean = false,
)

/** How many warp streaks and structural rings the tunnel draws. */
private const val TUNNEL_STREAKS = 150
private const val TUNNEL_RINGS = 16

/** Smootherstep `t³(t(6t−15)+10)` — the soft-in/soft-out curve the camera tours use. */
private fun worldSmoother(t: Double): Double {
    val x = t.coerceIn(0.0, 1.0)
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

/** A short triangular pulse peaking `1.0` at [center] and reaching `0` [half] frames out. */
private fun framePulse(phase: Double, center: Double, half: Double): Double =
    max(0.0, 1.0 - kotlin.math.abs(phase - center) / half)

/**
 * The [ResolvedTheme] the world currently on screen paints with: the **destination** world's
 * theme while a transit is previewing it ([spikeWorldThemePreview]), otherwise the live **active**
 * theme ([currentResolvedTheme], which already reflects whichever world is active). Both resolve
 * through the identical toolkit path (`Theme.resolve()`), so every world is coloured exactly the
 * same way — the *only* difference is which world's theme pair is assigned. This is what makes the
 * other world a real second theme rather than a special-cased colour override.
 *
 * Called by [spikeChrome] (pane chrome) and [buildXtermTheme] (ring terminal bodies). Falls back
 * to the active theme if the previewed pair can't resolve.
 *
 * @return the resolved palette for the world currently displayed.
 * @see spikeWorldThemePreview @see spikeChrome @see resolvedThemeForWorld
 */
internal fun currentWorldTheme(): ResolvedTheme {
    val preview = spikeWorldThemePreview ?: return currentResolvedTheme()
    return runCatching { resolvedThemeForWorld(preview) }.getOrNull() ?: currentResolvedTheme()
}

/**
 * Like [currentWorldTheme], but always the **dark** variant of the world's theme — used only for
 * the **sky** ([applyWorldSky]) and the sky-derived transit-tunnel glow, which should stay deep
 * cosmic dark even when the user runs the app in light mode. While a transit previews a
 * destination it is that world's dark-slot theme; otherwise the active world's dark-slot theme
 * ([currentDarkResolvedTheme]). Either way it stays dark regardless of the light/dark setting.
 *
 * The pane chrome and terminals still follow [currentWorldTheme] (the appearance-aware theme), so
 * only the backdrop sky and tunnel are forced dark — the panes keep matching the user's appearance
 * choice.
 *
 * @return the resolved palette to paint the current world's sky with.
 * @see currentWorldTheme @see currentDarkResolvedTheme @see applyWorldSky
 */
internal fun currentWorldSkyTheme(): ResolvedTheme {
    val preview = spikeWorldThemePreview ?: return currentDarkResolvedTheme()
    return runCatching { darkResolvedThemeForWorld(preview) }.getOrNull() ?: currentDarkResolvedTheme()
}

/**
 * The **tube-glow RGB** of a world's sky: the theme's [ResolvedTheme.bg] hue lifted to full glow
 * brightness (scaled so its brightest channel hits 255). The raw sky `bg` is near-black (it's a
 * background), so used directly the tunnel would be a dead black tube; normalising keeps the
 * sky's *hue* while giving the streaks/hoops something to glow with. It falls out that a deep
 * blue-black home sky yields a cyan tube and an amber-black other sky an amber tube — the two
 * end colours are sourced from the skies themselves, not hardcoded.
 *
 * @param theme the world theme whose sky to sample.
 * @return `[r, g, b]` in `0..255`, the sky hue at glow brightness.
 * @see WorldTransit.fromColor @see drawTransitTunnel
 */
private fun skyGlowRgb(theme: ResolvedTheme): DoubleArray {
    val r = ((theme.bg shr 16) and 0xFF).toDouble()
    val g = ((theme.bg shr 8) and 0xFF).toDouble()
    val b = (theme.bg and 0xFF).toDouble()
    val scale = 255.0 / maxOf(r, g, b, 1.0)
    return doubleArrayOf(min(255.0, r * scale), min(255.0, g * scale), min(255.0, b * scale))
}

/**
 * Paints the overlay **sky** from the current world's **dark** theme ([currentWorldSkyTheme]) —
 * an opaque [ResolvedTheme.bg] backstop under a soft radial lift built from
 * [ResolvedTheme.surfaceAlt] → `bg`, exactly the "deep-space colour of a pane background" the sky
 * always resembled but was previously hardcoded to. The sky uses the dark variant *even in light
 * mode* so space stays deep-cosmic dark regardless of the user's appearance choice (the panes
 * still follow the active theme). Both background *longhands* are set separately (never the
 * `background` shorthand — see [openWorld3dSpike] — which would wipe the opaque flicker backstop).
 *
 * Called on open, on the world palette swap, and on any live theme change (all via
 * [restyleWorldChrome], plus once directly at open), so the sky always tracks the world's theme.
 * No-op if the overlay is down or no theme resolves (the open-time cssText fallback then stands).
 *
 * @see currentWorldSkyTheme @see restyleWorldChrome
 */
internal fun applyWorldSky() {
    val overlay = spikeOverlay ?: return
    val theme = runCatching { currentWorldSkyTheme() }.getOrNull() ?: return
    val bg = argbToCss(theme.bg)
    overlay.style.backgroundColor = bg
    overlay.style.setProperty(
        "background-image",
        "radial-gradient(circle at 50% 42%,${argbToCss(theme.surfaceAlt)},$bg)",
    )
}

/**
 * Toggles the **other world**: arms a fly-through-the-wormhole transit that cycles the active
 * world on to the **next** [WorldConfig] in [WindowConfig.worlds] (wrapping after the last).
 * Wired to the **⌥⌘O** hotkey in [buildKeyHandler]. No-op if the feature is off, a transit is
 * already running, or the scene isn't up. With fewer than two worlds there is no distinct next
 * world, so the transit still plays as a cinematic but returns to the same world (never crashes).
 * When **fancy animations are off** ([spikeFancyAnimations]) the fly-through is skipped entirely:
 * the world switches instantly (theme preview + [WindowCommand.SetActiveWorld] + repaint + banner)
 * with no vortex, tunnel or camera flight.
 *
 * Cancels any camera motion / mode so the transit fully owns the camera, captures the departure
 * and destination sky colours for the tunnel, builds the vortex (parked shut), the tunnel canvas
 * + its particle fields, and the threshold flash, then hands off to the per-frame
 * [tickWorldTransit]. The actual world switch ([WindowCommand.SetActiveWorld]) fires later, at
 * the tunnel midpoint ([applyWorldPalette]).
 *
 * @see tickWorldTransit @see applyWorldPalette
 */
internal fun enterOrExitOtherWorld() {
    if (!OTHER_WORLD_ENABLED) return
    if (spikeWorldTransit != null) return
    val scene = spikeCssScene ?: return
    val overlay = spikeOverlay ?: return
    val chrome = spikeChromeColors ?: return

    // Destination world = the next world in the config, wrapping after the last. With <2 worlds
    // there is no distinct next: `destWorldId` is null (no SetActiveWorld sent) and the animation
    // simply replays returning to the same world. The destination theme selection drives both the
    // client-side preview (crisp mid-tunnel swap) and the tunnel's arrival colour.
    val cfg = latestWindowConfig
    val worlds = cfg?.worlds ?: emptyList()
    val activeIdx = worlds.indexOfFirst { it.id == cfg?.activeWorldId }.let { if (it < 0) 0 else it }
    val destWorld = if (worlds.isEmpty()) null else worlds[(activeIdx + 1) % worlds.size]
    val distinctDest = destWorld != null && destWorld.id != cfg?.activeWorldId
    val globalSnap = appVm.stateFlow.value.toThemeSnapshot()
    // Represent "preview to the destination world" as a concrete pair even when the world follows
    // the global slots (themeSelection == null) — so the preview is always non-null while a
    // transit runs and resolves to the same palette the world will show once the switch lands.
    val destSelection = destWorld?.themeSelection
        ?: WorldThemeSelection(darkThemeName = globalSnap.darkThemeName, lightThemeName = globalSnap.lightThemeName)
    val destWorldId = if (distinctDest) destWorld?.id else null
    val destName = destWorld?.name ?: ""

    // Fancy animations off: skip the fly-through cinematic and just switch worlds. This mirrors
    // the mid-tunnel [applyWorldPalette] step — preview the destination theme so the re-skin is
    // crisp before the [WindowCommand.SetActiveWorld] round-trip lands, fire the switch, repaint
    // the sky + pane chrome, and show the arrival banner — but with no vortex, no tunnel and no
    // camera flight, so we are simply in the new world at once (never a transit to tick down).
    if (!spikeFancyAnimations) {
        spikeWorldThemePreview = destSelection
        destWorldId?.let { launchCmd(WindowCommand.SetActiveWorld(it)) }
        restyleWorldChrome()
        updateWorldBanner(destName)
        return
    }

    // The transit is a camera *move* between the two worlds, not a mode change: whatever mode we
    // were in (free flight or command center) is restored on arrival. Remember it now, along with
    // the exact pose we're leaving from so the open leg glides off from there rather than snapping
    // to the command center. When not flown the camera holds the recomputed home pose (0,0,homeZ)
    // facing the origin, so those are the sensible defaults.
    val resumeFlight = spikeFlyMode
    val homeZAtArm = RING_R + perspDistance(window.innerHeight)
    val startX = if (spikeCamFlown) spikeCamX else 0.0
    val startY = if (spikeCamFlown) spikeCamY else 0.0
    val startZ = if (spikeCamFlown) spikeCamZ else homeZAtArm
    // The point the camera currently faces: out along its nose while flown, the origin at home.
    val lookAhead = 2000.0
    val lookStartX = if (spikeCamFlown) spikeCamX + spikeCamFx * lookAhead else 0.0
    val lookStartY = if (spikeCamFlown) spikeCamY + spikeCamFy * lookAhead else 0.0
    val lookStartZ = if (spikeCamFlown) spikeCamZ + spikeCamFz * lookAhead else 0.0

    // The transit drives the camera by hand — cancel every other camera owner so nothing fights
    // it mid-flight. We keep [spikeFlyMode] as-is (so it can be restored) but stop its per-frame
    // integration for the duration (the render loop skips [applyFlyStep] while a transit runs) and
    // clear held keys + residual velocity so the ship isn't still coasting when it arrives.
    spikeCamReturning = false
    spikeStashChase = null
    spikeShelfPanTargetX = null
    spikeFlyKeys.clear()
    clearFlyVelocity()

    // Capture the two skies' glow colours now so the tunnel can fade from the sky we're leaving
    // to the sky we're arriving in. Each world's sky uses its **dark** theme (even in light mode —
    // see [currentWorldSkyTheme]): the departure is the active world's dark-slot theme, the arrival
    // the destination world's. Both resolved explicitly here (independent of any preview) so the
    // tunnel matches both end skies.
    val fromTheme = runCatching { currentDarkResolvedTheme() }.getOrNull()
    val toTheme = runCatching { darkResolvedThemeForWorld(destSelection) }.getOrNull()
    val fromColor = fromTheme?.let { skyGlowRgb(it) } ?: doubleArrayOf(90.0, 200.0, 255.0)
    val toColor = toTheme?.let { skyGlowRgb(it) } ?: doubleArrayOf(255.0, 170.0, 95.0)

    val vortex = buildWormholeVortex(chrome)
    vortex.group.asDynamic().scale.set(0.0, 0.0, 0.0) // a point; spirals open in the tick
    scene.add(vortex.group)

    // Threshold flash — a brief full-screen white punch at the two seams (crossing into the
    // tunnel, and bursting out the far end), so each hard cut lands with impact.
    val flash = document.createElement("div") as HTMLElement
    flash.className = "spike-world-flash"
    flash.style.cssText = "position:absolute;inset:0;z-index:9;pointer-events:none;opacity:0;" +
        "background:radial-gradient(circle at 50% 48%,#ffffff 0%,#ffffff 40%,#dff2ff 100%);"
    overlay.appendChild(flash)

    // The tunnel canvas — full-screen, above the CSS3D scene (z 8), opacity-faded so it only
    // veils the world while we're inside the tube. Sized to the backing store at devicePixelRatio
    // for crisp streaks; the context is pre-scaled so we can draw in CSS pixels.
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.className = "spike-world-tunnel"
    val w = window.innerWidth
    val h = window.innerHeight
    val dpr = window.devicePixelRatio.coerceIn(1.0, 3.0)
    canvas.width = (w * dpr).toInt()
    canvas.height = (h * dpr).toInt()
    canvas.style.cssText = "position:absolute;inset:0;z-index:8;pointer-events:none;opacity:0;" +
        "width:${w}px;height:${h}px;"
    overlay.appendChild(canvas)
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.scale(dpr, dpr)

    // Seed the particle fields: streaks fan out at random angles from a small central radius;
    // rings are staggered in depth so they arrive continuously rather than in one pack.
    val streakAng = DoubleArray(TUNNEL_STREAKS) { Random.nextDouble(0.0, 2.0 * PI) }
    val streakRad = DoubleArray(TUNNEL_STREAKS) { Random.nextDouble(2.0, 60.0) }
    val streakSpd = DoubleArray(TUNNEL_STREAKS) { Random.nextDouble(0.7, 1.5) }
    val ringDepth = DoubleArray(TUNNEL_RINGS) { it.toDouble() / TUNNEL_RINGS }
    val ringRot = DoubleArray(TUNNEL_RINGS) { Random.nextDouble(0.0, 2.0 * PI) }

    spikeWorldTransit = WorldTransit(
        group = vortex.group,
        rings = vortex.rings,
        twists = vortex.twists,
        flash = vortex.flash,
        elements = vortex.elements,
        screenFlash = flash,
        canvas = canvas,
        ctx = ctx,
        streakAng = streakAng,
        streakRad = streakRad,
        streakSpd = streakSpd,
        ringDepth = ringDepth,
        ringRot = ringRot,
        destinationWorldId = destWorldId,
        destinationSelection = destSelection,
        destName = destName,
        fromColor = fromColor,
        toColor = toColor,
        resumeFlight = resumeFlight,
        startX = startX,
        startY = startY,
        startZ = startZ,
        lookStartX = lookStartX,
        lookStartY = lookStartY,
        lookStartZ = lookStartZ,
    )

    // Rift creation: the exact same "wormhole appears" bloom the new-pane vortex uses — same
    // duration (WORMHOLE_OPEN_FRAMES) so it plays at the same speed, not stretched/slowed — fired
    // as the rift opens here at the start of the journey. The tunnel hum/whoosh then takes over as
    // we dive into the tube ([tickWorldTransit]). @see playWormholeAppear
    playWormholeAppear(WORMHOLE_OPEN_FRAMES / 60.0)
}

/**
 * Per-frame driver of the [spikeWorldTransit], called from the render loop ([startSpikeLoop])
 * **just before** `css.render` (it moves the camera and a real 3D object). It fully owns the
 * camera for the duration, gazing straight down −Z at the origin the whole way, and walks the
 * four legs off the master clock — **open** (wind back, rift blooms), **approach** (charge in),
 * **tunnel** (ride the light tube; palette swaps at its midpoint behind the opaque canvas),
 * **arrive** (fly forward across the new world to the command center). On completion the camera
 * is handed back to the pristine home pose and any destination preview is dropped.
 *
 * @param camera the shared spike camera, used to billboard the vortex toward the viewer.
 * @see enterOrExitOtherWorld @see drawTransitTunnel @see startSpikeLoop
 */
internal fun tickWorldTransit(camera: PerspectiveCamera) {
    val wt = spikeWorldTransit ?: return
    wt.phase += spikeDtFrames
    val phase = wt.phase

    val tOpen = WORLD_TRANSIT_OPEN_FRAMES
    val tApproach = tOpen + WORLD_TRANSIT_APPROACH_FRAMES
    val tTunnel = tApproach + WORLD_TRANSIT_TUNNEL_FRAMES
    val tEnd = tTunnel + WORLD_TRANSIT_ARRIVE_FRAMES

    // The tunnel leg rides an opaque, full-screen canvas — the 3D scene behind it is fully
    // hidden — so flag the render loop to skip the scene render + overlays this frame. The
    // approach's canvas only reaches full opacity at its very end and the arrive leg fades it
    // back out (both fly the camera across visible scene), so only the tunnel leg qualifies.
    spikeWorldTransitOccluding = phase >= tApproach && phase < tTunnel

    // Tunnel hum + rushing whoosh — fired ONCE, timed to the *visible* tube, not the leg
    // boundary. The tunnel canvas fades up over the last 30% of the approach, so start the whoosh
    // there (≈ phase 180) with a fast attack so it's full as we punch into the tube at tApproach;
    // end it when the tube has fully faded out on arrival (over the first 45% of the arrive leg),
    // so it stops as we emerge rather than lingering across the new world. @see playWormholeTravel
    val tunnelVisibleStart = tApproach - WORLD_TRANSIT_APPROACH_FRAMES * 0.30
    val tunnelGone = tTunnel + WORLD_TRANSIT_ARRIVE_FRAMES * 0.25
    if (!wt.playedTravel && phase >= tunnelVisibleStart) {
        wt.playedTravel = true
        playWormholeTravel((tunnelGone - phase) / 60.0)
    }

    // Geometry. The rift opens at an off-to-the-side open-space point ([WORLD_TRANSIT_RIFT_*]) —
    // out beyond the ring in empty sky — so we turn toward it and fly in diagonally. The open leg
    // winds the camera back away from the rift; the approach charges along home→rift and stops just
    // short of the disc. The arrival leg is a straight run down the +Z axis from far behind the
    // ring to the command-center home pose.
    val d = perspDistance(window.innerHeight)
    val homeZ = RING_R + d
    val vx = WORLD_TRANSIT_RIFT_X
    val vy = WORLD_TRANSIT_RIFT_Y
    val vz = WORLD_TRANSIT_RIFT_Z
    // Unit vector home→rift, and the enter point a gap short of the disc along it.
    var dxr = vx - 0.0
    var dyr = vy - 0.0
    var dzr = vz - homeZ
    val dl = sqrt(dxr * dxr + dyr * dyr + dzr * dzr).coerceAtLeast(1.0)
    dxr /= dl; dyr /= dl; dzr /= dl
    val enterX = vx - dxr * WORLD_TRANSIT_STOP_GAP
    val enterY = vy - dyr * WORLD_TRANSIT_STOP_GAP
    val enterZ = vz - dzr * WORLD_TRANSIT_STOP_GAP
    val windX = 0.0 - dxr * WORLD_TRANSIT_PULLBACK
    val windY = 0.0 - dyr * WORLD_TRANSIT_PULLBACK
    val windZ = homeZ - dzr * WORLD_TRANSIT_PULLBACK
    val arriveStartZ = homeZ + WORLD_TRANSIT_ARRIVE_RUNWAY

    // Swoop control point for the approach curve: the wind→enter midpoint bulged out along the
    // horizontal perpendicular of the flight line and lifted up, so the ship banks in on an arc
    // rather than a straight line.
    val perpX = dzr
    val perpZ = -dxr
    val perpLen = sqrt(perpX * perpX + perpZ * perpZ).coerceAtLeast(1e-6)
    val npx = perpX / perpLen
    val npz = perpZ / perpLen
    val ctrlX = (windX + enterX) * 0.5 + npx * WORLD_TRANSIT_SWOOP_SIDE
    val ctrlY = (windY + enterY) * 0.5 + WORLD_TRANSIT_SWOOP_UP
    val ctrlZ = (windZ + enterZ) * 0.5 + npz * WORLD_TRANSIT_SWOOP_SIDE

    spikeCamFlown = true

    /** Aim the transit camera at [lx]/[ly]/[lz] from [px]/[py]/[pz], banked [roll] radians. */
    fun aimCamera(px: Double, py: Double, pz: Double, lx: Double, ly: Double, lz: Double, roll: Double) {
        spikeCamX = px; spikeCamY = py; spikeCamZ = pz
        var fx = lx - px; var fy = ly - py; var fz = lz - pz
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6)
        fx /= fl; fy /= fl; fz /= fl
        spikeCamFx = fx; spikeCamFy = fy; spikeCamFz = fz
        var ux = 0.0; var uy = 1.0; var uz = 0.0
        if (roll != 0.0) {
            // Rodrigues-rotate the roof about the nose (forward) axis by `roll`.
            val cr = cos(roll); val sr = sin(roll)
            val crossX = fy * uz - fz * uy
            val crossY = fz * ux - fx * uz
            val crossZ = fx * uy - fy * ux
            val dot = fx * ux + fy * uy + fz * uz
            ux = ux * cr + crossX * sr + fx * dot * (1.0 - cr)
            uy = uy * cr + crossY * sr + fy * dot * (1.0 - cr)
            uz = uz * cr + crossZ * sr + fz * dot * (1.0 - cr)
        }
        spikeCamUx = ux; spikeCamUy = uy; spikeCamUz = uz
    }

    // Billboard the vortex to the camera (gentle cant for depth) at the side rift point, and
    // churn its cloud layers, exactly as the spawn wormhole does.
    val g = wt.group.asDynamic()
    wt.group.position.set(vx, vy, vz)
    g.quaternion.copy(camera.asDynamic().quaternion)
    g.rotateX(WORLD_TRANSIT_TILT)
    wt.spin += WORMHOLE_SPIN_SPEED * spikeDtFrames
    for (i in wt.rings.indices) {
        val dir = if (i % 2 == 0) 1.0 else -1.35
        wt.rings[i].style.setProperty(
            "transform",
            "translate(-50%,-50%) rotate(${wt.twists[i] + wt.spin * dir}rad)",
        )
    }

    var portalScale = 0.0
    var portalOpacity = 0.0
    var tunnelAlpha = 0.0
    var tunnelSpeed = 0.0
    var tunnelShake = 0.0
    when {
        phase < tOpen -> {
            // Open: the rift blooms open out to the side while the camera turns toward it and
            // eases back, winding up for the run.
            val o = (phase / tOpen).coerceIn(0.0, 1.0)
            val e = worldSmoother(o)
            portalScale = worldSmoother(o) + WORMHOLE_OPEN_OVERSHOOT * sin(PI * o)
            portalOpacity = min(1.0, o * 2.0)
            // Pose eases start→wind; gaze eases the point we were facing→rift so the turn isn't a
            // snap. The start pose is wherever the camera actually was when the warp was armed (the
            // live free-flight pose, or the command-center home pose), so warping from anywhere in
            // free flight glides off smoothly instead of jumping to the command center first.
            aimCamera(
                wt.startX + (windX - wt.startX) * e,
                wt.startY + (windY - wt.startY) * e,
                wt.startZ + (windZ - wt.startZ) * e,
                wt.lookStartX + (vx - wt.lookStartX) * e,
                wt.lookStartY + (vy - wt.lookStartY) * e,
                wt.lookStartZ + (vz - wt.lookStartZ) * e,
                0.0,
            )
        }
        phase < tApproach -> {
            // Approach: swoop into the rift along a banked Bézier arc (accelerating — a squared
            // ease), the vortex swelling to fill the view; the tunnel canvas fades up over the
            // last 30%. The ship rolls through a few tilts, level at both ends.
            portalScale = 1.0
            portalOpacity = 1.0
            val a = ((phase - tOpen) / WORLD_TRANSIT_APPROACH_FRAMES).coerceIn(0.0, 1.0)
            val e = a * a // accelerate in
            val om = 1.0 - e
            // Quadratic Bézier wind → ctrl → enter.
            val bx = om * om * windX + 2.0 * om * e * ctrlX + e * e * enterX
            val by = om * om * windY + 2.0 * om * e * ctrlY + e * e * enterY
            val bz = om * om * windZ + 2.0 * om * e * ctrlZ + e * e * enterZ
            // Bank weave: 0 at both ends, tilting a few ways in between (the fast term crosses
            // zero at a = 1/3, 2/3 so the ship rolls right → left → right as it flies in).
            val roll = WORLD_TRANSIT_APPROACH_ROLL * (0.4 * sin(a * PI) + 0.6 * sin(a * 3.0 * PI))
            aimCamera(bx, by, bz, vx, vy, vz, roll)
            tunnelAlpha = worldSmoother(((a - 0.70) / 0.30).coerceIn(0.0, 1.0))
            tunnelSpeed = 0.5 + 1.2 * a
            tunnelShake = 0.35 * tunnelAlpha
        }
        phase < tTunnel -> {
            // Tunnel: ride the opaque light tube — thrown up/down and rolled back and forth.
            // Swap the world at the midpoint (hidden behind the canvas) so we exit into the new
            // sky; the vortex is gone from view now.
            portalScale = 0.0
            portalOpacity = 0.0
            val u = ((phase - tApproach) / WORLD_TRANSIT_TUNNEL_FRAMES).coerceIn(0.0, 1.0)
            if (!wt.reskinned && u >= 0.5) {
                wt.reskinned = true
                applyWorldPalette(wt)
            }
            tunnelAlpha = 1.0
            // Fastest in the middle of the tube, easing at both mouths.
            tunnelSpeed = 1.4 + 1.4 * sin(PI * u)
            // Violent throughout, peaking mid-tube; the camera stays parked (hidden).
            tunnelShake = 0.7 + 0.3 * sin(PI * u)
            aimCamera(enterX, enterY, enterZ, vx, vy, vz, 0.0)
        }
        else -> {
            // Arrive: burst out far behind the ring and fly forward down +Z to the command
            // center; the tunnel fades over the first 45% and a decaying rattle shakes the
            // camera as the new world stabilises.
            val r = ((phase - tTunnel) / WORLD_TRANSIT_ARRIVE_FRAMES).coerceIn(0.0, 1.0)
            val camZ = arriveStartZ + (homeZ - arriveStartZ) * worldSmoother(r)
            // A brief exit jolt only — fully settled by r≈0.15 (while the tunnel is still
            // fading over it), so the long run to the command center is dead smooth.
            val settle = worldSmoother((1.0 - r / 0.15).coerceIn(0.0, 1.0))
            val amp = WORLD_TRANSIT_ARRIVE_SHAKE * settle
            val jx = (sin(phase * 2.1) + 0.5 * sin(phase * 4.3)) * amp
            val jy = (sin(phase * 2.7) + 0.6 * sin(phase * 5.5)) * amp * 1.1
            val roll = sin(phase * 1.6) * 0.05 * settle
            aimCamera(jx, jy, camZ, 0.0, 0.0, 0.0, roll)
            tunnelAlpha = 1.0 - worldSmoother((r / 0.45).coerceIn(0.0, 1.0))
            tunnelSpeed = 1.4 * (1.0 - r)
            tunnelShake = 0.6 * (1.0 - r)
        }
    }

    g.scale.set(portalScale, portalScale, portalScale)
    for (el in wt.elements) el.style.opacity = portalOpacity.toString()
    wt.flash.style.opacity = "0"

    // Threshold white punches: crossing into the tunnel, and bursting out the far end.
    val flashOpacity = max(
        framePulse(phase, tApproach, 13.0),
        framePulse(phase, tTunnel, 15.0),
    ).coerceIn(0.0, 1.0)
    wt.screenFlash.style.opacity = flashOpacity.toString()

    // Draw / fade the tunnel. Its colour fades from the sky we left toward the sky we're arriving
    // in ([WorldTransit.fromColor] → [toColor]), smoothly crossing the halfway hue at the tunnel
    // midpoint (where the world itself swaps, hidden behind the opaque canvas): 0 while still
    // approaching (departure sky), a smootherstep ramp through the tube, 1 all through the arrival.
    wt.canvas.style.opacity = tunnelAlpha.toString()
    if (tunnelAlpha > 0.001) {
        val colorT = when {
            phase < tApproach -> 0.0
            phase < tTunnel ->
                worldSmoother(((phase - tApproach) / WORLD_TRANSIT_TUNNEL_FRAMES).coerceIn(0.0, 1.0))
            else -> 1.0
        }
        drawTransitTunnel(wt, colorT = colorT, speed = tunnelSpeed, shake = tunnelShake)
    }

    if (phase >= tEnd) {
        val resumeFlight = wt.resumeFlight
        clearWorldTransit()
        // Drop the client-side destination preview: by now the [WindowCommand.SetActiveWorld] fired
        // at the midpoint has round-tripped and [applyActiveWorldTheme] has made the destination the
        // *active* world, so [currentWorldTheme] resolves it on its own. Repaint once so the world
        // reads from the (now-active) theme rather than the preview override.
        spikeWorldThemePreview = null
        restyleWorldChrome()
        // Hand the camera back in the mode we started in — a move, not a mode change. If we were
        // in free flight, stay flown (and in [spikeFlyMode]) parked at the new command-center pose
        // the arrive leg just landed on, so the pilot flies on from there. Otherwise drop to the
        // pristine 1:1 command-center pose (recomputed each frame).
        if (resumeFlight) {
            spikeFlyMode = true
            spikeCamFlown = true
        } else {
            spikeFlyMode = false
            spikeCamFlown = false
        }
    }
}

/**
 * Renders one frame of the **high-tech light tunnel** onto the transit canvas: a dark tube
 * glowing at its vanishing centre, warp **streaks** rushing outward past the ship, structural
 * **hoop rings** rushing toward the viewer, and a bright core at the end of the tube — the
 * whole field banking gently so it feels like flying *through* it. Advances the particle
 * fields in place (recycling any that pass the viewer) and colours everything by fading from
 * the sky we left ([WorldTransit.fromColor]) toward the sky we're arriving in ([toColor]) via
 * [colorT], so the tube visibly connects the two worlds' skies.
 *
 * The tube is not straight: a wandering **spine** ([spineX]/[spineY]) bends it into a pipe system
 * that makes soft turns in every direction (left/right *and* up/down) as you fly. The spine is a
 * vanishing point that drifts along a smooth, non-repeating sum-of-sines path; every element is
 * displaced by the spine according to how far *ahead* it sits down the tube, so the far end swings
 * off to one side (a bend rounding away) while whatever is right at the viewer stays dead ahead.
 * The bends flow toward you as [WorldTransit.tunnelTravel] advances with the ride `speed`.
 *
 * @param wt the transit whose canvas + particle fields to draw and advance.
 * @param colorT 0 = the departure sky's glow, 1 = the arrival sky's glow (linearly blended;
 *   streaks whiten toward the rim regardless). @see WorldTransit.fromColor @see skyGlowRgb
 * @param speed rush multiplier (streak growth + ring advance), 0 at the mouths, peak mid-tube.
 * @param shake 0..1 violence — how hard the whole tube lurches up/down/sideways and rolls
 *   back and forth (jittered per-frame so the passage reads as rough and irregular).
 * @see tickWorldTransit @see WORLD_TRANSIT_TUNNEL_BEND_X
 */
private fun drawTransitTunnel(wt: WorldTransit, colorT: Double, speed: Double, shake: Double) {
    val ctx = wt.ctx
    val w = window.innerWidth.toDouble()
    val h = window.innerHeight.toDouble()
    val cx = w * 0.5
    val cy = h * 0.5
    val maxR = sqrt(w * w + h * h) * 0.72
    val dt = spikeDtFrames

    // Advance the travel clock the spine is sampled against — the bends flow toward the viewer
    // faster the harder we're rushing (peaks mid-tube), so turns come thick in the belly.
    wt.tunnelTravel += WORLD_TRANSIT_TUNNEL_BEND_RATE * speed * dt
    val travel = wt.tunnelTravel

    // The wandering spine: `spine(look)` is the sideways+vertical screen offset of the tube's
    // centre-line at look-ahead `look` (0 = right at the viewer, 1 = the far vanishing point),
    // relative to screen centre. Two independent sum-of-sines (different frequencies + phases for
    // x and y, incommensurate so it never repeats) make the pipe turn left/right AND up/down in
    // ever-changing directions. Subtracting the value at the viewer end pins `look == 0` to dead
    // ahead; the `look^1.3` weight keeps near sections nearly straight while the far end swings
    // fully — the perspective read of a bend rounding away. Sampled at (travel + look*span) so the
    // curve both flows toward you (travel) and bends across the tube's length (span).
    val span = WORLD_TRANSIT_TUNNEL_BEND_SPAN
    fun wanderX(a: Double): Double = (sin(a * 0.9) + 0.5 * sin(a * 2.3 + 1.7)) / 1.5
    fun wanderY(a: Double): Double = (sin(a * 0.7 + 2.3) + 0.5 * sin(a * 1.7 + 0.6)) / 1.5
    val vx0 = wanderX(travel)
    val vy0 = wanderY(travel)
    fun spineX(look: Double): Double {
        val l = look.coerceIn(0.0, 1.0)
        return (wanderX(travel + l * span) - vx0) * l.pow(1.3) * WORLD_TRANSIT_TUNNEL_BEND_X * maxR
    }
    fun spineY(look: Double): Double {
        val l = look.coerceIn(0.0, 1.0)
        return (wanderY(travel + l * span) - vy0) * l.pow(1.3) * WORLD_TRANSIT_TUNNEL_BEND_Y * maxR
    }
    // The far vanishing point (look == 1) — where the throat and core sit, drifting with the bend.
    val vpX = spineX(1.0)
    val vpY = spineY(1.0)

    // Colour: fade the tube glow from the departure sky to the arrival sky by `colorT`; streaks
    // whiten toward the rim (below). Both endpoints are the skies' glow colours (see skyGlowRgb).
    val baseR = wt.fromColor[0] + (wt.toColor[0] - wt.fromColor[0]) * colorT
    val baseG = wt.fromColor[1] + (wt.toColor[1] - wt.fromColor[1]) * colorT
    val baseB = wt.fromColor[2] + (wt.toColor[2] - wt.fromColor[2]) * colorT

    // Dark tube with a glowing throat — a solid backdrop so the tunnel fully veils the scene.
    // The throat is a dimmed cut of the faded base so its glow shares the current sky's hue, and
    // it sits at the drifting vanishing point so the lit end of the pipe swings as you round bends.
    val gx = cx + vpX
    val gy = cy + vpY
    val bg = ctx.asDynamic().createRadialGradient(gx, gy, 0.0, gx, gy, maxR)
    val throatR = (baseR * 0.28).toInt()
    val throatG = (baseG * 0.28).toInt()
    val throatB = (baseB * 0.28).toInt()
    bg.addColorStop(0.0, "rgb(${throatR + 40},${throatG + 34},${throatB + 40})")
    bg.addColorStop(0.35, "rgb($throatR,$throatG,$throatB)")
    bg.addColorStop(1.0, "#03040a")
    ctx.fillStyle = bg
    ctx.fillRect(0.0, 0.0, w, h)

    // Violent, irregular lurch: the whole tube jumps up/down/sideways and rolls back and forth,
    // driven by layered sines plus a per-frame random kick so the passage feels rough. All
    // scaled by `shake` (0 at the mouths → full in the belly of the tunnel).
    wt.tunnelRoll += 0.09 * dt
    val t = wt.tunnelRoll
    val amp = maxR * WORLD_TRANSIT_TUNNEL_SHAKE * shake
    val jx = (sin(t * 1.7) + 0.5 * sin(t * 3.9)) * amp + Random.nextDouble(-1.0, 1.0) * amp * 0.09
    val jy = (sin(t * 2.3) + 0.6 * sin(t * 5.1)) * amp * 1.2 + Random.nextDouble(-1.0, 1.0) * amp * 0.11
    val roll = (sin(t * 0.8) + 0.4 * sin(t * 1.9)) * WORLD_TRANSIT_TUNNEL_ROLL * shake +
        Random.nextDouble(-1.0, 1.0) * 0.003 * shake

    ctx.save()
    ctx.translate(cx + jx, cy + jy)
    ctx.rotate(roll)

    val spin = wt.tunnelRoll * 0.6

    // Structural hoops rushing toward the viewer (octagons for a "tech" read).
    ctx.asDynamic().lineJoin = "round"
    for (i in wt.ringDepth.indices) {
        wt.ringDepth[i] += (0.006 + 0.010 * speed) * dt
        if (wt.ringDepth[i] > 1.0) {
            wt.ringDepth[i] -= 1.0
            wt.ringRot[i] = Random.nextDouble(0.0, 2.0 * PI)
        }
        val z = wt.ringDepth[i]
        val r = maxR * z * z * 1.15 // perspective: accelerate outward as it nears
        val a = sin(PI * z) * 0.5 // fade in then out
        if (a <= 0.01 || r < 4.0) continue
        // Centre this hoop on the spine at its look-ahead (far hoops, z→0, swing to the bend;
        // hoops passing the viewer, z→1, sit dead ahead) so the ring of hoops traces the pipe.
        val ox = spineX(1.0 - z)
        val oy = spineY(1.0 - z)
        ctx.beginPath()
        val rot = wt.ringRot[i] + spin
        val sides = 8
        for (s in 0..sides) {
            val ang = rot + s.toDouble() / sides * 2.0 * PI
            val x = ox + cos(ang) * r
            val y = oy + sin(ang) * r
            if (s == 0) ctx.moveTo(x, y) else ctx.lineTo(x, y)
        }
        ctx.lineWidth = 1.0 + z * 2.5
        ctx.strokeStyle = "rgba(${baseR.toInt()},${baseG.toInt()},${baseB.toInt()},$a)"
        ctx.stroke()
    }

    // Warp streaks fanning outward — the light lines that sell forward speed.
    ctx.asDynamic().lineCap = "round"
    for (i in wt.streakAng.indices) {
        val ang = wt.streakAng[i]
        val prev = wt.streakRad[i]
        wt.streakRad[i] += (1.2 + wt.streakRad[i] * 0.045) * speed * wt.streakSpd[i] * dt
        if (wt.streakRad[i] > maxR) {
            wt.streakRad[i] = Random.nextDouble(2.0, 40.0)
            wt.streakAng[i] = Random.nextDouble(0.0, 2.0 * PI)
            continue
        }
        val rad = wt.streakRad[i]
        val c = cos(ang)
        val s = sin(ang)
        val frac = rad / maxR
        // Whiten and brighten toward the rim; near the throat streaks are faint.
        val white = frac * frac * 0.85
        val rr = (baseR + (255.0 - baseR) * white).toInt()
        val gg = (baseG + (255.0 - baseG) * white).toInt()
        val bb = (baseB + (255.0 - baseB) * white).toInt()
        val alpha = (0.15 + frac * 0.85).coerceIn(0.0, 1.0)
        // Bend the streak along the spine: its inner (throat) end is far ahead so it rides the
        // bent vanishing point, its outer (rim) end is right at the viewer so it straightens to
        // centre — each streak becomes a curved radial line following the pipe as it turns.
        val fracPrev = prev / maxR
        val oxN = spineX(1.0 - fracPrev); val oyN = spineY(1.0 - fracPrev)
        val oxF = spineX(1.0 - frac); val oyF = spineY(1.0 - frac)
        ctx.beginPath()
        ctx.moveTo(oxN + c * prev, oyN + s * prev)
        ctx.lineTo(oxF + c * rad, oyF + s * rad)
        ctx.lineWidth = 0.6 + frac * 2.6
        ctx.strokeStyle = "rgba($rr,$gg,$bb,$alpha)"
        ctx.stroke()
    }

    ctx.restore()

    // Cosmic energy pulse — a throat-centred *additive* glow that throbs, flickers and rarely
    // flares toward white across the whole screen, so the tube feels alive and cosmic rather than
    // static. Drawn un-shaken (like the throat) and centred on the same vanishing point `gx`/`gy`,
    // so it reads as light surging up the tube from its depths. Milder at the mouths, full mid-tube
    // (scaled by `shake`, the same 0-at-the-mouths → full-in-the-belly envelope).
    if (WORLD_TRANSIT_TUNNEL_PULSE > 0.0) {
        val pt = wt.tunnelRoll
        val throb = 0.35 + 0.35 * sin(pt * 0.9) // slow swell
        val shimmer = 0.30 * (0.5 + 0.5 * sin(pt * 3.3 + 1.0)) // faster flicker
        val surge = max(0.0, sin(pt * 0.41 + 0.7)).pow(5.0) // rare bright flares
        val flicker = Random.nextDouble(0.0, 1.0) * 0.08 // per-frame sparkle
        val depth = 0.45 + 0.55 * shake
        val pulse = throb + shimmer + flicker + 0.9 * surge
        val a0 = (pulse * WORLD_TRANSIT_TUNNEL_PULSE * depth).coerceIn(0.0, 0.85)
        // Whiten the centre during surges so the big flares punch toward white.
        val whiten = surge * 0.8
        val cr = (baseR + (255.0 - baseR) * whiten).toInt()
        val cg = (baseG + (255.0 - baseG) * whiten).toInt()
        val cb = (baseB + (255.0 - baseB) * whiten).toInt()
        val glow = ctx.asDynamic().createRadialGradient(gx, gy, 0.0, gx, gy, maxR * 0.95)
        glow.addColorStop(0.0, "rgba($cr,$cg,$cb,$a0)")
        glow.addColorStop(0.45, "rgba(${baseR.toInt()},${baseG.toInt()},${baseB.toInt()},${a0 * 0.45})")
        glow.addColorStop(1.0, "rgba(0,0,0,0)")
        ctx.save()
        ctx.asDynamic().globalCompositeOperation = "lighter"
        ctx.fillStyle = glow
        ctx.fillRect(0.0, 0.0, w, h)
        // On the strongest surges give the *whole* screen a faint additive white flash.
        val flat = (surge * 0.14 * WORLD_TRANSIT_TUNNEL_PULSE * depth).coerceIn(0.0, 0.22)
        if (flat > 0.004) {
            ctx.fillStyle = "rgba(255,255,255,$flat)"
            ctx.fillRect(0.0, 0.0, w, h)
        }
        ctx.restore()
    }

    // Bright core — the light at the end of the tube (feature-flagged off by default; the throat
    // glow + energy pulse stand in for it). Drawn *outside* the shaken/rolled frame, concentric
    // with the backdrop throat glow at the (un-shaken) vanishing point `gx`/`gy`, so the bright
    // ball stays glued to the throat instead of wobbling off it during the lurch.
    if (WORLD_TRANSIT_TUNNEL_CORE) {
        val core = ctx.asDynamic().createRadialGradient(gx, gy, 0.0, gx, gy, maxR * 0.22)
        core.addColorStop(0.0, "rgba(255,255,255,0.9)")
        core.addColorStop(0.4, "rgba(${(baseR + 40).toInt().coerceAtMost(255)},${(baseG + 20).toInt().coerceAtMost(255)},${baseB.toInt()},0.5)")
        core.addColorStop(1.0, "rgba(0,0,0,0)")
        ctx.fillStyle = core
        ctx.beginPath()
        ctx.asDynamic().arc(gx, gy, maxR * 0.22, 0.0, 2.0 * PI)
        ctx.fill()
    }
}

/**
 * Re-skins the **open** world to the destination world's theme in place: previews that theme
 * client-side ([spikeWorldThemePreview]), fires [WindowCommand.SetActiveWorld] so the server
 * makes it the real active world (the config round-trip then auto-repaints via
 * [applyActiveWorldTheme]), repaints the sky + every pane's chrome/terminal + the beacon glyphs
 * via [restyleWorldChrome] (which reads the preview through [currentWorldTheme] for both the sky
 * and the chrome), and shows the arrival **banner** naming the world. Called at the tunnel
 * midpoint of [tickWorldTransit], behind the opaque canvas, so the swap is invisible until we
 * exit.
 *
 * There is no hardcoded per-world colour here: setting the preview re-points [currentWorldTheme]
 * at the destination world's theme pair and the whole world (sky included) re-resolves from it,
 * and the [WindowCommand.SetActiveWorld] round-trip keeps the 2D app and the server in step.
 *
 * @param wt the in-flight transit carrying the destination id / theme / name.
 * @see spikeWorldThemePreview @see restyleWorldChrome @see currentWorldTheme
 */
internal fun applyWorldPalette(wt: WorldTransit) {
    spikeWorldThemePreview = wt.destinationSelection
    // Switch the real active world so the whole app (2D + the server) follows; the config
    // re-broadcast repaints via [applyActiveWorldTheme]. Null when there is no distinct next
    // world (fewer than two worlds) — the preview above then simply re-resolves the same world.
    wt.destinationWorldId?.let { launchCmd(WindowCommand.SetActiveWorld(it)) }
    restyleWorldChrome() // repaints sky + pane chrome + terminals + beacon glyphs from the preview
    updateWorldBanner(wt.destName)
}

/**
 * Adds or updates the small top-centre arrival **banner** naming the world we've flown into, so
 * you know which command center you've just entered — then fades it away a few seconds later.
 * The banner is a transient "you are here" cue, not a permanent HUD element: every call restarts
 * a [WORLD_BANNER_HOLD_MS] hold timer, after which it fades out and removes itself. Idempotent; an
 * empty [name] removes it immediately. Called on initial 3D entry ([openWorld3dSpike]) and at each
 * transit's arrival ([applyWorldPalette]).
 *
 * @param name the arriving world's display name (uppercased for the banner), or empty to clear.
 * @see applyWorldPalette @see spikeWorldBannerTimer
 */
internal fun updateWorldBanner(name: String) {
    val overlay = spikeOverlay ?: return
    spikeWorldBannerTimer?.let { window.clearTimeout(it) }
    spikeWorldBannerTimer = null
    val existing = overlay.querySelector(".spike-world-banner") as? HTMLElement
    if (name.isBlank()) {
        existing?.remove()
        return
    }
    val banner = existing ?: (document.createElement("div") as HTMLElement).also {
        it.className = "spike-world-banner"
        overlay.appendChild(it)
    }
    // The banner glow reads from the arriving world's theme accent (the same source [spikeChrome]
    // paints the pane accents from), so it stays in step with the world's theme rather than a
    // hardcoded amber — falling back to the resolved chrome accent if the theme can't resolve.
    val accent = runCatching { argbToCss(currentWorldTheme().accent) }.getOrNull()
        ?: spikeChromeColors?.accent ?: "#f7a24f"
    banner.textContent = name.uppercase()
    banner.style.cssText = "position:absolute;top:22px;left:50%;transform:translateX(-50%);" +
        "z-index:6;pointer-events:none;font:600 12px/1 ui-monospace,Menlo,monospace;" +
        "letter-spacing:3px;color:$accent;text-shadow:0 0 18px $accent;" +
        // `${accent}55` = the accent at ~33% alpha; theme accent tokens resolve to opaque
        // `#rrggbb`, so the 8-digit-hex suffix is valid (matches the old translucent border).
        "padding:6px 14px;border:1px solid ${accent}55;border-radius:5px;" +
        "background:rgba(20,10,4,0.42);opacity:1;transition:opacity 160ms ease;"
    // Hold the banner for a few seconds, then fade it out and remove it, mirroring the nav
    // label's transient behaviour. Restarted on every call so a rapid entry→switch keeps the
    // latest name up for the full hold rather than clearing early.
    spikeWorldBannerTimer = window.setTimeout({
        banner.style.transition = "opacity 700ms ease"
        banner.style.opacity = "0"
        spikeWorldBannerTimer = window.setTimeout({ banner.remove() }, 750)
    }, WORLD_BANNER_HOLD_MS)
}

/** How long (ms) the world-arrival banner holds at full opacity before fading out. @see updateWorldBanner */
private const val WORLD_BANNER_HOLD_MS = 3_500

/**
 * Tears down the in-flight [spikeWorldTransit]: removes the tunnel canvas, whiteout and vortex
 * DOM and drops the vortex group from the scene. Called on transit completion by
 * [tickWorldTransit] and on world close by [closeWorld3dSpike] (where the overlay goes down
 * wholesale, so the DOM removals are belt-and-braces). Leaves [spikeWorldThemePreview] to its
 * callers — completion clears it after the switch lands; a close resets it. No-op if no transit
 * is running.
 *
 * @see enterOrExitOtherWorld
 */
internal fun clearWorldTransit() {
    val wt = spikeWorldTransit ?: return
    spikeWorldTransit = null
    spikeWorldTransitOccluding = false
    for (el in wt.elements) runCatching { el.remove() }
    runCatching { wt.screenFlash.remove() }
    runCatching { wt.canvas.remove() }
    runCatching { spikeCssScene?.asDynamic()?.remove(wt.group) }
}
