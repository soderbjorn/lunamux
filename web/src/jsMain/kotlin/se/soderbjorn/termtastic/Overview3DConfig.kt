/*
 * Split from Overview3D.kt — tuning constants (card/canvas sizes, fidelity, split, dish).
 * See Overview3D.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import se.soderbjorn.termtastic.three.CanvasTexture
import se.soderbjorn.termtastic.three.Mesh
import se.soderbjorn.termtastic.three.MeshBasicMaterial
import se.soderbjorn.termtastic.three.PerspectiveCamera
import se.soderbjorn.termtastic.three.PlaneGeometry
import se.soderbjorn.termtastic.three.Raycaster
import se.soderbjorn.termtastic.three.Scene
import se.soderbjorn.termtastic.three.Vector2
import se.soderbjorn.termtastic.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** DOM id of the full-screen overlay element (also its CSS hook). */
internal const val OVERLAY_ID = "overview3d-overlay"

/**
 * Reference card plane size in world units (3.2 : 2 matches the canvas aspect).
 * The tab-card thumbnail and the pane-tile world sizes ([buildPaneTile]) are
 * derived from this; styles read [PaneTile.worldW]/[PaneTile.worldH] rather than
 * these constants directly.
 */
internal const val CARD_W = 3.2
internal const val CARD_H = 2.0

/**
 * Supersampling factor for thumbnail textures. Canvas backing stores are
 * sized this many times their logical dimensions and painted with a matching
 * [renderThumbnail] `scale`, so text density is unchanged but each glyph gets
 * this many times the pixels — crisp instead of jagged when a card fills the
 * viewport on a high-DPI display. Trades GPU texture memory for sharpness.
 */
internal const val RES = 3.0

/** Tab-card canvas *logical* size (px); physical backing store is ×[RES]. */
internal const val CARD_CANVAS_W = 512
internal const val CARD_CANVAS_H = 320

/** Logical height (px, ×[RES] physical) of a card's tab-title bar. */
internal const val TITLE_STRIP_PX = 26

/** Logical height (px, ×[RES] physical) of a tile's pane-title bar. */
internal const val TILE_STRIP_PX = 18

/**
 * Minimum device-px glyph size ([RES]-supersampled) below which a tile falls back
 * from the exact colored terminal grid ([renderTerminalGrid]) to the re-wrapped
 * monochrome thumbnail ([renderThumbnail]). Kept deliberately low: the 3D styles
 * (rotunda/exposé especially) show panes up close, where the accurate, correctly
 * sized, *colored* cell grid is the whole point — so we render it for panes of
 * essentially any real size and only demote a degenerately tiny tile (a wide/tall
 * terminal crammed into a sliver) where a micro-grid would be pure noise. Read in
 * [ThumbSource.repaint].
 */
internal const val MIN_GRID_FONT_PX = 4.0

/** Monospace font stack for thumbnail title bars / placeholders. */
internal const val THUMB_FONT = "Menlo, Monaco, 'Courier New', monospace"

/**
 * Duration of the dive-in activation transition (ms; matches the CSS fade).
 * The core closes the overview when this elapses after [beginDive]; styles may
 * animate a landing camera over the same window.
 */
internal const val DIVE_MS = 340.0

/**
 * The camera's default vertical field of view (degrees), matching the
 * [PerspectiveCamera] constructed in [ensureRenderer]. Reset before every open
 * (see [openOverview3d]) so a style that animates `fov` (the vertigo dolly-zoom,
 * the orbit warp) can't leak a warped lens into the next style's open.
 */
internal const val OVERVIEW_BASE_FOV = 50.0

/** World-units gap shaved off each pane tile so split panes read as tiles. */
internal const val TILE_GAP = 0.05

/** How far apart (scale factor) tiles spread when the card is split. */
internal const val SPLIT_SPREAD = 1.28

/** How far tiles lift toward the camera when split (world units). */
internal const val SPLIT_LIFT = 0.16

/** Plane subdivisions per axis for the dished pane geometry ([dishGeometry]). */
internal const val DISH_SEGMENTS = 14

/** Depth (world units) the pane corners recede in the dish curvature. */
internal const val DISH_DEPTH = 0.34
