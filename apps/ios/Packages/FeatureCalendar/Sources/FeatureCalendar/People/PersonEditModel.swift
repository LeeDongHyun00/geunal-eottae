import Foundation
import GeunalCore
import Observation

/// The person sheet (m1.md screen 17): a name, a solar or lunar birthday with an optional year, the alert and whether
/// the birthday feeds the "OO 생일 약속" chips. A lunar birthday is kept lunar; the note says which solar day it is
/// this year (rust-core.md §6). A person a group shares (M2) keeps the group's name and birthday; only the alert
/// changes.
@MainActor
@Observable
public final class PersonEditModel {
    public enum Kind: Hashable, Sendable {
        case solar
        case lunar
    }

    public static let firstYear = 1900

    public let personId: String?
    public let readOnly: Bool
    public var name = ""
    public private(set) var kind: Kind = .solar
    public var leapMonth = false
    public private(set) var month: Int?
    public private(set) var day: Int?
    /// Optional ("몰라도 돼요"); no age is ever shown.
    public var year: Int?
    public var alert: AlertRule = .inherit
    public var suggest = true
    /// The lunar birthday's solar day this year, for the note.
    public private(set) var solarThisYear: String?
    public private(set) var isWorking = false
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let now: @Sendable () -> Date

    public init(person: Person? = nil, core: any CoreProtocol, now: @escaping @Sendable () -> Date = Date.init) {
        personId = person?.id
        readOnly = person?.readOnly ?? false
        self.core = core
        self.now = now
        guard let person else { return }
        name = person.name
        alert = person.alert
        suggest = person.suggest
        if let birthday = person.birthday {
            month = Int(birthday.month)
            day = Int(birthday.day)
            year = birthday.year.map(Int.init)
            if case .lunar(let leap) = birthday.calendar {
                kind = .lunar
                leapMonth = leap
            }
        }
    }

    public var isNew: Bool { personId == nil }

    public var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    public var canSave: Bool { (readOnly || !trimmedName.isEmpty) && !isWorking }

    public var thisYear: Int { KoreanCalendar.parse(KoreanCalendar.day(of: now()))?.year ?? 2026 }

    /// The years the wheel offers, newest first (rust-core.md §6: 1900 to this year).
    public var years: [Int] { Array((Self.firstYear...max(thisYear, Self.firstYear)).reversed()) }

    /// What the wheel offers for the day: 30 days on the lunar calendar, and the month's own count on the solar one
    /// with 29 in February, since a 2월 29일 birthday exists (it falls on the 28th in other years).
    public var days: ClosedRange<Int> {
        guard kind == .solar else { return 1...30 }
        switch month ?? 1 {
        case 2: return 1...29
        case 4, 6, 9, 11: return 1...30
        default: return 1...31
        }
    }

    public var birthday: Birthday? {
        guard let month, let day else { return nil }
        return Birthday(
            month: UInt8(month), day: UInt8(day), year: year.map(Int32.init),
            calendar: kind == .lunar ? .lunar(leapMonth: leapMonth) : .solar)
    }

    public func setKind(_ kind: Kind) {
        self.kind = kind
        if kind == .solar {
            leapMonth = false
        }
        clampDay()
    }

    /// Picking a month or a day sets the other to a start when it had none, so the wheel always shows a date.
    public func setMonth(_ month: Int) {
        self.month = min(max(month, 1), 12)
        if day == nil {
            day = 1
        }
        clampDay()
    }

    public func setDay(_ day: Int) {
        if month == nil {
            month = 1
        }
        self.day = min(max(day, days.lowerBound), days.upperBound)
    }

    public func clearBirthday() {
        month = nil
        day = nil
        year = nil
        leapMonth = false
        solarThisYear = nil
    }

    /// Asks the core where a lunar birthday falls this year; the view calls it whenever the birthday changes.
    public func refreshNote() async {
        guard kind == .lunar, let birthday else {
            solarThisYear = nil
            return
        }
        let core = self.core
        let year = Int32(thisYear)
        let solar = (try? await offMain { try core.birthdayInYear(birthday: birthday, year: year) }) ?? nil
        // The person may have picked another date while the core looked.
        guard birthday == self.birthday else { return }
        solarThisYear = solar
    }

    /// The person's id, or `nil` when the core refused.
    public func save() async -> String? {
        guard canSave else { return nil }
        isWorking = true
        defer { isWorking = false }
        let input = PersonInput(
            id: personId, name: readOnly ? name : trimmedName, birthday: birthday, alert: alert, suggest: suggest)
        let core = self.core
        do {
            let id = try await offMain { try core.savePerson(input: input) }
            failed = false
            return id
        } catch {
            failed = true
            return nil
        }
    }

    public func delete() async -> Bool {
        guard let personId, !readOnly, !isWorking else { return false }
        isWorking = true
        defer { isWorking = false }
        let core = self.core
        do {
            try await offMain { try core.deletePerson(personId: personId) }
            failed = false
            return true
        } catch {
            failed = true
            return false
        }
    }

    private func clampDay() {
        if let day {
            self.day = min(max(day, days.lowerBound), days.upperBound)
        }
    }
}
