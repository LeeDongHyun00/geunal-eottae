package app.geunal.feature.account

import app.geunal.core.CoreException
import app.geunal.feature.account.onboarding.CalendarImport
import app.geunal.feature.account.onboarding.PermissionAction
import app.geunal.feature.account.onboarding.PermissionState
import app.geunal.feature.account.onboarding.PermissionsModel
import app.geunal.feature.account.support.AccountNotice
import app.geunal.feature.account.support.BuildChannel
import app.geunal.feature.account.testing.AccountFakeCore
import app.geunal.feature.account.testing.FakePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsModelTest {
    private val core = AccountFakeCore()
    private val permissions = FakePermissions()
    private val notices = mutableListOf<AccountNotice>()

    /** What the mirror answers to an import, and how often it was asked. */
    private var imported: Int? = 3
    private var imports = 0

    // Unconfined: the import here answers at once, so each call has landed when it returns.
    private fun model(channel: BuildChannel = BuildChannel.ALPHA) =
        PermissionsModel(
            core,
            permissions,
            channel,
            notify = { notices += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            importCalendars = {
                imports++
                imported
            },
        ).apply { refresh() }

    @Test
    fun importsTheDeviceCalendarsOnceAllowedAndSaysHowMany() {
        val model = model()
        assertEquals(PermissionAction.Ask(PermissionsModel.CALENDAR), model.calendarTapped())
        assertEquals(0, imports)

        model.calendarAnswered(granted = true)

        assertEquals(PermissionState.GRANTED, model.state.value.calendar)
        assertEquals(CalendarImport.Done(3), model.state.value.calendarImport)
        // Coming back to the screen reads the permission again and imports nothing twice.
        permissions.calendar = true
        model.refresh()
        assertEquals(1, imports)
    }

    @Test
    fun doesNotClaimAnImportThatDidNotHappen() {
        imported = null
        val model = model()

        model.calendarAnswered(granted = true)
        assertEquals(CalendarImport.Failed, model.state.value.calendarImport)

        // The button then tries again.
        imported = 2
        assertEquals(PermissionAction.None, model.calendarTapped())
        assertEquals(CalendarImport.Done(2), model.state.value.calendarImport)
    }

    @Test
    fun importsWhenAccessWasGivenInTheSettings() {
        val model = model()
        model.calendarAnswered(granted = false)
        assertEquals(CalendarImport.Idle, model.state.value.calendarImport)

        permissions.calendar = true
        model.refresh()

        assertEquals(CalendarImport.Done(3), model.state.value.calendarImport)
    }

    @Test
    fun opensTheCalendarOnlyOnceBothAreAnswered() {
        val model = model()
        assertFalse(model.state.value.canOpenCalendar)

        model.calendarAnswered(granted = true)
        assertFalse(model.state.value.canOpenCalendar)
        model.notificationsAnswered(granted = false)

        assertTrue(model.state.value.canOpenCalendar)
    }

    @Test
    fun sendsARefusalToTheSettings() {
        val model = model()
        model.calendarAnswered(granted = false)
        model.notificationsAnswered(granted = false)

        assertEquals(PermissionAction.OpenAppSettings, model.calendarTapped())
        assertEquals(PermissionAction.OpenAppSettings, model.notificationsTapped())
    }

    @Test
    fun opensTheNotificationSettingsWhereTheSystemNeverAsks() {
        // Before Android 13 notifications are on unless turned off in the settings, and there is nothing to ask.
        permissions.notificationsNeedAsking = false
        val model = model()

        assertEquals(PermissionState.DENIED, model.state.value.notifications)
        assertEquals(PermissionAction.OpenNotificationSettings, model.notificationsTapped())
    }

    @Test
    fun showsWhatThePersonTurnedOnInTheSettings() {
        val model = model()
        model.calendarAnswered(granted = false)

        permissions.calendar = true
        permissions.notifications = true
        model.refresh()

        assertEquals(PermissionState.GRANTED, model.state.value.calendar)
        assertEquals(PermissionState.GRANTED, model.state.value.notifications)
        assertEquals(PermissionAction.None, model.calendarTapped())
    }

    @Test
    fun turnsTheStatisticsOnOnlyWhenAsked() {
        val model = model()
        assertFalse(model.state.value.alphaStats)
        assertFalse(core.statsOn)

        model.alphaStatsTapped()

        assertTrue(core.statsOn)
        assertTrue(model.state.value.alphaStats)
    }

    @Test
    fun hasNoStatisticsCardInAPublicBuild() {
        val model = model(BuildChannel.PUBLIC)

        model.alphaStatsTapped()

        assertFalse(model.state.value.showsAlphaStats)
        assertFalse(core.statsOn)
    }

    @Test
    fun saysWhenTheStatisticsCouldNotBeTurnedOn() {
        core.alphaStatsError = CoreException.NotImplemented("set_alpha_stats")
        val model = model()

        model.alphaStatsTapped()

        assertFalse(model.state.value.alphaStats)
        assertEquals(listOf(AccountNotice.SETTING_FAILED), notices)
    }
}
