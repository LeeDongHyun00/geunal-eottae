package app.geunal.feature.calendar.support

import app.geunal.core.CoreException
import app.geunal.core.InternalException

/**
 * A read for a view: `null` when the core refuses or fails, and the view keeps what it showed. A panic in the core
 * reaches the app as [InternalException]; a view must not crash on one.
 */
inline fun <T> coreRead(block: () -> T): T? =
    try {
        block()
    } catch (e: CoreException) {
        null
    } catch (e: InternalException) {
        null
    }
