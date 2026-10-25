package app.geunal.bridge

import app.geunal.core.Change
import app.geunal.core.CoreListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The core's `CoreListener.onChange` as a [Flow] (docs/parts/android.md §2).
 *
 * `Core.open` takes the listener up front and keeps it for the core's lifetime, so this is a shared flow a view model
 * collects rather than a `callbackFlow` that registers on collection. The core calls [onChange] from its own threads;
 * `tryEmit` is thread-safe and the buffer keeps a burst of changes while the collector is busy redrawing.
 */
class ChangeListener : CoreListener {
    private val _changes =
        MutableSharedFlow<Change>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    override fun onChange(change: Change) {
        _changes.tryEmit(change)
    }

    private companion object {
        // Changes only say which screen to redraw, so dropping the oldest of a burst loses nothing a redraw would not catch up on.
        const val BUFFER = 64
    }
}
