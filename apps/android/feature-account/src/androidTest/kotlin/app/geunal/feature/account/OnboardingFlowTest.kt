package app.geunal.feature.account

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The first run on fakes: welcome, sign-in with the age box, guest, permissions, then the calendar. */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule(order = 0)
    val folder = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun walksFromTheWelcomeScreenToTheCalendar() {
        val host = OnboardingTestHost(folder.newFolder("onboarding")).apply { show(compose) }
        compose.waitForTag("welcome.start")
        compose.screenshot("01-welcome")

        compose.onNodeWithTag("welcome.start").performClick()
        compose.onNodeWithTag("signIn.guest").assertIsNotEnabled()
        compose.onNodeWithTag("signIn.kakao").assertIsNotEnabled()
        compose.screenshot("02-sign-in")
        compose.onNodeWithTag("signIn.age").performClick()
        compose.waitUntilEnabled("signIn.guest")
        compose.screenshot("02-sign-in--checked")

        compose.onNodeWithTag("signIn.guest").performClick()
        compose.waitForTag("permissions.later")
        compose.onNodeWithTag("permissions.open").assertIsNotEnabled()
        compose.screenshot("03-permissions")
        compose.onNodeWithTag("permissions.stats").performClick()
        compose.onNodeWithTag("permissions.stats").assertTextEquals("보내요 · 나에서 끌 수 있어요")
        compose.screenshot("03-permissions--stats-on")

        compose.onNodeWithTag("permissions.later").performClick()
        compose.waitForIdle()

        assertEquals(1, host.finished)
        assertTrue(host.onboarding.isFinished)
        assertTrue(host.core.statsOn)
    }
}
