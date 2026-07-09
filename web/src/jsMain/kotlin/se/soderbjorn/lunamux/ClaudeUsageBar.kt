/**
 * Claude API usage bar component for the Lunamux web frontend.
 *
 * Renders a compact vertical stack of Claude Code usage rows — one per metric
 * (Session / Week / one row per model-specific weekly limit, e.g. Fable or
 * Sonnet) — for the narrow left-sidebar footer. Each row is
 * a label, a thin severity-coloured progress bar, and the percentage, kept to
 * a single tight line. Hovering anywhere over the bar reveals one consolidated
 * popup ([buildUsagePopupHtml]) that lays out every metric together with its
 * reset limit and the "updated" timestamp — replacing the old per-row native
 * `title` tooltips, which could only show one metric at a time. A small header
 * row carries the "Claude usage" title and a refresh button. The stack is
 * updated whenever a [WindowEnvelope.ClaudeUsage] envelope arrives from the
 * server via [connectWindow].
 *
 * @see updateClaudeUsageBadge
 * @see WindowConnection
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * Maps abbreviated month names to zero-based month indices, used by [formatResetTime]
 * to parse human-readable reset timestamps from the Claude API.
 */
private val MONTH_MAP = mapOf(
    "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
    "may" to 4, "jun" to 5, "jul" to 6, "aug" to 7,
    "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11,
)

/**
 * Returns a CSS class name indicating the severity of a usage percentage.
 *
 * @param pct the usage percentage (0-100)
 * @return one of "usage-critical", "usage-warn", or "usage-ok"
 */
private fun pctClass(pct: Int): String = when {
    pct >= 90 -> "usage-critical"
    pct >= 70 -> "usage-warn"
    else -> "usage-ok"
}

/**
 * Formats an ISO-8601 timestamp into a human-readable "(updated YYYY-MM-DD HH:MM)" string.
 *
 * @param isoString the ISO-8601 date string from the server
 * @return a formatted string like "(updated 2026-04-18 14:30)", or empty string on failure
 */
private fun formatFetchedAt(isoString: String): String {
    if (isoString.isBlank()) return ""
    return try {
        val d = kotlin.js.Date(isoString)
        val year = d.getFullYear()
        val month = (d.getMonth() + 1).toString().padStart(2, '0')
        val day = d.getDate().toString().padStart(2, '0')
        val hour = d.getHours().toString().padStart(2, '0')
        val min = d.getMinutes().toString().padStart(2, '0')
        "(updated $year-$month-$day $hour:$min)"
    } catch (_: Exception) { "" }
}

/**
 * Parses the Claude API reset time string (e.g. "3:00 pm" or "Apr 20 3:00 pm")
 * into a formatted "(resets YYYY-MM-DD HH:MM)" string for display in the usage bar.
 *
 * @param raw the raw reset time string from the Claude API response
 * @return a formatted reset time string, or empty string if parsing fails
 */
private fun formatResetTime(raw: String): String {
    if (raw.isBlank()) return ""
    val timeMatch = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE).find(raw)
        ?: return ""
    var h = timeMatch.groupValues[1].toInt()
    val m = timeMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
    val isPm = timeMatch.groupValues[3].lowercase() == "pm"
    if (isPm && h < 12) h += 12
    if (!isPm && h == 12) h = 0
    val hh = h.toString().padStart(2, '0')
    val mm = m.toString().padStart(2, '0')

    val dateMatch = Regex("""([A-Za-z]{3})\s+(\d{1,2})""").find(raw)
    val now = kotlin.js.Date()
    val year: Int
    val month: Int
    val day: Int
    if (dateMatch != null) {
        month = MONTH_MAP[dateMatch.groupValues[1].lowercase()] ?: now.getMonth()
        day = dateMatch.groupValues[2].toInt()
        year = if (month < now.getMonth() ||
            (month == now.getMonth() && day < now.getDate()))
            now.getFullYear() + 1 else now.getFullYear()
    } else {
        year = now.getFullYear()
        month = now.getMonth()
        day = now.getDate()
    }
    val yyyy = year.toString()
    val mo = (month + 1).toString().padStart(2, '0')
    val dd = day.toString().padStart(2, '0')
    return "(resets $yyyy-$mo-$dd $hh:$mm)"
}

/**
 * Builds an HTML snippet for a single usage row: a label, a thin
 * severity-coloured fill bar, and the percentage. Reset limits and the
 * "updated" timestamp are no longer folded into a per-row `title` tooltip —
 * they live in the consolidated hover popup ([buildUsagePopupHtml]) so the
 * narrow row stays a single tight line.
 *
 * @param label the metric label (e.g. "Session", "Week", "Sonnet")
 * @param pct the usage percentage (0-100); also drives the fill-bar width
 * @return an HTML string containing the styled usage row
 */
private fun usageRow(label: String, pct: Int): String {
    val cls = pctClass(pct)
    val width = pct.coerceIn(0, 100)
    return """<div class="usage-row">""" +
        """<span class="usage-label">$label</span>""" +
        """<span class="usage-bar"><span class="usage-bar-fill $cls" style="width:${width}%"></span></span>""" +
        """<span class="usage-pct $cls">${pct}%</span>""" +
        """</div>"""
}

/**
 * One row of the consolidated hover popup: label, a wider severity-coloured
 * fill bar, the percentage, and the metric's reset limit (when known).
 *
 * @param label the metric label (e.g. "Session", "Week", "Sonnet")
 * @param pct the usage percentage (0-100); drives the fill width + severity
 * @param resetTime raw reset time string from the Claude API; rendered as a
 *   "resets …" cell when parseable, otherwise left blank
 * @return an HTML string of `.usage-popup-*` cells for the popup grid
 * @see buildUsagePopupHtml
 */
private fun usagePopupRow(label: String, pct: Int, resetTime: String): String {
    val cls = pctClass(pct)
    val width = pct.coerceIn(0, 100)
    val reset = formatResetTime(resetTime).removeSurrounding("(", ")")
    val resetCell = if (reset.isNotBlank()) reset else "&ndash;"
    return """<span class="usage-popup-label">$label</span>""" +
        """<span class="usage-popup-bar"><span class="usage-popup-bar-fill $cls" style="width:${width}%"></span></span>""" +
        """<span class="usage-popup-pct $cls">${pct}%</span>""" +
        """<span class="usage-popup-reset">$resetCell</span>"""
}

/**
 * One model-specific weekly usage row as extracted from the envelope's
 * `modelUsages` payload (server-side `ClaudeModelUsage`). The set of rows
 * varies with the CLI version and plan — e.g. just "Fable" today, "Sonnet"
 * on plans without Fable access, or both.
 *
 * @property label the model name shown as the row label (e.g. "Fable")
 * @property pct usage percentage (0-100) for this model's weekly window
 * @property reset raw "Resets ..." text, possibly empty
 */
private data class ModelUsageRow(val label: String, val pct: Int, val reset: String)

/**
 * Extracts the model-specific weekly rows from a dynamic usage payload.
 *
 * Reads the `modelUsages` array when present (servers that send
 * `ClaudeModelUsage` entries). For older servers that predate the field,
 * falls back to synthesizing a "Sonnet" row from the legacy
 * `weeklySonnetPercent` when it is non-zero, so a stale server still shows
 * its one model row instead of nothing.
 *
 * @param usage dynamic usage payload from the [WindowEnvelope.ClaudeUsage] JSON
 * @return the model rows in screen order, possibly empty
 */
private fun extractModelRows(usage: dynamic): List<ModelUsageRow> {
    val rows = mutableListOf<ModelUsageRow>()
    val arr = usage.modelUsages
    if (arr != null && arr != undefined) {
        val len = (arr.length as? Int) ?: 0
        for (i in 0 until len) {
            val entry = arr[i] ?: continue
            val label = (entry.label as? String)?.takeIf { it.isNotBlank() } ?: continue
            val pct = (entry.percent as? Int) ?: 0
            val reset = (entry.resetTime as? String) ?: ""
            rows.add(ModelUsageRow(label, pct, reset))
        }
    }
    if (rows.isEmpty()) {
        val legacySonnet = (usage.weeklySonnetPercent as? Int) ?: 0
        if (legacySonnet > 0) rows.add(ModelUsageRow("Sonnet", legacySonnet, ""))
    }
    return rows
}

/**
 * Builds the inner HTML for the consolidated usage hover popup: a title, a
 * four-column grid with one [usagePopupRow] per metric (Session / Week / one
 * per model row) showing its bar, percentage and reset limit, and an
 * "Updated …" footer. Shown by [ensureUsagePopup]'s hover wiring whenever the
 * pointer is over the usage bar.
 *
 * @param sessionPct session-window usage percentage
 * @param sessionReset raw session reset-time string
 * @param weeklyAllPct weekly all-models usage percentage
 * @param weeklyAllReset raw weekly all-models reset-time string
 * @param modelRows model-specific weekly rows (e.g. Fable / Sonnet)
 * @param fetchedAt ISO-8601 fetch timestamp, rendered in the footer
 * @return the popup's inner HTML string
 */
private fun buildUsagePopupHtml(
    sessionPct: Int,
    sessionReset: String,
    weeklyAllPct: Int,
    weeklyAllReset: String,
    modelRows: List<ModelUsageRow>,
    fetchedAt: String,
): String {
    // Model rows often share the weekly reset window; usagePopupRow renders a
    // dash when their reset text is empty or unparseable.
    val grid = """<div class="usage-popup-grid">""" +
        usagePopupRow("Session", sessionPct, sessionReset) +
        usagePopupRow("Week", weeklyAllPct, weeklyAllReset) +
        modelRows.joinToString("") { usagePopupRow(it.label, it.pct, it.reset) } +
        """</div>"""
    val updated = formatFetchedAt(fetchedAt).removeSurrounding("(", ")")
    val foot = if (updated.isNotBlank()) {
        """<div class="usage-popup-foot">${updated.replaceFirstChar { it.uppercase() }}</div>"""
    } else ""
    return """<div class="usage-popup-title">Claude Code usage</div>$grid$foot"""
}

/**
 * Lazily-created singleton popup element, parented to `document.body` rather
 * than the usage bar itself: the toolkit's `.dt-sidebar` sets
 * `overflow: hidden`, which would clip a popup anchored inside the sidebar
 * footer. A body-level fixed-position element escapes that clip.
 */
private var usagePopupEl: HTMLElement? = null

/** Guards one-time attachment of the hover listeners on the usage bar. */
private var usagePopupWired = false

/**
 * Returns the singleton usage popup element, creating it (and wiring the
 * show/hide-on-hover listeners on [bar]) on first call.
 *
 * The listeners are attached once (guarded by [usagePopupWired]) so repeated
 * [updateClaudeUsageBadge] calls — one per `/usage` poll — don't stack
 * duplicate handlers. On `mouseenter` the popup is positioned just above the
 * bar (clamped into the viewport) and shown; on `mouseleave` it is hidden. A
 * blank popup (no data yet) suppresses the show.
 *
 * @param bar the cached usage-bar element ([usageBar]) that owns the hover.
 * @return the popup element, ready to have its `innerHTML` set.
 */
private fun ensureUsagePopup(bar: HTMLElement): HTMLElement {
    usagePopupEl?.let { return it }
    val el = document.createElement("div") as HTMLElement
    el.className = "usage-popup"
    el.style.display = "none"
    document.body?.appendChild(el)
    usagePopupEl = el

    if (!usagePopupWired) {
        usagePopupWired = true
        bar.addEventListener("mouseenter", {
            val p = usagePopupEl ?: return@addEventListener
            if (p.innerHTML.isBlank()) return@addEventListener
            // Show first so offsetWidth/offsetHeight are measurable, then
            // place above the bar (it sits at the window's bottom-left),
            // clamped so the popup never runs off the viewport edges.
            p.style.display = "block"
            val rect = bar.getBoundingClientRect()
            val maxLeft = (window.innerWidth - p.offsetWidth - 8).coerceAtLeast(8)
            val left = rect.left.toInt().coerceIn(8, maxLeft)
            val top = (rect.top.toInt() - p.offsetHeight - 6).coerceAtLeast(8)
            p.style.left = "${left}px"
            p.style.top = "${top}px"
        })
        bar.addEventListener("mouseleave", {
            usagePopupEl?.style?.display = "none"
        })
    }
    return el
}

/**
 * Updates the Claude usage bar DOM element with the latest usage data.
 *
 * Called by [connectWindow] when a [WindowEnvelope.ClaudeUsage] envelope is received.
 * Extracts session, weekly, and per-model usage percentages from the dynamic payload,
 * renders them as color-coded badges, and attaches a refresh button that sends
 * [WindowCommand.RefreshUsage] to the server.
 *
 * @param usage a dynamic object containing sessionPercent, weeklyAllPercent,
 *              modelUsages (per-model rows, see [extractModelRows]), reset
 *              times, and fetchedAt fields; or null to hide the bar
 * @see connectWindow
 */
fun updateClaudeUsageBadge(usage: dynamic) {
    val bar = usageBar ?: return
    val divider = usageBarDividerEl
    if (usage == null || usage == undefined) {
        // No data at all — keep the bar visible but empty so it still reserves
        // space at the bottom of the window (required by issue #14 so the app
        // logo overlay has a background band to sit against). Hide only the
        // divider: there is nothing to drag-collapse when the bar is empty.
        bar.innerHTML = ""
        bar.className = "claude-usage-bar claude-usage-bar-empty"
        bar.style.display = ""
        divider?.style?.display = "none"
        // Drop any stale popup content so a hover during the empty state
        // shows nothing (ensureUsagePopup's mouseenter suppresses a blank
        // popup) and hide it if it happened to be open.
        usagePopupEl?.let { it.innerHTML = ""; it.style.display = "none" }
        return
    }
    val sessionPct = (usage.sessionPercent as? Int) ?: 0
    val sessionReset = (usage.sessionResetTime as? String) ?: ""
    val weeklyAllPct = (usage.weeklyAllPercent as? Int) ?: 0
    val weeklyAllReset = (usage.weeklyAllResetTime as? String) ?: ""
    val modelRows = extractModelRows(usage)
    val fetchedAt = (usage.fetchedAt as? String) ?: ""

    // Header (title + refresh) then one compact row per metric. Reset limits
    // and the "updated" timestamp are not printed inline — they live in the
    // consolidated hover popup built below.
    var html = """<div class="usage-header">""" +
        """<span class="usage-title">Claude usage</span>""" +
        """<button class="usage-refresh-btn" title="Refresh usage">&#x21bb;</button>""" +
        """</div>"""
    html += usageRow("Session", sessionPct)
    html += usageRow("Week", weeklyAllPct)
    for (row in modelRows) html += usageRow(row.label, row.pct)

    bar.innerHTML = html
    bar.querySelector(".usage-refresh-btn")?.addEventListener("click", { launchCmd(WindowCommand.RefreshUsage) })

    // Consolidated hover popup: every metric + its reset limit + the updated
    // timestamp in one nicely-formatted card, shown whenever the pointer is
    // over the bar. Rebuilt on each envelope; the hover listeners are wired
    // once by ensureUsagePopup.
    ensureUsagePopup(bar).innerHTML = buildUsagePopupHtml(
        sessionPct = sessionPct,
        sessionReset = sessionReset,
        weeklyAllPct = weeklyAllPct,
        weeklyAllReset = weeklyAllReset,
        modelRows = modelRows,
        fetchedAt = fetchedAt,
    )
    // Reset the class list but preserve the user's collapsed preference so a
    // refresh envelope doesn't silently re-expand a bar the user hid.
    bar.className = "claude-usage-bar"
    if (appVm.stateFlow.value.usageBarCollapsed) {
        bar.classList.add("collapsed")
    }
    // Clear the "display:none" that the initial HTML uses before any data has
    // arrived — from here on, the .collapsed class alone controls visibility.
    bar.style.display = ""
    // Divider stays visible whenever data is present, regardless of the
    // user's collapsed preference — that's what they grab to un-collapse.
    divider?.style?.display = ""
}
