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
package se.soderbjorn.termtastic.android.ui

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
import se.soderbjorn.darkness.core.ResolvedPalette

/**
 * @param palette the resolved theme palette for deriving toolbar colours.
 */
@Composable
internal fun ImeHelperToolbar(
    ctrlSticky: Boolean,
    onCtrlToggle: () -> Unit,
    shiftSticky: Boolean,
    onShiftToggle: () -> Unit,
    onSend: (ByteArray) -> Unit,
    palette: ResolvedPalette,
) {
    val toolbarBg = Color(palette.surface.sunken)
    val dividerColor = Color(palette.border.subtle)
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
        ToolbarKey("Ctrl", sticky = true, active = ctrlSticky, palette = palette) { onCtrlToggle() }
        ToolbarKey("Shift", sticky = true, active = shiftSticky, palette = palette) { onShiftToggle() }
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = 10.dp)
                .background(dividerColor),
        )
        ToolbarKey("Enter", palette = palette) { onSend(byteArrayOf(0x0d)) }
        ToolbarKey("Esc", palette = palette) { onSend(byteArrayOf(0x1b)) }
        ToolbarKey("Tab", palette = palette) {
            if (shiftSticky) {
                onSend("[Z".toByteArray(Charsets.UTF_8))
                onShiftToggle()
            } else {
                onSend(byteArrayOf(0x09))
            }
        }
        ToolbarKey("↑", palette = palette) { onSend("[A".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("↓", palette = palette) { onSend("[B".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("→", palette = palette) { onSend("[C".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("←", palette = palette) { onSend("[D".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("Home", palette = palette) { onSend("[H".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("End", palette = palette) { onSend("[F".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgUp", palette = palette) { onSend("[5~".toByteArray(Charsets.UTF_8)) }
        ToolbarKey("PgDn", palette = palette) { onSend("[6~".toByteArray(Charsets.UTF_8)) }
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
    palette: ResolvedPalette,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(6.dp)
    val accentColor = Color(palette.accent.primary)
    val keyBg = Color(palette.surface.raised)
    val stickyBg = Color(palette.surface.sunken)
    val borderColor = Color(palette.border.`default`)
    val bg = when {
        sticky && active -> accentColor
        sticky -> stickyBg
        else -> keyBg
    }
    val textColor = when {
        sticky && active -> Color(palette.accent.onPrimary)
        sticky -> Color(palette.text.secondary)
        else -> Color(palette.text.primary)
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
