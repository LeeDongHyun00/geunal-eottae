import Foundation
import GeunalCore

/// A core that answers with whatever the test decided (docs/parts/ios.md §9).
final class FakeCore: CoreProtocol {
    let version: Result<String, CoreError>
    let syncOutcome: Result<Void, CoreError>

    init(version: Result<String, CoreError> = .success("4.9.0 community"), sync: Result<Void, CoreError> = .success(()))
    {
        self.version = version
        self.syncOutcome = sync
    }

    func cipherVersion() throws -> String {
        try version.get()
    }

    func sync() async throws {
        try syncOutcome.get()
    }

    // The status screen calls only the two above; the rest of the M0 surface is not reached from these tests.
    func session() -> Session? { nil }
    func signInStub(subject: String, displayName: String, platform: String) async throws -> Session { unused() }
    func createGroup(name: String?) async throws -> String { unused() }
    func addMembers(groupId: String, userIds: [String]) async throws { unused() }
    func removeMember(groupId: String, userId: String) async throws { unused() }
    func sendText(groupId: String, body: String) async throws -> String { unused() }
    func requestDate(
        groupId: String, day: String, mode: Mode, start: UInt16?, end: UInt16?, activity: String?, note: String?,
        leaderOptions: Bool
    ) async throws -> String { unused() }
    func respond(groupId: String, appt: String, accept: Bool) async throws { unused() }
    func openPoll(groupId: String, appt: String, stage: Stage, options: [String]) async throws { unused() }
    func vote(groupId: String, appt: String, stage: Stage, option: String?) async throws { unused() }
    func closePoll(groupId: String, appt: String, stage: Stage) async throws { unused() }
    func cancel(groupId: String, appt: String) async throws { unused() }
    func setAvailability(groupId: String, windowStart: String, days: UInt16, bits: Data) async throws { unused() }
    func groups() throws -> [GroupInfo] { unused() }
    func members(groupId: String) throws -> [String] { unused() }
    func groupState(groupId: String) throws -> GroupStateView { unused() }
    func messages(groupId: String) throws -> [ChatMessage] { unused() }
    func groupAvailability(groupId: String) throws -> [MemberAvailability] { unused() }
    func connectRealtime(gatewayBase: String) async throws { unused() }
    func nextRealtimeEvent(timeoutMs: UInt64) async throws -> RealtimeEvent? { unused() }
    func disconnectRealtime() async { unused() }

    private func unused() -> Never { fatalError("the status screen does not call this") }
}

/// A secret store in memory, for opening the real core in a test without touching the keychain.
final class MemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]

    func get(name: String) -> Data? {
        lock.withLock { values[name] }
    }

    func put(name: String, value: Data) {
        lock.withLock { values[name] = value }
    }

    func delete(name: String) {
        lock.withLock { _ = values.removeValue(forKey: name) }
    }
}
