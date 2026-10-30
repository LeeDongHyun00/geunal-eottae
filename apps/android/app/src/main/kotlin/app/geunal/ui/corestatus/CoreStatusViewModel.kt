package app.geunal.ui.corestatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.Change
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One step of bringing the core up, as the screen shows it. */
sealed interface Step {
    data object Pending : Step

    data object Done : Step

    /** The failure's kind (`Network`, `Store`, …), never the core's message (docs/security/app-review.md §5). */
    data class Failed(
        val kind: String,
    ) : Step
}

data class CoreStatus(
    val cipherVersion: String? = null,
    val sync: Step = Step.Pending,
    val changes: Int = 0,
)

/**
 * The M0 check, kept on the calendar tab until the month view replaces it (docs/design/m1.md screen 4): which SQLCipher
 * the open core runs, how one sync went, and how many change notifications arrived since.
 */
class CoreStatusViewModel(
    private val core: CoreInterface,
    changes: Flow<Change>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _status = MutableStateFlow(CoreStatus())
    val status: StateFlow<CoreStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            changes.collect { _status.update { it.copy(changes = it.changes + 1) } }
        }
        viewModelScope.launch { check() }
    }

    private suspend fun check() {
        val cipherVersion = withContext(io) { core.cipherVersion() }
        _status.update { it.copy(cipherVersion = cipherVersion) }
        val sync =
            try {
                core.sync()
                Step.Done
            } catch (e: CoreException) {
                Step.Failed(e.javaClass.simpleName)
            } catch (e: InternalException) {
                Step.Failed(e.javaClass.simpleName)
            }
        _status.update { it.copy(sync = sync) }
    }
}
