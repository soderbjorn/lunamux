import Foundation
import Client

/// App-wide owner of the shared KMP `LocalRepository`.
///
/// All on-device, non-server local state — the saved host list (with TLS pins),
/// the onboarding flag, and the news/update bookkeeping — lives in one
/// `LocalState` persisted to a single `local_state.json` file in the app's
/// Documents directory, owned by the shared `LocalRepository`. The device-auth
/// token is the one exception: the repository keeps it in the shared
/// `SecureStore`, whose iOS actual is the Keychain. A single process-wide
/// instance is required so the host list, the onboarding gate, and the news
/// checker all observe and mutate the same state.
///
/// Replaces the previous per-concern stores: `HostsStore` (Documents JSON),
/// `OnboardingStore` (`UserDefaults`), and `KeychainAuthTokenStore` (Keychain).
/// The token's Keychain storage is preserved by the shared `SecureStore` (it
/// reuses the same service/account), so existing devices keep their approval.
enum AppRepository {
    /// The shared repository, constructed and hydrated once on first access.
    static let shared: Client.LocalRepository = {
        let repo = Client.LocalRepositoryKt.createLocalRepository(
            localStore: Client.LocalStore(),
            secureStore: Client.SecureStore()
        )
        repo.start()
        return repo
    }()
}

/// Codable-free, SwiftUI-friendly mirror of the shared KMP `HostEntry`, mapped
/// from the repository's `LocalState.hosts`. Kept as the view-facing type so the
/// host list views (`HostsView`) bind to a native `Identifiable`/`Equatable`
/// value rather than the bridged Kotlin class.
///
/// The server speaks TLS only (see `SERVER_TLS_PORT`), so every connection is
/// `https`/`wss`. `pinnedFingerprintHex` is filled in by `HostsViewModel` after a
/// successful TOFU first-connect captures the leaf cert's SHA-256.
struct HostEntryLocal: Identifiable, Equatable {
    let id: String
    var label: String
    var host: String
    var port: Int32
    var pinnedFingerprintHex: String?

    init(
        id: String,
        label: String,
        host: String,
        port: Int32,
        pinnedFingerprintHex: String? = nil
    ) {
        self.id = id
        self.label = label
        self.host = host
        self.port = port
        self.pinnedFingerprintHex = pinnedFingerprintHex
    }

    /// Map a shared KMP `HostEntry` (from `LocalState.hosts`) into the native value.
    init(from entry: Client.HostEntry) {
        self.id = entry.id
        self.label = entry.label
        self.host = entry.host
        self.port = entry.port
        self.pinnedFingerprintHex = entry.pinnedFingerprintHex
    }

    /// Convert back to the shared KMP `HostEntry` for persistence through the
    /// repository's `addHost`/`updateHost`.
    func toShared() -> Client.HostEntry {
        Client.HostEntry(
            id: id,
            label: label,
            host: host,
            port: port,
            pinnedFingerprintHex: pinnedFingerprintHex
        )
    }
}
