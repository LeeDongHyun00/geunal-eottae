import CoreBridge
import DesignSystem
import FeatureAccount
import FeatureCalendar
import Foundation
import GeunalCore
import Observation

/// The app's state above the screens: open the core once, decide between onboarding and the tabs, and keep what every
/// tab shares (the toast, the next-up bar, connectivity).
@MainActor
@Observable
final class AppModel {
    enum Phase {
        case starting
        case failed(String)
        case onboarding
        case main
    }

    private(set) var phase: Phase = .starting
    private(set) var core: (any CoreProtocol)?
    private(set) var nextUp: NextUpModel?
    let toasts = ToastCenter()
    let connectivity = ConnectivityMonitor()
    @ObservationIgnored let onboardingState = OnboardingState()
    @ObservationIgnored private var changes: Task<Void, Never>?

    func start() async {
        guard case .starting = phase else { return }
        #if DEBUG
            LaunchOptions.resetIfAsked()
        #endif
        do {
            try AppCore.forgetPreviousInstallation(onboarding: onboardingState)
            #if DEBUG
                LaunchOptions.skipOnboardingIfAsked(onboardingState)
            #endif
            let core = try await Task.detached { try AppCore.open() }.value
            self.core = core
            nextUp = NextUpModel(core: core)
            listen(to: core)
            phase = onboardingState.isFinished ? .main : .onboarding
            appBecameActive()
        } catch {
            phase = .failed(CoreStatusModel.describe(error))
        }
    }

    func finishOnboarding() {
        phase = .main
        Task { await nextUp?.refresh() }
    }

    /// Each time the app comes to the front: the day's session count for the alpha statistics (protocol §14), the
    /// guest account an offline start still owes, and a fresh next-up bar.
    func appBecameActive() {
        guard let core else { return }
        try? core.recordAppForeground()
        let online = connectivity.isOnline
        let state = onboardingState
        Task {
            if online {
                await PendingGuest.complete(core: core, state: state)
            }
            await nextUp?.refresh()
        }
    }

    private func listen(to core: any CoreProtocol) {
        changes?.cancel()
        let stream = AppCore.listener.changes
        changes = Task { [weak self] in
            for await change in stream {
                if change == .calendar {
                    await self?.nextUp?.refresh()
                }
            }
        }
    }
}
