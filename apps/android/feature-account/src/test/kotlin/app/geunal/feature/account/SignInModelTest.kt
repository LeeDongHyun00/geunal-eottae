package app.geunal.feature.account

import app.geunal.core.AuthResult
import app.geunal.core.CoreException
import app.geunal.core.Provider
import app.geunal.core.Session
import app.geunal.feature.account.onboarding.PendingGuest
import app.geunal.feature.account.onboarding.SignInModel
import app.geunal.feature.account.providers.ProviderCredential
import app.geunal.feature.account.providers.ProviderSignInException
import app.geunal.feature.account.restore.RestoreMode
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.OnboardingState
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class SignInModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val ticked = Instant.ofEpochSecond(1_793_000_000)
    private val core = AccountFakeCore()
    private val providers = FakeProviders()
    private val notices = mutableListOf<AccountNotice>()
    private val onboarding by lazy { OnboardingState(folder.newFolder("onboarding")) }

    private fun model() = SignInModel(core, providers, onboarding, notify = { notices += it }, now = { ticked })

    @Test
    fun doesNothingUntilTheAgeBoxIsTicked() =
        runTest {
            val outcome = model().signIn(Provider.KAKAO, isOnline = true)

            assertNull(outcome)
            assertEquals(emptyList<String>(), core.calls)
        }

    @Test
    fun keepsOnlyTheTimeTheBoxWasTicked() {
        val model = model()

        model.setAgeConfirmed(true)
        assertEquals(ticked, model.state.value.ageConfirmedAt)
        model.setAgeConfirmed(false)
        assertNull(model.state.value.ageConfirmedAt)
    }

    @Test
    fun handsTheProviderNonceToKakaoAndTheNonceToTheCore() =
        runTest {
            val model = model().apply { setAgeConfirmed(true) }

            val outcome = model.signIn(Provider.KAKAO, isOnline = true)

            assertEquals(SignInModel.Outcome.Permissions, outcome)
            assertEquals(listOf("p1"), providers.nonces)
            assertEquals(listOf("nonce", "kakao n1 token"), core.calls)
            assertEquals("하린", core.lastAccount?.displayName)
            assertEquals(ticked, core.lastAccount?.ageConfirmedAt)
            assertNull(model.state.value.busy)
        }

    @Test
    fun goesToTheDeviceChangeWhenAnotherDeviceIsActive() =
        runTest {
            core.authResult =
                Result.success(
                    AuthResult.ReplaceRequired(
                        userId = "u1",
                        activePlatform = "ios",
                        activeSince = Instant.parse("2026-10-01T00:00:00Z"),
                        recovery = true,
                    ),
                )
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Restore(RestoreMode.DEVICE_CHANGE), model.signIn(Provider.APPLE, isOnline = true))
        }

    @Test
    fun offersTheBackupOfAnExistingAccount() =
        runTest {
            core.authResult = Result.success(AuthResult.Registered(Session("u1", "d1"), newAccount = false, recovery = true))
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Restore(RestoreMode.OFFER), model.signIn(Provider.KAKAO, isOnline = true))
        }

    @Test
    fun goesOnToThePermissionsForAnExistingAccountWithoutRecoveryMaterial() =
        runTest {
            core.authResult = Result.success(AuthResult.Registered(Session("u1", "d1"), newAccount = false, recovery = false))
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Permissions, model.signIn(Provider.KAKAO, isOnline = true))
        }

    @Test
    fun staysQuietWhenThePersonCancels() =
        runTest {
            providers.result = Result.failure(ProviderSignInException.Cancelled())
            val model = model().apply { setAgeConfirmed(true) }

            assertNull(model.signIn(Provider.KAKAO, isOnline = true))
            assertEquals(emptyList<AccountNotice>(), notices)
        }

    @Test
    fun saysTheSignInFailed() =
        runTest {
            core.authResult = Result.failure(CoreException.Api(401u, "unauthenticated", ""))
            val model = model().apply { setAgeConfirmed(true) }

            assertNull(model.signIn(Provider.KAKAO, isOnline = true))
            assertEquals(listOf(AccountNotice.SIGN_IN_FAILED), notices)
            assertNull(model.state.value.busy)
        }

    @Test
    fun doesNotCallAProviderOffline() =
        runTest {
            val model = model().apply { setAgeConfirmed(true) }

            assertNull(model.signIn(Provider.APPLE, isOnline = false))
            assertEquals(emptyList<String>(), providers.nonces)
            assertEquals(listOf(AccountNotice.OFFLINE), notices)
        }

    @Test
    fun passesAMissingProviderNameAsEmptyForTheCoreToFill() =
        runTest {
            providers.result = Result.success(ProviderCredential(idToken = "token", displayName = "  "))
            val model = model().apply { setAgeConfirmed(true) }

            model.signIn(Provider.APPLE, isOnline = true)

            assertEquals("", core.lastAccount?.displayName)
        }

    @Test
    fun makesAGuestAccountWhenOnline() =
        runTest {
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Permissions, model.continueAsGuest(isOnline = true))
            assertEquals(listOf("guest"), core.calls)
            assertEquals("", core.lastAccount?.displayName)
            assertNull(onboarding.pendingGuestAgeConfirmedAt)
        }

    @Test
    fun keepsTheTickedTimeForAGuestWhoStartsOffline() =
        runTest {
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Permissions, model.continueAsGuest(isOnline = false))
            assertEquals(emptyList<String>(), core.calls)
            assertEquals(ticked, onboarding.pendingGuestAgeConfirmedAt)
        }

    @Test
    fun keepsTheGuestPendingWhileTheCoreCannotMakeIt() =
        runTest {
            core.guestResult = Result.failure(CoreException.NotImplemented("sign_in_guest"))
            val model = model().apply { setAgeConfirmed(true) }

            assertEquals(SignInModel.Outcome.Permissions, model.continueAsGuest(isOnline = true))
            assertNotNull(onboarding.pendingGuestAgeConfirmedAt)
        }

    @Test
    fun completesAPendingGuestLater() =
        runTest {
            onboarding.pendingGuestAgeConfirmedAt = ticked

            PendingGuest.complete(core, onboarding)

            assertEquals(listOf("guest"), core.calls)
            assertEquals(ticked, core.lastAccount?.ageConfirmedAt)
            assertNull(onboarding.pendingGuestAgeConfirmedAt)
        }

    @Test
    fun dropsAPendingGuestOnceThereIsASession() =
        runTest {
            onboarding.pendingGuestAgeConfirmedAt = ticked
            core.currentSession = Session("u1", "d1")

            PendingGuest.complete(core, onboarding)

            assertEquals(emptyList<String>(), core.calls)
            assertNull(onboarding.pendingGuestAgeConfirmedAt)
        }
}
