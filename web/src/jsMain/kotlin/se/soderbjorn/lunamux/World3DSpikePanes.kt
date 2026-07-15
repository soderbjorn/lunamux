/*
 * Split from World3DSpike.kt — pane/tab enumeration from the window config (collectPaneSpecs, collectTabs).
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
 * The user-facing pane order for [tabId], read from the toolkit-owned `LAYOUT_STATE`
 * blob (`paneOrderByTab`) — the **same source the left sidebar sorts its rows by**.
 *
 * Why this exists: the server's [TabConfig.panes] is a *creation-ordered* free-form
 * list (there is no split tree), while the toolkit keeps its own per-tab pane order
 * (head = primary) that the user can drag-reorder in the sidebar. That order is
 * persisted into `LAYOUT_STATE`. Reading it here lets the ring match the sidebar
 * after a manual rearrange, instead of showing the original creation order.
 *
 * Read through [layoutStateJson] — the same source [persistPaneOrder] writes to —
 * **not** `rawLayoutState` directly. `rawLayoutState` is only the last *server
 * broadcast*, so reading it here would have re-sorted the ring from a stale order in
 * two cases:
 *  - [movePaneSlot] (⇧←/⇧→) writes the new order to the shell and then to the server
 *    settings key, but its accompanying [WindowCommand.MovePaneWithinTab] re-broadcasts
 *    the *config* first — and [reconcileRing] runs on that config emission, before the
 *    settings round-trip lands. The stale rank then renumbered the panes straight back
 *    and the move appeared to do nothing;
 *  - `rawLayoutState` tracks the flat `LAYOUT_STATE` key, which holds only the
 *    **default** world's layout, so in a non-default world it was the wrong blob
 *    entirely. @see se.soderbjorn.lunamux.LunamuxToolkitBootstrap
 *
 * [layoutStateJson] prefers the mounted shell's live state, which already reflects the
 * [persistPaneOrder] write, so the order is correct on the very next reconcile.
 *
 * @param tabId the tab whose pane order to read.
 * @return the pane ids in the sidebar's display order, or an empty list when no blob
 *   or no entry exists (callers then fall back to [TabConfig.panes] order).
 * @see collectPaneSpecs @see layoutStateJson @see persistPaneOrder
 */
internal fun toolkitPaneOrder(tabId: String): List<String> =
    paneOrderByTabBlob()?.paneOrderFor(tabId) ?: emptyList()

/**
 * The whole `paneOrderByTab` map from the current `LAYOUT_STATE` blob, parsed once.
 *
 * Exists so a caller ranking **several** tabs ([collectPaneSpecs], which runs on every
 * config emission) parses the blob once rather than once per tab — [layoutStateJson]
 * hands back the blob as an unparsed string, so the per-tab [toolkitPaneOrder] would
 * otherwise re-parse the entire layout for each tab on the ring.
 *
 * @return the `paneOrderByTab` object, or `null` when there is no blob, it doesn't
 *   parse, or it has no such key (the key only appears after the first reorder).
 * @see toolkitPaneOrder @see paneOrderFor
 */
private fun paneOrderByTabBlob(): JsonObject? {
    val raw = layoutStateJson() ?: return null
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    return obj["paneOrderByTab"] as? JsonObject
}

/**
 * Reads one tab's pane-id list out of an already-parsed [paneOrderByTabBlob].
 *
 * @param tabId the tab whose pane order to read.
 * @return the pane ids in display order, or an empty list when this tab has no entry.
 * @see paneOrderByTabBlob
 */
private fun JsonObject.paneOrderFor(tabId: String): List<String> {
    val arr = this[tabId] as? JsonArray ?: return emptyList()
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
    // Parsed once for the whole sweep, not per tab — see [paneOrderByTabBlob].
    val orderByTab = paneOrderByTabBlob()
    // [effectiveTabs], never the flat `cfg.tabs`: that field is a legacy mirror the server pins to
    // `worlds.first()` for pre-1.9 clients and it does NOT track the active world, so reading it
    // built the ring from the DEFAULT world's panes no matter which world you were in — while the
    // 2D tab strip ([LunamuxTabSource]) showed the active one. @see effectiveTabs
    cfg.effectiveTabs.filter { !it.isHidden }.forEachIndexed { tabOrd, tab ->
        // Sort this tab's panes into the sidebar's display order (stable → unknown
        // panes keep config order at the tail), so the ring matches what the user sees.
        val orderRank = orderByTab?.paneOrderFor(tab.id).orEmpty()
            .withIndex().associate { (i, id) -> id to i }
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
                is WebBrowserContent -> {
                    // Web panes carry a live <webview>, never a PTY. Shown in
                    // the ring as a lightweight placeholder; the live cell is
                    // promoted onto the front plane only on engage.
                    specs.add(PaneSpec(leaf.id, tab.id, leaf.title, tab.title, leaf.sessionId, null, tabOrd, ord, PaneKind.WEB_BROWSER))
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
    // [effectiveTabs], for the same reason as [collectPaneSpecs] — and it must stay in step with it,
    // since `tabOrd` is an index into this list.
    return cfg.effectiveTabs.filter { !it.isHidden }.map { it.id to it.title }
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
internal fun rawLeafFor(paneId: String): dynamic =
    // Delegate to the shared world-aware scan so a ring preview reads exactly
    // what the 2D mount would — including panes in non-default worlds, which
    // the legacy top-level `cfg.tabs` mirror does not carry.
    findLeafDynamic(paneId)
