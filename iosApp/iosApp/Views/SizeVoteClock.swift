/// The phone's ack-clocked PTY size-vote pipeline.
///
/// This file contains ``SizeVoteClock``, which paces this client's size requests
/// against the server's answers instead of against a timer. It is a direct port
/// of the Android `SizeVoteClock`, kept as a separate implementation rather than
/// shared because the Kotlin one lives in the Android app module; the *contract*
/// is what matters and it is identical on both.
///
/// What it replaces. iOS used to vote straight from SwiftTerm's `sizeChanged`
/// delegate behind a 200 ms trailing debounce. That delegate fires on every
/// layout pass, so a rotation or a keyboard animation cast a burst of votes, each
/// one a real `SIGWINCH` and each `SIGWINCH` answered by a live TUI repainting
/// another copy of its output into the scrollback. The debounce was a guess at
/// how long a settling layout takes: too short and the storm gets through, too
/// long and a finished rotation feels laggy.
///
/// What replaces it is paced by the protocol rather than by a number: **at most
/// one vote in flight**, and while one is in flight only the latest desired grid
/// is remembered. A fast server means fast votes; a slow one means fewer.
///
/// - SeeAlso: `TerminalCoordinator`, which clocks it from the `Size` frames it
///   collects, and `docs/server-side-screen.md` for the design record.
import Foundation

/// Serialises one session's size requests against the server's `Size` answers.
///
/// Thread confinement: every entry point takes `lock`, and the sends are
/// dispatched onto a detached `Task` rather than performed inline, so callers may
/// drive this from the main thread (the layout pass) and from the event collector
/// without ordering hazards. The lock is *recursive* because ``resolve()``
/// re-enters ``request(cols:rows:force:)`` — matching Kotlin's reentrant
/// `@Synchronized`, which the original relies on.
final class SizeVoteClock {

    /// Sends a soft size vote — the arbiter decides, nobody is evicted.
    private let sendVote: (Int, Int) async -> Void
    /// Sends a take-over: seizes governance at the given grid.
    private let sendForce: (Int, Int) async -> Void
    /// Safety-valve timeout. See ``timeoutSeconds`` in ``init``.
    private let timeoutSeconds: Double

    private let lock = NSRecursiveLock()
    /// True between sending a request and resolving it. The one-in-flight latch.
    private var inFlight = false
    /// The latest desired grid while ``inFlight``, or nil when nothing is outstanding.
    private var desired: (cols: Int, rows: Int)?
    /// The safety-valve timer for the in-flight request.
    private var timeoutTask: Task<Void, Never>?
    /// The server's last known grid — one of the two things that resolves a request.
    private var serverGrid: (cols: Int, rows: Int)?

    /// - Parameters:
    ///   - timeoutSeconds: safety valve. A vote that *loses* — to a mobile floor,
    ///     or to a governing client — produces no `Size` broadcast at all, so
    ///     without an upper bound the one-in-flight latch would stay shut forever.
    ///     It is not a pacing mechanism: the ordinary clock is ``onServerGrid``.
    ///     The default is long enough that a healthy round trip always answers
    ///     first, short enough that a lost vote does not wedge the pipeline
    ///     noticeably.
    ///   - sendVote: sends a soft size vote.
    ///   - sendForce: sends a take-over.
    init(
        timeoutSeconds: Double = 1.0,
        sendVote: @escaping (Int, Int) async -> Void,
        sendForce: @escaping (Int, Int) async -> Void
    ) {
        self.timeoutSeconds = timeoutSeconds
        self.sendVote = sendVote
        self.sendForce = sendForce
    }

    /// Ask for `cols`×`rows`.
    ///
    /// Sent immediately when nothing is outstanding. While a *vote* is in flight
    /// this only remembers the desire, replacing any earlier one: intermediate
    /// sizes from a settling layout are of no interest once superseded. A
    /// take-over is not deferred at all.
    ///
    /// A request for the grid the server already has is not sent — there would be
    /// nothing to wait for — and resolves the pipeline instead.
    ///
    /// - Parameters:
    ///   - cols: desired columns.
    ///   - rows: desired rows.
    ///   - force: true for a take-over (seizes governance), false for a soft vote.
    ///     Called with true by real input, the take-over badge and Reformat.
    func request(cols: Int, rows: Int, force: Bool) {
        lock.lock()
        defer { lock.unlock() }

        guard cols >= 2, rows >= 2 else { return }
        if !force, let sg = serverGrid, sg == (cols, rows) {
            desired = nil
            unlatch()
            return
        }
        // A force PREEMPTS the one-in-flight rule and goes out at once. That rule
        // exists to keep ambient measurement from becoming a vote storm; making a
        // user gesture wait up to the safety-valve timeout behind an ambient vote
        // it is about to overrule would be a second of dead UI for no benefit.
        if inFlight && !force {
            desired = (cols, rows)
            return
        }
        desired = nil
        inFlight = true
        let send = force ? sendForce : sendVote
        Task { await send(cols, rows) }
        timeoutTask?.cancel()
        timeoutTask = Task { [timeoutSeconds] in
            try? await Task.sleep(nanoseconds: UInt64(timeoutSeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self.resolveLocked()
        }
    }

    /// Record the server's grid and resolve the in-flight request — the ordinary
    /// clock tick.
    ///
    /// Called for every `Size` frame the screen collects, including ones this
    /// client did not ask for: any answer from the server is proof the previous
    /// request has been dealt with, one way or another.
    ///
    /// - Parameters:
    ///   - cols: the server's column count.
    ///   - rows: the server's row count.
    func onServerGrid(cols: Int, rows: Int) {
        lock.lock()
        defer { lock.unlock() }
        serverGrid = (cols, rows)
        resolve()
    }

    /// The server's grid as last seen, for callers deciding whether a take-over
    /// would change anything.
    var lastServerGrid: (cols: Int, rows: Int)? {
        lock.lock()
        defer { lock.unlock() }
        return serverGrid
    }

    /// Drop any outstanding request without sending it — used when the screen
    /// goes away.
    func cancel() {
        lock.lock()
        defer { lock.unlock() }
        desired = nil
        unlatch()
    }

    /// ``resolve()`` behind the lock, for the timeout task to call from its own
    /// execution context.
    private func resolveLocked() {
        lock.lock()
        defer { lock.unlock() }
        resolve()
    }

    /// Unlatch and send the remembered desire if it still differs from the
    /// server's grid.
    ///
    /// Only ever called with the lock held; it re-enters ``request(cols:rows:force:)``,
    /// which takes the same recursive lock.
    private func resolve() {
        unlatch()
        guard let next = desired else { return }
        desired = nil
        // Always a vote: a take-over never queues here (see `request`). `request`
        // itself drops it when it turns out to match the grid the server now has.
        request(cols: next.cols, rows: next.rows, force: false)
    }

    private func unlatch() {
        inFlight = false
        timeoutTask?.cancel()
        timeoutTask = nil
    }
}
