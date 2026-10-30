package app.geunal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.Change
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.onboarding.PendingGuest
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.calendar.nextup.NextUpModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the root shows. */
sealed interface AppPhase {
    data object Starting : AppPhase

    /** The core did not open. Nothing on the device works without it, so the screen says so and offers a retry. */
    data class Failed(
        val reason: StartFailure,
    ) : AppPhase

    data object Onboarding : AppPhase

    data object Main : AppPhase
}

/** Why the core did not open, by kind only: its messages are never shown (docs/security/app-review.md §5 "코어 오류"). */
enum class StartFailure { STORE, SECRETS, OTHER }

/**
 * The app's state above the screens: opens the core once, decides between onboarding and the tabs, and keeps what every
 * tab shares (the toast and the next-up bar). It depends only on the UniFFI interface, so tests hand it a fake.
 */
class AppViewModel(
    private val openCore: () -> CoreInterface,
    private val onboarding: OnboardingState,
    private val changes: Flow<Change>,
    private val isOnline: StateFlow<Boolean>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val toasts = ToastCenter(viewModelScope)

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    /** Set once the phase leaves [AppPhase.Starting] for onboarding or the tabs. */
    var core: CoreInterface? = null
        private set
    var nextUp: NextUpModel? = null
        private set
    private var foregroundWaiting = false

    init {
        start()
    }

    /** Opens the core; "다시 시도" on the failure screen calls it again. */
    fun start() {
        if (core != null) return
        _phase.value = AppPhase.Starting
        viewModelScope.launch {
            val opened =
                try {
                    withContext(io) { openCore() }
                } catch (e: CoreException) {
                    _phase.value = AppPhase.Failed(if (e is CoreException.SecretStore) StartFailure.SECRETS else StartFailure.STORE)
                    return@launch
                } catch (e: InternalException) {
                    // A Rust panic, which UniFFI turns into this. A SecretStore callback that throws arrives as
                    // CoreException.SecretStore instead.
                    _phase.value = AppPhase.Failed(StartFailure.OTHER)
                    return@launch
                }
            val upNext = NextUpModel(opened, io = io)
            core = opened
            nextUp = upNext
            launch {
                changes.collect { if (it == Change.Calendar) upNext.refresh() }
            }
            _phase.value = if (onboarding.isFinished) AppPhase.Main else AppPhase.Onboarding
            if (foregroundWaiting) appBecameActive()
        }
    }

    fun finishOnboarding() {
        _phase.value = AppPhase.Main
        viewModelScope.launch { nextUp?.refresh() }
    }

    /**
     * Each time the app comes to the front: the day's session count for the alpha statistics (protocol §14), the guest
     * account an offline start still owes (m1.md decision 16), and a fresh next-up bar.
     */
    fun appBecameActive() {
        val core = core
        if (core == null) {
            // The app came to the front before the core opened; it counts once the core is there.
            foregroundWaiting = true
            return
        }
        foregroundWaiting = false
        viewModelScope.launch {
            withContext(io) {
                try {
                    core.recordAppForeground()
                } catch (e: CoreException) {
                    // Counted only while the statistics are on; the core says when it cannot, and nothing depends on it.
                }
                if (isOnline.value) PendingGuest.complete(core, onboarding)
            }
            nextUp?.refresh()
        }
    }

    override fun onCleared() {
        // The generated Core frees its Rust object on close; a fake in tests has nothing to free.
        (core as? AutoCloseable)?.close()
        core = null
    }
}
