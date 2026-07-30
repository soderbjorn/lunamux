/// The iOS passive mirror: the geometry of drawing another device's terminal
/// grid on this screen, and the view that hosts it.
///
/// This file contains ``SwiftTermCellMetrics`` (SwiftTerm's cell arithmetic,
/// reproduced so a grid can be measured without applying anything),
/// ``MirrorWindow`` (the solved fit) and ``MirrorHostView`` (the clipping
/// container that pans and zooms it).
///
/// ## How the grid is pinned, and why iOS does it differently from Android
///
/// The design invariant is that a client's emulator is sized only by the
/// server's `Size` frames — never by a local layout pass. Android enforces that
/// with an explicit pin read by its *vendored* `TerminalView.updateSize`.
/// SwiftTerm is a remote SPM package and cannot be patched, so iOS gets the same
/// invariant structurally instead: SwiftTerm derives its grid purely as
/// `Int(bounds.width / cellWidth) x Int(bounds.height / cellHeight)`
/// (`AppleTerminalView.processSizeChange`), so **sizing the terminal view's frame
/// to exactly the server's grid makes its own measurement produce the server's
/// grid**. There is nothing to fight and nothing to pin: the view measures
/// itself and arrives at the server's answer.
///
/// The frame is therefore the mirror's whole mechanism. It is laid out inside a
/// clipping host, so a grid wider than the phone simply overflows and is panned
/// over, and the emulator never learns that the phone is narrow.
///
/// ## Why the fit is rows-only
///
/// See `MirrorFit` in `:client` — the shared, unit-tested arithmetic this file
/// supplies platform metrics to. In short: one grid cannot fill both screens, and
/// fitting both axes lets the columns ratio bind, which shrinks the text to a
/// fraction of the user's own font and wastes most of the height. Filling the
/// height is legible; the overflowing columns are panned over.
///
/// - SeeAlso: `docs/server-side-screen.md`, the design record.
import SwiftTerm
import UIKit
import Client

// MARK: - Cell metrics

/// SwiftTerm's cell geometry, computed for any candidate font size without
/// touching the live view.
///
/// Every number here is copied from `AppleTerminalView.computeFontDimensions` and
/// `processSizeChange` deliberately: the mirror's fit is only correct if this
/// agrees with SwiftTerm exactly. A cell one pixel out is a last row — the prompt
/// — below the fold, which is the specific failure `MirrorFit` is documented
/// against.
enum SwiftTermCellMetrics {

    /// The cell one font size implies, in the form `MirrorFit` consumes.
    ///
    /// Note `lineSpacingAndAscentPx` is **zero** on iOS, and that is not an
    /// omission: SwiftTerm's row count is a plain `height / cellHeight` with no
    /// ascent term (unlike Termux's, which Android's provider feeds). Passing zero
    /// makes `MirrorFit.rowsThatFit` reduce to SwiftTerm's own expression.
    ///
    /// - Parameter fontSize: the candidate point size.
    /// - Returns: the cell geometry SwiftTerm would use at that size.
    static func metrics(fontSize: Int) -> Client.CellMetrics {
        let dim = cellDimension(fontSize: CGFloat(fontSize))
        return Client.CellMetrics(
            cellWidthPx: Float(dim.width),
            lineSpacingPx: Int32(dim.height),
            lineSpacingAndAscentPx: 0
        )
    }

    /// The exact `CellDimension` SwiftTerm computes for `fontSize`.
    ///
    /// Used both to feed ``metrics(fontSize:)`` and to size the terminal view's
    /// frame, which is what makes the view measure the server's grid.
    ///
    /// - Parameter fontSize: the point size to measure.
    /// - Returns: cell width and height in points.
    static func cellDimension(fontSize: CGFloat) -> CGSize {
        let font = TerminalFont.uiFont(size: fontSize)
        let ctFont = font as CTFont
        let cellHeight = ceil(CTFontGetAscent(ctFont) + CTFontGetDescent(ctFont) + CTFontGetLeading(ctFont))
        let cellWidth = "W".size(withAttributes: [.font: font]).width
        // Snap to the pixel grid the same way SwiftTerm does, or the cell we
        // measure and the cell it draws with differ by a sub-pixel and the
        // right-most column falls outside the frame we sized for it.
        let scale = UIScreen.main.scale
        let snappedWidth = ceil(cellWidth * scale) / scale
        let snappedHeight = ceil(cellHeight * scale) / scale
        return CGSize(
            width: max(1, snappedWidth),
            height: max(min(snappedHeight, 8192), 1)
        )
    }

    /// The grid `box` would fit at `fontSize` — measured and applied to nothing.
    ///
    /// The iOS counterpart of Android's `measureNaturalGrid` and the web's. This
    /// is deliberately **not** read back off the terminal: while mirroring, the
    /// applied font is the shrunken mirror font and the emulator's grid is the
    /// server's, so both would answer a different question. The caller needs "what
    /// would this phone render at, with the user's own font" — the take-over
    /// target, the connect-URL grid, and the baseline the mirror is measured
    /// against.
    ///
    /// - Parameters:
    ///   - box: the host view's pixel box.
    ///   - fontSize: the **user's** font size, never the applied one.
    /// - Returns: the natural grid, or nil while the view has no box yet, in which
    ///   case the caller must keep whatever it already had rather than conclude
    ///   anything.
    static func naturalGrid(box: CGSize, fontSize: CGFloat) -> (cols: Int, rows: Int)? {
        guard box.width > 0, box.height > 0, fontSize > 0 else { return nil }
        let dim = cellDimension(fontSize: fontSize)
        guard dim.width > 0, dim.height > 0 else { return nil }
        // SwiftTerm's own expression, floored at 2 so a degenerate box cannot ask
        // the server for a 0-column PTY.
        let cols = max(2, Int(box.width / dim.width))
        let rows = max(2, Int(box.height / dim.height))
        return (cols, rows)
    }
}

// MARK: - The solved window

/// The mirror's window onto the server's grid: what font to draw it at, where to
/// put it, and how far the zoom may go.
///
/// All of it is presentation. The emulator's grid stays the server's on both
/// axes, so nothing here costs a resize, a vote or a `SIGWINCH`.
struct MirrorWindow {
    /// The font actually applied: the fill-height font scaled by the pinch zoom.
    let fontSize: CGFloat
    /// The font at which all the server's rows fill the host's height — the
    /// default, and the zoom ceiling.
    let fillHeightFontSize: CGFloat
    /// The cell the applied font draws with.
    let cell: CGSize
    /// Points to shift the grid down by, centring it while it is shorter than the
    /// host — which is what keeps a zoomed-out mirror in the middle of the screen
    /// rather than parked against the top edge.
    let offsetY: CGFloat
    /// The full drawn width of the grid, which the pan range is measured against.
    let contentWidth: CGFloat
    /// The smallest useful zoom multiplier: the font at which the whole width fits
    /// at once. Derived per geometry rather than a constant, so zooming out always
    /// reaches the overview that makes a panned mirror navigable.
    let zoomFloor: CGFloat

    /// Absolute bounds for the mirror font, in points. The ceiling is above the
    /// phone's own driving font on purpose — filling the height with a laptop's
    /// rows can legitimately want larger text than the phone types at.
    static let minFontSize = 4
    static let maxFontSize = 36

    /// The mirror's zoom ceiling. `1.0` is the font that fills the host's height
    /// with the server's rows, and the cap is deliberate: above it the last row —
    /// the prompt — goes below the fold, and recovering it would need a second
    /// panning axis whose gesture collides with scrollback. A user who wants
    /// bigger text takes over.
    static let zoomMax: CGFloat = 1.0

    /// Solve the window for a server grid inside a host box.
    ///
    /// - Parameters:
    ///   - box: the host view's box in points.
    ///   - serverCols: the server's authoritative column count.
    ///   - serverRows: the server's authoritative row count.
    ///   - zoom: the user's pinch zoom, against the fill-height baseline.
    /// - Returns: the solved window, or nil for a degenerate box or grid.
    static func solve(box: CGSize, serverCols: Int, serverRows: Int, zoom: CGFloat) -> MirrorWindow? {
        guard box.width > 0, box.height > 0, serverCols > 0, serverRows > 0 else { return nil }
        let fit = Client.MirrorFit.shared
        // Kotlin's `(Int) -> CellMetrics` bridges with a boxed argument.
        let metrics: (KotlinInt) -> Client.CellMetrics = {
            SwiftTermCellMetrics.metrics(fontSize: $0.intValue)
        }

        let fillHeight = fit.solveFillHeightFont(
            viewHeightPx: Int32(box.height),
            serverRows: Int32(serverRows),
            minPx: Int32(minFontSize),
            maxPx: Int32(maxFontSize),
            metrics: metrics
        )
        let overview = fit.solveFitWidthFont(
            viewWidthPx: Int32(box.width),
            serverCols: Int32(serverCols),
            minPx: Int32(minFontSize),
            maxPx: Int32(maxFontSize),
            metrics: metrics
        )
        let floor = CGFloat(fit.zoomFloor(fillHeightFontPx: fillHeight, fitWidthFontPx: overview))
        let applied = Int(
            (CGFloat(fillHeight) * min(max(zoom, floor), zoomMax))
                .rounded()
                .clampedTo(lower: CGFloat(minFontSize), upper: CGFloat(fillHeight))
        )
        let appliedMetrics = SwiftTermCellMetrics.metrics(fontSize: applied)
        return MirrorWindow(
            fontSize: CGFloat(applied),
            fillHeightFontSize: CGFloat(fillHeight),
            cell: SwiftTermCellMetrics.cellDimension(fontSize: CGFloat(applied)),
            // Centred at the APPLIED font, not the fill-height one: a zoomed-out
            // grid is shorter than the host, and centring it beats parking it
            // against the top edge.
            offsetY: CGFloat(
                fit.centreOffsetY(
                    viewHeightPx: Int32(box.height),
                    serverRows: Int32(serverRows),
                    metrics: appliedMetrics
                )
            ),
            contentWidth: CGFloat(
                fit.contentWidthPx(serverCols: Int32(serverCols), cellWidthPx: appliedMetrics.cellWidthPx)
            ),
            zoomFloor: floor
        )
    }
}

private extension CGFloat {
    func clampedTo(lower: CGFloat, upper: CGFloat) -> CGFloat {
        Swift.min(Swift.max(self, lower), Swift.max(lower, upper))
    }
}

// MARK: - The host view

/// What ``MirrorHostView`` reports back to the coordinator that owns the layout.
protocol MirrorHostDelegate: AnyObject {
    /// The host's box changed (rotation, keyboard, split view), so the natural
    /// grid and the mirror fit both need re-solving.
    func mirrorHostDidLayout(_ host: MirrorHostView)
    /// The user panned the mirror horizontally by `delta` points.
    func mirrorHostDidPan(_ host: MirrorHostView, translationX: CGFloat)
    /// A pan ended with `velocityX` points/second, for the fling.
    func mirrorHostDidEndPan(_ host: MirrorHostView, velocityX: CGFloat)
    /// A pinch step, with its focal point in host coordinates.
    func mirrorHostDidPinch(_ host: MirrorHostView, scale: CGFloat, focusX: CGFloat)
    /// A double tap: toggle between filling the height and the whole-width
    /// overview.
    func mirrorHostDidDoubleTap(_ host: MirrorHostView)
}

/// The clipping container the SwiftTerm terminal is laid out inside.
///
/// It owns nothing about *what* the terminal shows — only where it sits and how
/// large it is drawn. Everything it does is presentation, so none of it can reach
/// the server.
final class MirrorHostView: UIView {

    /// The hosted terminal. Its frame is the mirror: see this file's header for
    /// why sizing the frame is what pins the grid.
    let terminalView: SwiftTerm.TerminalView

    weak var delegate: MirrorHostDelegate?

    /// The mirror's own pan. Recognizes simultaneously with the terminal's scroll
    /// view so one diagonal drag pans horizontally *and* scrolls scrollback
    /// vertically — which is what makes it feel like dragging a canvas rather than
    /// operating two separate controls.
    private(set) var mirrorPan: UIPanGestureRecognizer!
    private(set) var mirrorPinch: UIPinchGestureRecognizer!
    private(set) var mirrorDoubleTap: UITapGestureRecognizer!

    /// True while the mirror is pannable, i.e. while the content is wider than the
    /// host. Set by the coordinator from the solved window; gates the pan
    /// recognizer so a driving terminal (whose grid fits by definition) never
    /// swallows a horizontal drag.
    var panEnabled = false

    init(terminalView: SwiftTerm.TerminalView, gestureDelegate: UIGestureRecognizerDelegate) {
        self.terminalView = terminalView
        super.init(frame: .zero)
        // The whole point: a server grid wider or taller than the phone overflows
        // and is clipped here rather than being reflowed into the phone's width.
        clipsToBounds = true
        addSubview(terminalView)

        let pan = UIPanGestureRecognizer(target: self, action: #selector(handleMirrorPan(_:)))
        pan.delegate = gestureDelegate
        addGestureRecognizer(pan)
        mirrorPan = pan

        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handleMirrorPinch(_:)))
        pinch.delegate = gestureDelegate
        addGestureRecognizer(pinch)
        mirrorPinch = pinch

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleMirrorDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = gestureDelegate
        addGestureRecognizer(doubleTap)
        mirrorDoubleTap = doubleTap
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Deliberately does NOT size the terminal here. The terminal's frame is
        // the server's grid, and only the coordinator knows that; laying it out to
        // `bounds` would make this view a second geometry authority, which is the
        // exact thing the design forbids.
        delegate?.mirrorHostDidLayout(self)
    }

    /// Place the terminal at `frame`, without animation.
    ///
    /// Frame moves alone (panning) are cheap: SwiftTerm's `layoutSubviews` only
    /// reacts to changes in its *bounds*, so sliding the frame repaints without
    /// re-measuring the grid.
    ///
    /// - Parameter frame: the terminal's new frame in host coordinates.
    func placeTerminal(_ frame: CGRect) {
        guard terminalView.frame != frame else { return }
        UIView.performWithoutAnimation {
            terminalView.frame = frame
        }
    }

    @objc private func handleMirrorPan(_ gesture: UIPanGestureRecognizer) {
        switch gesture.state {
        case .changed:
            let dx = gesture.translation(in: self).x
            gesture.setTranslation(.zero, in: self)
            delegate?.mirrorHostDidPan(self, translationX: dx)
        case .ended, .cancelled:
            delegate?.mirrorHostDidEndPan(self, velocityX: gesture.velocity(in: self).x)
        default:
            break
        }
    }

    @objc private func handleMirrorPinch(_ gesture: UIPinchGestureRecognizer) {
        guard gesture.state == .changed else { return }
        let focus = gesture.location(in: self).x
        delegate?.mirrorHostDidPinch(self, scale: gesture.scale, focusX: focus)
        gesture.scale = 1.0
    }

    @objc private func handleMirrorDoubleTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended else { return }
        delegate?.mirrorHostDidDoubleTap(self)
    }
}
