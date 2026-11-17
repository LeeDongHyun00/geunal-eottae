package app.geunal.feature.calendar

import app.geunal.core.CoreException
import app.geunal.core.DayTime
import app.geunal.core.DeviceCalendarInput
import app.geunal.core.DeviceEventInput
import app.geunal.feature.calendar.calendars.DeviceCalendarAccess
import app.geunal.feature.calendar.calendars.DeviceCalendarMirror
import app.geunal.feature.calendar.calendars.DeviceCalendarSource
import app.geunal.feature.calendar.calendars.DeviceCalendarSummary
import app.geunal.feature.calendar.calendars.DeviceEventDates
import app.geunal.feature.calendar.testing.CalendarFakeCore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The phone's calendars as a map from the calendar's id to its events. */
private class FakeDeviceCalendars : DeviceCalendarSource {
    var granted = true
    var calendars = mapOf("1" to listOf(event("10@1")), "2" to listOf(event("20@1"), event("21@1")))
    var settingsOpened = 0
    var reads = 0

    override fun granted(): Boolean = granted

    override fun calendars(): List<DeviceCalendarSummary> {
        if (!granted) throw SecurityException("no access")
        return calendars.map { (id, events) -> DeviceCalendarSummary(id, "캘린더 $id", events.size) }
    }

    override fun read(ids: List<String>): List<DeviceCalendarInput> {
        if (!granted) throw SecurityException("no access")
        reads++
        return ids.mapNotNull { id -> calendars[id]?.let { DeviceCalendarInput(id, "캘린더 $id", it) } }
    }

    override fun openSettings() {
        settingsOpened++
    }

    override fun observeChanges(action: () -> Unit) = Unit

    companion object {
        fun event(id: String) =
            DeviceEventInput(id, "회의", allDay = false, start = DayTime("2026-11-07", 600u), end = DayTime("2026-11-07", 660u))
    }
}

class DeviceCalendarMirrorTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val source = FakeDeviceCalendars()

    private fun mirror() = DeviceCalendarMirror(core, source, io = dispatcher)

    private fun mirroredIds() = core.mirrors.last().map { it.sourceId }

    @Test
    fun importsEveryCalendarOfThePhone() =
        runTest(dispatcher) {
            val mirror = mirror()

            assertEquals(2, mirror.importAll())

            assertEquals(listOf("1", "2"), mirroredIds())
            assertEquals(3, core.mirrors.last().sumOf { it.events.size })
            assertTrue(mirror.state.value.isOn)
            assertEquals(setOf("1", "2"), mirror.state.value.selected)
        }

    @Test
    fun importsNothingWithoutAccessOrWhenTheCoreRefuses() =
        runTest(dispatcher) {
            source.granted = false
            assertNull(mirror().importAll())

            source.granted = true
            core.writeError = CoreException.Store("locked")
            val mirror = mirror()
            assertNull(mirror.importAll())

            assertTrue(core.mirrors.isEmpty())
            assertTrue(mirror.state.value.failed)
            assertFalse(mirror.state.value.isOn)
        }

    @Test
    fun writesNothingWhenThePhoneDidNotChange() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()

            mirror.refresh()

            assertEquals(1, core.mirrors.size)
        }

    @Test
    fun readsAChangedCalendarAgainAndDropsADeletedOne() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()

            // An event was added to calendar 1 and calendar 2 was deleted on the phone.
            source.calendars = mapOf("1" to listOf(FakeDeviceCalendars.event("10@1"), FakeDeviceCalendars.event("11@1")))
            mirror.refresh()

            assertEquals(listOf("1"), mirroredIds())
            assertEquals(
                2,
                core.mirrors
                    .last()
                    .single()
                    .events.size,
            )
        }

    @Test
    fun doesNotMirrorACalendarThatWasNeverTicked() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()
            mirror.toggle("2")
            assertEquals(listOf("1"), mirroredIds())

            // A new calendar appears on the phone: it waits for its tick.
            source.calendars = source.calendars + ("3" to emptyList())
            mirror.refresh()

            assertEquals(listOf("1"), mirroredIds())
            assertEquals(
                listOf("1", "2", "3"),
                mirror.state.value.calendars
                    .map { it.id },
            )
        }

    @Test
    fun readsNothingWhileTheMirrorIsOff() =
        runTest(dispatcher) {
            val mirror = mirror()

            mirror.refresh()

            assertEquals(0, source.reads)
            assertTrue(core.mirrors.isEmpty())
        }

    @Test
    fun emptiesTheMirrorWhenAccessIsTakenBack() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()

            source.granted = false
            mirror.refresh()

            assertTrue(core.mirrors.last().isEmpty())
            assertFalse(mirror.state.value.isOn)
            assertTrue(
                mirror.state.value.calendars
                    .isEmpty(),
            )
        }

    @Test
    fun asksForAccessBeforeTheSwitchTurnsOn() =
        runTest(dispatcher) {
            source.granted = false
            val mirror = mirror()
            mirror.load()

            assertTrue(mirror.setOn(true))
            assertTrue(core.mirrors.isEmpty())

            // Refused: the switch gives way to the settings, and asks no more.
            mirror.accessAnswered(false)
            assertEquals(DeviceCalendarAccess.DENIED, mirror.state.value.access)
            assertFalse(mirror.setOn(true))

            // Given in the settings: coming back reads it, and the switch works.
            source.granted = true
            mirror.load()
            assertFalse(mirror.setOn(true))
            assertEquals(listOf("1", "2"), mirroredIds())
            assertTrue(mirror.state.value.isOn)
        }

    @Test
    fun turnsOnWithTheAnswerToTheRequest() =
        runTest(dispatcher) {
            source.granted = false
            val mirror = mirror()
            mirror.setOn(true)

            source.granted = true
            mirror.accessAnswered(true)

            assertEquals(listOf("1", "2"), mirroredIds())
            assertTrue(mirror.state.value.isOn)
        }

    @Test
    fun keepsTheTicksWhileTheSwitchIsOff() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()
            mirror.toggle("2")

            mirror.setOn(false)
            assertTrue(core.mirrors.last().isEmpty())
            assertEquals(setOf("1"), mirror.state.value.selected)

            mirror.setOn(true)
            assertEquals(listOf("1"), mirroredIds())
        }

    @Test
    fun turnsOffWithTheLastTick() =
        runTest(dispatcher) {
            val mirror = mirror()
            mirror.importAll()

            mirror.toggle("1")
            mirror.toggle("2")

            assertTrue(core.mirrors.last().isEmpty())
            assertFalse(mirror.state.value.isOn)
        }

    @Test
    fun showsWhatTheCoreMirrorsWhenTheScreenOpens() =
        runTest(dispatcher) {
            mirror().importAll()

            // A new process: only the core remembers.
            val mirror = mirror()
            mirror.load()

            assertTrue(mirror.state.value.isOn)
            assertEquals(setOf("1", "2"), mirror.state.value.selected)
            assertEquals(
                listOf(1, 2),
                mirror.state.value.calendars
                    .map { it.eventCount },
            )
        }

    // How CalendarContract's instances become the core's input.

    @Test
    fun readsATimedEventInKoreanTime() {
        // 23:30–00:30 Korean time, whatever the phone's own zone is.
        val input =
            DeviceEventDates.input("7@1", "야식", false, Instant.parse("2026-11-07T14:30:00Z"), Instant.parse("2026-11-07T15:30:00Z"))

        assertEquals(DeviceEventInput("7@1", "야식", false, DayTime("2026-11-07", 1410u), DayTime("2026-11-08", 30u)), input)
    }

    @Test
    fun readsAnAllDayEventByItsUtcDays() {
        // The provider keeps all-day events from UTC midnight to UTC midnight after the last day.
        val oneDay = DeviceEventDates.input("8@1", "휴가", true, Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-08T00:00:00Z"))
        val threeDays =
            DeviceEventDates.input(
                "9@1",
                "여행",
                true,
                Instant.parse("2026-11-07T00:00:00Z"),
                Instant.parse("2026-11-10T00:00:00Z"),
            )
        val noLength =
            DeviceEventDates.input(
                "9@2",
                "기념일",
                true,
                Instant.parse("2026-11-07T00:00:00Z"),
                Instant.parse("2026-11-07T00:00:00Z"),
            )

        assertEquals(DayTime("2026-11-07", 0u) to DayTime("2026-11-07", 0u), oneDay.start to oneDay.end)
        assertEquals(DayTime("2026-11-07", 0u) to DayTime("2026-11-09", 0u), threeDays.start to threeDays.end)
        assertEquals(DayTime("2026-11-07", 0u) to DayTime("2026-11-07", 0u), noLength.start to noLength.end)
    }

    @Test
    fun keysEachRepeatByItsEventAndStart() {
        val first = DeviceEventDates.occurrenceId(42, Instant.parse("2026-11-07T01:00:00Z"))
        val second = DeviceEventDates.occurrenceId(42, Instant.parse("2026-11-14T01:00:00Z"))

        assertEquals("42@1794013200", first)
        assertTrue(first != second)
    }

    @Test
    fun namesACalendarWithItsAccount() {
        val join = { account: String, calendar: String -> "$account · $calendar" }

        assertEquals("회사 · 개인", DeviceEventDates.name("회사", "개인", join))
        assertEquals("개인", DeviceEventDates.name(null, "개인", join))
        // A provider's main calendar is often named after its account.
        assertEquals("me@example.invalid", DeviceEventDates.name("me@example.invalid", "me@example.invalid", join))
    }

    @Test
    fun mirrorsAYearBackAndTwoAhead() {
        val (from, to) = DeviceEventDates.window(Instant.parse("2026-11-07T03:00:00Z"))

        assertEquals(Instant.parse("2025-11-07T03:00:00Z"), from)
        assertEquals(Instant.parse("2028-11-07T03:00:00Z"), to)
    }
}
