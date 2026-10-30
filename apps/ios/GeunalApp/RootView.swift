import CoreBridge
import DesignSystem
import FeatureAccount
import GeunalCore
import SwiftUI

/// Onboarding until the person reaches the calendar once, the tabs afterwards. Over the tabs come the "기기
/// 로그아웃됨" sheet and the sign-in that follows it.
struct RootView: View {
    @State private var model = AppModel()
    /// "이 기기에서 다시 로그인" waits for the sheet to go: SwiftUI drops a cover asked for while a sheet is closing.
    @State private var signInAfterSheet = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .starting:
                GeunalColor.background.ignoresSafeArea()
            case .failed(let reason):
                StartFailureView(reason: reason)
            case .onboarding:
                if let core = model.core {
                    OnboardingFlow(dependencies: dependencies(core), onFinish: { model.finishOnboarding() })
                }
            case .main:
                if let core = model.core {
                    MainTabView(model: model, core: core)
                        .sheet(item: shownRevocation, onDismiss: signInIfAsked) { item in
                            RevokedSheet(
                                revocation: item.revocation, when: MeText.time(item.revocation.at),
                                onSignIn: {
                                    signInAfterSheet = true
                                    model.revocationShown()
                                },
                                onCalendarOnly: { model.revocationShown() })
                        }
                        .fullScreenCover(isPresented: $model.signingIn) {
                            OnboardingFlow(
                                dependencies: dependencies(core), onClose: { model.signingIn = false },
                                onFinish: { model.signedInAgain() })
                        }
                }
            }
        }
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                model.appBecameActive()
            }
        }
    }

    private func dependencies(_ core: any CoreProtocol) -> OnboardingDependencies {
        OnboardingDependencies(
            core: core, providers: AppCore.providers(), permissions: DevicePermissions(), owner: model.owner,
            connectivity: model.connectivity, toasts: model.toasts, state: model.onboardingState)
    }

    /// An item rather than a flag, so the sheet keeps its words while it slides away.
    private var shownRevocation: Binding<RevocationItem?> {
        Binding(
            get: { model.revocation.map(RevocationItem.init) },
            set: { item in
                if item == nil {
                    model.revocationShown()
                }
            })
    }

    private func signInIfAsked() {
        guard signInAfterSheet else { return }
        signInAfterSheet = false
        model.signInAgain()
    }
}

private struct RevocationItem: Identifiable {
    let revocation: Revocation
    var id: Date { revocation.at }
}

/// The core could not open its store. Nothing on the device can work without it, so the screen says so plainly.
private struct StartFailureView: View {
    let reason: String

    var body: some View {
        VStack(spacing: Space.s) {
            Text("그날어때를 열 수 없어요")
                .typeStyle(.title2)
                .foregroundStyle(GeunalColor.ink)
            Text("앱을 다시 열어 주세요. 계속 안 되면 운영자에게 알려 주세요.")
                .typeStyle(.secondary)
                .multilineTextAlignment(.center)
                .foregroundStyle(GeunalColor.secondaryText)
            Text(verbatim: reason)
                .typeStyle(.caption)
                .foregroundStyle(GeunalColor.tertiaryText)
                .accessibilityIdentifier("start.failure")
        }
        .padding(Space.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(GeunalColor.background)
    }
}
