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
    /// Counts the core's calendar changes, so the calendar views know to read again.
    private(set) var calendarChanges = 0
    let toasts = AppModel.makeToasts()
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
            #if DEBUG
                if LaunchOptions.seedsDemoCalendar {
                    try await Task.detached { try DemoCalendar.seed(core) }.value
                }
            #endif
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
    /// guest account an offline start still owes, a sync, and a fresh next-up bar.
    func appBecameActive() {
        guard let core else { return }
        try? core.recordAppForeground()
        let online = connectivity.isOnline
        let state = onboardingState
        Task {
            if online {
                await PendingGuest.complete(core: core, state: state)
                // The sync finishes what a sign-in left undone (the account, key packages, the recovery material) and
                // once a day fetches the holidays and sends the alpha statistics; what it changes arrives through the
                // listener. On its own task, so a slow network does not hold the next-up bar back.
                Task { try? await core.sync() }
            }
            await nextUp?.refresh()
        }
    }

    /// Five seconds for a toast and ten for its repeat card (m1.md decision 28), except for UI tests that tap them.
    private static func makeToasts() -> ToastCenter {
        #if DEBUG
            if LaunchOptions.toastsWait {
                return ToastCenter(duration: .seconds(600), proposalDuration: .seconds(600))
            }
        #endif
        return ToastCenter()
    }

    private func listen(to core: any CoreProtocol) {
        changes?.cancel()
        let stream = AppCore.listener.changes
        changes = Task { [weak self] in
            for await change in stream {
                if change == .calendar {
                    self?.calendarChanges += 1
                    await self?.nextUp?.refresh()
                }
            }
        }
    }
}
