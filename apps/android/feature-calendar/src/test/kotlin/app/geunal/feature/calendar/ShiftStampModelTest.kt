package app.geunal.feature.calendar

import app.geunal.core.CoreException
import app.geunal.core.ShiftDay
import app.geunal.feature.calendar.shift.ShiftStampModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftStampModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core =
        CalendarFakeCore().apply {
            templateList = listOf(Samples.template("t1", "오픈", 480, 780, tint = 2), Samples.template("t2", "마감", 1320, 120, tint = 5))
        }

    private fun TestScope.model(month: String = "2026-11") = ShiftStampModel(month, core, scope = this, io = dispatcher)

    @Test
    fun stampsADraftUntilDone() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            assertEquals("t1", model.state.value.selectedTemplateId)
            // November 2026 starts on a Sunday: five whole weeks, the last ending on 5 December.
            assertEquals(5, model.state.value.weeks.size)

            model.tap("2026-11-07")
            model.select("t2")
            model.tap("2026-11-14")
            // The same template again takes it off, and once more puts it back.
            model.tap("2026-11-14")
            assertTrue(
                model.state.value
                    .templates(on = "2026-11-14")
                    .isEmpty(),
            )
            model.tap("2026-11-14")

            val state = model.state.value
            assertEquals(2, state.countThisMonth)
            assertEquals(listOf("마감"), state.templates(on = "2026-11-14").map { it.name })
            assertEquals("알바", state.calendarName(of = state.selectedTemplate!!))
            assertTrue("nothing is saved before 완료", core.shifts.isEmpty())

            assertTrue(model.commit())
            assertEquals("setShifts 2026-11-07=t1 2026-11-14=t2", core.calls.last())
            assertFalse(model.state.value.hasChanges)
        }

    @Test
    fun anotherTemplateReplacesTheDaysShift() =
        runTest(dispatcher) {
            core.shifts = mapOf("2026-11-03" to listOf("t1"))
            val model = model()
            model.load()

            model.select("t2")
            model.tap("2026-11-03")

            assertEquals(listOf(ShiftDay("2026-11-03", listOf("t2"))), model.state.value.changedDays)
        }

    @Test
    fun knowsTheShiftsOnTheNextMonthsDaysOfTheGrid() =
        runTest(dispatcher) {
            core.shifts = mapOf("2026-12-01" to listOf("t1"))
            val model = model()
            model.load()

            assertTrue(
                model.state.value.weeks
                    .flatten()
                    .contains("2026-12-01"),
            )
            assertEquals(
                listOf("오픈"),
                model.state.value
                    .templates(on = "2026-12-01")
                    .map { it.name },
            )

            model.tap("2026-12-01")
            assertEquals(listOf(ShiftDay("2026-12-01", emptyList())), model.state.value.changedDays)

            // Moving on to December must not read the taps away.
            model.show("2026-12")
            assertEquals(listOf(ShiftDay("2026-12-01", emptyList())), model.state.value.changedDays)
        }

    @Test
    fun aDayWhoseMonthWasNotReadTakesNoTap() =
        runTest(dispatcher) {
            core.readError = CoreException.Store("busy")
            val model = model()
            model.load()

            model.tap("2026-11-07")

            assertFalse(model.state.value.hasChanges)
        }

    @Test
    fun choosesTheTemplateJustSavedAndDropsADeletedOnesStamps() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            model.tap("2026-11-07")

            model.editTemplate(null)
            assertNotNull(model.editing.value)
            core.templateList += Samples.template("t3", "미들", 660, 960)
            model.templateSaved("t3")
            assertNull(model.editing.value)
            assertEquals("t3", model.state.value.selectedTemplateId)

            // Its stamped shifts stay as ordinary events; only the draft forgets the template.
            core.templateList = core.templateList.filterNot { it.id == "t1" }
            model.templateDeleted("t1")
            assertTrue(
                model.state.value
                    .templates(on = "2026-11-07")
                    .isEmpty(),
            )
            assertFalse(model.state.value.hasChanges)
            assertEquals("t3", model.state.value.selectedTemplateId)
        }

    @Test
    fun keepsTheDraftWhenTheCoreRefuses() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            model.tap("2026-11-07")
            core.writeError = CoreException.Store("busy")

            assertFalse(model.commit())

            assertTrue(model.state.value.failed)
            assertTrue(model.state.value.hasChanges)
            assertFalse(model.state.value.isSaving)
        }
}
