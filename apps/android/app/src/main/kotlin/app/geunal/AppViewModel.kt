package app.geunal

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.geunal.core.Change
import app.geunal.core.CoreException
import app.geunal.core.CoreInterface
import app.geunal.core.InternalException
import app.geunal.core.LocalDataChoice
import app.geunal.core.Revocation
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.delete.isAccountDeleted
import app.geunal.feature.account.onboarding.LocalDataQuestion
import app.geunal.feature.account.onboarding.OnboardingStart
import app.geunal.feature.account.onboarding.PendingGuest
import app.geunal.feature.account.support.AccountKind
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.support.StepTiming
import app.geunal.feature.calendar.nextup.NextUpModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the root shows. */
sealed interface AppPhase {
    data object Starting : AppPhase

    /** The core did not open. Nothing on the device works without it, so the screen says so and offers a retry. */
    data class Failed(
        val reason: StartFailure,
        /** "다시 시도" is opening the core again; the screen stays up with its button saying "여는 중…". */
        val retrying: Boolean = false,
    ) : AppPhase

    data object Onboarding : AppPhase

    data object Main : AppPhase
}

/** What the app's toast says about the account; the root turns it into text. */
enum class AppNotice(
    @param:StringRes val text: Int,
) {
    ACCOUNT_DELETED(R.string.account_deleted),
    SESSION_ENDED(R.string.session_ended),
    SIGNED_IN_AGAIN(R.string.signed_in_again),
    SOMETHING_FAILED(R.string.something_failed),
}

/** Why the account is no longer signed in on this device, while the calendar goes on (m1.md screens 12, 23). */
sealed interface SignedOut {
    /** Another device signed in to the account (screen 23). */
    data object Revoked : SignedOut

    /** The core ended the session: a refused refresh token (decision 43). The way back depends on the account's kind. */
    data class SessionEnded(
        val kind: AccountKind,
    ) : SignedOut
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
    /** TalkBack and the like: a proposal card then stays until it is answered (m1.md decision 28). */
    screenReaderRunning: () -> Boolean = { false },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timing: StepTiming = StepTiming.OFF,
) : ViewModel() {
    val toasts = ToastCenter(viewModelScope, screenReaderRunning = screenReaderRunning)

    private val noticeChannel = Channel<AppNotice>(Channel.BUFFERED)

    /** Buffered until the root collects it, so a notice raised while the screens change is not lost. */
    val notices: Flow<AppNotice> = noticeChannel.receiveAsFlow()

    private val _accountEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Each time the account is gone, so what holds its screens lets them go at once. */
    val accountEnded: SharedFlow<Unit> = _accountEnded.asSharedFlow()

    /** Goes up each time onboarding has to start over from the welcome screen. */
    var onboardingRound = 0
        private set

    private val _revocation = MutableStateFlow<Revocation?>(null)

    /** Another device's sign-in signed this one out, and the sheet has not shown it yet (m1.md screen 23). */
    val revocation: StateFlow<Revocation?> = _revocation.asStateFlow()

    private val _signedOut = MutableStateFlow<SignedOut?>(null)
    val signedOut: StateFlow<SignedOut?> = _signedOut.asStateFlow()

    private val _signingIn = MutableStateFlow<OnboardingStart?>(null)

    /** The sign-in over the tabs, and where it opens; `null` while the tabs show. */
    val signingIn: StateFlow<OnboardingStart?> = _signingIn.asStateFlow()

    /** Goes up each time the sign-in over the tabs opens, so it starts afresh every time. */
    var signInRound = 0
        private set

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Starting)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    /** Set once the phase leaves [AppPhase.Starting] for onboarding or the tabs. */
    var core: CoreInterface? = null
        private set
    var nextUp: NextUpModel? = null
        private set

    /**
     * Another account's events, asked about over the tabs when a guest who started offline gets the account later
     * (m1.md screen 24). Onboarding asks its own.
     */
    var localData: LocalDataQuestion? = null
        private set
    private var foregroundWaiting = false

    /** Between [start] and the core's answer: a second tap of "다시 시도" must not open the store twice at once. */
    private var opening = false

    init {
        start()
    }

    /** Opens the core; "다시 시도" on the failure screen calls it again. */
    fun start() {
        if (core != null || opening) return
        opening = true
        // A retry keeps the failure screen, its button saying "여는 중…", rather than blanking it while the store opens.
        val current = _phase.value
        _phase.value = if (current is AppPhase.Failed) current.copy(retrying = true) else AppPhase.Starting
        viewModelScope.launch {
            val opened =
                try {
                    withContext(io) { openCore() }
                } catch (e: CoreException) {
                    failed(if (e is CoreException.SecretStore) StartFailure.SECRETS else StartFailure.STORE)
                    return@launch
                } catch (e: InternalException) {
                    // A Rust panic, which UniFFI turns into this. A SecretStore callback that throws arrives as
                    // CoreException.SecretStore instead.
                    failed(StartFailure.OTHER)
                    return@launch
                }
            opening = false
            val upNext = NextUpModel(opened, io = io)
            core = opened
            nextUp = upNext
            localData = LocalDataQuestion(opened, notify = { noticeChannel.trySend(AppNotice.SOMETHING_FAILED) }, io = io)
            launch {
                changes.collect {
                    when (it) {
                        Change.Calendar -> upNext.refresh()
                        Change.Account -> refreshAccount()
                        else -> Unit
                    }
                }
            }
            _phase.value = if (onboarding.isFinished) AppPhase.Main else AppPhase.Onboarding
            refreshAccount()
            if (foregroundWaiting) appBecameActive()
        }
    }

    private fun failed(reason: StartFailure) {
        opening = false
        _phase.value = AppPhase.Failed(reason)
    }

    /**
     * The account is gone, deleted here or found deleted by any call that reached the server (401 `account_deleted`,
     * m1.md decision 36). The core has already forgotten its secrets and kept the calendar; the app starts over at the
     * welcome screen and says so (m1.md screen 22).
     */
    fun accountDeleted() {
        onboarding.forgetAccount()
        _revocation.value = null
        _signedOut.value = null
        _signingIn.value = null
        onboardingRound++
        _phase.value = AppPhase.Onboarding
        _accountEnded.tryEmit(Unit)
        noticeChannel.trySend(AppNotice.ACCOUNT_DELETED)
    }

    fun finishOnboarding() {
        _phase.value = AppPhase.Main
        viewModelScope.launch {
            refreshAccount()
            nextUp?.refresh()
        }
    }

    /** The sheet's two answers both close it for this revocation; the core keeps it until the next sign-in. */
    fun revocationShown() {
        _revocation.value?.let { onboarding.revocationShownAt = it.at }
        _revocation.value = null
    }

    /** "이 기기에서 다시 로그인" or 나's "로그인": the sign-in screen over the tabs. */
    fun signInAgain() {
        signInRound++
        _signingIn.value = OnboardingStart.SIGN_IN_AGAIN
    }

    /** 나's "다시 들어가기" after a guest's session ended: the guest's code (decision 43). */
    fun returnAsGuest() {
        signInRound++
        _signingIn.value = OnboardingStart.GUEST_RETURN
    }

    fun signInClosed() {
        _signingIn.value = null
    }

    /** The sign-in over the tabs came through. */
    fun signedInAgain() {
        _signingIn.value = null
        viewModelScope.launch {
            refreshAccount()
            nextUp?.refresh()
        }
    }

    /**
     * Reads where the account stands: a revocation the sheet has not shown, and a session the core ended (m1.md §4
     * "로그인이 끝났을 때", decision 43). It keeps the kind of account a session was for, so the way back is known
     * after the session is gone. The toasts come once each; signing in again says so.
     */
    suspend fun refreshAccount() {
        val core = core ?: return
        val (revoked, session, account) =
            withContext(io) {
                // A store that cannot answer reads as nothing to show; the next change or foreground asks again.
                Triple(orNull { core.revocation() }, core.session(), orNull { core.account() })
            }
        // Without a session the core takes no answer about another account's events; the next sign-in asks again.
        if (session == null) localData?.drop()
        when {
            session != null -> {
                if (account != null) onboarding.knownAccount = if (account.providers.isEmpty()) AccountKind.GUEST else AccountKind.PROVIDER
                if (onboarding.sessionEnded) {
                    onboarding.sessionEnded = false
                    noticeChannel.trySend(AppNotice.SIGNED_IN_AGAIN)
                }
                _signedOut.value = null
            }
            revoked != null -> {
                _signedOut.value = SignedOut.Revoked
                if (revoked.at != onboarding.revocationShownAt && _revocation.value == null) {
                    _revocation.value = revoked
                    // The recovery code and the backup key are gone: no screen of them may stay open over the tabs.
                    _accountEnded.tryEmit(Unit)
                }
            }
            else -> {
                val known = onboarding.knownAccount
                if (known == null || _phase.value != AppPhase.Main) {
                    // Never signed in, or a guest whose account waits for a connection: nothing ended.
                    _signedOut.value = null
                    return
                }
                if (!onboarding.sessionEnded) {
                    onboarding.sessionEnded = true
                    noticeChannel.trySend(AppNotice.SESSION_ENDED)
                }
                _signedOut.value = SignedOut.SessionEnded(known)
            }
        }
    }

    /**
     * Each time the app comes to the front: the day's session count for the alpha statistics (protocol §14), the guest
     * account an offline start still owes (m1.md decision 16), a sync when online, and a fresh next-up bar. The sync also
     * finishes what a sign-in left undone, such as uploading the recovery material (protocol §10 "올리는 때").
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
            // Offline it could only fail; the next time in front with a connection tries again, as on iOS.
            if (isOnline.value) {
                try {
                    timing.measure("foreground", "sync") { core.sync() }
                } catch (e: CoreException) {
                    // Deleted while this device had no answer, or before the app last closed: the core has finished as
                    // after a 204, and the app ends the same way (m1.md decision 36). Anything else, refused or cut
                    // off: the calendar works without it, and the next time in front tries again.
                    if (e.isAccountDeleted && _phase.value == AppPhase.Main) accountDeleted()
                } catch (e: InternalException) {
                    // As above; a panic in the core must not take the app down.
                }
            }
            // The core backs nothing up while another account's events wait for an answer.
            // Not while the sign-in over the tabs is up: that flow asks its own, and two sheets would answer twice.
            if (_phase.value == AppPhase.Main && _signingIn.value == null) localData?.ask {}
            nextUp?.refresh()
            refreshAccount()
        }
    }

    fun answerLocalData(choice: LocalDataChoice) {
        viewModelScope.launch { localData?.answer(choice) }
    }

    private fun <T> orNull(read: () -> T?): T? =
        try {
            read()
        } catch (e: CoreException) {
            null
        }

    override fun onCleared() {
        // The generated Core frees its Rust object on close; a fake in tests has nothing to free.
        (core as? AutoCloseable)?.close()
        core = null
    }
}
