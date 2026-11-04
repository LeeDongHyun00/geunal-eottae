import os

/// Intervals Instruments shows under "Points of Interest"-style signposts (subsystem `app.geunal.ios`, category
/// `account`), so a TestFlight or development build can say which step of a sign-in or a deletion is slow: the
/// provider's sheet, the nonce, or the core's call (keys, recovery material and the server together). Only the step's
/// fixed name is recorded, never an account, a token or a server answer (docs/security/app-review.md).
@MainActor
enum AccountTrace {
    private static let signposter = OSSignposter(subsystem: "app.geunal.ios", category: "account")

    static func measure<T>(_ name: StaticString, _ work: () async throws -> T) async rethrows -> T {
        let state = signposter.beginInterval(name, id: signposter.makeSignpostID())
        defer { signposter.endInterval(name, state) }
        return try await work()
    }
}
