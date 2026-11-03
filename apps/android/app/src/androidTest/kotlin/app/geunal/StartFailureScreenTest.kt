package app.geunal

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.designsystem.GeunalTheme
import app.geunal.ui.root.StartFailureScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The start failure screen (m1.md §4 "시작 오류", decision 37). */
@RunWith(AndroidJUnit4::class)
class StartFailureScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun saysNothingWasErasedListsTheStepsAndTriesAgain() {
        var retrying by mutableStateOf(false)
        var taps = 0
        compose.setContent {
            GeunalTheme {
                StartFailureScreen(reason = StartFailure.SECRETS, retrying = retrying, onRetry = { taps++ })
            }
        }

        compose.onNodeWithText("그날어때를 열 수 없어요").assertExists()
        compose.onNodeWithText("이 기기의 캘린더를 지금 열지 못했어요. 지운 것은 없으니 아래 순서대로 해 보세요.").assertExists()
        compose.onNodeWithText("그래도 안 되면 휴대폰을 껐다 켠 뒤 다시 열어 주세요.").assertExists()
        compose.onNodeWithTag("start.failure").assertTextEquals("보관한 열쇠를 읽지 못했어요")
        compose.onNodeWithText("운영자", substring = true).assertDoesNotExist()
        compose.screenshot("start-failure")

        compose.onNodeWithTag("start.retry").assertIsEnabled().performClick()
        assertEquals(1, taps)

        retrying = true
        compose.onNodeWithTag("start.retry").assertIsNotEnabled()
        compose.onNodeWithText("여는 중…").assertExists()
        compose.screenshot("start-failure--retrying")
    }
}
