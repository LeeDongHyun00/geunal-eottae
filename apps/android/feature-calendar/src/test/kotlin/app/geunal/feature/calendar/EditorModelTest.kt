package app.geunal.feature.calendar

import app.geunal.core.AlertRule
import app.geunal.core.CoreException
import app.geunal.core.DayTime
import app.geunal.core.EditScope
import app.geunal.core.EventInput
import app.geunal.core.Frequency
import app.geunal.core.Palette
import app.geunal.core.Recurrence
import app.geunal.core.Weekday
import app.geunal.feature.calendar.editor.AlertText
import app.geunal.feature.calendar.editor.EditorModel
import app.geunal.feature.calendar.editor.EditorTarget
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

class EditorModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val weekly = Recurrence(Frequency.WEEKLY, 1u, emptyList(), null, null)

    private fun TestScope.model(target: EditorTarget) = EditorModel(target, core, scope = this, io = dispatcher)

    @Test
    fun aNewEventKeepsItsLengthWhenTheStartMoves() =
        runTest(dispatcher) {
            val model = model(EditorTarget.New("2026-10-30"))
            model.load()

            assertTrue(model.isNew)
            assertEquals(
                "c1",
                model.state.value.selectedCalendar
                    ?.id,
            )
            assertFalse("no title yet", model.state.value.canSave)

            model.setTitle("야간")
            assertTrue(model.state.value.canSave)

            // 09:00–10:00 moved to 23:00 ends at midnight, the start of the next day.
            model.setStart("2026-10-30", 1380)
            assertEquals("2026-10-31", model.state.value.endDay)
            assertEquals(0, model.state.value.endMinute)
            assertEquals(DayTime("2026-10-31", 0u), model.input()?.end)

            model.setEnd("2026-10-30", 1320)
            assertFalse(model.state.value.hasValidRange)
            assertFalse(model.state.value.canSave)
        }

    @Test
    fun savesWhatTheQuickEntryHandedOver() =
        runTest(dispatcher) {
            val draft = Samples.input("카페 마감", "2026-10-30", 1020, 1320, Samples.work).copy(end = DayTime("2026-10-31", 0u))
            val model = model(EditorTarget.New("2026-10-30", draft))
            model.load()

            val state = model.state.value
            assertEquals("카페 마감", state.title)
            assertEquals("c2", state.selectedCalendar?.id)
            // Midnight at the end of the day reads as 24:00 of the start day.
            assertEquals("2026-10-30", state.endDay)
            assertEquals(1440, state.endMinute)

            assertTrue(model.save())
            val (scope, input) = core.inputs.single()
            assertNull("an add has no scope", scope)
            assertEquals(draft, input)
        }

    @Test
    fun anEventEndingAtMidnightTurnedAllDayShowsTheDayItKeeps() =
        runTest(dispatcher) {
            val late = Samples.event("e2", "야간 근무", "2026-10-26", 1320, 1440)
            core.details = mapOf("e2" to Samples.detail(late))
            val model = model(EditorTarget.Existing("e2", null))
            model.load()
            // Timed, 24:00 shows as 00:00 of the next day.
            assertEquals("2026-10-27" to 0, model.state.value.shownEnd)

            model.setAllDay(true)

            assertEquals("2026-10-26" to 1440, model.state.value.shownEnd)
            assertEquals("2026-10-26", model.input()?.end?.day)
        }

    @Test
    fun theInheritedAlertFollowsTheCalendarNotTheOldOverride() =
        runTest(dispatcher) {
            val hourly = Samples.calendar("c5", "회사", Palette.SKY).copy(defaultAlert = AlertRule.Before(60))
            core.calendarList = core.calendarList + hourly
            val meeting = Samples.event("e3", "회의", "2026-11-03", 600, 660, calendar = hourly)
            core.details = mapOf("e3" to Samples.detail(meeting).copy(alert = AlertRule.Before(10), effectiveAlert = 10))
            val model = model(EditorTarget.Existing("e3", null))
            model.load()

            model.setAlert(AlertRule.Inherit)

            // 회사 rings an hour before, which is what saving now schedules.
            assertEquals(60, model.state.value.inheritedAlert)
            model.setCalendar(Samples.personal.id)
            assertEquals(10, model.state.value.inheritedAlert)
            model.setAllDay(true)
            assertEquals(360, model.state.value.inheritedAlert)
        }

    @Test
    fun opensARepeatingEventAndChangesOnlyThisOccurrence() =
        runTest(dispatcher) {
            val lecture = Samples.event("e1", "전공 수업", "2026-11-02", 600, 720).copy(occurrence = "2026-11-02")
            core.details = mapOf("e1" to Samples.detail(lecture, recurrence = weekly))
            val model = model(EditorTarget.Existing("e1", "2026-11-02"))
            model.load()

            assertTrue(model.state.value.wasRepeating)
            assertTrue(model.asksScope)
            // 개인 follows the settings' 10분 전.
            assertEquals(
                AlertText.Inherited(AlertText.Minutes(10)),
                AlertText.value(model.state.value.alert, model.state.value.inheritedAlert, allDay = false),
            )

            model.setTitle("휴강")
            assertTrue(model.save(EditScope.THIS_ONLY))

            val (scope, input) = core.inputs.single()
            assertEquals(EditScope.THIS_ONLY, scope)
            assertEquals("휴강", input.title)
            assertEquals(weekly, input.recurrence)
            assertTrue(core.calls.toString(), "update e1 WEEKLY" in core.calls)
        }

    @Test
    fun aDeviceEventOpensReadOnly() =
        runTest(dispatcher) {
            val dentist = Samples.event("d9", "치과", "2026-10-29", 600, 660, Samples.device)
            core.details = mapOf("d9" to Samples.detail(dentist))
            val model = model(EditorTarget.Existing("d9", null))
            model.load()

            val state = model.state.value
            assertTrue(state.readOnly)
            assertEquals("d1", state.selectedCalendar?.id)
            assertFalse(state.canSave)
            assertFalse(model.save())
            assertFalse(model.delete())
            assertTrue(core.inputs.isEmpty())
        }

    @Test
    fun saysSoWhenTheEventIsGone() =
        runTest(dispatcher) {
            val model = model(EditorTarget.Existing("gone", null))
            model.load()
            model.setTitle("다시")

            assertEquals(EditorModel.Problem.NOT_FOUND, model.state.value.problem)
            assertFalse(model.state.value.canSave)
        }

    @Test
    fun deletesAnEventAndSaysWhenTheCoreRefuses() =
        runTest(dispatcher) {
            val dinner = Samples.event("e2", "가족 · 저녁", "2026-10-27", 1140, 1260)
            core.details = mapOf("e2" to Samples.detail(dinner))
            val model = model(EditorTarget.Existing("e2", null))
            model.load()

            assertFalse("a single event asks no scope", model.asksScope)
            core.deleteError = CoreException.Store("busy")
            assertFalse(model.delete())
            assertEquals(EditorModel.Problem.DELETE_FAILED, model.state.value.problem)
            assertFalse(model.state.value.isWorking)

            core.deleteError = null
            assertTrue(model.delete())
            assertNull(model.state.value.problem)
            assertEquals("delete e2 THIS_AND_FOLLOWING", core.calls.last())
        }

    @Test
    fun saysSoWhenASaveIsRefused() =
        runTest(dispatcher) {
            val dinner = Samples.event("e2", "가족 · 저녁", "2026-10-27", 1140, 1260)
            core.details = mapOf("e2" to Samples.detail(dinner))
            core.updateError = CoreException.Invalid("title")
            val model = model(EditorTarget.Existing("e2", null))
            model.load()

            assertFalse(model.save())
            assertEquals(EditorModel.Problem.SAVE_FAILED, model.state.value.problem)
            assertTrue("it can be tried again", model.state.value.canSave)
        }

    @Test
    fun turnsAllDayOffToNineOClockOfItsFirstDay() =
        runTest(dispatcher) {
            val leave =
                EventInput("", "연차", allDay = true, DayTime("2026-10-30", 0u), DayTime("2026-10-30", 0u), null, AlertRule.Inherit, null)
            val model = model(EditorTarget.New("2026-10-30", leave))
            model.load()

            assertTrue(model.state.value.allDay)
            assertTrue(model.state.value.hasValidRange)
            // An empty calendar id leaves the choice to the editor: the first own calendar.
            assertEquals("c1", model.input()?.calendarId)

            model.setAllDay(false)
            val state = model.state.value
            assertEquals(540, state.startMinute)
            assertEquals("2026-10-30", state.endDay)
            assertEquals(600, state.endMinute)
        }

    @Test
    fun offersTheEventsOwnRuleAmongTheRepeatChips() =
        runTest(dispatcher) {
            val classes = Recurrence(Frequency.WEEKLY, 1u, listOf(Weekday.TUESDAY, Weekday.THURSDAY), null, null)
            val gym = Samples.event("e3", "운동", "2026-10-27", 1140, 1200)
            core.details = mapOf("e3" to Samples.detail(gym, recurrence = classes))
            val model = model(EditorTarget.Existing("e3", "2026-10-27"))
            model.load()

            val choices = model.repeatChoices()
            assertEquals(
                listOf(
                    null,
                    Frequency.WEEKLY,
                    Frequency.WEEKLY,
                    Frequency.DAILY,
                    Frequency.MONTHLY,
                    Frequency.YEARLY,
                ),
                choices.map {
                    it?.frequency
                },
            )
            assertEquals(classes, choices[2])

            // A stray tap on "안 함" can be taken back: the event's own rule keeps its chip.
            model.setRecurrence(null)
            assertEquals(classes, model.repeatChoices()[2])

            // A rule from the custom sheet joins it rather than replacing it.
            val biweekly = classes.copy(interval = 2u)
            model.setRecurrence(biweekly)
            assertEquals(listOf(classes, biweekly), model.repeatChoices().subList(2, 4))
        }
}
