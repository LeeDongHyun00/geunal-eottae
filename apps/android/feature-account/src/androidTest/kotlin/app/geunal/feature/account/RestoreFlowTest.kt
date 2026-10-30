package app.geunal.feature.account

import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AuthResult
import app.geunal.core.LocalData
import app.geunal.core.LocalDataChoice
import app.geunal.feature.account.support.DeviceAuthPurpose
import app.geunal.feature.account.testing.CODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The recovery-code screens on fakes: the guest return and its no-code sheet, the device change from a pasted code with
 * the question about another account's events, a word outside the list, and starting fresh behind the OS owner check.
 */
@RunWith(AndroidJUnit4::class)
class RestoreFlowTest {
    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun host() = OnboardingTestHost(folder.newFolder("onboarding"))

    private fun openSignIn(host: OnboardingTestHost) {
        host.show(compose)
        compose.waitForTag("welcome.start")
        compose.onNodeWithTag("welcome.start").performClick()
        compose.waitForTag("signIn.age")
    }

    private fun openDeviceChange(host: OnboardingTestHost) {
        host.core.authResult =
            Result.success(AuthResult.ReplaceRequired("u1", "ios", Instant.parse("2026-10-01T00:00:00Z"), recovery = true))
        openSignIn(host)
        compose.onNodeWithTag("signIn.age").performClick()
        compose.waitUntilEnabled("signIn.kakao")
        compose.onNodeWithTag("signIn.kakao").performClick()
        compose.waitForTag("restore.start")
    }

    @Test
    fun showsTheGuestReturnAndItsNoCodeSheet() {
        val host = host()
        openSignIn(host)

        compose.onNodeWithTag("signIn.guestReturn").performClick()
        compose.waitForTag("restore.noCode")
        compose.onNodeWithTag("restore.start").assertIsNotEnabled()
        compose.screenshot("21-restore--guest")
        compose.onNodeWithTag("restore.noCode").performClick()
        compose.waitForTag("restore.startOver")
        compose.screenshot("21-restore--guest-no-code")

        // Without the age tick a new guest account waits for it on the sign-in screen.
        compose.onNodeWithTag("restore.startOver").performClick()
        compose.waitForText("만 14세 이상인지 확인하면 게스트로 시작할 수 있어요")
        compose.waitForTag("signIn.age")
        assertEquals(emptyList<String>(), host.core.calls)
    }

    @Test
    fun keepsTheRecoveryScreenOutOfScreenshots() {
        val host = host()
        openSignIn(host)
        val flags = { compose.runOnUiThread<Int> { compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE } }
        assertEquals(0, flags())

        compose.onNodeWithTag("signIn.guestReturn").performClick()
        compose.waitForTag("restore.noCode")
        assertTrue(flags() != 0)

        compose.onNodeWithTag("restore.back").performClick()
        compose.waitForTag("signIn.age")
        compose.waitUntil { flags() == 0 }
    }

    @Test
    fun changesTheDeviceFromAPastedCode() {
        val host = host()
        host.core.otherAccountData = LocalData(events = 86u, people = 5u)
        openDeviceChange(host)
        compose.screenshot("21-restore")

        compose.runOnUiThread {
            compose.activity
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("code", CODE.mapIndexed { i, word -> "${i + 1} $word" }.joinToString(" ")))
        }
        compose.onNodeWithTag("restore.paste").performClick()
        compose.waitUntilEnabled("restore.start")
        compose.screenshot("21-restore--ready")

        compose.onNodeWithTag("restore.start").performClick()
        compose.waitForTag("keepLocal.keep")
        compose.screenshot("24-keep-local")
        // Sheet 24 has to be answered: back neither closes it nor leaves the screen.
        compose.pressBack()
        compose.onNodeWithTag("keepLocal.keep").assertExists()
        assertTrue(host.core.calls.none { it.startsWith("restore") })
        compose.onNodeWithTag("keepLocal.keep").performClick()
        compose.waitForTag("restore.open")
        compose.screenshot("21-restore--done")
        compose.onNodeWithTag("restore.open").performClick()
        compose.waitForIdle()

        assertEquals(LocalDataChoice.KEEP, host.core.lastLocalData)
        assertEquals("restore " + CODE.joinToString(" "), host.core.calls.last())
        assertEquals(1, host.finished)
    }

    @Test
    fun opensTheCalendarOnBackFromTheResult() {
        val host = host()
        openDeviceChange(host)
        compose.runOnUiThread {
            compose.activity
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("code", CODE.joinToString(" ")))
        }
        compose.onNodeWithTag("restore.paste").performClick()
        compose.waitUntilEnabled("restore.start")
        compose.onNodeWithTag("restore.start").performClick()
        compose.waitForTag("restore.open")

        compose.pressBack()

        // "돌아왔어요" is the first screen once the restore is done; back means "캘린더 열기".
        assertEquals(1, host.finished)
        compose.onNodeWithTag("restore.open").assertExists()
    }

    @Test
    fun marksAWordThatIsNotInTheList() {
        val host = host()
        openDeviceChange(host)

        compose.onNodeWithTag("restore.cell.7").performClick()
        compose.onNodeWithTag("restore.cell.7").performTextInput("바다")
        compose.onNodeWithTag("restore.cell.8").performClick()

        compose.waitForTag("restore.problem")
        compose.onNodeWithTag("restore.start").assertTextEquals("8번째 단어를 고쳐 주세요")
        compose.screenshot("21-restore--error-word")
    }

    @Test
    fun startsFreshOnlyAfterTheOwnerCheck() {
        val host = host()
        openDeviceChange(host)

        compose.onNodeWithTag("restore.noCode").performClick()
        compose.waitForTag("restore.startOver")
        compose.onNodeWithTag("restore.startOver").performClick()
        compose.waitForTag("restore.confirmWipe")
        compose.onNodeWithTag("restore.confirmWipe").assertTextEquals("백업 2개 지우고 새로 시작")
        compose.screenshot("21-restore--wipe-confirm")

        // A cancelled check deletes nothing and leaves the confirmation as it was.
        host.deviceAuth.passes = false
        compose.onNodeWithTag("restore.confirmWipe").performClick()
        compose.waitForIdle()
        assertFalse("startFresh" in host.core.calls)
        compose.waitForTag("restore.confirmWipe")

        host.deviceAuth.passes = true
        compose.onNodeWithTag("restore.confirmWipe").performClick()
        compose.waitForTag("permissions.later")

        assertEquals(listOf(DeviceAuthPurpose.WIPE_BACKUPS, DeviceAuthPurpose.WIPE_BACKUPS), host.deviceAuth.asked)
        assertEquals(listOf("nonce", "kakao n1 token", "backups", "startFresh"), host.core.calls)
    }
}
