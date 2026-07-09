/**
 * Server URL resolution for the Lunamux client.
 *
 * [ServerUrl] is the single source of truth for constructing HTTP and WebSocket
 * URLs to the Ktor backend. Every endpoint the client calls -- `/window`,
 * `/pty/{id}`, `/api/ui-settings` -- is derived through [ServerUrl.httpUrl] or
 * [ServerUrl.wsUrl] so scheme, host, and port are always consistent.
 *
 * @see LunamuxClient
 */
package se.soderbjorn.lunamux.client

/**
 * Immutable descriptor of the server this client should talk to. Encapsulates
 * the host/port split so the various websocket and REST endpoints (`/window`,
 * `/pty/{id}`, `/api/ui-settings`) can be derived consistently.
 *
 * Scheme is always TLS — `https` for HTTP, `wss` for WebSocket — because the
 * server only binds a TLS listener (see `SERVER_TLS_PORT`).
 *
 * @property host hostname or IP address of the Lunamux server.
 * @property port TCP port the server listens on (no default).
 */
data class ServerUrl(
    val host: String,
    val port: Int,
) {
    /** Always `"https"` — the server is TLS-only. */
    val httpScheme: String get() = "https"
    /** Always `"wss"` — the server is TLS-only. */
    val wsScheme: String get() = "wss"

    /**
     * Build a fully qualified HTTP URL for the given server [path].
     *
     * @param path the endpoint path, e.g. `"/api/ui-settings"`.
     * @return the complete URL string.
     */
    fun httpUrl(path: String): String = "$httpScheme://$host:$port${ensureSlash(path)}"

    /**
     * Build a fully qualified WebSocket URL for the given server [path].
     *
     * @param path the endpoint path, e.g. `"/window"`.
     * @return the complete URL string.
     */
    fun wsUrl(path: String): String = "$wsScheme://$host:$port${ensureSlash(path)}"

    private fun ensureSlash(path: String): String = if (path.startsWith("/")) path else "/$path"
}
