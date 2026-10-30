package app.geunal.feature.calendar

import app.geunal.core.BirthdayItem
import app.geunal.core.Core
import app.geunal.core.DayTime
import app.geunal.core.EventItem
import app.geunal.core.EventSource
import app.geunal.core.NoHandle
import app.geunal.core.Palette
import app.geunal.core.UpNext
import app.geunal.feature.calendar.nextup.NextUpModel
import app.geunal.feature.calendar.support.DayLabel
import app.geunal.feature.calendar.support.Until
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** A core whose next-up list the test sets; the rest of the surface is never reached. */
private class NextUpFakeCore : Core(NoHandle) {
    @Volatile var upcoming: List<UpNext> = emptyList()

    override fun nextUp(count: UShort): List<UpNext> = upcoming.take(count.toInt())
}

class NextUpModelTest {
    // 2026-10-26 14:20 in Seoul, 3 h 40 min before the 18:00 dinner.
    private val now = Instant.parse("2026-10-26T05:20:00Z")

    private fun dinner() =
        UpNext.Event(
            item =
                EventItem(
                    eventId = "e1",
                    occurrence = null,
                    calendarId = "c1",
                    calendarName = "개인",
                    color = Palette.BLOSSOM,
                    title = "가족 · 저녁",
                    allDay = false,
                    start = DayTime("2026-10-26", 1080u),
                    end = DayTime("2026-10-26", 1200u),
                    soft = false,
                    source = EventSource.Own,
                    recurrence = null,
                ),
            startsAt = now.plusSeconds(13_200),
            endsAt = now.plusSeconds(20_400),
        )

    private fun birthday(
        id: String,
        name: String,
        day: String,
    ) = UpNext.Birthday(BirthdayItem(personId = id, name = name, day = day, lunar = false))

    @Test
    fun describesTheNextEventAndBirthday() =
        runTest {
            val core = NextUpFakeCore().apply { upcoming = listOf(dinner(), birthday("p1", "민수", "2026-10-27")) }
            val model = NextUpModel(core, now = { now }, io = StandardTestDispatcher(testScheduler))

            model.refresh()

            val first = model.state.value.current!!
            assertEquals(NextUpModel.Title.Event("가족 · 저녁"), first.title)
            assertEquals(NextUpModel.Detail.Timed(DayLabel.Today, 1080, 1200, Until(0, 3, 40)), first.detail)
            model.forward()
            val second = model.state.value.current!!
            assertEquals(NextUpModel.Title.Birthday("민수"), second.title)
            assertEquals(NextUpModel.Detail.Birthday(DayLabel.Tomorrow), second.detail)
            assertFalse(model.state.value.canGoForward)
            model.back()
            assertEquals(0, model.state.value.index)
        }

    @Test
    fun staysOnTheSameItemAfterARefresh() =
        runTest {
            val first = birthday("p1", "민수", "2026-10-27")
            val second = birthday("p2", "지영", "2026-10-28")
            val core = NextUpFakeCore().apply { upcoming = listOf(first, second) }
            val model = NextUpModel(core, now = { now }, io = StandardTestDispatcher(testScheduler))
            model.refresh()
            model.forward()

            core.upcoming = listOf(second)
            model.refresh()

            assertEquals(
                NextUpModel.Title.Birthday("지영"),
                model.state.value.current
                    ?.title,
            )
        }

    @Test
    fun countsDownAtEveryMinute() =
        runTest {
            val core = NextUpFakeCore().apply { upcoming = listOf(dinner()) }
            // 15 seconds into 14:20; the clock moves only when the model waits, and the third wait ends the loop.
            var clock = now.plusSeconds(15)
            val waits = mutableListOf<Long>()
            val model =
                NextUpModel(
                    core,
                    now = { clock },
                    io = StandardTestDispatcher(testScheduler),
                    sleep = { millis ->
                        if (waits.size == 2) throw CancellationException("done")
                        waits += millis
                        clock = clock.plusMillis(millis)
                    },
                )
            model.refresh()
            assertEquals(
                Until(0, 3, 40),
                (
                    model.state.value.current!!
                        .detail as NextUpModel.Detail.Timed
                ).until,
            )

            runCatching { model.keepCurrent() }

            assertEquals(listOf(45_000L, 60_000L), waits)
            assertEquals(
                Until(0, 3, 38),
                (
                    model.state.value.current!!
                        .detail as NextUpModel.Detail.Timed
                ).until,
            )
        }

    @Test
    fun isEmptyWhenNothingIsComing() =
        runTest {
            val model = NextUpModel(NextUpFakeCore(), now = { now }, io = StandardTestDispatcher(testScheduler))

            model.refresh()

            assertNull(model.state.value.current)
        }

    @Test
    fun showsAnEventEndingAtMidnightAsTwentyFour() {
        val late =
            dinner().let {
                it.copy(item = it.item.copy(start = DayTime("2026-10-26", 1320u), end = DayTime("2026-10-27", 0u)))
            }

        val item = NextUpModel.item(late, now)

        assertEquals(1440, (item.detail as NextUpModel.Detail.Timed).end)
    }
}
