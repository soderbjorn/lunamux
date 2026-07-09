/**
 * `/agent/{id}` WebSocket route: the structured transcript channel for
 * agent-console panes in transcript mode.
 *
 * Each connection attaches to one [AgentSession] (looked up by its
 * session id in [TerminalSessions]) and:
 *  - immediately sends an [AgentServerMessage.Snapshot] of the whole
 *    transcript plus the awaiting-input state,
 *  - streams [AgentServerMessage.Append] / `Awaiting` / `Closed` events,
 *  - accepts [AgentClientMessage.Submit] lines from the client's input
 *    box, delivering them to blocked `console_read` / `console_ask` MCP
 *    calls via [AgentSession.submitLine].
 *
 * Auth is the regular interactive device gate ([DeviceAuth.authorize]) —
 * this socket is a client UI surface like `/pty/{id}`, not part of the
 * MCP endpoint.
 *
 * Screen-mode consoles don't use this route (their clients attach a
 * terminal view to `/pty/{id}`); connecting to one anyway just yields an
 * empty transcript.
 *
 * @see AgentSession
 * @see AgentProtocol.kt (wire types, in :clientServer)
 */
package se.soderbjorn.lunamux

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository

/**
 * Mount the `/agent/{id}` WebSocket on this [Route].
 *
 * @param settingsRepo the settings store backing [DeviceAuth].
 */
internal fun Route.agentRoutes(settingsRepo: SettingsRepository) {
    webSocket("/agent/{id}") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo)) {
            DeviceAuth.Decision.APPROVED -> Unit
            DeviceAuth.Decision.REJECTED -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                return@webSocket
            }
            DeviceAuth.Decision.HEADLESS -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                return@webSocket
            }
        }
        val id = call.parameters["id"]
        val session = (if (id != null) TerminalSessions.get(id) else null) as? AgentSession
        if (session == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown agent session id"))
            return@webSocket
        }

        // Snapshot first, then live events — the client dedupes by item id.
        send(Frame.Text(windowJson.encodeToString<AgentServerMessage>(session.transcriptSnapshot())))

        val pushJob = launch {
            session.transcriptEvents.collect { event ->
                send(Frame.Text(windowJson.encodeToString<AgentServerMessage>(event)))
                if (event is AgentServerMessage.Closed) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "console closed"))
                }
            }
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching {
                    windowJson.decodeFromString<AgentClientMessage>(frame.readText())
                }.getOrNull() ?: continue
                when (msg) {
                    is AgentClientMessage.Submit ->
                        session.submitLine(msg.text, echoBytes = true)
                }
            }
        } finally {
            pushJob.cancel()
        }
    }
}
