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
                    StateIndicator(state: paneState, size: 14)
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
        .toolbarBackground(.visible, for: .navigationBar)
        .onAppear {
            setupTerminal()
            UIApplication.shared.isIdleTimerDisabled = true
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

        let ptySocket = client.openPtySocket(sessionId: sessionId)
        let coord = TerminalCoordinator(ptySocket: ptySocket, client: client, sessionId: sessionId)
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
    }
}

// MARK: - Terminal Coordinator

/// Manages the SwiftTerm TerminalView lifecycle, PtySocket I/O, and
/// server-pushed resize handling. This is the iOS equivalent of the
/// Android TerminalScreen's emulator + PtySocket wiring.
final class TerminalCoordinator: NSObject, TerminalViewDelegate, UIScrollViewDelegate {
    let ptySocket: Client.PtySocket
    let client: Client.TermtasticClient
    let sessionId: String

    var terminalView: SwiftTerm.TerminalView?
    var onTitleChanged: ((String) -> Void)?
    var onStateChanged: ((String?) -> Void)?
    /// Fires with `true` when the viewport is at the bottom (auto-follow) and
    /// `false` when the user has scrolled up. Drives the "jump to bottom" pill.
    var onScrollChanged: ((Bool) -> Void)?
    /// Fires when PTY output arrives while the user is scrolled up.
    var onNewOutputWhilePaused: (() -> Void)?

    private let flowObserver = Client.FlowObserver()
    private var pendingOutput: [[UInt8]] = []
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
    private var naturalCols: Int = 80
    private var naturalRows: Int = 24
    private var hasSentInitialSize = false
    private(set) var currentFontSize: CGFloat = 12
    private static let minFontSize: CGFloat = 6
    private static let maxFontSize: CGFloat = 32

    /// Returns the bundled JetBrains Mono face (matches the Android app) at
    /// the requested size, falling back to the system monospace font if the
    /// custom font failed to register. JetBrains Mono carries proper
    /// monospace glyphs for the geometric, technical, and box-drawing blocks
    /// that SF Mono leaves to a proportional fallback (●, ○, ⏸, └, …).
    private static func terminalFont(size: CGFloat) -> UIFont {
        UIFont(name: "JetBrainsMono-Regular", size: size)
            ?? UIFont.monospacedSystemFont(ofSize: size, weight: .regular)
    }

    init(ptySocket: Client.PtySocket, client: Client.TermtasticClient, sessionId: String) {
        self.ptySocket = ptySocket
        self.client = client
        self.sessionId = sessionId
        super.init()
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

        // TEMP diagnostic — remove once font load is confirmed working.
        print("[Termtastic] JetBrainsMono-Regular loaded: \(UIFont(name: "JetBrainsMono-Regular", size: 12) != nil)")

        // Flush any PTY output that arrived before the view was created
        for chunk in pendingOutput {
            view.feed(byteArray: ArraySlice(chunk))
        }
        pendingOutput.removeAll()

        // Set a reasonable default font size (Android uses 32px ≈ ~12pt at 2x/3x)
        currentFontSize = 12
        view.font = Self.terminalFont(size: currentFontSize)

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
        // PTY output → terminal
        flowObserver.observe(flow: ptySocket.output) { [weak self] chunk in
            guard let self, let data = chunk as? KotlinByteArray else { return }
            let bytes = data.toSwiftData()
            DispatchQueue.main.async {
                guard let tv = self.terminalView else {
                    self.pendingOutput.append(bytes)
                    return
                }
                let wasPaused = self.isScrolledUp
                let isReset = Self.containsTerminalReset(bytes)

                tv.feed(byteArray: ArraySlice(bytes))

                // A full terminal reset (the PtySocket reconnect replay's
                // prefix, or a real `reset` on the server) can revert the
                // emulator's palette to stock — re-apply the theme so
                // default-coloured text stays readable.
                if isReset, let settings = Palette.settings {
                    self.applyTheme(settings)
                }
                // SwiftTerm's `updateScroller` just yanked the viewport to the
                // bottom. If the user was reading history, re-pin them at the
                // same distance from the bottom — this both holds the pause
                // during streaming and (because distance-from-bottom is
                // preserved) restores their place across the resume reset's
                // wipe + replay as the content regrows.
                if wasPaused {
                    self.repinScroll(tv)
                    self.onNewOutputWhilePaused?()
                }
            }
        }

        // Server-pushed PTY size
        flowObserver.observe(flow: ptySocket.ptySize) { [weak self] value in
            guard let self else { return }
            DispatchQueue.main.async {
                self.applyServerSize(value)
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

    private func applyServerSize(_ value: Any?) {
        guard let pair = value as? KotlinPair<KotlinInt, KotlinInt> else { return }
        let cols = Int(truncating: pair.first!)
        let rows = Int(truncating: pair.second!)
        guard cols > 0, rows > 0 else { return }

        applyingServerSize = true
        terminalView?.getTerminal().resize(cols: cols, rows: rows)
        terminalView?.setNeedsDisplay()
        DispatchQueue.main.async { self.applyingServerSize = false }
    }

    /// Called from updateUIView to assert the terminal's actual size once the
    /// view has been laid out. This closes the race where the PTY socket is
    /// opened before SwiftUI has measured the TerminalView.
    func assertSizeIfNeeded(_ view: SwiftTerm.TerminalView) {
        guard !hasSentInitialSize else { return }
        let cols = view.getTerminal().cols
        let rows = view.getTerminal().rows
        guard cols > 0, rows > 0 else { return }
        hasSentInitialSize = true
        naturalCols = cols
        naturalRows = rows
        Task { try? await ptySocket.forceResize(cols: Int32(cols), rows: Int32(rows)) }
    }

    func forceResize() {
        guard naturalCols > 0, naturalRows > 0 else { return }
        Task { try? await ptySocket.forceResize(cols: Int32(naturalCols), rows: Int32(naturalRows)) }
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

    @objc func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard let view = terminalView else { return }
        if gesture.state == .changed {
            let scale = gesture.scale
            // Apply a deadzone to avoid jitter (matching Android's 5% threshold)
            if scale < 0.95 || scale > 1.05 {
                let newSize = (currentFontSize * scale).clamped(to: Self.minFontSize...Self.maxFontSize)
                let rounded = round(newSize * 2) / 2  // snap to 0.5pt increments
                if rounded != currentFontSize {
                    currentFontSize = rounded
                    view.font = Self.terminalFont(size: currentFontSize)
                }
                gesture.scale = 1.0
            }
        }
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
        flowObserver.clear()
        ptySocket.closeDetached()
    }

    private func applyTheme(
        _ settings: Client.UiSettings,
        isDark: Bool = UITraitCollection.current.userInterfaceStyle == .dark
    ) {
        let palette = settings.schemeForPane(pane: "terminal").resolve(
            appearance: settings.appearance,
            systemIsDark: isDark
        )
        terminalView?.nativeForegroundColor = UIColor(Color(argb: palette.terminal.fg))
        terminalView?.nativeBackgroundColor = UIColor(Color(argb: palette.terminal.bg))
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
        applyTheme(Palette.resolved(isDark: isDark), isDark: isDark)
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

    func send(source: SwiftTerm.TerminalView, data: ArraySlice<UInt8>) {
        let bytes = Array(data)
        Task { try? await ptySocket.send(bytes: KotlinByteArray.from(bytes)) }
    }

    func sizeChanged(source: SwiftTerm.TerminalView, newCols: Int, newRows: Int) {
        if !applyingServerSize {
            naturalCols = newCols
            naturalRows = newRows
            Task { try? await ptySocket.resize(cols: Int32(newCols), rows: Int32(newRows)) }
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

private struct TerminalViewRepresentable: UIViewRepresentable {
    let coordinator: TerminalCoordinator

    func makeUIView(context: Context) -> SwiftTerm.TerminalView {
        let tv = SwiftTerm.TerminalView(frame: .zero)
        tv.backgroundColor = UIColor(Palette.background)
        coordinator.configureView(tv)

        // Pinch-to-zoom for font size (matches Android behaviour)
        let pinch = UIPinchGestureRecognizer(target: coordinator, action: #selector(TerminalCoordinator.handlePinch(_:)))
        tv.addGestureRecognizer(pinch)

        return tv
    }

    func updateUIView(_ uiView: SwiftTerm.TerminalView, context: Context) {
        // Once the view has a real layout, assert the terminal size to the
        // server. SwiftUI may not trigger sizeChanged until well after the
        // socket is open, so we do it explicitly on each layout pass until
        // a valid size has been sent.
        coordinator.assertSizeIfNeeded(uiView)
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
