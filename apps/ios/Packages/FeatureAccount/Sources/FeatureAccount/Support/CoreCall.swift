import Foundation

/// Runs a synchronous core call off the main actor. The account screens read the on-device store and the keychain,
/// which takes a few milliseconds, but a screen should never wait for SQLite.
func offMain<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
    try await Task.detached(operation: work).value
}
