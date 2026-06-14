/**
 * Monitors Claude CLI subscription usage by running a hidden PTY session.
 *
 * This file contains [ClaudeUsageMonitor], which spawns a background `claude`
 * process, periodically types the `/usage` slash command, scrapes the rendered
 * screen output via [ScreenEmulator], and parses the result into a
 * [ClaudeUsageData] object. The parsed data is emitted on a [SharedFlow] that
 * the `/window` WebSocket pushes to connected clients as a
 * [WindowEnvelope.ClaudeUsage] message.
 *
 * Lifecycle is controlled from [Application.main]: the monitor is started on
 * boot if the user has opted in via [SettingsRepository.isClaudeUsagePollEnabled],
 * and can be toggled at runtime from the [SettingsDialog].
 *
 * @see ClaudeUsageData
 * @see SettingsDialog.ClaudeUsageSection
 */
package se.soderbjorn.termtastic

import com.pty4j.PtyProcessBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.coroutines.coroutineContext
import org.slf4j.LoggerFactory

/**
 * Runs a hidden PTY session with the `claude` CLI and periodically types
 * `/usage` to scrape subscription usage data (session %, weekly %, reset
 * times). The parsed data is emitted as a [ClaudeUsageData] on [usageData].
 */
class ClaudeUsageMonitor {

    private val log = LoggerFactory.getLogger(ClaudeUsageMonitor::class.java)

    private val _usageData = MutableSharedFlow<ClaudeUsageData?>(replay = 1)
    val usageData = _usageData.asSharedFlow()

    private var scope: CoroutineScope? = null
    @Volatile private var ptyProcess: Process? = null
    private val refreshRequested = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Request an immediate usage refresh (skips the 10-minute wait). */
    fun requestRefresh() {
        refreshRequested.trySend(Unit)
    }

    /**
     * Start the background monitoring loop. Spawns a `claude` process in a
     * hidden PTY and begins periodically issuing `/usage` commands.
     * No-op if already running.
     */
    fun start() {
        if (scope != null) return
        log.info("ClaudeUsageMonitor: starting")
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch { runLoop() }
    }

    /**
     * Stop the monitoring loop, destroy the hidden PTY process, and emit
     * a null value on [usageData] to signal that usage data is no longer
     * available.
     */
    fun stop() {
        log.info("ClaudeUsageMonitor: stopping")
        scope?.cancel()
        scope = null
        ptyProcess?.let { proc ->
            runCatching { proc.destroyForcibly() }
            ptyProcess = null
        }
        _usageData.tryEmit(null)
    }

    /**
     * Top-level loop that keeps retrying [runSession] on failure with a
     * 30-second back-off. Runs until the coroutine scope is cancelled.
     */
    private suspend fun runLoop() {
        while (coroutineContext.isActive) {
            try {
                runSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("ClaudeUsageMonitor: session failed, retrying in 30s", e)
                delay(30_000)
            }
        }
    }

    /**
     * Run a single Claude CLI session: find the `claude` binary, spawn it
     * in a hidden PTY, wait for the prompt, then loop issuing `/usage`
     * commands every 10 minutes (or sooner on [requestRefresh]).
     */
    private suspend fun runSession() {
        val claudePath = findClaude() ?: run {
            log.warn("ClaudeUsageMonitor: 'claude' not found on PATH")
            delay(60_000)
            return
        }

        // Use a dedicated temp directory to avoid the "trust this folder"
        // prompt that appears for sensitive directories like $HOME.
        val workDir = java.io.File(System.getProperty("java.io.tmpdir"), "termtastic-claude-usage").apply { mkdirs() }
        val env = HashMap(System.getenv()).apply {
            put("TERM", "xterm-256color")
            // GUI launches (Electron from Finder/Dock) inherit a minimal
            // PATH that usually lacks Node.  Append well-known directories
            // so that npm-installed claude (#!/usr/bin/env node) still works.
            val extra = listOf(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "${System.getProperty("user.home")}/.nvm/versions/node/default/bin",
                "${System.getProperty("user.home")}/.local/bin",
            )
            val current = getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
            put("PATH", (extra + current.split(":")).distinct().joinToString(":"))
        }

        val pty = PtyProcessBuilder(arrayOf(claudePath))
            .setDirectory(workDir.absolutePath)
            .setEnvironment(env)
            .setInitialColumns(100)
            .setInitialRows(40)
            .start()
        ptyProcess = pty

        val screen = ScreenEmulator(initialCols = 100, initialRows = 40)
        val readJob = CoroutineScope(coroutineContext).launch {
            val input = pty.inputStream
            val buf = ByteArray(4096)
            while (isActive) {
                val n = input.read(buf)
                if (n <= 0) break
                val chunk = buf.copyOf(n)
                screen.feed(chunk, n)
            }
        }

        try {
            // Wait for claude to start up (look for prompt)
            waitForPrompt(screen, pty, timeoutMs = 15_000)
            log.info("ClaudeUsageMonitor: claude prompt detected, starting poll loop")

            while (coroutineContext.isActive) {
                // Send /usage command.  Ink (Claude Code's UI framework)
                // puts the terminal in raw mode, so Enter must be CR (\r),
                // not LF (\n) — the PTY won't translate in raw mode.
                // The first CR selects the autocomplete suggestion, the
                // second CR actually submits the command.
                pty.outputStream.write("/usage\r".toByteArray())
                pty.outputStream.flush()
                delay(500)
                pty.outputStream.write("\r".toByteArray())
                pty.outputStream.flush()

                // Wait for the usage screen to render
                delay(3_000)

                // Read and parse the screen
                val text = screen.snapshotVisibleText()
                val parsed = parseUsageScreen(text)
                if (parsed != null) {
                    _usageData.emit(parsed.copy(
                        fetchedAt = java.time.Instant.now().toString()
                    ))
                    log.debug("ClaudeUsageMonitor: parsed usage — session={}%, weekly={}%",
                        parsed.sessionPercent, parsed.weeklyAllPercent)
                } else {
                    log.info("ClaudeUsageMonitor: could not parse usage screen. Snapshot:\n{}", text)
                }

                // Send ESC to dismiss the usage overlay
                pty.outputStream.write(byteArrayOf(0x1B))
                pty.outputStream.flush()

                // Wait before next poll (10 minutes), but wake early on refresh request
                withTimeoutOrNull(600_000) { refreshRequested.receive() }
            }
        } finally {
            readJob.cancel()
            runCatching { pty.destroyForcibly() }
            ptyProcess = null
        }
    }

    /**
     * Wait for the Claude CLI's input prompt to appear on [screen].
     * If a "Quick safety check" trust prompt is detected, auto-confirms it
     * by sending a carriage return.
     *
     * @param screen the headless emulator tracking the CLI's output
     * @param pty the Claude CLI process (used to write confirmation input)
     * @param timeoutMs maximum time to wait for the prompt, in milliseconds
     */
    private suspend fun waitForPrompt(screen: ScreenEmulator, pty: Process, timeoutMs: Long) {
        var deadline = System.currentTimeMillis() + timeoutMs
        var trustConfirmed = false
        while (System.currentTimeMillis() < deadline && coroutineContext.isActive) {
            val text = screen.snapshotVisibleText()

            // If the "trust this folder" prompt appears, press Enter to confirm.
            // The rendered text may contain escape code artifacts (e.g. "[1CI trust")
            // so match on a stable substring from the dialog.
            if (!trustConfirmed && text.contains("Quick safety check")) {
                log.info("ClaudeUsageMonitor: trust prompt detected, auto-confirming")
                pty.outputStream.write("\r".toByteArray())
                pty.outputStream.flush()
                trustConfirmed = true
                // Reset deadline — Claude still needs time to fully start up
                deadline = System.currentTimeMillis() + timeoutMs
                delay(2_000)
                continue
            }

            // Claude Code shows "❯" as its input prompt — but ignore it if
            // it's part of the trust dialog (which also uses ❯ as a selector)
            if (text.contains("❯") && !text.contains("Quick safety check")) {
                return
            }
            delay(500)
        }
        // Proceed anyway after timeout — the /usage command might still work
        log.warn("ClaudeUsageMonitor: prompt not detected within {}ms, proceeding anyway", timeoutMs)
    }

    companion object {
        private val PERCENT_REGEX = Regex("""(\d+)%\s*used""")
        private val RESETS_REGEX = Regex("""[Rr]esets\s+(.+)""")

        /**
         * Parse the rendered `/usage` screen text into a [ClaudeUsageData].
         *
         * Extracts session percentage, weekly (all models) percentage, weekly
         * Sonnet percentage, reset times, and extra-usage status from the
         * screen output.
         *
         * @param text the full visible text from the [ScreenEmulator] after
         *             the `/usage` command has rendered
         * @return parsed usage data, or null if the "Current session" section
         *         was not found in the text
         */
        fun parseUsageScreen(text: String): ClaudeUsageData? {
            val lines = text.lines()

            // Find section indices
            val sessionIdx = lines.indexOfFirst { it.contains("Current session", ignoreCase = true) }
            val weeklyAllIdx = lines.indexOfFirst {
                it.contains("Current week", ignoreCase = true) &&
                    it.contains("all models", ignoreCase = true)
            }
            val weeklySonnetIdx = lines.indexOfFirst {
                it.contains("Current week", ignoreCase = true) &&
                    it.contains("Sonnet", ignoreCase = true)
            }
            val extraIdx = lines.indexOfFirst { it.contains("Extra usage", ignoreCase = true) }

            if (sessionIdx < 0) return null

            // Parse each section
            val sessionPercent = findPercent(lines, sessionIdx) ?: return null
            val sessionReset = findReset(lines, sessionIdx, weeklyAllIdx.takeIf { it > sessionIdx })

            val weeklyAllPercent = if (weeklyAllIdx >= 0) findPercent(lines, weeklyAllIdx) ?: 0 else 0
            val weeklyAllReset = if (weeklyAllIdx >= 0)
                findReset(lines, weeklyAllIdx, weeklySonnetIdx.takeIf { it > weeklyAllIdx })
            else ""

            val weeklySonnetPercent = if (weeklySonnetIdx >= 0) findPercent(lines, weeklySonnetIdx) ?: 0 else 0

            val extraEnabled = if (extraIdx >= 0) {
                val extraSection = lines.drop(extraIdx).take(3).joinToString(" ")
                !extraSection.contains("not enabled", ignoreCase = true)
            } else false

            val extraInfo = if (extraIdx >= 0) {
                lines.drop(extraIdx + 1).take(2)
                    .joinToString(" ").trim()
                    .takeIf { it.isNotBlank() }
            } else null

            return ClaudeUsageData(
                sessionPercent = sessionPercent,
                sessionResetTime = sessionReset,
                weeklyAllPercent = weeklyAllPercent,
                weeklyAllResetTime = weeklyAllReset,
                weeklySonnetPercent = weeklySonnetPercent,
                extraUsageEnabled = extraEnabled,
                extraUsageInfo = extraInfo,
            )
        }

        /**
         * Search for a `"N% used"` pattern in [lines] starting at [startIdx],
         * checking up to 4 lines ahead.
         *
         * @param lines all screen lines
         * @param startIdx the line index of the section header
         * @return the extracted percentage, or null if not found
         */
        private fun findPercent(lines: List<String>, startIdx: Int): Int? {
            for (i in startIdx until minOf(startIdx + 4, lines.size)) {
                val match = PERCENT_REGEX.find(lines[i])
                if (match != null) return match.groupValues[1].toIntOrNull()
            }
            return null
        }

        /**
         * Search for a `"Resets ..."` line in [lines] between [startIdx] and
         * [endIdx] (or up to 4 lines ahead if [endIdx] is null).
         *
         * @param lines all screen lines
         * @param startIdx the line index of the section header
         * @param endIdx optional upper bound (exclusive) for the search window
         * @return the reset time text, or an empty string if not found
         */
        private fun findReset(lines: List<String>, startIdx: Int, endIdx: Int?): String {
            val end = endIdx ?: minOf(startIdx + 4, lines.size)
            for (i in startIdx until minOf(end, lines.size)) {
                val match = RESETS_REGEX.find(lines[i])
                if (match != null) return match.groupValues[1].trim()
            }
            return ""
        }

        /**
         * Locate the `claude` CLI binary on the system.
         *
         * Checks well-known install locations first (standalone binaries, then
         * npm-installed versions), and falls back to `which claude` on the PATH.
         *
         * @return absolute path to the `claude` binary, or null if not found
         */
        private fun findClaude(): String? {
            // Prefer standalone (compiled) binaries first — they don't need
            // Node on the PATH, which is often absent in GUI-launched
            // environments (Electron from Finder/Dock).  npm-installed
            // versions (symlinks to .js files) come last as a fallback.
            val home = System.getProperty("user.home")
            val candidates = listOf(
                "$home/.local/bin/claude",
                "$home/.claude/local/claude",
                "$home/.claude/bin/claude",
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude",
                "$home/.npm-global/bin/claude",
            )
            for (path in candidates) {
                if (java.io.File(path).canExecute()) return path
            }
            // Try PATH via which
            return try {
                val proc = ProcessBuilder("which", "claude")
                    .redirectErrorStream(true)
                    .start()
                val result = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor()
                if (proc.exitValue() == 0 && result.isNotBlank()) result else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
