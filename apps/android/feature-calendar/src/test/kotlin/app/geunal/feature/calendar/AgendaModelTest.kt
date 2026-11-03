package app.geunal.feature.calendar

import app.geunal.feature.calendar.agenda.AgendaModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AgendaModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    // Monday 26 October 2026, 10:00 in Seoul: the first read covers 28 September to 22 November.
    private val now = Instant.parse("2026-10-26T01:00:00Z")

    private fun model(io: CoroutineDispatcher = dispatcher) = AgendaModel(core, now = { now }, io = io)

    private val AgendaModel.listed get() = state.value.days.map { it.day }

    @Test
    fun passesOverWeeksWithNothingOnThemToTheNextDayThatHasSomething() =
        runTest(dispatcher) {
            core.events = mapOf("2027-01-20" to listOf(Samples.event("e1", "졸업식", "2027-01-20", 600, 720)))
            val model = model()
            model.reload()
            assertTrue(model.state.value.isEmpty)

            model.loadMore()

            assertEquals(listOf("2027-01-20"), model.listed)
            assertTrue(model.state.value.canLoadMore)
            assertEquals(
                listOf("agenda 2026-09-28 56", "agenda 2026-11-23 28", "agenda 2026-12-21 28", "agenda 2027-01-18 28"),
                core.calls,
            )
        }

    @Test
    fun stopsAtTheFarthestWeekWhenNothingIsAhead() =
        runTest(dispatcher) {
            val model = model()
            model.reload()

            model.loadMore()

            assertFalse(model.state.value.canLoadMore)
            assertEquals(emptyList<String>(), model.listed)
            // Pages start 28, 56, … days ahead while that is under 3 × 366: the first read and 39 pages.
            assertEquals(40, core.calls.size)
        }

    @Test
    fun passesOverEmptyWeeksBeforeTodayToo() =
        runTest(dispatcher) {
            core.events = mapOf("2026-08-03" to listOf(Samples.event("e1", "휴가", "2026-08-03")))
            val model = model()
            model.reload()

            model.loadEarlier()

            assertEquals(listOf("2026-08-03"), model.listed)
            assertEquals(listOf("agenda 2026-09-28 56", "agenda 2026-08-31 28", "agenda 2026-08-03 28"), core.calls)
        }

    @Test
    fun aReloadWaitsForThePageBeingRead() =
        runTest(dispatcher) {
            // Reads on their own dispatcher really suspend, so a reload could land in the middle of a page.
            val model = model(io = StandardTestDispatcher(testScheduler))
            core.events = mapOf("2026-11-25" to listOf(Samples.event("e1", "면접", "2026-11-25", 600, 660)))
            model.reload()
            model.loadMore()

            // A change arrives just as the list's end asks for the next page.
            launch { model.reload() }
            launch { model.loadMore() }
            advanceUntilIdle()

            // The reload started over from today, and the page after it picked up from there.
            assertEquals(listOf("2026-11-25"), model.listed)
            assertTrue(model.state.value.canLoadMore)
        }
}
