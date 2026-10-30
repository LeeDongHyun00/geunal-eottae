package app.geunal.feature.calendar

import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.ContactImport
import app.geunal.core.ContactInput
import app.geunal.core.CoreException
import app.geunal.feature.calendar.people.ContactsFound
import app.geunal.feature.calendar.people.ContactsImportModel
import app.geunal.feature.calendar.people.ContactsImportModel.Phase
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.testing.CalendarFakeCore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsImportModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val sora = ContactInput("41", "소라", Birthday(6u, 2u, null, BirthdayCalendar.Solar))
    private val jihun = ContactInput("42", "지훈", Birthday(12u, 24u, 1995, BirthdayCalendar.Solar))
    private val reader = Reader(listOf(sora, jihun))
    private var finished: ContactImport? = null

    private class Reader(
        private val list: List<ContactInput>,
    ) : ContactsReader {
        var allowed = true

        override fun canRead(): Boolean = allowed

        override suspend fun birthdays(): ContactsFound =
            if (allowed) ContactsFound(list, withoutBirthday = 3) else ContactsFound(emptyList(), 0)
    }

    private fun TestScope.model() = ContactsImportModel(reader, core, scope = this, io = dispatcher, onFinished = { finished = it })

    @Test
    fun startsWithNobodyChosenAndImportsOnlyThePicked() =
        runTest(dispatcher) {
            val model = model()

            model.start(ask = { error("the permission is there") })
            advanceUntilIdle()
            val read = model.state.value
            assertEquals(Phase.PICKING, read.phase)
            assertEquals(listOf("소라", "지훈"), read.candidates.map { it.name })
            assertEquals(3, read.withoutBirthday)
            assertTrue("nobody is chosen for the person", read.selected.isEmpty())

            model.toggleAll()
            assertTrue(model.state.value.allSelected)
            model.toggleAll()
            assertTrue(
                model.state.value.selected
                    .isEmpty(),
            )

            model.toggle("41")
            model.importChosen()

            assertEquals("importContacts 41", core.calls.last())
            assertEquals(ContactImport(1u, 0u, 0u), finished)
        }

    @Test
    fun asksOnceAndReadsWhenItComesBackTurnedOn() =
        runTest(dispatcher) {
            reader.allowed = false
            val model = model()
            var asked = 0

            model.start(ask = { asked++ })
            // A rotation starts the sheet again while the question is up.
            model.start(ask = { asked++ })
            assertEquals(1, asked)

            model.answered(false)
            assertEquals(Phase.DENIED, model.state.value.phase)
            model.resumed()
            advanceUntilIdle()
            assertEquals(Phase.DENIED, model.state.value.phase)

            // Turned on in the settings, then back in the app.
            reader.allowed = true
            model.resumed()
            advanceUntilIdle()
            assertEquals(Phase.PICKING, model.state.value.phase)
            assertEquals(2, model.state.value.candidates.size)
        }

    @Test
    fun keepsTheChoiceWhenTheCoreRefuses() =
        runTest(dispatcher) {
            val model = model()
            model.start(ask = {})
            advanceUntilIdle()
            model.toggle("42")

            core.writeError = CoreException.Store("busy")
            model.importChosen()

            assertEquals(Phase.PICKING, model.state.value.phase)
            assertTrue(model.state.value.failed)
            assertEquals(setOf("42"), model.state.value.selected)
            assertNull(finished)
        }

    @Test
    fun choosesOnlyContactsItShowed() =
        runTest(dispatcher) {
            val model = model()
            model.toggle("41")
            assertTrue(
                "nothing to choose before the contacts are read",
                model.state.value.selected
                    .isEmpty(),
            )

            model.start(ask = {})
            advanceUntilIdle()
            model.toggle("99")
            assertFalse(
                model.state.value.selected
                    .contains("99"),
            )
        }
}
