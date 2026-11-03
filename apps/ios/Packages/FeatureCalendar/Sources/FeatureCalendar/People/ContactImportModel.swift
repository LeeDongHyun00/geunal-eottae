import Foundation
import GeunalCore
import Observation

/// A contact with a birthday, as the import lists it.
public struct ContactCandidate: Identifiable, Equatable, Sendable {
    /// The contact's identifier, so a second import updates the person instead of adding a copy.
    public let id: String
    public let name: String
    public let birthday: Birthday

    public init(id: String, name: String, birthday: Birthday) {
        self.id = id
        self.name = name
        self.birthday = birthday
    }
}

public enum ContactAccess: Equatable, Sendable {
    case notAsked
    case granted
    case denied
}

/// The device's contacts behind the import, so the model can be tested without the system alert.
@MainActor
public protocol ContactBook: AnyObject {
    func access() -> ContactAccess
    func requestAccess() async -> ContactAccess
    /// Everyone with a birthday, and how many contacts had none.
    func birthdays() async -> (candidates: [ContactCandidate], withoutBirthday: Int)
    func openSettings()
}

/// "연락처에서 생일 가져오기" (m1.md §2.4): the permission, then the person picks whom to bring in. Contacts are an
/// option, so nothing is picked until the person picks it; what comes in stays on the device and in the encrypted
/// backup (docs/design/tone.md "권한 요청").
@MainActor
@Observable
public final class ContactImportModel {
    public enum Phase: Equatable, Sendable {
        case checking
        case denied
        case picking
        case importing
        case finished(ContactImport)
    }

    public private(set) var phase: Phase = .checking
    public private(set) var candidates: [ContactCandidate] = []
    public private(set) var withoutBirthday = 0
    public private(set) var selected: Set<String> = []
    public private(set) var failed = false

    @ObservationIgnored private let core: any CoreProtocol
    @ObservationIgnored private let contacts: any ContactBook

    public init(core: any CoreProtocol, contacts: any ContactBook) {
        self.core = core
        self.contacts = contacts
    }

    public var allSelected: Bool { !candidates.isEmpty && selected.count == candidates.count }

    /// Asks for the permission the first time, then reads the contacts that have a birthday.
    public func start() async {
        guard phase == .checking || phase == .denied else { return }
        var access = contacts.access()
        if access == .notAsked {
            access = await contacts.requestAccess()
        }
        guard access == .granted else {
            phase = .denied
            return
        }
        let found = await contacts.birthdays()
        candidates = found.candidates
        withoutBirthday = found.withoutBirthday
        selected = []
        phase = .picking
    }

    public func isSelected(_ id: String) -> Bool { selected.contains(id) }

    public func toggle(_ id: String) {
        guard phase == .picking else { return }
        if selected.contains(id) {
            selected.remove(id)
        } else if candidates.contains(where: { $0.id == id }) {
            selected.insert(id)
        }
    }

    /// "모두 고르기" and back.
    public func toggleAll() {
        guard phase == .picking else { return }
        selected = allSelected ? [] : Set(candidates.map(\.id))
    }

    /// Brings the picked contacts in, once.
    public func importSelected() async {
        guard phase == .picking, !selected.isEmpty else { return }
        phase = .importing
        let picked = candidates.filter { selected.contains($0.id) }
            .map { ContactInput(contactId: $0.id, name: $0.name, birthday: $0.birthday) }
        let core = self.core
        do {
            let result = try await offMain { try core.importContacts(contacts: picked) }
            failed = false
            phase = .finished(result)
        } catch {
            failed = true
            phase = .picking
        }
    }

    public func openSettings() {
        contacts.openSettings()
    }

    /// The toast after the import: "3명을 가져왔어요", "1명을 새로 가져오고 2명을 고쳤어요".
    public static func summary(_ result: ContactImport) -> String {
        let added = Int(result.added)
        let updated = Int(result.updated)
        switch (added, updated) {
        case (0, 0):
            return String(localized: "새로 가져올 생일이 없었어요", bundle: .module)
        case (_, 0):
            return String(localized: "\(added)명을 가져왔어요", bundle: .module)
        case (0, _):
            return String(localized: "\(updated)명의 생일을 고쳤어요", bundle: .module)
        default:
            return String(localized: "\(added)명을 새로 가져오고 \(updated)명을 고쳤어요", bundle: .module)
        }
    }
}
