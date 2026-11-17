package app.geunal.feature.calendar

import app.geunal.feature.calendar.alerts.AlarmSetter
import app.geunal.feature.calendar.alerts.AlertAlarms
import app.geunal.feature.calendar.alerts.AlertKind
import app.geunal.feature.calendar.alerts.AlertPlanStore
import app.geunal.feature.calendar.alerts.AlertRoute
import app.geunal.feature.calendar.alerts.PlannedAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** AlarmManager as a map: what is set for which alert, and how. */
private class FakeAlarmSetter : AlarmSetter {
    var exactAllowed = true
    val set = mutableMapOf<String, String>()

    override fun exact(
        id: String,
        at: Instant,
    ): Boolean {
        if (!exactAllowed) return false
        set[id] = "exact $at"
        return true
    }

    override fun inexact(
        id: String,
        at: Instant,
    ) {
        set[id] = "inexact $at"
    }

    override fun cancel(id: String) {
        set -= id
    }
}

class AlertAlarmsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val setter = FakeAlarmSetter()
    private var now = Instant.parse("2026-11-06T00:00:00Z")
    private val file by lazy { File(folder.root, "alerts/plan") }

    private fun alarms() = AlertAlarms(AlertPlanStore(file), setter, now = { now })

    private fun alert(
        id: String,
        fireAt: String,
        route: AlertRoute = AlertRoute.Event(id, "2026-11-07"),
    ) = PlannedAlert(id, Instant.parse(fireAt), "저녁", "1시간 뒤 · 18:00–20:00", AlertKind.EVENT, route)

    @Test
    fun setsAnExactAlarmAndKeepsThePlan() {
        val first = alert("e1", "2026-11-07T08:00:00Z")
        val second = alert("p1", "2026-11-11T00:00:00Z", AlertRoute.Person("p1")).copy(kind = AlertKind.BIRTHDAY)

        alarms().schedule(first)
        alarms().schedule(second)

        assertEquals("exact 2026-11-07T08:00:00Z", setter.set["e1"])
        // Another instance over the same file, as the broadcast receiver makes, reads the same plan.
        assertEquals(listOf(first, second), alarms().scheduled())
    }

    @Test
    fun fallsBackWhenExactAlarmsAreNotAllowed() {
        setter.exactAllowed = false

        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))

        assertEquals("inexact 2026-11-07T08:00:00Z", setter.set["e1"])
    }

    @Test
    fun reschedulesRemindersAfterBoot() {
        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))
        alarms().schedule(alert("e2", "2026-11-08T08:00:00Z"))
        // A reboot: the system has forgotten every alarm, and the plan is still on disk.
        setter.set.clear()

        alarms().rearm()

        assertEquals(setOf("e1", "e2"), setter.set.keys)
    }

    @Test
    fun ringsAnAlertMissedByMinutesAndDropsAnOlderOne() {
        alarms().schedule(alert("missed", "2026-11-06T00:01:00Z"))
        alarms().schedule(alert("coming", "2026-11-07T08:00:00Z"))
        setter.set.clear()

        // The phone was off over the first one's time and is back five minutes late: it still rings.
        now = Instant.parse("2026-11-06T00:06:00Z")
        alarms().rearm()
        assertEquals(setOf("missed", "coming"), setter.set.keys)

        // Back a day late instead, its words would no longer be true: it is dropped from the plan.
        setter.set.clear()
        now = Instant.parse("2026-11-07T00:00:00Z")
        alarms().rearm()
        assertEquals(setOf("coming"), setter.set.keys)
        assertEquals(listOf("coming"), alarms().scheduled().map { it.id })
    }

    @Test
    fun upgradesToExactOnceTheAccessIsBack() {
        setter.exactAllowed = false
        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))

        setter.exactAllowed = true
        alarms().rearm()

        assertEquals("exact 2026-11-07T08:00:00Z", setter.set["e1"])
    }

    @Test
    fun handsOverAnAlertOnceWhenItsAlarmRings() {
        val alert = alert("e1", "2026-11-07T08:00:00Z")
        alarms().schedule(alert)

        assertEquals(alert, alarms().take("e1"))
        assertNull(alarms().take("e1"))
        assertTrue(alarms().scheduled().isEmpty())
    }

    @Test
    fun showsNothingForAnAlertCancelledBeforeItRang() {
        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))
        alarms().schedule(alert("e2", "2026-11-08T08:00:00Z"))

        alarms().cancel(listOf("e1"))

        assertNull(alarms().take("e1"))
        assertEquals(setOf("e2"), setter.set.keys)
    }

    @Test
    fun leavesNoTitleOnDiskAfterASignOut() {
        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))
        alarms().schedule(alert("e2", "2026-11-08T08:00:00Z"))

        alarms().clear()

        assertTrue(setter.set.isEmpty())
        assertFalse(file.exists())
        assertNull(alarms().take("e1"))
    }

    @Test
    fun readsADamagedPlanAsEmpty() {
        alarms().schedule(alert("e1", "2026-11-07T08:00:00Z"))
        file.writeBytes(file.readBytes().copyOf(20))

        assertTrue(alarms().scheduled().isEmpty())

        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(alarms().scheduled().isEmpty())
    }
}
