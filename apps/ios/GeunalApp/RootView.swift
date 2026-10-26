import CoreBridge
import DesignSystem
import FeatureAccount
import SwiftUI

/// Onboarding until the person reaches the calendar once, the tabs afterwards.
struct RootView: View {
    @State private var model = AppModel()
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
                    OnboardingFlow(
                        dependencies: OnboardingDependencies(
                            core: core, providers: AppCore.providers(), permissions: DevicePermissions(),
                            connectivity: model.connectivity, toasts: model.toasts, state: model.onboardingState),
                        onFinish: { model.finishOnboarding() })
                }
            case .main:
                if let core = model.core {
                    MainTabView(model: model, core: core)
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
