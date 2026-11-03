package app.geunal.feature.calendar

import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.ContactImport
import app.geunal.core.ContactInput
import app.geunal.feature.calendar.people.ContactsFound
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.people.PeopleModel
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PeopleModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val minsu = Samples.person("p1", "민수", 11, 4, next = "2026-11-04", days = 5)
    private val mom = Samples.person("p2", "엄마", 10, 3, lunar = true, next = "2026-11-11", days = 12)
    private val core =
        CalendarFakeCore().apply {
            peopleList = listOf(minsu, mom, Samples.person("p3", "가영", 3, 14))
            upcomingList = listOf(minsu, mom)
        }
    private val sora = ContactInput("41", "소라", Birthday(6u, 2u, null, BirthdayCalendar.Solar))
    private val contacts =
        object : ContactsReader {
            override fun canRead(): Boolean = true

            override suspend fun birthdays(): ContactsFound = ContactsFound(listOf(sora), withoutBirthday = 2)
        }

    // 2026-10-30 10:00 in Seoul.
    private var now = Instant.parse("2026-10-30T01:00:00Z")

    private fun TestScope.model(focus: String? = null) =
        PeopleModel(core, scope = this, contacts = contacts, io = dispatcher, focus = focus, now = { now })

    @Test
    fun readsEveryoneAndTheComingBirthdaysAndSearchesBoth() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            assertEquals("2026-10-30", model.state.value.today)
            assertEquals(
                listOf("민수", "엄마"),
                model.state.value.shownUpcoming
                    .map { it.name },
            )
            assertEquals(3, model.state.value.shownPeople.size)

            model.setQuery(" 엄 ")
            assertEquals(
                listOf("엄마"),
                model.state.value.shownUpcoming
                    .map { it.name },
            )
            assertEquals(
                listOf("엄마"),
                model.state.value.shownPeople
                    .map { it.name },
            )
        }

    @Test
    fun opensThePersonABirthdayLedTo() =
        runTest(dispatcher) {
            val model = model(focus = "p2")

            model.start()
            advanceUntilIdle()

            assertEquals(
                "p2",
                model.editing.value
                    ?.person
                    ?.id,
            )
        }

    @Test
    fun importsWhatWasChosenAndSaysHowItWent() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.openImport()
            val import = checkNotNull(model.importing.value)
            import.start(ask = {})
            advanceUntilIdle()
            import.toggle("41")
            import.importChosen()
            advanceUntilIdle()

            assertNull(model.importing.value)
            assertEquals(ContactImport(1u, 0u, 0u), model.imported.value)
            assertTrue(
                model.state.value.people
                    .any { it.name == "소라" },
            )
            model.importShown()
            assertNull(model.imported.value)
        }

    @Test
    fun theNewPersonSheetHandsOverToTheImportAndBack() =
        runTest(dispatcher) {
            val model = model()

            model.edit(null)
            model.openImport()
            assertNull(model.editing.value)
            assertNotNull(model.importing.value)

            // "직접 추가" from the import: the new person's sheet in its place.
            model.addByHand()
            assertNull(model.importing.value)
            assertTrue(checkNotNull(model.editing.value).isNew)
        }

    @Test
    fun closesTheSheetAndReadsAgainOnceItSaved() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            model.edit(null)
            val sheet = checkNotNull(model.editing.value)
            sheet.setName("서연")
            sheet.launchSave()
            advanceUntilIdle()

            assertNull(model.editing.value)
            assertTrue(
                model.state.value.people
                    .any { it.name == "서연" },
            )
        }

    @Test
    fun aSaveThatEndsLateLeavesTheNextSheetOpen() =
        runTest(dispatcher) {
            val model = model()
            model.load()

            // Saved, then closed with its close button before the core answered, and another person opened.
            model.edit(null)
            val first = checkNotNull(model.editing.value)
            first.setName("서연")
            first.launchSave()
            model.closeEditor()
            model.edit(minsu)
            val second = checkNotNull(model.editing.value)
            second.setName("민수 형")
            advanceUntilIdle()

            assertSame(second, model.editing.value)
            assertEquals("민수 형", second.state.value.name)
            assertTrue(
                model.state.value.people
                    .any { it.name == "서연" },
            )
        }

    @Test
    fun readsAgainWhenTheDayTurned() =
        runTest(dispatcher) {
            val model = model()
            model.load()
            core.upcomingList = listOf(mom)

            // The same day: nothing to read again.
            model.resumed()
            advanceUntilIdle()
            assertEquals(2, model.state.value.upcoming.size)

            now = Instant.parse("2026-10-30T15:30:00Z")
            model.resumed()
            advanceUntilIdle()
            assertEquals("2026-10-31", model.state.value.today)
            assertEquals(
                listOf("엄마"),
                model.state.value.upcoming
                    .map { it.name },
            )
        }
}
