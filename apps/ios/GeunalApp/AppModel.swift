import CoreBridge
import DesignSystem
import FeatureAccount
import FeatureCalendar
import Foundation
import GeunalCore
import Observation

/// The app's state above the screens: open the core once, decide between onboarding and the tabs, and keep what every
/// tab shares (the toast, the next-up bar, connectivity, the account's state).
@MainActor
@Observable
final class AppModel {
    /// One per process: the scene's screens and the system's background tasks and notification taps share it.
    static let shared = AppModel()

    enum Phase {
        case starting
        case failed(StartFailure)
        case onboarding
        case main
    }

    private(set) var phase: Phase = .starting
    private(set) var core: (any CoreProtocol)?
    private(set) var nextUp: NextUpModel?
    /// The 알림 screen's switch for the bar above the tab bar (m1.md screen 19).
    private(set) var showsNextUpBar = true
    /// Counts the core's calendar changes, so the calendar views know to read again.
    private(set) var calendarChanges = 0
    /// Counts the account's changes (a sign-in, a link, a revocation, a deletion), so 나 reads its card again.
    private(set) var accountChanges = 0
    /// Another device's sign-in signed this one out, and the sheet has not shown it yet (m1.md screen 23).
    private(set) var revocation: Revocation?
    /// The recovery code is not written down yet: the month view's banner (m1.md screen 4).
    private(set) var recoveryUnsaved = false
    /// "나중에 넣을게요" left the account's backup on the server without its code on this device: the banner and 나 ask
    /// for the code, and backups wait (m1.md screens 4, 12, 20).
    private(set) var recoveryPending = false
    /// "백업 가져오기" over the tabs, from the banner or 나.
    var enteringRecoveryCode = false
    /// Another account's events, asked about over the tabs when a guest who started offline gets the account later
    /// (m1.md screen 24); onboarding asks its own.
    private(set) var localData: LocalDataQuestion?
    /// The sign-in screen over the tabs, after "이 기기에서 다시 로그인" or 나's "로그인".
    var signingIn = false
    /// The account was decided before the app last closed, on the permissions screen: onboarding goes on there
    /// instead of at the sign-in (m1.md decision 30).
    private(set) var onboardingResumes = false
    /// A tapped alert waiting for the tabs (m1.md §2.6).
    private(set) var route: AlertRoute?
    /// The phone's calendars mirrored into the core (m1.md screen 15's second group).
    private(set) var mirror: DeviceCalendarMirror?
    let toasts = AppModel.makeToasts()
    let connectivity = ConnectivityMonitor()
    @ObservationIgnored let owner = AppCore.deviceOwner()
    @ObservationIgnored let onboardingState = OnboardingState()
    @ObservationIgnored private var changes: Task<Void, Never>?
    @ObservationIgnored private var alerts: AlertScheduler?
    @ObservationIgnored private let deviceCalendars = AppCore.deviceCalendars()
    @ObservationIgnored private var opening: Task<Void, Never>?
    @ObservationIgnored private var startedInFront = false
    /// MetricKit's crash counts that came before the core opened.
    @ObservationIgnored private var pendingCrashes: [String: UInt32] = [:]
    /// A background task learned the account was deleted: the toast waits for someone to open the app.
    @ObservationIgnored private var deletionUnannounced = false

    /// The scene's start: the core, then what coming to the front does, once.
    func start() async {
        await prepare()
        guard !startedInFront else { return }
        startedInFront = true
        appBecameActive()
    }

    /// Opens the core once, for whoever asks first: the scene, or a background task that launched the app without one.
    private func prepare() async {
        if let opening {
            await opening.value
            return
        }
        let task = Task { await open() }
        opening = task
        await task.value
    }

    private func open() async {
        #if DEBUG
            LaunchOptions.resetIfAsked()
        #endif
        do {
            try AppCore.forgetPreviousInstallation(onboarding: onboardingState)
            #if DEBUG
                LaunchOptions.skipOnboardingIfAsked(onboardingState)
                try LaunchOptions.failFirstOpenIfAsked()
            #endif
            let core = try await Task.detached { try AppCore.open() }.value
            #if DEBUG
                if LaunchOptions.seedsDemoCalendar {
                    try await Task.detached { try DemoCalendar.seed(core) }.value
                }
                if LaunchOptions.seedsBusyDay {
                    try await Task.detached { try DemoCalendar.seedBusyDay(core) }.value
                }
                if LaunchOptions.seedsDemoPeople {
                    try await Task.detached { try DemoCalendar.seedPeople(core) }.value
                }
            #endif
            self.core = core
            nextUp = NextUpModel(core: core)
            let toasts = self.toasts
            localData = LocalDataQuestion(core: core, notify: { toasts.show($0) })
            listen(to: core)
            onboardingResumes =
                !onboardingState.isFinished
                && (core.session() != nil || onboardingState.pendingGuestAgeConfirmedAt != nil)
            alerts = AlertScheduler(core: core, center: AppCore.alertCenter())
            let mirror = DeviceCalendarMirror(core: core, source: deviceCalendars)
            self.mirror = mirror
            deviceCalendars.observeChanges {
                Task { await mirror.refresh() }
            }
            recordPendingCrashes()
            phase = onboardingState.isFinished ? .main : .onboarding
        } catch {
            phase = .failed(StartFailure(error))
            // A background task can wake the app before the first unlock, when neither the keychain nor the store
            // opens; the next start tries again instead of keeping the failure.
            opening = nil
        }
    }

    /// "다시 시도" on the start failure screen (m1.md §4 "시작 오류"): opens the core again and, once it opens, does what
    /// coming to the front does.
    func retryStart() async {
        guard case .failed = phase else { return }
        await prepare()
        if core != nil {
            appBecameActive()
        }
    }

    func finishOnboarding() {
        phase = .main
        accountChanges += 1
        Task {
            await nextUp?.refresh()
            await refreshAccount()
            // The permissions screen may just have allowed alerts.
            await alerts?.refresh()
        }
    }

    /// Each time the app comes to the front: the day's session count for the alpha statistics (protocol §14), the
    /// guest account an offline start still owes, a sync, a fresh next-up bar, and the account's state, so a
    /// revocation that came while the app was closed shows on this open.
    func appBecameActive() {
        guard let core else { return }
        try? core.recordAppForeground()
        if deletionUnannounced {
            deletionUnannounced = false
            toasts.show(String(localized: "계정을 삭제했어요"))
        }
        let online = connectivity.isOnline
        let state = onboardingState
        Task {
            if online {
                await PendingGuest.complete(core: core, state: state)
                // The sync finishes what a sign-in left undone (the account, key packages, the recovery material) and
                // once a day fetches the holidays and sends the alpha statistics; what it changes arrives through the
                // listener. On its own task, so a slow network does not hold the next-up bar back.
                Task {
                    do {
                        try await core.sync()
                    } catch let error as CoreError where error.isAccountDeleted {
                        // Deleted while this device had no answer, or before the app last closed: the core has
                        // finished as after a 204, and the app ends the same way (m1.md decision 36).
                        self.accountDeleted()
                    } catch {}
                }
            }
            // The core backs nothing up while another account's events wait for an answer.
            if case .main = phase {
                await localData?.ask {}
            }
            await nextUp?.refresh()
            await refreshSettings()
            await refreshAccount()
            // The phone's calendars may have changed while the app was away; the alerts move on with the clock.
            await mirror?.refresh()
            await alerts?.refresh()
        }
    }

    /// A background refresh (docs/parts/ios.md §5): the phone's calendars, a sync and the nearest alerts. It counts
    /// no session, since nobody opened the app.
    func refreshInBackground() async {
        await prepare()
        guard let core else { return }
        await mirror?.refresh()
        if !Task.isCancelled {
            do {
                try await core.sync()
            } catch let error as CoreError where error.isAccountDeleted {
                accountDeletedInBackground()
            } catch {}
        }
        if !Task.isCancelled {
            await alerts?.refresh()
        }
    }

    /// The daily task on an unmetered network (rust-core.md §5 "백업"): the core backs up only when automatic backup is
    /// on and something changed since the last one.
    func backUpInBackground() async {
        await prepare()
        guard let core, connectivity.isUnmetered else { return }
        do {
            _ = try await core.autoBackup()
        } catch let error as CoreError where error.isAccountDeleted {
            accountDeletedInBackground()
        } catch {}
    }

    /// A background call learned the account is gone (m1.md decision 36): the app ends as after a deletion, and says so
    /// when it is next opened.
    private func accountDeletedInBackground() {
        accountDeleted(announce: false)
        deletionUnannounced = true
    }

    /// The system's notification tap; the tabs take it when they are there.
    func open(_ route: AlertRoute) {
        self.route = route
    }

    func routeTaken() {
        route = nil
    }

    /// MetricKit's crashes by Korean day, which the core adds to the alpha statistics while they are on (protocol §14).
    func recordCrashes(_ counts: [String: UInt32]) {
        pendingCrashes.merge(counts, uniquingKeysWith: +)
        recordPendingCrashes()
    }

    private func recordPendingCrashes() {
        guard let core, !pendingCrashes.isEmpty else { return }
        for (day, count) in pendingCrashes {
            try? core.recordCrashes(day: day, count: count)
        }
        pendingCrashes = [:]
    }

    /// The sheet's two answers both close it for this revocation; the core keeps it until the next sign-in.
    func revocationShown() {
        if let revocation {
            onboardingState.revocationShownAt = revocation.at
        }
        revocation = nil
    }

    func signInAgain() {
        signingIn = true
    }

    func enterRecoveryCode() {
        enteringRecoveryCode = true
    }

    /// The restore over the tabs closed: restored, started fresh, or "나중에" once more.
    func recoveryCodeClosed() {
        enteringRecoveryCode = false
        accountChanges += 1
        Task { await refreshAccount() }
    }

    /// The sign-in that `signInAgain()` opened came through.
    func signedInAgain() {
        signingIn = false
        accountChanges += 1
        Task { await refreshAccount() }
    }

    /// m1.md screen 22: the start screen and the toast after a deletion. The calendar stays on the device, and
    /// onboarding comes back for the next account.
    func accountDeleted(announce: Bool = true) {
        alerts?.removeAll()
        onboardingState.isFinished = false
        onboardingResumes = false
        accountChanges += 1
        recoveryUnsaved = false
        recoveryPending = false
        phase = .onboarding
        if announce {
            toasts.show(String(localized: "계정을 삭제했어요"))
        }
    }

    /// A revocation the sheet has not shown, and whether the recovery code still waits for the paper.
    func refreshAccount() async {
        guard let core else { return }
        let read = await Task.detached { (try? core.revocation(), try? core.recoveryStatus(), core.session()) }.value
        if let revoked = read.0, revoked.at != onboardingState.revocationShownAt {
            if revocation == nil {
                // Signed out from another device: the alerts with their titles go, as on a deletion.
                alerts?.removeAll()
            }
            revocation = revoked
        }
        recoveryUnsaved = read.0 == nil && read.1.map { $0.created && !$0.saved } == true
        // A signed-in device makes or receives the code at sign-in, unless the account's backup is waiting for it.
        recoveryPending = read.0 == nil && read.2 != nil && read.1.map { !$0.created } == true
    }

    /// The core reports a change of the 알림 settings as a calendar change.
    private func refreshSettings() async {
        guard let core else { return }
        if let settings = try? await Task.detached(operation: { try core.notificationSettings() }).value {
            showsNextUpBar = settings.nextUpBar
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
                switch change {
                case .calendar:
                    self?.calendarChanges += 1
                    await self?.nextUp?.refresh()
                    await self?.refreshSettings()
                    // Events, people's birthdays and the 알림 settings all decide what fires when.
                    await self?.alerts?.refresh()
                case .account:
                    self?.accountChanges += 1
                    await self?.refreshAccount()
                case .group, .chat:
                    break
                }
            }
        }
    }
}

/// Why the core did not open, by kind only (m1.md §4 "시작 오류", decision 37): the screen names the kind and keeps the
/// core's own message to itself.
enum StartFailure: Equatable {
    /// The keychain did not give the store's key.
    case key
    /// The store did not open.
    case store
    case unknown

    init(_ error: any Error) {
        switch error {
        case CoreError.SecretStore(_): self = .key
        case CoreError.Store(_): self = .store
        default: self = .unknown
        }
    }
}
