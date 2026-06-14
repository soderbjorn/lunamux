// ReformatIcon.swift
//
// The "reformat" toolbar glyph for the Termtastic iOS app.
//
// Draws the same icon the Electron/web client uses for its reformat-on-resize
// action: a terminal rectangle with a chevron tucked against each side wall,
// reading as "reflow the contents to fit the width". This replaces the earlier
// `arrow.clockwise` SF Symbol, which looked like a reload, so the reformat
// affordance is visually consistent across the web, desktop and mobile clients.

import SwiftUI

/// The reformat glyph as a `Shape`, authored in a 24×24 viewport and scaled to
/// fit whatever frame it is given. Mirrors the web client's SVG (`rect` plus two
/// inward chevrons) so the stroke geometry matches after scaling.
///
/// - SeeAlso: `ReformatIcon`
struct ReformatGlyph: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + x * s, y: rect.minY + y * s)
        }

        var path = Path()
        // Terminal frame: <rect x="3" y="5" width="18" height="14" rx="1.5"/>.
        path.addRoundedRect(
            in: CGRect(x: rect.minX + 3 * s, y: rect.minY + 5 * s,
                       width: 18 * s, height: 14 * s),
            cornerSize: CGSize(width: 1.5 * s, height: 1.5 * s)
        )
        // Left chevron: <polyline points="7 10 4 12 7 14"/>.
        path.move(to: p(7, 10))
        path.addLine(to: p(4, 12))
        path.addLine(to: p(7, 14))
        // Right chevron: <polyline points="17 10 20 12 17 14"/>.
        path.move(to: p(17, 10))
        path.addLine(to: p(20, 12))
        path.addLine(to: p(17, 14))
        return path
    }
}

/// Renders the reformat glyph at toolbar-icon size, stroked in the current
/// foreground style. Used by `TerminalScreen`'s top-bar reformat button;
/// callers apply `.foregroundStyle(Palette.headerAccent)` to tint it.
struct ReformatIcon: View {
    var body: some View {
        ReformatGlyph()
            .stroke(style: StrokeStyle(lineWidth: 1.8, lineCap: .round, lineJoin: .round))
            .frame(width: 22, height: 22)
            .accessibilityLabel("Reformat")
    }
}
