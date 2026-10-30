package app.geunal.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.BackupView
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.account.backup.BackupModel
import app.geunal.feature.account.backup.BackupScreen
import app.geunal.feature.account.testing.AccountFakeCore
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** 암호화 백업 (m1.md screen 20) on a fake core. */
@RunWith(AndroidJUnit4::class)
class BackupScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = AccountFakeCore()
    private var openedCode = false

    private fun show() {
        val model = BackupModel(core, MainScope())
        compose.setContent { GeunalTheme { BackupScreen(model = model, onRecoveryCode = { openedCode = true }, onClose = {}) } }
    }

    @Test
    fun backsUpNowAndListsWhatTheServerKeeps() {
        core.backup =
            core.backup.copy(last = BackupView(3, Instant.parse("2026-10-20T09:20:00Z"), 2_400_000u, true, 128u, 12u))
        show()
        compose.waitForTag("backup.item.3")
        compose.waitForText("일정 128개 · 사람 12명 · 2.4MB")
        compose.screenshot("20-backup")

        compose.onNodeWithTag("backup.now").performClick()

        compose.waitForText("방금")
        assertTrue(core.calls.contains("backupNow"))
    }

    @Test
    fun leadsToTheCodeNeverWrittenDown() {
        show()
        compose.waitForTag("backup.codeNotSaved")

        compose.onNodeWithTag("backup.codeNotSaved").performClick()

        compose.waitUntil(5_000) { openedCode }
    }

    @Test
    fun restsUntilTheCodePutOffIsEntered() {
        core.backup = core.backup.copy(ready = false)
        show()
        compose.waitForTag("backup.waitsForCode")

        compose.onNodeWithTag("backup.now").assertIsNotEnabled()
        compose.onNodeWithTag("backup.auto").performScrollTo().assertIsNotEnabled()
        compose.waitForText("복구 코드를 넣으면 백업할 수 있어요")
    }
}
