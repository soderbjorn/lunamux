import Foundation
import Client

/// Process-scoped singleton holding the live `TermtasticClient` and
/// `WindowSocket`. Mirrors the Android `ConnectionHolder` singleton.
@Observable
final class ConnectionHolder {
    static let shared = ConnectionHolder()

    private(set) var client: Client.TermtasticClient?
    private(set) var windowSocket: Client.WindowSocket?

    private init() {
        client = nil
        windowSocket = nil
    }

    /// Whether the server is showing an approval dialog for this connection.
    private(set) var pendingApproval: Bool = false

    /// Set to `true` by `connect(...)` when the most recent attempt failed
    /// specifically because the server's leaf cert no longer matches the
    /// stored pin (verify-mode mismatch in the shared
    /// `PinnedHttpClientFactory`). Callers branch on this in their `catch`
    /// to show a cert-changed dialog instead of a generic error — Darwin's
    /// `URLSessionDelegate` cancels the challenge with no marker NSError,
    /// so the bridged Swift error is otherwise indistinguishable from a
    /// wrong-port / unreachable-host failure.
    private(set) var lastPinMismatch: Bool = false

    private var approvalObserver: Client.FlowObserver?

    /// Tear down any existing client and create a fresh one for the given URL.
    ///
    /// `pinnedFingerprintHex` selects between the TOFU capture mode (nil,
    /// first-connect to a new host — the leaf cert's SHA-256 is observed
    /// during the handshake and exposed via `client.observedFingerprint`)
    /// and verify mode (non-nil — `URLSessionDelegate` constant-time compares
    /// the leaf against the pin and cancels the challenge on mismatch).
    /// A mismatch additionally sets `lastPinMismatch = true` so the caller's
    /// `catch` can show the cert-changed UX instead of a generic error.
    @MainActor
    func connect(
        serverUrl: Client.ServerUrl,
        authToken: String,
        pinnedFingerprintHex: String? = nil
    ) async throws {
        disconnect()
        pendingApproval = false
        lastPinMismatch = false
        let identity = Client.ClientIdentity(
            type: "Computer",
            hostname: ProcessInfo.processInfo.hostName,
            selfReportedIp: Self.firstNonLoopbackIPv4()
        )
        let fresh = Client.TermtasticClientKt.createTermtasticClient(
            serverUrl: serverUrl,
            authToken: authToken,
            identity: identity,
            pinnedFingerprintHex: pinnedFingerprintHex
        )
        let socket = fresh.openWindowSocket()
        // Watch for PendingApproval so we can update the UI while
        // waiting for the server-side dialog to be answered.
        let observer = Client.FlowObserver()
        approvalObserver = observer
        observer.observe(flow: fresh.windowState.pendingApproval) { [weak self] value in
            DispatchQueue.main.async {
                self?.pendingApproval = (value as? Bool) == true
            }
        }
        do {
            // Phase 1: WebSocket handshake — 15 s is plenty.
            try await withTimeout(seconds: 15) {
                try await socket.awaitSessionReady()
            }
            // Phase 2: wait for the first Config envelope.  When approval
            // is pending this can take minutes, so use a generous timeout.
            try await withTimeout(seconds: 300) {
                try await socket.awaitInitialConfig()
            }
            pendingApproval = false
        } catch {
            pendingApproval = false
            observer.clear()
            approvalObserver = nil
            // Sample the side-channel set by the shared
            // PinnedHttpClientFactory.ios before we tear the client down —
            // the StateFlow value stays readable after close(), but reading
            // it here keeps the lifetime obvious.
            lastPinMismatch = (fresh.observedMismatch.value as? String) != nil
            let nsError = error as NSError
            NSLog(
                "[ConnectionHolder] connect failed: domain=%@ code=%ld desc=%@ userInfo=%@",
                nsError.domain, nsError.code,
                nsError.localizedDescription, String(describing: nsError.userInfo)
            )
            try? await socket.close()
            fresh.close()
            throw error
        }
        observer.clear()
        approvalObserver = nil
        self.client = fresh
        self.windowSocket = socket
    }

    @MainActor
    func disconnect() {
        let ws = windowSocket
        windowSocket = nil
        client?.close()
        client = nil
        Task { try? await ws?.close() }
    }

    /// Best-effort first non-loopback IPv4 address.
    private static func firstNonLoopbackIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(first) }

        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = cursor {
            let sa = ifa.pointee.ifa_addr
            if sa?.pointee.sa_family == UInt8(AF_INET) {
                let name = String(cString: ifa.pointee.ifa_name)
                if name != "lo0" {
                    var addr = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(sa, socklen_t(sa!.pointee.sa_len),
                                   &addr, socklen_t(addr.count),
                                   nil, 0, NI_NUMERICHOST) == 0 {
                        return String(cString: addr)
                    }
                }
            }
            cursor = ifa.pointee.ifa_next
        }
        return nil
    }
}

/// Swift-friendly timeout wrapper for async operations.
private func withTimeout<T>(seconds: TimeInterval, operation: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw NSError(domain: "ConnectionHolder", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Connection timed out"])
        }
        let result = try await group.next()!
        group.cancelAll()
        return result
    }
}
