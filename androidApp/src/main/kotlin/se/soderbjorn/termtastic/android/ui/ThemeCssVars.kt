/**
 * CSS custom-property emission for the WebView-backed Android screens.
 *
 * The git-diff and file-browser content screens render server HTML inside an
 * [android.webkit.WebView] whose stylesheet references the flat `--t-*` theme
 * variables. [themeCssVars] mirrors the web toolkit's
 * `ResolvedTheme.toCssVarMap` (`se.soderbjorn.darkness.web.ThemeCssVars`),
 * emitting the same 19 semantic tokens so the mobile WebViews and the web
 * client paint identically.
 *
 * @see GitDiffScreen
 * @see FileBrowserContentScreen
 * @see se.soderbjorn.darkness.core.ResolvedTheme
 */
package se.soderbjorn.termtastic.android.ui

import se.soderbjorn.darkness.core.ResolvedTheme
import se.soderbjorn.darkness.core.argbToCss

/**
 * Build the `:root` body declaring the 19 flat `--t-*` CSS variables for a
 * resolved theme, one `--t-token: #rrggbb;` declaration per line.
 *
 * Called by [buildDiffHtml] (GitDiffScreen) and [wrapFileHtml]
 * (FileBrowserContentScreen) to seed the WebView stylesheet's variables.
 * The variable names and value formatting match the web toolkit's
 * `toCssVarMap` so both platforms resolve the same `var(--t-*)` references.
 *
 * @param theme the resolved 19-token palette.
 * @return the CSS variable declarations as a single newline-joined string.
 * @see se.soderbjorn.darkness.core.argbToCss
 */
internal fun themeCssVars(theme: ResolvedTheme): String {
    val c = { v: Long -> argbToCss(v) }
    return """
    --t-bg: ${c(theme.bg)};
    --t-surface: ${c(theme.surface)};
    --t-surface-alt: ${c(theme.surfaceAlt)};
    --t-border: ${c(theme.border)};
    --t-text: ${c(theme.text)};
    --t-text-dim: ${c(theme.textDim)};
    --t-text-bright: ${c(theme.textBright)};
    --t-accent: ${c(theme.accent)};
    --t-accent-soft: ${c(theme.accentSoft)};
    --t-glow: ${c(theme.glow)};
    --t-warn: ${c(theme.warn)};
    --t-danger: ${c(theme.danger)};
    --t-add: ${c(theme.add)};
    --t-add-bg: ${c(theme.addBg)};
    --t-add-text: ${c(theme.addText)};
    --t-syn-keyword: ${c(theme.synKeyword)};
    --t-syn-string: ${c(theme.synString)};
    --t-syn-number: ${c(theme.synNumber)};
    --t-syn-comment: ${c(theme.synComment)};
    --t-syn-function: ${c(theme.synFunction)};
    --t-syn-type: ${c(theme.synType)};
    --t-syn-operator: ${c(theme.synOperator)};
    --t-syn-constant: ${c(theme.synConstant)};
    """.trimIndent()
}
