/* SettingsPersisterAdapter.kt (jsMain)
 * Adapter that exposes Lunamux's existing [SettingsPersister] (the
 * server-backed REST poster wired up in `main.kt`'s `start()`) as a
 * darkness-toolkit [Persister]. Lets Lunamux consume the toolkit's
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
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.darkness.core.Persister
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.lunamux.client.viewmodel.AppBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.SettingsPersister

/**
 * Wraps a Lunamux [SettingsPersister] so the toolkit can read and
 * write theme / layout state through its standard [Persister] contract.
 *
 * @property settingsPersister the lunamux-side persister (server REST bridge).
 * @property snapshot a closure returning the most recent server-side
 *   UI-settings JSON object. The adapter calls this on every `read` so
 *   it always sees the latest state — Lunamux re-emits the snapshot
 *   whenever the server pushes a UiSettings envelope.
 * @property appVm the live [AppBackingViewModel]. Toolkit-side writes of
 *   [PersistKeys.UI_SETTINGS] are mirrored into the appVm in-memory
 *   state synchronously so Lunamux readers (xterm theme apply,
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
        // Route per-world layout keys to their server key (the default world's
        // aliases back onto flat LAYOUT_STATE) so the toolkit's world-uniform
        // keys resolve to the old-client-compatible storage. Non-layout keys
        // pass through unchanged. See [WorldLayoutKeys].
        val element = snapshot()[serverKeyForToolkitKey(key)] ?: return null
        return when (element) {
            is JsonPrimitive -> if (element.isString) element.content else element.toString()
            else -> element.toString()
        }
    }

    override suspend fun write(key: String, value: String) {
        // The toolkit-side Theme Manager persists its dual-slot choice +
        // appearance under THEME_V2_SELECTION and its custom themes under
        // THEME_V2_CUSTOM. When the selection blob is written we mirror its
        // appearance + slot names into the appVm immediately (no server-echo
        // wait) so Lunamux's own painters (xterm, `--t-*` CSS vars) stay
        // in lockstep with the chrome.
        val outgoing = if (key == PersistKeys.THEME_V2_SELECTION) {
            val pinned = pinSelectionSlotsToDefaultWorld(value)
            appVm.applyToolkitUiSettingsBlob(pinned)
            pinned
        } else {
            value
        }
        // Alias the default world's per-world layout key onto flat LAYOUT_STATE
        // (non-default worlds and non-layout keys pass through) so old clients
        // and existing saved data keep reading the default world's layout under
        // the legacy key. See [WorldLayoutKeys.serverKeyForToolkitKey].
        settingsPersister.putSetting(serverKeyForToolkitKey(key), outgoing)
    }

    /**
     * Keep the global `THEME_V2_SELECTION` key's **slot names** pinned to the
     * default world's pair, while preserving the incoming blob's **appearance**.
     *
     * `THEME_V2_SELECTION` is the legacy global blob (`{darkThemeName,
     * lightThemeName, appearance}`) that pre-1.9 clients read, and its slots are
     * the *default world's* pair (server-maintained via
     * `SettingsRepository.mirrorDefaultWorldThemePair`). Per-world theme picks
     * never reach this adapter — they route through
     * [LunamuxThemeManagerHost.setDarkThemeName] → `SetWorldTheme`. This write
     * only fires from the toolkit's own `persistUi` (an appearance change), and
     * the blob it emits carries the **active** world's slots (because
     * `refreshAndApplyActiveTheme` pushes the active world's pair into the
     * toolkit snapshot for chrome repaint). If the active world is a *non-default*
     * world, letting those slots through would overwrite the global compat value
     * and the global `appVm` base with the wrong world's theme (issue #127).
     *
     * So: for a non-default active world, swap the blob's slots back to the
     * default world's pair (falling back to the current global `appVm` slots when
     * the default world follows global); appearance passes through unchanged. For
     * the default world — or a world-less (pre-1.9 server) config — the blob is
     * already correct and returned as-is.
     *
     * @param blob the toolkit's `THEME_V2_SELECTION` JSON string.
     * @return the blob with its slots pinned to the default world's pair when the
     *   active world is non-default; otherwise the input unchanged.
     */
    private fun pinSelectionSlotsToDefaultWorld(blob: String): String {
        val cfg = lunamuxClient.windowState.config.value ?: return blob
        val defaultWorld = cfg.worlds.firstOrNull() ?: return blob
        val activeWorld = cfg.activeWorldOrNull() ?: return blob
        if (activeWorld.id == defaultWorld.id) return blob
        val snap = ThemeSnapshotV2.fromStrings(selectionJson = blob, customThemesJson = null)
        val dark = defaultWorld.themeSelection?.darkThemeName ?: appVm.stateFlow.value.darkThemeName
        val light = defaultWorld.themeSelection?.lightThemeName ?: appVm.stateFlow.value.lightThemeName
        return snap.copy(darkThemeName = dark, lightThemeName = light).selectionJson()
    }
}
