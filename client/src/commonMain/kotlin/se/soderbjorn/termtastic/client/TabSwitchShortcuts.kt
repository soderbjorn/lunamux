/* TabSwitchShortcuts.kt (commonMain)
 *
 * Pure mapping logic for the `Cmd+<digit>` tab-switch keyboard
 * shortcuts (Mac/Electron). Kept platform-agnostic and free of any
 * DOM / WindowConfig dependency so it can be unit-tested directly; the
 * web frontend feeds it the live tab count and uses the returned index
 * to pick the target tab id. See the jsMain `installTabNumberShortcuts`
 * wiring for the event side of this feature.
 */
package se.soderbjorn.termtastic.client

/**
 * Resolves which tab a `Cmd+<digit>` shortcut should activate.
 *
 * Convention (matches Safari / Chrome / iTerm):
 * - Digits `1`–`8` select the 1st–8th tab by raw position (zero-based
 *   index `digit - 1`), or nothing if that position doesn't exist.
 * - Digit `9` always selects the **last** tab, regardless of how many
 *   tabs there are.
 *
 * Called by the jsMain key handler ([installTabNumberShortcuts]) on
 * every matching keydown; the returned index is looked up against the
 * live `WindowConfig.tabs` list to obtain the tab id passed to
 * `WindowCommand.SetActiveTab`.
 *
 * @param digit the pressed number key, expected in `1..9`.
 * @param tabCount the number of tabs currently in the window.
 * @return the zero-based tab index to activate, or `null` when the
 *   shortcut should be a no-op (no tabs, out-of-range position, or a
 *   digit outside `1..9`).
 */
fun resolveTabSwitchIndex(digit: Int, tabCount: Int): Int? {
    if (tabCount <= 0) return null
    return when (digit) {
        9 -> tabCount - 1
        in 1..8 -> (digit - 1).takeIf { it < tabCount }
        else -> null
    }
}
