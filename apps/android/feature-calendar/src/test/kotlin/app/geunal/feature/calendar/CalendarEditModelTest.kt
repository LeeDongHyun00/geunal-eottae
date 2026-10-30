package app.geunal.feature.calendar

import app.geunal.core.AlertRule
import app.geunal.core.CoreException
import app.geunal.core.Palette
import app.geunal.feature.calendar.calendars.CalendarEditModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEditModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    private fun TestScope.model(
        calendar: app.geunal.core.CalendarView? = null,
        isLast: Boolean = false,
    ) = CalendarEditModel(calendar, isLast, used = setOf(Palette.TANGERINE), core = core, scope = this, io = dispatcher)

    @Test
    fun aNewCalendarNeedsANameAndSavesWhatWasPicked() =
        runTest(dispatcher) {
            val model = model()
            assertFalse(model.state.value.canSave)
            assertFalse(model.canDelete)

            model.setName("  운동 ")
            model.setColor(Palette.OLIVE)
            model.setSoft(true)
            model.setDefaultAlert(AlertRule.Before(30))

            assertTrue(model.save())
            assertEquals("saveCalendar 운동 olive soft=true before30", core.calls.last())
        }

    @Test
    fun saysWhatAnInheritingCalendarRingsAt() =
        runTest(dispatcher) {
            val model = model(Samples.personal)

            model.start()
            advanceUntilIdle()

            assertEquals(AlertRule.Inherit, model.state.value.defaultAlert)
            assertEquals(10, model.state.value.settingsAlert)
        }

    @Test
    fun deletesACalendarThatIsNotTheLast() =
        runTest(dispatcher) {
            val model = model(Samples.work)

            assertTrue(model.canDelete)
            assertTrue(model.delete())
            assertEquals("deleteCalendar c2", core.calls.last())
        }

    @Test
    fun keepsWhatWasTypedWhenTheCoreRefuses() =
        runTest(dispatcher) {
            core.writeError = CoreException.Invalid("name")
            val model = model(Samples.work)
            model.setName("야간 알바")

            assertFalse(model.save())
            assertEquals(CalendarEditModel.Problem.SAVE_FAILED, model.state.value.problem)
            assertEquals("야간 알바", model.state.value.name)

            assertFalse(model.delete())
            assertEquals(CalendarEditModel.Problem.DELETE_FAILED, model.state.value.problem)
        }
}
