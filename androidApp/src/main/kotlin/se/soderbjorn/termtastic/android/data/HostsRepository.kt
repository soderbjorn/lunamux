/**
 * Persistence layer for the user's saved server hosts.
 *
 * Host entries are serialised as a JSON array inside the same Jetpack DataStore
 * instance used by [ConnectionRepository]. The [HostsRepository] class exposes
 * a reactive [Flow] of [HostEntry] items and CRUD operations consumed by the
 * [se.soderbjorn.termtastic.android.ui.HostsScreen] composable.
 *
 * @see HostsRepository
 * @see se.soderbjorn.termtastic.android.ui.HostsScreen
 */
package se.soderbjorn.termtastic.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import se.soderbjorn.termtastic.client.HostEntry
import java.util.UUID

private val HOSTS_JSON = stringPreferencesKey("hosts_json")

private val json = Json { ignoreUnknownKeys = true }

/**
 * CRUD repository for the user's saved server host entries.
 *
 * Host entries are serialised as a JSON array inside the shared
 * [connectionDataStore]. The repository provides a reactive [hosts] flow
 * for observation and suspend functions for mutations.
 *
 * @param context application context used to access the DataStore.
 * @see se.soderbjorn.termtastic.client.HostEntry
 */
class HostsRepository(private val context: Context) {

    /** Reactive stream of all persisted host entries, emitting on every change. */
    val hosts: Flow<List<HostEntry>> =
        context.connectionDataStore.data.map { prefs -> decode(prefs[HOSTS_JSON]) }

    /**
     * Returns a snapshot of the current host list.
     *
     * @return the latest persisted list of [HostEntry] items.
     */
    suspend fun current(): List<HostEntry> = hosts.first()

    /**
     * Creates and persists a new host entry with a generated UUID.
     *
     * @param label user-visible display name for the host.
     * @param host hostname or IP address of the server.
     * @param port TCP port of the server.
     * @return the newly created [HostEntry].
     */
    suspend fun add(label: String, host: String, port: Int): HostEntry {
        val entry = HostEntry(id = UUID.randomUUID().toString(), label = label, host = host, port = port)
        mutate { it + entry }
        return entry
    }

    /**
     * Replaces an existing host entry by matching on [HostEntry.id].
     *
     * @param entry the updated entry whose id must already exist in the list.
     */
    suspend fun update(entry: HostEntry) {
        mutate { list -> list.map { if (it.id == entry.id) entry else it } }
    }

    /**
     * Removes the host entry with the given [id] from the persisted list.
     *
     * @param id UUID of the entry to remove.
     */
    suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    /**
     * Atomically reads, transforms, and writes the hosts list inside a DataStore transaction.
     *
     * @param transform function that receives the current list and returns the new list.
     */
    private suspend fun mutate(transform: (List<HostEntry>) -> List<HostEntry>) {
        context.connectionDataStore.edit { prefs ->
            val next = transform(decode(prefs[HOSTS_JSON]))
            prefs[HOSTS_JSON] = json.encodeToString(next)
        }
    }

    /**
     * Deserialises a JSON string into a list of [HostEntry] items.
     *
     * Returns an empty list on null/blank input or parse failure.
     *
     * @param raw the raw JSON string from the DataStore, or null.
     * @return the decoded list, never null.
     */
    private fun decode(raw: String?): List<HostEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<HostEntry>>(raw) }.getOrDefault(emptyList())
    }
}
