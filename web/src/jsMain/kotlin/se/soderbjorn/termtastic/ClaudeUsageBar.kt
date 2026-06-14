/**
 * Claude API usage bar component for the Termtastic web frontend.
 *
 * Renders a horizontal status bar showing Claude Code session and weekly usage
 * percentages, with color-coded severity indicators (ok/warn/critical) and
 * a refresh button. The bar is updated whenever a [WindowEnvelope.ClaudeUsage]
 * envelope arrives from the server via [connectWindow].
 *
 * @see updateClaudeUsageBadge
 * @see WindowConnection
 */
package se.soderbjorn.termtastic

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
 * Builds an HTML snippet for a single usage metric (e.g. "Session 42%").
 *
 * @param label the metric label (e.g. "Session", "Week", "Sonnet")
 * @param pct the usage percentage (0-100)
 * @param resetTime optional raw reset time string to format and display
 * @return an HTML string containing the styled usage item
 */
private fun usageItem(label: String, pct: Int, resetTime: String = ""): String {
    val reset = formatResetTime(resetTime)
    val resetHtml = if (reset.isNotBlank()) """ <span class="usage-reset">$reset</span>""" else ""
    return """<span class="usage-item">$label <span class="usage-pct ${pctClass(pct)}">${pct}%</span>$resetHtml</span>"""
}

/**
 * Updates the Claude usage bar DOM element with the latest usage data.
 *
 * Called by [connectWindow] when a [WindowEnvelope.ClaudeUsage] envelope is received.
 * Extracts session, weekly, and Sonnet usage percentages from the dynamic payload,
 * renders them as color-coded badges, and attaches a refresh button that sends
 * [WindowCommand.RefreshUsage] to the server.
 *
 * @param usage a dynamic object containing sessionPercent, weeklyAllPercent,
 *              weeklySonnetPercent, reset times, and fetchedAt fields; or null to hide the bar
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
        return
    }
    val sessionPct = (usage.sessionPercent as? Int) ?: 0
    val sessionReset = (usage.sessionResetTime as? String) ?: ""
    val weeklyAllPct = (usage.weeklyAllPercent as? Int) ?: 0
    val weeklyAllReset = (usage.weeklyAllResetTime as? String) ?: ""
    val weeklySonnetPct = (usage.weeklySonnetPercent as? Int) ?: 0
    val fetchedAt = (usage.fetchedAt as? String) ?: ""

    val ts = formatFetchedAt(fetchedAt)

    var html = """<span class="usage-item" style="font-weight:600;">Claude Code usage:</span>"""
    html += usageItem("Session", sessionPct, sessionReset)
    html += usageItem("Week", weeklyAllPct, weeklyAllReset)
    html += usageItem("Sonnet", weeklySonnetPct)
    if (ts.isNotBlank()) {
        html += """<span class="usage-item">$ts</span>"""
    }
    html += """<button class="usage-refresh-btn" title="Refresh usage">&#x21bb;</button>"""

    bar.innerHTML = html
    bar.querySelector(".usage-refresh-btn")?.addEventListener("click", { launchCmd(WindowCommand.RefreshUsage) })
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
