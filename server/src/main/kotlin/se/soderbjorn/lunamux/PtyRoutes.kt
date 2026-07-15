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
package se.soderbjorn.lunamux

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
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository

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
        when (DeviceAuth.authorize(token, info, settingsRepo, call.readPairingToken())) {
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
        // A phone/tablet on this session must always win the PTY size so the
        // terminal stays readable there (see [SizePriority.MOBILE]); we classify
        // it once here from the reported client type and elevate all of its size
        // votes accordingly in [handleControl]. Desktop/web clients keep the
        // per-vote priority they send (NORMAL, or THREE_D from the 3D world).
        val mobile = isMobileClientType(info.type)

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
                    is Frame.Binary -> {
                        // Input marks this client as the most recently active
                        // one for size arbitration (latest-active wins) —
                        // recorded here, not inside write(), because write()
                        // is also used by MCP tools with no client identity.
                        session.noteClientInput(clientId)
                        session.write(frame.readBytes())
                    }
                    is Frame.Text -> handleControl(session, clientId, mobile, frame.readText())
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
internal fun handleControl(session: TermSession, clientId: String, mobile: Boolean, text: String) {
    val control = runCatching {
        controlJson.decodeFromString<PtyControl>(text)
    }.getOrNull() ?: return
    when (control) {
        // A mobile client's votes are elevated to the MOBILE tier so they win
        // over any 2D/3D size; a desktop/web client keeps the tier it sent
        // (NORMAL, or THREE_D when the 3D world asserts a Pane.grid3d override).
        is PtyControl.Resize -> {
            val priority = if (mobile) SizePriority.MOBILE else control.priority
            session.setClientSize(clientId, control.cols, control.rows, priority)
        }
        is PtyControl.ForceResize -> {
            val priority = if (mobile) SizePriority.MOBILE else SizePriority.NORMAL
            session.forceClientSize(clientId, control.cols, control.rows, priority)
        }
        is PtyControl.ResetModes -> session.resetTerminalModes()
    }
}

/**
 * Classify a reported client [type] (the `X-Termtastic-Client-Type` /
 * `clientType` value — e.g. `"Web"`, `"Computer"`, `"Android"`, `"iOS"`) as a
 * phone/tablet. Mobile clients have their PTY-size votes elevated to
 * [SizePriority.MOBILE] so their smaller viewport always drives the shared
 * grid. Matched leniently (substring, case-insensitive) so future mobile type
 * strings (`"iPhone"`, `"iPad"`, …) are covered without another code change.
 *
 * @param type the self-reported client type, possibly `"Unknown"`.
 * @return `true` if [type] denotes a mobile client.
 */
internal fun isMobileClientType(type: String): Boolean {
    val t = type.lowercase()
    return t.contains("android") ||
        t.contains("ios") ||
        t.contains("iphone") ||
        t.contains("ipad") ||
        t.contains("phone") ||
        t.contains("mobile") ||
        t.contains("tablet")
}
