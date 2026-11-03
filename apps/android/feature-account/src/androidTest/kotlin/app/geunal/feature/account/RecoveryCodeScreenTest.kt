package app.geunal.feature.account

import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.account.security.RecoveryCodeModel
import app.geunal.feature.account.security.RecoveryCodeScreen
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.CODE
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 복구 코드 (m1.md screen 13) on a fake core: the words, the secure window, the sensitive clip and "종이에 적었어요". */
@RunWith(AndroidJUnit4::class)
class RecoveryCodeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = AccountFakeCore()
    private var shown by mutableStateOf(true)
    private var closed = false

    private fun show(deviceSecure: Boolean = true) {
        val model = RecoveryCodeModel(core, MainScope())
        val toasts = ToastCenter(MainScope())
        compose.setContent {
            GeunalTheme {
                if (shown) RecoveryCodeScreen(model = model, deviceSecure = deviceSecure, toasts = toasts, onClose = { closed = true })
            }
        }
    }

    private fun clipboard(): ClipboardManager = compose.activity.getSystemService(ClipboardManager::class.java)

    @Test
    fun showsTheWordsInASecureWindowAndRecordsThemWrittenDown() {
        show()
        compose.waitForTag("recovery.words")

        compose.onNodeWithContentDescription("1번 바람").assertExists()
        compose.onNodeWithContentDescription("12번 하늘").assertExists()
        val flags = compose.activity.window.attributes.flags
        assertTrue("kept out of screenshots", flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.screenshot("13-recovery")

        compose.onNodeWithTag("recovery.written").performClick()
        compose.waitUntil(5_000) { closed }
        assertTrue(core.calls.contains("markRecoverySaved"))
    }

    @Test
    fun copiesTheCodeMarkedSensitiveAndTakesItOffOnLeaving() {
        show()
        compose.waitForTag("recovery.words")

        compose.onNodeWithTag("recovery.copy").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            val clip = clipboard().primaryClip!!
            assertEquals(CODE.joinToString(" "), clip.getItemAt(0).text.toString())
            assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }

        shown = false
        compose.waitForIdle()
        compose.runOnUiThread {
            assertNotEquals("geunal", clipboard().primaryClipDescription?.label)
        }
        // Leaving the screen also takes the secure flag away.
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun suggestsAScreenLockWithoutOne() {
        show(deviceSecure = false)

        compose.waitForTag("recovery.noLock")
    }
}
