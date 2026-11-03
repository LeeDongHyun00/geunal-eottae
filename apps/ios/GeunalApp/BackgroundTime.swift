import UIKit

/// A moment of background time for core work started in front. iOS ends an app it suspends while the app holds a
/// file lock in a shared container (0xdead10cc), and the store lives in the App Group container: a sync or a refresh
/// that is still writing when the person goes home has to finish before the app is suspended.
@MainActor
final class BackgroundTime {
    private var id = UIBackgroundTaskIdentifier.invalid

    private init() {}

    /// Runs `work`, asking iOS for time to finish it if the app leaves the front meanwhile. When that time runs out
    /// first, the request ends and iOS suspends the app as it would have anyway.
    static func finishing(_ name: String, _ work: @escaping @MainActor () async -> Void) {
        let time = BackgroundTime()
        time.id = UIApplication.shared.beginBackgroundTask(withName: name) {
            MainActor.assumeIsolated { time.end() }
        }
        Task { @MainActor in
            await work()
            time.end()
        }
    }

    private func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
