package app.geunal.feature.account

import app.geunal.core.AccountView
import app.geunal.core.CoreException
import app.geunal.core.DeletionProof
import app.geunal.core.Provider
import app.geunal.feature.account.delete.DeleteAccountModel
import app.geunal.feature.account.delete.DeleteAccountModel.Problem
import app.geunal.feature.account.delete.confirmLabel
import app.geunal.feature.account.providers.ProviderCredential
import app.geunal.feature.account.providers.ProviderSignInException
import app.geunal.feature.account.support.DeviceAuthPurpose
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeDeviceAuth
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = AccountFakeCore()
    private val providers = FakeProviders()
    private val deviceAuth = FakeDeviceAuth()

    private val kakao = AccountView("u1", "하린", listOf(Provider.KAKAO))
    private val apple = AccountView("u1", "하린", listOf(Provider.APPLE))
    private val guest = AccountView("u1", "하린", emptyList())

    private fun TestScope.model(account: AccountView = guest) =
        DeleteAccountModel(core, providers, deviceAuth, account, scope = this, io = dispatcher).apply {
            openSheet()
            type(DeleteAccountModel.WORD)
        }

    private fun lostAnswer() = Result.failure<Unit>(CoreException.Network("reset"))

    @Test
    fun theButtonWaitsForTheWordTypedOut() =
        runTest(dispatcher) {
            val model = model()
            model.type("삭")
            assertFalse(model.state.value.canConfirm)

            model.delete(online = true)

            assertTrue("nothing is asked or sent", deviceAuth.asked.isEmpty() && core.deleteProofs.isEmpty())
            model.type(" 삭제 ")
            assertTrue(model.state.value.canConfirm)
        }

    @Test
    fun aGuestIsCheckedByTheOsAndSignedByTheCore() =
        runTest(dispatcher) {
            val model = model(guest)

            model.delete(online = true)

            assertEquals(listOf(DeviceAuthPurpose.DELETE_ACCOUNT), deviceAuth.asked)
            assertEquals(listOf<DeletionProof>(DeletionProof.Guest), core.deleteProofs)
            assertTrue(providers.nonces.isEmpty())
            assertTrue(model.state.value.deleted)
        }

    @Test
    fun aKakaoAccountSignsInAgainWithTheCoresNonce() =
        runTest(dispatcher) {
            val model = model(kakao)

            model.delete(online = true)

            assertEquals(listOf("p1"), providers.nonces)
            assertEquals(listOf<DeletionProof>(DeletionProof.Kakao(nonce = "n1", idToken = "token")), core.deleteProofs)
            assertTrue(model.state.value.deleted)
        }

    @Test
    fun anAppleAccountSendsTheAuthorizationCode() =
        runTest(dispatcher) {
            providers.result = Result.success(ProviderCredential(idToken = "token", authorizationCode = "code"))
            val model = model(apple)

            model.delete(online = true)

            assertEquals(listOf<DeletionProof>(DeletionProof.Apple(authorizationCode = "code")), core.deleteProofs)
        }

    @Test
    fun aCancelledOsPromptDeletesNothingAndKeepsTheWord() =
        runTest(dispatcher) {
            deviceAuth.passes = false
            val model = model(kakao)

            model.delete(online = true)

            assertTrue(core.deleteProofs.isEmpty())
            assertTrue(providers.nonces.isEmpty())
            assertEquals(DeleteAccountModel.WORD, model.state.value.typed)
            assertNull(model.state.value.problem)
        }

    @Test
    fun aCancelledProviderSignInSaysNothing() =
        runTest(dispatcher) {
            providers.result = Result.failure(ProviderSignInException.Cancelled())
            val model = model(kakao)

            model.delete(online = true)

            assertTrue(core.deleteProofs.isEmpty())
            assertNull(model.state.value.problem)
            assertFalse(model.state.value.deleted)
        }

    @Test
    fun anotherProviderAccountAsksAgainWithoutAnotherOsPrompt() =
        runTest(dispatcher) {
            core.deleteResults.clear()
            core.deleteResults += Result.failure(CoreException.ReauthRequired())
            core.deleteResults += Result.success(Unit)
            val model = model(kakao)

            model.delete(online = true)

            assertEquals(Problem.REAUTH_REQUIRED, model.state.value.problem)
            assertEquals(R.string.delete_again_kakao, confirmLabel(model.state.value, model.provider))

            model.delete(online = true)

            assertEquals("the OS asked once in this sheet", 1, deviceAuth.asked.size)
            assertEquals(2, providers.nonces.size)
            assertTrue(model.state.value.deleted)
        }

    @Test
    fun offlineSendsNothing() =
        runTest(dispatcher) {
            val model = model()

            model.delete(online = false)

            assertEquals(Problem.OFFLINE, model.state.value.problem)
            assertTrue(deviceAuth.asked.isEmpty())
        }

    @Test
    fun anAccountAlreadyDeletedEndsAsADeletion() =
        runTest(dispatcher) {
            core.deleteResults[0] = Result.failure(CoreException.Api(401u, "account_deleted", ""))
            val model = model()

            model.delete(online = true)

            assertTrue(model.state.value.deleted)
        }

    @Test
    fun aLostAnswerIsCheckedAndFoundDeleted() =
        runTest(dispatcher) {
            core.deleteResults[0] = lostAnswer()
            val model = model()

            model.delete(online = true)

            assertEquals(Problem.ANSWER_LOST, model.state.value.problem)
            assertFalse("the button waits for the check", model.state.value.canConfirm)
            assertEquals(R.string.delete_wait_connection, confirmLabel(model.state.value, null))

            core.syncResult = Result.failure(CoreException.Api(401u, "account_deleted", ""))
            model.checkLostAnswer()

            assertTrue(model.state.value.deleted)
        }

    @Test
    fun aLostAnswerWithTheAccountStillThereSendsTheFirstProofAgain() =
        runTest(dispatcher) {
            core.deleteResults.clear()
            core.deleteResults += lostAnswer()
            core.deleteResults += Result.success(Unit)
            val model = model(kakao)

            model.delete(online = true)
            model.checkLostAnswer()

            assertEquals(Problem.NOT_DELETED, model.state.value.problem)
            assertTrue(model.state.value.canConfirm)

            model.delete(online = true)

            assertEquals("no second OS prompt", 1, deviceAuth.asked.size)
            assertEquals("no second Kakao sign-in", 1, providers.nonces.size)
            assertEquals(core.deleteProofs[0], core.deleteProofs[1])
            assertTrue(model.state.value.deleted)
        }

    @Test
    fun aFirstProofAlreadyUsedAsksFromTheOsPromptOn() =
        runTest(dispatcher) {
            core.deleteResults.clear()
            core.deleteResults += lostAnswer()
            core.deleteResults += Result.failure(CoreException.ReauthRequired())
            core.deleteResults += Result.success(Unit)
            val model = model(kakao)
            model.delete(online = true)
            model.checkLostAnswer()

            model.delete(online = true)

            assertEquals(2, deviceAuth.asked.size)
            assertEquals(2, providers.nonces.size)
            assertTrue(model.state.value.deleted)
        }

    @Test
    fun anUnknownCheckStaysUnknown() =
        runTest(dispatcher) {
            core.deleteResults[0] = lostAnswer()
            val model = model()
            model.delete(online = true)

            core.syncResult = Result.failure(CoreException.Network("down"))
            model.checkLostAnswer()

            assertEquals(Problem.ANSWER_LOST, model.state.value.problem)
            assertFalse(model.state.value.deleted)
        }

    @Test
    fun closingTheSheetForgetsTheProofAndTheOsCheck() =
        runTest(dispatcher) {
            core.deleteResults.clear()
            core.deleteResults += lostAnswer()
            core.deleteResults += Result.success(Unit)
            val model = model(kakao)
            model.delete(online = true)

            model.closeSheet()

            assertNull(model.state.value.problem)
            model.openSheet()
            model.delete(online = true)
            assertEquals("asked again after the sheet closed", 2, deviceAuth.asked.size)
            assertEquals("a fresh proof", 2, providers.nonces.size)
        }

    @Test
    fun aGuestWithoutRecoveryMaterialIsToldToWait() =
        runTest(dispatcher) {
            core.deleteResults[0] = Result.failure(CoreException.RecoveryMissing())
            val model = model()

            model.delete(online = true)

            assertEquals(Problem.RECOVERY_MISSING, model.state.value.problem)
        }

    @Test
    fun aPhoneWithoutAScreenLockIsNotAsked() =
        runTest(dispatcher) {
            deviceAuth.secure = false
            val model = model()

            assertFalse(model.screenLocked)
            model.delete(online = true)

            assertTrue(model.state.value.deleted)
        }
}
