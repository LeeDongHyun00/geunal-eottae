package app.geunal.feature.calendar.alerts

import app.geunal.core.AlertTarget
import app.geunal.core.Birthday
import app.geunal.core.BirthdayCalendar
import app.geunal.core.CoreInterface
import app.geunal.feature.calendar.support.Coalescer
import app.geunal.feature.calendar.support.coreRead
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Keeps the nearest alerts scheduled (docs/parts/android.md §4 "로컬 알림"). The core says what fires when; each
 * refresh cancels what it no longer lists or words differently and schedules what is missing, so an unchanged calendar
 * touches nothing. The app refreshes on its start, each time it comes to the front and on every calendar change; the
 * alarms themselves fire without it ([AlertReceiver]).
 */
class AlertScheduler(
    private val core: CoreInterface,
    private val center: AlertCenter,
    private val texts: AlertTexts,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val coalescer = Coalescer()

    /** Set once this process has set its alarms again: a force stop drops them without telling the app. */
    private var rearmed = false

    /** A burst of calendar changes makes one refresh after the one under way. */
    suspend fun refresh() {
        coalescer.run { withContext(io) { refreshOnce() } }
    }

    /**
     * A sign-out or a deleted account (docs/security/app-review.md §2 "로컬 알림"): the scheduled and the shown alerts
     * carry titles, so all of them go. The next refresh schedules what the core still lists for the events that stay on
     * the device.
     */
    suspend fun removeAll() {
        withContext(io) { center.removeAll() }
    }

    private fun refreshOnce() {
        if (!center.allowsAlerts()) {
            // Turned off in the system settings: nothing is left to fire later.
            center.cancel(center.scheduled().map { it.id })
            return
        }
        // A core that cannot answer keeps what is scheduled; the next change or foreground asks again.
        val planned = coreRead { plan(core, texts) } ?: return
        if (!rearmed) {
            rearmed = true
            center.rearm()
        }
        val current = center.scheduled()
        val at = now()
        val stale = current.filter { it !in planned && !isDue(it, at) }.map { it.id }
        if (stale.isNotEmpty()) center.cancel(stale)
        planned.filter { it !in current }.forEach(center::schedule)
    }

    companion object {
        /**
         * As many as iOS keeps (64 pending notifications per app), far under Android's 500 alarms per app. The window
         * moves on with each refresh.
         */
        const val LIMIT = 64

        /**
         * How long an alert whose time has come is left to its alarm. The core stops listing an alert the moment it is
         * due, and an alarm that is not exact may ring minutes later: cancelling it as stale in between would lose it.
         */
        val DUE_GRACE: Duration = Duration.ofMinutes(10)

        internal fun isDue(
            alert: PlannedAlert,
            now: Instant,
        ): Boolean = !alert.fireAt.isAfter(now) && alert.fireAt.isAfter(now.minus(DUE_GRACE))

        /** The core's next alerts in the words of docs/design/tone.md, with a lunar birthday's date from its person. */
        fun plan(
            core: CoreInterface,
            texts: AlertTexts,
        ): List<PlannedAlert> {
            val alerts = core.scheduledAlerts(LIMIT.toUInt())
            val lunar: Map<String, Birthday> =
                if (alerts.any { it.target is AlertTarget.Birthday }) {
                    core
                        .people()
                        .mapNotNull { person -> person.birthday?.takeIf { it.calendar is BirthdayCalendar.Lunar }?.let { person.id to it } }
                        .toMap()
                } else {
                    emptyMap()
                }
            return alerts.mapNotNull { alert ->
                val (kind, route) =
                    when (val target = alert.target) {
                        is AlertTarget.Event -> AlertKind.EVENT to AlertRoute.Event(target.eventId, target.occurrence)
                        is AlertTarget.Birthday -> AlertKind.BIRTHDAY to AlertRoute.Person(target.personId)
                        is AlertTarget.LeaveBridge -> AlertKind.LEAVE to AlertRoute.Week(target.bridge.leaveDay)
                    }
                val words =
                    texts.words(alert, (alert.target as? AlertTarget.Birthday)?.let { lunar[it.personId] }) ?: return@mapNotNull null
                PlannedAlert(alert.id, alert.fireAt.toMillisecond(), words.title, words.body, kind, route)
            }
        }
    }
}
