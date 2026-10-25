import Foundation
import GeunalCore
import OSLog
import Security

/// The core's secret store over the keychain (docs/parts/ios.md §2). The device signing key, the account master key
/// and the database key live here as generic passwords under one service. They are readable after the first unlock,
/// so the notification extension can decrypt a push on a locked phone, and they never leave this device: a backup
/// restored elsewhere must not carry keys that unlock this installation's data.
public final class KeychainSecretStore: SecretStore {
    public static let defaultService = "app.geunal.core"

    public let service: String
    /// Shared between the app and the notification extension once both are signed with the entitlement (M1).
    public let accessGroup: String?

    // Status codes only: an item's name says which key failed, and the value never belongs in a log.
    private var log: Logger { Logger(subsystem: "app.geunal.ios", category: "keychain") }

    public init(service: String = KeychainSecretStore.defaultService, accessGroup: String? = nil) {
        self.service = service
        self.accessGroup = accessGroup
    }

    public func get(name: String) -> Data? {
        var query = baseQuery(name: name)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else {
            if status != errSecItemNotFound {
                log.error("get failed: \(status)")
            }
            return nil
        }
        return item as? Data
    }

    public func put(name: String, value: Data) {
        let update: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(baseQuery(name: name) as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var attributes = baseQuery(name: name)
            attributes.merge(update) { _, new in new }
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        if status != errSecSuccess {
            // The trait has no error channel; the core notices when the next `get` comes back empty.
            log.error("put failed: \(status)")
        }
    }

    public func delete(name: String) {
        let status = SecItemDelete(baseQuery(name: name) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            log.error("delete failed: \(status)")
        }
    }

    private func baseQuery(name: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: name,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        return query
    }
}
