/**
 * Visible text field for gesture-typing (Android swipe-to-write). Sits
 * between the terminal and the IME helper toolbar when active. A
 * standard TextField allows the keyboard to offer swipe suggestions,
 * unlike Termux's raw key capture in TerminalView.
 *
 * @see TerminalScreen
 * @see ImeHelperToolbar
 */
package se.soderbjorn.termtastic.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.soderbjorn.darkness.core.ResolvedPalette

/**
 * @param palette the resolved theme palette for deriving input bar colours.
 */
@Composable
internal fun SwipeInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    palette: ResolvedPalette,
) {
    val barBg = Color(palette.surface.raised)
    val inputBg = Color(palette.surface.sunken)
    val accentColor = Color(palette.accent.primary)
    val textColor = Color(palette.text.primary)
    val placeholderColor = Color(palette.text.tertiary)
    val onAccentColor = Color(palette.accent.onPrimary)
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = {
                Text(
                    "Type or swipe here…",
                    color = placeholderColor,
                    fontSize = 14.sp,
                )
            },
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = textColor,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg,
                cursorColor = accentColor,
                focusedIndicatorColor = accentColor,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(6.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor)
                .clickable { onSubmit() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⏎",
                fontSize = 18.sp,
                color = onAccentColor,
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
