/**
 * Connection settings persistence layer for the Termtastic Android app.
 *
 * Stores the active server host, port, and authentication token in Jetpack
 * DataStore (Preferences). Provides [ConnectionConfig] as the value type and
 * [ConnectionRepository] as the read/write API consumed by the connect flow
 * in [se.soderbjorn.termtastic.android.ui.HostsScreen].
 *
 * @see ConnectionRepository
 * @see se.soderbjorn.termtastic.android.ui.HostsScreen
 */
package se.soderbjorn.termtastic.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import se.soderbjorn.termtastic.client.AuthTokenStore

/**
 * Lazily-created Jetpack [DataStore] instance scoped to the application [Context].
 *
 * Stores connection settings (host, port, auth token) and the hosts JSON list.
 * Shared between [ConnectionRepository] and [HostsRepository].
 */
internal val Context.connectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "termtastic-connection")

/**
 * Immutable snapshot of the active server connection parameters.
 *
 * @property host hostname or IP address of the Termtastic server.
 * @property port TCP port the server listens on.
 */
data class ConnectionConfig(
    val host: String,
    val port: Int,
)

/** DataStore preference keys for connection-related values. */
object ConnectionKeys {
    val HOST = stringPreferencesKey("host")
    val PORT = intPreferencesKey("port")
    val AUTH_TOKEN = stringPreferencesKey("auth_token")
}

/** Default host address, pointing to the Android emulator's host loopback. */
const val DEFAULT_HOST = "10.0.2.2"

/** Default TCP port matching the Ktor server's default listen port. */
const val DEFAULT_PORT = 8083

/**
 * Read/write repository for the active server connection settings.
 *
 * Backed by Jetpack DataStore; exposes a reactive [Flow] for observing
 * changes and suspend functions for one-shot reads and writes.
 *
 * @param context application context used to access the DataStore.
 * @see ConnectionConfig
 */
class ConnectionRepository(private val context: Context) {
    /** Reactive stream of the current [ConnectionConfig], emitting on every change. */
    val connection: Flow<ConnectionConfig> =
        context.connectionDataStore.data.map { prefs ->
            ConnectionConfig(
                host = prefs[ConnectionKeys.HOST] ?: DEFAULT_HOST,
                port = prefs[ConnectionKeys.PORT] ?: DEFAULT_PORT,
            )
        }

    /**
     * Returns the current [ConnectionConfig] snapshot.
     *
     * @return the latest persisted connection config.
     */
    suspend fun current(): ConnectionConfig = connection.first()

    /**
     * Persists new host and port values to the DataStore.
     *
     * @param host hostname or IP address of the server.
     * @param port TCP port of the server.
     */
    suspend fun save(host: String, port: Int) {
        context.connectionDataStore.edit { prefs ->
            prefs[ConnectionKeys.HOST] = host
            prefs[ConnectionKeys.PORT] = port
        }
    }

    /**
     * Creates an [AuthTokenStore] adapter backed by this DataStore.
     *
     * The returned store uses blocking coroutine calls so it can satisfy the
     * synchronous [AuthTokenStore] interface required by the shared client library.
     *
     * @return an [AuthTokenStore] that reads and writes the auth token preference.
     */
    fun authTokenStore(): AuthTokenStore = object : AuthTokenStore {
        override fun load(): String? = runCatching {
            kotlinx.coroutines.runBlocking {
                context.connectionDataStore.data.first()[ConnectionKeys.AUTH_TOKEN]
            }
        }.getOrNull()

        override fun save(token: String) {
            kotlinx.coroutines.runBlocking {
                context.connectionDataStore.edit { prefs ->
                    prefs[ConnectionKeys.AUTH_TOKEN] = token
                }
            }
        }
    }
}
