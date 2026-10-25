package app.geunal.ui.corestatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.geunal.bridge.ChangeListener
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.CoreListener
import app.geunal.core.InternalException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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

    data class Failed(
        val reason: String,
    ) : Step
}

data class CoreStatus(
    val store: Step = Step.Pending,
    val cipherVersion: String? = null,
    val sync: Step = Step.Pending,
    val changes: Int = 0,
)

/**
 * Opens the core, syncs once and reports each step. It depends only on the UniFFI interface so tests hand it a fake
 * (docs/parts/android.md §2); [openCore] is the one place the real `Core.open` is called.
 */
class CoreStatusViewModel(
    private val openCore: (CoreListener) -> CoreInterface,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val listener = ChangeListener()
    private val _status = MutableStateFlow(CoreStatus())
    val status: StateFlow<CoreStatus> = _status.asStateFlow()
    private var core: CoreInterface? = null

    init {
        viewModelScope.launch {
            listener.changes.collect { _status.update { it.copy(changes = it.changes + 1) } }
        }
        viewModelScope.launch { bringUp() }
    }

    private suspend fun bringUp() {
        val core =
            try {
                withContext(io) { openCore(listener) }
            } catch (e: CoreException) {
                _status.update { it.copy(store = Step.Failed(e.message ?: e.toString())) }
                return
            } catch (e: InternalException) {
                // A Rust panic, which UniFFI turns into this; a SecretStore callback that threw also ends up here.
                _status.update { it.copy(store = Step.Failed(e.message ?: e.toString())) }
                return
            }
        this.core = core
        val cipherVersion = withContext(io) { core.cipherVersion() }
        _status.update { it.copy(store = Step.Done, cipherVersion = cipherVersion) }
        try {
            core.sync()
            _status.update { it.copy(sync = Step.Done) }
        } catch (e: CoreException) {
            _status.update { it.copy(sync = Step.Failed(e.message ?: e.toString())) }
        }
    }

    override fun onCleared() {
        // The generated Core frees its Rust object on close; the fake in tests has nothing to free.
        (core as? AutoCloseable)?.close()
        core = null
    }

    companion object {
        fun factory(openCore: (CoreListener) -> CoreInterface): ViewModelProvider.Factory =
            viewModelFactory { initializer { CoreStatusViewModel(openCore) } }
    }
}
