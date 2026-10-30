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
        }

        /// The design's example code (docs/design/m1.md screen 21), all in the BIP39 Korean list.
        static let words = ["바람", "연필", "구름", "달력", "열쇠", "우산", "단추", "마당", "편지", "소풍", "거울", "하늘"]

        private let scenario: Scenario
        private let lock = NSLock()
        private var signedIn: Session?
        private var alphaStats = false

        init(scenario: Scenario) {
            self.scenario = scenario
            super.init(noHandle: Core.NoHandle())
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
            register()
        }

        override func account() throws -> AccountView? {
            locked { signedIn }.map { AccountView(userId: $0.userId, displayName: "하린", providers: []) }
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
            case .standard:
                return .registered(session: register(), newAccount: true, recovery: false)
            }
        }

        private func register() -> Session {
            let session = Session(userId: "preview-user", deviceId: "preview-device")
            locked { signedIn = session }
            return session
        }

        // MARK: Recovery code

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
            scenario == .otherAccountData ? LocalData(events: 86, people: 5) : nil
        }

        override func restoreFromCode(
            words: [String], localData: LocalDataChoice, progress: ProgressListener?
        ) async throws -> RestoreSummary {
            guard words == Self.words else { throw CoreError.WrongRecoveryCode }
            progress?.onProgress(done: 512_000, total: 1_024_000)
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
        }

        override func suggestions(day: String) throws -> [Suggestion] { [] }

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
