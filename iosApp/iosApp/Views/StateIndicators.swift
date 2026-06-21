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

/// Per-row status dot, mirroring the web sidebar dot (`.tt-sidebar-dot`) and the
/// landing page's brand dot (issue #35 follow-up): a small glowing bead whose
/// colour/motion encodes the session state.
///  - idle (`nil`) → solid green, no pulse.
///  - `"working"`  → green, pulsing (breathes between full and ~35% opacity).
///  - `"waiting"`  → red, pulsing.
///
/// Rendered at the LEADING edge of each `TreeView` leaf row (replacing the old
/// leading pane-type icon, which now sits trailing). A soft `.shadow` glow
/// surrounds the core bead, echoing the web dot's festive box-shadow halo. The
/// colours are fixed (not theme tokens) so the states stay distinguishable in
/// any appearance mode.
struct StatusDot: View {
    /// The session state (`"working"`, `"waiting"`, or nil/idle).
    let state: String?
    /// Square footprint in points; the core bead is ~44% of it, the rest is
    /// glow headroom.
    var box: CGFloat = 16

    /// Fixed dot colours (phosphor green / red), matching the web client.
    private static let green = Color(red: 124.0 / 255, green: 252.0 / 255, blue: 158.0 / 255)
    private static let red = Color(red: 255.0 / 255, green: 95.0 / 255, blue: 87.0 / 255)

    /// Drives the repeating breathe; a single flip is enough for
    /// `repeatForever(autoreverses:)` to oscillate forever. Kept in sync with
    /// `state` via `.onAppear` / `.onChange` so it tracks state that changes
    /// while the row is already on screen.
    @State private var pulse = false

    var body: some View {
        let pulsing = state == "working" || state == "waiting"
        let color = state == "waiting" ? Self.red : Self.green
        // Web pulse cycle is 2.8s for both colours; the autoreversing animation
        // runs the 1.4s half-cycle each way so red and green breathe at the
        // same speed.
        let half = 1.4
        return Circle()
            .fill(color)
            .frame(width: box * 0.44, height: box * 0.44)
            .shadow(color: color.opacity(0.7), radius: box * 0.26)
            .frame(width: box, height: box)
            .opacity(pulsing && pulse ? 0.35 : 1.0)
            .animation(
                pulsing
                    ? .easeInOut(duration: half).repeatForever(autoreverses: true)
                    : .default,
                value: pulse
            )
            .onAppear { pulse = pulsing }
            .onChange(of: pulsing) { _, value in pulse = value }
            .accessibilityLabel(
                state == "working" ? "Status: working" :
                state == "waiting" ? "Status: waiting for input" :
                "Status: idle"
            )
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
