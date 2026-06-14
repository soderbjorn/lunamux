/* SettingsPersisterAdapter.kt (jsMain)
 * Adapter that exposes termtastic's existing [SettingsPersister] (the
 * server-backed REST poster wired up in `main.kt`'s `start()`) as a
 * darkness-toolkit [Persister]. Lets termtastic consume the toolkit's
 * canonical persistence abstraction without otherwise restructuring
 * its REST bridge.
 *
 * Reads route through a server-side snapshot populated each time
 * `applyServerUiSettings` lands a payload from the server. The
 * snapshot is a [JsonObject] in canonical nested form (the same shape
 * every Darkness app stores in `themes.json`); per-key reads extract
 * the corresponding element and serialise it to the string the toolkit
 * contract expects. Writes round-trip back through
 * `SettingsPersister.putSetting`. */
package se.soderbjorn.termtastic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.darkness.core.Persister
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.termtastic.client.viewmodel.AppBackingViewModel
import se.soderbjorn.termtastic.client.viewmodel.SettingsPersister

/**
 * Wraps a termtastic [SettingsPersister] so the toolkit can read and
 * write theme / layout state through its standard [Persister] contract.
 *
 * @property settingsPersister the termtastic-side persister (server REST bridge).
 * @property snapshot a closure returning the most recent server-side
 *   UI-settings JSON object. The adapter calls this on every `read` so
 *   it always sees the latest state — termtastic re-emits the snapshot
 *   whenever the server pushes a UiSettings envelope.
 * @property appVm the live [AppBackingViewModel]. Toolkit-side writes of
 *   [PersistKeys.UI_SETTINGS] are mirrored into the appVm in-memory
 *   state synchronously so termtastic readers (xterm theme apply,
 *   theme-card swatches, theme-editor mode-grouping) don't have to wait
 *   for a server-echo roundtrip to see the new appearance.
 */
class SettingsPersisterAdapter(
    private val settingsPersister: SettingsPersister,
    private val snapshot: () -> JsonObject,
    private val appVm: AppBackingViewModel,
) : Persister {
    /**
     * Toolkit contract is `String?` per key. For nested-JSON elements
     * we serialise back to JSON. For primitive strings we return the
     * underlying content. Missing keys return null.
     */
    override suspend fun read(key: String): String? {
        val element = snapshot()[key] ?: return null
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.content else element.toString()
            else -> element.toString()
        }
    }

    override suspend fun write(key: String, value: String) {
        if (key == PersistKeys.UI_SETTINGS) {
            kotlinx.browser.window.asDynamic().console
                .log("[settings-adapter] write UI_SETTINGS entry; appVm.appearance before=" +
                    appVm.stateFlow.value.appearance.name + " blob=" + value)
            appVm.applyToolkitUiSettingsBlob(value)
            kotlinx.browser.window.asDynamic().console
                .log("[settings-adapter] applyToolkitUiSettingsBlob returned; " +
                    "appVm.appearance now=" + appVm.stateFlow.value.appearance.name)
        } else {
            kotlinx.browser.window.asDynamic().console
                .log("[settings-adapter] write key=$key (len=${value.length})")
        }
        settingsPersister.putSetting(key, value)
        if (key == PersistKeys.UI_SETTINGS) {
            kotlinx.browser.window.asDynamic().console
                .log("[settings-adapter] POST for UI_SETTINGS dispatched")
        }
    }
}
