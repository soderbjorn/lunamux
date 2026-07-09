/**
 * Above-keyboard toolbar that synthesises escape sequences for the keys
 * that phone keyboards don't ship: Esc, Ctrl (sticky), Shift (sticky),
 * Tab, the four arrows, and Home/End/PgUp/PgDn.
 *
 * Ctrl and Shift are modelled as sticky modifiers — tap to arm, tap
 * again to unarm — which the next [TerminalView] key event can read via
 * `TerminalViewClient.readControlKey` and `readShiftKey`.
 *
 * @see TerminalScreen
 */
package se.soderbjorn.lunamux.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.soderbjorn.darkness.core.ResolvedTheme

/**
 * @param theme the resolved theme for deriving toolbar colours.
 */
@Composable
internal fun ImeHelperToolbar(
    ctrlSticky: Boolean,
    onCtrlToggle: () -> Unit,
    shiftSticky: Boolean,
    onShiftToggle: () -> Unit,
    onSend: (ByteArray) -> Unit,
    theme: ResolvedTheme,
) {
    val toolbarBg = Color(theme.surfaceAlt)
    val dividerColor = Color(theme.border)
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(toolbarBg)
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(modifier = Modifier.width(4.dp))
        ToolbarKey("Ctrl", sticky = true, active = ctrlSticky, theme = theme) { onCtrlToggle() }
        ToolbarKey("Shift", sticky = true, active = shiftSticky, theme = theme) { onShiftToggle() }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .background(dividerColor),
        )
        ToolbarKey("Enter", theme = theme) { onSend(byteArrayOf(0x0d)) }
        ToolbarKey("Esc", theme = theme) { onSend(byteArrayOf(0x1b)) }
        ToolbarKey("Tab", theme = theme) {
            if (shiftSticky) {
                onSend("[Z".toByteArray(Charsets.UTF_8))
                onShiftToggle()
            } else {
                onSend(byteArrayOf(0x09))
            }
        }
        ToolbarKey("↑", theme = theme) { onSend("[A".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("↓", theme = theme) { onSend("[B".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("→", theme = theme) { onSend("[C".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("←", theme = theme) { onSend("[D".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("Home", theme = theme) { onSend("[H".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("End", theme = theme) { onSend("[F".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgUp", theme = theme) { onSend("[5~".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgDn", theme = theme) { onSend("[6~".toByteArray(Charsets.UTF_8)) }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

/**
 * A single key button in the [ImeHelperToolbar].
 *
 * Renders as a rounded-rectangle pill with haptic feedback on tap.
 * Sticky keys (Ctrl, Shift) toggle between armed (accent) and unarmed
 * states.
 */
@Composable
private fun ToolbarKey(
    label: String,
    sticky: Boolean = false,
    active: Boolean = false,
    theme: ResolvedTheme,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(6.dp)
    val accentColor = Color(theme.accent)
    val keyBg = Color(theme.surface)
    val stickyBg = Color(theme.surfaceAlt)
    val borderColor = Color(theme.border)
    val bg = when {
        sticky && active -> accentColor
        sticky -> stickyBg
        else -> keyBg
    }
    val textColor = when {
        sticky && active -> Color(theme.bg)
        sticky -> Color(theme.textDim)
        else -> Color(theme.text)
    }
    val baseModifier = Modifier
        .padding(vertical = 6.dp)
        .fillMaxHeight()
        .clip(shape)
        .background(bg, shape)
    val borderedModifier = if (sticky && !active) {
        baseModifier.border(1.dp, borderColor, shape)
    } else {
        baseModifier
    }
    Box(
        modifier = borderedModifier
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = textColor,
                fontWeight = if (sticky) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}
