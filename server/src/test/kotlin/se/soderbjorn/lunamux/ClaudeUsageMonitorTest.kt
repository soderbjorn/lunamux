/**
 * Tests for [ClaudeUsageMonitor.parseUsageScreen] — scraping the Claude CLI's
 * `/usage` screen into a [ClaudeUsageData]:
 *  - the 2026-07 screen layout, where the model-specific weekly section is
 *    labelled per model (e.g. "Current week (Fable)"), reset lines carry a
 *    timezone suffix, and long advisory sections follow the bars;
 *  - the older layout with a "Current week (Sonnet)" section, including the
 *    legacy [ClaudeUsageData.weeklySonnetPercent] mirror;
 *  - screens with multiple model-specific sections at once;
 *  - degenerate screens (no session section → null).
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeUsageMonitorTest {

    /** The 2026-07 screen: per-model "Fable" section, TZ suffixes, advisory tail. */
    private val newFormatScreen = """
        Claude Code v2.1.201

        Settings  Status  Config  Usage  Stats

        Session

        Total cost:            $0.0000
        Total duration (API):  0s
        Total duration (wall): 1s
        Total code changes:    0 lines added, 0 lines removed
        Usage:                 0 input, 0 output, 0 cache read, 0 cache
        write

        Current session
        ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  16% used
        Resets 3:30pm (Europe/Stockholm)

        Current week (all models)
        ██████████████████████░░░░░░░░░░░░░░░░░░  55% used
        Resets Jul 6 at 11am (Europe/Stockholm)

        Current week (Fable)
        ████████████████████████████░░░░░░░░░░░░  70% used
        Resets Jul 6 at 11am (Europe/Stockholm)

        What's contributing to your limits usage?
        Approximate, based on local sessions on this machine — does not
        include other devices or claude.ai

        54% of your usage came from subagent-heavy sessions
         Each subagent runs its own requests.

        Skills                 % of usage
        /verify                        1%
    """.trimIndent()

    /** The pre-2026-07 screen with a Sonnet-specific weekly section. */
    private val oldFormatScreen = """
        Current session
        ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  37% used
        Resets Jun 11, 1:00 AM

        Current week (all models)
        ██████████████████████░░░░░░░░░░░░░░░░░░  62% used
        Resets Jun 16, 9:00 AM

        Current week (Sonnet)
        ████████████████░░░░░░░░░░░░░░░░░░░░░░░░  41% used

        Extra usage
        Extra usage is not enabled
    """.trimIndent()

    @Test
    fun parsesNewFormatWithFableSection() {
        val parsed = ClaudeUsageMonitor.parseUsageScreen(newFormatScreen)
        assertNotNull(parsed)
        assertEquals(16, parsed.sessionPercent)
        assertEquals("3:30pm (Europe/Stockholm)", parsed.sessionResetTime)
        assertEquals(55, parsed.weeklyAllPercent)
        assertEquals("Jul 6 at 11am (Europe/Stockholm)", parsed.weeklyAllResetTime)
        assertEquals(listOf(ClaudeModelUsage("Fable", 70, "Jul 6 at 11am (Europe/Stockholm)")), parsed.modelUsages)
        // No Sonnet section on this screen — the legacy mirror stays 0.
        assertEquals(0, parsed.weeklySonnetPercent)
    }

    @Test
    fun parsesOldFormatAndMirrorsSonnetIntoLegacyField() {
        val parsed = ClaudeUsageMonitor.parseUsageScreen(oldFormatScreen)
        assertNotNull(parsed)
        assertEquals(37, parsed.sessionPercent)
        assertEquals(62, parsed.weeklyAllPercent)
        assertEquals(listOf(ClaudeModelUsage("Sonnet", 41, "")), parsed.modelUsages)
        assertEquals(41, parsed.weeklySonnetPercent)
        assertEquals(false, parsed.extraUsageEnabled)
    }

    @Test
    fun parsesMultipleModelSectionsInScreenOrder() {
        val screen = """
            Current session
            ██  10% used
            Resets 3:30pm (Europe/Stockholm)

            Current week (all models)
            ██  55% used
            Resets Jul 6 at 11am (Europe/Stockholm)

            Current week (Fable)
            ██  70% used
            Resets Jul 6 at 11am (Europe/Stockholm)

            Current week (Sonnet)
            ██  41% used
            Resets Jul 6 at 11am (Europe/Stockholm)
        """.trimIndent()
        val parsed = ClaudeUsageMonitor.parseUsageScreen(screen)
        assertNotNull(parsed)
        assertEquals(listOf("Fable", "Sonnet"), parsed.modelUsages.map { it.label })
        assertEquals(listOf(70, 41), parsed.modelUsages.map { it.percent })
        assertEquals(41, parsed.weeklySonnetPercent)
    }

    @Test
    fun stripsLegacyOnlySuffixFromModelLabel() {
        val screen = """
            Current session
            ██  10% used
            Resets 3:30pm

            Current week (all models)
            ██  55% used
            Resets Jul 6 at 11am

            Current week (Sonnet only)
            ██  41% used
        """.trimIndent()
        val parsed = ClaudeUsageMonitor.parseUsageScreen(screen)
        assertNotNull(parsed)
        assertEquals(listOf(ClaudeModelUsage("Sonnet", 41, "")), parsed.modelUsages)
        assertEquals(41, parsed.weeklySonnetPercent)
    }

    @Test
    fun sessionOnlyScreenParsesWithEmptyModelRows() {
        val screen = """
            Current session
            ██  16% used
            Resets 3:30pm (Europe/Stockholm)
        """.trimIndent()
        val parsed = ClaudeUsageMonitor.parseUsageScreen(screen)
        assertNotNull(parsed)
        assertEquals(16, parsed.sessionPercent)
        assertEquals(0, parsed.weeklyAllPercent)
        assertTrue(parsed.modelUsages.isEmpty())
    }

    @Test
    fun returnsNullWithoutSessionSection() {
        assertNull(ClaudeUsageMonitor.parseUsageScreen("Settings  Status  Config"))
    }
}
