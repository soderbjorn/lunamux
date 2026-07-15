/// The face terminal content is drawn with, shared by every view that shows
/// PTY output: the full-screen `SwiftTerm` terminal in `TerminalScreen` and the
/// `MiniTerminalPane` previews in the overview/tiled grid.
///
/// It lives in one place because the font is not just a look: it carries the
/// per-glyph fallback chain that keeps terminal symbols out of the colour emoji
/// font (issue #141). A view that builds its own monospace font silently loses
/// that chain — which is exactly how the overview previews kept rendering ⏺ as
/// an emoji button after the terminal itself was fixed.
import CoreText
import SwiftUI
import UIKit

enum TerminalFont {
    /// PostScript name of the primary face (bundled, matches Android).
    private static let primaryName = "JetBrainsMono-Regular"
    /// PostScript name of the bundled per-glyph fallback face, matching the face
    /// Android falls back to. Two Iosevka build variants matter here: *Term*,
    /// because every one of its glyphs occupies exactly one terminal cell (stock
    /// Iosevka draws these symbols double-width), and *Extended*, whose 0.6em
    /// advance is identical to JetBrains Mono's, so fallback glyphs land on the
    /// cell grid at their natural size with nothing stretched to fit.
    private static let fallbackName = "Iosevka-Term-Extended"

    /// Returns the bundled JetBrains Mono face at `size`, with Iosevka Term
    /// Extended attached as a CoreText cascade list, falling back to the system
    /// monospace font when the custom face failed to register.
    ///
    /// JetBrains Mono has no glyph at all for a number of code points that TUIs
    /// (Claude Code especially) emit constantly: ⏺ U+23FA, ⎿ U+23BF, ⏸ U+23F8,
    /// the Braille spinner frames, ✔/✘, … For those, CoreText consults its own
    /// fallback chain, and since no iOS system font carries a *text* glyph for
    /// them, the only candidate left is Apple Color Emoji — which is why they
    /// rendered as coloured emoji buttons instead of terminal symbols (#141).
    /// The cascade list makes CoreText try Iosevka first, so they resolve to
    /// monochrome, single-cell glyphs; genuine emoji still reach Apple Color
    /// Emoji, because CoreText keeps its default fallbacks after the list.
    ///
    /// Note CoreText builds cascade entries at the base font's size and ignores
    /// any size or matrix attribute on their descriptors, which is why the
    /// fallback face has to bring a matching 0.6em advance of its own.
    ///
    /// Called for the terminal's initial font, on every pinch-to-zoom step, and
    /// for each overview preview line.
    ///
    /// - Parameter size: point size for the returned face.
    /// - Returns: the terminal face, cascade list attached when both faces registered.
    static func uiFont(size: CGFloat) -> UIFont {
        guard UIFont(name: primaryName, size: size) != nil else {
            return UIFont.monospacedSystemFont(ofSize: size, weight: .regular)
        }
        var descriptor = UIFontDescriptor(name: primaryName, size: size)
        if UIFont(name: fallbackName, size: size) != nil {
            descriptor = descriptor.addingAttributes([
                UIFontDescriptor.AttributeName(rawValue: kCTFontCascadeListAttribute as String): [
                    UIFontDescriptor(name: fallbackName, size: size),
                ],
            ])
        }
        return UIFont(descriptor: descriptor, size: size)
    }

    /// SwiftUI wrapper around ``uiFont(size:)`` for `Text`-based terminal views.
    ///
    /// Called by `MiniTerminalPane`. The cascade list rides along on the font's
    /// descriptor, so SwiftUI resolves the same glyphs the terminal does.
    ///
    /// - Parameter size: point size for the returned face.
    /// - Returns: the terminal face as a SwiftUI `Font`.
    static func swiftUI(size: CGFloat) -> Font {
        Font(uiFont(size: size))
    }
}
