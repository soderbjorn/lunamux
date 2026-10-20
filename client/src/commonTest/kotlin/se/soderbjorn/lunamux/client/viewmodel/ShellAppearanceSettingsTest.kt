/**
 * Tests that the shell style (Classic / Clean), navigation mode and surface
 * style (Depth / Flat) the Appearance settings persist are read back by
 * [SettingsViewModel.applyServerUiSettings], the hydration path used at boot
 * and for settings pushed by another client.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
                put("shellStyle", JsonPrimitive("clean"))
                put("navigationMode", JsonPrimitive("tabstrip"))
            },
        )
        assertEquals(ShellStyle.Clean, hydrated.shellStyle)
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
            shellStyle = ShellStyle.Clean,
            navigationMode = NavigationMode.Sidebar,
        )
        val hydrated = settings.applyServerUiSettings(
            cur,
            buildJsonObject { put("shellStyle", JsonPrimitive("brutalist")) },
        )
        assertEquals(ShellStyle.Clean, hydrated.shellStyle)
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
}
