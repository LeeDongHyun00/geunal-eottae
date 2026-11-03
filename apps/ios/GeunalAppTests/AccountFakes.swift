import FeatureAccount
import Foundation
import GeunalCore

/// A thread-safe box for what a fake core records, since the core is called off the main actor.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    var current: Value {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func update(_ change: (inout Value) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        change(&value)
    }
}

/// A core for the account screens: each answer is set by the test, and every call is recorded.
final class AccountFakeCore: Core, @unchecked Sendable {
    let calls = Locked<[String]>([])
    let lastAccount = Locked<NewAccount?>(nil)
    let lastLocalData = Locked<LocalDataChoice?>(nil)
    var authResult: Result<AuthResult, CoreError> = .success(
        .registered(session: Session(userId: "u1", deviceId: "d1"), newAccount: true, recovery: false))
    var guestResult: Result<Session, CoreError> = .success(Session(userId: "g1", deviceId: "d1"))
    var words: [String] = ["바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘", "가이드"]
    var otherAccountData: LocalData?
    /// How long the look for another account's events takes, for taps that land while the core looks.
    var localDataDelay: TimeInterval = 0
    var restoreResult: Result<RestoreSummary, CoreError> = .success(
        RestoreSummary(
            events: 3, people: 1, calendars: 1, shiftTemplates: 0, backupAt: Date(), replacedDevice: true, groups: 0))
    var alphaStats = false
    var alphaStatsError: CoreError?
    var summary = MeSummary(
        account: AccountView(userId: "u1", displayName: "하린", providers: []), calendars: 3, shiftTemplates: 2,
        people: 12, deviceCalendars: 0, recoverySaved: false, lastBackupAt: nil)
    var revoked: Revocation?
    /// The account's code; `nil` before one exists, which the core answers with `NotFound`.
    var code: [String]? = ["바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘"]
    var recovery = RecoveryStatus(created: true, saved: false, uploaded: true)
    var backupState = BackupStatus(last: nil, auto: true, changed: true, ready: true)
    var backupList: [BackupView] = []
    var backupResult: Result<BackupView, CoreError> = .success(
        BackupView(id: 1, createdAt: Date(), size: 1_000, automatic: false, events: 3, people: 1))
    /// What `deleteAccount` throws, one error per call in turn; once empty it deletes.
    let deleteErrors = Locked<[CoreError]>([])
    let lastProof = Locked<DeletionProof?>(nil)
    var linkResult: Result<[Provider], CoreError> = .success([.kakao])

    init() {
        super.init(noHandle: Core.NoHandle())
    }

    required init(unsafeFromHandle handle: UInt64) {
        fatalError("a fake core never comes from Rust")
    }

    private func record(_ call: String) {
        calls.update { $0.append(call) }
    }

    override func session() -> Session? { nil }

    override func signInNonce() async throws -> SignInNonce {
        record("nonce")
        return SignInNonce(nonce: "n1", providerNonce: "p1")
    }

    override func signInKakao(nonce: String, idToken: String, account: NewAccount) async throws -> AuthResult {
        record("kakao \(nonce) \(idToken)")
        lastAccount.update { $0 = account }
        return try authResult.get()
    }

    override func signInApple(nonce: String, idToken: String, account: NewAccount) async throws -> AuthResult {
        record("apple \(nonce) \(idToken)")
        lastAccount.update { $0 = account }
        return try authResult.get()
    }

    override func signInGuest(account: NewAccount) async throws -> Session {
        record("guest")
        lastAccount.update { $0 = account }
        return try guestResult.get()
    }

    override func startFresh(proof: DeletionProof?) async throws -> Session {
        record("startFresh")
        return Session(userId: "u1", deviceId: "d2")
    }

    override func checkRecoveryWord(text: String) throws -> String? {
        words.contains(text) ? text : nil
    }

    override func recoveryCompletions(prefix: String) throws -> [String] {
        words.filter { $0.hasPrefix(prefix) }
    }

    override func splitRecoveryCode(text: String) throws -> [String] {
        text.split(whereSeparator: { $0.isWhitespace || $0.isNumber || $0 == "." }).map(String.init)
    }

    override func localDataOfOtherAccount() throws -> LocalData? {
        if localDataDelay > 0 {
            Thread.sleep(forTimeInterval: localDataDelay)
        }
        return otherAccountData
    }

    override func restoreFromCode(
        words: [String], localData: LocalDataChoice, progress: ProgressListener?
    ) async throws -> RestoreSummary {
        record("restore \(words.joined(separator: " "))")
        lastLocalData.update { $0 = localData }
        progress?.onProgress(done: 1, total: 2)
        return try restoreResult.get()
    }

    override func alphaStatsEnabled() throws -> Bool { alphaStats }

    override func setAlphaStats(enabled: Bool) throws {
        if let alphaStatsError { throw alphaStatsError }
        record("alphaStats \(enabled)")
    }

    // MARK: 나 and the account (m1.md screens 12, 13, 20, 22)

    override func sync() async throws { record("sync") }
    override func meSummary() throws -> MeSummary { summary }
    override func revocation() throws -> Revocation? { revoked }

    override func recoveryWords() throws -> [String] {
        guard let code else { throw CoreError.NotFound(what: "recovery code") }
        return code
    }

    override func recoveryStatus() throws -> RecoveryStatus { recovery }
    override func markRecoverySaved() throws { record("markSaved") }
    override func backupStatus() throws -> BackupStatus { backupState }
    override func backups() async throws -> [BackupView] { backupList }

    override func backupNow(progress: ProgressListener?) async throws -> BackupView {
        record("backupNow")
        progress?.onProgress(done: 1, total: 2)
        return try backupResult.get()
    }

    override func setAutoBackup(enabled: Bool) throws { record("auto \(enabled)") }

    override func deleteAccount(proof: DeletionProof) async throws {
        record("delete")
        lastProof.update { $0 = proof }
        var next: CoreError?
        deleteErrors.update { errors in
            if !errors.isEmpty {
                next = errors.removeFirst()
            }
        }
        if let next { throw next }
    }

    override func linkKakao(nonce: String, idToken: String) async throws -> [Provider] {
        record("linkKakao \(nonce) \(idToken)")
        return try linkResult.get()
    }

    override func linkApple(nonce: String, idToken: String) async throws -> [Provider] {
        record("linkApple \(nonce) \(idToken)")
        return try linkResult.get()
    }
}

/// The OS prompt as the test sets it: passed or cancelled, on a device with or without a screen lock.
@MainActor
final class FakeDeviceOwner: DeviceOwnerCheck {
    var hasScreenLock = true
    var passes = true
    private(set) var asked: [DeviceOwnerReason] = []

    func confirm(_ reason: DeviceOwnerReason) async -> Bool {
        asked.append(reason)
        return passes || !hasScreenLock
    }
}

@MainActor
final class FakeProviders: ProviderSignIn {
    var result: Result<ProviderCredential, ProviderSignInError> = .success(
        ProviderCredential(idToken: "token", displayName: "하린"))
    private(set) var nonces: [String] = []

    func kakao(providerNonce: String) async throws -> ProviderCredential {
        nonces.append(providerNonce)
        return try result.get()
    }

    func apple(providerNonce: String) async throws -> ProviderCredential {
        nonces.append(providerNonce)
        return try result.get()
    }
}

@MainActor
final class FakePermissions: SystemPermissions {
    var calendar: PermissionState = .notAsked
    var calendarAnswer: PermissionState = .granted
    var notifications: PermissionState = .notAsked
    var notificationAnswer: PermissionState = .denied
    var calendars = 3
    private(set) var openedSettings = 0

    func calendarState() -> PermissionState { calendar }

    func requestCalendar() async -> PermissionState {
        calendar = calendarAnswer
        return calendarAnswer
    }

    func deviceCalendarCount() -> Int { calendars }
    func notificationState() async -> PermissionState { notifications }

    func requestNotifications() async -> PermissionState {
        notifications = notificationAnswer
        return notificationAnswer
    }

    func openSettings() {
        openedSettings += 1
    }
}

/// Defaults of their own for each test, so onboarding state never leaks between tests or into the app.
func scratchDefaults() -> UserDefaults {
    let name = "app.geunal.tests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return defaults
}

/// Collects the toasts a model asks for.
@MainActor
final class ToastLog {
    private(set) var messages: [String] = []

    func append(_ message: String) {
        messages.append(message)
    }
}
