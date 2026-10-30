package app.geunal.feature.calendar

import app.geunal.core.Change
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.screen.CalendarViewKind
import app.geunal.feature.calendar.screen.CalendarViewModel
import app.geunal.feature.calendar.screen.ViewKindStore
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val changes = MutableSharedFlow<Change>()
    private val store = MemoryStore()

    // Monday 2026-10-26, 10:00 in Seoul.
    private var clock = Instant.parse("2026-10-26T01:00:00Z")

    private class MemoryStore(
        var saved: CalendarViewKind = CalendarViewKind.MONTH,
    ) : ViewKindStore {
        override fun load() = saved

        override fun save(kind: CalendarViewKind) {
            saved = kind
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() =
        CalendarViewModel(core, ToastCenter(backgroundScope), PlainTexts, changes, store, now = { clock }, io = dispatcher)

    @Test
    fun opensOnTheViewLastShownAndRemembersTheNext() =
        runTest(dispatcher) {
            store.saved = CalendarViewKind.WEEK
            val model = viewModel()
            assertEquals(CalendarViewKind.WEEK, model.kind.value)

            model.select(CalendarViewKind.LIST)

            assertEquals(CalendarViewKind.LIST, model.kind.value)
            assertEquals(CalendarViewKind.LIST, store.saved)
        }

    @Test
    fun opensTodaysWeekFromThisMonthAndTheFirstWeekFromAnother() =
        runTest(dispatcher) {
            val model = viewModel()

            model.select(CalendarViewKind.WEEK)
            assertEquals("2026-10-25", model.week.state.value.visibleWeek)

            model.select(CalendarViewKind.MONTH)
            model.month.show("2026-12")
            model.select(CalendarViewKind.WEEK)
            // 1 December 2026 is a Tuesday; its week starts on Sunday 29 November.
            assertEquals("2026-11-29", model.week.state.value.visibleWeek)
        }

    @Test
    fun opensTheMonthTheWeekOnScreenMostlyLiesIn() =
        runTest(dispatcher) {
            val model = viewModel()
            model.select(CalendarViewKind.WEEK)

            // 27 December to 2 January: Wednesday the 30th decides.
            model.week.show("2026-12-31")
            model.select(CalendarViewKind.MONTH)

            assertEquals("2026-12", model.month.state.value.visibleMonth)
        }

    @Test
    fun readsTheViewsAgainWhenTheCalendarChanges() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            core.events = mapOf("2026-10-30" to listOf(Samples.event("e1", "카페 마감", "2026-10-30", 1020, 1320)))
            core.calls.clear()

            changes.emit(Change.Calendar)
            advanceUntilIdle()

            assertTrue(core.calls.toString(), core.calls.any { it.startsWith("month 2026-10") })
            assertTrue(core.calls.toString(), core.calls.any { it.startsWith("week 2026-10-25") })
            assertEquals(
                "카페 마감",
                model.agenda.state.value.upcoming
                    .single()
                    .events
                    .single()
                    .title,
            )
        }

    @Test
    fun readsAgainOnlyOnceTheDayHasTurned() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            core.calls.clear()

            model.refreshIfNewDay()
            advanceUntilIdle()
            assertTrue(core.calls.isEmpty())

            clock = Instant.parse("2026-10-26T15:00:01Z")
            model.refreshIfNewDay()
            advanceUntilIdle()

            assertEquals("2026-10-27", model.agenda.state.value.today)
            assertEquals("2026-10-27", model.month.state.value.today)
        }

    @Test
    fun startsANewEventAndTheShiftStampOnTheMonthOnScreen() =
        runTest(dispatcher) {
            val model = viewModel()

            assertEquals("2026-10-26", model.newEventDay())
            assertEquals("2026-10", model.stampMonth())

            model.month.show("2026-12")
            assertEquals("2026-12-01", model.newEventDay())
            assertEquals("2026-12", model.stampMonth())

            // The other views start from today and this month, wherever the month view was left.
            model.select(CalendarViewKind.LIST)
            assertEquals("2026-10-26", model.newEventDay())
            assertEquals("2026-10", model.stampMonth())
        }
}
