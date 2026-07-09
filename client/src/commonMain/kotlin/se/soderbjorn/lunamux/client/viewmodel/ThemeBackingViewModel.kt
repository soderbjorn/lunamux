/**
 * Backing ViewModel for the mobile (Android / iOS) appearance + theme picker.
 *
 * The mobile sessions view lets the user change the **appearance** (Auto / Light
 * / Dark) and pick a **theme** exactly as if they had done it in the Mac /
 * Electron app: it reads and writes the *same* canonical server blobs
 * ([PersistKeys.THEME_V2_SELECTION] + [PersistKeys.THEME_V2_CUSTOM]) rather than
 * any mobile-specific override. Writing those keys round-trips through
 * `POST /api/ui-settings`, which merges, persists, and broadcasts the change to
 * every connected client — so a change made on the phone shows up on the desktop
 * and vice-versa.
 *
 * This is the *write* counterpart to the read-only [LunamuxThemeConfig] path:
 * it seeds itself from [LunamuxClient.fetchThemeConfig] and then owns the
 * live [ThemeSnapshotV2] so the picker UI and the rest of the app repaint the
 * instant a setting changes (before the server round-trip completes).
 *
 * Mirrors the persistence logic of the web/desktop
 * [AppBackingViewModel.persistThemeSnapshot]; kept as a focused VM because the
 * mobile clients do not otherwise instantiate the full [AppBackingViewModel].
 *
 * @see LunamuxThemeConfig
 * @see ThemeSnapshotV2
 * @see SettingsPersister
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.PersistKeys
import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.Theme
import se.soderbjorn.darkness.core.ThemeGroup
import se.soderbjorn.darkness.core.ThemeSnapshotV2
import se.soderbjorn.darkness.core.allThemes
import se.soderbjorn.darkness.core.orderThemesForPicker
import se.soderbjorn.lunamux.client.LunamuxClient
import se.soderbjorn.lunamux.client.fetchThemeConfig

/**
 * Owns the live, editable canonical theme snapshot for the mobile clients.
 *
 * Constructed once per connection by the Android/iOS theme host, seeded via
 * [load], observed through [snapshot], and mutated through [setAppearance] /
 * [setActiveTheme].
 *
 * @param client    the connected client whose `/api/ui-settings` endpoint is the
 *   source of truth for the canonical theme selection.
 * @param persister the sink for setting writes; defaults to an
 *   [HttpSettingsPersister] over [client], matching every other mobile write.
 */
class ThemeBackingViewModel(
    private val client: LunamuxClient,
    private val persister: SettingsPersister = HttpSettingsPersister(client),
) {
    private val _snapshot = MutableStateFlow(ThemeSnapshotV2())

    /**
     * The current canonical selection (dark/light slot names, appearance, and
     * the shared custom themes). UI layers collect this and resolve it against
     * the host's dark-mode flag to drive their palette.
     */
    val snapshot: StateFlow<ThemeSnapshotV2> = _snapshot.asStateFlow()

    /**
     * Grouped catalog of pickable themes, mirroring the web theme manager's
     * "Dark" / "Light" sections.
     *
     * @property dark  themes whose [Theme.group] is [ThemeGroup.Dark].
     * @property light themes whose [Theme.group] is [ThemeGroup.Light].
     */
    data class GroupedThemes(val dark: List<Theme>, val light: List<Theme>)

    /**
     * Seed the snapshot from the server's current selection. No-op (keeps
     * defaults) if the request is rejected/fails — callers keep showing the
     * built-in default theme in that case.
     *
     * Called once when the connection is established (and may be called again to
     * re-sync after a reconnect).
     */
    suspend fun load() {
        val config = client.fetchThemeConfig() ?: return
        _snapshot.value = config.studio
    }

    /**
     * Resolve the active slot to a flat [ResolvedTheme] for the given system
     * dark-mode flag. Pure delegation to [ThemeSnapshotV2.resolve].
     *
     * @param systemIsDark whether the host OS is currently in dark mode (only
     *   consulted when the appearance is [Appearance.Auto]).
     * @return the resolved 19-token palette for the active slot.
     */
    fun resolve(systemIsDark: Boolean): ResolvedTheme = _snapshot.value.resolve(systemIsDark)

    /** The user's current Auto / Light / Dark preference. */
    val appearance: Appearance get() = _snapshot.value.appearance

    /**
     * The full pickable theme catalog (built-ins ∪ the user's custom themes),
     * split into the dark and light sections.
     *
     * Retained for compatibility; the mobile pickers now render a single list
     * via [themesOrdered] (issue #107). Built-ins are always present.
     *
     * @return the grouped catalog; built-ins are always present, even when the
     *   user has no custom themes.
     */
    fun themesGrouped(): GroupedThemes {
        val all = allThemes(_snapshot.value.customThemes)
        return GroupedThemes(
            dark = all.filter { it.group == ThemeGroup.Dark },
            light = all.filter { it.group == ThemeGroup.Light },
        )
    }

    /**
     * The full pickable catalog as a single list ordered for the post-#107
     * picker: starred dark → starred light → unstarred dark → unstarred light
     * (see [orderThemesForPicker]). This is what the Android / iOS sheets render
     * now that the "Dark"/"Light" headings are gone.
     *
     * @return the ordered single list; built-ins are always present.
     */
    fun themesOrdered(): List<Theme> =
        orderThemesForPicker(allThemes(_snapshot.value.customThemes), favorites)

    /** Names of the user's starred / favorite themes. */
    val favorites: Set<String> get() = _snapshot.value.favorites.toSet()

    /**
     * Whether [name] is currently starred. Convenience for the pickers' star
     * affordance (filled vs hollow, "Star" vs "Unstar" menu label).
     *
     * @param name the theme name to test.
     * @return `true` if the theme is favorited.
     */
    fun isFavorite(name: String): Boolean = name in _snapshot.value.favorites

    /**
     * Update the appearance preference and persist it. Emits the new snapshot
     * immediately so the UI repaints before the server round-trip.
     *
     * @param appearance the new Auto / Light / Dark preference.
     */
    suspend fun setAppearance(appearance: Appearance) {
        _snapshot.value = _snapshot.value.copy(appearance = appearance)
        persist()
    }

    /**
     * Whether the *active* slot — the one [ThemeSnapshotV2.resolve] reads for
     * painting — is the dark slot, given the host OS dark-mode flag.
     *
     * Used by the pickers to decide which slot a tap fills ([setActiveTheme])
     * and which assigned theme to highlight, mirroring the web theme manager's
     * `isDarkActive` rule.
     *
     * @param systemIsDark whether the host OS is currently in dark mode (only
     *   consulted when the appearance is [Appearance.Auto]).
     * @return `true` when the dark slot is active, `false` for the light slot.
     */
    fun activeSlotIsDark(systemIsDark: Boolean): Boolean =
        when (_snapshot.value.appearance) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.Auto -> systemIsDark
        }

    /**
     * Assign [name] to whichever slot is *currently active* (the appearance
     * preference, or the OS dark-mode flag when Auto) — exactly like clicking a
     * theme card in the Mac/Electron theme manager. The user is free to assign
     * any theme to either slot; the picker's dark/light sections are just a
     * catalog grouping, not the write target (issue #97). Because the written
     * slot is the one currently displayed, every pick takes visible effect
     * immediately. Emits the new snapshot right away and persists.
     *
     * @param name         the theme to assign to the active slot.
     * @param systemIsDark whether the host OS is currently in dark mode (only
     *   consulted when the appearance is [Appearance.Auto]).
     * @see activeSlotIsDark
     */
    suspend fun setActiveTheme(name: String, systemIsDark: Boolean) {
        val cur = _snapshot.value
        _snapshot.value = if (activeSlotIsDark(systemIsDark)) {
            cur.copy(darkThemeName = name)
        } else {
            cur.copy(lightThemeName = name)
        }
        persist()
    }

    /**
     * Toggle whether [name] is starred / favorited and persist the change. Emits
     * the new snapshot immediately so the picker re-orders and repaints the star
     * before the server round-trip. Invoked from the mobile long-press context
     * menu (issue #107); the starred set syncs to every connected client.
     *
     * @param name the theme to star or unstar.
     */
    suspend fun toggleFavorite(name: String) {
        val cur = _snapshot.value
        val next = if (name in cur.favorites) cur.favorites - name else cur.favorites + name
        _snapshot.value = cur.copy(favorites = next)
        persist()
    }

    /**
     * Persist the canonical v2 snapshot: the per-app dual-slot selection +
     * appearance under [PersistKeys.THEME_V2_SELECTION], the shared custom themes
     * under [PersistKeys.THEME_V2_CUSTOM], and the per-app starred-theme names
     * under [PersistKeys.THEME_V2_FAVORITES], in one batch — identical to
     * [AppBackingViewModel.persistThemeSnapshot].
     */
    private suspend fun persist() {
        val snap = _snapshot.value
        persister.putSettings(
            mapOf(
                PersistKeys.THEME_V2_SELECTION to snap.selectionJson(),
                PersistKeys.THEME_V2_CUSTOM to snap.customThemesJson(),
                PersistKeys.THEME_V2_FAVORITES to snap.favoritesJson(),
            ),
        )
    }
}
