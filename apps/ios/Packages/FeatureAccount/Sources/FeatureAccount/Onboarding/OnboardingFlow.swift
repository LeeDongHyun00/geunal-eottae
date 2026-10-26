import CoreBridge
import DesignSystem
import GeunalCore
import SwiftUI

/// Everything an onboarding flow needs from the app.
@MainActor
public struct OnboardingDependencies {
    public let core: any CoreProtocol
    public let providers: any ProviderSignIn
    public let permissions: any SystemPermissions
    public let owner: any DeviceOwnerCheck
    public let connectivity: ConnectivityMonitor
    public let toasts: ToastCenter
    public let state: OnboardingState
    public let channel: BuildChannel

    public init(
        core: any CoreProtocol, providers: any ProviderSignIn, permissions: any SystemPermissions,
        owner: any DeviceOwnerCheck, connectivity: ConnectivityMonitor, toasts: ToastCenter,
        state: OnboardingState = OnboardingState(), channel: BuildChannel = .current
    ) {
        self.core = core
        self.providers = providers
        self.permissions = permissions
        self.owner = owner
        self.connectivity = connectivity
        self.toasts = toasts
        self.state = state
        self.channel = channel
    }
}

/// The first run (m1.md §2.1): welcome → sign-in → permissions, or → the recovery code when the account lives on
/// another device or is a guest's coming back. `onFinish` opens the calendar.
public struct OnboardingFlow: View {
    private enum Route: Hashable {
        case signIn
        case permissions
        case restore(RestoreModel.Mode)
    }

    private let dependencies: OnboardingDependencies
    private let onClose: (() -> Void)?
    private let onFinish: () -> Void
    @State private var path: [Route] = []
    @State private var signIn: SignInModel
    @State private var permissions: PermissionsModel

    /// With `onClose` the flow opens on the sign-in screen and can be closed: signing in again over the tabs, after
    /// another device's sign-in (m1.md screen 23) or from 나's "로그인".
    public init(
        dependencies: OnboardingDependencies, onClose: (() -> Void)? = nil, onFinish: @escaping () -> Void
    ) {
        self.dependencies = dependencies
        self.onClose = onClose
        self.onFinish = onFinish
        let toasts = dependencies.toasts
        _signIn = State(
            initialValue: SignInModel(
                core: dependencies.core, providers: dependencies.providers, state: dependencies.state,
                notify: { toasts.show($0) }))
        _permissions = State(
            initialValue: PermissionsModel(
                core: dependencies.core, permissions: dependencies.permissions, channel: dependencies.channel,
                notify: { toasts.show($0) }))
    }

    public var body: some View {
        NavigationStack(path: $path) {
            root
                .navigationDestination(for: Route.self) { route in
                    destination(route)
                }
        }
        .tint(GeunalColor.ink)
        .overlay(alignment: .bottom) {
            if let toast = dependencies.toasts.current {
                ToastView(toast) { dependencies.toasts.performAction() }
                    .padding(.horizontal, Space.m)
                    .padding(.bottom, Space.xxxl)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: Motion.Toast.inDuration), value: dependencies.toasts.current)
    }

    @ViewBuilder
    private var root: some View {
        if let onClose {
            destination(.signIn)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(action: onClose) {
                            Text("닫기", bundle: .module)
                        }
                        .accessibilityIdentifier("onboarding.close")
                    }
                }
        } else {
            WelcomeView(
                onStart: { path.append(.signIn) },
                // Invites arrive with M2; until then the code holder signs in first like everyone else.
                onInviteCode: { path.append(.signIn) }
            )
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .signIn:
            SignInView(
                model: signIn, connectivity: dependencies.connectivity,
                onOutcome: { outcome in
                    switch outcome {
                    case .permissions: path.append(.permissions)
                    case .restore(let mode): openRestore(mode)
                    }
                },
                onGuestReturn: { openRestore(.guestReturn) })
        case .permissions:
            PermissionsView(model: permissions, onFinish: finish)
        case .restore(let mode):
            // The destination makes its own model from the route: state set next to the push is not yet visible
            // to the destination SwiftUI builds for it, which then came up empty.
            let toasts = dependencies.toasts
            RestoreScreen(
                model: RestoreModel(
                    mode: mode, core: dependencies.core, owner: dependencies.owner,
                    ageConfirmedAt: signIn.ageConfirmedAt, notify: { toasts.show($0) }),
                onDone: finish,
                onStartedFresh: { path.append(.permissions) },
                // Signing in again starts on the sign-in screen, so going back is popping to the root.
                onBackToSignIn: { path = onClose == nil ? [.signIn] : [] })
        }
    }

    private func openRestore(_ mode: RestoreModel.Mode) {
        path.append(.restore(mode))
    }

    private func finish() {
        dependencies.state.isFinished = true
        onFinish()
    }
}

/// Keeps the restore model it was first given: the flow rebuilds its destinations on every change, and the words
/// typed so far must stay.
private struct RestoreScreen: View {
    @State private var model: RestoreModel
    private let onDone: () -> Void
    private let onStartedFresh: () -> Void
    private let onBackToSignIn: () -> Void

    init(
        model: RestoreModel, onDone: @escaping () -> Void, onStartedFresh: @escaping () -> Void,
        onBackToSignIn: @escaping () -> Void
    ) {
        _model = State(initialValue: model)
        self.onDone = onDone
        self.onStartedFresh = onStartedFresh
        self.onBackToSignIn = onBackToSignIn
    }

    var body: some View {
        RestoreView(model: model, onDone: onDone, onStartedFresh: onStartedFresh, onBackToSignIn: onBackToSignIn)
    }
}
