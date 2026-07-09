/**
 * The three diff renderers used by the git pane.
 *
 *  - [renderGitDiffInline]: unified view with old/new line numbers in a
 *    single column.
 *  - [renderGitDiffSplit]: side-by-side panels with paired add/del rows.
 *  - [renderGitDiffGraphical]: side-by-side panels plus an SVG connector
 *    layer that draws curves between corresponding change regions and
 *    keeps the two scroll positions piecewise-linearly synchronised.
 *
 * @see buildGitView
 * @see GitPaneState
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.js.json

/**
 * Renders a unified/inline diff view with old and new line numbers side
 * by side. Addition lines are highlighted green, deletions red, context
 * plain.
 */
internal fun renderGitDiffInline(diffPane: HTMLElement, parsed: dynamic) {
    diffPane.innerHTML = ""
    val hunks = parsed.hunks as Array<dynamic>
    if (hunks.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No changes"
        diffPane.appendChild(empty); return
    }
    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-inline"
    for (hunk in hunks) {
        val hunkDiv = document.createElement("div") as HTMLElement
        hunkDiv.className = "git-hunk"
        val lines = hunk.lines as Array<dynamic>
        for (line in lines) {
            val lineDiv = document.createElement("div") as HTMLElement
            lineDiv.className = "git-diff-line"
            val type = line.type as String
            when (type) { "Addition" -> lineDiv.classList.add("git-line-add"); "Deletion" -> lineDiv.classList.add("git-line-del") }
            val oldNo = document.createElement("span") as HTMLElement
            oldNo.className = "git-line-no"; oldNo.textContent = (line.oldLineNo as? Number)?.toString() ?: ""
            val newNo = document.createElement("span") as HTMLElement
            newNo.className = "git-line-no"; newNo.textContent = (line.newLineNo as? Number)?.toString() ?: ""
            val content = document.createElement("span") as HTMLElement
            content.className = "git-line-content"; content.innerHTML = line.content as String
            lineDiv.appendChild(oldNo); lineDiv.appendChild(newNo); lineDiv.appendChild(content)
            hunkDiv.appendChild(lineDiv)
        }
        container.appendChild(hunkDiv)
    }
    diffPane.appendChild(container)
}

/**
 * Renders a side-by-side (split) diff view with the old file on the
 * left and the new file on the right.
 */
internal fun renderGitDiffSplit(diffPane: HTMLElement, parsed: dynamic) {
    diffPane.innerHTML = ""
    val hunks = parsed.hunks as Array<dynamic>
    if (hunks.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No changes"
        diffPane.appendChild(empty); return
    }
    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-split"

    data class SplitRow(
        val oldNo: String, val oldContent: String, val oldClass: String,
        val newNo: String, val newContent: String, val newClass: String,
    )
    val rows = mutableListOf<SplitRow>()

    for (hunk in hunks) {
        val lines = hunk.lines as Array<dynamic>
        var i = 0
        while (i < lines.size) {
            val line = lines[i]; val type = line.type as String
            if (type == "Context") {
                val no = (line.oldLineNo as? Number)?.toString() ?: ""
                val nno = (line.newLineNo as? Number)?.toString() ?: ""
                rows.add(SplitRow(no, line.content as String, "", nno, line.content as String, ""))
                i++
            } else {
                val dels = mutableListOf<dynamic>(); val adds = mutableListOf<dynamic>()
                while (i < lines.size && (lines[i].type as String) == "Deletion") { dels.add(lines[i]); i++ }
                while (i < lines.size && (lines[i].type as String) == "Addition") { adds.add(lines[i]); i++ }
                val maxLen = maxOf(dels.size, adds.size)
                for (j in 0 until maxLen) {
                    val del = dels.getOrNull(j); val add = adds.getOrNull(j)
                    val isModified = del != null && add != null
                    rows.add(SplitRow(
                        oldNo = if (del != null) (del.oldLineNo as? Number)?.toString() ?: "" else "",
                        oldContent = if (del != null) del.content as String else "",
                        oldClass = when { del == null -> "git-split-placeholder"; isModified -> "git-split-mod"; else -> "git-line-del" },
                        newNo = if (add != null) (add.newLineNo as? Number)?.toString() ?: "" else "",
                        newContent = if (add != null) add.content as String else "",
                        newClass = when { add == null -> "git-split-placeholder"; isModified -> "git-split-mod"; else -> "git-line-add" },
                    ))
                }
            }
        }
    }

    for (row in rows) {
        val rowDiv = document.createElement("div") as HTMLElement
        rowDiv.className = "git-split-row"
        val leftHalf = document.createElement("div") as HTMLElement
        leftHalf.className = "git-split-half ${row.oldClass}"
        val leftNo = document.createElement("span") as HTMLElement
        leftNo.className = "git-line-no"; leftNo.textContent = row.oldNo
        val leftContent = document.createElement("span") as HTMLElement
        leftContent.className = "git-line-content"; leftContent.innerHTML = row.oldContent
        leftHalf.appendChild(leftNo); leftHalf.appendChild(leftContent)
        val gutter = document.createElement("div") as HTMLElement
        gutter.className = "git-split-gutter"
        val rightHalf = document.createElement("div") as HTMLElement
        rightHalf.className = "git-split-half ${row.newClass}"
        val rightNo = document.createElement("span") as HTMLElement
        rightNo.className = "git-line-no"; rightNo.textContent = row.newNo
        val rightContent = document.createElement("span") as HTMLElement
        rightContent.className = "git-line-content"; rightContent.innerHTML = row.newContent
        rightHalf.appendChild(rightNo); rightHalf.appendChild(rightContent)
        rowDiv.appendChild(leftHalf); rowDiv.appendChild(gutter); rowDiv.appendChild(rightHalf)
        container.appendChild(rowDiv)
    }
    diffPane.appendChild(container)
}

/**
 * Renders a graphical side-by-side diff with SVG connector curves
 * linking corresponding change regions between the old and new file
 * panels.
 */
internal fun renderGitDiffGraphical(diffPane: HTMLElement, parsed: dynamic, state: GitPaneState) {
    diffPane.innerHTML = ""
    val oldContent = parsed.oldContent as? String
    val newContent = parsed.newContent as? String
    val language = parsed.language as? String
    val hunks = parsed.hunks as Array<dynamic>
    if (oldContent == null && newContent == null) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "git-diff-empty"; empty.textContent = "No content available for graphical diff"
        diffPane.appendChild(empty); return
    }
    val oldLines = (oldContent ?: "").split("\n")
    val newLines = (newContent ?: "").split("\n")
    val fontSize = state.diffFontSize
    val lineHeight = (fontSize * 1.54).toInt()
    val lh = lineHeight.toDouble()

    data class ChangeRegion(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int, val type: String)
    val oldModified = mutableSetOf<Int>(); val newModified = mutableSetOf<Int>()
    val oldChanged = mutableSetOf<Int>(); val newChanged = mutableSetOf<Int>()
    val regions = mutableListOf<ChangeRegion>()

    for (hunk in hunks) {
        val lines = hunk.lines as Array<dynamic>
        var lastOldLineNo = (hunk.oldStart as Number).toInt() - 1
        var lastNewLineNo = (hunk.newStart as Number).toInt() - 1
        var i = 0
        while (i < lines.size) {
            val type = lines[i].type as String
            if (type == "Context") {
                lastOldLineNo = (lines[i].oldLineNo as? Number)?.toInt() ?: lastOldLineNo
                lastNewLineNo = (lines[i].newLineNo as? Number)?.toInt() ?: lastNewLineNo
                i++; continue
            }
            val dels = mutableListOf<dynamic>(); val adds = mutableListOf<dynamic>()
            while (i < lines.size && (lines[i].type as String) == "Deletion") { dels.add(lines[i]); i++ }
            while (i < lines.size && (lines[i].type as String) == "Addition") { adds.add(lines[i]); i++ }
            val paired = minOf(dels.size, adds.size)
            for (j in 0 until paired) {
                val oln = (dels[j].oldLineNo as Number).toInt(); val nln = (adds[j].newLineNo as Number).toInt()
                oldModified.add(oln); oldChanged.add(oln); newModified.add(nln); newChanged.add(nln)
            }
            for (j in paired until dels.size) oldChanged.add((dels[j].oldLineNo as Number).toInt())
            for (j in paired until adds.size) newChanged.add((adds[j].newLineNo as Number).toInt())
            val regionType = when { dels.isNotEmpty() && adds.isNotEmpty() -> "mod"; dels.isNotEmpty() -> "del"; else -> "add" }
            val oldStart = if (dels.isNotEmpty()) (dels.first().oldLineNo as Number).toInt() else lastOldLineNo + 1
            val oldEnd = if (dels.isNotEmpty()) (dels.last().oldLineNo as Number).toInt() else oldStart - 1
            val newStart = if (adds.isNotEmpty()) (adds.first().newLineNo as Number).toInt() else lastNewLineNo + 1
            val newEnd = if (adds.isNotEmpty()) (adds.last().newLineNo as Number).toInt() else newStart - 1
            regions.add(ChangeRegion(oldStart, oldEnd, newStart, newEnd, regionType))
            if (dels.isNotEmpty()) lastOldLineNo = (dels.last().oldLineNo as Number).toInt()
            if (adds.isNotEmpty()) lastNewLineNo = (adds.last().newLineNo as Number).toInt()
        }
    }

    val container = document.createElement("div") as HTMLElement
    container.className = "git-diff-graphical"

    fun buildPanel(linesList: List<String>, modifiedSet: Set<Int>, changedSet: Set<Int>, highlightMod: String, highlightChanged: String): Pair<HTMLElement, HTMLElement> {
        val pane = document.createElement("div") as HTMLElement
        val inner = document.createElement("div") as HTMLElement
        inner.className = "git-graphical-inner"
        for ((idx, line) in linesList.withIndex()) {
            val lineNo = idx + 1
            val div = document.createElement("div") as HTMLElement
            div.className = "git-graphical-line" + when {
                modifiedSet.contains(lineNo) -> " $highlightMod"
                changedSet.contains(lineNo) -> " $highlightChanged"
                else -> ""
            }
            div.style.height = "${lineHeight}px"; div.style.lineHeight = "${lineHeight}px"
            val noSpan = document.createElement("span") as HTMLElement
            noSpan.className = "git-line-no"; noSpan.textContent = "$lineNo"
            val cSpan = document.createElement("span") as HTMLElement
            cSpan.className = "git-line-content"
            // Syntax-highlight each full-file line the same way the inline/split
            // hunk lines are highlighted (server-side). The graphical view renders
            // whole-file content, so we highlight per line here — block constructs
            // that span lines aren't tracked across lines, matching the per-line
            // behaviour of the hunk-based views. Empty lines keep a space so the
            // row still occupies its fixed height.
            cSpan.innerHTML = SyntaxHighlighter.highlight(line, language).ifEmpty { " " }
            div.appendChild(noSpan); div.appendChild(cSpan)
            inner.appendChild(div)
        }
        pane.appendChild(inner)
        return Pair(pane, inner)
    }

    val (leftPane, leftInner) = buildPanel(oldLines, oldModified, oldChanged, "highlight-mod", "highlight-del")
    leftPane.className = "git-graphical-left"
    val (rightPane, rightInner) = buildPanel(newLines, newModified, newChanged, "highlight-mod", "highlight-add")
    rightPane.className = "git-graphical-right"

    val diff = kotlin.math.abs(oldLines.size - newLines.size) * lineHeight
    if (diff > 0) {
        val pad = document.createElement("div") as HTMLElement
        pad.style.height = "${diff}px"
        if (oldLines.size > newLines.size) rightInner.appendChild(pad) else leftInner.appendChild(pad)
    }

    val svgNs = "http://www.w3.org/2000/svg"
    val svgContainer = document.createElement("div") as HTMLElement
    svgContainer.className = "git-graphical-connector"
    val svg = document.createElementNS(svgNs, "svg")
    svg.setAttribute("class", "git-graphical-svg")
    svgContainer.appendChild(svg)

    container.appendChild(leftPane); container.appendChild(svgContainer); container.appendChild(rightPane)
    diffPane.appendChild(container)

    // Piecewise linear scroll mapping
    data class Anchor(val rightY: Double, val leftY: Double)
    val anchors = mutableListOf<Anchor>()
    anchors.add(Anchor(0.0, 0.0))
    for (r in regions) {
        val oc = if (r.oldEnd >= r.oldStart) r.oldEnd - r.oldStart + 1 else 0
        val nc = if (r.newEnd >= r.newStart) r.newEnd - r.newStart + 1 else 0
        if (nc == 0) continue
        anchors.add(Anchor((r.newStart - 1) * lh, (r.oldStart - 1) * lh))
        anchors.add(Anchor((r.newStart - 1 + nc) * lh, (r.oldStart - 1 + oc) * lh))
    }
    anchors.add(Anchor(newLines.size * lh, oldLines.size * lh))

    fun mapRightToLeft(rY: Double): Double {
        for (i in 1 until anchors.size) {
            if (rY <= anchors[i].rightY) {
                val prev = anchors[i - 1]; val next = anchors[i]
                val span = next.rightY - prev.rightY
                if (span <= 0.0) return next.leftY
                val t = ((rY - prev.rightY) / span).coerceIn(0.0, 1.0)
                return prev.leftY + t * (next.leftY - prev.leftY)
            }
        }
        return anchors.last().leftY
    }

    var rafPending = false
    fun updateConnectors() {
        val connW = svgContainer.clientWidth.toDouble()
        val viewH = svgContainer.clientHeight.toDouble()
        if (connW <= 0 || viewH <= 0) return
        svg.setAttribute("width", "$connW"); svg.setAttribute("height", "$viewH")
        svg.setAttribute("viewBox", "0 0 $connW $viewH")
        while (svg.firstChild != null) svg.removeChild(svg.firstChild!!)
        val leftScroll = leftPane.scrollTop.toDouble(); val rightScroll = rightPane.scrollTop.toDouble()
        val centerY = viewH / 2.0

        data class RegionDraw(val leftTop: Double, val leftBot: Double, val rightTop: Double, val rightBot: Double, val type: String, val distFromCenter: Double)
        val candidates = mutableListOf<RegionDraw>()
        for (region in regions) {
            val lt = (region.oldStart - 1) * lh - leftScroll
            val lb = if (region.oldEnd >= region.oldStart) region.oldEnd * lh - leftScroll else lt + lh * 0.5
            val rt = (region.newStart - 1) * lh - rightScroll
            val rb = if (region.newEnd >= region.newStart) region.newEnd * lh - rightScroll else rt + lh * 0.5
            if (lb < 0 && rb < 0) continue; if (lt > viewH && rt > viewH) continue
            val midL = (lt + lb) / 2.0; val midR = (rt + rb) / 2.0
            val dist = minOf(kotlin.math.abs(midL - centerY), kotlin.math.abs(midR - centerY))
            candidates.add(RegionDraw(lt, lb, rt, rb, region.type, dist))
        }
        candidates.sortBy { it.distFromCenter }
        val maxDraw = 5
        for ((idx, rd) in candidates.take(maxDraw).withIndex()) {
            val fade = if (candidates.size <= 1) 1.0 else (1.0 - idx.toDouble() / maxDraw).coerceIn(0.3, 1.0)
            val fill = when (rd.type) {
                "del" -> "rgba(180, 60, 55, ${0.35 * fade})"; "add" -> "rgba(55, 140, 65, ${0.30 * fade})"
                else -> "rgba(170, 130, 50, ${0.30 * fade})"
            }
            val stroke = when (rd.type) {
                "del" -> "rgba(180, 60, 55, ${0.55 * fade})"; "add" -> "rgba(55, 140, 65, ${0.50 * fade})"
                else -> "rgba(170, 130, 50, ${0.50 * fade})"
            }
            val cx1 = connW * 0.35; val cx2 = connW * 0.65
            val path = document.createElementNS(svgNs, "path")
            val d = buildString {
                append("M 0 ${rd.leftTop} "); append("C $cx1 ${rd.leftTop} $cx2 ${rd.rightTop} $connW ${rd.rightTop} ")
                append("L $connW ${rd.rightBot} "); append("C $cx2 ${rd.rightBot} $cx1 ${rd.leftBot} 0 ${rd.leftBot} "); append("Z")
            }
            path.setAttribute("d", d); path.setAttribute("fill", fill)
            path.setAttribute("stroke", stroke); path.setAttribute("stroke-width", "1")
            svg.appendChild(path)
        }
    }

    window.requestAnimationFrame { updateConnectors() }
    fun scheduleUpdate() {
        if (!rafPending) { rafPending = true; window.requestAnimationFrame { rafPending = false; updateConnectors() } }
    }

    var syncing = false
    fun syncLeftFromRight() {
        if (syncing) return; syncing = true
        val rightTop = rightPane.scrollTop.toDouble()
        val halfView = rightPane.clientHeight / 2.0
        val leftCenter = mapRightToLeft(rightTop + halfView)
        val leftTarget = leftCenter - halfView
        val leftMax = (leftPane.scrollHeight - leftPane.clientHeight).toDouble()
        leftPane.scrollTop = leftTarget.coerceIn(0.0, if (leftMax > 0) leftMax else 0.0)
        syncing = false
    }
    rightPane.addEventListener("scroll", { _ -> syncLeftFromRight(); scheduleUpdate() })
    leftPane.addEventListener("scroll", { _ -> scheduleUpdate() })
    leftPane.asDynamic().addEventListener("wheel", { ev: dynamic ->
        ev.preventDefault()
        rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
        leftPane.scrollLeft = leftPane.scrollLeft + (ev.deltaX as Number).toDouble()
    }, json("passive" to false))
    svgContainer.asDynamic().addEventListener("wheel", { ev: dynamic ->
        ev.preventDefault()
        rightPane.scrollTop = rightPane.scrollTop + (ev.deltaY as Number).toDouble()
    }, json("passive" to false))
    window.requestAnimationFrame { syncLeftFromRight() }
    window.addEventListener("resize", { _ -> syncLeftFromRight(); scheduleUpdate() })
}
