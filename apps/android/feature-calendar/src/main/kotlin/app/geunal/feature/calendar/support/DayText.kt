package app.geunal.feature.calendar.support

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import app.geunal.feature.calendar.R
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/**
 * A day as the house style names it (docs/design/tone.md "날짜와 시간"): 오늘, 내일, 모레, then "11월 7일(토)" this year
 * and "2027년 1월 3일" in another. Models produce these values and the screens turn them into text, so the models are
 * tested without Android resources.
 */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Tomorrow : DayLabel

    data object DayAfterTomorrow : DayLabel

    data class ThisYear(
        val month: Int,
        val day: Int,
        val weekday: DayOfWeek,
    ) : DayLabel

    data class OtherYear(
        val year: Int,
        val month: Int,
        val day: Int,
    ) : DayLabel

    /** A day the core sent in a shape the app cannot read; shown as it came. */
    data class Raw(
        val text: String,
    ) : DayLabel

    companion object {
        fun of(
            day: String,
            today: String,
        ): DayLabel {
            val date = KoreanCalendar.parse(day) ?: return Raw(day)
            return when (KoreanCalendar.days(from = today, to = day)) {
                0L -> Today
                1L -> Tomorrow
                2L -> DayAfterTomorrow
                else ->
                    if (KoreanCalendar.parse(today)?.year == date.year) {
                        ThisYear(date.monthValue, date.dayOfMonth, date.dayOfWeek)
                    } else {
                        OtherYear(date.year, date.monthValue, date.dayOfMonth)
                    }
            }
        }
    }
}

/** How long until something starts, two units at most: "3시간 40분 뒤", "1일 3시간 뒤", "4분 뒤". */
data class Until(
    val days: Long,
    val hours: Long,
    val minutes: Long,
) {
    companion object {
        /** `null` once it has started; a part of a minute counts as a whole one. */
        fun of(
            start: Instant,
            now: Instant,
        ): Until? {
            val seconds = Duration.between(now, start).seconds
            if (seconds <= 0) return null
            val minutes = (seconds + 59) / 60
            return Until(days = minutes / 1440, hours = (minutes % 1440) / 60, minutes = minutes % 60)
        }
    }
}

object DayText {
    /** "09:00" from minutes after midnight; 1440, an end at midnight, is "24:00". */
    fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    /** "09:00–18:00", an en dash and no spaces. */
    fun range(
        start: Int,
        end: Int,
    ): String = clock(start) + "–" + clock(end)
}

@Composable
fun DayLabel.text(): String {
    val weekdays = stringArrayResource(R.array.calendar_weekday_short)
    return when (this) {
        DayLabel.Today -> stringResource(R.string.calendar_today)
        DayLabel.Tomorrow -> stringResource(R.string.calendar_tomorrow)
        DayLabel.DayAfterTomorrow -> stringResource(R.string.calendar_day_after_tomorrow)
        is DayLabel.ThisYear -> stringResource(R.string.calendar_day_this_year, month, day, weekdays[weekday.value % 7])
        // The year as text: a localized integer would read "2,027년".
        is DayLabel.OtherYear -> stringResource(R.string.calendar_day_other_year, year.toString(), month, day)
        is DayLabel.Raw -> text
    }
}

@Composable
fun Until.text(): String =
    when {
        days > 0 && hours > 0 -> stringResource(R.string.calendar_until_days_hours, days.toInt(), hours.toInt())
        days > 0 -> stringResource(R.string.calendar_until_days, days.toInt())
        hours > 0 && minutes > 0 -> stringResource(R.string.calendar_until_hours_minutes, hours.toInt(), minutes.toInt())
        hours > 0 -> stringResource(R.string.calendar_until_hours, hours.toInt())
        else -> stringResource(R.string.calendar_until_minutes, minutes.toInt())
    }
