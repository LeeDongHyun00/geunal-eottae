import Contacts
import GeunalCore
import UIKit

/// The system's contacts (docs/parts/ios.md §5 "연락처 생일"). A lunar birthday comes from `nonGregorianBirthday` and
/// stays lunar, so the core turns it into this year's solar day like one typed by hand. Only the name and the birthday
/// are read.
@MainActor
public final class DeviceContacts: ContactBook {
    public init() {}

    public func access() -> ContactAccess {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .notDetermined: .notAsked
        case .authorized: .granted
        case .denied, .restricted: .denied
        // iOS 18's limited access: the contacts the person chose to share are the ones the import reads.
        default: .granted
        }
    }

    public func requestAccess() async -> ContactAccess {
        let store = CNContactStore()
        _ = try? await store.requestAccess(for: .contacts)
        return access()
    }

    public func birthdays() async -> (candidates: [ContactCandidate], withoutBirthday: Int) {
        await Task.detached { Self.read() }.value
    }

    public func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private nonisolated static func read() -> (candidates: [ContactCandidate], withoutBirthday: Int) {
        let keys: [CNKeyDescriptor] = [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactBirthdayKey as CNKeyDescriptor,
            CNContactNonGregorianBirthdayKey as CNKeyDescriptor,
        ]
        let request = CNContactFetchRequest(keysToFetch: keys)
        request.sortOrder = .userDefault
        var candidates: [ContactCandidate] = []
        var withoutBirthday = 0
        let thisYear = KoreanCalendar.parse(KoreanCalendar.day(of: Date()))?.year ?? 2026
        try? CNContactStore().enumerateContacts(with: request) { contact, _ in
            let name = (CNContactFormatter.string(from: contact, style: .fullName) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return }
            if let birthday = birthday(of: contact, thisYear: thisYear) {
                candidates.append(ContactCandidate(id: contact.identifier, name: name, birthday: birthday))
            } else {
                withoutBirthday += 1
            }
        }
        return (candidates, withoutBirthday)
    }

    /// The lunar birthday when the contact has one (Contacts keeps it on the Chinese calendar, whose month and day
    /// numbers the Korean lunar table shares), else the solar one. A year outside 1900 to this year is dropped:
    /// Contacts writes 1604 for a birthday without one.
    nonisolated static func birthday(of contact: CNContact, thisYear: Int) -> Birthday? {
        if let lunar = contact.nonGregorianBirthday, isLunar(lunar.calendar), let month = lunar.month,
            let day = lunar.day, (1...12).contains(month), (1...30).contains(day)
        {
            return Birthday(
                month: UInt8(month), day: UInt8(day), year: nil, calendar: .lunar(leapMonth: lunar.isLeapMonth == true))
        }
        if let solar = contact.birthday, let month = solar.month, let day = solar.day, (1...12).contains(month),
            (1...31).contains(day)
        {
            let year = solar.year.flatMap { (1900...thisYear).contains($0) ? Int32($0) : nil }
            return Birthday(month: UInt8(month), day: UInt8(day), year: year, calendar: .solar)
        }
        return nil
    }

    private nonisolated static func isLunar(_ calendar: Calendar?) -> Bool {
        guard let identifier = calendar?.identifier else { return false }
        if identifier == .chinese {
            return true
        }
        // Foundation names the Korean calendar only from iOS 26; Contacts files a lunar birthday under the Chinese
        // one before that.
        if #available(iOS 26, *) {
            return identifier == .dangi
        }
        return false
    }
}
