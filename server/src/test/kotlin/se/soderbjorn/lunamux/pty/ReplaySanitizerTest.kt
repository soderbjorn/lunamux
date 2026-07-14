/**
 * Tests for [ReplaySanitizer] — the replay-path filter that strips terminal
 * *query* escape sequences from recorded PTY output so a replaying client
 * never answers them into the live shell:
 *  - every query family is stripped (DSR/CPR, DA1/2/3, XTVERSION, DECRQM,
 *    kitty keyboard query, OSC `?` reads, XTGETTCAP, DECRQSS);
 *  - lookalike non-queries survive byte-for-byte (DECSCUSR, DECSCL, OSC
 *    title/cwd/color *sets*, sixel, DECRST, SGR, plain UTF-8 text);
 *  - ring-buffer boundary rules hold (truncated head passed through,
 *    incomplete tail preserved, idempotence, empty input).
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReplaySanitizerTest {

    private val esc = "\u001b"
    private val bel = "\u0007"
    private val st = "\u001b\\"

    private fun strip(s: String): String =
        ReplaySanitizer.stripQueries(s.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

    private fun assertStripped(sequence: String) {
        assertEquals("beforeafter", strip("before${sequence}after"), "expected $sequence to be stripped")
    }

    private fun assertKept(sequence: String) {
        val input = "before${sequence}after"
        assertEquals(input, strip(input), "expected $sequence to survive")
    }

    // ------------------------------------------------------------ stripped

    @Test
    fun stripsDeviceStatusReports() {
        assertStripped("$esc[6n")        // CPR
        assertStripped("$esc[5n")        // DSR status
        assertStripped("$esc[?6n")       // DECXCPR
        assertStripped("$esc[?15n")      // printer status
        assertStripped("$esc[?25n")      // UDK status
    }

    @Test
    fun stripsDeviceAttributeQueries() {
        assertStripped("$esc[c")         // DA1
        assertStripped("$esc[0c")        // DA1 explicit
        assertStripped("$esc[>c")        // DA2
        assertStripped("$esc[>0c")
        assertStripped("$esc[=c")        // DA3
    }

    @Test
    fun stripsXtversionAndDecrqmAndKittyQuery() {
        assertStripped("$esc[>q")        // XTVERSION
        assertStripped("$esc[>0q")
        assertStripped("$esc[?2004\$p")  // DECRQM (private mode)
        assertStripped("$esc[4\$p")      // DECRQM (ANSI mode)
        assertStripped("$esc[?u")        // kitty keyboard query
    }

    @Test
    fun stripsOscReadQueries() {
        assertStripped("$esc]10;?$bel")      // foreground color query
        assertStripped("$esc]11;?$st")       // background color query, ST-terminated
        assertStripped("$esc]12;?$bel")      // cursor color query
        assertStripped("$esc]4;5;?$bel")     // palette entry query
        assertStripped("$esc]52;c;?$bel")    // clipboard read
    }

    @Test
    fun stripsDcsQueries() {
        assertStripped("${esc}P+q544e$st")   // XTGETTCAP
        assertStripped("${esc}P\$qm$st")     // DECRQSS
    }

    @Test
    fun stripsConsecutiveQueriesCompletely() {
        assertEquals("ab", strip("a$esc[6n$esc]11;?$bel$esc[>c${esc}P+q6b$st$esc[?u" + "b"))
    }

    // ---------------------------------------------------------------- kept

    @Test
    fun keepsCsiLookalikes() {
        assertKept("$esc[4 q")       // DECSCUSR (space intermediate)
        assertKept("$esc[62\"p")     // DECSCL (quote intermediate)
        assertKept("$esc[u")         // SCORC restore cursor
        assertKept("$esc[>1u")       // kitty push flags
        assertKept("$esc[?1049l")    // DECRST
        assertKept("$esc[?2004h")    // DECSET
        assertKept("$esc[38;5;196m") // SGR
        assertKept("$esc[2J$esc[H")  // clear + home
    }

    @Test
    fun keepsOscSetsAndLabels() {
        assertKept("$esc]0;my title$bel")            // title set
        assertKept("$esc]7;file://host/tmp$bel")     // cwd report
        assertKept("$esc]11;rgb:1e/1e/2e$bel")       // background color SET
        assertKept("$esc]4;5;rgb:aa/bb/cc$bel")      // palette SET
        assertKept("$esc]104$bel")                   // color reset
        assertKept("$esc]8;;https://x.test$bel")     // hyperlink (empty segments)
    }

    @Test
    fun keepsNonQueryDcsAndOtherEscapes() {
        assertKept("${esc}Pq#0;2;0;0;0#0~~\$-$st")   // sixel
        assertKept("${esc}P0;1q~~$st")               // sixel with params
        assertKept("${esc}c")                        // RIS
        assertKept("$esc>")                          // DECKPNM
        assertKept("${esc}7${esc}8")                 // save/restore cursor
    }

    @Test
    fun keepsPlainTextByteForByte() {
        val mixed = "plain text, UTF-8 →→ äöå 🚀, and\r\nnewlines\ttabs"
        val bytes = mixed.toByteArray(Charsets.UTF_8)
        assertContentEquals(bytes, ReplaySanitizer.stripQueries(bytes))
    }

    @Test
    fun keepsRestoreModeResetSequences() {
        // The exact bytes TerminalSession stamps into restored rings — these
        // must never be stripped or the issue-#91 mode neutralization breaks.
        val reset = "$esc[?9;1000;1001;1002;1003;1005;1006;1015l$esc[?1004l$esc[?2004l$esc[?1l$esc[?1049l$esc>"
        assertKept(reset)
        assertKept("$esc[?25h") // SHOW_CURSOR_SUFFIX
    }

    // ------------------------------------------------------------ boundary

    @Test
    fun preservesIncompleteTrailingSequences() {
        // The live stream will complete these right after the replay; the
        // emitting program is legitimately waiting for the answer.
        assertEquals("x$esc[6", strip("x$esc[6"))
        assertEquals("x$esc[?200", strip("x$esc[?200"))
        assertEquals("x$esc]11;?", strip("x$esc]11;?"))
        assertEquals("x${esc}P+q54", strip("x${esc}P+q54"))
        assertEquals("x$esc", strip("x$esc"))
    }

    @Test
    fun passesThroughTruncatedHeadFromRingWrap() {
        // A ring that overwrote the ESC introducer leaves an inert tail; the
        // filter must not misparse or mangle it.
        val truncated = "]11;?${bel}normal text"
        assertEquals(truncated, strip(truncated))
        val truncatedCsi = "?2004\$pmore"
        assertEquals(truncatedCsi, strip(truncatedCsi))
    }

    @Test
    fun isIdempotent() {
        val input = "a$esc[6n$esc]0;t$bel$esc[38;5;1mz$esc]11;?$bel$esc[4 q$esc[6".toByteArray(Charsets.UTF_8)
        val once = ReplaySanitizer.stripQueries(input)
        val twice = ReplaySanitizer.stripQueries(once)
        assertContentEquals(once, twice)
    }

    @Test
    fun handlesEmptyInput() {
        assertContentEquals(ByteArray(0), ReplaySanitizer.stripQueries(ByteArray(0)))
    }

    @Test
    fun givesUpOnOversizedStringPayloadsWithoutMangling() {
        // An OSC payload longer than the scan cap is not a query; the
        // introducer and payload must pass through untouched.
        val huge = "$esc]1337;File=inline:" + "A".repeat(8192) + bel
        assertEquals(huge, strip(huge))
    }
}
