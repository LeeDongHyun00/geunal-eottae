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
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AccountView
import app.geunal.core.AlertRule
import app.geunal.core.Core
import app.geunal.core.NoHandle
import app.geunal.core.Person
import app.geunal.core.PersonSource
import app.geunal.core.Provider
import app.geunal.core.ShiftTemplate
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
        compose.setContent {
            GeunalTheme {
                MainScaffold(nextUp = NextUpModel(core), toasts = toasts, onOpenItem = {}) { tab, padding ->
                    when (tab) {
                        MainTab.CALENDAR -> Box(Modifier.fillMaxSize().padding(padding)) { Text("calendar") }
                        MainTab.ME ->
                            MeScreen(
                                core = core,
                                pendingGuest = false,
                                onShifts = {},
                                onPeople = { openedPeople = true },
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
        compose.onNodeWithTag("me.shifts").assertTextContains("0개", substring = true)
        compose.screenshot("12-me")
        compose.onNodeWithTag("me.people").performClick()
        compose.waitUntil(10_000) { openedPeople }
    }

    private class FakeCore : Core(NoHandle) {
        override fun nextUp(count: UShort): List<UpNext> = emptyList()

        override fun account(): AccountView = AccountView(userId = "u1", displayName = "하린", providers = listOf(Provider.KAKAO))

        override fun shiftTemplates(): List<ShiftTemplate> = emptyList()

        override fun people(): List<Person> = listOf(person("p1", "민수"), person("p2", "엄마"))

        private fun person(
            id: String,
            name: String,
        ) = Person(id, name, null, null, null, PersonSource.Manual, readOnly = false, alert = AlertRule.Inherit, suggest = true)
    }
}
