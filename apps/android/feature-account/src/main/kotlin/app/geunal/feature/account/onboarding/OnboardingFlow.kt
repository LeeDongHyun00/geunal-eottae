package app.geunal.feature.account.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.geunal.core.CoreInterface
import app.geunal.core.LocalDataChoice
import app.geunal.core.Provider
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.restore.KeepLocalSheet
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.restore.RestoreModel
import app.geunal.feature.account.restore.RestoreScreen
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.support.StepTiming
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Everything onboarding needs from the app. */
class OnboardingDependencies(
    val core: CoreInterface,
    val providers: ProviderSignIn,
    val permissions: SystemPermissions,
    val deviceAuth: DeviceAuth,
    val isOnline: StateFlow<Boolean>,
    val toasts: ToastCenter,
    val state: OnboardingState,
    val channel: BuildChannel,
    val legal: LegalLinks,
    /** Debug builds time the sign-in steps into logcat; release builds measure nothing. */
    val timing: StepTiming = StepTiming.OFF,
)

/** A screen of the first run (m1.md §2.1). */
sealed interface OnboardingRoute {
    data object Welcome : OnboardingRoute

    data object SignIn : OnboardingRoute

    data object Permissions : OnboardingRoute

    /**
     * Holds its own model, so the screen still draws it while it slides out after a pop and the words go with the entry.
     * Compared by identity: each visit is a new attempt.
     */
    class Restore internal constructor(
        val mode: RestoreMode,
        val model: RestoreModel,
        internal val scope: CoroutineScope,
    ) : OnboardingRoute {
        override fun toString(): String = "Restore($mode)@${System.identityHashCode(this)}"
    }
}

/** Where the flow opens. Past the first run it opens over the tabs, can be closed, and skips the permissions. */
enum class OnboardingStart {
    /** The first run, or after the account was deleted. */
    WELCOME,

    /** "이 기기에서 다시 로그인" (m1.md screen 23), or 나's "로그인" after a revocation or an ended session (decision 43). */
    SIGN_IN_AGAIN,

    /** 나's "다시 들어가기" after a guest's session ended: the guest's code on screen 21 (decision 43). */
    GUEST_RETURN,
}

/**
 * The first run's state, kept across configuration changes: where the person is and the screens' models. The back stack
 * is a plain list that is never saved, because a restore entry's model holds the recovery words, which stay in memory
 * only; after the process dies onboarding starts again from the welcome screen.
 */

class OnboardingViewModel(
    private val dependencies: OnboardingDependencies,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val start: OnboardingStart = OnboardingStart.WELCOME,
) : ViewModel() {
    private val notices = Channel<AccountNotice>(Channel.BUFFERED)

    /** What a toast should say; the screen turns it into text. */
    val noticeFlow: Flow<AccountNotice> = notices.receiveAsFlow()
    private val notify: (AccountNotice) -> Unit = { notices.trySend(it) }

    val backStack =
        mutableStateListOf<OnboardingRoute>(
            if (start == OnboardingStart.WELCOME) OnboardingRoute.Welcome else OnboardingRoute.SignIn,
        )

    /** Signing in again over the tabs: the device answered the permissions on its first run. */
    val signsInAgain: Boolean get() = start != OnboardingStart.WELCOME

    private val _finished = MutableStateFlow(false)

    /** Set when an account came through over the tabs and the calendar comes back. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()
    val signIn = SignInModel(dependencies.core, dependencies.providers, dependencies.state, notify, timing = dependencies.timing)
    val permissions = PermissionsModel(dependencies.core, dependencies.permissions, dependencies.channel, notify)
    val localData = LocalDataQuestion(dependencies.core, notify, io)

    init {
        if (start == OnboardingStart.GUEST_RETURN) openRestore(RestoreMode.GUEST_RETURN)
    }

    /** The model of the restore entry on the stack, if there is one. */
    val restore: RestoreModel?
        get() = backStack.filterIsInstance<OnboardingRoute.Restore>().lastOrNull()?.model

    /** From the welcome screen only: a second tap while it slides away must not stack a second sign-in screen. */
    fun openSignIn() {
        if (backStack.lastOrNull() == OnboardingRoute.Welcome) backStack.add(OnboardingRoute.SignIn)
    }

    fun signIn(provider: Provider) {
        viewModelScope.launch { signIn.signIn(provider, dependencies.isOnline.value)?.let(::go) }
    }

    fun continueAsGuest() {
        viewModelScope.launch { signIn.continueAsGuest(dependencies.isOnline.value)?.let(::go) }
    }

    fun returnAsGuest() {
        openRestore(RestoreMode.GUEST_RETURN)
    }

    fun back() {
        if (backStack.size <= 1) return
        val top = backStack.removeAt(backStack.lastIndex)
        if (top is OnboardingRoute.Restore) top.scope.cancel()
    }

    /** The person reached the calendar; later launches skip onboarding. */
    fun finish() {
        dependencies.state.isFinished = true
    }

    /** A sign-in that ends after the person left the sign-in screen goes nowhere. */
    private fun go(outcome: SignInModel.Outcome) {
        if (backStack.lastOrNull() != OnboardingRoute.SignIn) return
        when (outcome) {
            // A new account or a guest's: it is set, so the permissions come next with nothing behind them.
            SignInModel.Outcome.Permissions -> accountDecided()
            is SignInModel.Outcome.Restore -> openRestore(outcome.mode)
        }
    }

    private fun openRestore(mode: RestoreMode) {
        popRestore()
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())
        val model =
            RestoreModel(
                mode = mode,
                core = dependencies.core,
                scope = scope,
                ageConfirmedAt = signIn.state.value.ageConfirmedAt,
                notify = notify,
                deviceAuth = dependencies.deviceAuth,
                io = io,
                timing = dependencies.timing,
                providers = dependencies.providers,
                lastSignIn = dependencies.state.lastProviderSignIn,
            )
        val route = OnboardingRoute.Restore(mode, model, scope)
        backStack.add(route)
        scope.launch {
            model.state
                .map { it.phase }
                .distinctUntilChanged()
                .collect { restoreMoved(route, it) }
        }
    }

    /**
     * The account is set without a restore: another account's events on the device are settled first (m1.md screen 24),
     * then the permissions. The restore takes that answer itself before it starts.
     */
    private fun accountDecided() {
        viewModelScope.launch { localData.ask { settle(OnboardingRoute.Permissions) } }
    }

    fun answerLocalData(choice: LocalDataChoice) {
        viewModelScope.launch { localData.answer(choice) }
    }

    private fun restoreMoved(
        route: OnboardingRoute.Restore,
        phase: RestoreModel.Phase,
    ) {
        when (phase) {
            // The account is set without a restore: the permissions come next, as after a new account.
            RestoreModel.Phase.StartedFresh, RestoreModel.Phase.Later -> accountDecided()
            // "돌아왔어요" stays, alone: back from it opens the calendar.
            is RestoreModel.Phase.Finished -> settle(route)
            // The age box first, or a new sign-in after the ticket's 30 minutes.
            RestoreModel.Phase.AgeCheckNeeded, RestoreModel.Phase.TicketExpired -> popRestore()
            else -> Unit
        }
    }

    /**
     * The account is set (m1.md §2.1 "뒤로 가기"): [next] becomes the first screen. Back can no longer reach a sign-in,
     * which would put a second account on this device over the one just made or restored.
     */
    private fun settle(next: OnboardingRoute) {
        if (next == OnboardingRoute.Permissions && signsInAgain) {
            // Back over the tabs: the permissions were answered on this device's first run.
            popRestore()
            finish()
            _finished.value = true
            return
        }
        if (next !in backStack) backStack.add(next)
        val left = backStack.filter { it != next }
        backStack.removeAll(left)
        left.filterIsInstance<OnboardingRoute.Restore>().forEach { it.scope.cancel() }
    }

    private fun popRestore() {
        val restores = backStack.filterIsInstance<OnboardingRoute.Restore>()
        backStack.removeAll(restores)
        restores.forEach { it.scope.cancel() }
    }

    override fun onCleared() {
        popRestore()
    }
}

/**
 * The first run (m1.md §2.1): welcome → sign-in → permissions, or → the recovery code when the account lives on another
 * device, its backup waits on the server, or a guest comes back. [onFinish] opens the calendar. A new [round] (after
 * the account was deleted) starts over from the welcome screen instead of where the last run ended.
 *
 * With [onClose] the flow opens at [start] over the tabs and can be closed: signing in again after another device's
 * sign-in (m1.md screen 23) or after the session ended (decision 43).
 */
@Composable
fun OnboardingFlow(
    dependencies: OnboardingDependencies,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    round: Int = 0,
    start: OnboardingStart = OnboardingStart.WELCOME,
    onClose: (() -> Unit)? = null,
) {
    val model = viewModel(key = "onboarding-$start-$round") { OnboardingViewModel(dependencies, start = start) }
    val finishedOverTabs by model.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finishedOverTabs) { if (finishedOverTabs) onFinish() }
    // At the bottom of the stack, back closes the flow over the tabs, but not while a sign-in is under way: its answer
    // would land with nothing to show it.
    val signInState by model.signIn.state.collectAsStateWithLifecycle()
    val closeable = onClose != null && model.backStack.size <= 1
    BackHandler(enabled = closeable) { if (signInState.busy == null) onClose?.invoke() }
    val resources = LocalResources.current
    val isOnline by dependencies.isOnline.collectAsStateWithLifecycle()
    LaunchedEffect(model, resources) {
        model.noticeFlow.collect { dependencies.toasts.show(resources.getString(it.text)) }
    }
    val finish = {
        model.finish()
        onFinish()
    }
    Box(modifier.fillMaxSize()) {
        NavDisplay(
            backStack = model.backStack,
            onBack = model::back,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = { route ->
                NavEntry(route) {
                    when (route) {
                        OnboardingRoute.Welcome ->
                            // Invites arrive with M2; until then a code holder signs in first like everyone else.
                            WelcomeScreen(onStart = model::openSignIn, onInviteCode = model::openSignIn)
                        OnboardingRoute.SignIn ->
                            SignInScreen(
                                model = model.signIn,
                                isOnline = isOnline,
                                legal = dependencies.legal,
                                onBack = {
                                    when {
                                        !closeable -> model.back()
                                        signInState.busy == null -> onClose?.invoke()
                                    }
                                },
                                closes = onClose != null && model.backStack.size <= 1,
                                onSignIn = model::signIn,
                                onGuest = model::continueAsGuest,
                                onGuestReturn = model::returnAsGuest,
                            )
                        OnboardingRoute.Permissions ->
                            PermissionsScreen(
                                model = model.permissions,
                                permissions = dependencies.permissions,
                                onFinish = finish,
                            )
                        is OnboardingRoute.Restore -> RestoreScreen(model = route.model, onBack = model::back, onDone = finish)
                    }
                }
            },
        )
        val asking by model.localData.asking.collectAsStateWithLifecycle()
        val answering by model.localData.isWorking.collectAsStateWithLifecycle()
        asking?.let { KeepLocalSheet(it, answering, model::answerLocalData) }
        ToastHost(
            center = dependencies.toasts,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.m)
                    .padding(bottom = Space.xxxl),
        )
    }
}
