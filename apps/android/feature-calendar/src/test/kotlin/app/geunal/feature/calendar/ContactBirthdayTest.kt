package app.geunal.feature.calendar

import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.feature.calendar.people.ContactBirthday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactBirthdayTest {
    @Test
    fun readsTheTwoFormsContactsAppsWrite() {
        assertEquals(Birthday(3u, 14u, 1990, BirthdayCalendar.Solar), ContactBirthday.parse("1990-03-14"))
        assertEquals(Birthday(6u, 2u, null, BirthdayCalendar.Solar), ContactBirthday.parse("--06-02"))
        // Some apps add a time after the day.
        assertEquals(Birthday(12u, 1u, 2001, BirthdayCalendar.Solar), ContactBirthday.parse("2001-12-01T00:00:00Z"))
    }

    @Test
    fun skipsWhatIsNotADay() {
        assertNull(ContactBirthday.parse(null))
        assertNull(ContactBirthday.parse("내 생일"))
        assertNull(ContactBirthday.parse("1990-13-01"))
        assertNull(ContactBirthday.parse("--02-32"))
    }
}
