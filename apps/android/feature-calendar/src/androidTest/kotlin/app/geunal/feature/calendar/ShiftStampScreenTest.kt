package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.calendar.shift.ShiftStampModel
import app.geunal.feature.calendar.shift.ShiftStampScreen
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shift stamp screen (m1.md screen 10) and its template sheet (screen 18) on a fake core. */
@RunWith(AndroidJUnit4::class)
class ShiftStampScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = CalendarFakeCore()
    private var closed = false

    private fun show(month: String = "2026-11") {
        val model = ShiftStampModel(month, core, MainScope())
        compose.setContent { GeunalTheme { ShiftStampScreen(model = model, onClose = { closed = true }) } }
    }

    @Test
    fun stampsShiftsAsADraftUntilDone() {
        // The board's three mint templates, a week already stamped.
        core.templateList =
            listOf(
                Samples.template("t1", "오픈", 480, 780, tint = 2),
                Samples.template("t2", "미들", 660, 960, tint = 3),
                Samples.template("t3", "마감", 1080, 1380, tint = 5),
            )
        core.shifts = mapOf("2026-11-02" to listOf("t1"), "2026-11-03" to listOf("t2"), "2026-11-05" to listOf("t3"))
        show()
        compose.waitForTag("shift.template.오픈")
        compose.onNodeWithTag("shift.done").assertIsNotEnabled()

        compose.onNodeWithTag("shift.day.2026-11-04").performClick()
        compose.onNodeWithTag("shift.template.마감").performClick()
        compose.onNodeWithTag("shift.day.2026-11-06").performClick()
        compose.onNodeWithTag("shift.day.2026-11-06").assertContentDescriptionContains("마감", substring = true)
        compose.onNodeWithTag("shift.done").assertIsEnabled()
        compose.screenshot("10-shift")
        assertTrue("nothing is saved before 완료", core.calls.none { it.startsWith("setShifts") })

        compose.onNodeWithTag("shift.done").performClick()
        compose.waitUntil(5_000) { closed }
        assertEquals("setShifts 2026-11-04=t1 2026-11-06=t3", core.calls.last())
    }

    @Test
    fun makesTheFirstTemplateAndStampsWithIt() {
        show()
        compose.waitForTag("shift.firstTemplate")

        compose.onNodeWithTag("shift.firstTemplate").performClick()
        compose.waitForTag("template.name")
        compose.onNodeWithTag("template.name").performTextInput("오픈")
        compose.onNodeWithTag("template.preview").assertTextContains("09:00–18:00 · 9시간", substring = true)
        compose.screenshot("18-template-edit")
        compose.onNodeWithTag("template.save").performScrollTo().performClick()

        compose.waitForTag("shift.template.오픈")
        compose.waitForText("찍은 근무는 ‘알바’ 캘린더의 일반 일정이 돼요. 같은 날을 한 번 더 누르면 빠져요.")
        compose.onNodeWithTag("shift.day.2026-11-09").performClick()
        compose.onNodeWithTag("shift.done").performClick()
        compose.waitUntil(5_000) { closed }
        assertEquals("setShifts 2026-11-09=t1", core.calls.last())
    }

    @Test
    fun opensATemplateOnALongPressAndDeletesIt() {
        core.templateList = listOf(Samples.template("t1", "오픈", 480, 780, tint = 2), Samples.template("t2", "마감", 1320, 120, tint = 5))
        show()
        compose.waitForTag("shift.template.마감")

        compose.onNodeWithTag("shift.template.마감").performTouchInput { longClick() }
        compose.waitForTag("template.delete")
        // 22:00–02:00 runs into the next day, and the note says so.
        compose.onNodeWithTag("template.preview").assertTextContains("22:00–02:00 · 4시간", substring = true)
        compose.onNodeWithTag("template.delete").performScrollTo().performClick()

        compose.waitUntil(5_000) { core.templateList.none { it.id == "t2" } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("shift.template.마감")).fetchSemanticsNodes().isEmpty() }
        assertEquals("deleteTemplate t2", core.calls.last())
    }
}
