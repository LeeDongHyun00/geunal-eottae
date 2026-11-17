package app.geunal.feature.calendar

import app.geunal.core.AlertTarget
import app.geunal.core.CoreException
import app.geunal.core.DayTime
import app.geunal.core.LeaveBridge
import app.geunal.core.ScheduledAlert
import app.geunal.feature.calendar.alerts.AlertBody
import app.geunal.feature.calendar.alerts.AlertCenter
import app.geunal.feature.calendar.alerts.AlertKind
import app.geunal.feature.calendar.alerts.AlertRoute
import app.geunal.feature.calendar.alerts.AlertScheduler
import app.geunal.feature.calendar.alerts.AlertTexts
import app.geunal.feature.calendar.alerts.AlertWords
import app.geunal.feature.calendar.alerts.NearDay
import app.geunal.feature.calendar.alerts.PlannedAlert
import app.geunal.feature.calendar.alerts.ShortDay
import app.geunal.feature.calendar.support.Until
import app.geunal.feature.calendar.testing.CalendarFakeCore
import app.geunal.feature.calendar.testing.Samples
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/** The alarms and notifications as lists: what is scheduled, and every call that changed it. */
private class FakeAlertCenter : AlertCenter {
    var allowed = true
    val pending = mutableListOf<PlannedAlert>()
    val calls = mutableListOf<String>()

    override fun allowsAlerts(): Boolean = allowed

    override fun scheduled(): List<PlannedAlert> = pending.toList()

    override fun schedule(alert: PlannedAlert) {
        calls += "schedule ${alert.id}"
        pending.removeAll { it.id == alert.id }
        pending += alert
    }

    override fun cancel(ids: List<String>) {
        calls += "cancel ${ids.joinToString(" ")}"
        pending.removeAll { it.id in ids }
    }

    override fun rearm() {
        calls += "rearm"
    }

    override fun removeAll() {
        calls += "removeAll"
        pending.clear()
    }
}

class AlertSchedulerTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = CalendarFakeCore()
    private val center = FakeAlertCenter()
    private var now = Instant.parse("2026-11-06T00:00:00Z")

    /** The title as it came and the lunar birthday's month and day, so the tests read what was planned. */
    private val texts =
        AlertTexts { alert, lunar -> AlertWords(alert.title, lunar?.let { "lunar ${it.month}-${it.day}" } ?: "body") }

    private fun TestScope.scheduler() = AlertScheduler(core, center, texts, now = { now }, io = dispatcher)

    private fun event(
        id: String,
        fireAt: String,
        title: String = "저녁",
    ) = ScheduledAlert(
        id = "event:$id:2026-11-07:60",
        fireAt = Instant.parse(fireAt),
        target = AlertTarget.Event(id, null),
        title = title,
        start = DayTime("2026-11-07", 1080u),
        allDay = false,
        end = DayTime("2026-11-07", 1200u),
    )

    private fun birthday(personId: String) =
        ScheduledAlert(
            id = "birthday:$personId:2026-11-11",
            fireAt = Instant.parse("2026-11-11T00:00:00Z"),
            target = AlertTarget.Birthday(personId),
            title = "민수",
            start = DayTime("2026-11-11", 0u),
            allDay = true,
            end = null,
        )

    @Test
    fun schedulesWhatTheCoreListsAndTouchesNothingWhenNothingChanged() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"), event("e2", "2026-11-08T08:00:00Z"))
            val scheduler = scheduler()

            scheduler.refresh()
            assertEquals(listOf("rearm", "schedule event:e1:2026-11-07:60", "schedule event:e2:2026-11-07:60"), center.calls)
            assertEquals(AlertRoute.Event("e1", null), center.pending.first().route)
            assertEquals(AlertKind.EVENT, center.pending.first().kind)

            center.calls.clear()
            scheduler.refresh()
            assertTrue(center.calls.isEmpty())
        }

    @Test
    fun cancelsWhatTheCoreNoLongerListsAndReplacesWhatIsWordedDifferently() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"), event("e2", "2026-11-08T08:00:00Z"))
            val scheduler = scheduler()
            scheduler.refresh()
            center.calls.clear()

            // e1 was deleted and e2 renamed.
            core.alertList = listOf(event("e2", "2026-11-08T08:00:00Z", title = "회식"))
            scheduler.refresh()

            assertEquals(
                listOf("cancel event:e1:2026-11-07:60 event:e2:2026-11-07:60", "schedule event:e2:2026-11-07:60"),
                center.calls,
            )
            assertEquals(listOf("회식"), center.pending.map { it.title })
        }

    @Test
    fun leavesAnAlertThatIsDueToItsAlarm() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-06T00:05:00Z"))
            val scheduler = scheduler()
            scheduler.refresh()
            center.calls.clear()

            // Its time came a minute ago: the core stops listing it, and an alarm that is not exact may not have rung yet.
            now = Instant.parse("2026-11-06T00:06:00Z")
            core.alertList = emptyList()
            scheduler.refresh()
            assertTrue(center.calls.isEmpty())

            // Long past, it is only stale.
            now = Instant.parse("2026-11-06T00:30:00Z")
            scheduler.refresh()
            assertEquals(listOf("cancel event:e1:2026-11-07:60"), center.calls)
        }

    @Test
    fun cancelsEverythingWhenNotificationsAreTurnedOff() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"))
            val scheduler = scheduler()
            scheduler.refresh()

            center.allowed = false
            scheduler.refresh()

            assertTrue(center.pending.isEmpty())
        }

    @Test
    fun setsTheAlarmsAgainOncePerProcess() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"))
            val scheduler = scheduler()

            scheduler.refresh()
            scheduler.refresh()

            assertEquals(1, center.calls.count { it == "rearm" })
        }

    @Test
    fun keepsWhatIsScheduledWhenTheCoreCannotAnswer() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"))
            val scheduler = scheduler()
            scheduler.refresh()

            core.readError = CoreException.Store("locked")
            scheduler.refresh()

            assertEquals(1, center.pending.size)
        }

    @Test
    fun removesEveryAlertOnASignOut() =
        runTest(dispatcher) {
            core.alertList = listOf(event("e1", "2026-11-07T08:00:00Z"))
            val scheduler = scheduler()
            scheduler.refresh()

            scheduler.removeAll()

            assertEquals("removeAll", center.calls.last())
            assertTrue(center.pending.isEmpty())
        }

    @Test
    fun routesABirthdayToItsPersonWithTheLunarDate() =
        runTest(dispatcher) {
            core.peopleList = listOf(Samples.person("p1", "민수", 10, 3, lunar = true), Samples.person("p2", "지영", 3, 14))
            core.alertList = listOf(birthday("p1"), birthday("p2"))

            scheduler().refresh()

            val (lunar, solar) = center.pending
            assertEquals(AlertRoute.Person("p1"), lunar.route)
            assertEquals(AlertKind.BIRTHDAY, lunar.kind)
            assertEquals("lunar 10-3", lunar.body)
            assertEquals("body", solar.body)
        }

    @Test
    fun routesALeaveSuggestionToItsWeek() =
        runTest(dispatcher) {
            val bridge = LeaveBridge("2026-10-30", "2026-10-30", "2026-11-02", 4u)
            core.alertList =
                listOf(
                    ScheduledAlert(
                        "leave:2026-10-30",
                        Instant.parse("2026-10-09T00:00:00Z"),
                        AlertTarget.LeaveBridge(bridge),
                        "",
                        DayTime("2026-10-30", 0u),
                        true,
                        null,
                    ),
                )

            scheduler().refresh()

            assertEquals(AlertRoute.Week("2026-10-30"), center.pending.single().route)
            assertEquals(AlertKind.LEAVE, center.pending.single().kind)
        }

    @Test
    fun keepsARouteThroughItsValues() {
        val routes =
            listOf(
                AlertRoute.Event("e1", "2026-11-07"),
                AlertRoute.Event("e1", null),
                AlertRoute.Person("p1"),
                AlertRoute.Week("2026-10-30"),
            )

        assertEquals(routes, routes.map { AlertRoute.of(it.values()) })
        assertNull(AlertRoute.of(mapOf("route" to "event")))
        assertNull(AlertRoute.of(emptyMap()))
    }

    // tone.md "알림 문구": every lead word is seen from the moment the alert fires.

    @Test
    fun saysHowLongUntilATimedEventOnItsDay() {
        // 18:00–20:00 Korean time, alerted an hour before.
        val body = AlertBody.of(event("e1", "2026-11-07T08:00:00Z"))

        assertEquals(AlertBody.Timed(NearDay.Today, Until(0, 1, 0), 1080, 1200), body)
    }

    @Test
    fun namesTheDayOfATimedEventAlertedTheDayBefore() {
        val body = AlertBody.of(event("e1", "2026-11-06T09:00:00Z"))

        assertEquals(AlertBody.Timed(NearDay.Tomorrow, null, 1080, 1200), body)
    }

    @Test
    fun showsAnEndAtMidnightAsTwentyFour() {
        val alert = event("e1", "2026-11-07T08:00:00Z").copy(end = DayTime("2026-11-08", 0u))

        assertEquals(1440, (AlertBody.of(alert) as AlertBody.Timed).end)
    }

    @Test
    fun wordsAllDayEventsByTheirDays() {
        val oneDay = birthday("p1").copy(target = AlertTarget.Event("e1", null), fireAt = Instant.parse("2026-11-10T09:00:00Z"))
        val threeDays = oneDay.copy(end = DayTime("2026-11-13", 0u))

        assertEquals(AlertBody.AllDay(NearDay.Tomorrow), AlertBody.of(oneDay.copy(end = DayTime("2026-11-11", 0u))))
        assertEquals(AlertBody.AllDayThrough(NearDay.Tomorrow, ShortDay(11, 13, DayOfWeek.FRIDAY)), AlertBody.of(threeDays))
    }

    @Test
    fun countsDownToABirthday() {
        val day = ShortDay(11, 11, DayOfWeek.WEDNESDAY)

        // 09:00 Korean time on the day, then one and five days before.
        assertEquals(AlertBody.BirthdayToday, AlertBody.of(birthday("p1")))
        assertEquals(
            AlertBody.BirthdayNear(NearDay.Tomorrow, day),
            AlertBody.of(birthday("p1").copy(fireAt = Instant.parse("2026-11-10T00:00:00Z"))),
        )
        assertEquals(AlertBody.BirthdayAhead(5, day), AlertBody.of(birthday("p1").copy(fireAt = Instant.parse("2026-11-06T00:00:00Z"))))
    }
}
