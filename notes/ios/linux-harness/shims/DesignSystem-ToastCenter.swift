// Linux stand-in for the DesignSystem pieces the calendar models use: the toast center as written, and its duration.
import Foundation
import Observation

public enum Metrics {
    public enum Toast {
        public static let duration: TimeInterval = 5
    }
}

@MainActor
@Observable
public final class ToastCenter {
    /// A question that comes with a toast: "매주 금요일 반복으로 바꿀까요?" · 괜찮아요 · 반복으로 바꾸기 (m1.md screen 6).
    public struct Proposal: Equatable, Sendable {
        public let message: String
        public let declineTitle: String
        public let acceptTitle: String

        public init(message: String, declineTitle: String, acceptTitle: String) {
            self.message = message
            self.declineTitle = declineTitle
            self.acceptTitle = acceptTitle
        }
    }

    public struct Toast: Identifiable, Equatable, Sendable {
        public let id: UUID
        /// Already localized by the caller, which knows its own bundle.
        public let message: String
        public let actionTitle: String?

        public init(id: UUID = UUID(), message: String, actionTitle: String? = nil) {
            self.id = id
            self.message = message
            self.actionTitle = actionTitle
        }
    }

    /// The card on screen with its question.
    public struct Question: Identifiable, Equatable, Sendable {
        public let id: UUID
        public let proposal: Proposal
    }

    public private(set) var current: Toast?
    public private(set) var question: Question?
    @ObservationIgnored private var action: (@MainActor () -> Void)?
    @ObservationIgnored private var acceptance: (@MainActor () -> Void)?
    @ObservationIgnored private var dismissal: Task<Void, Never>?
    @ObservationIgnored private var questionDismissal: Task<Void, Never>?
    private let duration: Duration
    private let proposalDuration: Duration
    private let screenReaderRunning: @MainActor () -> Bool

    /// The toast and its undo last `duration`, five seconds, because the core takes an undo back only that long after
    /// the add. The question is asked once, so its card stays `proposalDuration`, and with a screen reader running
    /// until it is answered (m1.md decision 28).
    public init(
        duration: Duration = .milliseconds(Int(Metrics.Toast.duration * 1000)),
        proposalDuration: Duration = .seconds(10),
        screenReaderRunning: @escaping @MainActor () -> Bool = { false }
    ) {
        self.duration = duration
        self.proposalDuration = proposalDuration
        self.screenReaderRunning = screenReaderRunning
    }

    /// Shows `message`, replacing any toast on screen and its question. `action` runs when its title is pressed.
    public func show(_ message: String, actionTitle: String? = nil, action: (@MainActor () -> Void)? = nil) {
        present(Toast(message: message, actionTitle: actionTitle), action: action)
    }

    /// Shows `message` and the card with `proposal` above it; `onAccept` runs when the person takes the proposal.
    public func show(
        _ message: String, actionTitle: String?, action: (@MainActor () -> Void)?, proposal: Proposal,
        onAccept: @escaping @MainActor () -> Void
    ) {
        present(Toast(message: message, actionTitle: actionTitle), action: action)
        ask(proposal, onAccept: onAccept)
    }

    /// The toast's action, the undo. The question goes with it: what it asked about is gone.
    public func performAction() {
        let action = self.action
        dismiss()
        dismissQuestion()
        action?()
    }

    /// "반복으로 바꾸기": the card goes; what `onAccept` shows replaces the toast.
    public func acceptProposal() {
        let acceptance = self.acceptance
        dismissQuestion()
        acceptance?()
    }

    /// "괜찮아요": the card goes, and the toast stays for the rest of its time.
    public func declineProposal() {
        dismissQuestion()
    }

    /// Closes the toast; with `id`, only if that toast is still the one on screen. A question stays for its own time.
    public func dismiss(_ id: UUID? = nil) {
        guard let current, id == nil || current.id == id else { return }
        dismissal?.cancel()
        dismissal = nil
        action = nil
        self.current = nil
    }

    private func dismissQuestion(_ id: UUID? = nil) {
        guard let question, id == nil || question.id == id else { return }
        questionDismissal?.cancel()
        questionDismissal = nil
        acceptance = nil
        self.question = nil
    }

    private func present(_ toast: Toast, action: (@MainActor () -> Void)?) {
        dismissal?.cancel()
        dismissQuestion()
        current = toast
        self.action = action
        let duration = self.duration
        dismissal = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.dismiss(toast.id)
        }
    }

    private func ask(_ proposal: Proposal, onAccept: @escaping @MainActor () -> Void) {
        let question = Question(id: UUID(), proposal: proposal)
        self.question = question
        acceptance = onAccept
        guard !screenReaderRunning() else { return }
        let duration = proposalDuration
        questionDismissal = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.dismissQuestion(question.id)
        }
    }
}
