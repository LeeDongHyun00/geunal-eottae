package app.geunal.feature.calendar

import app.geunal.core.AlertRule
import app.geunal.core.CoreException
import app.geunal.feature.calendar.shift.TemplateEditModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEditModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    private fun TestScope.model(template: app.geunal.core.ShiftTemplate? = null) =
        TemplateEditModel(template, core, scope = this, io = dispatcher)

    @Test
    fun aNewTemplateStartsAtNineToSixAndGoesInTheCoresCalendar() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            assertTrue(model.isNew)
            assertEquals(540, model.state.value.startMinute)
            assertEquals(1080, model.state.value.endMinute)
            assertNull("the core picks '알바'", model.state.value.selectedCalendar)
            assertFalse("no name yet", model.state.value.canSave)

            model.setName(" 오픈 ")
            model.setStart(480)
            model.setEnd(780)
            assertEquals(300, model.state.value.lengthMinutes)

            assertEquals("t1", model.save())
            assertEquals("saveTemplate 오픈 480-780 -", core.calls.last())
        }

    @Test
    fun anEndAtOrBeforeTheStartRunsIntoTheNextDay() =
        runTest(dispatcher) {
            val model = model()
            model.setName("마감")
            model.setStart(1320)
            model.setEnd(120)

            assertTrue(model.state.value.crossesMidnight)
            assertEquals(240, model.state.value.lengthMinutes)

            model.setEnd(1320)
            assertFalse("no length at all", model.state.value.canSave)
        }

    @Test
    fun editsAndDeletesATemplate() =
        runTest(dispatcher) {
            val open = Samples.template("t1", "오픈", 480, 780, tint = 2)
            core.templateList = listOf(open)
            val model = model(open)
            model.load()

            assertFalse(model.isNew)
            assertEquals("오픈", model.state.value.name)
            assertEquals(
                "c2",
                model.state.value.selectedCalendar
                    ?.id,
            )
            model.setAlert(AlertRule.Before(30))
            assertEquals("t1", model.save())
            assertEquals(AlertRule.Before(30), core.templateList.single().alert)

            assertTrue(model.delete())
            assertEquals("deleteTemplate t1", core.calls.last())
        }

    @Test
    fun saysSoWhenTheCoreRefuses() =
        runTest(dispatcher) {
            core.writeError = CoreException.Invalid("name")
            val model = model()
            model.setName("오픈")

            assertNull(model.save())
            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.isWorking)
        }
}
