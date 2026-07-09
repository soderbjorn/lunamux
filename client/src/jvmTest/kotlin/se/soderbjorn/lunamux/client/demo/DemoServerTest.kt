/**
 * JVM tests for the demo-mode simulation: the [DemoServer] command handling
 * (layout mutations, file-browser and git RPC replies) and the
 * [DemoTerminalSession] line discipline (echo, canned command responses,
 * Ctrl-C behaviour in foreground-program sessions).
 *
 * @see DemoServer
 * @see DemoTerminalSession
 */
package se.soderbjorn.lunamux.client.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.lunamux.FileBrowserContent
import se.soderbjorn.lunamux.GitContent
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests for [DemoServer] and [DemoTerminalSession]. */
class DemoServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repo = WindowStateRepository()
    private val server = DemoServer(scope, repo)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** The fixture config seeds the repository synchronously at construction. */
    @Test
    fun initialConfigIsSeeded() {
        val config = repo.config.value
        requireNotNull(config)
        assertEquals(4, config.tabs.size)
        assertEquals("demo-t1", config.activeTabId)
        assertEquals("working", repo.states.value["demo-s1"])
        // Every terminal pane's session has a transcript spec.
        val sessionIds = config.tabs.flatMap { it.panes }.map { it.leaf.sessionId }.filter { it.isNotEmpty() }
        for (id in sessionIds) {
            server.session(id) // must not fall back to an unseeded shell for fixtures
        }
    }

    /** ApplyLayout rearranges panes with toolkit geometry and updates the preset. */
    @Test
    fun applyLayoutRearranges() = runBlocking {
        server.handle(WindowCommand.ApplyLayout(tabId = "demo-t1", layout = "columns", primaryPaneId = "demo-p2"))
        val tab = repo.config.value!!.tabs.first { it.id == "demo-t1" }
        assertEquals("columns", tab.layoutPreset)
        // Three columns: all panes full height, non-overlapping x ranges.
        assertEquals(3, tab.panes.size)
        for (pane in tab.panes) assertEquals(1.0, pane.height, 1e-9)
        val xs = tab.panes.map { it.x }.sorted()
        assertEquals(3, xs.distinct().size)
        // The primary pane is in the first (leftmost) slot.
        assertEquals(0.0, tab.panes.first { it.leaf.id == "demo-p2" }.x, 1e-9)
    }

    /** Adding a terminal pane allocates a deterministic session with a shell. */
    @Test
    fun addPaneCreatesSession() = runBlocking {
        val before = repo.config.value!!.tabs.first { it.id == "demo-t3" }.panes.size
        server.handle(WindowCommand.AddPaneToTab(tabId = "demo-t3", cwd = null))
        val tab = repo.config.value!!.tabs.first { it.id == "demo-t3" }
        assertEquals(before + 1, tab.panes.size)
        // The new pane is focused (auto re-tiling reassigns z, so z is not
        // a reliable way to find it).
        val newPane = tab.panes.first { it.leaf.id == tab.focusedPaneId }
        assertTrue(newPane.leaf.sessionId.startsWith("demo-s"))
    }

    /**
     * [DemoServer.resetToFixtures] rolls every mutation back to the boot
     * state: layout changes, visitor-created panes (and their sessions), and
     * typed-into terminals all return to the fixtures, and attached clients
     * see the rewind as a live clear-screen + transcript frame.
     */
    @Test
    fun resetToFixturesRestoresBootState() = runBlocking {
        // Mutate everything resettable: layout, a new pane, terminal input.
        server.handle(WindowCommand.ApplyLayout(tabId = "demo-t1", layout = "columns", primaryPaneId = "demo-p2"))
        server.handle(WindowCommand.AddPaneToTab(tabId = "demo-t3", cwd = null))
        val newSessionId = repo.config.value!!.tabs.first { it.id == "demo-t3" }
            .let { tab -> tab.panes.first { it.leaf.id == tab.focusedPaneId }.leaf.sessionId }
        val shell = server.session("demo-s2") // fixture shell session, sits at a prompt
        // Echoes come back one character per frame — accumulate until the
        // whole typed line has round-tripped through the input coroutine.
        val typed = StringBuilder()
        val typedFrame = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            shell.output().first { typed.append(it.decodeToString()); typed.contains("garbage") }
        }
        shell.inputText("garbage")
        withTimeout(5_000) { typedFrame.await() }

        // An attached client sees the rewind frame (clear + fixture transcript).
        val rewindFrame = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            shell.output().first { it.decodeToString().startsWith("\u001b[3J\u001b[2J\u001b[H") }
        }
        server.resetToFixtures()

        assertEquals(DemoFixtures.initialConfig(), repo.config.value)
        assertEquals(DemoFixtures.initialStates, repo.states.value)
        withTimeout(5_000) { rewindFrame.await() }
        // The runtime pane's session was reaped; the fixture session survived
        // as the same object (attached transports keep streaming).
        assertTrue(server.session("demo-s2") === shell)
        // A fresh subscriber's snapshot is the clean fixture transcript again.
        val snapshot = withTimeout(5_000) { shell.output().first() }.decodeToString()
        assertTrue(!snapshot.contains("garbage"))
        assertTrue(newSessionId.isNotEmpty())
    }

    /**
     * Subscribe to the envelope stream *before* dispatching [send], then
     * return the first envelope matching [predicate]. The subscription is
     * started UNDISPATCHED so it is registered before the command runs —
     * the demo envelope flow has no replay, exactly like the real socket.
     */
    private suspend fun awaitEnvelope(
        predicate: (WindowEnvelope) -> Boolean,
        send: suspend () -> Unit,
    ): WindowEnvelope = withTimeout(5_000) {
        kotlinx.coroutines.coroutineScope {
            val deferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                server.envelopes.first(predicate)
            }
            send()
            deferred.await()
        }
    }

    /** FileBrowserListDir answers with the fixture entries for the root. */
    @Test
    fun fileBrowserListsRoot() = runBlocking {
        val reply = awaitEnvelope({ it is WindowEnvelope.FileBrowserDir }) {
            server.handle(WindowCommand.FileBrowserListDir(paneId = "demo-p8", dirRelPath = ""))
        } as WindowEnvelope.FileBrowserDir
        assertEquals("", reply.dirRelPath)
        assertTrue(reply.entries.any { it.name == "README.md" })
        assertTrue(reply.entries.first().isDir) // directories sort first
    }

    /** GitDiff answers with fixture hunks and records the selection. */
    @Test
    fun gitDiffRepliesAndSelects() = runBlocking {
        val reply = awaitEnvelope({ it is WindowEnvelope.GitDiffResult }) {
            server.handle(WindowCommand.GitDiff(paneId = "demo-p3", filePath = "src/main.s"))
        } as WindowEnvelope.GitDiffResult
        assertTrue(reply.hunks.isNotEmpty())
        assertEquals(null, reply.language) // 68k assembly: no tokeniser, plain text
        val gitPane = repo.config.value!!.tabs.flatMap { it.panes }.first { it.leaf.id == "demo-p3" }
        assertEquals("src/main.s", (gitPane.leaf.content as GitContent).selectedFilePath)
    }

    /** Expanding a directory persists into the pane's content state. */
    @Test
    fun fileBrowserExpandPersists() = runBlocking {
        server.handle(WindowCommand.FileBrowserSetExpanded(paneId = "demo-p8", dirRelPath = "gfx", expanded = true))
        val pane = repo.config.value!!.tabs.flatMap { it.panes }.first { it.leaf.id == "demo-p8" }
        assertTrue("gfx" in (pane.leaf.content as FileBrowserContent).expandedDirs)
    }

    /** Typing into a shell echoes, and Enter produces the canned response + prompt. */
    @Test
    fun terminalEchoesAndResponds() = runBlocking {
        val session = server.session("demo-s2")
        val collected = StringBuilder()
        val collector = scope.launch {
            session.output().collect { collected.append(it.decodeToString()) }
        }
        withTimeout(5_000) {
            // Wait for the snapshot replay first.
            while (!collected.contains("git pull")) kotlinx.coroutines.delay(10)
            session.inputText("pwd\r")
            while (!collected.contains("/Users/demo/code/lastlight")) kotlinx.coroutines.delay(10)
        }
        // The typed characters were echoed back before the response.
        assertTrue(collected.contains("pwd\r\n"))
        collector.cancel()
    }

    /**
     * Foreground-program sessions swallow input until Ctrl-C stops them.
     * Uses the watcher session (`demo-s5`): unlike the tracker, it has no
     * live feed, so the output length is stable while input is swallowed.
     */
    @Test
    fun foregroundProgramStopsOnCtrlC() = runBlocking {
        val session = server.session("demo-s5")
        val collected = StringBuilder()
        val collector = scope.launch {
            session.output().collect { collected.append(it.decodeToString()) }
        }
        withTimeout(5_000) {
            while (!collected.contains("watchexec")) kotlinx.coroutines.delay(10)
            val lenBefore = collected.length
            session.inputText("ls\r")
            kotlinx.coroutines.delay(100)
            assertEquals(lenBefore, collected.length) // swallowed: program owns the tty
            session.inputText("\u0003")
            while (!collected.contains("^C")) kotlinx.coroutines.delay(10)
            session.inputText("whoami\r")
            while (!collected.contains("demo@a500")) kotlinx.coroutines.delay(10)
        }
        collector.cancel()
    }

    /**
     * The [se.soderbjorn.lunamux.client.WindowSocket] suspend RPC
     * helpers must work against the in-process demo transport, where the
     * reply is emitted synchronously inside `send` — i.e. the helper has to
     * subscribe before sending or it misses its own reply and burns the
     * whole timeout (the bug behind multi-second hangs on the mobile demo).
     */
    @Test
    fun rpcHelpersWorkAgainstDemoTransport() = runBlocking {
        val socket = se.soderbjorn.lunamux.client.demo.DemoWindowSocket(server, repo)
        withTimeout(2_000) {
            val entries = socket.fileBrowserListDir(paneId = "demo-p8", dirRelPath = "src")
            assertTrue(entries!!.any { it.name == "main.s" })
            val git = socket.gitList(paneId = "demo-p3")
            assertTrue(git!!.any { it.filePath == "src/fx/scroller.s" })
            val diff = socket.gitDiff(paneId = "demo-p3", filePath = "README.md")
            assertTrue(diff is WindowEnvelope.GitDiffResult)
        }
    }

    /** Commands that change nothing must not broadcast a new config. */
    @Test
    fun noOpCommandsDoNotPublish() = runBlocking {
        val before = repo.config.value
        // Focusing the already-focused pane and raising the already-top
        // pane are the most common click-time no-ops.
        server.handle(WindowCommand.SetFocusedPane(tabId = "demo-t1", paneId = "demo-p1"))
        server.handle(WindowCommand.RaisePane(paneId = "demo-p3")) // p3 has top z in t1
        assertTrue(repo.config.value === before) // same instance: nothing republished
    }

    /**
     * The mobile overview authors pane geometry through the `ui-settings`
     * (`LAYOUT_STATE`) path, not window commands. The demo server must persist
     * those writes into the repository (and parse them into `geometryByTab`)
     * the way the real server does, so the layout survives the overview
     * view-model being recreated (open a pane, navigate back).
     */
    @Test
    fun uiSettingsPersistLayoutState() = runBlocking {
        val blob = """
            {"presetByTab":{"demo-t1":"columns"},
             "paneOrderByTab":{"demo-t1":["demo-p2","demo-p1"]},
             "geometryByTab":{"demo-t1":{"demo-p2":
               {"xPct":0.0,"yPct":0.0,"widthPct":0.5,"heightPct":1.0,
                "zIndex":2,"isMaximized":false,"isMinimized":false}}}}
        """.trimIndent()
        server.applyUiSettings(buildJsonObject { put(PersistKeys.LAYOUT_STATE, blob) })

        // Parsed geometry is now in the repository (process-lifetime), not just
        // an overview view-model — so it outlives that view-model.
        val geom = repo.geometryByTab.value["demo-t1"]?.get("demo-p2")
        assertNotNull(geom)
        assertEquals(0.5, geom.widthPct, 1e-9)
        // The raw blob is kept for read-modify-write of later edits.
        assertNotNull(repo.rawLayoutState.value)

        // A second patch merges rather than replacing the stored settings.
        server.applyUiSettings(buildJsonObject { put("SOME_OTHER_KEY", "x") })
        assertTrue(repo.geometryByTab.value["demo-t1"]?.get("demo-p2") != null)
    }

    /**
     * The finished Claude session (`demo-s6`) starts idle, flips to
     * `working` (published through the state map) when the user types a
     * line into it, and returns to idle when the burst is interrupted with
     * Ctrl-C — the demo's "Claude resumes when you talk to it" behaviour.
     */
    @Test
    fun finishedClaudeResumesOnInput() = runBlocking {
        val session = server.session("demo-s6")
        val collected = StringBuilder()
        val collector = scope.launch {
            session.output().collect { collected.append(it.decodeToString()) }
        }
        withTimeout(5_000) {
            // The completed-task transcript replays, and the session is idle.
            while (!collected.contains("greetings part")) kotlinx.coroutines.delay(10)
            assertEquals(null, repo.states.value["demo-s6"])

            session.inputText("swap the greets order\r")
            while (repo.states.value["demo-s6"] != "working") kotlinx.coroutines.delay(10)

            // Ctrl-C interrupts the burst and clears the state again.
            session.inputText("\u0003")
            while (repo.states.value["demo-s6"] == "working") kotlinx.coroutines.delay(10)
            while (!collected.contains("^C")) kotlinx.coroutines.delay(10)
        }
        collector.cancel()
    }

    /** Unknown commands fall back to a shell-style error. */
    @Test
    fun unknownCommandFallsBack() {
        assertTrue(demoShellRespond("frobnicate --now").contains("command not found: frobnicate"))
        assertTrue(demoShellRespond("git status").contains("feature/sine-scroller"))
        assertTrue(demoShellRespond("echo hello there").startsWith("hello there"))
    }
}
