/// Custom, hand-drawn toolbar glyphs shared across the Lunamux clients.
///
/// Both shapes here are drawn from scratch with SwiftUI `Path`s using the same
/// 24-unit coordinate geometry as their Android (`LayoutGridIcon` / `PlusIcon`
/// in `ToolbarIcons.kt`) and web (`ICON_LAYOUT` / `ICON_NEW_TAB` inline SVGs in
/// darkness-toolkit) counterparts, so every platform shows an identical mark.
/// They replace the SF Symbols `rectangle.3.group` and `plus` in the Sessions
/// toolbar (`TreeView`).

import SwiftUI

/// The "layout presets" shape: four unequal panes in a 2×2 "Mondrian" tiling
/// with roomy 3-unit gaps.
/// Stroked (not filled) so it reads as an outline glyph matching the other
/// toolbar symbols.
private struct LayoutGridShape: Shape {
    /// Builds four rounded-rectangle panes scaled into `rect` from the shared
    /// 24-unit design space.
    ///
    /// - Parameter rect: the bounds the glyph is laid out in.
    /// - Returns: a `Path` containing the four pane outlines.
    func path(in rect: CGRect) -> Path {
        let u = min(rect.width, rect.height) / 24
        func pane(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> Path {
            Path(
                roundedRect: CGRect(
                    x: rect.minX + x * u,
                    y: rect.minY + y * u,
                    width: w * u,
                    height: h * u
                ),
                cornerRadius: 1.6 * u
            )
        }
        var path = Path()
        path.addPath(pane(3, 3, 9.5, 8))
        path.addPath(pane(15.5, 3, 5.5, 8))
        path.addPath(pane(3, 14, 5, 7))
        path.addPath(pane(11, 14, 10, 7))
        return path
    }
}

/// Toolbar-ready "layout" glyph. Takes its colour from the surrounding
/// `.foregroundStyle`, exactly like an SF Symbol would.
struct LayoutGridIcon: View {
    var body: some View {
        GeometryReader { geo in
            let u = min(geo.size.width, geo.size.height) / 24
            LayoutGridShape()
                .stroke(style: StrokeStyle(lineWidth: 1.5 * u, lineJoin: .round))
        }
        .frame(width: 22, height: 22)
    }
}

/// A clean "+" shape with rounded line caps. Inspired by the SF Symbol `plus`,
/// drawn from scratch so it matches the Android/web add buttons exactly.
private struct PlusShape: Shape {
    /// Builds the two crossing strokes scaled into `rect` from the shared
    /// 24-unit design space.
    ///
    /// - Parameter rect: the bounds the glyph is laid out in.
    /// - Returns: a `Path` containing the vertical and horizontal strokes.
    func path(in rect: CGRect) -> Path {
        let u = min(rect.width, rect.height) / 24
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + 12 * u, y: rect.minY + 5 * u))
        path.addLine(to: CGPoint(x: rect.minX + 12 * u, y: rect.minY + 19 * u))
        path.move(to: CGPoint(x: rect.minX + 5 * u, y: rect.minY + 12 * u))
        path.addLine(to: CGPoint(x: rect.minX + 19 * u, y: rect.minY + 12 * u))
        return path
    }
}

/// Toolbar-ready "add" glyph. Takes its colour from the surrounding
/// `.foregroundStyle`, exactly like an SF Symbol would.
struct PlusIcon: View {
    var body: some View {
        GeometryReader { geo in
            let u = min(geo.size.width, geo.size.height) / 24
            PlusShape()
                .stroke(style: StrokeStyle(lineWidth: 2 * u, lineCap: .round))
        }
        .frame(width: 22, height: 22)
    }
}
