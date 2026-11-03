#if DEBUG
    import FeatureCalendar
    import Foundation
    import GeunalCore

    /// A stand-in core with fixed data for the UI tests and screenshots, debug builds only. It subclasses the generated
    /// `Core` without a Rust handle (docs/parts/rust-core.md §3 "테스트의 가짜 코어") and answers what the M1 screens
    /// call while the Rust bodies are still landing. A method it does not override traps, which keeps a screen from
    /// silently reading the real core in a test.
    final class PreviewCore: Core, @unchecked Sendable {
        enum Scenario: String {
            /// Kakao and Apple sign-ins register a new account.
            case standard
            /// Kakao and Apple sign-ins find another active device, so the device change screen follows.
            case replace
            /// Another account's events are on the device, so the restore asks first (m1.md screen 24).
            case otherAccountData
            /// A guest signed in on this device, the recovery code not yet on paper: 나, the banner, screens 13–22.
            case guest
            /// The same with a Kakao account, whose deletion signs in with Kakao once more.
            case kakao
            /// Another device signed in with the recovery code: the "기기 로그아웃됨" sheet on launch (screen 23).
            case revoked
            /// Kakao and Apple sign-ins find the account's backup on the server and no other device: "백업 가져오기"
            /// with "나중에 넣을게요" (screen 21).
            case offer
            /// A new account's sign-in finds another account's events on the device, asked about at once (screen 24).
            case otherAccountSignIn
        }

        /// The design's example code (docs/design/m1.md screen 21), all in the BIP39 Korean list.
        static let words = ["바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘"]

        private let scenario: Scenario
        private let lock = NSLock()
        private var signedIn: Session?
        private var linked: [Provider] = []
        private var recoverySaved = false
        private var revoked: Revocation?
        /// The offer's account has its code on the server only, until a restore or a fresh start.
        private var codeMissing = false
        /// Another account's events were answered for, by a restore or `settleLocalData`.
        private var localSettled = false
        private var autoBackup = true
        /// The phone's calendars the device calendar mirror took (m1.md screen 15).
        private var mirrored: [DeviceCalendarInput] = []
        private var alphaStats = false
        private var settings = NotificationSettings(
            eventAlert: 10, allDayAlert: 360, nextUpBar: true, birthdayAlerts: true, leaveSuggestions: true)

        init(scenario: Scenario) {
            self.scenario = scenario
            super.init(noHandle: Core.NoHandle())
            switch scenario {
            case .guest:
                signedIn = Self.session
            case .kakao:
                signedIn = Self.session
                linked = [.kakao]
            case .revoked:
                revoked = Revocation(
                    at: Date(timeIntervalSinceNow: -3_600), byPlatform: "android", via: .recovery)
            case .standard, .replace, .otherAccountData, .offer, .otherAccountSignIn:
                break
            }
        }

        required init(unsafeFromHandle handle: UInt64) {
            fatalError("a preview core never comes from Rust")
        }

        private func locked<T>(_ body: () -> T) -> T {
            lock.lock()
            defer { lock.unlock() }
            return body()
        }

        // MARK: M0

        override func cipherVersion() throws -> String { "4.9.0 community" }
        override func sync() async throws {}
        override func session() -> Session? { locked { signedIn } }

        // MARK: Sign-in

        override func signInNonce() async throws -> SignInNonce {
            SignInNonce(nonce: "preview-nonce", providerNonce: String(repeating: "0", count: 64))
        }

        override func signInKakao(nonce: String, idToken: String, account: NewAccount) async throws -> AuthResult {
            providerResult()
        }

        override func signInApple(nonce: String, idToken: String, account: NewAccount) async throws -> AuthResult {
            providerResult()
        }

        override func signInGuest(account: NewAccount) async throws -> Session {
            register()
        }

        override func startFresh(proof: DeletionProof?) async throws -> Session {
            locked { codeMissing = false }
            return register()
        }

        override func account() throws -> AccountView? {
            locked { signedIn.map { AccountView(userId: $0.userId, displayName: "하린", providers: linked) } }
        }

        override func linkKakao(nonce: String, idToken: String) async throws -> [Provider] {
            locked {
                linked = [.kakao]
                return linked
            }
        }

        override func linkApple(nonce: String, idToken: String) async throws -> [Provider] {
            locked {
                linked = [.apple]
                return linked
            }
        }

        override func revocation() throws -> Revocation? { locked { revoked } }

        override func meSummary() throws -> MeSummary {
            let view = try self.account()
            return MeSummary(
                account: view, calendars: 1, shiftTemplates: 0, people: 0, deviceCalendars: 0,
                recoverySaved: locked { recoverySaved }, lastBackupAt: view == nil ? nil : Self.backup.createdAt)
        }

        override func deleteAccount(proof: DeletionProof) async throws {
            locked {
                signedIn = nil
                linked = []
                recoverySaved = false
            }
        }

        override func recordAppForeground() throws {}
        override func alphaStatsEnabled() throws -> Bool { locked { alphaStats } }
        override func setAlphaStats(enabled: Bool) throws { locked { alphaStats = enabled } }

        private func providerResult() -> AuthResult {
            switch scenario {
            case .replace, .otherAccountData:
                return .replaceRequired(
                    userId: "preview-user", activePlatform: "android", activeSince: Date(timeIntervalSinceNow: -86_400),
                    recovery: true)
            case .offer:
                locked { codeMissing = true }
                return .registered(session: register(), newAccount: false, recovery: true)
            case .standard, .guest, .kakao, .revoked, .otherAccountSignIn:
                return .registered(session: register(), newAccount: true, recovery: false)
            }
        }

        private static let session = Session(userId: "preview-user", deviceId: "preview-device")

        private func register() -> Session {
            locked {
                signedIn = Self.session
                revoked = nil
            }
            return Self.session
        }

        // MARK: Recovery code and backups

        override func recoveryWords() throws -> [String] {
            guard locked({ signedIn }) != nil else { throw CoreError.NotFound(what: "recovery code") }
            return Self.words
        }

        override func recoveryStatus() throws -> RecoveryStatus {
            locked {
                let created = signedIn != nil && !codeMissing
                return RecoveryStatus(created: created, saved: recoverySaved, uploaded: created)
            }
        }

        override func markRecoverySaved() throws { locked { recoverySaved = true } }

        /// Last night's automatic backup.
        private static let backup = BackupView(
            id: 3, createdAt: Date(timeIntervalSinceNow: -6 * 3_600), size: 2_400_000, automatic: true, events: 128,
            people: 12)

        override func backupStatus() throws -> BackupStatus {
            locked { BackupStatus(last: Self.backup, auto: autoBackup, changed: false, ready: signedIn != nil) }
        }

        override func backups() async throws -> [BackupView] { [Self.backup] }

        override func backupNow(progress: ProgressListener?) async throws -> BackupView {
            progress?.onProgress(done: 2_400_000, total: 2_400_000)
            return Self.backup
        }

        override func setAutoBackup(enabled: Bool) throws { locked { autoBackup = enabled } }

        override func checkRecoveryWord(text: String) throws -> String? {
            let word = text.precomposedStringWithCanonicalMapping.trimmingCharacters(in: .whitespaces)
            return Self.words.contains(word) ? word : nil
        }

        override func recoveryCompletions(prefix: String) throws -> [String] {
            let prefix = prefix.precomposedStringWithCanonicalMapping
            return prefix.isEmpty ? [] : Self.words.filter { $0.hasPrefix(prefix) }
        }

        override func splitRecoveryCode(text: String) throws -> [String] {
            text.components(separatedBy: CharacterSet.letters.inverted).filter { !$0.isEmpty && Int($0) == nil }
        }

        override func localDataOfOtherAccount() throws -> LocalData? {
            let asks = (scenario == .otherAccountData || scenario == .otherAccountSignIn) && !locked({ localSettled })
            return asks ? LocalData(events: 86, people: 5) : nil
        }

        override func settleLocalData(choice: LocalDataChoice) throws { locked { localSettled = true } }

        /// The design's code passes; the same twelve words in another order fail the checksum.
        override func checkRecoveryCode(words: [String]) -> RecoveryCodeCheck {
            words != Self.words && words.sorted() == Self.words.sorted() ? .checksum : .valid
        }

        override func restoreFromCode(
            words: [String], localData: LocalDataChoice, progress: ProgressListener?
        ) async throws -> RestoreSummary {
            guard words == Self.words else { throw CoreError.WrongRecoveryCode }
            progress?.onProgress(done: 512_000, total: 1_024_000)
            locked {
                codeMissing = false
                localSettled = true
            }
            _ = register()
            return RestoreSummary(
                events: 128, people: 12, calendars: 4, shiftTemplates: 3, backupAt: Date(timeIntervalSinceNow: -3_600),
                replacedDevice: true, groups: 0)
        }

        // MARK: Calendar

        override func nextUp(count: UInt16) throws -> [UpNext] {
            let now = Date()
            let today = Self.koreanDay(now)
            let dinner = EventItem(
                eventId: "preview-dinner", occurrence: nil, calendarId: "preview-personal", calendarName: "개인",
                color: .blossom, title: "가족 · 저녁", allDay: false,
                start: DayTime(day: today, minute: 1080), end: DayTime(day: today, minute: 1200), soft: false,
                source: .own, recurrence: nil)
            let birthday = BirthdayItem(
                personId: "preview-minsu", name: "민수", day: Self.koreanDay(now + 86_400 * 5), lunar: false)
            return [
                .event(item: dinner, startsAt: now.addingTimeInterval(13_200), endsAt: now.addingTimeInterval(20_400)),
                .birthday(item: birthday),
            ]
        }

        /// An empty month: the onboarding tests end on the calendar, and the calendar's own tests run on the real core.
        override func calendarMonth(month: String) throws -> [DayCell] {
            guard let first = KoreanCalendar.start(of: month + "-01"),
                let firstDay = KoreanCalendar.weekStart(of: month + "-01")
            else { return [] }
            let days = KoreanCalendar.calendar.range(of: .day, in: .month, for: first)?.count ?? 30
            let lastDay = KoreanCalendar.adding(days: days - 1, to: month + "-01") ?? month
            let weekdayOfLast = KoreanCalendar.weekday(of: lastDay) ?? 7
            let count = (KoreanCalendar.days(from: firstDay, to: lastDay) ?? 0) + 1 + (7 - weekdayOfLast)
            return (0..<count).compactMap { offset in
                guard let day = KoreanCalendar.adding(days: offset, to: firstDay),
                    let weekday = KoreanCalendar.weekday(of: day).flatMap(Weekday.init(number:))
                else { return nil }
                return DayCell(
                    day: day, weekday: weekday, inMonth: KoreanCalendar.month(of: day) == month, holidays: [],
                    events: [], birthdays: [], leaveBridge: nil)
            }
        }

        override func calendarWeek(startDay: String) throws -> [WeekDay] {
            (0..<7).compactMap { offset in
                guard let day = KoreanCalendar.adding(days: offset, to: startDay),
                    let weekday = KoreanCalendar.weekday(of: day).flatMap(Weekday.init(number:))
                else { return nil }
                return WeekDay(
                    day: day, weekday: weekday, holidays: [], allDay: [], birthdays: [], timed: [], hidden: 0)
            }
        }

        override func calendarAgenda(fromDay: String, days: UInt16) throws -> [AgendaDay] { [] }

        override func calendars() throws -> [CalendarView] {
            [
                CalendarView(
                    id: "preview-personal", name: "개인", color: .tangerine, kind: .own, soft: false, visible: true,
                    defaultAlert: .inherit, eventCount: 0, shiftTemplateCount: 0)
            ]
                + locked { mirrored }.map {
                    CalendarView(
                        id: "device:" + $0.sourceId, name: $0.name, color: nil, kind: .device, soft: false,
                        visible: true, defaultAlert: .inherit, eventCount: UInt32($0.events.count),
                        shiftTemplateCount: 0)
                }
        }

        override func mirrorDeviceCalendars(calendars: [DeviceCalendarInput]) throws -> DeviceMirror {
            locked { mirrored = calendars }
            return DeviceMirror(
                calendars: UInt32(calendars.count), events: UInt32(calendars.map(\.events.count).reduce(0, +)))
        }

        /// The alerts wait for the system's permission, which a UI test may give; the preview schedules none.
        override func scheduledAlerts(limit: UInt32) throws -> [ScheduledAlert] { [] }

        override func suggestions(day: String) throws -> [Suggestion] { [] }

        // Calls the app makes on its own (coming to the front, a background task); a method left to `Core` would
        // reach Rust without a handle and crash the preview.
        /// No snapshot: the widget write skips, and the widgets say "그날어때를 열어 시작하세요".
        override func widgetSnapshot() throws -> String { throw CoreError.NotFound(what: "widget snapshot") }
        override func recordCrashes(day: String, count: UInt32) throws {}
        override func autoBackup() async throws -> BackupView? { nil }
        override func people() throws -> [Person] { [] }

        override func notificationSettings() throws -> NotificationSettings { locked { settings } }

        override func setNotificationSettings(settings: NotificationSettings) throws {
            locked { self.settings = settings }
        }

        override func addEvent(input: EventInput, fromSuggestion: Bool) throws -> AddedEvent {
            AddedEvent(eventId: UUID().uuidString, repeatHint: nil)
        }

        override func deleteEvent(eventId: String, occurrence: String?, scope: EditScope) throws {}

        private static func koreanDay(_ date: Date) -> String {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "Asia/Seoul") ?? .current
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            return String(format: "%04ld-%02ld-%02ld", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
        }
    }
#endif
