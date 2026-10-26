import GeunalCore

/// Runs a synchronous core call off the main actor. The calendar reads and writes the on-device store directly, which
/// takes a few milliseconds, but a scroll should never wait for SQLite.
func offMain<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
    try await Task.detached(operation: work).value
}
