package app.geunal.feature.calendar.alerts

import java.time.Instant

/**
 * Where tapping a local notification goes (m1.md §2.6): an event opens in the editor, a birthday the person in
 * 사람·생일, a leave suggestion the calendar on its week.
 */
sealed interface AlertRoute {
    data class Event(
        val eventId: String,
        val occurrence: String?,
    ) : AlertRoute

    data class Person(
        val personId: String,
    ) : AlertRoute

    data class Week(
        val day: String,
    ) : AlertRoute

    /** Plain strings, so nothing but the route rides along with the notification. */
    fun values(): Map<String, String> =
        when (this) {
            is Event -> listOfNotNull(KIND to "event", "event" to eventId, occurrence?.let { "occurrence" to it }).toMap()
            is Person -> mapOf(KIND to "person", "person" to personId)
            is Week -> mapOf(KIND to "week", "day" to day)
        }

    companion object {
        private const val KIND = "route"

        fun of(values: Map<String, String>): AlertRoute? =
            when (values[KIND]) {
                "event" -> values["event"]?.let { Event(it, values["occurrence"]) }
                "person" -> values["person"]?.let(::Person)
                "week" -> values["day"]?.let(::Week)
                else -> null
            }
    }
}

/** What an alert is about. It picks the notification channel and the title a locked screen may show (tone.md). */
enum class AlertKind { EVENT, BIRTHDAY, LEAVE }

/** One local notification as the app schedules it. */
data class PlannedAlert(
    /** The core's id, which stays the same while the alert does. */
    val id: String,
    /** To the millisecond, which is what the alarm and the stored plan keep. */
    val fireAt: Instant,
    val title: String,
    val body: String,
    val kind: AlertKind,
    val route: AlertRoute,
) {
    // The title is the person's own words and must not reach a log through string interpolation.
    override fun toString(): String = "PlannedAlert(…)"
}

/** The system's alarms and notifications behind a seam, so the scheduling is tested without them. */
interface AlertCenter {
    /** Whether the person allows this app's notifications at all. */
    fun allowsAlerts(): Boolean

    /** What is scheduled now, as it was planned. */
    fun scheduled(): List<PlannedAlert>

    fun schedule(alert: PlannedAlert)

    fun cancel(ids: List<String>)

    /**
     * Sets every scheduled alert's alarm again. The system drops alarms on a reboot, a force stop and when the exact
     * alarm access is taken back, and tells the app about the first only.
     */
    fun rearm()

    /** Every scheduled alert and every one already shown. */
    fun removeAll()
}
