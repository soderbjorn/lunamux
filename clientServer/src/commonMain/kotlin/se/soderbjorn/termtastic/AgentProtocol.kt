/**
 * Wire types for the `/agent/{id}` WebSocket — the structured transcript
 * channel between the server's PTY-less agent-console session and a
 * client rendering an [AgentContent] pane in transcript mode.
 *
 * Screen-mode agent panes do NOT use this socket: they attach a terminal
 * view to the ordinary `/pty/{sessionId}` byte stream. This channel
 * exists so transcript mode can be rendered as a real conversation UI
 * (scrolling list + input box) instead of a terminal approximation.
 *
 * Direction: [AgentServerMessage] is server → client;
 * [AgentClientMessage] is client → server. Both serialize with
 * [windowJson]'s conventions but use `"type"` as the discriminator (like
 * [WindowCommand] / [WindowEnvelope]).
 *
 * @see AgentContent
 */
package se.soderbjorn.termtastic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * One entry in an agent console's transcript.
 *
 * @property id monotonically increasing per-console entry id (lets a
 *   client dedupe after a reconnect snapshot).
 * @property role `"output"` (agent text), `"prompt"` (an agent question
 *   awaiting input), or `"input"` (a line the user submitted).
 * @property text the entry's text (may be multi-line for output).
 */
@Serializable
data class AgentTranscriptItem(
    val id: Long,
    val role: String,
    val text: String,
)

/**
 * Server → client messages on the `/agent/{id}` socket.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class AgentServerMessage {
    /**
     * Full transcript snapshot, sent once on connect (and never again) so
     * a (re)connecting client can render the whole history.
     *
     * @param items every transcript entry so far, oldest first.
     * @param awaitingInput true when a `console_read`/`console_ask` is
     *   currently blocked waiting for a line — the client should enable
     *   its input affordance.
     */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val items: List<AgentTranscriptItem>,
        val awaitingInput: Boolean = false,
    ) : AgentServerMessage()

    /**
     * One new transcript entry appended after the snapshot.
     *
     * @param item the appended entry.
     */
    @Serializable
    @SerialName("append")
    data class Append(val item: AgentTranscriptItem) : AgentServerMessage()

    /**
     * The console's input state changed: the agent started (or stopped)
     * waiting for a user line.
     *
     * @param awaiting whether input is currently awaited.
     */
    @Serializable
    @SerialName("awaiting")
    data class Awaiting(val awaiting: Boolean) : AgentServerMessage()

    /**
     * The agent console was closed server-side (MCP client disconnected
     * or the pane was closed) — the client should render a "console
     * ended" state and stop reconnecting.
     */
    @Serializable
    @SerialName("closed")
    data object Closed : AgentServerMessage()
}

/**
 * Client → server messages on the `/agent/{id}` socket.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class AgentClientMessage {
    /**
     * The user submitted a line from the transcript input box. Delivered
     * to the blocked `console_read` / `console_ask` MCP call (or queued
     * until one arrives).
     *
     * @param text the submitted line (without a trailing newline).
     */
    @Serializable
    @SerialName("submit")
    data class Submit(val text: String) : AgentClientMessage()
}
