/**
 * Demo-mode [WindowSocket]: a thin bridge between the consumer-facing
 * interface and the in-process [DemoServer]. No network, no handshake — the
 * fixture config is available synchronously, so `awaitSessionReady` and
 * `awaitInitialConfig` return immediately on every platform.
 *
 * @see WindowSocket
 * @see DemoServer
 * @see se.soderbjorn.termtastic.client.TermtasticClient.openWindowSocket
 */
package se.soderbjorn.termtastic.client.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.soderbjorn.termtastic.WindowCommand
import se.soderbjorn.termtastic.WindowConfig
import se.soderbjorn.termtastic.WindowEnvelope
import se.soderbjorn.termtastic.client.WindowSocket
import se.soderbjorn.termtastic.client.WindowStateRepository

/**
 * The window channel of demo mode. [config]/[states] read straight from the
 * client's [WindowStateRepository] (which [DemoServer] keeps up to date),
 * [send] forwards into the simulation, and [envelopes] mirrors the
 * simulation's replies — so view models, the web envelope router, and the
 * mobile screens all behave exactly as they do against a real server.
 *
 * @param server the in-process simulation this socket talks to.
 * @param windowState the client's config/state cache (seeded by [server]).
 */
class DemoWindowSocket internal constructor(
    private val server: DemoServer,
    private val windowState: WindowStateRepository,
) : WindowSocket {
    override val config: StateFlow<WindowConfig?> get() = windowState.config
    override val states: StateFlow<Map<String, String?>> get() = windowState.states
    override val envelopes: SharedFlow<WindowEnvelope> get() = server.envelopes

    /** Demo mode is always "connected" — there is nothing to disconnect from. */
    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** No-op: the fixture config is already in [windowState]. */
    @Throws(CancellationException::class, Exception::class)
    override suspend fun awaitInitialConfig() = Unit

    /** No-op: there is no transport handshake in demo mode. */
    @Throws(CancellationException::class, Exception::class)
    override suspend fun awaitSessionReady() = Unit

    @Throws(CancellationException::class, Exception::class)
    override suspend fun send(command: WindowCommand) {
        server.handle(command)
    }

    /** No-op: the simulation lives for the client's lifetime. */
    @Throws(CancellationException::class, Exception::class)
    override suspend fun close() = Unit
}
