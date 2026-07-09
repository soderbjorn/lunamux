/**
 * Exposé-zoom style for the 3D tab overview — the "mission control" [Overview3DStyle].
 *
 * The design goal is *continuity with the real workspace*: the current tab's
 * panes start at their **actual** on-screen layout positions (read from each
 * [PaneTile.homeX]/[PaneTile.homeY], which the core computed from the pane's
 * fractional layout rectangle) and expand into a clean, tilted grid as the
 * camera pulls back. The other tabs cluster behind the grid and are revealed as
 * you zoom out. A single eased zoom parameter `t ∈ [0,1]` drives everything:
 * `t = 0` frames the working layout tight (only the current tab, at true layout
 * positions), `t = 1` is the pulled-back overview grid with the other tabs'
 * clusters visible.
 *
 * Motion (mirrors the CSS-3D reference, done as the three.js equivalent):
 *  - each current-tab tile lerps between its real-layout placement (t=0) and its
 *    grid slot (t=1); the whole group tilts (`rotation.x`) and the camera pushes
 *    back (`position.z`) as t grows;
 *  - the other tabs' clusters fade + slide in behind the grid as t→1;
 *  - the wheel/trackpad is a continuous zoom axis (the signature interaction);
 *  - while a dive is in progress the zoom snaps back toward the real layout so a
 *    picked pane "lands" in its working-layout position.
 *
 * Aspect ratio is preserved by **letterboxing**: a tile is never stretched to
 * fill its grid cell — it is scaled *uniformly* by `min(cellW/worldW,
 * cellH/worldH)` so its true [PaneTile.worldW]:[PaneTile.worldH] ratio is kept.
 * The layout/grid rectangles only ever decide a tile's *centre* and how much
 * room it has.
 *
 * As with the other styles, all content capture (terminal grids / file / git
 * thumbnails), selection state, and the open/close/render lifecycle live in the
 * shared core ([Overview3D.kt]); this file owns only the exposé's *spatial*
 * behaviour and gesture handling. The core builds [ovCards] and seeds
 * [ovSelected] / [ovPaneSelected]; this style parents the tile meshes into its
 * own group and animates them.
 *
 * @see Overview3DStyle
 * @see CarouselStyle
 * @see RotundaStyle
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.three.Group
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.Scene
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** How fast the eased zoom chases its target each frame (0..1 lerp factor). */
private const val ZOOM_EASE = 0.09

/** Wheel/trackpad delta → zoom-target gain (per notch). Continuous zoom axis. */
private const val ZOOM_WHEEL_GAIN = 0.0016

/**
 * Per-frame lerp factor by which each tile glides toward its computed target
 * position / scale / opacity. This is what makes exposé transitions *fluid*:
 * changing tab or moving the selection re-targets the tiles, and they slide to
 * their new spots instead of snapping — a pane that was a background cluster
 * eases forward into the grid, the outgoing tab's panes ease back, etc.
 */
private const val TILE_EASE = 0.2

/** Grid tilt at full zoom-out (radians ≈ 13°); scaled by the eased zoom. */
private const val TILT = 0.22

/** Camera Z at t=0 (tight): the current tab's real layout fills the frame. */
private const val CAM_Z0 = 3.3

/** Camera Z at t=1 (pulled back): grid + other-tab clusters all in view. */
private const val CAM_Z1 = 7.6

/** Camera height at full zoom-out (raised for the 3/4 mission-control view). */
private const val CAM_Y = 0.45

/** World width of the exposé grid region the current tab's cells tile. */
private const val GRID_W = 4.6

/** World height of the exposé grid region. */
private const val GRID_H = 2.9

/** Fraction of a grid cell a letterboxed tile is allowed to fill (padding). */
private const val CELL_FILL = 0.9

/** Z the current-tab tiles flatten onto at full zoom-out (flat grid). */
private const val GRID_Z = 0.0

/** Horizontal spacing (world units) between adjacent other-tab clusters. */
private const val CLUSTER_SPACING = 2.2

/** Cluster vertical offset at t=0 (irrelevant — clusters are hidden there). */
private const val CLUSTER_Y0 = 0.5

/** Cluster vertical offset at t=1 (settles just below the grid centre). */
private const val CLUSTER_Y1 = -0.15

/** Z the other-tab clusters sit at (behind the grid, further from camera). */
private const val CLUSTER_Z = -2.6

/** Uniform shrink applied to a whole cluster (a mini version of the grid). */
private const val CLUSTER_SCALE = 0.42

/** Peak opacity of the revealed clusters (never fully solid — they read as behind). */
private const val CLUSTER_MAX_OPACITY = 0.85

/** Scale bump applied to the selected pane tile (uniform — aspect preserved). */
private const val SELECT_BUMP = 1.06

/**
 * The exposé-zoom [Overview3DStyle]. A stateless singleton apart from the
 * per-open scene state it resets in [build]: its tile group, the eased zoom
 * value + target, and a drag-parallax yaw.
 */
internal object ExposeStyle : Overview3DStyle {

    /** Per-open group every tile mesh is parented into; tilted as one unit. */
    private var group: Group? = null

    /** Current eased zoom (0 = tight working-layout, 1 = pulled-back grid). */
    private var zoom = 0.0

    /** Zoom target the eased [zoom] chases (driven by wheel + dive). */
    private var zoomTarget = 1.0

    /** Drag-driven parallax yaw (radians); eases back to 0 when released. */
    private var parallaxYaw = 0.0

    /**
     * Builds the exposé scene graph: creates the tile group, parents every
     * card's pane tiles into it (hidden), and seeds the zoom at `t=0` so the
     * reveal — the current tab expanding from its real layout out into the grid
     * — plays on open (zoom eases toward its `t=1` target in [tick]). Resets the
     * per-open gesture state. Called by the core right after it builds [ovCards]
     * and seeds the selection.
     *
     * @param scene the cached scene to add the group to.
     * @param camera the cached camera (posed in [resetCamera]).
     */
    override fun build(scene: Scene, camera: PerspectiveCamera) {
        val g = Group()
        group = g
        scene.add(g)
        for (card in ovCards) for (tile in card.tiles) {
            tile.mesh.visible = false
            tile.material.opacity = 0.0
            g.add(tile.mesh)
        }
        zoom = 0.0
        zoomTarget = 1.0
        parallaxYaw = 0.0
    }

    /**
     * Poses the camera for the opening (tight, `t=0`) frame — head-on and close
     * so the current tab's working layout fills the viewport; [tick] then drives
     * the pull-back as the zoom eases toward its target. Called once by the core
     * after [build].
     *
     * @param camera the cached camera.
     */
    override fun resetCamera(camera: PerspectiveCamera) {
        camera.position.set(0.0, 0.0, CAM_Z0)
        camera.lookAt(0.0, 0.0, 0.0)
    }

    /**
     * Per-frame update: eases the zoom toward its target (snapping it back to
     * the layout while a dive lands), tilts the group + pushes the camera back
     * in proportion to the eased zoom, drives hover-based pane selection, and
     * lays out every card — the current tab as the real-layout→grid lerp, the
     * others as clusters revealed behind. Called by the core each animation
     * frame before it renders.
     *
     * @param now `window.performance.now()` for this frame (unused; the zoom is
     *   frame-rate-eased rather than time-based, matching the reference).
     * @param camera the cached camera.
     */
    override fun tick(now: Double, camera: PerspectiveCamera) {
        // While a pick is landing, pull the zoom back toward the working layout
        // so the chosen pane "lands" in its real on-screen position.
        if (!ovDiveStart.isNaN()) zoomTarget = 0.0
        zoom += (zoomTarget - zoom) * ZOOM_EASE
        val e = ease(zoom.coerceIn(0.0, 1.0))

        // Tilt the whole group as it opens out; drag adds a gentle parallax yaw
        // that eases back to centre when the pointer is released.
        group?.let {
            it.rotation.x = -TILT * e
            it.rotation.y = parallaxYaw
        }
        parallaxYaw *= 0.9

        // Hover pick (skipped mid-dive): hovering a current-tab tile drives the
        // same pane highlight the keyboard uses, so mouse and arrows agree.
        val hoverUd = if (ovDiveStart.isNaN()) raycastPointer(pickables()) else null
        if (ovPointerMoved && hoverUd != null && (hoverUd.index as? Int) == ovSelected) {
            val hpid = hoverUd.paneId as? String
            if (hpid != null) {
                val ti = ovCards[ovSelected].tiles.indexOfFirst { it.paneId == hpid }
                if (ti >= 0) setPaneSelection(ti)
            }
        }
        ovPointerMoved = false

        // Lay out each card: the selected one expands into the grid, the rest
        // cluster behind (their slot = position among the non-selected cards).
        val others = ovCards.indices.filter { it != ovSelected }
        ovCards.forEachIndexed { ci, card ->
            if (ci == ovSelected) {
                layoutCurrent(card, e)
            } else {
                layoutCluster(card, others.indexOf(ci), others.size, e)
            }
        }

        // Camera: push back and rise as the grid opens out.
        camera.position.set(0.0, CAM_Y * e, CAM_Z0 + (CAM_Z1 - CAM_Z0) * e)
        camera.lookAt(0.0, 0.0, 0.0)
    }

    /**
     * Positions the current tab's tiles between their real-layout placement
     * (`e=0`) and their exposé-grid slots (`e=1`). Each tile is scaled
     * *uniformly* (letterboxed) into its cell so its true aspect is preserved;
     * the selected pane gets a small uniform bump. Called from [tick] for the
     * front card.
     *
     * @param card the selected card.
     * @param e the eased zoom (0 = layout, 1 = grid).
     */
    private fun layoutCurrent(card: OverviewCard, e: Double) {
        val n = card.tiles.size
        card.tiles.forEachIndexed { i, tile ->
            val cell = cellOf(i, n)
            // Letterbox: uniform scale to fit the cell, never stretch.
            val gScale = min(cell[2] * CELL_FILL / tile.worldW, cell[3] * CELL_FILL / tile.worldH)
            val px = tile.homeX + (cell[0] - tile.homeX) * e
            val py = tile.homeY + (cell[1] - tile.homeY) * e
            val pz = tile.homeZ + (GRID_Z - tile.homeZ) * e
            // Scale eases from 1 (tile at its native layout size) to gScale.
            val sc = (1.0 + (gScale - 1.0) * e) * (if (i == ovPaneSelected) SELECT_BUMP else 1.0)
            applyTile(tile, px, py, pz, sc, 1.0)
        }
    }

    /**
     * Positions one background tab's tiles as a shrunken cluster behind the grid,
     * fading + sliding in as the zoom opens out (hidden when tight). The cluster
     * is a mini exposé grid centred at its horizontal slot; every tile is still
     * letterboxed (uniform scale) so aspect is preserved. Called from [tick] for
     * every non-selected card.
     *
     * @param card the background card.
     * @param slot the card's index among the non-selected cards (0-based).
     * @param count how many non-selected cards there are (to centre the row).
     * @param e the eased zoom (clusters appear only as e→1).
     */
    private fun layoutCluster(card: OverviewCard, slot: Int, count: Int, e: Double) {
        val n = card.tiles.size
        val clusterX = (slot - (count - 1) / 2.0) * CLUSTER_SPACING
        val clusterY = CLUSTER_Y0 + (CLUSTER_Y1 - CLUSTER_Y0) * e
        val op = e * CLUSTER_MAX_OPACITY
        card.tiles.forEachIndexed { j, tile ->
            val cell = cellOf(j, n)
            val gScale = min(cell[2] * CELL_FILL / tile.worldW, cell[3] * CELL_FILL / tile.worldH)
            applyTile(
                tile,
                clusterX + cell[0] * CLUSTER_SCALE,
                clusterY + cell[1] * CLUSTER_SCALE,
                CLUSTER_Z,
                gScale * CLUSTER_SCALE,
                op,
            )
        }
    }

    /**
     * Eases one tile toward a target transform, the core of the exposé's fluid
     * motion. Each frame the layout computes where a tile *should* be (grid slot
     * or background cluster) and this glides the mesh's actual position, uniform
     * scale, and material opacity toward it by [TILE_EASE] — so when a tile's role
     * changes (tab switch, selection move) it slides rather than snapping. The
     * tile stays visible until it has faded essentially to nothing.
     *
     * @param tile the pane tile to move.
     * @param tx/ty/tz target position (world units).
     * @param tScale target uniform scale (letterboxed — aspect preserved).
     * @param tOpacity target material opacity (0 = fully faded / hidden).
     */
    private fun applyTile(tile: PaneTile, tx: Double, ty: Double, tz: Double, tScale: Double, tOpacity: Double) {
        val m = tile.mesh
        val cx = m.position.x as Double
        val cy = m.position.y as Double
        val cz = m.position.z as Double
        m.position.set(cx + (tx - cx) * TILE_EASE, cy + (ty - cy) * TILE_EASE, cz + (tz - cz) * TILE_EASE)
        val cs = m.scale.x as Double
        val ns = cs + (tScale - cs) * TILE_EASE
        m.scale.set(ns, ns, 1.0)
        val no = tile.material.opacity + (tOpacity - tile.material.opacity) * TILE_EASE
        tile.material.opacity = no
        m.rotation.set(0.0, 0.0, 0.0)
        m.visible = no > 0.012
    }

    /**
     * Computes tile [i]'s grid cell for a tab of [n] tiles: `[centreX, centreY,
     * cellW, cellH]` in world units, laid out row-major in a near-square grid
     * ([gridCols] columns) centred on the origin. The caller derives the tile's
     * letterbox scale from the cell size and its true world size.
     *
     * @param i the tile index. @param n the tab's tile count.
     * @return `doubleArrayOf(centreX, centreY, cellW, cellH)`.
     */
    private fun cellOf(i: Int, n: Int): DoubleArray {
        val cols = gridCols(n)
        val rows = ceil(n.toDouble() / cols).toInt().coerceAtLeast(1)
        val col = i % cols
        val row = i / cols
        val cellW = GRID_W / cols
        val cellH = GRID_H / rows
        val cx = (col - (cols - 1) / 2.0) * cellW
        val cy = ((rows - 1) / 2.0 - row) * cellH
        return doubleArrayOf(cx, cy, cellW, cellH)
    }

    /** Column count for a near-square grid of [n] tiles (⌈√n⌉, at least 1). */
    private fun gridCols(n: Int): Int = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)

    /**
     * Keyboard navigation matched to the exposé's spatial layout. The current
     * tab's panes sit side by side in the grid, so **←/→ move the highlight
     * between panes** (wrapping through all of them, so every pane is reachable in
     * a small grid or a single row alike). The other tabs live behind as clusters
     * — the "next layer back" — so **↑/↓ (and Tab / Shift+Tab) switch tabs**,
     * gliding the chosen tab's grid to the front. This is why `↑`/`↓` always do
     * something even when the grid is one row. The wheel zooms; a background
     * cluster can also be clicked to jump to it; Enter/Escape are core-handled.
     *
     * @param dir the directional intent from the core key handler.
     */
    override fun nav(dir: OvNav) {
        when (dir) {
            OvNav.LEFT -> movePane(-1)
            OvNav.RIGHT -> movePane(1)
            OvNav.UP, OvNav.PANE_PREV -> stepSelection(-1)
            OvNav.DOWN, OvNav.PANE_NEXT -> stepSelection(1)
        }
    }

    /**
     * Moves the pane highlight by [delta] within the current tab, wrapping
     * through the panes (no "whole tab" slot — in a grid every step lands on a
     * concrete pane). Seeds from pane 0 if nothing is highlighted yet.
     *
     * @param delta -1 = previous pane (←), +1 = next pane (→).
     */
    private fun movePane(delta: Int) {
        val card = ovCards.getOrNull(ovSelected) ?: return
        val n = card.tiles.size
        if (n == 0) return
        val cur = if (ovPaneSelected in 0 until n) ovPaneSelected else 0
        setPaneSelection(((cur + delta) % n + n) % n)
    }

    /**
     * Wheel/trackpad — the signature interaction: integrates the delta into the
     * continuous zoom target (clamped 0..1). Scrolling one way pulls back to the
     * overview grid, the other way dives toward the working layout. Tab switching
     * is on Tab / Shift+Tab (and clicking a cluster), so the wheel is *only* zoom
     * — no surprise tab jump when you over-scroll.
     *
     * @param delta signed dominant-axis wheel delta.
     * @param nowMs `window.performance.now()` (unused; the zoom is eased in [tick]).
     */
    override fun wheel(delta: Double, nowMs: Double) {
        zoomTarget = (zoomTarget + delta * ZOOM_WHEEL_GAIN).coerceIn(0.0, 1.0)
    }

    /**
     * Pointer drag — a gentle horizontal parallax: the accumulated drag yaws the
     * group slightly (eased back to centre in [tick] when released), so a drag is
     * expressive but harmless (zoom, not drag, is the primary mouse gesture).
     *
     * @param dx horizontal pointer movement (px) since the last event.
     * @param dy vertical pointer movement (px) since the last event (unused).
     */
    override fun drag(dx: Double, dy: Double) {
        parallaxYaw = (parallaxYaw + dx * 0.0015).coerceIn(-0.28, 0.28)
    }

    /**
     * The pickable meshes for click/hover: the current tab's tiles always, plus
     * the background tabs' cluster tiles once the overview has zoomed out enough
     * for them to be visible (so a cluster can be clicked to bring its tab front).
     *
     * @return the pickable tile meshes for the current frame.
     */
    override fun pickables(): Array<dynamic> {
        val meshes = ArrayList<dynamic>()
        ovCards.getOrNull(ovSelected)?.let { for (tile in it.tiles) meshes.add(tile.mesh.asDynamic()) }
        if (zoom > 0.3) {
            for (ci in ovCards.indices) if (ci != ovSelected) {
                for (tile in ovCards[ci].tiles) meshes.add(tile.mesh.asDynamic())
            }
        }
        return meshes.toTypedArray()
    }

    /**
     * Click handling: a current-tab pane tile dives into that pane
     * ([beginDive]); a background-tab cluster tile brings that tab to the front
     * ([selectCard]); empty space closes the overview ([closeOverview3d]).
     *
     * @param userData the nearest raycast hit's payload (`index` = card, `paneId`
     *   on tiles), or `null` for empty space.
     */
    override fun click(userData: dynamic) {
        if (userData == null) { closeOverview3d(); return }
        val idx = (userData.index as? Int) ?: return
        if (idx == ovSelected) beginDive(userData.paneId as? String) else selectCard(idx)
    }

    /** Removes the tile group from the scene (tiles/cards are disposed by the core). */
    override fun teardown(scene: Scene) {
        group?.let { scene.remove(it) }
        group = null
    }

    /**
     * Footer hint for the exposé's controls: the wheel is the signature zoom
     * axis, the arrows move around the current tab's pane grid, Tab switches
     * tabs, and a background-tab cluster can be clicked to jump straight to it.
     */
    override fun hint(): String =
        "← → panes · ↑ ↓ tabs · scroll to zoom · ⏎ open · click a pane · esc close"
}
