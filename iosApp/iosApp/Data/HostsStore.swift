import Foundation
import Observation

/// Codable mirror of the shared KMP `HostEntry` for JSON file persistence.
///
/// The server speaks TLS only (see `SERVER_TLS_PORT` in the shared
/// `Constants.kt`), so every connection is `https`/`wss`.
/// `pinnedFingerprintHex` is filled in by `HostsViewModel` after a successful
/// TOFU first-connect captures the leaf cert's SHA-256; until then the
/// client runs in capture mode and the server's `DeviceAuth.ApprovalDialog`
/// provides the trust ceremony.
///
/// `init(from:)` tolerantly decodes legacy JSON shapes (with or without
/// `pinnedFingerprintHex`, and silently ignoring any stale `useTls` field
/// from earlier builds) — no migration shim needed.
struct HostEntryLocal: Codable, Identifiable, Equatable {
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
        pinnedFingerprintHex: String? = nil,
    ) {
        self.id = id
        self.label = label
        self.host = host
        self.port = port
        self.pinnedFingerprintHex = pinnedFingerprintHex
    }

    private enum CodingKeys: String, CodingKey {
        case id, label, host, port, pinnedFingerprintHex
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.label = try c.decode(String.self, forKey: .label)
        self.host = try c.decode(String.self, forKey: .host)
        self.port = try c.decode(Int32.self, forKey: .port)
        self.pinnedFingerprintHex = try c.decodeIfPresent(String.self, forKey: .pinnedFingerprintHex)
    }
}

/// File-based JSON persistence for the saved hosts list, following a
/// `StorageManager` pattern. Reads/writes to the app's documents
/// directory so data survives app updates.
@Observable
final class HostsStore {
    static let shared = HostsStore()

    var hosts: [HostEntryLocal] = []

    private let fileURL: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        fileURL = docs.appendingPathComponent("hosts.json")
        load()
    }

    func add(label: String, host: String, port: Int32) {
        let entry = HostEntryLocal(
            id: UUID().uuidString,
            label: label,
            host: host,
            port: port,
            pinnedFingerprintHex: nil
        )
        hosts.append(entry)
        save()
    }

    func update(_ entry: HostEntryLocal) {
        if let idx = hosts.firstIndex(where: { $0.id == entry.id }) {
            hosts[idx] = entry
            save()
        }
    }

    func delete(id: String) {
        hosts.removeAll { $0.id == id }
        save()
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            let data = try Data(contentsOf: fileURL)
            hosts = try decoder.decode([HostEntryLocal].self, from: data)
        } catch {
            print("HostsStore: failed to load: \(error)")
        }
    }

    private func save() {
        do {
            let data = try encoder.encode(hosts)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("HostsStore: failed to save: \(error)")
        }
    }
}
