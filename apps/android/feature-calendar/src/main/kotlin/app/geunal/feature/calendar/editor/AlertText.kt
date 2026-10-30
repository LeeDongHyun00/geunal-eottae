package app.geunal.feature.calendar.editor

import android.content.res.Resources
import app.geunal.core.AlertRule
import app.geunal.feature.calendar.R
import app.geunal.feature.calendar.support.DayText

/**
 * How an alert reads (m1.md screen 9 "알림"). An all-day event's alert counts from 00:00 of its day (rust-core.md §3),
 * so "전날 18:00" is 360 minutes before it and "당일 09:00" is -540.
 */
sealed interface AlertText {
    /** "캘린더 기본". */
    data object Inherit : AlertText

    /** "없음". */
    data object Off : AlertText

    /** "정시". */
    data object OnTime : AlertText

    /** "10분 전". */
    data class Minutes(
        val minutes: Int,
    ) : AlertText

    /** "1시간 전". */
    data class Hours(
        val hours: Int,
    ) : AlertText

    /** "1일 전". */
    data class Days(
        val days: Int,
    ) : AlertText

    /** An all-day event on its own day: "당일 09:00". */
    data class SameDay(
        val minute: Int,
    ) : AlertText

    /** An all-day event's "전날 18:00", or "2일 전 18:00" further back. */
    data class DaysBeforeAt(
        val days: Int,
        val minute: Int,
    ) : AlertText

    /** An event that inherits, with what the calendar's default comes to: "캘린더 기본 · 1시간 전". */
    data class Inherited(
        val value: AlertText,
    ) : AlertText

    companion object {
        /** The alert menu's choices (m1.md screen 9): an all-day event alerts at a time of its day or the day before. */
        fun choices(allDay: Boolean): List<AlertRule> =
            if (allDay) {
                listOf(AlertRule.Inherit, AlertRule.Off, AlertRule.Before(-540), AlertRule.Before(360))
            } else {
                listOf(
                    AlertRule.Inherit,
                    AlertRule.Off,
                    AlertRule.Before(0),
                    AlertRule.Before(10),
                    AlertRule.Before(30),
                    AlertRule.Before(60),
                    AlertRule.Before(1440),
                )
            }

        fun of(
            rule: AlertRule,
            allDay: Boolean,
        ): AlertText =
            when (rule) {
                AlertRule.Inherit -> Inherit
                AlertRule.Off -> Off
                is AlertRule.Before -> of(rule.minutes, allDay)
            }

        fun of(
            minutes: Int,
            allDay: Boolean,
        ): AlertText {
            if (allDay) {
                if (minutes <= 0) return SameDay(-minutes)
                // Minutes before 00:00 of the day: 360 is 18:00 the day before, 1800 is 18:00 two days before.
                val minute = (1440 - minutes % 1440) % 1440
                val days = (minutes + 1439) / 1440
                return DaysBeforeAt(days, minute)
            }
            return when {
                minutes == 0 -> OnTime
                minutes % 1440 == 0 -> Days(minutes / 1440)
                minutes % 60 == 0 -> Hours(minutes / 60)
                else -> Minutes(minutes)
            }
        }

        /**
         * The alert row's value. An event that inherits says what that comes to, from the core's effective alert, so
         * "캘린더 기본" is never a puzzle.
         */
        fun value(
            rule: AlertRule,
            effective: Int?,
            allDay: Boolean,
        ): AlertText = if (rule is AlertRule.Inherit && effective != null) Inherited(of(effective, allDay)) else of(rule, allDay)
    }
}

fun AlertText.text(resources: Resources): String =
    when (this) {
        AlertText.Inherit -> resources.getString(R.string.editor_alert_inherit)
        AlertText.Off -> resources.getString(R.string.editor_alert_off)
        AlertText.OnTime -> resources.getString(R.string.editor_alert_on_time)
        is AlertText.Minutes -> resources.getString(R.string.editor_alert_minutes, minutes)
        is AlertText.Hours -> resources.getString(R.string.editor_alert_hours, hours)
        is AlertText.Days -> resources.getString(R.string.editor_alert_days, days)
        is AlertText.SameDay -> resources.getString(R.string.editor_alert_same_day, DayText.clock(minute))
        is AlertText.DaysBeforeAt ->
            if (days <= 1) {
                resources.getString(R.string.editor_alert_day_before, DayText.clock(minute))
            } else {
                resources.getString(R.string.editor_alert_days_before_at, days, DayText.clock(minute))
            }
        is AlertText.Inherited -> resources.getString(R.string.editor_alert_inherited, value.text(resources))
    }
