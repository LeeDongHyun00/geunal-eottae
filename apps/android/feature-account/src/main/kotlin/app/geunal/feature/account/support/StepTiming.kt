package app.geunal.feature.account.support

import android.os.Debug
import android.os.Process
import android.os.SystemClock

/**
 * How long each step of a sign-in takes, for finding where a slow one spends its time. Debug builds pass a [log]; release
 * builds use [OFF], which runs the step and measures nothing.
 *
 * A line holds only the flow and step names and numbers: never a token, a body, an account or device id, or any text
 * the person typed (docs/security/app-review.md §5).
 */
class StepTiming(
    private val log: ((String) -> Unit)? = null,
    private val wallMs: () -> Long = SystemClock::elapsedRealtime,
    /** The whole process, every thread: the core's work runs on threads other than the caller's. */
    private val cpuMs: () -> Long = Process::getElapsedCpuTime,
    /** Rust's allocations live on the native heap. Before and after only: a step's peak is not seen. */
    private val nativeHeapKb: () -> Long = { Debug.getNativeHeapAllocatedSize() / 1024 },
) {
    suspend fun <T> measure(
        flow: String,
        step: String,
        block: suspend () -> T,
    ): T {
        val log = log ?: return block()
        val wall = wallMs()
        val cpu = cpuMs()
        val heap = nativeHeapKb()
        var outcome = "ok"
        try {
            return block()
        } catch (e: Throwable) {
            // The kind only; a message can carry what the server said.
            outcome = e.javaClass.simpleName
            throw e
        } finally {
            log("$flow/$step $outcome wall=${wallMs() - wall}ms cpu=${cpuMs() - cpu}ms nativeHeap=${heap}KB→${nativeHeapKb()}KB")
        }
    }

    companion object {
        val OFF = StepTiming()

        const val LOG_TAG = "GeunalTiming"
    }
}
