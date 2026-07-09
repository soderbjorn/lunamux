/*
 * Split from Overview3D.kt — per-open content build: cards, pane tiles, dished geometry, data-tile priming.
 * See Overview3D.kt for the module overview. Shared imports are carried
 * verbatim; unused ones are harmless (warnings, not errors).
 */
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.three.CanvasTexture
import se.soderbjorn.lunamux.three.Mesh
import se.soderbjorn.lunamux.three.MeshBasicMaterial
import se.soderbjorn.lunamux.three.PerspectiveCamera
import se.soderbjorn.lunamux.three.PlaneGeometry
import se.soderbjorn.lunamux.three.Raycaster
import se.soderbjorn.lunamux.three.Scene
import se.soderbjorn.lunamux.three.Vector2
import se.soderbjorn.lunamux.three.WebGLRenderer
import kotlin.js.json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds the style-independent content for every visible tab into [ovCards]:
 * one [OverviewCard] per tab (via [buildCardContent]), tagging each card mesh
 * and pane-tile mesh with the `userData` the raycaster reads for click/hover
 * picking (`index` = card index; `paneId` on tiles). The active style then
 * places these in the scene.
 *
 * @param tabs the visible tabs, in order.
 * @param fg thumbnail foreground color. @param bg background color.
 * @param accent theme accent color.
 */
internal fun buildCards(tabs: List<TabConfig>, fg: String, bg: String, accent: String) {
    tabs.forEachIndexed { i, tab ->
        val card = buildCardContent(tab, fg, bg, accent)
        card.mesh.userData = json("index" to i)
        for (tile in card.tiles) tile.mesh.userData = json("index" to i, "paneId" to tile.paneId)
        ovCards.add(card)
    }
}

/**
 * Resolves the persisted style id (see [experimental3dSwitcherStyle]) to its
 * [Overview3DStyle] implementation, defaulting to the carousel for any
 * unrecognised value.
 *
 * @param id one of [OVERVIEW_3D_STYLE_CAROUSEL] / [OVERVIEW_3D_STYLE_ROTUNDA] /
 *   [OVERVIEW_3D_STYLE_EXPOSE] / [OVERVIEW_3D_STYLE_FLIPSTACK] /
 *   [OVERVIEW_3D_STYLE_CORRIDOR] / [OVERVIEW_3D_STYLE_ORBIT] /
 *   [OVERVIEW_3D_STYLE_VERTIGO].
 * @return the matching style object.
 */
internal fun styleFor(id: String): Overview3DStyle = when (id) {
    OVERVIEW_3D_STYLE_ROTUNDA -> RotundaStyle
    OVERVIEW_3D_STYLE_EXPOSE -> ExposeStyle
    OVERVIEW_3D_STYLE_FLIPSTACK -> FlipStackStyle
    OVERVIEW_3D_STYLE_CORRIDOR -> CorridorStyle
    OVERVIEW_3D_STYLE_ORBIT -> OrbitStyle
    OVERVIEW_3D_STYLE_VERTIGO -> VertigoStyle
    else -> CarouselStyle
}

/**
 * Resolves the fractional layout rectangle of every pane in [tab], in pane
 * order. Primary source is the toolkit's *live* layout state
 * ([AppShellHandle.currentLayoutStateJson] — reflects in-session drags);
 * per-pane fallback is the wire-model [Pane] geometry. A maximized pane
 * reports the full tab area on top, mirroring how the real layout draws it.
 *
 * @param tab the tab whose panes to resolve.
 * @return one [PaneRect] per entry of `tab.panes`, same order.
 */
internal fun resolvePaneRects(tab: TabConfig): List<PaneRect> {
    val tabGeom: dynamic = runCatching {
        val jsonStr = appShellHandle?.currentLayoutStateJson() ?: return@runCatching null
        val parsed: dynamic = JSON.parse(jsonStr)
        val byTab = parsed.geometryByTab
        if (byTab == null) null else byTab[tab.id]
    }.getOrNull()

    return tab.panes.map { pane ->
        val fromToolkit: PaneRect? = runCatching {
            val o = if (tabGeom == null) null else tabGeom[pane.leaf.id]
            if (o == null || o == undefined) return@runCatching null
            if (o.isMaximized == true) {
                PaneRect(0.0, 0.0, 1.0, 1.0, Int.MAX_VALUE)
            } else {
                PaneRect(
                    (o.xPct as Number).toDouble(),
                    (o.yPct as Number).toDouble(),
                    (o.widthPct as Number).toDouble(),
                    (o.heightPct as Number).toDouble(),
                    ((o.zIndex as? Number)?.toInt()) ?: 1,
                )
            }
        }.getOrNull()
        fromToolkit ?: PaneRect(pane.x, pane.y, pane.width, pane.height, pane.z.toInt())
    }
}

/**
 * Returns (creating on first use) the shared content source for a pane,
 * deduplicated per pane id in [ovSources].
 *
 * Prefers the pane's **live** xterm from the [terminals] registry — the same
 * buffer the visible pane renders, read size-neutrally (no socket, no resize),
 * and full-fidelity capable. Mounted tabs (the active tab and any previously
 * visited) have one, including in demo mode ([connectDemoPane]). Tabs never
 * mounted yet have no registry term, so the overview falls back to a hidden
 * **mirror** term fed by a read-only preview socket (or [attachDemoPreview] in
 * demo) — the link picker's proven pattern — so every tab still previews.
 *
 * @param paneId the pane (leaf) id — the dedup key and registry key.
 * @param sessionId the PTY session id (used by the mirror fallback).
 * @return the shared source (live or mirror).
 */
internal fun ensureSource(paneId: String, sessionId: String): ThumbSource {
    ovSources[paneId]?.let { return it }

    val liveTerm = terminals[paneId]?.term
    val source: ThumbSource = if (liveTerm != null) {
        ThumbSource(liveTerm, live = true, hiddenHost = null)
    } else {
        // Unmounted tab: mirror the session into a hidden term (cheap preview),
        // matching the link picker's hidden-host setup exactly.
        val hiddenHost = document.createElement("div") as HTMLElement
        hiddenHost.className = "link-preview-hidden-host"
        document.body?.appendChild(hiddenHost)
        val term = Terminal(json(
            "cursorBlink" to false, "disableStdin" to true,
            "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
            "fontSize" to 9, "cols" to 120, "rows" to 40,
            "scrollback" to 200, "theme" to buildXtermTheme(),
        ))
        term.open(hiddenHost)
        val s = ThumbSource(term, live = false, hiddenHost = hiddenHost)
        if (isDemoClient) {
            attachDemoPreview(term, sessionId)
        } else {
            val url = "$proto://$backendHost/pty/$sessionId?$authQueryParam"
            val socket = WebSocket(url)
            socket.asDynamic().binaryType = "arraybuffer"
            socket.onmessage = { event ->
                val data = event.asDynamic().data
                if (data !is String) term.write(Uint8Array(data as ArrayBuffer))
            }
            s.socket = socket
        }
        s
    }
    source.subscribe()
    source.scheduleRepaint()
    ovSources[paneId] = source
    return source
}

/**
 * Resolves which PTY session a pane mirrors, or `null` for non-terminal
 * panes.
 *
 * @param pane the pane to inspect.
 * @return the session id, or `null` (placeholder thumbnail).
 */
internal fun paneSessionId(pane: Pane): String? {
    val content = pane.leaf.content
    val terminalKind = content == null || content is TerminalContent
    return if (terminalKind && pane.leaf.sessionId.isNotEmpty()) pane.leaf.sessionId else null
}

/**
 * Builds the style-independent **content** for one tab: the full-card
 * thumbnail (the focused / first terminal pane), the glow halo (child of the
 * card mesh), and one [PaneTile] per pane — each with its captured content
 * (terminal grid / file listing / git status), live source, and true world
 * size. It does *not* place anything in the scene: the active [Overview3DStyle]
 * (via [buildCards] → [Overview3DStyle.build]) parents the card / tile meshes
 * and arranges them. Pane tiles are built at their real layout geometry
 * ([resolvePaneRects]); a style that wants a different arrangement repositions
 * the tile meshes.
 *
 * @param tab the tab to build content for.
 * @param fg thumbnail foreground color. @param bg thumbnail background color.
 * @param accent CSS accent color for the glow halo.
 * @return the assembled card content, already painted once.
 */
internal fun buildCardContent(tab: TabConfig, fg: String, bg: String, accent: String): OverviewCard {
    // Full-card view mirrors the focused pane's terminal (or the first
    // terminal pane), titled with the tab name.
    val focusPane = tab.panes.firstOrNull { it.leaf.id == tab.focusedPaneId && paneSessionId(it) != null }
        ?: tab.panes.firstOrNull { paneSessionId(it) != null }
    val cardSource = focusPane?.let { ensureSource(it.leaf.id, paneSessionId(it)!!) }

    val cardCanvas = document.createElement("canvas") as HTMLCanvasElement
    cardCanvas.width = (CARD_CANVAS_W * RES).roundToInt()
    cardCanvas.height = (CARD_CANVAS_H * RES).roundToInt()
    val cardTexture = CanvasTexture(cardCanvas)
    cardTexture.colorSpace = "srgb"
    val cardView = ThumbView(
        cardCanvas, cardTexture, tab.title, TITLE_STRIP_PX, 12, fg, bg, RES, accent,
        // No "no terminal" placeholder on the card: a tab with only
        // file-browser/git panes would flash that text during the split
        // crossfade before its tiles fade in. A plain dark panel morphs cleanly
        // into the tiles.
        placeholder = null,
        // Round the card's own corners so, while it sits behind the split tiles,
        // its opaque square corners never peek out past the tiles' rounded ones.
        roundCorners = true,
    )

    // FrontSide (side 0): cards on the far half of the ring face away and are
    // back-face-culled — otherwise their DoubleSide backface showed through as
    // a dark, *mirrored* filled rectangle that flashed during tab switches.
    // transparent=true because the card fades out as it splits into panes;
    // depthWrite=false so the (invisible, faded) card never depth-occludes the
    // pane tiles that lift in front of it during the split.
    val cardMaterial = MeshBasicMaterial(json(
        "map" to cardTexture, "side" to 0, "transparent" to true,
        "depthWrite" to false,
    ))
    val mesh = Mesh(ovCardGeometry!!, cardMaterial)

    val glowMaterial = MeshBasicMaterial(json(
        "color" to accent, "transparent" to true, "opacity" to 0.0,
        "depthWrite" to false, "side" to 0,
    ))
    val glow = Mesh(ovGlowGeometry!!, glowMaterial)
    glow.position.set(0.0, 0.0, -0.02)
    mesh.add(glow)

    // Pane tiles: one per pane, laid out per the real geometry. Stacking
    // order ranks drive a small z stagger so overlapping panes keep their
    // relative depth when split.
    val rects = resolvePaneRects(tab)
    val zRank = rects.withIndex().sortedBy { it.value.z }.withIndex()
        .associate { (rank, e) -> e.index to rank }
    val tiles = tab.panes.mapIndexed { pi, pane ->
        val rect = rects[pi]
        val rank = zRank[pi] ?: 0
        buildPaneTile(pane, rect, rank, fg, bg, accent)
    }
    // Tiles are left unparented here; the active style parents them (the
    // carousel adds them to the card mesh, the rotunda/exposé to their own
    // scene groups).

    val card = OverviewCard(tab.id, tab.title, mesh, cardMaterial, glowMaterial, cardView, cardSource, tiles, tab.focusedPaneId)
    if (cardSource != null) cardSource.views.add(cardView)
    cardView.paint(null)
    cardSource?.scheduleRepaint()
    return card
}

/**
 * Builds one pane tile: proportional canvas + texture + plane, attached to
 * the shared session source (or painted once as a placeholder), with its
 * assembled and split placements precomputed from [rect].
 *
 * @param pane the pane the tile represents.
 * @param rect the pane's fractional rectangle within the tab.
 * @param zRank the pane's rank in the tab's stacking order (0 = bottom).
 * @param fg thumbnail foreground color. @param bg thumbnail background color.
 * @param accent theme accent color for the tile's title-bar underline.
 * @return the assembled tile (initially invisible; the split animation
 *   reveals it).
 */
internal fun buildPaneTile(
    pane: Pane,
    rect: PaneRect,
    zRank: Int,
    fg: String,
    bg: String,
    accent: String,
): PaneTile {
    val sessionId = paneSessionId(pane)
    val source = sessionId?.let { ensureSource(pane.leaf.id, it) }

    // Physical backing store is the pane's fraction of the (supersampled)
    // card canvas — so a tile is as crisp as the full card would be there.
    val wPx = max(96, (rect.w * CARD_CANVAS_W * RES).roundToInt())
    val hPx = max(64, (rect.h * CARD_CANVAS_H * RES).roundToInt())
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = wPx
    canvas.height = hPx
    val texture = CanvasTexture(canvas)
    texture.colorSpace = "srgb"

    val content = pane.leaf.content
    val kind = when {
        source != null -> TileKind.TERMINAL
        content is FileBrowserContent -> TileKind.FILE_BROWSER
        content is GitContent -> TileKind.GIT
        else -> TileKind.OTHER
    }
    val placeholder = when (kind) {
        TileKind.TERMINAL -> null
        TileKind.FILE_BROWSER -> "file browser"
        TileKind.GIT -> "git"
        TileKind.OTHER -> "no terminal"
    }
    // File-browser / git thumbnails read top-down (a listing), unlike the
    // bottom-anchored terminal tail.
    val topAnchored = kind == TileKind.FILE_BROWSER || kind == TileKind.GIT
    // Only tall-enough tiles get a title bar (a bar on a sliver of a tile
    // just eats the whole thumbnail). Threshold is in physical px.
    val stripPx = if (hPx >= 80 * RES) TILE_STRIP_PX else 0
    val view = ThumbView(
        canvas, texture, pane.leaf.title, stripPx, 9, fg, bg, RES, accent, topAnchored, placeholder,
        paneBorder = true,
    )

    val tileW = max(0.1, rect.w * CARD_W - TILE_GAP)
    val tileH = max(0.1, rect.h * CARD_H - TILE_GAP)
    // Subdivided + dished so the pane reads as a genuinely curved surface (its
    // outer edges bow away from the camera), not just a flat tilted quad.
    val geometry = PlaneGeometry(tileW, tileH, DISH_SEGMENTS, DISH_SEGMENTS)
    dishGeometry(geometry, tileW, tileH, DISH_DEPTH)
    // FrontSide (side 0) + depthWrite so the curved sheet never z-fights its
    // own backface (which punched a lens-shaped "hole" through the pane under
    // DoubleSide) and overlapping tiles occlude correctly. Tiles are only ever
    // viewed from the front (their card is at the ring front when split), so
    // the backface is never needed.
    val material = MeshBasicMaterial(json(
        "map" to texture, "side" to 0, "transparent" to true,
        "opacity" to 0.0, "depthWrite" to true,
    ))
    val mesh = Mesh(geometry, material)
    mesh.visible = false

    // Card-local placements: assembled = flush with the card at the pane's
    // layout position; split = spread outward from the card center + lifted
    // toward the viewer, staggered by stacking rank.
    val homeX = (rect.x + rect.w / 2.0 - 0.5) * CARD_W
    val homeY = (0.5 - rect.y - rect.h / 2.0) * CARD_H
    val homeZ = 0.02 + zRank * 0.006
    val splitZ = SPLIT_LIFT + zRank * 0.05
    mesh.position.set(homeX, homeY, homeZ)

    val tile = PaneTile(
        pane.leaf.id, pane.leaf.title, kind, mesh, material, geometry, view, source,
        homeX, homeY, homeZ,
        homeX * SPLIT_SPREAD, homeY * SPLIT_SPREAD, splitZ,
        tileW, tileH,
    )
    if (source != null) source.views.add(view) else view.paint(null)
    return tile
}

/**
 * Bends a subdivided [PlaneGeometry] into a shallow dish so a pane tile reads as
 * a curved surface: the center bulges toward the camera and the outer
 * edges/corners bow away, in proportion to squared distance from the center.
 *
 * The displacement is kept **entirely non-negative** (center at `+depth`,
 * corners at `0`) so every vertex sits *in front of* the flat card plane behind
 * it — otherwise a receding corner would dip behind the (faded) card and get
 * depth-clipped into a visible hole. Recomputes normals so the curvature is
 * well-formed (harmless for the unlit material).
 *
 * @param geometry the subdivided plane geometry (mutated in place).
 * @param w the pane width in world units. @param h the pane height.
 * @param depth how far (world units) the center bulges forward of the corners.
 */
internal fun dishGeometry(geometry: PlaneGeometry, w: Double, h: Double, depth: Double) {
    runCatching {
        val pos = geometry.asDynamic().attributes.position
        val count = (pos.count as Number).toInt()
        val hx = w / 2.0
        val hy = h / 2.0
        for (i in 0 until count) {
            val x = (pos.getX(i) as Number).toDouble()
            val y = (pos.getY(i) as Number).toDouble()
            val nx = if (hx > 0) x / hx else 0.0
            val ny = if (hy > 0) y / hy else 0.0
            // Normalized squared radius (0 at center, up to 2 at the corners),
            // mapped so center = +depth (forward) and corners = 0.
            pos.setZ(i, depth * (1.0 - (nx * nx + ny * ny) / 2.0))
        }
        pos.needsUpdate = true
        geometry.asDynamic().computeVertexNormals()
    }
}

/**
 * Builds the thumbnail text lines for a file-browser tile from the cached
 * root directory listing ([fileBrowserPaneStates]`[paneId].dirListings[""]`),
 * directories first then files, each row a name with a leading marker.
 *
 * @param paneId the file-browser pane's id.
 * @return the lines to paint, or `null` when no listing has been cached yet
 *   (the tile keeps its placeholder until the fetch replies).
 */
internal fun fileBrowserThumbLines(paneId: String): List<String>? {
    val listing = fileBrowserPaneStates[paneId]?.dirListings?.get("") ?: return null
    val entries = listing.unsafeCast<Array<dynamic>>()
    val dirs = ArrayList<String>()
    val files = ArrayList<String>()
    for (e in entries) {
        val name = e.name as? String ?: continue
        if (e.isDir as? Boolean == true) dirs.add("> $name") else files.add("  $name")
    }
    val lines = dirs + files
    return if (lines.isEmpty()) listOf("(empty)") else lines
}

/**
 * Builds the thumbnail text lines for a git tile from the cached changed-file
 * list ([gitPaneStates]`[paneId].entries`), one `X  path` row per file where
 * `X` is the status letter (A/M/D/R/?), matching the full git list.
 *
 * @param paneId the git pane's id.
 * @return the lines to paint, or `null` when no list has been cached yet.
 */
internal fun gitThumbLines(paneId: String): List<String>? {
    val entries = gitPaneStates[paneId]?.entries ?: return null
    val arr = entries.unsafeCast<Array<dynamic>>()
    if (arr.size == 0) return listOf("(no changes)")
    val out = ArrayList<String>()
    for (e in arr) {
        val path = e.filePath as? String ?: continue
        val c = when (e.status as? String) {
            "Added" -> "A"; "Modified" -> "M"; "Deleted" -> "D"
            "Renamed" -> "R"; "Untracked" -> "?"; else -> "·"
        }
        out.add("$c  $path")
    }
    return out
}

/**
 * Repaints a file-browser / git tile from its currently-cached data (or its
 * placeholder when nothing is cached yet). Called on open for any already-
 * cached data and again from [onPaneContentUpdated] as fetches reply.
 *
 * @param tile the non-terminal tile to repaint.
 */
internal fun repaintDataTile(tile: PaneTile) {
    val lines = when (tile.contentKind) {
        TileKind.FILE_BROWSER -> fileBrowserThumbLines(tile.paneId)
        TileKind.GIT -> gitThumbLines(tile.paneId)
        else -> return
    }
    tile.view.paint(lines)
}

/**
 * Kicks off thumbnail data for every file-browser / git tile in the freshly
 * built ring: installs the [onPaneContentUpdated] listener that repaints a
 * tile when its data arrives, paints any already-cached data immediately,
 * and issues a fetch ([WindowCommand.FileBrowserListDir] / [WindowCommand.GitList])
 * for tiles with no cached data yet. Terminal tiles are untouched (they
 * stream over their own PTY sockets).
 *
 * Called at the end of [openOverview3d]; the listener is cleared in
 * [closeOverview3d].
 */
internal fun primeDataTiles() {
    onPaneContentUpdated = { paneId ->
        for (card in ovCards) for (tile in card.tiles) {
            if (tile.paneId == paneId &&
                (tile.contentKind == TileKind.FILE_BROWSER || tile.contentKind == TileKind.GIT)
            ) {
                repaintDataTile(tile)
            }
        }
    }
    for (card in ovCards) for (tile in card.tiles) {
        when (tile.contentKind) {
            TileKind.FILE_BROWSER -> {
                repaintDataTile(tile)
                if (fileBrowserPaneStates[tile.paneId]?.dirListings?.get("") == null) {
                    launchCmd(WindowCommand.FileBrowserListDir(paneId = tile.paneId, dirRelPath = ""))
                }
            }
            TileKind.GIT -> {
                repaintDataTile(tile)
                if (gitPaneStates[tile.paneId]?.entries == null) {
                    launchCmd(WindowCommand.GitList(paneId = tile.paneId))
                }
            }
            else -> {}
        }
    }
}
