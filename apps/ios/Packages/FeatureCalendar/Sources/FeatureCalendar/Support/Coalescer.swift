/// Runs a piece of work once for a burst of requests. Calendar changes come in bursts (a mirror, a sync, a stamped
/// month, the phone's calendar app saving twice); a request while the work runs makes it go round once more instead
/// of starting a second run, which would read state the first has not finished writing. Every caller returns once a
/// run that began after its request has finished.
@MainActor
final class Coalescer {
    private var running: Task<Void, Never>?
    private var again = false

    func run(_ work: @escaping @MainActor () async -> Void) async {
        if let running {
            again = true
            await running.value
            return
        }
        let task = Task {
            repeat {
                again = false
                await work()
            } while again
            // In the same step as the last check of `again`, so no request falls between them.
            running = nil
        }
        running = task
        await task.value
    }
}
