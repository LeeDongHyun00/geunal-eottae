package app.geunal.feature.calendar.support

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns a burst of requests into as few runs as cover them: one run at a time, and whatever is asked for while one is
 * under way makes a single run after it. Two refreshes at once would both read what is there before either wrote it.
 */
class Coalescer {
    private val running = Mutex()

    /** A run is asked for and has not started; it will see everything that changed before it starts. */
    private val waiting = AtomicBoolean(false)

    /** Runs [block] now or after the run under way. Returns at once when a waiting run already covers this request. */
    suspend fun run(block: suspend () -> Unit) {
        if (!waiting.compareAndSet(false, true)) return
        running.withLock {
            waiting.set(false)
            block()
        }
    }
}
