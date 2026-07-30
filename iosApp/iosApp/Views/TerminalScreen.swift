import SwiftUI
import UIKit
import SwiftTerm
import Client

/// Full terminal emulator screen using SwiftTerm. Mirrors the Android
/// `TerminalScreen` which uses Termux's `TerminalView` + `TerminalEmulator`.
struct TerminalScreen: View {
    let sessionId: String
    var onBack: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme

    @State private var headerTitle: String = ""
    @State private var paneState: String?
    @State private var coordinator: TerminalCoordinator?
    @State private var swipeInputActive: Bool = false
    @State private var swipeText: String = ""
    @State private var ctrlSticky: Bool = false
    @State private var shiftSticky: Bool = false

    /// Whether the user has scrolled up off the bottom of the scrollback
    /// (auto-follow paused) — drives the floating "jump to bottom" pill.
    @State private var scrolledUp: Bool = false
    /// Whether fresh PTY output arrived while scrolled up — switches the pill
    /// label/colour to advertise it.
    @State private var hasNewOutput: Bool = false

    /// Whether another device is driving the session, so this screen is showing a
    /// passive mirror. Published by the coordinator's mode machine.
    @State private var passive: Bool = false
    /// [passive], debounced, so a momentary handoff blip doesn't flash the badge.
    @State private var showTakeOver: Bool = false

    /// Quiet threshold for the on-foreground PTY refresh: sessions that
    /// streamed output within the last few seconds are clearly alive and
    /// are left alone; everything else (idle or killed-while-suspended)
    /// gets a reconnect whose replay is prefixed with a terminal reset.
    private static let ptyResumeStaleMillis: Int64 = 3000

    var body: some View {
        VStack(spacing: 0) {
            // Terminal view fills available space
            if let coord = coordinator {
                TerminalViewRepresentable(coordinator: coord)
                    .ignoresSafeArea(.keyboard)
                    .overlay(alignment: .bottomTrailing) {
                        if scrolledUp {
                            ScrollToBottomPill(hasNewOutput: hasNewOutput) {
                                coord.scrollToBottom()
                                hasNewOutput = false
                            }
                            .padding(.trailing, 12)
                            .padding(.bottom, 10)
                            .transition(.opacity)
                        }
                    }
                    // Take-over badge: shown while another device drives the PTY.
                    // Tapping it is an explicit, input-free take-over. A tap on
                    // the mirror itself deliberately does nothing — see
                    // `TerminalCoordinator.handleMirrorTap`.
                    .overlay(alignment: .top) {
                        if showTakeOver {
                            TakeOverBadge { coord.ensureDriving() }
                                .padding(.top, 10)
                                .transition(.opacity)
                        }
                    }

                if swipeInputActive {
                    SwipeInputBar(text: $swipeText) {
                        // Send the typed text and the carriage return as two
                        // separate, ordered frames so Enter lands as its own
                        // keystroke — matching native typing. A single
                        // "<text>\r" burst written raw to the PTY often isn't
                        // treated as accept-line (the trailing CR gets absorbed
                        // into the burst), which made the command text appear
                        // but never run. An empty field still sends a bare CR.
                        coord.sendLine(Array(swipeText.utf8))
                        swipeText = ""
                    }
                }

                ImeHelperToolbar(
                    ctrlSticky: $ctrlSticky,
                    shiftSticky: $shiftSticky,
                    onSend: { bytes in
                        var modified = bytes
                        if ctrlSticky, bytes.count == 1, bytes[0] >= 0x61, bytes[0] <= 0x7a {
                            // Ctrl+letter: a=0x01, b=0x02, ..., z=0x1a
                            modified = [bytes[0] - 0x60]
                            ctrlSticky = false
                        } else if ctrlSticky {
                            ctrlSticky = false
                        }
                        if shiftSticky {
                            shiftSticky = false
                        }
                        coord.sendBytes(modified)
                    }
                )
            } else {
                Palette.background.ignoresSafeArea()
            }
        }
        .background(Palette.background)
        .navigationBarBackButtonHidden(false)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 6) {
                    // Leading pane-type icon (issue #48) — the same glyph the
                    // session list draws before each pane title, keeping the
                    // full-screen header consistent with the list. This screen
                    // only ever hosts terminal panes (never floating windows).
                    PaneIcon(kind: .terminal, floating: false)
                    // Pane status indicator (issue #38), painted in the theme
                    // foreground colour: idle = solid dot, working = breathing
                    // dot, waiting = pulsing warning triangle.
                    StatusDot(state: paneState, box: 18)
                    Text(headerTitle)
                        .font(.headline)
                        .foregroundStyle(Palette.textPrimary)
                        .lineLimit(1)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 4) {
                    Button {
                        swipeInputActive.toggle()
                    } label: {
                        Image(systemName: swipeInputActive ? "keyboard.chevron.compact.down.fill" : "keyboard.chevron.compact.down")
                            .foregroundStyle(swipeInputActive ? Palette.headerAccent : .gray)
                    }
                    .accessibilityLabel("Text input bar")
                    Button {
                        coordinator?.forceResize()
                    } label: {
                        ReformatIcon()
                            .foregroundStyle(Palette.headerAccent)
                    }
                    .accessibilityLabel("Reformat")
                }
            }
        }
        // Without this the pushed screen inherits the *pushing* screen's title
        // display mode. That used to be `.large`, so this screen reserved a
        // full large-title band and left it empty — this screen's title is the
        // `.principal` item above, not a navigation title (issue #136).
        .navigationBarTitleDisplayMode(.inline)
        // Name the bar's fill. `.visible` alone only forces the bar to *have* a
        // background, and the one it picks is the system material — which is
        // white on a light-mode device and read as a white band above the dark
        // terminal (issue #136). The scheme follows the theme's surface for the
        // same reason it does on the Sessions bar (issue #95).
        .toolbarBackground(Palette.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(
            Palette.backgroundIsDark(systemIsDark: colorScheme == .dark) ? .dark : .light,
            for: .navigationBar
        )
        .onAppear {
            setupTerminal()
            UIApplication.shared.isIdleTimerDisabled = true
        }
        // Debounce the badge: governance can change hands twice in quick
        // succession during a handoff, and a badge that blinks on the way past
        // reads as a glitch. `.task(id:)` cancels and restarts on every flip, so
        // only a passive state that *persists* raises it.
        .task(id: passive) {
            guard passive else {
                showTakeOver = false
                return
            }
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.15)) { showTakeOver = true }
        }
        .onDisappear {
            coordinator?.teardown()
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .onChange(of: scenePhase) { _, phase in
            // Refresh the terminal when the app returns to the foreground:
            // iOS kills suspended apps' TCP connections without an error,
            // so the PTY stream can be a zombie. The reconnect replays the
            // server's ring buffer behind a full terminal reset, bringing
            // the view up to date with whatever happened while away.
            if phase == .active {
                coordinator?.ptySocket.reconnectIfStale(maxQuietMillis: Self.ptyResumeStaleMillis)
            }
        }
        .onChange(of: colorScheme) { _, newScheme in
            // SwiftTerm's colours are installed imperatively, so a light/dark
            // flip won't repaint them on its own. Re-apply the active theme for
            // the new appearance so the matching slot's terminal colours take
            // effect immediately, matching the SwiftUI chrome around it.
            coordinator?.applyThemeForAppearance(isDark: newScheme == .dark)
        }
    }

    private func setupTerminal() {
        guard let client = ConnectionHolder.shared.client else {
            onBack()
            return
        }

        // The grid holder rides the connect URL, so the server authors this pane's
        // *attach* redraw at the width this phone renders at rather than at
        // whatever the PTY currently is — the difference between a pane that opens
        // correct and one that opens at another device's width and reflows a moment
        // later. It is filled by the first natural-grid measurement.
        let gridFlow = Client.PtyGridFlow()
        let ptySocket = client.openPtySocket(sessionId: sessionId, initialGrid: gridFlow.flow)
        let coord = TerminalCoordinator(
            ptySocket: ptySocket,
            client: client,
            sessionId: sessionId,
            gridFlow: gridFlow
        )
        self.coordinator = coord
        self.headerTitle = sessionId

        // Observe window config for pane title
        coord.onTitleChanged = { title in
            DispatchQueue.main.async { self.headerTitle = title }
        }

        // Observe per-session state for the dot indicator
        coord.onStateChanged = { state in
            DispatchQueue.main.async { self.paneState = state }
        }

        // Scroll position → pill visibility. When the user returns to the
        // bottom, also clear the "new output" hint so it resets for next time.
        coord.onScrollChanged = { atBottom in
            DispatchQueue.main.async {
                withAnimation(.easeOut(duration: 0.12)) {
                    self.scrolledUp = !atBottom
                    if atBottom { self.hasNewOutput = false }
                }
            }
        }

        // Output arrived while scrolled up → advertise it on the pill.
        coord.onNewOutputWhilePaused = {
            DispatchQueue.main.async {
                withAnimation(.easeOut(duration: 0.12)) { self.hasNewOutput = true }
            }
        }

        // Mode machine → take-over badge. The verdict is the server's (or the
        // width fallback when nobody governs); this view only renders it.
        coord.onPassiveChanged = { isPassive in
            DispatchQueue.main.async { self.passive = isPassive }
        }
    }
}

// MARK: - Terminal Coordinator

/// Manages the SwiftTerm TerminalView lifecycle, PtySocket I/O, and
/// server-pushed resize handling. This is the iOS equivalent of the
/// Android TerminalScreen's emulator + PtySocket wiring.
final class TerminalCoordinator: NSObject, TerminalViewDelegate, UIScrollViewDelegate, UIGestureRecognizerDelegate, MirrorHostDelegate {
    let ptySocket: Client.PtySocket
    let client: Client.LunamuxClient
    let sessionId: String

    var terminalView: SwiftTerm.TerminalView?
    /// The clipping container the terminal is laid out inside. It, not the
    /// terminal, is this screen's viewport: the terminal's frame is the *server's*
    /// grid and may be larger than the screen. See ``TerminalMirror``.
    weak var mirrorHost: MirrorHostView?
    var onTitleChanged: ((String) -> Void)?
    var onStateChanged: ((String?) -> Void)?
    /// Fires whenever the passive/driving verdict changes, driving the take-over
    /// badge.
    var onPassiveChanged: ((Bool) -> Void)?
    /// Fires with `true` when the viewport is at the bottom (auto-follow) and
    /// `false` when the user has scrolled up. Drives the "jump to bottom" pill.
    var onScrollChanged: ((Bool) -> Void)?
    /// Fires when PTY output arrives while the user is scrolled up.
    var onNewOutputWhilePaused: (() -> Void)?

    private let flowObserver = Client.FlowObserver()
    private var pendingOutput: [[UInt8]] = []
    /// A server size that arrived before the view existed, applied once
    /// `configureView` runs (the iOS analogue of the Android factory seed).
    private var pendingServerSize: (cols: Int, rows: Int)?
    private var applyingServerSize = false
    /// Whether the user has scrolled up off the bottom. SwiftTerm's iOS view
    /// renders straight from the scroll view's `contentOffset` and force-scrolls
    /// to the bottom on every output line (`updateScroller`), so we track the
    /// scroll position ourselves via the scroll-view delegate and re-pin the
    /// offset after each feed to hold the user's place.
    private var isScrolledUp = false
    /// The absolute `contentOffset.y` to hold while paused — anchored to the
    /// *content line* the user is reading, NOT the distance from the bottom.
    /// New output is appended below, so existing lines keep their y-position;
    /// holding this absolute offset keeps the read line perfectly still
    /// (holding distance-from-bottom instead would drift it up every line).
    /// It also survives a resume reset: once the replay regrows the content,
    /// the same absolute offset maps back to the same line.
    private var anchorOffsetY: CGFloat = 0
    /// Distance (px) under which we treat the viewport as "at the bottom" and
    /// resume auto-follow — covers sub-pixel rounding from `updateScroller`.
    private static let bottomEpsilon: CGFloat = 4
    /// The grid this phone would render at with the **user's own** font and the
    /// host's box — measured by ``SwiftTermCellMetrics/naturalGrid(box:fontSize:)``
    /// and applied to nothing.
    ///
    /// Deliberately not read back off the terminal: the terminal's grid is the
    /// *server's*, and while mirroring its font is the shrunken mirror font, so
    /// either would answer a different question and make the take-over target and
    /// the mirror's baseline circular. This is what Reformat and every take-over
    /// ask the PTY for, and what rides the connect URL.
    private var naturalGrid: (cols: Int, rows: Int)?

    /// The server's last authoritative PTY grid, or nil before it has said
    /// anything.
    private var serverGrid: (cols: Int, rows: Int)?

    /// The server's governance verdict for *this* connection: true when this
    /// device drives, false when another does, nil when no client governs yet (or
    /// the server is too old to say).
    ///
    /// Authoritative — governance is the server's decision — so it outranks the
    /// width comparison the mirror used to infer it from, which could not tell two
    /// same-width clients apart and could not see governance move without the grid
    /// moving. Nil falls back to that comparison.
    private var driving: Bool?

    /// The grid a take-over last asked for, held optimistically so a burst of
    /// keystrokes does not each fire a `forceResize` before the server's `Size`
    /// echo returns.
    private var drivingTo: (cols: Int, rows: Int)?

    /// Last verdict handed to ``onPassiveChanged``, so it only fires on a change.
    private var lastPassive = false

    /// Extra zoom applied to the mirror only, driven by pinch. Purely local
    /// presentation: the server grid is untouched, so no other client sees it.
    /// Kept across passive/driving transitions so re-entering the mirror preserves
    /// it.
    private var mirrorZoom: CGFloat = 1.0
    /// The mirror's horizontal pan, in points from the grid's left edge.
    private var mirrorPan: CGFloat = 0
    /// The solved mirror window, or nil while driving (then the grid is this
    /// phone's own and the window is the whole host).
    private var mirrorWindow: MirrorWindow?
    /// The font last pushed to the terminal, so the guarded setter fires only on a
    /// real change — SwiftTerm's `font` setter runs `resetFont`, which resizes the
    /// emulator *and* soft-resets it.
    private var appliedFontSize: CGFloat?
    /// Display link driving the pan fling's decay.
    private var flingLink: CADisplayLink?
    private var flingVelocity: CGFloat = 0

    /// The desired-grid holder handed to `openPtySocket`, so the connect URL
    /// carries this phone's width and the server authors its attach redraw at it.
    private let gridFlow: Client.PtyGridFlow

    /// Size requests are paced against the server's answers, not against a timer.
    /// - SeeAlso: ``SizeVoteClock``
    private var sizeVotes: SizeVoteClock!

    /// Our own pan recognizer that converts vertical finger swipes into mouse
    /// wheel events while the foreground program has mouse reporting enabled
    /// (full-screen TUIs — Claude Code, vim, less, tmux — that own the
    /// alternate screen and therefore have no local scrollback to drag). See
    /// `handleScrollPan(_:)` for why this is needed and how it mirrors Android's
    /// `TerminalView.doScroll`.
    weak var scrollWheelPan: UIPanGestureRecognizer?
    /// Accumulated vertical pan translation (points) not yet converted into a
    /// discrete wheel step; one step is emitted per cell-height of travel.
    private var wheelScrollAccumulator: CGFloat = 0
    /// The **user's** terminal font size — the one this phone drives at, and the
    /// one the natural grid is measured with. Never the mirror's fitted size.
    private(set) var currentFontSize: CGFloat = 12
    private static let minFontSize: CGFloat = 6
    private static let maxFontSize: CGFloat = 32

    /// The face the terminal draws with, at `size`. See ``TerminalFont`` for why
    /// it is shared with the overview previews rather than built here.
    ///
    /// Called for the initial font and on every pinch-to-zoom step.
    ///
    /// - Parameter size: point size for the returned face.
    /// - Returns: the terminal face, per-glyph fallback chain attached.
    private static func terminalFont(size: CGFloat) -> UIFont {
        TerminalFont.uiFont(size: size)
    }

    init(
        ptySocket: Client.PtySocket,
        client: Client.LunamuxClient,
        sessionId: String,
        gridFlow: Client.PtyGridFlow
    ) {
        self.ptySocket = ptySocket
        self.client = client
        self.sessionId = sessionId
        self.gridFlow = gridFlow
        super.init()
        sizeVotes = SizeVoteClock(
            sendVote: { [weak self] cols, rows in
                try? await self?.ptySocket.resize(cols: Int32(cols), rows: Int32(rows))
            },
            sendForce: { [weak self] cols, rows in
                try? await self?.ptySocket.forceResize(cols: Int32(cols), rows: Int32(rows))
            }
        )
        subscribeFlows()
    }

    func configureView(_ view: SwiftTerm.TerminalView) {
        self.terminalView = view
        view.terminalDelegate = self
        // SwiftTerm's iOS TerminalView is a UIScrollView but leaves its scroll
        // delegate unset, so we can take it to observe user scrolling for the
        // pause/jump-to-bottom affordance without disturbing SwiftTerm.
        view.delegate = self
        view.inputAccessoryView = nil
        // Don't let the scroll view auto-inset for the safe area. Inside a
        // NavigationStack SwiftUI can propagate the window's top safe area to
        // a hosted UIScrollView, which pushes the resting `contentOffset` to a
        // negative value; SwiftTerm then draws its first row below that inset,
        // leaving a band of background at the top (the "weird padding" seen on
        // notched phones — it varies by device because the inset is the safe
        // area height). `.never` also keeps `contentOffset` free of any inset
        // so the scroll-pause offset math below stays exact.
        view.contentInsetAdjustmentBehavior = .never

        // TEMP diagnostic — remove once font load is confirmed working.
        print("[Lunamux] JetBrainsMono-Regular loaded: \(UIFont(name: "JetBrainsMono-Regular", size: 12) != nil)")

        // Flush any PTY output that arrived before the view was created
        for chunk in pendingOutput {
            view.feed(byteArray: ArraySlice(chunk))
        }
        pendingOutput.removeAll()

        // Apply a server size that arrived before this view existed (the
        // ordered events stream no-ops a Size while terminalView is nil).
        if let ps = pendingServerSize {
            pendingServerSize = nil
            applyServerSize(cols: ps.cols, rows: ps.rows)
        }

        // A pane restored directly into a full-screen app (mouse reporting
        // already on from the replayed buffer) must start with native scrolling
        // disabled; otherwise the first swipe tears the top rows before the next
        // fed chunk re-syncs it.
        syncScrollEnabled(view)

        // Set a reasonable default font size (Android uses 32px ≈ ~12pt at 2x/3x).
        // On a roomy iPad canvas (regular horizontal size class) the 12 pt phone
        // default reads tiny, so start a few points larger.
        let isRegularWidth = view.traitCollection.horizontalSizeClass == .regular
        currentFontSize = isRegularWidth ? 15 : 12
        // Applied through the geometry pass rather than directly, so the frame is
        // always sized for the font before the font is installed — SwiftTerm's
        // `font` setter derives a grid from the *current* frame (`resetFont`), so
        // setting it against a stale frame resizes the emulator to a grid nobody
        // asked for and soft-resets it on the way.
        applyGeometry()

        // Apply theme from centrally-fetched settings, or fetch independently
        if let settings = Palette.settings {
            applyTheme(settings)
        } else {
            Task {
                if let config = try? await client.fetchThemeConfig() {
                    DispatchQueue.main.async {
                        Palette.config = config
                        if let settings = Palette.settings {
                            self.applyTheme(settings)
                        }
                    }
                }
            }
        }
    }

    private func subscribeFlows() {
        // PTY transport events → terminal, in the order the server produced
        // them (output bytes, authoritative size changes and reconnect resets
        // on one stream, so a resize never races the redraw bytes it triggers).
        flowObserver.observe(flow: ptySocket.events) { [weak self] value in
            guard let self else { return }
            if let bytesEv = value as? Client.PtyEventBytes {
                let bytes = bytesEv.data.toSwiftData()
                DispatchQueue.main.async {
                    guard let tv = self.terminalView else {
                        self.pendingOutput.append(bytes)
                        return
                    }
                    let wasPaused = self.isScrolledUp
                    // A real server-side `reset` still arrives as ESC c inside
                    // the byte stream and reverts the palette to stock; a
                    // reconnect is a PtyEvent.Reset (handled below) instead.
                    let isReset = Self.containsTerminalReset(bytes)

                    tv.feed(byteArray: ArraySlice(bytes))

                    if isReset, let settings = Palette.settings {
                        self.applyTheme(settings)
                    }
                    // Keep native scrolling disabled while the program owns the
                    // screen (mouse reporting on): this fed chunk may have just
                    // entered/left the alternate screen, so re-sync now.
                    self.syncScrollEnabled(tv)
                    // SwiftTerm's `updateScroller` just yanked the viewport to
                    // the bottom. If the user was reading history, re-pin them
                    // at the same distance from the bottom. Skipped in mouse-
                    // reporting mode, where the viewport is pinned to the bottom
                    // and swipes are forwarded as wheel events instead.
                    if wasPaused && !self.mouseReportingActive(tv) {
                        self.repinScroll(tv)
                        self.onNewOutputWhilePaused?()
                    }
                }
            } else if let sizeEv = value as? Client.PtyEventSize {
                let cols = Int(sizeEv.cols)
                let rows = Int(sizeEv.rows)
                DispatchQueue.main.async {
                    self.applyServerSize(cols: cols, rows: rows)
                }
            } else if let govEv = value as? Client.PtyEventGovernance {
                // Who drives is the server's call. An ungoverned session (nobody
                // has acted yet, or the governor just left) clears the verdict
                // rather than pinning a stale one, so the width fallback resumes.
                let verdict: Bool? = govEv.governed ? govEv.driving : nil
                DispatchQueue.main.async {
                    self.driving = verdict
                    // Re-solve now, on the event stream, rather than on the next
                    // layout pass: a same-width take-over moves governance with no
                    // `Size` frame at all, so this is the only signal that the
                    // presentation must change hands.
                    self.applyGeometry()
                    self.publishPassive()
                    if let tv = self.terminalView { self.syncScrollEnabled(tv) }
                }
            } else if value is Client.PtyEventReset {
                DispatchQueue.main.async {
                    guard let tv = self.terminalView else { return }
                    // Reconnect boundary: clear the emulator and re-apply the
                    // theme before the ring-buffer replay that follows (the RIS
                    // no longer rides the byte stream).
                    tv.feed(byteArray: ArraySlice([0x1b, UInt8(ascii: "c")]))
                    if let settings = Palette.settings {
                        self.applyTheme(settings)
                    }
                }
            }
        }

        // Window config → pane title
        flowObserver.observe(flow: client.windowState.config) { [weak self] value in
            guard let self, let config = value as? Client.WindowConfig else { return }
            if let title = Self.findLeafTitle(config: config, sessionId: self.sessionId) {
                self.onTitleChanged?(title)
            }
        }

        // Per-session state → dot indicator
        flowObserver.observe(flow: client.windowState.states) { [weak self] value in
            guard let self else { return }
            let state: String?
            if let dict = value as? NSDictionary {
                state = dict[self.sessionId] as? String
            } else if let dict = value as? [String: Any] {
                state = dict[self.sessionId] as? String
            } else {
                state = nil
            }
            DispatchQueue.main.async { self.onStateChanged?(state) }
        }
    }

    // MARK: - Geometry: the server's grid, this phone's window onto it

    /// Adopt the server's authoritative grid.
    ///
    /// The emulator follows the server on **both** axes, driving or mirroring.
    /// The stream is absolutely cursor-addressed for exactly the server's screen,
    /// so a client whose rows differ lands every address in the wrong place — the
    /// symptom being typed characters splicing into the middle of the transcript.
    ///
    /// - Parameters:
    ///   - cols: the server's column count.
    ///   - rows: the server's row count.
    private func applyServerSize(cols: Int, rows: Int) {
        guard cols > 0, rows > 0 else { return }
        serverGrid = (cols, rows)
        // The vote pipeline is clocked by these frames: any answer from the server
        // resolves whatever this phone last asked for.
        sizeVotes.onServerGrid(cols: cols, rows: rows)
        // The server drifted off the grid we forced to → another device reclaimed;
        // drop the optimistic guard so the next real input re-takes-over.
        if let d = drivingTo, d != (cols, rows) { drivingTo = nil }

        // A Size can arrive before the view is laid out; stash it and let
        // configureView apply it once terminalView exists.
        guard terminalView != nil else {
            pendingServerSize = (cols, rows)
            return
        }
        applyingServerSize = true
        terminalView?.getTerminal().resize(cols: cols, rows: rows)
        // Re-solve the window against the new grid and resize the terminal's frame
        // to match it, so the view's own measurement agrees with what we just did
        // rather than undoing it on the next layout pass.
        applyGeometry()
        publishPassive()
        terminalView?.setNeedsDisplay()
        DispatchQueue.main.async { self.applyingServerSize = false }
    }

    /// Whether this phone should present a passive mirror rather than drive.
    ///
    /// The shared definition, so Android, web and iOS cannot disagree about what
    /// "passive" means.
    var isPassive: Bool {
        Client.PtyPresentation.shared.isPassive(
            naturalCols: Int32(naturalGrid?.cols ?? 0),
            serverCols: Int32(serverGrid?.cols ?? 0),
            driving: driving.map { KotlinBoolean(bool: $0) }
        )
    }

    /// Push the verdict out to the badge, but only when it actually moved.
    private func publishPassive() {
        let now = isPassive
        guard now != lastPassive else { return }
        lastPassive = now
        if !now {
            // Take-over: the mirror's window collapses. Zoom is kept (re-entering
            // the mirror preserves it); the pan is not, because a driving grid fits
            // its own view and there is nowhere to pan to.
            mirrorPan = 0
            stopFling()
        }
        onPassiveChanged?(now)
    }

    /// Re-measure this phone's natural grid and *ask* the PTY for it.
    ///
    /// Driven by the host's layout pass (rotation, keyboard show/hide, split) and
    /// by a change to the user's font size.
    ///
    /// Measuring is unconditional; only the **ask** is a vote, and a vote is soft —
    /// the arbiter moves governance only on an explicit force or on real input. So
    /// rotating the phone rescales the mirror instead of stealing the PTY, while
    /// still keeping the take-over target and the fit baseline truthful.
    ///
    /// Deliberately not a force: merely opening a pane must not seize the size from
    /// the device the user is actually working on — every PTY resize makes the
    /// running program repaint, and a normal-buffer repainter (Claude Code) leaks a
    /// duplicate frame into scrollback each time (anthropics/claude-code#49086).
    /// Taking over stays explicit: the Reformat button, the badge, or real typing.
    private func remeasureAndAsk() {
        guard let host = mirrorHost else { return }
        guard let natural = SwiftTermCellMetrics.naturalGrid(
            box: host.bounds.size,
            fontSize: currentFontSize
        ) else { return }
        if naturalGrid == nil || naturalGrid! != natural {
            naturalGrid = natural
            gridFlow.set(cols: Int32(natural.cols), rows: Int32(natural.rows))
        }
        sizeVotes.request(cols: natural.cols, rows: natural.rows, force: false)
    }

    /// Lay the terminal out: pick the font, size its frame to the server's grid,
    /// and place it.
    ///
    /// This is the *only* place the terminal's frame or font is set, and that is
    /// the whole pin. Because SwiftTerm derives its grid as
    /// `Int(bounds / cell)`, a frame sized to `serverGrid x cell` makes the view's
    /// own measurement land on the server's answer — so no layout pass can ever
    /// reflow the emulator out from under the redraw the server authored for it.
    func applyGeometry() {
        guard let view = terminalView, let host = mirrorHost else { return }
        let box = host.bounds.size

        guard box.width > 0, box.height > 0, let grid = serverGrid else {
            // No box yet (pre-layout), or nothing said by the server yet: there is
            // no grid to pin to, so the terminal simply fills the host — the
            // pre-attach behaviour, and what demo/offline panes keep.
            applyFont(currentFontSize, view: view, frame: host.bounds)
            host.panEnabled = false
            return
        }

        let passive = isPassive
        let window = passive
            ? MirrorWindow.solve(
                box: box,
                serverCols: grid.cols,
                serverRows: grid.rows,
                zoom: mirrorZoom
            )
            : nil
        mirrorWindow = window

        let fontSize = window?.fontSize ?? currentFontSize
        let cell = window?.cell ?? SwiftTermCellMetrics.cellDimension(fontSize: fontSize)
        let contentWidth = CGFloat(grid.cols) * cell.width
        let contentHeight = CGFloat(grid.rows) * cell.height

        // A sub-point epsilon on each axis, and the size of it is load-bearing in
        // both directions.
        //
        // It has to be there at all because the frame must floor-divide back to
        // exactly the server's grid: `cols * cellWidth / cellWidth` is not reliably
        // `cols` in binary floating point, and one ulp low floors to `cols - 1`.
        // That would not merely drop the right-most column — SwiftTerm would resize
        // the emulator to the short grid, this coordinator would put it back, and
        // the two would trade places on every layout pass.
        //
        // It has to stay *small* because SwiftTerm pins `contentOffset` to
        // `(lines - rows) * cellHeight` while the scroll view's own maximum is
        // `contentSize - bounds`, so every point of slack here is a point the
        // viewport can settle back by under a drag. Half a cell — the obvious
        // choice — is half a row of visible drift. Half a point is ~1.5 px and is
        // still eleven orders of magnitude above the rounding it defends against.
        let epsilon: CGFloat = 0.5
        let frameSize = CGSize(
            width: contentWidth + epsilon,
            height: contentHeight + epsilon
        )

        let originX: CGFloat
        let originY: CGFloat
        if let window {
            mirrorPan = CGFloat(Client.MirrorFit.shared.clampPan(
                panPx: Float(mirrorPan),
                contentWidthPx: Float(window.contentWidth),
                viewWidthPx: Int32(box.width)
            ))
            originX = -mirrorPan
            originY = window.offsetY
            host.panEnabled = window.contentWidth > box.width
        } else {
            // A driving grid fits its own view, so there is nothing to centre and
            // nowhere to pan.
            mirrorPan = 0
            originX = 0
            originY = 0
            host.panEnabled = false
        }

        applyFont(fontSize, view: view, frame: CGRect(origin: CGPoint(x: originX, y: originY), size: frameSize))
    }

    /// Place the frame, then install the font — in that order, always.
    ///
    /// SwiftTerm's `font` setter calls `resetFont`, which recomputes the cell and
    /// then resizes the emulator from the frame it finds. Installing a font against
    /// a frame still sized for the *old* cell therefore resizes the shared emulator
    /// to a grid nobody asked for. Sizing first makes that derived resize land on
    /// exactly the grid we want.
    ///
    /// That derived resize also **soft-resets** the emulator (`Terminal.softReset`),
    /// which is SwiftTerm's behaviour and not something a package consumer can turn
    /// off. Two consequences are worth naming, because the mirror changes the font
    /// far more often than a driving terminal ever did:
    ///
    /// - `resetAllColors()` reverts the palette to stock, so the applied theme has
    ///   to be re-installed or default-coloured text becomes unreadable against the
    ///   themed background. This was already true of pinch-to-zoom before the
    ///   mirror existed; it simply went unnoticed because it took a deliberate
    ///   gesture to trigger.
    /// - Scroll margins and the application-cursor mode are cleared. Those are
    ///   captured and restored here because a full-screen TUI's mirror renders
    ///   wrong without its margins, and the arrow-key encoding matters the moment
    ///   the user takes over. The remaining flags (origin mode, insert mode,
    ///   cursor visibility) are not public API on `Terminal` and are left to the
    ///   next resync's mode epilogue.
    ///
    /// - Parameters:
    ///   - fontSize: the point size to draw at.
    ///   - view: the terminal.
    ///   - frame: the terminal's frame in host coordinates.
    private func applyFont(_ fontSize: CGFloat, view: SwiftTerm.TerminalView, frame: CGRect) {
        mirrorHost?.placeTerminal(frame)
        guard appliedFontSize != fontSize else { return }
        appliedFontSize = fontSize

        let terminal = view.getTerminal()
        let scrollTop = terminal.buffer.scrollTop
        let scrollBottom = terminal.buffer.scrollBottom
        let applicationCursor = terminal.applicationCursor

        view.font = Self.terminalFont(size: fontSize)

        // Clamped, because the resize the font change provoked may have moved the
        // row count out from under the saved margin.
        let rows = terminal.rows
        if scrollTop < rows {
            terminal.buffer.scrollTop = scrollTop
            terminal.buffer.scrollBottom = min(scrollBottom, rows - 1)
        }
        terminal.applicationCursor = applicationCursor
        if let settings = Palette.settings {
            applyTheme(settings)
        }
    }

    /// Take-over: force the shared PTY to this phone's natural grid.
    ///
    /// A no-op when the server already matches (ordinary typing while driving is
    /// free) or when a force to that grid is already in flight. After the force the
    /// server's `Size` and resync arrive, the verdict flips, and the mirror's window
    /// collapses. Invoked by real input, the take-over badge and Reformat — the
    /// "intent, not presence" model.
    func ensureDriving() {
        guard let local = naturalGrid, local.cols > 0, local.rows > 0 else { return }
        if let sg = serverGrid, sg == local { return }
        if let d = drivingTo, d == local { return }
        drivingTo = local
        sizeVotes.request(cols: local.cols, rows: local.rows, force: true)
    }

    /// Reformat: the explicit, input-free take-over on the toolbar.
    func forceResize() {
        guard let local = naturalGrid, local.cols > 0, local.rows > 0 else { return }
        drivingTo = local
        sizeVotes.request(cols: local.cols, rows: local.rows, force: true)
    }

    /// The maximum vertical content offset (the "bottom" of the scrollback).
    private func maxOffsetY(_ view: SwiftTerm.TerminalView) -> CGFloat {
        max(0, view.contentSize.height - view.bounds.height)
    }

    /// Re-pins the viewport to the anchored content line after SwiftTerm
    /// scrolled it to the bottom on new output, keeping the read line still.
    private func repinScroll(_ view: SwiftTerm.TerminalView) {
        let target = min(anchorOffsetY, maxOffsetY(view))
        if abs(view.contentOffset.y - target) > 0.5 {
            view.contentOffset.y = target
        }
    }

    /// Jumps to the bottom and resumes auto-follow. Wired to the pill tap.
    func scrollToBottom() {
        guard let view = terminalView else { return }
        isScrolledUp = false
        anchorOffsetY = maxOffsetY(view)
        view.setContentOffset(CGPoint(x: 0, y: maxOffsetY(view)), animated: true)
        onScrollChanged?(true)
    }

    /// UIScrollViewDelegate: observe *user* scrolling (ignore the programmatic
    /// offset changes SwiftTerm makes via `updateScroller` on output) and drive
    /// the pause state + pill. While scrolled up, anchor to the absolute offset
    /// so the read line stays put across subsequent feeds.
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard let view = terminalView else { return }
        // In mouse-reporting mode there's no scrollback pause affordance — the
        // viewport stays pinned and swipes become wheel events — so ignore any
        // offset changes here.
        guard !mouseReportingActive(view) else { return }
        // Only react to user-initiated scrolling; SwiftTerm's auto-scroll to
        // the bottom also lands here but with no active drag/deceleration.
        guard scrollView.isDragging || scrollView.isDecelerating || scrollView.isTracking else { return }
        let dist = max(0, maxOffsetY(view) - scrollView.contentOffset.y)
        let up = dist > Self.bottomEpsilon
        if up { anchorOffsetY = scrollView.contentOffset.y }
        if up != isScrolledUp {
            isScrolledUp = up
            onScrollChanged?(!up)
        }
    }

    // MARK: - UIGestureRecognizerDelegate (wheel-scroll pan)

    /// Only lets `scrollWheelPan` begin while mouse reporting is active. When
    /// it's off, the recognizer fails immediately, releasing the failure
    /// requirement below so the scroll view's own pan drives native scrollback
    /// scrolling as usual.
    ///
    /// - Parameter gestureRecognizer: The recognizer asking to begin.
    /// - Returns: `true` to begin; `false` to fail (defer to native scrolling).
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        // The mirror's pan only exists while there is something to pan to: a
        // driving grid fits its own view by definition, so the recognizer fails
        // and the terminal's own gestures behave exactly as they do today.
        if gestureRecognizer === mirrorHost?.mirrorPan {
            return mirrorHost?.panEnabled == true
        }
        // Pinch means two different things, and both are allowed. While mirroring
        // it is purely local presentation — the server grid is untouched, so no
        // other client sees it. While driving it changes the user's font, which
        // changes the grid this device would fit, so it measures and *votes* and
        // waits for the server, exactly as the web client's font-size control does.
        //
        // Android instead makes the driving font a fixed setting and the pinch
        // mirror-only. That is not portable here: iOS has no font-size setting
        // anywhere else, so the same choice would leave the terminal with no font
        // control at all.
        if gestureRecognizer === mirrorHost?.mirrorPinch {
            return true
        }
        if gestureRecognizer === mirrorHost?.mirrorDoubleTap {
            return isPassive && mirrorHost?.panEnabled == true
        }
        guard gestureRecognizer === scrollWheelPan, let view = terminalView else { return true }
        // While mirroring, a swipe scrolls our own transcript rather than being
        // turned into wheel reports for the remote program: the synthesized redraw
        // replays the driving program's mouse-tracking modes, and the mirror drops
        // the reports it would then emit, so without this the mirror could not be
        // scrolled at all. Android calls the same thing `setLocalScrollOnly`.
        return mouseReportingActive(view) && !isPassive
    }

    /// Coexist with the taps / long-press / pinch recognizers, but never with
    /// another *pan* — while our wheel-scroll pan is active it must be the sole
    /// pan handler so SwiftTerm's mouse-drag pan and the scroll view's own pan
    /// don't also fire (which would start an in-app selection or a competing
    /// scroll). Paired with `shouldBeRequiredToFailBy` below.
    ///
    /// - Parameters:
    ///   - gestureRecognizer: Our recognizer (`scrollWheelPan`).
    ///   - otherGestureRecognizer: The competing recognizer.
    /// - Returns: `true` for non-pan recognizers, `false` for pans.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // The mirror's pan must coexist with the terminal's scroll view, because
        // one diagonal drag has to do both jobs: our recognizer takes the
        // horizontal component and the scroll view keeps the vertical one. Taking
        // the gesture exclusively instead would make a panned mirror unscrollable.
        if gestureRecognizer === mirrorHost?.mirrorPan { return true }
        if gestureRecognizer === mirrorHost?.mirrorPinch { return true }
        guard gestureRecognizer === scrollWheelPan else { return false }
        return !(otherGestureRecognizer is UIPanGestureRecognizer)
    }

    /// Makes the other pan recognizers (SwiftTerm's mouse-drag pan and the
    /// scroll view's built-in pan) wait for `scrollWheelPan` to fail while mouse
    /// reporting is active — so when our wheel pan recognizes, they're blocked.
    /// When reporting is off, `gestureRecognizerShouldBegin` fails our pan and
    /// native scrolling proceeds.
    ///
    /// - Parameters:
    ///   - gestureRecognizer: Our recognizer (`scrollWheelPan`).
    ///   - otherGestureRecognizer: The competing recognizer to gate.
    /// - Returns: `true` to require our pan's failure before `otherGestureRecognizer`.
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === scrollWheelPan,
              otherGestureRecognizer is UIPanGestureRecognizer,
              let view = terminalView else { return false }
        return mouseReportingActive(view)
    }

    // MARK: - MirrorHostDelegate

    /// The host's box moved (rotation, keyboard, split view): re-measure what this
    /// phone would fit, and re-solve the mirror's window against the new box.
    func mirrorHostDidLayout(_ host: MirrorHostView) {
        remeasureAndAsk()
        applyGeometry()
        publishPassive()
    }

    /// Pan the window, never the grid. Both offsets are pure view state, so this
    /// costs no resize, no vote and no `SIGWINCH`, and no other client sees it.
    func mirrorHostDidPan(_ host: MirrorHostView, translationX: CGFloat) {
        stopFling()
        // Pixels, not columns: cell-snapped panning is visibly janky.
        mirrorPan -= translationX
        applyGeometry()
    }

    func mirrorHostDidEndPan(_ host: MirrorHostView, velocityX: CGFloat) {
        startFling(velocityX: velocityX)
    }

    /// Pinch. Two gestures wearing one recognizer, because they read identically to
    /// the hand: make the text bigger.
    ///
    /// **While driving** it changes the user's font size. That changes the grid this
    /// device would fit, so it re-measures and votes and waits for the server to
    /// answer — it does *not* refit the terminal locally. A client that answered its
    /// own font change would be a second geometry authority, which is the
    /// disagreement the whole design removes; the visible cost is the tmux round
    /// trip, accepted deliberately.
    ///
    /// **While mirroring** it is local zoom on the mirror's window, and nothing
    /// leaves the device.
    func mirrorHostDidPinch(_ host: MirrorHostView, scale: CGFloat, focusX: CGFloat) {
        // Deadzone against jitter, matching Android's 5% threshold.
        guard scale < 0.95 || scale > 1.05 else { return }

        guard let window = mirrorWindow else {
            let target = (currentFontSize * scale).clamped(to: Self.minFontSize...Self.maxFontSize)
            // Snap to 0.5 pt increments so a slow pinch does not send a vote per
            // sub-point step.
            let rounded = (target * 2).rounded() / 2
            guard rounded != currentFontSize else { return }
            currentFontSize = rounded
            applyGeometry()
            remeasureAndAsk()
            return
        }
        let oldCellWidth = window.cell.width
        // Bounds are derived, not constant: the floor is the font at which the
        // whole width fits (the overview) and the ceiling the one that fills the
        // height. A fixed floor could not reach the overview, and anything above
        // the ceiling hides the prompt.
        mirrorZoom = min(max(mirrorZoom * scale, window.zoomFloor), MirrorWindow.zoomMax)
        applyGeometry()
        guard let newCellWidth = mirrorWindow?.cell.width, newCellWidth != oldCellWidth else { return }
        mirrorPan = CGFloat(Client.MirrorFit.shared.focalAnchoredPan(
            oldPanPx: Float(mirrorPan),
            focusXPx: Float(focusX),
            oldCellWidthPx: Float(oldCellWidth),
            newCellWidthPx: Float(newCellWidth)
        ))
        // Unclamped above by design — clamped here, against the *new* content width.
        applyGeometry()
    }

    /// Double tap toggles the mirror between filling the height and the whole-width
    /// overview — the photo-viewer idiom, and what makes a panned mirror navigable:
    /// the overview is the map.
    func mirrorHostDidDoubleTap(_ host: MirrorHostView) {
        guard let window = mirrorWindow, window.zoomFloor < MirrorWindow.zoomMax else { return }
        let midpoint = (window.zoomFloor + MirrorWindow.zoomMax) / 2
        mirrorZoom = mirrorZoom > midpoint ? window.zoomFloor : MirrorWindow.zoomMax
        applyGeometry()
    }

    // MARK: - Pan fling

    /// Decay a flick into a glide, so the mirror feels like a canvas rather than a
    /// control that stops dead under the finger.
    ///
    /// A display-link decay rather than a `UIView` animation because the pan is not
    /// an animatable property — it is recomputed and re-clamped through
    /// `applyGeometry` on every step, which is also what stops the glide cleanly at
    /// either edge.
    ///
    /// - Parameter velocityX: the gesture's exit velocity in points/second.
    private func startFling(velocityX: CGFloat) {
        stopFling()
        guard mirrorHost?.panEnabled == true, abs(velocityX) > 80 else { return }
        flingVelocity = -velocityX
        let link = CADisplayLink(target: self, selector: #selector(stepFling))
        link.add(to: .main, forMode: .common)
        flingLink = link
    }

    @objc private func stepFling(_ link: CADisplayLink) {
        let dt = CGFloat(link.duration)
        mirrorPan += flingVelocity * dt
        // Exponential decay, tuned to settle in roughly half a second.
        flingVelocity *= pow(0.002, dt)
        let before = mirrorPan
        applyGeometry()
        // Clamped to a standstill (an edge) or slowed to a crawl: stop.
        if abs(flingVelocity) < 40 || mirrorPan != before {
            stopFling()
        }
    }

    private func stopFling() {
        flingLink?.invalidate()
        flingLink = nil
        flingVelocity = 0
    }

    /// Whether the foreground program currently has mouse reporting on. When it
    /// does, the app owns the alternate screen (no local scrollback) and expects
    /// wheel events to move its own viewport, so finger swipes must be forwarded
    /// as wheel events rather than handled by the scroll view.
    ///
    /// - Parameter view: The SwiftTerm view whose terminal is queried.
    /// - Returns: `true` when mouse reporting is active and allowed.
    private func mouseReportingActive(_ view: SwiftTerm.TerminalView) -> Bool {
        view.allowMouseReporting && view.getTerminal().mouseMode != .off
    }

    /// Enables the scroll view's own scrolling only when the program is *not*
    /// mouse-reporting.
    ///
    /// In mouse-reporting mode the foreground app owns the alternate screen and
    /// has no local scrollback, so the scroll view has nothing to scroll — but
    /// its pan would still drag `contentOffset` around, and SwiftTerm renders
    /// straight from `contentOffset`, so a stray offset tears the top rows
    /// (drawing stale scrollback lines there). Pinning `isScrollEnabled = false`
    /// keeps `contentOffset` at the bottom; our `scrollWheelPan` is a separate
    /// recognizer and keeps working, forwarding swipes as wheel events. Called
    /// from `configureView` and after every fed chunk (mouse mode can toggle at
    /// any time).
    ///
    /// - Parameter view: The SwiftTerm view to reconfigure.
    private func syncScrollEnabled(_ view: SwiftTerm.TerminalView) {
        // While mirroring, keep native scrolling on even in mouse-reporting mode.
        // The synthesized redraw replays the driving program's mouse-tracking
        // modes, but the mirror has a local transcript of its own and must not
        // inject input, so a swipe belongs to our scrollback rather than to the
        // remote program. Android calls the same thing `setLocalScrollOnly`.
        let shouldScroll = !mouseReportingActive(view) || isPassive
        if view.isScrollEnabled != shouldScroll {
            view.isScrollEnabled = shouldScroll
        }
    }

    /// Converts a vertical finger swipe into a stream of mouse wheel events for
    /// the foreground program.
    ///
    /// Wired to `scrollWheelPan` (installed in `TerminalViewRepresentable.makeUIView`).
    /// SwiftTerm's iOS view only translates swipes into wheel scrolling for the
    /// *local* scrollback; in mouse-reporting mode its pan handler instead sends
    /// button-drag motion, which full-screen apps read as a selection rather
    /// than a scroll — so those apps (Claude Code, vim, less, tmux) never scroll
    /// on a swipe. This mirrors Android's `TerminalView.doScroll`, which emits
    /// `MOUSE_WHEELUP/DOWN` when `isMouseTrackingActive()`. One wheel step is
    /// sent per cell-height of travel; a downward drag scrolls the content up
    /// (wheel up), matching natural touch scrolling.
    ///
    /// - Parameter gesture: The pan recognizer driving the scroll.
    @objc func handleScrollPan(_ gesture: UIPanGestureRecognizer) {
        // While mirroring, a swipe scrolls our own transcript: the mirror must not
        // inject input, so the wheel reports it would emit are dropped anyway.
        guard let view = terminalView, mouseReportingActive(view), !isPassive else { return }
        switch gesture.state {
        case .began:
            wheelScrollAccumulator = 0
        case .changed:
            let terminal = view.getTerminal()
            let rows = max(1, terminal.rows)
            let cellHeight = view.bounds.height / CGFloat(rows)
            guard cellHeight > 0 else { return }
            wheelScrollAccumulator += gesture.translation(in: view).y
            gesture.setTranslation(.zero, in: view)
            // Emit one discrete wheel step per cell of accumulated travel.
            while abs(wheelScrollAccumulator) >= cellHeight {
                let up = wheelScrollAccumulator > 0
                wheelScrollAccumulator -= up ? cellHeight : -cellHeight
                sendWheel(up: up, at: gesture.location(in: view), view: view)
            }
        default:
            break
        }
    }

    /// Sends a single mouse wheel event to the PTY, encoded for whichever mouse
    /// protocol the program negotiated (SGR, x10, urxvt, …).
    ///
    /// Uses SwiftTerm's own `encodeButton`/`sendEvent`, so the escape sequence
    /// matches the active protocol and is routed to the host through the same
    /// `send(source:data:)` delegate path as typed input. Called from
    /// `handleScrollPan`.
    ///
    /// - Parameters:
    ///   - up: `true` for a wheel-up (button 4), `false` for wheel-down (button 5).
    ///   - point: The touch location in the view, used to derive the cell the
    ///     event is reported at.
    ///   - view: The SwiftTerm view whose terminal encodes and sends the event.
    private func sendWheel(up: Bool, at point: CGPoint, view: SwiftTerm.TerminalView) {
        let terminal = view.getTerminal()
        let cols = max(1, terminal.cols)
        let rows = max(1, terminal.rows)
        let col = min(cols - 1, max(0, Int(point.x / (view.bounds.width / CGFloat(cols)))))
        let row = min(rows - 1, max(0, Int(point.y / (view.bounds.height / CGFloat(rows)))))
        let flags = terminal.encodeButton(button: up ? 4 : 5, release: false, shift: false, meta: false, control: false)
        terminal.sendEvent(buttonFlags: flags, x: col, y: row)
    }

    func sendBytes(_ bytes: [UInt8]) {
        Task { try? await ptySocket.send(bytes: KotlinByteArray.from(bytes)) }
    }

    /// Sends [text] (if any) and then a carriage return as two ordered frames
    /// within a single task, so Enter registers as its own keystroke. Used by
    /// the word-bar submit; see SwipeInputBar's onSubmit for why the CR must be
    /// a separate frame from the text.
    func sendLine(_ text: [UInt8]) {
        Task {
            if !text.isEmpty {
                try? await ptySocket.send(bytes: KotlinByteArray.from(text))
            }
            try? await ptySocket.send(bytes: KotlinByteArray.from(Array("\r".utf8)))
        }
    }

    func teardown() {
        stopFling()
        sizeVotes.cancel()
        flowObserver.clear()
        ptySocket.closeDetached()
    }

    private func applyTheme(_ theme: Client.ResolvedTheme) {
        // The flat theme has no dedicated terminal pane: foreground uses the
        // `text` token, background the `bg` token.
        terminalView?.nativeForegroundColor = UIColor(Color(argb: theme.text))
        terminalView?.nativeBackgroundColor = UIColor(Color(argb: theme.bg))
    }

    /// Re-resolve and install the terminal palette for the given system
    /// appearance.
    ///
    /// Unlike the SwiftUI views (whose dynamic `Palette` colours and CSS
    /// `prefers-color-scheme` rules re-resolve themselves), SwiftTerm's colours
    /// are pushed in imperatively, so nothing repaints them when the device
    /// flips light/dark. `TerminalScreen` calls this from `onChange(of:
    /// colorScheme)` so the active theme *slot* (we keep a separate one per
    /// appearance) switches immediately. Resolving `Palette.config` against the
    /// explicit `isDark` — rather than reading `UITraitCollection.current` —
    /// avoids any race with the trait collection lagging the SwiftUI update.
    func applyThemeForAppearance(isDark: Bool) {
        guard Palette.config != nil else { return }
        applyTheme(Palette.resolved(isDark: isDark))
    }

    /// Whether `bytes` contains a full terminal reset (RIS, `ESC c`).
    /// Mirrors the Android client's detection; see the output observer.
    private static func containsTerminalReset(_ bytes: [UInt8]) -> Bool {
        guard bytes.count >= 2 else { return false }
        for i in 0..<(bytes.count - 1) where bytes[i] == 0x1b && bytes[i + 1] == 0x63 {
            return true
        }
        return false
    }

    private static func findLeafTitle(config: Client.WindowConfig, sessionId: String) -> String? {
        for tab in config.tabs {
            for pane in tab.panes where pane.leaf.sessionId == sessionId {
                return pane.leaf.title
            }
        }
        return nil
    }

    // MARK: - TerminalViewDelegate

    /// Bytes the terminal wants to write to the PTY — typed input, but also
    /// machine-generated reports the local emulator produced by itself.
    ///
    /// Real input takes over first, so it lands at this phone's width. The two
    /// exceptions are what stopped a passive mirror from silently seizing the
    /// session:
    ///
    /// - **Device replies** (cursor position, device attributes, colour reports)
    ///   are answers to a question the *remote program* asked. They must still be
    ///   sent — the program is blocked waiting — but they are not user intent, and
    ///   treating them as such made the phone take the PTY whenever a program
    ///   probed the terminal. (The server drops them anyway now, having one
    ///   answerer of its own; sending them is harmless and keeps the client honest
    ///   against an older server.)
    /// - **Ambient reports** (mouse wheel, focus in/out) are emitted by the mirror
    ///   itself from scrolling and focus changes. They are neither input nor a
    ///   take-over, so while passive they are dropped outright — otherwise merely
    ///   scrolling the mirror would steal the grid.
    func send(source: SwiftTerm.TerminalView, data: ArraySlice<UInt8>) {
        let bytes = Array(data)
        let kotlinBytes = KotlinByteArray.from(bytes)
        let presentation = Client.PtyPresentation.shared
        if presentation.isDeviceReply(bytes: kotlinBytes) {
            Task { try? await ptySocket.send(bytes: kotlinBytes) }
            return
        }
        if isPassive, presentation.isAmbientReport(bytes: kotlinBytes) {
            return
        }
        ensureDriving()
        Task { try? await ptySocket.send(bytes: kotlinBytes) }
    }

    /// SwiftTerm re-measured itself.
    ///
    /// Deliberately does **not** vote, and deliberately does not update the natural
    /// grid. The terminal's frame is sized to the *server's* grid (see
    /// ``applyGeometry()``), so this fires with the server's own answer echoed back
    /// — voting on it would be this client telling the server what the server just
    /// told it. What this phone would like is measured separately, at the user's
    /// font, by ``remeasureAndAsk()``.
    ///
    /// It is kept as a safety net: if a layout pass ever beats the geometry pass
    /// and lands the emulator on the wrong grid, re-asserting restores it.
    func sizeChanged(source: SwiftTerm.TerminalView, newCols: Int, newRows: Int) {
        guard !applyingServerSize, let grid = serverGrid else { return }
        guard newCols != grid.cols || newRows != grid.rows else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self, let view = self.terminalView, let grid = self.serverGrid else { return }
            guard view.getTerminal().cols != grid.cols || view.getTerminal().rows != grid.rows else { return }
            self.applyingServerSize = true
            view.getTerminal().resize(cols: grid.cols, rows: grid.rows)
            self.applyGeometry()
            DispatchQueue.main.async { self.applyingServerSize = false }
        }
    }

    func setTerminalTitle(source: SwiftTerm.TerminalView, title: String) {
        // Server-managed titles take priority; ignore xterm title sequences
    }

    func scrolled(source: SwiftTerm.TerminalView, position: Double) {}
    func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    func requestOpenLink(source: SwiftTerm.TerminalView, link: String, params: [String: String]) {
        if let url = URL(string: link) { UIApplication.shared.open(url) }
    }
    func rangeChanged(source: SwiftTerm.TerminalView, startY: Int, endY: Int) {}
    func clipboardCopy(source: SwiftTerm.TerminalView, content: Data) {
        if let str = String(data: content, encoding: .utf8) {
            UIPasteboard.general.string = str
        }
    }
}

// MARK: - UIViewRepresentable wrapper

/// Hosts the terminal inside ``MirrorHostView``.
///
/// SwiftUI lays out the *host*, never the terminal: the terminal's frame is the
/// server's grid and may be wider or taller than the screen, which is exactly
/// what makes a passive mirror possible. See ``TerminalMirror`` for why sizing
/// the frame is what pins the emulator.
private struct TerminalViewRepresentable: UIViewRepresentable {
    let coordinator: TerminalCoordinator

    func makeUIView(context: Context) -> MirrorHostView {
        let tv = SwiftTerm.TerminalView(frame: .zero)
        tv.backgroundColor = UIColor(Palette.background)

        let host = MirrorHostView(terminalView: tv, gestureDelegate: coordinator)
        host.backgroundColor = UIColor(Palette.background)
        host.delegate = coordinator
        coordinator.mirrorHost = host
        coordinator.configureView(tv)

        // Wheel-scroll pan: forwards swipes as mouse wheel events while the
        // foreground program has mouse reporting on, so full-screen TUIs scroll
        // on a swipe (see TerminalCoordinator.handleScrollPan). Its delegate
        // gates it to mouse-reporting mode and blocks the competing pans, so it
        // stays out of the way of native scrollback scrolling otherwise.
        let scrollPan = UIPanGestureRecognizer(target: coordinator, action: #selector(TerminalCoordinator.handleScrollPan(_:)))
        scrollPan.delegate = coordinator
        tv.addGestureRecognizer(scrollPan)
        coordinator.scrollWheelPan = scrollPan

        return host
    }

    func updateUIView(_ uiView: MirrorHostView, context: Context) {
        // Geometry is driven by the host's own layout pass
        // (`mirrorHostDidLayout`) and by the server's `Size` frames, not from
        // here: a SwiftUI update that re-measured and re-voted would make this
        // client a second geometry authority, which is the thing the design
        // forbids. Re-solving is idempotent, so this is only a backstop for an
        // update that carries no layout change.
        coordinator.applyGeometry()
    }
}

// MARK: - Swipe Input Bar

/// Visible text field for gesture-typing (iOS QuickPath / Android swipe).
/// Sits between the terminal and the IME toolbar when active. The standard
/// TextField allows the keyboard to offer swipe-to-write suggestions,
/// unlike SwiftTerm's raw key capture.
private struct SwipeInputBar: View {
    @Binding var text: String
    let onSubmit: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(spacing: 6) {
            // ZStack so the placeholder can carry the dim-green theme colour —
            // SwiftUI ignores foreground styling on a TextField's built-in
            // prompt, so we overlay our own when the field is empty (matching
            // Android, which tints the placeholder with the theme text colour).
            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text("Type or swipe here\u{2026}")
                        .font(.system(size: 14, design: .monospaced))
                        .foregroundStyle(Palette.textSecondary)
                        .padding(.horizontal, 10)
                        .allowsHitTesting(false)
                }
                TextField("", text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(false)
                    .font(.system(size: 14, design: .monospaced))
                    .foregroundStyle(Palette.textPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .focused($isFocused)
                    .onSubmit { onSubmit() }
                    .accessibilityLabel("Type or swipe here")
            }
            .background(Palette.background, in: RoundedRectangle(cornerRadius: 6))

            Button(action: onSubmit) {
                Text("\u{23CE}")
                    .font(.system(size: 18))
                    .frame(width: 36, height: 36)
                    .background(Palette.headerAccent, in: RoundedRectangle(cornerRadius: 6))
                    .foregroundStyle(Palette.background)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Palette.surface)
        .onAppear { isFocused = true }
    }
}

// MARK: - Scroll-to-bottom pill

/// Floating pill shown over the terminal while the user has scrolled up.
/// Tapping it jumps to the bottom and resumes auto-follow. When fresh output
/// arrives while paused, it switches to an accent-highlighted "New output"
/// label to advertise that there's something new below.
private struct ScrollToBottomPill: View {
    let hasNewOutput: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                Text(hasNewOutput ? "New output" : "Jump to bottom")
                    .font(.system(size: 12, weight: .semibold))
                Image(systemName: "arrow.down")
                    .font(.system(size: 11, weight: .bold))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundStyle(hasNewOutput ? Palette.background : Palette.textPrimary)
            .background(
                hasNewOutput ? Palette.headerAccent : Palette.surface,
                in: Capsule()
            )
            .overlay(
                hasNewOutput ? nil : Capsule().stroke(Color(white: 0.35), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.35), radius: 4, y: 2)
        }
        .accessibilityLabel(hasNewOutput ? "New output below, jump to bottom" : "Jump to bottom")
    }
}

// MARK: - Take-over badge

/// Shown while another device drives the PTY and this screen is a passive mirror.
///
/// Tapping it is an explicit, input-free take-over: it fits the shared PTY to this
/// device's width. A tap on the *mirror itself* deliberately does nothing — a pan
/// that ends with barely any movement arrives as a tap, and each accidental one
/// would cost a real `SIGWINCH`, a full repaint of the running program, one frame
/// leaked into its scrollback (anthropics/claude-code#49086) and a reflow under
/// whoever is using the laptop. Take-over stays explicit: this badge, the Reformat
/// button, or actually typing.
///
/// Copy is neutral because the size broadcast does not carry *which* device is
/// driving. Filled with the accent rather than the surface tint so it reads as an
/// action over the mirrored content instead of blending into the terminal chrome —
/// the same treatment as the "New output" pill.
private struct TakeOverBadge: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text("Mirroring another device")
                    .font(.system(size: 12, weight: .semibold))
                Text("\u{00B7} Tap to take over")
                    .font(.system(size: 12, weight: .bold))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
            .foregroundStyle(Palette.background)
            .background(Palette.headerAccent, in: Capsule())
            .shadow(color: .black.opacity(0.35), radius: 4, y: 2)
        }
        .accessibilityLabel("Mirroring another device. Tap to take over.")
    }
}

// MARK: - IME Helper Toolbar

/// Above-keyboard toolbar for Esc/Ctrl/Shift/Tab/arrows, matching Android's
/// `ImeHelperToolbar` composable.
private struct ImeHelperToolbar: View {
    @Binding var ctrlSticky: Bool
    @Binding var shiftSticky: Bool
    let onSend: ([UInt8]) -> Void

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                StickyToolbarKey(label: "Ctrl", active: ctrlSticky) { ctrlSticky.toggle() }
                StickyToolbarKey(label: "Shift", active: shiftSticky) { shiftSticky.toggle() }
                ToolbarDivider()
                ToolbarKey(label: "Enter") { haptic.impactOccurred(); onSend([0x0d]) }
                ToolbarKey(label: "Esc") { haptic.impactOccurred(); onSend([0x1b]) }
                ToolbarKey(label: "Tab") { haptic.impactOccurred(); onSend([0x09]) }
                ToolbarKey(label: "\u{2191}", accessLabel: "Up arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[A".utf8)) }
                ToolbarKey(label: "\u{2193}", accessLabel: "Down arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[B".utf8)) }
                ToolbarKey(label: "\u{2192}", accessLabel: "Right arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[C".utf8)) }
                ToolbarKey(label: "\u{2190}", accessLabel: "Left arrow") { haptic.impactOccurred(); onSend(Array("\u{1b}[D".utf8)) }
                ToolbarKey(label: "Home") { haptic.impactOccurred(); onSend(Array("\u{1b}[H".utf8)) }
                ToolbarKey(label: "End") { haptic.impactOccurred(); onSend(Array("\u{1b}[F".utf8)) }
                ToolbarKey(label: "PgUp") { haptic.impactOccurred(); onSend(Array("\u{1b}[5~".utf8)) }
                ToolbarKey(label: "PgDn") { haptic.impactOccurred(); onSend(Array("\u{1b}[6~".utf8)) }
            }
            .padding(.horizontal, 4)
        }
        .frame(height: 44)
        .background(Palette.background)
    }
}

private struct ToolbarDivider: View {
    var body: some View {
        Rectangle()
            .fill(Palette.textSecondary.opacity(0.3))
            .frame(width: 1)
            .padding(.vertical, 10)
    }
}

private struct StickyToolbarKey: View {
    let label: String
    let active: Bool
    let action: () -> Void

    private let haptic = UIImpactFeedbackGenerator(style: .light)

    var body: some View {
        Button {
            haptic.impactOccurred()
            action()
        } label: {
            Text(label)
                .font(.footnote)
                .fontWeight(.semibold)
                // Match Android: green theme text, dark-on-accent when active.
                .foregroundStyle(active ? Palette.background : Palette.textPrimary)
                .padding(.horizontal, 14)
                .frame(maxHeight: .infinity)
                .background(
                    active ? Palette.headerAccent : Palette.background,
                    in: RoundedRectangle(cornerRadius: 6)
                )
                .overlay(
                    active ? nil : RoundedRectangle(cornerRadius: 6).stroke(Color(white: 0.35), lineWidth: 1)
                )
        }
        .padding(.vertical, 6)
        .accessibilityLabel(label)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

private struct ToolbarKey: View {
    let label: String
    var accessLabel: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.footnote)
                // Match Android: key labels and arrows use the green theme text.
                .foregroundStyle(Palette.textPrimary)
                .padding(.horizontal, 14)
                .frame(maxHeight: .infinity)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 6))
        }
        .padding(.vertical, 6)
        .accessibilityLabel(accessLabel ?? label)
    }
}

// MARK: - Helpers

private extension UIColor {
    convenience init(hexString: String) {
        var hex = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") { hex.removeFirst() }
        var rgb: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&rgb)
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}

// MARK: - Comparable clamping

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Kotlin/Swift bridge helpers

private extension KotlinByteArray {
    func toSwiftData() -> [UInt8] {
        var result = [UInt8](repeating: 0, count: Int(size))
        for i in 0..<Int(size) {
            result[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return result
    }

    static func from(_ bytes: [UInt8]) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (i, b) in bytes.enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: b))
        }
        return array
    }
}
