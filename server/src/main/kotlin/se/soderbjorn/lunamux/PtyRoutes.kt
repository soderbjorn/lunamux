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
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.pty.ClientPosture

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
        // How this client participates in size governance (see [ClientPosture]).
        // A driver may seize the grid by typing/forcing; a viewer mirrors the
        // desktop until it explicitly takes over. The client declares it with a
        // `posture=viewer|driver` query param; absent that we sniff the reported
        // client type (a phone/tablet defaults to viewer, everything else to a
        // driver) so older clients still behave sensibly.
        val posture = call.readClientPosture(info.type)
        session.setClientPosture(clientId, posture)

        // The client declares the grid its own emulator currently renders via
        // ?cols=&rows= so the server can synthesize the attach redraw at that
        // width. A governing driver's vote resizes+reflows the grid before
        // synthesis; a viewer's is stored (it mirrors the desktop's width). Absent
        // params (an older client) → synthesize at the current PTY dims.
        val qCols = call.request.queryParameters["cols"]?.toIntOrNull()
        val qRows = call.request.queryParameters["rows"]?.toIntOrNull()

        // One writer coroutine over the single ordered event stream. It sends the
        // attach Size + synthesized redraw first, then streams live events gated by
        // seq — exact ordering by construction, replacing the old wall-clock merge{}.
        val writerJob = launch {
            session.streamAttach(clientId, qCols, qRows) { frame -> send(frame) }
        }

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> acceptClientBytes(session, clientId, frame.readBytes())
                    is Frame.Text -> handleControl(session, clientId, frame.readText())
                    else -> Unit
                }
            }
        } finally {
            writerJob.cancel()
            session.removeClient(clientId)
        }
    }
}

/**
 * Handle one inbound binary frame from an attached `/pty` client: record it as activity and
 * write it to the PTY — unless it is a device reply, which is dropped outright.
 *
 * **The canonical grid is the session's single answerer** (see
 * [se.soderbjorn.lunamux.pty.SessionGrid.armAnswerSink]). Every attached interactive client's
 * emulator also answers the running program's queries — cursor position, device attributes,
 * colour, mode and window reports — over this same channel. Forwarding those meant N answers
 * to a question that has exactly one: two attached devices produced two answers, ZLE consumed
 * the surplus as typed input and echoed it into canonical state, which is the spliced echo and
 * duplicated prompt lines on-device testing kept surfacing. Dropping them here mutes web,
 * Android and iOS uniformly with no client change; each keeps its local auto-answer machinery
 * and its answers simply die at the server.
 *
 * They are not activity either: counting a reply as input let a client that was merely
 * mirroring seize the grid whenever the program happened to probe the terminal, so a phone
 * left attached silently owned every session. Activity is recorded here rather than inside
 * [TermSession.write] because that is also used by MCP tools, which have no client identity.
 *
 * Extracted from the socket handler so the rule is unit-testable without a real WebSocket.
 *
 * @param session the attached session.
 * @param clientId this connection's size-arbitration id.
 * @param bytes one inbound burst, verbatim from the frame.
 * @see TerminalInputClassifier.isDeviceReply
 */
internal fun acceptClientBytes(session: TermSession, clientId: String, bytes: ByteArray) {
    if (TerminalInputClassifier.isDeviceReply(bytes)) return
    session.noteClientInput(clientId)
    session.write(bytes)
}

/**
 * Stream a `/pty` attach to [send]: adopt the client's declared grid (if any),
 * then send the attach `Governance` + `Size` + synthesized redraw, followed by
 * the live [TermSession.events] — each gated so events already folded into the
 * attach payload are not re-sent. Suspends until the collecting coroutine is
 * cancelled.
 *
 * The attach frames are sent inside [onSubscription], which runs after the
 * subscription is registered but before any event is delivered to this collector.
 * That closes the gap the old snapshot-then-subscribe flow had: an event emitted
 * between capturing the redraw and starting to collect can't be lost, because the
 * subscription already exists when [attachPayload] samples the seq, and any event
 * with a greater seq is delivered by the collector.
 *
 * Factored out of the socket handler so the ordering (Governance-before-Size,
 * Size-before-redraw, seq gating) is unit-testable without a real WebSocket.
 *
 * @param clientId this connection's size-arbitration id.
 * @param qCols the client's declared grid width from the connect URL, or null.
 * @param qRows the client's declared grid height, or null.
 * @param send sink for outbound frames (the WebSocket's `send`, or a test double).
 * @see TermSession.attachPayload
 */
internal suspend fun TermSession.streamAttach(
    clientId: String,
    qCols: Int?,
    qRows: Int?,
    send: suspend (Frame) -> Unit,
) {
    if (qCols != null && qRows != null && qCols > 0 && qRows > 0) {
        setClientSize(clientId, qCols, qRows)
    }
    var attachSeq = Long.MIN_VALUE
    events
        .onSubscription {
            val attach = attachPayload()
            attachSeq = attach.seq
            // Governance first: clients decide mirror-vs-driving inside their Size
            // handler, so the verdict must be current when the Size lands — Size
            // first meant it was judged with the previous connection's answer. And
            // both before the redraw, or the client renders one frame at the wrong
            // size/presentation and corrects visibly.
            send(Frame.Text(governanceFrame(clientId, attach.governorClientId)))
            send(Frame.Text(sizeFrame(attach.cols, attach.rows)))
            if (attach.bytes.isNotEmpty()) send(Frame.Binary(true, attach.bytes))
        }
        .collect { ev ->
            when (ev) {
                is SessionEvent.Output -> if (ev.seq > attachSeq) send(Frame.Binary(true, ev.bytes))
                is SessionEvent.Size -> if (ev.seq > attachSeq) send(Frame.Text(sizeFrame(ev.cols, ev.rows)))
                is SessionEvent.Governance ->
                    if (ev.seq > attachSeq) send(Frame.Text(governanceFrame(clientId, ev.governorClientId)))
            }
        }
}

/**
 * Encode a `Governance` control frame for one connection.
 *
 * The broadcast event carries the governor's *id*; each connection resolves it
 * against its own id here, so a client is told only whether it is driving and
 * never learns another client's identity.
 *
 * @param clientId this connection's id.
 * @param governorClientId the governing client's id, or null when none governs.
 * @return the JSON control frame body.
 * @see PtyServerMessage.Governance
 */
private fun governanceFrame(clientId: String, governorClientId: String?): String =
    windowJson.encodeToString<PtyServerMessage>(
        PtyServerMessage.Governance(
            driving = governorClientId != null && governorClientId == clientId,
            governed = governorClientId != null,
        )
    )

/**
 * Encode a `Size` control frame carrying the authoritative PTY grid.
 *
 * @param cols the effective PTY columns.
 * @param rows the effective PTY rows.
 * @return the JSON control frame body.
 */
private fun sizeFrame(cols: Int, rows: Int): String =
    windowJson.encodeToString<PtyServerMessage>(PtyServerMessage.Size(cols, rows))

/**
 * Handle a JSON control message received on a `/pty/{id}` WebSocket.
 *
 * Deserialises the text as a [PtyControl] and dispatches resize commands
 * to the [TerminalSession]. Unknown or malformed messages are silently
 * dropped.
 */
internal fun handleControl(session: TermSession, clientId: String, text: String) {
    val control = runCatching {
        controlJson.decodeFromString<PtyControl>(text)
    }.getOrNull() ?: return
    when (control) {
        // Every client keeps the tier it sent (NORMAL, or THREE_D when the 3D
        // world asserts a Pane.grid3d override). A phone no longer gets a
        // special tier — it governs by posture/take-over, not by winning min().
        is PtyControl.Resize ->
            session.setClientSize(clientId, control.cols, control.rows, control.priority)
        is PtyControl.ForceResize ->
            session.forceClientSize(clientId, control.cols, control.rows, control.priority)
        is PtyControl.ResetModes -> session.resetTerminalModes()
    }
}

/**
 * Decide the connecting client's [ClientPosture] for size governance.
 *
 * Prefers the client's own declaration — a `posture=viewer|driver` query param
 * on the `/pty` URL (the web has no request-header channel, so a query param is
 * the portable way for it to declare intent). When absent or unrecognised it
 * falls back to sniffing the reported client [type] via [isMobileClientType]:
 * a phone/tablet defaults to [ClientPosture.VIEWER] (it mirrors the desktop
 * until the user takes over), everything else to [ClientPosture.DRIVER]. This
 * keeps older clients that send no `posture` behaving sensibly.
 *
 * @param type the self-reported client type (from [readClientInfo]).
 * @return the posture to register with the session for this connection.
 * @see ClientSizeArbiter.setPosture
 */
internal fun io.ktor.server.application.ApplicationCall.readClientPosture(
    type: String,
): ClientPosture = when (request.queryParameters["posture"]?.lowercase()) {
    "viewer" -> ClientPosture.VIEWER
    "driver" -> ClientPosture.DRIVER
    else -> if (isMobileClientType(type)) ClientPosture.VIEWER else ClientPosture.DRIVER
}

/**
 * Classify a reported client [type] (the `X-Termtastic-Client-Type` /
 * `clientType` value — e.g. `"Web"`, `"Computer"`, `"Android"`, `"iOS"`) as a
 * phone/tablet. Used by [readClientPosture] as the *fallback* posture signal
 * when a client sends no explicit `posture=` declaration: a mobile client
 * defaults to a viewer that mirrors the desktop. Matched leniently (substring,
 * case-insensitive) so future mobile type strings (`"iPhone"`, `"iPad"`, …) are
 * covered without another code change.
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
