/*
 * Split from World3DSpike.kt — tuning constants (ring geometry, fades, flex, fly, working/waiting signals).
 * See World3DSpike.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
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
 * The **slight camera tilt** (`j`, [toggleCameraTilt]) — a quick hop that parks the
 * camera a small step off-axis, still looking at the front pane, so the pane reads
 * at a gentle three-quarter angle:
 * - [TILT_SIDE] / [TILT_UP] sideways / upward step as fractions of the perspective
 *   distance — deliberately modest ("slightly tilted, not that much").
 * - [TILT_FRAMES] journey length (~/60 s); brisk, it's a nudge rather than a tour.
 */
internal const val TILT_SIDE = 0.30
internal const val TILT_UP = 0.14
internal const val TILT_FRAMES = 130.0

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
internal const val STASH_SHELF_Y = 9600.0
internal const val STASH_SHELF_Z = 260.0
internal const val STASH_ROW_X0 = -960.0
internal const val STASH_ROW_GAP = 900.0

/**
 * Frame length of a **stash / unstash journey** (~[STASH_CAM_FRAMES]/60 s) — the single
 * shared duration for *both* the camera flight ([flyCamTo]) **and** the pane's flight to
 * / from the shelf ([RingPane.stashProg] advances `1/STASH_CAM_FRAMES` per frame). Using
 * one duration and one smootherstep curve for both is what makes the **camera travel in
 * lockstep with the pane** — you watch it sail slowly across the air the whole way rather
 * than the pane arriving first. Deliberately long, so the trip is slow and cinematic.
 * @see flyCamTo @see stashFront @see startSpikeLoop
 */
internal const val STASH_CAM_FRAMES = 520.0

/**
 * Arc shape of a **stash / unstash** camera flight — how far the journey's Bézier apex
 * bulges out ([STASH_CAM_PULLOUT], radially away from the origin) and lifts
 * ([STASH_CAM_RISE], +Y). Gentler than the [CAM_RETURN_PULLOUT] `c` return (which
 * starts from wherever you flew to and pulls way back to reframe the whole sphere): a
 * stash starts from the home pose, so a smaller arc keeps the followed pane a
 * comfortable size and the sweep tasteful rather than flinging the camera to orbit.
 * @see flyCamTo @see stashFront
 */
internal const val STASH_CAM_PULLOUT = 1500.0
internal const val STASH_CAM_RISE = 1000.0

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
internal const val STASH_CAM_SWAY = 2400.0
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
 * Frame length of a **shelf browse** glide (~[SHELF_BROWSE_FRAMES]/60 s) — the short
 * hop ←/→ makes between adjacent shelf slots while the camera is up at the stash shelf
 * ([shelfBrowse]). Deliberately quick and flat (straight line, no pullout/sway/roll):
 * browsing the shelf should feel like stepping along a corridor, not another journey.
 * @see shelfBrowse @see STASH_CAM_FRAMES
 */
internal const val SHELF_BROWSE_FRAMES = 65.0

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
 * @see buildHomeBeacon
 */
internal const val BEACON_Y = 650.0
internal const val BEACON_W = 640
internal const val BEACON_H = 900
internal const val BEACON_SPIN_SPEED = 0.003 // rad/frame → ~1 revolution / 35 s
internal const val BEACON_PULSE_S = 2.4

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
 * @see buildStashBeacon
 */
internal const val STASH_BEACON_RISE = 820.0
internal const val STASH_BEACON_S = 640
internal const val STASH_BEACON_SPIN_SPEED = -0.005 // rad/frame → ~1 revolution / 21 s
internal const val STASH_BEACON_PULSE_S = 3.6

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
 * a **pulsating green light** — the same outward `box-shadow` bloom mechanic the urgent
 * [WAITING_GLOW_COLOR] red halo uses, only green and breathing at the calm working
 * cadence ([WORKING_PULSE_SPEED], reusing [spikePulsePhase]) rather than the red's
 * urgent throb. Cycled at runtime by the `g` key ([spikeWorkingStyle]) so you can flip a
 * working pane between showing this glow, the travelling dots, or both together live.
 * Because the bloom bleeds *outside* the pane it stays spottable across the ring, like
 * the red halo, but its softer green cadence reads as "busy, no action needed" versus
 * the red "come here now".
 *
 *  - [WORKING_GLOW_COLOR] the halo colour as bare `r,g,b` (fed into `rgba(...)`).
 *  - [WORKING_GLOW_MIN]/[WORKING_GLOW_MAX] the halo alpha floor/ceiling; the floor is
 *    non-zero so the green never fully fades out between breaths.
 *  - [WORKING_GLOW_BLUR] px blur radius of the bloom.
 *  - [WORKING_GLOW_SPREAD] px spread radius — pushes the bloom out past the pane edge.
 * @see spikeWorkingStyle @see toggleWorkingStyle
 */
internal const val WORKING_GLOW_COLOR = "34,197,94" // #22c55e — a clear emerald green
internal const val WORKING_GLOW_MIN = 0.22 // floor: the green never fully fades out
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
 *  - [WAITING_PULSE_SPEED] rad/frame the urgency breath advances (~2.5s per pulse).
 *  - [WAITING_GLOW_MIN]/[WAITING_GLOW_MAX] the halo alpha floor/ceiling; the floor is
 *    deliberately non-zero so the red never fully disappears between pulses.
 *  - [WAITING_GLOW_BLUR] px blur radius of the bloom (a big soft spread seen from afar).
 *  - [WAITING_GLOW_SPREAD] px spread radius — pushes the bloom out past the pane edge.
 */
internal const val WAITING_GLOW_COLOR = "239,68,68" // #ef4444
internal const val WAITING_GLOW_BORDER = "#ef4444"
internal const val WAITING_PULSE_SPEED = 0.05 // rad/frame → ~2.5s per urgent pulse
internal const val WAITING_GLOW_MIN = 0.28 // floor: the red never fully fades out
internal const val WAITING_GLOW_MAX = 0.90 // full flare
internal const val WAITING_GLOW_BLUR = 64.0 // px — a wide soft bloom, spottable from across the ring
internal const val WAITING_GLOW_SPREAD = 10.0 // px — pushes the bloom out past the edge
