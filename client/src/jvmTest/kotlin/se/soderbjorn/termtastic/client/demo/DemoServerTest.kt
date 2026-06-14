/**
 * JVM tests for the demo-mode simulation: the [DemoServer] command handling
 * (layout mutations, file-browser and git RPC replies) and the
 * [DemoTerminalSession] line discipline (echo, canned command responses,
 * Ctrl-C behaviour in foreground-program sessions).
 *
 * @see DemoServer
 * @see DemoTerminalSession
 */
package se.soderbjorn.termtastic.client.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import se.soderbjorn.termtastic.FileBrowserContent
import se.soderbjorn.termtastic.GitContent
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowStateRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(3, config.tabs.size)
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
        val newPane = tab.panes.maxBy { it.z }
        assertTrue(newPane.leaf.sessionId.startsWith("demo-s"))
        assertEquals(tab.focusedPaneId, newPane.leaf.id)
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
            server.handle(WindowCommand.GitDiff(paneId = "demo-p3", filePath = "src/api/sessions.ts"))
        } as WindowEnvelope.GitDiffResult
        assertTrue(reply.hunks.isNotEmpty())
        assertEquals("typescript", reply.language)
        val gitPane = repo.config.value!!.tabs.flatMap { it.panes }.first { it.leaf.id == "demo-p3" }
        assertEquals("src/api/sessions.ts", (gitPane.leaf.content as GitContent).selectedFilePath)
    }

    /** Expanding a directory persists into the pane's content state. */
    @Test
    fun fileBrowserExpandPersists() = runBlocking {
        server.handle(WindowCommand.FileBrowserSetExpanded(paneId = "demo-p8", dirRelPath = "src/lib", expanded = true))
        val pane = repo.config.value!!.tabs.flatMap { it.panes }.first { it.leaf.id == "demo-p8" }
        assertTrue("src/lib" in (pane.leaf.content as FileBrowserContent).expandedDirs)
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
            while (!collected.contains("/Users/demo/code/orbit")) kotlinx.coroutines.delay(10)
        }
        // The typed characters were echoed back before the response.
        assertTrue(collected.contains("pwd\r\n"))
        collector.cancel()
    }

    /** Foreground-program sessions swallow input until Ctrl-C stops them. */
    @Test
    fun foregroundProgramStopsOnCtrlC() = runBlocking {
        val session = server.session("demo-s3")
        val collected = StringBuilder()
        val collector = scope.launch {
            session.output().collect { collected.append(it.decodeToString()) }
        }
        withTimeout(5_000) {
            while (!collected.contains("tsx watch")) kotlinx.coroutines.delay(10)
            val lenBefore = collected.length
            session.inputText("ls\r")
            kotlinx.coroutines.delay(100)
            assertEquals(lenBefore, collected.length) // swallowed: program owns the tty
            session.inputText("\u0003")
            while (!collected.contains("^C")) kotlinx.coroutines.delay(10)
            session.inputText("whoami\r")
            while (!collected.contains("demo@orbit")) kotlinx.coroutines.delay(10)
        }
        collector.cancel()
    }

    /**
     * The [se.soderbjorn.termtastic.client.WindowSocket] suspend RPC
     * helpers must work against the in-process demo transport, where the
     * reply is emitted synchronously inside `send` — i.e. the helper has to
     * subscribe before sending or it misses its own reply and burns the
     * whole timeout (the bug behind multi-second hangs on the mobile demo).
     */
    @Test
    fun rpcHelpersWorkAgainstDemoTransport() = runBlocking {
        val socket = se.soderbjorn.termtastic.client.demo.DemoWindowSocket(server, repo)
        withTimeout(2_000) {
            val entries = socket.fileBrowserListDir(paneId = "demo-p8", dirRelPath = "src")
            assertTrue(entries!!.any { it.name == "server.ts" })
            val git = socket.gitList(paneId = "demo-p3")
            assertTrue(git!!.any { it.filePath == "src/api/ratelimit.ts" })
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

    /** Unknown commands fall back to a shell-style error. */
    @Test
    fun unknownCommandFallsBack() {
        assertTrue(demoShellRespond("frobnicate --now").contains("command not found: frobnicate"))
        assertTrue(demoShellRespond("git status").contains("feature/rate-limit"))
        assertTrue(demoShellRespond("echo hello there").startsWith("hello there"))
    }
}
