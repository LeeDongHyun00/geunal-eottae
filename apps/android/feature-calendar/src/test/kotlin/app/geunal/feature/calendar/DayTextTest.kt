package app.geunal.feature.calendar

import app.geunal.feature.calendar.support.DayLabel
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

class DayTextTest {
    @Test
    fun namesTheNearDays() {
        assertEquals(DayLabel.Today, DayLabel.of("2026-10-25", today = "2026-10-25"))
        assertEquals(DayLabel.Tomorrow, DayLabel.of("2026-10-26", today = "2026-10-25"))
        assertEquals(DayLabel.DayAfterTomorrow, DayLabel.of("2026-10-27", today = "2026-10-25"))
        assertEquals(DayLabel.ThisYear(11, 7, DayOfWeek.SATURDAY), DayLabel.of("2026-11-07", today = "2026-10-25"))
        assertEquals(DayLabel.OtherYear(2027, 1, 3), DayLabel.of("2027-01-03", today = "2026-10-25"))
    }

    @Test
    fun keepsADayItCannotReadAsItCame() {
        assertEquals(DayLabel.Raw("someday"), DayLabel.of("someday", today = "2026-10-25"))
    }

    @Test
    fun writesTimesWithTwentyFourHours() {
        assertEquals("09:00–18:00", DayText.range(540, 1080))
        assertEquals("24:00", DayText.clock(1440))
        assertEquals("00:05", DayText.clock(5))
    }

    @Test
    fun saysHowLongUntilWithTwoUnitsAtMost() {
        val now = Instant.parse("2026-10-26T05:20:00Z")
        assertEquals(Until(0, 3, 40), Until.of(now.plusSeconds(13_200), now))
        assertEquals(Until(1, 3, 0), Until.of(now.plusSeconds(97_200), now))
        assertEquals(Until(0, 0, 4), Until.of(now.plusSeconds(240), now))
        // A part of a minute counts as a whole one, so "1분 뒤" shows until the event starts.
        assertEquals(Until(0, 0, 1), Until.of(now.plusSeconds(10), now))
        assertNull(Until.of(now, now))
    }

    @Test
    fun computesDaysInKoreanTime() {
        // 15:30 UTC on the 26th is already the 27th in Seoul.
        assertEquals("2026-10-27", KoreanCalendar.day(Instant.parse("2026-10-26T15:30:00Z")))
        assertEquals(DayOfWeek.SATURDAY, KoreanCalendar.weekday("2026-11-07"))
    }
}
