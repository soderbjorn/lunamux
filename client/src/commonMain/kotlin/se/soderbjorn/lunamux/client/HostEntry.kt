/**
 * Data model for a saved server/host entry in the Lunamux connection list.
 *
 * Mobile clients (Android, iOS) persist a list of [HostEntry] items so users
 * can switch between multiple Lunamux servers without re-typing addresses.
 *
 * @see se.soderbjorn.lunamux.client.ServerUrl
 */
package se.soderbjorn.lunamux.client

import kotlinx.serialization.Serializable

/**
 * A single saved Lunamux server endpoint shown in the host picker UI.
 *
 * The server speaks TLS only (see `SERVER_TLS_PORT`); every connection is
 * `https`/`wss`. [pinnedFingerprintHex] is filled in the first time a
 * connect succeeds — until then the client runs in TOFU capture mode and
 * the server's `DeviceAuth.ApprovalDialog` provides the trust ceremony. A
 * subsequent connect uses the stored pin in strict-verify mode; a mismatch
 * throws and surfaces the cert-changed UI.
 *
 * @property id                   Unique identifier for this entry (typically a UUID).
 * @property label                Human-readable display name chosen by the user.
 * @property host                 Hostname or IP address of the Lunamux server.
 * @property port                 TCP port the server listens on.
 * @property pinnedFingerprintHex Lowercase hex SHA-256 of the server's leaf cert
 *   captured on the first successful connect, or `null` until then. Used by
 *   `createPinnedHttpClient` to decide between capture and verify modes.
 *
 * @see se.soderbjorn.lunamux.client.ServerUrl
 * @see se.soderbjorn.lunamux.client.createPinnedHttpClient
 */
@Serializable
data class HostEntry(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val pinnedFingerprintHex: String? = null,
)
