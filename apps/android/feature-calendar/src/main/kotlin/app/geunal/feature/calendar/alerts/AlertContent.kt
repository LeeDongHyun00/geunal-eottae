package app.geunal.feature.calendar.alerts

import android.content.res.Resources
import app.geunal.core.AlertTarget
import app.geunal.core.Birthday
import app.geunal.core.ScheduledAlert
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.people.PeopleText
import app.geunal.feature.calendar.support.DayText
import app.geunal.feature.calendar.support.KoreanCalendar
import app.geunal.feature.calendar.support.Until
import app.geunal.feature.calendar.support.weekdayShort
import java.time.DayOfWeek
import java.time.Instant

/** A date inside a notification, short: "11/7(토)" (tone.md "알림 문구"). */
data class ShortDay(
    val month: Int,
    val day: Int,
    val weekday: DayOfWeek,
) {
    companion object {
        fun of(day: String): ShortDay? = KoreanCalendar.parse(day)?.let { ShortDay(it.monthValue, it.dayOfMonth, it.dayOfWeek) }
    }
}

/** A day seen from the moment the alert fires: 오늘, 내일, 모레, then the short date. */
sealed interface NearDay {
    data object Today : NearDay

    data object Tomorrow : NearDay

    data object DayAfterTomorrow : NearDay

    data class Date(
        val day: ShortDay,
    ) : NearDay

    companion object {
        fun of(
            day: String,
            fireDay: String,
        ): NearDay? =
            when (KoreanCalendar.days(from = fireDay, to = day)) {
                0L -> Today
                1L -> Tomorrow
                2L -> DayAfterTomorrow
                else -> ShortDay.of(day)?.let(::Date)
            }
    }
}

/**
 * What a local notification says (tone.md "알림 문구", m1.md decisions 32–34), as values: the models and their tests
 * stay free of Android resources, and [text] turns them into words.
 */
sealed interface AlertBody {
    /** "1시간 뒤 · 18:00–20:00" on the day itself ([until], or "지금" without one), "내일 · 18:00–20:00" before it. */
    data class Timed(
        val day: NearDay,
        val until: Until?,
        val start: Int,
        /** `null` from a core that does not say the end: only the start shows. */
        val end: Int?,
    ) : AlertBody

    /** "내일 · 종일". */
    data class AllDay(
        val day: NearDay,
    ) : AlertBody

    /** Days in a row: "내일부터 11/9(월)까지". */
    data class AllDayThrough(
        val day: NearDay,
        val last: ShortDay,
    ) : AlertBody

    /** "오늘이에요". */
    data object BirthdayToday : AlertBody

    /** One or two days before: "내일 · 11/7(토)". */
    data class BirthdayNear(
        val day: NearDay,
        val date: ShortDay,
    ) : AlertBody

    /** "D-3 · 11/9(월)". */
    data class BirthdayAhead(
        val days: Int,
        val date: ShortDay,
    ) : AlertBody

    /** "10/30(금) 하루 쉬면 10/30(금)–11/2(월) 쉬어요". */
    data class LeaveBridge(
        val leave: ShortDay,
        val first: ShortDay,
        val last: ShortDay,
    ) : AlertBody

    companion object {
        /** `null` for a day the app cannot read; such an alert is left out rather than worded wrongly. */
        fun of(alert: ScheduledAlert): AlertBody? {
            val fireDay = KoreanCalendar.day(alert.fireAt)
            return when (val target = alert.target) {
                is AlertTarget.Event -> if (alert.allDay) allDay(alert, fireDay) else timed(alert, fireDay)
                is AlertTarget.Birthday -> birthday(alert.start.day, fireDay)
                is AlertTarget.LeaveBridge -> {
                    val leave = ShortDay.of(target.bridge.leaveDay) ?: return null
                    val first = ShortDay.of(target.bridge.firstDay) ?: return null
                    val last = ShortDay.of(target.bridge.lastDay) ?: return null
                    LeaveBridge(leave, first, last)
                }
            }
        }

        private fun timed(
            alert: ScheduledAlert,
            fireDay: String,
        ): AlertBody? {
            val day = NearDay.of(alert.start.day, fireDay) ?: return null
            val start = alert.start.minute.toInt()
            val startsAt = KoreanCalendar.startOf(alert.start.day)?.plusSeconds(start * 60L) ?: return null
            val end =
                alert.end?.let {
                    // An event ending at midnight ends at minute 0 of the next day (rust-core.md §3); it shows as 24:00.
                    if (it.day != alert.start.day && it.minute.toInt() == 0) 1440 else it.minute.toInt()
                }
            return Timed(day, if (day == NearDay.Today) Until.of(startsAt, alert.fireAt) else null, start, end)
        }

        private fun allDay(
            alert: ScheduledAlert,
            fireDay: String,
        ): AlertBody? {
            val day = NearDay.of(alert.start.day, fireDay) ?: return null
            val last = alert.end?.day?.takeIf { it != alert.start.day } ?: return AllDay(day)
            return AllDayThrough(day, ShortDay.of(last) ?: return null)
        }

        private fun birthday(
            day: String,
            fireDay: String,
        ): AlertBody? {
            val days = KoreanCalendar.days(from = fireDay, to = day) ?: return null
            if (days <= 0) return BirthdayToday
            val date = ShortDay.of(day) ?: return null
            return if (days <= 2) BirthdayNear(NearDay.of(day, fireDay) ?: return null, date) else BirthdayAhead(days.toInt(), date)
        }
    }
}

/** A notification's two lines. */
data class AlertWords(
    val title: String,
    val body: String,
) {
    // The title is the person's own words and must not reach a log through string interpolation.
    override fun toString(): String = "AlertWords(…)"
}

/** How an alert is worded, behind a seam so the scheduling is tested with plain texts. */
fun interface AlertTexts {
    /** [lunar] is the birthday as the person gave it, for one kept by the lunar calendar. `null` leaves the alert out. */
    fun words(
        alert: ScheduledAlert,
        lunar: Birthday?,
    ): AlertWords?
}

/** The words of tone.md "알림 문구", from the app's resources. */
class ResourceAlertTexts(
    private val resources: Resources,
) : AlertTexts {
    override fun words(
        alert: ScheduledAlert,
        lunar: Birthday?,
    ): AlertWords? {
        val body = AlertBody.of(alert)?.text(resources) ?: return null
        return when (val target = alert.target) {
            is AlertTarget.Event -> AlertWords(alert.title, body)
            is AlertTarget.Birthday ->
                AlertWords(
                    resources.getString(R.string.next_up_birthday, alert.title),
                    // A lunar birthday ends with the day as it was given: "· 음력 10월 3일".
                    lunar?.let { resources.getString(R.string.alert_lunar, body, PeopleText.birthday(it, resources)) } ?: body,
                )
            is AlertTarget.LeaveBridge ->
                AlertWords(resources.getString(R.string.calendar_leave_bridge, target.bridge.daysOff.toInt()), body)
        }
    }
}

fun ShortDay.text(resources: Resources): String =
    resources.getString(R.string.alert_short_day, month, day, weekdayShort(resources, weekday))

fun NearDay.text(resources: Resources): String =
    when (this) {
        NearDay.Today -> resources.getString(R.string.calendar_today)
        NearDay.Tomorrow -> resources.getString(R.string.calendar_tomorrow)
        NearDay.DayAfterTomorrow -> resources.getString(R.string.calendar_day_after_tomorrow)
        is NearDay.Date -> day.text(resources)
    }

fun Until.text(resources: Resources): String =
    when {
        days > 0 && hours > 0 -> resources.getString(R.string.calendar_until_days_hours, days.toInt(), hours.toInt())
        days > 0 -> resources.getString(R.string.calendar_until_days, days.toInt())
        hours > 0 && minutes > 0 -> resources.getString(R.string.calendar_until_hours_minutes, hours.toInt(), minutes.toInt())
        hours > 0 -> resources.getString(R.string.calendar_until_hours, hours.toInt())
        else -> resources.getString(R.string.calendar_until_minutes, minutes.toInt())
    }

fun AlertBody.text(resources: Resources): String =
    when (this) {
        is AlertBody.Timed -> {
            val lead =
                when {
                    day != NearDay.Today -> day.text(resources)
                    until != null -> until.text(resources)
                    else -> resources.getString(R.string.alert_now)
                }
            resources.getString(R.string.alert_timed, lead, end?.let { DayText.range(start, it) } ?: DayText.clock(start))
        }
        is AlertBody.AllDay -> resources.getString(R.string.next_up_all_day, day.text(resources))
        is AlertBody.AllDayThrough -> resources.getString(R.string.alert_all_day_through, day.text(resources), last.text(resources))
        AlertBody.BirthdayToday -> resources.getString(R.string.alert_birthday_today)
        is AlertBody.BirthdayNear -> resources.getString(R.string.alert_birthday_near, day.text(resources), date.text(resources))
        is AlertBody.BirthdayAhead -> resources.getString(R.string.alert_birthday_ahead, days, date.text(resources))
        is AlertBody.LeaveBridge ->
            resources.getString(R.string.alert_leave, leave.text(resources), first.text(resources), last.text(resources))
    }

/** The moment an alert fires, to the millisecond: what the alarm and the stored plan keep. */
internal fun Instant.toMillisecond(): Instant = Instant.ofEpochMilli(toEpochMilli())
