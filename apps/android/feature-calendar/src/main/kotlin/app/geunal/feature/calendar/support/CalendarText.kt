package app.geunal.feature.calendar.support

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import app.geunal.core.Frequency
import app.geunal.core.Recurrence
import app.geunal.core.Weekday
import app.geunal.feature.calendar.R
import java.time.DayOfWeek

/** A day as a title: "10월 30일 금요일", with the year in front in another year than today's (tone.md "날짜와 시간"). */
data class DayTitle(
    val year: Int?,
    val month: Int,
    val day: Int,
    val weekday: DayOfWeek,
) {
    companion object {
        fun of(
            day: String,
            today: String,
        ): DayTitle? {
            val date = KoreanCalendar.parse(day) ?: return null
            val sameYear = KoreanCalendar.parse(today)?.year == date.year
            return DayTitle(if (sameYear) null else date.year, date.monthValue, date.dayOfMonth, date.dayOfWeek)
        }
    }
}

/** The calendar header's small line and big title (boards/Month, Week, List). */
sealed interface Heading {
    /** "2026년" over "10월". */
    data class Month(
        val year: Int,
        val month: Int,
    ) : Heading

    /** "2026년 10월" over "25–31일". */
    data class WeekInMonth(
        val year: Int,
        val month: Int,
        val firstDay: Int,
        val lastDay: Int,
    ) : Heading

    /** "2026년", or "2026–2027년" across New Year, over "11월 29일–12월 5일". */
    data class WeekAcross(
        val firstYear: Int,
        val lastYear: Int,
        val firstMonth: Int,
        val firstDay: Int,
        val lastMonth: Int,
        val lastDay: Int,
    ) : Heading

    /** The list view: "2026년" over "다가오는 일정". */
    data class Upcoming(
        val year: Int,
    ) : Heading

    companion object {
        fun month(month: String): Heading? {
            val date = KoreanCalendar.parse("$month-01") ?: return null
            return Month(date.year, date.monthValue)
        }

        /** The week from [start], a Sunday. */
        fun week(start: String): Heading? {
            val first = KoreanCalendar.parse(start) ?: return null
            val last = first.plusDays(6)
            return if (first.monthValue == last.monthValue) {
                WeekInMonth(first.year, first.monthValue, first.dayOfMonth, last.dayOfMonth)
            } else {
                WeekAcross(first.year, last.year, first.monthValue, first.dayOfMonth, last.monthValue, last.dayOfMonth)
            }
        }
    }
}

/** How a repeat rule reads in the list view and the quick entry's chip: "매주 금요일", "매주 월·수" (m1.md screen 8). */
sealed interface RepeatText {
    data class Daily(
        val interval: Int,
    ) : RepeatText

    /** One day reads in full ("금요일"); several are short and joined by a middle dot ("월·수"). */
    data class Weekly(
        val interval: Int,
        val weekdays: List<DayOfWeek>,
    ) : RepeatText

    data class Monthly(
        val interval: Int,
        val day: Int,
    ) : RepeatText

    data class Yearly(
        val interval: Int,
    ) : RepeatText

    companion object {
        /** [startDay] gives the weekday or day of month a rule without its own days falls on. */
        fun of(
            recurrence: Recurrence,
            startDay: String,
        ): RepeatText {
            val interval = recurrence.interval.toInt().coerceAtLeast(1)
            return when (recurrence.frequency) {
                Frequency.DAILY -> Daily(interval)
                Frequency.WEEKLY ->
                    Weekly(
                        interval,
                        recurrence.weekdays
                            .map { it.dayOfWeek }
                            .sortedBy { it.value % 7 }
                            .ifEmpty { listOfNotNull(KoreanCalendar.weekday(startDay)) },
                    )
                Frequency.MONTHLY -> Monthly(interval, KoreanCalendar.parse(startDay)?.dayOfMonth ?: 1)
                Frequency.YEARLY -> Yearly(interval)
            }
        }
    }
}

/** The core's weekday as java.time's. */
val Weekday.dayOfWeek: DayOfWeek
    get() =
        when (this) {
            Weekday.SUNDAY -> DayOfWeek.SUNDAY
            Weekday.MONDAY -> DayOfWeek.MONDAY
            Weekday.TUESDAY -> DayOfWeek.TUESDAY
            Weekday.WEDNESDAY -> DayOfWeek.WEDNESDAY
            Weekday.THURSDAY -> DayOfWeek.THURSDAY
            Weekday.FRIDAY -> DayOfWeek.FRIDAY
            Weekday.SATURDAY -> DayOfWeek.SATURDAY
        }

/** Sunday 0 through Saturday 6, the order of the week rows. */
val DayOfWeek.column: Int get() = value % 7

object ChipTime {
    /**
     * A suggestion chip's time: hours only when both ends are on the hour ("19–21", "22–02", "22–24"), the clock
     * otherwise ("09:30–11:00"). Digits only, so no resource is needed.
     */
    fun of(
        start: Int,
        end: Int,
    ): String =
        if (start % 60 == 0 && end % 60 == 0) {
            "%02d–%02d".format(start / 60, end / 60)
        } else {
            DayText.range(start, end)
        }
}

/**
 * The object particle after a title the person typed: "카페 마감을", "회의를" (tone.md "확인 대신 되돌리기"). Korean picks
 * 을 after a final consonant and 를 after a vowel, so it depends on how the last letter reads aloud.
 */
object Josa {
    /**
     * Whether the last letter or digit reads with a final consonant. Brackets, punctuation and emoji at the end are
     * skipped ("회의(온라인)" reads up to "인"); a title without any letter counts as ending in a vowel.
     */
    fun endsInConsonant(word: String): Boolean {
        var index = word.length
        while (index > 0) {
            val point = word.codePointBefore(index)
            index -= Character.charCount(point)
            when (point) {
                // Hangul syllables are laid out as 19 × 21 × 28: the last index is the final consonant, 0 for none.
                in 0xAC00..0xD7A3 -> return (point - 0xAC00) % 28 != 0
                // 영, 일, 삼, 육, 칠, 팔 end in a consonant; 이, 사, 오, 구 do not.
                in '0'.code..'9'.code -> return point.toChar() in "013678"
                // English letters as Koreans read them: 엘, 엠, 엔, 알 end in a consonant.
                in 'A'.code..'Z'.code, in 'a'.code..'z'.code -> return point.toChar() in "lmnrLMNR"
            }
        }
        return false
    }
}

fun weekdayShort(
    resources: Resources,
    weekday: DayOfWeek,
): String = resources.getStringArray(R.array.calendar_weekday_short)[weekday.column]

fun weekdayLong(
    resources: Resources,
    weekday: DayOfWeek,
): String = resources.getStringArray(R.array.calendar_weekday_long)[weekday.column]

fun DayTitle.text(resources: Resources): String {
    val weekday = weekdayLong(resources, weekday)
    // The year as text: a localized integer would read "2,027년".
    return year?.let { resources.getString(R.string.calendar_title_other_year, it.toString(), month, day, weekday) }
        ?: resources.getString(R.string.calendar_title_this_year, month, day, weekday)
}

@Composable
fun DayTitle.text(): String = text(LocalResources.current)

fun Heading.caption(resources: Resources): String =
    when (this) {
        is Heading.Month -> resources.getString(R.string.calendar_heading_year, year.toString())
        is Heading.WeekInMonth -> resources.getString(R.string.calendar_heading_year_month, year.toString(), month)
        is Heading.WeekAcross ->
            if (firstYear == lastYear) {
                resources.getString(R.string.calendar_heading_year, firstYear.toString())
            } else {
                resources.getString(R.string.calendar_heading_years, firstYear.toString(), lastYear.toString())
            }
        is Heading.Upcoming -> resources.getString(R.string.calendar_heading_year, year.toString())
    }

fun Heading.title(resources: Resources): String =
    when (this) {
        is Heading.Month -> resources.getString(R.string.calendar_heading_month, month)
        is Heading.WeekInMonth -> resources.getString(R.string.calendar_heading_days, firstDay, lastDay)
        is Heading.WeekAcross -> resources.getString(R.string.calendar_heading_range, firstMonth, firstDay, lastMonth, lastDay)
        is Heading.Upcoming -> resources.getString(R.string.calendar_heading_upcoming)
    }

fun RepeatText.text(resources: Resources): String =
    when (this) {
        is RepeatText.Daily ->
            if (interval <= 1) {
                resources.getString(R.string.calendar_repeat_daily)
            } else {
                resources.getString(R.string.calendar_repeat_every_days, interval)
            }
        is RepeatText.Weekly -> {
            val days =
                weekdays.singleOrNull()?.let { weekdayLong(resources, it) }
                    ?: weekdays.joinToString("·") { weekdayShort(resources, it) }
            if (interval <= 1) {
                resources.getString(R.string.calendar_repeat_weekly, days)
            } else {
                resources.getString(R.string.calendar_repeat_every_weeks, interval, days)
            }
        }
        is RepeatText.Monthly ->
            if (interval <= 1) {
                resources.getString(R.string.calendar_repeat_monthly, day)
            } else {
                resources.getString(R.string.calendar_repeat_every_months, interval, day)
            }
        is RepeatText.Yearly ->
            if (interval <= 1) {
                resources.getString(R.string.calendar_repeat_yearly)
            } else {
                resources.getString(R.string.calendar_repeat_every_years, interval)
            }
    }

@Composable
fun RepeatText.text(): String = text(LocalResources.current)
