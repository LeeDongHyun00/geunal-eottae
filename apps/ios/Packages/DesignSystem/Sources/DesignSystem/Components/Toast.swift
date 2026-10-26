import Observation
import SwiftUI

/// The one toast at a time that sits where the next-up bar is (components.md §6, m1.md screen 6): "카페 마감을 추가했어요
/// · 되돌리기". It replaces confirmation dialogs, so it lasts five seconds and pressing the action closes it at once.
@MainActor
@Observable
public final class ToastCenter {
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

    public private(set) var current: Toast?
    @ObservationIgnored private var action: (@MainActor () -> Void)?
    @ObservationIgnored private var dismissal: Task<Void, Never>?
    private let duration: Duration

    public init(duration: Duration = .milliseconds(Int(Metrics.Toast.duration * 1000))) {
        self.duration = duration
    }

    /// Shows `message`, replacing any toast on screen. `action` runs when its title is pressed.
    public func show(_ message: String, actionTitle: String? = nil, action: (@MainActor () -> Void)? = nil) {
        dismissal?.cancel()
        let toast = Toast(message: message, actionTitle: actionTitle)
        current = toast
        self.action = action
        dismissal = Task { [weak self, duration] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.dismiss(toast.id)
        }
    }

    public func performAction() {
        let action = self.action
        dismiss()
        action?()
    }

    /// Closes the toast; with `id`, only if that toast is still the one on screen.
    public func dismiss(_ id: UUID? = nil) {
        guard let current, id == nil || current.id == id else { return }
        dismissal?.cancel()
        dismissal = nil
        action = nil
        self.current = nil
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
