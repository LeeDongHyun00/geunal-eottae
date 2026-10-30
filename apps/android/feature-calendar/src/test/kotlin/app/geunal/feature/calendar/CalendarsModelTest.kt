package app.geunal.feature.calendar

import app.geunal.core.CoreException
import app.geunal.core.Palette
import app.geunal.feature.calendar.calendars.CalendarsModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarsModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    private fun TestScope.model() = CalendarsModel(core, scope = this, io = dispatcher)

    @Test
    fun listsThisAppsCalendarsOnly() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            // The device calendars' group comes with the device mirror.
            assertEquals(
                listOf("개인", "알바"),
                model.state.value.calendars
                    .map { it.name },
            )
        }

    @Test
    fun hidesACalendarAndPutsTheSwitchBackWhenRefused() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.setVisible("c2", false)
            assertFalse(
                "the switch moves at once",
                model.state.value.calendars
                    .first { it.id == "c2" }
                    .visible,
            )
            advanceUntilIdle()
            assertEquals("setCalendarVisible c2 false", core.calls.last())

            core.writeError = CoreException.Store("busy")
            model.setVisible("c1", false)
            advanceUntilIdle()
            assertTrue(
                model.state.value.calendars
                    .first { it.id == "c1" }
                    .visible,
            )
            assertTrue(model.state.value.visibilityFailed)
        }

    @Test
    fun aNewCalendarStartsOnAColorNoOtherHas() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.edit(null)

            val sheet = checkNotNull(model.editing.value)
            assertTrue(sheet.isNew)
            // 개인 is tangerine and 알바 mint; the first free color of the twelve.
            assertEquals(Palette.TOMATO, sheet.state.value.color)
        }

    @Test
    fun theLastCalendarCannotBeDeleted() =
        runTest(dispatcher) {
            core.calendarList = listOf(Samples.personal, Samples.device)
            val model = model()
            model.load()

            model.edit(
                model.state.value.calendars
                    .single(),
            )

            val sheet = checkNotNull(model.editing.value)
            assertTrue(sheet.isLast)
            assertFalse(sheet.canDelete)
            assertFalse(sheet.delete())
            assertTrue(core.calls.none { it.startsWith("deleteCalendar") })
        }

    @Test
    fun closesTheSheetAndReadsAgainOnceItSaved() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.edit(null)
            val sheet = checkNotNull(model.editing.value)
            sheet.setName("학교")
            sheet.launchSave()
            advanceUntilIdle()

            assertNull(model.editing.value)
            assertTrue(
                model.state.value.calendars
                    .any { it.name == "학교" },
            )
        }
}
