/**
 * Tests that the appearance settings Lunamux persists for the toolkit — the
 * legacy look shorthand (Classic / Calm), the navigation mode, the surface style
 * (Depth / Flat / Soft) and the per-mode style settings ([ModeStyles]) — are
 * read back by [SettingsViewModel.applyServerUiSettings], the hydration path
 * used at boot and for settings pushed by another client.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import se.soderbjorn.lunula.core.AppearanceShape
import se.soderbjorn.lunula.core.FocusStyle
import se.soderbjorn.lunula.core.Looks
import se.soderbjorn.lunula.core.ModeStyles
import se.soderbjorn.lunula.core.NavigationMode
import se.soderbjorn.lunula.core.ShellStyle
import se.soderbjorn.lunula.core.SurfaceStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellAppearanceSettingsTest {

    private val settings = SettingsViewModel(settingsPersister = null)

    @Test
    fun persistedShellStyleAndNavigationModeAreReadBack() {
        val hydrated = settings.applyServerUiSettings(
            AppBackingViewModel.State(),
            buildJsonObject {
                put("shellStyle", JsonPrimitive("calm"))
                put("navigationMode", JsonPrimitive("tabstrip"))
            },
        )
        assertEquals(ShellStyle.Calm, hydrated.shellStyle)
        assertEquals(NavigationMode.TabStrip, hydrated.navigationMode)
    }

    @Test
    fun unsetKeysKeepTheDefaultsSoClassicStaysClassic() {
        val hydrated = settings.applyServerUiSettings(AppBackingViewModel.State(), buildJsonObject {})
        assertNull(hydrated.shellStyle, "no stored style must resolve to the toolkit default (Classic)")
        assertNull(hydrated.navigationMode, "no stored mode must resolve to the toolkit default (Both)")
        assertNull(hydrated.surfaceStyle, "no stored surfaces must resolve to the toolkit default (Depth)")
    }

    @Test
    fun absentOrUnknownValuesPreserveTheCurrentPick() {
        val cur = AppBackingViewModel.State(
            shellStyle = ShellStyle.Calm,
            navigationMode = NavigationMode.Sidebar,
        )
        val hydrated = settings.applyServerUiSettings(
            cur,
            buildJsonObject { put("shellStyle", JsonPrimitive("brutalist")) },
        )
        assertEquals(ShellStyle.Calm, hydrated.shellStyle)
        assertEquals(NavigationMode.Sidebar, hydrated.navigationMode)
    }

    @Test
    fun persistedSurfaceStyleIsReadBack() {
        val hydrated = settings.applyServerUiSettings(
            AppBackingViewModel.State(),
            buildJsonObject { put("surfaceStyle", JsonPrimitive("flat")) },
        )
        assertEquals(SurfaceStyle.Flat, hydrated.surfaceStyle)
    }

    @Test
    fun unknownSurfaceStylePreservesTheCurrentPick() {
        val hydrated = settings.applyServerUiSettings(
            AppBackingViewModel.State(surfaceStyle = SurfaceStyle.Flat),
            buildJsonObject { put("surfaceStyle", JsonPrimitive("glass")) },
        )
        assertEquals(SurfaceStyle.Flat, hydrated.surfaceStyle)
    }

    @Test
    fun persistedModeStylesAreReadBackPerMode() {
        val styles = ModeStyles(dark = Looks.Calm.shape, light = AppearanceShape(focusStyle = FocusStyle.Tone))
        val hydrated = settings.applyServerUiSettings(
            AppBackingViewModel.State(),
            buildJsonObject { put("modeStyles", JsonPrimitive(styles.toJson())) },
        )
        assertEquals(styles, hydrated.modeStyles)
        assertEquals(FocusStyle.Tone, hydrated.modeStyles?.forMode(isDark = false)?.focusStyle)
    }

    @Test
    fun missingOrUnreadableModeStylesKeepTheCurrentPair() {
        val cur = AppBackingViewModel.State(modeStyles = ModeStyles(dark = Looks.Calm.shape))
        val absent = settings.applyServerUiSettings(cur, buildJsonObject {})
        assertEquals(cur.modeStyles, absent.modeStyles)
        val garbage = settings.applyServerUiSettings(
            cur,
            buildJsonObject { put("modeStyles", JsonPrimitive("not json")) },
        )
        assertEquals(cur.modeStyles, garbage.modeStyles)
        assertNull(settings.applyServerUiSettings(AppBackingViewModel.State(), buildJsonObject {}).modeStyles)
    }
}
