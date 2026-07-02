/**
 * Tests for the program-set terminal title feature (OSC 0/2 → pane title):
 *  - [sanitizeProgramTitle] — untrusted-payload cleaning;
 *  - [computeLeafTitle] — title precedence (manual rename > program title >
 *    cwd > fallback);
 *  - [PaneManager.applyProgramTitle] — applying/clearing a title on the
 *    pane(s) backed by a session, without ever overriding a manual rename;
 *  - [OscScanner] — OSC 0/2 title extraction from a raw byte stream,
 *    including sequences split across feed() calls, UTF-8 titles, and
 *    non-interference with the OSC 7 cwd path.
 */
package se.soderbjorn.termtastic

import se.soderbjorn.termtastic.pty.OscScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ProgramTitleTest {

    // ---------------------------------------------------------------- helpers

    private fun leaf(
        id: String = "p1",
        sessionId: String = "s1",
        title: String = "fallback",
        customName: String? = null,
        programTitle: String? = null,
        cwd: String? = null,
    ) = LeafNode(
        id = id,
        sessionId = sessionId,
        title = title,
        customName = customName,
        programTitle = programTitle,
        cwd = cwd,
        content = TerminalContent(sessionId),
    )

    private fun cfg(vararg leaves: LeafNode) = WindowConfig(
        tabs = listOf(
            TabConfig(
                id = "t1",
                title = "Tab 1",
                panes = leaves.map { l ->
                    Pane(leaf = l, x = 0.0, y = 0.0, width = 0.5, height = 0.5, z = 1L)
                },
            )
        ),
    )

    private fun WindowConfig.leafById(id: String): LeafNode =
        tabs.flatMap { it.panes }.map { it.leaf }.first { it.id == id }

    // --------------------------------------------------- sanitizeProgramTitle

    @Test
    fun sanitize_keeps_a_normal_title_verbatim() {
        assertEquals("Fix Login Bug", sanitizeProgramTitle("Fix Login Bug"))
    }

    @Test
    fun sanitize_strips_html_significant_characters() {
        assertEquals("scriptalert(x)/script", sanitizeProgramTitle("""<script>alert("x")</script>"""))
    }

    @Test
    fun sanitize_replaces_control_characters_and_collapses_whitespace() {
        assertEquals("a b c", sanitizeProgramTitle("a\u0007b\t\t c"))
    }

    @Test
    fun sanitize_uses_first_nonempty_line_only() {
        assertEquals("first", sanitizeProgramTitle("\n  first\nsecond"))
    }

    @Test
    fun sanitize_caps_length() {
        val long = "x".repeat(300)
        assertEquals(80, sanitizeProgramTitle(long)!!.length)
    }

    @Test
    fun sanitize_never_splits_a_surrogate_pair_at_the_cap() {
        // 79 ASCII chars, then an astral emoji whose high surrogate is the
        // 80th UTF-16 unit — a naive take(80) would keep a lone surrogate.
        val result = sanitizeProgramTitle("x".repeat(79) + "😀")!!
        assertEquals("x".repeat(79), result)
    }

    @Test
    fun sanitize_strips_c1_controls_and_bidi_overrides() {
        // U+009B (8-bit CSI) and U+202E (RTL override) are invisible /
        // spoofing-capable and must not survive into a broadcast title.
        assertEquals("a b", sanitizeProgramTitle("a\u009Bb"))
        assertEquals("gpj .exe", sanitizeProgramTitle("gpj\u202E.exe"))
    }

    @Test
    fun sanitize_returns_null_for_empty_or_blank() {
        assertNull(sanitizeProgramTitle(""))
        assertNull(sanitizeProgramTitle("   "))
        assertNull(sanitizeProgramTitle("\u0007\u0008"))
    }

    // ------------------------------------------------------- computeLeafTitle

    @Test
    fun custom_name_beats_program_title_and_cwd() {
        assertEquals(
            "my name",
            computeLeafTitle("my name", "Fix Login Bug", "/tmp/project", "fb"),
        )
    }

    @Test
    fun program_title_beats_cwd() {
        assertEquals(
            "Fix Login Bug",
            computeLeafTitle(null, "Fix Login Bug", "/tmp/project", "fb"),
        )
    }

    @Test
    fun cwd_beats_fallback_and_blank_program_title_is_ignored() {
        assertEquals("/tmp/project", computeLeafTitle(null, null, "/tmp/project", "fb"))
        assertEquals("/tmp/project", computeLeafTitle(null, "  ", "/tmp/project", "fb"))
        assertEquals("fb", computeLeafTitle(null, null, null, "fb"))
    }

    @Test
    fun leaf_overload_reads_the_leaf_fields() {
        val l = leaf(programTitle = "Fix Login Bug", cwd = "/tmp/project")
        assertEquals("Fix Login Bug", computeLeafTitle(l))
    }

    // ---------------------------------------- PaneManager.applyProgramTitle

    @Test
    fun apply_sets_program_title_and_recomputes_title() {
        val out = PaneManager.applyProgramTitle(cfg(leaf(cwd = "/tmp/project")), "s1", "Fix Login Bug")
        assertNotNull(out)
        val l = out.leafById("p1")
        assertEquals("Fix Login Bug", l.programTitle)
        assertEquals("Fix Login Bug", l.title)
    }

    @Test
    fun apply_never_changes_the_visible_title_of_a_manually_renamed_pane() {
        val out = PaneManager.applyProgramTitle(
            cfg(leaf(customName = "my name", title = "my name", cwd = "/tmp/project")),
            "s1",
            "Fix Login Bug",
        )
        assertNotNull(out)
        val l = out.leafById("p1")
        // Recorded, so it resurfaces if the manual name is later cleared…
        assertEquals("Fix Login Bug", l.programTitle)
        // …but the visible title stays the user's.
        assertEquals("my name", l.title)
    }

    @Test
    fun clearing_the_manual_name_falls_back_to_the_program_title() {
        val named = PaneManager.applyProgramTitle(
            cfg(leaf(customName = "my name", title = "my name", cwd = "/tmp/project")),
            "s1",
            "Fix Login Bug",
        )!!
        val cleared = PaneManager.renamePane(named, "p1", "")
        assertNotNull(cleared)
        assertEquals("Fix Login Bug", cleared.leafById("p1").title)
    }

    @Test
    fun empty_title_clears_back_to_the_cwd_title() {
        val titled = PaneManager.applyProgramTitle(cfg(leaf(cwd = "/tmp/project")), "s1", "Fix Login Bug")!!
        val out = PaneManager.applyProgramTitle(titled, "s1", "")
        assertNotNull(out)
        val l = out.leafById("p1")
        assertNull(l.programTitle)
        assertEquals("/tmp/project", l.title)
    }

    @Test
    fun apply_is_a_noop_for_unknown_session_or_unchanged_title() {
        val base = cfg(leaf(cwd = "/tmp/project"))
        assertNull(PaneManager.applyProgramTitle(base, "s999", "Fix Login Bug"))
        val titled = PaneManager.applyProgramTitle(base, "s1", "Fix Login Bug")!!
        assertNull(PaneManager.applyProgramTitle(titled, "s1", "Fix Login Bug"))
    }

    @Test
    fun apply_titles_every_pane_linked_to_the_session() {
        val out = PaneManager.applyProgramTitle(
            cfg(leaf(id = "p1", cwd = "/tmp/a"), leaf(id = "p2", cwd = "/tmp/b")),
            "s1",
            "Fix Login Bug",
        )
        assertNotNull(out)
        assertEquals("Fix Login Bug", out.leafById("p1").title)
        assertEquals("Fix Login Bug", out.leafById("p2").title)
    }

    @Test
    fun clear_sweep_reverts_all_program_titles_to_cwd_names() {
        val titled = PaneManager.applyProgramTitle(
            cfg(
                leaf(id = "p1", sessionId = "s1", cwd = "/tmp/a"),
                leaf(id = "p2", sessionId = "s2", customName = "my name", title = "my name", cwd = "/tmp/b"),
            ),
            "s1",
            "Fix Login Bug",
        )!!
        val cleared = PaneManager.clearProgramTitles(titled)
        assertNotNull(cleared)
        assertEquals("/tmp/a", cleared.leafById("p1").title)
        assertNull(cleared.leafById("p1").programTitle)
        // Untouched panes keep their state; nothing-to-clear is a no-op.
        assertEquals("my name", cleared.leafById("p2").title)
        assertNull(PaneManager.clearProgramTitles(cleared))
    }

    // ------------------------------------------------ OscScanner title capture

    private fun scannerCapturing(
        titles: MutableList<String>,
        cwds: MutableList<String> = mutableListOf(),
    ) = OscScanner(onCwd = { cwds.add(it) }, onTitle = { titles.add(it) })

    private fun OscScanner.feedText(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun osc0_sets_the_title() {
        val titles = mutableListOf<String>()
        scannerCapturing(titles).feedText("\u001B]0;Fix Login Bug\u0007")
        assertEquals(listOf("Fix Login Bug"), titles)
    }

    @Test
    fun osc2_with_st_terminator_sets_the_title() {
        val titles = mutableListOf<String>()
        scannerCapturing(titles).feedText("\u001B]2;Hello World\u001B\\")
        assertEquals(listOf("Hello World"), titles)
    }

    @Test
    fun utf8_title_decodes_correctly() {
        val titles = mutableListOf<String>()
        scannerCapturing(titles).feedText("\u001B]0;✳ Fixa åäö\u0007")
        assertEquals(listOf("✳ Fixa åäö"), titles)
    }

    @Test
    fun empty_title_fires_with_empty_string() {
        val titles = mutableListOf<String>()
        scannerCapturing(titles).feedText("\u001B]0;\u0007")
        assertEquals(listOf(""), titles)
    }

    @Test
    fun sequence_split_across_feeds_still_fires() {
        val titles = mutableListOf<String>()
        val scanner = scannerCapturing(titles)
        scanner.feedText("\u001B]0;Fix Lo")
        scanner.feedText("gin Bug\u0007")
        assertEquals(listOf("Fix Login Bug"), titles)
    }

    @Test
    fun osc7_still_reports_cwd_and_does_not_fire_title() {
        val titles = mutableListOf<String>()
        val cwds = mutableListOf<String>()
        scannerCapturing(titles, cwds).feedText("\u001B]7;file://localhost/tmp/pro%20ject\u0007")
        assertEquals(listOf("/tmp/pro ject"), cwds)
        assertEquals(emptyList(), titles)
    }

    @Test
    fun osc1_icon_name_and_plain_output_fire_nothing() {
        val titles = mutableListOf<String>()
        val scanner = scannerCapturing(titles)
        scanner.feedText("\u001B]1;icon only\u0007")
        scanner.feedText("just some regular output\r\nmore\r\n")
        assertEquals(emptyList(), titles)
    }
}
