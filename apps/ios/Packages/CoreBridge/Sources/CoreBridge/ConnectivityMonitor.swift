import Foundation
import Network
import Observation

/// Whether the device has a usable network path, for the screens that need the server: sign-in turns the Kakao and
/// Apple buttons off while offline (m1.md screen 2), backups and deletion say "연결되면" (m1.md §4), and the automatic
/// backup waits for Wi-Fi. Everything on the device keeps working regardless.
@MainActor
@Observable
public final class ConnectivityMonitor {
    public private(set) var isOnline = true
    private let monitor = NWPathMonitor()

    /// A path that is neither metered nor in Low Data Mode, as Wi-Fi usually is: the daily automatic backup waits for
    /// one (rust-core.md §5 "백업"). Read from the monitor's latest path, since a background task that launched the app
    /// may ask before the first update arrives.
    public var isUnmetered: Bool {
        let path = monitor.currentPath
        return path.status == .satisfied && !path.isExpensive && !path.isConstrained
    }

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
