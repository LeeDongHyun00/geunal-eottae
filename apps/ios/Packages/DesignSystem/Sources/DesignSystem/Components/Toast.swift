import Observation
import SwiftUI
import UIKit

/// The one toast at a time that sits where the next-up bar is (components.md §6, m1.md screen 6): "카페 마감을 추가했어요
/// · 되돌리기". It replaces confirmation dialogs, so it lasts five seconds and pressing the action closes it at once.
/// A question that comes with it ("매주 금요일 반복으로 바꿀까요?") is a card of its own that outlives the toast
/// (m1.md decision 28).
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
        screenReaderRunning: @escaping @MainActor () -> Bool = { UIAccessibility.isVoiceOverRunning }
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

/// The toast's look. Over a plain screen it is the ink pill, 48 pt with radius 14. In the next-up bar's place it takes
/// the bar's 56 pt capsule with ink text (components.md "토스트"), and draws that glass itself only where the bar does.
public struct ToastView: View {
    public enum Style: Sendable {
        case ink
        /// `chrome` on iOS 17 and 18, where the bar draws its own capsule; iOS 26's tab accessory brings the glass.
        case slot(chrome: Bool)
    }

    private let toast: ToastCenter.Toast
    private let style: Style
    private let onAction: () -> Void

    public init(_ toast: ToastCenter.Toast, style: Style = .ink, onAction: @escaping () -> Void) {
        self.toast = toast
        self.style = style
        self.onAction = onAction
    }

    public var body: some View {
        HStack(spacing: Space.s) {
            Text(verbatim: toast.message)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionTitle = toast.actionTitle {
                Button(action: onAction) {
                    Text(verbatim: actionTitle)
                        .frame(minHeight: Metrics.minHitTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .typeStyle(.secondaryStrong)
        .foregroundStyle(isSlot ? GeunalColor.ink : GeunalColor.background)
        .padding(.horizontal, isSlot ? Space.l : Space.m)
        .frame(minHeight: isSlot ? Metrics.NextUpBar.height : Metrics.Toast.height)
        .background { background }
        .accessibilityElement(children: .contain)
        .onAppear {
            AccessibilityNotification.Announcement(toast.message).post()
        }
    }

    private var isSlot: Bool {
        if case .slot = style { return true }
        return false
    }

    @ViewBuilder private var background: some View {
        switch style {
        case .ink:
            RoundedRectangle(cornerRadius: Metrics.Toast.radius)
                .fill(GeunalColor.ink)
                .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        case .slot(let chrome):
            if chrome {
                Capsule()
                    .fill(.regularMaterial)
                    .overlay(Capsule().strokeBorder(Color.black.opacity(0.07), lineWidth: 1))
                    .shadow(color: .black.opacity(0.10), radius: 16, y: 12)
            }
        }
    }
}

extension View {
    /// Shows the center's toast in place of `content`'s usual occupant, the next-up bar, animated the way
    /// components.md §9 says. `chrome` is the bar's: whether the slot draws its own capsule.
    public func toastSlot(_ center: ToastCenter, chrome: Bool) -> some View {
        modifier(ToastSlot(center: center, chrome: chrome))
    }
}

private struct ToastSlot: ViewModifier {
    let center: ToastCenter
    let chrome: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        ZStack {
            if let toast = center.current {
                ToastView(toast, style: .slot(chrome: chrome)) { center.performAction() }
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            } else {
                content
                    .transition(.opacity)
            }
        }
        .animation(
            reduceMotion
                ? .easeInOut(duration: Motion.ReducedMotion.fadeDuration)
                : .easeOut(duration: Motion.Toast.inDuration),
            value: center.current)
    }
}

extension View {
    /// The question a toast may carry, as a card above the bottom bars (m1.md screen 6). The tab bar's accessory is
    /// one line high on iOS 26, so the toast stays in the next-up bar's place and the question sits 8 above it.
    public func toastProposal(_ center: ToastCenter) -> some View {
        modifier(ProposalSlot(center: center))
    }
}

private struct ProposalSlot: ViewModifier {
    let center: ToastCenter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom) {
                ZStack {
                    if let question = center.question {
                        ProposalCard(
                            proposal: question.proposal, onDecline: { center.declineProposal() },
                            onAccept: { center.acceptProposal() }
                        )
                        .padding(.horizontal, Metrics.TabBar.sideInset)
                        .padding(.bottom, Space.xs)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: Motion.Toast.inDuration), value: center.question)
            }
    }
}

/// The question card (components.md "제안 카드", boards/Added): the next-up bar's glass with radius 24, the question in
/// 15 · 600, then 괜찮아요 and the action as two 44 pills on the right.
public struct ProposalCard: View {
    private let proposal: ToastCenter.Proposal
    private let onDecline: () -> Void
    private let onAccept: () -> Void

    /// Not a token: components.md gives the card its own radius.
    private static let radius: CGFloat = 24

    public init(proposal: ToastCenter.Proposal, onDecline: @escaping () -> Void, onAccept: @escaping () -> Void) {
        self.proposal = proposal
        self.onDecline = onDecline
        self.onAccept = onAccept
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(verbatim: proposal.message)
                .typeStyle(.secondaryStrong)
                .foregroundStyle(GeunalColor.ink)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: Space.xs) {
                Spacer(minLength: 0)
                Button(action: onDecline) {
                    Text(verbatim: proposal.declineTitle)
                }
                .buttonStyle(.geunal(.pill, height: Metrics.Button.secondary))
                .accessibilityIdentifier("toast.decline")
                Button(action: onAccept) {
                    Text(verbatim: proposal.acceptTitle)
                }
                .buttonStyle(.geunal(.prominentPill, height: Metrics.Button.secondary))
                .accessibilityIdentifier("toast.accept")
            }
        }
        .padding(EdgeInsets(top: 14, leading: 18, bottom: 12, trailing: 12))
        .background {
            RoundedRectangle(cornerRadius: Self.radius)
                .fill(.regularMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: Self.radius)
                        .strokeBorder(Color.black.opacity(0.07), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.12), radius: 16, y: 12)
        }
        .accessibilityElement(children: .contain)
        .onAppear {
            AccessibilityNotification.Announcement(proposal.message).post()
        }
    }
}
