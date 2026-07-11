/*
 * Split from World3DSpike.kt — tuning constants (ring geometry, fades, flex, fly, working/waiting signals).
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
 * **Uniform-screen mode toggle.** When `true`, every pane in the ring is sized
 * to one common screen-like box ([spikeScreenW]×[spikeScreenH], the viewport's
 * aspect) and **reformatted** (safeFit) so its terminal grid reflows to fill it.
 * When `false`, each pane keeps its own native size and no reformat happens (so a
 * real pane's PTY is never resized). Reformatting a *real* pane resizes its PTY
 * for the duration of the spike; the 2D layout re-fits it after close.
 */
internal const val SPIKE_UNIFORM_SCREENS = true

/** Vertical field of view (degrees) for the CSS3D camera. */
internal const val SPIKE_FOV = 50.0

/**
 * Ring radius (px-world units) the panes are arranged on. Large relative to a
 * pane so a side pane fanned out by [SLOT_ANGLE] lands in the viewport margin,
 * beyond the front pane's edge, and stays visible. The front pane always renders
 * 1:1 regardless of this value (the camera sits at `RING_R + perspDistance`).
 */
internal const val RING_R = 1150.0

/**
 * Fixed fan angle (radians) between adjacent panes *within a tab*, measured from
 * the front — so the immediate neighbours always sit ~[SLOT_ANGLE] to each side
 * and peek around the front pane no matter how many panes the tab has.
 */
internal const val SLOT_ANGLE = 0.52

/** How much side panes yaw toward tangent (0 = flat-facing, 1 = edge-on). */
internal const val SIDE_YAW_FACTOR = 0.55

/** Beyond this many slots from the front, a pane fades out (and stops being live). */
internal const val MAX_VISIBLE_SLOTS = 3.2

/**
 * Angle (radians) between adjacent tabs around the **vertical wheel** — the
 * "latitude" step. Tabs are no longer a flat vertical stack; they ride a circle of
 * radius [RING_R] centred on the origin (the same sphere the horizontal pane fan
 * rides as "longitude"), so scrolling ↑/↓ *rotates* the wheel and you can "go
 * around" the tabs. Paired with the panes' own [SLOT_ANGLE], every pane lands on a
 * single sphere. ~0.52 rad ≈ 30°, giving ~600px of arc between neighbouring tabs.
 */
internal const val TAB_ANGLE = 0.52

/**
 * Edge-fade for tabs by their angular distance (in wheel-slots) from the front: a
 * pane stays full-opacity within [TAB_FADE_PLATEAU] slots, then ramps to 0 over
 * [TAB_FADE_EDGE]. Chosen so an adjacent tab (|tabRel| == 1) lingers at ~0.5
 * opacity (a faint, dimmed neighbour curving away on the wheel) while |tabRel| >= 2
 * is fully gone — so tabs coming around the back never show through.
 */
internal const val TAB_FADE_PLATEAU = 0.5
internal const val TAB_FADE_EDGE = 1.0

/**
 * Free-fly **distance fade** — how a pane's opacity falls off with straight-line
 * distance from the flying camera (replacing the selection-focus fade once
 * [spikeFlyReveal] blends in). A pane stays full-opacity within [FLY_FADE_FULL]
 * world-units of the camera, then ramps to 0 over [FLY_FADE_EDGE] beyond that.
 * Tuned against [RING_R] (1150) to stay generous: a pane stays full-opacity out to
 * several ring-radii, so you can back well away from the sphere and still see every
 * pane; only when a pane is very far does it fade. @see edgeFade @see startSpikeLoop
 */
// FULL reaches past the demo movie's two-worlds vista (~13k from the shelf), so
// panes on the shelf stay fully lit in the shot that frames shelf + rotunda together.
internal const val FLY_FADE_FULL = 14000.0
internal const val FLY_FADE_EDGE = 6000.0

/**
 * Half-width (as a cosine of the angle off the nose) of the soft band over which a
 * pane crossing behind the flying camera fades out, so panes swinging past the
 * ±90° side plane dissolve instead of popping. ~cos(84.8°); panes more than this
 * far behind the view direction are fully hidden, fully in front are unaffected.
 * @see startSpikeLoop
 */
internal const val FLY_BEHIND_BAND = 0.09

/** Per-frame lerp weight easing [spikeFlyReveal] between the two fade regimes. */
internal const val FLY_REVEAL_EASE = 0.08

/** Height (px) of each plane's title strip. */
internal const val TITLE_H = 26

/** Fallback pane plane size (px) when a real pane can't be measured. */
internal const val PANE_W = 780
internal const val PANE_H = 480

/** Per-frame lerp the horizontal pane fan eases toward the selected pane. */
internal const val PANE_EASE = 0.16

/** Per-frame lerp the vertical tab scroll eases toward the selected tab. */
internal const val TAB_EASE = 0.14

/** How close (units) both axes must settle before the front pane can be engaged. */
internal const val SETTLE_EPS = 0.02

/**
 * Dim-veil opacity of a fully *unlit* pane (one slot or more off the front pole).
 * The render loop scales this by (1 − litness), where litness is derived from the
 * same eased scrolls that swing the panes — so a pane brightens gradually as it
 * approaches the front, at exactly the speed it moves, instead of popping from
 * dim to lit when the scroll settles.
 */
internal const val PANE_DIM_OPACITY = 0.5

/** Multiplicative visual-zoom step per Alt+=/Alt+− press (pure GPU scale, no reflow). */
internal const val ZOOM_STEP = 1.5

/**
 * Bounds for the front pane's visual zoom multiplier (1.0 = native). The floor is
 * deliberately deep — at [ZOOM_STEP] 1.5 it takes six `−` presses to reach it — so
 * a pane can be shrunk to a small tile while surveying the world.
 */
internal const val ZOOM_MIN = 0.1
internal const val ZOOM_MAX = 6.0

/**
 * Grid-key steps and bounds under the **PTY-truth sizing model** (see
 * [presentPaneToGrid]): the grid keys (`,`/`.` cols, `<`/`>` rows) add/remove this
 * many **cells** per press, changing the terminal, the pane's plane, and the shared
 * PTY together — unlike zoom (a pure GPU magnify at a fixed grid). Bounds keep a
 * command from driving a grid degenerate or absurd; server-driven sizes are exempt
 * (the pane always follows the real PTY).
 */
internal const val GRID_COLS_STEP = 20
internal const val GRID_ROWS_STEP = 10
internal const val GRID_MIN_COLS = 20
internal const val GRID_MAX_COLS = 400
internal const val GRID_MIN_ROWS = 5
internal const val GRID_MAX_ROWS = 200

/**
 * Duration (ms) of the fluid pane-box glide when the grid changes: the terminal
 * reflows to the new grid instantly (a text reflow can't tween), but the pane's
 * plane — wrapper, terminal container, and the stretch-along border SVG — eases to
 * the new `cols × cellW` box via a CSS width/height transition instead of snapping
 * ([presentPaneToGrid] with `animate = true`). Retargeting mid-glide (held key
 * auto-repeat, a server Size follow) restarts the transition from the current
 * interpolated size, so chained steps stay fluid.
 */
internal const val GRID_ANIM_MS = 260

/**
 * Delay (ms) between the two halves of the `r` reformat **jiggle** ([reformatPane]):
 * the PTY is forced one row off, then back, so the program gets two real SIGWINCHes
 * and repaints at the current grid (a same-size resize is deduped by both the kernel
 * and the server's size StateFlow — it reaches nobody). Long enough for the first
 * force to round-trip the server and reach the program; short enough that the pane's
 * one-row breath reads as a single gesture.
 */
internal const val REFORMAT_JIGGLE_MS = 160

/** Per-frame lerp each pane's scale eases toward its target (front = 1:1, side = normalized). */
internal const val SCALE_EASE = 0.16

/**
 * Much slower per-frame lerp used **only while a zoom preset glides** (⇧+ fit /
 * ⇧− floor / `0` 1:1 reset, gated by [spikeZoomGlide]). A preset moves the target
 * a long way in one jump — up to [ZOOM_MIN]↔fit — and at [SCALE_EASE] the exponential ease
 * covers most of that distance within a few frames, reading as a snap. This rate
 * stretches the same glide to roughly a second so the extreme jumps feel like a
 * deliberate transition; the small `+`/`−` steps keep the snappier [SCALE_EASE].
 */
internal const val ZOOM_PRESET_EASE = 0.03

/**
 * Slack left around the **fit-screen** preset (`⇧+`, [zoomFrontFit]): the pane is fitted to
 * this fraction of the viewport rather than filling it edge-to-edge, so the window's border
 * and glow stay *inside* the screen instead of spilling off it. A hair under 1 — just enough
 * margin to keep the frame visible. @see zoomFrontFit
 */
internal const val ZOOM_FIT_MARGIN = 0.96

/**
 * Extra depth (world units) each **non-front** pane is pushed back, scaled by its
 * size, so a neighbour reads as clearly recessed behind the centred pane. This is
 * the *aesthetic* recess; the hard occlusion guarantee is the per-frame clamp
 * backed by [SIDE_NEAR_CLEARANCE] (see [startSpikeLoop]).
 */
internal const val SIDE_Z_PUSH = 130.0

/**
 * Minimum depth gap (world units) kept between a non-front pane's **near edge**
 * and the front pane's plane at `z = [RING_R]`. A side pane yawed by
 * [SIDE_YAW_FACTOR] (or tilted onto another tab's latitude by [TAB_ANGLE])
 * protrudes toward the camera by `halfWidth·sin(yaw) + halfHeight·sin(tilt)` —
 * for a very wide, tall, or zoom-remembered pane that can exceed the fixed
 * [SIDE_Z_PUSH], letting its plane cross the front pane's plane and composite
 * *over* it (the paint-order raise in [startSpikeLoop] cannot save an
 * actually-nearer plane). The render loop therefore clamps every non-front
 * pane's centre depth so its near edge stays at least this far behind the
 * front plane. @see startSpikeLoop
 */
internal const val SIDE_NEAR_CLEARANCE = 60.0

/**
 * Idle **bob** for every pane you haven't grabbed (engaged): a slow, small vertical
 * float, like the tiles in a carousel-ring app switcher. [BOB_AMPLITUDE] world-units
 * of travel at [BOB_SPEED] radians/frame, with each pane phase-offset by
 * [BOB_STAGGER]×index so they drift out of sync (a gentle shimmer, not a rigid lift).
 * The engaged pane holds perfectly still so typing isn't disturbed.
 */
internal const val BOB_AMPLITUDE = 9.0
internal const val BOB_SPEED = 0.007 // slow, ~15s float — a drift, not a wobble
internal const val BOB_STAGGER = 1.3

/**
 * **Latch flex** — the one-shot spring a pane plays when you engage (Enter, outward)
 * or disengage (⌥⌘X / navigate away, inward) it, so the moment reads as a significant
 * event and not just a silent focus change. The render loop runs a decaying-sine
 * envelope over [FLEX_FRAMES] frames on the flexing pane and drives three things off
 * it in lock-step:
 *  1. a **convex bulge** — the pane's surface swells outward toward its centre (or
 *     dishes inward on disengage) via an SVG `feDisplacementMap` fisheye applied to
 *     the plane. This is the real "bend" — a flat CSS3D plane can't curve on its own
 *     (transforms keep straight lines straight), so the warp is done in the pixel
 *     filter, on the live terminal, without any reflow.
 *  2. a small **scale lunge** toward/away from the camera so the bulge reads as depth.
 *  3. a subtle **tilt** for a touch of physicality.
 *
 * - [FLEX_FRAMES] the animation length; ~0.87s at 60fps.
 * - [FLEX_BULGE] peak displacement-map `scale` (px) at the crest — the bulge depth.
 * - [FLEX_AMPLITUDE] peak scale-lunge deflection (fraction of size).
 * - [FLEX_FREQ] number of half-swings: 1.0 = one clean out-and-back bump; >1 adds a
 *   spring overshoot (a small counter-swing before it settles).
 * - [FLEX_DECAY] how fast the envelope damps — higher front-loads the deflection.
 * - [FLEX_TILT] radians of transient x-axis tilt applied alongside the bulge.
 * - [FLEX_DIR_OUT]/[FLEX_DIR_IN] the sign of the deflection for engage / disengage.
 * @see bulgeMapUri @see createBulgeFilter
 */
internal const val FLEX_FRAMES = 26.0
internal const val FLEX_BULGE = 120.0
internal const val FLEX_AMPLITUDE = 0.12
internal const val FLEX_FREQ = 1.0
internal const val FLEX_DECAY = 1.15
internal const val FLEX_TILT = 0.14
internal const val FLEX_DIR_OUT = 1.0
internal const val FLEX_DIR_IN = -1.0

/**
 * Free-fly camera **flight model** — inertial, so the camera handles like a
 * spaceship rather than teleporting a fixed step each frame. The main engine
 * pushes along the nose — you *aim* the ship with pitch/yaw/roll, then hold the
 * throttle to accelerate wherever it points — while `A`/`D` lateral thrusters
 * strafe along the ship's right vector. Held keys apply *thrust* (an
 * acceleration) that builds up **velocity**; releasing them lets drag
 * ([FLY_DAMPING]/[FLY_ROT_DAMPING]) coast the motion smoothly to a stop instead of
 * killing it instantly. Terminal cruise speed ≈ accel / (1 − damping).
 *
 * - [FLY_ACCEL] world-units/frame² of forward thrust while the throttle is held.
 * - [FLY_DAMPING] fraction of linear velocity retained each frame (drag → glide);
 *   high, so the ship keeps coasting through the void the way a spaceship should.
 * - [FLY_ROT_ACCEL] radians/frame² of angular thrust per held steering key.
 * - [FLY_ROT_DAMPING] fraction of angular velocity retained each frame.
 * - [FLY_STOP_EPS] velocity magnitude below which residual drift is snapped to 0.
 */
internal const val FLY_ACCEL = 2.4
internal const val FLY_DAMPING = 0.94
internal const val FLY_ROT_ACCEL = 0.0024
internal const val FLY_ROT_DAMPING = 0.88
internal const val FLY_STOP_EPS = 0.02

/**
 * Birth/death animation for panes and empty-tab cards created or removed while the
 * spike is open (see [reconcileRing]). A new plane starts at scale factor 0 and
 * eases toward 1 by [SPAWN_EASE]; a removed one is marked dying and eases toward 0
 * by [DESPAWN_EASE], then is disposed once below [SPAWN_GONE_EPS]. Despawn is a
 * touch faster than spawn so a close feels crisp while a create feels like it grows
 * into place.
 */
internal const val SPAWN_EASE = 0.16
internal const val DESPAWN_EASE = 0.26
internal const val SPAWN_GONE_EPS = 0.02

/**
 * The `c` **cinematic return** — instead of a straight lerp home, the camera flies a
 * curved path: it swings *out and up* to a high vantage that frames the whole sphere,
 * then swoops down to the pristine 1:1 pose, facing the sphere the entire way so the
 * landing reads as a graceful approach rather than a snap.
 *
 * - [CAM_RETURN_FRAMES] total frames of the journey (~60fps), so the whole move takes
 *   ~[CAM_RETURN_FRAMES]/60 s regardless of how far out the camera had flown.
 * - [CAM_RETURN_PULLOUT] world-units the arc's apex bulges *away* from the sphere
 *   (radially outward from the origin) so you pull back and see everything mid-flight.
 * - [CAM_RETURN_RISE] world-units the apex lifts in +Y for the "descend to land" feel.
 * @see resetCamera @see startSpikeLoop
 */
internal const val CAM_RETURN_FRAMES = 420.0
internal const val CAM_RETURN_PULLOUT = 2600.0
internal const val CAM_RETURN_RISE = 1500.0

/**
 * The **tilt view** camera move (`j`, [tiltCamera]) — a quick hop that parks the
 * camera a small step off-axis, still looking at the target pane, so the pane reads
 * at a gentle three-quarter angle:
 * - [TILT_SIDE] / [TILT_UP] sideways / upward step as fractions of the perspective
 *   distance — deliberately modest ("slightly tilted, not that much").
 * - [TILT_FRAMES] journey length (~/60 s); brisk, it's a nudge rather than a tour.
 */
internal const val TILT_SIDE = 0.30
internal const val TILT_UP = 0.14
internal const val TILT_FRAMES = 130.0

/**
 * The **overview** camera move (`m`, [flyOverview]) — a cinematic pull-back to a fixed
 * "hero shot" of the whole command-center world: the camera flies to a pose that sits
 * up high, off to one side and pulled back in front, then gazes down at the world's
 * centre (the origin the pane sphere is built around) so the entire ring — every tab,
 * every window — reads as one nice framed picture, three-quarter and slightly angled.
 * Unlike the pane fly-bys ([flyBehindPane] et al.) this frames the *scene*, not a pane,
 * so it takes no target and lands the same composed shot from anywhere you trigger it
 * (command center or free flight).
 *
 * - [OVERVIEW_DIR_SIDE] / [OVERVIEW_DIR_UP] / [OVERVIEW_DIR_FRONT] the (un-normalized)
 *   direction from the origin to the camera: `+X` right, `+Y` up, and a **negative** `Z`
 *   so the camera sits *behind* the ring, looking back through it — you see the whole
 *   arrangement from over-and-behind (the panes' backs), a bit off to one side. The
 *   ratios set the shot's elevation (~33°) and how far round the side it swings.
 * - [OVERVIEW_DIST] how far out along that direction the camera parks, as a multiple of
 *   the home distance ([RING_R] + `perspDistance`): >1 pulls back beyond the 1:1 home
 *   pose so the whole sphere plus a generous margin of sky fits the [SPIKE_FOV] frustum.
 * - [OVERVIEW_PULLOUT] / [OVERVIEW_RISE] the Bézier apex shaping — how far the flight
 *   bulges **radially out** from the origin and **lifts** in +Y. Essential here because
 *   the launch pose sits *in front* of the ring and the target sits *behind* it: without
 *   an arc the path is a straight line that flies **through** the world (a moment of
 *   void as the panes whip past and end up behind the camera). Swinging out and over the
 *   top keeps the camera outside the sphere, gazing down at a full world the whole way.
 * - [OVERVIEW_FRAMES] journey length (~/60 s) — a brisk, camera-only reframe.
 * - [OVERVIEW_ROLL] gentle in-flight bank (radians, `sin 2πs`, unwinds mid-flight) for a
 *   touch of cinematic sweep on the way to the shot.
 * - [OVERVIEW_LAND_ROLL] the **permanent** bank the shot lands and holds (radians), so
 *   the parked picture sits "slightly rotated" like a hand-held hero frame.
 * @see flyOverview @see flyCamTo
 */
internal const val OVERVIEW_DIR_SIDE = 0.45
internal const val OVERVIEW_DIR_UP = 0.55
internal const val OVERVIEW_DIR_FRONT = -0.70
internal const val OVERVIEW_DIST = 1.42
internal const val OVERVIEW_PULLOUT = 2600.0
internal const val OVERVIEW_RISE = 2400.0
internal const val OVERVIEW_FRAMES = 360.0
internal const val OVERVIEW_ROLL = 0.14
internal const val OVERVIEW_LAND_ROLL = 0.12

/**
 * The **stash shelf** — a horizontal row of slots floating high above the sphere where
 * panes sent up by Space ([toggleStash]) come to rest, next to each other. A stashed
 * pane's slot index (its position in [spikeStashed]) maps to a world position via
 * [stashShelfPos]: `x = STASH_ROW_X0 + slot*STASH_ROW_GAP`, `y = STASH_SHELF_Y`,
 * `z = STASH_SHELF_Z`. The row is **left-anchored** (deterministic — a pane's slot
 * doesn't drift when another stashes/unstashes) and sits slightly forward in +Z so the
 * shelved panes face the viewer.
 *
 * - [STASH_SHELF_Y] shelf height above the origin — deliberately *far* above the
 *   sphere's top, so the stash trip is a real journey across open sky rather than a
 *   short hop (the flight duration is [STASH_CAM_FRAMES]; the distance is what makes
 *   that time feel travelled).
 * - [STASH_SHELF_Z] small +Z bias so the row faces the camera's stash-view pose.
 * - [STASH_ROW_X0] x of the first (slot 0) shelf position; the row grows toward +X.
 * - [STASH_ROW_GAP] horizontal spacing between adjacent shelved panes. Must exceed
 *   [PANE_W] or neighbours overlap; the excess is the visible air between them.
 * @see stashShelfPos @see spikeStashed
 */
internal const val STASH_SHELF_Y = 26000.0
internal const val STASH_SHELF_Z = 260.0
internal const val STASH_ROW_X0 = -960.0
internal const val STASH_ROW_GAP = 900.0

/**
 * Per-sheet offset of a merged tab stack ([TabBundle]): each pane behind the front one is
 * pushed by this much in X (right), −Y (down) and −Z (away from the camera) per step of
 * its [RingPane.mergeOrd], so the collapsed tab reads as a fanned pile of papers with the
 * first pane in sequence on top. Small relative to a pane's world size so the whole stack
 * still frames as one object at the bay. @see tickBundles @see bundleStackOffset
 */
internal const val STACK_OFF_X = 46.0
internal const val STACK_OFF_Y = 40.0
internal const val STACK_OFF_Z = 34.0

/**
 * Frame length of the **merge** (fan-in) and **separation** (fan-out) phases of a tab
 * stash — the panes collapsing onto / spreading back off the stack, *before* the shared
 * [STASH_CAM_FRAMES] flight to / from the bay. Short and snappy relative to the flight so
 * the merge reads as a quick gather and the flight as the long haul. Separation staggers
 * each sheet by [BUNDLE_SEP_STAGGER] frames so the pile spreads in sequence rather than
 * all at once. @see tickBundles
 */
internal const val BUNDLE_MERGE_FRAMES = 42.0
internal const val BUNDLE_SEP_FRAMES = 46.0
internal const val BUNDLE_SEP_STAGGER = 7.0

/**
 * Total yaw (radians) a merged tab stack spins through over its flight to / from the bay —
 * applied as a rigid-body rotation of the whole stack about its vertical axis ([tickBundles]),
 * so the fanned per-sheet offsets ([bundleStackOffset]) sweep in and out of view mid-journey.
 * Must be a **whole number of turns** so the spin lands cleanly yaw-flat (the dock rest tilt is the
 * separate [BUNDLE_DOCK_PITCH]); applied at a constant rate (linear in flightProg), since rotating
 * the big CSS3D sheets strains the compositor in proportion to angular speed and the stutter was
 * worst mid-flight — easing would only peak the speed there. One slow turn reveals the fan without
 * the fast 3-turn spin that stuttered; 2π is the floor (less would snap at landing). Lower is
 * cheaper but a fraction of a turn is not an option. @see tickBundles @see BUNDLE_DOCK_PITCH
 */
internal const val BUNDLE_FLIGHT_SPIN = 2.0 * kotlin.math.PI

/**
 * The resting **pitch** (radians, about the X axis) of a **docked** tab stack — a merged pile
 * parks tilted this far back toward the camera, like a drafting table angled up at you, so its
 * sheets (stepped down-and-back, [bundleStackOffset]) fan into view and read as *several* windows
 * rather than one flat face hiding the rest behind it. The stack eases into this pitch on arrival
 * and out of it on departure, so it is fanned at rest yet flat (head-on) the instant it merges or
 * lands back on the ring. −π/4 ≈ 45°, tilting the stack's stepped-back tail up toward the camera
 * so the smaller receding sheets fan into view above the front one. Flip the sign to tilt the
 * other way. @see tickBundles
 */
internal const val BUNDLE_DOCK_PITCH = -kotlin.math.PI / 4.0

/**
 * World-distance over which a **moving** bundle sheet fades as it nears its target during the
 * fan-in ([BundleState.MERGING]) / fan-out ([BundleState.SEPARATING]): a sheet more than this far
 * from where it will rest this frame is fully transparent, fully opaque once seated, lerped
 * between. Fading by *distance to target* (not stack order) keeps whichever sheet starts at the
 * merge point — the pane you were looking at — solid throughout, while every sheet that sweeps in
 * from a far ring slot stays faint until it seats, so none slams across the view. The stack front
 * ([RingPane.mergeOrd] 0) is *not* necessarily that anchor pane (it's the display-order head), so
 * an order-based test can't tell them apart; distance can. ~ half a pane width. @see tickBundles
 */
internal const val BUNDLE_MERGE_FADE_DIST = 340.0

/**
 * Frame length of a **stash / unstash journey** (~[STASH_CAM_FRAMES]/60 s) — the single
 * shared duration for *both* the camera flight ([flyCamTo]) **and** the pane's flight to
 * / from the shelf ([RingPane.stashProg] advances `1/STASH_CAM_FRAMES` per frame). Using
 * one duration and one smootherstep curve for both is what makes the **camera travel in
 * lockstep with the pane** — you watch it sail slowly across the air the whole way rather
 * than the pane arriving first. Deliberately long, so the trip is slow and cinematic.
 * @see flyCamTo @see stashFront @see startSpikeLoop
 */
internal const val STASH_CAM_FRAMES = 685.0

/**
 * Frame length of a **stash-view (`v`) flight** — the camera-only trip up to the dock
 * and back with no pane in tow ([toggleStashView], and the open-sky come-home leg of
 * [resetCamera] when leaving the dock). Much quicker than [STASH_CAM_FRAMES]: that
 * duration is deliberately slow so the camera stays in lockstep with a pane sailing to
 * the shelf, but a `v` peek carries no pane, so there is nothing to wait for — a
 * brisker flight just gets you there. @see toggleStashView @see resetCamera
 */
internal const val STASH_VIEW_CAM_FRAMES = 300.0

/**
 * Arc shape of a **stash / unstash** camera flight — how far the journey's Bézier apex
 * bulges out ([STASH_CAM_PULLOUT], radially away from the origin) and lifts
 * ([STASH_CAM_RISE], +Y). Gentler than the [CAM_RETURN_PULLOUT] `c` return (which
 * starts from wherever you flew to and pulls way back to reframe the whole sphere): a
 * stash starts from the home pose, so a smaller arc keeps the followed pane a
 * comfortable size and the sweep tasteful rather than flinging the camera to orbit.
 * @see flyCamTo @see stashFront
 */
internal const val STASH_CAM_PULLOUT = 3500.0
internal const val STASH_CAM_RISE = 2600.0

/**
 * Cinematic shaping of a **stash / unstash** camera flight, on top of the Bézier arc:
 *
 * - [STASH_CAM_SWAY] bows the flight path *sideways* — a horizontal bulge (world units
 *   at the midpoint, perpendicular to the straight start→target line, zero at both
 *   ends) that turns the straight climb into a sweeping lateral curve, so the journey
 *   arcs around the open sky instead of riding a rail.
 * - [STASH_CAM_ROLL] banks the camera about its own nose (max radians) with a
 *   `sin 2πs` profile: it leans into the curve on the way out, unwinds through the
 *   midpoint, counter-banks on the approach, and lands perfectly level.
 *
 * Both are passed by the stash flights only — the plain `c` return keeps the classic
 * straight-up-and-over arc. @see flyCamTo @see stashFront @see unstashNearest
 */
internal const val STASH_CAM_SWAY = 5200.0
internal const val STASH_CAM_ROLL = 0.45

/**
 * The **landing pose** of a stash flight (and of every [shelfBrowse] glide, so browsing
 * keeps the same closeness): the camera parks [STASH_CAM_LAND_DIST] × `perspDistance`
 * in front of the shelf slot, dropped [STASH_CAM_LAND_DROP] world units below it so it
 * gazes slightly up at the pane. A pane viewed from `d × perspDistance` appears at
 * `1/d` of its 1:1 size, so 1.18 lands the pane at ~85% of full screen — close enough
 * to *feel arrived at*, with a sliver of the neighbouring slots for context.
 * @see stashFront @see shelfBrowse
 */
internal const val STASH_CAM_LAND_DIST = 1.18
internal const val STASH_CAM_LAND_DROP = 140.0

/**
 * The **pane fly-bys** — the two cinematic fly-mode moves that tour the camera around
 * the pane nearest to it: `B` glides **behind** it (through-the-looking-glass, parked on
 * its back side looking at it), `N` glides to its **flank** (a three-quarter view,
 * [PANE_SIDE_ANGLE] off the pane's normal, lifted a touch), and `O`/`U` glide **over /
 * under** it (perched [PANE_VERT_ANGLE] off the pane's own up/down axis toward its
 * front — a pane is a flat plane, so the perch keeps enough of the face in view to
 * stay *visibly* above rather than edge-on-invisible). All are slow deliberate
 * [flyCamTo] tours that pick the flank the camera is already nearest, swing around that
 * side (sway signed to match) with a gentle bank, and park still in fly mode — any
 * movement key mid-flight cancels the tour and hands control straight back.
 *
 * - [PANE_TOUR_FRAMES] journey length (~/60 s); deliberately slow.
 * - [PANE_BEHIND_DIST] how far behind the pane's back the `B` pose parks.
 * - [PANE_SIDE_DIST] camera→pane distance of the `N` three-quarter pose.
 * - [PANE_SIDE_ANGLE] radians off the pane's facing normal for the `N` pose (~66°:
 *   mostly side-on, face still readable).
 * - [PANE_SIDE_LIFT] fraction of [PANE_SIDE_DIST] the `N` pose rises above the pane.
 * - [PANE_VERT_DIST] camera→pane distance of the `O`/`U` over/under perch.
 * - [PANE_VERT_ANGLE] radians the over/under perch leans off the pane's up/down axis
 *   toward its front (~34°: clearly overhead, face still visible).
 * - [PANE_TOUR_PULLOUT]/[PANE_TOUR_RISE] Bézier apex shaping (kept small — these are
 *   local orbits, not journeys).
 * - [PANE_TOUR_SWAY]/[PANE_TOUR_ROLL] lateral swing + bank, as in the stash flights.
 * @see flyBehindPane @see flyBesidePane @see flyAbovePane @see flyBelowPane
 */
internal const val PANE_TOUR_FRAMES = 420.0
internal const val PANE_BEHIND_DIST = 1000.0
/** How far in **front** of the pane's face the `H` head-on pose parks (twin of [PANE_BEHIND_DIST]). */
internal const val PANE_FRONT_DIST = 1000.0
internal const val PANE_SIDE_DIST = 1400.0
internal const val PANE_SIDE_ANGLE = 1.15
internal const val PANE_SIDE_LIFT = 0.18
internal const val PANE_VERT_DIST = 1200.0
internal const val PANE_VERT_ANGLE = 0.6
internal const val PANE_TOUR_PULLOUT = 400.0
internal const val PANE_TOUR_RISE = 200.0
internal const val PANE_TOUR_SWAY = 900.0
internal const val PANE_TOUR_ROLL = 0.3

/**
 * Fraction of a camera tour's eased progress over which the **aim blends** from the
 * launch nose direction to the tour's look point. Without it, frame 1 of a tour sets
 * `forward = look − pos` outright — an instant re-point (ugly when `c` is pressed while
 * parked at the shelf gazing up, and the return wants to gaze down at the origin). With
 * it, the nose swings smoothly through the first ~third of the flight and tracks the
 * look point exactly thereafter. @see flyCamTo
 */
internal const val CAM_TOUR_LOOK_BLEND = 0.35

/**
 * Fraction of a camera tour's *tail* over which the aim **eases from the in-flight look
 * point to a separate arrival look point** ([flyCamTo]'s `endLook`). The mirror of
 * [CAM_TOUR_LOOK_BLEND] at the other end of the journey: it lets a flight *watch one
 * thing on the way* (e.g. a pane sailing up to the shelf, tracked via `followPaneId`)
 * and then **swing the gaze to frame the destination sign** just as it lands — the home
 * / stash beacon banners are the point of the arrival, not the pane. `0.0` disables the
 * tail ease (aim holds the in-flight look right to touchdown). @see flyCamTo
 */
internal const val CAM_TOUR_END_BLEND = 0.32

/**
 * Frame length of a **shelf browse** glide (~[SHELF_BROWSE_FRAMES]/60 s) — the short
 * hop ←/→ makes between adjacent shelf slots while the camera is up at the stash shelf
 * ([shelfBrowse]). Deliberately quick and flat (straight line, no pullout/sway/roll):
 * browsing the shelf should feel like stepping along a corridor, not another journey.
 * @see shelfBrowse @see STASH_CAM_FRAMES
 */
internal const val SHELF_BROWSE_FRAMES = 65.0

/**
 * Per-frame exponential ease of the **shelf pan** — the lateral dolly ←/→ runs while
 * up at the dock ([shelfBrowse]). The camera trucks straight sideways along the shelf
 * (fixed height, depth and forward gaze), easing its x toward the browsed slot at this
 * rate each frame. Matched to [PANE_EASE] so sliding between docked windows feels like
 * the command center's pane switching. A plain dolly — not a [flyCamTo] tour — because
 * a cinematic look-at-the-destination arc made the slide swing inward as it moved,
 * which read as jerky; a fixed straight-ahead gaze slides cleanly along the row.
 * @see shelfBrowse @see spikeShelfPanTargetX
 */
internal const val SHELF_PAN_EASE = 0.16

/** Below this world-units gap to the target x, the shelf pan snaps home and ends. @see SHELF_PAN_EASE */
internal const val SHELF_PAN_STOP_EPS = 0.5

/**
 * The **home beacon** — the big neon double-chevron landmark hovering above the
 * default camera position, pointing at the pane sphere. Two chevron planes are
 * crossed at 90° and spun slowly about the pointing axis, so the arrow reads as a
 * volumetric object from every free-fly angle (a single CSS3D plane would vanish
 * edge-on). Positioned at `(0, BEACON_Y, homeZ)` — directly *above* the home camera
 * pose — so it sits outside the frustum at rest (never blocking the ring view) and
 * only shows itself once you fly off and look around.
 *
 * - [BEACON_Y] world-units height above the home camera spot.
 * - [BEACON_W]/[BEACON_H] pixel size of each chevron plane (world units at scale 1).
 * - [BEACON_SPIN_SPEED] radians/frame of the spin about the arrow's own axis.
 * - [BEACON_PULSE_S] seconds per glow-pulse breath (pure-CSS keyframe animation).
 * - [BEACON_LABEL_TEXT] the banner words floating above the chevron.
 * - [BEACON_LABEL_RISE] world-units the banner floats above the beacon anchor.
 * - [BEACON_LABEL_FONT_PX] font size (px) of each banner line.
 * @see buildHomeBeacon
 */
internal const val BEACON_Y = 650.0
internal const val BEACON_W = 640
internal const val BEACON_H = 900
internal const val BEACON_SPIN_SPEED = 0.003 // rad/frame → ~1 revolution / 35 s
internal const val BEACON_PULSE_S = 2.4
internal const val BEACON_LABEL_TEXT = "COMMAND CENTER"
internal const val BEACON_LABEL_RISE = 760.0
internal const val BEACON_LABEL_FONT_PX = 190

/**
 * The **stash beacon** — the home beacon's sibling landmark at the stash shelf: two
 * nested neon *diamond* outlines (a "storage crystal", deliberately distinct from the
 * home arrow) on planes crossed at 90°, hovering above the shelf row and slowly
 * **counter-spinning** (opposite direction, different cadence to the home beacon, so
 * the two landmarks never read as copies). It marks the shelf from anywhere in
 * free-fly — the shelf is far above the sphere ([STASH_SHELF_Y]), so without a
 * landmark an empty shelf is just featureless sky.
 *
 * - [STASH_BEACON_RISE] world-units the crystal hovers above the shelf row.
 * - [STASH_BEACON_S] pixel size (square) of each diamond plane.
 * - [STASH_BEACON_SPIN_SPEED] radians/frame of the spin — negative: counter to the home beacon.
 * - [STASH_BEACON_PULSE_S] seconds per glow-pulse breath (pure-CSS keyframes).
 * - [STASH_LABEL_TEXT] the banner word floating above the crystal.
 * - [STASH_LABEL_RISE] world-units the banner floats above the shelf row (above the crystal).
 * - [STASH_LABEL_FONT_PX] font size (px) of the banner.
 * @see buildStashBeacon
 */
internal const val STASH_BEACON_RISE = 820.0
internal const val STASH_BEACON_S = 640
internal const val STASH_BEACON_SPIN_SPEED = -0.005 // rad/frame → ~1 revolution / 21 s
internal const val STASH_BEACON_PULSE_S = 3.6
internal const val STASH_LABEL_TEXT = "Window Dock"
internal const val STASH_LABEL_RISE = 1800.0
internal const val STASH_LABEL_FONT_PX = 200

/**
 * **Sign-reveal arrival framing** — how the shelf-arrival flights ([stashFront],
 * [toggleStashView], [shelfBrowse]) frame the destination so its beacon **sign** is in
 * view on touchdown, not cropped above the pane. The arrival pose is computed from the
 * vertical span between the shelved pane's bottom and the sign's top (see
 * [shelfArrivalPose]): the camera looks at the mid-point of that span and stands far
 * enough back that the whole span fits the [SPIKE_FOV] frustum, plus [SIGN_REVEAL_MARGIN]
 * of breathing room top and bottom. Because the distance is derived from the span (not a
 * fixed dolly), it self-adjusts to variable pane sizes and window heights.
 *
 * - [SIGN_REVEAL_MARGIN] extra world-units of air kept above the sign and below the pane.
 * - [SIGN_REVEAL_MIN_HALF] floor on the framed half-span, so a tiny/empty shelf still
 *   parks at a sensible standoff instead of nose-to-the-sign.
 */
internal const val SIGN_REVEAL_MARGIN = 240.0
internal const val SIGN_REVEAL_MIN_HALF = 900.0

/**
 * **Feature flag** for the **stash station** — the giant enclosing spaceship / space
 * station hull that wraps the stash shelf ([buildStashStation]). When `true`, the shelf
 * high above the sphere ([STASH_SHELF_Y]) is no longer bare sky: it sits inside a
 * cavernous hangar with a huge **open bay door** on the front (+Z) face, and every
 * shelf flight ([stashFront], [unstashNearest], [toggleStashView]) becomes a two-leg
 * cinematic that flies *in through the door* to drop a terminal off and *out through the
 * door* to pick one up. When `false` the shelf stays open-sky and the flights keep their
 * original single-arc choreography — the station code is inert.
 *
 * The hull is pure CSS3D (real oriented DOM planes, like the beacons — **not**
 * billboarded, so their transforms are static at rest and never re-rasterize, unlike
 * the flicker-prone [buildCosmos] bodies), and each panel is built small and scaled up
 * ([STATION_TEX_SCALE]) so the huge walls stay within the GPU tile budget. A pane rising
 * from the ring to the shelf passes through the floor plane once mid-flight — a brief
 * intersection traded for the grounded look. @see buildStashStation @see flyStationEnter
 */
internal const val SPIKE_STASH_STATION_ENABLED = true

/**
 * The stash station's **interior box** — centre and half-extents (world units). Centred
 * on the shelf ([STATION_CZ] = [STASH_SHELF_Z]) and lifted to [STATION_CY] so the box
 * encloses the shelved panes below (~[STASH_SHELF_Y]) up past the [STASH_LABEL_TEXT] sign
 * above. The X centre is biased toward +X ([STATION_CX]) because the shelf row is
 * left-anchored at [STASH_ROW_X0] and grows toward +X, so the occupied slots sit right of
 * origin. Half-extents are generous so the hangar reads as *huge*:
 * - [STATION_HW] half-width (X): spans several shelf slots plus air.
 * - [STATION_HH] half-height (Y): floor line to ceiling, clearing panes and the sign.
 * - [STATION_HD] half-depth (Z): back wall to the front door wall.
 * The back wall sits at `STATION_CZ − STATION_HD`, the front (door) wall at
 * `STATION_CZ + STATION_HD`. @see buildStashStation
 */
internal const val STATION_CX = 1200.0
internal const val STATION_CY = STASH_SHELF_Y + 700.0
internal const val STATION_CZ = STASH_SHELF_Z
internal const val STATION_HW = 6600.0
internal const val STATION_HH = 2250.0
internal const val STATION_HD = 4800.0

/**
 * The **open bay door** cut into the station's front (+Z) wall — the mouth the camera
 * flies through. [STATION_DOOR_W]×[STATION_DOOR_H] is the opening size; the door is
 * centred on the front wall at ([STATION_DOOR_CX], [STATION_DOOR_CY]). The front wall is
 * built as four hull pieces framing this hole (two jambs, a lintel, a sill) plus a
 * glowing accent rim ([buildStationDoorRim]) around the opening. @see buildStationFront
 */
internal const val STATION_DOOR_W = 6000.0
internal const val STATION_DOOR_H = 3600.0
internal const val STATION_DOOR_CX = STATION_CX
internal const val STATION_DOOR_CY = STATION_CY

/**
 * Cinematic shaping of the **fly-through-the-door** shelf journeys ([flyStationEnter] on
 * the way in, [resetCamera]'s hangar return on the way out) — the two-leg replacement for
 * the single stash arc when the station is enabled.
 *
 * - [STATION_APPROACH] world-units the outside **staging pose** parks beyond the front
 *   door wall (`STATION_CZ + STATION_HD + STATION_APPROACH` on +Z), looking back through
 *   the door at the shelf so the door mouth frames the destination on the approach.
 * - [STATION_LEG_A_FRAC] fraction of the journey spent on leg A (fly to the staging pose
 *   / fly out to it); the remainder is leg B (fly in through the door / fly on home).
 * - [STATION_ENTER_PULLOUT] the small Bézier apex bulge of the door-transit leg — kept
 *   modest so the fly-in reads as a straight punch through the doorway, not an orbit.
 * - [STATION_INTERIOR_STANDOFF] caps the camera→shelf standoff of the **interior park**
 *   pose ([shelfArrivalPose]'s derived distance is clamped to this) so the camera always
 *   lands *inside* the front door wall (`STATION_CZ + STATION_INTERIOR_STANDOFF` stays
 *   below the wall at `STATION_CZ + STATION_HD`) rather than poking back outside it.
 * @see flyStationEnter @see resetCamera @see stationStagingPose
 */
internal const val STATION_APPROACH = 3150.0
internal const val STATION_LEG_A_FRAC = 0.56
internal const val STATION_ENTER_PULLOUT = 280.0
internal const val STATION_INTERIOR_STANDOFF = 2450.0

/**
 * Frame length of the short **door ↔ dock transit** leg shared by the two camera-only dock
 * flights: the settle *into* the dock (leg B of the [flyStationEnter] `v` visit, flying in
 * through the bay door to the interior park) and the back-out *from* it (leg A of
 * [resetCamera]'s return, reversing out through the door). This leg only covers the short
 * door-to-shelf hop, so timing it like the long approach / descent legs made the camera
 * *crawl* through the doorway — the smootherstep tracer's soft, near-zero-velocity ends
 * stretched over a small distance, which read as the "camera settles slowly into the dock"
 * (and, in reverse, the sluggish first moment leaving it). A short fixed budget keeps the
 * near-dock motion crisp while the big approach / descent legs keep their stately pace.
 * @see flyStationEnter @see resetCamera
 */
internal const val STATION_DOCK_TRANSIT_FRAMES = STASH_CAM_FRAMES * 0.18

/**
 * Pace of the no-pane hangar return's long **descent** leg ([resetCamera] leg B — the
 * fly-home-watching-the-ship-recede) relative to a stash journey. Below `1.0` keeps the drop
 * home brisk without touching the shared [STASH_CAM_FRAMES] (which times real stash / unstash
 * pane flights and must stay locked to the pane). @see resetCamera
 */
internal const val STATION_RETURN_SPEED = 0.7

/**
 * The **stash chase cam** — how the camera trails a stashing / unstashing pane so you
 * watch it fly the whole way up to (or down from) the station, instead of the camera
 * doing its own scripted arc. Each frame the camera is parked at a fixed offset from the
 * pane's live position ([tickStashChase]): mostly **below** it and a little in **front**
 * of its face, looking up at it, so the terminal reads while the majestic ship looms beyond.
 *
 * The distance is a cinematic **zoom-out/in hump** over journey progress: close on the pane
 * leaving the ring, pulling way back to [STASH_CHASE_FAR_DIST] at [STASH_CHASE_PEAK] — the
 * mid-flight "see the whole cargo ship" wide shot as the pane approaches the door — then
 * dollying back in to follow it through the doorway to its shelf. Symmetric for stash and
 * unstash (progress runs from the origin end toward the station either way).
 *
 * - [STASH_CHASE_NEAR_DIST] camera→pane distance at the ends (pane fills frame).
 * - [STASH_CHASE_FAR_DIST] the pulled-back distance at the hump's peak (whole ship in view).
 * - [STASH_CHASE_PEAK] journey fraction (0..1) the pull-back peaks at — placed late so the
 *   wide shot lands as the pane nears / enters the door.
 * - [STASH_CHASE_OFF_X]/[STASH_CHASE_OFF_Y]/[STASH_CHASE_OFF_Z] the *direction* of the
 *   camera offset from the pane (normalized at use): +Z is in front of the pane's face
 *   (the door side), −Y a gentle below bias, +X a slight side bias for depth. Kept mostly
 *   **+Z (front)** and only slightly below so the camera stays on the door side of the hull
 *   through the whole transit — trailing far below would put the solid floor and front wall
 *   between the camera and the pane, hiding it for a few seconds as it enters/leaves.
 * - [STASH_CHASE_LOOK_UP] world-units the aim rises above the pane at the peak, tilting the
 *   ship up into frame on the reveal.
 * - [STASH_CHASE_SETTLE_FRAMES] the short home-settle flight after an unstash chase reaches
 *   the ring, easing the trailing pose onto the pristine 1:1 view instead of snapping.
 * @see tickStashChase @see armStashChase
 */
internal const val STASH_CHASE_NEAR_DIST = 1150.0
internal const val STASH_CHASE_FAR_DIST = 8000.0
internal const val STASH_CHASE_PEAK = 0.5
internal const val STASH_CHASE_OFF_X = 0.15
internal const val STASH_CHASE_OFF_Y = -0.32
internal const val STASH_CHASE_OFF_Z = 1.0
internal const val STASH_CHASE_LOOK_UP = 1200.0
internal const val STASH_CHASE_SETTLE_FRAMES = 150.0

/**
 * The **chase spotlight**: while a stash chase runs, every pane except the travelling one
 * fades toward [STASH_CHASE_OTHER_OPACITY] so nothing occludes it — the ring's neighbour
 * panes (especially the one that rotates to the front when you stash) sit right at the
 * camera and were hiding the travelling pane for the first second or two of the trip (and
 * again just before an unstash lands). The travelling pane is held lit. [STASH_CHASE_FOCUS_EASE]
 * is the per-frame ease of the spotlight in/out — a quick dissolve of the world as you fly
 * off with the pane, and back as you return. @see spikeChaseFocus @see tickStashChase
 */
internal const val STASH_CHASE_OTHER_OPACITY = 0.0
internal const val STASH_CHASE_FOCUS_EASE = 0.14

/** Frames over which the chase's trailing pose eases in from the camera's start pose (no frame-1 snap). */
internal const val STASH_CHASE_EASE_IN = 45.0

/**
 * The station hull's **palette** (CSS colour strings). The walls are a dark metallic
 * blue-grey so the neon panes, beacons and door rim read brightly against them; the
 * door rim and interior trim glow in the live theme accent ([SpikeChrome.accent]) at
 * build time, not from these constants. @see buildStationWall
 */
internal const val STATION_HULL_LIGHT = "#141b28"
internal const val STATION_HULL_MID = "#0b1019"
internal const val STATION_HULL_DARK = "#05070d"

/** Seconds per glow-pulse breath of the door rim (pure-CSS keyframe, like the beacons). */
internal const val STATION_DOOR_PULSE_S = 3.0

/**
 * **Texture down-scale** for the station hull. The walls are huge in world units (the
 * ceiling spans `2·STATION_HW × 2·STATION_HD` ≈ 8800×6400), and a CSS3D plane that big is
 * a giant DOM layer: browsers cap composited-layer / rasterization size (commonly 8–16k
 * px), so an oversized plane **flickers and, past the cap, fails to paint at all — going
 * transparent and letting the 2D UI beneath bleed through** (the same failure that shelved
 * [buildCosmos]). So every hull panel is built at `worldSize / STATION_TEX_SCALE` pixels
 * and the [CSS3DObject] is scaled back up by this factor: the DOM element stays small and
 * cheap while occupying the full world size. Blur/border/radius are authored in the small
 * build-pixel space, so they render this factor larger on screen. @see buildStationWall
 */
internal const val STATION_TEX_SCALE = 10.0

/**
 * World-units the glowing door rim is nudged out (+Z) from the front wall plane so it is
 * **not coplanar** with the door-frame hull pieces — coplanar CSS3D planes z-fight and
 * shimmer. Tiny relative to the standoffs, so the rim still reads as sitting in the
 * doorway. @see buildStashStation
 */
internal const val STATION_RIM_Z_LIFT = 12.0

/**
 * The stashed **panes** enter and leave through the front bay door too (not a floor hatch —
 * the deck is solid). A pane climbing from the ring far below routes up *outside* the front
 * of the hull to [STATION_PANE_DOOR_OUT] beyond the door, then in through the doorway to its
 * shelf ([stashPanePath]) — a two-leg path split at [STATION_PANE_LEG_A] (fraction on the
 * outside climb; the rest is the door transit). Routing outside the front keeps the pane
 * clear of the solid floor on the way up.
 * @see stashPanePath
 */
internal const val STATION_PANE_DOOR_OUT = 1100.0
internal const val STATION_PANE_LEG_A = 0.55

/**
 * **Feature flag** for the cosmos dressing — the decorative planets, nebulae and
 * star clusters ([buildCosmos]). **Disabled for now**: the first CSS3D
 * implementation (big gradient/box-shadow DOM planes, billboarded to the camera
 * every frame) caused severe full-scene flicker. Until the rendering approach is
 * reworked (freeze transforms at rest, pre-rasterize the bodies, or move them to
 * the WebGL layer), the world ships without the dressing. Flip to `true` to see
 * the current state. @see buildCosmos @see tickCosmos
 */
internal const val SPIKE_COSMOS_ENABLED = false

/**
 * **Feature flag** for freezing stashing panes to a static snapshot in flight — a pane
 * flying to / from the stash shelf (a lone pane, or every sheet of a tab bundle including the
 * front sheet) has its live terminal body swapped for a one-shot `<canvas>` snapshot and its
 * wrapper promoted to a stable composited layer, so the moving CSS3D plane re-samples a cached
 * raster instead of re-rasterizing live DOM every frame. **Disabled for now** while we weigh
 * whether it meaningfully helps the take-off stutter; flip to `true` to re-enable. When
 * `false`, [tickPaneFreeze] thaws any in-flight snapshot each frame, so toggling it off mid-run
 * cleanly restores every pane to its live body. @see tickPaneFreeze @see freezePaneSnapshot
 */
internal const val SPIKE_FLIGHT_FREEZE_ENABLED = false

/**
 * **Feature flag** for extending [SPIKE_FLIGHT_FREEZE_ENABLED] to panes *sitting* at the stash
 * site — a parked tab bundle, or a lone pane rested on the shelf. When `false` (default) those
 * at-rest panes always paint **live**: freezing is strictly the journey, never the destination.
 * When `true`, they are also held as static snapshots while parked (cheaper GPU tiles for a
 * crowded shelf, at the cost of a shelf preview that stops updating until brought back). Has no
 * effect unless [SPIKE_FLIGHT_FREEZE_ENABLED] is also `true`. @see tickPaneFreeze
 */
internal const val SPIKE_FREEZE_PARKED_ENABLED = false

/**
 * Rate multiplier on [spikeBobPhase] for the **cosmos drift** — the slow vertical
 * float of the decorative planets/nebulae/star clusters ([tickCosmos]). Below 1 so
 * the sky drifts even more languidly than the pane bob: celestial bodies should
 * feel massive, not bobbing corks. Per-body amplitude/phase live in the catalog
 * ([buildCosmos]); only the shared cadence is tuned here.
 */
internal const val COSMOS_DRIFT_RATE = 0.45

/**
 * The colour a **working** (agent-running) pane breathes, and the radians/frame its
 * breath advances. Any pane whose session state is `"working"` pulses a translucent
 * veil of this colour between [WORKING_PULSE_MIN]..[WORKING_PULSE_MAX] opacity — even
 * the centred front pane — so you can spot a busy agent from across the ring. The
 * *only* pane that stops breathing is the one you've **engaged** (Enter to capture),
 * so the pane you're actively typing into stays calm; it resumes on disengage.
 */
internal const val WORKING_PULSE_COLOR = "#3b82f6"
internal const val WORKING_PULSE_SPEED = 0.015 // rad/frame → ~7s per slow breath (kept)
internal const val WORKING_PULSE_MIN = 0.07 // fuller colour journey, still never fully out…
internal const val WORKING_PULSE_MAX = 0.30 // …travelling further before easing back

/**
 * **Feature flag** (no UI) for the **phaser-fire pane close** — a purely cosmetic
 * alternative to the instant shrink-out of [confirmRemove]. When `true`, removing a
 * pane in the 3D world does *not* immediately mark it dying; instead the camera pours
 * a several-second burst of Star-Trek-style phaser fire at it — irregular bright bolts
 * streaking from the viewer (the "camera") and converging on the pane, heating its
 * background to a deepening, flickering red while the pane visibly **bulges more and
 * more** (the same [FLEX_BULGE] fisheye the engage flex uses, but driven ever outward
 * and jolted by each hit so the pane looks progressively wounded) — before it finally
 * **implodes**, its bulge snapping inward as it collapses smoothly into its own centre
 * and vanishes. When `false` the close is the classic instant shrink-out. Off by
 * default; flip to `true` to arm the effect.
 *
 * The barrage runs [PHASER_TOTAL_FRAMES] frames (~60 fps), then a [PHASER_COLLAPSE_FRAMES]
 * implosion. Bolts spawn at an irregular cadence between [PHASER_BOLT_INTERVAL_MIN] and
 * [PHASER_BOLT_INTERVAL_MAX] frames apart (tightening as the barrage intensifies), each
 * living [PHASER_BOLT_LIFE] frames as it flies. The pane's red heat veil ramps to
 * [PHASER_TINT_MAX] opacity with a [PHASER_TINT_FLICKER] shimmer; each bolt's beam is a
 * jagged [PHASER_BOLT_SEGS]-segment streak.
 *
 * The "wounded" deformation (applied in the render loop) grows the fisheye bulge from
 * [PHASER_BULGE_START] to [PHASER_BULGE_MAX] × [FLEX_BULGE] over the barrage, on top of a
 * constant [PHASER_HURT_TREMOR] shudder (at [PHASER_HURT_TREMOR_SPEED]) and a
 * [PHASER_HURT_TILT] wobble, while the pane swells by [PHASER_SWELL]. Each landed bolt
 * adds [PHASER_RECOIL_PER_HIT] of recoil (decaying by [PHASER_RECOIL_DECAY]/frame) that
 * punches the bulge ([PHASER_RECOIL_BULGE]) and scale ([PHASER_RECOIL_SCALE]) for a jolt.
 * The collapse eases the swollen scale to 0 while driving the bulge inward by
 * [PHASER_IMPLODE] × [FLEX_BULGE], so the pane caves into its centre.
 * @see startPhaserDeath @see tickPhaser
 */
internal const val PHASER_CLOSE_ENABLED = true
internal const val PHASER_TOTAL_FRAMES = 240.0 // ~4 s of fire before the collapse
internal const val PHASER_COLLAPSE_FRAMES = 52.0 // ~0.87 s smooth implosion into the centre
internal const val PHASER_BOLT_INTERVAL_MIN = 6 // frames between bolt volleys (min) — slower fire
internal const val PHASER_BOLT_INTERVAL_MAX = 17 // …and max, for irregular firing
internal const val PHASER_BOLT_LIFE = 11.0 // frames a bolt streak stays lit
internal const val PHASER_BOLT_SEGS = 7 // jagged segments per beam (the "fire" wobble)
internal const val PHASER_TINT_MAX = 0.86 // peak red-heat veil opacity at collapse
internal const val PHASER_TINT_FLICKER = 0.18 // per-frame opacity shimmer of the veil
internal const val PHASER_BULGE_START = 0.25 // fisheye bulge at barrage start (× FLEX_BULGE)
internal const val PHASER_BULGE_MAX = 1.85 // …swelling to this (× FLEX_BULGE) by the end
internal const val PHASER_HURT_TREMOR = 22.0 // px of constant shuddering bulge tremor
internal const val PHASER_HURT_TREMOR_SPEED = 0.55 // rad/frame of that shudder
internal const val PHASER_HURT_TILT = 0.05 // rad of hurt wobble tilt
internal const val PHASER_SWELL = 0.10 // fraction the pane swells (bloats) over the barrage
internal const val PHASER_RECOIL_PER_HIT = 0.5 // recoil added per landed bolt
internal const val PHASER_RECOIL_DECAY = 0.86 // per-frame recoil decay
internal const val PHASER_RECOIL_BULGE = 46.0 // px of bulge punch per unit recoil
internal const val PHASER_RECOIL_SCALE = 0.05 // scale punch per unit recoil
internal const val PHASER_IMPLODE = 1.6 // inward bulge (× FLEX_BULGE) at full collapse

/**
 * **Feature flag** (no UI) for the **wormhole pane spawn** — the birth-effect
 * counterpart to the [PHASER_CLOSE_ENABLED] phaser-fire close. When `true`, a pane
 * created while the 3D world is open does *not* simply grow in at its ring slot;
 * instead the camera swings off to a patch of open space to the **side**, a swirling
 * Babylon-5 / Star-Trek **wormhole spirals open** there, and the new pane **emerges
 * out of it** — pushed toward the viewer in a flash of light, tumbling, then flying to
 * its ring slot while the vortex collapses shut behind it and the camera follows it
 * home. When `false` the spawn is the classic instant grow-in. Off would be safest for
 * a demo of many panes, but a single interactive create is the target; the effect only
 * arms for a **lone** newborn while the camera is idle (see [armWormholeSpawn]), so a
 * workspace-restore burst falls back to the plain grow-in.
 *
 * The sequence runs in frames (~60 fps): [WORMHOLE_FOCUS_FRAMES] of camera flight to
 * frame the spawn point, [WORMHOLE_OPEN_FRAMES] for the vortex to spiral open, then
 * [WORMHOLE_EMERGE_FRAMES] for the pane to push out and sail to its slot (the vortex
 * begins collapsing over the tail [WORMHOLE_CLOSE_TAIL] of that leg). The camera flies
 * back over [WORMHOLE_RETURN_FRAMES], tracking the emerging pane the whole way.
 * @see armWormholeSpawn @see tickWormhole @see reconcileRing
 */
internal const val WORMHOLE_SPAWN_ENABLED = true

/**
 * Frames of the opening camera flight to frame the spawn point (~2.8 s). "Frames" here
 * are **60fps-equivalent** — [tickWormhole] advances the phase by the wall-clock delta
 * normalised to a 60Hz step ([spikeDtFrames]), so the duration is the same on a 60Hz or
 * a 144Hz+ display. @see spikeDtFrames
 */
internal const val WORMHOLE_FOCUS_FRAMES = 170.0

/** 60fps-equivalent frames for the vortex to spiral fully open (~2.3 s), after the camera lands. */
internal const val WORMHOLE_OPEN_FRAMES = 140.0

/** 60fps-equivalent frames for the pane to emerge and sail from the vortex to its ring slot (~3.7 s). */
internal const val WORMHOLE_EMERGE_FRAMES = 220.0

/**
 * Frames of the camera's follow-the-pane flight back home. **Must equal**
 * [WORMHOLE_EMERGE_FRAMES] (both legs start together at the open-end), so the camera
 * lands home the *same* frame the pane docks. If the return outlasts the emerge, the
 * camera keeps pulling in — shedding its arc's pull-back — after the pane has already
 * settled, ballooning the docked pane (a size "jump after settle"). @see tickWormhole
 */
internal const val WORMHOLE_RETURN_FRAMES = 220.0

/**
 * The return arc's apex pull-back / rise — kept at **0** (a straight pull home). A
 * non-zero pull-back pushes the camera's mid-flight apex *behind* the home pose, so the
 * docked pane shrinks below its final size and then snaps back up as the apex collapses
 * on landing — read as a sudden "grow after settle". The outbound focus flight keeps its
 * cinematic swing ([WORMHOLE_FOCUS_PULLOUT]); only the return must stay flat. @see tickWormhole
 */
internal const val WORMHOLE_RETURN_PULLOUT = 0.0
internal const val WORMHOLE_RETURN_RISE = 0.0

/**
 * Fraction of the emerge leg over which the vortex **collapses shut** behind the pane
 * — it stays fully open while the pane is coming through, then caves in over this tail
 * so it has vanished by the time the pane reaches its slot. @see tickWormhole
 */
internal const val WORMHOLE_CLOSE_TAIL = 0.42

/**
 * Where the vortex opens in world space — off to the **right** of the pane sphere,
 * lifted a touch and set forward of centre so it sits in open sky the camera can frame
 * against the void rather than against the ring. Relative to the sphere ([RING_R] ≈
 * 1150): a comfortable ring-and-a-half out to the side. @see armWormholeSpawn
 */
internal const val WORMHOLE_POS_X = 1780.0
internal const val WORMHOLE_POS_Y = 230.0
internal const val WORMHOLE_POS_Z = 640.0

/**
 * The camera's **spawn-viewing pose**, derived from the vortex position each open so it
 * self-adjusts to the window's [perspDistance]:
 * - [WORMHOLE_CAM_BACK] × perspDistance is how far the camera parks back on +Z from the
 *   vortex, so the portal frames at a comfortable size (a plane at `d × perspDistance`
 *   reads at `1/d` of 1:1, so 1.25 lands the funnel mouth at ~80% of the view).
 * - [WORMHOLE_CAM_SIDE] / [WORMHOLE_CAM_LIFT] nudge the camera off-axis (a slight
 *   three-quarter angle and a gentle look-down) so the emergence reads with depth
 *   rather than dead-on flat. @see armWormholeSpawn
 */
internal const val WORMHOLE_CAM_BACK = 1.25
internal const val WORMHOLE_CAM_SIDE = 300.0
internal const val WORMHOLE_CAM_LIFT = 170.0

/** Bézier apex shaping of the focus flight to the spawn point (see [flyCamTo]). */
internal const val WORMHOLE_FOCUS_PULLOUT = 700.0
internal const val WORMHOLE_FOCUS_RISE = 320.0

/**
 * The **vortex disc** — a whirlpool of turbulent blue cloud (SVG `feTurbulence`) on a
 * flat plane with a *dark hole* at its centre, tilted off the view axis so it
 * foreshortens to an ellipse like the classic Star-Trek / Babylon-5 rift viewed at an
 * angle. Two counter-rotating cloud layers churn the gas; the pane is born out of the
 * dark eye. @see buildWormholeVortex
 *
 * - [WORMHOLE_DIAMETER] world-units — the disc diameter.
 * - [WORMHOLE_TILT_X]/[WORMHOLE_TILT_Y] radians the disc is canted after billboarding, so
 *   the round disc reads as a tilted ellipse (big X cant ≈ the shallow reference angle).
 */
internal const val WORMHOLE_DIAMETER = 1320.0
internal const val WORMHOLE_TILT_X = 0.92
internal const val WORMHOLE_TILT_Y = 0.16

/**
 * Radians/frame the vortex's primary cloud layer drifts at rest, and the extra spin it
 * gains while the pane emerges. Deliberately slow — a majestic subspace churn, not a
 * spinning disc. The finer wisp layer counter-rotates a touch faster (see [tickWormhole]).
 */
internal const val WORMHOLE_SPIN_SPEED = 0.011
internal const val WORMHOLE_SPIN_EMERGE = 0.016

/**
 * Peak **elastic overshoot** of the vortex as it snaps open — a fraction past 1.0 at
 * the crest of the open ease before it settles, so the portal punches into existence
 * rather than fading in. @see tickWormhole
 */
internal const val WORMHOLE_OPEN_OVERSHOOT = 0.18

/**
 * The emerging pane's scale **overshoot** — it pops out slightly larger than its final
 * ring size (a fraction past 1.0) mid-flight, then settles to 1:1 as it docks, so the
 * emergence has a lunge toward the viewer. @see tickWormhole
 */
internal const val WORMHOLE_PANE_OVERSHOOT = 0.14

/** Radians of in-plane **tumble** the pane spins through as it emerges, decaying to 0 on arrival. */
internal const val WORMHOLE_PANE_TUMBLE = 0.55

/**
 * World-units the pane's emergence path is pushed **toward the camera** from the vortex
 * centre, so the pane plane stays clearly *in front of* the tilted vortex disc and never
 * intersects it. Without this the pane spawns coplanar with the vortex and the CSS-3D
 * renderer splits the two intersecting planes along their seam — a hard diagonal clip
 * across the terminal. Must exceed the vortex's half-depth
 * (`WORMHOLE_DIAMETER/2 × sin(tilt)`) so the whole disc is cleared. @see tickWormhole
 */
internal const val WORMHOLE_PANE_FRONT = 620.0

/**
 * Fraction of the emerge leg's *tail* over which the pane's wormhole overrides ease back
 * into the render loop's own ring-slot transform, so the hand-off at arrival is
 * continuous (no scale/opacity snap). @see tickWormhole
 */
internal const val WORMHOLE_HANDOFF = 0.22

/**
 * The vortex palette. The swirl is built from soft **blue** cloud bands (heavily
 * blurred into wisps, not hard spokes — see [buildWormholePortal]); the theme accent
 * ([SpikeChrome.accent]) is woven in so the rift keys to the active theme. The **core**
 * is a hot glowing eye — the warm orange/pink centre of the classic Star-Trek rift —
 * built from [WORMHOLE_CORE_HOT] fading out through [WORMHOLE_CORE_WARM]. Colours are
 * 6-digit hex; per-stop alpha is appended as an 8th/9th `#rrggbbaa` pair at build time.
 * @see buildWormholePortal
 */
internal const val WORMHOLE_SWIRL_A = "#7fb4ff" // soft blue cloud band
internal const val WORMHOLE_SWIRL_B = "#bcdcff" // pale blue cloud band
internal const val WORMHOLE_CORE_HOT = "#fff2dc" // white-hot centre of the eye
internal const val WORMHOLE_CORE_WARM = "#ff9a5a" // warm orange the eye falls off through

/**
 * **Feature flag** for how a *working* (agent-running) pane signals itself:
 *  - `true`  → an **animated jagged border** — a spiky, electric outline whose dashes
 *    "run" around the pane's perimeter each frame (see [WorkingBorder] / [jaggedRectPath]).
 *  - `false` → the legacy [WORKING_PULSE_COLOR] breath veil that fades in/out.
 * Flip this to compare the two treatments; both are wired up per pane, only the
 * selected one is shown.
 */
internal const val WORKING_BORDER_ENABLED = true

/**
 * Animated dotted "working" border tuning — a rounded-rectangle outline drawn as
 * round-capped dots that drift slowly around the pane.
 *  - [WORKING_BORDER_COLOR] the dot colour.
 *  - [WORKING_BORDER_RADIUS] px corner radius (matches the wrapper's rounded corners).
 *  - [WORKING_BORDER_PAD] px inset of the outline from the pane edge (keeps it inside
 *    the `overflow:hidden` wrapper).
 *  - [WORKING_BORDER_WIDTH] stroke width in px (with round caps → the dot diameter).
 *  - [WORKING_BORDER_DASH] the `stroke-dasharray`: a tiny dash + long gap → spaced dots.
 *  - [WORKING_BORDER_SPEED] px/frame the dash offset advances — a slow crawl, not a race.
 */
internal const val WORKING_BORDER_COLOR = "#5b9bff"
internal const val WORKING_BORDER_RADIUS = 8.0
internal const val WORKING_BORDER_PAD = 1.5 // ≈ half the stroke → dots straddle the pane edge
internal const val WORKING_BORDER_WIDTH = 3.0
internal const val WORKING_BORDER_DASH = "0.1 13" // round-capped 0.1 dash → a dot, every 13px
internal const val WORKING_BORDER_SPEED = 0.14

/**
 * Alternative "working" treatment to the [WORKING_BORDER_COLOR] jagged/dotted border:
 * a **pulsating light** — the same outward `box-shadow` bloom mechanic the urgent
 * [WAITING_GLOW_COLOR] halo uses, breathing at the calm working cadence
 * ([WORKING_PULSE_SPEED], reusing [spikePulsePhase]) rather than the urgent throb.
 * Selected by the **Status indication** setting ([spikeStatusIndication]): the `GLOW`
 * and `GLOW_ANIMATION` styles paint this glow ([spikeStatusShowGlow]), and
 * `GLOW_ANIMATION` adds the travelling dots on top ([spikeStatusShowDots]). Because the
 * bloom bleeds *outside* the pane it stays spottable across the ring, like the amber
 * waiting halo, but its softer cadence reads as "busy, no action needed".
 *
 * The colour is the **reactor's working blue** ([WARP_CORE_COLOR]), so a working pane
 * reads the same whether the world is in `REACTOR` or a glow style — the glow just uses
 * the reactor's colours (blue working / amber waiting) at its own softer cadence, keeping
 * the two status modes in agreement.
 *
 *  - [WORKING_GLOW_COLOR] the halo colour as bare `r,g,b` (fed into `rgba(...)`).
 *  - [WORKING_GLOW_MIN]/[WORKING_GLOW_MAX] the halo alpha floor/ceiling; the floor is
 *    non-zero so the glow never fully fades out between breaths.
 *  - [WORKING_GLOW_BLUR] px blur radius of the bloom.
 *  - [WORKING_GLOW_SPREAD] px spread radius — pushes the bloom out past the pane edge.
 * @see spikeStatusIndication @see spikeStatusShowGlow @see WARP_CORE_COLOR
 */
internal const val WORKING_GLOW_COLOR = "79,184,255" // #4fb8ff — reactor blue (matches WARP_CORE_COLOR)
internal const val WORKING_GLOW_MIN = 0.22 // floor: the glow never fully fades out
internal const val WORKING_GLOW_MAX = 0.85 // full flare
internal const val WORKING_GLOW_BLUR = 60.0 // px — wide soft bloom, spottable from across the ring
internal const val WORKING_GLOW_SPREAD = 8.0 // px — pushes the bloom out past the edge

/**
 * The resting `box-shadow` every pane wrapper carries — a soft dark bloom that lifts
 * the pane off the starfield and gives it depth. Kept as a constant so the per-frame
 * [WAITING_GLOW_COLOR] halo can be *layered on top of it* (comma-appended) without the
 * render loop having to restate the depth shadow inline. Must match the value baked
 * into the wrapper's initial `cssText`.
 */
internal const val PANE_BASE_SHADOW = "0 0 42px rgba(0,0,0,0.55)"

/**
 * A pane that **needs your input** — session state `"waiting"`, i.e. an agent has
 * stopped and is blocking on you (the same state the toolkit reports as "needs input")
 * — pulses a **red halo** that bleeds *outward* from the pane edge as an extra
 * `box-shadow` layered over [PANE_BASE_SHADOW]. Unlike the inset "working" veil/border,
 * an outward bloom stays visible even when the pane is small and far across the ring, so
 * you can spot a pane that wants you from clear across the world. The halo breathes
 * between [WAITING_GLOW_MIN]..[WAITING_GLOW_MAX] alpha at [WAITING_PULSE_SPEED] rad/frame
 * — noticeably faster than the ~7s working breath, reading as a more urgent "come here".
 * The pane border also turns [WAITING_GLOW_BORDER] while waiting.
 *
 *  - [WAITING_GLOW_COLOR] the halo colour as bare `r,g,b` (fed into `rgba(...)`).
 *  - [WAITING_GLOW_BORDER] the same red as a hex border colour.
 *  - [WAITING_PULSE_SPEED] rad/frame the urgency breath advances (~3.3s per pulse).
 *  - [WAITING_GLOW_MIN]/[WAITING_GLOW_MAX] the halo alpha floor/ceiling; the floor is
 *    deliberately non-zero so the red never fully disappears between pulses.
 *  - [WAITING_GLOW_BLUR] px blur radius of the bloom (a big soft spread seen from afar).
 *  - [WAITING_GLOW_SPREAD] px spread radius — pushes the bloom out past the pane edge.
 */
internal const val WAITING_GLOW_COLOR = "239,68,68" // #ef4444
internal const val WAITING_GLOW_BORDER = "#ef4444"
internal const val WAITING_PULSE_SPEED = 0.032 // rad/frame → ~3.3s per pulse — urgent but not frantic
internal const val WAITING_GLOW_MIN = 0.28 // floor: the red never fully fades out
internal const val WAITING_GLOW_MAX = 0.90 // full flare
internal const val WAITING_GLOW_BLUR = 64.0 // px — a wide soft bloom, spottable from across the ring
internal const val WAITING_GLOW_SPREAD = 10.0 // px — pushes the bloom out past the edge

// ---------------------------------------------------------------------------
// WARP-CORE CHARGE + AWAITING-INPUT (HOLD) — the [spikeStatusIndication] cinematic
// reactor treatment (the `p` key), a layer-independent alternative to the working
// dots/green-glow and the red needs-input halo. A *working* pane spools a blue
// reactor that breathes and, on finishing, discharges a bloom + thruster plume; a
// *waiting* pane idles amber, leaning out of formation and radiating sonar pings.
// Numbers are ported from the standalone WARP_CORE_SPIKE.html spec (its `<script>`
// tuning constants are the starting values); charge-rate constants are per-60fps-
// frame (scaled by [spikeDtFrames]) and second-based constants (durations,
// escalation, ping cadence) are driven off [spikeWarpClock].
// @see World3DSpikeWarpCore.kt @see tickWarpCore @see spikeStatusIndication
// ---------------------------------------------------------------------------

/**
 * Charge **spool-up** rate per 60fps frame: `chargeProg += (1 - chargeProg) * this`.
 * Deliberately slow (~1.5s to ~0.85) so a brief blip never visibly charges a reactor —
 * short commands self-filter below [WARP_MIN_DISCHARGE] and earn no discharge bloom.
 * @see tickWarpCore
 */
internal const val WARP_ATTACK = 0.020

/** Gentle charge **drain** per frame while a reactor idles (run ended without discharge). @see tickWarpCore */
internal const val WARP_COOLDOWN = 0.05

/** Fast multiplicative charge **drain** per frame during a discharge: `chargeProg *= WARP_DRAIN^frames`. @see tickWarpCore */
internal const val WARP_DRAIN = 0.86

/**
 * The charge floor a reactor must reach to earn a **discharge** (bloom + thruster) when
 * its run ends — the anti-strobe / short-command guard. Below it the run just cools off. @see tickWarpCore
 */
internal const val WARP_MIN_DISCHARGE = 0.28

/** Discharge bloom + thruster **duration** in seconds. @see tickWarpCore @see tickWarpCoreOverlay */
internal const val WARP_DISCHARGE_S = 0.85

/** Breath cadence of the sustain hum in rad/second — a slow ~4s reactor breathing. @see tickWarpCore */
internal const val WARP_BREATH_SPEED = 1.6

/** Breath amplitude of the sustain hum (fraction of charge). @see tickWarpCore */
internal const val WARP_BREATH_AMP = 0.06

/** Extra charge intensity a burst of terminal output adds via [RingPane.warpFlicker] (0..1). @see tickWarpCore */
internal const val WARP_FLICKER_PER_OUTPUT = 0.5

/** Per-frame multiplicative decay of [RingPane.warpFlicker] (output-activity flicker fades out). @see tickWarpCore */
internal const val WARP_FLICKER_DECAY = 0.90

/** Blue charging reactor colour as bare `r,g,b` (fed into `rgba(...)` for the outward halo). @see tickWarpCore */
internal const val WARP_CORE_COLOR = "79,184,255" // #4fb8ff — reactor blue
internal const val WARP_CORE_HEX = "#4fb8ff" // same, as a hex border tint
internal const val WARP_CORE_HOT = "#bfe6ff" // white-hot discharge centre (hex)
internal const val WARP_CORE_HOT_RGB = "191,230,255" // #bfe6ff as bare r,g,b for canvas gradients

/** Amber "holding for you" reactor colour as bare `r,g,b`. Distinct from blue-work / cyan-success / orange-fail. @see tickWarpCore */
internal const val WARP_AMBER_COLOR = "240,182,73" // #f0b649 — attention amber
internal const val WARP_AMBER_HEX = "#f0b649"

/** Orange failure sputter colour for a discharge whose command failed (exit ≠ 0). @see tickWarpCoreOverlay */
internal const val WARP_FAIL_COLOR = "224,101,90" // #e0655a

/** px blur radius of the reactor's outward glow bloom (a wide soft halo seen across the ring). @see tickWarpCore */
internal const val WARP_GLOW_BLUR = 60.0

/**
 * PERF (revertible) — reactor outward-halo blur pinning. The `box-shadow` halo cannot be
 * animated on the compositor, so **any** change to it re-rasterizes the whole blurred shadow
 * each frame; the original code also *grew* the blur radius with charge (up to
 * `WARP_GLOW_BLUR + 70 = 130px`), making that per-frame raster as large as possible.
 *
 * When [WARP_GLOW_BLUR_PINNED] is true the halo uses a **fixed** blur radius
 * (`WARP_GLOW_BLUR + [WARP_GLOW_BLUR_PINNED_ADD]`) and lets charge read through the pulsing
 * *alpha* alone — a smaller, constant raster area. Set it back to `false` to restore the
 * original charge-driven growth ([WARP_GLOW_BLUR_GROW_AMBER]/[WARP_GLOW_BLUR_GROW_BLUE]).
 * @see tickWarpCore
 */
internal const val WARP_GLOW_BLUR_PINNED = true

/** Static blur add (px) over [WARP_GLOW_BLUR] when [WARP_GLOW_BLUR_PINNED] — a mid of the old 0..70 / 0..40 growth. */
internal const val WARP_GLOW_BLUR_PINNED_ADD = 45.0

/** Original per-frame blur growth (px) added to the amber "hold" halo at full intensity (revert path). @see tickWarpCore */
internal const val WARP_GLOW_BLUR_GROW_AMBER = 70.0

/** Original per-frame blur growth (px) added to the blue "charge" halo at full intensity (revert path). @see tickWarpCore */
internal const val WARP_GLOW_BLUR_GROW_BLUE = 40.0

/** px spread radius of the reactor's outward glow bloom (pushes it past the pane edge). @see tickWarpCore */
internal const val WARP_GLOW_SPREAD = 8.0

/** Peak alpha of the charging blue outward glow at full charge. @see tickWarpCore */
internal const val WARP_GLOW_ALPHA = 0.85

/**
 * PERF (revertible) — **quantization step** for the outward-halo `box-shadow` alpha.
 *
 * Even with [WARP_GLOW_BLUR_PINNED] holding the blur radius constant, the halo still pulses
 * its *alpha* every frame — and because a blurred `box-shadow` cannot composite, **any** change
 * to the string re-rasterizes the whole ~[WARP_GLOW_BLUR]+ px shadow around every active pane.
 * The pulse is a slow sine, so consecutive frames are visually identical to two decimals.
 * [tickWarpCore] snaps the halo alpha to this step and **skips the DOM write when the snapped
 * string is unchanged** ([RingPane.lastShadowKey]) — cutting the shadow re-raster from ~60/s to
 * a handful/s per pane with no visible banding at this granularity. Set to `0.0` to restore the
 * original every-frame write (no quantization). @see tickWarpCore @see RingPane.lastShadowKey
 */
internal const val WARP_GLOW_ALPHA_STEP = 0.02

/** Peak opacity of the inner "heat" veil laid over the terminal (kept low so text stays legible). @see tickWarpCore */
internal const val WARP_HEAT_MAX = 0.55

/**
 * The **core ring** radial-ellipse gradients (blue charge / amber hold) — the big glowing
 * ring of light that fills the pane interior, ported verbatim from the spec's `.core`.
 * `radial-gradient(closest-side, …)` is an *ellipse* matching the pane aspect: a transparent
 * centre (so the terminal reads through) rising to a bright ring hugging just inside the
 * edges, then falling back to transparent. Screen-blended + heavily blurred by [ensureWarpCore].
 * @see tickWarpCore
 */
internal const val WARP_RING_BLUE =
    "radial-gradient(closest-side, transparent 50%, #4fb8ff55 64%, #4fb8ff 78%, #2a86d8bb 88%, transparent 100%)"
internal const val WARP_RING_AMBER =
    "radial-gradient(closest-side, transparent 48%, #f0b64966 64%, #f0b649 78%, #c8871fbb 88%, transparent 100%)"

/**
 * PERF (revertible) — **pre-softened** core-ring gradients used when [WARP_RING_LOWCOST].
 * The original [WARP_RING_BLUE]/[WARP_RING_AMBER] rely on a heavy per-frame `filter:blur` to
 * turn a fairly sharp ring band into a broad glow. These variants **bake that softness into
 * the colour stops** — the fade starts earlier, the bright band is wider and lower-contrast —
 * so only a small residual [WARP_RING_BLUR_LOWCOST] is needed, eliminating almost all of the
 * ring's blur cost. Tune the stops here if the softened look needs eyeballing. @see ensureWarpCore
 */
internal const val WARP_RING_BLUE_SOFT =
    "radial-gradient(closest-side, transparent 40%, #4fb8ff22 58%, #4fb8ffaa 76%, #2a86d866 90%, transparent 100%)"
internal const val WARP_RING_AMBER_SOFT =
    "radial-gradient(closest-side, transparent 38%, #f0b64922 58%, #f0b649aa 76%, #c8871f66 90%, transparent 100%)"

/**
 * PERF (revertible) — drop the core ring's expensive per-frame CSS `filter:blur`.
 *
 * The ring div carries `filter:blur([WARP_RING_BLUR]px)` + `mix-blend-mode`, and its opacity /
 * scale / colour change every frame — so the 2-pass Gaussian is re-run and can never be cached.
 * It is the single heaviest layer op in the world. When true, [ensureWarpCore] uses the
 * pre-softened gradients ([WARP_RING_BLUE_SOFT]/[WARP_RING_AMBER_SOFT]) plus only
 * [WARP_RING_BLUR_LOWCOST] of residual blur. Set to `false` to restore the original heavy
 * ring (sharp gradient + [WARP_RING_BLUR]). @see ensureWarpCore @see WARP_RING_BLUE_ACTIVE
 */
internal const val WARP_RING_LOWCOST = true

/** Residual ring blur (px) when [WARP_RING_LOWCOST] — small, since the gradient is pre-softened. */
internal const val WARP_RING_BLUR_LOWCOST = 6.0

/** Blur radius (px) of the core ring — softens the ellipse ring into a broad reactor glow. @see ensureWarpCore */
internal const val WARP_RING_BLUR = 22.0

/** The core-ring blur (px) actually applied: small residual when [WARP_RING_LOWCOST], else the original [WARP_RING_BLUR]. */
internal val WARP_RING_BLUR_ACTIVE = if (WARP_RING_LOWCOST) WARP_RING_BLUR_LOWCOST else WARP_RING_BLUR

/** The active blue "charge" core-ring gradient — pre-softened when [WARP_RING_LOWCOST]. @see tickWarpCore */
internal val WARP_RING_BLUE_ACTIVE = if (WARP_RING_LOWCOST) WARP_RING_BLUE_SOFT else WARP_RING_BLUE

/** The active amber "hold" core-ring gradient — pre-softened when [WARP_RING_LOWCOST]. @see tickWarpCore */
internal val WARP_RING_AMBER_ACTIVE = if (WARP_RING_LOWCOST) WARP_RING_AMBER_SOFT else WARP_RING_AMBER

/**
 * Awaiting-input (HOLD) heartbeat: a **slow, calm** breathing, NOT a fast strobe (this was
 * explicitly tuned down). Base sin frequency in rad/second, plus [WARP_HOLD_ESC_FREQ] added
 * as it escalates over [WARP_HOLD_ESC_S] seconds. @see tickWarpCore
 */
internal const val WARP_HOLD_FREQ = 0.7
internal const val WARP_HOLD_ESC_FREQ = 0.5 // added to the freq at full escalation
internal const val WARP_HOLD_ESC_S = 18.0 // seconds to fully escalate the heartbeat

/** Base amber intensity while holding; escalates by [WARP_HOLD_ESC_INTEN] the longer it waits. @see tickWarpCore */
internal const val WARP_HOLD_INTEN = 0.6
internal const val WARP_HOLD_ESC_INTEN = 0.35

/** Sonar-ping cadence while awaiting, in seconds: slower at first, tightening to [WARP_PING_MIN_S] as it escalates. @see spawnWarpPing */
internal const val WARP_PING_MAX_S = 3.4
internal const val WARP_PING_MIN_S = 2.2
internal const val WARP_PING_LIFE_S = 1.7 // sonar ring expansion duration (matches the CSS keyframe)

/**
 * Collective **reactor load** → faint warm sky tint: the summed ring charge (averaged over
 * pane count) drives the HUD meter and a subtle warm overlay so a busy ring visibly hums.
 * [WARP_LOAD_TINT_ALPHA] is the tint's opacity at full average load. @see tickWarpCoreOverlay
 */
internal const val WARP_LOAD_TINT_ALPHA = 0.16
