import Foundation
import Observation
import Client

/// Manages the hosts list and connection state. Observes the shared
/// `LocalRepository` (the single `local_state.json` store) for persistence and
/// wraps `ConnectionHolder` for WebSocket lifecycle.
///
/// The host list is mirrored from `LocalState.hosts` via a `FlowObserver`, and
/// every mutation (add/edit/delete, TOFU pin capture, re-pair) is written back
/// through the repository's suspend API. Mirrors the Android `HostsScreen`,
/// which observes the same shared repository.
@Observable
final class HostsViewModel {
    /// The saved hosts, mirrored from the repository's `LocalState.hosts`.
    var hosts: [HostEntryLocal] = []
    var connectingId: String?
    var errorMessage: String?
    /// Set when the latest connect attempt failed because the server's leaf
    /// cert no longer matches the stored pin. The view binds a dedicated
    /// alert to this so the user gets Re-pair / Forget / Cancel instead of
    /// the generic "Connection failed" message. Mirrors the
    /// `PinMismatchDialog` shown on Android.
    var pinMismatchEntry: HostEntryLocal?
    var waitingForApproval: Bool { ConnectionHolder.shared.pendingApproval }

    /// Sentinel id used in `connectingId` for the built-in demo row, which
    /// is not a persisted host entry. Drives the row's progress spinner and
    /// disables the rest of the list during the (instant) demo connect.
    static let demoConnectingId = "builtin-demo"

    private let repository = AppRepository.shared
    private let flowObserver = Client.FlowObserver()

    init() {
        // Mirror the persisted host list into `hosts`. The value is `LocalState?`
        // (nil until hydration completes); an empty list is published until then.
        flowObserver.observe(flow: repository.state) { [weak self] value in
            let state = value as? Client.LocalState
            let mapped = (state?.hosts ?? []).map { HostEntryLocal(from: $0) }
            DispatchQueue.main.async { self?.hosts = mapped }
        }
    }

    deinit {
        flowObserver.clear()
    }

    /// Connect to the built-in demo "server": the magic demo host makes the
    /// shared client run against its in-process simulation, so this never
    /// touches the network and completes instantly. No auth, no TLS pin, no
    /// saved host entry.
    func connectDemo() {
        connectingId = Self.demoConnectingId
        errorMessage = nil
        Task {
            do {
                let serverUrl = Client.ServerUrl(host: DemoModeKt.DEMO_HOST, port: 0)
                try await ConnectionHolder.shared.connect(
                    serverUrl: serverUrl,
                    authToken: "demo",
                    pinnedFingerprintHex: nil
                )
                // Demo settings resolve to the stock defaults; setting them
                // anyway keeps every colour accessor on the same path as a
                // real connection.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run { connectingId = nil }
            } catch {
                await MainActor.run {
                    connectingId = nil
                    errorMessage = error.localizedDescription
                }
            }
        }
    }

    func connect(entry: HostEntryLocal) {
        connectingId = entry.id
        errorMessage = nil
        Task {
            do {
                let token = try await repository.getOrCreateAuthToken()
                let serverUrl = Client.ServerUrl(
                    host: entry.host,
                    port: entry.port
                )
                try await ConnectionHolder.shared.connect(
                    serverUrl: serverUrl,
                    authToken: token,
                    pinnedFingerprintHex: entry.pinnedFingerprintHex
                )
                // TOFU: capture and persist the observed fingerprint on the
                // first successful connect so subsequent connects run in
                // strict-verify mode.
                if entry.pinnedFingerprintHex == nil,
                   let client = ConnectionHolder.shared.client,
                   let captured = client.observedFingerprint.value as? String {
                    var updated = entry
                    updated.pinnedFingerprintHex = captured
                    try? await repository.updateHost(entry: updated.toShared())
                }
                // Fetch the user's theme settings so all views use the
                // selected theme from the start. Palette.settings is a
                // static var read by all colour accessors.
                if let client = ConnectionHolder.shared.client {
                    Palette.config = try? await client.fetchThemeConfig()
                }
                await MainActor.run {
                    connectingId = nil
                }
            } catch {
                await MainActor.run {
                    connectingId = nil
                    if ConnectionHolder.shared.lastPinMismatch {
                        pinMismatchEntry = entry
                    } else {
                        errorMessage = error.localizedDescription
                    }
                }
            }
        }
    }

    func addHost(label: String, host: String, port: Int32) {
        Task { try? await repository.addHost(label: label, host: host, port: port) }
    }

    func updateHost(_ entry: HostEntryLocal) {
        Task { try? await repository.updateHost(entry: entry.toShared()) }
    }

    func deleteHost(id: String) {
        Task { try? await repository.deleteHost(id: id) }
    }

    /// Clear the stored pin so the next connect attempt re-runs the TOFU
    /// capture and re-fires the server's `DeviceAuth.ApprovalDialog`.
    /// Triggered from the cert-changed alert's "Re-pair" button when the
    /// user has decided the new certificate is legitimate (server was
    /// reinstalled, key rolled, etc.).
    func repairPin(_ entry: HostEntryLocal) {
        var updated = entry
        updated.pinnedFingerprintHex = nil
        Task { try? await repository.updateHost(entry: updated.toShared()) }
        pinMismatchEntry = nil
    }

    /// Delete the host entry from the cert-changed alert's "Forget" button.
    /// Distinct from `deleteHost(id:)` only in that it also clears the
    /// alert state.
    func forgetHost(_ entry: HostEntryLocal) {
        Task { try? await repository.deleteHost(id: entry.id) }
        pinMismatchEntry = nil
    }
}
