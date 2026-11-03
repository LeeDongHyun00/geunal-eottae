package app.geunal.feature.calendar

import app.geunal.core.DayTime
import app.geunal.core.EventItem
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

    private fun model(
        range: IntRange? = null,
        events: List<EventItem>? = emptyList(),
    ) = QuickEntryModel(day = "2026-10-30", range = range, events = events, core = core, now = { now }, io = dispatcher)

    private val dayEvents =
        listOf(
            Samples.event("e1", "교양 수업", "2026-10-30", 540, 630),
            Samples.event("e2", "치과", "2026-10-30", 660, 720),
            Samples.event("e3", "팀플 회의", "2026-10-30", 960, 1020),
            Samples.event("e4", "동아리 회의", "2026-10-30", 1140, 1260),
        )

    @Test
    fun showsThreeOfTheDaysEventsUntilTheRestAreOpenedInPlace() =
        runTest(dispatcher) {
            val model = model(events = dayEvents)
            model.load()

            val folded = model.state.value
            assertEquals(listOf("e1", "e2", "e3"), folded.shownEvents.map { it.eventId })
            assertEquals(1, folded.foldedEvents)

            model.toggleEvents()
            assertEquals(dayEvents, model.state.value.shownEvents)

            model.toggleEvents()
            assertEquals(3, model.state.value.shownEvents.size)
            // The month view's own cells: no second read of the month (m1.md decision 42).
            assertFalse(core.calls.any { it.startsWith("month") })
        }

    @Test
    fun foldsNothingWithThreeEventsOrFewer() =
        runTest(dispatcher) {
            val model = model(events = dayEvents.take(3))
            model.load()

            assertEquals(3, model.state.value.shownEvents.size)
            assertEquals(0, model.state.value.foldedEvents)
        }

    @Test
    fun readsTheDayFromTheMonthWhenTheViewDoesNotHoldItAll() =
        runTest(dispatcher) {
            core.events = mapOf("2026-10-30" to dayEvents, "2026-10-29" to listOf(Samples.event("x", "다른 날", "2026-10-29", 600, 660)))
            val model = model(events = null)

            model.load()

            assertEquals(dayEvents, model.state.value.events)
            assertTrue("month 2026-10" in core.calls)
        }

    @Test
    fun cutsAnEventAtTheEdgesOfTheDay() {
        val overnight =
            Samples.event("n", "야간", "2026-10-29", 1320, 0).copy(end = DayTime("2026-10-31", 120u))
        assertEquals(0..1440, QuickEntryModel.minutesOn(overnight, "2026-10-30"))
        assertEquals(1320..1440, QuickEntryModel.minutesOn(overnight, "2026-10-29"))
        assertEquals(0..120, QuickEntryModel.minutesOn(overnight, "2026-10-31"))
        assertEquals(540..630, QuickEntryModel.minutesOn(dayEvents.first(), "2026-10-30"))
    }

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

    @Test
    fun handsWhatIsTypedToTheEditor() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            // Nothing typed yet is still something to finish in the editor.
            assertEquals("", model.draft().title)
            assertTrue(model.draft().allDay)

            model.setTitle(" 스터디 ")
            model.setRange(18..21)
            model.setCalendar("c2")
            model.setRepeat(QuickEntryModel.Repeat.WEEKLY)
            val draft = model.draft()

            assertEquals("스터디", draft.title)
            assertEquals("c2", draft.calendarId)
            assertFalse(draft.allDay)
            assertEquals(DayTime("2026-10-30", 540u), draft.start)
            assertEquals(DayTime("2026-10-30", 660u), draft.end)
            assertEquals(Frequency.WEEKLY, draft.recurrence?.frequency)
        }
}
