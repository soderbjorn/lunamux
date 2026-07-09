/**
 * Session state indicators for the Lunamux iOS app.
 *
 * Renders the per-session status shown next to titles in `TreeView` rows and
 * the `TerminalScreen` navigation bar, mirroring the web client's
 * `.tt-status-dot` indicator (`applyDotState()` in
 * `web/.../WebStateActions.kt`). Per issue #38 the indicator is painted in the
 * theme's foreground text colour (`Palette.textPrimary`) — not a fixed
 * green/red — so it meshes with any theme:
 *  - idle (`nil`) → no indicator at all (hidden); the bead appears only when
 *    working or waiting (issue #43).
 *  - `"working"`  → the same dot, breathing (pulsing) between full and ~30%.
 *  - `"waiting"`  → the dot is swapped for a pulsing warning/exclamation
 *    triangle, so working and waiting stay distinguishable now that they share
 *    one colour.
 */

import SwiftUI

/// Per-row status indicator, mirroring the web `.tt-status-dot` (issue #38): a
/// small bead whose motion/shape — but no longer its colour — encodes the
/// session state. It is always painted in the theme's foreground text colour
/// (`Palette.textPrimary`) so it meshes with any theme instead of a fixed
/// green/red.
///  - idle (`nil`) → no indicator at all (hidden); appears only when working
///    or waiting (issue #43).
///  - `"working"`  → the same dot, breathing between full and ~30% opacity.
///  - `"waiting"`  → a pulsing warning/exclamation triangle in place of the dot.
///
/// Rendered at the LEADING edge of each `TreeView` leaf row, aggregated on tab
/// headers, and shown next to the `TerminalScreen` title. A soft `.shadow` glow
/// surrounds the dot, echoing the web bead's box-shadow halo.
struct StatusDot: View {
    /// The session state (`"working"`, `"waiting"`, or nil/idle).
    let state: String?
    /// Square footprint in points; the core bead is ~44% of it, the rest is
    /// glow headroom.
    var box: CGFloat = 16

    /// Drives the working "breathe"; a single flip is enough for
    /// `repeatForever(autoreverses:)` to oscillate forever. Kept in sync with
    /// `state` via `.onAppear` / `.onChange` so it tracks state that changes
    /// while the row is already on screen.
    @State private var pulse = false

    var body: some View {
        let color = Palette.textPrimary
        Group {
            if state == "waiting" {
                // Waiting for input → swap the dot for the warning triangle.
                WaitingWarningIcon(box: box, color: color)
            } else if state == "working" {
                Circle()
                    .fill(color)
                    .frame(width: box * 0.44, height: box * 0.44)
                    .shadow(color: color.opacity(0.7), radius: box * 0.26)
                    .frame(width: box, height: box)
                    // Web `.tt-status-dot.state-working` breathes 1 → 0.3 → 1
                    // over a 2.5s ease-in-out cycle; the 1.25s autoreversing
                    // half-cycle matches.
                    .opacity(pulse ? 0.3 : 1.0)
                    .animation(
                        .easeInOut(duration: 1.25).repeatForever(autoreverses: true),
                        value: pulse
                    )
                    .onAppear { pulse = true }
                    .accessibilityLabel("Status: working")
            }
            // Idle (nil, or any other value) → render nothing (issue #43): the
            // indicator appears only while a session is working or waiting.
        }
    }
}

/// Warning triangle with an exclamation mark, fading between full and 30%
/// opacity to flag a session waiting for user input.
///
/// Geometry mirrors the web client's `.tt-status-dot.state-waiting` mask (a
/// 16-unit viewBox: a filled triangle with the exclamation bar and dot punched
/// out as transparent holes via an even-odd fill), and the fade mirrors the
/// `fade-warning` keyframes (opacity 1 → 0.3 → 1 over 2.5s ease-in-out). Drawn
/// in the supplied `color` (the theme foreground) so it meshes with any theme.
private struct WaitingWarningIcon: View {
    /// Square footprint in points (matches the dot it replaces).
    let box: CGFloat
    /// Fill colour (the theme's foreground text colour).
    let color: Color
    /// Drives the repeat-forever fade; flipped once on appear.
    @State private var pulse = false

    var body: some View {
        Canvas { context, canvasSize in
            // Scale the 16-unit viewBox to fill the canvas; the triangle has
            // its own padding (apex at y=1.7, base at y=13.7) so it never
            // touches the edges.
            let s = min(canvasSize.width, canvasSize.height) / 16
            var path = Path()
            // Triangle silhouette.
            path.move(to: CGPoint(x: 8 * s, y: 1.7 * s))
            path.addLine(to: CGPoint(x: 14.5 * s, y: 13.7 * s))
            path.addLine(to: CGPoint(x: 1.5 * s, y: 13.7 * s))
            path.closeSubpath()
            // Exclamation bar (cut-out).
            path.addRect(CGRect(x: 7.25 * s, y: 5.6 * s, width: 1.5 * s, height: 4.2 * s))
            // Exclamation dot (cut-out).
            path.addEllipse(in: CGRect(x: 7.1 * s, y: 10.9 * s, width: 1.8 * s, height: 1.8 * s))
            // Even-odd fill turns the bar + dot into transparent holes, so the
            // exclamation reads on any theme background.
            context.fill(path, with: .color(color), style: FillStyle(eoFill: true))
        }
        .frame(width: box, height: box)
        .opacity(pulse ? 0.3 : 1.0)
        .animation(.easeInOut(duration: 1.25).repeatForever(autoreverses: true), value: pulse)
        .onAppear { pulse = true }
        .accessibilityLabel("Status: waiting for input")
    }
}
