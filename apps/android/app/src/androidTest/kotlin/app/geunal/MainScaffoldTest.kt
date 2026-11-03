package app.geunal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.AccountView
import app.geunal.core.Core
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
        compose.setContent {
            GeunalTheme {
                MainScaffold(nextUp = NextUpModel(core), toasts = toasts, onOpenEvent = {}) { tab, padding ->
                    when (tab) {
                        MainTab.CALENDAR -> Box(Modifier.fillMaxSize().padding(padding)) { Text("calendar") }
                        MainTab.ME -> MeScreen(core = core, pendingGuest = false, modifier = Modifier.padding(padding))
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
        compose.screenshot("12-me")
    }

    private class FakeCore : Core(NoHandle) {
        override fun nextUp(count: UShort): List<UpNext> = emptyList()

        override fun account(): AccountView = AccountView(userId = "u1", displayName = "하린", providers = listOf(Provider.KAKAO))
    }
}
