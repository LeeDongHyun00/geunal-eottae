import GeunalCore

/// The core's change callback as an `AsyncStream`, so a view model can `for await` changes on the main actor instead of
/// being called on whatever thread the core's sync engine runs (docs/parts/ios.md §3). Changes are buffered without
/// bound: they are small, and dropping one would leave a screen stale.
public final class ChangeStreamListener: CoreListener {
    public let changes: AsyncStream<Change>
    private let continuation: AsyncStream<Change>.Continuation

    public init() {
        let (stream, continuation) = AsyncStream.makeStream(of: Change.self, bufferingPolicy: .unbounded)
        changes = stream
        self.continuation = continuation
    }

    public func onChange(change: Change) {
        continuation.yield(change)
    }

    /// Ends the stream; the core holds no more references to this listener afterwards.
    public func finish() {
        continuation.finish()
    }
}
