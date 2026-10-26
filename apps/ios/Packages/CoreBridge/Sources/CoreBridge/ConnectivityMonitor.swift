import Foundation
import Network
import Observation

/// Whether the device has a usable network path, for the screens that need the server: sign-in turns the Kakao and
/// Apple buttons off while offline (m1.md screen 2), backups and deletion say "연결되면" (m1.md §4). Everything on the
/// device keeps working regardless, so nothing else reads this.
@MainActor
@Observable
public final class ConnectivityMonitor {
    public private(set) var isOnline = true
    private let monitor = NWPathMonitor()

    public init() {
        // Sendable, so it does not inherit the main actor: the monitor calls it on its own queue.
        monitor.pathUpdateHandler = { @Sendable [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                self?.isOnline = online
            }
        }
        monitor.start(queue: DispatchQueue(label: "app.geunal.connectivity"))
    }
}
