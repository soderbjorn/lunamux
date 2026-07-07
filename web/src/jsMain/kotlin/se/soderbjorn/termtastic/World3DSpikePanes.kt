/*
 * Split from World3DSpike.kt — pane/tab enumeration from the window config (collectPaneSpecs, collectTabs).
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
 * The user-facing pane order for [tabId], read from the toolkit-owned `LAYOUT_STATE`
 * blob (`paneOrderByTab`) — the **same source the left sidebar sorts its rows by**.
 *
 * Why this exists: the server's [TabConfig.panes] is a *creation-ordered* free-form
 * list (there is no split tree), while the toolkit keeps its own per-tab pane order
 * (head = primary) that the user can drag-reorder in the sidebar. That order is
 * persisted into `LAYOUT_STATE` and surfaced live on
 * [se.soderbjorn.termtastic.client.WindowStateRepository.rawLayoutState]. Reading it
 * here lets the ring match the sidebar after a manual rearrange, instead of showing
 * the original creation order. The blob may arrive either as a [JsonObject] or as a
 * JSON-string [JsonPrimitive] (server settings round-trip), so both are handled.
 *
 * @param tabId the tab whose pane order to read.
 * @return the pane ids in the sidebar's display order, or an empty list when no blob
 *   or no entry exists (callers then fall back to [TabConfig.panes] order).
 * @see collectPaneSpecs
 */
internal fun toolkitPaneOrder(tabId: String): List<String> {
    val raw = runCatching { termtasticClient.windowState.rawLayoutState.value }.getOrNull() ?: return emptyList()
    val obj: JsonObject = when {
        raw is JsonObject -> raw
        raw is JsonPrimitive && raw.isString ->
            runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull() ?: return emptyList()
        else -> return emptyList()
    }
    val orderByTab = obj["paneOrderByTab"] as? JsonObject ?: return emptyList()
    val arr = orderByTab[tabId] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/**
 * Enumerates the terminal panes the ring should show, across **all** visible tabs,
 * grouped by tab. Empty tabs (no terminal panes) are skipped; each surviving tab
 * gets a sequential [PaneSpec.tabOrd] and each pane a [PaneSpec.paneOrdInTab].
 * Falls back to the mounted registry terms (one tab) if no config has arrived.
 *
 * Panes **within** a tab are ordered by [toolkitPaneOrder] — the same order the left
 * sidebar renders — so a manual sidebar drag-reorder is reflected on the ring rather
 * than the server's creation order. Panes the toolkit doesn't track yet (e.g. just-
 * created this tick) fall to the tail in [TabConfig.panes] order; the sort is stable
 * so ties keep config order. This mirrors the sidebar's `orderIndex[id] ?: MAX_VALUE`.
 *
 * @return the pane specs in ring order (tab order, then sidebar order within a tab).
 */
internal fun collectPaneSpecs(): List<PaneSpec> {
    val cfg = latestWindowConfig
    if (cfg == null) {
        return terminals.values
            .filter { it.container.asDynamic().isConnected == true }
            .mapIndexed { i, e -> PaneSpec(e.paneId, "", e.paneId, "", e.sessionId, e, 0, i) }
    }
    // tabOrd spans **every** non-hidden tab (not just non-empty ones) so an empty tab
    // still owns a latitude on the sphere — its slot is filled by an [EmptyTabCard]
    // rather than compacted away. Ordering matches [orderedTabs].
    val specs = mutableListOf<PaneSpec>()
    cfg.tabs.filter { !it.isHidden }.forEachIndexed { tabOrd, tab ->
        // Sort this tab's panes into the sidebar's display order (stable → unknown
        // panes keep config order at the tail), so the ring matches what the user sees.
        val orderRank = toolkitPaneOrder(tab.id).withIndex().associate { (i, id) -> id to i }
        val orderedPanes = tab.panes.sortedBy { orderRank[it.leaf.id] ?: Int.MAX_VALUE }
        var ord = 0
        for (pane in orderedPanes) {
            val leaf = pane.leaf
            val entry = terminals[leaf.id]
            val content = leaf.content
            when (content) {
                // Git / file-browser panes carry a live DOM view, never a PTY, so they
                // are always shown regardless of mount state — [buildRingPane] builds a
                // streaming preview for them. (No `entry`; they are not in `terminals`.)
                is GitContent -> {
                    specs.add(PaneSpec(leaf.id, tab.id, leaf.title, tab.title, leaf.sessionId, null, tabOrd, ord, PaneKind.GIT))
                    ord++
                }
                is FileBrowserContent -> {
                    specs.add(PaneSpec(leaf.id, tab.id, leaf.title, tab.title, leaf.sessionId, null, tabOrd, ord, PaneKind.FILE_BROWSER))
                    ord++
                }
                else -> {
                    // Terminal (or agent-screen) panes: shown when mounted, or as a
                    // read-only mirror when the leaf has a session but no live entry.
                    val isTerminal = content == null || content is TerminalContent
                    if (entry == null && !(isTerminal && leaf.sessionId.isNotEmpty())) continue
                    specs.add(PaneSpec(leaf.id, tab.id, leaf.title, tab.title, leaf.sessionId, entry, tabOrd, ord, PaneKind.TERMINAL))
                    ord++
                }
            }
        }
    }
    return specs
}

/**
 * The ordered list of tabs the ring lays out as latitudes: **every** non-hidden tab
 * (including empty ones), as `(tabId, title)`. The index into this list is the tab's
 * `tabOrd`, matching [collectPaneSpecs]. Falls back to a single anonymous tab when no
 * window config has arrived yet (mirrors [collectPaneSpecs]'s registry fallback).
 *
 * @return the tab id/title pairs in ring (latitude) order.
 * @see reconcileRing @see EmptyTabCard
 */
internal fun collectTabs(): List<Pair<String, String>> {
    val cfg = latestWindowConfig ?: return listOf("" to "")
    return cfg.tabs.filter { !it.isHidden }.map { it.id to it.title }
}

/**
 * Looks up the **raw** (dynamic JSON) leaf descriptor for [paneId] from [currentConfig].
 * This is the shape [buildGitView] / [buildFileBrowserView] expect — string-valued enums
 * and plain JS arrays — as opposed to the typed [latestWindowConfig]. Mirrors the leaf
 * scan in [mountPaneContent], so a ring preview reads exactly what the 2D mount would.
 *
 * @param paneId the pane (leaf) id to find.
 * @return the raw leaf `dynamic`, or `null` if no config or no such leaf.
 * @see buildRingPane
 */
internal fun rawLeafFor(paneId: String): dynamic {
    val cfg: dynamic = currentConfig ?: return null
    val tabsArr = cfg.tabs as? Array<dynamic> ?: return null
    for (tab in tabsArr) {
        val panes = (tab.panes as? Array<dynamic>) ?: continue
        for (p in panes) {
            if ((p.leaf?.id as? String) == paneId) return p.leaf
        }
    }
    return null
}
