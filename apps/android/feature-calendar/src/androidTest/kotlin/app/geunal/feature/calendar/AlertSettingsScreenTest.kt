package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.calendar.alerts.AlertSettingsModel
import app.geunal.feature.calendar.alerts.AlertSettingsScreen
import app.geunal.feature.calendar.testing.CalendarFakeCore
import kotlinx.coroutines.MainScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 알림 설정 (m1.md screen 19) on a fake core, with the system's switch on and off. */
@RunWith(AndroidJUnit4::class)
class AlertSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = CalendarFakeCore()

    private fun show(systemOn: Boolean = true) {
        val model = AlertSettingsModel(core, MainScope(), access = { systemOn })
        compose.setContent { GeunalTheme { AlertSettingsScreen(model = model, onClose = {}) } }
    }

    @Test
    fun changesTheDefaultsAndTheSwitches() {
        show()
        compose.waitForTag("alerts.event")

        compose.onNodeWithTag("alerts.event").assertTextContains("10분 전")
        compose.onNodeWithTag("alerts.allDay").assertTextContains("전날 18:00")
        compose.screenshot("19-notify-settings")

        compose.onNodeWithTag("alerts.event").performClick()
        compose.onNodeWithText("30분 전").performClick()
        compose.waitUntil(5_000) { core.calls.contains("setNotificationSettings 30 360 true true true") }

        compose.onNodeWithTag("alerts.leave").performScrollTo().performClick()
        compose.waitUntil(5_000) { core.calls.contains("setNotificationSettings 30 360 true true false") }
    }

    @Test
    fun dimsTheAlertsWhileTheSystemHasThemOff() {
        show(systemOn = false)
        compose.waitForTag("alerts.systemOff")

        compose.onNodeWithTag("alerts.birthdays").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("alerts.system").performScrollTo().assertTextContains("꺼짐")
    }
}
