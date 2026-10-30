package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.Palette
import app.geunal.designsystem.GeunalTheme
import app.geunal.feature.calendar.calendars.CalendarsModel
import app.geunal.feature.calendar.calendars.CalendarsScreen
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 캘린더 관리 (m1.md screen 15) and the calendar sheet (screen 16) on a fake core. */
@RunWith(AndroidJUnit4::class)
class CalendarsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core =
        CalendarFakeCore().apply {
            calendarList =
                listOf(
                    Samples.calendar("s1", "학교", Palette.SEA).copy(eventCount = 42u),
                    Samples.work,
                    Samples.personal,
                    Samples.calendar("s4", "운동", Palette.OLIVE).copy(soft = true),
                    Samples.device,
                )
        }
    private var closed = false

    private fun show() {
        val model = CalendarsModel(core, MainScope())
        compose.setContent { GeunalTheme { CalendarsScreen(model = model, onClose = { closed = true }) } }
    }

    @Test
    fun listsTheCalendarsAndHidesOne() {
        show()
        compose.waitForTag("calendars.row.s1")

        compose.onNodeWithTag("calendars.row.s1").assertTextContains("바다 · 일정 42개")
        compose.onNodeWithTag("calendars.row.s4").assertTextContains("올리브 · 소프트 캘린더")
        compose.screenshot("15-calendars")

        compose.onNodeWithTag("calendars.show.c2").performClick()
        compose.waitUntil(5_000) { core.calls.contains("setCalendarVisible c2 false") }
        compose.onNodeWithTag("calendars.show.c2").assertIsOff()

        compose.onNodeWithTag("calendars.back").performClick()
        compose.waitUntil(5_000) { closed }
    }

    @Test
    fun makesASoftCalendar() {
        show()
        compose.waitForTag("calendars.new")

        compose.onNodeWithTag("calendars.new").performScrollTo().performClick()
        compose.waitForTag("calendarEdit.name")
        compose.onNodeWithTag("calendarEdit.save").assertIsNotEnabled()
        compose.onNodeWithTag("calendarEdit.name").performTextInput("독서")
        compose.onNodeWithTag("calendarEdit.name").performImeAction()
        compose.onNodeWithTag("calendarEdit.color.grape").performScrollTo().performClick()
        compose.onNodeWithTag("calendarEdit.soft").performScrollTo().performClick()
        compose.screenshot("16-calendar-edit")
        compose.onNodeWithTag("calendarEdit.save").performScrollTo().performClick()

        compose.waitForTag("calendars.row.c6")
        assertEquals("saveCalendar 독서 grape soft=true inherit", core.calls.last { it.startsWith("saveCalendar") })
    }

    @Test
    fun deletesACalendarAfterAskingWithItsEvents() {
        show()
        compose.waitForTag("calendars.row.s1")

        compose.onNodeWithTag("calendars.row.s1").performClick()
        compose.waitForText("캘린더 삭제 · 일정 42개도 지워져요")
        compose.onNodeWithTag("calendarEdit.delete").performScrollTo().performClick()
        compose.waitForText("‘학교’ 캘린더를 삭제할까요?")
        compose.onNodeWithTag("delete.confirm").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("calendars.row.s1")).fetchSemanticsNodes().isEmpty() }
        assertTrue(core.calls.contains("deleteCalendar s1"))
    }

    @Test
    fun keepsTheLastCalendar() {
        core.calendarList = listOf(Samples.personal, Samples.device)
        show()
        compose.waitForTag("calendars.row.c1")

        compose.onNodeWithTag("calendars.row.c1").performClick()
        compose.waitForTag("calendarEdit.last")
        compose.onNodeWithTag("calendarEdit.delete").assertIsNotEnabled()
    }
}
