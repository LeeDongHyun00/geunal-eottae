package app.geunal.feature.calendar

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.ContactInput
import app.geunal.designsystem.GeunalTheme
import app.geunal.designsystem.components.ToastCenter
import app.geunal.feature.calendar.people.ContactsFound
import app.geunal.feature.calendar.people.ContactsReader
import app.geunal.feature.calendar.people.PeopleModel
import app.geunal.feature.calendar.people.PeopleScreen
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 사람·생일 (m1.md screen 11) and the person sheet (screen 17) on a fake core and fake contacts. */
@RunWith(AndroidJUnit4::class)
class PeopleScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val core = CalendarFakeCore()
    private val contacts = FakeContacts()
    private var closed = false

    private fun show(focus: String? = null) {
        val model = PeopleModel(core, MainScope(), contacts, focus = focus)
        val toasts = ToastCenter(MainScope())
        compose.setContent { GeunalTheme { PeopleScreen(model = model, toasts = toasts, onClose = { closed = true }) } }
    }

    @Test
    fun listsTheComingBirthdaysAndSearchesEveryone() {
        val minsu = Samples.person("p1", "민수", 11, 4, next = "2026-11-04", days = 5)
        val mom = Samples.person("p2", "엄마", 10, 3, lunar = true, next = "2026-11-11", days = 12)
        core.peopleList = listOf(Samples.person("p3", "가영", 3, 14), minsu, mom)
        core.upcomingList = listOf(minsu, mom)
        show()
        compose.waitForTag("people.upcoming.p1")
        compose.onNodeWithTag("people.upcoming.p1").assertTextContains("D-5")
        compose.onNodeWithTag("people.upcoming.p2").assertTextContains("음력 10월 3일 · ", substring = true)
        compose.onNodeWithTag("people.person.p3").assertTextContains("3월 14일")
        compose.screenshot("11-people")

        // A search narrows both lists to the names that hold it.
        compose.onNodeWithTag("people.search").performTextInput("엄")
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("people.person.p3")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("people.person.p2").assertExists()
        compose.onNodeWithTag("people.upcoming.p2").assertExists()
        compose.onNodeWithTag("people.upcoming.p1").assertDoesNotExist()

        compose.onNodeWithTag("people.back").performClick()
        compose.waitUntil(5_000) { closed }
    }

    @Test
    fun addsSomeoneWithALunarBirthday() {
        show()
        compose.waitForTag("people.empty.add")

        compose.onNodeWithTag("people.empty.add").performClick()
        compose.waitForTag("person.name")
        compose.onNodeWithTag("person.save").assertIsNotEnabled()
        compose.onNodeWithTag("person.name").performTextInput("엄마")
        // Done puts the keyboard away, so the sheet is whole again for the rows under the name.
        compose.onNodeWithTag("person.name").performImeAction()
        compose.onNodeWithText("음력").performScrollTo().performClick()
        compose.onNodeWithTag("person.birthday").performScrollTo().performClick()
        compose.waitForTag("person.month.10")
        compose.onNodeWithTag("person.month.10").performClick()
        compose.onNodeWithTag("person.day.3").performScrollTo().performClick()
        compose.onNodeWithTag("person.pickDone").performClick()

        // Where the lunar day falls this year comes from the core.
        compose.waitFor(hasTestTag("person.lunarNote") and hasText("올해는 11월 11일", substring = true))
        compose.onNodeWithTag("person.birthday").assertTextContains("음력 10월 3일")
        compose.screenshot("17-person-edit--lunar", sheet = true)

        compose.onNodeWithTag("person.save").performScrollTo().performClick()
        compose.waitForTag("people.person.p1")
        assertEquals("savePerson 엄마 10-3 lunar", core.calls.last { it.startsWith("savePerson") })
    }

    @Test
    fun importsOnlyTheContactsLeftChosen() {
        core.peopleList = listOf(Samples.person("p1", "민수", 11, 4))
        contacts.list =
            listOf(
                ContactInput("41", "소라", Birthday(6u, 2u, null, BirthdayCalendar.Solar)),
                ContactInput("42", "지훈", Birthday(12u, 24u, 1995, BirthdayCalendar.Solar)),
            )
        show()
        compose.waitForTag("people.import")

        compose.onNodeWithTag("people.import").performScrollTo().performClick()
        compose.waitForTag("contacts.42")
        // Nobody is chosen for the person (docs/security/app-review.md §2 "연락처").
        compose.onNodeWithTag("contacts.import").assertIsNotEnabled()
        compose.onNodeWithTag("contacts.41").performClick()
        compose.screenshot("11-people--import", sheet = true)
        compose.onNodeWithTag("contacts.import").performClick()

        compose.waitForText("1명을 가져왔어요")
        compose.waitForTag("people.person.c-41")
        assertEquals("importContacts 41", core.calls.last { it.startsWith("importContacts") })
    }

    @Test
    fun opensSomeoneFromTheListAndDeletesThem() {
        core.peopleList = listOf(Samples.person("p1", "민수", 11, 4), Samples.person("p2", "가영", 3, 14))
        show()
        compose.waitForTag("people.person.p2")

        compose.onNodeWithTag("people.person.p2").performClick()
        compose.waitForTag("person.delete")
        compose.onNodeWithTag("person.birthday").assertTextContains("3월 14일")
        compose.onNodeWithTag("person.delete").performScrollTo().performClick()
        // Asked once first: "가영을 지울까요?"
        compose.waitForText("가영을 지울까요?")
        compose.onNodeWithTag("delete.confirm").performClick()

        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("people.person.p2")).fetchSemanticsNodes().isEmpty() }
        assertEquals("deletePerson p2", core.calls.last())
    }

    @Test
    fun theNewPersonSheetHandsOverToTheImport() {
        core.peopleList = listOf(Samples.person("p1", "민수", 11, 4))
        contacts.list = listOf(ContactInput("41", "소라", Birthday(6u, 2u, null, BirthdayCalendar.Solar)))
        show()
        compose.waitForTag("people.add")

        compose.onNodeWithTag("people.add").performClick()
        compose.waitForTag("person.fromContacts")
        compose.onNodeWithTag("person.fromContacts").performScrollTo().performClick()

        compose.waitForTag("contacts.41")
        compose.onNodeWithTag("person.name").assertDoesNotExist()
    }

    @Test
    fun opensThePersonABirthdayLedTo() {
        core.peopleList = listOf(Samples.person("p1", "민수", 11, 4), Samples.person("p2", "엄마", 10, 3, lunar = true))
        show(focus = "p2")

        compose.waitForTag("person.delete")
        compose.onNodeWithTag("person.birthday").assertTextContains("음력 10월 3일")
    }

    private class FakeContacts : ContactsReader {
        @Volatile var list: List<ContactInput> = emptyList()

        override fun canRead(): Boolean = true

        override suspend fun birthdays(): ContactsFound = ContactsFound(list, withoutBirthday = 1)
    }
}
