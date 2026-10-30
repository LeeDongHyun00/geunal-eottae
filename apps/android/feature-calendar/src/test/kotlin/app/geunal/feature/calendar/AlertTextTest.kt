package app.geunal.feature.calendar

import app.geunal.core.AlertRule
import app.geunal.feature.calendar.editor.AlertText
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertTextTest {
    @Test
    fun readsATimedEventsAlertInTheLargestWholeUnit() {
        assertEquals(AlertText.OnTime, AlertText.of(0, allDay = false))
        assertEquals(AlertText.Minutes(10), AlertText.of(10, allDay = false))
        assertEquals(AlertText.Minutes(90), AlertText.of(90, allDay = false))
        assertEquals(AlertText.Hours(1), AlertText.of(60, allDay = false))
        assertEquals(AlertText.Days(1), AlertText.of(1440, allDay = false))
    }

    @Test
    fun readsAnAllDayEventsAlertAsATimeOfItsDayOrADayBefore() {
        // All-day alerts count from 00:00 of the day (rust-core.md §3).
        assertEquals(AlertText.SameDay(540), AlertText.of(-540, allDay = true))
        assertEquals(AlertText.DaysBeforeAt(1, 1080), AlertText.of(360, allDay = true))
        assertEquals(AlertText.DaysBeforeAt(2, 1080), AlertText.of(1800, allDay = true))
    }

    @Test
    fun saysWhatTheCalendarsDefaultComesTo() {
        assertEquals(AlertText.Inherited(AlertText.Hours(1)), AlertText.value(AlertRule.Inherit, effective = 60, allDay = false))
        assertEquals(AlertText.Inherit, AlertText.value(AlertRule.Inherit, effective = null, allDay = false))
        assertEquals(AlertText.Off, AlertText.value(AlertRule.Off, effective = 60, allDay = false))
        assertEquals(AlertText.Minutes(30), AlertText.value(AlertRule.Before(30), effective = 60, allDay = false))
    }

    @Test
    fun offersTheChoicesOfTheBoard() {
        assertEquals(
            listOf(
                AlertText.Inherit,
                AlertText.Off,
                AlertText.OnTime,
                AlertText.Minutes(10),
                AlertText.Minutes(30),
                AlertText.Hours(1),
                AlertText.Days(1),
            ),
            AlertText.choices(allDay = false).map { AlertText.of(it, allDay = false) },
        )
        assertEquals(
            listOf(AlertText.Inherit, AlertText.Off, AlertText.SameDay(540), AlertText.DaysBeforeAt(1, 1080)),
            AlertText.choices(allDay = true).map { AlertText.of(it, allDay = true) },
        )
    }
}
