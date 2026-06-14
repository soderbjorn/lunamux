/**
 * Android activity entry point for the Termtastic app.
 *
 * Defines the Material 3 dark and light color schemes used throughout the app and
 * hosts the single [MainActivity] that bootstraps Jetpack Compose rendering with
 * edge-to-edge display support. The activity delegates all UI to [TermtasticApp].
 *
 * @see se.soderbjorn.termtastic.android.ui.TermtasticApp
 */
package se.soderbjorn.termtastic.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import se.soderbjorn.termtastic.android.ui.TermtasticApp

/** Dark Material 3 colour scheme using amber as the primary accent and green as secondary. */
private val TermtasticDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF4B869),        // Amber accent
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = Color(0xFF5A4A2A),
    onPrimaryContainer = Color(0xFFF4B869),
    secondary = Color(0xFF65DA82),       // Brand green
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2A4A30),
    onSecondaryContainer = Color(0xFF65DA82),
    surface = Color(0xFF2C2C2E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFF8E8E93),
    background = Color(0xFF1C1C1E),
    onBackground = Color(0xFFF5F5F5),
    error = Color(0xFFFF6961),
    onError = Color(0xFF1C1C1E),
)

/** Light Material 3 colour scheme, mirroring the dark variant with adjusted contrast. */
private val TermtasticLightColorScheme = lightColorScheme(
    primary = Color(0xFFD4943D),        // Darker amber for light
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFF0D6),
    onPrimaryContainer = Color(0xFF5A4A2A),
    secondary = Color(0xFF3DAA55),       // Darker green for light
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6F5DD),
    onSecondaryContainer = Color(0xFF2A4A30),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF6E6E73),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1C1C1E),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
)

/**
 * Single-activity entry point for the Termtastic Android app.
 *
 * Enables edge-to-edge rendering, applies the adaptive dark/light Material 3
 * theme, and sets [TermtasticApp] as the root composable inside a full-screen
 * [Surface].
 */
class MainActivity : ComponentActivity() {
    /** @param savedInstanceState standard Android saved-state bundle (unused). */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = if (isSystemInDarkTheme()) TermtasticDarkColorScheme else TermtasticLightColorScheme
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TermtasticApp(applicationContext = applicationContext)
                }
            }
        }
    }
}
