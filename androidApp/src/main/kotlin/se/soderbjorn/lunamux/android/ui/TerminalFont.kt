/**
 * The face terminal content is drawn with, shared by every view that shows PTY output:
 * the full-screen Termux [com.termux.view.TerminalView] in [TerminalScreen] and the
 * [MiniTerminalPane] miniatures in the overview/tiled grid.
 *
 * It lives in one place because the font is not just a look: it carries the per-glyph
 * fallback chain that keeps terminal symbols out of the colour emoji font (issue #141).
 * A view that reaches for `FontFamily.Monospace` instead silently loses that chain —
 * which is exactly how the overview miniatures kept rendering ⏺ as an emoji button after
 * the terminal itself was fixed.
 *
 * The iOS analogue is `TerminalFont` in `TerminalFont.swift`.
 */
package se.soderbjorn.lunamux.android.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.annotation.RequiresApi

/** Primary terminal face: the one the grid's cell metrics are measured from. */
private const val TERMINAL_FONT_ASSET = "fonts/JetBrainsMono-Regular.ttf"

/**
 * Per-glyph fallback face, for the code points [TERMINAL_FONT_ASSET] has no glyph for.
 *
 * Iosevka *Term* gives every glyph exactly one terminal cell (stock Iosevka draws these symbols
 * double-width), and the *Extended* width matches JetBrains Mono's 0.6em advance, so fallback
 * glyphs land on the cell grid at their natural size with nothing stretched to fit.
 */
private const val TERMINAL_FALLBACK_FONT_ASSET = "fonts/IosevkaTerm-Extended.ttf"

/**
 * Loads and caches the terminal typeface.
 *
 * @see TerminalFont.typeface
 */
object TerminalFont {
    @Volatile
    private var cached: Typeface? = null

    /**
     * Returns the face the terminal and its miniatures draw with, loading it once per process.
     *
     * The face is a font *collection*: JetBrains Mono, then Iosevka Term Extended, then the
     * system fonts. Android itemizes text across that chain one glyph at a time, so each code
     * point is drawn by the first font in the chain that actually has it.
     *
     * This exists because JetBrains Mono has no glyph for a number of code points TUIs emit
     * constantly (⏺ U+23FA, ⎿ U+23BF, ⏸ U+23F8, the Braille spinner frames, ✔/✘). Without
     * Iosevka ahead of the system fonts, those code points reach Noto Color Emoji and render as
     * coloured emoji buttons — issue #141. Keeping "sans-serif" last means genuine emoji (😀)
     * still work.
     *
     * Note this deliberately does *not* use [com.termux.view.TerminalView.setFallbackTypeface].
     * That mechanism decides per code point via `Paint.hasGlyph`, which was measured on-device
     * reporting `true` for ⏺ even for a typeface built with no system fallback at all — it
     * cannot report a single font's real coverage, so the fallback face was never consulted. A
     * font collection asks no such question: the itemizer resolves each glyph against the real
     * cmaps.
     *
     * Caching matters: the fallback face is a ~9 MB asset, and the miniatures would otherwise
     * rebuild the collection on every recomposition.
     *
     * Called by the [com.termux.view.TerminalView] factory in [TerminalScreen] and by
     * [MiniTerminalPane].
     *
     * @param context supplies the [android.content.res.AssetManager] the fonts are read from;
     *   only the application context is retained.
     * @return the collection face; on API < 29 ([Typeface.CustomFallbackBuilder] is API 29+), or
     *   if the assets cannot be read, plain JetBrains Mono, and [Typeface.MONOSPACE] as a last
     *   resort.
     */
    fun typeface(context: Context): Typeface =
        cached ?: synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }

    private fun load(context: Context): Typeface =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) buildCollection(context) else null)
            ?: runCatching { Typeface.createFromAsset(context.assets, TERMINAL_FONT_ASSET) }.getOrNull()
            ?: Typeface.MONOSPACE

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCollection(context: Context): Typeface? = runCatching {
        val primary = Font.Builder(context.assets, TERMINAL_FONT_ASSET).build()
        val fallback = Font.Builder(context.assets, TERMINAL_FALLBACK_FONT_ASSET).build()
        Typeface.CustomFallbackBuilder(FontFamily.Builder(primary).build())
            .addCustomFallback(FontFamily.Builder(fallback).build())
            .setSystemFallback("sans-serif")
            .build()
    }.getOrNull()
}
