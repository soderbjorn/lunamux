/**
 * Termtastic xterm-specific font sync.
 *
 * The font *list* and the *detection* logic now live in the
 * darkness-toolkit ([se.soderbjorn.darkness.web.themeeditor.fontPresets],
 * [se.soderbjorn.darkness.web.themeeditor.detectInstalledFonts],
 * [se.soderbjorn.darkness.web.themeeditor.resolveFontFamilyCss]). What's
 * still termtastic-specific is the runtime sync: xterm.js caches
 * character metrics on its first paint, so a late `@font-face` load
 * needs to be awaited and every live terminal needs `term.options.fontFamily`
 * pushed onto it (a CSS variable change alone doesn't reach the canvas).
 * That's what [applyGlobalFontFamily] / [applyStackToTerminals] do.
 *
 * The legacy `--t-font-mono` CSS var is still set so existing rules in
 * `styles.css` (git diff panes, markdown code blocks) keep working
 * unchanged. The toolkit's `--dt-font-mono` chain is updated separately
 * by `main.kt` so chrome surfaces wired to that var also pick up the
 * change.
 *
 * @see applyGlobalFontSize
 * @see se.soderbjorn.darkness.web.themeeditor.fontPresets
 */
package se.soderbjorn.termtastic

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.themeeditor.fontPresets
import se.soderbjorn.darkness.web.themeeditor.resolveFontFamilyCss

/**
 * Applies the selected font-family preset to every live xterm.js terminal
 * and publishes the resolved CSS stack as the `--t-font-mono` custom
 * property on `<html>`, so CSS rules in `styles.css` (git diff panes,
 * markdown `pre`/`code`) pick up the new font too.
 *
 * For bundled presets, waits for the font file to finish loading via the
 * `document.fonts.load(...)` FontFaceSet API before refitting each terminal.
 *
 * @param key the persisted preset key, or `null` for the system default.
 */
internal fun applyGlobalFontFamily(key: String?) {
    val stack = resolveFontFamilyCss(key)
    val preset = fontPresets.firstOrNull { it.key == key }
    if (preset?.bundled == true) {
        val primary = stack.substringBefore(',').trim()
        val fonts = document.asDynamic().fonts
        if (fonts != null) {
            fonts.load("400 16px $primary")
            fonts.load("700 16px $primary").then({ _: dynamic ->
                applyStackToTerminals(stack)
            }, { _: dynamic ->
                applyStackToTerminals(stack)
            })
            return
        }
    }
    applyStackToTerminals(stack)
}

/**
 * Pushes a resolved CSS font stack to every live xterm.js terminal and to
 * the `--t-font-mono` custom property.
 *
 * @param stack the fully resolved CSS `font-family` stack to apply.
 */
private fun applyStackToTerminals(stack: String) {
    for ((_, entry) in terminals) {
        entry.term.options.fontFamily = stack
        try { safeFit(entry.term, entry.fit) } catch (_: Throwable) {}
    }
    (document.documentElement as? HTMLElement)
        ?.style?.setProperty("--t-font-mono", stack)
}
