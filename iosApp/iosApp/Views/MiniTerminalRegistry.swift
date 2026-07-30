import Foundation
import SwiftTerm
import Client

/// Max recent logical lines kept per terminal miniature. Matches the Android
/// `MINI_REGISTRY_MAX_LINES`.
private let miniRegistryMaxLines = 80

/// Observable holder for one session's most-recent terminal lines
/// (oldest-to-newest). `MiniTerminalPane` reads `lines` in its body, so a feed
/// that updates this re-renders just that thumbnail.
@Observable
final class MiniTerminalLineBox {
    /// The session's trailing logical lines, oldest-to-newest.
    var lines: [String] = []
}

/// Overview-scoped registry of live, read-only terminal miniatures — the iOS
/// counterpart of the Android `MiniTerminalRegistry`.
///
/// The overview shows a terminal thumbnail per visible pane. Opening (and
/// closing) a PTY socket from each thumbnail's own view lifecycle made
/// rendering churn: switching tabs tore down every socket and rebuilt them on
/// return, and the rapid close→reopen to the same session raced the server's
/// attach/detach handling, so thumbnails came up blank.
///
/// This registry decouples the PTY socket + headless emulator lifecycle from
/// the pane views. It opens at most one socket per session id, keeps it alive
/// for as long as the overview is on screen (across tab swipes), and publishes
/// each session's most recent lines via a `MiniTerminalLineBox`. A thumbnail
/// just binds to that box, so swiping away and back re-attaches to an
/// already-populated emulator and renders instantly. Panes that share a session
/// (linked views) share a single socket.
///
/// The registry is created by `OverviewView`, threaded down to each
/// `MiniTerminalPane`, and `close`d when the overview leaves the screen.
///
/// Read-only invariant: like the Android version, entries never call
/// `PtySocket.resize`/`send`, so a thumbnail can never shrink the real PTY for
/// other clients. The headless `SwiftTerm.Terminal` is fed bytes and resized to
/// the server-pushed size only; its `send` delegate callback is a no-op.
///
/// - SeeAlso: `MiniTerminalPane`
/// - SeeAlso: `OverviewView`
final class MiniTerminalRegistry {
    /// One live miniature: the PTY socket, its headless emulator, the flow
    /// observer feeding it, and the published lines.
    private final class Entry {
        let socket: Client.PtySocket
        let terminal: SwiftTerm.Terminal
        let delegate: HeadlessTerminalDelegate
        let observer: Client.FlowObserver
        let box: MiniTerminalLineBox

        init(
            socket: Client.PtySocket,
            terminal: SwiftTerm.Terminal,
            delegate: HeadlessTerminalDelegate,
            observer: Client.FlowObserver,
            box: MiniTerminalLineBox
        ) {
            self.socket = socket
            self.terminal = terminal
            self.delegate = delegate
            self.observer = observer
            self.box = box
        }
    }

    private let client: Client.LunamuxClient
    private var entries: [String: Entry] = [:]
    private var closed = false

    /// - Parameter client: the connected client used to open PTY sockets.
    init(client: Client.LunamuxClient) {
        self.client = client
    }

    /// Return the line box for `sessionId`, creating and starting the
    /// underlying socket + emulator on first request. Subsequent calls (and
    /// other panes sharing the session) get the same box.
    ///
    /// Called from a SwiftUI view body, so it mutates only the registry's own
    /// dictionary (never SwiftUI-observed state); the first feed that fills the
    /// box happens on a later main-loop tick.
    ///
    /// - Parameter sessionId: the PTY session to mirror.
    /// - Returns: the session's live line box.
    func box(for sessionId: String) -> MiniTerminalLineBox {
        if let existing = entries[sessionId] { return existing.box }
        let entry = makeEntry(sessionId: sessionId)
        entries[sessionId] = entry
        return entry.box
    }

    /// Build and start a live entry: open the socket, wire a headless emulator,
    /// and collect server-pushed size + output into the line box. All terminal
    /// mutation and reads run on the main thread, where `FlowObserver` delivers,
    /// so the emulator needs no extra locking.
    private func makeEntry(sessionId: String) -> Entry {
        let socket = client.openPtySocket(sessionId: sessionId)
        let delegate = HeadlessTerminalDelegate()
        let terminal = SwiftTerm.Terminal(delegate: delegate)
        let box = MiniTerminalLineBox()
        let observer = Client.FlowObserver()

        // One ordered event stream: size, output and reconnect resets applied
        // to the headless emulator in the order the server produced them (so a
        // resize can't race the bytes it wraps and mangle the thumbnail).
        observer.observe(flow: socket.events) { [weak box, weak terminal] value in
            guard let terminal else { return }
            if let bytesEv = value as? Client.PtyEventBytes {
                terminal.feed(byteArray: bytesEv.data.toMiniBytes())
            } else if let sizeEv = value as? Client.PtyEventSize {
                let cols = Int(sizeEv.cols)
                let rows = Int(sizeEv.rows)
                guard cols > 0, rows > 0 else { return }
                terminal.resize(cols: cols, rows: rows)
            } else if value is Client.PtyEventReset {
                terminal.feed(byteArray: [0x1b, UInt8(ascii: "c")])
            } else {
                return
            }
            box?.lines = extractRecentLines(terminal, maxLines: miniRegistryMaxLines)
        }

        return Entry(
            socket: socket,
            terminal: terminal,
            delegate: delegate,
            observer: observer,
            box: box
        )
    }

    /// Tear down every live entry: cancel its collectors and close its socket
    /// (detached so the close reaches the server even as the overview unwinds).
    /// Idempotent. Called by `OverviewView` on disappear.
    func close() {
        guard !closed else { return }
        closed = true
        for entry in entries.values {
            entry.observer.clear()
            entry.socket.closeDetached()
        }
        entries.removeAll()
    }
}

/// Minimal `TerminalDelegate` for a headless miniature emulator. Every protocol
/// method has a default implementation except `send`, which the miniature
/// no-ops: it is read-only and must never write back to the PTY (that would let
/// a thumbnail steer the real session).
private final class HeadlessTerminalDelegate: TerminalDelegate {
    func send(source: SwiftTerm.Terminal, data: ArraySlice<UInt8>) {
        // Read-only miniature: swallow any reply the emulator would send.
    }
}

/// Extract the most recent up-to-`maxLines` logical lines from `terminal`'s
/// active buffer (scrollback + current screen). `getBufferAsData` joins each
/// buffer line right-trimmed and newline-separated — the iOS analogue of the
/// Android emulator's `transcriptText` — so trailing blank lines are dropped
/// and the tail is returned oldest-to-newest. Must be called on the same thread
/// that feeds the emulator (the main thread, here).
///
/// CONSOLIDATION: the split + trim-to-`maxLines` below mirrors Android
/// `MiniTerminalRegistry.extractRecentLines` and web
/// `LinkThumbnailRenderer.readLogicalLines`. Only the buffer read
/// (`getBufferAsData`) and the NUL-to-space fix are SwiftTerm-specific; the pure
/// transform could move to `client/commonMain` (e.g.
/// `TerminalThumbnailModel.trimRecentLines`) and be reached from Swift through the
/// generated `Client` framework — exactly as `SessionsViewModeStore` already is.
/// See the full design note in `web/.../LinkThumbnailRenderer.kt`. Note iOS does
/// NOT re-wrap to the thumbnail width: it resizes the emulator to the real PTY
/// cols and lets SwiftUI `Text` wrap (see `MiniTerminalPane` in `OverviewView`),
/// unlike web's explicit `wrapLine`. Reconcile that before sharing the wrap step.
///
/// - Parameters:
///   - terminal: the headless emulator to read.
///   - maxLines: the most lines to return.
/// - Returns: the trailing logical lines, oldest-to-newest.
func extractRecentLines(_ terminal: SwiftTerm.Terminal, maxLines: Int) -> [String] {
    let data = terminal.getBufferAsData()
    guard let raw = String(data: data, encoding: .utf8), !raw.isEmpty else { return [] }
    // `getBufferAsData` serialises blank/cursor-positioned cells (cell code 0) as
    // NUL (`\u{0}`), not spaces — SwiftUI renders NUL as zero-width, so gaps
    // between words collapse and lines read like "Youopenedthepage". Map NUL back
    // to a space, mirroring SwiftTerm's own `getText`, before splitting into lines.
    let text = raw.replacingOccurrences(of: "\u{0}", with: " ")
    var all = text.components(separatedBy: "\n")
    while let last = all.last, last.trimmingCharacters(in: .whitespaces).isEmpty {
        all.removeLast()
    }
    if all.isEmpty { return [] }
    if all.count > maxLines { all = Array(all.suffix(maxLines)) }
    return all
}

// MARK: - Kotlin/Swift byte bridge

private extension KotlinByteArray {
    /// Copy this Kotlin `ByteArray` into a Swift `[UInt8]` for `Terminal.feed`.
    func toMiniBytes() -> [UInt8] {
        var result = [UInt8](repeating: 0, count: Int(size))
        for i in 0..<Int(size) {
            result[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return result
    }
}
