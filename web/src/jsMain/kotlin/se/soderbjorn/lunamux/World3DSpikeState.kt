/*
 * Split from World3DSpike.kt — module-level mutable spike state.
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

/** Whether the spike overlay is currently open. */
internal var spikeOpen = false

/** The full-screen overlay root, or `null` when closed. */
internal var spikeOverlay: HTMLElement? = null

/**
 * The 2D app shell (`#app`) hidden while the world is open, or `null` when the
 * world is closed / the shell was never found. Kept so [closeWorld3dSpike] can
 * restore its prior inline `visibility` on the way out. Hiding the 2D shell means
 * that if a compositor tile is dropped under memory pressure (the "tile memory
 * limits exceeded" flicker), a see-through gap in the 3D overlay reveals nothing
 * but empty space — never the old 2D interface. @see WORLD3D_PERFORMANCE_ANALYSIS
 */
internal var spikeHiddenShell: HTMLElement? = null

/** The `#app` shell's inline `visibility` before the world hid it, restored on close. */
internal var spikeHiddenShellVis: String = ""

/** The "SELECT MODE" badge shown while selection mode is active, or `null` when closed. */
internal var spikeModeBadge: HTMLElement? = null

/**
 * The amber "press ⌥X again to remove" confirmation banner, or `null` when closed.
 * Fades in when a removal is armed (first ⌥X) and out when it is confirmed or
 * cancelled. @see requestRemoveFocused
 */
internal var spikeConfirmBadge: HTMLElement? = null

/**
 * Whether a removal is armed and awaiting a confirming second ⌥X. Set by the first
 * ⌥X on a removable front pane or empty tab; cleared on confirm, on any other
 * navigate key, or when the arm times out. @see requestRemoveFocused
 */
internal var spikeRemoveArmed = false

/** The auto-cancel timer for an armed removal (fires after [REMOVE_ARM_MS]), or `null`. */
internal var spikeRemoveArmTimer: Int? = null

/** How long (ms) an armed removal stays live before it auto-cancels for safety. */
internal const val REMOVE_ARM_MS = 4000

/**
 * The active theme colours (as CSS strings) the pane chrome paints with, resolved
 * once per open from the live [ResolvedTheme] so the ring's title strips / borders
 * match 2D mode instead of the old hard-coded slate. `null` while closed.
 */
internal var spikeChromeColors: SpikeChrome? = null

/** The CSS3D renderer that transforms the reparented terminal planes. */
internal var spikeCss3d: CSS3DRenderer? = null

/** The CSS3D scene holding the pane planes. */
internal var spikeCssScene: Scene? = null

/** The shared perspective camera. */
internal var spikeCamera: PerspectiveCamera? = null

/** The reparented panes, in ring order (grouped by tab). */
internal var spikePanes: MutableList<RingPane> = mutableListOf()

/** Placeholder cards for empty tabs (tabs with no panes), one per empty tab. */
internal var spikeEmptyTabs: MutableList<EmptyTabCard> = mutableListOf()

/**
 * Live subscription to [se.soderbjorn.lunamux.client.WindowSocket.config] while
 * the spike is open: every config change (a pane/tab created or closed — by the
 * spike's own `t`/`n`/`x` keys or by the 2D app) drives [reconcileRing], which
 * animates panes and empty-tab cards in and out. Cancelled in [closeWorld3dSpike].
 */
internal var spikeConfigJob: Job? = null

/**
 * Live subscription to the toolkit `LAYOUT_STATE` broadcasts
 * ([se.soderbjorn.lunamux.client.WindowStateRepository.rawLayoutState]) while the
 * spike is open, driving [syncStashFromMinimized] — a pane minimized (docked) or
 * restored from any client moves between the ring and the shelf live. Cancelled in
 * [closeWorld3dSpike]. @see persistPaneMinimized
 */
internal var spikeLayoutJob: Job? = null

/**
 * Auto-focus requests set when the spike issues a create command, honoured by the
 * next [reconcileRing] so creating something snaps you to it. [spikePendingFocusTab]
 * is the tab whose newest pane to front (set by [createPane]); [spikePendingFocusNewTab]
 * asks to front the last (newest) tab once it appears (set by [createTab]).
 */
internal var spikePendingFocusTab: String? = null
internal var spikePendingFocusNewTab = false

/**
 * Pane ids of panes freshly created via [createPane] (`n`) that should be pinned to the
 * **end** of their tab's display order. A new pane starts at the ring tail (it isn't in
 * the toolkit `paneOrderByTab` blob yet, so [toolkitPaneOrder] ranks it last), but the
 * moment the toolkit registers it — typically adjacent to the split source, not last —
 * [reconcileRing] renumbers it and it slides inward. [pinNewPanesLast] watches this set
 * and, once the toolkit first lists such a pane, rewrites the order to append it (a
 * one-shot: the id is dropped as soon as it lands last or its pane is gone), so a newly
 * created pane stays at the end of the row instead of wedging in beside the current one.
 * @see createPane @see pinNewPanesLast
 */
internal val spikePinLastPanes: MutableSet<String> = mutableSetOf()

/** Index of the tab currently at the front (its panes fan on the ring). */
internal var spikeTabIndex = 0

/** Eased vertical scroll toward [spikeTabIndex] (the "floor" position). */
internal var spikeTabScroll = 0.0

/** Eased horizontal scroll toward the current tab's selected pane. */
internal var spikePaneScroll = 0.0

/** Selected pane ordinal per tab (indexed by tab ordinal). */
internal var spikeTabSel: MutableList<Int> = mutableListOf()

/** The pane index that is settled at the front, or -1 while moving. */
internal var spikeSettledIndex = -1

/**
 * Whether the spike is **engaged** (Enter) — keystrokes go to the focused
 * terminal. An *explicit* flag, not derived from DOM focus, because the app
 * aggressively re-focuses its active pane (even a direct `blur()` bounces back):
 * in **navigate** mode ([spikeEngaged] false) the key handler consumes every key
 * so a still-focused terminal underneath never sees it, so navigation works
 * regardless of what the browser thinks is focused. Set by [activateFront],
 * cleared by [disengage].
 */
internal var spikeEngaged = false

/** Tab / pane the user last engaged — activated in the app on close so you land there. */
internal var spikeLastEngagedTab: String? = null
internal var spikeLastEngagedPane: String? = null

/** Common screen-like plane size (px) used by [SPIKE_UNIFORM_SCREENS] and mirror panes. */
internal var spikeScreenW = PANE_W
internal var spikeScreenH = PANE_H

/** Set so the first render-loop frame runs [postOpenLayout] once (after elements are attached). */
internal var spikeNeedsInitialLayout = false

/** The front pane's *target* visual zoom, bumped by each +/−. */
internal var spikeZoomTarget = 1.0

/**
 * True while the front pane should ease toward [spikeZoomTarget] at the slow
 * [ZOOM_PRESET_EASE] instead of the snappy [SCALE_EASE]. Set by the ⇧ zoom
 * presets and the `0`/⇧0 reset (whose one-jump targets are far away and would
 * otherwise read as a snap), cleared by the render loop once the pane arrives,
 * and by the other zoom paths (`+`/`−` steps, [loadFrontZoom] on navigation) so
 * only the big jumps glide.
 *
 * @see zoomFrontTo @see ZOOM_PRESET_EASE
 */
internal var spikeZoomGlide = false

/**
 * Physical `KeyboardEvent.code`s of the keys that produce **`+` / `-` / `0`** on the
 * user's actual keyboard layout — what the ⇧ zoom presets in [buildKeyHandler] match
 * against. Seeded with the US-layout physical positions and re-resolved from the
 * live layout by [resolveZoomPresetCodes] on every world open, because the presets
 * must match on `code` (⇧ rewrites `ke.key`) but the *position* of `+`/`-` differs
 * per layout: on a Swedish layout `+` sits on code `Minus` and `-` on code `Slash`,
 * so hard-coded US codes made ⇧+ hit the zoom-floor preset instead of fit.
 *
 * @see resolveZoomPresetCodes @see buildKeyHandler
 */
internal var spikeZoomPlusCodes: Set<String> = setOf("Equal", "NumpadAdd")

/** Codes producing `-` on the user's layout — the ⇧− zoom-floor preset. @see spikeZoomPlusCodes */
internal var spikeZoomMinusCodes: Set<String> = setOf("Minus", "NumpadSubtract")

/** Codes producing `0` on the user's layout — the ⇧0 1:1-reset preset. @see spikeZoomPlusCodes */
internal var spikeZoomZeroCodes: Set<String> = setOf("Digit0", "Numpad0")

/**
 * Per-pane **visual zoom** memory (paneId → zoom multiplier), so a pane you `+`/`−`
 * keeps its magnification when you navigate away and back. A module-level map that
 * survives the overlay closing/reopening; zoom changes are also written through to
 * the server (`Pane.zoom`, via [WindowCommand.SetPaneZoom] in [zoomFront] /
 * [resetFrontZoom]) so they survive an app restart too — on a fresh run
 * [seedZoomFromConfig] restores the persisted values into this map, which remains
 * the render loop's single read path. Local-first: a session entry is never
 * overwritten by a config broadcast.
 *
 * (Grid size needs no such memory: under the PTY-truth model — see
 * [presentPaneToGrid] — the PTY itself remembers each pane's cols/rows, and every
 * rebuilt pane follows it via the server's `Size` broadcast.)
 */
internal val spikeZoomByPane = mutableMapOf<String, Double>()

/** Whether the front pane is in selection mode (snapped to 1:1 for drag-select). */
internal var spikeSelectionMode = false

/**
 * **Stashes** — the ordered ids of panes that have been sent up to the floating
 * *shelf* above the sphere (Space in navigate mode). The list index doubles as each
 * pane's slot in the shelf's left-to-right row, so stashing appends to the end of the
 * row and unstashing frees a slot. The render loop lerps every pane between its ring
 * slot and [stashShelfPos] of its slot, and navigation skips stashed panes so the
 * ring closes to a gap where they were.
 *
 * Stash **is** the 2D minimize (dock) state: [stashFront] / [unstashNearest] write
 * each change through to the toolkit `LAYOUT_STATE` blob's per-pane `isMinimized`
 * flag ([persistPaneMinimized]), which the server persists and broadcasts — so a
 * stashed pane shows docked in the 2D layout, a pane docked in 2D rides the shelf
 * here ([syncStashFromMinimized]), and the shelf survives app restarts. Re-seeded
 * from the minimized panes on every [openWorld3dSpike].
 * @see cameraAtShelf @see toggleStash
 */
internal val spikeStashed: MutableList<String> = mutableListOf()

/** Advancing phase (radians) for the "working" background-pane breath; ticked each frame. */
internal var spikePulsePhase = 0.0

/** Advancing `stroke-dashoffset` (px) that makes the jagged "working" border run; ticked each frame. */
internal var spikeBorderDash = 0.0

/** Advancing phase (radians) for the urgent "needs input" ([WAITING_GLOW_COLOR]) halo breath; ticked each frame. */
internal var spikeWaitPhase = 0.0

/** Advancing phase (radians) for the idle up/down **bob** of unfocused panes; ticked each frame. */
internal var spikeBobPhase = 0.0

/**
 * The home beacon's inner **spin group** (its `rotation.y` is the spin about the
 * arrow's pointing axis), or `null` when closed. The outer aim group needs no handle
 * after build — its tilt is fixed. Built by [buildHomeBeacon]; spun each frame by the
 * render loop by [spikeBeaconPhase]; cleared by [clearHomeBeacon].
 */
internal var spikeBeaconSpin: se.soderbjorn.lunamux.three.Group? = null

/** Advancing spin angle (radians) of the home beacon; ticked each frame. @see spikeBeaconSpin */
internal var spikeBeaconPhase = 0.0

/**
 * The home beacon's **"COMMAND CENTER" banner** — a flat text plane floating above
 * the chevron, or `null` when closed. Unlike the spun chevrons it is **billboarded**
 * to the camera every frame (its own orientation is meaningless), so the words stay
 * upright and readable from any free-fly angle. Built by [buildHomeBeacon]; faced to
 * the camera by the render loop; cleared by [clearHomeBeacon]. @see spikeBeaconSpin
 */
internal var spikeBeaconLabel: se.soderbjorn.lunamux.three.CSS3DObject? = null

/**
 * The stash beacon's inner **spin group** (its `rotation.y` is the spin about the
 * vertical axis), or `null` when closed. Built by [buildStashBeacon]; counter-spun each
 * frame by the render loop via [spikeStashBeaconPhase]; cleared by [clearStashBeacon].
 * @see spikeBeaconSpin
 */
internal var spikeStashBeaconSpin: se.soderbjorn.lunamux.three.Group? = null

/** Advancing spin angle (radians) of the stash beacon; ticked each frame. @see spikeStashBeaconSpin */
internal var spikeStashBeaconPhase = 0.0

/**
 * The stash beacon's **"STASH" banner** — a flat text plane fixed in world space above
 * the crystal, or `null` when closed. Like the home banner it neither spins nor
 * billboards: it faces +Z toward the stash-view landing pose and turns in 3D as you
 * fly. Built by [buildStashBeacon]; cleared by [clearStashBeacon]. @see spikeBeaconLabel
 */
internal var spikeStashBeaconLabel: se.soderbjorn.lunamux.three.CSS3DObject? = null

/**
 * The decorative celestial bodies (planets, moons, nebulae, star clusters) dressing
 * the sky, in build order. Filled by [buildCosmos] once per open; billboarded and
 * drifted every frame by [tickCosmos]; cleared by [clearCosmos] on close (the DOM
 * goes down with the overlay). @see SpikeCosmosBody
 */
internal val spikeCosmos: MutableList<SpikeCosmosBody> = mutableListOf()

/**
 * The **stash station** hull group (the enclosing hangar around the shelf), or `null`
 * when closed or the feature is off. A [se.soderbjorn.lunamux.three.Group] holding every
 * wall / door-frame [CSS3DObject]; built once per open by [buildStashStation] and cleared
 * by [clearStashStation] (the DOM goes down with the overlay). Its presence also gates the
 * fly-through-the-door cinematic ([stationBuilt]). @see buildStashStation
 */
internal var spikeStashStation: se.soderbjorn.lunamux.three.Group? = null

/**
 * Optional **follow-on flight** chained after the current cinematic tour lands — the
 * mechanism behind the two-leg fly-through-the-door journeys ([flyStationEnter] in, and
 * [resetCamera]'s hangar return out). Set by [flyCamTo]'s `then` parameter; the render loop invokes it the
 * frame the tour completes (which typically arms the *next* [flyCamTo], continuing the
 * chain) and clears it. `null` for an ordinary one-leg flight. @see flyCamTo @see startSpikeLoop
 */
internal var spikeCamTourThen: (() -> Unit)? = null

/**
 * The **active stash chase**, or `null` when no stash/unstash flight is under way. While
 * set, the render loop drives the camera as a chase cam trailing the pane
 * ([tickStashChase]) instead of running a scripted [flyCamTo] arc — so you watch the pane
 * sail up to (or down from) the station the whole way. Armed by [armStashChase] from
 * [stashFront] / [unstashNearest]; cleared when the pane reaches its destination.
 * @see StashChase @see stationBuilt
 */
internal var spikeStashChase: StashChase? = null

/**
 * Eased 0..1 **chase spotlight** weight — rises toward 1 while a [spikeStashChase] runs and
 * falls to 0 otherwise. The render loop fades every pane *except* the chased one by this
 * amount, so the ring's neighbour panes (which sit right at the camera as it leaves or
 * returns to the ring) can't occlude the travelling pane. @see STASH_CHASE_FOCUS_EASE
 */
internal var spikeChaseFocus = 0.0

/**
 * The **wormhole spawns** currently in flight — normally empty, or holding a single
 * [WormholeSpawn] while a newly-created pane is being born through a vortex. Armed by
 * [armWormholeSpawn], advanced/drawn each frame by [tickWormhole], and cleared by
 * [clearWormholes] on close (the portal DOM goes down with the overlay). @see WormholeSpawn
 */
internal val spikeWormholes: MutableList<WormholeSpawn> = mutableListOf()

/**
 * The **shelf browse index** — which shelf slot the camera is parked in front of while
 * up at the stash shelf ([cameraAtShelf]). ←/→ in navigate mode glide the camera from
 * slot to slot ([shelfBrowse]) the way they walk panes around the ring down below;
 * Space unstashes the nearest (= browsed) pane. Seeded by every flight that parks at
 * the shelf ([stashFront], [toggleStashView]); `-1` when not meaningful.
 */
internal var spikeShelfIndex = -1

/**
 * Whether the idle pane — **and the free-fly spaceship** — bob is on. Persisted as the
 * `world3dWindowBobbing` setting; seeded here at every open by
 * [syncWorld3dRuntimeFromSettings] and live-updated by the in-world settings panel.
 */
internal var spikeBobEnabled = true

/**
 * How working / waiting panes signal their state — the persisted **Status indication**
 * setting (`world3dStatusIndication`), collapsing the old warp-core toggle and
 * working-style cycle into one choice:
 *  - [NONE] — no per-pane status visuals at all.
 *  - [GLOW] — a soft outward halo only (green for working, amber for waiting).
 *  - [GLOW_ANIMATION] — the halo plus the animated dotted border.
 *  - [REACTOR] — the full warp-core reactor cinematic (blue charge / amber HOLD), which
 *    supersedes the glow/dots for working & waiting panes ([tickWarpCore]).
 * Seeded at open by [syncWorld3dRuntimeFromSettings]; live-updated by the in-world
 * settings panel. @see tickWarpCore @see spikeStatusShowGlow @see spikeStatusShowDots
 */
internal enum class StatusIndication { NONE, GLOW, GLOW_ANIMATION, REACTOR }

/** The active [StatusIndication]; defaults to [StatusIndication.REACTOR]. */
internal var spikeStatusIndication = StatusIndication.REACTOR

/** Whether [spikeStatusIndication] paints the outward glow halo (working green / waiting amber). */
internal val spikeStatusShowGlow: Boolean
    get() = spikeStatusIndication == StatusIndication.GLOW ||
        spikeStatusIndication == StatusIndication.GLOW_ANIMATION

/** Whether [spikeStatusIndication] paints the animated dotted border. */
internal val spikeStatusShowDots: Boolean
    get() = spikeStatusIndication == StatusIndication.GLOW_ANIMATION

/**
 * A monotonic **seconds** clock for the warp-core effect, advanced once per frame by the
 * render loop (`+= spikeDtFrames / 60.0`). Drives the reactor breath, the awaiting
 * heartbeat + escalation, sonar-ping cadence and discharge shaping — all of which the
 * spec expresses in wall-clock seconds. Only meaningful while [spikeStatusIndication] is REACTOR.
 * @see tickWarpCore @see tickWarpCoreOverlay
 */
internal var spikeWarpClock = 0.0

/** The shortcuts legend panel, so `k` can hide/show it; `null` when closed. */
internal var spikeLegendPanel: HTMLElement? = null

/** The in-world 3D settings overlay window (toggled by ⌥⌘,), or `null` when not shown. */
internal var spikeSettingsPanel: HTMLElement? = null

/**
 * The free-fly shortcuts legend panel (same style as [spikeLegendPanel],
 * swapped in at the same bottom-left spot while [spikeFlyMode] is on);
 * `null` when closed. @see updateLegendVisibility
 */
internal var spikeFlyLegendPanel: HTMLElement? = null

/**
 * The engage / type shortcuts legend panel (same style as [spikeLegendPanel],
 * swapped in at the same bottom-left spot while [spikeEngaged] is on — a pane is
 * engaged and typing, so only the disengage chord is live); `null` when closed.
 * @see updateLegendVisibility
 */
internal var spikeEngageLegendPanel: HTMLElement? = null

/**
 * The big "Play demo tour" button stacked above the shortcuts legend — the
 * clickable twin of the ⌥⌘M chord. Built only in the web demo
 * ([isDemoClient] in a plain browser, not [isElectronClient]): the Electron
 * demo keeps the tour hotkey-only, and outside demo mode the tour has no
 * simulated sessions to drive; `null` when closed. @see updateDemoTourButton
 */
internal var spikeDemoTourButton: HTMLElement? = null

/**
 * Timer ending the tour button's attention pulse (~15 s after the chrome is
 * built), or `null` once the pulse has stopped. @see updateDemoTourButton
 */
internal var spikeDemoTourPulseTimer: Int? = null

/**
 * The small "or explore yourself" reassurance line under the tour button —
 * hidden while the tour plays (the keys are locked out then, so the promise
 * would be false); `null` when closed or outside the web demo.
 * @see updateDemoTourButton
 */
internal var spikeDemoTourHint: HTMLElement? = null

/**
 * Whether the user hid the shortcut legends with `k`. One flag for **both**
 * legends — hiding shortcuts in navigate mode hides them in fly mode too.
 * Kept for the app run like the other spike memories, so reopening the
 * world honours the choice.
 */
internal var spikeLegendHidden = false

/**
 * Legend rows keyed by shortcut id, for the keypress flash
 * ([flashShortcut]): navigate-mode rows and fly-mode rows in separate maps
 * because both panels exist at once (only one is visible). Rebuilt with
 * the chrome, cleared on close.
 */
internal val spikeLegendRows = mutableMapOf<String, HTMLElement>()

/** Fly-mode legend rows keyed by shortcut id. @see spikeLegendRows */
internal val spikeFlyLegendRows = mutableMapOf<String, HTMLElement>()

/** Engage-mode legend rows keyed by shortcut id. @see spikeLegendRows */
internal val spikeEngageLegendRows = mutableMapOf<String, HTMLElement>()

/**
 * The navigate-mode legend's **section rows** (each section's caption + the divider
 * line above it) — the non-shortcut chrome rows [updateLegendVisibility] hides while
 * the camera is up at the dock, so the trimmed shelf legend never shows an orphan
 * heading or rule. Populated for the COMMAND CENTER panel only; rebuilt with the
 * chrome, cleared on close. @see spikeLegendRows
 */
internal val spikeLegendSectionRows = mutableListOf<HTMLElement>()

/** The big "now showing" pane-name label that fades in on navigation; `null` when closed. */
internal var spikeNavLabel: HTMLElement? = null

/** Timer handle for the nav-label's auto fade-out (cancelled/restarted on each cycle). */
internal var spikeNavLabelTimer: Int? = null

/**
 * The single **status toast** currently on screen (the "Screenshot saved to
 * Desktop" / "Recording saved to Desktop" pill), or `null` when none is showing.
 * Tracked so a new toast dismisses the previous one immediately rather than
 * stacking, and — critically — so [captureWindowScreenshot] can tear an earlier
 * toast down *before* it snaps the window, keeping a stale confirmation pill out
 * of the screenshot. @see showSpikeToast @see dismissSpikeToast
 */
internal var spikeActiveToast: HTMLElement? = null

/** Pending fade/removal timer handles for [spikeActiveToast], cleared when it is dismissed. */
internal var spikeActiveToastTimers: MutableList<Int> = mutableListOf()

/**
 * The live `MediaRecorder` while the 3D world is being screen-recorded, else
 * `null`. Recording captures the composited window (World3D is a CSS3DRenderer
 * with no WebGL canvas to `captureStream()`), toggled by `⇧R`. @see toggleWindowRecording
 */
internal var spikeMediaRecorder: dynamic = null

/** The desktop-capture `MediaStream` feeding [spikeMediaRecorder]; its tracks are stopped on finalize. */
internal var spikeRecordingStream: dynamic = null

/** Accumulated `MediaRecorder` `Blob` chunks for the in-flight recording (a JS array), or `null`. */
internal var spikeRecordingChunks: dynamic = null

/** Whether a screen recording is currently in progress. Guards the `⇧R` toggle. @see toggleWindowRecording */
internal var spikeRecording: Boolean = false

/**
 * Target world-x the **shelf pan** is easing the camera toward, or `null` when no pan
 * is active. Set by [shelfBrowse] to the browsed slot's x; the render loop trucks
 * [spikeCamX] toward it each frame (fixed y/z and straight-ahead gaze — a clean lateral
 * dolly along the dock row) and clears it on arrival. Any cinematic flight ([flyCamTo])
 * or station chase ([armStashChase]) clears it so a pending pan can't fight a journey.
 * @see SHELF_PAN_EASE @see shelfBrowse
 */
internal var spikeShelfPanTargetX: Double? = null

/**
 * Last-seen `cameraAtShelf()` value the legend was rendered for — the render loop
 * watches for a change and re-runs [updateLegendVisibility] when the camera crosses
 * in/out of the dock, so the shortcuts table trims to (or restores from) the
 * dock-only subset ([SHELF_SHORTCUT_IDS]) without polling the DOM every frame.
 */
internal var spikeLegendAtShelf: Boolean = false

/**
 * **Free-fly camera** state — a true 6-DOF *spaceship* flight model. The pose is a
 * position ([spikeCamX]/Y/Z) plus an **orientation basis**: the nose direction
 * [spikeCamFx]/Fy/Fz (forward) and the roof direction [spikeCamUx]/Uy/Uz (up). The
 * right vector is derived on demand as forward × up. Keeping orientation as an
 * orthonormal basis — rather than Euler yaw/pitch angles — is what makes **roll** a
 * real, first-class axis and steering gimbal-free: every rotation is applied about
 * the ship's own *local* axes, so "up" is always relative to the cockpit.
 *
 * While [spikeFlyMode] is on (toggled by `F`), the held keys in [spikeFlyKeys] drive
 * it every frame: `W`/`S` pitch the nose and `Q`/`E` roll it (left hand), while ↑/↓
 * fire the main engine forward/reverse along wherever the nose points and ←/→ yaw it
 * (right hand). `F` again fixates the pose and hands the arrows back to pane navigation.
 *
 * [spikeCamFlown] is false while the camera still sits at the pristine default pose
 * (recomputed each frame so the front pane stays exactly 1:1 through window
 * resizes); the first fly input flips it true and the absolute pose takes over.
 * [spikeCamReturning] runs the smooth `c` fly-back: the pose eases to default and,
 * once there, drops back to pristine 1:1.
 */
internal var spikeFlyMode = false
internal val spikeFlyKeys = mutableSetOf<String>()

internal var spikeCamFlown = false
internal var spikeCamReturning = false
internal var spikeCamX = 0.0
internal var spikeCamY = 0.0
internal var spikeCamZ = 0.0
// Orientation basis (orthonormal): forward = nose direction, up = roof direction.
internal var spikeCamFx = 0.0
internal var spikeCamFy = 0.0
internal var spikeCamFz = -1.0
internal var spikeCamUx = 0.0
internal var spikeCamUy = 1.0
internal var spikeCamUz = 0.0

/**
 * Cinematic-return journey state (see [CAM_RETURN_FRAMES]). [spikeCamReturnT] is the
 * normalized progress `0..1` advanced each frame while [spikeCamReturning]; the
 * `spikeCamReturnStart*` snapshot the pose at the moment `c` was pressed, and the
 * `spikeCamReturnApex*` are the arc's out-and-up control point. The render loop
 * traces a quadratic Bézier through them while facing the sphere. @see resetCamera
 */
internal var spikeCamReturnT = 0.0
internal var spikeCamReturnStartX = 0.0
internal var spikeCamReturnStartY = 0.0
internal var spikeCamReturnStartZ = 0.0
internal var spikeCamReturnStartUx = 0.0
internal var spikeCamReturnStartUy = 1.0
internal var spikeCamReturnStartUz = 0.0
internal var spikeCamReturnApexX = 0.0
internal var spikeCamReturnApexY = 0.0
internal var spikeCamReturnApexZ = 0.0

/**
 * Generalized-**tour** target for the [spikeCamReturning] tracer. The `c` return is
 * one instance of a tour (fly home, look at the origin, land pristine); a **stash**
 * flight is another (fly up to the shelf-view pose, look at the shelf, park flown).
 * [spikeCamTourTargetX]/Y/Z is the pose the arc lands on, [spikeCamTourLookX]/Y/Z the
 * point the camera faces the whole way, and [spikeCamTourLandPristine] whether, on
 * arrival, the camera drops back to the pristine 1:1 default ([spikeCamFlown] = false,
 * as the `c` return does) or stays parked at the flown target (the shelf). Set by
 * [flyCamTo]; read by the render loop. @see flyCamTo @see resetCamera @see stashFront
 */
internal var spikeCamTourTargetX = 0.0
internal var spikeCamTourTargetY = 0.0
internal var spikeCamTourTargetZ = 0.0
internal var spikeCamTourLookX = 0.0
internal var spikeCamTourLookY = 0.0
internal var spikeCamTourLookZ = 0.0
internal var spikeCamTourLandPristine = true

/**
 * Optional **arrival look point** the tour's aim eases toward over its final
 * [CAM_TOUR_END_BLEND] — the tail mirror of the launch-aim blend. When
 * [spikeCamTourHasEndLook] is set, the camera watches its in-flight look (the fixed
 * point, or a [spikeCamTourFollowPaneId] pane) for most of the journey and then swings
 * to frame [spikeCamTourEndLookX]/Y/Z as it lands — used to reveal the destination
 * beacon sign (home / stash banner) on touchdown while still tracking the pane en route.
 * Cleared (flag false) for plain tours that hold one look the whole way.
 * @see flyCamTo @see CAM_TOUR_END_BLEND
 */
internal var spikeCamTourHasEndLook = false
internal var spikeCamTourEndLookX = 0.0
internal var spikeCamTourEndLookY = 0.0
internal var spikeCamTourEndLookZ = 0.0

/** Frame length of the in-flight tour; the tracer advances `spikeCamReturnT` by `1/this`. */
internal var spikeCamTourFrames = CAM_RETURN_FRAMES

/**
 * If set, the tour's look point **tracks this pane's live world position each frame**
 * (overriding the fixed [spikeCamTourLookX]/Y/Z), so the camera keeps a stashing /
 * unstashing pane centred in frame the whole way — you watch it sail across the air.
 * Cleared when the tour lands. @see stashFront @see flyCamTo
 */
internal var spikeCamTourFollowPaneId: String? = null

/**
 * Cinematic shaping of the in-flight tour, on top of the Bézier arc. [spikeCamTourSway]
 * bows the path **sideways** — world units of horizontal bulge at the midpoint, along
 * the perpendicular of the straight start→target line (sign picks the side, zero at
 * both ends). [spikeCamTourRoll] **banks** the camera about its own nose — max radians,
 * `sin 2πs` profile, so it leans in on the way out, unwinds, counter-banks on approach
 * and lands level. Both `0.0` for the plain `c` return; the stash flights set them.
 * Set by [flyCamTo]; read by the render loop tracer. @see STASH_CAM_SWAY @see STASH_CAM_ROLL
 */
internal var spikeCamTourSway = 0.0
internal var spikeCamTourRoll = 0.0

/**
 * A **permanent** camera bank the tour eases *into* over the flight and **holds at
 * landing** — unlike [spikeCamTourRoll]'s `sin 2πs` in-flight lean (which unwinds to
 * level), this ramps from 0 to its full value as the tour lands and stays there, so the
 * parked pose sits tilted. Used by the [flyOverview] hero shot to keep the whole-world
 * picture "slightly rotated" once it arrives. `0.0` for every flight that lands level.
 * Set by [flyCamTo]; read by the render loop tracer. @see flyOverview @see OVERVIEW_ROLL
 */
internal var spikeCamTourLandRoll = 0.0

/**
 * The camera's **nose direction at tour launch** — snapshotted by [flyCamTo] alongside
 * the start position/up. The tracer blends the aim from this toward the tour's look
 * point over the first [CAM_TOUR_LOOK_BLEND] of the flight, so a journey that begins
 * pointing somewhere else (parked at the shelf gazing up when `c` wants the origin)
 * swings its nose smoothly instead of re-pointing in a single frame.
 */
internal var spikeCamReturnStartFx = 0.0
internal var spikeCamReturnStartFy = 0.0
internal var spikeCamReturnStartFz = -1.0

/**
 * Cross-dissolve weight between the two pane-fade regimes, eased each frame toward
 * `1.0` while the camera is [spikeCamFlown] and `0.0` when it holds the home pose.
 * At `0` the render loop fades panes by *selection* (the [MAX_VISIBLE_SLOTS] focus
 * effect that lights only the fanned neighbours); at `1` it fades them by *camera
 * distance and facing* (the near hemisphere lights up, the far side and anything
 * behind the nose recede) — so flying reveals the whole sphere. Blending prevents a
 * pop when flight starts/ends. Read only in [startSpikeLoop]'s frame.
 * @see FLY_REVEAL_EASE
 */
internal var spikeFlyReveal = 0.0

/**
 * Inertial **velocity** state for the spaceship flight model. Held keys accelerate
 * these; drag decays them so the camera glides. Linear velocity ([spikeCamVX]/Y/Z)
 * is in world-units/frame and only ever grows along the nose (the engine has no
 * lateral thrusters). Angular velocity ([spikeCamPitchVel]/[spikeCamYawVel]/
 * [spikeCamRollVel], radians/frame) spins the orientation basis about its own local
 * right/up/forward axes. All reset to 0 on land/return.
 * @see applyFlyStep
 */
internal var spikeCamVX = 0.0
internal var spikeCamVY = 0.0
internal var spikeCamVZ = 0.0
internal var spikeCamPitchVel = 0.0
internal var spikeCamYawVel = 0.0
internal var spikeCamRollVel = 0.0

/** Keyup listener (fly-key release tracking), detached on close. */
internal var spikeKeysUp: ((Event) -> Unit)? = null

/**
 * Manual **working-state override** per pane (paneId → forced working?), toggled by
 * the `w` key on the centred pane. Lets you force the breathing pulse on/off to see
 * it without a real agent running; when a pane has no entry here its *real* session
 * state (`"working"`) is used. Kept for the app run like the other spike memories.
 */
internal val spikeWorkingOverride = mutableMapOf<String, Boolean>()

/**
 * Manual **waiting-state override** per pane (paneId → forced needs-input?), toggled by
 * the `e` key on the centred pane. Lets you force the red "needs input" halo on/off to
 * preview it without an agent actually blocking; when a pane has no entry here its
 * *real* session state (`"waiting"`) is used. Mirrors [spikeWorkingOverride].
 */
internal val spikeWaitingOverride = mutableMapOf<String, Boolean>()

/** `requestAnimationFrame` handle for the render loop. */
internal var spikeRaf: Int? = null

/**
 * Wall-clock length of one 60Hz frame in ms — the reference step the per-frame clocks are
 * normalised against, so a "frame" in the animation constants means 1/60 s of real time
 * regardless of the display's actual refresh rate. @see spikeDtFrames
 */
internal const val SPIKE_FRAME_MS = 1000.0 / 60.0

/**
 * Upper clamp (in 60fps-frames) on a single [spikeDtFrames] step, so a long stall — a
 * backgrounded tab, a GC pause — makes the clocks resume smoothly instead of teleporting
 * the animation forward by the whole gap. @see spikeDtFrames
 */
internal const val SPIKE_DT_MAX_FRAMES = 4.0

/**
 * `performance.now()` (ms) of the previous rendered frame, or `NaN` before the first —
 * the baseline the render loop differences against to derive [spikeDtFrames]. Reset to
 * `NaN` whenever the loop (re)starts so the first frame steps by exactly one frame.
 * @see spikeDtFrames
 */
internal var spikeLastFrameMs = Double.NaN

/**
 * How many **60fps-equivalent frames** of real time elapsed since the previous rendered
 * frame — i.e. `(now - lastFrame) / SPIKE_FRAME_MS`, clamped to [SPIKE_DT_MAX_FRAMES] and
 * `1.0` on the first frame. The render loop recomputes it once at the top of every frame;
 * the time-based animation clocks (the wormhole phase in [tickWormhole], the phaser bolts
 * in [tickPhaser], and the cinematic camera return) advance by *this* rather than a flat
 * `1.0`, so their wall-clock duration is identical on a 60Hz and a 144Hz+ display. On a
 * 60Hz display this is ~1.0 (unchanged behaviour); at 144Hz it is ~0.42, so ~2.4× as many
 * frames elapse over the same real duration. @see SPIKE_FRAME_MS
 */
internal var spikeDtFrames = 1.0

/** Resize listener, detached on close. */
internal var spikeResize: ((Event) -> Unit)? = null

/** Keydown listener, detached on close. */
internal var spikeKeys: ((Event) -> Unit)? = null
