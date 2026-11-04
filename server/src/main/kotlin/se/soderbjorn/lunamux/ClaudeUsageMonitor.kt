/**
 * Monitors Claude CLI subscription usage by asking the CLI for it.
 *
 * This file contains [ClaudeUsageMonitor], which periodically runs
 * `claude -p /usage --output-format json` as a short-lived process, reads the
 * plain-text usage report from the JSON `result` field, and parses it into a
 * [ClaudeUsageData] object. The parsed data is emitted on a [SharedFlow] that
 * the `/window` WebSocket pushes to connected clients as a
 * [WindowEnvelope.ClaudeUsage] message.
 *
 * Print mode is what makes this robust: `/usage` is answered locally (no model
 * call, no tokens, no cost), and a print-mode run shows no interactive UI — no
 * folder-trust dialog, no prompt to wait for, no screen to scrape. The monitor
 * used to drive a hidden interactive session in a PTY instead, which broke
 * whenever a Claude Code update changed a startup dialog (most recently by
 * putting "No, exit" first in the trust dialog, which the blind Enter then
 * chose).
 *
 * Lifecycle is controlled from [Application.main]: the monitor is started on
 * boot if the user has opted in via [SettingsRepository.isClaudeUsagePollEnabled],
 * and can be toggled at runtime from the [SettingsDialog].
 *
 * @see ClaudeUsageData
 * @see SettingsDialog.ClaudeUsageSection
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodically runs `claude -p /usage` and publishes the parsed subscription
 * usage (session %, weekly %, per-model weekly rows, reset times) as a
 * [ClaudeUsageData] on [usageData].
 *
 * Created once by [Application.main]; started and stopped from there and
 * from the settings toggle in [SettingsDialog]; read by the `/window`
 * WebSocket ([usageData]), its `RefreshUsage` command ([requestRefresh]) and
 * the MCP `get_claude_usage` tool ([current]).
 *
 * @param workDir folder the CLI runs in. Defaults to a dedicated temp folder
 *   ([defaultWorkDir]) so every poll shares one Claude Code project entry
 *   instead of creating one per run, and never runs inside a real project.
 */
class ClaudeUsageMonitor(private val workDir: File = defaultWorkDir()) {

    private val log = LoggerFactory.getLogger(ClaudeUsageMonitor::class.java)

    private val _usageData = MutableSharedFlow<ClaudeUsageData?>(replay = 1)
    val usageData = _usageData.asSharedFlow()

    /**
     * Most recent successfully parsed usage snapshot, or `null` when the
     * monitor is stopped or hasn't parsed one yet. Mirrors the shared flow's
     * replay cache without requiring a collector — the accessor the MCP
     * `get_claude_usage` tool reads synchronously.
     */
    @Volatile
    private var latest: ClaudeUsageData? = null

    /**
     * The latest parsed usage snapshot without subscribing to [usageData].
     *
     * @return the last [ClaudeUsageData] emitted, or `null` if none is
     *   available (monitor disabled, not yet parsed, or recently stopped).
     */
    fun current(): ClaudeUsageData? = latest

    private var scope: CoroutineScope? = null

    /** The `claude -p` run in flight, killed by [stop]. */
    @Volatile private var process: Process? = null
    private val refreshRequested = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Request an immediate usage refresh (skips the 10-minute wait). */
    fun requestRefresh() {
        refreshRequested.trySend(Unit)
    }

    /**
     * Start the background polling loop. Polls immediately, then every
     * [POLL_INTERVAL_MS]. No-op if already running.
     */
    fun start() {
        if (scope != null) return
        log.info("ClaudeUsageMonitor: starting")
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch { runLoop() }
    }

    /**
     * Stop the polling loop, kill a `claude` run in flight, and emit a null
     * value on [usageData] to signal that usage data is no longer available.
     */
    fun stop() {
        log.info("ClaudeUsageMonitor: stopping")
        scope?.cancel()
        scope = null
        process?.let { proc ->
            runCatching { proc.destroyForcibly() }
            process = null
        }
        latest = null
        _usageData.tryEmit(null)
    }

    /**
     * Polls until the scope is cancelled: [pollOnce], then wait — the full
     * [POLL_INTERVAL_MS] after a poll that ran, [RETRY_INTERVAL_MS] after one
     * that could not run the CLI at all — waking early on [requestRefresh].
     */
    private suspend fun runLoop() {
        while (coroutineContext.isActive) {
            val ran = try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("ClaudeUsageMonitor: poll failed", e)
                false
            }
            val wait = if (ran) POLL_INTERVAL_MS else RETRY_INTERVAL_MS
            withTimeoutOrNull(wait) { refreshRequested.receive() }
        }
    }

    /**
     * Runs the CLI once and publishes what it reports.
     *
     * Called by [runLoop]. A report that cannot be parsed (an older CLI, an
     * API-key login with no subscription limits, a changed format) is logged
     * with its text and leaves the previous snapshot in place.
     *
     * @return `true` when the CLI ran and answered (parsed or not), `false`
     *   when it could not be found, failed or timed out — so [runLoop] retries
     *   sooner.
     */
    private suspend fun pollOnce(): Boolean {
        val claudePath = findClaude() ?: run {
            log.warn("ClaudeUsageMonitor: 'claude' not found on PATH")
            return false
        }
        val report = fetchUsageReport(claudePath) ?: return false
        val parsed = parseUsageReport(report)
        if (parsed == null) {
            log.info("ClaudeUsageMonitor: could not parse usage report:\n{}", report)
            return true
        }
        val stamped = parsed.copy(fetchedAt = java.time.Instant.now().toString())
        latest = stamped
        _usageData.emit(stamped)
        log.debug("ClaudeUsageMonitor: parsed usage — session={}%, weekly={}%",
            parsed.sessionPercent, parsed.weeklyAllPercent)
        return true
    }

    /**
     * Runs `claude -p /usage --output-format json` in [workDir] and returns
     * the report text from its `result` field.
     *
     * Flags, each keeping a background poll from touching the user's setup:
     *  - `--no-session-persistence`: no transcript is saved per poll;
     *  - `--setting-sources project`: the user's own settings are not loaded,
     *    so their hooks (SessionStart and the like) do not fire every poll —
     *    [workDir] has no project settings, and the login is unaffected;
     *  - `--strict-mcp-config`: no MCP servers are started.
     *
     * Stdin is closed at once so the CLI never waits for piped input; a run
     * that takes longer than [RUN_TIMEOUT_MS] is killed.
     *
     * Called by [pollOnce].
     *
     * @param claudePath absolute path of the CLI, from [findClaude].
     * @return the report text, or `null` when the run failed, timed out, or
     *   answered with an error.
     */
    private suspend fun fetchUsageReport(claudePath: String): String? = coroutineScope {
        workDir.mkdirs()
        val proc = ProcessBuilder(
            claudePath, "-p", "/usage", "--output-format", "json",
            "--no-session-persistence", "--setting-sources", "project", "--strict-mcp-config",
        )
            .directory(workDir)
            .apply { environment()["PATH"] = augmentedPath(environment()["PATH"]) }
            .start()
        process = proc
        try {
            proc.outputStream.close()
            // Drain both pipes while waiting, so a chatty run can't block on a full pipe.
            val stdout = async(Dispatchers.IO) { proc.inputStream.readBytes().decodeToString() }
            val stderr = async(Dispatchers.IO) { proc.errorStream.readBytes().decodeToString() }
            val finished = runInterruptible(Dispatchers.IO) { proc.waitFor(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            if (!finished) {
                log.warn("ClaudeUsageMonitor: 'claude -p /usage' did not finish in {} ms", RUN_TIMEOUT_MS)
                proc.destroyForcibly()
                return@coroutineScope null
            }
            val out = stdout.await()
            if (proc.exitValue() != 0) {
                log.warn("ClaudeUsageMonitor: 'claude -p /usage' exited {}: {}",
                    proc.exitValue(), stderr.await().ifBlank { out }.take(500))
                return@coroutineScope null
            }
            usageReportFromJson(out).also {
                if (it == null) log.warn("ClaudeUsageMonitor: unexpected 'claude -p /usage' output: {}", out.take(500))
            }
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
            process = null
        }
    }

    companion object {
        /** Time between polls. */
        private const val POLL_INTERVAL_MS = 10 * 60_000L

        /** Time before trying again after the CLI could not be run. */
        private const val RETRY_INTERVAL_MS = 60_000L

        /** Longest a single `claude -p /usage` run may take; it normally answers in a few seconds. */
        private const val RUN_TIMEOUT_MS = 60_000L

        /**
         * The folder the CLI runs in by default: a dedicated temp directory,
         * so polls never run inside one of the user's projects.
         *
         * @return `<java.io.tmpdir>/lunamux-claude-usage`.
         */
        fun defaultWorkDir(): File = File(System.getProperty("java.io.tmpdir"), "lunamux-claude-usage")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * One usage row of the report, e.g.
         * `Current week (all models): 32% used · resets Nov 9 at 12am (Europe/Helsinki)`.
         * Captures the label, the percentage and the optional reset text; the
         * separator before "resets" is matched loosely (`·`, `-`, `|`, …).
         */
        private val USAGE_LINE_REGEX =
            Regex("""^\s*(.+?):\s*(\d+)%\s*used(?:\s*\S\s*resets?\s+(.+?))?\s*$""", RegexOption.IGNORE_CASE)

        /**
         * Matches a weekly row label and captures the parenthesized
         * qualifier, e.g. "Current week (all models)" → "all models",
         * "Current week (Fable)" → "Fable". The qualifier set varies with the
         * CLI version and the user's plan, so model names are not hardcoded.
         */
        private val WEEK_LABEL_REGEX = Regex("""week\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)

        /** The extra-usage line and whatever follows its label. */
        private val EXTRA_USAGE_REGEX = Regex("""^\s*Extra usage\b\s*:?\s*(.*)$""", RegexOption.IGNORE_CASE)

        /**
         * Extracts the report text from `claude -p --output-format json` output.
         *
         * Called by [fetchUsageReport].
         *
         * @param output the CLI's stdout: one JSON object.
         * @return its `result` string, or `null` when the output is not that
         *   JSON, has no `result`, or is flagged `is_error`.
         */
        internal fun usageReportFromJson(output: String): String? {
            val obj = runCatching { json.parseToJsonElement(output.trim()) as? JsonObject }.getOrNull()
                ?: return null
            if (obj["is_error"]?.jsonPrimitive?.booleanOrNull == true) return null
            return obj["result"]?.jsonPrimitive?.contentOrNull
        }

        /**
         * Parse the `/usage` report text into a [ClaudeUsageData].
         *
         * Reads one row per `<label>: N% used · resets <when>` line: the
         * current session, the all-models weekly pool, and one
         * [ClaudeModelUsage] per model-specific weekly row (e.g. Fable,
         * Sonnet), plus the extra-usage line when there is one. The advisory
         * sections that follow ("What's contributing to your usage?") carry
         * no "% used" rows and are ignored.
         *
         * @param text the report from [usageReportFromJson]
         * @return parsed usage data, or null if the report has no current
         *   session row (not a subscription login, or an unknown format)
         */
        fun parseUsageReport(text: String): ClaudeUsageData? {
            data class Row(val label: String, val percent: Int, val reset: String)
            val rows = text.lines().mapNotNull { line ->
                USAGE_LINE_REGEX.find(line)?.let { m ->
                    Row(m.groupValues[1].trim(), m.groupValues[2].toInt(), m.groupValues[3].trim())
                }
            }

            val session = rows.firstOrNull { it.label.contains("session", ignoreCase = true) } ?: return null
            val weekly = rows.mapNotNull { row ->
                WEEK_LABEL_REGEX.find(row.label)?.let { row to it.groupValues[1].trim() }
            }
            val allModels = weekly.firstOrNull { it.second.equals("all models", ignoreCase = true) }?.first

            // Every weekly row that isn't the all-models pool is a
            // model-specific row ("Fable", "Sonnet", "Opus", ...). A trailing
            // " only" (older CLIs printed "Sonnet only") is stripped.
            val modelUsages = weekly
                .filter { it.first !== allModels }
                .map { (row, qualifier) ->
                    ClaudeModelUsage(
                        label = qualifier.removeSuffix(" only").trim(),
                        percent = row.percent,
                        resetTime = row.reset,
                    )
                }

            // Legacy field for clients that predate modelUsages: mirror the
            // Sonnet row when one exists.
            val weeklySonnetPercent = modelUsages
                .firstOrNull { it.label.equals("Sonnet", ignoreCase = true) }
                ?.percent ?: 0

            val extra = text.lines().firstNotNullOfOrNull { EXTRA_USAGE_REGEX.find(it) }
            val extraInfo = extra?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            val extraEnabled = extra != null && extraInfo?.contains("not enabled", ignoreCase = true) != true

            return ClaudeUsageData(
                sessionPercent = session.percent,
                sessionResetTime = session.reset,
                weeklyAllPercent = allModels?.percent ?: 0,
                weeklyAllResetTime = allModels?.reset ?: "",
                weeklySonnetPercent = weeklySonnetPercent,
                extraUsageEnabled = extraEnabled,
                extraUsageInfo = extraInfo,
                modelUsages = modelUsages,
            )
        }

        /**
         * Returns [current] with well-known install directories put first, so
         * an npm-installed `claude` (`#!/usr/bin/env node`) still finds Node
         * when the server was launched from Finder/Dock with a minimal PATH.
         *
         * Called by [fetchUsageReport] for the CLI's environment.
         *
         * @param current the inherited PATH, or `null` when unset.
         * @return the PATH to run the CLI with.
         */
        private fun augmentedPath(current: String?): String {
            val home = System.getProperty("user.home")
            val extra = listOf(
                "/opt/homebrew/bin",
                "/usr/local/bin",
                "$home/.nvm/versions/node/default/bin",
                "$home/.local/bin",
            )
            val inherited = (current ?: "/usr/bin:/bin:/usr/sbin:/sbin").split(":")
            return (extra + inherited).distinct().joinToString(":")
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
                if (File(path).canExecute()) return path
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
