package app.geunal.feature.calendar.support

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Every calendar day and time the core hands over is Korean time (docs/parts/rust-core.md §3 "날짜와 시각"), so the
 * screens compute "today" and weekdays in Asia/Seoul whatever the phone's own time zone is.
 */
object KoreanCalendar {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")

    /** The Korean day `instant` falls on, as `"YYYY-MM-DD"`. */
    fun day(of: Instant): String = date(of).toString()

    fun date(of: Instant): LocalDate = of.atZone(zone).toLocalDate()

    /** `"YYYY-MM-DD"` to a date; `null` for anything else. */
    fun parse(day: String): LocalDate? =
        try {
            LocalDate.parse(day)
        } catch (e: DateTimeParseException) {
            null
        }

    /** Whole days from `from` to `to`; negative when `to` is earlier. */
    fun days(
        from: String,
        to: String,
    ): Long? {
        val first = parse(from) ?: return null
        val last = parse(to) ?: return null
        return ChronoUnit.DAYS.between(first, last)
    }

    fun weekday(of: String): DayOfWeek? = parse(of)?.dayOfWeek

    /** Midnight at the start of the Korean day. */
    fun startOf(day: String): Instant? = parse(day)?.atStartOfDay(zone)?.toInstant()

    /** The day [count] days after [day]; a negative count goes back. */
    fun addDays(
        day: String,
        count: Long,
    ): String? = parse(day)?.plusDays(count)?.toString()

    /** `"YYYY-MM"` of a day. */
    fun month(of: String): String = of.take(7)

    /** The month [count] months after [month] (`"YYYY-MM"`); a negative count goes back. */
    fun addMonths(
        month: String,
        count: Long,
    ): String? = parse("$month-01")?.plusMonths(count)?.toString()?.take(7)

    /** The Sunday that starts the week [day] falls in; weeks run Sunday to Saturday like the month grid. */
    fun weekStart(of: String): String? {
        val date = parse(of) ?: return null
        return date.minusDays((date.dayOfWeek.value % 7).toLong()).toString()
    }
}
