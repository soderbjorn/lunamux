/**
 * Tests for [ClaudeUsageMonitor]'s parsing of `claude -p /usage --output-format json`:
 *  - [ClaudeUsageMonitor.usageReportFromJson] — taking the report text out of
 *    the JSON envelope, and refusing error results and non-JSON output;
 *  - [ClaudeUsageMonitor.parseUsageReport] — the 2026-11 report (one
 *    `<label>: N% used · resets <when>` line per limit, then an advisory
 *    "What's contributing" section), per-model weekly rows including the
 *    legacy Sonnet mirror and " only" suffix, the extra-usage line, and
 *    reports without a session row (→ null).
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeUsageMonitorTest {

    /** The report Claude Code 2.1.318 prints for a subscription login. */
    private val report = """
        You are currently using your subscription to power your Claude Code usage

        Current session: 25% used · resets Nov 5 at 12am (Europe/Helsinki)
        Current week (all models): 32% used · resets Nov 9 at 12am (Europe/Helsinki)
        Current week (Fable): 0% used · resets Nov 9 at 12am (Europe/Helsinki)

        What's contributing to your usage?
        Approximate · sessions on this machine only · shares overlap

        Last 24h · 3215 requests · 38 sessions
          40%  Start fresh when you switch tasks · run /clear
          25%  Run /feature-implement on a cheaper model
          Top skills: /feature-implement 25%, /feature-plan 12%
    """.trimIndent()

    @Test
    fun takesTheReportOutOfTheJsonEnvelope() {
        val out = """{"is_error":false,"num_turns":0,"total_cost_usd":0,"subtype":"success",""" +
            """"result":"Current session: 25% used · resets 12am\nCurrent week (all models): 32% used"}"""
        assertEquals(
            "Current session: 25% used · resets 12am\nCurrent week (all models): 32% used",
            ClaudeUsageMonitor.usageReportFromJson(out),
        )
    }

    @Test
    fun refusesErrorResultsAndNonJsonOutput() {
        assertNull(ClaudeUsageMonitor.usageReportFromJson("""{"is_error":true,"result":"Not logged in"}"""))
        assertNull(ClaudeUsageMonitor.usageReportFromJson("Error: unknown option '--no-session-persistence'"))
        assertNull(ClaudeUsageMonitor.usageReportFromJson("""{"is_error":false}"""))
    }

    @Test
    fun parsesTheReport() {
        val parsed = ClaudeUsageMonitor.parseUsageReport(report)
        assertNotNull(parsed)
        assertEquals(25, parsed.sessionPercent)
        assertEquals("Nov 5 at 12am (Europe/Helsinki)", parsed.sessionResetTime)
        assertEquals(32, parsed.weeklyAllPercent)
        assertEquals("Nov 9 at 12am (Europe/Helsinki)", parsed.weeklyAllResetTime)
        assertEquals(listOf(ClaudeModelUsage("Fable", 0, "Nov 9 at 12am (Europe/Helsinki)")), parsed.modelUsages)
        // No Sonnet row in this report — the legacy mirror stays 0.
        assertEquals(0, parsed.weeklySonnetPercent)
        assertFalse(parsed.extraUsageEnabled)
        assertNull(parsed.extraUsageInfo)
    }

    @Test
    fun parsesMultipleModelRowsInOrderAndMirrorsSonnet() {
        val text = """
            Current session: 10% used · resets 3:30pm
            Current week (all models): 55% used · resets Jul 6 at 11am
            Current week (Fable): 70% used · resets Jul 6 at 11am
            Current week (Sonnet only): 41% used
        """.trimIndent()
        val parsed = ClaudeUsageMonitor.parseUsageReport(text)
        assertNotNull(parsed)
        assertEquals(listOf("Fable", "Sonnet"), parsed.modelUsages.map { it.label })
        assertEquals(listOf(70, 41), parsed.modelUsages.map { it.percent })
        assertEquals("", parsed.modelUsages[1].resetTime)
        assertEquals(41, parsed.weeklySonnetPercent)
    }

    @Test
    fun readsTheExtraUsageLine() {
        val off = ClaudeUsageMonitor.parseUsageReport("Current session: 1% used\nExtra usage: not enabled")
        assertNotNull(off)
        assertFalse(off.extraUsageEnabled)

        val on = ClaudeUsageMonitor.parseUsageReport("Current session: 1% used\nExtra usage: \$4.20 of \$50 spent")
        assertNotNull(on)
        assertTrue(on.extraUsageEnabled)
        assertEquals("\$4.20 of \$50 spent", on.extraUsageInfo)
    }

    @Test
    fun sessionOnlyReportParsesWithEmptyWeeklyRows() {
        val parsed = ClaudeUsageMonitor.parseUsageReport("Current session: 16% used · resets 3:30pm")
        assertNotNull(parsed)
        assertEquals(16, parsed.sessionPercent)
        assertEquals(0, parsed.weeklyAllPercent)
        assertTrue(parsed.modelUsages.isEmpty())
    }

    @Test
    fun returnsNullWithoutASessionRow() {
        assertNull(ClaudeUsageMonitor.parseUsageReport("You are using an API key; usage limits do not apply."))
    }
}
