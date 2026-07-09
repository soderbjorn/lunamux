/**
 * Agent-activity visibility plumbing for the MCP server:
 *
 *  - [McpActivityLog] — an in-memory ring of every MCP *write* call
 *    (tool name + argument summary + timestamp), surfaced in the settings
 *    dialog's MCP section so the owner can audit what agents did.
 *  - [McpNotices] — a broadcast channel carrying
 *    [se.soderbjorn.lunamux.WindowEnvelope.AgentNotify] envelopes from
 *    the `notify` tool to every connected `/window` client.
 *
 * Together with the per-window `agentNote` badge (see
 * `WindowState.setAgentNote`) these implement the plan's "agent activity
 * is never invisible" requirement.
 *
 * @see registerMcpWriteTools
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.WindowEnvelope
import java.util.ArrayDeque

/**
 * One recorded MCP write call.
 *
 * @property atEpochMs when the call happened.
 * @property tool the MCP tool name.
 * @property detail a short human-readable argument summary (already
 *   redacted — never contains typed terminal input beyond a length).
 */
data class McpActivityEntry(
    val atEpochMs: Long,
    val tool: String,
    val detail: String,
)

/**
 * Fixed-size in-memory log of MCP write activity. Also mirrors every
 * entry to the server log at INFO so activity survives in logfiles even
 * though the ring is process-lifetime only.
 */
object McpActivityLog {

    private val log = LoggerFactory.getLogger(McpActivityLog::class.java)
    private const val CAPACITY = 200
    private val entries = ArrayDeque<McpActivityEntry>()

    /**
     * Record one write call.
     *
     * @param tool the MCP tool name.
     * @param detail short argument summary for the settings-dialog list.
     */
    fun record(tool: String, detail: String) {
        log.info("MCP write: {} {}", tool, detail)
        synchronized(entries) {
            entries.addLast(McpActivityEntry(System.currentTimeMillis(), tool, detail))
            while (entries.size > CAPACITY) entries.removeFirst()
        }
    }

    /**
     * Most recent entries, newest first.
     *
     * @param limit maximum number of entries to return.
     */
    fun recent(limit: Int = 50): List<McpActivityEntry> = synchronized(entries) {
        entries.toList().takeLast(limit).reversed()
    }
}

/**
 * Broadcast bridge from MCP tools to every `/window` WebSocket. The
 * socket handler in `WindowRoutes` collects [flow] and forwards each
 * envelope to its client; the `notify` tool emits here.
 */
object McpNotices {

    private val _flow = MutableSharedFlow<WindowEnvelope>(extraBufferCapacity = 16)

    /** Envelopes to push to every connected window client. */
    val flow = _flow.asSharedFlow()

    /**
     * Queue [envelope] for broadcast (non-suspending; drops only if an
     * absurd burst overruns the buffer).
     */
    fun emit(envelope: WindowEnvelope) {
        _flow.tryEmit(envelope)
    }
}
