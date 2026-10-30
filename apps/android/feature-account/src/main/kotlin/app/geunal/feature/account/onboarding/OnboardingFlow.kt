package app.geunal.feature.account.onboarding

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
import app.geunal.core.Provider
import app.geunal.designsystem.Space
import app.geunal.designsystem.components.ToastCenter
import app.geunal.designsystem.components.ToastHost
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.restore.RestoreModel
import app.geunal.feature.account.restore.RestoreScreen
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.support.DeviceAuth
import app.geunal.feature.account.support.OnboardingState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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

/**
 * The first run's state, kept across configuration changes: where the person is and the screens' models. The back stack
 * is a plain list that is never saved, because a restore entry's model holds the recovery words, which stay in memory
 * only; after the process dies onboarding starts again from the welcome screen.
 */
class OnboardingViewModel(
    private val dependencies: OnboardingDependencies,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val notices = Channel<AccountNotice>(Channel.BUFFERED)

    /** What a toast should say; the screen turns it into text. */
    val noticeFlow: Flow<AccountNotice> = notices.receiveAsFlow()
    private val notify: (AccountNotice) -> Unit = { notices.trySend(it) }

    val backStack = mutableStateListOf<OnboardingRoute>(OnboardingRoute.Welcome)
    val signIn = SignInModel(dependencies.core, dependencies.providers, dependencies.state, notify)
    val permissions = PermissionsModel(dependencies.core, dependencies.permissions, dependencies.channel, notify)

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
            SignInModel.Outcome.Permissions -> settle(OnboardingRoute.Permissions)
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

    private fun restoreMoved(
        route: OnboardingRoute.Restore,
        phase: RestoreModel.Phase,
    ) {
        when (phase) {
            // The account is set without a restore: the permissions come next, as after a new account.
            RestoreModel.Phase.StartedFresh, RestoreModel.Phase.Later -> settle(OnboardingRoute.Permissions)
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
 * device, its backup waits on the server, or a guest comes back. [onFinish] opens the calendar.
 */
@Composable
fun OnboardingFlow(
    dependencies: OnboardingDependencies,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = viewModel { OnboardingViewModel(dependencies) }
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
                                onBack = model::back,
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
