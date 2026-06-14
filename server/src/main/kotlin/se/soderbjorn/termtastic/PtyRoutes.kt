/**
 * `/pty/{id}` WebSocket route. Each connection attaches to a single
 * [TerminalSession] and bidirectionally bridges PTY bytes ↔ WebSocket
 * frames. Resize control messages are routed through [handleControl].
 *
 * Lives next to [Application.module], which mounts it via [ptyRoutes]
 * inside its `routing { }` block.
 *
 * @see TerminalSession
 * @see DeviceAuth
 */
package se.soderbjorn.termtastic

import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.soderbjorn.termtastic.auth.DeviceAuth
import se.soderbjorn.termtastic.persistence.SettingsRepository

/** Tolerant JSON used for parsing inbound /pty control messages. */
private val controlJson = Json { ignoreUnknownKeys = true }

/**
 * Mount the `/pty/{id}` WebSocket on this [Route]. The handler validates
 * the device-auth token, looks up the [TerminalSession] for the path id,
 * pushes the current PTY size and a snapshot of recent output, and then
 * pumps PTY bytes to the socket while forwarding inbound binary frames
 * (keystrokes) and text frames (resize control) back into the session.
 *
 * @param settingsRepo the SQLite-backed settings store, used by [DeviceAuth]
 */
internal fun Route.ptyRoutes(settingsRepo: SettingsRepository) {
    webSocket("/pty/{id}") {
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
        val session = if (id != null) TerminalSessions.get(id) else null
        if (session == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown session id"))
            return@webSocket
        }

        val clientId = java.util.UUID.randomUUID().toString()

        val (initialCols, initialRows) = session.sizeEvents.value
        send(
            Frame.Text(
                windowJson.encodeToString<PtyServerMessage>(
                    PtyServerMessage.Size(initialCols, initialRows)
                )
            )
        )

        val snapshot = session.snapshot()
        if (snapshot.isNotEmpty()) {
            send(Frame.Binary(true, snapshot))
        }

        val outJob = launch {
            session.output.collect { chunk ->
                send(Frame.Binary(true, chunk))
            }
        }

        val sizeJob = launch {
            session.sizeEvents.drop(1).collect { (cols, rows) ->
                val payload = windowJson.encodeToString<PtyServerMessage>(
                    PtyServerMessage.Size(cols, rows)
                )
                send(Frame.Text(payload))
            }
        }

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> session.write(frame.readBytes())
                    is Frame.Text -> handleControl(session, clientId, frame.readText())
                    else -> Unit
                }
            }
        } finally {
            outJob.cancel()
            sizeJob.cancel()
            session.removeClient(clientId)
        }
    }
}

/**
 * Handle a JSON control message received on a `/pty/{id}` WebSocket.
 *
 * Deserialises the text as a [PtyControl] and dispatches resize commands
 * to the [TerminalSession]. Unknown or malformed messages are silently
 * dropped.
 */
internal fun handleControl(session: TerminalSession, clientId: String, text: String) {
    val control = runCatching {
        controlJson.decodeFromString<PtyControl>(text)
    }.getOrNull() ?: return
    when (control) {
        is PtyControl.Resize -> session.setClientSize(clientId, control.cols, control.rows)
        is PtyControl.ForceResize -> session.forceClientSize(clientId, control.cols, control.rows)
    }
}
