/*
 * Split from World3DSpike.kt — building/reconciling/disposing ring planes and empty-tab cards.
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
 * The pane-chrome colours (CSS strings) for the ring, pulled from the same
 * [ResolvedTheme] tokens 2D mode paints its pane/title chrome with, so the two
 * modes look like the same app.
 *
 * @property titlebarBg the title-strip fill ([ResolvedTheme.titlebar]).
 * @property titleText the primary title text ([ResolvedTheme.titleText]).
 * @property titleDim the secondary (tab name) text ([ResolvedTheme.textDim]).
 * @property surface the pane body fill behind the terminal ([ResolvedTheme.surface]).
 * @property border the resting pane border ([ResolvedTheme.border]).
 * @property accent the theme accent (title underline / ready-to-engage border).
 */
internal class SpikeChrome(
    val titlebarBg: String,
    val titleText: String,
    val titleDim: String,
    val surface: String,
    val border: String,
    val accent: String,
)

/**
 * Resolves the current [ResolvedTheme] into [SpikeChrome] CSS strings, falling
 * back to the old neutral slate if no theme is available.
 *
 * @return the pane-chrome colours for this open.
 */
internal fun spikeChrome(): SpikeChrome {
    val t = runCatching { currentResolvedTheme() }.getOrNull()
        ?: return SpikeChrome("#10161f", "#cdd9ec", "#7d90ad", "#0b0f14", "#26374e", "#4f8cf7")
    return SpikeChrome(
        titlebarBg = argbToCss(t.titlebar),
        titleText = argbToCss(t.titleText),
        titleDim = argbToCss(t.textDim),
        surface = argbToCss(t.surface),
        border = argbToCss(t.border),
        accent = argbToCss(t.accent),
    )
}

/**
 * Re-resolves [spikeChrome] and re-tints the **open** world in place, so a
 * theme change while the 3D overlay is up (the demo tour's Workbench swap,
 * a Theme Manager pick, an appearance flip) doesn't leave the ring wearing
 * the colours captured at open time. No-op when the world is closed.
 *
 * Called from `refreshAndApplyActiveTheme` (main.kt) right after the 2D
 * repaint. Three kinds of colour live in the world:
 *  - **per-frame** — pane edge/glow colours the render loop re-reads from
 *    [spikeChromeColors] every frame; updating the global is enough;
 *  - **static pane chrome** — wrapper surface and title-bar colours baked
 *    into inline styles by [buildRingPane]/[buildEmptyTabCard]; restyled
 *    here element by element (bar = wrapper child 0, tab span = bar child 1);
 *  - **beacon glyphs** — the accent-stroked SVGs of the home/stash beacons
 *    ([buildHomeBeacon]/[buildStashBeacon]), found via their
 *    `spike-beacon-glyph` class and re-stroked/re-bloomed in place (their
 *    pulse animations carry different names/cadences, so the style attribute
 *    is not rewritten wholesale);
 *  - **mirror terminals** — the read-only xterm instances [buildRingPane]
 *    creates for panes whose real terminal isn't mounted; they never enter
 *    the `terminals` registry, so [applyThemeToTerminals] misses them and
 *    they are re-themed here directly.
 */
internal fun restyleWorldChrome() {
    if (!spikeOpen) return
    val chrome = spikeChrome()
    spikeChromeColors = chrome

    // Mirror panes carry fresh xterm instances that never enter the
    // `terminals` registry, so [applyThemeToTerminals] can't reach them —
    // re-theme them here. Real entries (p.entry != null) are already covered
    // by the registry pass that ran just before this in [applyAll].
    val xtermTheme = runCatching { buildXtermTheme() }.getOrNull()
    for (p in spikePanes) {
        if (p.entry == null && xtermTheme != null) p.term?.options?.theme = xtermTheme
        p.wrapper.style.background = chrome.surface
        val bar = p.wrapper.children.item(0) as? HTMLElement ?: continue
        bar.style.background = chrome.titlebarBg
        bar.style.setProperty("border-bottom", "1px solid ${chrome.accent}")
        bar.style.color = chrome.titleText
        (bar.children.item(1) as? HTMLElement)?.style?.color = chrome.titleDim
    }
    // Empty-tab placeholders paint nothing (the visible card box was removed), so
    // there is no chrome to re-tint on a live theme change. @see buildEmptyTabCard
    spikeOverlay?.querySelectorAll(".spike-beacon-glyph")?.let { glyphs ->
        for (i in 0 until glyphs.length) {
            val svg = glyphs.item(i) as? Element ?: continue
            svg.setAttribute("stroke", chrome.accent)
            svg.asDynamic().style.filter =
                "drop-shadow(0 0 26px ${chrome.accent}) drop-shadow(0 0 90px ${chrome.accent})"
        }
    }
    // The "COMMAND CENTER" banner (see [buildHomeBeacon]) is accent-coloured text with
    // a layered neon glow; recolour both in place so it tracks a live theme change.
    spikeOverlay?.querySelectorAll(".spike-beacon-label")?.let { labels ->
        for (i in 0 until labels.length) {
            val el = labels.item(i) as? HTMLElement ?: continue
            el.style.color = chrome.accent
            el.style.setProperty(
                "text-shadow",
                "0 0 24px ${chrome.accent},0 0 72px ${chrome.accent}",
            )
        }
    }
}

/**
 * Builds one ring plane for [spec]: obtains its terminal (real registry term, or
 * a fresh read-only mirror), reparents it onto a titled CSS3D plane, and appends
 * a [RingPane]. Sizing is finalized in [postOpenLayout].
 *
 * @param spec the pane to show. @param n total pane count.
 * @param scene the CSS3D scene. @param chrome the theme colours for the plane chrome.
 */
internal fun buildRingPane(spec: PaneSpec, n: Int, scene: Scene, chrome: SpikeChrome, birth: Double = 1.0) {
    val entry = spec.entry
    val isTerminal = spec.kind == PaneKind.TERMINAL
    val isMirror = isTerminal && entry == null

    val term: Terminal?
    val fit: FitAddon?
    val container: HTMLElement
    var mirrorSocket: WebSocket? = null
    var origParent: Node? = null
    var origNext: Node? = null

    if (!isTerminal) {
        // Git / file-browser panes carry a live DOM view, not an xterm. Two cases,
        // mirroring the terminal split of "real vs mirror":
        //  - **Mounted** (its tab is the active 2D tab): the real `.tt-pane-body` cell is
        //    live in the document. Reparent it exactly like a real terminal container —
        //    shares the 2D view registration untouched (no clobber, no re-fetch) and is
        //    restored to the 2D layout on close.
        //  - **Unmounted**: build a fresh view via [buildGitView]/[buildFileBrowserView],
        //    which self-registers in [gitPaneViews]/[fileBrowserPaneViews] and self-fires
        //    its fetch commands so the plane streams updates over the `/window` channel.
        //    Owned by us and dropped (view registration + DOM) in [disposeRingPane].
        term = null
        fit = null
        val liveCell = runCatching {
            document.querySelector(".tt-pane-body[data-pane=\"${spec.paneId}\"]") as? HTMLElement
        }.getOrNull()
        if (liveCell != null && liveCell.asDynamic().isConnected == true) {
            origParent = liveCell.parentNode
            origNext = liveCell.nextSibling
            liveCell.setAttribute("data-spike-prevcss", liveCell.style.cssText)
            container = liveCell
        } else {
            val host = document.createElement("div") as HTMLElement
            host.style.cssText = "position:absolute;left:0;top:0;overflow:hidden;"
            val leaf = rawLeafFor(spec.paneId)
            if (leaf != null) {
                runCatching {
                    val view = when (spec.kind) {
                        PaneKind.GIT -> buildGitView(spec.paneId, leaf, null)
                        else -> buildFileBrowserView(spec.paneId, leaf, null)
                    }
                    view.style.width = "100%"
                    view.style.height = "100%"
                    host.appendChild(view)
                }
            }
            container = host
        }
    } else if (entry != null) {
        term = entry.term
        fit = entry.fit
        container = entry.container
        origParent = container.parentNode
        origNext = container.nextSibling
        container.setAttribute("data-spike-prevcss", container.style.cssText)
        entry.oobOverlayRight?.style?.display = "none"
        entry.oobOverlayBottom?.style?.display = "none"
    } else {
        val host = document.createElement("div") as HTMLElement
        host.style.cssText = "position:absolute;left:0;top:0;"
        val mTerm = Terminal(
            json(
                "cursorBlink" to false, "disableStdin" to true,
                "fontFamily" to "Menlo, Monaco, 'Courier New', monospace",
                "fontSize" to 13, "scrollback" to 400, "theme" to runCatching { buildXtermTheme() }.getOrNull(),
            ),
        )
        val mFit = FitAddon()
        mTerm.loadAddon(mFit)
        mTerm.open(host)
        if (isDemoClient) {
            runCatching { attachDemoPreview(mTerm, spec.sessionId) }
        } else {
            runCatching {
                val url = "$proto://$backendHost/pty/${spec.sessionId}?$authQueryParam"
                val socket = WebSocket(url)
                socket.asDynamic().binaryType = "arraybuffer"
                socket.onmessage = { event ->
                    val data = event.asDynamic().data
                    if (data is String) {
                        // Control frame: keep the mirror's grid in lock-step with the
                        // authoritative PTY size, exactly like the 2D pane's handler
                        // ([connectPane] → [applyServerSize]). Previously String frames
                        // were dropped, so after any client resized the PTY the mirror
                        // interpreted the repaint against a stale grid — one of the
                        // "reformat blanks the pane" root causes.
                        runCatching {
                            val msg = windowJson.decodeFromString<PtyServerMessage>(data)
                            when (msg) {
                                is PtyServerMessage.Size ->
                                    applyMirrorSize(spec.paneId, mTerm, msg.cols, msg.rows)
                            }
                        }
                    } else {
                        mTerm.write(Uint8Array(data as ArrayBuffer))
                        // Warp-core polish: a burst of live output flickers this pane's
                        // charging reactor a touch brighter (decayed each frame). Cheap
                        // no-op unless the reactor status style is on and the pane is on the ring.
                        if (spikeStatusIndication == StatusIndication.REACTOR) {
                            spikePanes.firstOrNull { it.paneId == spec.paneId }?.let {
                                it.warpFlicker = minOf(1.0, it.warpFlicker + WARP_FLICKER_PER_OUTPUT)
                            }
                        }
                    }
                }
                mirrorSocket = socket
            }
        }
        term = mTerm
        fit = mFit
        container = host
    }

    val provW = if (SPIKE_UNIFORM_SCREENS || isMirror) spikeScreenW else PANE_W
    val provH = if (SPIKE_UNIFORM_SCREENS || isMirror) spikeScreenH else PANE_H

    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${provW}px;height:${provH + TITLE_H}px;position:relative;" +
        "background:${chrome.surface};border:1px solid ${chrome.border};border-radius:8px;overflow:hidden;" +
        "box-shadow:0 0 42px rgba(0,0,0,0.55);"

    val bar = document.createElement("div") as HTMLElement
    bar.style.cssText = "position:absolute;left:0;top:0;right:0;height:${TITLE_H}px;z-index:2;" +
        "display:flex;align-items:center;gap:8px;padding:0 10px;box-sizing:border-box;" +
        "background:${chrome.titlebarBg};border-bottom:1px solid ${chrome.accent};" +
        "font:600 12px ui-monospace,Menlo,monospace;color:${chrome.titleText};white-space:nowrap;overflow:hidden;"
    val titleSpan = document.createElement("span") as HTMLElement
    titleSpan.textContent = spec.title
    titleSpan.style.cssText = "flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;"
    bar.appendChild(titleSpan)
    if (spec.tabTitle.isNotBlank()) {
        val tabSpan = document.createElement("span") as HTMLElement
        tabSpan.textContent = spec.tabTitle
        tabSpan.style.cssText = "flex:0 0 auto;color:${chrome.titleDim};font-weight:400;max-width:38%;" +
            "overflow:hidden;text-overflow:ellipsis;"
        bar.appendChild(tabSpan)
    }
    // (No "preview" chip — an unmounted tab's mirror is indistinguishable in the ring.)
    val previewTag: HTMLElement? = null
    wrapper.appendChild(bar)

    container.style.cssText = "position:absolute;left:0;top:${TITLE_H}px;width:${provW}px;height:${provH}px;"
    wrapper.appendChild(container)

    // Dim veil — opacity is driven continuously per frame by the render loop
    // (in lock-step with the pane swing), so no CSS transition: it would only
    // add a fixed lag behind the motion-matched fade.
    val dim = document.createElement("div") as HTMLElement
    dim.style.cssText = "position:absolute;inset:0;pointer-events:none;" +
        "background:#04060a;opacity:0;"
    wrapper.appendChild(dim)

    // "Working" breath veil — above the dim so it reads over the darkening, below
    // the z-index:2 title bar so the title stays legible. Opacity is driven per
    // frame by the render loop (no CSS transition, which would damp the pulse).
    val glow = document.createElement("div") as HTMLElement
    glow.style.cssText = "position:absolute;inset:0;pointer-events:none;" +
        "background:$WORKING_PULSE_COLOR;opacity:0;mix-blend-mode:screen;"
    wrapper.appendChild(glow)

    // Animated jagged "working" border (the alternative to the breath veil above,
    // selected by WORKING_BORDER_ENABLED). Sized to the full wrapper incl. title bar.
    val border = createWorkingBorder(provW, provH + TITLE_H)
    wrapper.appendChild(border.root)

    val obj = CSS3DObject(wrapper)
    scene.add(obj)

    // Per-pane bulge filter (idle until a latch flex arms it in the render loop).
    val (bulgeId, bulgeMap) = createBulgeFilter()

    val baseFont = ((term?.options?.fontSize as? Number)?.toInt()) ?: 13
    spikePanes.add(
        RingPane(
            paneId = spec.paneId, tabId = spec.tabId, sessionId = spec.sessionId,
            title = spec.title, tabTitle = spec.tabTitle,
            tabOrd = spec.tabOrd, paneOrdInTab = spec.paneOrdInTab,
            dispOrd = spec.paneOrdInTab.toDouble(),
            kind = spec.kind,
            term = term, fit = fit, container = container, wrapper = wrapper, dim = dim, glow = glow,
            border = border,
            previewTag = previewTag, obj = obj, entry = entry, origParent = origParent, origNext = origNext,
            // A git / file-browser DOM view is live and clickable straight away, so it is
            // "interactive" without any promotion; a terminal is interactive only when its
            // real entry is mounted (a mirror must promote first). Non-terminal panes never
            // need the xterm reformat pass.
            mirrorSocket = mirrorSocket, interactive = !isTerminal || entry != null,
            needsRefit = isTerminal && (SPIKE_UNIFORM_SCREENS || isMirror),
            baseFont = baseFont, baseCw = provW, baseCh = provH, normScale = 1.0,
            birth = birth,
            bulgeFilterId = bulgeId, bulgeMap = bulgeMap,
            // A pane already stashed when it is built — the shelf was seeded from the
            // persisted minimize (dock) state on open, or the pane arrived minimized —
            // starts *on* the shelf rather than flying up from its ring slot, so an
            // open doesn't play n unwanted stash cinematics.
            stashProg = if (spec.paneId in spikeStashed) 1.0 else 0.0,
            stashSlot = spikeStashed.indexOf(spec.paneId).coerceAtLeast(0),
        ),
    )
}

/**
 * Applies an authoritative PTY size broadcast ([PtyServerMessage.Size]) to a **mirror**
 * pane's local terminal — the preview-socket counterpart of [applyServerSize], which
 * needs a mounted [TerminalEntry] a mirror doesn't have. Any client's resize of the
 * shared PTY (another window's reformat, or this mirror's own [PtyControl.ForceResize]
 * echoing back) lands here, the mirror's grid follows, and the plane is re-hugged
 * around the new grid on the next frame — so post-resize repaint bytes are never
 * interpreted against a stale grid (which showed as garbled or blank content).
 *
 * Called only from the mirror socket's `onmessage` handler in [buildRingPane]. Unlike
 * [applyServerSize] no echo guard is needed: a mirror never auto-sends resizes — it
 * only ever sends an explicit [PtyControl.ForceResize] from a reformat or grid step.
 *
 * @param paneId the ring pane's id, used to find the plane to re-hug.
 * @param term the mirror's local terminal.
 * @param cols the authoritative PTY column count.
 * @param rows the authoritative PTY row count.
 * @see applyServerSize @see setPaneGrid @see spikeOnServerSize
 */
internal fun applyMirrorSize(paneId: String, term: Terminal, cols: Int, rows: Int) {
    if (cols < 1 || rows < 1) return
    if (cols == term.cols && rows == term.rows) return
    // Same follow-diagnostic as applyServerSize: a grid key's ForceResize log
    // immediately followed by this line carrying the OLD size means some other
    // client won the PTY size fight and the pane is being snapped back.
    console.log(
        "[world3d-spike] mirror Size ${cols}x$rows for pane $paneId " +
            "(local grid was ${term.cols}x${term.rows}) — following"
    )
    val p = spikePanes.find { it.paneId == paneId }
    if (p != null) {
        // Registered pane: the full truthful-follow path — resize the terminal,
        // re-present the plane at the new grid, keep a tail-following viewport
        // pinned. `reassert = false`: the PTY already changed; we only follow.
        setPaneGrid(p, cols, rows, reassert = false)
    } else {
        // Attach race: the Size frame can beat the pane's registration in
        // [spikePanes] (the socket opens inside buildRingPane). Resize the term
        // so the imminent snapshot replay renders at the right grid; the plane
        // is presented moments later by [postOpenLayout].
        runCatching { term.asDynamic().resize(cols, rows) }
    }
}

/**
 * Builds and registers the **invisible** placeholder for an empty tab's latitude and
 * adds it to [spikeEmptyTabs] and the CSS3D scene. Called from [reconcileRing] (and the
 * initial [openWorld3dSpike] build) whenever a non-hidden tab has no panes.
 *
 * The visible "empty tab" card box was removed by request: an emptied tab now shows
 * *nothing* at its latitude. This record is still built and registered so the empty
 * tab keeps its plumbing — navigating to it, adding a pane (`n` → [createPane]) and
 * removing it (`x x` → [confirmRemove]) all resolve the tab via [spikeEmptyTabs] — but
 * its wrapper is an empty, fully transparent div that paints no box, border, glyph or
 * text. It still echoes a pane's footprint (size only) so latitude spacing lines up.
 *
 * @param tabId the backing tab's id. @param title the tab's display title.
 * @param tabOrd the latitude ordinal. @param scene the CSS3D scene to add into.
 * @param chrome the theme colours (unused now the card paints nothing; kept for the
 *   shared [reconcileRing]/[openWorld3dSpike] call signature). @param birth initial
 *   anim factor (0 → grows in).
 * @see reconcileRing @see EmptyTabCard
 */
internal fun buildEmptyTabCard(
    tabId: String, title: String, tabOrd: Int, scene: Scene, chrome: SpikeChrome, birth: Double = 1.0,
) {
    val provW = if (SPIKE_UNIFORM_SCREENS) spikeScreenW else PANE_W
    val provH = if (SPIKE_UNIFORM_SCREENS) spikeScreenH else PANE_H

    // Empty, fully transparent placeholder — no box is drawn. Sized to a pane's
    // footprint only so the latitude keeps consistent spacing; nothing is painted.
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.style.cssText = "width:${provW}px;height:${provH + TITLE_H}px;position:relative;" +
        "background:transparent;pointer-events:none;"

    val obj = CSS3DObject(wrapper)
    scene.add(obj)
    spikeEmptyTabs.add(EmptyTabCard(tabId, tabOrd, title, wrapper, obj, birth = birth))
}

/**
 * Reconciles the ring against the latest window config — the single source of truth
 * for structural change while the spike is open. Driven by the [spikeConfigJob]
 * subscription on every config emission (a pane/tab created or closed, whether by the
 * spike's own `t`/`n`/`x` keys or by the 2D app).
 *
 * Panes and empty-tab cards present in the config but absent from the ring are
 * **built** growing in (birth 0 → 1); ones on the ring but gone from the config are
 * marked **dying** (the render loop shrinks then disposes them). Survivors get their
 * `tabOrd`/`paneOrdInTab` renumbered to match, and the selection
 * ([spikeTabIndex]/[spikeTabSel]) is resized and clamped so navigation stays valid.
 * Any [spikePendingFocusTab]/[spikePendingFocusNewTab] request is honoured so a just-
 * created pane/tab fronts itself. Idempotent: an unchanged config is a no-op.
 *
 * @see buildRingPane @see buildEmptyTabCard @see spikeConfigJob
 */
internal fun reconcileRing() {
    val scene = spikeCssScene ?: return
    val chrome = spikeChromeColors ?: return
    val specs = collectPaneSpecs()
    val tabs = collectTabs()
    val tabCount = tabs.size.coerceAtLeast(1)

    // --- Panes: add newcomers (growing in), renumber survivors, retire the gone. ---
    // Existence is checked against *all* panes (incl. dying) so a pane we optimistically
    // marked dying on an `x` press can't be re-added as a duplicate before its Close lands.
    val specIds = specs.map { it.paneId }.toSet()
    var added = false
    val newBornIds = mutableListOf<String>()
    for (spec in specs) {
        val existing = spikePanes.firstOrNull { it.paneId == spec.paneId }
        if (existing == null) {
            buildRingPane(spec, spikePanes.size, scene, chrome, birth = 0.0)
            added = true
            newBornIds.add(spec.paneId)
        } else if (!existing.dying && !isParkedBundlePane(existing)) {
            // A *committed* bundle pane is deliberately held off-ring ([RingPane.tabOrd] = −1)
            // so the command center excludes it; don't let a stale config broadcast (arriving
            // before our SetTabHidden round-trips, tab still listed) renumber it back on. Once
            // the bundle is unstashed ([TabBundle.committed] cleared) this renumbers again so
            // separation lands the panes on their real ring slots. @see stashTab
            existing.tabOrd = spec.tabOrd
            existing.paneOrdInTab = spec.paneOrdInTab
        }
    }
    // A pane mid phaser-fire close (phaserPhase >= 0) has already left the config but
    // must keep burning at the front for the full barrage — [tickPhaser] marks it dying
    // when it completes, so don't shrink it out here the instant its Close lands.
    // A pane held in a **tab bundle** (bundleId != null) has also left the config — its
    // whole tab is unlisted ([TabConfig.isHidden]) — but is flying to / resting in the
    // hangar bay as part of a merged stack owned by [tickBundles]; it must survive the
    // death sweep until [unstashTab] separates it back onto the ring. @see stashTab
    for (p in spikePanes) {
        if (!p.dying && p.phaserPhase < 0.0 && p.bundleId == null && p.paneId !in specIds) p.dying = true
    }

    // A phasering pane keeps burning at its old ring slot, but it has already left the
    // config — so the renumber above just gave every *later* pane in its tab the next
    // ordinal down, pulling the killed pane's neighbour forward into the very slot it is
    // still dying in. Re-open a gap at each phasering pane's frozen ordinal: bump every
    // surviving (non-dying, non-phaser) pane in its tab whose ordinal now lands at or past
    // it back by one, so the survivors hold their places until [tickPhaser] finishes the
    // barrage, marks the pane dying and re-reconciles (which then closes the gap for real).
    // Ascending frozen-ordinal order keeps this correct when two panes burn in one tab.
    // @see startPhaserDeath @see tickPhaser
    val phaseringPanes = spikePanes
        .filter { it.phaserPhase >= 0.0 && !it.dying }
        .sortedBy { it.paneOrdInTab }
    for (ph in phaseringPanes) {
        for (s in spikePanes) {
            if (s === ph || s.dying || s.phaserPhase >= 0.0) continue
            if (s.tabOrd == ph.tabOrd && s.paneOrdInTab >= ph.paneOrdInTab) s.paneOrdInTab += 1
        }
    }

    // --- Empty-tab cards: a non-hidden tab with no kept panes gets one card. ---
    val paneTabIds = specs.map { it.tabId }.toSet()
    val emptyTabs = tabs.mapIndexedNotNull { ord, (id, title) ->
        if (id.isNotEmpty() && id !in paneTabIds) Triple(id, title, ord) else null
    }
    val emptyTabIds = emptyTabs.map { it.first }.toSet()
    for ((id, title, ord) in emptyTabs) {
        val card = spikeEmptyTabs.firstOrNull { it.tabId == id }
        if (card == null) buildEmptyTabCard(id, title, ord, scene, chrome, birth = 0.0)
        else if (!card.dying) { card.tabOrd = ord; card.title = title }
    }
    for (c in spikeEmptyTabs) if (!c.dying && c.tabId !in emptyTabIds) c.dying = true

    // --- Selection: resize per-tab memory, then clamp everything into range. ---
    while (spikeTabSel.size < tabCount) spikeTabSel.add(0)
    while (spikeTabSel.size > tabCount) spikeTabSel.removeAt(spikeTabSel.size - 1)
    spikeTabIndex = spikeTabIndex.coerceIn(0, tabCount - 1)
    for (t in 0 until tabCount) {
        val cnt = spikePanes.count { !it.dying && it.tabOrd == t }
        spikeTabSel[t] = spikeTabSel[t].coerceIn(0, (cnt - 1).coerceAtLeast(0))
    }

    // --- Honour a pending "front the thing I just made" request. ---
    spikePendingFocusTab?.let { wanted ->
        // Front the pane we just *created* — identified as a **newborn built this pass**
        // ([newBornIds]), not merely the highest-ordinal pane in the tab. A tab can already
        // hold a stashed pane (up on the shelf, its ring slot a gap) whose ordinal outranks
        // the newcomer; a plain `maxByOrNull { paneOrdInTab }` would front that shelved pane's
        // empty gap and leave the new pane sitting off to the side, unselected. Keying on the
        // newborn also defers the front until the server actually mints the pane — an earlier
        // config broadcast that arrives without it no longer clears the request against a
        // pre-existing pane. @see spikePendingFocusTab @see createPane
        val newest = specs
            .filter { it.tabId == wanted && it.paneId in newBornIds }
            .maxByOrNull { it.paneOrdInTab }
        if (newest != null) {
            leaveFrontPane()
            spikeTabIndex = newest.tabOrd
            spikeTabSel[newest.tabOrd] = newest.paneOrdInTab
            loadFrontZoom(); showNavLabel(); spikeSettledIndex = -1
            spikePendingFocusTab = null
            // Keep this fresh pane at the *end* of the row: it sits at the tail now (the
            // toolkit hasn't ordered it yet), but the toolkit will soon slot it in beside
            // the split source — [pinNewPanesLast] moves it back to last once that lands.
            spikePinLastPanes.add(newest.paneId)
        }
    }
    // A just-created pane whose toolkit order has now arrived may need pinning last.
    pinNewPanesLast()
    if (spikePendingFocusNewTab && emptyTabs.isNotEmpty()) {
        leaveFrontPane()
        spikeTabIndex = emptyTabs.maxOf { it.third }
        loadFrontZoom(); showNavLabel(); spikeSettledIndex = -1
        spikePendingFocusNewTab = false
    }

    // Freshly-built panes need one formatting pass so they don't come up blank.
    if (added) spikeNeedsInitialLayout = true

    // Wormhole spawn: a *lone* newborn (created interactively, not part of a burst) is
    // born through a vortex off to the side while the camera is idle — otherwise it just
    // grows in. Armed last, once selection/focus above have settled. @see armWormholeSpawn
    if (wormholeSpawnEligible(newBornIds.size)) {
        spikePanes.firstOrNull { it.paneId == newBornIds[0] }?.let { armWormholeSpawn(it) }
    }
}

/**
 * Tears one pane off the ring: restores a real terminal's container to its 2D home
 * (font size, inline styles, DOM parent) or disposes a mirror's term/socket/element,
 * and removes the CSS3D object from the scene. Shared by [closeWorld3dSpike] (all
 * panes) and the render loop's death sweep (a single [RingPane.dying] pane that has
 * finished shrinking out).
 *
 * A restored mounted terminal also **reasserts its 2D grid** ([forceReassert],
 * deferred one frame for real layout) when its automatic reflow is on — a 3D
 * reformat/grid step may have force-resized the shared PTY while the world was open,
 * and without this the pane stayed at that foreign size back in 2D.
 *
 * @param p the pane to dispose.
 * @see forceReassert
 */
internal fun disposeRingPane(p: RingPane) {
    runCatching {
        when {
            // Git / file-browser: a reparented **real** 2D cell (has an origParent) is
            // restored to the 2D layout, its view registration left untouched; an
            // **owned preview** has its view registration dropped (so incoming messages
            // stop rendering into detached DOM) and its host removed. The cached *state*
            // is kept either way, so the 2D pane repaints instantly if it later mounts.
            p.kind == PaneKind.GIT || p.kind == PaneKind.FILE_BROWSER -> {
                if (p.origParent != null) {
                    val container = p.container
                    container.style.cssText = container.getAttribute("data-spike-prevcss") ?: ""
                    container.removeAttribute("data-spike-prevcss")
                    p.origParent?.insertBefore(container, p.origNext)
                } else {
                    if (p.kind == PaneKind.GIT) gitPaneViews.remove(p.paneId)
                    else fileBrowserPaneViews.remove(p.paneId)
                    runCatching { p.container.remove() }
                }
            }
            p.entry != null -> {
                val entry = p.entry!!
                entry.term.options.fontSize = p.baseFont
                val container = p.container
                container.style.cssText = container.getAttribute("data-spike-prevcss") ?: ""
                container.removeAttribute("data-spike-prevcss")
                p.origParent?.insertBefore(container, p.origNext)
                // Reclaim the pane's 2D grid: while in the world, a 3D reformat/grid
                // step (this pane's or another client's) may have force-resized the
                // shared PTY, and the broadcast `Size` shrank this mounted terminal
                // to match. Refit to the restored 2D container and reassert — deferred
                // a frame so the reinserted cell has real 2D layout to measure.
                // Skipped for panes with automatic reflow off: the user froze that
                // pane's size, so we leave the PTY exactly as the world left it.
                if (entry.autoReflow) {
                    window.requestAnimationFrame {
                        runCatching { forceReassert(entry) }
                    }
                }
            }
            else -> {
                runCatching { p.mirrorSocket?.close() }
                runCatching { p.term?.dispose() }
                runCatching { p.container.remove() }
            }
        }
    }
    runCatching { spikeCssScene?.asDynamic()?.remove(p.obj) }
    runCatching { p.wrapper.remove() }
    // Drop any stash entry so the shelf never holds a disposed pane's id.
    spikeStashed.remove(p.paneId)
}

/** Removes an empty-tab card's plane from the scene and DOM (death-sweep / close). */
internal fun disposeEmptyCard(c: EmptyTabCard) {
    runCatching { spikeCssScene?.asDynamic()?.remove(c.obj) }
    runCatching { c.wrapper.remove() }
}
