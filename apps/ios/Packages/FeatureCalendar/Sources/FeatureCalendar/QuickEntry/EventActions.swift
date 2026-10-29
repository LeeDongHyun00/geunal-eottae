import DesignSystem
import Foundation
import GeunalCore

/// Adding an event and what follows it (m1.md screen 6): the toast "카페 마감을 추가했어요 · 되돌리기" for five
/// seconds, and on the third same title on the same weekday the question whether to make it weekly.
@MainActor
public struct EventActions {
    private let core: any CoreProtocol
    private let toasts: ToastCenter

    public init(core: any CoreProtocol, toasts: ToastCenter) {
        self.core = core
        self.toasts = toasts
    }

    /// Saves `input`; `false` when the core refused, after saying so.
    @discardableResult
    public func add(_ input: EventInput, fromSuggestion: Bool) async -> Bool {
        let core = self.core
        let added: AddedEvent
        do {
            added = try await offMain { try core.addEvent(input: input, fromSuggestion: fromSuggestion) }
        } catch {
            toasts.show(String(localized: "저장하지 못했어요. 다시 시도해 주세요", bundle: .module))
            return false
        }
        let particle = Josa.object(after: input.title)
        let message = String(localized: "\(input.title)\(particle) 추가했어요", bundle: .module)
        let undoTitle = String(localized: "되돌리기", bundle: .module)
        let eventId = added.eventId
        // The toast's closures hold the center weakly: it holds them until the toast goes.
        let undo: @MainActor () -> Void = { [weak toasts] in
            Task { await Self.undo(eventId, core: core, toasts: toasts) }
        }
        guard let hint = added.repeatHint else {
            toasts.show(message, actionTitle: undoTitle, action: undo)
            return true
        }
        let weekday = DayText.weekdayLongName(hint.weekday.number)
        let proposal = ToastCenter.Proposal(
            message: String(localized: "매주 \(weekday) 반복으로 바꿀까요?", bundle: .module),
            declineTitle: String(localized: "괜찮아요", bundle: .module),
            acceptTitle: String(localized: "반복으로 바꾸기", bundle: .module))
        toasts.show(
            message, actionTitle: undoTitle, action: undo, proposal: proposal,
            onAccept: { [weak toasts] in
                Task { await Self.makeWeekly(eventId, input: input, weekday: weekday, core: core, toasts: toasts) }
            })
        return true
    }

    /// "되돌리기": the event goes, and the core takes back what the add counted (rust-core.md §6 "입력 추천").
    static func undo(_ eventId: String, core: any CoreProtocol, toasts: ToastCenter?) async {
        do {
            try await offMain { try core.deleteEvent(eventId: eventId, occurrence: nil, scope: .thisAndFollowing) }
            toasts?.show(String(localized: "되돌렸어요", bundle: .module))
        } catch {
            toasts?.show(String(localized: "되돌리지 못했어요. 다시 시도해 주세요", bundle: .module))
        }
    }

    /// "반복으로 바꾸기": the same event repeats weekly on its own weekday from now on.
    static func makeWeekly(
        _ eventId: String, input: EventInput, weekday: String, core: any CoreProtocol, toasts: ToastCenter?
    ) async {
        var weekly = input
        weekly.recurrence = Recurrence(frequency: .weekly, interval: 1, weekdays: [], until: nil, count: nil)
        let changed = weekly
        do {
            _ = try await offMain {
                try core.updateEvent(eventId: eventId, occurrence: nil, scope: .thisAndFollowing, input: changed)
            }
            toasts?.show(String(localized: "매주 \(weekday)로 바꿨어요", bundle: .module))
        } catch {
            toasts?.show(String(localized: "바꾸지 못했어요. 다시 시도해 주세요", bundle: .module))
        }
    }
}
