/**
 * `/admin/...` routes for graceful server lifecycle control from the
 * Electron host process.
 *
 * The bundled server is intentionally long-lived — terminal sessions
 * survive Electron UI restarts. To install a new Termtastic version,
 * however, the server must stop so the .app bundle's `server.jar` can
 * be replaced. This file exposes a single localhost-only HTTP endpoint
 * that lets Electron trigger that stop, with the same final-flush
 * guarantees as the JVM shutdown hook in [main].
 *
 * @see Application.module
 * @see ServerInitializer
 */
package se.soderbjorn.termtastic

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory
import se.soderbjorn.termtastic.persistence.SettingsRepository
import kotlin.system.exitProcess

private val LOG = LoggerFactory.getLogger("AdminRoutes")

/**
 * Returns true for any loopback host the Ktor request can report. We
 * accept the IPv4 and IPv6 short and long forms so a request that
 * arrives on the IPv6 loopback isn't rejected.
 *
 * @param host the resolved remote host string from the request origin
 * @return whether [host] is a localhost address
 */
private fun isLocalhost(host: String?): Boolean =
    host == "127.0.0.1" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host.equals("localhost", ignoreCase = true)

/**
 * Mount the `/admin/...` REST endpoints on this [Route].
 *
 * Currently exposes:
 *  - `POST /admin/shutdown` — synchronously flushes the same state the
 *    JVM shutdown hook flushes (window config and scrollback for every
 *    live leaf), responds `200 OK`, then triggers `exitProcess(0)` from
 *    a short-delayed daemon thread so Netty has time to finish writing
 *    the response before the JVM dies.
 *
 * The shutdown route is gated on the request originating from the
 * loopback interface. The server binds to `0.0.0.0` (so the "allow
 * connections from other sources than localhost" setting can be flipped
 * at runtime) but `/admin/shutdown` is never reachable across the
 * network — only the embedding Electron host process can stop it.
 *
 * Called by [Application.module] from `Application.kt`.
 *
 * @param settingsRepo    the SQLite-backed settings store, shared with the rest of the server
 * @param scrollbackSaver the periodic scrollback flusher returned by [installScrollbackSaver]
 *
 * @see ScrollbackSaver.saveAll
 * @see SettingsRepository.saveWindowConfig
 */
internal fun Route.adminRoutes(
    settingsRepo: SettingsRepository,
    scrollbackSaver: ScrollbackSaver,
) {
    post("/admin/shutdown") {
        val origin = call.request.origin
        if (!isLocalhost(origin.remoteHost)) {
            LOG.warn("Refusing /admin/shutdown from non-loopback origin: ${origin.remoteHost}")
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        // Synchronous flush before the 200 response: returning OK is
        // the contract Electron relies on to know that all unsaved
        // window config and scrollback have hit disk. The JVM shutdown
        // hook flushes again on exit (idempotent), but waiting for it
        // would mean returning 200 before the data is durable.
        runCatching {
            settingsRepo.saveWindowConfig(WindowState.config.value.withBlankSessionIds())
            scrollbackSaver.saveAll(force = true)
        }.onFailure {
            LOG.error("Final flush failed during /admin/shutdown", it)
        }
        LOG.info("/admin/shutdown accepted; scheduling JVM exit")
        call.respond(HttpStatusCode.OK)
        // Daemon thread with a small delay so Netty can flush the
        // response bytes before exitProcess tears the JVM down. 200 ms
        // is well above any localhost loopback round-trip yet still
        // imperceptible to the user.
        Thread {
            Thread.sleep(200)
            exitProcess(0)
        }.also { it.isDaemon = true; it.name = "admin-shutdown" }.start()
    }
}
