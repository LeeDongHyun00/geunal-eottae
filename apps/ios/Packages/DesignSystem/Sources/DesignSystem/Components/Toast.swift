import Observation
import SwiftUI

/// The one toast at a time that sits where the next-up bar is (components.md §6, m1.md screen 6): "카페 마감을 추가했어요
/// · 되돌리기". It replaces confirmation dialogs, so it lasts five seconds and pressing the action closes it at once.
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
        public let proposal: Proposal?

        public init(id: UUID = UUID(), message: String, actionTitle: String? = nil, proposal: Proposal? = nil) {
            self.id = id
            self.message = message
            self.actionTitle = actionTitle
            self.proposal = proposal
        }
    }

    public private(set) var current: Toast?
    @ObservationIgnored private var action: (@MainActor () -> Void)?
    @ObservationIgnored private var acceptance: (@MainActor () -> Void)?
    @ObservationIgnored private var dismissal: Task<Void, Never>?
    private let duration: Duration
    private let proposalDuration: Duration

    /// A toast with a question stays for `proposalDuration`: the question is asked once, so five seconds would lose
    /// it for anyone who reads slowly.
    public init(
        duration: Duration = .milliseconds(Int(Metrics.Toast.duration * 1000)),
        proposalDuration: Duration = .seconds(10)
    ) {
        self.duration = duration
        self.proposalDuration = proposalDuration
    }

    /// Shows `message`, replacing any toast on screen. `action` runs when its title is pressed.
    public func show(_ message: String, actionTitle: String? = nil, action: (@MainActor () -> Void)? = nil) {
        present(Toast(message: message, actionTitle: actionTitle), action: action, acceptance: nil)
    }

    /// Shows `message` with a question under it; `onAccept` runs when the person takes the proposal.
    public func show(
        _ message: String, actionTitle: String?, action: (@MainActor () -> Void)?, proposal: Proposal,
        onAccept: @escaping @MainActor () -> Void
    ) {
        present(
            Toast(message: message, actionTitle: actionTitle, proposal: proposal), action: action, acceptance: onAccept)
    }

    public func performAction() {
        let action = self.action
        dismiss()
        action?()
    }

    public func acceptProposal() {
        let acceptance = self.acceptance
        dismiss()
        acceptance?()
    }

    /// Closes the toast; with `id`, only if that toast is still the one on screen.
    public func dismiss(_ id: UUID? = nil) {
        guard let current, id == nil || current.id == id else { return }
        dismissal?.cancel()
        dismissal = nil
        action = nil
        acceptance = nil
        self.current = nil
    }

    private func present(_ toast: Toast, action: (@MainActor () -> Void)?, acceptance: (@MainActor () -> Void)?) {
        dismissal?.cancel()
        current = toast
        self.action = action
        self.acceptance = acceptance
        let duration = toast.proposal == nil ? self.duration : proposalDuration
        dismissal = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.dismiss(toast.id)
        }
    }
}

/// The toast's look: 48 pt, radius 14, ink with the background color for text, the action on the right.
public struct ToastView: View {
    private let toast: ToastCenter.Toast
    private let onAction: () -> Void

    public init(_ toast: ToastCenter.Toast, onAction: @escaping () -> Void) {
        self.toast = toast
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
        .foregroundStyle(GeunalColor.background)
        .padding(.horizontal, Space.m)
        .frame(minHeight: Metrics.Toast.height)
        .background(GeunalColor.ink, in: RoundedRectangle(cornerRadius: Metrics.Toast.radius))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        .accessibilityElement(children: .contain)
        .onAppear {
            AccessibilityNotification.Announcement(toast.message).post()
        }
    }
}

extension View {
    /// Shows the center's toast in place of `content`'s usual occupant, animated the way components.md §9 says.
    public func toastSlot(_ center: ToastCenter) -> some View {
        modifier(ToastSlot(center: center))
    }
}

private struct ToastSlot: ViewModifier {
    let center: ToastCenter
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        ZStack {
            if let toast = center.current {
                ToastView(toast) { center.performAction() }
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
    /// one line high on iOS 26, so the toast stays in the next-up bar's place and the question sits right above it.
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
                    if let toast = center.current, let proposal = toast.proposal {
                        ProposalCard(
                            proposal: proposal, onDecline: { center.dismiss(toast.id) },
                            onAccept: { center.acceptProposal() }
                        )
                        .padding(.horizontal, Metrics.TabBar.sideInset)
                        .padding(.bottom, Space.xs)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeOut(duration: Motion.Toast.inDuration), value: center.current)
            }
    }
}

/// The question card: the proposal in 15, then 괜찮아요 and the action as two pills on the right (boards/Added).
public struct ProposalCard: View {
    private let proposal: ToastCenter.Proposal
    private let onDecline: () -> Void
    private let onAccept: () -> Void

    public init(proposal: ToastCenter.Proposal, onDecline: @escaping () -> Void, onAccept: @escaping () -> Void) {
        self.proposal = proposal
        self.onDecline = onDecline
        self.onAccept = onAccept
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text(verbatim: proposal.message)
                .typeStyle(.secondary)
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
        .padding(.leading, Space.l)
        .padding(.trailing, Space.xs)
        .padding(.vertical, Space.s)
        .background {
            RoundedRectangle(cornerRadius: Metrics.NextUpBar.radius)
                .fill(.regularMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: Metrics.NextUpBar.radius)
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
