/**
 * Session state indicators for the Termtastic iOS app.
 *
 * Renders the per-session status shown next to titles in `TreeView` rows
 * and the `TerminalScreen` navigation bar, mirroring the web client's
 * `.pane-status-spinner` element (`applySpinnerState()` in
 * `web/.../WebStateActions.kt`):
 *  - `"working"` — a small circular spinner.
 *  - `"waiting"` — a warning triangle with an exclamation mark, drawn in the
 *    theme's semantic warn colour and fading between full and 30% opacity
 *    (the web `fade-warning` keyframes: 2.5s ease-in-out cycle).
 */

import SwiftUI

/// Status indicator for a session state, shown next to the session title.
///
/// Shows a spinner when `state` is `"working"`, a fading warning triangle
/// when it is `"waiting"`, and nothing otherwise. Used by `TreeView`'s tab
/// header and leaf rows and by `TerminalScreen`'s toolbar title.
struct StateIndicator: View {
    /// The session state (`"working"`, `"waiting"`, or nil).
    let state: String?
    /// Icon size in points (12 in list rows, 14 in the pane header,
    /// matching the web client's two spinner variants).
    let size: CGFloat

    var body: some View {
        if state == "working" {
            ProgressView()
                .scaleEffect(size / 14)
                .frame(width: size, height: size)
                .accessibilityLabel("Status: working")
        } else if state == "waiting" {
            WaitingWarningIcon(size: size)
        }
    }
}

/// Warning triangle with an exclamation mark, fading between full and 30%
/// opacity to flag a session waiting for user input.
///
/// Geometry mirrors the web client's `WAITING_WARNING_SVG` (16x16 viewBox:
/// stroked triangle, exclamation bar, and dot), and the fade mirrors the
/// `fade-warning` CSS keyframes (opacity 1 → 0.3 → 1 over 2.5s ease-in-out).
private struct WaitingWarningIcon: View {
    /// Icon size in points.
    let size: CGFloat
    /// Drives the repeat-forever fade; flipped once on appear.
    @State private var pulse = false

    var body: some View {
        Canvas { context, canvasSize in
            let s = min(canvasSize.width, canvasSize.height) / 16
            let color = Palette.warn
            var triangle = Path()
            triangle.move(to: CGPoint(x: 8 * s, y: 1.5 * s))
            triangle.addLine(to: CGPoint(x: 14.5 * s, y: 13.5 * s))
            triangle.addLine(to: CGPoint(x: 1.5 * s, y: 13.5 * s))
            triangle.closeSubpath()
            context.stroke(
                triangle,
                with: .color(color),
                style: StrokeStyle(lineWidth: 1.3 * s, lineJoin: .round)
            )
            let bar = Path(
                roundedRect: CGRect(x: 7.25 * s, y: 6 * s, width: 1.5 * s, height: 4 * s),
                cornerRadius: 0.5 * s
            )
            context.fill(bar, with: .color(color))
            let dot = Path(ellipseIn: CGRect(
                x: (8 - 0.85) * s, y: (12 - 0.85) * s,
                width: 1.7 * s, height: 1.7 * s
            ))
            context.fill(dot, with: .color(color))
        }
        .frame(width: size, height: size)
        .opacity(pulse ? 0.3 : 1.0)
        .animation(.easeInOut(duration: 1.25).repeatForever(autoreverses: true), value: pulse)
        .onAppear { pulse = true }
        .accessibilityLabel("Status: waiting for input")
    }
}
