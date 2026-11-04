package app.geunal.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AccountView
import app.geunal.core.CoreException
import app.geunal.core.DeletionProof
import app.geunal.core.Provider
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.account.delete.DeleteAccountModel
import app.geunal.feature.account.delete.DeleteAccountScreen
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakeDeviceAuth
import app.geunal.feature.account.testing.FakeProviders
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 계정 삭제 (m1.md screen 22) on a fake core. */
@RunWith(AndroidJUnit4::class)
class DeleteAccountScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = AccountFakeCore()
    private val providers = FakeProviders()

    // Set on the main thread by the model, read by the test thread.
    @Volatile private var deleted = false

    private fun show(
        account: AccountView,
        online: Boolean = true,
    ) {
        val model = DeleteAccountModel(core, providers, FakeDeviceAuth(), account, MainScope(), onDeleted = { deleted = true })
        compose.setContent { GeunalTheme { DeleteAccountScreen(model = model, online = online, onClose = {}) } }
    }

    @Test
    fun aKakaoAccountTypesTheWordThenSignsInAgain() {
        show(AccountView("u1", "하린", listOf(Provider.KAKAO)))
        compose.waitForText("서버에서 지워지는 것")
        compose.screenshot("22-delete-account")

        compose.onNodeWithTag("delete.start").performScrollTo().performClick()
        compose.waitForText("하린님의 계정을 삭제할까요?")
        compose.onNodeWithTag("delete.confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete.word").performTextInput("삭제")
        compose.waitForText("카카오로 확인하고 삭제")
        compose.screenshot("22-delete-sheet", inside = "delete.confirm")

        // The Kakao sheet is taller than the guest's, and with the keyboard up its button sits below the visible part.
        compose
            .onNodeWithTag("delete.confirm")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        compose.waitUntil(10_000) { deleted }
        assertEquals(listOf<DeletionProof>(DeletionProof.Kakao(nonce = "n1", idToken = "token")), core.deleteProofs)
    }

    @Test
    fun aLostAnswerWaitsForTheCheck() {
        core.deleteResults[0] = Result.failure(CoreException.Network("reset"))
        // The check finds nothing either way while the answer is unknown.
        core.syncResult = Result.failure(CoreException.Network("down"))
        show(AccountView("u1", "하린", emptyList()))
        compose.onNodeWithTag("delete.start").performScrollTo().performClick()
        compose.waitForTag("delete.word")
        compose.onNodeWithTag("delete.word").performTextInput("삭제")

        compose.onNodeWithTag("delete.confirm").performScrollTo().performClick()

        compose.waitForText("연결이 끊겨 삭제를 마쳤는지 확인하지 못했어요.")
        compose.onNodeWithTag("delete.confirm").assertIsNotEnabled()
    }

    @Test
    fun offlineTheButtonWaitsForAConnection() {
        show(AccountView("u1", "하린", emptyList()), online = false)

        compose.waitForText("연결되면 지울 수 있어요")
        compose.onNodeWithTag("delete.start").performScrollTo().assertIsNotEnabled()
    }
}
