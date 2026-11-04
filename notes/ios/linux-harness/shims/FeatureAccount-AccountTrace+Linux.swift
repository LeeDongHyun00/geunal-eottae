// Linux stand-in for AccountTrace.swift, which needs Apple's `os`.
@MainActor
enum AccountTrace {
    static func measure<T>(_ name: StaticString, _ work: () async throws -> T) async rethrows -> T {
        try await work()
    }
}
