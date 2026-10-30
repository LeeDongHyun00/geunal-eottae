package app.geunal.feature.account

import app.geunal.core.AuthResult
import app.geunal.core.CoreException
import app.geunal.core.Provider
import app.geunal.core.Session
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.onboarding.LegalLinks
import app.geunal.feature.account.onboarding.OnboardingDependencies
import app.geunal.feature.account.onboarding.OnboardingRoute
import app.geunal.feature.account.onboarding.OnboardingViewModel
import app.geunal.feature.account.providers.ProviderCredential
import app.geunal.feature.account.providers.ProviderSignIn
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.restore.RestoreModel
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeDeviceAuth
import app.geunal.feature.account.testing.FakePermissions
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()
    private val onboarding by lazy { OnboardingState(folder.newFolder("onboarding")) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(providers: ProviderSignIn = FakeProviders()) =
        OnboardingViewModel(
            OnboardingDependencies(
                core = core,
                providers = providers,
                permissions = FakePermissions(),
                deviceAuth = FakeDeviceAuth(),
                isOnline = MutableStateFlow(true),
                toasts = ToastCenter(backgroundScope),
                state = onboarding,
                channel = BuildChannel.ALPHA,
                legal = LegalLinks(),
            ),
            io = dispatcher,
        )

    private fun TestScope.signedIn(
        provider: Provider = Provider.KAKAO,
        providers: ProviderSignIn = FakeProviders(),
    ): OnboardingViewModel {
        val model = viewModel(providers)
        model.openSignIn()
        model.signIn.setAgeConfirmed(true)
        model.signIn(provider)
        advanceUntilIdle()
        return model
    }

    private fun replacementTicket() {
        core.authResult =
            Result.success(AuthResult.ReplaceRequired("u1", "ios", Instant.parse("2026-10-01T00:00:00Z"), recovery = true))
    }

    @Test
    fun leavesOnlyThePermissionsOnceAGuestStarts() =
        runTest(dispatcher) {
            val model = viewModel()

            model.openSignIn()
            model.signIn.setAgeConfirmed(true)
            model.continueAsGuest()
            advanceUntilIdle()

            // The account is set: back must not reach the sign-in again (m1.md decision 30).
            assertEquals(listOf(OnboardingRoute.Permissions), model.backStack.toList())
        }

    @Test
    fun leavesOnlyThePermissionsAfterANewAccountSignsIn() =
        runTest(dispatcher) {
            val model = signedIn()

            assertEquals(listOf(OnboardingRoute.Permissions), model.backStack.toList())
        }

    @Test
    fun opensTheDeviceChangeWhenAnotherDeviceHasTheAccount() =
        runTest(dispatcher) {
            replacementTicket()

            val model = signedIn()

            assertEquals(RestoreMode.DEVICE_CHANGE, (model.backStack.last() as OnboardingRoute.Restore).mode)
            assertEquals(RestoreMode.DEVICE_CHANGE, model.restore?.mode)
        }

    @Test
    fun leavesOnlyThePermissionsAfterAFreshStart() =
        runTest(dispatcher) {
            replacementTicket()
            val model = signedIn()
            val restore = checkNotNull(model.restore)

            restore.openNoCode()
            restore.startOver()
            advanceUntilIdle()
            restore.confirmWipe()
            advanceUntilIdle()

            assertEquals(listOf(OnboardingRoute.Permissions), model.backStack.toList())
            assertNull(model.restore)
        }

    @Test
    fun leavesOnlyThePermissionsWhenTheCodeComesLater() =
        runTest(dispatcher) {
            core.authResult = Result.success(AuthResult.Registered(Session("u1", "d1"), newAccount = false, recovery = true))
            val model = signedIn()
            val restore = checkNotNull(model.restore)
            assertEquals(RestoreMode.OFFER, restore.mode)

            restore.openNoCode()
            restore.later()
            advanceUntilIdle()

            assertEquals(listOf(OnboardingRoute.Permissions), model.backStack.toList())
        }

    @Test
    fun keepsOnlyTheFinishedRestore() =
        runTest(dispatcher) {
            replacementTicket()
            val model = signedIn()
            val route = model.backStack.last()
            val restore = checkNotNull(model.restore)

            restore.paste("바람 연필 구름 달력 열쇠 우산 단추 마당 편지 소풍 거울 하늘")
            advanceUntilIdle()
            restore.start()
            advanceUntilIdle()

            // "돌아왔어요" stays, with nothing behind it; its model goes on drawing the result.
            assertEquals(listOf(route), model.backStack.toList())
            assertTrue(restore.state.value.phase is RestoreModel.Phase.Finished)
            assertEquals(restore, model.restore)
        }

    @Test
    fun goesBackToTheSignInWhenTheTicketRunsOut() =
        runTest(dispatcher) {
            replacementTicket()
            core.restoreResult = Result.failure(CoreException.SignInAgain())
            val model = signedIn()
            val restore = checkNotNull(model.restore)

            restore.paste("바람 연필 구름 달력 열쇠 우산 단추 마당 편지 소풍 거울 하늘")
            advanceUntilIdle()
            restore.start()
            advanceUntilIdle()

            assertEquals(listOf(OnboardingRoute.Welcome, OnboardingRoute.SignIn), model.backStack.toList())
            assertEquals(AccountNotice.TICKET_EXPIRED, model.noticeFlow.first())
        }

    @Test
    fun dropsTheRestoreModelWithItsScreen() =
        runTest(dispatcher) {
            val model = viewModel()
            model.openSignIn()
            model.returnAsGuest()
            assertNotNull(model.restore)

            model.back()

            assertEquals(listOf(OnboardingRoute.Welcome, OnboardingRoute.SignIn), model.backStack.toList())
            assertNull(model.restore)
        }

    @Test
    fun goesNowhereWhenASignInEndsAfterThePersonLeft() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<ProviderCredential>()
            val slow =
                object : ProviderSignIn {
                    override suspend fun kakao(providerNonce: String) = gate.await()

                    override suspend fun apple(providerNonce: String) = gate.await()
                }
            val model = signedIn(providers = slow)

            model.back()
            gate.complete(ProviderCredential(idToken = "token"))
            advanceUntilIdle()

            assertEquals(listOf(OnboardingRoute.Welcome), model.backStack.toList())
        }

    @Test
    fun opensOneSignInScreenForTwoTaps() =
        runTest(dispatcher) {
            val model = viewModel()

            model.openSignIn()
            model.openSignIn()

            assertEquals(listOf(OnboardingRoute.Welcome, OnboardingRoute.SignIn), model.backStack.toList())
        }

    @Test
    fun keepsTheWelcomeScreenOnBack() =
        runTest(dispatcher) {
            val model = viewModel()

            model.back()

            assertEquals(listOf(OnboardingRoute.Welcome), model.backStack.toList())
        }

    @Test
    fun remembersThatOnboardingFinished() =
        runTest(dispatcher) {
            val model = viewModel()

            model.finish()

            assertTrue(onboarding.isFinished)
        }
}
