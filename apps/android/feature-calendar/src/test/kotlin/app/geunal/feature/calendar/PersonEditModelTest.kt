package app.geunal.feature.calendar

import app.geunal.core.AlertRule
import app.geunal.core.BirthdayCalendar
import app.geunal.core.CoreException
import app.geunal.core.PersonSource
import app.geunal.feature.calendar.people.PersonEditModel
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
import java.time.Instant

class PersonEditModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()

    // 2026-10-26 in Seoul.
    private val now = Instant.parse("2026-10-26T01:00:00Z")

    private fun TestScope.model(person: app.geunal.core.Person? = null) =
        PersonEditModel(person, core, scope = this, io = dispatcher, now = { now })

    @Test
    fun aNewPersonNeedsANameAndFollowsTheDefaultAlert() =
        runTest(dispatcher) {
            val model = model()

            assertTrue(model.isNew)
            assertFalse(model.state.value.canSave)
            assertEquals(AlertRule.Inherit, model.state.value.alert)
            assertTrue(model.state.value.suggest)

            model.setName("  서연 ")
            model.setBirthday(11, 19)
            assertTrue(model.save())
            assertEquals("savePerson 서연 11-19", core.calls.last())
        }

    @Test
    fun aLunarBirthdaySaysWhereItFallsThisYear() =
        runTest(dispatcher) {
            val model = model()
            model.setName("엄마")
            model.setLunar(true)
            model.setBirthday(10, 3, leapMonth = true)
            advanceUntilIdle()

            assertEquals("2026-11-11", model.state.value.solarThisYear)
            assertTrue(core.calls.toString(), "birthdayInYear 10-3 2026" in core.calls)
            assertEquals(
                BirthdayCalendar.Lunar(leapMonth = true),
                model.state.value.birthday
                    ?.calendar,
            )

            // Back to solar: no leap month and no solar note.
            model.setLunar(false)
            advanceUntilIdle()
            assertEquals(
                BirthdayCalendar.Solar,
                model.state.value.birthday
                    ?.calendar,
            )
            assertNull(model.state.value.solarThisYear)
        }

    @Test
    fun switchingCalendarsKeepsADayThatCalendarHas() =
        runTest(dispatcher) {
            val model = model()
            model.setName("민수")

            // No lunar month has a 31st: the core would refuse it and the save would only fail.
            model.setBirthday(1, 31)
            model.setLunar(true)
            assertEquals(30, model.state.value.day)

            // Lunar 2월 30일 is a day; the solar February keeps 29 for the leap-year birthdays.
            model.setBirthday(2, 30)
            model.setLunar(false)
            assertEquals(29, model.state.value.day)

            // A day both calendars have stays as it was.
            model.setBirthday(3, 14)
            model.setLunar(true)
            assertEquals(14, model.state.value.day)
            advanceUntilIdle()
        }

    @Test
    fun opensAPersonAndDeletesThem() =
        runTest(dispatcher) {
            val mom = Samples.person("p2", "엄마", 10, 3, lunar = true)
            core.peopleList = listOf(mom)
            val model = model(mom)

            assertFalse(model.isNew)
            assertEquals("엄마", model.state.value.name)
            assertTrue(model.state.value.lunar)
            assertEquals(10, model.state.value.month)

            assertTrue(model.delete())
            assertEquals("deletePerson p2", core.calls.last())
        }

    @Test
    fun aBirthdayAGroupSharedCannotBeDeleted() =
        runTest(dispatcher) {
            val shared = Samples.person("p9", "지영", 4, 1, source = PersonSource.Group("g1"))
            val model = model(shared)

            assertTrue(model.readOnly)
            assertFalse(model.delete())
            // Only the alert is this device's to change.
            model.setAlert(AlertRule.Off)
            assertTrue(model.save())
        }

    @Test
    fun saysSoWhenTheCoreRefuses() =
        runTest(dispatcher) {
            core.writeError = CoreException.Invalid("name")
            val model = model()
            model.setName("서연")

            assertFalse(model.save())
            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.isWorking)
        }
}
