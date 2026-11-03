package app.geunal.feature.calendar

import app.geunal.core.DayTime
import app.geunal.core.Frequency
import app.geunal.feature.calendar.quickentry.QuickEntryModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class QuickEntryModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    // 2026-10-26 10:00 in Seoul.
    private val now = Instant.parse("2026-10-26T01:00:00Z")

    private fun model(range: IntRange? = null) =
        QuickEntryModel(day = "2026-10-30", range = range, core = core, now = { now }, io = dispatcher)

    @Test
    fun offersOnlyOwnCalendarsAndPicksTheFirst() =
        runTest(dispatcher) {
            core.suggestionList = listOf(Samples.suggestion("카페 마감", "2026-10-30", 1020, 1320, Samples.work))
            val model = model()

            model.load()

            val state = model.state.value
            assertEquals(listOf(Samples.personal, Samples.work), state.calendars)
            assertEquals("c1", state.selectedCalendar?.id)
            assertEquals(1, state.suggestions.size)
            assertEquals(Samples.work.color, model.color(state.suggestions.single()))
        }

    @Test
    fun savesTheTypedTitleOverTheDraggedRange() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.setRange(18..21)
            assertTrue(model.state.value.needsTitle)
            assertNull(model.typedEvent())

            model.setTitle("  스터디 ")
            model.setCalendar("c2")
            val input = model.typedEvent()!!

            assertEquals("스터디", input.title)
            assertEquals("c2", input.calendarId)
            assertFalse(input.allDay)
            assertEquals(DayTime("2026-10-30", 540u), input.start)
            assertEquals(DayTime("2026-10-30", 660u), input.end)
        }

    @Test
    fun endsARangeThatReachesMidnightOnTheNextDay() =
        runTest(dispatcher) {
            val model = model(range = 44..47)
            model.load()
            model.setTitle("야간")

            val input = model.typedEvent()!!

            assertEquals(DayTime("2026-10-30", 1320u), input.start)
            assertEquals(DayTime("2026-10-31", 0u), input.end)
        }

    @Test
    fun savesAllDayWithoutARange() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            model.setTitle("여행")
            model.setRepeat(QuickEntryModel.Repeat.WEEKLY)

            val input = model.typedEvent()!!

            assertTrue(input.allDay)
            assertEquals(Frequency.WEEKLY, input.recurrence?.frequency)
            assertEquals(1.toUShort(), input.recurrence?.interval)
        }
}
