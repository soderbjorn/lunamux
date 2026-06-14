/**
 * Shared network constants used by both the Ktor server and all client targets
 * (Electron, Android, iOS) to agree on connection endpoints.
 */
package se.soderbjorn.termtastic

/**
 * The TCP port the Ktor backend listens on for TLS-only HTTPS/WSS connections.
 * Defined here in the shared module so every platform target resolves the same
 * value at compile time. There is no plain-HTTP fallback: the server only
 * binds an `sslConnector` on this port (see `Application.kt`), and every
 * client constructs `https://`/`wss://` URLs against it.
 *
 * Clients pin the server's self-signed leaf certificate via SHA-256 fingerprint
 * after a TOFU first-connect (see `PinnedHttpClientFactory`); the Electron
 * bundled app reads the local fingerprint sidecar at startup so the loopback
 * connect never triggers a `certificate-error`.
 */
const val SERVER_TLS_PORT = 8443