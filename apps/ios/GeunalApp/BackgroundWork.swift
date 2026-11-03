@preconcurrency import BackgroundTasks
import Foundation

/// The system's background time (docs/parts/ios.md §5). A refresh every so often reads the phone's calendars again,
/// syncs and refills the nearest alerts; a daily task on Wi-Fi runs the automatic backup (rust-core.md §5 "백업").
/// The system decides when either runs, if at all, so both only add to what coming to the front already does.
enum BackgroundWork {
    /// Listed under `BGTaskSchedulerPermittedIdentifiers` in Info.plist; registering any other traps.
    static let refresh = "app.geunal.ios.refresh"
    static let backup = "app.geunal.ios.backup"

    /// Before the launch finishes, as BackgroundTasks requires. The handlers come on the main queue, which the main
    /// actor they are written on expects.
    @MainActor
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refresh, using: .main) { task in
            run(task, backedUp: false) { await AppModel.shared.refreshInBackground() }
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: backup, using: .main) { task in
            run(task, backedUp: true) { await AppModel.shared.backUpInBackground() }
        }
    }

    /// When the app leaves the front, and after each run. A new request replaces the waiting one, so the backup's is
    /// sent again only after a backup ran or when none waits: resubmitting it on every refresh or trip to the
    /// background would push its 12 hours back each time, and the backup might never come.
    static func schedule(backedUp: Bool = false) async {
        let refreshing = BGAppRefreshTaskRequest(identifier: refresh)
        refreshing.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        try? BGTaskScheduler.shared.submit(refreshing)
        let waiting = await BGTaskScheduler.shared.pendingTaskRequests()
        guard backedUp || !waiting.contains(where: { $0.identifier == backup }) else { return }
        let backingUp = BGProcessingTaskRequest(identifier: backup)
        backingUp.requiresNetworkConnectivity = true
        backingUp.earliestBeginDate = Date(timeIntervalSinceNow: 12 * 60 * 60)
        try? BGTaskScheduler.shared.submit(backingUp)
    }

    /// BGTask may be told it is done from any thread; the box only carries it into the work's task.
    private struct Running: @unchecked Sendable {
        let task: BGTask
    }

    private static func run(
        _ task: BGTask, backedUp: Bool, _ work: @escaping @MainActor @Sendable () async -> Void
    ) {
        let running = Running(task: task)
        let job = Task { @MainActor in
            await work()
            await schedule(backedUp: backedUp)
            running.task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = { job.cancel() }
    }
}
