import Foundation
import GeunalCore
import Observation

/// Drives the M0 screen: opens the core, reads the SQLCipher version and runs one sync, reporting each step. It knows
/// the core only through `CoreProtocol`, so tests hand it a fake (docs/parts/ios.md §3).
@MainActor
@Observable
final class CoreStatusModel {
    enum Step: Equatable {
        case waiting
        case running
        case done(String)
        case failed(String)

        /// What the screen shows for the step.
        var text: String {
            switch self {
            case .waiting: String(localized: "대기")
            case .running: String(localized: "진행 중")
            case .done(let detail): detail
            case .failed(let reason): String(localized: "실패: \(reason)")
            }
        }
    }

    private(set) var open: Step = .waiting
    private(set) var cipher: Step = .waiting
    private(set) var sync: Step = .waiting
    private(set) var isRunning = false

    private let openCore: @Sendable () throws -> any CoreProtocol

    init(openCore: @escaping @Sendable () throws -> any CoreProtocol) {
        self.openCore = openCore
    }

    /// Runs the three steps in order and stops at the first failure.
    func start() async {
        guard !isRunning else { return }
        isRunning = true
        defer { isRunning = false }
        open = .running
        cipher = .waiting
        sync = .waiting

        // The core's synchronous calls stay off the main thread (docs/parts/ios.md §3).
        let openCore = openCore
        let opened = await Task.detached {
            Result { try openCore() }
        }.value
        let core: any CoreProtocol
        switch opened {
        case .success(let value):
            core = value
            open = .done(String(localized: "완료"))
        case .failure(let error):
            open = .failed(Self.describe(error))
            return
        }

        cipher = .running
        let version = await Task.detached {
            Result { try core.cipherVersion() }
        }.value
        switch version {
        case .success(let value):
            cipher = .done(value)
        case .failure(let error):
            cipher = .failed(Self.describe(error))
            return
        }

        sync = .running
        do {
            try await core.sync()
            sync = .done(String(localized: "완료"))
        } catch {
            sync = .failed(Self.describe(error))
        }
    }

    /// A short reason for the screen. The core's errors carry their own reason; anything else falls back to the
    /// system description.
    static func describe(_ error: any Error) -> String {
        switch error {
        case CoreError.Store(let reason):
            String(localized: "저장소: \(reason)")
        case CoreError.SecretStore(let reason):
            String(localized: "비밀 보관: \(reason)")
        default:
            error.localizedDescription
        }
    }
}
