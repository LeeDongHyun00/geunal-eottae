package app.geunal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AccountView
import app.geunal.core.Core
import app.geunal.core.MeSummary
import app.geunal.core.NoHandle
import app.geunal.core.Provider
import app.geunal.core.UpNext
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.nextup.NextUpModel
import app.geunal.ui.me.MeScreen
import app.geunal.ui.root.MainScaffold
import app.geunal.ui.root.MainTab
import kotlinx.coroutines.MainScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The tab bar with the next-up bar above it, and the 나 tab's account card, on a fake core. */
@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val toasts = ToastCenter(MainScope())

    @Test
    fun showsTheTabsWithTheNextUpBarAndTheMeTab() {
        val core = FakeCore()
        var openedPeople = false
        var openedAlerts = false
        compose.setContent {
            GeunalTheme {
                MainScaffold(nextUp = NextUpModel(core), toasts = toasts, onOpenItem = {}) { tab, padding ->
                    when (tab) {
                        MainTab.CALENDAR -> Box(Modifier.fillMaxSize().padding(padding)) { Text("calendar") }
                        MainTab.ME ->
                            MeScreen(
                                core = core,
                                pendingGuest = false,
                                signedOut = null,
                                onSignIn = {},
                                onReturnAsGuest = {},
                                onShifts = {},
                                onPeople = { openedPeople = true },
                                onCalendars = {},
                                onAlerts = { openedAlerts = true },
                                onRecoveryCode = {},
                                onBackup = {},
                                onDeleteAccount = {},
                                modifier = Modifier.padding(padding),
                            )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nextUp")).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("04-calendar-tab")

        // A toast takes the bar's place while it shows (m1.md screen 6).
        compose.runOnUiThread { toasts.show("추가했어요") }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("nextUp")).fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { toasts.dismiss() }

        compose.onNodeWithTag("tab.me").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("me.profile")).fetchSemanticsNodes().isNotEmpty() }
        // The calendar section counts what the covers over the tabs hold (m1.md screen 12).
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("me.people") and hasText("2명")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("me.calendars").assertTextContains("3개", substring = true)
        compose.onNodeWithTag("me.shifts").assertTextContains("0개", substring = true)
        compose.screenshot("12-me")
        compose.onNodeWithTag("me.people").performClick()
        compose.waitUntil(10_000) { openedPeople }
        compose.onNodeWithTag("me.alerts").performScrollTo().performClick()
        compose.waitUntil(10_000) { openedAlerts }
    }

    private class FakeCore : Core(NoHandle) {
        override fun nextUp(count: UShort): List<UpNext> = emptyList()

        // The 나 tab reads its counts in one summary.
        override fun meSummary(): MeSummary =
            MeSummary(
                account = AccountView(userId = "u1", displayName = "하린", providers = listOf(Provider.KAKAO)),
                calendars = 3u,
                shiftTemplates = 0u,
                people = 2u,
                deviceCalendars = 0u,
                recoverySaved = true,
                lastBackupAt = null,
            )
    }
}
